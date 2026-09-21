package se.lth.math.videoimucapture;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The REQUEST side of the physical lenses: which physical streams the repeating request
 * carries, and for how long. {@link StereoCapture} is the other half -- it drains what arrives
 * and keeps the frames that were asked for -- and issues no request of its own.
 *
 * Three shapes, all the same move: put physical streams into the repeating request, let
 * StereoCapture keep what it wants from them, take them out again.
 *
 *   one pair       warm uw+main for 900 ms, keep one pair from the stream, restore the preview
 *   pair sequence  the same per pair, for every pair the session was bound with
 *   periodic       uw+main in the recording's own request for the whole clip
 */
final class StereoRequests {
    private static final String TAG = "StereoRequests";

    private final RepeatingRequestHost mHost;

    StereoRequests(RepeatingRequestHost host) {
        mHost = host;
    }

    private StereoCapture stereo() {
        StillCaptureManager stills = mHost.stills();
        return stills == null ? null : stills.stereo();
    }

    boolean supported() {
        StereoCapture s = stereo();
        return s != null && s.stereoSupported();
    }

    /** The session is going away: no request to restore, just the bookkeeping. */
    void sessionGone() {
        mPeriodicStereo = false;
        mStereoSequenceActive = false;
        StereoCapture s = stereo();
        if (s != null) {
            s.stopPeriodicStereo();
        }
    }

    // ------------------------------------------------------------------ the warm-up request

    /** What a warm-up carries across from the preview, so a pair is exposed like the rest. */
    private static final CaptureRequest.Key<?>[] WARM_KEYS = {
            CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AE_LOCK, CaptureRequest.CONTROL_AWB_LOCK,
            CaptureRequest.SENSOR_EXPOSURE_TIME, CaptureRequest.SENSOR_SENSITIVITY,
            CaptureRequest.FLASH_MODE, CaptureRequest.LENS_FOCUS_DISTANCE};

    /**
     * A TEMPLATE_PREVIEW request with the preview's settings, the preview surface and the given
     * lens surfaces, at the WIDEST zoom.
     *
     * Widest zoom IN THE WARM-UP, so the HAL has already switched master lens by the time the
     * pair is kept. This is what makes the ultrawide half a wide-angle frame rather than a crop
     * of the main camera's view (see StereoCapture.applyFullFieldOfView).
     */
    private CaptureRequest buildWarmRequest(StereoCapture stereo, Collection<Surface> lenses)
            throws CameraAccessException {
        CaptureRequest.Builder warm =
                mHost.device().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        CaptureRequest.Builder preview = mHost.previewBuilder();
        for (CaptureRequest.Key key : WARM_KEYS) {
            Object v = preview.get(key);
            if (v != null) {
                warm.set(key, v);
            }
        }
        stereo.applyFullFieldOfView(warm);
        warm.addTarget(mHost.previewSurface());
        for (Surface s : lenses) {
            warm.addTarget(s);
        }
        return warm.build();
    }

    private boolean sessionReady() {
        return mHost.device() != null && mHost.session() != null
                && mHost.previewBuilder() != null && stereo() != null;
    }

    /** Arm on the camera thread after the warm-up has run; a no-op if the session has gone. */
    private void armAfter(long delayMs, final String[] pair, final File outputDir,
                          final RecordingWriter writer,
                          final StillCaptureManager.CaptureMode mode) {
        mHost.handler().postDelayed(() -> {
            StereoCapture s = stereo();
            if (s != null) {
                s.armPairFromStream(pair, outputDir, writer, mode);
            }
        }, delayMs);
    }

    // ------------------------------------------------------------------ one pair

