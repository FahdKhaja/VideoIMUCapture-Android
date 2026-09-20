package se.lth.math.videoimucapture;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A capture matrix the operator can shoot in one outing, one button per cell.
 *
 * WHY THIS EXISTS. Every capture-side question this fork has asked is a COMPARISON: manual
 * shutter against auto, portrait against landscape, OIS on against off. Answering one means two
 * clips that differ in exactly one thing, and until now the second variable was always the
 * operator's memory -- which settings were on, how long the walk was, whether the route matched.
 * The settings were being flipped over adb between clips, which means the phone had to be
 * tethered, which means the walk was whatever fits on a USB cable.
 *
 * So each step here carries its own settings, its own duration, and its own one-line instruction
 * for the half a test that software cannot set: how to hold the phone. The app applies the
 * settings, counts down, records for a FIXED time and stops itself. Two clips from this list
 * differ in what the list says they differ in, and their names say which cell they are.
 *
 * The steps are deliberately short and paired. A pair that cannot be walked back to back in one
 * outing is a pair that will be compared across two different days, two different lights and two
 * different routes, and that comparison answers nothing.
 */
public final class TestPlan {

    /**
     * Which buttons the cell presses, and in what order.
     *
     * Until now every cell pressed the record button and only the record button, so the whole
     * matrix could ask questions about video and no question at all about a stills run -- which
     * is precisely where the 2026-09-14 failure lived. A capture whose two start controls are
     * independent needs cells for the combinations, including the two orderings, because the
     * ordering is what decides which path opens the session and therefore what the directory
     * ends up called.
     */
    public enum Streams {
        /** Record button only: the classic cell. */
        VIDEO,
        /** Camera button only: a stills run, no video. */
        STILLS,
        /** Camera button, then record: the stills run owns the session and video joins it. */
        STILLS_THEN_VIDEO,
        /** Record button, then camera: video owns the session and stills join it. */
        VIDEO_THEN_STILLS,
        /** One press of the camera button in OBJECT, which fires the whole composite. */
        COMPOSITE
    }

    /** One cell of the matrix. */
    public static final class Step {
        public final String id;            // goes in the directory name: test<id>_...
        public final String title;         // what the button says
        public final String instruction;   // the part the app cannot set: how to hold, what to do
        public final int seconds;          // fixed, so two clips are the same length
        public final Map<String, Object> prefs;   // applied before recording, restored after
        public final Streams streams;      // which controls the cell presses
        public final CaptureModeManager.Mode mode;   // null leaves the operator's mode alone

        Step(String id, String title, String instruction, int seconds, Map<String, Object> prefs) {
            this(id, title, instruction, seconds, prefs, Streams.VIDEO, null);
        }

        Step(String id, String title, String instruction, int seconds, Map<String, Object> prefs,
             Streams streams, CaptureModeManager.Mode mode) {
            this.id = id;
            this.title = title;
            this.instruction = instruction;
            this.seconds = seconds;
            this.prefs = prefs;
            this.streams = streams;
            this.mode = mode;
        }

        /** Whether this cell is being ASKED FOR right now, as opposed to merely existing. */
        public boolean isRequested() {
            return REQUESTED.contains(id);
        }
    }

