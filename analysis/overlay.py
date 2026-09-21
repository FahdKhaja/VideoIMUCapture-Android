"""The multisensor shot as a picture: every lens's frame placed inside the ultrawide's, with the
stereo data that places it.

Three files per session:
  <s>_fov_overlay.png   the ultrawide frame as the canvas; main, 3x and 5x warped into it by
                        the similarity measured from their matched features, each outlined and
                        labelled with its measured scale against the census prediction; the
                        matched features drawn; a table of every pair's numbers.
  <s>_pairs_board.png   the six pairs, each as its two frames side by side with the inlier
                        matches drawn across, and the row data above (stamps, zoom, exposure).
  <s>_anaglyph.png      ultrawide + main as red/cyan after similarity alignment: what is left
                        misaligned is parallax, i.e. the depth the 18.02 mm baseline sees.
"""
import os
import random
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "gen"))
from analyze_pairs import census, match, bursts, DATA, NAME, UW, MAIN, T3, T5, W, H  # noqa: E402
import recording_pb2 as pb  # noqa: E402

SESSION = sys.argv[1] if len(sys.argv) > 1 else "L1d"
OUT = os.path.join(DATA, SESSION + "_viz")
os.makedirs(OUT, exist_ok=True)

COLOR = {UW: (255, 255, 255), MAIN: (80, 220, 80), T3: (80, 160, 255), T5: (60, 80, 255)}
FONT = cv2.FONT_HERSHEY_SIMPLEX


def rows_by_burst():
    data = pb.VideoCaptureData()
    with open(os.path.join(DATA, SESSION, "video_meta.pb3"), "rb") as f:
        data.ParseFromString(f.read())
    out = {}
    for s in data.stills:
        if s.jpeg_file.startswith("stereo_"):
            out.setdefault(str(s.burst_id), {})[s.physical_camera_id] = s
    return out


def fit(pa_path, pb_path):
    """Similarity a->b from matched features. Returns (M 2x3, inlier mask, pa, pb) or None."""
    pa, pbp, _, _ = match(pa_path, pb_path)
    if len(pa) < 6:
        return None
    M, inl = cv2.estimateAffinePartial2D(pa, pbp, method=cv2.RANSAC,
                                         ransacReprojThreshold=6.0, maxIters=5000)
    if M is None:
        return None
    inl = inl.ravel().astype(bool)
    s = float(np.hypot(M[0, 0], M[0, 1]))
    if inl.sum() < 12 or s < 0.05:
        return None
    return M, inl, pa, pbp


def text(img, s, org, scale=0.6, color=(255, 255, 255), thick=1, box=False):
    # No stroked outline: OpenCV lays a thick Hershey pass out wider than a thin one, so the
    # "outline" poked out past the text as a ghost. A dark box behind labels over imagery
    # instead; banner text sits on its own dark strip and needs nothing.
    if box:
        (tw, th), base = cv2.getTextSize(s, FONT, scale, thick)
        x, y = org
        cv2.rectangle(img, (x - 4, y - th - 4), (x + tw + 4, y + base + 2), (20, 20, 20), -1)
    cv2.putText(img, s, org, FONT, scale, color, thick, cv2.LINE_AA)


def banner(width, lines, scale=0.55):
    h = 14 + 22 * len(lines)
    b = np.zeros((h, width, 3), np.uint8)
    b[:] = (28, 28, 28)
    for i, (s, c) in enumerate(lines):
        text(b, s, (10, 24 + 22 * i), scale, c)
    return b


