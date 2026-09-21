"""What the pair-sequence rows say, before any pixel is looked at.

Per session: every stereo_ row grouped by burst, with the two halves' timestamps (are they one
sensor period?), exposure/ISO per lens (does the HAL expose them independently?), focal length
and focus distance, and the crop the HAL reports against the lens's own active array (the
question the crop_region field was added to answer).  Then the census: intrinsics and poses for
the four rear physicals, which is what any baseline solve has to start from.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "gen"))
import recording_pb2 as pb  # noqa: E402
from analyze_pairs import DATA  # noqa: E402

ACTIVE = {}  # physical id -> (w, h) from the census


def load_census():
    with open(os.path.join(DATA, "camera_census.json"), encoding="utf-8") as f:
        census = json.load(f)
    found = []

    def walk(node, parent_key=None):
        if isinstance(node, dict):
            if "LENS_INTRINSIC_CALIBRATION" in node:
                found.append((parent_key, node))
            for k, v in node.items():
                walk(v, k)
        elif isinstance(node, list):
            for v in node:
                walk(v, parent_key)

    walk(census)
    print("=== CENSUS: %d camera entries with intrinsics ===" % len(found))
    for parent_key, d in found:
        ids = {k: v for k, v in d.items() if "id" in k.lower() and not isinstance(v, (dict, list))}
        cid = None
        for k in ("id", "camera_id", "cameraId", "physical_id"):
            if k in d:
                cid = str(d[k])
                break
        label = cid if cid is not None else "(under key %r)" % parent_key
        intr = d.get("LENS_INTRINSIC_CALIBRATION")
        pose_t = d.get("LENS_POSE_TRANSLATION")
        pose_r = d.get("LENS_POSE_ROTATION")
        aa = d.get("SENSOR_INFO_ACTIVE_ARRAY_SIZE")
        phys = d.get("SENSOR_INFO_PHYSICAL_SIZE")
        fl = d.get("LENS_INFO_AVAILABLE_FOCAL_LENGTHS")
        dist = d.get("LENS_DISTORTION")
        mfd = d.get("LENS_INFO_MINIMUM_FOCUS_DISTANCE")
        facing = d.get("LENS_FACING")
        physicals = d.get("physical_ids") or d.get("PHYSICAL_CAMERA_IDS") or d.get("physicalCameraIds")
        print("--- camera %s  ids=%s facing=%s physicals=%s" % (label, ids, facing, physicals))
        print("    focal_mm=%s  active=%s  physical_mm=%s  min_focus_D=%s" % (fl, aa, phys, mfd))
        if intr:
            fx, fy, cx, cy, s = intr[:5]
            print("    intrinsics fx=%.2f fy=%.2f cx=%.2f cy=%.2f skew=%.3f" % (fx, fy, cx, cy, s))
        print("    pose_t=%s" % pose_t)
        print("    pose_r=%s" % pose_r)
        print("    distortion=%s" % dist)
        if cid is not None and aa:
            parts = str(aa).split()
            if len(parts) == 4:
                ACTIVE[cid] = (int(parts[2]) - int(parts[0]), int(parts[3]) - int(parts[1]))
    print()


def dump_session(name):
    path = os.path.join(DATA, name, "video_meta.pb3")
    data = pb.VideoCaptureData()
    with open(path, "rb") as f:
        data.ParseFromString(f.read())
    stills = list(data.stills)
    stereo = [s for s in stills if s.jpeg_file.startswith("stereo_")]
    other = [s for s in stills if not s.jpeg_file.startswith("stereo_")]
    print("=== %s: %d still rows, %d stereo rows, %d single-still rows; imu=%d frames=%d" % (
        name, len(stills), len(stereo), len(other), len(data.imu), len(data.video_meta)))
    bursts = {}
    for s in stereo:
        bursts.setdefault(s.burst_id, []).append(s)
    t_first = None
    for bid in sorted(bursts):
        rows = sorted(bursts[bid], key=lambda r: r.burst_index)
        if t_first is None and rows and rows[0].time_ns:
            t_first = rows[0].time_ns
        ts = [r.time_ns for r in rows]
        dt_ms = (max(ts) - min(ts)) / 1e6 if len(ts) == 2 and all(ts) else float("nan")
        since = (min(ts) - t_first) / 1e9 if t_first and all(ts) else float("nan")
        print("  burst %d  (+%.3f s)  halves=%d  |dt|=%.3f ms  size_field=%d" % (
            bid, since, len(rows), dt_ms, rows[0].burst_size if rows else -1))
        for r in rows:
            cr = r.crop_region
            crop = "%d,%d-%d,%d" % (cr.left, cr.top, cr.right, cr.bottom) if r.HasField("crop_region") else "-"
            aa = ACTIVE.get(r.physical_camera_id)
            full = ""
            if aa and r.HasField("crop_region"):
                full = "  (%s active %dx%d -> crop %dx%d = %.2fx)" % (
                    r.physical_camera_id, aa[0], aa[1], cr.right - cr.left, cr.bottom - cr.top,
                    aa[0] / max(1, cr.right - cr.left))
            # Field 29: the logical result's stamp beside the kept frame's own. Equal means
            # the pixels are the request's frame; different means an armed reader kept a
            # warm-up frame already in flight. Absent (0) on rows written before the field.
            lts = r.logical_result_time_ns
            if lts == 0:
                frame = "no logical stamp"
            elif r.time_ns == lts:
                frame = "REQUEST'S FRAME"
            else:
                frame = "WARM-UP FRAME (%.1f ms off)" % ((r.time_ns - lts) / 1e6)
            print("     [%d] phys=%-2s %-34s t=%d  exp=%.2fms iso=%d  f=%.2fmm  focus=%.2fD  zoom=%.2f  crop=%s%s  %s" % (
                r.burst_index, r.physical_camera_id, r.jpeg_file, r.time_ns,
                r.exposure_time_ns / 1e6, r.iso, r.focal_length_mm, r.focus_distance_diopters,
                r.zoom_ratio, crop, full, frame))
    print()


if __name__ == "__main__":
    load_census()
    for n in sys.argv[1:] or ("L1", "M5"):
        dump_session(n)
