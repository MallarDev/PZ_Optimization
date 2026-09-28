#!/usr/bin/env python3
"""Offline cost rig of the foliage sway composite (2026-09-27): the game's own chunkShader.vert / .frag and the sway variants
that pzopt.Sway.patchComposite generates (dumped by tests' SwayPatchDump for a shape), drawn the way the chunk composite
draws its textures: ~45 chunk quads (1024 x 2048 texels at zoom 1, chunk corners 512 x 256 px apart, back to front) over a
5120 x 2160 target with a depth buffer, GL_LEQUAL, premultiplied blend, gl_FragDepth written. Synthetic textures: a
ground diamond, plants (opaque blobs with sway attributes in an RG8 texture, a tile mask), empty space above. Timed with
GL timer queries (median of N frames). No game, seconds per variant; run it through the queue (a `cmd` job) so it never
overlaps a game run.

    composite_rig.py [--frames 300] [--plants 0.35] [--sway-share 0.7] <variant.frag>...   (stock = the game's own)

Needs the god rays venv: ~/.cache/pzopt-gr-venv/bin/python (moderngl, numpy)."""
import argparse
import math
import os
import re
import statistics

import numpy as np
import moderngl

D = os.environ.get("PZ_SHADERS", "/games/steamapps/common/ProjectZomboid/projectzomboid/media/shaders/")
W, H = 5120, 2160
TW, TH = 1024, 2048
UNITS = {"DIFFUSE": 0, "DEPTH": 1, "pzSwAux": 30, "pzSwGustTex": 31, "pzSwMask": 28}


