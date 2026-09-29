#!/usr/bin/env python3
"""Region A/B/C judge for depth-texture artifacts (issue #38 follow-up, 2026-09-29).

Three pixel-aligned --shot-at captures of one camera position: TEST (the change on), BASE (the change off, same features)
and REF (the features that read the depth off: what the objects should look like). For each named screen region (the
objects under suspicion) and each control region (plain surfaces nearby) code measures the luminance against REF: mean
ratio, the share of pixels 25+ levels darker / brighter than REF, the per-row spread of the ratio (dark bands across an
object), and TEST vs BASE (what the change itself moved). Jev reads only those numbers and answers whether the suspect
regions show artifacts from the features, and whether the change introduced, removed or left them.

usage: region-judge.py --test <run> --base <run> --ref <run> --region name:x,y,w,h ... --control name:x,y,w,h ...
                       [--context "..."] [--json out] [--no-jev]
"""
import argparse, glob, json, os, sys
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


def luma(run):
    d = run if os.path.isdir(run) else sorted(glob.glob(f"harness/runs/{run}-*"))[-1]
    a = np.asarray(Image.open(os.path.join(d, "shot-game.png")).convert("RGB"), dtype=np.float32)
    return d, 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]


def parse(spec):
    name, box = spec.split(":", 1)
    x, y, w, h = (int(v) for v in box.split(","))
    return name, (x, y, w, h)


def stats(img, ref, box):
    x, y, w, h = box
    a, r = img[y:y + h, x:x + w], ref[y:y + h, x:x + w]
    rows = (a.mean(axis=1) + 1) / (r.mean(axis=1) + 1)
    return {
        "mean_ratio_vs_ref": round(float((a.mean() + 1) / (r.mean() + 1)), 3),
        "darker_25_share": round(float(((r - a) > 25).mean()), 3),
        "brighter_25_share": round(float(((a - r) > 25).mean()), 3),
        "row_ratio_spread": round(float(rows.max() - rows.min()), 3),
        "min_row_ratio": round(float(rows.min()), 3),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", required=True)
    ap.add_argument("--base", required=True)
    ap.add_argument("--ref", required=True)
    ap.add_argument("--region", action="append", default=[])
    ap.add_argument("--control", action="append", default=[])
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    td, T = luma(a.test)
    bd, B = luma(a.base)
    rd, R = luma(a.ref)
    card = {"runs": {"test": td, "base": bd, "ref": rd}, "context": a.context, "suspect_regions": {}, "control_regions": {}}
    for kind, specs in (("suspect_regions", a.region), ("control_regions", a.control)):
        for spec in specs:
            name, box = parse(spec)
            x, y, w, h = box
            card[kind][name] = {
                "box": box,
                "test_vs_ref": stats(T, R, box),
                "base_vs_ref": stats(B, R, box),
                "test_vs_base_mean_abs_diff": round(float(np.abs(T[y:y + h, x:x + w] - B[y:y + h, x:x + w]).mean()), 2),
            }
    print(json.dumps(card, indent=1))
    if not a.no_jev:
        from typesafe_client import ask, noul, choice, fmt
        q = {
            "features_cause_artifacts": noul(
                "Each suspect region is an object the user reported artifacts on. With the depth-reading features on (both "
                "`test_vs_ref` and `base_vs_ref`), do the suspect regions depart from the reference clearly more than the "
                "control regions do: many more pixels 25+ levels darker, a lower mean ratio, a larger row_ratio_spread (dark "
                "bands across the object)? Controls show how much the features change a plain surface."),
            "change_affects_regions": noul(
                "Does the change under test (test vs base) move the suspect regions by clearly more than it moves the "
                "controls (`test_vs_base_mean_abs_diff`, and test_vs_ref differing from base_vs_ref)? A few tenths of a level "
                "is capture noise."),
            "verdict": choice("What best describes the suspect regions?", {
                "pre_existing": "artifacts are present with the features on, the same with and without the change",
                "introduced_by_change": "artifacts appear or grow only with the change on",
                "fixed_by_change": "artifacts present without the change, gone or much smaller with it",
                "no_artifact": "the suspect regions behave like the controls",
                "inconclusive": "the numbers do not support any of these",
            }),
        }
        ans = ask(card, q)
        print(fmt(ans))
        card["jev"] = ans
    if a.json:
        json.dump(card, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
