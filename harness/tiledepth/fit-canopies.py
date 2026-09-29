#!/usr/bin/env python3
"""Fit sloped slabs to the store canopies (awnings) that borrow a generic tile depth box (issue #38 follow-up, 2026-09-29).

The canopy tiles ("Canopy", walls_decoration_01 and the bar / pie / crepe restaurants' sets) are Translucent, not
OpaquePixelsOnly, and borrow flat boxes (walls_decoration_01_68/69/84/85/86/144-147) that reach the ceiling: the stock tile
shader writes that box's depth on every texel of it, the transparent air under the canopy too, so pixel light and ambient
occlusion saw a block standing on the pavement (a dark patch under Denny's canopy) and lit the canopy's top from the level
above. This fits each straight canopy with the shape it has: a thin slab sloping down from the wall behind it (Facing S =
north wall, E = west, N = south, W = east) plus an optional vertical valance at its front edge, as rotated boxes in
TileGeometryUtils' box space (x east, y up, z south, a level 2.4495 tall). Domes and curved hoods fit poorly (IoU under
--min-iou) and are left out; at run time pzopt.TileDepthFix also drops the depth of every texel the sprite's alpha mask
leaves empty.

Prints the rows for pzopt.TileDepthFix.BOXES ("tile|source|stock box|box;box") and a report (IoU, uncovered pixels).
usage: fit-canopies.py [--min-iou 0.85] [--sheet out.png]
"""
import argparse, importlib.util, math, os, re, sys
import numpy as np
from PIL import Image, ImageDraw

spec = importlib.util.spec_from_file_location("fb", os.path.join(os.path.dirname(__file__), "fit-boxes.py"))
fb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fb)
TOP = fb.TOP


def rot(ax, ay, az):
    return fb._rot(ax, ay, az)


def box_corners(b):
    """b = (center, rotation degrees, min, max): the 8 world corners (joml translation(c).rotateXYZ(r) * local)."""
    c, r, mn, mx = b
    R = rot(*r)
    return [tuple(np.array(c) + R @ np.array((x, y, z))) for x in (mn[0], mx[0]) for y in (mn[1], mx[1]) for z in (mn[2], mx[2])]


def mask_of(boxes):
    m = np.zeros((256, 128), bool)
    for b in boxes:
        if b[0] == "e":
            m |= ellipsoid_hit(b)
            continue
        pts = [fb.project(p) for p in box_corners(b)]
        im = Image.new("L", (128, 256), 0)
        ImageDraw.Draw(im).polygon(fb._hull(pts), fill=255)
        m |= np.asarray(im) > 0
    return m


def slab(wall, depth, inset, h1, h2, t0=0.0, t1=0.0, th=0.03):
    """A slab from the wall (height h1) out `depth` squares (height h2 < h1), `inset` off the wall line."""
    L = math.hypot(depth, h1 - h2)
    ang = math.degrees(math.atan2(h1 - h2, depth))
    lo, hi = -0.5 + t0, 0.5 - t1
    mid = (lo + hi) / 2
    half = (hi - lo) / 2
    yc = (h1 + h2) / 2
    out = -0.5 + inset + depth / 2  # centre's distance from the square's centre towards the outside, negative side = wall
    if wall == "N":  # wall at z = -0.5, slopes down towards +z: +z of the local box turns down with a positive X rotation
        return ((mid, yc, out), (ang, 0, 0), (-half, -th / 2, -L / 2), (half, th / 2, L / 2))
    if wall == "S":
        return ((mid, yc, -out), (-ang, 0, 0), (-half, -th / 2, -L / 2), (half, th / 2, L / 2))
    if wall == "W":  # wall at x = -0.5, slopes down towards +x: +x turns down with a negative Z rotation
        return ((out, yc, mid), (0, 0, -ang), (-L / 2, -th / 2, -half), (L / 2, th / 2, half))
    return ((-out, yc, mid), (0, 0, ang), (-L / 2, -th / 2, -half), (L / 2, th / 2, half))  # E


def valance(wall, depth, inset, h2, v, t0=0.0, t1=0.0, th=0.03):
    """The vertical strip hanging from the slab's front edge, v tall."""
    lo, hi = -0.5 + t0, 0.5 - t1
    f = -0.5 + inset + depth
    if wall == "N":
        return ((0, 0, 0), (0, 0, 0), (lo, h2 - v, f - th), (hi, h2, f))
    if wall == "S":
        return ((0, 0, 0), (0, 0, 0), (lo, h2 - v, -f), (hi, h2, -f + th))
    if wall == "W":
        return ((0, 0, 0), (0, 0, 0), (f - th, h2 - v, lo), (f, h2, hi))
    return ((0, 0, 0), (0, 0, 0), (-f, h2 - v, lo), (-f + th, h2, hi))