def textures(ctx, rng, plants, n=8):
    out = []
    ys, xs = np.mgrid[0:TH, 0:TW]
    # the chunk's ground diamond: 1024 x 512 at the texture's bottom half (logical rows from the top)
    cx, cy = TW / 2, TH - 700
    diamond = (np.abs(xs - cx) / 512 + np.abs(ys - cy) / 256) <= 1.0
    for k in range(n):
        col = np.zeros((TH, TW, 4), np.uint8)
        dep = np.zeros((TH, TW), np.uint16) + 65535
        aux = np.zeros((TH, TW, 2), np.uint8)
        col[diamond] = (60 + rng.integers(0, 40), 110, 50, 255)
        dep[diamond] = (1000 + (ys[diamond] - (cy - 256))).astype(np.uint16)
        # plants: blobs of 40..110 px on the diamond, weight rising with height
        count = int(plants * 60)
        for _ in range(count):
            px = int(cx + rng.uniform(-420, 420))
            py = int(cy + rng.uniform(-200, 200))
            if abs(px - cx) / 512 + abs(py - cy) / 256 > 0.95:
                continue
            h = int(rng.uniform(40, 110))
            w = int(h * 0.8)
            y0, y1 = max(0, py - h), py
            x0, x1 = max(0, px - w // 2), min(TW, px + w // 2)
            yy, xx = np.mgrid[y0:y1, x0:x1]
            blob = ((xx - px) / (w / 2.0)) ** 2 + ((yy - (py - h / 2)) / (h / 2.0)) ** 2 <= 1.0
            ys_, xs_ = yy[blob], xx[blob]
            col[ys_, xs_] = (40, 90 + rng.integers(0, 80), 30, 255)
            d16 = (900 + (ys_ - y0)).astype(np.uint16)
            dep[ys_, xs_] = d16
            hfrac = (py - ys_) / max(1, h)
            wq = np.clip(np.round(0.3 * hfrac ** 1.5 * 63), 0, 63).astype(np.uint8)
            cls = 0 if rng.random() < 0.7 else 1
            aux[ys_, xs_, 0] = wq * 4 + cls
            aux[ys_, xs_, 1] = (rng.integers(0, 16) * 16 + (d16 % 16)).astype(np.uint8)
        mask = np.zeros((TH // 16, TW // 16), np.uint8)
        a = aux[..., 0].reshape(TH // 16, 16, TW // 16, 16).max(axis=(1, 3))
        m = a > 0
        # dilate by 2 tiles (the reach)
        for dy in (-2, -1, 0, 1, 2):
            for dx in (-2, -1, 0, 1, 2):
                mask |= np.roll(np.roll(m, dy, 0), dx, 1).astype(np.uint8) * 255
        tc = ctx.texture((TW, TH), 4, col.tobytes())
        tc.build_mipmaps(0, 3)
        tc.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.NEAREST)
        td = ctx.depth_texture((TW, TH), (dep.astype(np.float32) / 65535.0).tobytes())
        td.compare_func = ""
        td.filter = (moderngl.NEAREST, moderngl.NEAREST)
        ta = ctx.texture((TW, TH), 2, aux.tobytes())
        ta.filter = (moderngl.NEAREST, moderngl.NEAREST)
        tm = ctx.texture((TW // 16, TH // 16), 1, mask.tobytes())
        tm.filter = (moderngl.NEAREST, moderngl.NEAREST)
        out.append((tc, td, ta, tm))
    return out


def quads():
    """Chunk quads (x, y) of 1024 x 2048 texture px drawn 1:1, back to front: chunk corners 512 x 256 px apart."""
    q = []
    for s in range(-12, 16):
        for d in range(-14, 14):
            x = (s - d) * 512 + W / 2 - TW / 2
            y = (s + d) * 256 + 200 - TH + 700
            if x + TW < 0 or x > W or y + TH < 0 or y > H:
                continue
            q.append((s + d, x, y))
    q.sort()
    return [(x, y) for _, x, y in q]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("variants", nargs="*")
    ap.add_argument("--frames", type=int, default=300)
    ap.add_argument("--plants", type=float, default=0.35)
    ap.add_argument("--sway-share", type=float, default=0.7, help="share of chunk quads that hold plants (drawn with the variant)")
    a = ap.parse_args()
    ctx = moderngl.create_standalone_context(backend="egl", require=460)
    rng = np.random.default_rng(7)
    tex = textures(ctx, rng, a.plants)
    gust = ctx.texture((32, 32), 1, rng.integers(0, 255, (32, 32), dtype=np.uint8).tobytes())
    gust.repeat_x = gust.repeat_y = True
    col = ctx.texture((W, H), 4)
    dep = ctx.depth_texture((W, H))
    fbo = ctx.framebuffer([col], dep)
    vert = open(D + "chunkShader.vert").read()
    stock = open(D + "chunkShader.frag").read()
    progs = [("stock", ctx.program(vertex_shader=vert, fragment_shader=stock))]
    for v in a.variants:
        progs.append((os.path.basename(os.path.dirname(v)) + "/" + os.path.basename(v), ctx.program(vertex_shader=vert, fragment_shader=open(v).read())))
    qs = quads()
    # one quad VBO: position (x, y), uv, colour; moved per draw by a uniform-free offset: we rebuild positions per quad
    data = []
    for (x, y) in qs:
        for (u, v) in ((0, 0), (1, 0), (1, 1), (0, 0), (1, 1), (0, 1)):
            data += [x + u * TW, y + v * TH, u, 1 - v, 1, 1, 1, 1]
    vbo = ctx.buffer(np.array(data, np.float32).tobytes())
    mvp = np.array([[2 / W, 0, 0, 0], [0, -2 / H, 0, 0], [0, 0, 1, 0], [-1, 1, 0, 1]], np.float32)
    rows = []
    vaos = {}

    def setup(name, p):
        vao = ctx.vertex_array(p, [(vbo, "2f 2f 4f", "vPos", "vUV", "vCol")])
        if "ModelViewProjection" in p:
            p["ModelViewProjection"].write(mvp.tobytes())
        for u, unit in UNITS.items():
            if u in p:
                p[u].value = unit
        vals = {"pzSwWind": (1.0, 0.5, 1.0, 0.4), "pzSwPh": (1.0, 2.0, 0.0, 0.5), "pzSwGust": (3.0, 5.0, 0.125, 0.1),
                "pzSwPx": (2.0, 4.0, 9.0, 16.0), "pzSwMap": (32.0, 0.0, 128.0, 0.0), "chunkDepth": 0.0}
        for u, val in vals.items():
            if u in p:
                p[u].value = val
        return vao

    stock_vao = setup("stock", progs[0][1])
    for name, p in progs:
        vao = setup(name, p)
        times = []
        for f in range(a.frames):
            fbo.use()
            ctx.enable(moderngl.DEPTH_TEST | moderngl.BLEND)
            ctx.depth_func = "<="
            ctx.blend_func = (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)
            fbo.clear(0, 0, 0, 1, depth=1.0)
            gust.use(31)
            q = ctx.query(time=True)
            with q:
                for i in range(len(qs)):
                    tc, td, ta, tm = tex[i % len(tex)]
                    tc.use(0)
                    td.use(1)
                    sway = name != "stock" and (i * 0.618) % 1.0 < a.sway_share
                    if sway:
                        ta.use(30)
                        tm.use(28)
                        if "pzSwOn" in p:
                            p["pzSwOn"].value = (1.0, 1.0 / TW, 1.0 / TH, float(TW))
                        vao.render(moderngl.TRIANGLES, vertices=6, first=i * 6)
                    else:
                        stock_vao.render(moderngl.TRIANGLES, vertices=6, first=i * 6)
            ctx.finish()
            if f >= 20:
                times.append(q.elapsed / 1000.0)
        rows.append((name, statistics.median(times), statistics.mean(times)))
    base = rows[0][1]
    print(f"{len(qs)} chunk quads of {TW}x{TH} over {W}x{H}, plants {a.plants}, sway share {a.sway_share}, frames {a.frames}")
    for name, med, mean in rows:
        print(f"  {name:40s} median {med:8.1f} us  mean {mean:8.1f} us  vs stock {med - base:+7.1f} us")


if __name__ == "__main__":
    main()
