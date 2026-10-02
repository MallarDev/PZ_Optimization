#!/usr/bin/env python3
"""Are the characters' sun shadows cut flat at the head? (2026-10-02, player report "the top of the shadow's head looks
cut off, a flat cut"). Reads a pzopt.FrameCapture run made with `--prop devShadowTipTogglePeriod=MS` (the sun quads end at
the old place and at the fixed one every other period, "capsule shadows: dev tip toggle old|fixed at epoch_ms" lines) and
measures every character shadow's far tip in the frames of each mode; Jev (text-only) reads the numbers.

    shadow-tip-judge.py <run> [--box x0,y0,x1,y1] [--every N] [--json out.json] [--no-jev]

Per frame: the lit ground's luminance per 96 px block (its median: paint lines and shadows are the minority, bilinear), shadow = 30..86 % of it; shadow
components over 600 px; each one's long axis (PCA). The far tip is the end with fewer non-ground, non-shadow pixels (the
character's body) round it. At the tip:
  - hardness: the mean luminance gradient on the boundary in the last 6 px over that on the shadow's sides (middle half
    of its length). The head's shadow lies further from the caster than the sides, so its penumbra is wider: a rounded
    tip is softer than the sides (~0.2-0.4); the quad's straight edge is as hard as the sides or harder (~1).
  - abruptness (information only): the width in the last 3 px along the axis over the widest in the last 40 px; the
    cut runs at a slant to the shadow's axis (the quad is boxed along the shadow's screen direction), so it hardly moves.
Only shadows over 150 px long count (whole characters' shadows, not fragments). A tip counts as cut when hardness > 0.7.
Calibration (headcut-t18.5, 18:30 sun at 25 deg): the player's shadow old 1.05 (IQR 0.94-1.08), fixed 0.25 (0.25-0.28).
--box must frame the characters' shadows only: every dark edge in it over 150 px (a roof, a building's shade, a kerb)
passes as a tip and is the same in both modes (the noon run without a box: 140 such "tips", Jev "not_fixed"). Under a
high sun a character's shadow is shorter than 150 px and nothing is measured; the cut needs a sun below ~45 deg anyway.
"""
import argparse
import json
import os
import re
import sys
from collections import deque

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "ppl"))
from capture import load  # noqa: E402

TOGGLE = re.compile(r"dev tip toggle (old|fixed) at epoch_ms (\d+)")


def periods(run):
    return [(int(m.group(2)), m.group(1)) for m in TOGGLE.finditer(open(os.path.join(run, "console.txt"), errors="replace").read())]


def mode_at(ev, t):
    m, st = None, 0
    for e, k in ev:
        if e <= t:
            m, st = k, e
    return m, st


def ground(L, block=96):
    h, w = L.shape
    gy, gx = (h + block - 1) // block, (w + block - 1) // block
    g = np.zeros((gy, gx))
    for j in range(gy):
        for i in range(gx):
            g[j, i] = np.median(L[j * block:(j + 1) * block, i * block:(i + 1) * block])
    ys = (np.arange(h) + 0.5) / block - 0.5
    xs = (np.arange(w) + 0.5) / block - 0.5
    y0 = np.clip(np.floor(ys).astype(int), 0, gy - 1)
    x0 = np.clip(np.floor(xs).astype(int), 0, gx - 1)
    y1 = np.clip(y0 + 1, 0, gy - 1)
    x1 = np.clip(x0 + 1, 0, gx - 1)
    fy = np.clip(ys - y0, 0, 1)[:, None]
    fx = np.clip(xs - x0, 0, 1)[None, :]
    top = g[y0][:, x0] * (1 - fx) + g[y0][:, x1] * fx
    bot = g[y1][:, x0] * (1 - fx) + g[y1][:, x1] * fx
    return top * (1 - fy) + bot * fy


def components(mask, min_px):
    h, w = mask.shape
    lab = np.zeros(mask.shape, np.int32)
    out = []
    ys, xs = np.nonzero(mask)
    n = 0
    for sy, sx in zip(ys, xs):
        if lab[sy, sx]:
            continue
        n += 1
        q = deque([(sy, sx)])
        lab[sy, sx] = n
        pts = []
        while q:
            y, x = q.popleft()
            pts.append((y, x))
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                yy, xx = y + dy, x + dx
                if 0 <= yy < h and 0 <= xx < w and mask[yy, xx] and not lab[yy, xx]:
                    lab[yy, xx] = n
                    q.append((yy, xx))
        if len(pts) >= min_px:
            out.append(np.array(pts))
    return out


