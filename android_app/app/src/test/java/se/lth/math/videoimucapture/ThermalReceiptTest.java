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
 * Heat is recorded in the pb3 every five seconds and has never once been acted on or reported.
 * A clip shot on a throttling phone and one shot cold are different measurements, and telling
 * them apart used to mean going and looking at the thermal stream.
 *
 * The thresholds matter more than they look. SEVERE warns and MODERATE marks the clip, but
 * only CRITICAL ends a session, because ending a walk over degradation the operator has not
 * seen would cost more captures than it saves.
 */
public class ThermalReceiptTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private static JSONObject write(File dir, int worstStatus, boolean stoppedForHeat)
            throws Exception {
        try (FileOutputStream os = new FileOutputStream(new File(dir, "still_1_00.jpg"))) {
            os.write(new byte[16]);
        }
        SessionManifest m = new SessionManifest(null, dir, "WALK", null);
        m.noteStillsRequested();
        m.noteWorstThermalStatus(worstStatus);
        if (stoppedForHeat) {
            m.noteStoppedForHeat();
        }
        m.write(null);
        return new JSONObject(new String(
                Files.readAllBytes(new File(dir, SessionManifest.FILENAME).toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void aCoolSessionIsNotMarkedThrottled() throws Exception {
        JSONObject root = write(mFolder.newFolder("walk_cool"), 0, false);
        JSONObject thermal = root.getJSONObject("thermal");
        assertEquals(0, thermal.getInt("worst_status"));
        assertFalse(thermal.getBoolean("throttled"));
        assertFalse(thermal.getBoolean("stopped_for_heat"));
        assertFalse(root.getString("summary").contains("throttled"));
    }

    @Test
    public void moderateMarksTheClipWithoutShoutingAboutIt() throws Exception {
        // MODERATE is where the sensor's output starts to change, so the clip is flagged for
        // anyone comparing it later -- but the operator is not told to stop walking.
        JSONObject root = write(mFolder.newFolder("walk_warm"),
                ThermalLogger.STATUS_MODERATE, false);
        assertTrue(root.getJSONObject("thermal").getBoolean("throttled"));
        assertFalse("not severe yet", root.getString("summary").contains("throttled"));
    }

    @Test
    public void severeSaysSoInTheSummary() throws Exception {
        JSONObject root = write(mFolder.newFolder("walk_hot"),
                ThermalLogger.STATUS_SEVERE, false);
        assertTrue(root.getJSONObject("thermal").getBoolean("throttled"));
        assertTrue(root.getString("summary"), root.getString("summary").contains("throttled"));
        assertFalse(root.getString("summary").contains("ENDED EARLY"));
    }

    @Test
    public void criticalEndsTheSessionAndTheReceiptSaysWhy() throws Exception {
        JSONObject root = write(mFolder.newFolder("walk_too_hot"),
                ThermalLogger.STATUS_CRITICAL, true);
        assertTrue(root.getJSONObject("thermal").getBoolean("stopped_for_heat"));
        assertTrue(root.getString("summary"),
                root.getString("summary").contains("ENDED EARLY: the phone was too hot"));
    }

    @Test
    public void anUnknownStatusIsNotThrottling() throws Exception {
        // -1 is "the platform would not tell us", which must not read as a hot phone.
        JSONObject root = write(mFolder.newFolder("walk_unknown"), -1, false);
        assertEquals(-1, root.getJSONObject("thermal").getInt("worst_status"));
        assertFalse(root.getJSONObject("thermal").getBoolean("throttled"));
    }

    @Test
    public void theThresholdsStayInOrder() {
        assertTrue(ThermalLogger.STATUS_MODERATE < ThermalLogger.STATUS_SEVERE);
        assertTrue(ThermalLogger.STATUS_SEVERE < ThermalLogger.STATUS_CRITICAL);
    }
}
