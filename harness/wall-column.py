#!/usr/bin/env python3
"""How much darker (or brighter) is a window tile's wall face than the plain wall tiles beside it?

Usage: wall-column.py <run>... [--box X0,X1,Y0[,BAND,SLOPE]] [--json out] [--judge [--context "..."]]

Reads each run's shot-game.png and shot2-game.png (the --shot-at captures, 2 s apart; the numbers are their mean). The wall is sampled per pixel column in a band under the window
whose top edge runs along the facade (y = Y0 + slope * (x - X0); slope -0.5 for a wall that looks east, +0.5 for one
that looks south). Three tiles of width W from X0: the left neighbour [X0 - W, X0), the window tile [X0, X0 + W), the
right neighbour [X0 + W, X0 + 2W). Per run: each tile's mean luminance (Rec. 709 of the sRGB values, 0..255), the window
tile's ratio to its neighbours' mean, and the worst 4-px column step inside the three tiles beyond the neighbours' own
siding texture (an edge strip).

Default box: the shed east wall of the maintainer's 2026-10-03 report (Riverside, save 2026-10-02_10-30-09, player at
11298,6861, zoom 1, 4096 x 1728 game shots): window tile x 1984-2046, band top 825 at x 1984.
--judge asks Jev (numbers only) whether the window column is gone in the runs named test (the last run) against the
pixelLight-off reference (a run whose label contains "noppl") and the before run (the first).
"""
import argparse
import glob
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))


def run_dir(name):
    p = Path(name)
    if p.is_dir():
        return p
    hits = sorted(glob.glob(str(Path(__file__).resolve().parent / "runs" / f"{name}-2026*")))
    if not hits:
        sys.exit(f"no run {name}")
    return Path(hits[-1])


# the window walls of the 2026-10-03 scene (4096 x 1728 game shots): name -> (window tile x0, tile width, band top y at x0,
# band height, slope of the band along the facade, which neighbour tiles: "lr" both, "l" the left one only)
SITES = {
    "shed_below_window": (1984, 62, 825, 30, -0.5, "lr"),  # the reported shed, east wall: under the window to the ground
    "shed_above_window": (1984, 62, 722, 8, -0.5, "lr"),   # the same tile between the eave and the window
    "house_s_window": (1210, 60, 1496, 16, 0.5, "l"),      # the house at the bottom, south wall: above the window (a door tile right)
    "house_e_window": (383, 62, 737, 20, -0.5, "lr"),      # the grey house at the left, east wall: above its right window
}


def measure(lum, x0, w, y0, band, slope, sides="lr"):
    lo, hi = x0 - w, x0 + (2 * w if "r" in sides else w)
    cols = {}
    for x in range(lo, hi):
        top = int(round(y0 + slope * (x - x0)))
        cols[x] = float(np.median(lum[top:top + band, x]))  # median: rain streaks crossing the band do not count
    tile = lambda a, b: float(np.mean([cols[x] for x in range(a, b)]))
    left, win = tile(x0 - w, x0), tile(x0, x0 + w)
    right = tile(x0 + w, x0 + 2 * w) if "r" in sides else None
    nb = (left + right) / 2 if right is not None else left
    # 4-px column means: the biggest step between neighbouring groups (siding grain averages out over the band)
    g = [np.mean([cols[x] for x in range(s, s + 4)]) for s in range(lo, hi - 3, 4)]
    steps = [abs(g[i + 1] - g[i]) for i in range(len(g) - 1)]
    # the column's own signature: the step at each edge of the window tile (6 px inside minus 6 px outside, 2 px left out
    # at the seam itself), signed: a dark column is negative at both edges
    edge = lambda inside, outside: round(float(np.mean([cols[x] for x in inside]) - np.mean([cols[x] for x in outside])), 1)
    el = edge(range(x0 + 2, x0 + 8), range(x0 - 8, x0 - 2))
    er = edge(range(x0 + w - 8, x0 + w - 2), range(x0 + w + 2, x0 + w + 8)) if "r" in sides else None
    return {"edge_step_left": el, "edge_step_right": er, "left_tile": round(left, 1), "window_tile": round(win, 1), "right_tile": None if right is None else round(right, 1),
            "window_vs_neighbours": round(win / max(1e-6, nb), 3),
            "max_column_step": round(float(max(steps)), 1), "median_column_step": round(float(np.median(steps)), 1)}


