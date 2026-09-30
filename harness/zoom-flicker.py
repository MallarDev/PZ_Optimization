#!/usr/bin/env python3
"""Do lights blink while the camera zooms? A motion-tolerant blink metric over a devCapture sequence, and Jev's verdict.

Built for the 2026-09-30 report "the lights flicker while zooming in and out" (docs/findings-zoom-light-flicker-2026-09-30.md).
While the camera zooms every pixel moves, so flicker.py / region-flicker.py (a pixel that jumps and comes back) count the
moving edges too. Here each frame is reduced to the mean of BxB pixel blocks (16 by default) and a block blinks when its
value leaves the range of the 3x3 blocks around it in BOTH the previous and the next frame by more than --thresh (4/255):
steady zoom or pan motion moves a block's content less than a block a frame and stays inside that range, a lamp pool that
brightens or dims for one frame does not.

Capture (1:1, no resampling: a scaled devCapture blit aliases fine texture into fake blinks):
  --prop devCapture=8,26,240,100,gray,crop=1920:630:1280:900   (the centre of a 5120x2160 screen)
with the zoom rig  --flag zoom=1 --flag zoom_cycle=1 --flag route=S:1 --flag speed=0.025 --route-seconds 40.

Usage: zoom-flicker.py --test RUN [--before RUN] [--control RUN] [--context "..."] [--json out] [--no-jev] [--block 16]
  --test     the build / setting under test
  --before   the same scene on the build or setting that showed the bug (optional)
  --control  the same scene with the suspect feature off, the no-bug reference (optional)
Prints per run: blinking blocks in all, per 10 s, frames with >= 3 blinking blocks, the largest frame, and the split by
time after the last zoom step (the ease is ~300 ms); then Jev's verdict (numbers only). Exit 0 = Jev says the test does not
flicker.
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

sys.path.insert(0, str(Path(__file__).resolve().parent))


def capture(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().splitlines()
    head = lines[0]
    w = int(re.search(r"w=(\d+)", head).group(1))
    h = int(re.search(r"h=(\d+)", head).group(1))
    gray = "fmt=gray" in head
    stamps = np.array([int(x) for x in lines[1:] if x.strip().isdigit()], dtype=np.int64)
    raw = np.memmap(os.path.join(d, "frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(stamps), raw.size // (w * h * (1 if gray else 4)))
    frames = raw[: n * w * h * (1 if gray else 4)].reshape(n, h, w, *(() if gray else (4,)))
    return w, h, gray, stamps[:n], frames


def zoom_marks(run):
    """The harness's zoom-step marks (pzopt-frames.out '# zoom-<level> <us> <frame>') as epoch ms."""
    p = os.path.join(run, "pzopt-frames.out")
    if not os.path.exists(p):
        return []
    anchor, marks = None, []
    for line in open(p):
        if line.startswith("# anchor") and anchor is None:
            f = line.split()
            anchor = (int(f[2]) / 1000.0, int(f[3]))
        m = re.match(r"# (zoom-[\d.]+) (\d+) (\d+)", line)
        if m and anchor:
            marks.append((m.group(1), anchor[0] + (int(m.group(2)) - anchor[1]) / 1000.0))
    return marks


