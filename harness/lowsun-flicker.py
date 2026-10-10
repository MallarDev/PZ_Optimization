#!/usr/bin/env python3
"""Low-sun shadow flicker (2026-10-07, the maintainer's "at sunrise and sunset the shadows of trees and buildings are too long,
cut off, and the picture flickers between bright and dark"): on a still camera, how much of the picture brightens and then
darkens back (or the reverse) within a second or two, i.e. shadows that vanish and come back instead of stepping once.

Input: run dirs with a gray pzopt.FrameCapture of a still camera (`--flag route=E:0 --flag hold=N`, e.g.
`--prop devCapture=6,14,30,25,gray`), usually with the sun swept (`--prop devSunHour=19.6 --prop devSunHourSpeed=0.03`: a
1.5 deg sun step every ~3 s). The screen is cut into 16 x 16 px blocks (the overlay column on the right and the hotbar
masked); a block's mean luma is a series. A reversal: the block moves by >= --thr in one direction within --rise frames and
then by >= --thr back within the next --back frames (a shadow gone for a while, then back). Foliage sway is per pixel and
averages out in a block. Also: the whole picture's mean luma, its largest one-second swing and its overshoots.

Usage: lowsun-flicker.py RUN [RUN ...] [--json out.json] [--judge TEST CONTROL [--before RUN]] [--context "..."]
With --judge, Jev answers over the numbers whether TEST still shows the vanish-and-return flicker against CONTROL
(sunShadows=false or the same scene without the step) and BEFORE.
"""
import argparse
import json
import os
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))


def load(run):
    d = os.path.join(run, "capture")
    meta = open(os.path.join(d, "index.txt")).readline().split()
    kv = dict(x.split("=") for x in meta)
    w, h = int(kv["w"]), int(kv["h"])
    raw = np.fromfile(os.path.join(d, "frames.gray"), dtype=np.uint8)
    n = raw.size // (w * h)
    stamps = [int(x) for x in open(os.path.join(d, "index.txt")).read().split()[3:] if x.isdigit()]
    return raw[: n * w * h].reshape(n, h, w)[:, ::-1, :], stamps