_RAYS = None


def rays():
    """Every texel's view ray (origin, unit direction into the screen) under TileGeometryUtils' projection."""
    global _RAYS
    if _RAYS is None:
        inv = np.linalg.inv(fb.MVP)
        ys, xs = np.mgrid[0:256, 0:128]
        nx, ny = (xs + 0.5) / 128 * 2 - 1, (2 - (ys + 0.5) / 128) - 1
        def un(z):
            p = np.stack([nx, ny, np.full_like(nx, z, dtype=float), np.ones_like(nx, dtype=float)], -1) @ inv.T
            return p[..., :3] / p[..., 3:4]
        o = un(-1.0)
        d = un(1.0) - o
        _RAYS = (o, d / np.linalg.norm(d, axis=-1, keepdims=True))
    return _RAYS


OUT = {"N": (2, 1.0, -0.5), "S": (2, -1.0, 0.5), "W": (0, 1.0, -0.5), "E": (0, -1.0, 0.5)}  # wall: axis, outward sign, wall coordinate


def ellipsoid(wall, u, a, r, b, cy, cut):
    """The outward half of an ellipsoid centred on the wall line (u along it), a along the wall, r out, b up; nothing
    below cut."""
    ax, sg, wc = OUT[wall]
    c = [0.0, cy, 0.0]
    c[ax] = wc
    c[2 - ax] = u
    rad = [0.0, b, 0.0]
    rad[ax] = r
    rad[2 - ax] = a
    return ("e", tuple(c), tuple(rad), ax, sg, wc, cut)


def ellipsoid_hit(e):
    """Per texel: True where a ray meets the ellipsoid's kept part (the nearest kept intersection)."""
    _, c, rad, ax, sg, wc, cut = e
    o, d = rays()
    oc = (o - np.array(c)) / np.array(rad)
    dd = d / np.array(rad)
    A = (dd * dd).sum(-1)
    B = 2 * (oc * dd).sum(-1)
    C = (oc * oc).sum(-1) - 1
    disc = B * B - 4 * A * C
    ok = disc >= 0
    sq = np.sqrt(np.where(ok, disc, 0))
    hit = np.zeros(ok.shape, bool)
    for t in ((-B - sq) / (2 * A), (-B + sq) / (2 * A)):  # nearer first (the rays point into the screen)
        P = o + d * t[..., None]
        keep = ok & ~hit & ((P[..., ax] - wc) * sg >= -1e-6) & (P[..., 1] >= cut)
        hit |= keep
    return hit


def fit_ellipsoid(opaque, wall):
    best = None
    for u in (-0.5, -0.25, 0.0, 0.25, 0.5):
        for a in (0.5, 0.75, 1.0, 1.25):
            for r in (0.4, 0.55, 0.7, 0.85, 1.0):
                for b in (0.3, 0.45, 0.6, 0.8, 1.0):
                    for cy in (1.6, 1.8, 2.0, 2.2, 2.4):
                        if cy + b > TOP + 0.3:
                            continue
                        for k in (0.0, 0.5, 1.0):
                            e = ellipsoid(wall, u, a, r, b, cy, cy - k * b)
                            m = ellipsoid_hit(e)
                            iou = (opaque & m).sum() / max((opaque | m).sum(), 1)
                            if best is None or iou > best[0]:
                                best = (iou, int((opaque & ~m).sum()), [e])
    return best


def fit(opaque, wall):
    best = None
    for d in [0.4 + 0.05 * k for k in range(13)]:
        for e in (0.0, 0.05):
            for h1 in (2.2, 2.3, 2.4, TOP):
                for drop in [0.2 + 0.1 * k for k in range(11)]:
                    h2 = h1 - drop
                    s = slab(wall, d, e, h1, h2)
                    ms = mask_of([s])
                    for v in (0.0, 0.1, 0.2, 0.3, 0.4, 0.5):
                        boxes = [s] + ([valance(wall, d, e, h2, v)] if v > 0 else [])
                        m = ms | (mask_of(boxes[1:]) if v > 0 else False)
                        iou = (opaque & m).sum() / max((opaque | m).sum(), 1)
                        if best is None or iou > best[0]:
                            best = (iou, int((opaque & ~m).sum()), boxes)
    return best


