package se.lth.math.videoimucapture;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssAntennaInfo;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import android.Manifest;
import com.google.protobuf.ByteString;

import java.util.List;
import java.util.Locale;

/**
 * GPS track logger built on LocationManager (GPS provider directly — no Play Services,
 * no fused black box). Registers while the activity is resumed so the receiver is warm
 * before recording starts; fixes are written only while a recording is active.
 *
 * Location.getElapsedRealtimeNanos() is on the same clock family as the IMU sensor
 * timestamps, which is what makes the GNSS stream joinable to everything else.
 * Altitude is WGS84 ellipsoid metres — NOT geoid/MSL (the separation is ~30 m in
 * New England); the consumer decides what to do about that, not this logger.
 */
public class GnssLogger implements LocationListener {
    private static final String TAG = "GnssLogger";
    private static final long UPDATE_INTERVAL_MS = 1000;

    /**
     * The operator's switch for the whole GNSS stream (settings -> Sensor streams).
     *
     * Default ON. OFF stops the receiver being asked for anything at all -- no fixes, no raw
     * measurements, no ephemeris -- which is the difference between a clip shot indoors that
     * merely never got a fix and a clip that was deliberately shot without position. Both
     * produce an empty GNSS column; only one of them is a fault, and until the switch existed
     * the file could not tell them apart.
     */
    public static final String PREF_GNSS_ENABLED = "gnss_enabled";

    /** Why the stream is not running, or that it is. Drives the readout and the warning dialog. */
    public enum State {
        /** Listeners attached and a recent fix is in hand. */
        FIX,
        /** Listeners attached, satellites may be visible, but no fix yet. */
        SEARCHING,
        /** Switched off by the operator in settings. */
        DISABLED,
        /** Location permission has not been granted. */
        NO_PERMISSION,
        /** Permission is there, but the device's own location switch is off. */
        PROVIDER_OFF,
        /** Not registered because the activity is not resumed. */
        IDLE
    }

    /** An immutable snapshot, so the UI thread never reads half-updated counters. */
    public static final class Status {
        public final State state;
        public final int satellitesVisible;
        public final int satellitesUsedInFix;
        /** Horizontal accuracy of the last fix in metres, NaN if unknown or no fix yet. */
        public final float accuracyM;
        /** Age of the last fix in seconds, -1 if there has not been one this session. */
        public final long fixAgeS;

        Status(State state, int visible, int used, float accuracyM, long fixAgeS) {
            this.state = state;
            this.satellitesVisible = visible;
            this.satellitesUsedInFix = used;
            this.accuracyM = accuracyM;
            this.fixAgeS = fixAgeS;
        }

        /** True when fixes are arriving, i.e. the clip is carrying a position track. */
        public boolean hasFix() {
            return state == State.FIX;
        }

        /** True when the stream could be producing data and simply is not yet. */
        public boolean isRunning() {
            return state == State.FIX || state == State.SEARCHING;
        }

        /**
         * One short field for the recording HUD. Deliberately the same shape as the IMU
         * readout: the operator is reading a dense row mid-walk and should not have to parse
         * a sentence.
         */
        public String readout() {
            switch (state) {
                case DISABLED:
                    return "GNSS: OFF";
                case NO_PERMISSION:
                    return "GNSS: NOPERM";
                case PROVIDER_OFF:
                    return "GNSS: LOC-OFF";
                case IDLE:
                    return "GNSS: IDLE";
                case SEARCHING:
                    return String.format(Locale.getDefault(), "GNSS: NOFIX %dsv",
                            satellitesVisible);
                case FIX:
                default:
                    String acc = Float.isNaN(accuracyM)
                            ? "" : String.format(Locale.getDefault(), " +-%.0fm", accuracyM);
                    return String.format(Locale.getDefault(), "GNSS: FIX %d/%dsv%s",
                            satellitesUsedInFix, satellitesVisible, acc);
            }
        }

