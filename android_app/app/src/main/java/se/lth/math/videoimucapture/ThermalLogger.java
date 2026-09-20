package se.lth.math.videoimucapture;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Heat, written next to pressure.
 *
 * The phone cooks after a minute or two of recording, and the barometer drifts about 79 hPa
 * an hour while it does. Until this class the file said nothing about temperature, so every
 * throttling event, dropped frame or exposure change under heat was an inference. Three
 * numbers every few seconds, on the same clock as the sensors:
 *
 *   battery temperature   the battery thermistor, from the sticky ACTION_BATTERY_CHANGED
 *                         broadcast, tenths of a degree converted to degrees C
 *   thermal status        PowerManager.THERMAL_STATUS_* (API 29+): 0 none, 1 light, 2 moderate,
 *                         3 severe, 4 critical, 5 emergency, 6 shutdown; -1 if unavailable
 *   thermal headroom      PowerManager.getThermalHeadroom (API 30+): the forecast fraction of
 *                         the throttling threshold, 1.0 meaning throttling now; NaN if unavailable
 *
 * None of these is a measurement of the camera module itself, which is the part that heats
 * first; they are what the OS exposes, and the status is the one it acts on. Sampled on the
 * main looper because the battery broadcast and the power service are cheap to ask and the
 * sensor thread has enough to do.
 */
public class ThermalLogger {
    private static final String TAG = "VIMUC-Thermal";
    public static final long PERIOD_MS = 5000;

    private final Context mAppContext;
    private final PowerManager mPower;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private RecordingWriter mWriter = null;

    // PowerManager.THERMAL_STATUS_*, named because the numbers on their own say nothing about
    // which of them is worth acting on.
    public static final int STATUS_MODERATE = 2;   // the sensor's output starts to change
    public static final int STATUS_SEVERE = 3;     // visible degradation; warn, do not stop
    public static final int STATUS_CRITICAL = 4;   // the platform may take the camera

    private volatile float mLastBatteryC = Float.NaN;
    private volatile int mLastStatus = -1;
    private volatile float mLastHeadroom = Float.NaN;
    private volatile int mWorstStatus = -1;
    private boolean mCriticalFired = false;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            sample();
            if (mWriter != null) {
                mHandler.postDelayed(this, PERIOD_MS);
            }
        }
    };

    public ThermalLogger(Context context) {
        mAppContext = context.getApplicationContext();
        mPower = (PowerManager) mAppContext.getSystemService(Context.POWER_SERVICE);
    }

    public void startRecording(RecordingWriter writer) {
        mWriter = writer;
        // Each session reports its OWN worst heat. Carrying the last one's over would make
        // every clip after a hot one look throttled, which is the kind of stale flag that
        // teaches an operator to ignore the flag.
        resetWorstStatus();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    public void stopRecording() {
        mWriter = null;
        mHandler.removeCallbacks(mTick);
    }

    /** Battery thermistor in degrees C, or NaN. A sticky broadcast: no receiver is registered. */
    public float batteryTempC() {
        Intent i = mAppContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) {
            return Float.NaN;
        }
        int tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        return tenths == Integer.MIN_VALUE ? Float.NaN : tenths / 10f;
    }

    public int thermalStatus() {
        if (Build.VERSION.SDK_INT >= 29 && mPower != null) {
            return mPower.getCurrentThermalStatus();
        }
        return -1;
    }

    public float thermalHeadroom() {
        if (Build.VERSION.SDK_INT >= 30 && mPower != null) {
            try {
                return mPower.getThermalHeadroom(10);
            } catch (RuntimeException e) {
                return Float.NaN;
            }
        }
        return Float.NaN;
    }

    private void sample() {
        mLastBatteryC = batteryTempC();
        mLastStatus = thermalStatus();
        mLastHeadroom = thermalHeadroom();
        RecordingWriter w = mWriter;
        if (w == null) {
            return;
        }
        RecordingProtos.ThermalData.Builder b = RecordingProtos.ThermalData.newBuilder()
                .setTimeNs(SystemClock.elapsedRealtimeNanos())
                .setThermalStatus(mLastStatus);
        if (!Float.isNaN(mLastBatteryC)) {
            b.setBatteryTempC(mLastBatteryC);
        }
        if (!Float.isNaN(mLastHeadroom)) {
            b.setThermalHeadroom(mLastHeadroom);
        }
        w.queueData(b.build());
        if (mLastStatus > mWorstStatus) {
            mWorstStatus = mLastStatus;
        }
        if (mLastStatus >= STATUS_SEVERE) {
            Log.w(TAG, "thermal status " + mLastStatus + " at " + mLastBatteryC + " C");
        }
        // THE ONE PLACE THIS CLASS ACTS RATHER THAN WATCHES. Everything above is recording,
        // and recording is what this class was built for -- but at CRITICAL the platform is
        // already throttling hard and is entitled to take the camera away, and a session that
        // is taken from is a session that never got its trailer, its closing RAW or its
        // receipt. Ending it here is the same argument as the storage reserve: stop while
        // stopping still works.
        //
        // Not at SEVERE. Severe is degradation -- lower clocks, a hotter sensor, frames the
        // solve may or may not like -- and ending a walk over degradation the operator has
        // not seen would cost more captures than it saved. Severe is a warning on the screen.
        if (mLastStatus >= STATUS_CRITICAL && !mCriticalFired) {
            mCriticalFired = true;
            Log.e(TAG, "thermal status " + mLastStatus + " (critical): ending the capture");
            Listener l = mListener;
            if (l != null) {
                mHandler.post(() -> l.onThermalCritical(mLastStatus, mLastBatteryC));
            }
        }
    }

    /** Told, on the main thread, when the phone is too hot to keep capturing safely. */
    public interface Listener {
        void onThermalCritical(int status, float batteryC);
    }

    private volatile Listener mListener;

    public void setListener(Listener l) {
        mListener = l;
    }

    /**
     * The worst thermal status seen since the last reset, so a session that degraded can say
     * so in its own receipt instead of leaving it to be inferred from the stream afterwards.
     */
    public int worstStatus() {
        return mWorstStatus;
    }

    public void resetWorstStatus() {
        mWorstStatus = -1;
        mCriticalFired = false;
    }

    /** True while the phone is throttling enough to change what the sensor delivers. */
    public boolean isThrottling() {
        return mLastStatus >= STATUS_MODERATE;
    }

    /** The most recent battery temperature, or NaN before the first sample. */
    public float lastBatteryTempC() {
        if (Float.isNaN(mLastBatteryC)) {
            mLastBatteryC = batteryTempC();     // cheap: a sticky broadcast, no receiver
        }
        return mLastBatteryC;
    }

    /** One line for the screen: what the phone says about its own heat right now. */
    public String summary() {
        StringBuilder s = new StringBuilder();
        if (!Float.isNaN(mLastBatteryC)) {
            s.append(String.format(java.util.Locale.US, "%.1f C", mLastBatteryC));
        }
        if (mLastStatus >= 0) {
            s.append(s.length() > 0 ? "  " : "").append("thermal ").append(mLastStatus);
        }
        if (!Float.isNaN(mLastHeadroom)) {
            s.append(s.length() > 0 ? "  " : "")
                    .append(String.format(java.util.Locale.US, "headroom %.2f", mLastHeadroom));
        }
        return s.toString();
    }
}
