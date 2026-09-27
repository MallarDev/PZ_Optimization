#!/usr/bin/env python3
"""The sun shadow of one tree on flat open ground, offline (2026-09-27, detailed shadows): the real ChunkAo kernel in a
dumped texture's geometry (devAoDumpTree: iso0 / iso1, size, AO scale), its source depth replaced by the flat ground of
the texture's lowest level, one tree at a chosen square with a silhouette layer of the dump (TreeSilhouette.dump), the sun
from azimuth / elevation. Shows where the shadow starts (the tree's foot, marked) and what shape it has.

    card_rig.py --dump ~/Zomboid/pzopt-chunkao/<n> [--layer L] [--at 4,4] [--az 270] [--elev 30] [--out /tmp/card]
                [--size normal|jumbo|xl|xxl] [--no-cards] [--opacity 0.9] [--softness 1.0]

Writes <out>-sun.png (the raw sun term, rows as on screen) with the foot marked red. Needs the moderngl venv of
harness/contact/kernel_rig.py.
"""
import argparse
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "contact"))
from kernel_rig import glsl, SRC  # noqa: E402

import moderngl  # noqa: E402
from PIL import Image  # noqa: E402

UNITS_PER_DEPTH = 0.6123724356957945 / (0.0028867084 * 0.5)
WX = np.array([0.7071068, -0.3535534, -0.6123724])
WY = np.array([-0.7071068, -0.3535534, -0.6123724])
WZ = np.array([0.0, 0.8660254, -0.5])
TS = 2
SIZES = {  # TreeBake.offsetX / offsetY at tile scale 2; levels as ChunkAo.treeLevels
    "normal": (64.0, 192.0, 1.0),
    "jumbo": (192.0, 448.0, 7.0 / 3.0),
    "xl": (320.0, 704.0, 11.0 / 3.0),
    "xxl": (448.0, 960.0, 15.0 / 3.0),
}


