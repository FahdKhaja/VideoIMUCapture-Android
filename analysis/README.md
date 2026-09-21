# analysis

Desk-side checks on what the app recorded: do the rows describe the pixels, and do the pixels
carry the lens the census says they do. These are the scripts that settled the 2026-09-20
questions; what they found is in [FINDINGS.md](FINDINGS.md).

They were written against one phone. The physical camera ids (`2` ultrawide, `5` main, `6` 3x,
`7` 5x), the 1920x1080 stream size and the 18.02 mm ultrawide offset in `analyze_pairs.py` are
the Galaxy S24 Ultra's — on another device, read the ids and `LENS_POSE_TRANSLATION` out of
its own `camera_census.json` first.

## Setup

Python 3, from the repo root:

    pip install -r analysis/requirements.txt
    python -m grpc_tools.protoc -I protobuf --python_out=analysis/gen protobuf/recording.proto

`grpc_tools` rather than a bare `protoc` because `recording.proto` imports
`google/protobuf/timestamp.proto`, and a standalone protoc binary does not ship the include.
`analysis/gen/` is generated and ignored; regenerate it whenever the proto gains a field.

## Data

Everything is read from `analysis/data/` (or `$VIMU_DATA`), which is **ignored by git and must
stay that way**: the frames are photographs of wherever the phone was standing.

    analysis/data/
      camera_census.json            files/camera_census.json from the phone
      <name>/                       one pulled session: stereo_*.jpg, video_meta.pb3, session.json
      zoom/                         stereo_probe.json + zoomprobe_*.jpg, after a probe run
      wn/                           one pair per cell, renamed <cell>_stereo_<burst>_<tag>.jpg

Pull from PowerShell, not Git Bash (which rewrites `/sdcard/` paths):

    $F = "/sdcard/Android/data/se.lth.math.videoimucapture/files"
    adb pull "$F/camera_census.json" analysis/data/
    adb pull "$F/walk_2026_09_20_20_09_01" analysis/data/L1d

The session's directory name is printed by `android_app/tools/drive_cell.ps1` with the receipt.
The probe is started with the `run_stereo_probe` boolean extra on `CameraCaptureActivity`; its
JSON and frames land in the root of `files/`.

## The scripts

Session names are directories under the data root; with no argument each script runs on the
2026-09-20 sessions it was written for.

| script | asks | reads |
|---|---|---|
| `dump_meta.py <session>...` | What do the rows say, before any pixel is looked at? Per burst: are the halves one sensor period apart, exposure/ISO per lens, zoom, the crop the HAL reports against the active array, and whether `time_ns == logical_result_time_ns` (the pixels are the request's frame, not a warm-up frame). | pb3, census |
| `analyze_pairs.py <session>...` | Does each stream carry its own lens's field of view? SIFT + RANSAC similarity per pair; the scale is the ratio of effective focal lengths, compared with the census prediction. Then, from the published 18.02 mm uw+main baseline, tries a metric PnP for the 3x and 5x — the unpublished baselines G1/G2 exist to measure. | jpgs, census |
| `frame_holes.py <session>...` | Where are the holes in the frame records? The file's `frame_accounting` says how many were lost and to which counter; the encoder's frame numbers say where, and whether a still or a pair was being shot there. | pb3 |
| `zoom_probe.py [dir]` | Does `CONTROL_ZOOM_RATIO` 0.6 lift the crop on the ultrawide stream? Six fits against the zoom-1.0 frames, each answering one question. | `zoom/`, or the directory named |
| `overlay.py <session>` | The all-lens shot as three pictures: every lens placed inside the ultrawide frame by its own matched features, the six pairs with inlier matches and row data, and a red/cyan anaglyph of uw+main. Written to `data/<session>_viz/`. | jpgs, pb3, census |
| `wn_compare.py` | What did N1/N2 (HAL edge + noise reduction) and W1/W2 (distortion correction) change in the pixels? Robust noise sigma on flat regions, Laplacian detail on textured ones, JPEG size; straightness of long edges in the outer 30% of the frame. | `wn/` |

Reading `analyze_pairs.py`'s table: a measured scale within 12% of the census column means the
stream is that lens; a scale near 1.0 between the ultrawide and the main camera means the HAL
cropped the wide lens to the narrow one's framing; "no reliable match" means fewer than 12
RANSAC inliers or a collapsed fit, and is a statement about the scene, not the lens. Every
number is printed with its inlier count so that nobody mistakes a first number for a
measurement.
