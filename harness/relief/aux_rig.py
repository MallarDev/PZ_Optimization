#!/usr/bin/env python3
"""Offline rig for pzopt.ReliefAux: runs its encode shader (the Java source's FRAG, extracted) on a pixelLight dump
(devPplDumpAt + devPplView=2: the unlit albedo and the window depth, one pixel = one chunk-texture texel at zoom 1) with
moderngl on EGL, decodes the codes and prints their statistics; --png writes the decoded slopes as an image.

    /tmp/px/venv/bin/python harness/relief/aux_rig.py <run> <tag> [x0,y0,x1,y1] [--png out.png]
"""
import argparse
import os
import re
import sys

import moderngl
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "ppl"))
from dump import load  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")


def java_const(path, name):
    """A String.join("\\n", ...) constant of the Java source; bare identifiers among the parts are other such constants."""
    src = open(path).read()
    m = re.search(r"\b" + name + r' = String\.join\("\\n",(.*?)\);\n', src, re.S)
    out = []
    for tok in re.findall(r'"((?:[^"\\]|\\.)*)"|^\s*([A-Z_]+),\s*$', m.group(1), re.M):
        out.append(tok[0].encode().decode("unicode_escape") if not tok[1] else java_const(path, tok[1]).rstrip("\n"))
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("tag")
    ap.add_argument("box", nargs="?", default="")
    ap.add_argument("--png", default="")
    ap.add_argument("--gmax", type=float, default=0.2)
    ap.add_argument("--snap", type=float, default=0.8)
    ap.add_argument("--factor", default="", help="light x,y,z (world, towards it): run the factor pass too, share 1; writes <png>-factor.png and -relit.png")
    ap.add_argument("--depth", type=float, default=1.0, help="reliefDepthPct / 100")
    ap.add_argument("--steps", type=int, default=4)
    a = ap.parse_args()
    d = load(a.run, a.tag)
    col, dep = d.color, d.depth
    if a.box:
        x0, y0, x1, y1 = map(int, a.box.split(","))
        col, dep = col[y0:y1, x0:x1], dep[y0:y1, x0:x1]
    h, w = dep.shape
    src = os.path.join(ROOT, "src/pzopt/pzopt/ReliefAux.java")
    vert, frag = java_const(src, "VERT"), java_const(src, "ENCODE_FRAG")
    ctx = moderngl.create_standalone_context(backend="egl")
    prog = ctx.program(vertex_shader=vert, fragment_shader=frag)
    # texel row 0 = the top of the picture, as the chunk textures (rows top-down)
    tc = ctx.texture((w, h), 4, np.ascontiguousarray(col).tobytes())
    tc.filter = (moderngl.NEAREST, moderngl.NEAREST)
    td = ctx.texture((w, h), 1, np.ascontiguousarray(dep.astype(np.float32)).tobytes(), dtype="f4")
    td.filter = (moderngl.NEAREST, moderngl.NEAREST)
    tc.use(0)
    td.use(1)
    prog["C"] = 0
    prog["D"] = 1
    ts = 2.0
    prog["P"] = (1 / (32 * ts), 1 / (16 * ts), -1.0 / (0.0028867084 / 2.0), a.gmax)
    prog["Q"] = (a.snap, 0.05 * a.depth, a.steps, 0.7)
    out = ctx.texture((w, h), 1)
    fbo = ctx.framebuffer([out])
    fbo.use()
    ctx.viewport = (0, 0, w, h)
    vbo = ctx.buffer(np.array([-1, -1, 1, -1, 1, 1, -1, 1], "f4").tobytes())
    vao = ctx.vertex_array(prog, [(vbo, "2f", "pos")])
    vao.render(moderngl.TRIANGLE_FAN)
    code = np.frombuffer(out.read(), np.uint8).reshape(h, w).astype(int)
    has = code > 0
    v = code - 1
    cls = np.where(has, v // 81, -1)
    r = v - cls * 81
    ku, kv = r // 9 - 4, r % 9 - 4
    print(f"{w}x{h}: coded {has.mean() * 100:.1f} %, floor {np.mean(cls == 0) * 100:.1f} %, wall x {np.mean(cls == 1) * 100:.1f} %, wall y {np.mean(cls == 2) * 100:.1f} %")
    for name, k in (("ku", ku), ("kv", kv)):
        vals, cnt = np.unique(k[has], return_counts=True)
        print(" ", name, " ".join(f"{int(x)}:{c * 100 / has.sum():.1f}%" for x, c in zip(vals, cnt)))
    if a.factor:
        L = np.array([float(x) for x in a.factor.split(",")]); L /= np.linalg.norm(L)
        fp = ctx.program(vertex_shader=vert, fragment_shader=java_const(src, "FACTOR_FRAG"))
        out.use(0)
        fp["A"] = 0
        for k, v in (("P", (1 / (32 * ts), 1 / (16 * ts), -1.0 / (0.0028867084 / 2.0), a.gmax)), ("Q", (a.snap, 0.05 * a.depth, a.steps, 0.7)), ("L", (L[0], L[1], L[2], 1.0)), ("share", 1.0)):
            if k in fp:
                fp[k] = v
        fo = ctx.texture((w, h), 1, dtype="f4")
        ctx.framebuffer([fo]).use()
        ctx.vertex_array(fp, [(vbo, "2f", "pos")]).render(moderngl.TRIANGLE_FAN)
        f = np.frombuffer(fo.read(), np.float32).reshape(h, w)
        print(f"factor: mean {f[has].mean():.3f}, p5 {np.percentile(f[has], 5):.3f}, p95 {np.percentile(f[has], 95):.3f}")
        base = a.png[:-4] if a.png else "/tmp/relief-aux"
        Image.fromarray(np.clip(f * 127.5, 0, 255).astype(np.uint8)).save(base + "-factor.png")
        Image.fromarray(np.clip(col[..., :3] * f[..., None], 0, 255).astype(np.uint8)).save(base + "-relit.png")
    if a.png:
        img = np.zeros((h, w, 3), np.uint8)
        img[..., 0] = np.where(has, (ku + 4) * 31, 40)
        img[..., 1] = np.where(has, (kv + 4) * 31, 40)
        img[..., 2] = np.where(has, cls * 120, 40)
        Image.fromarray(img).save(a.png)
        print(a.png)


if __name__ == "__main__":
    main()
