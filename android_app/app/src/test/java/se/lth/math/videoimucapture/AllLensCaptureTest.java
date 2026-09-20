package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;
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
 * A simultaneous capture used to mean two lenses, and the receipt counted to two and stopped.
 * With every rear lens configured one burst carries four frames, and a manifest that still
 * reports "1 pair" would hide a lens that was asked for and did not deliver — which is the
 * only interesting failure this shot has.
 */
public class AllLensCaptureTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private static void touch(File dir, String name) throws Exception {
        try (FileOutputStream os = new FileOutputStream(new File(dir, name))) {
            os.write(new byte[16]);
        }
    }

    private static JSONObject measured(File dir) throws Exception {
        SessionManifest m = new SessionManifest(null, dir, "WALK", null);
        m.noteStillsRequested();
        m.write(null);
        return new JSONObject(new String(
                Files.readAllBytes(new File(dir, SessionManifest.FILENAME).toPath()),
                StandardCharsets.UTF_8)).getJSONObject("measured");
    }

    @Test
    public void fourLensesInOneBurstAreCountedAsFour() throws Exception {
        File dir = mFolder.newFolder("walk_all_lens");
        touch(dir, "stereo_900_uw.jpg");
        touch(dir, "stereo_900_main.jpg");
        touch(dir, "stereo_900_phys6.jpg");
        touch(dir, "stereo_900_phys7.jpg");

        JSONObject m = measured(dir);
        assertEquals(4, m.getInt("stereo_halves"));
        assertEquals(1, m.getInt("stereo_bursts_seen"));
        assertEquals("all four landed", 4, m.getInt("lenses_in_widest_capture"));
        assertEquals("still one simultaneous capture", 1, m.getInt("stereo_pairs_complete"));
    }

    @Test
    public void aLensThatDidNotDeliverIsVisible() throws Exception {
        // The whole point: asked for four, got three. Under the old two-and-stop counting
        // this read identically to a healthy pair.
        File dir = mFolder.newFolder("walk_missing_lens");
        touch(dir, "stereo_901_uw.jpg");
        touch(dir, "stereo_901_main.jpg");
        touch(dir, "stereo_901_phys6.jpg");

        JSONObject m = measured(dir);
        assertEquals(3, m.getInt("lenses_in_widest_capture"));
        assertTrue("the pair itself still landed", m.getInt("stereo_pairs_complete") == 1);
    }

    @Test
    public void theMetricPairAloneStillReadsAsTwo() throws Exception {
        File dir = mFolder.newFolder("walk_pair_only");
        touch(dir, "stereo_902_uw.jpg");
        touch(dir, "stereo_902_main.jpg");

        JSONObject m = measured(dir);
        assertEquals(2, m.getInt("lenses_in_widest_capture"));
        assertEquals(1, m.getInt("stereo_pairs_complete"));
    }

    @Test
    public void aLoneFrameIsNotASimultaneousCapture() throws Exception {
        File dir = mFolder.newFolder("walk_one_half");
        touch(dir, "stereo_903_uw.jpg");

        JSONObject m = measured(dir);
        assertEquals(1, m.getInt("lenses_in_widest_capture"));
        assertEquals(0, m.getInt("stereo_pairs_complete"));
    }

    @Test
    public void severalBurstsEachReportTheirOwnWidth() throws Exception {
        File dir = mFolder.newFolder("walk_two_bursts");
        touch(dir, "stereo_904_uw.jpg");
        touch(dir, "stereo_904_main.jpg");
        touch(dir, "stereo_905_uw.jpg");
        touch(dir, "stereo_905_main.jpg");
        touch(dir, "stereo_905_phys6.jpg");
        touch(dir, "stereo_905_phys7.jpg");

        JSONObject m = measured(dir);
        assertEquals(2, m.getInt("stereo_bursts_seen"));
        assertEquals(2, m.getInt("stereo_pairs_complete"));
        assertEquals("the widest, not the average", 4, m.getInt("lenses_in_widest_capture"));
    }
}
