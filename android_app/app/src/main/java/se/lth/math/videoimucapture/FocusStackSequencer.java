package se.lth.math.videoimucapture;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives a focus stack one settled step at a time.
 *
 * A focus bracket cannot be a burst. The first build proved it: five requests spanning
 * 0.658 dioptres came back as five frames all reporting 0.100 D, landing 33.3 ms apart
 * — one sensor period — with a global sharpness spread of 1.0048x. captureBurst exists
 * to minimise the gap between frames, which is exactly the wrong property when the
 * parameter being bracketed has to physically move.
 *
 * So each step is now: park the lens with a REPEATING request, wait until the lens
 * reports it has arrived, then open the shutter. The wait is bounded, and how long it
 * took is recorded per shot, so a lens that never arrives is visible in the data.
 *
 * What the stack's slices ARE -- the plan, the rows, the files -- is StillCaptureManager's.
 */
final class FocusStackSequencer {
    private static final String TAG = "FocusStack";

    /** Dioptre tolerance for "the lens got there". Well under one depth-of-field step. */
    private static final float FOCUS_TOLERANCE_D = 0.02f;
    /** Give up on a step after this long and shoot anyway, flagged as unsettled. */
    private static final long FOCUS_SETTLE_TIMEOUT_MS = 400L;
    /** Breathing room after the shutter before the lens is driven somewhere else. */
    private static final long FOCUS_SHOT_SPACING_MS = 120L;

    private final RepeatingRequestHost mHost;

    // THREADING. Every one of these is written by runFocusStep and read by onResult,
    // which runs on the camera callback thread. The whole sequence is therefore posted to
    // the host's handler — the same thread the session callbacks are delivered on — so the
    // steps and the results they are waiting for are serialised by construction rather than
    // by hoping. volatile covers the initial hand-off from whichever thread pressed the
    // button.
    private volatile float mFocusTarget = Float.NaN;
    private volatile long mFocusStepStartNs;
    private volatile Runnable mFocusTimeout;
    private final AtomicBoolean mFocusStepPending = new AtomicBoolean(false);
    private final AtomicBoolean mFocusStackRunning = new AtomicBoolean(false);

    /**
     * Set by runFocusStep, invoked by onResult. Held as a field rather than passed so
     * the result callback needs no knowledge of which step it is completing.
     */
    private volatile Runnable mFocusSettleSignal = () -> {
    };

    FocusStackSequencer(RepeatingRequestHost host) {
        mHost = host;
    }

    /**
     * @param shots      number of slices; the plan is centred on the current autofocus result
     *                   and stepped by the depth of field, so this is "how thick a subject".
     * @param lastResult the most recent metered result, which is where "current" comes from
     */
    void start(int shots, boolean writeRaw, File outputDir, RecordingWriter writer,
               TotalCaptureResult lastResult) {
        if (mHost.stills() == null || mHost.session() == null
                || mHost.previewBuilder() == null || mHost.device() == null) {
            Log.w(TAG, "focus stack requested before the session exists");
            return;
        }
        if (!mFocusStackRunning.compareAndSet(false, true)) {
            Log.w(TAG, "focus stack already running; ignoring");
            return;
        }
        final float[] plan = mHost.stills().planFocusStack(lastResult, shots);
        mHost.stills().beginFocusStack(plan.length, writeRaw, outputDir, writer);
        // Remember what the preview was doing so autofocus can be handed back afterwards.
        // If the preview never named a mode, hand back CONTINUOUS_PICTURE rather than the
        // OFF this sequence is about to set — otherwise a finished stack leaves the camera
        // stuck at the last slice's focus with no way back but a restart.
        final Integer afMode = mHost.previewBuilder().get(CaptureRequest.CONTROL_AF_MODE);
        final int restoreMode = afMode != null
                ? afMode : CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        final Float afDist = mHost.previewBuilder().get(CaptureRequest.LENS_FOCUS_DISTANCE);
        mHost.handler().post(() -> runFocusStep(plan, 0, restoreMode, afDist));
    }

    /**
     * The session is going away. The steps still queued die with the camera thread, so the
     * flags they would have cleared are cleared here -- otherwise a stack cut short by a
     * release would refuse every stack after it as "already running".
     */
    void sessionGone() {
        mFocusStepPending.set(false);
        mFocusStackRunning.set(false);
    }

