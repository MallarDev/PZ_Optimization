#!/usr/bin/env python3
"""Jev's verdict on a wall mirror popping by a room corner, before vs after (2026-10-06, flip save: the upstairs bathroom
mirror blinked in and out as the player walked near it).

The game cuts a room's walls away round the player; at a corner the cut flag flips every 0.1-0.7 s while the player
walks, and the mirror's wall with its reflection went with it. Runs: the same explore=mirror walk (director=jev,
mirror_only=N) with --prop devMirrorsLog=true --prop devMirrorsRectsEvery=1 and a full-frame devCapture, before
(--prop mirrorsCutawayHoldMs=0) and after (the default). Per run, over the walk at the mirror:

  flips             the mirror's on / off changes (the "dev attached ... captured <-> cut away" lines)
  pops              on or off states that lasted under 0.75 s (a blink of the mirror and its wall; the flips came 0.1-0.7 s apart)
  visible_share     the share of the walk the mirror was on screen and reflecting (the fix must not just hide it)
  glass_pop_blinks  the glass_blinks whose two changes both fall on a change of the on / off state (within 0.1 s)
  glass_blinks      from the frames: abrupt changes of the glass's box (its rect from the dev rects log; frames within
                    0.1 s of a logged rect or in a gap under 1 s) followed by another within 0.75 s (mean |difference| to
                    the previous frame's box, best of +-2 px shifts, above --thr; changes within 0.1 s are one event)
  walk_s            the walk's length at the mirror (from the first "to mirror" event to the last log line)

  python3 harness/mirrors/corner-judge.py --before <run> [<run> ...] --after <run> [<run> ...] [--mirror 6765,5405,1]
      [--json out.json] [--no-jev]
"""
import argparse, json, os, re, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("corner_pops", os.path.join(os.path.dirname(os.path.abspath(__file__)), "corner-pops.py"))
cp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cp)

POP_MS = 750


def states(run, mirror):
    """[(t, on)]: the mirror's capture state changes; on = captured (reflecting), off = cut away / absent / skipped."""
    pat = re.compile(r"mirrors: dev attached \S+ on \S+ at " + re.escape(mirror) + r": (.*?) -> (.*?) \(frame .*epoch_ms=(\d+)\)")
    out = []
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        m = pat.search(line)
        if m:
            out.append((int(m.group(3)), m.group(2).startswith("captured")))
    return out


def walk_span(run):
    t0 = t1 = None
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        e = re.search(r"epoch_ms=(\d+)", line)
        if not e:
            continue
        t = int(e.group(1))
        if t0 is None and "mirror walk: to mirror" in line:
            t0 = t
        if t0 is not None:
            t1 = t
    return t0, t1


def glass_blinks(run, mirror, thr, flips_t=()):
    frames, st, w, h = cp.load(run)
    rs = cp.rects(run, mirror)
    if not rs:
        return None
    scale = w / 1920.0
    t0, t1 = rs[0][0], rs[-1][0]
    i0, i1 = int(np.searchsorted(st, t0)), int(np.searchsorted(st, t1))
    ts = np.array([r[0] for r in rs])
    prev, changes = None, []
    for i in range(i0, min(i1, len(st) - 1) + 1):
        t = int(st[i])
        k = int(np.searchsorted(ts, t))
        near = min(abs(t - ts[max(0, k - 1)]), abs(ts[min(k, len(ts) - 1)] - t))
        gap = ts[min(k, len(ts) - 1)] - ts[max(0, k - 1)]
        if near > 100 and gap > 1000:
            prev = None
            continue
        x, y, bw, bh = cp.rect_at(rs, t)
        xa, ya, bw, bh = int(x * scale), int(y * scale), max(4, int(bw * scale)), max(4, int(bh * scale))
        if xa < 0 or ya < 0 or xa + bw > w or ya + bh > h:
            prev = None
            continue
        img = frames[i][::-1]
        box = img[ya:ya + bh, xa:xa + bw, :3].astype(np.float32)
        if prev is not None:
            pimg, pxa, pya = prev
            best = 1e9
            for dy in range(-2, 3):
                for dx in range(-2, 3):
                    yy, xx = pya + dy, pxa + dx
                    if yy < 0 or xx < 0 or yy + bh > h or xx + bw > w:
                        continue
                    best = min(best, float(np.abs(box - pimg[yy:yy + bh, xx:xx + bw, :3].astype(np.float32)).mean()))
            if best < 1e9 and best > thr:
                changes.append(t)
        prev = (img, xa, ya)
    events = []  # a change spanning a few frames (the wall's bake and the reflection a frame apart) is one event
    for t in changes:
        if not events or t - events[-1][1] > 100:
            events.append([t, t])
        else:
            events[-1][1] = t
    pairs = [(a, b) for a, b in zip(events, events[1:]) if b[0] - a[1] < POP_MS]

    def at_flip(e):
        return any(e[0] - 100 <= t <= e[1] + 100 for t in flips_t)
    pop_blinks = sum(1 for a, b in pairs if at_flip(a) and at_flip(b))
    return {"glass_changes": len(events), "glass_blinks": len(pairs), "glass_pop_blinks": pop_blinks}


