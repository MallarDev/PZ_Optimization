#!/usr/bin/env python3
"""Does a manhole cover lying in a puddle flicker while the character walks (2026-10-05, the maintainer's report)?

Input: 1:1 RGBA devCapture runs of the manhole rig (the same walk in each), e.g.
  --mode bench --launcher direct --source-save Sandbox/2026-10-02_10-30-09 --flag weather=clear --flag puddles=1
  --flag explore=circle [--flag director=jev] --flag circle_spots=0 --flag circle_laps=1 --flag circle_radius=2.5
  --flag zombies=off --flag zoom=max --flag route=S:1 --flag speed=0.06 --route-seconds 17
  --prop devCapture=7,10,60,100,crop=1700:600:1400:900,ram
`--control` is a stock run (`--prop enabled=false`): the cover's template (40x24 gray) is taken from its reference frame, the
darkest 24x12 box within 8 px of --hint. In every run each frame is aligned to that run's reference frame (phase correlation of the whole
crop) and the cover found by normalised cross-correlation within 4 px of the camera-shifted position.
Inside the cover's core ellipse a pixel shows the puddle when it is > 25 brighter than its own
20th percentile over the run (the cover is the dark state; no tone matching across scenes, so rain or grading do not matter). Metrics: the puddle share per frame and the frame-to-frame flip share
(pixels changing between cover and puddle), and band rows: core rows >= 60 % puddle (the bug's bands; rain streaks
over the cover are thin vertical lines and raise the pixel shares but make no band rows; the same flip measure on plain
road 90 px beside the cover gives the rain's own level, cover_flips_minus_road what the cover adds). Stock: ~0.002 flips, no band
rows; the 2026-10-05 bug: ~0.3 flips, bands in most frames.
Jev (TypeSafe) answers whether `test` still flickers against `before` and `control`; exit 0 = fixed or no flicker.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view as swv

TW, TH = 40, 24


def frames(run):
    cap = Path(run) / "capture"
    head = (cap / "index.txt").read_text().split("\n")[0].split()
    w, h = int(head[0][2:]), int(head[1][2:])
    return np.memmap(cap / "frames.rgba", dtype=np.uint8, mode="r").reshape(-1, h, w, 4)


def gray(a, f):
    return np.flipud(a[f])[:, :, :3].astype(np.float32).mean(axis=2)  # devCapture rows are bottom-up


def shift(A, B):
    F = np.fft.fft2(A - A.mean()) * np.conj(np.fft.fft2(B - B.mean()))
    c = np.fft.ifft2(F / (np.abs(F) + 1e-6)).real
    y, x = np.unravel_index(np.argmax(c), c.shape)
    if y > c.shape[0] // 2:
        y -= c.shape[0]
    if x > c.shape[1] // 2:
        x -= c.shape[1]
    return int(x), int(y)


def find(g, T, x0, y0, r):
    h, w = g.shape
    ys, xs = max(0, y0 - r), max(0, x0 - r)
    sub = g[ys:min(h, y0 + r + TH), xs:min(w, x0 + r + TW)]
    win = swv(sub, (TH, TW))
    wm = win - win.mean(axis=(2, 3), keepdims=True)
    tn = (T - T.mean()) / T.std()
    ncc = (wm * tn).sum(axis=(2, 3)) / (np.sqrt((wm ** 2).sum(axis=(2, 3))) * np.sqrt(T.size) + 1e-6)
    y, x = np.unravel_index(np.argmax(ncc), ncc.shape)
    return xs + int(x), ys + int(y), float(ncc[y, x])


def template(run, ref, hint):
    g = gray(frames(run), ref)
    best = None
    for y in range(hint[1] - 8, hint[1] + 9):
        for x in range(hint[0] - 8, hint[0] + 9):
            m = g[y - 6:y + 6, x - 12:x + 12].mean()
            if best is None or m < best[0]:
                best = (m, x, y)
    _, x, y = best
    return g[y - TH // 2:y + TH // 2, x - TW // 2:x + TW // 2].copy()


def measure(run, T, ref, hint):
    a = frames(run)
    n = a.shape[0]
    R = gray(a, ref)
    mx, my, ncc0 = find(R, T, hint[0] - TW // 2, hint[1] - TH // 2, 220)
    yy, xx = np.mgrid[0:TH, 0:TW]
    core = ((xx - TW / 2 + 0.5) / (TW * 0.36)) ** 2 + ((yy - TH / 2 + 0.5) / (TH * 0.30)) ** 2 < 1
    rows = [r for r in range(TH) if core[r].sum() >= 8]  # core rows wide enough to tell a band from a rain streak
    patches, road = [], []
    for f in range(n):
        g = gray(a, f)
        sx, sy = shift(g, R)
        x, y, _ = find(g, T, mx + sx, my + sy, 4)
        patches.append(g[y:y + TH, x:x + TW])
        road.append(g[y + 5:y + 5 + TH, x - 90:x - 90 + TW])  # plain road beside the cover: the rain's own flicker
    P = np.array(patches)
    Q = np.array(road)
    rm = (Q - np.percentile(Q, 20, axis=0) > 25)
    road_flips = np.array([(rm[i] ^ rm[i - 1]).mean() for i in range(1, n)])
    base = np.percentile(P, 20, axis=0)  # the cover's own dark state over the run (no cross-scene tone matching)
    shares, masks, bands = [], [], []
    for p in P:
        m = (p - base > 25) & core
        shares.append(float(m.sum() / core.sum()))
        masks.append(m)
        bands.append(np.array([m[r][core[r]].mean() >= 0.6 for r in rows]))  # a band: >= 60 % of the row's core is puddle
    s = np.array(shares)
    flips = np.array([(masks[i] ^ masks[i - 1]).sum() / core.sum() for i in range(1, n)])
    band = np.array([b.mean() for b in bands])
    band_flips = np.array([(bands[i] ^ bands[i - 1]).sum() for i in range(1, n)])
    return {"run": Path(run).name, "frames": n, "cover_found_ncc": round(ncc0, 3),
            "puddle_share_mean": round(float(s.mean()), 3), "frames_puddle_over_5pct": int((s > 0.05).sum()),
            "flip_share_mean": round(float(flips.mean()), 4), "frames_flip_over_5pct": int((flips > 0.05).sum()),
            "flip_share_p95": round(float(np.percentile(flips, 95)), 3),
            "band_row_share_mean": round(float(band.mean()), 3), "frames_with_bands": int((band > 0).sum()),
            "band_rows_changing_per_frame": round(float(band_flips.mean()), 2),
            "road_beside_flip_share_mean": round(float(road_flips.mean()), 4),
            "cover_flips_minus_road": round(float(flips.mean() - road_flips.mean()), 4)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--test", required=True)
    ap.add_argument("--before")
    ap.add_argument("--control", required=True, help="stock run of the same walk (template source)")
    ap.add_argument("--ref", type=int, default=200, help="reference frame of each run")
    ap.add_argument("--hint", default="1124,262", help="the cover's centre in the control's reference frame (x,y)")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    hint = tuple(int(v) for v in a.hint.split(","))
    T = template(a.control, a.ref, hint)
    runs = {"test": a.test, "before": a.before, "control": a.control}
    res = {k: measure(v, T, a.ref, hint) for k, v in runs.items() if v}
    for k, r in res.items():
        print(f"{k:8s} {r['run']}: {r['frames']} frames, cover ncc {r['cover_found_ncc']}; puddle over the cover {r['puddle_share_mean']} "
              f"(frames > 5 %: {r['frames_puddle_over_5pct']}); flips {r['flip_share_mean']} a frame, p95 {r['flip_share_p95']} "
              f"(frames > 5 %: {r['frames_flip_over_5pct']}); band rows {r['band_row_share_mean']} (frames with bands "
              f"{r['frames_with_bands']}, rows changing a frame {r['band_rows_changing_per_frame']}); road beside "
              f"{r['road_beside_flip_share_mean']} flips (cover minus road {r['cover_flips_minus_road']})")
    out = {"metrics": res}
    code = 0
    if not a.no_jev:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from typesafe_client import ask, choice, fmt, noul  # noqa: E402
        state = {
            "setup": "In-game frame captures (1:1, 60 fps) of the same scene: the character walks circles on a wet street at the "
                     "widest zoom, puddles at full size, a manhole cover lying in a puddle. In each frame the cover is located and "
                     "every pixel of its core is classed as cover or puddle (the puddle drawn over it). puddle_share = share of the "
                     "core showing puddle; flip_share = share of the core changing between cover and puddle from one frame to the "
                     "next. The reported artefact is horizontal bands of puddle across the cover that change every frame: a band "
                     "row is a row of the core that is >= 60 % puddle (band_row_share, frames_with_bands, band_rows_changing_per_frame). "
                     "Rain streaks are thin vertical lines over the cover: they raise puddle_share and flip_share but never make "
                     "band rows. road_beside_flip_share = the same flip measure on plain road 90 px beside the cover: the rain's own "
                     "flicker, which differs between builds (the optimized build draws rain streaks differently); "
                     "cover_flips_minus_road is what the cover adds. `control` is the stock game; `before` the build without "
                     "the fix; `test` the fix.",
            "report": "manholes flicker while walking zoomed out all the way, in the rain puddles",
            **res,
        }
        questions = {
            "test_flickers": noul("Does `test` show the reported band flicker on the cover (band rows in many frames, changing frame to frame, well above `control`)?"),
            "before_flickers": noul("If `before` is given: does it show that flicker?"),
            "control_flickers": noul("Does `control` (stock) show that flicker?"),
            "verdict": choice({"question": "What best describes `test`?"},
                              {"fixed": "`before` flickers and `test` is like `control` on the cover: no band rows and the cover adds no flicker over the road beside it (cover_flips_minus_road ~0)",
                               "still_flickers": "`test` still shows the flicker",
                               "flicker_confirmed": "`test` flickers and there is no fixed build to compare (a repro)",
                               "no_flicker": "neither `test` nor `before` flickers: not reproduced",
                               "inconclusive": "too few frames or contradicting numbers"}),
        }
        log = []
        answers = ask(state, questions, log=log)
        print("jev:")
        print("  " + fmt(answers).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
        out.update({"state": state, "answers": answers, "typesafe": log[0]})
        v = answers.get("verdict", {})
        top = v.get("choice") if isinstance(v, dict) else None
        code = 0 if top in ("fixed", "no_flicker") else 1
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=1))
    sys.exit(code)


if __name__ == "__main__":
    main()
