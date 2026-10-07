#!/usr/bin/env python3
"""Do long sun shadows stop on chunk lines? (2026-10-07, the maintainer's "at sunrise and sunset the shadows of trees and
buildings are too long and are cut off")

Each chunk texture computes its own sun term; a caster that one texture takes into account and its neighbour does not (a tree
card past the cards' reach, a column outside the far-field window) ends its shadow on the chunk line between them. Three
screenshots of one still camera (`--shot-at`) find those lines exactly:
  X:   --prop devAoView=1 --prop devSunView=9   (the texel's x / 8 in its chunk)
  Y:   --prop devAoView=1 --prop devSunView=10  (y / 8)
  VIS: --prop devAoView=1 --prop devSunView=11  (the sun's visibility alone: 1 lit, 0 full shadow)
  Z:   --prop devAoView=1 --prop devSunView=5   (optional, height: only the lowest level's ground counts, not crowns / walls)
plus `--prop overlay=false --prop hdr=false --prop hdrAuto=false --prop colorGrading=false --prop pixelLight=false
--prop godRays=false --prop ambientOcclusion=false` so the screen shows the term as it is.

A seam pair: two neighbouring pixels where one chunk coordinate wraps (|dx| > 0.6) and the other stays (< 0.03); an inner
pair: both stay. The visibility step across seams against the step between inner pairs (same surface, same distance) tells a
cut: `seam_excess` = mean |dVIS| over seam pairs in shadow - over inner pairs in shadow; `cut_share` = seam pairs in shadow
with a step > 0.08 (the inner pairs' rate is the noise floor).

Usage: shadow-seams.py --x RUN --y RUN --vis RUN [--vis RUN ...] [--json f]
       shadow-seams.py ... --judge [--before-vis RUN] [--context "..."]   (Jev: first --vis is the test)
"""
import argparse
import json
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))


def gray(run):
    p = run if run.endswith(".png") else os.path.join(run, "shot-game.png")
    return np.asarray(Image.open(p).convert("L")).astype(np.float32) / 255.0


def pairs(X, Y, axis):
    """Seam and inner masks for neighbour pairs along an axis (0: vertical, 1: horizontal)."""
    if axis == 1:
        xa, xb, ya, yb = X[:, :-1], X[:, 1:], Y[:, :-1], Y[:, 1:]
    else:
        xa, xb, ya, yb = X[:-1, :], X[1:, :], Y[:-1, :], Y[1:, :]
    dx, dy = np.abs(xa - xb), np.abs(ya - yb)
    seam = (dx > 0.6) & (dy < 0.03) | (dy > 0.6) & (dx < 0.03)
    inner = (dx < 0.03) & (dy < 0.03)
    return seam, inner


SUN_SCREEN = None  # the shadows' direction on screen (unit), from --sun-az


