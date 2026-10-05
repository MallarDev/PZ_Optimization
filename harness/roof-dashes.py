#!/usr/bin/env python3
"""White puddle dashes on flat roofs (2026-10-05, Discord "Flickering textures on white roofs when it's raining": the
GigaMart / car wash roofs in West Point). The puddle pass z-fought the roof's floor and lit the corrugation in short
horizontal white dashes that came and went from frame to frame; stock shows none.

    harness/roof-dashes.py <run> [<run> ...] [--crop x,y,w,h] [--json f]
    harness/roof-dashes.py --test <run> [--before <run>] --control <stock run> [--json f] [--no-jev]

Per run (a devCapture of the roof rig, colour or gray): per frame the pixels that are > 40/255 brighter than the pixels two
rows above and below and sit in a horizontal run of >= 6 such pixels (a dash; the roof's own ridges are diagonal, rain
streaks are vertical, ground sparkles are dots), and how many of them were not a dash in the previous frame (dash flips).
Prints mean / p90 / max dash px per frame, frames with >= 200 dash px, flips per frame. With --test / --control (and
--before) Jev gets the numbers (text only) and says whether `test` still flickers; exit 0 = fixed or no flicker.
Rig: --mode bench --launcher direct --source-save Sandbox/2026-10-02_10-30-09 --flag start=12032,6904 --flag weather=clear|rain
--flag puddles=1 --flag explore=circle [--flag director=jev] --flag circle_spots=0 --flag circle_radius=2.5 --flag zombies=off
--flag zoom=max --flag route=S:1 --flag speed=0.06 --route-seconds 17 --prop devCapture=7,10,60,100,crop=2900:80:1500:760,ram
(the GigaMart roof, West Point); before = --prop puddleJiggleDepth=false --prop swayFloorExact=false, control --prop enabled=false.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))


def frames(run):
    cap = Path(run) / "capture"
    idx = (cap / "index.txt").read_text().split()
    hdr = dict(kv.split("=") for kv in idx[:3] if "=" in kv)
    w, h = int(hdr["w"]), int(hdr["h"])
    gray = cap / "frames.gray"
    if gray.exists():
        data = np.memmap(gray, dtype=np.uint8, mode="r")
        n = data.size // (w * h)
        for i in range(n):
            yield data[i * w * h:(i + 1) * w * h].reshape(h, w)
    else:
        data = np.memmap(cap / "frames.rgba", dtype=np.uint8, mode="r")
        n = data.size // (w * h * 4)
        for i in range(n):
            f = data[i * w * h * 4:(i + 1) * w * h * 4].reshape(h, w, 4)
            yield (0.299 * f[..., 0] + 0.587 * f[..., 1] + 0.114 * f[..., 2]).astype(np.uint8)


def dashes(g):
    g = g.astype(np.int16)
    up, dn = np.roll(g, 2, 0), np.roll(g, -2, 0)
    m = (g - np.maximum(up, dn)) > 40
    # horizontal runs >= 6: erode with a 6-wide window, then dilate back
    k = 6
    c = np.cumsum(np.pad(m, ((0, 0), (1, 0))).astype(np.int32), axis=1)
    full = (c[:, k:] - c[:, :-k]) == k  # window starting at x is all set
    out = np.zeros_like(m)
    for o in range(k):
        out[:, o:o + full.shape[1]] |= full
    out[:2] = out[-2:] = False
    return out


def measure(run, crop=None):
    counts, flips, prev = [], [], None
    for g in frames(run):
        if crop:
            x, y, w, h = map(int, crop.split(","))
            g = g[y:y + h, x:x + w]
        d = dashes(g)
        counts.append(int(d.sum()))
        if prev is not None:
            flips.append(int((d & ~prev).sum()))
        prev = d
    c = np.array(counts)
    return {"frames": len(c), "dash_px_mean": round(float(c.mean()), 1), "dash_px_p90": int(np.percentile(c, 90)),
            "dash_px_max": int(c.max()), "frames_ge_200_pct": round(100.0 * float((c >= 200).mean()), 1),
            "new_dash_px_per_frame": round(float(np.mean(flips)) if flips else 0.0, 1)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="*")
    ap.add_argument("--test")
    ap.add_argument("--before")
    ap.add_argument("--control")
    ap.add_argument("--crop")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    named = [(k, r) for k, r in (("test", a.test), ("before", a.before), ("control", a.control)) if r]
    res = {}
    for k, run in [(Path(r).name, r) for r in a.runs] + named:
        r = measure(run, a.crop)
        res[k] = dict(r, run=str(run)) if k in ("test", "before", "control") else r
        print(f"{k:40s} " + " ".join(f"{kk}={v}" for kk, v in r.items()))
    out = {"metrics": res}
    code = 0
    if a.test and a.control and not a.no_jev:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from typesafe_client import ask, choice, fmt, noul  # noqa: E402
        state = {
            "setup": "In-game frame captures (1:1, 60 fps) of the same scene: the character walks circles in a parking lot at the "
                     "widest zoom, puddles at full size, a supermarket's white corrugated flat roof in view. The reported artefact: "
                     "the puddle drawn on the roof and the roof trade places, so short white horizontal dashes along the "
                     "corrugation ridges appear over whole chunk-sized parts of the roof in one frame and are gone in the next. "
                     "dash_px = pixels per frame in such dashes (> 40 brighter than two rows above and below, in a horizontal run "
                     ">= 6 px; rain streaks are vertical, the roof's own ridges diagonal, ground sparkles dots); "
                     "frames_ge_200_pct = share of frames with >= 200 dash px; new_dash_px_per_frame = dash px not there the frame "
                     "before. A few dozen dash px a frame are the scene's own edges (the stock game has them too). `control` is the "
                     "stock game; `before` the build without the fix; `test` the fix.",
            "report": "flickering textures on white roofs when it's raining (Discord, West Point GigaMart and car wash roofs)",
            **{k: v for k, v in res.items() if k in ("test", "before", "control")},
        }
        questions = {
            "test_flickers": noul("Does `test` show the reported dash flicker on the roof (many dash px in many frames, well above `control`)?"),
            "before_flickers": noul("If `before` is given: does it show that flicker?"),
            "control_flickers": noul("Does `control` (stock) show that flicker?"),
            "verdict": choice({"question": "What best describes `test`?"},
                              {"fixed": "`before` flickers and `test` is like `control`: dash px at the stock level, no frames with hundreds of dash px",
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
