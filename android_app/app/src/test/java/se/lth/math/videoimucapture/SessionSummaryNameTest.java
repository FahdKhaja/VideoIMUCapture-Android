package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The directory name says which button was pressed FIRST, and the roll has been reading it as
 * though it said what the session contains. These pin both halves: what the name really claims,
 * and what the roll should say once the files have been counted.
 */
public class SessionSummaryNameTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private File file(String name, int bytes) throws Exception {
        File f = mFolder.newFile(name);
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(new byte[bytes]);
        }
        return f;
    }

    @Test
    public void parsesAPlainStillsRun() {
        String[] p = SessionSummary.parseName("walk_2026_09_14_14_04_55");
        assertEquals("WALK", p[0]);
        assertEquals("stills run", p[1]);
        assertEquals("2026-09-14 14:04", p[2]);
        assertNull("no test cell", p[3]);
    }

    @Test
    public void parsesAVideoClaimAndATestCell() {
        String[] p = SessionSummary.parseName("testS2_walk_vid_2026_09_10_20_09_28");
        assertEquals("WALK", p[0]);
        assertEquals("video", p[1]);
        assertEquals("2026-09-10 20:09", p[2]);
        assertEquals("S2", p[3]);
    }

    @Test
    public void parsesTheOtherModes() {
        assertEquals("OBJECT", SessionSummary.parseName("object_2026_09_14_15_27_40")[0]);
        assertEquals("PANO", SessionSummary.parseName("pano_vid_2026_09_14_15_27_40")[0]);
        assertEquals("OTHER", SessionSummary.parseName("something_else_entirely")[0]);
    }

    @Test
    public void aNameClaimingVideoOverADirectoryWithNoneSaysSo() throws Exception {
        // walk_vid_2026_09_14_14_04_55's shape: the name claims video, the files disagree.
        String kind = SessionSummary.measuredKind("video", "WALK", null, null, 60);
        assertTrue(kind, kind.contains("NO VIDEO"));
    }

    @Test
    public void aWalkDirectoryHoldingVideoIsReportedAsVideo() throws Exception {
        // The M3 case: stills opened the session so the name has no _vid, but video is there.
        File video = file("video_recording.mp4", 4096);
        assertEquals("video + stills",
                SessionSummary.measuredKind("stills run", "WALK", null, video, 12));
    }

    @Test
    public void anEmptyMp4IsNotVideo() throws Exception {
        File video = file("empty.mp4", 0);
        String kind = SessionSummary.measuredKind("video", "WALK", null, video, 3);
        assertTrue(kind, kind.contains("NO VIDEO"));
    }

    @Test
    public void anEmptySessionIsEmptyAndACellKeepsItsLabel() {
        assertEquals("EMPTY", SessionSummary.measuredKind("stills run", "WALK", null, null, 0));
        assertEquals("test M1 EMPTY",
                SessionSummary.measuredKind("stills run", "WALK", "M1", null, 0));
    }

    @Test
    public void aManualBurstKeepsItsOwnWord() {
        assertEquals("manual burst",
                SessionSummary.measuredKind("manual burst", "STILLS", null, null, 5));
    }
}
