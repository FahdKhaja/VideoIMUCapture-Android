# Findings

What the analysis scripts have settled, what they have not, and what that means for data
already shot. Dated, newest first. One phone: Galaxy S24 Ultra, Android 16. Every number here
is reproducible from the named session with the named script; the sessions themselves are not
in git (see [README.md](README.md)).

## 2026-09-20

Sessions: `L1` 19:02 and `M5` 19:05 (before any fix), zoom probe 19:31, `L1c` 19:53 on
`bfc822f`, `L1d` 20:09 on `09e5e47`, then W1/W2/N1/N2/M5 driven from the desk on `7e316fd`.

### 1. The ultrawide stream is cropped toward the main camera, and the HAL says it is not

`analyze_pairs.py`, similarity scale ultrawide -> main. The census intrinsics predict 1.636.

| session | zoom | measured | inliers | |
|---|---|---|---|---|
| L1 | 1.0 | 1.009 | 19/142 | same framing as the main camera |
| M5 | 1.0 | 1.008 | 20/137 | same |
| bare probe session | 1.0 | 1.181 | 439/713 | a *different* crop |

Every row in those sessions reports `crop_region` as the full 4000x3000 array. The crop is in
the HAL's stream path (Samsung's SAT), not in the request, and it **varies with the session**:
1.62x inside the app, 1.39x in a bare probe session.

So at zoom 1.0 the census ultrawide intrinsics do not describe the ultrawide stream. Its
effective focal at 1080p measured 1285 px against a census 792.5. The 18.02 mm baseline is
still real — it is where the lens sits — but `Z = f*B/d` with the census `f` underestimates
depth by that factor.

### 2. `CONTROL_ZOOM_RATIO` 0.6 lifts the crop

`zoom_probe.py` on `StereoProbe.probeZoom`'s frames:

| fit | measured | if uncropped | inliers |
|---|---|---|---|
| z1.0 uw -> z1.0 main (control) | 1.181 | 1.636 | 439/713 |
| **z0.6 uw -> z1.0 main** | **1.658** | 1.636 | 335/544 |
| z0.6 uw -> z1.0 uw | 1.404 | 1.636 | 871/1017 |
| z0.6 uw targeted alone -> z1.0 main | 1.660 | 1.636 | 406/613 |
| z1.0 uw targeted alone -> z1.0 main | 1.182 | 1.636 | 412/639 |
| z0.6 main -> z1.0 main | 1.000 | 1.000 | 2374/2511 |

At 0.6 the ultrawide stream is the wide lens; the main camera's stream does not change; and
the crop is a consequence of zoom 1.0, not of pairing the lenses. The pair warm-ups now put the
REPEATING request at 0.6 before a pair is kept (`applyFullFieldOfView`). A one-shot request at
0.6 dropped into a stream running at 1.0 reported 1.0 in five bursts of six.

The periodic path — pairs kept inside a video — deliberately does **not** do this: that request
drives the video, and 0.6 would switch every clip to the wide lens.

The first probe run failed with "disabled by policy". That was the phone locking mid-run and
revoking the camera, not the HAL. The probe now holds the screen on, runs its zoom stage before
the streaming stage, and reports a refused open instead of returning nothing.

### 3. Field 29 caught the rows describing a frame the file did not hold

`L1c` had the right pixels (uw -> main 1.646, 35/84; main -> 3x 2.670, 67/234) and wrong rows.
`dump_meta.py`: all twelve stereo rows were WARM-UP FRAMES, `time_ns` 213-634 ms before
`logical_result_time_ns`, with `zoom_ratio` 1.00 on pixels shot at 0.6, and three pairs of six
with halves one frame (41.8 ms) apart. The row described the still request's frame; the file
held a warm-up frame an armed reader had already kept.

Fixed in `09e5e47`: there is no still request. A pair is chosen from the warmed stream by
target timestamp — the periodic path's mechanism — and its rows come from the matching
repeating result. `L1d` confirms it: all twelve rows `REQUEST'S FRAME`, zoom 0.60, `|dt|`
0.000 ms on every pair, receipt agrees, on a build labelled with its own commit.

Also fixed on the way: `burst_size` said 4 on a pair burst (now 2), and one-shot rows did not
carry the image's own stamp, so simultaneity was an assumption rather than a reading.

### 4. At zoom 0.6 the ultrawide, main and 3x streams carry the census intrinsics

| | L1c | L1d | census |
|---|---|---|---|
| uw -> main | 1.646 (35/84) | 1.656 (88/139) | 1.636 |
| main -> 3x | 2.670 (67/234) | 2.630 (29/84) | 2.655 |
| uw effective f at 1920 | 788.0 px | 783.1 px | 792.5 px |
| 3x effective f at 1920 | 3462.6 px | 3410.2 px | 3443.5 px |

`overlay.py` on L1d, placing each lens inside the ultrawide frame: main x1.657 (94 inliers)
against census x1.636; 3x x4.335 against x4.345, chained through the main camera (36 inliers)
because SIFT does not bridge a 4.3x scale jump directly — uw -> 3x fits 10/33 where main -> 3x
fits 29/84.

