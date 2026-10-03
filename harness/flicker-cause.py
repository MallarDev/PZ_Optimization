#!/usr/bin/env python3
"""Do a capture's one-frame flicker frames line up with visibility blinks? (2026-10-03, the tile flicker under a carried
lantern with torchSource.)

    flicker-cause.py <run> [--min-px 2000] [--window-ms 12] [--no-jev] [--json f]

With `pzopt-pplframes.out` in the run (`--prop devPplFrameLog=true`, PixelLight.FrameLog) every flicker frame is matched to
the pixelLight frame the render thread drew last before the capture read it back, and that frame's state (lights, carried
lights, merges, lattice blocks packed, chunks, frames in flight, times drawn) is listed beside its neighbours' and the run's
typical values; Jev says which of them sets the flicker frames apart.

Reads a full-rate `devCapture` (gray or colour, `<run>/capture`) and the console's `vis blink:` lines
(`--prop devVisBlinkTrace=true`, pzopt.VisBlink: squares whose native visibility bits changed and changed back within 3
frames). A flicker frame has >= --min-px pixels that jump by >= 32/255 against both neighbours while the neighbours agree
(< 16), outside a disc round the screen centre (the player's own animation). For each flicker frame: the nearest blink
event and whether one is within --window-ms; the same for every frame (the chance rate). Jev (numbers only) answers
whether the blinks explain the flicker.
"""
import argparse
import glob
import json
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def frames(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    stamps = [int(x) for x in lines[1:] if x.strip()]
    gray = head.get("fmt") == "gray"
    raw = np.memmap(os.path.join(d, "frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(stamps), raw.size // (w * h * (1 if gray else 4)))

    def get(i):
        if gray:
            return np.asarray(raw[i * w * h:(i + 1) * w * h]).reshape(h, w)[::-1].astype(np.int16)
        f = np.asarray(raw[i * w * h * 4:(i + 1) * w * h * 4]).reshape(h, w, 4)[::-1, :, :3].astype(np.float32)
        return (f @ np.array([0.299, 0.587, 0.114], np.float32)).astype(np.int16)
    return get, stamps[:n], w, h


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--min-px", type=int, default=2000)
    ap.add_argument("--window-ms", type=int, default=12)
    ap.add_argument("--no-jev", action="store_true")
    ap.add_argument("--json")
    a = ap.parse_args()
    get, st, w, h = frames(a.run)
    yy, xx = np.mgrid[0:h, 0:w]
    keep = (xx - w / 2) ** 2 + (yy - h / 2) ** 2 > (min(w, h) * 0.12) ** 2
    text = "".join(open(c, errors="ignore").read() for c in sorted(glob.glob(os.path.join(a.run, "console*.txt"))))
    blinks = [(int(m.group(1)), int(m.group(2)), int(m.group(3))) for m in
              re.finditer(r"vis blink: epoch_ms=(\d+) frame=\d+ blinks=(\d+) canSee=(\d+)", text)]
    bt = np.array([b[0] for b in blinks], np.int64) if blinks else np.zeros(0, np.int64)
    bcs = np.array([b[2] for b in blinks], np.int64) if blinks else np.zeros(0, np.int64)

    def near(t, cansee=False):
        if bt.size == 0:
            return None
        sel = bt if not cansee else bt[bcs > 0]
        return int(np.min(np.abs(sel - t))) if sel.size else None

    prev, cur = get(0), get(1)
    events, all_near, all_near_cs = [], 0, 0
    for i in range(1, len(st) - 1):
        nxt = get(i + 1)
        m = (np.abs(cur - prev) > 32) & (np.abs(cur - nxt) > 32) & (np.abs(prev - nxt) < 16) & keep
        px = int(m.sum())
        d, dcs = near(st[i]), near(st[i], True)
        all_near += d is not None and d <= a.window_ms
        all_near_cs += dcs is not None and dcs <= a.window_ms
        if px >= a.min_px:
            events.append({"t_s": round((st[i] - st[0]) / 1000, 3), "px": px, "nearest_blink_ms": d, "nearest_cansee_blink_ms": dcs})
        prev, cur = cur, nxt
    n = len(st) - 2
    hit = sum(1 for e in events if e["nearest_blink_ms"] is not None and e["nearest_blink_ms"] <= a.window_ms)
    hit_cs = sum(1 for e in events if e["nearest_cansee_blink_ms"] is not None and e["nearest_cansee_blink_ms"] <= a.window_ms)
    pf = os.path.join(a.run, "pzopt-pplframes.out")
    if os.path.exists(pf):
        game, rend = {}, []
        for line in open(pf):
            p = line.split()
            if p and p[0] == "g" and len(p) >= 11:
                game[int(p[2])] = dict(zip(("ms", "seq", "lights", "carried", "merged", "blocks", "chunks", "in_flight", "ox", "oy", "capped", "capped_no_torch"), map(int, p[1:13])))
            elif p and p[0] == "r" and len(p) >= 4:
                rend.append((int(p[1]), int(p[2]), int(p[3])))
        rt = np.array([r[0] for r in rend], np.int64)

        def drawn(t):
            i = int(np.searchsorted(rt, t, side="right")) - 1
            return rend[i] if i >= 0 else None
        keys = tuple(k for k in ("lights", "carried", "merged", "blocks", "chunks", "in_flight", "capped", "capped_no_torch") if all(k in g for g in list(game.values())[:1]))
        typical = {k: float(np.median([g[k] for g in game.values()])) if game else None for k in keys}
        typical["times_drawn_share_gt1"] = round(sum(1 for r in rend if r[2] > 1) / max(1, len(rend)), 4)
        for e in events:
            t = st[0] + int(e["t_s"] * 1000)
            d = drawn(t)
            if d is None:
                continue
            seq = d[1]
            e["drawn_seq"], e["times_drawn"], e["drawn_ms_before_capture"] = seq, d[2], t - d[0]
            e["state"] = {k: game[seq][k] for k in keys} if seq in game else None
            e["prev_state"] = {k: game[seq - 1][k] for k in keys} if seq - 1 in game else None
            e["next_state"] = {k: game[seq + 1][k] for k in keys} if seq + 1 in game else None
    else:
        typical = None
    summary = {
        "frames": n, "seconds": round((st[-1] - st[0]) / 1000, 1), "fps": round(n / max(1e-3, (st[-1] - st[0]) / 1000)),
        "flicker_frames": len(events), "flicker_frames_with_blink_within_window": hit,
        "flicker_frames_with_cansee_blink_within_window": hit_cs,
        "window_ms": a.window_ms, "blink_log_lines": len(blinks), "blink_lines_with_cansee": int((bcs > 0).sum()),
        "chance_share_of_all_frames_within_window_of_a_blink": round(all_near / max(1, n), 3),
        "chance_share_within_window_of_a_cansee_blink": round(all_near_cs / max(1, n), 3),
        "typical_frame_state": typical,
        "events": events[:40],
    }
    print(json.dumps(summary, indent=1))
    if not a.no_jev:
        from typesafe_client import ask, choice, noul, fmt
        state = {
            "setup": "A frame-exact capture (every presented frame read back in-game, gray) of a night scene: the character "
                     "stands still and turns slowly with a lit hand torch and a lit lantern; the game draws its light per pixel. "
                     "A flicker frame: a large area changes brightness for exactly one frame. Separately the game logged, per "
                     "frame, squares of the map whose visibility state (seen / can see / could see) changed and changed back "
                     "within 3 frames ('blinks'); 'cansee' blinks flip the can-see bit. 'chance_share' is the share of ALL "
                     "frames that happen to lie within the same window of a blink, the base rate a coincidence would give.",
            "numbers": {k: v for k, v in summary.items() if k != "events"},
            "first_events": summary["events"][:10],
        }
        questions = {
            "explained": noul("Do the flicker frames coincide with visibility blinks far more often than the chance share "
                              "predicts, so the blinks explain the flicker?"),
            "state_differs": noul("Each flicker event lists the light state of the frame that was drawn ('state'), of the frames "
                                  "before and after, and the run's typical values: on the flicker frames, does some field (lights, "
                                  "carried lights after merging, merges, lattice blocks packed, chunks, frames in flight, a frame drawn "
                                  "more than once) differ from its neighbours and from the typical value?"),
            "which_field": choice({"question": "Which field changes on the flicker frames (and back on the next)?"},
                                  {"lights_or_merge": "the light table: lights / carried / merged differ on the flicker frame",
                                   "lattice": "the lattice upload: blocks packed differ (a repack on that frame)",
                                   "replay": "the frame was drawn more than once (a replayed state) or frames in flight jump",
                                   "chunks": "the chunk count differs",
                                   "none": "no field differs: the flicker is not in this state",
                                   "inconclusive": "missing state or too few events"}),
            "cansee_explained": noul("Do they coincide with can-see blinks specifically, beyond chance?"),
            "verdict": choice({"question": "What best explains the flicker frames?"},
                              {"vis_blinks": "the one-frame visibility blinks of squares (the native's vision state)",
                               "other": "something else: the flicker frames do not line up with the blinks beyond chance",
                               "no_flicker": "there are too few flicker frames to tell",
                               "inconclusive": "contradicting or missing numbers"}),
        }
        log = []
        ans = ask(state, questions, log=log)
        print("jev:")
        print("  " + fmt(ans).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
        summary["jev"] = ans
    if a.json:
        open(a.json, "w").write(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()