    /**
     * The cells currently being asked for: the standing request, in one place.
     *
     * The list grew to two dozen cells and became unusable, because everything in it was
     * equally present and nothing said what to go and shoot. Deleting the answered ones was
     * the wrong fix for that -- it cost the ability to reshoot a settled question, which is
     * exactly what you want when the code underneath it changes, and it still left no order
     * to what remained.
     *
     * So the ask lives here and the cells all stay. Everything on this list is something no
     * capture on this phone has answered yet; when one is shot it drops out of the operator's
     * view on its own.
     */
    private static final java.util.Set<String> REQUESTED = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "M1", "M2", "M3", "M4", "M5", "M6",     // the session says what it got
                    "L1",                                   // every rear lens, one instant
                    "G1", "G2",                             // measure the unpublished baselines
                    "I1", "I2",                             // IMU batching
                    "N1", "N2",                             // HAL sharpening and denoise
                    "W1", "W2",                             // distortion correction
                    "H1", "H2",                             // hyperfocal against autofocus
                    "O1", "O2"));                           // OIS where the motor has work

    // ------------------------------------------------------------------ what has been shot

    private static final String DONE_PREFIX = "cell_done_";

    /**
     * When a cell was last shot, as epoch millis, or 0 if never.
     *
     * Per device and persistent, because "have I done this one?" is a question about this
     * phone's history and not about the code. Cells that were shot before this existed are
     * seeded from what the issues record, so the list is honest the first time it is opened
     * rather than pretending a day of captures never happened.
     */
    public static long doneAt(SharedPreferences sp, String id) {
        seedHistory(sp);
        return sp.getLong(DONE_PREFIX + id, 0L);
    }

    public static boolean isDone(SharedPreferences sp, String id) {
        return doneAt(sp, id) > 0L;
    }

    public static void markDone(SharedPreferences sp, String id) {
        sp.edit().putLong(DONE_PREFIX + id, System.currentTimeMillis()).apply();
    }

    public static void markNotDone(SharedPreferences sp, String id) {
        sp.edit().remove(DONE_PREFIX + id).apply();
    }

    /**
     * The cells this phone had already shot before anything tracked it, with the dates the
     * ReconStab issues record. Written once.
     *
     * A, B, C, D: the 2026-09-03 matrix, 30 s each, preserved at matrix_20260903 (#47).
     * E: shot the same day; #44 has the clip awaiting analysis.
     * Z1, Z2: shot on a desk, and #48 is CLOSED on their data.
     * S1, S2: 2026-09-10, dark room; #36 carries the measurements.
     */
    private static void seedHistory(SharedPreferences sp) {
        if (sp.getBoolean("cell_history_seeded", false)) {
            return;
        }
        SharedPreferences.Editor ed = sp.edit();
        long sept3 = 1788480000000L;    // 2026-09-03
        long sept10 = 1789084800000L;   // 2026-09-10
        for (String id : new String[]{"A", "B", "C", "D", "E", "Z1", "Z2"}) {
            ed.putLong(DONE_PREFIX + id, sept3);
        }
        for (String id : new String[]{"S1", "S2"}) {
            ed.putLong(DONE_PREFIX + id, sept10);
        }
        ed.putBoolean("cell_history_seeded", true).apply();
    }

    /** The cells asked for and not yet shot: the to-do list. */
    public static List<Step> outstanding(SharedPreferences sp) {
        List<Step> out = new ArrayList<>();
        for (Step s : steps()) {
            if (s.isRequested() && !isDone(sp, s.id)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Everything shot on this phone, most recent first. */
    public static List<Step> completed(SharedPreferences sp) {
        List<Step> out = new ArrayList<>();
        for (Step s : steps()) {
            if (isDone(sp, s.id)) {
                out.add(s);
            }
        }
        java.util.Collections.sort(out,
                (a, b) -> Long.compare(doneAt(sp, b.id), doneAt(sp, a.id)));
        return out;
    }

    private static Map<String, Object> prefs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static final String WALK =
            "Walk the same route at the same pace as the other steps. A square around the room, "
                    + "rounded corners, is a good one: the straights and the corners give two "
                    + "different rotation rates in one clip.";

    /**
     * The matrix as of 2026-09-20. A cell earns its place by having a question nobody has
     * answered yet; when the question is settled the cell goes, because a list of buttons that
     * mostly do not need pressing is a list nobody reads.
     *
     * RETIRED 2026-09-20, with what settled them:
     *   Z1/Z2  zoom crop -- #48 is CLOSED on their data: the crop is reported and NOT applied.
     *   E      OIS on with the manual shutter -- shot, and #44 records the clip as still
     *          awaiting analysis. It was superseded as a CAPTURE by O1/O2, which ask the same
     *          question at auto exposure where the motor actually has something to remove; E
     *          was "underpowered by its own success".
     *
     * A, B, C, D stay although their 2026-09-03 data was shot and analysed (#47, preserved at
     * matrix_20260903). The manual shutter was fixed TWICE later the same day -- f8165c6, then
     * 1e4d60b -- so B and D describe a shutter that no longer exists, and A and C are their
     * controls. #72's half of the hold question also wants them graded with the matcher bench
     * rather than blur_census, which has not been done.
     */
    public static List<Step> steps() {
        List<Step> out = new ArrayList<>();

        out.add(new Step("A", "A - portrait, auto exposure",
                "Hold the phone UPRIGHT (portrait), camera roughly level.\n\n" + WALK
                        + "\n\nThis is the control: what the app did before today.",
                30, prefs("blur_budget_manual", false, "lock_radiometry", false,
                        "ois", false, "ois_data", false)));

        out.add(new Step("B", "B - portrait, manual shutter",
                "Hold the phone UPRIGHT (portrait), camera roughly level.\n\n" + WALK
                        + "\n\nSame as A with the shutter capped from the gyro. Against A this "
                        + "says what the manual shutter costs and buys.",
                30, prefs("blur_budget_manual", true, "lock_radiometry", false,
                        "ois", false, "ois_data", false)));

        out.add(new Step("C", "C - landscape, auto exposure",
                "Hold the phone SIDEWAYS (landscape), camera roughly level.\n\n" + WALK
                        + "\n\nAgainst A: does the hold change anything when the shutter does not?",
                30, prefs("blur_budget_manual", false, "lock_radiometry", false,
                        "ois", false, "ois_data", false)));

        out.add(new Step("D", "D - landscape, manual shutter",
                "Hold the phone SIDEWAYS (landscape), camera roughly level.\n\n" + WALK
                        + "\n\nThe fourth corner of the square. B vs D is the rolling-shutter "
                        + "question (#47): the readout direction turns with the phone, the "
                        + "panning does not.",
                30, prefs("blur_budget_manual", true, "lock_radiometry", false,
                        "ois", false, "ois_data", false)));

        // RESTORED 2026-09-20. These were deleted to shorten the list, which was the wrong
        // fix for the wrong problem: the list was unreadable because nothing said what to
        // shoot, not because it was long. Deleting them cost the ability to reshoot a
        // settled question -- and a settled question is exactly what you want to reshoot
        // when the code underneath it changes. They live in the All view, out of the way.
        out.add(new Step("E", "E - portrait, manual shutter, OIS ON",
                "Hold the phone UPRIGHT (portrait), camera roughly level.\n\n" + WALK
                        + "\n\nOIS on. The file already records what the HAL SAYS about OIS; "
                        + "against B this says what the lens actually DID, by comparing image "
                        + "motion with the gyro that should predict it.\n\nShot 2026-09-03; "
                        + "#44 has the clip awaiting analysis, and O1/O2 ask the same question "
                        + "where the motor has more to do.",
                30, prefs("blur_budget_manual", true, "lock_radiometry", false,
                        "ois", true, "ois_data", true)));

        String zoomShot = "Point the phone at something with detail across the WHOLE frame — a "
                + "bookshelf, a cluttered bench, a brick wall — from about two metres.\n\nHold as "
                + "still as you can and DO NOT MOVE BETWEEN Z1 AND Z2. Shoot them back to back "
                + "from the same spot; if the phone moves, the pair is worthless.";
        out.add(new Step("Z1", "Z1 - zoom check, ratio 1.0",
                zoomShot + "\n\nThis one at zoom 1.0 — the full sensor field.\n\nAnswered: #48 "
                        + "is closed on this pair. The crop is reported and NOT applied.",
                12, prefs("zoom_ratio", 1.0f, "blur_budget_manual", false,
                        "lock_radiometry", false, "ois", false, "ois_data", false)));
        out.add(new Step("Z2", "Z2 - zoom check, ratio 0.6",
                zoomShot + "\n\nThis one at zoom 0.6 — your usual setting. If it looks TIGHTER "
                        + "than Z1, the crop is real.\n\nAnswered: #48 is closed on this pair.",
                12, prefs("zoom_ratio", 0.6f, "blur_budget_manual", false,
                        "lock_radiometry", false, "ois", false, "ois_data", false)));

        out.add(new Step("F", "F - exposure keys, standing still",
                "Stand still, phone UPRIGHT, pointed at something with both bright and dark in "
                        + "it.\n\nPress VOLUME UP three times at about 5 seconds, then VOLUME "
                        + "DOWN six times at about 15 seconds.\n\nThe readout should show EV and "
                        + "the system volume bar should NOT appear. No walking: the point is the "
                        + "exposure steps, and motion would hide them.",
                25, prefs("blur_budget_manual", false, "lock_radiometry", false,
                        "ois", false, "ois_data", false)));

        // O1/O2 are a PAIR, and they exist because the first attempt at the OIS question was
        // underpowered by its own success. It compared cells B and E, both with the manual
        // shutter running -- which had already driven blur to about 1 px, leaving the motor
        // almost nothing to remove. The measured effect was in the right direction (21% less
        // sensitive to camera rotation) at P = 80%, which is a hint, not an answer.
        //
        // AUTO exposure is where OIS has something to do: 16.67 ms shutter, 3-5 px of blur, the
        // condition the motor was built for. Same route, back to back, one variable.
        String oisShot = "Walk the same route at the same pace as the other cell, holding the "
                + "phone UPRIGHT.\n\nShoot O1 and O2 back to back so the light does not move "
                + "between them.";
        out.add(new Step("O1", "O1 - OIS off, auto exposure",
                oisShot + "\n\nOIS off, auto exposure. The control.",
                30, prefs("ois", false, "ois_data", false, "blur_budget_manual", false,
                        "lock_radiometry", false)));
        out.add(new Step("O2", "O2 - OIS ON, auto exposure",
                oisShot + "\n\nOIS on. This is the one that should look nicer to the eye — and "
                        + "the question is whether it also measures sharper without costing the "
                        + "solve anything.",
                30, prefs("ois", true, "ois_data", true, "blur_budget_manual", false,
                        "lock_radiometry", false)));

        // S1/S2 are a PAIR: does keeping a two-lens stereo pair every second (ReconStab #36)
        // cost the video anything? While pairs are on, both physical streams sit in the
        // recording's own repeating request for the whole clip. That is the one thing S2 does
        // that S1 does not, and the file records everything that could show it: the frame
        // table (holes), per-frame exposure and ISO (a perturbed AE), frame duration (a rate
        // the HAL quietly lowered), and thermal. S2 also answers the pair's own questions --
        // both files present, same stamp on both lenses, the ultrawide wider than the main.
        String stereoShot = "Walk the same route at the same pace as the other cell, phone "
                + "UPRIGHT, over ground with texture at one to three metres -- the rock, not "
                + "the horizon. Shoot S1 and S2 back to back.";
        out.add(new Step("S1", "S1 - video, no stereo pairs",
                stereoShot + "\n\nPairs off. The control.",
                30, prefs("stereo_interval_s", 0, "blur_budget_manual", false,
                        "lock_radiometry", false, "ois", false, "ois_data", false)));
        out.add(new Step("S2", "S2 - video, stereo pair every second",
                stereoShot + "\n\nPairs on at 1 Hz. The readout should show PAIRS counting "
                        + "up. Against S1 this says what the second sensor costs the video.",
                30, prefs("stereo_interval_s", 1, "blur_budget_manual", false,
                        "lock_radiometry", false, "ois", false, "ois_data", false)));

        // M1..M6 are the manifest matrix, and they are the acceptance test for the thing the
        // 2026-09-14 column orbit exposed: two independent start controls and a session that
        // could not say which of them it got. Four sessions in the archive have no video and
        // never said so.
        //
        // Every cell here ends with a session.json, and the cell PASSES when that file's
        // "agrees" is true -- expected and measured halves in step -- and its "summary" line
        // describes the cell you actually shot. Nothing needs to be solved and nothing needs
        // to be looked at on a computer; the phone's own roll shows the kind it MEASURED, so
        // a failure is visible before leaving the site, which is the entire point.
        //
        // M3 and M4 are the same two streams in the two orders, and they exist because the
        // order decides which path opens the directory and therefore what it is named. M4 is
        // the case that produced "walk_" directories legitimately full of video, which is why
        // the name has never been a safe answer to "what is in here?".
        String manifestShot = "Point at anything with texture and walk a few steps. The picture "
                + "does not matter for this cell -- what is being tested is whether the session "
                + "can say what it recorded.\n\nAfterwards, open the roll and read the line "
                + "under the session.";
        out.add(new Step("M1", "M1 - WALK, stills only",
                manifestShot + "\n\nStills only: no video is started at all. This is the shape "
                        + "of the 2026-09-14 session. PASS = the roll says stills run, the "
                        + "manifest agrees, and there is at least one stereo pair.",
                20, prefs("stereo_interval_s", 0, "blur_budget_manual", false,
                        "lock_radiometry", false),
                Streams.STILLS, CaptureModeManager.Mode.WALK));
        out.add(new Step("M2", "M2 - WALK, video only",
                manifestShot + "\n\nVideo only: the camera button is never pressed. PASS = the "
                        + "roll says video and the manifest measures an mp4.\n\nWATCH THE "
                        + "CAPTURE BUTTON while this records. It must stay on its STILL icon, "
                        + "not turn into a stop button: in a video-only clip pressing it starts "
                        + "a stills run, and it wore a stop icon until 2026-09-20. The mode "
                        + "strip should be dimmed, and the screen should not time out.",
                20, prefs("stereo_interval_s", 0, "blur_budget_manual", false,
                        "lock_radiometry", false),
                Streams.VIDEO, CaptureModeManager.Mode.WALK));
        out.add(new Step("M3", "M3 - WALK, stills then video",
                manifestShot + "\n\nThe camera button first, then record a few seconds later. "
                        + "The stills run owns the session and the video joins it, so the "
                        + "directory is named walk_ and CONTAINS VIDEO. PASS = the roll says "
                        + "video + stills despite the name.",
                20, prefs("stereo_interval_s", 0, "blur_budget_manual", false,
                        "lock_radiometry", false),
                Streams.STILLS_THEN_VIDEO, CaptureModeManager.Mode.WALK));
        out.add(new Step("M4", "M4 - WALK, video then stills",
                manifestShot + "\n\nRecord first, then the camera button. Video owns the "
                        + "session, so the directory is named walk_vid_. PASS = the roll says "
                        + "video + stills and the manifest counts both.",
                20, prefs("stereo_interval_s", 0, "blur_budget_manual", false,
                        "lock_radiometry", false),
                Streams.VIDEO_THEN_STILLS, CaptureModeManager.Mode.WALK));
        out.add(new Step("M5", "M5 - OBJECT composite",
                "Put the phone on something steady, pointed at a small object about half a "
                        + "metre away, and do not touch it.\n\nOne press fires the whole "
                        + "composite: focus stack, bracket, RAW, stereo pair. PASS = the "
                        + "manifest counts a complete stereo pair.\n\nTRY TO PRESS THE CAPTURE "
                        + "BUTTON AGAIN while it runs. It should be dimmed and do nothing: two "
                        + "presses used to start two overlapping composites, writing two "
                        + "directories and driving the camera twice at once.",
                25, prefs("stereo_interval_s", 0, "lock_radiometry", true),
                Streams.COMPOSITE, CaptureModeManager.Mode.OBJECT));
        out.add(new Step("M6", "M6 - PANO, stills only",
                "Tripod or gimbal if you have one, otherwise pivot on the spot in steps, "
                        + "pausing at each.\n\nPANO brackets at each quiet moment. PASS = the "
                        + "manifest's stills count matches what the roll shows.",
                25, prefs("stereo_interval_s", 0, "lock_radiometry", true),
                Streams.STILLS, CaptureModeManager.Mode.PANO));

        // N1/N2 are a PAIR (ReconStab #55): what does the HAL's own picture processing cost a
        // matcher? Neither EDGE_MODE nor NOISE_REDUCTION_MODE has ever been set in this fork,
        // so N1 is not a control in the usual sense -- it is the archive. Every clip this
        // project has ever solved was shot the way N1 is shot.
        //
        // The comparison is made downstream on identical scenes: feature count, match count and
        // the spread of reprojection error. What makes it decidable is that both clips now
        // record edge_mode and noise_reduction_mode per frame, so N1 also answers a question
        // nothing has asked yet -- what the vendor default actually IS.
        String pixelShot = "Point at something with fine, low-contrast texture at one to three "
                + "metres -- wet rock, gravel, brushed metal, fabric. NOT a high-contrast edge: "
                + "the argument is about texture a denoiser eats and halos a sharpener "
                + "invents.\n\nStand still. Shoot N1 and N2 back to back from the same spot in "
                + "the same light.";
        out.add(new Step("N1", "N1 - HAL processing as shipped",
                pixelShot + "\n\nEdge and noise reduction left at the vendor default, which is "
                        + "how every clip in the archive was shot.",
                20, prefs("raw_pixels", false, "lock_radiometry", true,
                        "ois", false, "ois_data", false)));
        out.add(new Step("N2", "N2 - raw pixels, no sharpening or denoise",
                pixelShot + "\n\nBoth turned OFF. Against N1 this says what the HAL was doing "
                        + "to every solve frame this project has produced.",
                20, prefs("raw_pixels", true, "lock_radiometry", true,
                        "ois", false, "ois_data", false)));

        // H1/H2 (ReconStab #61): does the focal length hold still while autofocus is running?
        //
        // Every walk this project has shot was shot with AF live, and a solve that freezes
        // intrinsics is asserting a constant nobody has checked. The check is free and comes
        // FIRST: plot lens_intrinsic_calibration[0] across H1 and count frames whose lens_state
        // says the lens was moving when the shutter opened. H2 is what the answer looks like if
        // the plot is flat -- a lens parked at hyperfocal and never touched again -- so the two
        // together say both how much it moves and whether parking it fixes anything.
        String focusShot = "Walk the same short route twice, phone UPRIGHT, past things at "
                + "DIFFERENT distances -- something close on one side and something far on the "
                + "other. A corridor with a doorway works. The point is to give autofocus "
                + "something to hunt for in H1.";
        out.add(new Step("H1", "H1 - autofocus live (the archive)",
                focusShot + "\n\nAF running, as every walk so far.",
                30, prefs("focus_mode", "TOUCH_AUTO", "focus_hyperfocal", false,
                        "lock_radiometry", true, "ois", false, "ois_data", false)));
        out.add(new Step("H2", "H2 - parked at hyperfocal",
                focusShot + "\n\nManual focus at the computed hyperfocal distance; the voice "
                        + "coil should not move once. Against H1 the focal should be a flat "
                        + "line and no frame should report a moving lens.",
                30, prefs("focus_mode", "MANUAL", "focus_hyperfocal", true,
                        "lock_radiometry", true, "ois", false, "ois_data", false)));

        // L1: one instant, every rear lens.
        //
        // The probe on this handset says 2+5+6+7 configure together, and that preview + JPEG +
        // RAW + four physical streams at 1920x1080 is a supported combination -- seven streams
        // in one session. Until now the app only ever asked for two of them, because the only
        // question anyone had put to two lenses at once was the 18.02 mm baseline.
        //
        // What the extra two are NOT is more scale. Ids 6 and 7 report LENS_POSE_TRANSLATION
        // [0, 0, 0], so the device declines to say where they sit; only 2 and 5 have a
        // published separation. What they are is the same instant at 7.9 mm and 18.6 mm
        // beside it, which is a different thing to have and worth having on purpose.
        //
        // THE LENS SET IS A SESSION SETTING. Streams are bound when the capture session is
        // created and there is no adding one to a live session, so this cell cannot set it
        // the way the others set theirs -- it has to be in place before the camera opens.
        out.add(new Step("L1", "L1 - every rear lens, one instant",
                "SET THIS FIRST: Settings > Lenses in the session > All rear lenses, then "
                        + "leave the app and come back so the camera reopens. The cell cannot "
                        + "do it for you: the streams are fixed when the session is built.\n\n"
                        + "Then put the phone on something steady, pointed at a scene with "
                        + "detail at several distances, and do not touch it.\n\nPASS = four "
                        + "stereo_ files sharing one burst id — uw, main, phys6, phys7 — and "
                        + "lenses_in_widest_capture = 4 in the manifest. Fewer means a lens "
                        + "was configured and did not deliver, which is the finding.",
                20, prefs("stereo_interval_s", 0, "lock_radiometry", true,
                        "blur_budget_manual", false),
                Streams.STILLS, CaptureModeManager.Mode.WALK));

        // G1/G2 are a PAIR and they MEASURE THE BASELINES THE DEVICE WILL NOT STATE
        // (ReconStab #9). Ids 6 and 7 report LENS_POSE_TRANSLATION [0, 0, 0]; only the
        // ultrawide publishes an offset. But the phone already carries its own ruler -- the
        // 18.02 mm between 2 and 5 is published -- and every lens fires at the same instant,
        // so the rest can be measured against it rather than taken on faith.
        //
        // For a flat target at distance Z, disparity = f * B / Z with f known for every lens
        // from the census. One distance gives each B directly. TWO distances give it as the
        // slope of disparity against 1/Z, which is the version worth shooting: a line through
        // the origin says the model holds, and a line that misses the origin says a focal
        // length or a principal point is wrong and the single-distance answer would have
        // absorbed that error silently.
        //
        // 1 m and 2 m because the numbers work there and every lens can focus: at 1 m the
        // known pair should show ~22 px on the main camera and the periscope far more, both
        // comfortably measurable; much closer and the telephotos will not focus, much further
        // and the disparity disappears into the noise.
        //
        // OIS OFF IS NOT OPTIONAL HERE. A stabiliser moves the optical path between frames,
        // which is exactly the quantity being measured. Distortion correction off for the
        // same reason: the geometry has to be the lens's own.
        String baselineShot = "SET THIS FIRST: Settings > Lenses in the session > All rear "
                + "lenses, then leave the app and come back so the camera reopens.\n\nPut the "
                + "phone on a tripod or wedge it against something solid, pointed square at a "
                + "FLAT textured target — a newspaper, a brick wall, a poster with fine "
                + "detail. Square on, not angled. The target must fill the 5x view, so it "
                + "needs detail right in the middle of the frame.\n\nMeasure the distance with "
                + "a tape and write it down; the measurement is only as good as that number.";
        out.add(new Step("G1", "G1 - baselines, target at 1 m",
                baselineShot + "\n\nTarget at ONE METRE.",
                15, prefs("stereo_interval_s", 0, "lock_radiometry", true,
                        "blur_budget_manual", false, "ois", false, "ois_data", false,
                        "distortion_correction", false),
                Streams.STILLS, CaptureModeManager.Mode.WALK));
        out.add(new Step("G2", "G2 - baselines, target at 2 m",
                baselineShot + "\n\nTarget at TWO METRES, same target, same phone position "
                        + "otherwise. G1 and G2 together turn each baseline into the slope of "
                        + "a line rather than one number that has to be trusted.",
                15, prefs("stereo_interval_s", 0, "lock_radiometry", true,
                        "blur_budget_manual", false, "ois", false, "ois_data", false,
                        "distortion_correction", false),
                Streams.STILLS, CaptureModeManager.Mode.WALK));

        // I1/I2 (ReconStab #63): what does batching the IMU cost, and what does it buy?
        //
        // Every listener used the three-argument registerListener until 2026-09-20, so the
        // framework woke the CPU per event -- ~470 times a second for the gyro and as many for
        // the accelerometer, while the GPU encodes. Batching delays delivery without moving a
        // sample in time, so for a recorder it should be free.
        //
        // "Should be" is the reason this pair exists. Both clips record batch_latency_us and
        // the FIFO sizes, so the comparison is decidable from the files: histogram the
        // inter-sample intervals in each, against the thermal stream in the same file. I1 says
        // what an unbatched stream looks like on this device, which nobody has actually
        // plotted; I2 says whether 100 ms of batching changes the sample timing, the gaps, or
        // the heat.
        //
        // Video, not stills, on purpose: batching is NOT free for the stillness shutter, which
        // reads the stream live to decide when a walk fires a still. This pair deliberately
        // does not exercise that, because a still fired 100 ms late is a different experiment
        // and would confound this one.
        String imuShot = "Walk the same route at the same pace for both, phone UPRIGHT. The "
                + "scene does not matter -- this pair is about the IMU stream, not the "
                + "picture.\n\nShoot I1 and I2 back to back so the phone is at a similar "
                + "temperature for both.";
        out.add(new Step("I1", "I1 - IMU unbatched (the archive)",
                imuShot + "\n\nDelivery one sample at a time, as every clip before today.",
                30, prefs("imu_batch_ms", 0, "blur_budget_manual", false,
                        "lock_radiometry", true, "ois", false, "ois_data", false)));
        out.add(new Step("I2", "I2 - IMU batched at 100 ms",
                imuShot + "\n\nThe gyro, accelerometer and magnetometer buffer in their own "
                        + "FIFO. The readout should still show IMU at full rate: batching "
                        + "changes when samples ARRIVE, not how many there are. If the rate "
                        + "drops, that is the finding.",
                30, prefs("imu_batch_ms", 100, "blur_budget_manual", false,
                        "lock_radiometry", true, "ois", false, "ois_data", false)));

        // W1/W2 (ReconStab #58): does the HAL hand back frames it has already un-warped? The
        // coefficients recorded in every session describe the RAW sensor, so if correction ran,
        // a solve using them corrects twice -- and both that and the opposite error look like a
        // slightly-worse-than-expected reprojection, which is not a signature worth trusting.
        //
        // Decidable from the two clips alone, before any solve: put something straight near the
        // frame edge and see whether it bends differently between them. If the two look
        // identical, the HAL ignored the request, and that is the finding.
        String distortionShot = "Point at a STRAIGHT edge running near the frame border -- a "
                + "door frame, a window, the join of a wall and ceiling -- from about two "
                + "metres, with the line as close to the edge of the picture as you can get it. "
                + "Barrel distortion is invisible in the middle of the frame.\n\nDo not move "
                + "between W1 and W2.";
        out.add(new Step("W1", "W1 - distortion correction OFF",
                distortionShot + "\n\nCorrection off: the lens as it is, matching the k1..k5 "
                        + "the file records. This is the setting the app defaults to.",
                12, prefs("distortion_correction", false, "lock_radiometry", true,
                        "ois", false, "ois_data", false)));
        out.add(new Step("W2", "W2 - distortion correction ON",
                distortionShot + "\n\nCorrection on. If that straight line is straighter here "
                        + "than in W1, the HAL is un-warping and the recorded coefficients no "
                        + "longer describe the picture.",
                12, prefs("distortion_correction", true, "lock_radiometry", true,
                        "ois", false, "ois_data", false)));

        return out;
    }

    /** Apply a step's settings, returning the previous values so they can be put back. */
    public static Map<String, Object> apply(SharedPreferences sp, Step step) {
        Map<String, Object> previous = new LinkedHashMap<>();

        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, Object> e : step.prefs.entrySet()) {
            Object want = e.getValue();
            if (want instanceof Boolean) {
                previous.put(e.getKey(), sp.getBoolean(e.getKey(), false));
                ed.putBoolean(e.getKey(), (Boolean) want);
            } else if (want instanceof Integer) {
                previous.put(e.getKey(), sp.getInt(e.getKey(), 0));
                ed.putInt(e.getKey(), (Integer) want);
            } else if (want instanceof Float) {
                previous.put(e.getKey(), sp.getFloat(e.getKey(), 1.0f));
                ed.putFloat(e.getKey(), (Float) want);
            } else if (want instanceof String) {
                previous.put(e.getKey(), sp.getString(e.getKey(), ""));
                ed.putString(e.getKey(), (String) want);
            }
        }
        ed.apply();
        return previous;
    }

    /** Put back what {@link #apply} displaced. The operator's own settings are not ours to keep. */
    public static void restore(SharedPreferences sp, Map<String, Object> previous) {
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, Object> e : previous.entrySet()) {
            Object was = e.getValue();
            if (was instanceof Boolean) {
                ed.putBoolean(e.getKey(), (Boolean) was);
            } else if (was instanceof Integer) {
                ed.putInt(e.getKey(), (Integer) was);
            } else if (was instanceof Float) {
                ed.putFloat(e.getKey(), (Float) was);
            } else if (was instanceof String) {
                ed.putString(e.getKey(), (String) was);
            }
        }
        ed.apply();
    }

    private TestPlan() {
    }
}
