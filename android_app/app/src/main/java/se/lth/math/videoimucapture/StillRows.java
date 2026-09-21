package se.lth.math.videoimucapture;

import android.graphics.Rect;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Build;

/** The parts of a StillMetaData row that a still and a stereo half fill in the same way. */
final class StillRows {

    private StillRows() {
    }

    /**
     * Record the readout rectangle and zoom for one shot.
     *
     * @param per    this lens's own result where the device supplies one, else the logical
     *               result — the crop is per-sensor, so the physical result is the one that
     *               answers the question.
     * @param outer  the logical result, which is where CONTROL_ZOOM_RATIO lives.
     */
    static void recordCrop(RecordingProtos.StillMetaData.Builder b, CaptureResult per,
                            TotalCaptureResult outer) {
        Rect crop = per.get(CaptureResult.SCALER_CROP_REGION);
        if (crop != null) {
            b.setCropRegion(RecordingProtos.VideoFrameMetaData.Rect.newBuilder()
                    .setLeft(crop.left).setTop(crop.top)
                    .setRight(crop.right).setBottom(crop.bottom));
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Float z = outer.get(CaptureResult.CONTROL_ZOOM_RATIO);
            if (z != null) {
                b.setZoomRatio(z);
            }
        }
    }

    /** The orientation at the shutter instant, which is what lets a pan be stitched from angles. */
    static void addOrientation(RecordingProtos.StillMetaData.Builder b, IMUManager imu) {
        if (imu == null) {
            return;
        }
        float[] q = imu.getLatestOrientation();
        if (q != null) {
            for (float v : q) {
                b.addOrientationQuaternion(v);
            }
            b.setOrientationTimeNs(imu.getLatestOrientationTimeNs());
        }
    }
}
