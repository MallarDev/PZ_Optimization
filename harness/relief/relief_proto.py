#!/usr/bin/env python3
"""Offline prototype of pzopt.Relief on a pixelLight dump (devPplDumpAt + devPplView=2: the unlit albedo and the depth).

    harness/relief/proto.py <run> <tag> <x0,y0,x1,y1> [out.png] [--light west|torch:x,y] [--modes lum,groove,mix,...]

Rebuilds each pixel's world position from the window depth (zoom 1, no upscaler: one pixel = one chunk-texture texel), the
surface plane from the neighbours two pixels away (as pixelLight's texel normal does), a height per pixel from the colour
(one estimate per mode), the relief normal, and relights the crop by a low light (flat vs relief, with the self-shadow
ray), one column per mode.
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "ppl"))
from dump import load  # noqa: E402

LEVEL = 2.4494897
DEPTH_UNIT = 0.0014434  # window depth per unit of x + y + 2z
SNAP = 0.8
GMAX = 0.2
QBITS = int(os.environ.get("QBITS", "0"))
LUM = np.array([0.299, 0.587, 0.114], np.float32)


def positions(depth):
    h, w = depth.shape
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    A = xx / 64.0
    B = yy / 32.0
    C = -depth.astype(np.float64) / DEPTH_UNIT  # window depth falls towards the camera (x + y grows)
    Z = (C - B) * 0.125
    S = C - 2.0 * Z
    P = np.stack([(S + A) * 0.5, (S - A) * 0.5, Z], -1)
    return P * np.array([1.0, 1.0, LEVEL])  # z in squares


def plane_normals(P, depth, K=2):
    """pixelLight's texel normal: neighbours K away, the side with the smaller depth change, snapped to the three planes."""
    d = depth
    def sh(a, dy, dx):
        return np.roll(np.roll(a, -dy, 0), -dx, 1)
    xp, xm, yp, ym = sh(d, 0, K), sh(d, 0, -K), sh(d, K, 0), sh(d, -K, 0)
    sx = np.where((xp < 1) & ((xm >= 1) | (np.abs(xp - d) <= np.abs(d - xm))), 1, -1)
    sy = np.where((yp < 1) & ((ym >= 1) | (np.abs(yp - d) <= np.abs(d - ym))), 1, -1)
    Px = np.where((sx > 0)[..., None], sh(P, 0, K), sh(P, 0, -K))
    Py = np.where((sy > 0)[..., None], sh(P, K, 0), sh(P, -K, 0))
    tu = (Px - P) * (sx / K)[..., None]
    tv = (Py - P) * (sy / K)[..., None]
    n = np.cross(tu, tv)
    n /= np.maximum(np.linalg.norm(n, axis=-1, keepdims=True), 1e-12)
    flip = (n @ np.array([3.0, 3.0, LEVEL])) < 0
    n[flip] *= -1
    snapped = np.zeros(n.shape[:2], bool)
    # a relaxed snap (the three planes are 90 degrees apart; DEPTH16 steps over two texels tilt a wall ~20 degrees), then
    # the plane's exact tangents for one texel along u (A = x - y grows 1/64) and v (B = x + y - 6z grows 1/32)
    dA, dB = 1.0 / 64.0, 1.0 / 32.0
    exact = {0: ((0, -dA, -dA / 6 * LEVEL), (0, 0, -dB / 6 * LEVEL)),
             1: ((dA, 0, dA / 6 * LEVEL), (0, 0, -dB / 6 * LEVEL)),
             2: ((dA / 2, -dA / 2, 0), (dB / 2, dB / 2, 0))}
    for k in range(3):
        m = n[..., k] > SNAP
        e = np.zeros(3)
        e[k] = 1
        n[m] = e
        tu[m] = exact[k][0]
        tv[m] = exact[k][1]
        snapped |= m
    cu = (xp < 1) & (xm < 1) & (np.abs(xp + xm - 2 * d) < 4 / 65535 + 0.25 * np.abs(xp - xm))
    cv = (yp < 1) & (ym < 1) & (np.abs(yp + ym - 2 * d) < 4 / 65535 + 0.25 * np.abs(yp - ym))
    return n, tu, tv, snapped, cu, cv, sx, sy


