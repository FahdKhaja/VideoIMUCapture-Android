package se.lth.math.videoimucapture;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 */
public class StereoProbe {
    private static final String TAG = "StereoProbe";
    public static final String PROBE_FILE = "stereo_probe.json";
    public static final String EXTRA_RUN = "run_stereo_probe";

    /** Candidate stream sizes, largest first — a pair that fails big may still pass small. */
    private static final Size[] CANDIDATE_SIZES = {
            new Size(1920, 1080),
            new Size(1280, 720),
            new Size(640, 480),
    };

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
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                    List<String> physicals = new ArrayList<>(ch.getPhysicalCameraIds());
                    if (physicals.size() < 2) {
                        continue;
                    }
                    logicals.put(probeLogical(manager, id, ch, physicals, handler));
                    // After probeLogical has closed its device: every streaming case opens
                    // and closes its own, because the failing ones kill it.
                    // Zoom BEFORE streaming. The streaming stage ends on the four-lens case,
                    // which kills the device, and on 2026-09-20 a three-lens case put the
                    // camera into "disabled by policy" for two seconds -- the zoom stage,
                    // running straight after, could not open it and returned nothing at all.
                    // It asks nothing of the HAL that has ever failed, so it goes first.
                    JSONObject z = new JSONObject();
                    z.put("logical_id", id);
                    z.put("cases", probeZoom(context, manager, id, physicals, handler));
                    zoom.put(z);
                    JSONObject s = new JSONObject();
                    s.put("logical_id", id);
                    s.put("cases", probeStreaming(manager, id, physicals, handler));
                    streaming.put(s);
                }
                root.put("logical_cameras", logicals);
                root.put("streaming", streaming);
                root.put("zoom", zoom);
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

    private static JSONObject probeLogical(CameraManager manager, String logicalId,
                                           CameraCharacteristics ch, List<String> physicals,
                                           Handler handler) throws Exception {
        JSONObject o = new JSONObject();
        o.put("logical_id", logicalId);
        o.put("physical_ids", new JSONArray(physicals));

        // Which request keys may be set PER PHYSICAL camera — if exposure/sensitivity are
        // here, the two streams can be driven independently, not just co-exposed.
        if (Build.VERSION.SDK_INT >= 28) {
            JSONArray keys = new JSONArray();
            for (android.hardware.camera2.CaptureRequest.Key<?> k
                    : ch.getAvailablePhysicalCameraRequestKeys()) {
                keys.put(k.getName());
            }
            o.put("available_physical_request_keys", keys);
        }

        CameraDevice device = openCamera(manager, logicalId, handler);
        if (device == null) {
            o.put("error", "could not open logical camera (in use?)");
            return o;
        }
        List<ImageReader> readers = new ArrayList<>();
        try {
            // Arities 2..N. Pairs answer "can we do stereo"; triples and quads answer
            // "can we do stereo TWICE AT ONCE", which is the only way a single shutter
            // produces a measurement and an independent check of it.
            JSONArray results = new JSONArray();
            JSONObject byArity = new JSONObject();
            for (int k = 2; k <= physicals.size(); k++) {
                int supported = 0;
                int tried = 0;
                for (List<String> combo : combinations(physicals, k)) {
                    JSONObject r = probeCombo(manager, device, combo);
                    results.put(r);
                    tried++;
                    if (r.optBoolean("supported")) {
                        supported++;
                    }
                }
                byArity.put(String.valueOf(k), supported + "/" + tried + " supported");
            }
            o.put("combos", results);
            o.put("combos_by_arity", byArity);
            o.put("realistic_combinations",
                    probeRealistic(manager, device, logicalId, physicals, readers));
        } finally {
            device.close();
            for (ImageReader r : readers) {
                r.close();
            }
        }
        return o;
    }

    /**
     * The pair test above opens a session containing ONLY the two physical streams,
     * which is not the session the app actually runs. This asks whether a dual-lens
     * capture can coexist with the preview and stills already in flight — because if it
     * cannot, the stereo shot needs its own session and a preview teardown, which is a
     * very different piece of work.
     *
     * Tested largest-first: whichever configuration survives determines the design.
     */
    private static JSONArray probeRealistic(CameraManager manager, CameraDevice device,
                                            String logicalId, List<String> physicals,
                                            List<ImageReader> readers) throws Exception {
        JSONArray out = new JSONArray();

        // Ordered so the calibrated pair leads: 2+5 is the only pair with a published
        // LENS_POSE_TRANSLATION, so any larger set should contain it — a third and fourth lens
        // are only worth having if the metric one is still in the shot to tie them to.
        List<String> ordered = new ArrayList<>();
        for (String pref : new String[]{"2", "5", "6", "7"}) {
            if (physicals.contains(pref)) {
                ordered.add(pref);
            }
        }
        for (String p : physicals) {
            if (!ordered.contains(p)) {
                ordered.add(p);
            }
        }
        if (ordered.size() < 2) {
            return out;
        }

        StreamConfigurationMap map = manager.getCameraCharacteristics(logicalId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return out;
        }
        Size maxJpeg = largest(map.getOutputSizes(ImageFormat.JPEG));
        Size maxRaw = largest(map.getOutputSizes(ImageFormat.RAW_SENSOR));
        Size preview = new Size(1920, 1080);
        Size stereo = new Size(1920, 1080);

        String[][] shapes = {
                {"preview+jpeg+raw", "P", "J", "R", "S"},
                {"preview+jpeg", "P", "J", "S"},
                {"preview", "P", "S"},
                {"jpeg", "J", "S"},
        };
        // Arity 2 is the session the app runs today. 3 and 4 are the question: a full-res JPEG
        // and a RAW alongside FOUR physical streams is seven surfaces, and the guaranteed
        // stream combinations run out long before that.
        for (int nPhys = 2; nPhys <= ordered.size(); nPhys++) {
            List<String> chosen = new ArrayList<>(ordered.subList(0, nPhys));
            for (String[] shape : shapes) {
                List<OutputConfiguration> configs = new ArrayList<>();
                List<ImageReader> local = new ArrayList<>();
                String label = shape[0] + "+" + nPhys + "physical";
                try {
                    for (int i = 1; i < shape.length; i++) {
                        switch (shape[i]) {
                            case "P": {
                                ImageReader r = ImageReader.newInstance(preview.getWidth(),
                                        preview.getHeight(), ImageFormat.YUV_420_888, 2);
                                local.add(r);
                                configs.add(new OutputConfiguration(r.getSurface()));
                                break;
                            }
                            case "J": {
                                if (maxJpeg == null) continue;
                                ImageReader r = ImageReader.newInstance(maxJpeg.getWidth(),
                                        maxJpeg.getHeight(), ImageFormat.JPEG, 2);
                                local.add(r);
                                configs.add(new OutputConfiguration(r.getSurface()));
                                break;
                            }
                            case "R": {
                                if (maxRaw == null) continue;
                                ImageReader r = ImageReader.newInstance(maxRaw.getWidth(),
                                        maxRaw.getHeight(), ImageFormat.RAW_SENSOR, 2);
                                local.add(r);
                                configs.add(new OutputConfiguration(r.getSurface()));
                                break;
                            }
                            case "S": {
                                for (String pid : chosen) {
                                    ImageReader r = ImageReader.newInstance(stereo.getWidth(),
                                            stereo.getHeight(), ImageFormat.YUV_420_888, 2);
                                    local.add(r);
                                    OutputConfiguration oc =
                                            new OutputConfiguration(r.getSurface());
                                    oc.setPhysicalCameraId(pid);
                                    configs.add(oc);
                                }
                                break;
                            }
                        }
                    }
                    SessionConfiguration sc = new SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR, configs, Runnable::run,
                            new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(
                                        @NonNull android.hardware.camera2.CameraCaptureSession s) {
                                }

                                @Override
                                public void onConfigureFailed(
                                        @NonNull android.hardware.camera2.CameraCaptureSession s) {
                                }
                            });
                    JSONObject r = new JSONObject();
                    r.put("combo", label);
                    r.put("physicals", TextUtils.join("+", chosen));
                    r.put("streams", configs.size());
                    r.put("supported", device.isSessionConfigurationSupported(sc));
                    out.put(r);
                } catch (IllegalArgumentException | UnsupportedOperationException e) {
                    JSONObject r = new JSONObject();
                    r.put("combo", label);
                    r.put("physicals", TextUtils.join("+", chosen));
                    r.put("supported", false);
                    r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    out.put(r);
                } finally {
                    // A full-res RAW reader is ~25 MB; twelve of these combinations held open
                    // at once would fail the later ones for reasons that are not the camera's.
                    for (ImageReader r : local) {
                        r.close();
                    }
                }
            }
        }
        return out;
    }

    private static Size largest(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    /**
     * Ask whether N physical streams configure simultaneously, for any N.
     *
     * Generalised from the pairs-only version 2026-08-02. The question is whether this
     * device can give more than two lenses at one shutter, because a SECOND baseline is
     * what turns a stereo measurement into a checkable one: this device publishes
     * LENS_POSE_TRANSLATION for the ultrawide alone ([0, 0.018018510, 0] — 18.02 mm on Y
     * and nothing else), so every other pair is scale-free until it is calibrated against
     * that one. Two simultaneous pairs would let each capture check itself.
     *
     * Note the readers are closed PER COMBINATION rather than accumulated. With 6 pairs,
     * 4 triples and a quad, each retried across several candidate sizes, holding every
     * ImageReader open to the end would run to hundreds of megabytes of buffers and could
     * fail the later, larger combinations for reasons that have nothing to do with the
     * camera. isSessionConfigurationSupported() is synchronous and does not retain the
     * surfaces, so releasing them immediately is safe.
     */
    private static JSONObject probeCombo(CameraManager manager, CameraDevice device,
                                         List<String> ids) throws Exception {
        JSONObject o = new JSONObject();
        o.put("combo", TextUtils.join("+", ids));
        o.put("n_lenses", ids.size());
        String supportedAt = null;
        String lastError = null;

        for (Size size : CANDIDATE_SIZES) {
            // Every physical must actually offer the size, or the answer is meaningless.
            boolean allOffer = true;
            for (String id : ids) {
                if (!offersSize(manager, id, size)) {
                    allOffer = false;
                    break;
                }
            }
            if (!allOffer) {
                continue;
            }
            List<ImageReader> local = new ArrayList<>();
            try {
                List<OutputConfiguration> configs = new ArrayList<>();
                for (String id : ids) {
                    ImageReader r = ImageReader.newInstance(
                            size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
                    local.add(r);
                    OutputConfiguration c = new OutputConfiguration(r.getSurface());
                    c.setPhysicalCameraId(id);
                    configs.add(c);
                }

                SessionConfiguration config = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        configs,
                        Runnable::run,
                        new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(
                                    @NonNull android.hardware.camera2.CameraCaptureSession s) {
                            }

                            @Override
                            public void onConfigureFailed(
                                    @NonNull android.hardware.camera2.CameraCaptureSession s) {
                            }
                        });

                if (device.isSessionConfigurationSupported(config)) {
                    supportedAt = size.toString();
                }
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                for (ImageReader r : local) {
                    r.close();
                }
            }
            if (supportedAt != null) {
                break;
            }
        }
        o.put("supported", supportedAt != null);
        if (supportedAt != null) {
            o.put("largest_supported_size", supportedAt);
        }
        if (lastError != null) {
            o.put("last_error", lastError);
        }
        return o;
    }

    /** Every combination of {@code k} ids drawn from {@code ids}, in stable order. */
    private static List<List<String>> combinations(List<String> ids, int k) {
        List<List<String>> out = new ArrayList<>();
        int n = ids.size();
        if (k > n || k <= 0) {
            return out;
        }
        int[] idx = new int[k];
        for (int i = 0; i < k; i++) {
            idx[i] = i;
        }
        while (true) {
            List<String> combo = new ArrayList<>();
            for (int i : idx) {
                combo.add(ids.get(i));
            }
            out.add(combo);
            int i = k - 1;
            while (i >= 0 && idx[i] == n - k + i) {
                i--;
            }
            if (i < 0) {
                return out;
            }
            idx[i]++;
            for (int j = i + 1; j < k; j++) {
                idx[j] = idx[j - 1] + 1;
            }
        }
    }

    private static boolean offersSize(CameraManager manager, String id, Size size) {
        try {
            StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return false;
            }
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            return sizes != null && Arrays.asList(sizes).contains(size);
        } catch (CameraAccessException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ streaming

    /**
     * The question the rest of this file cannot answer: does it actually STREAM?
     *
     * isSessionConfigurationSupported() checks stream layout. It said yes to seven streams
     * with four physical outputs, and on 2026-09-20 that configuration was built, the first
     * frame of its warm-up was submitted, and the vendor HAL logged
     *
     *   camxsession.cpp: SyncProcessCaptureRequest() More than 2 real time pipeline request
     *   How to handle?  numOfRealtimePipelines = 4
     *
     * then cancelled the request and raised CAMERA_ERROR (3) on that frame. The session was
     * fine; the sensors could not all run. Concurrency is a property of the REQUEST -- how
     * many physical outputs one request targets -- and only submitting one finds it out.
     *
     * So this binds a set of physical streams, submits a repeating request that targets a
     * SUBSET of them, and waits for a frame from every targeted lens or for the device to
     * die. Each case gets a fresh device, because a failing case kills the one it had. The
     * pairs are the design that survives if the four-way case fails: a session bound with
     * every lens whose requests only ever ask two at a time still measures every baseline
     * against a static target, one pair per request.
     */
    private static JSONArray probeStreaming(CameraManager manager, String logicalId,
                                            List<String> physicals, Handler handler) {
        JSONArray out = new JSONArray();
        List<String> ordered = new ArrayList<>();
        for (String pref : new String[]{"2", "5", "6", "7"}) {
            if (physicals.contains(pref)) {
                ordered.add(pref);
            }
        }
        for (String id : physicals) {
            if (!ordered.contains(id)) {
                ordered.add(id);
            }
        }
        if (ordered.size() < 2) {
            return out;
        }
        List<String> all = ordered;
        List<String> pair = ordered.subList(0, 2);
        List<List<String>> binds = new ArrayList<>();
        List<List<String>> requests = new ArrayList<>();
        // The archive: the pair, bound alone and asked for alone.
        binds.add(pair);
        requests.add(pair);
        // Everything bound, pairs asked for. Every pair that includes the ultrawide is a
        // baseline measurable against the one published offset; 5+6 is a check on the rest.
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                binds.add(all);
                requests.add(Arrays.asList(all.get(i), all.get(j)));
            }
        }
        // Three bound and asked, then four -- the four-way case is the one known to be fatal
        // and goes last so that its death cannot be blamed on a neighbour.
        if (all.size() >= 3) {
            binds.add(all.subList(0, 3));
            requests.add(all.subList(0, 3));
            binds.add(all);
            requests.add(all.subList(0, 3));
        }
        if (all.size() >= 4) {
            binds.add(all);
            requests.add(all);
        }
        for (int c = 0; c < binds.size(); c++) {
            out.put(streamOnce(manager, logicalId, binds.get(c), requests.get(c), handler));
        }
        return out;
    }

    private static final long STREAM_WAIT_MS = 2500L;

    private static JSONObject streamOnce(CameraManager manager, String logicalId,
                                         List<String> bound, List<String> requested,
                                         Handler handler) {
        JSONObject r = new JSONObject();
        List<ImageReader> readers = new ArrayList<>();
        android.hardware.camera2.CameraCaptureSession[] sessionOut =
                new android.hardware.camera2.CameraCaptureSession[1];
        final java.util.concurrent.atomic.AtomicInteger deviceError =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        CameraDevice device = null;
        long t0 = System.currentTimeMillis();
        try {
            r.put("bound", TextUtils.join("+", bound));
            r.put("requested", TextUtils.join("+", requested));
            device = openCameraTracked(manager, logicalId, handler, deviceError);
            if (device == null) {
                r.put("verdict", "could not open device");
                return r;
            }
            final java.util.Map<String, Integer> frames = new java.util.LinkedHashMap<>();
            final CountDownLatch firstFromEach = new CountDownLatch(requested.size());
            List<OutputConfiguration> configs = new ArrayList<>();
            java.util.Map<String, ImageReader> readerFor = new java.util.LinkedHashMap<>();
            for (final String pid : bound) {
                ImageReader reader = ImageReader.newInstance(1920, 1080,
                        ImageFormat.YUV_420_888, 2);
                readers.add(reader);
                readerFor.put(pid, reader);
                frames.put(pid, 0);
                reader.setOnImageAvailableListener(rd -> {
                    try (android.media.Image img = rd.acquireLatestImage()) {
                        if (img == null) {
                            return;
                        }
                        synchronized (frames) {
                            Integer n = frames.get(pid);
                            int now = n == null ? 1 : n + 1;
                            frames.put(pid, now);
                            if (now == 1 && requested.contains(pid)) {
                                firstFromEach.countDown();
                            }
                        }
                    }
                }, handler);
                OutputConfiguration oc = new OutputConfiguration(reader.getSurface());
                oc.setPhysicalCameraId(pid);
                configs.add(oc);
            }

            final CountDownLatch configured = new CountDownLatch(1);
            final boolean[] configOk = {false};
            SessionConfiguration sc = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, configs, handler::post,
                    new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(
                                @NonNull android.hardware.camera2.CameraCaptureSession s) {
                            sessionOut[0] = s;
                            configOk[0] = true;
                            configured.countDown();
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull android.hardware.camera2.CameraCaptureSession s) {
                            sessionOut[0] = s;
                            configured.countDown();
                        }
                    });
            device.createCaptureSession(sc);
            configured.await(4, TimeUnit.SECONDS);
            r.put("configured", configOk[0]);
            if (!configOk[0] || sessionOut[0] == null) {
                r.put("verdict", "session did not configure");
                return r;
            }

            android.hardware.camera2.CaptureRequest.Builder b = device.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW, new java.util.HashSet<>(requested));
            for (String pid : requested) {
                b.addTarget(readerFor.get(pid).getSurface());
            }
            final String[] captureFailure = {null};
            sessionOut[0].setRepeatingRequest(b.build(),
                    new android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureFailed(
                                @NonNull android.hardware.camera2.CameraCaptureSession s,
                                @NonNull android.hardware.camera2.CaptureRequest req,
                                @NonNull android.hardware.camera2.CaptureFailure f) {
                            if (captureFailure[0] == null) {
                                captureFailure[0] = "reason " + f.getReason()
                                        + " frame " + f.getFrameNumber();
                            }
                        }
                    }, handler);

            // Wait for a frame from every requested lens, or for the device to die, or for
            // the clock -- whichever is first. Polled rather than a single await so a device
            // error ends the wait promptly instead of at the timeout.
            long deadline = System.currentTimeMillis() + STREAM_WAIT_MS;
            boolean allDelivered = false;
            while (System.currentTimeMillis() < deadline) {
                if (firstFromEach.await(50, TimeUnit.MILLISECONDS)) {
                    allDelivered = true;
                    break;
                }
                if (deviceError.get() >= 0) {
                    break;
                }
            }
            JSONObject perLens = new JSONObject();
            List<String> delivered = new ArrayList<>();
            synchronized (frames) {
                for (java.util.Map.Entry<String, Integer> e : frames.entrySet()) {
                    perLens.put(e.getKey(), e.getValue());
                    if (e.getValue() > 0) {
                        delivered.add(e.getKey());
                    }
                }
            }
            r.put("frames_per_lens", perLens);
            r.put("delivered", TextUtils.join("+", delivered));
            if (deviceError.get() >= 0) {
                r.put("device_error", deviceError.get());
            }
            if (captureFailure[0] != null) {
                r.put("capture_failure", captureFailure[0]);
            }
            String verdict;
            if (deviceError.get() >= 0) {
                verdict = "DEVICE DIED (error " + deviceError.get() + ")";
            } else if (allDelivered) {
                verdict = "streams";
            } else if (captureFailure[0] != null) {
                verdict = "capture failed";
            } else {
                verdict = "timed out: " + delivered.size() + " of " + requested.size()
                        + " lenses delivered";
            }
            r.put("verdict", verdict);
            r.put("streams", allDelivered && deviceError.get() < 0);
        } catch (Exception e) {
            try {
                r.put("verdict", "exception");
                r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception ignored) {
            }
        } finally {
            try {
                r.put("ms", System.currentTimeMillis() - t0);
            } catch (Exception ignored) {
            }
            if (sessionOut[0] != null) {
                try {
                    sessionOut[0].close();
                } catch (RuntimeException ignored) {
                }
            }
            if (device != null) {
                device.close();
            }
            for (ImageReader reader : readers) {
                reader.close();
            }
            // A device that just errored needs a moment before the next open succeeds.
            try {
                Thread.sleep(deviceError.get() >= 0 ? 800L : 150L);
            } catch (InterruptedException ignored) {
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ zoom

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
    private static JSONArray probeZoom(Context context, CameraManager manager, String logicalId,
                                       List<String> physicals, Handler handler) {
        JSONArray out = new JSONArray();
        final String uw = "2";
        final String main = "5";
        if (Build.VERSION.SDK_INT < 30 || !physicals.contains(uw) || !physicals.contains(main)) {
            return out;
        }
        final java.util.concurrent.atomic.AtomicInteger err =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        CameraDevice device = null;
        final android.hardware.camera2.CameraCaptureSession[] sess =
                new android.hardware.camera2.CameraCaptureSession[1];
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
                device = openCameraTracked(manager, logicalId, handler, err);
            }
            if (device == null) {
                JSONObject r = new JSONObject();
                r.put("error", "could not open the logical camera after 6 attempts"
                        + (err.get() >= 0 ? " (device error " + err.get() + ")" : ""));
                out.put(r);
                return out;
            }
            final java.util.Map<String, ImageReader> readerFor = new java.util.LinkedHashMap<>();
            final java.util.Map<String, java.util.concurrent.atomic.AtomicBoolean> want =
                    new java.util.HashMap<>();
            final java.util.Map<String, Object[]> kept =
                    java.util.Collections.synchronizedMap(new java.util.HashMap<>());
            List<OutputConfiguration> configs = new ArrayList<>();
            for (final String pid : new String[]{uw, main}) {
                ImageReader r = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 3);
                readers.add(r);
                readerFor.put(pid, r);
                final java.util.concurrent.atomic.AtomicBoolean w =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                want.put(pid, w);
                r.setOnImageAvailableListener(rd -> {
                    try (android.media.Image img = rd.acquireLatestImage()) {
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
                    new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(
                                @NonNull android.hardware.camera2.CameraCaptureSession s) {
                            sess[0] = s;
                            ok[0] = true;
                            configured.countDown();
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull android.hardware.camera2.CameraCaptureSession s) {
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
            java.util.Map<String, android.graphics.Rect> active = new java.util.HashMap<>();
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
                    android.hardware.camera2.CaptureRequest.Builder b =
                            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW,
                                    new java.util.HashSet<>(Arrays.asList(targets)));
                    b.set(android.hardware.camera2.CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
                    for (String pid : targets) {
                        b.addTarget(readerFor.get(pid).getSurface());
                        android.graphics.Rect a = active.get(pid);
                        if (a != null) {
                            b.setPhysicalCameraKey(
                                    android.hardware.camera2.CaptureRequest.SCALER_CROP_REGION,
                                    a, pid);
                        }
                    }
                    final android.hardware.camera2.TotalCaptureResult[] last =
                            new android.hardware.camera2.TotalCaptureResult[1];
                    sess[0].setRepeatingRequest(b.build(),
                            new android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                @Override
                                public void onCaptureCompleted(
                                        @NonNull android.hardware.camera2.CameraCaptureSession s,
                                        @NonNull android.hardware.camera2.CaptureRequest req,
                                        @NonNull android.hardware.camera2.TotalCaptureResult res) {
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
                    android.hardware.camera2.TotalCaptureResult res = last[0];
                    if (res != null) {
                        Float z = res.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO);
                        r.put("zoom_reported", z == null ? JSONObject.NULL : z);
                        r.put("logical_crop", String.valueOf(res.get(
                                android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)));
                        Long lts = res.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP);
                        r.put("logical_result_ts", lts == null ? JSONObject.NULL : lts);
                        JSONObject per = new JSONObject();
                        for (java.util.Map.Entry<String, android.hardware.camera2.CaptureResult> e
                                : res.getPhysicalCameraResults().entrySet()) {
                            JSONObject p = new JSONObject();
                            p.put("crop", String.valueOf(e.getValue().get(
                                    android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)));
                            p.put("focal_mm", e.getValue().get(
                                    android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH));
                            p.put("ts", e.getValue().get(
                                    android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP));
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
    private static byte[] toNv21(android.media.Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        android.media.Image.Plane[] p = image.getPlanes();
        byte[] out = new byte[w * h * 3 / 2];
        java.nio.ByteBuffer y = p[0].getBuffer();
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
        java.nio.ByteBuffer u = p[1].getBuffer();
        java.nio.ByteBuffer v = p[2].getBuffer();
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
            new android.graphics.YuvImage(nv21, ImageFormat.NV21, w, h, null)
                    .compressToJpeg(new android.graphics.Rect(0, 0, w, h), 92, s);
        } catch (Exception e) {
            Log.e(TAG, "could not write " + out + ": " + e);
        }
    }

    /** openCamera, but the device's later errors are visible to the caller. */
    private static CameraDevice openCameraTracked(
            CameraManager manager, String id, Handler handler,
            final java.util.concurrent.atomic.AtomicInteger errorOut) {
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

    private static CameraDevice openCamera(CameraManager manager, String id, Handler handler) {
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