def tip_metrics(pts, body, grad, boundary):
    c = pts.mean(0)
    d = pts - c
    ev, evec = np.linalg.eigh(np.cov(d.T))
    ax = evec[:, np.argmax(ev)]
    p = d @ ax
    lo, hi = p.min(), p.max()
    length = hi - lo
    if length < 40:
        return None
    h, w = body.shape

    def body_near(sel):
        q = pts[sel]
        n = 0
        for y, x in q[:: max(1, len(q) // 60)]:
            n += body[max(0, y - 10):min(h, y + 11), max(0, x - 10):min(w, x + 11)].sum()
        return n

    near_hi = body_near(p > hi - 0.06 * length)
    near_lo = body_near(p < lo + 0.06 * length)
    s = p if near_lo >= near_hi else -p  # the far tip at the largest s
    end = s.max()
    k = np.floor(end - s).astype(int)
    width = np.bincount(k[k < 40], minlength=40)
    if width.max() < 6:
        return None
    abrupt = width[:3].mean() / width.max()
    on_b = boundary[pts[:, 0], pts[:, 1]]
    tip_b = on_b & (end - s < 6)
    side_b = on_b & (np.abs(s - (s.min() + end) / 2) < 0.25 * length)
    if tip_b.sum() < 3 or side_b.sum() < 10:
        return None
    hard = grad[pts[tip_b, 0], pts[tip_b, 1]].mean() / max(1e-3, grad[pts[side_b, 0], pts[side_b, 1]].mean())
    return {"abrupt": float(abrupt), "hard": float(hard), "length_px": float(length), "y": float(c[0]), "x": float(c[1])}


def frame_metrics(rgb):
    L = rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114
    G = ground(L)
    r = L / np.maximum(G, 1)
    shadow = (r > 0.30) & (r < 0.86)
    groundish = np.abs(r - 1) < 0.08
    body = ~shadow & ~groundish
    gy, gx = np.gradient(L)
    grad = np.hypot(gx, gy)
    inner = shadow.copy()
    inner[1:, :] &= shadow[:-1, :]
    inner[:-1, :] &= shadow[1:, :]
    inner[:, 1:] &= shadow[:, :-1]
    inner[:, :-1] &= shadow[:, 1:]
    boundary = shadow & ~inner
    out = []
    for pts in components(shadow, 600):
        m = tip_metrics(pts, body, grad, boundary)
        if m and m["length_px"] > 150:
            out.append(m)
    return out


def summary(tips):
    if not tips:
        return {"tips": 0}
    a = np.array([t["abrupt"] for t in tips])
    h = np.array([t["hard"] for t in tips])
    cut = h > 0.7
    return {"tips": int(len(tips)), "abruptness_median": round(float(np.median(a)), 3),
            "abruptness_p90": round(float(np.percentile(a, 90)), 3), "hardness_median": round(float(np.median(h)), 3),
            "hardness_p90": round(float(np.percentile(h, 90)), 3), "cut_share": round(float(cut.mean()), 3)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--box", default="")
    ap.add_argument("--every", type=int, default=3)
    ap.add_argument("--skip-ms", type=int, default=150, help="frames this soon after a switch are left out")
    ap.add_argument("--json", default="")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    ev = periods(a.run)
    if not ev:
        sys.exit("no 'dev tip toggle' lines in the console: run with --prop devShadowTipTogglePeriod=MS")
    frames, stamps = load(a.run)
    box = [int(v) for v in a.box.split(",")] if a.box else None
    tips = {"old": [], "fixed": []}
    nframes = {"old": 0, "fixed": 0}
    for i in range(0, len(stamps), a.every):
        m, st = mode_at(ev, stamps[i])
        if m is None or stamps[i] - st < a.skip_ms:
            continue
        f = frames[i].astype(np.float32)
        if box:
            f = f[box[1]:box[3], box[0]:box[2]]
        tips[m] += frame_metrics(f)
        nframes[m] += 1
    card = {
        "rig": "same run, the sun shadow quads' far end alternating between the OLD build's place and the FIXED one",
        "metrics": {
            "hardness": "luminance gradient on the far tip's edge / on the shadow's sides; a rounded head's shadow is softer than the sides (about 0.2-0.4, its penumbra is wider further from the caster); a straight cut edge is as hard as the sides or harder (about 1)",
            "abruptness": "information only: shadow width in the last 3 px of its far tip / its widest in the last 40 px (the cut is slanted, so this barely changes)",
            "cut_share": "share of shadow tips with hardness > 0.7 (cut flat)",
        },
        "before_old": dict(summary(tips["old"]), frames=nframes["old"]),
        "after_fixed": dict(summary(tips["fixed"]), frames=nframes["fixed"]),
    }
    print(json.dumps(card, indent=1))
    if not a.no_jev:
        from typesafe_client import ask, choice, fmt, noul
        q = {
            "before_cut": noul("Are many shadow tips of BEFORE (`before_old`) cut flat: a large cut_share and a hardness p90 "
                               "around 1 or more?"),
            "after_round": noul("Do the shadow tips of AFTER (`after_fixed`) end like rounded heads: hardness median and p90 "
                                "clearly below 0.7 and cut_share near 0, clearly lower than BEFORE?"),
            "verdict": choice("What do the numbers say about the flat cut at the head of the characters' shadows?", {
                "fixed": "BEFORE has flat-cut tips, AFTER has rounded ones",
                "not_fixed": "AFTER still has flat-cut tips",
                "no_problem_before": "BEFORE already had rounded tips",
                "inconclusive": "too few tips or the numbers support none of these",
            }),
        }
        ans = ask(card, q)
        print(fmt(ans))
        card["jev"] = ans
    if a.json:
        json.dump(card, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
