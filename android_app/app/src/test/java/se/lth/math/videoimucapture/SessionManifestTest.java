package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The manifest's whole claim is that it describes what is on disk rather than what the capture
 * path believed. So these tests build directories on disk and read the manifest back.
 *
 * The case that matters most is the 2026-09-14 one: stills present, video absent, the capture
 * path convinced it started a video. A manifest that says "agrees" there would be worse than no
 * manifest at all, because it would be a receipt confirming a session that does not exist.
 */
public class SessionManifestTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    /** Context is null on purpose: appVersion catches it and the receipt says "unknown". */
    private static SessionManifest manifest(File dir, String mode) {
        return new SessionManifest(null, dir, mode, null);
    }

    private static void touch(File dir, String name, int bytes) throws Exception {
        File f = new File(dir, name);
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(new byte[bytes]);
        }
    }

    private static JSONObject read(File dir) throws Exception {
        String text = new String(
                Files.readAllBytes(new File(dir, SessionManifest.FILENAME).toPath()),
                StandardCharsets.UTF_8);
        return new JSONObject(text);
    }

    @Test
    public void stillsWithoutVideo_isReportedAndDisagrees() throws Exception {
        File dir = mFolder.newFolder("walk_2026_09_14_14_04_55");
        for (int i = 0; i < 60; i++) {
            touch(dir, "still_" + i + "_00.jpg", 16);
        }
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteVideoRequested();          // the operator pressed record; nothing came of it
        m.noteStillsFired(60);
        m.write(null);

        JSONObject root = read(dir);
        assertEquals(60, root.getJSONObject("measured").getInt("stills_jpg"));
        assertFalse(root.getJSONObject("measured").getBoolean("video"));
        assertFalse("video was requested and did not land", root.getBoolean("agrees"));
        assertTrue(root.getString("summary").contains("NO VIDEO"));
        assertTrue(root.getString("summary").contains("left no file"));
    }

    @Test
    public void aStillsRunWithNoPair_saysItHasNoScale() throws Exception {
        File dir = mFolder.newFolder("walk_no_pair");
        touch(dir, "still_1_00.jpg", 16);
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.write(null);

        assertTrue(read(dir).getString("summary").contains("NO METRIC SCALE"));
    }

    @Test
    public void bothHalvesOfABurstCountAsOnePair() throws Exception {
        File dir = mFolder.newFolder("walk_pairs");
        touch(dir, "stereo_7_uw.jpg", 16);
        touch(dir, "stereo_7_main.jpg", 16);
        touch(dir, "stereo_8_uw.jpg", 16);       // half a pair: the main never arrived
        touch(dir, "still_1_00.jpg", 16);
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteStereoPairs(2);                    // two were armed; only one completed
        m.write(null);

        JSONObject measured = read(dir).getJSONObject("measured");
        assertEquals("three files, two bursts", 2, measured.getInt("stereo_bursts_seen"));
        assertEquals("only one burst has both halves", 1,
                measured.getInt("stereo_pairs_complete"));
        assertEquals(3, measured.getInt("stereo_halves"));
        assertFalse("armed two, completed one", read(dir).getBoolean("agrees"));
    }

    @Test
    public void aDuplicatedHalfIsNotASecondPair() throws Exception {
        // Guards the burst/halves bookkeeping: the same tag twice must not complete a pair.
        File dir = mFolder.newFolder("walk_dupe");
        touch(dir, "stereo_3_uw.jpg", 16);
        touch(dir, "stereo_33_uw.jpg", 16);      // a DIFFERENT burst whose id shares a prefix
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.write(null);

        JSONObject measured = read(dir).getJSONObject("measured");
        assertEquals(2, measured.getInt("stereo_bursts_seen"));
        assertEquals("neither burst has two halves", 0,
                measured.getInt("stereo_pairs_complete"));
    }

    @Test
    public void videoAndStillsTogetherAgree() throws Exception {
        File dir = mFolder.newFolder("walk_vid_ok");
        touch(dir, "video_recording.mp4", 4096);
        touch(dir, "still_1_00.jpg", 16);
        touch(dir, "stereo_1_uw.jpg", 16);
        touch(dir, "stereo_1_main.jpg", 16);
        SessionManifest m = manifest(dir, "WALK");
        m.noteVideoRequested();
        m.noteStillsRequested();
        m.noteStillsFired(1);
        m.noteStereoPairs(1);
        m.write(null);

        JSONObject root = read(dir);
        assertTrue(root.getBoolean("agrees"));
        assertTrue(root.getJSONObject("measured").getBoolean("video"));
        assertTrue(root.getString("summary").startsWith("WALK"));
        assertTrue(root.getString("summary").contains("video + 1 stills"));
    }

    @Test
    public void aZeroLengthMp4IsNotVideo() throws Exception {
        // The encoder finalises the file after the button comes up. A file that exists but is
        // empty is the failure this receipt is supposed to catch, not evidence of a recording.
        File dir = mFolder.newFolder("walk_empty_mp4");
        touch(dir, "video_recording.mp4", 0);
        SessionManifest m = manifest(dir, "WALK");
        m.noteVideoRequested();
        m.write(null);

        JSONObject root = read(dir);
        assertFalse(root.getJSONObject("measured").getBoolean("video"));
        assertFalse(root.getBoolean("agrees"));
        assertTrue(root.getString("summary").contains("NOTHING CAPTURED"));
    }

    @Test
    public void frameAccountingIsCarriedAndFlagged() throws Exception {
        File dir = mFolder.newFolder("walk_holes");
        touch(dir, "video_recording.mp4", 4096);
        SessionManifest m = manifest(dir, "WALK");
        m.noteVideoRequested();
        // The August jetty numbers: 9,457 written, five lost to the merge.
        m.write(new RecordingWriter.FrameAccounting(9457, 0, 5, 0, 0));

        JSONObject root = read(dir);
        JSONObject frames = root.getJSONObject("frame_records");
        assertEquals(9457, frames.getInt("meta_written"));
        assertEquals(5, frames.getInt("meta_dropped_unmatched"));
        assertFalse(frames.getBoolean("complete"));
        assertTrue(root.getString("summary").contains("5 frame records lost"));
    }

    /**
     * The 2026-09-20 L1 failure, which the receipt of the day called a clean session.
     *
     * Seven shots were asked for and four reached the card. The old check asked only whether
     * ANY still had landed, so three missing pictures produced "agrees": true and a summary
     * that read like a normal run. Nothing else in the capture would have told the operator.
     */
    @Test
    public void stillsThatNeverLandedAreCountedAndDisagree() throws Exception {
        File dir = mFolder.newFolder("walk_2026_09_20_17_48_53");
        for (int i = 0; i < 4; i++) {
            touch(dir, "still_" + i + "_00.jpg", 16);
        }
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteStillsFired(7);
        m.write(null);

        JSONObject root = read(dir);
        assertEquals(4, root.getJSONObject("measured").getInt("stills_jpg"));
        assertEquals(7, root.getJSONObject("expected").getInt("stills_fired"));
        assertFalse("four of seven is not agreement", root.getBoolean("agrees"));
        assertTrue(root.getString("summary"),
                root.getString("summary").contains("3 OF 7 STILLS NEVER REACHED THE CARD"));
    }

    @Test
    public void everyStillLandingStillAgrees() throws Exception {
        // The counting check must not cry wolf on the normal case, which is every other
        // session shot that day: fired and measured equal, and the run reads clean.
        File dir = mFolder.newFolder("walk_ok");
        for (int i = 0; i < 9; i++) {
            touch(dir, "still_" + i + "_00.jpg", 16);
        }
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteStillsFired(9);
        m.write(null);
        assertTrue(read(dir).getBoolean("agrees"));
    }

    /**
     * An all-lens session that delivered a pair.
     *
     * Two L1 runs ninety seconds apart, one built on the metric pair and one on all four
     * physicals, produced manifests that could not be told apart. The lens set the session was
     * BUILT with is the missing half: without it, two delivered lenses is just a normal clip.
     */
    @Test
    public void anAllLensSessionThatDeliversAPairSaysSo() throws Exception {
        File dir = mFolder.newFolder("walk_all_lens");
        touch(dir, "stereo_111_uw.jpg", 16);
        touch(dir, "stereo_111_main.jpg", 16);
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteLensSet("all", 4);
        m.write(null);

        JSONObject root = read(dir);
        assertEquals("all", root.getJSONObject("expected").getString("lens_set"));
        assertEquals(4, root.getJSONObject("expected").getInt("lenses_configured"));
        assertEquals(2, root.getJSONObject("measured").getInt("lenses_seen"));
        assertFalse("half the lenses are missing", root.getBoolean("agrees"));
        assertTrue(root.getString("summary"),
                root.getString("summary").contains("4 lenses expected, 2 delivered"));
    }

    /**
     * The all-lens shot as this phone can actually take it: six pairs in sequence.
     *
     * One request runs two sensors, so no burst ever holds four files -- the widest capture
     * is two, and judging by it would fail every all-lens session that worked. What "every
     * lens delivered" means here is every lens appearing in SOME burst.
     */
    @Test
    public void aPairSequenceCoveringEveryLensAgrees() throws Exception {
        File dir = mFolder.newFolder("walk_pairs_in_sequence");
        String[][] pairs = {{"uw", "main"}, {"uw", "phys6"}, {"uw", "phys7"},
                {"main", "phys6"}, {"main", "phys7"}, {"phys6", "phys7"}};
        for (int i = 0; i < pairs.length; i++) {
            touch(dir, "stereo_" + (1000 + i) + "_" + pairs[i][0] + ".jpg", 16);
            touch(dir, "stereo_" + (1000 + i) + "_" + pairs[i][1] + ".jpg", 16);
        }
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteLensSet("all", 4);
        m.noteStereoPairs(6);
        m.write(null);

        JSONObject root = read(dir);
        JSONObject measured = root.getJSONObject("measured");
        assertEquals(6, measured.getInt("stereo_bursts_seen"));
        assertEquals(6, measured.getInt("stereo_pairs_complete"));
        assertEquals("no burst is wider than a pair", 2,
                measured.getInt("lenses_in_widest_capture"));
        assertEquals("but every lens appeared", 4, measured.getInt("lenses_seen"));
        assertTrue(root.getString("summary"), root.getBoolean("agrees"));
        assertFalse(root.getString("summary").contains("configured"));
    }

    @Test
    public void aPairSequenceCutShortDisagrees() throws Exception {
        // Four of six pairs landed, and the fourth lens never appeared: both are findings.
        File dir = mFolder.newFolder("walk_pairs_cut_short");
        String[][] pairs = {{"uw", "main"}, {"uw", "phys6"}, {"main", "phys6"}};
        for (int i = 0; i < pairs.length; i++) {
            touch(dir, "stereo_" + (2000 + i) + "_" + pairs[i][0] + ".jpg", 16);
            touch(dir, "stereo_" + (2000 + i) + "_" + pairs[i][1] + ".jpg", 16);
        }
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteLensSet("all", 4);
        m.noteStereoPairs(6);
        m.write(null);

        JSONObject root = read(dir);
        assertEquals(3, root.getJSONObject("measured").getInt("lenses_seen"));
        assertFalse(root.getBoolean("agrees"));
        assertTrue(root.getString("summary").contains("4 lenses expected, 3 delivered"));
    }

    @Test
    public void aPairSessionThatDeliversAPairIsFine() throws Exception {
        File dir = mFolder.newFolder("walk_pair");
        touch(dir, "stereo_111_uw.jpg", 16);
        touch(dir, "stereo_111_main.jpg", 16);
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteLensSet("pair", 2);
        m.write(null);

        JSONObject root = read(dir);
        assertTrue(root.getBoolean("agrees"));
        assertFalse(root.getString("summary").contains("configured"));
    }

    /**
     * The encoder's verdict is a static, and a stills run must not quote it.
     *
     * The PANO and L1 receipts of 2026-09-20 both carried "video_file_complete": true while
     * reporting zero video bytes. They were describing a clip recorded minutes earlier in a
     * different directory: clearLastFileVerdict() runs when a VIDEO session opens, and a
     * stills run never opens one.
     */
    @Test
    public void aStillsRunDoesNotQuoteTheLastClipsVerdict() throws Exception {
        File dir = mFolder.newFolder("pano_2026_09_20_17_46_38");
        touch(dir, "still_0_00.jpg", 16);
        SessionManifest m = manifest(dir, "PANO");
        m.noteStillsRequested();
        m.noteStillsFired(1);
        m.noteVideoFileComplete(Boolean.TRUE);   // left over from the previous clip
        m.write(null);

        JSONObject measured = read(dir).getJSONObject("measured");
        assertFalse("no video was requested, so there is no verdict to report",
                measured.has("video_file_complete"));
        assertFalse(measured.getBoolean("video"));
    }

    @Test
    public void aVideoRunStillReportsItsOwnVerdict() throws Exception {
        File dir = mFolder.newFolder("walk_with_video");
        touch(dir, "video_recording.mp4", 4096);
        SessionManifest m = manifest(dir, "WALK");
        m.noteVideoRequested();
        m.noteVideoFileComplete(Boolean.FALSE);
        m.write(null);

        JSONObject root = read(dir);
        assertFalse(root.getJSONObject("measured").getBoolean("video_file_complete"));
        assertEquals(4096, root.getJSONObject("measured").getLong("video_unplayable_bytes"));
        assertTrue(root.getString("summary").contains("NO TRAILER"));
    }

    /**
     * A stills run that produced nothing at all.
     *
     * Counting the shortfall does not ask this on its own: if nothing was ever counted as
     * fired there is no shortfall to report, so an empty session would read as agreement.
     * Found by reverting agrees() to its old form and watching which tests noticed -- the
     * pair case failed for the wrong reason, which is what exposed the gap.
     */
    @Test
    public void aStillsRunThatProducedNothingDisagrees() throws Exception {
        File dir = mFolder.newFolder("walk_empty");
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();          // and no noteStillsFired: nothing ever counted
        m.write(null);

        JSONObject root = read(dir);
        assertFalse("an empty stills run is not a clean session", root.getBoolean("agrees"));
        assertTrue(root.getString("summary").contains("NOTHING CAPTURED"));
    }

    /**
     * The camera device died two seconds in.
     *
     * 2026-09-20, L1 on v0.20: the HAL refused a four-lens request on its first frame,
     * raised ERROR_CAMERA_DEVICE (3), and the run went on for eighteen more seconds with
     * nothing to take pictures with. The receipt counted the missing stills and could not say
     * why. The error code and the moment are what make it a diagnosis.
     */
    @Test
    public void aCameraDeathIsNamedAndDisagrees() throws Exception {
        File dir = mFolder.newFolder("walk_2026_09_20_18_16_18");
        touch(dir, "still_1_00.jpg", 16);
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteStillsFired(1);            // everything asked for landed...
        m.noteCameraError(3);            // ...and the device still died
        m.write(null);

        JSONObject root = read(dir);
        assertFalse("a session whose camera died did not do what was asked",
                root.getBoolean("agrees"));
        assertEquals(3, root.getJSONObject("camera_error").getInt("code"));
        assertTrue(root.getJSONObject("camera_error").has("at_s"));
        assertTrue(root.getString("summary"),
                root.getString("summary").contains("CAMERA DIED (error 3)"));
    }

    @Test
    public void aHealthySessionCarriesNoCameraErrorField() throws Exception {
        // Absent rather than -1: a field that is always there teaches the reader to skip it.
        File dir = mFolder.newFolder("walk_healthy");
        touch(dir, "still_1_00.jpg", 16);
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteStillsFired(1);
        m.write(null);
        assertFalse(read(dir).has("camera_error"));
    }

    /**
     * Pair-shaped files with no metadata behind them.
     *
     * The first L1 pair sequence on 2026-09-20: six bursts on the card, twelve files, every
     * lens present -- and five of the six bursts were warm-up frames that armed readers kept
     * after the capture request threw. Only the first burst's callback ran. Counting files,
     * the receipt said "6 stereo pairs (metric scale)"; counting rows, it is one pair and ten
     * frames that happened to be passing.
     */
    @Test
    public void stereoFilesWithoutMetadataRowsAreImpostorsAndDisagree() throws Exception {
        File dir = mFolder.newFolder("walk_2026_09_20_18_53_06");
        String[][] pairs = {{"uw", "main"}, {"uw", "phys6"}, {"uw", "phys7"},
                {"main", "phys6"}, {"main", "phys7"}, {"phys6", "phys7"}};
        for (int i = 0; i < pairs.length; i++) {
            touch(dir, "stereo_" + (3000 + i) + "_" + pairs[i][0] + ".jpg", 16);
            touch(dir, "stereo_" + (3000 + i) + "_" + pairs[i][1] + ".jpg", 16);
        }
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteLensSet("all", 4);
        m.noteStereoPairs(6);
        m.noteStereoMetaRows(2);         // one callback ran: two rows for twelve files
        m.write(null);

        JSONObject root = read(dir);
        assertEquals(12, root.getJSONObject("measured").getInt("stereo_halves"));
        assertEquals(2, root.getJSONObject("expected").getInt("stereo_meta_rows"));
        assertFalse("ten files have no capture behind them", root.getBoolean("agrees"));
        assertTrue(root.getString("summary"),
                root.getString("summary").contains("10 STEREO FILES HAVE NO METADATA"));
    }

    @Test
    public void rowsMatchingFilesIsACleanSequence() throws Exception {
        File dir = mFolder.newFolder("walk_rows_match");
        touch(dir, "stereo_1_uw.jpg", 16);
        touch(dir, "stereo_1_main.jpg", 16);
        SessionManifest m = manifest(dir, "WALK");
        m.noteStillsRequested();
        m.noteStereoPairs(1);
        m.noteStereoMetaRows(2);
        m.write(null);

        JSONObject root = read(dir);
        assertTrue(root.getBoolean("agrees"));
        assertFalse(root.getString("summary").contains("NO METADATA"));
    }

    /**
     * A periodic session on the all-lens set: four configured, two delivered, on purpose.
     *
     * The periodic path targets the metric pair whatever the session was built with, because
     * its request also drives the video. W1 and W2 on 2026-09-20 -- two clean 12-second clips
     * with twelve pairs each -- read "4 lenses configured, 2 delivered" and disagreed. What
     * the receipt has to judge against is what the stereo was ASKED to deliver.
     */
    @Test
    public void aPeriodicSessionOnTheAllLensSetExpectsThePair() throws Exception {
        File dir = mFolder.newFolder("testW1_walk_vid");
        touch(dir, "video_recording.mp4", 4096);
        for (int i = 0; i < 12; i++) {
            touch(dir, "stereo_" + (5000 + i) + "_uw.jpg", 16);
            touch(dir, "stereo_" + (5000 + i) + "_main.jpg", 16);
        }
        SessionManifest m = manifest(dir, "WALK");
        m.noteVideoRequested();
        m.noteLensSet("all", 4, 2);     // built with four, asked for the pair
        m.noteStereoPairs(12);
        m.noteStereoMetaRows(24);
        m.write(null);

        JSONObject root = read(dir);
        assertEquals(4, root.getJSONObject("expected").getInt("lenses_configured"));
        assertEquals(2, root.getJSONObject("expected").getInt("lenses_expected"));
        assertEquals(2, root.getJSONObject("measured").getInt("lenses_seen"));
        assertTrue(root.getString("summary"), root.getBoolean("agrees"));
        assertFalse(root.getString("summary").contains("expected"));
    }

    @Test
    public void anUnwritableDirectoryDoesNotThrow() {
        // A session that has just been shot must not be lost because its receipt could not be
        // written. The directory here never existed.
        SessionManifest m = manifest(new File(mFolder.getRoot(), "gone"), "WALK");
        m.noteStillsRequested();
        m.write(null);   // must not throw
    }
}
