"""What the pixels of a pair sequence can say.

Two questions, in order of how much they decide.

1. FIELD OF VIEW PER LENS.  For every pair, match features and fit a similarity; its scale is
   the ratio of the two streams' effective focal lengths in pixels.  The census predicts that
   ratio from factory intrinsics (uw 1651 px on 4000, main 2756 on 4080, 3x 7174 on 4000,
   5x 13647 on 4080).  A measured scale that matches the prediction means the 1080p stream
   carries the lens's own field of view; a scale near 1.0 between the ultrawide and the main
   camera means the HAL cropped the wide lens to the narrow one's view inside the stream --
   exactly what the first stereo pair showed before crop_region existed, and what the rows now
   say is NOT happening at the request level (every crop comes back as the full array).

2. THE UNPUBLISHED BASELINES.  uw+main has a published offset (18.02 mm).  Match them, recover
   the essential matrix, scale the translation to 18.02 mm, triangulate.  Those 3D points are
   metric; where they also match in the 3x or 5x frame, solvePnP gives that lens's pose in the
   same metric frame -- a baseline in millimetres with no tape measure.  Only attempted with
   the intrinsics question 1 settles, and reported with its inlier count and reprojection
   error so nobody mistakes a first number for a measurement.
"""
import glob
import json
import os
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
# Where the pulled sessions live: <DATA>/camera_census.json and one directory per session.
# Never in git -- the frames are photographs of wherever the phone was standing.
DATA = os.environ.get("VIMU_DATA") or os.path.join(HERE, "data")
W, H = 1920, 1080
UW, MAIN, T3, T5 = "2", "5", "6", "7"
TAG = {"uw": UW, "main": MAIN, "phys6": T3, "phys7": T5}
NAME = {UW: "uw(2)", MAIN: "main(5)", T3: "3x(6)", T5: "5x(7)"}


# Physical camera ids and everything keyed on them are the Galaxy S24 Ultra's. On another
# phone, read the ids out of its own camera_census.json first.
# ------------------------------------------------------------------ census intrinsics

def census():
    with open(os.path.join(DATA, "camera_census.json"), encoding="utf-8") as f:
        c = json.load(f)
    out = {}

    def walk(node, key=None):
        if isinstance(node, dict):
            if "LENS_INTRINSIC_CALIBRATION" in node and key in (UW, MAIN, T3, T5):
                fx, fy, cx, cy, _ = node["LENS_INTRINSIC_CALIBRATION"][:5]
                aw, ah = [int(v) for v in node["SENSOR_INFO_ACTIVE_ARRAY_SIZE"].split()[2:4]]
                d = node.get("LENS_DISTORTION") or [0, 0, 0, 0, 0]
                out[key] = dict(fx=fx, fy=fy, cx=cx, cy=cy, aw=aw, ah=ah, dist=d)
            for k, v in node.items():
                walk(v, k)
        elif isinstance(node, list):
            for v in node:
                walk(v, key)

    walk(c)
    return out


def k_eff(cam, scale_override=None):
    """Full-array intrinsics mapped onto the 1920x1080 stream.

    Assumes the HAL fits the array's WIDTH to 1920 and crops the height to 1080 (a 4:3 array
    scaled to 1920 wide is 1440 tall; 180 rows leave at each edge).  The alternative -- fit
    the height, crop the width -- moves only the principal point; the scale question below is
    indifferent to it and the baseline solve is only mildly sensitive.
    """
    s = W / cam["aw"] if scale_override is None else scale_override
    dy = (cam["ah"] * s - H) / 2
    return np.array([[cam["fx"] * s, 0, cam["cx"] * s],
                     [0, cam["fy"] * s, cam["cy"] * s - dy],
                     [0, 0, 1]], dtype=np.float64)


def dist_cv(cam):
    """Android LENS_DISTORTION [k1 k2 k3 k4 k5] (k4,k5 tangential) -> OpenCV [k1 k2 p1 p2 k3]."""
    d = cam["dist"]
    return np.array([d[0], d[1], d[3], d[4], d[2]], dtype=np.float64)


# ------------------------------------------------------------------ features

_sift = cv2.SIFT_create(nfeatures=6000, contrastThreshold=0.02)
_feat_cache = {}


def features(path):
    if path not in _feat_cache:
        img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
        clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8))
        img = clahe.apply(img)  # these frames are dark; equalise before detecting
        kp, des = _sift.detectAndCompute(img, None)
        _feat_cache[path] = (kp, des)
    return _feat_cache[path]


