#!/usr/bin/env python3
"""Tile pops: square-sized areas whose light switches in one frame (2026-10-03, the lantern under torchSource).

    tile-pop.py <run>... [--block 16] [--step 14] [--calm 5] [--judge] [--json f]

For a `devCapture` sequence (gray or colour, 1:1 crop) each frame is cut into --block px blocks (mean luma). A pop is a
block, at least --lit bright in one of the two frames (lit ground), whose mean changes by >= --step against the previous
frame while at least 5 of its 8 neighbours change by < --calm:
a lone tile switching on or off (pixelLight's per-square torch visibility, no fade), not a beam or the camera sweeping (its
neighbours move with it), not the player (a disc round the centre is masked). Prints pops per second, the share of frames
with any pop and the largest per frame. --judge: Jev over the runs' numbers (the first run is the one in question, the
others controls).
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def load(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    st = [int(x) for x in lines[1:] if x.strip()]
    gray = head.get("fmt") == "gray"
    raw = np.memmap(os.path.join(d, "frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(st), raw.size // (w * h * (1 if gray else 4)))

    def get(i):
        if gray:
            return np.asarray(raw[i * w * h:(i + 1) * w * h]).reshape(h, w)[::-1].astype(np.float32)
        f = np.asarray(raw[i * w * h * 4:(i + 1) * w * h * 4]).reshape(h, w, 4)[::-1, :, :3].astype(np.float32)
        return f @ np.array([0.299, 0.587, 0.114], np.float32)
    return get, st[:n], w, h


def measure(run, block, step, calm, lit=40.0):
    get, st, w, h = load(run)
    bh, bw = h // block, w // block

    def blocks(f):
        return f[:bh * block, :bw * block].reshape(bh, block, bw, block).mean(axis=(1, 3))
    yy, xx = np.mgrid[0:bh, 0:bw]
    keep = (xx - bw / 2) ** 2 + (yy - bh / 2) ** 2 > (min(bw, bh) * 0.12) ** 2
    prev = blocks(get(0))
    per_frame = []
    for i in range(1, len(st)):
        cur = blocks(get(i))
        d = np.abs(cur - prev)
        quiet = np.zeros_like(d, dtype=np.int32)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dy == 0 and dx == 0:
                    continue
                quiet += np.roll(np.roll(d, dy, 0), dx, 1) < calm
        pops = (d >= step) & (quiet >= 5) & keep & (np.maximum(cur, prev) >= lit)  # on lit ground: the dark's foliage sway is not it
        per_frame.append(int(pops.sum()))
        prev = cur
    secs = max(1e-3, (st[-1] - st[0]) / 1000)
    pf = np.array(per_frame)
    return {"run": os.path.basename(run.rstrip("/")), "frames": len(st), "seconds": round(secs, 1), "fps": round(len(st) / secs),
            "pops": int(pf.sum()), "pops_per_s": round(float(pf.sum()) / secs, 2),
            "share_of_frames_with_a_pop": round(float((pf > 0).mean()), 4), "max_pops_in_a_frame": int(pf.max(initial=0))}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--block", type=int, default=16)
    ap.add_argument("--step", type=float, default=14)
    ap.add_argument("--calm", type=float, default=5)
    ap.add_argument("--lit", type=float, default=40, help="a pop's block is at least this bright in one of the two frames (lit floor)")
    ap.add_argument("--labels", default="", help="comma list, one per run (what each run is)")
    ap.add_argument("--judge", action="store_true")
    ap.add_argument("--json")
    a = ap.parse_args()
    labels = a.labels.split(",") if a.labels else [""] * len(a.runs)
    res = []
    for r, lab in zip(a.runs, labels):
        m = measure(r, a.block, a.step, a.calm, a.lit)
        m["what"] = lab
        res.append(m)
        print(json.dumps(m))
    out = {"runs": res}
    if a.judge:
        from typesafe_client import ask, choice, noul, fmt
        state = {
            "setup": "Frame-exact captures (every presented frame read back in-game, 1:1 crop) of the same night scene: the "
                     "character stands and turns slowly holding a lit lantern (and a torch). A 'pop' is a tile-sized block of the "
                     "picture whose brightness jumps in one frame while its neighbours stay calm: a square switching its light on "
                     "or off at once, which a player sees as tiles flickering. The camera and the beams move smoothly and are "
                     "not counted. The first run is the build in question; the others are controls (labelled).",
            "runs": res,
        }
        questions = {
            "first_pops_more": noul("Does the first run show clearly more tile pops (per second and share of frames) than every "
                                    "control?"),
            "stock_render_pops": noul("Does the control rendered without per-pixel lighting (the game's own lighting) show tile "
                                      "pops at a comparable rate to the first run?"),
            "verdict": choice({"question": "What do the numbers say about the tile flicker in the first run?"},
                              {"first_only": "the first run's configuration causes the tile pops; the controls do not have them",
                               "shared": "the controls pop about as much: not specific to the first run",
                               "none": "no run has a meaningful number of pops",
                               "inconclusive": "too few frames or contradicting numbers"}),
        }
        log = []
        ans = ask(state, questions, log=log)
        print("jev:")
        print("  " + fmt(ans).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
        out["jev"] = ans
    if a.json:
        open(a.json, "w").write(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