def measure(X, Y, V, Z=None):
    h, w = V.shape
    keep = np.zeros_like(V, bool)
    keep[int(h * 0.04):int(h * 0.88), int(w * 0.02):int(w * 0.98)] = True  # HUD, hotbar
    if Z is not None:
        keep &= Z < 0.5 / 255.0  # the ground of the lowest level (height 0) (devSunView=5: height / 2 levels); crowns and walls out
    out = {}
    agg = {"seam": [], "inner": []}
    for axis in (0, 1):
        seam, inner = pairs(X, Y, axis)
        if axis == 1:
            va, vb, ka = V[:, :-1], V[:, 1:], keep[:, :-1] & keep[:, 1:]
        else:
            va, vb, ka = V[:-1, :], V[1:, :], keep[:-1, :] & keep[1:, :]
        d = np.abs(va - vb)
        shade = np.minimum(va, vb) < 0.92  # in or at a shadow
        agg["seam"].append(d[seam & ka & shade])
        agg["inner"].append(d[inner & ka & shade])
    s = np.concatenate(agg["seam"])
    i = np.concatenate(agg["inner"])
    out["seam_pairs_in_shadow"] = int(s.size)
    out["inner_pairs_in_shadow"] = int(i.size)
    out["seam_mean_step"] = round(float(s.mean()), 4) if s.size else 0.0
    out["inner_mean_step"] = round(float(i.mean()), 4) if i.size else 0.0
    out["seam_excess"] = round(out["seam_mean_step"] - out["inner_mean_step"], 4)
    out["cut_share_pct"] = round(100.0 * float((s > 0.08).mean()), 2) if s.size else 0.0
    out["inner_step_rate_pct"] = round(100.0 * float((i > 0.08).mean()), 2) if i.size else 0.0
    out["shadow_share_pct"] = round(100.0 * float(((V < 0.92) & keep).sum()) / keep.sum(), 2)
    # end edges: on the ground, away from object outlines, the visibility rising by > 0.12 within 6 px going away from the
    # sun (a shadow's end); a cut-off shadow ends on a line or a dotted comb, one reaching its caster's tip on a short edge
    if SUN_SCREEN is not None and Z is not None:
        ux, uy = SUN_SCREEN
        k = 6
        ox, oy = int(round(ux * k)), int(round(uy * k))
        g = keep.copy()
        for _ in range(4):  # erode: object outlines out
            e0 = g.copy()
            e0[1:, :] &= g[:-1, :]
            e0[:-1, :] &= g[1:, :]
            e0[:, 1:] &= g[:, :-1]
            e0[:, :-1] &= g[:, 1:]
            g = e0
        A = V[max(0, -oy):h - max(0, oy), max(0, -ox):w - max(0, ox)]
        B = V[max(0, oy):max(0, oy) + A.shape[0], max(0, ox):max(0, ox) + A.shape[1]]
        G = g[max(0, -oy):h - max(0, oy), max(0, -ox):w - max(0, ox)] & g[max(0, oy):max(0, oy) + A.shape[0], max(0, ox):max(0, ox) + A.shape[1]]
        e = (B - A > 0.12) & G
        out["end_edges_per_mpx_ground"] = round(1e6 * float(e.sum()) / max(1, G.sum()), 1)
    out["mean_visibility"] = round(float(V[keep].mean()), 4)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--x", required=True)
    ap.add_argument("--y", required=True)
    ap.add_argument("--vis", action="append", required=True)
    ap.add_argument("--z", help="a devSunView=5 shot of the same camera: only ground pixels count")
    ap.add_argument("--sun-az", type=float, help="the sun's azimuth (deg, from the run's 'sun shadows' line): end edges on the ground")
    ap.add_argument("--before-vis")
    ap.add_argument("--json")
    ap.add_argument("--judge", action="store_true")
    ap.add_argument("--context", default="")
    a = ap.parse_args()
    global SUN_SCREEN
    if a.sun_az is not None:
        import math
        az = math.radians(a.sun_az)
        dx, dy = -math.sin(az), math.cos(az)  # away from the sun on the ground (x east, y south)
        sx, sy = dx - dy, (dx + dy) * 0.5
        n = math.hypot(sx, sy)
        SUN_SCREEN = (sx / n, sy / n)
    X, Y = gray(a.x), gray(a.y)
    Z = gray(a.z) if a.z else None
    res = {}
    for r in a.vis + ([a.before_vis] if a.before_vis else []):
        res[os.path.basename(os.path.normpath(r))] = m = measure(X, Y, gray(r), Z)
        print(os.path.basename(os.path.normpath(r))[:28], json.dumps(m))
    if a.judge:
        from typesafe_client import ask, choice, noul, fmt  # noqa: E402
        test = res[os.path.basename(os.path.normpath(a.vis[0]))]
        before = res.get(os.path.basename(os.path.normpath(a.before_vis))) if a.before_vis else None
        state = {
            "setup": "A still camera over a town at sunset (the sun ~5 deg up: shadows 50+ squares long). Each 8x8-square chunk of "
                     "the ground computes its own sun shadows; a long shadow that one chunk includes and the next does not stops "
                     "on the straight chunk line between them. Over pixel pairs straddling a chunk line (seam pairs) vs pairs "
                     "inside a chunk (inner pairs), both where shadow is: the mean step of the sun's visibility (0..1) and the "
                     "share of pairs stepping by more than 0.08. A seam step clearly above the inner one means shadows are cut "
                     "on chunk lines. end_edges_per_mpx_ground: places on the open ground where a shadow stops (the visibility "
                     "rising sharply going away from the sun) per million ground pixels: a shadow cut off by a reach limit adds "
                     "an end line or a dotted comb, a shadow reaching its caster's tip only a short end. mean_visibility on the "
                     "ground: lower = more of the ground in the long shadows. " + a.context,
            "report": "the long shadows of trees and buildings at sunrise / sunset are cut off",
            "test": test,
        }
        if before:
            state["before"] = before
        print(json.dumps(state, indent=1))
        qs = {
            "test_cut": noul("Are shadows in `test` still cut off: on chunk lines (seam steps clearly above inner steps) or by "
                             "reach limits (ground end edges no fewer than before)?"),
            "verdict": choice({"question": "Verdict on `test`" + (" compared with `before`" if before else "")},
                              {"fixed": "test's ground end edges are clearly fewer than before's (shadows reach their tips) and its seam steps stay near the inner level",
                               "improved": "test is clearly better than before but still shows cut ends",
                               "still_cut": "test is no better than before",
                               "cut_confirmed": "no before given and test's seams step clearly above the inner level",
                               "inconclusive": "too few seam pairs in shadow or contradicting numbers"}),
        }
        if before:
            qs["before_cut"] = noul("Are shadows in `before` cut on chunk lines?")
        log = []
        ans = ask(state, qs, log=log)
        print("jev:")
        print("  " + fmt(ans).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
        res["judge"] = {"state": state, "answers": ans}
    if a.json:
        Path(a.json).write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
