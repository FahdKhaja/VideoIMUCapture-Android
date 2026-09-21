package se.lth.math.videoimucapture;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * One button, three behaviours. The operating principle is the operator's: almost dumb.
 *
 * The camera button never asks a question. What it does depends on a single mode
 * setting, and each mode is a complete opinion about how to capture that kind of
 * subject rather than a pile of knobs:
 *
 *   WALK   — press to start, press to stop. Stills fire at the quiet moments of the
 *            operator's gait, exposure and white balance locked at the first frame so
 *            the whole run is radiometrically consistent. JPEG, because RAW at this
 *            rate does not fit (25 MB per DNG against ~7 MB per JPEG: a ten-minute
 *            walk is 8.4 GB one way and 38 GB the other), with one RAW at each end as
 *            a linearity reference for the JPEGs in between.
 *
 *   OBJECT — stationary. One press fires the composite: a focus stack across the
 *            subject's depth, then an exposure bracket, then a full-quality RAW. Focus
 *            stacking is only meaningful here — it combines focal planes of the SAME
 *            view, so it needs a viewpoint that does not move, which is exactly why it
 *            must not run in WALK.
 *
 *   PANO   — gimbal or tripod. Stillness-triggered like WALK, but the viewpoint really
 *            is fixed, so an exposure bracket per position is correct and merges
 *            cleanly.
 *
 * WHY WALK DOES NOT BRACKET. A five-shot burst spans 134 ms on this hardware, which at
 * walking pace is 19 cm of travel — far too much for an HDR merge, which assumes a
 * fixed viewpoint. But a radiance field does not need the merge: every frame is a valid
 * observation carrying its own recorded exposure, so range is recovered ACROSS views
 * rather than within a pixel. One frame per quiet moment beats five ghosted ones.
 */
public class CaptureModeManager implements StillnessTrigger.Listener {
    private static final String TAG = "CaptureMode";

    public enum Mode {WALK, OBJECT, PANO}

    /** Fired on the main thread when a run starts or stops, for UI state. */
    public interface StateListener {
        void onRunStateChanged(RunState state);
    }

    private final CameraCaptureActivity mActivity;
    private final StillnessTrigger mTrigger;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private Mode mMode = Mode.WALK;
    private StateListener mStateListener;

    private boolean mRunning = false;
    private File mRunDir;
    private RecordingWriter mWriter;
    private boolean mOwnsWriter = false;
    private int mShots = 0;
    // Whether THIS video session engaged the AE/AWB lock, so that ending it releases only what
    // it took. The lock is a preference since v0.14: the greenhouse capture showed that a scene
    // whose light changes every three feet needs the exposure to move, and a session that
    // cannot move it forces a stop-and-restart — which turns one traverse into many sessions,
    // and cross-session matching is the thing that fails. With the lock off, auto exposure
    // keeps running and every frame records its own exposure and ISO, which is what lets it
    // float safely.
    private boolean mVideoLockedRadiometry = false;
    private boolean mEndRawPending = false;

    // The receipt for the session, opened by whichever path opens the directory and written
    // when the LAST of the two closes. Video and stills can each open a session and each join
    // the other's, so "the session is over" is not a fact either path holds alone.
    private SessionManifest mManifest;
    private boolean mVideoActive = false;
    /** OBJECT mode's one press, which runs its own session start to finish. */
    private final ObjectComposite mComposite;

    public CaptureModeManager(CameraCaptureActivity activity) {
        mActivity = activity;
        mTrigger = new StillnessTrigger(this);
        mComposite = new ObjectComposite(activity, mMain, this::notifyState);
    }

    public void setStateListener(StateListener l) {
        mStateListener = l;
    }

    public void setMode(Mode mode) {
        // A video recording and a composite own the mode just as a stills run does: the mode
        // is the discipline the clip is being shot under and it names the directory, so
        // changing it mid-clip makes the name a lie about what the frames were. The strip is
        // dimmed for all three; this refuses the change if anything gets past that.
        if (mRunning || mVideoActive || mComposite.isActive()) {
            Log.w(TAG, "mode change ignored while a capture is active");
            return;
        }
        mMode = mode;
    }

    public Mode getMode() {
        return mMode;
    }

    public boolean isRunning() {
        return mRunning;
    }

    public StillnessTrigger getTrigger() {
        return mTrigger;
    }