    private void runFocusStep(float[] plan, int index, int restoreAfMode,
                              Float restoreAfDist) {
        if (index >= plan.length) {
            restoreAfterFocusStack(restoreAfMode, restoreAfDist);
            mFocusStackRunning.set(false);
            Log.i(TAG, "focus stack complete: " + plan.length + " slices");
            return;
        }
        final float target = plan[index];
        mFocusTarget = target;
        mFocusStepStartNs = SystemClock.elapsedRealtimeNanos();
        mFocusStepPending.set(true);

        final Runnable timeout = () -> fireFocusShot(plan, index, target, false,
                restoreAfMode, restoreAfDist);
        mFocusTimeout = timeout;
        mFocusSettleSignal = () -> fireFocusShot(plan, index, target, true,
                restoreAfMode, restoreAfDist);

        try {
            CaptureRequest.Builder preview = mHost.previewBuilder();
            preview.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            preview.set(CaptureRequest.LENS_FOCUS_DISTANCE, target);
            mHost.reissuePreview();
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "could not drive focus to " + target + ": " + e);
        }
        mHost.handler().postDelayed(timeout, FOCUS_SETTLE_TIMEOUT_MS);
    }

    /**
     * Take the shot for one step. Reached from either the settle callback or the timeout;
     * the AtomicBoolean guarantees exactly one of them wins, and `settled` is passed in by
     * whichever did rather than inferred from the elapsed time.
     */
    private void fireFocusShot(float[] plan, int index, float target, boolean settled,
                               int restoreAfMode, Float restoreAfDist) {
        if (!mFocusStepPending.compareAndSet(true, false)) {
            return;
        }
        Runnable t = mFocusTimeout;
        if (t != null) {
            mHost.handler().removeCallbacks(t);
        }
        final long waited = SystemClock.elapsedRealtimeNanos() - mFocusStepStartNs;
        if (mHost.stills() == null) {
            Log.w(TAG, "focus stack abandoned at slice " + index + ": session gone");
            mFocusStackRunning.set(false);
            return;
        }
        mHost.stills().captureFocusShot(mHost.device(), mHost.session(),
                mHost.previewBuilder(), index, target, waited, settled);
        mHost.handler().postDelayed(
                () -> runFocusStep(plan, index + 1, restoreAfMode, restoreAfDist),
                FOCUS_SHOT_SPACING_MS);
    }

    /**
     * Called for every preview result while a focus step is outstanding. Accepts only
     * results whose OWN request carried the target distance — the pipeline is several
     * frames deep, so results for the previous lens position keep arriving after the new
     * request goes out, and grading those is precisely how the burst version fooled itself
     * into reporting five focus positions it never reached.
     *
     * A focus stack step may be waiting on the lens to arrive; cheap when idle.
     */
    void onResult(CaptureRequest request, CaptureResult result) {
        if (!mFocusStepPending.get()) {
            return;
        }
        Float requested = request.get(CaptureRequest.LENS_FOCUS_DISTANCE);
        if (requested == null || Math.abs(requested - mFocusTarget) > 1e-4f) {
            return;   // a result from before this step's request took effect
        }
        Integer state = result.get(CaptureResult.LENS_STATE);
        if (state != null && state != CameraMetadata.LENS_STATE_STATIONARY) {
            return;   // still moving
        }
        Float actual = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (actual != null && Math.abs(actual - mFocusTarget) > FOCUS_TOLERANCE_D) {
            return;   // parked, but not where we asked
        }
        mFocusSettleSignal.run();
    }

    /** Hand autofocus back, then the one way out every borrowed request takes. */
    private void restoreAfterFocusStack(int afMode, Float afDist) {
        CaptureRequest.Builder preview = mHost.previewBuilder();
        if (preview == null) {
            return;
        }
        preview.set(CaptureRequest.CONTROL_AF_MODE, afMode);
        if (afDist != null) {
            preview.set(CaptureRequest.LENS_FOCUS_DISTANCE, afDist);
        }
        mHost.restorePreview("focus stack ended");
    }
}
