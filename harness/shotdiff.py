#!/usr/bin/env python3
"""Pixel difference of two screenshots (visual parity of a change that should not alter the picture).

    harness/shotdiff.py <a.png> <b.png> [--out diff.png] [--threshold 8]

Prints the share of pixels whose largest channel difference exceeds the threshold, the mean and max difference, and the
bounding box of the changed pixels; --out writes a heat map (changed pixels bright on a dimmed copy of a).
"""
import argparse
import sys

from PIL import Image, ImageChops


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--out")
    ap.add_argument("--threshold", type=int, default=8)
    a = ap.parse_args()
    ia = Image.open(a.a).convert("RGB")
    ib = Image.open(a.b).convert("RGB")
    if ia.size != ib.size:
        print("size differs: %s vs %s" % (ia.size, ib.size))
        sys.exit(1)
    d = ImageChops.difference(ia, ib)
    r, g, b = d.split()
    m = ImageChops.lighter(ImageChops.lighter(r, g), b)
    hist = m.histogram()
    n = ia.size[0] * ia.size[1]
    changed = sum(hist[a.threshold + 1:])
    mean = sum(i * c for i, c in enumerate(hist)) / n
    mx = max(i for i, c in enumerate(hist) if c)
    mask = m.point(lambda v: 255 if v > a.threshold else 0)
    print("%s vs %s: %.3f %% of pixels differ by more than %d (mean %.2f, max %d), changed box %s"
          % (a.a, a.b, 100.0 * changed / n, a.threshold, mean, mx, mask.getbbox()))
    if a.out:
        base = Image.eval(ia, lambda v: v // 3)
        heat = Image.merge("RGB", (mask, Image.new("L", ia.size, 0), Image.new("L", ia.size, 0)))
        Image.composite(heat, base, mask).save(a.out)


if __name__ == "__main__":
    main()
