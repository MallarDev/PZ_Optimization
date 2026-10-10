#!/usr/bin/env python3
"""Horizontal stripes across tree crowns (the sun-shadow march on a baked tree card), and Jev's verdict.

A stripe pixel is a dark row inside a lit crown: luma at most --dark, both the pixel 2 rows above and 2 rows below at
least --ridge brighter, on a horizontal run of >= --run such pixels (+-1 px of slack) with another such run 2..8 rows
above or below it (the stripes come interleaved, a crown shows a band of them). Sprite outlines, branches and the
ground between crowns fail the "run" or the "repeated" test. The detector sees the picture only; it does not know what
a tree is, so compare with a control of the same scene (sunShadows=false) rather than with 0.

Usage: tree-stripes.py --test <run|png> [--control <run|png>] [--before <run|png>] [--context "..."] [--json f] [--no-jev]
A run means its devCapture sequence (<run>/capture, frames.gray or frames.rgba) when it has one, else its shot-game.png
and shot2-game.png. Prints per input: stripe px per megapixel per frame (mean / p90 / max), frames with >= 200 stripe
px, the worst frame, and with a capture the split per zoom step mark; then Jev (numbers only). Exit 0 = Jev says the
test does not show the stripes.
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image

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


def luma(frame, gray):
    if gray:
        return frame.astype(np.int16)
    f = frame[:, :, :3].astype(np.int32)
    return ((f[:, :, 0] * 299 + f[:, :, 1] * 587 + f[:, :, 2] * 114) // 1000).astype(np.int16)


def runs_along_x(m, need):
    """Pixels of m that lie on a horizontal run of >= need set pixels (one gap of a pixel allowed)."""
    h, w = m.shape
    filled = m | (np.roll(m, 1, axis=1) & np.roll(m, -1, axis=1))  # a one-pixel gap closed
    cs = np.cumsum(filled, axis=1, dtype=np.int32)
    cs = np.concatenate([np.zeros((h, 1), np.int32), cs], axis=1)
    out = np.zeros_like(m)
    for x0 in range(0, w - need + 1):
        full = (cs[:, x0 + need] - cs[:, x0]) >= need
        out[:, x0:x0 + need] |= full[:, None]
    return out & m


IGNORE = []


def stripe_mask(l, dark, ridge, need):
    # the stripes come in 2-row pairs: the lit neighbour is the brighter of the rows 2 and 3 away
    up = np.full_like(l, 255)
    dn = np.full_like(l, 255)
    up[3:] = np.maximum(l[1:-2], l[:-3])
    dn[:-3] = np.maximum(l[2:-1], l[3:])
    thin = (l <= dark) & (up - l >= ridge) & (dn - l >= ridge)
    lines = runs_along_x(thin, need)
    # repeated: another line row 2..8 px above or below
    near = np.zeros_like(lines)
    for d in range(2, 9):
        near[d:] |= lines[:-d]
        near[:-d] |= lines[d:]
    m = lines & near
    for (x, y, w, h) in IGNORE:
        m[y:y + h, x:x + w] = False
    return m


def shots(arg):
    p = Path(arg)
    if p.is_dir():
        if (p / "capture" / "index.txt").exists():
            return [p]
        return [q for q in (p / "shot-game.png", p / "shot2-game.png") if q.exists()]
    return [p]


def measure(arg, a):
    """Per-frame stripe px per megapixel, with the frame stamps and zoom marks when a capture."""
    p = Path(arg)
    rows, stamps, marks = [], [], []
    if p.is_dir() and (p / "capture" / "index.txt").exists():
        w, h, gray, st, frames = capture(str(p))
        mp = w * h / 1e6
        step = max(1, int(a.every))
        for i in range(0, len(frames), step):
            m = stripe_mask(luma(frames[i], gray), a.dark, a.ridge, a.run)
            rows.append(int(m.sum()) / mp)
            stamps.append(int(st[i]))
        marks = zoom_marks(str(p))
        size = f"{w}x{h} capture, {len(frames)} frames, every {step}"
    else:
        for q in shots(arg):
            img = np.asarray(Image.open(q).convert("RGB"))
            mp = img.shape[0] * img.shape[1] / 1e6
            m = stripe_mask(luma(img, False), a.dark, a.ridge, a.run)
            rows.append(int(m.sum()) / mp)
            stamps.append(0)
            if a.mask_dir:
                Path(a.mask_dir).mkdir(parents=True, exist_ok=True)
                Image.fromarray((m * 255).astype(np.uint8)).save(Path(a.mask_dir) / (q.parent.name + "-" + q.stem + "-stripes.png"))
        size = f"{len(rows)} shot(s)"
    r = np.array(rows) if rows else np.zeros(1)
    out = {
        "size": size, "frames": len(rows),
        "stripe_px_per_mp_mean": round(float(r.mean()), 1),
        "stripe_px_per_mp_p90": round(float(np.percentile(r, 90)), 1),
        "stripe_px_per_mp_max": round(float(r.max()), 1),
        "frames_over_200": int((r >= 200).sum()),
        "frames_over_50": int((r >= 50).sum()),
    }
    if marks and stamps and stamps[0]:
        # per zoom level: the frames between a mark and the next
        by = {}
        st = np.array(stamps)
        for k, (name, t) in enumerate(marks):
            t1 = marks[k + 1][1] if k + 1 < len(marks) else 1e18
            sel = (st >= t * 1000) & (st < t1 * 1000)
            if sel.any():
                by.setdefault(name, []).extend(r[sel].tolist())
        out["by_zoom_level"] = {k: round(float(np.mean(v)), 1) for k, v in by.items()}
        out["zoom_steps"] = len(marks)
    out["per_frame"] = [round(x, 1) for x in rows]
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--test", required=True)
    ap.add_argument("--control")
    ap.add_argument("--before")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    ap.add_argument("--dark", type=int, default=70, help="max luma of a stripe pixel")
    ap.add_argument("--ridge", type=int, default=10, help="min luma step to the brighter of the rows 2-3 px above and below")
    ap.add_argument("--run", type=int, default=16, help="min horizontal run in px")
    ap.add_argument("--every", type=int, default=3, help="capture: measure every Nth frame")
    ap.add_argument("--mask-dir", help="shots: write the stripe masks here")
    ap.add_argument("--ignore", action="append", default=[], help="x,y,w,h region (capture px, as stored: bottom-up) left out, e.g. the overlay panel; repeatable")
    a = ap.parse_args()
    for r in a.ignore:
        IGNORE.append(tuple(int(v) for v in r.split(",")))
    results = {}
    for name, arg in (("test", a.test), ("control", a.control), ("before", a.before)):
        if not arg:
            continue
        m = measure(arg, a)
        results[name] = m
        per = m.pop("per_frame")
        print(f"{name}: {arg}")
        print(f"  {m['size']}: stripe px/Mpx mean {m['stripe_px_per_mp_mean']} p90 {m['stripe_px_per_mp_p90']} max {m['stripe_px_per_mp_max']}; "
              f"frames >= 50: {m['frames_over_50']}, >= 200: {m['frames_over_200']} of {m['frames']}")
        if "by_zoom_level" in m:
            print("  by zoom level (mean): " + ", ".join(f"{k} {v}" for k, v in m["by_zoom_level"].items()))
        m["per_frame_every"] = per[:: max(1, len(per) // 40)]
    verdict = None
    if not a.no_jev:
        from typesafe_client import ask, choice, noul, fmt  # noqa: E402
        state = {
            "what": "Project Zomboid, pzopt build: a player reported thin horizontal black lines across tree crowns while running "
                    "through a forest and zooming. The stripe detector counts dark 1-px rows inside lit areas that lie on a "
                    "horizontal run of >= 24 px with another such row 2..8 px away (an interleaved band). Sprite outlines and "
                    "the ground give a floor of such pixels in every picture, so compare test against control (same scene, "
                    "suspect feature off), not against 0. A still shot is one frame; a capture is a frame sequence of a run "
                    "with zoom steps. " + a.context,
            "metric": "stripe px per megapixel per frame",
            **results,
        }
        questions = {
            "test_has_stripes": noul("Does `test` show clearly more stripe pixels than `control` (the reported striped crowns)?"),
            "before_has_stripes": noul("If `before` is given: does it show clearly more stripe pixels than `control`? (no when absent)"),
            "control_has_stripes": noul("Does `control` itself show a high stripe count (frames >= 200 or a p90 far above its mean)?"),
            "verdict": choice({"question": "What best describes `test`?"},
                              {"stripes_present": "test shows the striped crowns: clearly above control",
                               "fixed": "test is at or near control (a before, when given, was above)",
                               "inconclusive": "the numbers do not separate test from control (both high, both noisy, or too few frames)"}),
        }
        log = []
        answers = ask(state, questions, log=log)
        print("Jev: " + fmt(answers))
        verdict = answers
    if a.json:
        json.dump({"args": vars(a), "results": results, "jev": verdict}, open(a.json, "w"), indent=1)
    if verdict:
        v = verdict["verdict"]
        top = max(v["choice"].items(), key=lambda kv: kv[1])[0] if isinstance(v.get("choice"), dict) else v.get("choice")
        return 0 if top == "fixed" else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
