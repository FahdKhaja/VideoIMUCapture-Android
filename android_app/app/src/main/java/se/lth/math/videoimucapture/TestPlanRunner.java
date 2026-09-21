package se.lth.math.videoimucapture;

import android.content.SharedPreferences;
import android.os.Handler;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The test matrix (see {@link TestPlan}), one button per cell.
 *
 * The operator picks a cell; the app sets that cell's settings, shows the one thing it cannot
 * set -- how to hold the phone -- counts down, records for a fixed time, stops itself, and
 * puts the operator's own settings back. The clip lands in a directory named for the cell.
 *
 * Fixed duration and self-stopping are the point, not conveniences: two clips of different
 * lengths, or two clips whose settings drifted because a run was aborted halfway, do not
 * compare, and a comparison is the only reason any of these clips exist.
 *
 * WHAT the cells are is {@link TestPlan}'s. This is the three dialogs and the runner that
 * presses the app's own buttons on a cell's schedule. tools/drive_cell.ps1 finds its way
 * through these dialogs by their TEXT, so a change of wording here is a change to that script.
 */
final class TestPlanRunner {

    private final CameraCaptureActivity mActivity;

    TestPlanRunner(CameraCaptureActivity activity) {
        mActivity = activity;
    }

    // Read at each use rather than captured, as the activity's fields were: a cell runs on
    // delayed posts for half a minute, and the activity may have re-initialised its camera.
    private CaptureModeManager modes() {
        return mActivity.getmCaptureModeManager();
    }

    private Camera2Proxy proxy() {
        return mActivity.getmCamera2Proxy();
    }

