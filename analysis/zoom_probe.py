"""Does the ultrawide stream come back uncropped at zoom 0.6?  The HAL's claims beside the pixels.

Reads stereo_probe.json's "zoom" section and the zoomprobe_*.jpg frames pulled next to it,
then fits the same similarity used for the pair sequences.  Every fit is against the zoom-1.0
main frame or the zoom-1.0 ultrawide frame, so each number answers one question:

  z1.0_both uw -> z1.0_both main   control: the pair as shot all day (expect ~1.01, cropped)
  z0.6_both uw -> z1.0_both main   THE TEST: 1.64 means the wide stream is now the wide lens
  z0.6_both uw -> z1.0_both uw     the same question asked of the ultrawide alone (expect 1.62)
  z0.6_uw   uw -> z1.0_both main   does targeting only the ultrawide change the answer
  z1.0_uw   uw -> z1.0_both main   is the crop a consequence of pairing, or of zoom 1.0
  z0.6_both main -> z1.0_both main does the main camera change at all at 0.6 (expect ~1.00)
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from analyze_pairs import census, match, similarity, DATA, UW, MAIN  # noqa: E402

# The probe run to read: a directory under the data root, "zoom" unless one is named.
ZDIR = os.path.join(DATA, sys.argv[1] if len(sys.argv) > 1 else "zoom")


def frame(case, tag):
    p = os.path.join(ZDIR, "zoomprobe_%s_%s.jpg" % (case, tag))
    return p if os.path.exists(p) else None


def main_():
    with open(os.path.join(ZDIR, "stereo_probe.json"), encoding="utf-8") as f:
        probe = json.load(f)
    cams = census()
    pred_uw_main = (cams[MAIN]["fx"] / cams[MAIN]["aw"]) / (cams[UW]["fx"] / cams[UW]["aw"])

    print("=== WHAT THE HAL CLAIMS ===")
    for z in probe.get("zoom", []):
        for c in z.get("cases", []):
            if "error" in c:
                print("  %-10s ERROR %s" % (c.get("case", "?"), c["error"]))
                continue
            per = c.get("physical_results", {})
            crops = ", ".join("%s:%s" % (k, v.get("crop")) for k, v in per.items())
            kept = c.get("kept", {})
            got = "+".join(k for k, v in kept.items() if v)
            print("  %-10s zoom req %.1f -> reported %s   logical crop %s   per-physical [%s]   kept %s" % (
                c["case"], c["zoom_requested"], c.get("zoom_reported"), c.get("logical_crop"),
                crops, got or "nothing"))
    print()

    ref_main = frame("z1.0_both", "main")
    ref_uw = frame("z1.0_both", "uw")
    tests = [
        ("z1.0_both uw -> z1.0_both main", frame("z1.0_both", "uw"), ref_main, pred_uw_main, "control (cropped all day)"),
        ("z0.6_both uw -> z1.0_both main", frame("z0.6_both", "uw"), ref_main, pred_uw_main, "THE TEST"),
        ("z0.6_both uw -> z1.0_both uw  ", frame("z0.6_both", "uw"), ref_uw, pred_uw_main, "wide vs itself at 1.0"),
        ("z0.6_uw   uw -> z1.0_both main", frame("z0.6_uw", "uw"), ref_main, pred_uw_main, "ultrawide targeted alone"),
        ("z1.0_uw   uw -> z1.0_both main", frame("z1.0_uw", "uw"), ref_main, pred_uw_main, "alone at 1.0: is it the pairing?"),
        ("z0.6_both main -> z1.0_both main", frame("z0.6_both", "main"), ref_main, 1.0, "main at 0.6 (expect ~1.00)"),
    ]
    print("=== WHAT THE PIXELS SAY (similarity scale a->b) ===")
    print("  %-32s %6s/%-5s  %9s  %9s  %s" % ("fit", "inl", "n", "measured", "if uncropped", "note"))
    for label, a, b, pred, note in tests:
        if a is None or b is None:
            print("  %-32s  (frame missing)" % label)
            continue
        pa, pb, _, _ = match(a, b)
        s, inl, n = similarity(pa, pb)
        verdict = ""
        if inl >= 12 and not (s != s):
            if abs(s - pred) / pred < 0.12:
                verdict = "MATCHES the census: uncropped" if pred > 1.2 else "unchanged"
            elif abs(s - 1.0) < 0.15:
                verdict = "same framing: still cropped"
            else:
                verdict = "partial: %.0f%% of the way" % (100 * (s - 1) / (pred - 1)) if pred > 1.2 else "changed"
        else:
            verdict = "no reliable match"
        print("  %-32s %6d/%-5d  %9.3f  %9.3f  %s -- %s" % (label, inl, n, s, pred, note, verdict))


if __name__ == "__main__":
    main_()
