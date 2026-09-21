package se.lth.math.videoimucapture;



/**
 * What is actually running, for the UI.
 *
 * One boolean used to carry all of this, and it could not, because the two start controls
 * are independent: during a video-only recording something IS running, but a stills run is
 * NOT, and those two facts drive different parts of the screen. Collapsing them put the
 * capture button into its stop state during a plain video clip -- where pressing it does
 * not stop anything, it starts a stills run.
 */
public final class RunState {
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
