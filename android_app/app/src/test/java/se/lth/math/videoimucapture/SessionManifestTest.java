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

    @Test
    public void anUnwritableDirectoryDoesNotThrow() {
        // A session that has just been shot must not be lost because its receipt could not be
        // written. The directory here never existed.
        SessionManifest m = manifest(new File(mFolder.getRoot(), "gone"), "WALK");
        m.noteStillsRequested();
        m.write(null);   // must not throw
    }
}