def main_():
    cams = census()
    by = bursts(SESSION)
    rows = rows_by_burst()
    order = sorted(by)
    ratio = lambda a, b: (cams[b]["fx"] / cams[b]["aw"]) / (cams[a]["fx"] / cams[a]["aw"])

    # ------------------------------------------------------------- FOV overlay
    base_bid = next(b for b in order if UW in by[b] and MAIN in by[b])
    base = cv2.imread(by[base_bid][UW])
    canvas = (base * 0.45).astype(np.uint8)
    legend = []
    main_to_uw = fit(by[base_bid][MAIN], by[base_bid][UW])

    def place(lens):
        """Similarity lens -> ultrawide: directly if the features match, else through the main
        camera (a 3x frame matches the main camera's at 29/84 where it matches the wide at
        5/41 -- the 4.3x scale jump is what SIFT cannot bridge, not the lens)."""
        bid = next((b for b in order if UW in by[b] and lens in by[b]), None)
        if bid is not None:
            r = fit(by[bid][lens], by[bid][UW])
            if r is not None:
                return r[0], r[1], r[2], r[3], None, "vs ultrawide"
        bid = next((b for b in order if MAIN in by[b] and lens in by[b]), None)
        if bid is None or main_to_uw is None:
            return None
        r1 = fit(by[bid][lens], by[bid][MAIN])
        if r1 is None:
            return None
        A1 = np.vstack([r1[0], [0, 0, 1]])
        A2 = np.vstack([main_to_uw[0], [0, 0, 1]])
        return (A2 @ A1)[:2], r1[1], r1[2], r1[3], main_to_uw[0], "via main"

    for lens in (MAIN, T3, T5):
        p = place(lens)
        pred = ratio(UW, lens)
        col = COLOR[lens]
        if p is None:
            # No reliable match at this range: draw the census-PREDICTED footprint, dashed.
            w, h = W / pred, H / pred
            x0, y0 = int((W - w) / 2), int((H - h) / 2)
            for k in range(0, int(w), 24):
                cv2.line(canvas, (x0 + k, y0), (x0 + min(k + 12, int(w)), y0), col, 2)
                cv2.line(canvas, (x0 + k, y0 + int(h)), (x0 + min(k + 12, int(w)), y0 + int(h)), col, 2)
            for k in range(0, int(h), 24):
                cv2.line(canvas, (x0, y0 + k), (x0, y0 + min(k + 12, int(h))), col, 2)
                cv2.line(canvas, (x0 + int(w), y0 + k), (x0 + int(w), y0 + min(k + 12, int(h))), col, 2)
            text(canvas, "%s  predicted x%.2f  (no reliable match at this range)" % (NAME[lens], pred),
                 (x0 + 8, max(20, y0 - 8)), 0.55, col, box=True)
            legend.append(("%-8s no reliable match; census predicts x%.2f, footprint drawn dashed" % (NAME[lens], pred), col))
            continue
        M, inl, pa, pbp, dots_xform, how = p
        partner = UW if how == "vs ultrawide" else MAIN
        bid = next(b for b in order if lens in by[b] and partner in by[b])
        scale = 1.0 / float(np.hypot(M[0, 0], M[0, 1]))   # how much narrower than the wide
        img = cv2.imread(by[bid][lens])
        warped = cv2.warpAffine(img, M, (W, H))
        mask = cv2.warpAffine(np.full((H, W), 255, np.uint8), M, (W, H)) > 0
        canvas[mask] = (0.35 * canvas[mask] + 0.65 * warped[mask]).astype(np.uint8)
        corners = np.float32([[0, 0], [W, 0], [W, H], [0, H]]).reshape(-1, 1, 2)
        poly = cv2.transform(corners, M).reshape(-1, 2).astype(int)
        cv2.polylines(canvas, [poly], True, col, 2)
        dots = pbp[inl]
        if dots_xform is not None:       # matched in the main frame; bring into the wide's
            dots = cv2.transform(dots.reshape(-1, 1, 2), dots_xform).reshape(-1, 2)
        for d in dots[::2]:
            cv2.circle(canvas, (int(d[0]), int(d[1])), 3, col, -1)
        text(canvas, "%s  measured x%.3f  (census x%.3f)  %d inliers %s" % (
            NAME[lens], scale, pred, inl.sum(), how),
             (poly[0][0] + 8, max(20, poly[0][1] - 8)), 0.6, col, box=True)
        legend.append(("%-8s measured x%.3f vs census x%.3f  %3d/%d inliers %s  dots = matched features" % (
            NAME[lens], scale, pred, inl.sum(), len(pa), how), col))
    head = [("%s  --  every lens placed inside the ultrawide frame by its own matched features" % SESSION, (255, 255, 255)),
            ("ultrawide(2) is the canvas; each outline is where that lens's full frame lands, at the scale the pixels say", (200, 200, 200))]
    fov = np.vstack([banner(W, head, 0.6), canvas, banner(W, legend)])
    cv2.imwrite(os.path.join(OUT, SESSION + "_fov_overlay.png"), fov)

    # ------------------------------------------------------------- pairs board
    tw, th = 640, 360
    boards = []
    random.seed(1)
    for bid in order:
        ids = sorted(by[bid], key=lambda i: cams[i]["fx"] / cams[i]["aw"])
        a, b = ids[0], ids[1]
        r = fit(by[bid][a], by[bid][b])
        A = cv2.resize(cv2.imread(by[bid][a]), (tw, th))
        B = cv2.resize(cv2.imread(by[bid][b]), (tw, th))
        pane = np.hstack([A, B])
        ra, rb = rows.get(bid, {}).get(a), rows.get(bid, {}).get(b)
        if r is not None:
            M, inl, pa, pbp = r
            idx = [i for i in range(len(pa)) if inl[i]]
            random.shuffle(idx)
            for i in idx[:70]:
                p = (int(pa[i][0] * tw / W), int(pa[i][1] * th / H))
                q = (int(pbp[i][0] * tw / W) + tw, int(pbp[i][1] * th / H))
                cv2.line(pane, p, q, (60, 220, 255), 1, cv2.LINE_AA)
                cv2.circle(pane, p, 3, COLOR[a], -1)
                cv2.circle(pane, q, 3, COLOR[b], -1)
            stat = "scale x%.3f (census x%.3f)  %d/%d inliers" % (
                float(np.hypot(M[0, 0], M[0, 1])), ratio(a, b), inl.sum(), len(pa))
        else:
            stat = "no reliable match (census x%.3f)" % ratio(a, b)
        dt = abs(ra.time_ns - rb.time_ns) / 1e6 if ra and rb else float("nan")
        same = (ra and rb and ra.time_ns == ra.logical_result_time_ns
                and rb.time_ns == rb.logical_result_time_ns)
        lines = [("%s + %s   burst %s   %s" % (NAME[a], NAME[b], bid, stat), (255, 255, 255))]
        if ra and rb:
            lines.append(("halves |dt| %.3f ms   zoom %.2f / %.2f   exp %.1f / %.1f ms   iso %d / %d   %s" % (
                dt, ra.zoom_ratio, rb.zoom_ratio, ra.exposure_time_ns / 1e6, rb.exposure_time_ns / 1e6,
                ra.iso, rb.iso, "rows are the frames' own (REQUEST'S FRAME)" if same else "row/frame stamps DIFFER"),
                (120, 255, 120) if same else (80, 80, 255)))
        text(pane, NAME[a], (10, th - 12), 0.7, COLOR[a], 2, box=True)
        text(pane, NAME[b], (tw + 10, th - 12), 0.7, COLOR[b], 2, box=True)
        boards.append(np.vstack([banner(2 * tw, lines, 0.5), pane]))
    cv2.imwrite(os.path.join(OUT, SESSION + "_pairs_board.png"), np.vstack(boards))

    # ------------------------------------------------------------- anaglyph
    r = fit(by[base_bid][MAIN], by[base_bid][UW])
    if r is not None:
        M = r[0]
        uw_g = cv2.cvtColor(base, cv2.COLOR_BGR2GRAY)
        main_g = cv2.warpAffine(cv2.cvtColor(cv2.imread(by[base_bid][MAIN]), cv2.COLOR_BGR2GRAY), M, (W, H))
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
        uw_g, main_g = clahe.apply(uw_g), clahe.apply(main_g)
        ana = np.dstack([main_g, main_g, uw_g])      # B, G from main; R from ultrawide
        lines = [("ultrawide (red) + main (cyan), burst %s, main warped into the wide frame by the measured similarity" % base_bid, (255, 255, 255)),
                 ("what stays doubled is parallax across the 18.02 mm baseline -- the depth signal; near objects split more", (200, 200, 200))]
        cv2.imwrite(os.path.join(OUT, SESSION + "_anaglyph.png"), np.vstack([banner(W, lines, 0.55), ana]))

    for f in sorted(os.listdir(OUT)):
        print(os.path.join(OUT, f))


if __name__ == "__main__":
    main_()