        /** A sentence for the settings screen and the warning dialog. */
        public String describe() {
            switch (state) {
                case DISABLED:
                    return "Off. No position track will be recorded.";
                case NO_PERMISSION:
                    return "Location permission not granted, so no position track will be "
                            + "recorded. Tap to grant it.";
                case PROVIDER_OFF:
                    return "The device's location switch is off, so no position track will be "
                            + "recorded. Tap to open location settings.";
                case IDLE:
                    return "Idle. Starts when the capture screen is open.";
                case SEARCHING:
                    return satellitesVisible == 0
                            ? "Searching. No satellites visible yet -- indoors this can stay "
                            + "this way indefinitely."
                            : String.format(Locale.getDefault(),
                            "Searching. %d satellites visible, no fix yet.", satellitesVisible);
                case FIX:
                default:
                    String acc = Float.isNaN(accuracyM)
                            ? "" : String.format(Locale.getDefault(), ", +-%.0f m", accuracyM);
                    return String.format(Locale.getDefault(),
                            "Fix: %d of %d satellites used%s.",
                            satellitesUsedInFix, satellitesVisible, acc);
            }
        }
    }

    private final LocationManager mLocationManager;
    private volatile RecordingWriter mRecordingWriter = null;
    private boolean mRegistered = false;
    // Why registration did not happen, so the readout can say which of the reasons it was
    // rather than only that there is no GNSS.
    private volatile State mUnregisteredReason = State.IDLE;
    // Live counters, written on the main looper by the GNSS callbacks and read from the UI
    // thread for the readout. Published into an immutable Status on read.
    private volatile int mSatellitesVisible = 0;
    private volatile int mSatellitesUsedInFix = 0;
    private volatile float mLastAccuracyM = Float.NaN;
    private volatile long mLastFixElapsedMs = -1;
    // A fix older than this is not a fix any more: at walking pace the phone is fifteen
    // metres from where that position was taken, and a readout still claiming FIX is worse
    // than one admitting NOFIX.
    private static final long FIX_STALE_MS = 15000;
    // Raw GNSS (ReconStab #39/#31): the per-satellite measurements and constellation status,
    // registered alongside the fixes. Callbacks fire on the main looper; they write only while
    // a recording is active, like the fixes.
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private GnssMeasurementsEvent.Callback mMeasCallback;
    private GnssStatus.Callback mStatusCallback;
    private GnssNavigationMessage.Callback mNavCallback;

    public GnssLogger(Context context) {
        mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** The operator's switch, read live so a change in settings takes effect on next resume. */
    public static boolean isEnabledInSettings(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_GNSS_ENABLED, true);
    }

    /** Whether the device's own location switch is on. Separate from the app's permission. */
    public boolean isProviderEnabled() {
        return mLocationManager != null
                && mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    /** What the stream is doing right now. Cheap enough to call on every capture result. */
    public Status status() {
        if (!mRegistered) {
            return new Status(mUnregisteredReason, 0, 0, Float.NaN, -1);
        }
        long fixAgeMs = mLastFixElapsedMs < 0
                ? -1 : SystemClock.elapsedRealtime() - mLastFixElapsedMs;
        boolean fresh = fixAgeMs >= 0 && fixAgeMs < FIX_STALE_MS;
        return new Status(fresh ? State.FIX : State.SEARCHING,
                mSatellitesVisible, mSatellitesUsedInFix,
                fresh ? mLastAccuracyM : Float.NaN,
                fixAgeMs < 0 ? -1 : fixAgeMs / 1000);
    }

    public void register(Context context) {
        if (mRegistered || mLocationManager == null) {
            return;
        }
        if (!isEnabledInSettings(context)) {
            Log.i(TAG, "GNSS stream switched off in settings; receiver not asked for anything.");
            mUnregisteredReason = State.DISABLED;
            return;
        }
        if (!hasPermission(context)) {
            Log.i(TAG, "No location permission — GNSS stream disabled.");
            mUnregisteredReason = State.NO_PERMISSION;
            return;
        }
        if (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.i(TAG, "GPS provider disabled — GNSS stream disabled.");
            mUnregisteredReason = State.PROVIDER_OFF;
            return;
        }
        try {
            mLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, this, Looper.getMainLooper());
            registerRaw();
            mRegistered = true;
            mUnregisteredReason = State.IDLE;
            Log.d(TAG, "GNSS updates registered.");
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission revoked mid-flight: " + e);
            mUnregisteredReason = State.NO_PERMISSION;
        }
    }

