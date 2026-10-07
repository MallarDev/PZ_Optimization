#!/usr/bin/env python3
"""Do the long sun shadows still end at a reach? (2026-10-07, "fix the shadows end")

A shadow longer than the march that draws it ends where the march stops. If raising the reach no longer changes the
picture, every shadow at that sun height already reaches its caster's tip. Visibility screenshots (`--prop devAoView=1
--prop devSunView=11`, the sun's visibility alone, + the plain-view props of shadow-seams.py) of one still camera at one
sun height, with different `sunShadowFarSquares`, compared on the ground (`--z`, a `devSunView=5` shot: height 0).

Usage: shadow-reach.py --z RUN --ref RUN (the longest reach) --cmp RUN [--cmp RUN ...] [--judge --context "..."]
For each --cmp: the ground's mean |visibility change| against --ref, the share changing by > 0.05 (shadow there in one and
not the other), and the share where --ref is darker (shadow --cmp misses: cut off).
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--z", required=True)
    ap.add_argument("--ref", required=True)
    ap.add_argument("--cmp", action="append", required=True)
    ap.add_argument("--judge", action="store_true")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    a = ap.parse_args()
    Z = gray(a.z)
    R = gray(a.ref)
    h, w = R.shape
    keep = np.zeros_like(R, bool)
    keep[int(h * 0.04):int(h * 0.88), int(w * 0.02):int(w * 0.98)] = True
    keep &= Z < 0.5 / 255.0
    out = {"ref": os.path.basename(os.path.normpath(a.ref)), "ground_px": int(keep.sum())}
    for c in a.cmp:
        C = gray(c)
        d = (C - R)[keep]
        out[os.path.basename(os.path.normpath(c))] = m = {
            "mean_abs_change": round(float(np.abs(d).mean()), 4),
            "changed_share_pct": round(100.0 * float((np.abs(d) > 0.05).mean()), 2),
            "missing_shadow_share_pct": round(100.0 * float((d > 0.05).mean()), 2),  # lit here, shadow in the ref
        }
        print(os.path.basename(os.path.normpath(c))[:24], json.dumps(m))
    if a.judge:
        from typesafe_client import ask, choice, noul, fmt  # noqa: E402
        state = {
            "setup": "Sun-visibility images (1 lit, 0 in shadow) of the ground under one still camera at sunset, rendered with "
                     "different shadow reaches. `ref` uses the longest reach the renderer has. For each other image: how much "
                     "of the ground changes against ref, and how much is lit where ref has shadow (shadow cut off by the "
                     "shorter reach). If a reach shows (almost) the same ground as ref, every shadow at that sun height "
                     "already ends at its caster's tip with that reach. " + a.context,
            "report": "the long shadows of trees and buildings at sunrise / sunset are cut off",
            "results": dict(out),
        }
        print(json.dumps(state, indent=1))
        qs = {
            "released_cut": noul("Does the released reach (the run named with r32) miss a clear share of the shadow ref shows "
                                 "(shadows cut off)?"),
            "fix_converged": noul("Is the fixed default (ref) converged: does the next shorter reach tested change a negligible "
                                  "share of the ground (well under 1 %), so ref's shadows end at their tips?"),
            "verdict": choice({"question": "Verdict on the fix (ref) for the cut-off shadow ends"},
                              {"fixed": "the released reach cuts shadows off and the fix's shadows have converged (reach their tips)",
                               "improved": "the fix shows far more of the shadows but has not converged",
                               "not_fixed": "no clear difference from the released reach",
                               "inconclusive": "the numbers do not allow a verdict"}),
        }
        log = []
        ans = ask(state, qs, log=log)
        print("jev:")
        print("  " + fmt(ans).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
        out["judge"] = {"state": state, "answers": ans}
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