def heights(rgb, a, mode):
    lum = rgb @ LUM
    mean = ndimage.uniform_filter(rgb, size=(4, 4, 1))
    if mode == "lum":
        return lum
    if mode == "groove":
        return -np.linalg.norm(rgb - mean, axis=-1)
    if mode == "mix":
        dc = rgb - mean
        return 0.5 * (dc @ LUM) - np.linalg.norm(dc, axis=-1)
    if mode in ("gbox", "g", "deriv"):
        return rgb[..., 1]
    if mode == "deriv-l1":  # the green half level 0, half the 2x2 mean (a textureLod at 0.5 between the levels)
        g = rgb[..., 1]
        m = np.repeat(np.repeat(0.25 * (g[0::2, 0::2][:g.shape[0]//2, :g.shape[1]//2] + g[1::2, 0::2][:g.shape[0]//2, :g.shape[1]//2] + g[0::2, 1::2][:g.shape[0]//2, :g.shape[1]//2] + g[1::2, 1::2][:g.shape[0]//2, :g.shape[1]//2]), 2, 0), 2, 1)
        out = g.copy()
        out[:m.shape[0], :m.shape[1]] = 0.5 * g[:m.shape[0], :m.shape[1]] + 0.5 * m
        return out
    if mode in ("gbox_", "g_"):
        return rgb[..., 1]
    if mode == "lbox":
        return lum
    if mode == "gdogbox":  # green minus its 4x4 mean (mip 2): the shader's coarse term is one fetch
        return rgb[..., 1] - mean[..., 1]
    if mode == "lum-s1":  # luminance, smoothed a little (the art's 1-texel dither is noise, not relief)
        return ndimage.gaussian_filter(lum, 0.8)
    if mode == "mix-s1":
        dc = rgb - mean
        return ndimage.gaussian_filter(0.5 * (dc @ LUM) - np.linalg.norm(dc, axis=-1), 0.8)
    if mode == "dog":  # band-pass luminance: fine minus coarse
        return ndimage.gaussian_filter(lum, 0.7) - ndimage.gaussian_filter(lum, 3.0)
    if mode == "cavity":  # darker than the neighbourhood = lower, brighter clipped (painted highlights are not bumps)
        return np.minimum(lum - (mean @ LUM), 0.02)
    raise SystemExit("unknown mode " + mode)


def relief(rgb, a, depth, mode, strength, light, steps=4):
    P = positions(depth)
    n0, tu, tv, snapped, cu, cv, sx, sy = plane_normals(P, depth)
    h = heights(rgb, a, mode)
    h = np.where(a > 0.5, h, 0.0)
    def sh(v, dy, dx):
        return np.roll(np.roll(v, -dy, 0), -dx, 1)
    hup, hum, hvp, hvm = sh(h, 0, 1), sh(h, 0, -1), sh(h, 1, 0), sh(h, -1, 0)
    gu = np.where(cu, 0.5 * (hup - hum), np.where(sx > 0, hup - h, h - hum))
    gv = np.where(cv, 0.5 * (hvp - hvm), np.where(sy > 0, hvp - h, h - hvm))
    if mode.startswith("deriv"):
        # the shader's dFdx / dFdy of the green it fetched (pixel = texel at zoom 1): one difference per 2x2 quad pair
        hh = h if mode == "deriv" else h
        xs = np.arange(hh.shape[1])
        ys = np.arange(hh.shape[0])
        ev_x, od_x = xs & ~1, np.minimum(xs | 1, hh.shape[1] - 1)
        ev_y, od_y = ys & ~1, np.minimum(ys | 1, hh.shape[0] - 1)
        gu = hh[:, od_x] - hh[:, ev_x]
        gv = hh[od_y, :] - hh[ev_y, :]
        gl = np.hypot(gu, gv)
        lim = GMAX / (GMAX + gl)
        gu, gv = gu * lim, gv * lim
    if mode.endswith("box"):
        # the shader's four textureGathers: the 2x2 blocks around the texel (TL, TR, BL, BR), their means, a smoothed gradient
        b = (h + sh(h, 0, 1) + sh(h, 1, 0) + sh(h, 1, 1)) * 0.25  # block whose top-left texel is (y, x)
        TL, TR, BL, BR = sh(b, -1, -1), sh(b, -1, 0), sh(b, 0, -1), b
        gu = 0.5 * (TR + BR - TL - BL)
        gv = 0.5 * (BL + BR - TL - TR)
        gl = np.hypot(gu, gv)
        lim = GMAX / (GMAX + gl)
        gu, gv = gu * lim, gv * lim
    if QBITS:
        # the aux texture's code: each gradient component quantised to QBITS bits, a signed square-root scale up to GMAX
        lv = (1 << QBITS) - 1 if QBITS < 8 else QBITS - 1  # QBITS >= 8: that many levels (odd: zero is one)
        def q(g):
            t = np.sign(g) * np.sqrt(np.clip(np.abs(g) / GMAX, 0, 1))  # -1..1
            k = np.round((t * 0.5 + 0.5) * lv)
            t2 = k / lv * 2 - 1
            return np.sign(t2) * t2 * t2 * GMAX
        gu, gv = q(np.clip(gu, -GMAX, GMAX)), q(np.clip(gv, -GMAX, GMAX))
    s = 0.05 * strength
    nr = np.cross(tu + n0 * (s * gu)[..., None], tv + n0 * (s * gv)[..., None])
    nr /= np.maximum(np.linalg.norm(nr, axis=-1, keepdims=True), 1e-12)
    nr[(nr * n0).sum(-1) < 0] *= -1
    use = snapped & (a > 0.5) & (depth < 1)
    nr[~use] = n0[~use]
    Ls = np.broadcast_to(light, nr.shape)
    flat = np.maximum((n0 * Ls).sum(-1), 0)
    lit = np.maximum((nr * Ls).sum(-1), 0)
    ratio = np.where(flat > 0.05, lit / np.maximum(flat, 0.2), 1.0)
    # self-shadow ray across the plane (solve the light's in-plane direction in texel steps)
    ln = (Ls * n0).sum(-1)
    lp = Ls - n0 * ln[..., None]
    g00, g01, g11 = (tu * tu).sum(-1), (tu * tv).sum(-1), (tv * tv).sum(-1)
    r0, r1 = (tu * lp).sum(-1), (tv * lp).sum(-1)
    det = g00 * g11 - g01 * g01
    det = np.where(np.abs(det) < 1e-20, 1e-20, det)
    st = np.stack([g11 * r0 - g01 * r1, g00 * r1 - g01 * r0], -1) / det[..., None]
    m = np.maximum(np.abs(st).max(-1), 1e-6)
    st /= m[..., None]
    rise = np.linalg.norm(st[..., :1] * tu + st[..., 1:] * tv, axis=-1) * ln / np.maximum(np.linalg.norm(lp, axis=-1), 1e-6)
    vis = np.ones_like(h)
    hh, ww = h.shape
    yy, xx = np.mgrid[0:hh, 0:ww]
    for k in range(1, steps + 1):
        ty = np.clip(yy + np.round(st[..., 1] * k).astype(int), 0, hh - 1)
        tx = np.clip(xx + np.round(st[..., 0] * k).astype(int), 0, ww - 1)
        occ = s * (h[ty, tx] - h) - rise * k
        vis = np.minimum(vis, 1 - np.clip(occ / (0.25 * s), 0, 1))
    vis = np.where(use & (ln > 0), 1 - 0.7 * (1 - vis), 1.0)
    return ratio * vis, nr, h


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("tag")
    ap.add_argument("box")
    ap.add_argument("out", nargs="?", default="/tmp/relief-proto.png")
    ap.add_argument("--light", default="west")
    ap.add_argument("--modes", default="lum,groove,mix")
    ap.add_argument("--strength", type=float, default=1.0)
    ap.add_argument("--scale", type=int, default=1)
    ap.add_argument("--shade", action="store_true", help="the relief factor alone, grey (0.5 = flat)")
    args = ap.parse_args()
    d = load(args.run, args.tag)
    x0, y0, x1, y1 = map(int, args.box.split(","))
    col = d.color[y0:y1, x0:x1].astype(np.float32) / 255.0
    depth = d.depth[y0:y1, x0:x1]
    a = col[..., 3]
    rgb = col[..., :3] / np.maximum(a, 1e-3)[..., None]
    L = {"west": np.array([-0.8, 0.25, 0.45]), "south": np.array([0.2, 0.9, 0.35]), "east": np.array([0.85, -0.2, 0.45])}[args.light]
    L = L / np.linalg.norm(L)
    tiles = [("albedo", col[..., :3])]
    for mode in args.modes.split(","):
        k, nr, h = relief(rgb, a, depth, mode, args.strength, L)
        if args.shade:
            tiles.append((mode, np.repeat(np.clip(0.5 * k, 0, 1)[..., None], 3, -1)))
        else:
            tiles.append((mode, np.clip(col[..., :3] * k[..., None], 0, 1)))
    w, hgt = x1 - x0, y1 - y0
    sc = args.scale
    out = Image.new("RGB", ((w * sc + 4) * len(tiles), hgt * sc))
    for i, (name, img) in enumerate(tiles):
        im = Image.fromarray((img * 255).astype(np.uint8)).resize((w * sc, hgt * sc), Image.NEAREST)
        ImageDraw.Draw(im).text((4, 4), name, fill=(255, 255, 0))
        out.paste(im, (i * (w * sc + 4), 0))
    out.save(args.out)
    print(args.out, out.size)


if __name__ == "__main__":
    main()
