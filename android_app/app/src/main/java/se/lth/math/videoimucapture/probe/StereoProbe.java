package se.lth.math.videoimucapture.probe;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers one question: can this device stream TWO PHYSICAL LENSES AT ONCE?
 *
 * Why it matters here: the ultrawide publishes LENS_POSE_TRANSLATION = 18.02 mm from the
 * main camera on the SM-S928U, and both publish factory intrinsics. A simultaneous pair
 * across a known baseline is a metric-scale stereo rig — it fixes scale from a single
 * capture, which photogrammetry from a monocular walk cannot do without external control.
 *
 * getConcurrentCameraIds() only offers rear+front on this device, so two independent
 * sessions are out. The remaining route is the Android logical-multi-camera mechanism:
 * one session on the logical camera, with individual OutputConfigurations bound to
 * physical ids via setPhysicalCameraId(). This probe enumerates candidate pairs and asks
 * CameraDevice.isSessionConfigurationSupported() about each, which answers without
 * committing to a session.
 *
 * Triggered by an intent extra rather than UI, so it can be run over adb:
 *   adb shell am start -n se.lth.math.videoimucapture/.CameraCaptureActivity \
 *       --ez run_stereo_probe true
 * Result lands in the app files dir as stereo_probe.json.
 *
 * That question grew into three, each in its own class and each a stage of one run:
 * {@link LayoutProbe} (will the layout configure), {@link ZoomProbe} (is the ultrawide stream
 * the ultrawide's view) and {@link StreamingProbe} (does it actually stream). The order they
 * run in matters and is argued where it is set, below.
 */
public class StereoProbe {
    private static final String TAG = "StereoProbe";
    public static final String PROBE_FILE = "stereo_probe.json";
    public static final String EXTRA_RUN = "run_stereo_probe";

    public static void run(Context context) {
        JSONObject root = new JSONObject();
        HandlerThread thread = new HandlerThread("StereoProbe");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            root.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            root.put("android_sdk", Build.VERSION.SDK_INT);

            if (Build.VERSION.SDK_INT < 29) {
                root.put("error", "isSessionConfigurationSupported needs API 29+");
            } else {
                JSONArray logicals = new JSONArray();
                JSONArray streaming = new JSONArray();
                JSONArray zoom = new JSONArray();
                JSONArray unreadable = new JSONArray();
                // Attached BEFORE the loop, so whatever has been measured is in the file
                // whatever happens next. They were attached after it, and the streaming stage
                // ends by killing the camera service ON PURPOSE: the very next line --
                // characteristics for the next camera id -- then threw "unknown device 1", and
                // the exception took all three stages' results with it. Both zoom-probe runs to
                // date (2026-09-20 19:32, 2026-09-21 08:58) wrote a 235-byte file for that
                // reason; the verdict survived only because the zoom stage's frames are JPEGs.
                root.put("logical_cameras", logicals);
                root.put("streaming", streaming);
                root.put("zoom", zoom);
                root.put("unreadable_cameras", unreadable);
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics ch;
                    try {
                        ch = manager.getCameraCharacteristics(id);
                    } catch (CameraAccessException | IllegalArgumentException e) {
                        // A camera that will not describe itself -- usually because the stage
                        // before it has just taken the service down -- is noted and skipped.
                        Log.w(TAG, "camera " + id + " unreadable, skipped: " + e);
                        unreadable.put(new JSONObject().put("id", id)
                                .put("error", String.valueOf(e)));
                        continue;
                    }
                    List<String> physicals = new ArrayList<>(ch.getPhysicalCameraIds());
                    if (physicals.size() < 2) {
                        continue;
                    }
                    logicals.put(LayoutProbe.probeLogical(manager, id, ch, physicals, handler));
                    // After probeLogical has closed its device: every streaming case opens
                    // and closes its own, because the failing ones kill it.
                    // Zoom BEFORE streaming. The streaming stage ends on the four-lens case,
                    // which kills the device, and on 2026-09-20 a three-lens case put the
                    // camera into "disabled by policy" for two seconds -- the zoom stage,
                    // running straight after, could not open it and returned nothing at all.
                    // It asks nothing of the HAL that has ever failed, so it goes first.
                    JSONObject z = new JSONObject();
                    z.put("logical_id", id);
                    z.put("cases", ZoomProbe.probeZoom(context, manager, id, physicals, handler));
                    zoom.put(z);
                    JSONObject s = new JSONObject();
                    s.put("logical_id", id);
                    s.put("cases", StreamingProbe.probeStreaming(manager, id, physicals, handler));
                    streaming.put(s);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "probe failed: " + e);
            try {
                root.put("exception", String.valueOf(e));
            } catch (Exception ignored) {
            }
        } finally {
            thread.quitSafely();
        }
        write(context, root);
    }

    private static void write(Context context, JSONObject root) {
        try {
            File out = new File(context.getExternalFilesDir(null), PROBE_FILE);
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "stereo probe written to " + out.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "could not write probe result: " + e);
        }
    }
}
