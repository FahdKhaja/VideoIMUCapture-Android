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
 * An mp4 whose trailer was never written is the nastiest failure in the app, because it is
 * invisible to every check that looks at the filesystem: the file is in the right place, it is
 * the right size, its bytes are real encoded frames. It simply has no index, so nothing will
 * open it -- and the operator finds out at a desk.
 *
 * These pin that the receipt calls it, and that it is called something DIFFERENT from a session
 * that produced no file, because the operator can see the file sitting there.
 */
public class UnplayableVideoTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private static void touch(File dir, String name, int bytes) throws Exception {
        try (FileOutputStream os = new FileOutputStream(new File(dir, name))) {
            os.write(new byte[bytes]);
        }
    }

    private static JSONObject read(File dir) throws Exception {
        return new JSONObject(new String(
                Files.readAllBytes(new File(dir, SessionManifest.FILENAME).toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void aFileWithNoTrailerIsNotReportedAsVideo() throws Exception {
        File dir = mFolder.newFolder("walk_vid_no_trailer");
        touch(dir, "video_recording.mp4", 8192);        // plausible size, unreadable content
        SessionManifest m = new SessionManifest(null, dir, "WALK", null);
        m.noteVideoRequested();
        m.noteVideoFileComplete(false);
        m.write(null);

        JSONObject root = read(dir);
        JSONObject measured = root.getJSONObject("measured");
        assertFalse("an unopenable file is not video", measured.getBoolean("video"));
        assertFalse(measured.getBoolean("video_file_complete"));
        assertEquals("but its bytes are still reported", 8192,
                measured.getInt("video_unplayable_bytes"));
        assertFalse(root.getBoolean("agrees"));
        assertTrue(root.getString("summary"), root.getString("summary").contains("WILL NOT PLAY"));
    }

    @Test
    public void aCompleteFileIsReportedNormally() throws Exception {
        File dir = mFolder.newFolder("walk_vid_fine");
        touch(dir, "video_recording.mp4", 8192);
        SessionManifest m = new SessionManifest(null, dir, "WALK", null);
        m.noteVideoRequested();
        m.noteVideoFileComplete(true);
        m.write(null);

        JSONObject root = read(dir);
        assertTrue(root.getJSONObject("measured").getBoolean("video"));
        assertTrue(root.getBoolean("agrees"));
        assertFalse(root.getString("summary").contains("WILL NOT PLAY"));
    }

    @Test
    public void aStillsOnlySessionMakesNoClaimAboutTheEncoder() throws Exception {
        // null means no video was recorded, which must not be reported as a video failure.
        File dir = mFolder.newFolder("walk_stills_only");
        touch(dir, "still_1_00.jpg", 16);
        SessionManifest m = new SessionManifest(null, dir, "WALK", null);
        m.noteStillsRequested();
        m.noteVideoFileComplete(null);
        m.write(null);

        JSONObject measured = read(dir).getJSONObject("measured");
        assertFalse(measured.has("video_file_complete"));
        assertFalse(measured.has("video_unplayable_bytes"));
        assertTrue(read(dir).getBoolean("agrees"));
    }

    @Test
    public void theRollSaysWontPlayRatherThanDidNotArrive() throws Exception {
        File dir = mFolder.newFolder("walk_vid_2026_09_20_10_00_00");
        touch(dir, "video_recording.mp4", 8192);
        SessionManifest m = new SessionManifest(null, dir, "WALK", null);
        m.noteVideoRequested();
        m.noteVideoFileComplete(false);
        m.write(null);

        assertEquals("THE MP4 HAS NO TRAILER AND WILL NOT PLAY",
                SessionSummary.quick(dir).receiptWarning);
    }

    @Test
    public void aStoppedForSpaceSessionSaysSoInTheSummary() throws Exception {
        File dir = mFolder.newFolder("walk_vid_ran_out");
        touch(dir, "video_recording.mp4", 8192);
        SessionManifest m = new SessionManifest(null, dir, "WALK", null);
        m.noteVideoRequested();
        m.noteVideoFileComplete(true);
        m.noteFreeAtStart(2L << 30);
        m.noteStoppedForSpace();
        m.write(null);

        JSONObject root = read(dir);
        assertTrue(root.getJSONObject("storage").getBoolean("stopped_for_space"));
        assertEquals(2L << 30, root.getJSONObject("storage").getLong("free_at_start_bytes"));
        assertTrue(root.getString("summary"),
                root.getString("summary").contains("ENDED EARLY"));
    }
}