def parse(path):
    kv = {}
    for ln in open(path):
        if "=" in ln:
            k, v = ln.strip().split("=", 1)
            kv[k] = v
    return kv


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", required=True)
    ap.add_argument("--layer", type=int, default=-1, help="silhouette layer (default: the first the dump's trees use)")
    ap.add_argument("--size", default="jumbo", choices=sorted(SIZES))
    ap.add_argument("--at", default="4,4", help="the tree's square, relative to the chunk's corner")
    ap.add_argument("--az", type=float, default=270.0, help="the sun's azimuth, degrees clockwise from north (PZ: north = -y)")
    ap.add_argument("--elev", type=float, default=30.0)
    ap.add_argument("--opacity", type=float, default=0.9)
    ap.add_argument("--softness", type=float, default=1.0, help="sunShadowSoftnessPct / 100 (1 = a 3 degree sun radius)")
    ap.add_argument("--no-cards", action="store_true")
    ap.add_argument("--out", default="/tmp/card")
    a = ap.parse_args()
    kv = parse(os.path.join(a.dump, "pzopt-chunkao-dump.txt"))
    W, H, aw, ah, sc, ppu = int(kv["w"]), int(kv["h"]), int(kv["aw"]), int(kv["ah"]), float(kv["sc"]), float(kv["ppu"])
    iso0 = tuple(map(float, kv["iso0"].split(",")))
    iso1 = tuple(map(float, kv["iso1"].split(",")))
    ys = -1.0
    # the flat ground (z = 0 of the texture's lowest level) in the kernel's own mapping (worldAt with zl = 0)
    cy, cx = np.meshgrid(np.arange(H) + 0.5, np.arange(W) + 0.5, indexing="ij")  # cy: GL row (bottom-up)
    q = (cy if ys < 0 else H - cy) / iso0[2] - iso0[3]
    u = q / iso1[0]
    p = (cx - iso0[0]) * iso0[1]
    x, y = (u + p) * 0.5, (u - p) * 0.5
    ground = (x > -0.5) & (y > -0.5) & (x < 8.5) & (y < 8.5)
    dep = np.where(ground, (20.0 - u) * iso1[2], 1.0).astype(np.float32)

    ctx = moderngl.create_standalone_context(backend="egl", require=330)
    src0 = ctx.texture((W, H), 1, np.ascontiguousarray(dep).tobytes(), dtype="f4")
    src0.filter = (moderngl.NEAREST, moderngl.NEAREST)
    n, L = int(kv["silSize"]), int(kv["silLayers"])
    data = np.fromfile(os.path.join(a.dump, "pzopt-chunkao-treesil.bin"), np.uint8)
    sil = ctx.texture_array((n, n, L), 1, data.tobytes(), dtype="f1")
    sil.build_mipmaps()
    sil.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
    layer = a.layer
    if layer < 0:
        layer = sorted({int(float(v)) for v in kv["treeC"].split(",")[3::4] if float(v) >= 0})[0]
    Image.fromarray(data.reshape(L, n, n)[layer][::-1]).save(a.out + "-layer.png")

    offx, offy, levels = SIZES[a.size]
    fw, fh = 2 * offx, offy + 32 * TS
    fx, fy = map(float, a.at.split(","))
    fx += 0.5
    fy += 0.5
    hs = levels * 2.4494897
    tree_c = [(fx, fy, 0.0, float(layer))] + [(0.0, 0.0, 0.0, 0.0)] * 31
    tree_d = [(45.254834 * TS / fw, 1.0 - (offy + 16 * TS) / fh, 39.191835 * TS / fh, 0.0)] + [(0.0, 0.0, 0.0, 0.0)] * 31
    rh = 3.3 if levels >= 4.5 else 2.4 if levels >= 3.5 else 1.5 if levels >= 2 else 0.6
    s_ = fx + fy + 1.0  # the card through the square's south corner (x + y = square's + 2)
    lat = fx - fy
    tree_a = [((s_ + lat) * 0.5, (s_ - lat) * 0.5, 0.6 * hs, rh)] + [(0.0, 0.0, 0.0, 0.0)] * 31
    tree_b = [(0.4 * hs, hs, 0.0, s_)] + [(0.0, 0.0, 0.0, 0.0)] * 31

    az, el = math.radians(a.az), math.radians(a.elev)
    sw = np.array([math.sin(az) * math.cos(el), -math.cos(az) * math.cos(el), math.sin(el)])  # east = +x, north = -y
    v = sw[0] * WX + sw[1] * WY + sw[2] * WZ
    pl = math.hypot(v[0], v[1])
    perp = (-v[1] / pl, v[0] / pl, 0.0)
    tan_a = math.tan(math.radians(3.0 * a.softness))
    tan_e = sw[2] / math.hypot(sw[0], sw[1])
    length = max(1.0, min(8.0, 2.0 * 2.4494897 / max(0.05, tan_e)))
    steps = max(8, min(32, round(24 * length / 8.0)))

    vert = glsl(os.path.join(SRC, "AmbientOcclusion.java"), "QUAD_VERT")
    pk = ctx.program(vertex_shader=vert, fragment_shader=glsl(os.path.join(SRC, "ChunkAo.java"), "AO_FRAG"))

    def s(name, val):
        if name in pk:
            pk[name].value = val

    s("rect", [(0.0, 0.0, float(W), float(H))] + [(0.0, 0.0, 0.0, 0.0)] * 8)
    s("off", [0.0] * 9)
    s("nSrc", 1)
    s("geo", (1.0 / sc, ppu, ys, 0.0))
    s("params", (ppu, 0.6, UNITS_PER_DEPTH, 1.0))
    s("strength", (1.0, 0.5, 1.0, 0.5))
    s("mode", (0.0, 0.0, 0.0, 0.0))
    s("iso0", iso0)
    s("iso1", (iso1[0], iso1[1], iso1[2], 0.0))
    s("sunDir", (float(v[0]), float(v[1]), float(v[2]), 1.0))
    s("sunPerp", (perp[0], perp[1], 0.0, tan_a))
    s("sunPar", (length * ppu, 1.0, float(steps), 0.0))
    s("sunWorld", (float(sw[0]), float(sw[1]), float(sw[2]), 0.0))
    s("treeA", tree_a)
    s("treeB", tree_b)
    s("treeC", tree_c)
    s("treeD", tree_d)
    s("sunTree", (0.35, 0.0, 1.0, 0.0 if a.no_cards else a.opacity))
    s("treeSil", 10)
    for i in range(9):
        s("Src%d" % i, 0)
    raw = ctx.texture((aw, ah), 4, dtype="f4")
    rawf = ctx.framebuffer([raw])
    quad = ctx.buffer(np.array([-1, -1, 1, -1, 1, 1, -1, 1], "f4").tobytes())
    name = [k for k in pk if isinstance(pk[k], moderngl.Attribute)][0]
    vk = ctx.vertex_array(pk, [(quad, "2f", name)])
    src0.use(0)
    sil.use(10)
    rawf.use()
    ctx.viewport = (0, 0, aw, ah)
    vk.render(moderngl.TRIANGLE_FAN)
    r = np.frombuffer(raw.read(), np.float32).reshape(ah, aw, 4)[..., 2]
    img = (np.clip(np.where(r < 0, 0.5, r), 0, 1) * 255).astype(np.uint8)
    rgb = np.stack([img] * 3, -1)
    # the foot on the texture (inverse of worldAt at z = 0), in AO texels
    pf = fx - fy
    uf = fx + fy
    cxf = pf / iso0[1] + iso0[0]
    rowf = (uf * iso1[0] + iso0[3]) * iso0[2]
    cyf = rowf if ys < 0 else H - rowf
    ix, iy = int(cxf * sc), int(cyf * sc)
    rgb[max(0, iy - 2):iy + 3, max(0, ix - 2):ix + 3] = (255, 0, 0)
    Image.fromarray(rgb[::-1]).save(a.out + "-sun.png")
    shaded = ((r >= 0) & (r < 0.95)).sum()
    print(f"layer {layer} size {a.size} at {fx},{fy} sun az {a.az} el {a.elev}: shaded texels {shaded}, min {r[r >= 0].min():.3f}")


if __name__ == "__main__":
    main()
