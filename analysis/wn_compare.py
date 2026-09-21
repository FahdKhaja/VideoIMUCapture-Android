"""What W1/W2 and N1/N2 actually changed in the pixels, from one pair each.

N1 vs N2 (HAL edge/noise reduction on vs off):
  noise   = robust sigma of (frame - 5x5 median) over FLAT pixels (low local gradient):
            what denoise removes.
  detail  = mean |Laplacian| over TEXTURED pixels: what sharpening adds and denoise eats.
  Both per lens, plus the JPEG size (a denoised frame compresses smaller).

W1 vs W2 (distortion correction off vs on):
  the longest straight edges in the outer 30% of the main frame (where lens distortion is
  largest), each fitted with a line through its Canny points; the RMS deviation in pixels is
  how bent the edge is. Correction ON should shrink it if the HAL is un-warping -- which
  would mean the recorded k1..k5 no longer describe the picture.
"""
import glob
import os
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from analyze_pairs import DATA  # noqa: E402

D = os.path.join(DATA, "wn")


def frame(cell, tag):
    m = glob.glob(os.path.join(D, "%s_stereo_*_%s.jpg" % (cell, tag)))
    return m[0] if m else None


def noise_detail(path):
    g = cv2.imread(path, cv2.IMREAD_GRAYSCALE).astype(np.float32)
    med = cv2.medianBlur(g.astype(np.uint8), 5).astype(np.float32)
    resid = g - med
    blur = cv2.GaussianBlur(g, (0, 0), 2.0)
    gx = cv2.Sobel(blur, cv2.CV_32F, 1, 0)
    gy = cv2.Sobel(blur, cv2.CV_32F, 0, 1)
    grad = np.hypot(gx, gy)
    flat = grad < np.percentile(grad, 40)
    textured = grad > np.percentile(grad, 85)
    sigma = 1.4826 * np.median(np.abs(resid[flat] - np.median(resid[flat])))
    lap = np.abs(cv2.Laplacian(g, cv2.CV_32F, ksize=3))
    return float(sigma), float(lap[textured].mean()), os.path.getsize(path)


def long_edges(path, n=4):
    g = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    H, W = g.shape
    edges = cv2.Canny(cv2.GaussianBlur(g, (0, 0), 1.2), 40, 120)
    margin = np.zeros_like(edges)
    mx, my = int(W * 0.30), int(H * 0.30)
    margin[:, :mx] = 1
    margin[:, W - mx:] = 1
    margin[:my, :] = 1
    margin[H - my:, :] = 1
    edges = edges * margin
    segs = cv2.HoughLinesP(edges, 1, np.pi / 360, threshold=80, minLineLength=220, maxLineGap=12)
    out = []
    if segs is None:
        return out, edges
    for s in segs.reshape(-1, 4):
        x1, y1, x2, y2 = [int(v) for v in s]
        length = float(np.hypot(x2 - x1, y2 - y1))
        # Canny points within a 6 px band of the segment, fitted with total least squares.
        pts = np.column_stack(np.nonzero(edges)[::-1]).astype(np.float32)  # (x, y)
        d = np.array([x2 - x1, y2 - y1], np.float32) / length
        rel = pts - np.array([x1, y1], np.float32)
        along = rel @ d
        perp = rel[:, 0] * -d[1] + rel[:, 1] * d[0]
        band = (along >= 0) & (along <= length) & (np.abs(perp) <= 6)
        p = pts[band]
        if len(p) < 60:
            continue
        mean = p.mean(0)
        _, _, vt = np.linalg.svd(p - mean, full_matrices=False)
        normal = vt[1]
        rms = float(np.sqrt(np.mean(((p - mean) @ normal) ** 2)))
        out.append((length, rms, (x1, y1, x2, y2), len(p)))
    out.sort(key=lambda t: -t[0])
    # keep segments that are not near-duplicates of a longer one
    kept = []
    for s in out:
        if all(np.hypot(s[2][0] - k[2][0], s[2][1] - k[2][1]) > 40
               or np.hypot(s[2][2] - k[2][2], s[2][3] - k[2][3]) > 40 for k in kept):
            kept.append(s)
        if len(kept) == n:
            break
    return kept, edges


