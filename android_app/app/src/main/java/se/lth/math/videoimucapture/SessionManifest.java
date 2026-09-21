package se.lth.math.videoimucapture;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the session actually contains, written into the session at stop.
 *
 * WHY THIS EXISTS. On 2026-09-14 the operator orbited a concrete column intending to shoot
 * video and got 60 stills and no video. Nothing said so — not at capture time, not in the
 * directory name, not in the file. It was found six days later, downstream, when the clip
 * could not be dispatched. Four sessions in the archive are like that.
 *
 * The directory name cannot answer it. A session is named "<mode>_vid" when the VIDEO opened
 * it, which is not the same claim as "this session HAS video": press capture first and the
 * video lands in a directory called "walk_". The name records which button was pressed first,
 * and that is all it has ever recorded.
 *
 * So the session states what it got, in the session, at the moment it ends — and states it by
 * MEASURING the directory rather than by trusting the counters that were supposed to be
 * driving the capture. Those counters are here too, under "expected", precisely so that the
 * two can disagree: a manifest whose halves differ is the file telling you that a shot was
 * requested and did not land. That disagreement is the instrument. A cell of the TESTS matrix
 * passes when the two halves agree.
 *
 * It is JSON rather than only a protobuf field because the question it answers — "what is in
 * here?" — should cost one `cat`, not a schema and a parser, six days later on another
 * machine. The same accounting also goes into the pb3 for readers already holding one.
 */
public final class SessionManifest {

    private static final String TAG = "SessionManifest";
    public static final String FILENAME = "session.json";

    private final File mDir;
    private final String mMode;
    private final String mTestTag;
    private final long mStartedWallMs;
    private final String mAppVersion;
    private final String mGitSha;

    // What the capture path BELIEVES it did. The measured counts come from the directory.
    private boolean mVideoRequested = false;
    private boolean mStillsRequested = false;
    private int mStillsFired = 0;
    private int mStereoPairsArmed = 0;
    private long mFreeAtStartBytes = -1;
    private boolean mStoppedForSpace = false;
    private Boolean mVideoFileComplete = null;
    private boolean mStoppedForHeat = false;
    private int mWorstThermalStatus = -1;
    private boolean mStoppedForBattery = false;
    private int mBatteryAtStart = -1;
    private int mBatteryAtEnd = -1;
    private String mLensSet = null;
    private int mLensesConfigured = -1;
    private int mLensesExpected = -1;
    private int mCameraError = -1;
    private long mCameraErrorAtMs = -1;
    private int mStereoMetaRows = -1;

    public SessionManifest(android.content.Context context, File dir, String mode,
                           String testTag) {
        mDir = dir;
        mMode = mode;
        mTestTag = testTag;
        mStartedWallMs = System.currentTimeMillis();
        mAppVersion = appVersion(context);
        mGitSha = gitSha(context);
    }