def blinks(run, block, thresh):
    w, h, gray, stamps, frames = capture(run)
    hb, wb = h // block, w // block

    def lowpass(i):
        f = frames[i]
        if not gray:
            f = f[..., :3].astype(np.float32) @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
        return f[: hb * block, : wb * block].reshape(hb, block, wb, block).mean(axis=(1, 3), dtype=np.float32)

    def span(g):
        v = sliding_window_view(g, (3, 3))
        return v.min(axis=(2, 3)), v.max(axis=(2, 3))

    n = len(stamps)
    per = np.zeros(n, dtype=np.int32)
    prev = lowpass(0)
    pmn, pmx = span(prev)
    cur = lowpass(1)
    cmn, cmx = span(cur)
    for i in range(1, n - 1):
        nxt = lowpass(i + 1)
        nmn, nmx = span(nxt)
        b = cur[1:-1, 1:-1]
        per[i] = int(((b > np.maximum(pmx, nmx) + thresh) | (b < np.minimum(pmn, nmn) - thresh)).sum())
        pmn, pmx, cur, cmn, cmx = cmn, cmx, nxt, nmn, nmx
    secs = max(1e-3, (stamps[-1] - stamps[0]) / 1000.0)
    marks = zoom_marks(run)
    me = np.array([m[1] for m in marks]) if marks else np.array([])
    after = {"0-100ms": 0, "100-300ms": 0, "300-1000ms": 0, "later_or_no_step": 0}
    for i in np.nonzero(per)[0]:
        k = np.searchsorted(me, stamps[i]) - 1 if len(me) else -1
        dt = stamps[i] - me[k] if k >= 0 else 1e9
        key = "0-100ms" if dt < 100 else "100-300ms" if dt < 300 else "300-1000ms" if dt < 1000 else "later_or_no_step"
        after[key] += int(per[i])
    worst = np.argsort(per)[::-1][:5]
    return {
        "run": os.path.basename(run.rstrip("/")),
        "frames": int(n), "seconds": round(secs, 1), "fps": round(n / secs), "capture": f"{w}x{h}", "block_px": block,
        "zoom_steps": len(marks),
        "blinking_blocks_total": int(per.sum()),
        "blinking_blocks_per_10s": round(10 * per.sum() / secs, 1),
        "frames_with_3_or_more": int((per >= 3).sum()),
        "frames_with_50_or_more": int((per >= 50).sum()),
        "largest_frame_blocks": int(per.max()),
        "largest_frame_share_pct": round(100 * per.max() / max(1, (hb - 2) * (wb - 2)), 1),
        "blocks_by_time_after_zoom_step": after,
        "worst_frames": [{"frame": int(i), "blocks": int(per[i])} for i in worst],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", required=True)
    ap.add_argument("--before")
    ap.add_argument("--control")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    ap.add_argument("--block", type=int, default=16)
    ap.add_argument("--thresh", type=float, default=4.0)
    a = ap.parse_args()
    runs = {"test": a.test, "before": a.before, "control": a.control}
    res = {k: blinks(v, a.block, a.thresh) for k, v in runs.items() if v}
    for k, r in res.items():
        print(f"{k:8s} {r['run']}: {r['frames']} frames {r['capture']} at {r['fps']} fps, {r['zoom_steps']} zoom steps; blinking blocks "
              f"{r['blinking_blocks_total']} ({r['blinking_blocks_per_10s']} per 10 s), frames >= 3: {r['frames_with_3_or_more']}, "
              f">= 50: {r['frames_with_50_or_more']}, largest {r['largest_frame_blocks']} ({r['largest_frame_share_pct']} % of the view); "
              f"by time after a step {r['blocks_by_time_after_zoom_step']}")
    out = {"metrics": res}
    code = 0
    if not a.no_jev:
        from typesafe_client import ask, choice, fmt, noul  # noqa: E402
        state = {
            "setup": "Frame-exact captures (every presented frame read back in game, a 1:1 crop of the screen centre) of the same "
                     "scene: the camera zooms in and out one mouse-wheel step every second (eased over ~300 ms) while the "
                     "character stands still. Each frame is reduced to 16x16-pixel block means; a block 'blinks' when it leaves "
                     "the range of its 3x3 neighbourhood in both the previous and the next frame by more than 4/255, so steady "
                     "zoom motion is not counted but a lamp pool that brightens or dims for a frame is. A few dozen blinks per 10 s "
                     "are the background of fast zoom motion (edges crossing blocks). " + a.context,
            "report": "the player sees the lights flicker while zooming in and out",
            **res,
        }
        questions = {
            "test_flickers": noul("Does `test` show the reported flicker: many blinking blocks, frames where a large share of the view "
                                  "blinks, concentrated in the zoom ease (0-300 ms after a step), well above the background of zoom motion?"),
            "before_flickers": noul("If `before` is given: does it show that flicker (answer no when it is absent)?"),
            "control_flickers": noul("If `control` is given: does it show that flicker (answer no when it is absent)?"),
            "verdict": choice({"question": "What best describes `test`?",
                               "note": "Compare the rates, the large frames and the ease concentration with `before` and `control`."},
                              {"fixed": "`before` flickers and `test` is at or below the no-flicker background (like `control`)",
                               "still_flickers": "`test` still shows the flicker",
                               "flicker_confirmed": "`test` flickers and there is no fixed build to compare (a repro)",
                               "no_flicker": "neither `test` nor `before` flickers: not reproduced",
                               "inconclusive": "too few frames, no zoom steps or contradicting numbers"}),
        }
        log = []
        answers = ask(state, questions, log=log)
        print("jev:")
        print("  " + fmt(answers).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
        out.update({"state": state, "answers": answers, "typesafe": log[0]})
        v = answers.get("verdict", {})
        top = v.get("choice") if isinstance(v, dict) else None
        code = 0 if top in ("fixed", "no_flicker") else 1
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=1))
    sys.exit(code)


if __name__ == "__main__":
    main()