def analyse(run, thr, rise, back, skip=4.0):
    f, stamps = load(run)
    k0 = int(round(skip * 30.0))  # the capture's first frames: the world settling (first bakes, the first sun term)
    f, stamps = f[k0:], stamps[k0:]
    n, h, w = f.shape
    W = int(w * 0.84) // 16 * 16  # the overlay column on the right
    H = int(h * 0.88) // 16 * 16  # the hotbar and the overlay's frame graph
    b = f[:, :H, :W].astype(np.float32).reshape(n, H // 16, 16, W // 16, 16).mean((2, 4))
    # the overlay's box (bottom right: its numbers change every frame) as the series of the block left of it, so it never moves
    by0, bx0 = int(h * 0.66) // 16, int(w * 0.70) // 16
    b[:, by0:, bx0:] = b[:, by0:, bx0 - 1:bx0]
    nb = b.shape[1] * b.shape[2] - (b.shape[1] - by0) * (b.shape[2] - bx0)
    rev = np.zeros(n, np.int64)  # blocks whose reversal starts at frame t
    events = 0
    for t in range(n - 1):
        hi = min(n, t + rise + 1)
        # the extreme move within `rise` frames, then the way back within `back` frames
        seg = b[t + 1:hi] - b[t]
        up = seg.max(0)
        dn = seg.min(0)
        k_up = seg.argmax(0) + t + 1
        k_dn = seg.argmin(0) + t + 1
        cand_up = up >= thr
        cand_dn = dn <= -thr
        r = np.zeros(cand_up.shape, bool)
        if cand_up.any():
            ys, xs = np.nonzero(cand_up)
            for y, x in zip(ys, xs):
                k = k_up[y, x]
                tail = b[k + 1:min(n, k + back + 1), y, x]
                if tail.size and b[k, y, x] - tail.min() >= thr:
                    r[y, x] = True
        if cand_dn.any():
            ys, xs = np.nonzero(cand_dn)
            for y, x in zip(ys, xs):
                k = k_dn[y, x]
                tail = b[k + 1:min(n, k + back + 1), y, x]
                if tail.size and tail.max() - b[k, y, x] >= thr:
                    r[y, x] = True
        rev[t] = r.sum()
    # a block counts once per reversal: take frames where reversals start and suppress the following rise window
    seen = np.zeros(b.shape[1:], np.int64) - 10_000
    blocks = 0
    for t in range(n - 1):
        if rev[t] == 0:
            continue
        hi = min(n, t + rise + 1)
        seg = b[t + 1:hi] - b[t]
        mv = np.abs(seg).max(0) >= thr
        fresh = mv & (t - seen > rise + back)
        blocks += int(fresh.sum())
        seen[fresh] = t
    win3 = np.convolve(rev, np.ones(5, np.int64), mode="same")  # reversals starting within +-2 frames
    coh_mask = win3 >= 0.005 * nb
    coh = rev[coh_mask].sum()
    coh_frames = coh_mask.sum()
    m = f[:, :H, :W].reshape(n, -1).mean(1)
    fps = 30.0
    if len(stamps) > 2:
        fps = 1000.0 * (len(stamps) - 1) / max(1, stamps[-1] - stamps[0])
    sec = max(1, int(round(fps)))
    swings = [float(m[i:i + sec].max() - m[i:i + sec].min()) for i in range(0, max(1, n - sec), sec // 2 or 1)]
    # overshoots of the whole picture: a local extreme that comes back by >= 0.5 luma within back frames
    over = []
    for t in range(1, n - 1):
        lo, hi = max(0, t - 3 * rise), min(n, t + back + 1)
        if m[t] == m[lo:hi].max() and m[t] - m[lo] >= 0.5 and m[t] - m[t:hi].min() >= 0.5:
            over.append({"t_s": round(t / fps, 2), "up": round(float(m[t] - m[lo]), 2), "back": round(float(m[t] - m[t:hi].min()), 2)})
        if m[t] == m[lo:hi].min() and m[lo] - m[t] >= 0.5 and m[t:hi].max() - m[t] >= 0.5:
            over.append({"t_s": round(t / fps, 2), "down": round(float(m[lo] - m[t]), 2), "back": round(float(m[t:hi].max() - m[t]), 2)})
    secs = n / fps
    # patchwork pops: a block steps (a stable level before, another after, >= thr apart) at frame t; a sun step's recompute
    # wave is the run of frames where blocks step (gaps under 0.5 s); while it lasts, the picture is a patchwork of chunk
    # textures under the old and the new shadows
    k = 4
    steps = np.zeros(n, np.int64)
    if n > 2 * k + 2:
        win = np.lib.stride_tricks.sliding_window_view(b, k, axis=0)  # (n-k+1, by, bx, k)
        med = np.median(win, axis=-1)
        spread = win.max(-1) - win.min(-1)
        jump = np.zeros_like(b)
        ok = np.zeros(b.shape, bool)
        for t in range(k, n - k):
            bef, aft = med[t - k], med[t + 1]
            jump[t] = np.abs(aft - bef)
            ok[t] = (spread[t - k] < 0.6 * thr) & (spread[t + 1] < 0.6 * thr) & (jump[t] >= thr)
        for t in range(k, n - k):
            lo, hi = max(0, t - 3), min(n, t + 4)
            peak = ok[t] & (jump[t] >= jump[lo:hi].max(0))
            steps[t] = int(peak.sum())
    waves = []
    cur = None
    gap = max(1, int(0.5 * fps))
    for t in range(n):
        if steps[t] == 0:
            continue
        if cur is None or t - cur["last"] > gap:
            cur = {"first": t, "last": t, "frames": []}
            waves.append(cur)
        cur["last"] = t
        cur["frames"].append((t, int(steps[t])))
    wave_out = []
    patch_frames = 0
    for wv in waves:
        tot = sum(c for _, c in wv["frames"])
        if tot < 0.005 * nb:
            continue  # foliage and the walking character's neighbourhood: not a wave
        acc = 0
        pf = 0
        frames_at = dict(wv["frames"])
        for t in range(wv["first"], wv["last"] + 1):
            acc += frames_at.get(t, 0)
            if 0.1 * tot <= acc < 0.9 * tot:
                pf += 1
        if tot >= 0.1 * nb:  # a wave over a tenth of the picture or more: a patchwork a player sees
            patch_frames += pf
        wave_out.append({"t_s": round(wv["first"] / fps, 2), "share_of_picture_pct": round(100 * tot / nb, 1),
                         "duration_s": round((wv["last"] - wv["first"] + 1) / fps, 2), "patchwork_frames": pf,
                         "max_share_in_one_frame_pct": round(100 * max(c for _, c in wv["frames"]) / nb, 1)})
    pops = sum(w["share_of_picture_pct"] for w in wave_out)
    # abrupt: blocks that move >= thr between two consecutive captured frames (a patchwork wave or a one-frame pop of the
    # whole picture; a step eased in over ~0.5 s moves a block a fraction of that per frame)
    ab = (np.abs(np.diff(b, axis=0)) >= thr).reshape(n - 1, -1).sum(1)
    return {
        "run": os.path.basename(os.path.normpath(run)), "frames": n, "fps": round(fps, 1), "seconds": round(secs, 1),
        "blocks": nb, "reversal_blocks": blocks, "reversal_blocks_per_10s": round(10 * blocks / secs, 1),
        # reversals many blocks share in one moment (a shadow step that vanishes and comes back); scattered ones are leaves
        # swaying in the wind (shown in sunlight and shade, they reverse with sun shadows on whatever the steps do)
        "coherent_reversal_share_per_10s_pct": round(1000 * float(coh) / secs / nb, 2),
        "coherent_reversal_frames": int(coh_frames),
        "reversal_share_of_picture_per_10s_pct": round(1000 * blocks / secs / nb, 2),
        "worst_frame_reversal_pct": round(100 * float(rev.max()) / nb, 2),
        "mean_luma_first_last": [round(float(m[0]), 1), round(float(m[-1]), 1)],
        "max_1s_mean_luma_swing": round(max(swings), 2),
        "picture_overshoots": over[:20], "picture_overshoot_count": len(over),
        "abrupt_share_of_picture_per_10s_pct": round(1000 * float(ab.sum()) / secs / nb, 1),
        "worst_frame_abrupt_pct": round(100 * float(ab.max()) / nb, 1),
        "frames_over_5pct_abrupt": int((ab > 0.05 * nb).sum()),
        "pop_waves": len(wave_out), "popped_share_of_picture_per_10s_pct": round(10 * pops / secs, 1),
        "patchwork_seconds_per_10s": round(10 * patch_frames / fps / secs, 2),
        "longest_wave_s": max([w["duration_s"] for w in wave_out], default=0.0), "waves": wave_out[:12],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="*")
    ap.add_argument("--thr", type=float, default=3.0)
    ap.add_argument("--rise", type=int, default=12)
    ap.add_argument("--back", type=int, default=45)
    ap.add_argument("--json")
    ap.add_argument("--judge", nargs=2, metavar=("TEST", "CONTROL"))
    ap.add_argument("--before")
    ap.add_argument("--context", default="")
    ap.add_argument("--skip", type=float, default=4.0, help="seconds of the capture skipped (settling)")
    a = ap.parse_args()
    out = {}
    for r in a.runs:
        out[r] = analyse(r, a.thr, a.rise, a.back, a.skip)
        print(json.dumps(out[r]))
    if a.judge:
        from typesafe_client import ask, choice, noul, fmt  # noqa: E402
        def view(r):
            # what the judge reads: scattered reversals (isolated blocks: leaves swaying in sunlight, 2026-10-07) under their
            # own name, not as shadow reversals; the coherent ones are the flicker's signature
            v = dict(r)
            v["scattered_reversals_leaves_in_wind_per_10s_pct"] = v.pop("reversal_share_of_picture_per_10s_pct")
            for k in ("reversal_blocks", "reversal_blocks_per_10s", "worst_frame_reversal_pct", "waves"):
                v.pop(k, None)
            return v
        test = analyse(a.judge[0], a.thr, a.rise, a.back, a.skip)
        control = analyse(a.judge[1], a.thr, a.rise, a.back, a.skip)
        before = analyse(a.before, a.thr, a.rise, a.back, a.skip) if a.before else None
        state = {
            "setup": "Frame-exact captures (every presented frame, 30 fps) of the same still camera over a church and its parking "
                     "lot at sunset, the sun swept from 12 to 5 degrees above the horizon (a shadow step every ~3 s). The picture "
                     "is cut into 16x16 px blocks; a reversal is a block that brightens or darkens by >= 3/255 and then goes back by "
                     ">= 3/255 within 1.5 s (a shadow that vanishes and comes back). A sun step itself moves blocks once (no "
                     "reversal). Abrupt: blocks moving >= 3/255 between two consecutive frames (a wave of chunk-shaped patches "
                     "or the whole picture jumping in one frame), what a player sees as flicker; a change eased in over half a "
                     "second is not abrupt. Coherent reversals: reversals shared by >= 0.5 % of the picture within 5 frames (a "
                     "shadow vanishing and coming back); scattered reversals are leaves swaying in the wind. `control` = the same scene without the problem; `before` = the build the player reported. "
                     + a.context,
            "report": "at sunrise and sunset the long shadows of trees and buildings are cut off and the picture flickers between "
                      "bright and dark",
            "test": view(test), "control": view(control),
        }
        if before:
            state["before"] = view(before)
        print(json.dumps(state, indent=1))
        qs = {
            "test_flickers": noul("Does `test` show the bright/dark flicker: abrupt changes (abrupt share per 10 s, frames over 5 % "
                                  "abrupt), coherent reversals or picture overshoots well above `control`?"),
            "verdict": choice({"question": "Verdict on `test`" + (" compared with `before`" if before else "")},
                              {"fixed": "test has no abrupt changes, coherent reversals or patchwork beyond the control while before (if given) does",
                               "still_flickers": "test still shows reversals / overshoots well above the control",
                               "flicker_confirmed": "no before given and test flickers well above the control",
                               "no_flicker": "neither test nor control flickers",
                               "inconclusive": "metrics contradict or too few frames"}),
        }
        if before:
            qs["before_flickers"] = noul("Does `before` show the flicker (reversals / overshoots well above control)?")
        log = []
        ans = ask(state, qs, log=log)
        print("jev:")
        print("  " + fmt(ans).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
        out["judge"] = {"state": state, "answers": ans}
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    main()