def match(pa, pb):
    ka, da = features(pa)
    kb, db = features(pb)
    if da is None or db is None or len(da) < 8 or len(db) < 8:
        return np.zeros((0, 2)), np.zeros((0, 2)), [], []
    bf = cv2.BFMatcher(cv2.NORM_L2)
    knn = bf.knnMatch(da, db, k=2)
    good = [m for m, n in (p for p in knn if len(p) == 2) if m.distance < 0.78 * n.distance]
    pa_pts = np.float64([ka[m.queryIdx].pt for m in good])
    pb_pts = np.float64([kb[m.trainIdx].pt for m in good])
    return pa_pts, pb_pts, [m.queryIdx for m in good], [m.trainIdx for m in good]


def similarity(pa_pts, pb_pts):
    """Scale of the similarity mapping a->b, with RANSAC; returns (scale, inliers, n)."""
    if len(pa_pts) < 6:
        return float("nan"), 0, len(pa_pts)
    M, inl = cv2.estimateAffinePartial2D(pa_pts, pb_pts, method=cv2.RANSAC,
                                         ransacReprojThreshold=6.0, maxIters=5000)
    if M is None:
        return float("nan"), 0, len(pa_pts)
    s = float(np.hypot(M[0, 0], M[0, 1]))
    if s < 0.05:
        # RANSAC collapsed every point onto one: a consensus, but not a fit. Reported as a
        # scale it read "partial crop: -60%" and an effective focal of 0.0 px.
        return float("nan"), 0, len(pa_pts)
    return s, int(inl.sum()), len(pa_pts)


# ------------------------------------------------------------------ per session

def bursts(session):
    files = sorted(glob.glob(os.path.join(DATA, session, "stereo_*.jpg")))
    by = {}
    for f in files:
        stem = os.path.basename(f)[:-4]
        _, bid, tag = stem.split("_")
        by.setdefault(bid, {})[TAG[tag]] = f
    return by


def fov_table(session, cams):
    print("=== %s: FIELD OF VIEW PER PAIR (similarity scale a->b) ===" % session)
    print("  %-16s %6s/%-5s  %9s  %9s  %s" % ("pair", "inl", "n", "measured", "census", "reading"))
    results = {}
    for bid, pair in sorted(bursts(session).items()):
        ids = sorted(pair, key=lambda i: cams[i]["fx"] / cams[i]["aw"])  # wider lens first
        a, b = ids[0], ids[1]
        pa, pb, _, _ = match(pair[a], pair[b])
        s, inl, n = similarity(pa, pb)
        pred = (cams[b]["fx"] / cams[b]["aw"]) / (cams[a]["fx"] / cams[a]["aw"])
        if np.isnan(s) or inl < 12:
            reading = "no reliable match"
        elif abs(s - pred) / pred < 0.12:
            reading = "streams carry each lens's own FOV"
        elif abs(s - 1.0) < 0.15:
            reading = "SAME FRAMING: wide lens cropped to the narrow one"
        else:
            reading = "partial crop: %.0f%% of the census ratio" % (100 * (s - 1) / (pred - 1))
        print("  %-16s %6d/%-5d  %9.3f  %9.3f  %s" % (
            NAME[a] + "->" + NAME[b], inl, n, s, pred, reading))
        results[(a, b)] = (s, inl, n, pred)
    print()
    return results


