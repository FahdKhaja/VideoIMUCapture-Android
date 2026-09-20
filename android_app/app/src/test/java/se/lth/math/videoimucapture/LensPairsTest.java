package se.lth.math.videoimucapture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The order the lens pairs fire in, which is the order they survive in if the sequence is cut
 * short.
 *
 * On this phone one request runs two sensors and no more -- the four-lens request of
 * 2026-09-20 killed the camera on its first frame -- so the all-lens shot is a sequence of
 * pairs. The published pair (ultrawide + main, 18.02 mm) is the ruler every other baseline is
 * measured against, so it goes first; pairs with the ultrawide next; then the main camera's;
 * then whatever is left.
 */
public class LensPairsTest {

    private static final String UW = "2";
    private static final String MAIN = "5";

    @Test
    public void theMetricPairLeadsAndEveryPairAppearsOnce() {
        // The census order is what getPhysicalCameraIds() happens to hand back.
        List<String[]> pairs = StillCaptureManager.lensPairs(
                Arrays.asList("5", "2", "6", "7"), UW, MAIN);
        assertEquals("C(4,2)", 6, pairs.size());
        assertArrayEquals(new String[]{"2", "5"}, pairs.get(0));
        assertArrayEquals(new String[]{"2", "6"}, pairs.get(1));
        assertArrayEquals(new String[]{"2", "7"}, pairs.get(2));
        assertArrayEquals(new String[]{"5", "6"}, pairs.get(3));
        assertArrayEquals(new String[]{"5", "7"}, pairs.get(4));
        assertArrayEquals(new String[]{"6", "7"}, pairs.get(5));
    }

    @Test
    public void thePairAloneIsJustThePair() {
        // The archive's configuration: the sequence machinery must reduce to the one pair
        // every clip was shot with, not add anything to it.
        List<String[]> pairs = StillCaptureManager.lensPairs(Arrays.asList("2", "5"), UW, MAIN);
        assertEquals(1, pairs.size());
        assertArrayEquals(new String[]{"2", "5"}, pairs.get(0));
    }

    @Test
    public void aMissingAnchorDoesNotInventAPair() {
        // A device with no ultrawide has no published baseline; the remaining pairs still
        // enumerate, in a stable order, without a null in the first slot.
        List<String[]> pairs = StillCaptureManager.lensPairs(Arrays.asList("5", "6", "7"), UW, MAIN);
        assertEquals(3, pairs.size());
        assertArrayEquals(new String[]{"5", "6"}, pairs.get(0));
        assertArrayEquals(new String[]{"5", "7"}, pairs.get(1));
        assertArrayEquals(new String[]{"6", "7"}, pairs.get(2));
    }

    @Test
    public void fewerThanTwoLensesIsNoPairs() {
        assertEquals(0, StillCaptureManager.lensPairs(
                Collections.singletonList("5"), UW, MAIN).size());
        assertEquals(0, StillCaptureManager.lensPairs(
                Collections.<String>emptyList(), UW, MAIN).size());
    }
}
