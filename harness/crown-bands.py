#!/usr/bin/env python3
"""Horizontal bands across a tree crown in a screen region, bright or dark, of any width, and Jev's verdict (2026-10-02).

    crown-bands.py --crop x,y,w,h --test <run|png> [--control <run|png>] [--before <run|png>] [--context "..."]
                   [--json f] [--no-jev]

The flip report "artifacts in the trees on the right side while driving at max zoom": bright horizontal bands, several
pixels wide and ~10 px apart, across a crown (an appended tree divided by the AO term of the ground under it).
tree-stripes.py looks for dark 1-px rows and does not see them. Here: the region's row profile over 24-px windows, the
band energy = mean |row - mean of the rows 3 above and 3 below| (luma levels), and the share of window rows above 6
levels. Crop in screen pixels, top-left origin. A run means the last frame of its devCapture (colour or gray, stored
bottom-up), else its shot-game.png. Compare against a control of the same scene (the suspect feature off).
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


def last_frame(arg):
    p = Path(arg)
    d = p / "capture"
    if p.is_dir() and (d / "index.txt").exists():
        head = (d / "index.txt").read_text().splitlines()[0]
        w = int(re.search(r"w=(\d+)", head).group(1))
        h = int(re.search(r"h=(\d+)", head).group(1))
        gray = (d / "frames.gray").exists()
        ch = 1 if gray else 4
        raw = np.memmap(d / ("frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
        n = raw.size // (w * h * ch)
        f = raw[(n - 1) * w * h * ch:n * w * h * ch].reshape(h, w, ch)[::-1]
        if gray:
            return f[..., 0].astype(np.float32)
        f = f[..., :3].astype(np.float32)
    else:
        q = p / "shot-game.png" if p.is_dir() else p
        f = np.asarray(Image.open(q).convert("RGB")).astype(np.float32)
    return f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114


def bands(l, crop):
    x, y, w, h = crop
    a = l[y:y + h, x:x + w]
    k = 24
    c = np.cumsum(np.pad(a, ((0, 0), (1, 0))), axis=1)
    hb = (c[:, k:] - c[:, :-k]) / k
    d = np.abs(hb[3:-3] - (hb[:-6] + hb[6:]) / 2)
    return {"band_energy": round(float(d.mean()), 3), "band_share_over_6": round(float((d > 6).mean()), 4)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--crop", required=True)
    ap.add_argument("--test", required=True)
    ap.add_argument("--control")
    ap.add_argument("--before")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    crop = tuple(int(v) for v in a.crop.split(","))
    results = {}
    for name, arg in (("test", a.test), ("control", a.control), ("before", a.before)):
        if arg:
            results[name] = bands(last_frame(arg), crop)
            print(f"{name}: {arg}\n  band energy {results[name]['band_energy']}  share > 6: {results[name]['band_share_over_6']}")
    verdict = None
    if not a.no_jev:
        from typesafe_client import ask, choice, noul, fmt  # noqa: E402
        state = {
            "what": "Project Zomboid, pzopt build: horizontal bands across a tree crown (bright rows several px wide, ~10 px apart). "
                    "Band energy = mean |row luma - mean of the rows 3 above and 3 below| over 24-px windows in the crown's screen "
                    "region; a smooth crown is ~1-2, leaf texture alone gives that floor. Compare test against control (the same "
                    "scene with the suspect feature off) and before (the build that showed the bands). " + a.context,
            **results,
        }
        questions = {
            "test_has_bands": noul("Does `test` show clearly more band energy than `control`?"),
            "before_has_bands": noul("If `before` is given: does it show clearly more band energy than `control`? (no when absent)"),
            "verdict": choice({"question": "What best describes `test`?"},
                              {"bands_present": "test shows the bands: clearly above control",
                               "fixed": "test is at or near control while before was clearly above",
                               "inconclusive": "the numbers do not separate the runs"}),
        }
        answers = ask(state, questions, log=[])
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
