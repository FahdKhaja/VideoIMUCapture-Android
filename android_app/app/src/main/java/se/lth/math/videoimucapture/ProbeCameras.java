package se.lth.math.videoimucapture;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Opening the camera for a probe, blocking until it is open or has failed. */
final class ProbeCameras {
    // One tag for the whole probe run, so one logcat filter follows it end to end.
    private static final String TAG = "StereoProbe";

    private ProbeCameras() {
    }

    /** openCamera, but the device's later errors are visible to the caller. */
    static CameraDevice openTracked(
            CameraManager manager, String id, Handler handler,
            final AtomicInteger errorOut) {
        final CameraDevice[] out = new CameraDevice[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    out[0] = camera;
                    latch.countDown();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    latch.countDown();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "device error " + error + " on " + id);
                    errorOut.set(error);
                    latch.countDown();
                }
            }, handler);
            latch.await(5, TimeUnit.SECONDS);
        } catch (CameraAccessException | SecurityException | InterruptedException e) {
            Log.e(TAG, "openCamera failed: " + e);
        }
        return out[0];
    }

    static CameraDevice open(CameraManager manager, String id, Handler handler) {
        final CameraDevice[] out = new CameraDevice[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    out[0] = camera;
                    latch.countDown();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    latch.countDown();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "open error " + error + " on " + id);
                    camera.close();
                    latch.countDown();
                }
            }, handler);
            latch.await(5, TimeUnit.SECONDS);
        } catch (CameraAccessException | SecurityException | InterruptedException e) {
            Log.e(TAG, "openCamera failed: " + e);
        }
        return out[0];
    }
}
