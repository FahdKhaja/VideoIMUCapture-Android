package se.lth.math.videoimucapture;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Whether the widest zoom lifts the ultrawide stream's crop. See {@link #probeZoom}. */
final class ZoomProbe {
    // One tag for the whole probe run, so one logcat filter follows it end to end.
    private static final String TAG = "StereoProbe";

    private ZoomProbe() {
    }

    /**
     * Does the ultrawide's physical stream come back UNCROPPED when the logical camera is at
     * its widest zoom?
     *
     * Measured 2026-09-20 across two pair sequences: a similarity fit between the ultrawide
     * and main frames gives scale 1.009 where the census focal lengths predict 1.636. The
     * wide sensor's stream is cropped to the main camera's framing, and every row says
     * crop_region is the full 4000x3000 array -- so the crop is applied after the request,
     * inside the HAL's stream path. One burst of six reported CONTROL_ZOOM_RATIO 0.6 in its
     * logical result, which is the state in which the ultrawide is the logical camera's
     * master lens.
     *
     * Four cases from one session bound with the ultrawide and main readers: zoom 1.0 and
     * 0.6, each with both lenses targeted and with the ultrawide alone. Each keeps one frame
     * per targeted lens as zoomprobe_<case>_<tag>.jpg in the files directory and records
     * what the logical and per-physical results CLAIM the crop and zoom were. The verdict is
     * a similarity fit on the desktop; the claims are here so the two can be compared.
     */
    static JSONArray probeZoom(Context context, CameraManager manager, String logicalId,
                                       List<String> physicals, Handler handler) {
        JSONArray out = new JSONArray();
        final String uw = "2";
        final String main = "5";
        if (Build.VERSION.SDK_INT < 30 || !physicals.contains(uw) || !physicals.contains(main)) {
            return out;
        }
        final AtomicInteger err =
                new AtomicInteger(-1);
        CameraDevice device = null;
        final CameraCaptureSession[] sess =
                new CameraCaptureSession[1];
        List<ImageReader> readers = new ArrayList<>();
        try {
            // The camera service can refuse an open for a couple of seconds after a device
            // error ("disabled by policy", 2026-09-20, following a three-lens request). An
            // empty result from a refused open is indistinguishable from a probe that never
            // ran, so: retry, and if it still will not open, say so in the result.
            for (int attempt = 0; attempt < 6 && device == null; attempt++) {
                if (attempt > 0) {
                    Thread.sleep(1000L);
                    err.set(-1);
                }
                device = ProbeCameras.openTracked(manager, logicalId, handler, err);
            }
            if (device == null) {
                JSONObject r = new JSONObject();
                r.put("error", "could not open the logical camera after 6 attempts"
                        + (err.get() >= 0 ? " (device error " + err.get() + ")" : ""));
                out.put(r);
                return out;
            }
            final Map<String, ImageReader> readerFor = new LinkedHashMap<>();
            final Map<String, AtomicBoolean> want =
                    new HashMap<>();
            final Map<String, Object[]> kept =
                    Collections.synchronizedMap(new HashMap<>());
            List<OutputConfiguration> configs = new ArrayList<>();
            for (final String pid : new String[]{uw, main}) {
                ImageReader r = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 3);
                readers.add(r);
                readerFor.put(pid, r);
                final AtomicBoolean w =
                        new AtomicBoolean(false);
                want.put(pid, w);
                r.setOnImageAvailableListener(rd -> {
                    try (Image img = rd.acquireLatestImage()) {
                        if (img == null) {
                            return;
                        }
                        if (w.compareAndSet(true, false)) {
                            kept.put(pid, new Object[]{toNv21(img), img.getWidth(),
                                    img.getHeight(), img.getTimestamp()});
                        }
                    }
                }, handler);
                OutputConfiguration oc = new OutputConfiguration(r.getSurface());
                oc.setPhysicalCameraId(pid);
                configs.add(oc);
            }
            final CountDownLatch configured = new CountDownLatch(1);
            final boolean[] ok = {false};
            device.createCaptureSession(new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, configs, handler::post,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(
                                @NonNull CameraCaptureSession s) {
                            sess[0] = s;
                            ok[0] = true;
                            configured.countDown();
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession s) {
                            sess[0] = s;
                            configured.countDown();
                        }
                    }));
            configured.await(4, TimeUnit.SECONDS);
            if (!ok[0]) {
                JSONObject r = new JSONObject();
                r.put("error", "session did not configure");
                out.put(r);
                return out;
            }
            Map<String, Rect> active = new HashMap<>();
            for (String pid : new String[]{uw, main}) {
                active.put(pid, manager.getCameraCharacteristics(pid)
                        .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE));
            }
            // The zoom-1.0 pair first, so the control frames exist whatever 0.6 does.
            Object[][] cases = {
                    {"z1.0_both", 1.0f, new String[]{uw, main}},
                    {"z0.6_both", 0.6f, new String[]{uw, main}},
                    {"z0.6_uw", 0.6f, new String[]{uw}},
                    {"z1.0_uw", 1.0f, new String[]{uw}},
            };
            for (Object[] c : cases) {
                final String name = (String) c[0];
                final float zoom = (Float) c[1];
                final String[] targets = (String[]) c[2];
                JSONObject r = new JSONObject();
                r.put("case", name);
                r.put("zoom_requested", zoom);
                r.put("targets", TextUtils.join("+", targets));
                try {
                    CaptureRequest.Builder b =
                            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW,
                                    new HashSet<>(Arrays.asList(targets)));
                    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
                    for (String pid : targets) {
                        b.addTarget(readerFor.get(pid).getSurface());
                        Rect a = active.get(pid);
                        if (a != null) {
                            b.setPhysicalCameraKey(
                                    CaptureRequest.SCALER_CROP_REGION,
                                    a, pid);
                        }
                    }
                    final TotalCaptureResult[] last =
                            new TotalCaptureResult[1];
                    sess[0].setRepeatingRequest(b.build(),
                            new CameraCaptureSession.CaptureCallback() {
                                @Override
                                public void onCaptureCompleted(
                                        @NonNull CameraCaptureSession s,
                                        @NonNull CaptureRequest req,
                                        @NonNull TotalCaptureResult res) {
                                    last[0] = res;
                                }
                            }, handler);
                    Thread.sleep(1500);        // let the zoom and the exposure settle
                    kept.clear();
                    for (String pid : targets) {
                        want.get(pid).set(true);
                    }
                    long deadline = System.currentTimeMillis() + 2500;
                    while (System.currentTimeMillis() < deadline && kept.size() < targets.length
                            && err.get() < 0) {
                        Thread.sleep(50);
                    }
                    sess[0].stopRepeating();
                    Thread.sleep(200);
                    TotalCaptureResult res = last[0];
                    if (res != null) {
                        Float z = res.get(CaptureResult.CONTROL_ZOOM_RATIO);
                        r.put("zoom_reported", z == null ? JSONObject.NULL : z);
                        r.put("logical_crop", String.valueOf(res.get(
                                CaptureResult.SCALER_CROP_REGION)));
                        Long lts = res.get(CaptureResult.SENSOR_TIMESTAMP);
                        r.put("logical_result_ts", lts == null ? JSONObject.NULL : lts);
                        JSONObject per = new JSONObject();
                        for (Map.Entry<String, CaptureResult> e
                                : res.getPhysicalCameraResults().entrySet()) {
                            JSONObject p = new JSONObject();
                            p.put("crop", String.valueOf(e.getValue().get(
                                    CaptureResult.SCALER_CROP_REGION)));
                            p.put("focal_mm", e.getValue().get(
                                    CaptureResult.LENS_FOCAL_LENGTH));
                            p.put("ts", e.getValue().get(
                                    CaptureResult.SENSOR_TIMESTAMP));
                            per.put(e.getKey(), p);
                        }
                        r.put("physical_results", per);
                    }
                    JSONObject files = new JSONObject();
                    for (String pid : targets) {
                        Object[] k = kept.get(pid);
                        if (k == null) {
                            files.put(pid, JSONObject.NULL);
                            continue;
                        }
                        String tag = pid.equals(uw) ? "uw" : "main";
                        File f = new File(context.getExternalFilesDir(null),
                                "zoomprobe_" + name + "_" + tag + ".jpg");
                        writeJpeg((byte[]) k[0], (Integer) k[1], (Integer) k[2], f);
                        JSONObject fo = new JSONObject();
                        fo.put("file", f.getName());
                        fo.put("image_ts", (Long) k[3]);
                        files.put(pid, fo);
                    }
                    r.put("kept", files);
                    r.put("device_error", err.get());
                } catch (Exception e) {
                    r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                out.put(r);
                if (err.get() >= 0) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "zoom probe failed: " + e);
            try {
                JSONObject r = new JSONObject();
                r.put("error", String.valueOf(e));
                out.put(r);
            } catch (Exception ignored) {
            }
        } finally {
            if (sess[0] != null) {
                try {
                    sess[0].close();
                } catch (RuntimeException ignored) {
                }
            }
            if (device != null) {
                device.close();
            }
            for (ImageReader r : readers) {
                r.close();
            }
        }
        return out;
    }

    /** YUV_420_888 planes to NV21, honouring row and pixel strides. */
    private static byte[] toNv21(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        Image.Plane[] p = image.getPlanes();
        byte[] out = new byte[w * h * 3 / 2];
        ByteBuffer y = p[0].getBuffer();
        int yrs = p[0].getRowStride();
        int yps = p[0].getPixelStride();
        int pos = 0;
        if (yps == 1 && yrs == w) {
            y.get(out, 0, w * h);
            pos = w * h;
        } else {
            byte[] row = new byte[yrs];
            for (int r = 0; r < h; r++) {
                y.position(r * yrs);
                int len = Math.min(yrs, y.remaining());
                y.get(row, 0, len);
                for (int c = 0; c < w; c++) {
                    out[pos++] = row[c * yps];
                }
            }
        }
        ByteBuffer u = p[1].getBuffer();
        ByteBuffer v = p[2].getBuffer();
        int urs = p[1].getRowStride();
        int ups = p[1].getPixelStride();
        int vrs = p[2].getRowStride();
        int vps = p[2].getPixelStride();
        int cw = w / 2;
        int ch = h / 2;
        byte[] urow = new byte[urs];
        byte[] vrow = new byte[vrs];
        for (int r = 0; r < ch; r++) {
            u.position(r * urs);
            u.get(urow, 0, Math.min(urs, u.remaining()));
            v.position(r * vrs);
            v.get(vrow, 0, Math.min(vrs, v.remaining()));
            for (int c = 0; c < cw; c++) {
                out[pos++] = vrow[c * vps];
                out[pos++] = urow[c * ups];
            }
        }
        return out;
    }

    private static void writeJpeg(byte[] nv21, int w, int h, File out) {
        try (FileOutputStream s = new FileOutputStream(out)) {
            new YuvImage(nv21, ImageFormat.NV21, w, h, null)
                    .compressToJpeg(new Rect(0, 0, w, h), 92, s);
        } catch (Exception e) {
            Log.e(TAG, "could not write " + out + ": " + e);
        }
    }
}
