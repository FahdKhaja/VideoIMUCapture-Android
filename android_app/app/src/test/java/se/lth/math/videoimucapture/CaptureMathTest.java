package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The arithmetic and the bookkeeping that the device cannot be asked about here: the hyperfocal
 * formula against numbers worked out by hand, and the frame accounting that decides whether a
 * file may be joined to its images by list position at all.
 */
public class CaptureMathTest {

    private static final float TOL = 1e-4f;

    @Test
    public void hyperfocalMatchesTheFormulaByHand() {
        // f = 5.0 mm, N = 1.8, sensor diagonal 9.6 mm.
        //   c = 9.6 / 1500                = 0.0064 mm
        //   H = 25 / (1.8 * 0.0064) + 5   = 2170.139 + 5 = 2175.139 mm
        //   diopters = 1000 / 2175.139    = 0.459741
        Float d = CameraSettingFocusMode.hyperfocalDiopters(5.0f, 1.8f, 9.6);
        assertEquals(0.459741f, d, TOL);
        assertEquals("and therefore 2.175 m", 2.175f, 1.0f / d, 1e-3f);
    }

    @Test
    public void aLongerLensFocusesItsHyperfocalFurtherAway() {
        // Fewer diopters means a greater distance. Doubling the focal length should push the
        // hyperfocal out by roughly four times, since H goes as f squared.
        float shortLens = CameraSettingFocusMode.hyperfocalDiopters(5.0f, 1.8f, 9.6);
        float longLens = CameraSettingFocusMode.hyperfocalDiopters(10.0f, 1.8f, 9.6);
        assertTrue("longer lens -> fewer diopters", longLens < shortLens);
        assertEquals("roughly four times the distance",
                4.0f, shortLens / longLens, 0.02f);
    }

    @Test
    public void aSmallerApertureBringsHyperfocalCloser() {
        float wideOpen = CameraSettingFocusMode.hyperfocalDiopters(5.0f, 1.8f, 9.6);
        float stoppedDown = CameraSettingFocusMode.hyperfocalDiopters(5.0f, 3.6f, 9.6);
        assertTrue("stopping down -> more diopters -> nearer", stoppedDown > wideOpen);
    }

    @Test
    public void nonsenseInputsGiveNoAnswerRatherThanAWrongOne() {
        assertNull(CameraSettingFocusMode.hyperfocalDiopters(0f, 1.8f, 9.6));
        assertNull(CameraSettingFocusMode.hyperfocalDiopters(-5f, 1.8f, 9.6));
        assertNull(CameraSettingFocusMode.hyperfocalDiopters(5f, 0f, 9.6));
        assertNull(CameraSettingFocusMode.hyperfocalDiopters(5f, 1.8f, 0));
    }

    @Test
    public void aFileWithNoLossesIsComplete() {
        RecordingWriter.FrameAccounting a =
                new RecordingWriter.FrameAccounting(9462, 0, 0, 0, 0);
        assertTrue(a.isComplete());
        assertEquals(0, a.totalDropped());
    }

    @Test
    public void theAugustJettyFileWouldHaveBeenFlagged() {
        // 9,457 records written, five discarded by the merge: the hole that shifted every
        // record after it by one and produced the wrong focal in #48.
        RecordingWriter.FrameAccounting a =
                new RecordingWriter.FrameAccounting(9457, 0, 5, 0, 0);
        assertFalse(a.isComplete());
        assertEquals(5, a.totalDropped());
    }

    @Test
    public void everyKindOfLossCounts() {
        RecordingWriter.FrameAccounting a =
                new RecordingWriter.FrameAccounting(100, 1, 2, 3, 4);
        assertEquals(10, a.totalDropped());
        assertFalse(a.isComplete());
    }
}
