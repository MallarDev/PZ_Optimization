#!/usr/bin/env python3
"""Offline check of pzopt.Mirrors' reflection march (signs, directions, depth tests) on a synthetic iso scene.

Scene (squares, z in levels): floor z = 0 (checker), a north wall y = 0 for x in [-6, 6], z in [0, 1], a mirror on it
(x in [-1, 2], z in [0.3, 0.9], facing +y), a red box x 0.4..0.9, y 0.8..1.3, z 0..0.75 (a character) and a blue box
x -2.5..-1.5, y 0.6..1.6, z 0..0.5. The frame is rendered with the game's iso mapping (u = x - y, v = x + y - 6z,
iso depth w = x + y + 2z, larger = nearer the camera); mirror pixels are marched exactly as the shader does and
compared with an analytic ray cast of the reflected ray. Writes /tmp/mir-sim.png (frame | march | truth).
"""
import numpy as np
from PIL import Image

import sys
FLOOR_FIRST = "--floor-first" in sys.argv
floor_hits = [0]
PXU, PXV = 64.0, 32.0            # px per iso unit (2x tiles at zoom 1)
W, H = 640, 520
U0, V0 = -5.0, -8.0              # iso coords of the top-left pixel
BOXES = [((0.4, 0.8, 0.0), (0.9, 1.3, 0.75), (220, 40, 40)), ((-2.5, 0.6, 0.0), (-1.5, 1.6, 0.5), (40, 60, 220))]
MIR = (-1.0, 2.0, 0.3, 0.9)

def floor_col(x, y):
    return np.where(((np.floor(x) + np.floor(y)) % 2) == 0, 170, 110)

def scene(u, v):
    """colour, w of the nearest surface along the view ray of iso point (u, v) (arrays)."""
    x0, y0 = (u + v) / 2, (v - u) / 2           # floor point (z = 0)
    w = v.copy()                                 # floor
    col = np.stack([floor_col(x0, y0)] * 3, -1).astype(float)
    # wall y = 0: x = u, z = (u - v) / 6
    zw = (u - v) / 6.0
    onwall = (zw >= 0) & (zw <= 1) & (u > -6) & (u < 6)
    ww = u + 2 * zw
    take = onwall & (ww > w)
    w = np.where(take, ww, w)
    mir = take & (u >= MIR[0]) & (u <= MIR[1]) & (zw >= MIR[2]) & (zw <= MIR[3])
    col[take] = [200, 190, 160]
    col[mir] = [180, 200, 215]
    # boxes along P0 + s (1, 1, 1/3)
    for lo, hi, c in BOXES:
        d = np.array([1, 1, 1 / 3.0])
        smin = np.full(u.shape, -1e9); smax = np.full(u.shape, 1e9)
        for k, p0 in enumerate((x0, y0, np.zeros_like(x0))):
            a = (lo[k] - p0) / d[k]; b = (hi[k] - p0) / d[k]
            smin = np.maximum(smin, np.minimum(a, b)); smax = np.minimum(smax, np.maximum(a, b))
        hit = (smax >= smin) & (smax >= 0)
        wb = v + 8 / 3.0 * smax
        take = hit & (wb > w)
        w = np.where(take, wb, w)
        col[take] = c
    return col, w, mir

def truth(P):
    """first hit of the reflected ray P + t (-1, 1, -1/3): floor or boxes (wall behind it is never reached)."""
    d = np.array([-1.0, 1.0, -1 / 3.0])
    tf = P[2] * 3.0
    best = tf; c = floor_col(P[0] + d[0] * tf, P[1] + d[1] * tf)
    col = (c, c, c)
    for lo, hi, cc in BOXES:
        tmin, tmax = -1e9, 1e9
        for k in range(3):
            if abs(d[k]) < 1e-9:
                if not (lo[k] <= P[k] <= hi[k]): tmin, tmax = 1, 0
                continue
            a = (lo[k] - P[k]) / d[k]; b = (hi[k] - P[k]) / d[k]
            tmin = max(tmin, min(a, b)); tmax = min(tmax, max(a, b))
        if tmax >= tmin and tmin > 0 and tmin < best:
            best = tmin; col = cc
    return np.array(col, float), best

