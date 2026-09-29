#!/usr/bin/env python3
"""Flicker inside one screen crop of several devCapture sequences of the same walk, and Jev's verdict on it (2026-09-29, the
Fossoil shelf goods: after pplJiggle the solid black blinks were gone and a per-frame speckle was left, which dark-blink.py's
near-black rule did not count and region-flicker.py's player disc masked).

    harness/crop-flicker.py --test <run> [--control <run>] [--stock <run>] [--crop x,y,w,h] [--judge] [--out f.json] [--strips dir]

Per run: the pixels of the crop (capture pixels, top-left origin) that change by more than 24/255 and come back within 12 on
the next frame (A-B-A), per frame; how many darker / brighter; frames with >= 20 such pixels; the worst frame. `--strips`
writes the worst frame with its neighbours, 3x. `--judge` hands the numbers to Jev (text only): does `test` still flicker
there compared with `stock` (and `control`, the known-bad build)? Crop default: the goods behind the Fossoil counter in the
`crop=3000:1150:900:650` capture.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))


def load(run):
    cap = Path(run) / "capture"
    idx = (cap / "index.txt").read_text().split()
    hdr = dict(kv.split("=") for kv in idx[:3] if "=" in kv)
    w, h = int(hdr["w"]), int(hdr["h"])
    gray = cap / "frames.gray"
    if gray.exists():
        d = np.memmap(gray, dtype=np.uint8, mode="r")
        return d[: d.size // (w * h) * w * h].reshape(-1, h, w), w, h
    d = np.memmap(cap / "frames.rgba", dtype=np.uint8, mode="r")
    return d[: d.size // (w * h * 4) * w * h * 4].reshape(-1, h, w, 4), w, h


def measure(run, crop, strips=None, tag=""):
    f, w, h = load(run)
    x, y, cw, ch = crop
    def luma(i):
        a = f[i, h - y - ch:h - y][::-1, x:x + cw]
        return (a[..., :3].astype(np.float32).mean(axis=2) if a.ndim == 3 else a.astype(np.float32))
    n = len(f)
    px, dn, up = np.zeros(n), np.zeros(n), np.zeros(n)
    prev, cur = luma(0), luma(1)
    for i in range(1, n - 1):
        nxt = luma(i + 1)
        m = (np.abs(cur - prev) > 24) & (np.abs(cur - nxt) > 24) & (np.abs(prev - nxt) < 12)
        px[i], dn[i], up[i] = m.sum(), (m & (cur < prev)).sum(), (m & (cur > prev)).sum()
        prev, cur = cur, nxt
    worst = int(np.argmax(px))
    res = {"frames": n, "crop_px": cw * ch, "flip_px_per_frame": round(float(px.mean()), 1),
           "flip_share_of_crop_pct": round(100 * float(px.mean()) / (cw * ch), 3),
           "darker_px_per_frame": round(float(dn.mean()), 1), "brighter_px_per_frame": round(float(up.mean()), 1),
           "frames_with_20px_or_more_pct": round(100 * float((px >= 20).mean()), 1),
           "frames_with_200px_or_more_pct": round(100 * float((px >= 200).mean()), 1),
           "worst_frame_px": int(px.max())}
    if strips:
        from PIL import Image
        i = min(max(worst, 1), n - 2)
        def rgb(j):
            a = f[j, h - y - ch:h - y][::-1, x:x + cw]
            return a[..., :3] if a.ndim == 3 else np.repeat(a[..., None], 3, axis=2)
        s = np.concatenate([rgb(j) for j in (i - 1, i, i + 1)], axis=1)
        Path(strips).mkdir(parents=True, exist_ok=True)
        Image.fromarray(np.ascontiguousarray(s)).resize((s.shape[1] * 3, s.shape[0] * 3), Image.NEAREST).save(f"{strips}/{tag}.png")
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", required=True)
    ap.add_argument("--control")
    ap.add_argument("--stock")
    ap.add_argument("--crop", default="380,240,200,190")
    ap.add_argument("--judge", action="store_true")
    ap.add_argument("--out")
    ap.add_argument("--strips")
    a = ap.parse_args()
    crop = [int(v) for v in a.crop.split(",")]
    runs = {"test": a.test, "control": a.control, "stock": a.stock}
    state = {k: measure(v, crop, a.strips, k) for k, v in runs.items() if v}
    for k, v in state.items():
        print(f"{k:8} {runs[k]}\n         {v}")
    out = {"crop": a.crop, "runs": runs, "numbers": state}
    if a.judge:
        from typesafe_client import ask, choice, noul, fmt
        jstate = {
            "setup": "Frame-exact in-game captures (every presented frame) of the same walk: a copy of the player's save at "
                     "night, the character walking circles outside a store, the camera following. The numbers cover one crop: "
                     "the goods on the shelves behind the store counter. A flip pixel changes by more than 24/255 and comes "
                     "back on the next frame (a one-frame blink or speckle); steady camera motion is not counted. `stock` = "
                     "the game without the mod; `control` = the mod's build with the known flicker bug; `test` = the build "
                     "with the fix.",
            "report": "the player sees the goods on the shelves flicker while walking in circles",
            **state,
        }
        qs = {
            "test_flickers": noul("Does `test` still show flicker on the goods that `stock` does not have: clearly more flip "
                                  "pixels per frame or more frames with flips than `stock`, beyond a small difference a "
                                  "player would not notice?"),
            "kind": choice("Compare `test` with `stock` (and `control` if given) on the goods.",
                           {"fixed": "test is at stock's level: no flicker left a player would notice",
                            "reduced": "test flickers much less than control but clearly more than stock",
                            "unchanged": "test flickers about as much as control",
                            "inconclusive": "too few frames or contradicting numbers"}),
        }
        log = []
        ans = ask(jstate, qs, log=log)
        print("jev:\n  " + fmt(ans).replace("\n", "\n  "))
        out["jev"] = ans
    if a.out:
        Path(a.out).write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