def match_segments(a, b):
    pairs = []
    for sa in a:
        best = None
        for sb in b:
            d = np.hypot(sa[2][0] - sb[2][0], sa[2][1] - sb[2][1]) + np.hypot(sa[2][2] - sb[2][2], sa[2][3] - sb[2][3])
            d2 = np.hypot(sa[2][0] - sb[2][2], sa[2][1] - sb[2][3]) + np.hypot(sa[2][2] - sb[2][0], sa[2][3] - sb[2][1])
            d = min(d, d2)
            if d < 120 and (best is None or d < best[0]):
                best = (d, sb)
        if best:
            pairs.append((sa, best[1]))
    return pairs


def label(img, s, org, color=(255, 255, 255), scale=0.6, thick=1):
    (tw, th), base = cv2.getTextSize(s, cv2.FONT_HERSHEY_SIMPLEX, scale, thick)
    x, y = org
    cv2.rectangle(img, (x - 4, y - th - 4), (x + tw + 4, y + base + 2), (20, 20, 20), -1)
    cv2.putText(img, s, org, cv2.FONT_HERSHEY_SIMPLEX, scale, color, thick, cv2.LINE_AA)


def main_():
    print("=== N1 vs N2: noise and detail per lens (main = 5, uw = 2) ===")
    print("  %-4s %-5s %10s %10s %12s" % ("cell", "lens", "noise sig", "detail", "jpeg bytes"))
    for cell in ("N1", "N2"):
        for tag in ("main", "uw"):
            p = frame(cell, tag)
            if p:
                s, d, b = noise_detail(p)
                print("  %-4s %-5s %10.2f %10.2f %12s" % (cell, tag, s, d, format(b, ",")))
    # side-by-side crop of the same region, 2x
    a = cv2.imread(frame("N1", "main"))
    b = cv2.imread(frame("N2", "main"))
    y0, x0, h, w = 380, 1150, 220, 320
    crop = np.hstack([cv2.resize(a[y0:y0 + h, x0:x0 + w], None, fx=2, fy=2, interpolation=cv2.INTER_NEAREST),
                      cv2.resize(b[y0:y0 + h, x0:x0 + w], None, fx=2, fy=2, interpolation=cv2.INTER_NEAREST)])
    label(crop, "N1  HAL processing as shipped", (10, 28))
    label(crop, "N2  raw pixels, no sharpening or denoise", (2 * w + 10, 28))
    cv2.imwrite(os.path.join(D, "N_compare.png"), crop)

    print()
    print("=== W1 vs W2: longest straight edges in the outer 30% of the main frame ===")
    e1, _ = long_edges(frame("W1", "main"))
    e2, _ = long_edges(frame("W2", "main"))
    pairs = match_segments(e1, e2)
    print("  %-6s %-9s %-9s %-9s %s" % ("edge", "len px", "W1 rms", "W2 rms", "reading"))
    vis1 = cv2.imread(frame("W1", "main"))
    vis2 = cv2.imread(frame("W2", "main"))
    for i, (sa, sb) in enumerate(pairs):
        delta = sb[1] - sa[1]
        reading = ("straighter with correction ON by %.2f px" % -delta if delta < -0.15
                   else "more bent with correction ON by %.2f px" % delta if delta > 0.15
                   else "no change")
        print("  %-6d %-9.0f %-9.2f %-9.2f %s" % (i + 1, sa[0], sa[1], sb[1], reading))
        for vis, s in ((vis1, sa), (vis2, sb)):
            x1, y1, x2, y2 = s[2]
            cv2.line(vis, (x1, y1), (x2, y2), (60, 220, 255), 2)
            label(vis, "#%d rms %.2f px" % (i + 1, s[1]), (min(x1, x2) + 6, min(y1, y2) - 8), (60, 220, 255), 0.55)
    if not pairs:
        print("  no edge found in both frames")
    label(vis1, "W1  distortion correction OFF", (10, 30), (255, 255, 255), 0.8, 2)
    label(vis2, "W2  distortion correction ON", (10, 30), (255, 255, 255), 0.8, 2)
    cv2.imwrite(os.path.join(D, "W_compare.png"), np.vstack([vis1, vis2]))
    print()
    print(os.path.join(D, "N_compare.png"))
    print(os.path.join(D, "W_compare.png"))


if __name__ == "__main__":
    main_()
