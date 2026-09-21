package se.lth.math.videoimucapture;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.view.Surface;

/**
 * What a sequence that borrows the repeating request needs from the camera that owns it.
 *
 * Three things take the repeating request away from the preview for a while -- a focus stack
 * parking the lens, a pair warm-up adding physical streams, periodic pairs swapping the
 * builder for the length of a clip -- and each of them used to reach into Camera2Proxy's
 * fields and put the preview back in its own way. They now ask for the session through this,
 * and there is ONE way back: {@link #restorePreview}.
 *
 * Every accessor may return null once the camera has been released; a sequence that runs on
 * delayed posts has to check before each step, exactly as it did when these were fields.
 */
interface RepeatingRequestHost {
    CameraDevice device();

    CameraCaptureSession session();

    /** The live preview/record builder. Setting a key on it takes effect at the next re-issue. */
    CaptureRequest.Builder previewBuilder();

    /** Swap the builder itself, for a request that needs different physical ids behind it. */
    void replacePreviewBuilder(CaptureRequest.Builder b);

    Surface previewSurface();

    /** The camera thread: session callbacks arrive on it, so steps posted here are serialised. */
    Handler handler();

    StillCaptureManager stills();

    /** Make the preview builder, as it now stands, the repeating request. */
    void reissuePreview() throws CameraAccessException;

    /** Make some OTHER request the repeating one: a warm-up. */
    void setRepeating(CaptureRequest request) throws CameraAccessException;

    /**
     * Put the preview builder back as the repeating request, and close any pair arm that the
     * stream it was waiting on can no longer complete. Never throws; logs with {@code why}.
     */
    void restorePreview(String why);
}
