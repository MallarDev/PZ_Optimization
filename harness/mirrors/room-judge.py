#!/usr/bin/env python3
"""Jev's verdict on three mirror reports (2026-10-08, the maintainer: "the mirror geometry flickers when the player moves,
reflection panels are visible even when rooms are undiscovered, a mirror reflection is not contained to its own room: the
mirror reflects the character when it is in another room").

Runs: the same Jev walk (`explore=mirror director=jev mirror_corners=pair mirror_laps=2`: the walk starts in the room south
of the mirrors' room, the mirrors' room not seen yet, walks into it and back), `--prop devMirrorsLog=true
--prop devMirrorsRectsEvery=24` and a 1:1 `devCapture` crop holding the room; before = `--prop devMirrorsSkip=62914560`
(the old ways: 4194304 people of every room mirrored, 8388608 unseen panes reflect, 16777216 the geometry's cached light,
33554432 the age refresh), after = the defaults. Per run:

  cross_room          people in front of a mirror plane of another room (the `people in front of another room's mirror`
                      counter) and whether they were mirrored (old way) or not
  unseen_shown        pane-frames of panes the player never saw, and the share whose reflection was shown (`dev pane` lines)
  flicker             per pane, the glass's change between frames beyond its surroundings' (the frame aligned on the wall
                      round the pane, so the camera's pan is taken out): mean and p95 over the walk; the same measure on a
                      patch of plain wall beside the pane is the noise floor
  marches             pane marches over the run (each one resamples the frame)

  python3 harness/mirrors/room-judge.py --before <run> --after <run> [--crop 1660,330] [--panes "x,y,z;..."] [--json f] [--no-jev]
"""
import argparse, json, os, re, sys
import numpy as np


def counters(run):
    txt = open(os.path.join(run, "console.txt"), errors="replace").read()
    last = [l for l in txt.split("\n") if "people in front of another room's mirror" in l]
    s = last[-1] if last else ""
    m = re.search(r"people in front of another room's mirror (\d+) \((mirrored: old way|not mirrored)\)", s)
    u = re.search(r"unseen pane-frames (\d+)", s)
    pm = re.search(r"\((\d+) pane marches", s)
    shown = hidden = 0
    for l in txt.split("\n"):
        mm = re.search(r"mirrors: dev pane mirror [\d,]+: seen false, light [\d.]+, reflection shown ([\d.]+)", l)
        if mm:
            if float(mm.group(1)) > 0.05: shown += 1
            else: hidden += 1
    walk = re.search(r"nav summary: (.*)", txt)
    return {"cross_room_people_frames": int(m.group(1)) if m else None, "cross_room_mirrored": (m.group(2) == "mirrored: old way") if m else None,
            "unseen_pane_frames": int(u.group(1)) if u else None, "unseen_pane_states_shown": shown, "unseen_pane_states_hidden": hidden,
            "pane_marches": int(pm.group(1)) if pm else None, "walk": walk.group(1)[:120] if walk else None}


