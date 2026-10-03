#!/usr/bin/env python3
"""Glass masks of the game's mirror tiles (IsMirror, facing S / E: the sides the iso camera sees) for pzopt.Mirrors.

Each sprite's full 2x frame (128 x 256) goes into one cell of an R8 atlas: 255 = mirror glass, 0 = frame / body.
Rule: a texel is glass when it is opaque, light (luma > 0.42) and grey (saturation < 0.32), plus bluish (b - r > 0.075)
for the white medicine cabinet (its white body is grey too); the largest connected blob is kept and replaced by its convex hull
(streak highlights, notches), then eroded by one texel (the frame's anti-aliased rim stays out).

usage: masks.py [--out src/media/ui/pzopt/mirrors] [--sheet /tmp/masks.png]
writes mirror-masks.png (8 cells a row) and mirror-masks.txt (sprite name, cell)
"""
import argparse, os, sys, subprocess
import numpy as np
from PIL import Image
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tiles, packsprite

CW, CH, COLS = 128, 256, 8

def components(m):
    lab = np.zeros(m.shape, np.int32); n = 0; h, w = m.shape
    for y0, x0 in zip(*np.nonzero(m)):
        if lab[y0, x0]:
            continue
        n += 1; lab[y0, x0] = n; q = deque([(y0, x0)])
        while q:
            y, x = q.popleft()
            for yy, xx in ((y+1, x), (y-1, x), (y, x+1), (y, x-1)):
                if 0 <= yy < h and 0 <= xx < w and m[yy, xx] and not lab[yy, xx]:
                    lab[yy, xx] = n; q.append((yy, xx))
    return lab, n

def largest(m):
    lab, n = components(m)
    if n == 0:
        return m
    sizes = np.bincount(lab.ravel())[1:]
    return lab == 1 + int(np.argmax(sizes))

def flood(bg):
    """bg texels connected to the border."""
    seed = np.zeros(bg.shape, bool)
    seed[0, :] = bg[0, :]; seed[-1, :] = bg[-1, :]; seed[:, 0] = bg[:, 0]; seed[:, -1] = bg[:, -1]
    lab, n = components(bg)
    keep = set(np.unique(lab[seed])) - {0}
    return np.isin(lab, list(keep))

def hull(m):
    from PIL import ImageDraw
    ys, xs = np.nonzero(m)
    pts = sorted(set(zip(xs.tolist(), ys.tolist())))
    if len(pts) < 3:
        return m
    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lo, up = [], []
    for q in pts:
        while len(lo) >= 2 and cross(lo[-2], lo[-1], q) <= 0: lo.pop()
        lo.append(q)
    for q in reversed(pts):
        while len(up) >= 2 and cross(up[-2], up[-1], q) <= 0: up.pop()
        up.append(q)
    img = Image.new("L", (m.shape[1], m.shape[0]), 0)
    ImageDraw.Draw(img).polygon(lo[:-1] + up[:-1], fill=1, outline=1)
    return np.asarray(img).astype(bool)

def mask_of(name, img):
    a = np.asarray(img).astype(np.float32) / 255.0
    rgb, al = a[..., :3], a[..., 3]
    mx, mn = rgb.max(-1), rgb.min(-1)
    sat = (mx - mn) / np.maximum(mx, 1e-3)
    lum = rgb @ np.array([0.299, 0.587, 0.114], np.float32)
    m = (al > 0.9) & (lum > 0.42) & (sat < 0.32)
    if name.startswith("fixtures_bathroom"):
        m &= (rgb[..., 2] - rgb[..., 0]) > 0.075
    m = largest(m)
    m = hull(m) & (al > 0.9)  # every pane is convex: notches and highlight holes closed
    m = m & np.roll(m, 1, 0) & np.roll(m, -1, 0) & np.roll(m, 1, 1) & np.roll(m, -1, 1)
    return (m * 255).astype(np.uint8)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(HERE, "..", "..", "src", "media", "ui", "pzopt", "mirrors"))
    ap.add_argument("--sheet")
    a = ap.parse_args()
    names = []
    for f in sorted(__import__("glob").glob(os.path.join(tiles.GAME, "media", "*.tiles"))):
        for n, p in tiles.read(f):
            if "IsMirror" in p and p.get("Facing") in ("S", "E") and n not in names:
                names.append(n)
    imgs = dict(packsprite.pages(os.path.join(tiles.GAME, "media", "texturepacks", "Tiles2x.pack"), set(names)))
    names = [n for n in names if n in imgs]
    rows = (len(names) + COLS - 1) // COLS
    atlas = np.zeros((rows * CH, COLS * CW), np.uint8)
    lines = []
    for i, n in enumerate(names):
        img = imgs[n]
        if img.size != (CW, CH):
            img = img.resize((CW, CH), Image.NEAREST)
        m = mask_of(n, img)
        r, c = divmod(i, COLS)
        atlas[r * CH:(r + 1) * CH, c * CW:(c + 1) * CW] = m
        lines.append(f"{n} {i}")
        print(f"{n:28s} cell {i:2d} glass {int((m > 0).sum()):5d} px")
    os.makedirs(a.out, exist_ok=True)
    Image.fromarray(atlas, "L").save(os.path.join(a.out, "mirror-masks.png"), optimize=True)
    open(os.path.join(a.out, "mirror-masks.txt"), "w").write("# pzopt.Mirrors glass masks (harness/mirrors/masks.py): sprite cell; cells 128x256, 8 a row\n" + "\n".join(lines) + "\n")
    if a.sheet:
        sheet = Image.new("RGB", (COLS * CW, rows * CH), (255, 0, 255))
        for i, n in enumerate(names):
            r, c = divmod(i, COLS)
            im = imgs[n].resize((CW, CH), Image.NEAREST)
            tint = Image.fromarray(np.dstack([atlas[r*CH:(r+1)*CH, c*CW:(c+1)*CW]] * 3 + [np.full((CH, CW), 255, np.uint8)]), "RGBA")
            sheet.paste(im, (c * CW, r * CH), im)
            red = Image.new("RGBA", (CW, CH), (255, 0, 0, 120))
            sheet.paste(red, (c * CW, r * CH), Image.fromarray((atlas[r*CH:(r+1)*CH, c*CW:(c+1)*CW] // 2).astype(np.uint8)))
        sheet.save(a.sheet)

if __name__ == "__main__":
    main()
