package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The roll reads session.json to say the one thing the files cannot: that the operator asked
 * for something and did not get it.
 *
 * Half of these tests are about the reader REFUSING to make a fuss. The roll is the field-side
 * check, so a receipt that is missing, truncated, or from a build that does not exist yet must
 * leave the row looking exactly as it always did rather than taking the list down.
 */
public class ReceiptWarningTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private File sessionWith(String name, String json) throws Exception {
        File dir = mFolder.newFolder(name);
        if (json != null) {
            try (FileOutputStream os =
                         new FileOutputStream(new File(dir, SessionManifest.FILENAME))) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        return dir;
    }

    private static String warningOf(File dir) {
        return SessionSummary.quick(dir).receiptWarning;
    }

    @Test
    public void noReceiptMeansNoComplaint() throws Exception {
        // Most of the archive. These sessions predate the receipt entirely.
        assertNull(warningOf(sessionWith("walk_2026_08_21_17_20_48", null)));
    }

    @Test
    public void aReceiptThatAgreesSaysNothing() throws Exception {
        File dir = sessionWith("walk_ok", "{\"agrees\": true}");
        assertNull(warningOf(dir));
    }

    @Test
    public void recordPressedAndNoVideoIsCalledOut() throws Exception {
        // The 2026-09-14 column orbit, in one line.
        File dir = sessionWith("walk_2026_09_14_14_04_55",
                "{\"agrees\": false,"
                        + " \"expected\": {\"video\": true, \"stills\": true},"
                        + " \"measured\": {\"video\": false, \"stills_jpg\": 60}}");
        assertEquals("RECORD WAS PRESSED AND NO VIDEO ARRIVED", warningOf(dir));
    }

    @Test
    public void aStillsRunThatFiredNothingIsCalledOut() throws Exception {
        File dir = sessionWith("walk_silent",
                "{\"agrees\": false,"
                        + " \"expected\": {\"video\": false, \"stills\": true},"
                        + " \"measured\": {\"video\": false, \"stills_jpg\": 0}}");
        assertEquals("a stills run fired nothing", warningOf(dir));
    }

    @Test
    public void missingPairsAreCounted() throws Exception {
        File dir = sessionWith("walk_halfpairs",
                "{\"agrees\": false,"
                        + " \"expected\": {\"video\": false, \"stills\": true,"
                        + "  \"stereo_pairs_armed\": 30},"
                        + " \"measured\": {\"video\": false, \"stills_jpg\": 12,"
                        + "  \"stereo_pairs_complete\": 7}}");
        assertEquals("only 7 of 30 stereo pairs completed", warningOf(dir));
    }

    @Test
    public void aTruncatedReceiptIsIgnoredRatherThanThrowing() throws Exception {
        File dir = sessionWith("walk_truncated", "{\"agrees\": fal");
        assertNull("a half-written receipt must not take the roll down", warningOf(dir));
    }

    @Test
    public void anEmptyReceiptIsIgnored() throws Exception {
        File dir = sessionWith("walk_empty_receipt", "");
        assertNull(warningOf(dir));
    }

    @Test
    public void aReceiptFromALaterBuildStillWarnsGenerically() throws Exception {
        // agrees is false but the shape is unfamiliar: say something, guess nothing.
        File dir = sessionWith("walk_future",
                "{\"agrees\": false, \"something_new\": {\"whatever\": 1}}");
        String w = warningOf(dir);
        assertNotNull(w);
        assertTrue(w, w.contains("did not get what it asked for"));
    }

    @Test
    public void onTheRealFailureTheReceiptIsTheONLYThingThatKnows() throws Exception {
        // walk_2026_09_14_14_04_55 exactly as it sits in the archive. Its name never claimed
        // video -- the stills run opened the session, so there is no "_vid" -- which means the
        // measured kind is "stills run" and is entirely correct: a directory of 60 stills is
        // what is there. Nothing derived from the files can know that record was pressed.
        //
        // This is the case that cost six days, and it is the case where kind looks FINE. The
        // receipt is the only thing carrying the operator's intent, which is the whole reason
        // the roll reads it.
        File dir = sessionWith("walk_2026_09_14_14_04_55",
                "{\"agrees\": false,"
                        + " \"expected\": {\"video\": true, \"stills\": true},"
                        + " \"measured\": {\"video\": false, \"stills_jpg\": 1}}");
        try (FileOutputStream os = new FileOutputStream(new File(dir, "still_1_00.jpg"))) {
            os.write(new byte[16]);
        }
        SessionSummary s = SessionSummary.quick(dir);
        assertEquals("WALK", s.mode);
        assertEquals("2026-09-14 14:04", s.when);
        assertEquals("the files alone see nothing wrong", "stills run", s.kind);
        assertEquals("RECORD WAS PRESSED AND NO VIDEO ARRIVED", s.receiptWarning);
    }

    @Test
    public void aNameClaimingVideoIsCaughtTwice() throws Exception {
        // When the video DID open the session, the name claims video and the files contradict
        // it, so the row is flagged by the kind as well as by the receipt.
        File dir = sessionWith("walk_vid_2026_09_14_15_27_21",
                "{\"agrees\": false,"
                        + " \"expected\": {\"video\": true, \"stills\": true},"
                        + " \"measured\": {\"video\": false, \"stills_jpg\": 5}}");
        try (FileOutputStream os = new FileOutputStream(new File(dir, "still_1_00.jpg"))) {
            os.write(new byte[16]);
        }
        SessionSummary s = SessionSummary.quick(dir);
        assertTrue(s.kind, s.kind.contains("NO VIDEO"));
        assertNotNull(s.receiptWarning);
    }
}
