"""Where are the holes in the frame records?  (issue #3)

The file's trailing frame_accounting says HOW MANY frame records were lost and to which of four
counters. This says WHERE: video_meta rows carry the encoder's frame number, so a jump in that
sequence is a hole, and its position decides whether a positional join is off by one from the
first frame or only after some event mid-clip. Stereo/still rows are listed beside the holes,
because a hole that coincides with a pair warm-up is a different bug from one at the clip edge.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "gen"))
import recording_pb2 as pb  # noqa: E402
from analyze_pairs import DATA  # noqa: E402


def holes(name):
    data = pb.VideoCaptureData()
    with open(os.path.join(DATA, name, "video_meta.pb3"), "rb") as f:
        data.ParseFromString(f.read())
    a = data.frame_accounting
    print("=== %s: %d frame rows; accounting: written=%d  queue-full meta=%d time=%d  "
          "unmatched meta=%d time=%d  complete=%s" % (
              name, len(data.video_meta), a.meta_written, a.meta_dropped_queue_full,
              a.time_dropped_queue_full, a.meta_dropped_unmatched, a.time_dropped_unmatched,
              a.complete))
    rows = list(data.video_meta)
    if not rows:
        print("  no video\n")
        return
    t0 = rows[0].time_ns
    print("  first frame number %d, last %d, span %.2f s" % (
        rows[0].frame_number, rows[-1].frame_number, (rows[-1].time_ns - t0) / 1e9))
    events = sorted((s.time_ns, s.jpeg_file) for s in data.stills if s.time_ns)
    n = 0
    for prev, cur in zip(rows, rows[1:]):
        gap = cur.frame_number - prev.frame_number
        if gap != 1:
            n += gap - 1
            near = [f for t, f in events if prev.time_ns - 150e6 <= t <= cur.time_ns + 150e6]
            print("  hole: frames %d..%d missing (%d) at +%.3f s, dt across it %.1f ms%s" % (
                prev.frame_number + 1, cur.frame_number - 1, gap - 1,
                (prev.time_ns - t0) / 1e9, (cur.time_ns - prev.time_ns) / 1e6,
                ("   near: " + ", ".join(near[:3])) if near else ""))
    lead = rows[0].frame_number
    print("  holes inside the sequence: %d frames; frames before the first row: %d" % (n, lead))
    if events:
        print("  still/stereo rows span +%.2f .. +%.2f s" % (
            (events[0][0] - t0) / 1e9, (events[-1][0] - t0) / 1e9))
    print()


if __name__ == "__main__":
    for s in sys.argv[1:]:
        holes(s)
