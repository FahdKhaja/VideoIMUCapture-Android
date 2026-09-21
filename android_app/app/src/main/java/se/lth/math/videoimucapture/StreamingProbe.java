package se.lth.math.videoimucapture;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Whether a configured layout actually STREAMS. See {@link #probeStreaming}. */
final class StreamingProbe {
    // One tag for the whole probe run, so one logcat filter follows it end to end.
    private static final String TAG = "StereoProbe";

    private StreamingProbe() {
    }

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
    static JSONArray probeStreaming(CameraManager manager, String logicalId,
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
        CameraCaptureSession[] sessionOut =
                new CameraCaptureSession[1];
        final AtomicInteger deviceError =
                new AtomicInteger(-1);
        CameraDevice device = null;
        long t0 = System.currentTimeMillis();
        try {
            r.put("bound", TextUtils.join("+", bound));
            r.put("requested", TextUtils.join("+", requested));
            device = ProbeCameras.openTracked(manager, logicalId, handler, deviceError);
            if (device == null) {
                r.put("verdict", "could not open device");
                return r;
            }
            final Map<String, Integer> frames = new LinkedHashMap<>();
            final CountDownLatch firstFromEach = new CountDownLatch(requested.size());
            List<OutputConfiguration> configs = new ArrayList<>();
            Map<String, ImageReader> readerFor = new LinkedHashMap<>();
            for (final String pid : bound) {
                ImageReader reader = ImageReader.newInstance(1920, 1080,
                        ImageFormat.YUV_420_888, 2);
                readers.add(reader);
                readerFor.put(pid, reader);
                frames.put(pid, 0);
                reader.setOnImageAvailableListener(rd -> {
                    try (Image img = rd.acquireLatestImage()) {
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
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(
                                @NonNull CameraCaptureSession s) {
                            sessionOut[0] = s;
                            configOk[0] = true;
                            configured.countDown();
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession s) {
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

            CaptureRequest.Builder b = device.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW, new HashSet<>(requested));
            for (String pid : requested) {
                b.addTarget(readerFor.get(pid).getSurface());
            }
            final String[] captureFailure = {null};
            sessionOut[0].setRepeatingRequest(b.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureFailed(
                                @NonNull CameraCaptureSession s,
                                @NonNull CaptureRequest req,
                                @NonNull CaptureFailure f) {
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
                for (Map.Entry<String, Integer> e : frames.entrySet()) {
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
}
