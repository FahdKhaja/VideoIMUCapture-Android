package se.lth.math.videoimucapture;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;

/**
 * How much charge is left, how fast it is going, and stopping the capture before the phone dies.
 *
 * The third way a walk ends without producing a capture, after a full card and a hot phone, and
 * the one with no warning at all: the screen goes black mid-stride. What that costs is not the
 * last few seconds of footage -- it is the whole session, because a process killed by an empty
 * battery writes no mp4 trailer, no closing RAW, no frame accounting and no receipt. Every frame
 * on disk, and nothing able to open them.
 *
 * This app is unusually good at flattening a battery. The camera runs the whole time, the GPU
 * encodes at ~94 Mbit/s, the IMU wakes the CPU hundreds of times a second (#63), GNSS is live,
 * and the screen is on because the operator is watching the readout. #37 exists because the
 * phone gets hot; it gets hot because it is drawing a great deal of power.
 *
 * Same shape as {@link StorageGuard} deliberately -- measure the drain rather than model it,
 * hold a reserve back so that stopping is always possible, and say the answer in minutes rather
 * than in a percentage the operator has to interpret. Battery differs from storage in two ways
 * that the code has to respect: the reading is coarse, arriving in whole percent, so a rate
 * needs a real drop before it means anything; and it can go UP, because the operator may be
 * carrying a power bank, in which case none of this applies.
 */
public final class BatteryGuard {

    private static final String TAG = "BatteryGuard";

    /** Stop the capture here. Enough charge left to close the session and to get home. */
    static final int CRITICAL_PERCENT = 5;

    /**
     * Below this a session is refused. Deliberately close to critical: unlike a card, which
     * needs gigabytes before a walk is worth starting, a short OBJECT composite at 10% is a
     * perfectly reasonable thing to want, and refusing it would be the app overruling an
     * operator who can see their own battery.
     */
    static final int FLOOR_PERCENT = 8;

    /** Show the level on the readout from here down, whether or not a rate is known yet. */
    static final int LOW_PERCENT = 20;

    /** A rate means nothing until the level has actually moved this far. */
    private static final int MIN_DROP_PERCENT = 2;

    private static final long TICK_MS = 10_000;   // coarse signal; polling faster buys nothing

    /** What the guard knows right now. */
    public static final class Status {
        public final int percent;          // -1 when unreadable
        public final boolean charging;
        public final double percentPerHour; // measured; 0 until it can tell
        public final long secondsLeft;      // to CRITICAL_PERCENT; -1 when unknown
        public final boolean low;
        public final boolean critical;

        Status(int percent, boolean charging, double percentPerHour, long secondsLeft) {
            this.percent = percent;
            this.charging = charging;
            this.percentPerHour = percentPerHour;
            this.secondsLeft = secondsLeft;
            // A phone on a power bank is not about to die, so it is never critical for this
            // purpose -- but an unreadable level is not an excuse to stop a capture either.
            this.critical = percent >= 0 && percent <= CRITICAL_PERCENT && !charging;
            this.low = !critical && percent >= 0 && percent <= LOW_PERCENT;
        }
    }

    public interface Listener {
        void onBatteryCritical(Status status);
    }

    private final Context mAppContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private Listener mListener;

    private boolean mActive = false;
    private int mPercentAtStart = -1;
    private long mStartedMs = 0;
    private Status mLast;
    private boolean mFired = false;

    public BatteryGuard(Context context) {
        mAppContext = context.getApplicationContext();
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    /**
     * Charge remaining, 0-100, or -1 if the platform will not say.
     *
     * The same sticky broadcast the thermal logger reads for temperature: no receiver is
     * registered and nothing is subscribed, so asking is cheap enough to do on a timer.
     */
    public static int percent(Context context) {
        Intent i = batteryIntent(context);
        if (i == null) {
            return -1;
        }
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) {
            return -1;
        }
        return Math.round(level * 100f / scale);
    }

    /** True when the phone is plugged into something that is actually charging it. */
    public static boolean isCharging(Context context) {
        Intent i = batteryIntent(context);
        if (i == null) {
            return false;
        }
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private static Intent batteryIntent(Context context) {
        try {
            return context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the battery: " + e);
            return null;
        }
    }

    /**
     * Whether a session should be allowed to start.
     *
     * An unreadable battery is NOT a refusal. The operator is standing somewhere with a
     * subject in front of them, and a platform that will not answer a question is a poor
     * reason to refuse the capture they came for.
     */
    public static boolean enoughToStart(Context context) {
        int p = percent(context);
        return p < 0 || p >= FLOOR_PERCENT || isCharging(context);
    }

    public void begin() {
        if (mActive) {
            return;
        }
        mActive = true;
        mFired = false;
        mPercentAtStart = percent(mAppContext);
        mStartedMs = SystemClock.elapsedRealtime();
        mLast = new Status(mPercentAtStart, isCharging(mAppContext), 0, -1);
        Log.i(TAG, "watching: battery " + mPercentAtStart + "%"
                + (mLast.charging ? " (charging)" : ""));
        mMain.postDelayed(mTick, TICK_MS);
    }

    public void end() {
        if (!mActive) {
            return;
        }
        mActive = false;
        mMain.removeCallbacks(mTick);
        int now = percent(mAppContext);
        Log.i(TAG, "stopped watching: battery " + now + "%, "
                + Math.max(0, mPercentAtStart - now) + " points used");
    }

    public boolean isWatching() {
        return mActive;
    }

    public Status status() {
        return mLast;
    }

    /** The level right now, for the receipt. */
    public int percentNow() {
        return percent(mAppContext);
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mActive) {
                return;
            }
            mLast = measure();
            if (mLast.critical && !mFired) {
                mFired = true;
                Log.e(TAG, "critical: battery " + mLast.percent + "% -- ending the capture");
                if (mListener != null) {
                    mListener.onBatteryCritical(mLast);
                }
            }
            mMain.postDelayed(this, TICK_MS);
        }
    };

    private Status measure() {
        int now = percent(mAppContext);
        boolean charging = isCharging(mAppContext);
        long elapsedMs = SystemClock.elapsedRealtime() - mStartedMs;
        int dropped = mPercentAtStart - now;
        // Charging, or not enough movement to mean anything. Whole-percent readings make a
        // rate computed off one step wildly wrong -- a single point in thirty seconds reads
        // as 120%/hour -- so nothing is projected until the level has really moved.
        if (charging || now < 0 || dropped < MIN_DROP_PERCENT || elapsedMs < 60_000) {
            return new Status(now, charging, 0, -1);
        }
        double hours = elapsedMs / 3_600_000.0;
        double perHour = dropped / hours;
        int usable = Math.max(0, now - CRITICAL_PERCENT);
        long secondsLeft = perHour > 0 ? (long) (usable / perHour * 3600.0) : -1;
        return new Status(now, false, perHour, secondsLeft);
    }

    /** For the readout: "BATT 14%" or "BATT 14% 22min", short enough to sit in one line. */
    public static String readout(Status s) {
        if (s == null || s.percent < 0) {
            return "";
        }
        if (s.charging) {
            return String.format(Locale.US, "BATT %d%% chg|", s.percent);
        }
        if (s.secondsLeft >= 0 && s.percent <= LOW_PERCENT) {
            return String.format(Locale.US, "BATT %d%% %dmin|", s.percent, s.secondsLeft / 60);
        }
        return String.format(Locale.US, "BATT %d%%|", s.percent);
    }
}
