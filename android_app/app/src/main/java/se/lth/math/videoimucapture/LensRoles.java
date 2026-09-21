package se.lth.math.videoimucapture;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which physical lens plays which part: the metric pair, the lenses with a stream in the
 * current session, what each one's files are called, and the order pairs are shot in.
 *
 * Process-wide rather than per session, because the receipt, the test cells and the capture
 * path all ask the same questions and must get the same answers, and because the roles are a
 * fact about the phone rather than about any one session on it.
 */
public final class LensRoles {
    private static final String TAG = "LensRoles";

    private LensRoles() {
    }

    /**
     * The pair with a published baseline: on this device the ultrawide (physical 2) sits
     * LENS_POSE_TRANSLATION = 18.02 mm from the main camera (physical 5), and both carry
     * factory intrinsics. A SIMULTANEOUS pair across a known baseline is metric scale
     * from a single capture — the quantity a monocular walk cannot produce without
     * external control, and the reason this stage exists at all.
     *
     * DERIVED, not assumed (ReconStab #10). "2" and "5" were the last hardcoded device facts
     * in the capture path; everything else is read from the census. They are now the fallback
     * for a device that will not answer, and the roles are worked out from what the physical
     * cameras publish about themselves: the reference lens is the one whose pose translation
     * is the origin, and the ultrawide is the shortest focal length beside it.
     *
     * The chosen ids are recorded per stereo half, and the census carries every physical
     * camera's LENS_POSE_TRANSLATION, so the baseline is recoverable from the file rather
     * than being a constant a reader has to already know.
     */
    private static volatile String sPhysUltrawide = "2";
    private static volatile String sPhysMain = "5";

    /** The wide-baseline half of the pair (shortest focal). */
    public static String physUltrawide() {
        return sPhysUltrawide;
    }

    /** The reference half of the pair (pose translation at the origin). */
    public static String physMain() {
        return sPhysMain;
    }

    /** The two lenses with a published baseline, as the id set a request builder is made for. */
    public static Set<String> stereoPhysicalIds() {
        return new HashSet<>(Arrays.asList(sPhysUltrawide, sPhysMain));
    }

    /** Every physical lens with a stream in the current session, in configuration order. */
    private static volatile List<String> sActiveLensIds = Collections.emptyList();

    public static List<String> activeLensIds() {
        return sActiveLensIds;
    }

    static void setActiveLensIds(List<String> ids) {
        sActiveLensIds = new ArrayList<>(ids);
    }

    /**
     * Whether the NEXT session configures every physical lens or only the metric pair.
     *
     * Set by the proxy from the preference just before the session's readers are built,
     * because streams are bound at createCaptureSession and there is no adding one to a live
     * session. Reading the preference where the readers are built instead would need a Context
     * that code does not have and would not change when it takes effect: the camera has to be
     * reopened either way.
     */
    private static volatile boolean sAllLensShot = false;

    public static void setAllLensShot(boolean all) {
        sAllLensShot = all;
    }

    public static boolean allLensShot() {
        return sAllLensShot;
    }

    /**
     * What a lens's images are called on disk.
     *
     * The metric pair keeps "uw" and "main" so that every reader, grader and parser written
     * against the archive still works, and the extra lenses are named for their physical id
     * rather than for a role -- "tele" would be a guess about what the device is, while the
     * id is what the device said.
     */
    static String lensTag(String physicalId) {
        if (physicalId.equals(sPhysMain)) {
            return "main";
        }
        if (physicalId.equals(sPhysUltrawide)) {
            return "uw";
        }
        return "phys" + physicalId;
    }

    // ON THIS PHONE A SIMULTANEOUS CAPTURE IS A PAIR. Asking four physical outputs on one
    // request kills the camera: the S24 Ultra HAL logs "More than 2 real time pipeline
    // request How to handle? numOfRealtimePipelines = 4", cancels the frame and raises
    // ERROR_CAMERA_DEVICE. Measured 2026-09-20, twice, the second time under control. What
    // the streaming probe then showed is that a session BOUND with all four lenses serves
    // any pair on request, half a second each, from one session -- so the all-lens shot is
    // not one instant from four sensors, it is six instants from two sensors each, and
    // against a static target that measures every baseline just the same.

    /**
     * Every pair of the given lenses, the published metric pair first.
     *
     * Order is the point: (uw, main) carries the only offset the device states, 18.02 mm,
     * and every other pair is measured against it. Pairs with the ultrawide come next, then
     * pairs with the main camera, then the rest -- so if the sequence is cut short, what
     * survives is the most useful part of it.
     */
    public static List<String[]> lensPairs(List<String> ids, String uw, String main) {
        List<String[]> out = new ArrayList<>();
        if (ids.contains(uw) && ids.contains(main)) {
            out.add(new String[]{uw, main});
        }
        for (String anchor : new String[]{uw, main}) {
            if (!ids.contains(anchor)) {
                continue;
            }
            for (String id : ids) {
                if (!id.equals(uw) && !id.equals(main)) {
                    out.add(new String[]{anchor, id});
                }
            }
        }
        List<String> rest = new ArrayList<>();
        for (String id : ids) {
            if (!id.equals(uw) && !id.equals(main)) {
                rest.add(id);
            }
        }
        for (int i = 0; i < rest.size(); i++) {
            for (int j = i + 1; j < rest.size(); j++) {
                out.add(new String[]{rest.get(i), rest.get(j)});
            }
        }
        return out;
    }

