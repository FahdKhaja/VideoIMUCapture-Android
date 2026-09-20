package se.lth.math.videoimucapture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The capture button reads stillsRunning and the idle timer reads anyActive, and the whole
 * reason RunState exists is that those are different questions. These pin the four cases.
 */
public class RunStateTest {

    private static CaptureModeManager.RunState state(boolean stills, boolean video,
                                                     boolean composite) {
        return new CaptureModeManager.RunState(stills, video, composite, "");
    }

    @Test
    public void videoOnly_isBusyButNotAStillsRun() {
        // The defect this class was introduced for: during a plain video clip the capture
        // button wore a stop icon, and pressing it started a stills run instead of stopping
        // anything. The button follows stillsRunning, which must be false here.
        CaptureModeManager.RunState s = state(false, true, false);
        assertFalse("the capture button must not read as a stop button", s.stillsRunning);
        assertTrue("but the phone is busy: no idle timeout, no mode change", s.anyActive);
    }

    @Test
    public void stillsOnly_isAStillsRun() {
        CaptureModeManager.RunState s = state(true, false, false);
        assertTrue(s.stillsRunning);
        assertTrue(s.anyActive);
    }

    @Test
    public void bothTogether_isStillAStillsRun() {
        // M3/M4: the capture button is a stop button for the stills half regardless of who
        // opened the session.
        CaptureModeManager.RunState s = state(true, true, false);
        assertTrue(s.stillsRunning);
        assertTrue(s.anyActive);
    }

    @Test
    public void aCompositeIsBusyWithoutBeingARun() {
        CaptureModeManager.RunState s = state(false, false, true);
        assertFalse(s.stillsRunning);
        assertTrue("the composite must hold off the idle timer", s.anyActive);
        assertTrue("and disable the button that would start a second one", s.compositeRunning);
    }

    @Test
    public void idleIsIdle() {
        CaptureModeManager.RunState s = state(false, false, false);
        assertFalse(s.stillsRunning);
        assertFalse(s.anyActive);
        assertFalse(s.compositeRunning);
    }
}