    /**
     * The exact commit this build came from.
     *
     * A version number is bumped once and then carried by every build until the next bump, so
     * two clips that behave differently can both say "0.19". For a matrix of paired cells that
     * is not good enough: a pair is a comparison only if both halves came from one binary, and
     * this is the field that proves it.
     */
    private static String gitSha(android.content.Context context) {
        try {
            return context.getString(R.string.git_sha);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Which build shot this. The same lookup the sensor census uses -- BuildConfig is not
     * generated for this module, and a receipt that cannot name its build is no use for
     * telling two clips apart afterwards.
     */
    private static String appVersion(android.content.Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    public File dir() {
        return mDir;
    }

    public void noteVideoRequested() {
        mVideoRequested = true;
    }

    public void noteStillsRequested() {
        mStillsRequested = true;
    }

    public void noteStillsFired(int shots) {
        mStillsFired = shots;
    }

    public void noteStereoPairs(int pairs) {
        if (pairs > mStereoPairsArmed) {
            mStereoPairsArmed = pairs;
        }
    }

    /**
     * Which lenses this session was BUILT with, as opposed to how many delivered.
     *
     * Without it an all-lens run that produced two files and a pair run that produced two
     * files are the same receipt, and on 2026-09-20 that is exactly what happened: two L1
     * runs ninety seconds apart, one on the metric pair and one on all four physicals,
     * identical-looking manifests, and no way afterwards to say which was which. The count of
     * configured lenses is also what makes a shortfall legible -- four configured and two
     * delivered is a finding; two configured and two delivered is a normal clip.
     */
    public void noteLensSet(String set, int configured) {
        noteLensSet(set, configured, configured);
    }

    /**
     * @param configured how many physical lenses the session was BUILT with
     * @param expected   how many its stereo was ASKED to deliver -- the same, except for a
     *                   periodic session on the all-lens set, whose request targets the metric
     *                   pair by design and delivers two of four on purpose
     */
    public void noteLensSet(String set, int configured, int expected) {
        mLensSet = set;
        mLensesConfigured = configured;
        mLensesExpected = expected;
    }

    /**
     * How many stereo metadata rows the capture path actually wrote.
     *
     * A stereo file is written by the image reader; its row is written by the capture
     * callback. When the capture request fails after the readers are armed, the reader still
     * keeps the next warm-up frame and the callback never runs -- a file with no row. The
     * first L1 pair sequence on 2026-09-20 produced five such bursts out of six, and a
     * receipt that counted files called it "6 stereo pairs (metric scale)". Rows against
     * files is what tells a capture from a frame that happened to be passing.
     */
    public void noteStereoMetaRows(int rows) {
        mStereoMetaRows = rows;
    }

    /** Free bytes when the session opened, so a short session can explain itself. */
    public void noteFreeAtStart(long bytes) {
        mFreeAtStartBytes = bytes;
    }

    /**
     * Whether the encoder finished the mp4 properly. Null when no video was recorded.
     *
     * A file whose trailer was never written has every frame on disk and no index, so it is
     * the right size, it is in the right place, and nothing will open it. Length alone --
     * which is all {@link #measure()} can see -- reports that file as a healthy video.
     */
    public void noteVideoFileComplete(Boolean complete) {
        mVideoFileComplete = complete;
    }

    /**
     * The session was ended by the storage guard rather than by the operator. Without this a
     * walk that stopped itself is indistinguishable from one the operator ended early, and
     * the difference decides whether the data is short because of the route or because of the
     * card.
     */
    public void noteStoppedForSpace() {
        mStoppedForSpace = true;
    }

    /** The same, for heat, and the worst thermal status the session reached. */
    public void noteStoppedForHeat() {
        mStoppedForHeat = true;
    }

    public void noteWorstThermalStatus(int status) {
        mWorstThermalStatus = status;
    }

    /** ...and for charge, with the level at each end so the drain is readable afterwards. */
    public void noteStoppedForBattery() {
        mStoppedForBattery = true;
    }

    /**
     * The camera device itself failed during the session.
     *
     * On 2026-09-20 the vendor HAL raised CAMERA_ERROR (3) on the first frame of a four-lens
     * warm-up, two seconds into a twenty-second run. The proxy released the device, nothing
     * told the session, the trigger kept deciding shots at a camera that no longer existed,
     * and the receipt could only say that stills were missing -- not that there had been
     * nothing left to take them with. The error code and the moment are the two facts that
     * turn "some pictures are missing" into a diagnosis.
     */
    public void noteCameraError(int error) {
        if (mCameraError < 0) {
            mCameraError = error;
            mCameraErrorAtMs = System.currentTimeMillis();
        }
    }

    public void noteBatteryAtStart(int percent) {
        mBatteryAtStart = percent;
    }

    public void noteBatteryAtEnd(int percent) {
        mBatteryAtEnd = percent;
    }

    /**
     * Measure the directory and write the manifest. Never throws: a session that has just
     * been shot must not be lost because its receipt could not be written.
     */
    public void write(RecordingWriter.FrameAccounting frames) {
        try {
            JSONObject root = new JSONObject();
            root.put("app_version", mAppVersion);
            root.put("git_sha", mGitSha);
            root.put("mode", mMode);
            if (mTestTag != null) {
                root.put("test_cell", mTestTag);
            }
            long ended = System.currentTimeMillis();
            root.put("started_unix_ms", mStartedWallMs);
            root.put("ended_unix_ms", ended);
            root.put("duration_s", Double.parseDouble(
                    String.format(Locale.US, "%.1f", (ended - mStartedWallMs) / 1000.0)));

            JSONObject expected = new JSONObject();
            expected.put("video", mVideoRequested);
            expected.put("stills", mStillsRequested);
            expected.put("stills_fired", mStillsFired);
            expected.put("stereo_pairs_armed", mStereoPairsArmed);
            if (mLensSet != null) {
                expected.put("lens_set", mLensSet);
                expected.put("lenses_configured", mLensesConfigured);
                expected.put("lenses_expected", mLensesExpected);
            }
            if (mStereoMetaRows >= 0) {
                expected.put("stereo_meta_rows", mStereoMetaRows);
            }
            root.put("expected", expected);

            JSONObject measured = measure();
            root.put("measured", measured);

            if (frames != null) {
                JSONObject f = new JSONObject();
                f.put("meta_written", frames.metaWritten);
                f.put("meta_dropped_queue_full", frames.metaDroppedQueue);
                f.put("meta_dropped_unmatched", frames.metaDroppedMerge);
                f.put("time_dropped_queue_full", frames.timeDroppedQueue);
                f.put("time_dropped_unmatched", frames.timeDroppedMerge);
                f.put("complete", frames.isComplete());
                root.put("frame_records", f);
            }

            JSONObject storage = new JSONObject();
            if (mFreeAtStartBytes >= 0) {
                storage.put("free_at_start_bytes", mFreeAtStartBytes);
            }
            storage.put("free_at_end_bytes", StorageGuard.freeBytes(mDir));
            storage.put("stopped_for_space", mStoppedForSpace);
            root.put("storage", storage);

            // Heat, for the same reason as storage: a clip that degraded under throttling and
            // one shot on a cool phone are not the same measurement, and the thermal stream in
            // the pb3 answers that only for someone who goes looking.
            JSONObject thermal = new JSONObject();
            thermal.put("worst_status", mWorstThermalStatus);
            thermal.put("stopped_for_heat", mStoppedForHeat);
            thermal.put("throttled", mWorstThermalStatus >= ThermalLogger.STATUS_MODERATE);
            root.put("thermal", thermal);

            // Charge at both ends. The drain across a session is the number that says whether
            // the next walk of this length will finish, which is a question the operator
            // otherwise has to answer by guessing.
            JSONObject battery = new JSONObject();
            if (mBatteryAtStart >= 0) {
                battery.put("percent_at_start", mBatteryAtStart);
            }
            if (mBatteryAtEnd >= 0) {
                battery.put("percent_at_end", mBatteryAtEnd);
            }
            if (mBatteryAtStart >= 0 && mBatteryAtEnd >= 0) {
                battery.put("percent_used", Math.max(0, mBatteryAtStart - mBatteryAtEnd));
            }
            battery.put("stopped_for_battery", mStoppedForBattery);
            root.put("battery", battery);

            // Only when it happened. A receipt that always carried "camera_error": -1 would
            // teach the reader to skip the field, and this one is worth reading every time.
            if (mCameraError >= 0) {
                JSONObject cam = new JSONObject();
                cam.put("code", mCameraError);
                cam.put("at_s", Double.parseDouble(String.format(Locale.US, "%.1f",
                        (mCameraErrorAtMs - mStartedWallMs) / 1000.0)));
                root.put("camera_error", cam);
            }

            root.put("agrees", agrees(measured));
            root.put("summary", summary(measured, frames));

            File out = new File(mDir, FILENAME);
            try (FileOutputStream os = new FileOutputStream(out)) {
                os.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "wrote " + out.getName() + ": " + root.optString("summary"));
        } catch (JSONException | IOException | RuntimeException e) {
            Log.e(TAG, "could not write the session manifest: " + e);
        }
    }

    /** Count what the directory holds, trusting nothing the capture path reported. */
    private JSONObject measure() throws JSONException {
        JSONObject m = new JSONObject();
        File video = new File(mDir, "video_recording.mp4");
        boolean hasVideo = video.isFile() && video.length() > 0;
        m.put("video", hasVideo);
        m.put("video_bytes", hasVideo ? video.length() : 0);

        // Length says a file is there; only the encoder knows whether it can be opened. A
        // recording whose trailer was never written is exactly the right size and completely
        // unreadable, so it is reported as NOT video -- because for every purpose downstream
        // it is not.
        // Only when THIS session asked for video. The encoder's verdict is a static left by
        // the last clip, and a stills run that reports "video_file_complete: true" is quoting
        // a different session's mp4 -- which is what the PANO and L1 receipts did on
        // 2026-09-20, both of them describing a video recorded minutes earlier in another
        // directory. A field that is silently about someone else is worse than no field.
        if (mVideoRequested && mVideoFileComplete != null) {
            m.put("video_file_complete", mVideoFileComplete);
            if (hasVideo && !mVideoFileComplete) {
                m.put("video", false);
                m.put("video_unplayable_bytes", video.length());
            }
        }

        File pb3 = new File(mDir, "video_meta.pb3");
        m.put("meta_bytes", pb3.isFile() ? pb3.length() : 0);

        int singleJpg = 0;
        int singleDng = 0;
        int stereoHalves = 0;
        Map<String, java.util.Set<String>> tagsPerBurst = new HashMap<>();
        // Every distinct lens that delivered ANYWHERE in the session. On this phone a
        // simultaneous capture is a pair -- the HAL will not run more than two sensors on
        // one request -- so an all-lens session is a SEQUENCE of pairs, and the question
        // "did every lens deliver" is answered across bursts, not within one.
        java.util.Set<String> lensesSeen = new java.util.LinkedHashSet<>();

        File[] files = mDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.startsWith("still_")) {
                    if (n.endsWith(".jpg")) {
                        singleJpg++;
                    } else if (n.endsWith(".dng")) {
                        singleDng++;
                    }
                } else if (n.startsWith("stereo_") && n.endsWith(".jpg")) {
                    stereoHalves++;
                    // stereo_<burstId>_<tag>.jpg
                    String stem = n.substring(0, n.length() - 4);
                    String[] parts = stem.split("_");
                    if (parts.length >= 3) {
                        // burst id -> the DISTINCT lens tags seen for it. This counted to two
                        // and stopped, because a simultaneous capture meant a pair. With the
                        // all-lens shot one burst can carry four frames -- uw, main and a tag
                        // per extra physical -- and capping at two would report a four-lens
                        // capture as a pair, hiding a missing lens completely.
                        String burst = parts[1];
                        String tag = parts[2];
                        java.util.Set<String> tags = tagsPerBurst.get(burst);
                        if (tags == null) {
                            tags = new java.util.LinkedHashSet<>();
                            tagsPerBurst.put(burst, tags);
                        }
                        tags.add(tag);
                        lensesSeen.add(tag);
                    }
                }
            }
        }
        int complete = 0;
        int widestBurst = 0;
        for (java.util.Set<String> tags : tagsPerBurst.values()) {
            if (tags.size() >= 2) {
                complete++;
            }
            if (tags.size() > widestBurst) {
                widestBurst = tags.size();
            }
        }
        m.put("stills_jpg", singleJpg);
        m.put("stills_dng", singleDng);
        m.put("stereo_halves", stereoHalves);
        m.put("stereo_bursts_seen", tagsPerBurst.size());
        m.put("stereo_pairs_complete", complete);
        // The most lenses any one simultaneous capture managed. 2 is the metric pair; more
        // means the all-lens shot fired and says how many of them actually landed.
        m.put("lenses_in_widest_capture", widestBurst);
        m.put("lenses_seen", lensesSeen.size());
        return m;
    }

