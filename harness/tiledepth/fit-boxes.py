#!/usr/bin/env python3
"""Fit wall-mounted depth boxes to the sprites that share a generic tile depth texture (issue #38, 2026-09-29).

The game maps 88 "Floating" wall cabinets to fixtures_counters_01_16's depth texture: a box over the whole square
from y 1.8 to 2.4495 (the ceiling). pixelLight / AO / sun shadows / reflections read that depth as geometry, so the
upper half of every cabinet sat on the ceiling plane (lit by the level above) and its front a full square out from
the wall. This script reads each sprite from Tiles2x.pack, keeps the pixels the opaqueWithDepth shader writes
(alpha > 0.8), and fits the box model the Spiffo's cabinets use in tileGeometry.txt: one box against the wall behind
the doors (Facing S = north wall, E = west, N = south, W = east), or for a corner piece two boxes (an L along two
walls), with a depth from the wall, an inset, a bottom height and trimmed ends. Box space is TileGeometryUtils'
(x east, y up in squares with a level 2.4495 tall, z south, square centred on 0); the projection below is its ortho
view (rotation 30 / 315 degrees, 128 x 256 tile at tileScale 2), checked against the shipped box _16 (IoU 0.98).

Prints the rows of pzopt.TileDepthFix.BOXES (tile|source|source box|fitted boxes) and one report line per sprite (IoU, pixels the boxes miss: those
keep the stock depth at run time). Re-run after a game update that touches the tiles or tileDepthTextureAssignments.txt.
usage: fit-boxes.py [--source fixtures_counters_01_16] [--sheet out.png]
"""
import argparse, io, os, re, struct, sys
import numpy as np
from PIL import Image, ImageDraw

MEDIA = os.environ.get("PZ_MEDIA", "/games/steamapps/common/ProjectZomboid/projectzomboid/media")
TOP = 2.4495  # the ceiling: the generic box's top, a level's height in box units

# ---- the game's tile projection (TileGeometryUtils.calcMatricesForSquare) ----
def _rot(ax, ay, az):
    ax, ay, az = np.radians([ax, ay, az])
    X = np.array([[1, 0, 0], [0, np.cos(ax), -np.sin(ax)], [0, np.sin(ax), np.cos(ax)]])
    Y = np.array([[np.cos(ay), 0, np.sin(ay)], [0, 1, 0], [-np.sin(ay), 0, np.cos(ay)]])
    Z = np.array([[np.cos(az), -np.sin(az), 0], [np.sin(az), np.cos(az), 0], [0, 0, 1]])
    return X @ Y @ Z

def _ortho(l, r, b, t, n, f):
    return np.array([[2 / (r - l), 0, 0, -(r + l) / (r - l)], [0, 2 / (t - b), 0, -(t + b) / (t - b)],
                     [0, 0, -2 / (f - n), -(f + n) / (f - n)], [0, 0, 0, 1]])

_s = np.sqrt(2)
_T = np.eye(4); _T[1, 3] = -2 * _s * 0.375
_MV = np.eye(4); _MV[:3, :3] = _rot(30, 315, 0)
MVP = _ortho(-_s / 2, _s / 2, -_s, _s, -2, 2) @ _T @ _MV

def project(p):
    q = MVP @ np.array([p[0], p[1], p[2], 1.0])
    return (q[0] + 1) / 2 * 128, (2 - (q[1] + 1)) * 128

