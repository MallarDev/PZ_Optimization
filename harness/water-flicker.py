#!/usr/bin/env python3
"""Water reflection flicker metric on a gray devCapture of a walk (camera follows the player).

water-flicker.py <run> [--png DIR] [--top N] [--grid CxR] [--skip S]

Per frame t: align t-1 and t+1 onto t (integer shift search, +-10 px), then a pixel "blinks" when it differs from BOTH
neighbours by > T in the same direction while the neighbours agree with each other (< T/2): a one-frame flash or drop.
Prints the blink count per frame (mean, p99, frames over 0.5 % of the crop), a grid heat map of where they are, and
writes the worst frames (prev | this | next) as PNGs.
"""
import argparse
import os
import sys

import numpy as np


def load(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    stamps = [int(x) for x in lines[1:] if x.strip()]
    raw = np.fromfile(os.path.join(d, "frames.gray"), dtype=np.uint8)
    n = min(len(stamps), raw.size // (w * h))
    return raw[: n * w * h].reshape(n, h, w)[:, ::-1], np.array(stamps[:n])


def best_shift(a, b, r=10):
    """(dy, dx) so that b shifted by it matches a best (centre region)."""
    h, w = a.shape
    m = r + 2
    ac = a[m:h - m:2, m:w - m:2].astype(np.int16)
    best, arg = None, (0, 0)
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            bc = b[m + dy:h - m + dy:2, m + dx:w - m + dx:2].astype(np.int16)
            e = np.abs(ac - bc).mean()
            if best is None or e < best:
                best, arg = e, (dy, dx)
    return arg


def shifted(b, s):
    dy, dx = s
    return np.roll(np.roll(b, -dy, 0), -dx, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--png")
    ap.add_argument("--top", type=int, default=6)
    ap.add_argument("--t", type=int, default=28)
    ap.add_argument("--grid", default="10x6")
    ap.add_argument("--skip", type=float, default=3.0, help="seconds skipped at the start (settle)")
    a = ap.parse_args()
    fr, st = load(a.run)
    n, h, w = fr.shape
    t0 = st[0]
    first = int(np.searchsorted(st, t0 + a.skip * 1000))
    gx, gy = (int(v) for v in a.grid.split("x"))
    heat = np.zeros((gy, gx))
    counts = []
    m = 12
    sh = {}
    for t in range(max(1, first), n):
        sh[t] = best_shift(fr[t], fr[t - 1])  # fr[t-1] shifted by sh[t] lines up with fr[t]
    for t in range(max(1, first), n - 1):
        p, c, q = fr[t - 1], fr[t], fr[t + 1]
        sp, sq = sh[t], (-sh[t + 1][0], -sh[t + 1][1])
        p2 = shifted(p, sp).astype(np.int16)
        q2 = shifted(q, sq).astype(np.int16)
        c2 = c.astype(np.int16)
        d1, d2 = c2 - p2, c2 - q2
        blink = ((d1 > a.t) & (d2 > a.t) | (d1 < -a.t) & (d2 < -a.t)) & (np.abs(p2 - q2) < a.t // 2)
        blink[:m] = blink[-m:] = False
        blink[:, :m] = blink[:, -m:] = False
        k = int(blink.sum())
        counts.append((k, t, sp, sq))
        if k:
            ys, xs = np.nonzero(blink)
            np.add.at(heat, (ys * gy // h, xs * gx // w), 1)
    ks = np.array([c[0] for c in counts])
    dur = (st[-1] - st[first]) / 1000.0
    print(f"{a.run}: {n} frames, {dur:.1f} s analysed ({len(ks) / max(dur, 1e-3):.0f} fps), crop {w}x{h}")
    print(f"blink px/frame mean {ks.mean():.1f} p50 {np.percentile(ks, 50):.0f} p99 {np.percentile(ks, 99):.0f} max {ks.max()}; "
          f"frames > 0.1 %: {(ks > w * h * 0.001).sum()}  > 0.5 %: {(ks > w * h * 0.005).sum()}")
    print("heat (blink px per cell, rows top->bottom):")
    for r in range(gy):
        print("  " + " ".join(f"{int(v):6d}" for v in heat[r]))
    big=[t for k,t,_,_ in counts if k > w*h*0.005]
    print("big frames:", big)
    import numpy as _n; print("gaps:", _n.diff(big).tolist())
    worst = sorted(counts, reverse=True)[: a.top]
    for k, t, sp, sq in worst:
        print(f"  frame {t} +{(st[t] - t0) / 1000:.2f}s blink {k} shifts prev {sp} next {sq}")
    if a.png:
        from PIL import Image
        os.makedirs(a.png, exist_ok=True)
        for k, t, sp, sq in worst:
            strip = np.concatenate([shifted(fr[t - 1], sp), fr[t], shifted(fr[t + 1], sq)], 1)
            Image.fromarray(strip).save(os.path.join(a.png, f"f{t:05d}-{k}.png"))


if __name__ == "__main__":
    sys.exit(main())