def fmt_box(b):
    if b[0] == "e":
        _, c, rad, ax, sg, wc, cut = b
        return "e " + " ".join(f"{round(v, 4) + 0.0:g}" for v in (*c, *rad, ax, sg, wc, cut))
    c, r, mn, mx = b
    return " ".join(f"{round(v, 4) + 0.0:g}" for v in (*c, *r, *mn, *mx))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-iou", type=float, default=0.85)
    ap.add_argument("--sheet")
    a = ap.parse_args()
    M = fb.MEDIA
    props = fb.read_tiledefs(os.path.join(M, "newtiledefinitions.tiles"))
    assigned = {}
    for line in open(os.path.join(M, "tileDepthTextureAssignments.txt")):
        m = re.match(r"\s*(\S+)\s*=\s*(\S+),", line)
        if m:
            assigned[m.group(1)] = m.group(2)
    txt = open(os.path.join(M, "tileGeometry.txt")).read()
    boxes = {}
    for m in re.finditer(r"/\* (\S+) \*/\s*tile\s*\{\s*xy = \S+,\s*box\s*\{\s*translate = 0x0x0,\s*rotate = 0x0x0,\s*min = (-?\d+)x(-?\d+)x(-?\d+),\s*max = (-?\d+)x(-?\d+)x(-?\d+),\s*\}\s*(?:properties|\})", txt):
        v = [int(x) / 10000 for x in m.groups()[1:]]
        boxes[m.group(1)] = ((0, 0, 0), (0, 0, 0), tuple(v[:3]), tuple(v[3:]))
    tiles = sorted((t for t, p in props.items() if p.get("CustomName") == "Canopy" and p.get("Facing") in fb.WALL_OF_FACING),
                   key=lambda t: (t.rsplit("_", 1)[0], int(t.rsplit("_", 1)[1])))
    sprites = fb.read_pack(os.path.join(M, "texturepacks", "Tiles2x.pack"), set(tiles))
    cache, rows, sheet = {}, [], []
    for t in tiles:
        if t not in sprites:
            continue
        wall = fb.WALL_OF_FACING[props[t]["Facing"]]
        opaque = np.asarray(sprites[t])[:, :, 3] > 0  # the texels the stock tile shader writes (Translucent: every one of the box's)
        key = (opaque.tobytes(), wall)
        if key not in cache:
            fs = fit(opaque, wall)
            fe = fit_ellipsoid(opaque, wall)
            cache[key] = fe if fe[0] > fs[0] + 0.01 else fs
        iou, miss, bx = cache[key]
        ok = iou >= a.min_iou
        src = assigned.get(t, t) if assigned.get(t, t) in boxes else "-"
        print(f"# {t:40s} facing {props[t]['Facing']} src {src:28s} {'ellipsoid' if bx[0][0] == 'e' else 'slab':9s} IoU {iou:.3f} uncovered {miss:4d} of {int(opaque.sum())}{'' if ok else '  (left out)'}", file=sys.stderr)
        sheet.append((t, sprites[t], bx, ok, boxes.get(src)))
        if ok:
            rows.append(f'      "{t}|{src}|{fmt_box(boxes[src]) if src != "-" else "-"}|' + ";".join(fmt_box(b) for b in bx) + '",')
    print("\n".join(rows))
    if a.sheet:
        cols = 12
        W = Image.new("RGBA", (128 * cols, 150 * ((len(sheet) + cols - 1) // cols)), (40, 40, 40, 255))
        dr = ImageDraw.Draw(W)
        for k, (t, im, bx, ok, stock) in enumerate(sheet):
            ox, oy = 128 * (k % cols), 150 * (k // cols)
            W.alpha_composite(im.crop((0, 0, 128, 140)), (ox, oy))
            for b, col in ([(stock, (255, 60, 60, 255))] if stock else []) + [(b, (60, 255, 60, 255) if ok else (255, 200, 0, 255)) for b in bx]:
                if b[0] == "e":
                    edge = mask_of([b])
                    edge = edge & ~np.roll(edge, 1, 0) | edge & ~np.roll(edge, 1, 1)
                    for yy, xx in zip(*np.nonzero(edge[:140])):
                        dr.point((ox + xx, oy + yy), fill=col)
                    continue
                pts = [fb.project(p) for p in box_corners(b)]
                dr.polygon([(ox + px, oy + py) for px, py in fb._hull(pts)], outline=col)
            dr.text((ox + 2, oy + 138), t.replace("walls_decoration_01", "wd01").replace("location_restaurant_", ""), fill=(255, 255, 255, 255))
        W.save(a.sheet)


if __name__ == "__main__":
    main()
