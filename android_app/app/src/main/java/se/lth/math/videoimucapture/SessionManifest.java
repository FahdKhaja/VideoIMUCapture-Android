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

    public SessionManifest(android.content.Context context, File dir, String mode,
                           String testTag) {
        mDir = dir;
        mMode = mode;
        mTestTag = testTag;
        mStartedWallMs = System.currentTimeMillis();
        mAppVersion = appVersion(context);
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
        if (mVideoFileComplete != null) {
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
        return m;
    }

    /** True when nothing the capture path asked for is missing from the directory. */
    private boolean agrees(JSONObject measured) {
        if (mVideoRequested != measured.optBoolean("video", false)) {
            return false;
        }
        if (mStillsRequested && measured.optInt("stills_jpg", 0) == 0) {
            return false;
        }
        return mStereoPairsArmed <= measured.optInt("stereo_pairs_complete", 0);
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
        if (measured.optLong("video_unplayable_bytes", 0) > 0) {
            sb.append(" — the mp4 HAS NO TRAILER AND WILL NOT PLAY");
        } else if (mVideoRequested && !hasVideo) {
            sb.append(" — video was started and left no file");
        }
        if (frames != null && !frames.isComplete()) {
            sb.append(" — ").append(frames.totalDropped()).append(" frame records lost");
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