def _hull(pts):
    pts = sorted(set(pts))
    cr = lambda o, a, b: (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lo, up = [], []
    for p in pts:
        while len(lo) >= 2 and cr(lo[-2], lo[-1], p) <= 0: lo.pop()
        lo.append(p)
    for p in reversed(pts):
        while len(up) >= 2 and cr(up[-2], up[-1], p) <= 0: up.pop()
        up.append(p)
    return lo[:-1] + up[:-1]

def box_mask(b):
    (x0, y0, z0), (x1, y1, z1) = b
    pts = [project((x, y, z)) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
    im = Image.new("L", (128, 256), 0)
    ImageDraw.Draw(im).polygon(_hull(pts), fill=255)
    return np.asarray(im) > 0

# ---- game data ----
def read_tiledefs(path):
    d = open(path, "rb").read(); i = 4
    def ri():
        nonlocal i; v = struct.unpack_from("<i", d, i)[0]; i += 4; return v
    def rs():
        nonlocal i; j = d.index(b"\n", i); s = d[i:j].decode("latin1").rstrip("\r"); i = j + 1; return s
    ri(); out = {}
    for _ in range(ri()):
        name = rs().strip(); rs(); ri(); ri(); ri()
        for k in range(ri()):
            out[f"{name}_{k}"] = dict((rs(), rs()) for _ in range(ri()))
    return out

def read_pack(path, want):
    d = open(path, "rb").read(); i = 0
    def ri():
        nonlocal i; v = struct.unpack_from("<i", d, i)[0]; i += 4; return v
    def rs():
        nonlocal i; n = ri(); s = d[i:i + n].decode("latin1"); i += n; return s
    assert ri() == 1263557200, "not a PZPK pack"
    ri(); out = {}
    for _ in range(ri()):
        rs(); ne = ri(); ri()
        ents = []
        for _ in range(ne):
            nm = rs(); ents.append((nm, struct.unpack_from("<8i", d, i))); i += 32
        ln = ri(); png = d[i:i + ln]; i += ln
        hit = [e for e in ents if e[0] in want]
        if hit:
            img = Image.open(io.BytesIO(png)).convert("RGBA")
            for nm, (x, y, w, h, ox, oy, fw, fh) in hit:
                fr = Image.new("RGBA", (fw, fh)); fr.paste(img.crop((x, y, x + w, y + h)), (ox, oy)); out[nm] = fr
    return out

# ---- the box model ----
WALL_OF_FACING = {"S": "N", "E": "W", "N": "S", "W": "E"}  # the doors face away from the wall they hang on
CORNERS = [("N", "W"), ("N", "E"), ("S", "E"), ("S", "W")]

def wall_box(wall, depth, inset, y0, t0, t1):
    a, b = -0.5 + inset + depth, -0.5 + inset  # the box's extent away from its wall
    lo, hi = -0.5 + t0, 0.5 - t1               # along the wall
    if wall == "N": return ((lo, y0, b), (hi, TOP, a))
    if wall == "S": return ((lo, y0, -a), (hi, TOP, -b))
    if wall == "W": return ((b, y0, lo), (a, TOP, hi))
    return ((-a, y0, lo), (-b, TOP, hi))        # E

def score(boxes, opaque):
    m = np.zeros_like(opaque)
    for b in boxes: m |= box_mask(b)
    return (opaque & m).sum() / max((opaque | m).sum(), 1), int((opaque & ~m).sum())

DEPTHS = [0.40 + 0.025 * k for k in range(13)]  # 0.40 .. 0.70
INSETS = [0.0, 0.025, 0.05, 0.075]
BOTTOMS = [1.75, 1.775, 1.8, 1.825, 1.85, 1.9]
TRIMS = [0.0, 0.025, 0.05]

def fit(opaque, corner, facing):
    best = None
    if corner:
        for w1, w2 in CORNERS:
            for d in DEPTHS:
                for e in INSETS:
                    for y0 in BOTTOMS:
                        boxes = [wall_box(w1, d, e, y0, 0, 0), wall_box(w2, d, e, y0, 0, 0)]
                        iou, miss = score(boxes, opaque)
                        if best is None or iou > best[0]: best = (iou, miss, boxes)
        return best
    wall = WALL_OF_FACING[facing]
    for d in DEPTHS:
        for e in INSETS:
            for y0 in BOTTOMS:
                for t0 in TRIMS:
                    for t1 in TRIMS:
                        boxes = [wall_box(wall, d, e, y0, t0, t1)]
                        iou, miss = score(boxes, opaque)
                        if best is None or iou > best[0]: best = (iou, miss, boxes)
    return best

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default="fixtures_counters_01_16", help="the shared depth texture the tiles are assigned to")
    ap.add_argument("--sheet", help="write a check sheet: sprite, stock depth outline (red), fitted boxes (green)")
    args = ap.parse_args()
    assigned = {}
    for line in open(os.path.join(MEDIA, "tileDepthTextureAssignments.txt")):
        m = re.match(r"\s*(\S+)\s*=\s*(\S+),", line)
        if m: assigned[m.group(1)] = m.group(2)
    tiles = sorted([t for t, s in assigned.items() if s == args.source] + [args.source],
                   key=lambda t: (t.rsplit("_", 1)[0], int(t.rsplit("_", 1)[1])))
    props = read_tiledefs(os.path.join(MEDIA, "newtiledefinitions.tiles"))
    sprites = read_pack(os.path.join(MEDIA, "texturepacks", "Tiles2x.pack"), set(tiles))
    cache, rows, sheet = {}, [], []
    for t in tiles:
        p = props.get(t, {})
        facing, corner = p.get("Facing"), "Corner" in p.get("GroupName", "")
        if t not in sprites or facing not in WALL_OF_FACING:
            print(f"# skip {t}: no sprite or no Facing", file=sys.stderr); continue
        opaque = np.asarray(sprites[t])[:, :, 3] > 204  # opaqueWithDepth.frag: texel alpha > 0.8
        key = (opaque.tobytes(), corner, facing)
        if key not in cache: cache[key] = fit(opaque, corner, facing)
        iou, miss, boxes = cache[key]
        print(f"# {t:40s} facing {facing} {'corner' if corner else 'wall  '} IoU {iou:.3f} uncovered {miss:4d} of {int(opaque.sum())}", file=sys.stderr)
        rows.append((t, boxes))
        sheet.append((t, sprites[t], boxes))
    txt = open(os.path.join(MEDIA, "tileGeometry.txt")).read()
    m = re.search(r"/\* " + re.escape(args.source) + r" \*/\s*tile\s*\{\s*xy = \S+,\s*box\s*\{\s*translate = 0x0x0,\s*rotate = 0x0x0,\s*min = (-?\d+)x(-?\d+)x(-?\d+),\s*max = (-?\d+)x(-?\d+)x(-?\d+)", txt)
    stock = " ".join(f"{int(v) / 10000 + 0.0:g}" for v in m.groups())
    # rows of pzopt.TileDepthFix.BOXES: tile|source|the source's own box|fitted box;box (min x y z, max x y z)
    for t, boxes in rows:
        fitted = ";".join(" ".join(f"{round(v, 4) + 0.0:g}" for c in b for v in c) for b in boxes)
        print(f'      "{t}|{args.source}|{stock}|{fitted}",')
    if args.sheet:
        cols = 12
        W = Image.new("RGBA", (128 * cols, 140 * ((len(sheet) + cols - 1) // cols)), (40, 40, 40, 255))
        dr = ImageDraw.Draw(W)
        for k, (t, im, boxes) in enumerate(sheet):
            ox, oy = 128 * (k % cols), 140 * (k // cols)
            W.alpha_composite(im.crop((0, 0, 128, 140)), (ox, oy))
            for b, colr in [(wall_box("N", 1.0, 0, 1.8, 0, 0), (255, 60, 60, 255))] + [(b, (60, 255, 60, 255)) for b in boxes]:
                (x0, y0, z0), (x1, y1, z1) = b
                pts = [project((x, y, z)) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
                dr.polygon([(ox + px, oy + py) for px, py in _hull(pts)], outline=colr)
            dr.text((ox + 2, oy + 128), t.replace("fixtures_counters_01", "fc01").replace("location_", ""), fill=(255, 255, 255, 255))
        W.save(args.sheet)

if __name__ == "__main__":
    main()