def flicker(run, crop, keys, shift=0):
    OX, OY = crop
    cap = os.path.join(run, "capture")
    lines = open(os.path.join(cap, "index.txt")).read().split("\n")
    kv = dict(p.split("=") for p in lines[0].split()); w, h = int(kv["w"]), int(kv["h"])
    st = np.array([int(x) for x in lines[1:] if x.strip()]); n = os.path.getsize(os.path.join(cap, "frames.rgba")) // (w * h * 4); st = st[:n]
    mm = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r", shape=(n, h, w, 4))
    txt = open(os.path.join(run, "console.txt"), errors="replace").read()
    walk0 = min(int(m.group(1)) for m in re.finditer(r"mirror walk: to mirror 1 .*?epoch_ms=(\d+)", txt))
    rects = {k: [] for k in keys}
    for line in txt.split("\n"):
        if "dev rects" not in line: continue
        m = re.search(r"epoch_ms=(\d+)", line)
        for r in re.finditer(r"\[\w+ (-?\d+),(-?\d+) (\d+)x(\d+) @([\d,]+)\]", line):
            if r.group(5) in rects: rects[r.group(5)].append((int(m.group(1)), tuple(map(int, r.groups()[:4]))))
    out = {}
    for k in keys:
        if not rects[k]: continue
        rt = np.array([a for a, _ in rects[k]]); res = []; prevf = None
        for i in range(n):
            if st[i] < walk0: prevf = None; continue
            j = int(np.argmin(np.abs(rt - st[i])))
            if abs(rt[j] - st[i]) > 150: prevf = None; continue
            x, y, ww, hh = rects[k][j][1]; x -= OX + shift; y -= OY; M = 50
            if x < M + 40 or y < M + 40 or x + ww > w - M - 40 or y + hh > h - M - 40: prevf = None; continue
            f = np.asarray(mm[i])[::-1][..., :3].astype(np.float32).mean(2)
            if prevf is not None:
                box = f[y - M:y + hh + M, x - M:x + ww + M]
                mask = np.ones_like(box, bool); mask[M + 6:M + hh - 6, M + 6:M + ww - 6] = False
                best = None
                for dy in range(-40, 41, 2):
                    for dx in range(-40, 41, 2):
                        e = np.abs(prevf[y - M + dy:y + hh + M + dy, x - M + dx:x + ww + M + dx] - box)[mask].mean()
                        if best is None or e < best[0]: best = (e, dx, dy)
                _, dx0, dy0 = best
                for dy in range(dy0 - 1, dy0 + 2):
                    for dx in range(dx0 - 1, dx0 + 2):
                        e = np.abs(prevf[y - M + dy:y + hh + M + dy, x - M + dx:x + ww + M + dx] - box)[mask].mean()
                        if e < best[0]: best = (e, dx, dy)
                e, dx, dy = best
                if e < 6:
                    g = f[y + 8:y + hh - 8, x + 8:x + ww - 8]; pg = prevf[y + 8 + dy:y + hh - 8 + dy, x + 8 + dx:x + ww - 8 + dx]
                    res.append(float(np.abs(g - pg).mean() - e))
            prevf = f
        a = np.array(res) if res else np.zeros(1)
        out[k] = {"pairs": len(res), "mean": round(float(a.mean()), 2), "p95": round(float(np.percentile(a, 95)), 2)}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", required=True)
    ap.add_argument("--after", required=True)
    ap.add_argument("--crop", default="1660,330")
    ap.add_argument("--panes", default="8125,11546,2;8131,11546,2;8125,11548,2")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    crop = tuple(int(v) for v in a.crop.split(","))
    keys = a.panes.split(";")
    state = {}
    for name, run in (("before", a.before), ("after", a.after)):
        c = counters(run)
        c["flicker"] = flicker(run, crop, keys)
        c["flicker_noise_floor_wall_beside"] = flicker(run, crop, keys[1:2], shift=110)
        state[name] = c
    print(json.dumps(state, indent=1))
    verdict = None
    if not a.no_jev:
        sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        from typesafe_client import ask, noul, choice
        qs = {
            "other_room_fixed": noul("Was a character in another room mirrored before (cross_room_mirrored true with people "
                                     "frames) and is no one in another room mirrored after (cross_room_mirrored false)?"),
            "undiscovered_fixed": noul("Did panes the player never saw show their reflection before (unseen_pane_states_shown "
                                       "> 0) and show none after (unseen_pane_states_shown 0)?"),
            "flicker_reduced": noul("Is the reflections' frame-to-frame change while the player walks clearly lower after than "
                                    "before (flicker mean per pane lower by a third or more), approaching the noise floor of "
                                    "the plain wall?"),
            "walk_ok": noul("Did both walks run (walk summaries present, the same scene)?"),
            "verdict": choice("Overall, are the three mirror reports fixed?",
                              {"fixed": "all three confirmed before and gone or clearly reduced after",
                               "partly": "one or two fixed, or the flicker only slightly lower",
                               "not_fixed": "the after run still shows them"}),
        }
        verdict = ask(state, qs)
        print("jev:", json.dumps({k: (v.get("choice"), v.get("confidence")) if "choice" in v else round(v.get("noul", 0), 3) for k, v in verdict.items()}))
    if a.json:
        json.dump({"state": state, "jev": verdict}, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