    /**
     * The matrix, as three lists rather than one.
     *
     * One flat list of every cell became unusable the moment there were more than a handful:
     * everything in it was equally present, so nothing said what to go and shoot, and the
     * operator was left reading two dozen titles to work out which ones were the ask. Cutting
     * cells did not fix that -- it cost the ability to reshoot settled questions and still
     * left no order to the rest.
     *
     * So: TO SHOOT is the standing request minus whatever this phone has already done, and it
     * empties as the work gets done. DONE is the history, newest first, so a cell can be found
     * again and reshot when the code underneath it changes. ALL is the catalogue.
     */
    void show() {
        final SharedPreferences sp =
                PreferenceManager.getDefaultSharedPreferences(mActivity);
        final int todo = TestPlan.outstanding(sp).size();
        final int done = TestPlan.completed(sp).size();
        final String[] views = {
                "To shoot  (" + todo + ")",
                "Done  (" + done + ")",
                "All cells  (" + TestPlan.steps().size() + ")"};
        new AlertDialog.Builder(mActivity)
                .setTitle("Test matrix")
                .setItems(views, (d, which) -> {
                    if (which == 0) {
                        showCellList("To shoot", TestPlan.outstanding(sp), sp);
                    } else if (which == 1) {
                        showCellList("Done", TestPlan.completed(sp), sp);
                    } else {
                        showCellList("All cells", TestPlan.steps(), sp);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showCellList(String heading, List<TestPlan.Step> steps,
                              SharedPreferences sp) {
        if (steps.isEmpty()) {
            new AlertDialog.Builder(mActivity)
                    .setTitle(heading)
                    .setMessage("Nothing here. Everything asked for has been shot.")
                    .setPositiveButton("Back", (d, w) -> show())
                    .show();
            return;
        }
        SimpleDateFormat fmt =
                new SimpleDateFormat("d MMM", Locale.US);
        String[] titles = new String[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            TestPlan.Step s = steps.get(i);
            long at = TestPlan.doneAt(sp, s.id);
            // A tick and the date it was shot, because "have I done this one" is the whole
            // question the operator is holding while they read the list.
            titles[i] = at > 0
                    ? "✓  " + s.title + "   · " + fmt.format(new Date(at))
                    : s.title;
        }
        final List<TestPlan.Step> shown = steps;
        new AlertDialog.Builder(mActivity)
                .setTitle(heading)
                .setItems(titles, (d, which) -> confirmTestStep(shown.get(which)))
                .setNegativeButton("Back", (d, w) -> show())
                .show();
    }

    private void confirmTestStep(TestPlan.Step step) {
        final SharedPreferences sp =
                PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean done = TestPlan.isDone(sp, step.id);
        String state = done
                ? "\n\nAlready shot. Running it again replaces the date, not the old session."
                : "";
        AlertDialog.Builder b =
                new AlertDialog.Builder(mActivity)
                        .setTitle(step.title)
                        .setMessage(step.instruction + "\n\nRecords " + step.seconds
                                + " s and stops by itself." + state)
                        .setPositiveButton("Start", (d, w) -> runTestStep(step))
                        .setNegativeButton("Back", (d, w) -> show());
        // A cell can be ticked off by hand, because some of them are answered by data that
        // already exists rather than by pressing the button again -- and a to-do list that
        // cannot be crossed off by hand is one the operator stops trusting.
        b.setNeutralButton(done ? "Mark not done" : "Mark done", (d, w) -> {
            if (done) {
                TestPlan.markNotDone(sp, step.id);
            } else {
                TestPlan.markDone(sp, step.id);
            }
            show();
        });
        b.show();
    }

    private void runTestStep(TestPlan.Step step) {
        CameraCaptureFragment frag = mActivity.getmCameraCaptureFragment();
        if (frag == null || modes() == null) {
            return;
        }
        final SharedPreferences sp =
                PreferenceManager.getDefaultSharedPreferences(mActivity);
        // Asked BEFORE the settings are written, because the question is whether this cell
        // changes one of them.
        final boolean rebuild = step.needsSessionRebuild(sp) && proxy() != null;
        final Map<String, Object> previous = TestPlan.apply(sp, step);
        // OIS and its data mode live in the capture REQUEST, not in the recording, so a change
        // has to be pushed to the running session or the clip records the previous state while
        // the file says otherwise -- which is exactly the class of quiet mismatch this app keeps
        // finding in itself.
        if (proxy() != null) {
            proxy().reapplyCameraSettings();
        }
        // ...and the lens set does not live in the request at all: it decides which streams the
        // session was built with. A cell that asks for it has to rebuild the session, or it
        // shoots the previous configuration and files it under this cell's name. The two L1
        // runs on 2026-09-20 are what that looks like -- one on the pair, one on all four, both
        // labelled L1, neither receipt saying which.
        if (rebuild) {
            Toast.makeText(mActivity, step.id + ": rebuilding the camera session",
                    Toast.LENGTH_SHORT).show();
            proxy().reconfigureLensStreams();
        }
        modes().setTestTag(step.id);
        // A cell that names a mode sets it, because the mode is in-memory state rather than a
        // preference and TestPlan.apply cannot reach it. Put the operator's mode back at the
        // end along with everything else the cell changed.
        final CaptureModeManager.Mode previousMode = modes().getMode();
        if (step.mode != null) {
            modes().setMode(step.mode);
            frag.refreshModeHighlight();
        }

        final Handler h = new Handler(mActivity.getMainLooper());
        final int[] countdown = {3};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (countdown[0] > 0) {
                    Toast.makeText(mActivity,
                            step.id + " in " + countdown[0], Toast.LENGTH_SHORT)
                            .show();
                    countdown[0]--;
                    h.postDelayed(this, 1000L);
                    return;
                }
                Toast.makeText(mActivity,
                        "recording " + step.id + " for " + step.seconds + " s",
                        Toast.LENGTH_SHORT).show();
                long end = runStreams(frag, step, h);
                h.postDelayed(() -> {
                    modes().setTestTag(null);
                    if (step.mode != null) {
                        modes().setMode(previousMode);
                        frag.refreshModeHighlight();
                    }
                    TestPlan.restore(sp, previous);
                    if (proxy() != null) {
                        proxy().reapplyCameraSettings();
                        // Put the streams back too. Restoring the preference alone would leave
                        // the operator's next capture running on the cell's configuration,
                        // which is the same bug pointed the other way.
                        if (rebuild) {
                            proxy().reconfigureLensStreams();
                        }
                    }
                    // Ticked off here, at the end of the cell that actually ran, so the list
                    // empties as the work is done rather than as it is started. A cell that
                    // was abandoned half way -- the app backgrounded, the camera taken -- does
                    // not reach this and stays on the list, which is the right answer.
                    TestPlan.markDone(sp, step.id);
                    int left = TestPlan.outstanding(sp).size();
                    Toast.makeText(mActivity,
                            step.id + " done — " + left + " left to shoot",
                            Toast.LENGTH_LONG).show();
                }, end);
            }
        };
        // The rebuilt session is not usable the instant reconfigureLensStreams returns:
        // createCaptureSession is asynchronous and the preview has to come back before the
        // cell presses anything at it.
        h.postDelayed(tick, rebuild ? Camera2Proxy.SESSION_REBUILD_MS : 0L);
    }

    /**
     * Press the cell's controls on its schedule, and return when the last one has been
     * released so the settings can be put back after it rather than during it.
     *
     * The second stream is offset by {@link #STREAM_OFFSET_MS} rather than started with the
     * first: the two paths hand the session between them, and starting them in the same
     * millisecond would test the race instead of the combination.
     */
    private long runStreams(CameraCaptureFragment frag, TestPlan.Step step,
                            Handler h) {
        final long d = step.seconds * 1000L;
        switch (step.streams) {
            case STILLS:
                modes().onCaptureButton();
                h.postDelayed(() -> modes().onCaptureButton(), d);
                return d + TAIL_MS;
            case STILLS_THEN_VIDEO:
                modes().onCaptureButton();
                h.postDelayed(() -> frag.clickToggleRecording(null), STREAM_OFFSET_MS);
                h.postDelayed(() -> frag.clickToggleRecording(null), d);
                h.postDelayed(() -> modes().onCaptureButton(), d + STREAM_OFFSET_MS);
                return d + STREAM_OFFSET_MS + TAIL_MS;
            case VIDEO_THEN_STILLS:
                frag.clickToggleRecording(null);
                h.postDelayed(() -> modes().onCaptureButton(), STREAM_OFFSET_MS);
                h.postDelayed(() -> modes().onCaptureButton(), d);
                h.postDelayed(() -> frag.clickToggleRecording(null), d + STREAM_OFFSET_MS);
                return d + STREAM_OFFSET_MS + TAIL_MS;
            case COMPOSITE:
                // One press runs the whole composite and ends it; nothing stops this cell.
                modes().onCaptureButton();
                return d + TAIL_MS;
            case VIDEO:
            default:
                frag.clickToggleRecording(null);
                h.postDelayed(() -> frag.clickToggleRecording(null), d);
                return d + TAIL_MS;
        }
    }

    /** How long after the first control the second one is pressed, in a two-stream cell. */
    private static final long STREAM_OFFSET_MS = 4000L;
    /**
     * Slack after the last release before the cell's settings are restored. A stills run takes
     * 2.5 s to drain its closing RAW and seal the session, and putting the settings back under
     * it would change the capture the manifest is about to describe.
     */
    private static final long TAIL_MS = 5000L;
}
