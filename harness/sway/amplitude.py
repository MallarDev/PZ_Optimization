#!/usr/bin/env python3
"""How far the plants move on screen in a devCapture sequence (harness/ppl/capture.py PNGs or a run dir): for every
64 x 64 block of the first frame, its best horizontal shift (-12..+12 px, 1/4 px by parabolic refinement) in every later
frame (normalised cross-correlation); blocks that correlate < 0.6 skipped. Prints the median over moving blocks of each
block's shift range (max - min over the sequence) and its std: the sway amplitude as the viewer sees it.

    amplitude.py <png dir> [--blocks N]"""
import glob
import os
import sys

import numpy as np
from PIL import Image


def load(d):
    fs = sorted(glob.glob(os.path.join(d, "f*.png")))
    return [np.asarray(Image.open(f).convert("L"), np.float32) for f in fs]


def shift(a, b, maxs=12):
    best, bs, sc = -2.0, 0, []
    h, w = a.shape
    for s in range(-maxs, maxs + 1):
        if s >= 0:
            x, y = a[:, s:], b[:, :w - s]
        else:
            x, y = a[:, :w + s], b[:, -s:]
        x = x - x.mean()
        y = y - y.mean()
        c = float((x * y).sum() / (np.sqrt((x * x).sum() * (y * y).sum()) + 1e-6))
        sc.append(c)
        if c > best:
            best, bs = c, s
    i = bs + maxs
    if 0 < i < len(sc) - 1:
        d = sc[i - 1] - 2 * sc[i] + sc[i + 1]
        if d < 0:
            return bs + 0.5 * (sc[i - 1] - sc[i + 1]) / d, best
    return float(bs), best


def main():
    d = sys.argv[1]
    fr = load(d)
    f0 = fr[0]
    H, W = f0.shape
    ranges = []
    for by in range(40, H - 64, 64):
        for bx in range(16, W - 80, 64):
            ref = f0[by:by + 64, bx:bx + 64]
            if ref.std() < 8:
                continue
            ss = []
            ok = True
            for f in fr[1:]:
                # compare against a wider strip of the later frame: the block's shift inside it
                s, c = shift(ref, f[by:by + 64, bx:bx + 64])
                if c < 0.6:
                    ok = False
                    break
                ss.append(s)
            if ok and ss:
                ranges.append((max(ss + [0.0]) - min(ss + [0.0]), float(np.std(ss))))
    if not ranges:
        print(d, ": no trackable blocks")
        return
    r = np.array(ranges)
    moving = r[r[:, 0] > 0.5]
    print("%s: %d blocks, %d moving (>0.5 px); moving blocks: median range %.2f px, p90 %.2f px, median std %.2f px"
          % (os.path.basename(d.rstrip("/")), len(r), len(moving), np.median(moving[:, 0]) if len(moving) else 0,
             np.percentile(moving[:, 0], 90) if len(moving) else 0, np.median(moving[:, 1]) if len(moving) else 0))


if __name__ == "__main__":
    main()
