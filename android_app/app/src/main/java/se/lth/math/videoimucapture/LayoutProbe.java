package se.lth.math.videoimucapture;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Size;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which physical-lens stream LAYOUTS this device says it will configure -- pairs, triples and
 * quads, alone and beside the app's real preview + JPEG + RAW streams. Asked through
 * isSessionConfigurationSupported(), which answers without committing to a session, and
 * which {@link StreamingProbe} then showed is necessary and not sufficient.
 */
final class LayoutProbe {
    // One tag for the whole probe run, so one logcat filter follows it end to end.
    private static final String TAG = "StereoProbe";

    private LayoutProbe() {
    }

    /** Candidate stream sizes, largest first — a pair that fails big may still pass small. */
    private static final Size[] CANDIDATE_SIZES = {
            new Size(1920, 1080),
            new Size(1280, 720),
            new Size(640, 480),
    };

    static JSONObject probeLogical(CameraManager manager, String logicalId,
                                           CameraCharacteristics ch, List<String> physicals,
                                           Handler handler) throws Exception {
        JSONObject o = new JSONObject();
        o.put("logical_id", logicalId);
        o.put("physical_ids", new JSONArray(physicals));

        // Which request keys may be set PER PHYSICAL camera — if exposure/sensitivity are
        // here, the two streams can be driven independently, not just co-exposed.
        if (Build.VERSION.SDK_INT >= 28) {
            JSONArray keys = new JSONArray();
            for (CaptureRequest.Key<?> k
                    : ch.getAvailablePhysicalCameraRequestKeys()) {
                keys.put(k.getName());
            }
            o.put("available_physical_request_keys", keys);
        }

        CameraDevice device = ProbeCameras.open(manager, logicalId, handler);
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
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(
                                        @NonNull CameraCaptureSession s) {
                                }

                                @Override
                                public void onConfigureFailed(
                                        @NonNull CameraCaptureSession s) {
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
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(
                                    @NonNull CameraCaptureSession s) {
                            }

                            @Override
                            public void onConfigureFailed(
                                    @NonNull CameraCaptureSession s) {
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
}