    // ------------------------------------------------------------------- video session
    //
    // TWO BUTTONS, TWO INSTRUMENTS, ONE DISCIPLINE. The camera button takes stills; the
    // record button takes video. What the MODE decides is not which button does what — it
    // is the discipline applied to whichever one is pressed: locked radiometry, an IMU and
    // GNSS stream on the same clock, and an output directory named for what it contains.
    //
    // This exists because plain video recording had none of that. The 2026-08-01 walk came
    // back with one exposure value across 948 frames and it looked like the lock working;
    // it was not locked at all. The scene was uniformly bright and AE happened to sit on
    // the sensor's ISO floor for half a minute. Step into shade and the same recording
    // would have drifted, and a radiance field would have explained the drift as content.
    //
    // The two also COMPOSE. Press record then capture and the stills land in the video's
    // own directory, sharing its writer and therefore its clock — dense frames for
    // structure plus full-resolution stills at the quiet moments, which is exactly the
    // combination the 09:39 walk should have produced and did not.

    private boolean mVideoOwnsSession = false;
    // Set for the duration of one test-matrix step, so its clip is named for its cell.
    private String mTestTag = null;

    public void setTestTag(String tag) {
        mTestTag = tag;
    }

    /**
     * Claim (or join) a capture session for a video recording.
     *
     * @return the directory the video and its metadata belong in, or null on failure.
     */
    public File beginVideoSession() {
        // THE RECORDING GETS THE REPEATING REQUEST, and gets it before its first frame. A pair
        // warm-up in flight is ended here, whichever branch follows: see
        // StereoRequests.cancelPairs for what M3 looked like when nothing did this.
        Camera2Proxy early = mActivity.getmCamera2Proxy();
        final int[] cut = early == null ? null : early.cancelStereoPairs("video starting");
        mVideoStartHoldMs = cut != null ? PAIR_CANCEL_SETTLE_MS : 0L;
        if (mRunning && mRunDir != null) {
            // A stills run is already up: join it rather than opening a second writer over
            // the top of the first. One session, one clock, one directory.
            mVideoOwnsSession = false;
            mVideoActive = true;
            if (mManifest != null) {
                mManifest.noteVideoRequested();
                if (cut != null) {
                    // The run's own anchor, cut short on purpose. Said in the receipt, so that
                    // "two pairs of six" reads as a decision and not as four lost captures.
                    mManifest.notePairSequenceCut(cut[0], cut[1], "video joined");
                }
            }
            notifyState(mMode + " · stills + video");
            Log.i(TAG, "video joining the active " + mMode + " run in " + mRunDir);
            return mRunDir;
        }
        // A test-matrix clip names its own cell. Without this the cell lives only in whatever the
        // operator remembers, and a matrix whose cells cannot be told apart afterwards is not a
        // matrix.
        String prefix = mMode.name().toLowerCase(Locale.US) + "_vid";
        if (mTestTag != null) {
            prefix = "test" + mTestTag + "_" + prefix;
        }
        File dir = mActivity.newCaptureDir(prefix);
        if (dir == null) {
            return null;
        }
        mVideoOwnsSession = true;
        mVideoActive = true;
        mRunDir = dir;
        // A fresh session counts from zero. Without this a video-only clip inherits the shot
        // count of whatever stills run came before it and its receipt claims stills it never
        // took -- a manifest is only worth having if nothing in it is left over.
        mShots = 0;
        mManifest = SessionReceipts.open(mActivity, dir, mMode.name(), mTestTag);
        mManifest.noteVideoRequested();
        // A verdict left over from the previous clip would be reported against this one, and
        // "the last recording was fine" is not a statement about this recording.
        TextureMovieEncoder.clearLastFileVerdict();
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        // ...and so would the previous session's pairs. The stereo counters were reset where
        // pairs are STARTED -- a stills run's anchor, the composite, a periodic run -- so a
        // video with no interval, which starts none, reset nothing and sealed with whatever
        // the session before it had fired. M2 on 2026-09-21, straight after an M1 that shot six
        // pairs: "6 armed, 12 rows", no stereo file on the card, agrees: false. They are reset
        // where a session OPENS, here and in startRun, and nowhere a session is merely joined.
        if (proxy != null) {
            proxy.resetStereoCounts();
        }
        mVideoLockedRadiometry = PreferenceManager
                .getDefaultSharedPreferences(mActivity).getBoolean("lock_radiometry", true);
        if (proxy != null && mVideoLockedRadiometry) {
            proxy.lockAutoAlgorithms(true);
        }
        Log.i(TAG, "video session radiometry " + (mVideoLockedRadiometry ? "locked" : "floating"));
        notifyState(mMode == Mode.OBJECT
                ? "OBJECT · video (adds little to a fixed viewpoint)"
                : mMode + " · video");
        Log.i(TAG, "video session started in " + dir + " (mode " + mMode + ")");
        return dir;
    }