    /**
     * True when nothing the capture path asked for is missing from the directory.
     *
     * This used to ask whether ANY still had landed, which is a much weaker question than it
     * looks. On 2026-09-20 a run fired seven shots, four reached the card, and the receipt
     * said "agrees": three pictures went missing and the one file whose entire job is to
     * notice that reported a clean session. Counting is the whole point -- the capture path
     * already knows how many it asked for, and the directory already knows how many arrived.
     *
     * The same for lenses. A session built with four physical streams that delivers two is
     * not a session that worked; it is the all-lens shot half failing, which is precisely the
     * finding L1 exists to produce.
     */
    private boolean agrees(JSONObject measured) {
        if (mCameraError >= 0) {
            // Whatever landed before the device died, the session did not do what was asked.
            return false;
        }
        if (mVideoRequested != measured.optBoolean("video", false)) {
            return false;
        }
        if (mStillsRequested) {
            if (shortfall(measured) > 0) {
                return false;
            }
            // And the older, cruder question, kept because counting alone does not ask it: a
            // stills run that produced NOTHING has no shortfall to report if nothing was ever
            // counted as fired, and would otherwise slip through as agreement. Stereo halves
            // count -- a run whose only output is a pair is a real capture.
            if (measured.optInt("stills_jpg", 0) == 0
                    && measured.optInt("stereo_halves", 0) == 0) {
                return false;
            }
        }
        // Across the session, not within one burst: on this phone a request can run two
        // sensors, so every lens delivering means every lens appearing in SOME pair.
        if (mLensesExpected > 0 && measured.optInt("stereo_bursts_seen", 0) > 0
                && measured.optInt("lenses_seen", 0) < mLensesExpected) {
            return false;
        }
        // A stereo file with no metadata row is a warm-up frame, not a capture.
        if (mStereoMetaRows >= 0 && measured.optInt("stereo_halves", 0) > mStereoMetaRows) {
            return false;
        }
        return mStereoPairsArmed <= measured.optInt("stereo_pairs_complete", 0);
    }

