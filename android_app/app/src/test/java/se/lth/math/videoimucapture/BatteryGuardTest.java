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
 * The battery guard's thresholds and the two rules that keep it from being a nuisance: a phone
 * on a power bank is never stopped, and a platform that will not report a level is not a reason
 * to refuse the capture the operator came for.
 */
public class BatteryGuardTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private static BatteryGuard.Status status(int percent, boolean charging, long secondsLeft) {
        return new BatteryGuard.Status(percent, charging, 0, secondsLeft);
    }

    @Test
    public void criticalStopsTheCaptureWithChargeInHand() {
        // Not at 0: closing the session takes seconds of work, and the operator still has to
        // get the phone home.
        assertTrue(status(BatteryGuard.CRITICAL_PERCENT, false, -1).critical);
        assertTrue(status(1, false, -1).critical);
        assertFalse(status(BatteryGuard.CRITICAL_PERCENT + 1, false, -1).critical);
        assertTrue("and there is charge left when it fires", BatteryGuard.CRITICAL_PERCENT > 0);
    }

    @Test
    public void aChargingPhoneIsNeverCritical() {
        // A power bank is the whole answer to this problem, and stopping a capture that is
        // plugged in would be the app fighting the fix.
        assertFalse(status(2, true, -1).critical);
        assertFalse(status(0, true, -1).critical);
    }

    @Test
    public void anUnreadableLevelIsNotCritical() {
        // -1 is "the platform would not say", which must never read as a flat battery.
        assertFalse(status(-1, false, -1).critical);
        assertFalse(status(-1, false, -1).low);
    }

    @Test
    public void lowIsAWarningAndNotAStop() {
        BatteryGuard.Status s = status(BatteryGuard.LOW_PERCENT, false, 600);
        assertTrue(s.low);
        assertFalse(s.critical);
    }

    @Test
    public void criticalOutranksLow() {
        BatteryGuard.Status s = status(1, false, 0);
        assertTrue(s.critical);
        assertFalse("one state, not two", s.low);
    }

    @Test
    public void theFloorSitsAboveCriticalButStaysOutOfTheOperatorsWay() {
        // A short OBJECT composite at 10% is a reasonable thing to want; refusing it would be
        // the app overruling someone who can see their own battery. So the floor is close to
        // critical rather than comfortably above it.
        assertTrue(BatteryGuard.FLOOR_PERCENT > BatteryGuard.CRITICAL_PERCENT);
        assertTrue(BatteryGuard.FLOOR_PERCENT < BatteryGuard.LOW_PERCENT);
    }

    @Test
    public void theReadoutSaysChargingRatherThanCountingDown() {
        assertEquals("BATT 42% chg|", BatteryGuard.readout(status(42, true, -1)));
    }

    @Test
    public void theReadoutAddsMinutesOnlyWhenItKnowsThem() {
        assertEquals("BATT 12% 20min|", BatteryGuard.readout(status(12, false, 1200)));
        assertEquals("BATT 12%|", BatteryGuard.readout(status(12, false, -1)));
        assertEquals("", BatteryGuard.readout(status(-1, false, -1)));
        assertEquals("", BatteryGuard.readout(null));
    }

    @Test
    public void theReceiptCarriesTheDrainAcrossTheSession() throws Exception {
        File dir = mFolder.newFolder("walk_drain");
        try (FileOutputStream os = new FileOutputStream(new File(dir, "still_1_00.jpg"))) {
            os.write(new byte[16]);
        }
        SessionManifest m = new SessionManifest(null, dir, "WALK", null);
        m.noteStillsRequested();
        m.noteBatteryAtStart(88);
        m.noteBatteryAtEnd(71);
        m.write(null);

        JSONObject battery = new JSONObject(new String(
                Files.readAllBytes(new File(dir, SessionManifest.FILENAME).toPath()),
                StandardCharsets.UTF_8)).getJSONObject("battery");
        assertEquals(88, battery.getInt("percent_at_start"));
        assertEquals(71, battery.getInt("percent_at_end"));
        assertEquals("the number that says whether the next walk finishes",
                17, battery.getInt("percent_used"));
        assertFalse(battery.getBoolean("stopped_for_battery"));
    }

    @Test
    public void aSessionStoppedForBatterySaysSo() throws Exception {
        File dir = mFolder.newFolder("walk_flat");
        try (FileOutputStream os = new FileOutputStream(new File(dir, "still_1_00.jpg"))) {
            os.write(new byte[16]);
        }
        SessionManifest m = new SessionManifest(null, dir, "WALK", null);
        m.noteStillsRequested();
        m.noteBatteryAtStart(9);
        m.noteBatteryAtEnd(BatteryGuard.CRITICAL_PERCENT);
        m.noteStoppedForBattery();
        m.write(null);

        JSONObject root = new JSONObject(new String(
                Files.readAllBytes(new File(dir, SessionManifest.FILENAME).toPath()),
                StandardCharsets.UTF_8));
        assertTrue(root.getJSONObject("battery").getBoolean("stopped_for_battery"));
        assertTrue(root.getString("summary"),
                root.getString("summary").contains("ENDED EARLY: the battery ran down"));
    }

    @Test
    public void aChargingSessionNeverProjectsATimeRemaining() {
        // Charging means the level may be RISING, and a projection off a rising level is
        // nonsense in the dangerous direction.
        assertEquals(-1, status(50, true, -1).secondsLeft);
    }
}