### 5. No baseline for the 3x or 5x yet, and the 5x has no FOV confirmation

The scene, not the method. Triangulated depth from the uw+main pair was 0.34-0.41 m (L1, M5)
and 0.57-0.78 m (L1c, L1d). The 3x focuses no closer than 0.4 m and the 5x no closer than
0.8 m, so both telephotos sat at their near limit, out of focus, at ISO 2000-3200. No pair
with the 5x in it produced a usable fit in any session (under 12 inliers, or RANSAC collapsed
to a point), and at most 6 main<->tele matches carried a metric point — too few for PnP. G1/G2 at 1-2 m on a flat textured target is the scene this needs, and
`analyze_pairs.py`'s FOV table has to come back sane for all four lenses on that shot before
any baseline from it is believed.

### 6. N1 vs N2: the HAL's denoise removes most of what the ultrawide sensor produces

`wn_compare.py`, one pair per cell, same scene:

| cell | lens | noise sigma | detail | JPEG bytes |
|---|---|---|---|---|
| N1 as shipped | main | 1.48 | 23.41 | 494,343 |
| N1 as shipped | uw | 1.48 | 18.10 | 472,677 |
| N2 edge + NR off | main | 2.97 | 37.92 | 820,274 |
| N2 edge + NR off | uw | 8.90 | 104.03 | 1,391,101 |

Turning the HAL's processing off doubles residual noise on the main camera and multiplies it
by six on the ultrawide, with JPEGs 1.7x and 2.9x larger. Every frame shot before the setting
existed had that processing on. One dark indoor scene; the ratio will differ in daylight.

### 7. W1 vs W2: no detectable change on the main camera; the ultrawide is unanswered

Three matched long edges in the outer 30% of the main frame, RMS deviation from a straight
line, distortion correction off -> on: 0.39 -> 0.48, 0.45 -> 0.45, 0.70 -> 0.45 px. All
sub-pixel, differences within noise, consistent with a main lens whose census distortion is
small. On the ultrawide the check was inconclusive: dark noisy frames, and the edge matcher
latched three W1 edges onto one W2 segment. W's verdict wants a long straight line near the
frame edge, in light.

### 8. What driving cells from the desk found in the receipts

Fixed in `7e316fd`:

- The receipt disagreed with two good clips because the periodic path delivers the metric pair
  by design on the all-lens set. It now judges against `lenses_expected`, not
  `lenses_configured`.
- The stereo row counter never reset between video clips (24, 48, would have been 72).
- A crop warning fired on every clip from iterating four readers against a two-lens builder.

And one the receipt did not catch: the 18:53 L1 said `agrees: true` while logcat said
`Physical camera id: 5 is not valid!` five times. Read the receipt **and** the log;
`drive_cell.ps1` prints both.

## What this means for data already shot

- **One-shot pairs from `09e5e47` on are trustworthy end to end**: census intrinsics for
  uw/main/3x, rows describe the pixels, halves in one sensor period. `session.json` carries
  `git_sha`, so a session can say which side of the line it is on.
- **Every pair before that, and periodic pairs inside video still,** have an ultrawide half
  cropped by a session-dependent 1.4-1.6x, and (one-shot only) rows whose zoom and exposure
  describe a different frame. Their depths need the effective focal measured per session:
  `analyze_pairs.py`, uw -> main scale, `f_eff = f_main / scale`.
- **The 5x is unconfirmed** in every session to date.

## Open

- **G1/G2** (target + tape, 1 m then 2 m): first baseline numbers for the 3x and 5x, and the
  5x's FOV. **H1/H2, I1/I2, O1/O2** need a walk. None is drivable from a still phone.
- **Periodic pairs** still carry the zoom-1.0 crop with nothing in the row to say so.
- **"1 frame records lost"** appeared on W2 and N1 but not W1 or N2 — one intermittent loss
  per clip; on the full count, four clips of six. Smells like a start-edge race in
  `RecordingWriter`. Which counter it is has not been read yet. Issue #3.
- **W on the ultrawide** needs a proper scene (above).
- **Downstream**: the crop finding is written up on ReconStab #6 and the N1/N2 numbers on
  ReconStab #55 (2026-09-21). Still owed there: N1/N2 scored by the matcher on a real route,
  and a per-session effective focal for every archive pair.
- **Code structure**: the modularization pass asked for on 2026-09-20 was done on 2026-09-21
  (branch `modularize`) and **has not been run on the phone**. 106 host tests pass and the
  APK assembles, but the host suite cannot see a camera. Before it is merged, from a still
  phone with `tools/drive_cell.ps1`: M1-M6 (the two buttons, both orderings, the composite),
  L1 on the all-lens set (the pair sequence and the one restore-preview path), one W or N
  cell (periodic pairs inside a video), and the stereo probe once (it moved package).
  Receipts should agree and rows should read as they did on `7e316fd`; log tags changed
  (`StereoCapture`, `StereoRequests`, `LensRoles`, `FocusStack`), messages did not.
  Deliberately NOT done: `stereo/` and `session/` packages. Those classes call back into
  the capture core, so moving them means widening a few dozen members to public across a
  circular boundary -- worth doing, but not stacked on a refactor nobody has yet watched run.