    /**
     * Work out which two physical cameras are the pair, from what they publish (ReconStab #10).
     *
     * The rule follows the geometry rather than the device. LENS_POSE_TRANSLATION is given in
     * the logical camera's own frame, so the lens at the ORIGIN is the reference the logical
     * camera is built around -- the main. Of the others, the one with the shortest focal length
     * is the ultrawide, which is also the one that will be furthest from the main and therefore
     * the longest baseline on offer.
     *
     * A lens that publishes no pose translation is not a candidate at any focal length: without
     * it there is no baseline, and a pair without a baseline is two pictures rather than a
     * measurement.
     *
     * If the device will not answer, the S24U's own ids stand. Nothing here changes what this
     * phone does -- it should resolve to exactly 2 and 5, and the log says so on every start so
     * that a device which resolves differently says so out loud rather than quietly shooting a
     * different pair.
     *
     * @param logical the LOGICAL camera's characteristics, whose focal length breaks the tie
     */
    static void resolve(CameraManager cameraManager, CameraCharacteristics logical,
                        Set<String> physicals) {
        if (physicals == null || physicals.size() < 2) {
            return;
        }
        String reference = null;
        String widest = null;
        float widestFocal = Float.MAX_VALUE;
        double referenceOffset = Double.MAX_VALUE;
        Map<String, Float> focals = new LinkedHashMap<>();
        Map<String, Double> offsets = new LinkedHashMap<>();

        for (String id : physicals) {
            try {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                float[] translation = ch.get(CameraCharacteristics.LENS_POSE_TRANSLATION);
                float[] focalLengths =
                        ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (translation == null || translation.length < 3
                        || focalLengths == null || focalLengths.length == 0) {
                    continue;
                }
                double offset = Math.sqrt(translation[0] * translation[0]
                        + translation[1] * translation[1] + translation[2] * translation[2]);
                focals.put(id, focalLengths[0]);
                offsets.put(id, offset);
            } catch (CameraAccessException | IllegalArgumentException e) {
                Log.w(TAG, "could not read physical camera " + id + ": " + e);
            }
        }

        // THE ORIGIN IS NOT UNIQUE, which the first version of this method assumed.
        //
        // Measured on the SM-S928U 2026-09-20: logical camera 0 wraps physicals {2, 5, 6, 7},
        // and THREE of them -- 5, 6 and 7 -- report LENS_POSE_TRANSLATION [0, 0, 0]. Only the
        // ultrawide, id 2, publishes an offset at all ([0, 0.01802, 0]). So "the lens at the
        // origin is the reference" does not identify one lens; it identifies three, and
        // getPhysicalCameraIds() returns a Set, whose iteration order is not specified. The
        // main camera was being chosen by whichever zero the set happened to yield first.
        //
        // Picking 6 or 7 would not have failed loudly. It would have paired the ultrawide
        // with a telephoto, called the result a stereo pair, and left every downstream reader
        // applying an 18.02 mm baseline to two lenses whose true separation is unpublished --
        // the metric anchor anchoring to nothing.
        //
        // The tie is broken on focal length instead, and the LOGICAL camera settles it: its
        // own reported focal IS the reference lens's, because the logical camera's default
        // field of view is that lens's field of view. On this device the logical camera says
        // 6.3 mm, which is id 5 exactly, against 7.9 and 18.6 for the telephotos.
        float logicalFocal = 0f;
        float[] logicalFocals =
                logical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (logicalFocals != null && logicalFocals.length > 0) {
            logicalFocal = logicalFocals[0];
        }
        double bestFocalGap = Double.MAX_VALUE;
        for (Map.Entry<String, Double> e : offsets.entrySet()) {
            double offset = e.getValue();
            if (offset > referenceOffset) {
                continue;
            }
            double focalGap = logicalFocal > 0
                    ? Math.abs(focals.get(e.getKey()) - logicalFocal) : 0;
            if (offset < referenceOffset || focalGap < bestFocalGap) {
                referenceOffset = offset;
                bestFocalGap = focalGap;
                reference = e.getKey();
            }
        }
        for (Map.Entry<String, Float> e : focals.entrySet()) {
            if (e.getKey().equals(reference)) {
                continue;
            }
            if (e.getValue() < widestFocal) {
                widestFocal = e.getValue();
                widest = e.getKey();
            }
        }
        if (reference == null || widest == null) {
            Log.i(TAG, "lens roles could not be derived (physicals " + physicals
                    + "); keeping " + sPhysUltrawide + " + " + sPhysMain);
            return;
        }
        boolean changed = !reference.equals(sPhysMain) || !widest.equals(sPhysUltrawide);
        sPhysMain = reference;
        sPhysUltrawide = widest;
        Log.i(TAG, String.format(Locale.US,
                "lens roles derived: main=%s (focal %.2f mm, offset %.2f mm), "
                        + "ultrawide=%s (focal %.2f mm, offset %.2f mm), "
                        + "logical focal %.2f mm, candidates %s%s",
                reference, focals.get(reference), offsets.get(reference) * 1000.0,
                widest, focals.get(widest), offsets.get(widest) * 1000.0,
                logicalFocal, focals.keySet(),
                changed ? "  -- DIFFERENT from the hardcoded pair" : ""));
    }
}