    /** How many stills were asked for and never reached the card. */
    private int shortfall(JSONObject measured) {
        if (mStillsFired <= 0) {
            return 0;
        }
        return Math.max(0, mStillsFired - measured.optInt("stills_jpg", 0));
    }

    /**
     * The sentence the operator needed on 2026-09-14. Built from what is on disk, and it says
     * the missing thing out loud rather than leaving it to be noticed by its absence.
     */
    private String summary(JSONObject measured, RecordingWriter.FrameAccounting frames) {
        boolean hasVideo = measured.optBoolean("video", false);
        int stills = measured.optInt("stills_jpg", 0);
        int pairs = measured.optInt("stereo_pairs_complete", 0);

        StringBuilder sb = new StringBuilder(mMode);
        sb.append(" — ");
        if (hasVideo && stills > 0) {
            sb.append("video + ").append(stills).append(" stills");
        } else if (hasVideo) {
            sb.append("video only");
        } else if (stills > 0) {
            sb.append(stills).append(" stills, NO VIDEO");
        } else {
            sb.append("NOTHING CAPTURED");
        }
        sb.append(pairs > 0
                ? ", " + pairs + " stereo pairs (metric scale)"
                : ", no stereo pair (NO METRIC SCALE)");
        int missing = shortfall(measured);
        if (missing > 0) {
            sb.append(" — ").append(missing).append(" OF ").append(mStillsFired)
                    .append(" STILLS NEVER REACHED THE CARD");
        }
        if (mLensesExpected > 0 && measured.optInt("stereo_bursts_seen", 0) > 0) {
            int got = measured.optInt("lenses_seen", 0);
            if (got < mLensesExpected) {
                sb.append(" — ").append(mLensesExpected).append(" lenses expected, ")
                        .append(got).append(" delivered");
            }
        }
        if (mStereoMetaRows >= 0) {
            int impostors = measured.optInt("stereo_halves", 0) - mStereoMetaRows;
            if (impostors > 0) {
                sb.append(" — ").append(impostors)
                        .append(" STEREO FILES HAVE NO METADATA (warm-up frames, not captures)");
            }
        }
        if (measured.optLong("video_unplayable_bytes", 0) > 0) {
            sb.append(" — the mp4 HAS NO TRAILER AND WILL NOT PLAY");
        } else if (mVideoRequested && !hasVideo) {
            sb.append(" — video was started and left no file");
        }
        if (frames != null && !frames.isComplete()) {
            sb.append(" — ").append(frames.totalDropped()).append(" frame records lost");
        }
        if (mCameraError >= 0) {
            sb.append(" — CAMERA DIED (error ").append(mCameraError).append(") at ")
                    .append(String.format(Locale.US, "%.1f",
                            (mCameraErrorAtMs - mStartedWallMs) / 1000.0))
                    .append(" s");
        }
        if (mStoppedForSpace) {
            sb.append(" — ENDED EARLY: the card ran out");
        }
        if (mStoppedForBattery) {
            sb.append(" — ENDED EARLY: the battery ran down");
        }
        if (mStoppedForHeat) {
            sb.append(" — ENDED EARLY: the phone was too hot");
        } else if (mWorstThermalStatus >= ThermalLogger.STATUS_SEVERE) {
            sb.append(" — throttled (thermal ").append(mWorstThermalStatus).append(')');
        }
        return sb.toString();
    }
}
