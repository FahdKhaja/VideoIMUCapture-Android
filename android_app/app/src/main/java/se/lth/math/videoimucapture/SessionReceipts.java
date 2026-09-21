package se.lth.math.videoimucapture;

import java.io.File;

/**
 * The parts of a session's receipt that every kind of session fills in the same way.
 *
 * Three paths open a session -- the record button, the capture button, the OBJECT composite --
 * and each used to build its own SessionManifest and note the same opening facts by hand. The
 * composite's copy was written later and missed some: its 2026-09-20 M5 receipt reported
 * thermal status -1 and carried no battery reading at either end. That is what three copies
 * are for.
 *
 * WHEN a receipt is written stays with whoever owns the session: the delays differ, and the
 * continuous paths wait on the encoder's verdict where the composite has no video to wait for.
 */
final class SessionReceipts {

    private SessionReceipts() {
    }

    /** A new receipt, with what the phone had to give when the session opened. */
    static SessionManifest open(CameraCaptureActivity activity, File dir, String mode,
                                String testTag) {
        SessionManifest manifest = new SessionManifest(activity, dir, mode, testTag);
        manifest.noteFreeAtStart(StorageGuard.freeBytes(new File(activity.getResultRoot())));
        manifest.noteBatteryAtStart(BatteryGuard.percent(activity));
        noteSensorStreams(manifest, activity);
        return manifest;
    }

    /**
     * Which sensor streams this session opened with. Written at open rather than at seal
     * because the question is what the clip was SHOT with; a permission granted halfway
     * through does not retrofit a position track onto the first half.
     */
    static void noteSensorStreams(SessionManifest manifest, CameraCaptureActivity activity) {
        IMUManager imu = activity.getmImuManager();
        GnssLogger gnss = activity.getmGnssLogger();
        manifest.noteSensorStreams(
                imu != null && imu.isEnabledInSettings(),
                imu != null && imu.isStreamActive(),
                GnssLogger.isEnabledInSettings(activity),
                gnss == null ? "unavailable" : gnss.status().state.name());
    }

    /** What the session cost: the worst thermal status it reached and the charge it ended on. */
    static void noteCost(SessionManifest manifest, CameraCaptureActivity activity) {
        if (activity.getmThermalLogger() != null) {
            manifest.noteWorstThermalStatus(activity.getmThermalLogger().worstStatus());
        }
        if (activity.getBatteryGuard() != null) {
            manifest.noteBatteryAtEnd(activity.getBatteryGuard().percentNow());
        }
    }

    /**
     * Record which lenses the session was built with.
     *
     * Not which it used: a session built with four physical streams that produced two files
     * looks, in a receipt that only counts files, exactly like a normal metric-pair run.
     */
    static void noteLensSet(SessionManifest manifest, Camera2Proxy proxy) {
        int configured = LensRoles.activeLensIds().size();
        // How many lenses this session's stereo was ASKED to deliver, which is not always how
        // many were configured: the periodic path targets the metric pair by design whatever
        // the session was built with (its request also drives the video), so a periodic
        // session on the all-lens set delivering two lenses is the intended outcome. W1/W2
        // on 2026-09-20 read "4 lenses configured, 2 delivered" and disagreed with two
        // perfectly good clips.
        boolean periodic = proxy != null && proxy.periodicStereoPairs() > 0
                && proxy.oneShotStereoBursts() == 0;
        int expected = periodic ? Math.min(2, configured) : configured;
        manifest.noteLensSet(LensRoles.allLensShot() ? "all" : "pair", configured,
                expected);
    }
}