    /**
     * One simultaneous frame from each of the ultrawide and main lenses.
     *
     * WARM-UP IS REQUIRED, and finding that out cost a capture. A logical multi-camera
     * does not keep every physical sensor running — only the ones feeding current
     * output. Firing a one-shot request at an idle physical stream returns
     * ERROR_CAMERA_BUFFER (errorCode 5) for it: measured here as errorStreamId=3, and
     * the pair came back with the main frame present and the ultrawide missing.
     *
     * So the physical streams are added to the REPEATING request first, which starts
     * the second sensor and lets its exposure settle, and only then is the pair
     * kept. The normal preview request is restored afterwards so two sensors are
     * not left running — that is real power and heat for a capability used once per
     * composite.
     */
    void capturePair(File outputDir, RecordingWriter writer,
                     StillCaptureManager.CaptureMode mode) {
        if (!supported() || !sessionReady()) {
            return;
        }
        if (mPeriodicStereo) {
            // The physical streams are already in the repeating request and pairs are being
            // kept on the interval; a warm-up here would swap the recording's request for a
            // TEMPLATE_PREVIEW copy of it. The next periodic pair is at most one interval away.
            Log.i(TAG, "stereo pair requested while periodic pairs run; leaving it to the interval");
            return;
        }
        final StereoCapture stereo = stereo();
        // More than the metric pair configured: pairs in sequence, never all at once. A
        // warm-up that targets four physical streams is what killed the device on
        // 2026-09-20 -- the HAL will run two sensors per request on this phone -- and the
        // streaming probe showed every pair streams from a session bound with all four.
        if (stereo.getStereoSurfaces().size() > 2) {
            captureLensPairSequence(outputDir, writer, mode);
            return;
        }
        try {
            mHost.setRepeating(buildWarmRequest(stereo, stereo.getStereoSurfaces().values()));
            Log.d(TAG, "stereo warm-up streaming");

            // Kept FROM the warm-up stream, not by a second request: see armPairFromStream.
            armAfter(900L, new String[]{LensRoles.physUltrawide(), LensRoles.physMain()},
                    outputDir, writer, mode);
            mHost.handler().postDelayed(
                    () -> mHost.restorePreview("stereo warm-up ended"), 2200L);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "stereo warm-up failed: " + e);
        }
    }

    // ------------------------------------------------------------ pairs, in sequence

    private volatile boolean mStereoSequenceActive = false;

    /** Whether a pair sequence is between its first warm-up and its final preview restore. */
    boolean sequenceActive() {
        return mStereoSequenceActive;
    }

    /** Warm-up per pair: the probe's first frame from a cold pair came at ~500 ms. */
    private static final long PAIR_WARM_MS = 700L;
    /** After the pair is armed, before the next pair's warm-up replaces the stream. */
    private static final long PAIR_SETTLE_MS = 450L;

    /**
     * Every configured pair, one after another, each from its own warm repeating request.
     *
     * The single-pair path above warms both physical streams for 900 ms and keeps once.
     * This does the same thing per pair -- warm request carrying the preview plus exactly
     * two physical surfaces, then a pair kept, then the next -- because two is what one
     * request may run on this phone. Six pairs take about seven seconds; a static target
     * does not mind, and every pair is simultaneous within itself, which is all a disparity
     * needs. The ordinary preview is restored once, at the end.
     *
     * The stillness trigger is held off for the duration (see CaptureModeManager.captureNow):
     * a JPEG burst replaces the repeating request, which would end the pair's warm-up under
     * it and return ERROR_CAMERA_BUFFER for the cold lens.
     */
    private void captureLensPairSequence(File outputDir, RecordingWriter writer,
                                         StillCaptureManager.CaptureMode mode) {
        final List<String[]> pairs = stereo().configuredLensPairs();
        if (pairs.isEmpty()) {
            Log.w(TAG, "no lens pairs to capture");
            return;
        }
        mStereoSequenceActive = true;
        Log.i(TAG, "lens pair sequence: " + pairs.size() + " pairs");
        runPair(pairs, 0, outputDir, writer, mode);
    }

    private void runPair(final List<String[]> pairs, final int i,
                         final File outputDir, final RecordingWriter writer,
                         final StillCaptureManager.CaptureMode mode) {
        if (!sessionReady()) {
            Log.w(TAG, "pair sequence abandoned at " + i + ": session gone");
            mStereoSequenceActive = false;
            return;
        }
        final String[] pair = pairs.get(i);
        try {
            StereoCapture stereo = stereo();
            List<Surface> lenses = new ArrayList<>();
            for (String pid : pair) {
                Surface s = stereo.lensSurface(pid);
                if (s != null) {
                    lenses.add(s);
                }
            }
            mHost.setRepeating(buildWarmRequest(stereo, lenses));
            Log.d(TAG, "pair " + (i + 1) + "/" + pairs.size() + " warming: "
                    + pair[0] + "+" + pair[1]);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "pair warm-up failed: " + e);
            mStereoSequenceActive = false;
            mHost.restorePreview("pair sequence failed");
            return;
        }

        // Kept FROM this warm-up stream, by timestamp, not by a second request. The stream is
        // at 0.6 and carries both lenses' outputs of every frame; the pair is one of them.
        armAfter(PAIR_WARM_MS, pair, outputDir, writer, mode);
        mHost.handler().postDelayed(() -> {
            if (i + 1 < pairs.size()) {
                runPair(pairs, i + 1, outputDir, writer, mode);
            } else {
                mStereoSequenceActive = false;
                mHost.restorePreview("pair sequence ended");
                Log.i(TAG, "lens pair sequence complete: " + pairs.size() + " pairs");
            }
        }, PAIR_WARM_MS + PAIR_SETTLE_MS);
    }

    // ------------------------------------------------- periodic stereo pairs (ReconStab #36)
    //
    // The mechanism is in StereoCapture (see "periodic pairs inside a video" there). This
    // end owns the REQUEST: while pairs are on, the repeating request carries both physical
    // streams as targets, so both sensors run and every frame reaches the two readers.
    //
    // Per-physical keys need a builder created FOR those physical ids -- setPhysicalCameraKey
    // on a plain builder throws "Physical camera id: 2 is not valid!", which is how the first
    // OBJECT pair was lost. The recording's builder is plain, on purpose: a physical-aware
    // builder for every recording would change the control clips this app is compared
    // against. So the swap is made here, only while pairs are on: a new builder from the same
    // template with the ids, every key of the current request copied across, the three targets
    // added, and the host's builder replaced. Every other path that re-issues the preview
    // builder (lock, torch, EV, manual exposure, AE range, focus stack) then carries the
    // streams along without knowing. Stop does the reverse.

    private boolean mPeriodicStereo = false;

    boolean periodicActive() {
        return mPeriodicStereo;
    }

    /**
     * Put both physical streams into the repeating request and start keeping a pair every
     * intervalMs. Safe to call when unsupported: it logs and does nothing.
     */
    void startPeriodic(long intervalMs, File outputDir, RecordingWriter writer,
                       StillCaptureManager.CaptureMode mode) {
        if (Build.VERSION.SDK_INT < 28 || !supported() || !sessionReady()) {
            Log.w(TAG, "periodic stereo unavailable (session or lens pair missing)");
            return;
        }
        if (mPeriodicStereo) {
            return;
        }
        try {
            StereoCapture stereo = stereo();
            CaptureRequest.Builder b = mHost.device().createCaptureRequest(
                    CameraDevice.TEMPLATE_RECORD, LensRoles.stereoPhysicalIds());
            copyAllKeys(mHost.previewBuilder().build(), b);
            b.addTarget(mHost.previewSurface());
            // The METRIC pair, not every lens the session happens to have configured. The
            // periodic stream exists to put a known 18.02 mm ruler in the clip; adding a
            // telephoto whose offset the device will not publish would put two more streams
            // in the recording's own repeating request for the whole walk and contribute no
            // scale for the cost.
            for (Surface s : stereo.getMetricPairSurfaces().values()) {
                b.addTarget(s);
            }
            stereo.applyPhysicalFullArrays(b, LensRoles.stereoPhysicalIds());
            // NO widest-zoom here, deliberately. This request also drives the VIDEO for the
            // whole clip, and at 0.6 the logical camera switches master to the ultrawide --
            // every walk would be shot on the wide lens. So periodic pairs inside a video keep
            // the ultrawide half cropped toward the main camera's view (a session-dependent
            // 1.4-1.6x; see applyFullFieldOfView), and their depth needs the effective focal
            // measured, not the census value. Changing the clip's lens is an operator
            // decision, not a side effect of asking for a metric anchor.
            mHost.replacePreviewBuilder(b);
            mHost.reissuePreview();
            stereo.startPeriodicStereo(intervalMs * 1_000_000L, outputDir, writer, mode);
            mPeriodicStereo = true;
            Log.i(TAG, "periodic stereo: physical streams added to the repeating request, "
                    + intervalMs + " ms interval");
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "periodic stereo could not start: " + e);
        }
    }

    /** Take the physical streams back out of the repeating request. */
    void stopPeriodic() {
        if (!mPeriodicStereo) {
            return;
        }
        mPeriodicStereo = false;
        StereoCapture stereo = stereo();
        if (stereo != null) {
            stereo.stopPeriodicStereo();
        }
        if (mHost.session() == null || mHost.previewBuilder() == null
                || mHost.device() == null) {
            return;
        }
        try {
            CaptureRequest.Builder b =
                    mHost.device().createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            copyAllKeys(mHost.previewBuilder().build(), b);
            b.addTarget(mHost.previewSurface());
            mHost.replacePreviewBuilder(b);
            mHost.reissuePreview();
            Log.i(TAG, "periodic stereo: physical streams removed, preview request restored");
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "periodic stereo: could not restore the plain request: " + e);
        }
    }

    /** Every key the built request carries, onto another builder. Targets are not keys. */
    private static void copyAllKeys(CaptureRequest from, CaptureRequest.Builder to) {
        for (CaptureRequest.Key<?> key : from.getKeys()) {
            copyKey(from, to, key);
        }
    }

    private static <T> void copyKey(CaptureRequest from, CaptureRequest.Builder to,
                                    CaptureRequest.Key<T> key) {
        T v = from.get(key);
        if (v != null) {
            try {
                to.set(key, v);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "key " + key.getName() + " not copied: " + e);
            }
        }
    }
}