    /** Raw measurements + constellation status. Best-effort: a device or ROM may support neither. */
    private void registerRaw() {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            mMeasCallback = new GnssMeasurementsEvent.Callback() {
                @Override
                public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
                    onRawMeasurements(event);
                }
            };
            mLocationManager.registerGnssMeasurementsCallback(mMeasCallback, mMainHandler);

            mStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus status) {
                    onSatelliteStatus(status);
                }
            };
            mLocationManager.registerGnssStatusCallback(mStatusCallback, mMainHandler);

            // Broadcast ephemeris. Pseudoranges say how far each satellite was; they do not
            // say where it WAS. Without the orbits a measurement file is a set of distances to
            // unknown points, and recording the subframes here means the clip carries its own
            // orbits rather than depending on an archive still serving that day years from now.
            mNavCallback = new GnssNavigationMessage.Callback() {
                @Override
                public void onGnssNavigationMessageReceived(GnssNavigationMessage message) {
                    onNavigationMessage(message);
                }
            };
            // The register call returns whether the device will supply them at all. Without
            // checking it, "no navigation messages in the file" has two very different causes
            // -- the device refused, or it agreed and the sky was too poor to decode a
            // subframe -- and the file cannot tell them apart.
            boolean nav = mLocationManager.registerGnssNavigationMessageCallback(
                    mNavCallback, mMainHandler);
            Log.i(TAG, nav
                    ? "GNSS navigation messages: device accepted the callback."
                    : "GNSS navigation messages: DEVICE REFUSED — no ephemeris will be recorded.");
        } catch (RuntimeException e) {
            // SecurityException is a RuntimeException; catch the wider type once.
            Log.w(TAG, "Raw GNSS callbacks unavailable: " + e);
        }
    }

    public void unregister() {
        if (mRegistered) {
            mLocationManager.removeUpdates(this);
            if (mMeasCallback != null) {
                mLocationManager.unregisterGnssMeasurementsCallback(mMeasCallback);
                mMeasCallback = null;
            }
            if (mStatusCallback != null) {
                mLocationManager.unregisterGnssStatusCallback(mStatusCallback);
                mStatusCallback = null;
            }
            if (mNavCallback != null) {
                mLocationManager.unregisterGnssNavigationMessageCallback(mNavCallback);
                mNavCallback = null;
            }
            mRegistered = false;
            mUnregisteredReason = State.IDLE;
            mSatellitesVisible = 0;
            mSatellitesUsedInFix = 0;
            mLastAccuracyM = Float.NaN;
            mLastFixElapsedMs = -1;
        }
    }

    private void onRawMeasurements(GnssMeasurementsEvent event) {
        RecordingWriter writer = mRecordingWriter;
        if (writer == null || !writer.isRecording()) {
            return;
        }
        GnssClock clock = event.getClock();
        RecordingProtos.GnssMeasurementData.Builder b =
                RecordingProtos.GnssMeasurementData.newBuilder()
                        .setTimeNs(clock.getTimeNanos());
        if (clock.hasFullBiasNanos()) {
            b.setFullBiasNs(clock.getFullBiasNanos());
        }
        if (clock.hasBiasNanos()) {
            b.setBiasNs(clock.getBiasNanos());
        }
        if (clock.hasDriftNanosPerSecond()) {
            b.setDriftNsps(clock.getDriftNanosPerSecond());
        }
        // The rest of the clock. hardware_clock_discontinuity_count is the one that makes the
        // difference between a measurement file and a post-processable one: the receiver clock
        // can jump, and a jump restarts the carrier-phase count. A solver that cannot see the
        // jump carries the break into the position.
        if (Build.VERSION.SDK_INT >= 29 && clock.hasElapsedRealtimeNanos()) {
            b.setElapsedRealtimeNs(clock.getElapsedRealtimeNanos());
            if (clock.hasElapsedRealtimeUncertaintyNanos()) {
                b.setElapsedRealtimeUncertaintyNs(clock.getElapsedRealtimeUncertaintyNanos());
            }
        } else {
            // Older devices do not put the sensor clock on the GnssClock. Reading it here is
            // late by the callback's own latency, but a joinable-with-a-caveat timestamp beats
            // an epoch that cannot be lined up with a video frame at all.
            b.setElapsedRealtimeNs(SystemClock.elapsedRealtimeNanos());
        }
        b.setHardwareClockDiscontinuityCount(clock.getHardwareClockDiscontinuityCount());
        if (clock.hasLeapSecond()) {
            b.setLeapSecond(clock.getLeapSecond());
        }
        if (clock.hasTimeUncertaintyNanos()) {
            b.setTimeUncertaintyNs(clock.getTimeUncertaintyNanos());
        }
        if (clock.hasBiasUncertaintyNanos()) {
            b.setBiasUncertaintyNs(clock.getBiasUncertaintyNanos());
        }
        if (clock.hasDriftUncertaintyNanosPerSecond()) {
            b.setDriftUncertaintyNsps(clock.getDriftUncertaintyNanosPerSecond());
        }
        for (GnssMeasurement m : event.getMeasurements()) {
            RecordingProtos.GnssMeasurementData.Measurement.Builder mb =
                    RecordingProtos.GnssMeasurementData.Measurement.newBuilder()
                            .setSvid(m.getSvid())
                            .setConstellation(m.getConstellationType())
                            .setCn0Dbhz(m.getCn0DbHz())
                            .setPseudorangeRateMps(m.getPseudorangeRateMetersPerSecond())
                            .setPseudorangeRateUncertaintyMps(
                                    m.getPseudorangeRateUncertaintyMetersPerSecond())
                            .setAccumulatedDeltaRangeM(m.getAccumulatedDeltaRangeMeters())
                            .setAccumulatedDeltaRangeState(m.getAccumulatedDeltaRangeState())
                            .setMultipathIndicator(m.getMultipathIndicator())
                            .setState(m.getState())
                            .setReceivedSvTimeNs(m.getReceivedSvTimeNanos());
            if (Build.VERSION.SDK_INT >= 26 && m.hasCarrierFrequencyHz()) {
                mb.setCarrierFrequencyHz(m.getCarrierFrequencyHz());
            }
            // Every measurement in an epoch is taken at a slightly different instant, and this
            // is that offset. RINEX observations are (clock time + this); without it every
            // satellite in the epoch is placed at the same moment, which is wrong by enough to
            // matter at the decimetre level these measurements exist to reach.
            mb.setTimeOffsetNs(m.getTimeOffsetNanos());
            mb.setReceivedSvTimeUncertaintyNs(m.getReceivedSvTimeUncertaintyNanos());
            mb.setAccumulatedDeltaRangeUncertaintyM(
                    m.getAccumulatedDeltaRangeUncertaintyMeters());
            if (Build.VERSION.SDK_INT >= 29 && m.hasCodeType()) {
                // RINEX 3 names an observation by band AND code; without this the writer has
                // to guess which signal was tracked.
                mb.setCodeType(m.getCodeType());
            }
            if (Build.VERSION.SDK_INT >= 30) {
                if (m.hasBasebandCn0DbHz()) {
                    mb.setBasebandCn0Dbhz(m.getBasebandCn0DbHz());
                }
                // Inter-signal biases: the hardware delay between this signal and the reference
                // one. Combine constellations, or L1 with L5, without removing these and a
                // metres-level offset remains that looks exactly like a position error.
                if (m.hasFullInterSignalBiasNanos()) {
                    mb.setFullInterSignalBiasNs(m.getFullInterSignalBiasNanos());
                }
                if (m.hasFullInterSignalBiasUncertaintyNanos()) {
                    mb.setFullInterSignalBiasUncertaintyNs(
                            m.getFullInterSignalBiasUncertaintyNanos());
                }
                if (m.hasSatelliteInterSignalBiasNanos()) {
                    mb.setSatelliteInterSignalBiasNs(m.getSatelliteInterSignalBiasNanos());
                }
                if (m.hasSatelliteInterSignalBiasUncertaintyNanos()) {
                    mb.setSatelliteInterSignalBiasUncertaintyNs(
                            m.getSatelliteInterSignalBiasUncertaintyNanos());
                }
            }
            b.addMeasurements(mb);
        }
        writer.queueData(b.build());
    }

    private void onNavigationMessage(GnssNavigationMessage message) {
        RecordingWriter writer = mRecordingWriter;
        if (writer == null || !writer.isRecording()) {
            return;
        }
        RecordingProtos.GnssNavigationMessageData.Builder b =
                RecordingProtos.GnssNavigationMessageData.newBuilder()
                        .setTimeNs(SystemClock.elapsedRealtimeNanos())
                        .setSvid(message.getSvid())
                        .setType(message.getType())
                        .setStatus(message.getStatus())
                        .setMessageId(message.getMessageId())
                        .setSubmessageId(message.getSubmessageId());
        byte[] data = message.getData();
        if (data != null) {
            b.setData(ByteString.copyFrom(data));
        }
        writer.queueData(b.build());
    }

    /**
     * Antenna geometry, written once at the start of a recording.
     *
     * Device-static, so it does not belong in the per-epoch stream — but it does belong in the
     * file. A position from these measurements is the position of the antenna PHASE CENTRE,
     * which is not where the phone is and is not the same place at L1 as at L5. At metres
     * nobody cares; at the decimetres this stream exists to reach, a few unmodelled centimetres
     * is a real part of the error budget, and a fixed knowable offset is the easiest kind of
     * error to stop making.
     */
    public void writeAntennaInfo() {
        RecordingWriter writer = mRecordingWriter;
        if (writer == null || mLocationManager == null || Build.VERSION.SDK_INT < 30) {
            return;
        }
        try {
            List<GnssAntennaInfo> infos = mLocationManager.getGnssAntennaInfos();
            if (infos == null || infos.isEmpty()) {
                Log.i(TAG, "Device publishes no GNSS antenna info.");
                return;
            }
            for (GnssAntennaInfo a : infos) {
                GnssAntennaInfo.PhaseCenterOffset o = a.getPhaseCenterOffset();
                writer.queueData(RecordingProtos.GnssAntennaInfoData.newBuilder()
                        .setCarrierFrequencyMhz(a.getCarrierFrequencyMHz())
                        .setPhaseCenterOffsetXMm(o.getXOffsetMm())
                        .setPhaseCenterOffsetYMm(o.getYOffsetMm())
                        .setPhaseCenterOffsetZMm(o.getZOffsetMm())
                        .setPhaseCenterOffsetXUncertaintyMm(o.getXOffsetUncertaintyMm())
                        .setPhaseCenterOffsetYUncertaintyMm(o.getYOffsetUncertaintyMm())
                        .setPhaseCenterOffsetZUncertaintyMm(o.getZOffsetUncertaintyMm())
                        .setHasPhaseCenterVariationCorrections(
                                a.getPhaseCenterVariationCorrections() != null)
                        .setHasSignalGainCorrections(a.getSignalGainCorrections() != null)
                        .build());
            }
            Log.i(TAG, "Wrote GNSS antenna info for " + infos.size() + " frequencies.");
        } catch (RuntimeException e) {
            Log.w(TAG, "GNSS antenna info unavailable: " + e);
        }
    }

    private void onSatelliteStatus(GnssStatus status) {
        int n = status.getSatelliteCount();
        int used = 0;
        // The counters are kept up to date whether or not a recording is running: the
        // operator needs to watch the receiver lock on BEFORE pressing record, which is the
        // whole reason this logger registers while merely resumed.
        for (int i = 0; i < n; i++) {
            if (status.usedInFix(i)) {
                used++;
            }
        }
        mSatellitesVisible = n;
        mSatellitesUsedInFix = used;

        RecordingWriter writer = mRecordingWriter;
        if (writer == null || !writer.isRecording()) {
            return;
        }
        used = 0;
        RecordingProtos.GnssStatusData.Builder b =
                RecordingProtos.GnssStatusData.newBuilder()
                        .setTimeNs(SystemClock.elapsedRealtimeNanos())
                        .setSatelliteCount(n);
        for (int i = 0; i < n; i++) {
            boolean inFix = status.usedInFix(i);
            if (inFix) {
                used++;
            }
            RecordingProtos.GnssStatusData.Satellite.Builder sb =
                    RecordingProtos.GnssStatusData.Satellite.newBuilder()
                            .setSvid(status.getSvid(i))
                            .setConstellation(status.getConstellationType(i))
                            .setCn0Dbhz(status.getCn0DbHz(i))
                            .setUsedInFix(inFix)
                            .setElevationDeg(status.getElevationDegrees(i))
                            .setAzimuthDeg(status.getAzimuthDegrees(i));
            if (Build.VERSION.SDK_INT >= 26 && status.hasCarrierFrequencyHz(i)) {
                sb.setCarrierFrequencyHz(status.getCarrierFrequencyHz(i));
            }
            b.addSatellites(sb);
        }
        b.setUsedInFixCount(used);
        writer.queueData(b.build());
    }

    public void startRecording(RecordingWriter recordingWriter) {
        mRecordingWriter = recordingWriter;
        // Device-static, so once per clip, at the top of the file.
        writeAntennaInfo();
    }

    public void stopRecording() {
        mRecordingWriter = null;
    }

    @Override
    public void onLocationChanged(Location loc) {
        // Freshness first, for the same reason as the satellite counters: the readout has to
        // be able to say FIX while the operator is still deciding whether to press record.
        mLastFixElapsedMs = SystemClock.elapsedRealtime();
        mLastAccuracyM = loc.hasAccuracy() ? loc.getAccuracy() : Float.NaN;

        RecordingWriter writer = mRecordingWriter;
        if (writer == null || !writer.isRecording()) {
            return;
        }
        RecordingProtos.GnssData.Builder builder = RecordingProtos.GnssData.newBuilder()
                .setElapsedRealtimeNs(loc.getElapsedRealtimeNanos())
                .setUtcTimeMs(loc.getTime())
                .setLatitudeDeg(loc.getLatitude())
                .setLongitudeDeg(loc.getLongitude())
                .setProvider(loc.getProvider() == null ? "" : loc.getProvider())
                .setHasAltitude(loc.hasAltitude())
                .setHasSpeed(loc.hasSpeed())
                .setHasBearing(loc.hasBearing());
        if (loc.hasAltitude()) {
            builder.setAltitudeWgs84M(loc.getAltitude());
        }
        if (loc.hasAccuracy()) {
            builder.setHorizontalAccuracyM(loc.getAccuracy());
        }
        if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) {
            builder.setVerticalAccuracyM(loc.getVerticalAccuracyMeters());
        }
        if (loc.hasSpeed()) {
            builder.setSpeedMps(loc.getSpeed());
        }
        if (loc.hasBearing()) {
            builder.setBearingDeg(loc.getBearing());
        }
        writer.queueData(builder.build());
    }

    // Required by the LocationListener interface on older API levels.
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        // The device's location switch went off underneath a registered listener. No further
        // fixes will arrive, so stop the readout claiming one it is no longer getting.
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            Log.i(TAG, "GPS provider turned off mid-session.");
            mLastFixElapsedMs = -1;
            mSatellitesUsedInFix = 0;
        }
    }
}
