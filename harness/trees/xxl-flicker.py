#!/usr/bin/env python3
"""Flicker of a see-through XXL tree over a standing player (2026-10-08, the maintainer's flip save): every presented
frame of a still camera (pzopt.FrameCapture, --prop devCapture=start,secs,240,scale[,gray]) and the transients in a box
round the screen centre, where the tree's see-through crown sits over the player, and in the rest of the world view.

A transient pixel changes by >= THRESH/255 and comes back within MAXK frames (something appearing or vanishing for one to
three frames); with the camera still, steady changes (a walking zombie, a fading tree) are A -> B and not counted.
Burst frames: frames where more than BURST % of the centre box blinks in blob pixels (a whole-image sub-pixel resample, e.g.
a dynamic-resolution step, moves every thin edge at once but leaves no dense cluster). Blob pixels: transient pixels whose 5 x 5
neighbourhood is at least half transient (a crown or a sprite blinking as a whole); isolated ones (single-pixel glints,
sub-pixel sparkle on wet blood) are left out of the blob figures.

Usage: xxl-flicker.py RUN [--start S] [--end E] [--box 0.42] [--json out.json] [--strip out.png]
"""
import argparse
import json
import os
import sys

import numpy as np


def load(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()], dtype=np.int64)
    gray = head.get("fmt") == "gray"
    path = os.path.join(d, "frames.gray" if gray else "frames.rgba")
    ch = 1 if gray else 4
    raw = np.memmap(path, dtype=np.uint8, mode="r")
    n = min(len(stamps), raw.size // (w * h * ch))
    f = raw[: n * w * h * ch].reshape(n, h, w, ch)
    return f, stamps[:n], w, h, gray


def luma(f, i, gray):
    a = f[i, ::-1]
    if gray:
        return a[:, :, 0].astype(np.int16)
    return (a[:, :, :3].astype(np.float32) @ np.array([0.299, 0.587, 0.114], np.float32)).astype(np.int16)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--start", type=float, default=0.0)
    ap.add_argument("--end", type=float, default=1e9)
    ap.add_argument("--box", type=float, default=0.42, help="centre box size as a share of the height (width = 1.2 x)")
    ap.add_argument("--thresh", type=int, default=32)
    ap.add_argument("--maxk", type=int, default=3)
    ap.add_argument("--burst", type=float, default=1.0, help="percent of the centre box blinking in one frame")
    ap.add_argument("--json")
    ap.add_argument("--strip")
    a = ap.parse_args()
    f, stamps, w, h, gray = load(a.run)
    t = (stamps - stamps[0]) / 1000.0
    idx = [i for i in range(len(t)) if a.start <= t[i] <= a.end]
    if len(idx) < 10:
        sys.exit("too few frames in the window")
    # HUD: the top bar, the left icon column, the bottom hotbar strip, the right icon column
    world = np.ones((h, w), bool)
    world[: int(0.04 * h)] = False
    world[:, : int(0.035 * w)] = False
    world[:, int(0.965 * w):] = False
    world[int(0.9 * h):, int(0.35 * w): int(0.65 * w)] = False
    bh = int(a.box * h)
    bw = int(1.2 * bh)
    cy, cx = h // 2, w // 2
    centre = np.zeros((h, w), bool)
    centre[cy - bh // 2: cy + bh // 2, cx - bw // 2: cx + bw // 2] = True
    centre &= world
    rest = world & ~centre
    L = [luma(f, i, gray) for i in idx]
    per_c, per_r, bursts, worst = [], [], [], (0, 0)
    blob_c = []
    for j in range(1, len(L) - 1):
        base = L[j - 1]
        jump = np.abs(L[j] - base) >= a.thresh
        back = np.zeros_like(jump)
        for k in range(1, a.maxk + 1):  # back near the frame before the jump within maxk frames
            if j + k < len(L):
                back |= np.abs(L[j + k] - base) < a.thresh // 2
        tr = jump & back
        k5 = np.cumsum(np.cumsum(np.pad(tr.astype(np.int32), ((3, 2), (3, 2))), 0), 1)
        dense = (k5[5:, 5:] - k5[:-5, 5:] - k5[5:, :-5] + k5[:-5, :-5]) >= 13
        blob_c.append(int((tr & dense & centre).sum()))
        c = int((tr & centre).sum())
        r = int((tr & rest).sum())
        per_c.append(c)
        per_r.append(r)
        share = 100.0 * blob_c[-1] / max(1, centre.sum())
        if share > a.burst:
            bursts.append({"t": round(float(t[idx[j]] - t[idx[0]]), 3), "frame": idx[j], "share_pct": round(share, 2)})
        if c > worst[0]:
            worst = (c, idx[j])
    gaps = int((np.diff(stamps[idx]) > 2.5 * np.median(np.diff(stamps[idx]))).sum())
    out = {
        "run": os.path.basename(os.path.normpath(a.run)),
        "frames": len(idx),
        "fps": round(float((len(idx) - 1) / max(1e-6, t[idx[-1]] - t[idx[0]])), 1),
        "window_s": [round(float(t[idx[0]]), 2), round(float(t[idx[-1]]), 2)],
        "capture_gaps": gaps,
        "centre_box_px": int(centre.sum()),
        "rest_px": int(rest.sum()),
        "centre": {
            "transient_px_per_frame": round(float(np.mean(per_c)), 1),
            "per_100k_px": round(1e5 * float(np.mean(per_c)) / max(1, centre.sum()), 2),
            "max_share_in_one_frame_pct": round(100.0 * max(per_c) / max(1, centre.sum()), 2),
            "blob_px_per_frame": round(float(np.mean(blob_c)), 2),
            "blob_max_share_in_one_frame_pct": round(100.0 * max(blob_c) / max(1, centre.sum()), 3),
            "blob_frames_over_0_5pct": int(sum(1 for v in blob_c if v > 0.005 * centre.sum())),
            "burst_frames": len(bursts),
            "bursts": bursts[:20],
        },
        "rest": {
            "transient_px_per_frame": round(float(np.mean(per_r)), 1),
            "per_100k_px": round(1e5 * float(np.mean(per_r)) / max(1, rest.sum()), 2),
            "max_share_in_one_frame_pct": round(100.0 * max(per_r) / max(1, rest.sum()), 2),
        },
    }
    print(json.dumps(out, indent=1))
    if a.json:
        with open(a.json, "w") as fh:
            json.dump(out, fh, indent=1)
    if a.strip and worst[0] > 0:
        from PIL import Image
        i = worst[1]
        frames = [luma(f, k, gray).clip(0, 255).astype(np.uint8)[cy - bh // 2: cy + bh // 2, cx - bw // 2: cx + bw // 2]
                  for k in range(max(0, i - 2), min(len(stamps), i + 4))]
        Image.fromarray(np.hstack(frames)).save(a.strip)


if __name__ == "__main__":
    main()