    /**
     * How long the restore takes to reach the pixels. Measured on M3f, 2026-09-21: with the
     * warm-up cancelled and the recording started in the same instant, the clip's first four
     * frames were still warm-up frames and the swap landed as a three-row hole at +0.10 s,
     * over by +0.24 s. The pipeline is a few frames deep and a request swap stalls it for
     * four or five more.
     */
    private static final long PAIR_CANCEL_SETTLE_MS = 400L;

    private long mVideoStartHoldMs = 0L;

    /**
     * How long the recording's FIRST FRAME should wait after {@link #beginVideoSession}: zero,
     * unless that call had to end a pair warm-up, in which case the preview it restored needs
     * this long to actually be what the sensor is delivering. The session, its writer and its
     * sensor streams open at once; only the encoder and the frame rows wait.
     */
    public long videoStartHoldMs() {
        return mVideoStartHoldMs;
    }

    /** Release whatever beginVideoSession took, and nothing that it did not. */
    public void endVideoSession() {
        mVideoActive = false;
        if (!mVideoOwnsSession) {
            // The stills run owns the session; it will unlock and close on its own stop.
            // Unless it has already stopped, in which case the video was the last stream
            // standing and sealing the receipt falls here.
            if (!mRunning) {
                sealSession();
            }
            notifyState(mRunning ? mMode + " · stills" : "");
            return;
        }
        mVideoOwnsSession = false;
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy != null && mVideoLockedRadiometry) {
            proxy.lockAutoAlgorithms(false);
        }
        mVideoLockedRadiometry = false;
        File dir = mRunDir;
        // A stills run alongside keeps the session open and will seal it itself.
        if (!mRunning) {
            sealSession();
            mRunDir = null;
        }
        notifyState(mRunning ? mMode + " · stills" : "video saved");
        Log.i(TAG, "video session ended: " + dir);
    }

    /**
     * Write the session its receipt, once, when nothing is still streaming into it.
     *
     * The mp4 is finalised by the encoder on the GL thread after the record button is
     * released, so the file is measured on a short delay -- measuring it the instant the
     * button comes up reports a zero-length video that is about to exist, which is exactly
     * the false alarm this manifest is supposed to make impossible.
     */
    /** Told by the storage guard that this session did not end because the operator said so. */
    public void noteStoppedForSpace() {
        if (mManifest != null) {
            mManifest.noteStoppedForSpace();
        }
    }

    /** The same, for heat. */
    public void noteStoppedForHeat() {
        if (mManifest != null) {
            mManifest.noteStoppedForHeat();
        }
    }

    /**
     * The camera device died under the session. Recorded, not acted on: the activity owns
     * the decision to stop everything, the same way it does for space, heat and charge.
     */
    public void noteCameraError(int error) {
        if (mManifest != null) {
            mManifest.noteCameraError(error);
        }
    }

    /** ...and for charge. */
    public void noteStoppedForBattery() {
        if (mManifest != null) {
            mManifest.noteStoppedForBattery();
        }
    }

    private void sealSession() {
        final SessionManifest manifest = mManifest;
        if (manifest == null) {
            return;
        }
        mManifest = null;
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy != null) {
            // Periodic pairs from a video, plus the one-shot bursts of a stills run's anchor
            // -- which is one burst on the metric pair and six on the all-lens set.
            manifest.noteStereoPairs(proxy.periodicStereoPairs() + proxy.oneShotStereoBursts());
            manifest.noteStereoMetaRows(proxy.stereoMetaRows());
        }
        manifest.noteStillsFired(mShots);
        SessionReceipts.noteLensSet(manifest, proxy);
        final RecordingWriter writer = mActivity.getsRecordingWriter();
        // The same delay that lets the mp4 finalise is what makes the encoder's verdict
        // available: the trailer is written during release(), on the encoder thread, after the
        // record button comes up. Reading it before then would report a file that is still
        // being closed.
        SessionReceipts.noteCost(manifest, mActivity);
        mMain.postDelayed(() -> {
            manifest.noteVideoFileComplete(TextureMovieEncoder.lastFileComplete());
            manifest.write(writer == null ? null : writer.accounting());
        }, 1200L);
    }

    /** True when the video recording, not a stills run, is holding the session open. */
    public boolean videoOwnsSession() {
        return mVideoOwnsSession;
    }

    /** The camera button. Exactly one entry point, whatever the mode. */
    public void onCaptureButton() {
        if (mMode == Mode.OBJECT) {
            mComposite.fire(mMode.name(), mTestTag);
            return;
        }
        if (mRunning) {
            stopRun();
        } else {
            startRun();
        }
    }

    // ------------------------------------------------------------------ continuous run

    private void startRun() {
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy == null) {
            Log.w(TAG, "no camera");
            return;
        }
        if (mVideoActive && mRunDir != null) {
            // JOIN the video's session rather than opening a second one beside it. This is the
            // mirror of beginVideoSession's join, and it was missing: startRun created a new
            // directory unconditionally, so pressing record and then capture put the JPEGs in
            // a fresh "walk_" directory while their metadata went to the video's writer in
            // "walk_vid_". The stills and the rows describing them ended up in two different
            // sessions -- the header above this class has claimed otherwise since v0.13.
            Log.i(TAG, "stills joining the active video session in " + mRunDir);
        } else {
            mRunDir = mActivity.newCaptureDir(mMode.name().toLowerCase(Locale.US));
            if (mRunDir == null) {
                return;
            }
        }
        if (mManifest == null) {
            mManifest = SessionReceipts.open(mActivity, mRunDir, mMode.name(), mTestTag);
            // This run OPENED the session, so its pairs count from zero. Not when it joins a
            // video's: that session's periodic pairs are already being counted.
            proxy.resetStereoCounts();
        }
        mManifest.noteStillsRequested();
        mWriter = mActivity.getsRecordingWriter();
        mOwnsWriter = false;
        if (!mWriter.isRecording()) {
            try {
                mWriter.startRecording(new File(mRunDir, "video_meta.pb3").getAbsolutePath());
                mOwnsWriter = true;
            } catch (IOException e) {
                Log.e(TAG, "could not open metadata file: " + e);
                return;
            }
        }
        // The IMU stream must be recorded alongside: it is what lets the blur PREDICTED
        // at trigger time be graded against the blur actually achieved at each shutter.
        // Thermal too. Only the video path started this, so every stills run this fork has
        // recorded has an empty thermal column -- including the 20 s WALK run on 2026-09-03,
        // graded ABSENT. A stills WALK is hundreds of full-resolution shots and RAW writes,
        // which is the hottest thing this app does; if throttling is going to change what the
        // sensor delivers, this is the run where it happens.
        mActivity.startSensorStreams(mWriter);

        // Lock the auto algorithms for the whole run so every frame shares one
        // radiometry; a drifting AE would make the splat explain brightness as content.
        proxy.lockAutoAlgorithms(true);

        // Quality is set by mode rather than by the operator, because the right answer
        // differs and neither is a preference. Measured on identical pixels (re-encoding
        // one frame, so noise cannot confound it): q90 is 44% of the size of the device
        // default for a gradient-field error of ~0.87 luma units per pixel step, against
        // typical texture gradients of tens of units. A WALK run is hundreds of frames
        // and storage-bound, so it takes the halving; OBJECT and PANO are a handful of
        // frames where the storage is irrelevant and the detail is the point.
        StillCaptureManager scm = proxy.getStillCaptureManager();
        if (scm != null) {
            scm.setJpegQuality(mMode == Mode.WALK ? 90 : 0);
        }

        mShots = 0;
        mRunning = true;
        mEndRawPending = false;
        mActivity.getmImuManager().setStillnessTrigger(mTrigger);
        mTrigger.start(SystemClock.elapsedRealtimeNanos());

        // A RAW at the start, one at the end: the JPEGs between them are 8-bit with a
        // tone curve, and these two give the run a linear reference to check against.
        captureNow(StillCaptureManager.Mode.SINGLE, 1, true, 0f, 0f, false);

        // THE METRIC ANCHOR. Until now the paired-lens still was a property of the VIDEO
        // recording -- startPeriodicStereo had exactly one caller and it was inside
        // startRecording() -- so a stills run produced none at all. The archive says it
        // plainly: 89 single stills across three stills-only walks and not one pair, while
        // every session that got pairs was recording video.
        //
        // That is not a missing convenience. Main and ultrawide fire together from a fixed
        // 18.02 mm separation and it is the only thing in a handheld capture that fixes
        // SCALE: GNSS is a 3.8 m receiver, the IMU gives gravity but not distance, and
        // structure-from-motion is scale-free by construction. A walk without a pair is a
        // shape, not a measurement -- which is what the 60-still column orbit turned out to
        // be, the best-conditioned capture in the project and unscaleable.
        //
        // OBJECT already had the right instinct and ends its composite with one pair. WALK
        // gets the same guarantee, plus the periodic stream when the operator has asked for
        // an interval.
        startRunStereo(proxy);

        notifyState(mMode + " · stills");
        Log.i(TAG, "run started in " + mRunDir);
    }

    /**
     * Pairs for a stills run: the periodic stream if an interval is set, otherwise a single
     * anchoring pair once the opening RAW has drained.
     *
     * The two are exclusive by construction -- captureStereoPair declines while periodic
     * pairs are running, because a one-shot warm-up would swap the repeating request out
     * from under them -- so asking for both is safe and the interval wins.
     */
    private void startRunStereo(Camera2Proxy proxy) {
        StillCaptureManager scm = proxy.getStillCaptureManager();
        if (scm == null || !scm.stereo().stereoSupported()) {
            Log.i(TAG, "no stereo pair available on this device: run has no metric anchor");
            return;
        }
        int intervalS = PreferenceManager
                .getDefaultSharedPreferences(mActivity).getInt("stereo_interval_s", 0);
        StillCaptureManager.CaptureMode cm = mMode == Mode.PANO
                ? StillCaptureManager.CaptureMode.PANO
                : StillCaptureManager.CaptureMode.WALK;
        if (intervalS > 0) {
            proxy.startPeriodicStereo(intervalS * 1000L, mRunDir, mWriter, cm);
            return;
        }
        if (mVideoActive) {
            // A video is recording on this session. The one-shot pair warms up by replacing
            // the repeating request with a TEMPLATE_PREVIEW copy for ~900 ms, which is the
            // recording's own request; the clip would take the hit for the anchor. Video
            // sessions get their pairs from the interval instead, which puts both physical
            // streams in the RECORD request and leaves them there.
            Log.i(TAG, "video is recording: leaving the anchor pair to the interval");
            return;
        }
        // One pair, after the opening RAW: the warm-up puts both physical streams into the
        // repeating request for ~900 ms and restores the preview afterwards, so it must not
        // land on top of a burst that is still draining.
        final File dir = mRunDir;
        final RecordingWriter writer = mWriter;
        mMain.postDelayed(() -> {
            if (!mRunning) {
                return;
            }
            if (mVideoActive) {
                // Asked at the moment of firing, not only when this was scheduled: a video that
                // joined in the last 1.8 s is recording on the request this would replace.
                Log.i(TAG, "video joined before the anchor pair fired: leaving it to the interval");
                return;
            }
            Camera2Proxy p = mActivity.getmCamera2Proxy();
            if (p != null) {
                Log.i(TAG, "firing the run's anchoring stereo pair");
                p.captureStereoPair(dir, writer, cm);
            }
        }, 1800L);
    }

    private void stopRun() {
        mRunning = false;
        mTrigger.stop();
        mActivity.getmImuManager().setStillnessTrigger(null);

        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy != null) {
            mEndRawPending = true;
            captureNow(StillCaptureManager.Mode.SINGLE, 1, true, 0f, 0f, false);
            proxy.lockAutoAlgorithms(false);
            // Only if the video is not still running on the same session: the pairs belong
            // to whichever stream is still open, and taking the physical streams out of a
            // live recording's request mid-clip is the one thing that must not happen here.
            if (!mVideoActive) {
                proxy.stopPeriodicStereo();
            }
        }
        final int shots = mShots;
        final RecordingWriter writer = mWriter;
        final boolean owns = mOwnsWriter;
        // Let the closing RAW drain before the metadata file is sealed.
        mMain.postDelayed(() -> {
            // ...and tear the streams down only if this run is the last thing using them.
            // The video path has guarded this direction since v0.13 ("only if the video owned
            // them"); this direction never did, so stopping a stills run while a video was
            // recording on the same session closed the IMU, GNSS and thermal streams under a
            // live clip -- and, when the stills run had opened it, the metadata writer itself.
            // The video kept recording pictures and stopped recording anything that explains
            // them, which no file in the archive would show as anything but a short session.
            if (mVideoActive) {
                notifyState(mMode + " · video");
                Log.i(TAG, "stills stopped; video still recording, streams left open");
                return;
            }
            mActivity.stopSensorStreams();
            if (owns && writer != null) {
                writer.stopRecording();
            }
            notifyState(shots + " shots");
            sealSession();
        }, 2500L);
        Log.i(TAG, "run stopped after " + shots + " shots");
    }

    @Override
    public void onQuietMoment(long timestampNs, float predictedBlurPx, float omega,
                              boolean forced) {
        // Sensor thread. Hop to main: the camera session is driven from there.
        mMain.post(() -> {
            if (!mRunning) {
                return;
            }
            if (mMode == Mode.PANO) {
                // Fixed viewpoint, so a bracket per position is correct and mergeable.
                captureNow(StillCaptureManager.Mode.EXPOSURE_BRACKET, 3, false,
                        predictedBlurPx, omega, forced);
            } else {
                captureNow(StillCaptureManager.Mode.SINGLE, 1, false,
                        predictedBlurPx, omega, forced);
            }
        });
    }

    private void captureNow(StillCaptureManager.Mode burstMode, int shots, boolean raw,
                            float predictedBlurPx, float omega, boolean forced) {
        // Counted when ASKED, not when the request is issued. The trigger decided six shots
        // on 2026-09-20 with the camera dead from the second second; four of them fell out
        // here and the receipt said two were fired and one landed. Six asked, one landed is
        // the truth, and the receipt's shortfall is only honest if it counts the asking.
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy != null && proxy.isStereoSequenceActive()) {
            // Not asked of the camera, so not counted: a JPEG burst replaces the repeating
            // request, and the pair being warmed under it would come back with a cold lens.
            // The trigger will ask again at the next quiet moment.
            Log.i(TAG, "quiet moment passed over: lens pairs in flight");
            return;
        }
        mShots += shots;
        if (proxy == null || proxy.getStillCaptureManager() == null) {
            Log.w(TAG, "shot asked for with no camera to take it");
            return;
        }
        StillCaptureManager.CaptureMode cm =
                mMode == Mode.PANO ? StillCaptureManager.CaptureMode.PANO
                        : StillCaptureManager.CaptureMode.WALK;
        proxy.getStillCaptureManager().setTriggerContext(cm, predictedBlurPx, omega, forced);
        // Feed the trigger the optics it should be modelling: exposure moves by orders
        // of magnitude between sun and shade, and a stale value is the wrong budget.
        proxy.refreshTriggerOptics(mTrigger);
        proxy.captureStills(burstMode, shots, 2.0f, raw, mRunDir, mWriter);
    }

    /**
     * Tell the UI what is running, reading the state rather than being told it.
     *
     * Every call site used to pass its own idea of "running", which is how a video session
     * came to announce itself as a live stills run. The caller now supplies only the sentence;
     * the facts come from the fields that actually hold them.
     */
    private void notifyState(String summary) {
        if (mStateListener == null) {
            return;
        }
        final RunState state =
                new RunState(mRunning, mVideoActive, mComposite.isActive(), summary);
        // The storage watch follows the session, and this is the one place that knows when a
        // session begins and ends whichever control opened it. Free space is not checked while
        // the app merely sits at the preview: nothing is being written then, and a statfs every
        // two seconds for nothing is exactly the kind of idle cost #37 is about.
        StorageGuard guard = mActivity.getStorageGuard();
        if (guard != null) {
            if (state.anyActive) {
                guard.begin();
            } else {
                guard.end();
            }
        }
        BatteryGuard battery = mActivity.getBatteryGuard();
        if (battery != null) {
            if (state.anyActive) {
                battery.begin();
            } else {
                battery.end();
            }
        }
        mMain.post(() -> mStateListener.onRunStateChanged(state));
    }
}