def measure(run, mirror, thr):
    s = states(run, mirror)
    t0, t1 = walk_span(run)
    if t0 is None or not s:
        return {"run": os.path.basename(run.rstrip("/")), "valid": False}
    # the state over the walk
    seq = [(t, on) for t, on in s if t >= t0] or [s[-1]]
    before = [on for t, on in s if t < t0]
    cur = before[-1] if before else seq[0][1]
    edges = [(t0, cur)]
    for t, on in s:
        if t >= t0 and on != edges[-1][1]:
            edges.append((t, on))  # (a change of reason without a change of on / off is no edge)
    edges.append((t1, None))
    flips = pops = 0
    on_ms = 0
    for (ta, a), (tb, b) in zip(edges, edges[1:]):
        if a:
            on_ms += tb - ta
        if b is not None and b != a:
            flips += 1
    # states between two changes (the one the walk's end cuts short is not a blink)
    durs = [(tb - ta, a) for (ta, a), (tb, b) in zip(edges[1:-2], edges[2:-1])]
    pops = sum(1 for d, a in durs if d < POP_MS)
    walk_ms = max(1, t1 - t0)
    out = {"run": os.path.basename(run.rstrip("/")), "valid": True, "walk_s": round(walk_ms / 1000, 1), "flips": flips,
           "pops": pops, "pops_per_min": round(pops * 60000 / walk_ms, 2), "visible_share": round(on_ms / walk_ms, 3)}
    g = glass_blinks(run, mirror, thr, [t for t, _ in edges[1:-1]])
    if g:
        out.update(g)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", nargs="+", required=True)
    ap.add_argument("--after", nargs="+", required=True)
    ap.add_argument("--mirror", default="6765,5405,1")
    ap.add_argument("--thr", type=float, default=14.0)
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    before = [measure(r, a.mirror, a.thr) for r in a.before]
    after = [measure(r, a.mirror, a.thr) for r in a.after]

    def tot(rs, k):
        return sum(r.get(k, 0) for r in rs if r.get("valid"))
    summary = {k: {"before": tot(before, k), "after": tot(after, k)} for k in ("flips", "pops", "glass_blinks", "glass_pop_blinks", "glass_changes")}
    for k in ("walk_s",):
        summary[k] = {"before": round(tot(before, k), 1), "after": round(tot(after, k), 1)}
    vs = lambda rs: round(float(np.mean([r["visible_share"] for r in rs if r.get("valid")])), 3) if any(r.get("valid") for r in rs) else None
    summary["visible_share_mean"] = {"before": vs(before), "after": vs(after)}
    state = {
        "problem": "A wall mirror by a room corner (upstairs bathroom of the maintainer's flip save) blinked in and out as "
                   "the player walked near it: the game cuts the room's walls away round the player and at the corner the "
                   "cut flipped every 0.1-0.7 s, taking the mirror's wall and its reflection with it. The fix gives a "
                   "wall carrying a mirror the game's own 750 ms cutaway lock (which its chunk-texture renderer never "
                   "applied): its cut changes at most once per 750 ms, the first cut at once (the player is never hidden), "
                   "and the wall returns only after 1.5 s unwanted. While the player stands by the corner the mirror's wall "
                   "therefore stays cut (as stock cuts it most of that time) instead of flickering, so visible_share is "
                   "expected somewhat lower after.",
        "measures": {"flips": "the mirror's on / off changes during the walk", "pops": "on or off states shorter than "
                     "0.75 s: the blinks the player sees", "visible_share": "share of the walk the mirror reflected (the "
                     "fix must not simply hide the mirror for good)", "glass_blinks": "from the frames: two abrupt "
                     "changes of the glass's screen box within 0.75 s, any cause (also the player's own reflection moving "
                     "in it): information", "glass_pop_blinks": "the glass_blinks whose two changes both fall on a change "
                     "of the mirror's on / off state (within 0.1 s): the mirror's pops as the frames show them",
                     "walk_s": "seconds walked at "
                     "the mirror (Jev directs every walk, so the paths differ between runs)"},
        "before_runs": before, "after_runs": after, "totals": summary,
    }
    print(json.dumps(state, indent=1))
    verdict = None
    if not a.no_jev:
        from typesafe_client import ask, noul, choice
        qs = {
            "comparable": noul("Did both arms walk the mirror long enough to judge (walk_s comparable within a factor of two, "
                               "and the before runs show the mirror flipping, flips > 0)?"),
            "pops_fixed": noul("Are the blinks fixed: after the fix, are pops (on / off states shorter than 0.75 s) near zero "
                               "(at most one per after run) while the before runs had clearly more?"),
            "blinks_fewer": noul("In the frames, are glass_pop_blinks (the mirror's own pops) near zero after the fix and "
                                 "lower than before? (glass_blinks is information only: it also counts the player's "
                                 "reflection moving in the glass)"),
            "mirror_still_shown": noul("Is the mirror still shown a fair part of the walk after the fix (visible_share_mean "
                                       "after at least half of before), so the fix did not just hide it?"),
            "verdict": choice("Overall, is the mirror's popping by the corner fixed?",
                              {"fixed": "pops are gone after the fix, the mirror is still shown, and the comparison is fair",
                               "partly_fixed": "pops are fewer but not gone, or the mirror is shown much less",
                               "not_fixed": "pops are as frequent after as before",
                               "invalid": "the runs do not allow the comparison"}),
        }
        verdict = ask(state, qs)
        print("jev:", json.dumps({k: (v.get("choice"), v.get("confidence")) if "choice" in v else round(v.get("noul", 0), 3) for k, v in verdict.items()}))
    if a.json:
        json.dump({"state": state, "jev": verdict}, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    sys.exit(main())