def baselines(session, cams, fov):
    print("=== %s: BASELINES FROM THE PUBLISHED PAIR ===" % session)
    by = bursts(session)
    ref = next((p for p in by.values() if UW in p and MAIN in p), None)
    if ref is None:
        print("  no uw+main burst")
        return
    # Effective focal lengths: if the FOV table says a lens is cropped, its factory fx does not
    # describe these pixels.  Use the MEASURED scale against the main camera instead, whose own
    # framing matches its census focal within the table's tolerance in every run so far.
    k_main = k_eff(cams[MAIN])
    eff = {MAIN: k_main}
    for cid in (UW, T3, T5):
        key = (UW, MAIN) if cid == UW else (MAIN, cid)
        s, inl, n, pred = fov.get(key, (float("nan"), 0, 0, 1))
        if np.isnan(s) or inl < 12:
            eff[cid] = None
            continue
        f_px = k_main[0, 0] / s if cid == UW else k_main[0, 0] * s
        c = cams[cid]
        eff[cid] = np.array([[f_px, 0, W / 2], [0, f_px * c["fy"] / c["fx"], H / 2], [0, 0, 1]])
        print("  %-8s effective f = %7.1f px (census would be %7.1f)" % (
            NAME[cid], f_px, k_eff(c)[0, 0]))
    if eff[UW] is None:
        print("  ultrawide unusable; stopping")
        return

    # --- uw <-> main: essential matrix, scaled to the published 18.02 mm
    pu, pm, iu, im = match(ref[UW], ref[MAIN])
    nu = cv2.undistortPoints(pu.reshape(-1, 1, 2), eff[UW], None).reshape(-1, 2)
    nm = cv2.undistortPoints(pm.reshape(-1, 1, 2), eff[MAIN], None).reshape(-1, 2)
    E, mask = cv2.findEssentialMat(nu, nm, focal=1.0, pp=(0, 0), method=cv2.RANSAC,
                                   prob=0.999, threshold=2.0 / k_main[0, 0])
    if E is None:
        print("  essential matrix failed")
        return
    _, R, t, pose_mask = cv2.recoverPose(E, nu, nm, focal=1.0, pp=(0, 0), mask=mask)
    inl = pose_mask.ravel() > 0
    t = t.ravel() / np.linalg.norm(t)
    B = 0.018018510  # metres, LENS_POSE_TRANSLATION of the ultrawide
    t_m = t * B
    print("  uw->main: %d/%d inliers; recovered direction %s (census: along +y, 18.02 mm)" % (
        inl.sum(), len(nu), np.round(t, 3)))
    P_u = np.hstack([np.eye(3), np.zeros((3, 1))])
    P_m = np.hstack([R, t_m.reshape(3, 1)])
    X_h = cv2.triangulatePoints(P_u, P_m, nu[inl].T, nm[inl].T)
    X = (X_h[:3] / X_h[3]).T
    depth_m = X[:, 2]
    ok = (depth_m > 0.05) & (depth_m < 5.0)
    print("  triangulated %d points; depth median %.3f m, 10..90%% = %.3f..%.3f m" % (
        ok.sum(), np.median(depth_m[ok]), *np.percentile(depth_m[ok], [10, 90])))
    # main-camera keypoint index -> 3D point (in the ultrawide frame)
    im_arr = np.array(im)[inl][ok]
    pts3d_by_main_idx = {int(i): X[k] for k, i in enumerate(np.array(im)[inl]) if ok[k]}

    # --- each telephoto: PnP against the metric points, via its match with the main camera
    for cid in (T3, T5):
        if eff.get(cid) is None:
            print("  %-8s: no usable FOV estimate, skipped" % NAME[cid])
            continue
        pair = next((p for p in by.values() if MAIN in p and cid in p), None)
        if pair is None:
            continue
        pm2, pt, im2, it = match(pair[MAIN], pair[cid])
        # The main frame of THIS burst is a different exposure of the same static scene, so
        # its keypoint indices are not the reference burst's; match main->main to bridge.
        pm_ref, pm_this, i_ref, i_this = match(ref[MAIN], pair[MAIN])
        bridge = {}
        for a, b, qa, qb in zip(pm_ref, pm_this, i_ref, i_this):
            if qa in pts3d_by_main_idx:
                bridge[qb] = pts3d_by_main_idx[qa]
        obj, img = [], []
        for q_main, q_t, p_t in zip(im2, it, pt):
            if q_main in bridge:
                obj.append(bridge[q_main])
                img.append(p_t)
        obj = np.array(obj, dtype=np.float64)
        img = np.array(img, dtype=np.float64)
        print("  %-8s: %d main<->tele matches, %d carry a metric point" % (
            NAME[cid], len(pt), len(obj)))
        if len(obj) < 8:
            print("           too few for PnP")
            continue
        okp, rvec, tvec, inliers = cv2.solvePnPRansac(
            obj, img, eff[cid], None, reprojectionError=4.0, iterationsCount=3000,
            confidence=0.999, flags=cv2.SOLVEPNP_EPNP)
        if not okp or inliers is None:
            print("           PnP failed")
            continue
        Rt, _ = cv2.Rodrigues(rvec)
        centre_uw_frame = (-Rt.T @ tvec).ravel()
        centre_main = centre_uw_frame - (-R.T @ t_m.reshape(3, 1)).ravel()
        proj, _ = cv2.projectPoints(obj[inliers.ravel()], rvec, tvec, eff[cid], None)
        rms = float(np.sqrt(np.mean(np.sum((proj.reshape(-1, 2) - img[inliers.ravel()]) ** 2, 1))))
        print("           PnP inliers %d/%d, reprojection RMS %.2f px" % (len(inliers), len(obj), rms))
        print("           centre rel. ultrawide = %s mm  |%.1f mm|" % (
            np.round(centre_uw_frame * 1000, 1), np.linalg.norm(centre_uw_frame) * 1000))
        print("           centre rel. main      = %s mm  |%.1f mm|   <- the unpublished baseline" % (
            np.round(centre_main * 1000, 1), np.linalg.norm(centre_main) * 1000))
    print()


if __name__ == "__main__":
    cams = census()
    for cid in (UW, MAIN, T3, T5):
        c = cams[cid]
        print("%-8s census fx=%7.1f on %d px  -> %.4f px/px;  eff f at 1920 = %7.1f px" % (
            NAME[cid], c["fx"], c["aw"], c["fx"] / c["aw"], k_eff(c)[0, 0]))
    print()
    for session in sys.argv[1:] or ("L1", "M5"):
        fov = fov_table(session, cams)
        baselines(session, cams, fov)
