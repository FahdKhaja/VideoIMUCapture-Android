package se.lth.math.videoimucapture;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * How much room is left, how fast it is going, and stopping the capture before it runs out.
 *
 * NOTHING IN THIS APP HAS EVER LOOKED AT FREE SPACE. Not before a run, not during one. A WALK
 * is hundreds of full-resolution JPEGs and a 94 Mbit/s video stream -- by the capture manager's
 * own arithmetic a ten-minute walk is 8.4 GB -- and the operator finds out how that went at a
 * desk, afterwards.
 *
 * What made it worth building is what the failure actually looks like, which is not a message.
 * A JPEG that cannot be written logs and vanishes. A metadata write that fails takes the writer
 * thread down while leaving isRecording() true, so the sensor queue fills and the thread
 * feeding it blocks forever. The clip does not end: it thins out, and then it hangs.
 *
 * So the rule here is that the capture STOPS ITSELF while stopping is still possible. A clean
 * stop has real work to do -- the muxer's trailer, the closing RAW, the frame accounting, the
 * manifest -- and none of it can be done on a full card. {@link #RESERVE_BYTES} is the room
 * that work needs, held back from the operator so it is there when it is needed.
 *
 * The rate is MEASURED, not modelled. Free space at the start against free space now, over the
 * seconds between them, which needs no assumption about codec, bitrate, JPEG size or how often
 * the operator stands still -- and which counts everything else on the phone that is writing
 * at the same time, because that space is just as gone.
 */
public final class StorageGuard {

    private static final String TAG = "StorageGuard";

    /**
     * Held back so that ending a session is always possible. The mp4 trailer alone can be
     * megabytes on a long clip, and the closing RAW is ~25 MB on this device.
     */
    static final long RESERVE_BYTES = 256L << 20;          // 256 MB

    /**
     * Below this, a session is not worth starting. At the automatic bitrate for the full
     * sensor this is roughly a minute and a half of video, and a walk that dies after ninety
     * seconds has cost the outing without producing a capture anyone can solve.
     */
    static final long FLOOR_BYTES = 1L << 30;              // 1 GB

    /** Warn the operator below this much projected recording time. */
    static final long LOW_SECONDS = 120;

    private static final long TICK_MS = 2000;

    /** What the guard knows right now. */
    public static final class Status {
        public final long freeBytes;
        public final long usableBytes;      // free space minus the reserve: what is actually ours
        public final double bytesPerSecond; // measured over this session, 0 until it can tell
        public final long secondsLeft;      // -1 when there is nothing to base it on yet
        public final boolean low;
        public final boolean critical;

        Status(long freeBytes, double bytesPerSecond, long secondsLeft) {
            this.freeBytes = freeBytes;
            this.usableBytes = Math.max(0, freeBytes - RESERVE_BYTES);
            this.bytesPerSecond = bytesPerSecond;
            this.secondsLeft = secondsLeft;
            this.critical = freeBytes <= RESERVE_BYTES;
            this.low = !critical && secondsLeft >= 0 && secondsLeft < LOW_SECONDS;
        }
    }

    /** Told when the capture must end, on the main thread. */
    public interface Listener {
        void onStorageCritical(Status status);
    }

    private final File mRoot;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private Listener mListener;

    private boolean mActive = false;
    private long mFreeAtStart = 0;
    private long mStartedMs = 0;
    private Status mLast;
    private boolean mFired = false;

    public StorageGuard(File root) {
        mRoot = root;
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    /**
     * Free bytes on the volume the captures land on. 0 if it cannot be read.
     *
     * getUsableSpace, and not StorageManager#getAllocatableBytes, which lint suggests and
     * which would report a larger number by counting cached data the system COULD evict.
     * Larger is the wrong direction here. This number decides whether a walk starts and when
     * one stops itself, and space that exists only if the platform agrees to clear someone
     * else's cache is not space to promise an operator standing in a field. The conservative
     * reading is the honest one, and being wrong in this direction only ever costs a session
     * that could have run slightly longer.
     */
    @SuppressLint("UsableSpace")
    public static long freeBytes(File root) {
        if (root == null) {
            return 0;
        }
        try {
            return root.getUsableSpace();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read free space: " + e);
            return 0;
        }
    }

    /** True when there is enough room to be worth starting a session at all. */
    public static boolean enoughToStart(File root) {
        return freeBytes(root) >= FLOOR_BYTES;
    }

    /** Human-readable, for a message the operator reads once and acts on. */
    public static String describe(long bytes) {
        if (bytes >= (1L << 30)) {
            return String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        }
        return String.format(Locale.US, "%d MB", bytes >> 20);
    }

    /**
     * An estimate for the pre-flight message only, where nothing has been measured yet.
     * Deliberately the video-only number: it is the floor of what a session will consume, and
     * the stills a WALK fires on top of it are not predictable from settings.
     */
    public static String estimateAtBitrate(long freeBytes, int bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            return "";
        }
        long seconds = (long) ((freeBytes - RESERVE_BYTES) / (bitsPerSecond / 8.0));
        if (seconds < 0) {
            seconds = 0;
        }
        return String.format(Locale.US, "about %d min of video", Math.max(1, seconds / 60));
    }

    /** Begin watching. Safe to call twice; the second call is ignored. */
    public void begin() {
        if (mActive) {
            return;
        }
        mActive = true;
        mFired = false;
        mFreeAtStart = freeBytes(mRoot);
        mStartedMs = SystemClock.elapsedRealtime();
        mLast = new Status(mFreeAtStart, 0, -1);
        Log.i(TAG, "watching: " + describe(mFreeAtStart) + " free at start");
        mMain.postDelayed(mTick, TICK_MS);
    }

    public void end() {
        if (!mActive) {
            return;
        }
        mActive = false;
        mMain.removeCallbacks(mTick);
        Log.i(TAG, "stopped watching: " + describe(freeBytes(mRoot)) + " free, "
                + describe(Math.max(0, mFreeAtStart - freeBytes(mRoot))) + " written");
    }

    public boolean isWatching() {
        return mActive;
    }

    /** The most recent reading, or null before the first session. */
    public Status status() {
        return mLast;
    }

    /** Free space now, without waiting for the next tick. */
    public long freeNow() {
        return freeBytes(mRoot);
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mActive) {
                return;
            }
            mLast = measure();
            if (mLast.critical && !mFired) {
                // Once. The stop takes seconds to complete and the tick would otherwise fire
                // again in the middle of it, stopping a session that is already stopping.
                mFired = true;
                Log.e(TAG, "critical: " + describe(mLast.freeBytes)
                        + " free, at or below the reserve -- ending the capture");
                if (mListener != null) {
                    mListener.onStorageCritical(mLast);
                }
            }
            mMain.postDelayed(this, TICK_MS);
        }
    };

    private Status measure() {
        long free = freeBytes(mRoot);
        long elapsedMs = SystemClock.elapsedRealtime() - mStartedMs;
        long written = mFreeAtStart - free;
        // Under ten seconds, or if something freed space underneath us, there is nothing
        // honest to project from. Say so with -1 rather than inventing a number.
        if (elapsedMs < 10_000 || written <= 0) {
            return new Status(free, 0, -1);
        }
        double rate = written / (elapsedMs / 1000.0);
        long usable = Math.max(0, free - RESERVE_BYTES);
        long secondsLeft = rate > 0 ? (long) (usable / rate) : -1;
        return new Status(free, rate, secondsLeft);
    }
}
