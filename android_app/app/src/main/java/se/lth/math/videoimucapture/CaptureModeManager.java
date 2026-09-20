package se.lth.math.videoimucapture;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;

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

    /**
     * What is actually running, for the UI.
     *
     * One boolean used to carry all of this, and it could not, because the two start controls
     * are independent: during a video-only recording something IS running, but a stills run is
     * NOT, and those two facts drive different parts of the screen. Collapsing them put the
     * capture button into its stop state during a plain video clip -- where pressing it does
     * not stop anything, it starts a stills run.
     */
    public static final class RunState {
        /** A stills run is live. This is what the capture button is a stop button FOR. */
        public final boolean stillsRunning;
        /** A video recording is live, whether or not it owns the session. */
        public final boolean videoActive;
        /** An OBJECT composite is part-way through its sequence. */
        public final boolean compositeRunning;
        /** Anything at all is going on: what the idle timer and the mode strip care about. */
        public final boolean anyActive;
        public final String summary;

        RunState(boolean stillsRunning, boolean videoActive, boolean compositeRunning,
                 String summary) {
            this.stillsRunning = stillsRunning;
            this.videoActive = videoActive;
            this.compositeRunning = compositeRunning;
            this.anyActive = stillsRunning || videoActive || compositeRunning;
            this.summary = summary;
        }
    }

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
    // An OBJECT composite is a 15-second SEQUENCE of posted stages, not an instant, and the
    // button that fires it had no guard. Two presses started two composites: two directories,
    // two writers racing for the same file, two sets of stage handlers reconfiguring focus and
    // exposure under each other, and a lockAutoAlgorithms(false) from the first landing in the
    // middle of the second. The button gives no hint that it is busy, so this was one
    // impatient tap away at all times.
    private boolean mCompositeActive = false;

    public CaptureModeManager(CameraCaptureActivity activity) {
        mActivity = activity;
        mTrigger = new StillnessTrigger(this);
    }

    public void setStateListener(StateListener l) {
        mStateListener = l;
    }

    public void setMode(Mode mode) {
        // A video recording and a composite own the mode just as a stills run does: the mode
        // is the discipline the clip is being shot under and it names the directory, so
        // changing it mid-clip makes the name a lie about what the frames were. The strip is
        // dimmed for all three; this refuses the change if anything gets past that.
        if (mRunning || mVideoActive || mCompositeActive) {
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
        if (mRunning && mRunDir != null) {
            // A stills run is already up: join it rather than opening a second writer over
            // the top of the first. One session, one clock, one directory.
            mVideoOwnsSession = false;
            mVideoActive = true;
            if (mManifest != null) {
                mManifest.noteVideoRequested();
            }
            notifyState(mMode + " · stills + video");
            Log.i(TAG, "video joining the active " + mMode + " run in " + mRunDir);
            return mRunDir;
        }
        // A test-matrix clip names its own cell. Without this the cell lives only in whatever the
        // operator remembers, and a matrix whose cells cannot be told apart afterwards is not a
        // matrix.
        String prefix = mMode.name().toLowerCase(java.util.Locale.US) + "_vid";
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
        mManifest = new SessionManifest(mActivity, dir, mMode.name(), mTestTag);
        mManifest.noteFreeAtStart(StorageGuard.freeBytes(new File(mActivity.getResultRoot())));
        mManifest.noteVideoRequested();
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        mVideoLockedRadiometry = androidx.preference.PreferenceManager
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

    private void sealSession() {
        final SessionManifest manifest = mManifest;
        if (manifest == null) {
            return;
        }
        mManifest = null;
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy != null) {
            manifest.noteStereoPairs(proxy.periodicStereoPairs());
        }
        manifest.noteStillsFired(mShots);
        final RecordingWriter writer = mActivity.getsRecordingWriter();
        mMain.postDelayed(() -> manifest.write(writer == null ? null : writer.accounting()),
                1200L);
    }

    /** True when the video recording, not a stills run, is holding the session open. */
    public boolean videoOwnsSession() {
        return mVideoOwnsSession;
    }

    /** The camera button. Exactly one entry point, whatever the mode. */
    public void onCaptureButton() {
        if (mMode == Mode.OBJECT) {
            fireObjectComposite();
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
            mRunDir = mActivity.newCaptureDir(mMode.name().toLowerCase(java.util.Locale.US));
            if (mRunDir == null) {
                return;
            }
        }
        if (mManifest == null) {
            mManifest = new SessionManifest(mActivity, mRunDir, mMode.name(), mTestTag);
            mManifest.noteFreeAtStart(StorageGuard.freeBytes(new File(mActivity.getResultRoot())));
        }
        mManifest.noteStillsRequested();
        mWriter = mActivity.getsRecordingWriter();
        mOwnsWriter = false;
        if (!mWriter.isRecording()) {
            try {
                mWriter.startRecording(new File(mRunDir, "video_meta.pb3").getAbsolutePath());
                mOwnsWriter = true;
            } catch (java.io.IOException e) {
                Log.e(TAG, "could not open metadata file: " + e);
                return;
            }
        }
        // The IMU stream must be recorded alongside: it is what lets the blur PREDICTED
        // at trigger time be graded against the blur actually achieved at each shutter.
        mActivity.getmImuManager().startRecording(mWriter);
        mActivity.getmGnssLogger().startRecording(mWriter);
        // Thermal too. Only the video path started this, so every stills run this fork has
        // recorded has an empty thermal column -- including the 20 s WALK run on 2026-09-03,
        // graded ABSENT. A stills WALK is hundreds of full-resolution shots and RAW writes,
        // which is the hottest thing this app does; if throttling is going to change what the
        // sensor delivers, this is the run where it happens.
        mActivity.getmThermalLogger().startRecording(mWriter);

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
        mTrigger.start(android.os.SystemClock.elapsedRealtimeNanos());

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
        if (scm == null || !scm.stereoSupported()) {
            Log.i(TAG, "no stereo pair available on this device: run has no metric anchor");
            return;
        }
        int intervalS = androidx.preference.PreferenceManager
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
            Camera2Proxy p = mActivity.getmCamera2Proxy();
            if (p != null) {
                Log.i(TAG, "firing the run's anchoring stereo pair");
                p.captureStereoPair(dir, writer);
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
            mActivity.getmImuManager().stopRecording();
            mActivity.getmGnssLogger().stopRecording();
            mActivity.getmThermalLogger().stopRecording();
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
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy == null || proxy.getStillCaptureManager() == null) {
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
        mShots += shots;
    }

    // ------------------------------------------------------------------- object mode

    private void fireObjectComposite() {
        if (mCompositeActive) {
            Log.w(TAG, "composite already running; ignoring the press");
            return;
        }
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy == null) {
            return;
        }
        File dir = mActivity.newCaptureDir("object");
        if (dir == null) {
            return;
        }
        mCompositeActive = true;
        // OBJECT runs its own session start to finish and never joins one, so its receipt is
        // local to the composite rather than the field the two continuous paths share.
        final SessionManifest manifest = new SessionManifest(mActivity, dir, mMode.name(), mTestTag);
        manifest.noteStillsRequested();
        RecordingWriter writer = mActivity.getsRecordingWriter();
        boolean owns = false;
        if (!writer.isRecording()) {
            try {
                writer.startRecording(new File(dir, "video_meta.pb3").getAbsolutePath());
                owns = true;
            } catch (java.io.IOException e) {
                Log.e(TAG, "could not open metadata file: " + e);
                return;
            }
        }
        // OBJECT recorded no IMU and no GNSS at all — measured across every stack from
        // 07-31 and 08-01: imu=0, gnss=0 in each. startRun() begins those streams for WALK
        // and PANO and this path simply never did. A tripod composite still wants both: the
        // stereo pair's baseline is metric but its POSITION is not, the orientation stamped
        // on each still comes from a stream that was not being written, and a stack shot
        // beside a walk cannot be tied to it without a shared clock carrying shared motion.
        mActivity.getmImuManager().startRecording(writer);
        mActivity.getmGnssLogger().startRecording(writer);
        // ...and thermal, for the same reason and with the same history: an OBJECT composite is
        // a focus stack plus brackets plus a stereo pair, minutes of full-resolution work.
        mActivity.getmThermalLogger().startRecording(writer);
        proxy.lockAutoAlgorithms(true);
        StillCaptureManager scm = proxy.getStillCaptureManager();
        if (scm != null) {
            scm.setTriggerContext(StillCaptureManager.CaptureMode.OBJECT, 0f, 0f, false);
        }

        // Sequenced rather than concurrent: each stage reconfigures the request, and a
        // burst must finish draining before the next changes focus or exposure under it.
        //
        // The focus stack is FIRST and gets the longest slot. It is the only stage that
        // waits on hardware: each of its five slices parks the voice coil and waits for the
        // lens to report it has arrived, up to 400 ms per step plus 120 ms of spacing. Five
        // slices is therefore 2.6 s worst case against the 167 ms the burst version took —
        // which is the whole reason that version came back with five identical pictures.
        notifyState("OBJECT · focus stack");
        proxy.captureFocusStack(5, false, dir, writer);

        mMain.postDelayed(() -> {
            notifyState("OBJECT · exposure bracket");
            proxy.captureStills(StillCaptureManager.Mode.EXPOSURE_BRACKET, 5, 2.0f,
                    false, dir, writer);
        }, 4000L);

        mMain.postDelayed(() -> {
            notifyState("OBJECT · full-quality RAW");
            proxy.captureStills(StillCaptureManager.Mode.SINGLE, 1, 0f, true, dir, writer);
        }, 7000L);

        // The stereo pair. Last, because it is the one stage whose value does not
        // degrade if the operator has already drifted — both frames are simultaneous,
        // so the 18.02 mm baseline between them holds regardless of what the hand did
        // before it. Everything else in this composite is monocular and therefore
        // scale-free; this is the stage that makes the capture metric.
        final boolean ownsWriter = owns;
        final boolean hasStereo = scm != null && scm.stereoSupported();
        if (hasStereo) {
            mMain.postDelayed(() -> {
                notifyState("OBJECT · stereo pair (metric scale)");
                proxy.captureStereoPair(dir, writer);
            }, 10000L);
        }

        mMain.postDelayed(() -> {
            proxy.lockAutoAlgorithms(false);
            // Stop the streams this composite started — but only if it owns the session.
            // If a video recording is running alongside, killing the IMU here would blind
            // it mid-clip.
            if (ownsWriter) {
                mActivity.getmImuManager().stopRecording();
                mActivity.getmGnssLogger().stopRecording();
                mActivity.getmThermalLogger().stopRecording();
                writer.stopRecording();
            }
            mCompositeActive = false;
            notifyState(hasStereo ? "OBJECT complete + stereo" : "OBJECT complete");
            manifest.noteStereoPairs(hasStereo ? 1 : 0);
            mMain.postDelayed(() -> manifest.write(writer.accounting()), 1200L);
            Log.i(TAG, "object composite complete: " + dir);
        }, hasStereo ? 15500L : 10500L);   // stereo adds a warm-up before its capture
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
                new RunState(mRunning, mVideoActive, mCompositeActive, summary);
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
        mMain.post(() -> mStateListener.onRunStateChanged(state));
    }
}