def measure_all(pngs, sites):
    """The mean of the measurements over the run's shots (shot-game.png and shot2-game.png, 2 s apart: rain and flicker average out)."""
    per = []
    for png in pngs:
        a = np.asarray(Image.open(png).convert("RGB")).astype(np.float64)
        lum = a[..., 0] * 0.2126 + a[..., 1] * 0.7152 + a[..., 2] * 0.0722
        per.append({k: measure(lum, *v) for k, v in sites.items()})
    out = {}
    for k in sites:
        out[k] = {f: (None if per[0][k][f] is None else round(float(np.mean([m[k][f] for m in per])), 3 if f == "window_vs_neighbours" else 1))
                  for f in per[0][k]}
        out[k]["shots"] = len(per)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--box", help="X0,X1,Y0[,BAND,SLOPE]: one site instead of the built-in ones")
    ap.add_argument("--json")
    ap.add_argument("--judge", action="store_true")
    ap.add_argument("--context", default="")
    ap.add_argument("--left-only", action="append", default=[], help="SITE: compare the window tile with its left neighbour only (the right one is lit by something else, e.g. a lamp)")
    ap.add_argument("--noise", action="append", default=[], help="A:B, two runs that should look alike (run-to-run noise)")
    a = ap.parse_args()
    sites = SITES
    if a.box:
        v = [float(t) for t in a.box.split(",")]
        sites = {"box": (int(v[0]), int(v[1] - v[0]), int(v[2]), int(v[3]) if len(v) > 3 else 30, v[4] if len(v) > 4 else -0.5, "lr")}
    for k in a.left_only:
        sites = dict(sites)
        sites[k] = sites[k][:5] + ("l",)
    res = {}
    for r in a.runs:
        d = run_dir(r)
        res[r] = measure_all([p for p in (d / "shot-game.png", d / "shot2-game.png") if p.exists()], sites)
        for k, m in res[r].items():
            print(f"{r:20s} {k:18s} ratio {m['window_vs_neighbours']:.3f}  edges {m['edge_step_left']} / {m['edge_step_right']}  step max {m['max_column_step']:5.1f} median {m['median_column_step']:4.1f}  "
                  f"tiles {m['left_tile']} / {m['window_tile']} / {m['right_tile']}")
    out = {"runs": res}
    if a.judge:
        from typesafe_client import ask, choice, noul, fmt  # noqa: E402
        before, test = a.runs[0], a.runs[-1]
        ref = next((r for r in a.runs if "noppl" in r), None)
        if ref is None:
            sys.exit("--judge needs a pixelLight-off reference run (label with noppl)")

        def gap(r):  # per site: how far a run is from the reference (the defect), window tile vs neighbours and vs the left one
            g = {}
            for k, m in res[r].items():
                rm = res[ref][k]
                g[k] = {"ratio_minus_reference": round(m["window_vs_neighbours"] - rm["window_vs_neighbours"], 3),
                        "window_vs_left_minus_reference": round(m["window_tile"] / max(1e-6, m["left_tile"]) - rm["window_tile"] / max(1e-6, rm["left_tile"]), 3),
                        "max_step_minus_reference": round(m["max_column_step"] - rm["max_column_step"], 1),
                        "edge_step_left_minus_reference": round(m["edge_step_left"] - rm["edge_step_left"], 1),
                        "edge_step_right_minus_reference": None if m["edge_step_right"] is None else round(m["edge_step_right"] - rm["edge_step_right"], 1)}
            return g
        noise = {}
        for p in a.noise:  # pairs of runs that should look the same: their gap is the run-to-run noise (sway, rain, light flicker)
            x, y = p.split(":")
            noise[p] = {k: {"ratio_diff": round(res[x][k]["window_vs_neighbours"] - res[y][k]["window_vs_neighbours"], 3),
                            "max_step_diff": round(res[x][k]["max_column_step"] - res[y][k]["max_column_step"], 1),
                            "edge_step_left_diff": round(res[x][k]["edge_step_left"] - res[y][k]["edge_step_left"], 1),
                            "edge_step_right_diff": None if res[x][k]["edge_step_right"] is None else round(res[x][k]["edge_step_right"] - res[y][k]["edge_step_right"], 1)} for k in res[x]}
        state = {
            "setup": "Screenshots (two per run, 2 s apart, averaged) of the same scene from a copy of the player's save (houses and a shed; several window tiles "
                     "between plain wall tiles, one site each). Luminance (0..255, per pixel column the median over the band: rain streaks drop out) of the wall face in a band beside the window "
                     "(shed: under and above the window; the houses: above the window), per tile: the window tile and its left / "
                     "right neighbours (house_s_window has a door tile on its right: left only). window_vs_neighbours = window "
                     "tile / mean of the neighbours; max_column_step = the biggest jump between 4-px column groups across the "
                     "tiles (it includes fixed scenery inside the band: eave edges, frames, a door edge, a lamp's glow; the same in every run); "
                     "edge_step_left / right = the brightness step right at the window tile's left / right edge (6 px inside "
                     "minus 6 px outside, signed): the column's own signature, strongly negative at both edges when the tile "
                     "is a dark column. "
                     "The defect is the difference from the reference: `gap_*` = run minus reference per site; `noise` = the "
                     f"same differences between runs that should look alike. `before` = {before} (the reported behaviour), "
                     f"`reference` = {ref} (per-pixel lighting off: the wall as it should look), `test` = {test} (the fix). "
                     + a.context,
            "report": "on window tiles (the shed is the reported one) the wall face is a full-height darker vertical column (the "
                      "window does not get proper shading vertically), with a thin coloured strip on its right edge",
            "measurements": res, "gap_before": gap(before), "gap_test": gap(test), "noise": noise,
        }
        questions = {
            "before_shows_issue": noul("Does `before` show the reported column: at the reported shed sites a window tile clearly "
                                       "darker than the reference (gap_before far outside the noise)?"),
            "test_fixed": noul("Is the column completely gone in `test`: at every site, is gap_test within the run-to-run noise or "
                               "otherwise explained by the context (not a darker window tile)?"),
            "verdict": choice({"question": "Verdict on the fix"},
                              {"eliminated": "the column and its edge strip are gone in test",
                               "reduced": "smaller but still there",
                               "not_fixed": "test still shows the column",
                               "inconclusive": "the numbers do not decide it"}),
        }
        log = []
        answers = ask(state, questions, log=log)
        print("jev:")
        print("  " + fmt(answers).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
        out.update({"state": state, "answers": answers, "typesafe": log[0]})
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
