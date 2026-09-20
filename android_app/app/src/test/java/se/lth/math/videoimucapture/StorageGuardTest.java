package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The arithmetic of the storage guard, and the thresholds that decide whether a walk starts,
 * warns, or stops itself.
 *
 * The reserve is the load-bearing idea and the one worth pinning: a session cannot be ENDED on
 * a completely full card -- the muxer trailer, the closing RAW, the frame accounting and the
 * manifest all have to be written -- so "critical" has to fire while there is still room to do
 * that work, not when the last byte is gone.
 */
public class StorageGuardTest {

    private static final long GB = 1L << 30;
    private static final long MB = 1L << 20;

    private static StorageGuard.Status status(long free, double rate, long secondsLeft) {
        return new StorageGuard.Status(free, rate, secondsLeft);
    }

    @Test
    public void theReserveIsNotOfferedToTheOperator() {
        // 1 GB free is not 1 GB of recording: the reserve is spoken for.
        StorageGuard.Status s = status(GB, 0, -1);
        assertEquals(GB - StorageGuard.RESERVE_BYTES, s.usableBytes);
    }

    @Test
    public void criticalFiresWhileThereIsStillRoomToCloseTheSession() {
        assertTrue("at the reserve", status(StorageGuard.RESERVE_BYTES, 0, -1).critical);
        assertTrue("below it", status(StorageGuard.RESERVE_BYTES - MB, 0, -1).critical);
        assertFalse("above it", status(StorageGuard.RESERVE_BYTES + MB, 0, -1).critical);
    }

    @Test
    public void usableNeverGoesNegative() {
        // A card that is already past the reserve must report zero, not a negative headroom
        // that would come out of a subtraction as an enormous positive time remaining.
        assertEquals(0, status(0, 0, -1).usableBytes);
        assertEquals(0, status(StorageGuard.RESERVE_BYTES / 2, 0, -1).usableBytes);
    }

    @Test
    public void lowWarnsOnTimeRemainingRatherThanOnBytes() {
        // Bytes mean nothing without a rate: 2 GB is a long walk at video-only rates and a
        // short one with stills firing. The warning is on the projection.
        assertTrue(status(8 * GB, 50 * MB, StorageGuard.LOW_SECONDS - 1).low);
        assertFalse(status(8 * GB, 50 * MB, StorageGuard.LOW_SECONDS + 1).low);
    }

    @Test
    public void lowIsNotClaimedBeforeThereIsAMeasurement() {
        // secondsLeft of -1 means the guard cannot tell yet. That must not read as "zero
        // seconds left", which is the shape of bug that would stop every session instantly.
        StorageGuard.Status s = status(8 * GB, 0, -1);
        assertFalse(s.low);
        assertFalse(s.critical);
    }

    @Test
    public void criticalOutranksLow() {
        StorageGuard.Status s = status(MB, 50 * MB, 0);
        assertTrue(s.critical);
        assertFalse("one state, not two", s.low);
    }

    @Test
    public void describeIsReadableAtBothScales() {
        assertEquals("1.5 GB", StorageGuard.describe((long) (1.5 * GB)));
        assertEquals("512 MB", StorageGuard.describe(512 * MB));
        assertEquals("0 MB", StorageGuard.describe(0));
    }

    @Test
    public void thePreflightEstimateSubtractsTheReserveToo() {
        // 94 Mbit/s is the measured automatic bitrate for the full sensor at 30 fps.
        long free = StorageGuard.RESERVE_BYTES + 705 * MB;   // ~1 minute at 94 Mbit/s
        String estimate = StorageGuard.estimateAtBitrate(free, 94_000_000);
        assertEquals("about 1 min of video", estimate);
    }

    @Test
    public void thePreflightEstimateNeverPromisesRoomThatIsNotThere() {
        String estimate = StorageGuard.estimateAtBitrate(StorageGuard.RESERVE_BYTES / 2,
                94_000_000);
        assertEquals("about 1 min of video", estimate);   // clamped, never negative
        assertEquals("", StorageGuard.estimateAtBitrate(8 * GB, 0));
    }

    @Test
    public void theFloorIsWellAboveTheReserve() {
        // Otherwise a session could pass the pre-flight check and be stopped by the guard
        // moments later, which would be worse than refusing it outright.
        assertTrue(StorageGuard.FLOOR_BYTES > StorageGuard.RESERVE_BYTES * 2);
    }
}