def main():
    py, px = np.mgrid[0:H, 0:W].astype(float) + 0.5
    u = U0 + px / PXU; v = V0 + py / PXV
    col, w, mir = scene(u, v)
    march_img = col.copy(); truth_img = col.copy()
    kA, cA, kB, cB = 1 / PXU, U0, 1 / PXV, V0     # (screen y down here; the shader's kB < 0 only flips the axis)
    steps, thick, steppx = 32, 0.8, 10.0
    bad = 0; n = 0
    for yy, xx in zip(*np.nonzero(mir)):
        uu, vv = u[yy, xx], v[yy, xx]
        P = np.array([uu + 0.0, 0.0, 0.0]); P[2] = (P[0] + P[1] - vv) / 6.0
        # --- the shader's marchRay (axis 0)
        tEnd = max(0.05, P[2] * 3.0)
        pxPerT = np.array([-2.0 / kA, 2.0 / kB])
        px0 = np.array([(P[0] - P[1] - cA) / kA, (P[0] + P[1] - 6 * P[2] - cB) / kB])
        wM = P[0] + P[1] + 2 * P[2]
        nn = int(np.clip(np.linalg.norm(pxPerT) * tEnd / steppx, 4, steps)); dt = tEnd / nn
        def depth_at(q):
            ix, iy = int(q[0]), int(q[1])
            if not (0 <= ix < W and 0 <= iy < H): return None
            return w[iy, ix]
        tLo, tHit = 0.0, -1.0
        tf = P[2] * 3.0
        if FLOOR_FIRST and 0.05 < tf <= tEnd:
            pf = px0 + pxPerT * tf; wsf = depth_at(pf)
            if wsf is not None and abs(wsf - (wM - 2 / 3.0 * tf)) < 0.25:
                clear = True
                kk = max(3, nn // 2)
                for k in range(1, kk + 1):
                    t = tf * k / (kk + 1.0); ws = depth_at(px0 + pxPerT * t)
                    dd = (ws if ws is not None else -1e9) - (wM - 2 / 3.0 * t)
                    if 0 <= dd < thick: clear = False; break
                if clear: nn = 0; tHit = tf; floor_hits[0] += 1
        for i in range(1, nn + 1):
            t = dt * i; q = px0 + pxPerT * t
            ws = depth_at(q)
            if ws is None: break
            dd = ws - (wM - 2 / 3.0 * t)
            if 0 <= dd < thick: tHit = t; break
            tLo = t
        if tHit < 0: tHit = tEnd
        else:
            a, b = tLo, tHit
            for _ in range(4):
                m = 0.5 * (a + b); ws = depth_at(px0 + pxPerT * m)
                dd = (ws if ws is not None else -1e9) - (wM - 2 / 3.0 * m)
                if 0 <= dd < thick: b = m
                else: a = m
            tHit = b
        q = px0 + pxPerT * tHit
        ix, iy = int(q[0]), int(q[1])
        mc = col[iy, ix] if 0 <= ix < W and 0 <= iy < H else np.zeros(3)
        march_img[yy, xx] = mc
        tc, tt = truth(P)
        truth_img[yy, xx] = tc
        n += 1; bad += int(np.abs(mc - tc).max() > 30)
    print(f"mirror pixels {n}, march differs from truth on {bad} ({100.0 * bad / max(n, 1):.1f} %)" + (f", floor-first took {floor_hits[0]} ({100.0 * floor_hits[0] / max(n, 1):.0f} %)" if FLOOR_FIRST else ""))
    out = np.concatenate([col, march_img, truth_img], 1).clip(0, 255).astype(np.uint8)
    Image.fromarray(out).save("/tmp/mir-sim.png")

if __name__ == "__main__" and "--ppr" not in sys.argv:
    main()


def ppr():
    """Pixel-projected variant: every visible scene pixel in front of the plane writes itself into the pixel its mirror
    image lands on (nearest to the plane wins, as the water's atomicMax keys). Coverage and error against the truth."""
    py, px = np.mgrid[0:H, 0:W].astype(float) + 0.5
    u = U0 + px / PXU; v = V0 + py / PXV
    col, w, mir = scene(u, v)
    # the visible point of every pixel: P0 + s (1,1,1/3) with w = v + 8 s / 3
    s = (w - v) * 3.0 / 8.0
    x = (u + v) / 2 + s; y = (v - u) / 2 + s; z = s / 3.0
    d = y                                      # distance in front of the plane y = 0
    ok = (d > 0.02) & ~mir
    mx, mz = x + d, z + d / 3.0                # its image's pane point
    mu, mv = mx - 0.0, mx + 0.0 - 6.0 * mz     # (y = 0 on the pane)
    tx = ((mu - U0) * PXU).astype(int); ty = ((mv - V0) * PXV).astype(int)
    key = np.full((H, W), np.inf); out = np.zeros((H, W, 3))
    sel = ok & (tx >= 0) & (tx < W) & (ty >= 0) & (ty < H)
    for yy, xx in zip(*np.nonzero(sel)):
        a, b = ty[yy, xx], tx[yy, xx]
        if mir[a, b] and d[yy, xx] < key[a, b]:
            key[a, b] = d[yy, xx]; out[a, b] = col[yy, xx]
    n = mir.sum(); holes = (mir & np.isinf(key)).sum()
    bad = 0
    for yy, xx in zip(*np.nonzero(mir & ~np.isinf(key))):
        P = np.array([u[yy, xx], 0.0, 0.0]); P[2] = (P[0] - v[yy, xx]) / 6.0
        tc, _ = truth(P); bad += int(np.abs(out[yy, xx] - tc).max() > 30)
    print(f"ppr: mirror pixels {n}, holes {holes} ({100.0 * holes / n:.1f} %), wrong where written {bad} ({100.0 * bad / max(n - holes, 1):.1f} %)")


if __name__ == "__main__" and "--ppr" in __import__("sys").argv:
    ppr()
