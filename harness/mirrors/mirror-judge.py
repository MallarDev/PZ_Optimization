#!/usr/bin/env python3
"""Jev's verdict on the mirror reflection artifacts of an explore=mirror walk, before vs after (2026-10-04).

Both runs: the same walk (explore=mirror director=jev on the same save) with --prop devMirrorsView=5
--prop devMirrorsViewToggleMs=1000 --prop mirrorsWindows=false (the glass on screen is the mirrors' alone); the "before" run
adds --prop devMirrorsSkip=98304 (bits 32768 + 65536: the old no-hit stand-in and ray reach). Per mirror (the walk's
"to mirror" events):

  far_fallback_share   glass whose ray found nothing and took a pixel at its reach end, one level under the floor, squares
                       away (artifact 1: the house siding as grey bands across a wall mirror's upper half)
  under_floor_share    glass whose ray "hit" something under the room's own floor (artifact 2: the cut-away outer wall's
                       brick strip as a dark brown slab in the medicine cabinet)
  occluder_standin_share glass where a hidden floor landing took the hiding object's own colour (artifact 3: the dining
                       table's grey top as one slab over half the wall mirror; it existed in the intermediate "mid" state,
                       devMirrorsSkip=65536)
  flat_slab_share      in the picture (normal phase, camera still), the largest region of one flat colour on the glass
                       (information: also high where a mirror really faces a uniform surface)
  glass_px             glass pixels measured (both runs must cover the mirror)

  python3 harness/mirrors/mirror-judge.py --before <run> --after <run> [--mid <run>] [--stock-ref <run> --stock-cycle 700]
      [--after-standin-pct 50] [--json out.json] [--no-jev]
"""
import argparse, json, os, re, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from kinds import KINDS, biggest, classify  # noqa: E402


def load(run):
    cap = os.path.join(run, "capture")
    lines = open(os.path.join(cap, "index.txt")).read().split("\n")
    kv = dict(p.split("=") for p in lines[0].split())
    w, h = int(kv["w"]), int(kv["h"])
    st = np.array([int(x) for x in lines[1:] if x.strip()], dtype=np.int64)
    mm = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(st), mm.size // (w * h * 4))
    return mm[: n * w * h * 4].reshape(n, h, w, 4), st[:n]


def walk_axes(run):
    """mirror name@x,y,z -> 'north' / 'west' (the wall it hangs on), from the walk's building line."""
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        if "mirror walk: building" in line:
            return {m.group(1): m.group(2) for m in re.finditer(r"#\d+ (\S+) (north|west) \(", line)}
    return {}


def walk_events(run):
    ev = []
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        m = re.search(r"mirror walk: to mirror \d+ (\S+) at (\S+), station.*epoch_ms=(\d+)", line)
        if m:
            ev.append((int(m.group(3)), m.group(1) + "@" + m.group(2)))
    return ev


def flat_slab(img, mask):
    """Largest 4-connected region of the glass whose neighbours differ by < 3 per channel, as a share of the glass."""
    g = img.astype(np.int16)
    flat_r = np.zeros(mask.shape, bool)
    flat_d = np.zeros(mask.shape, bool)
    flat_r[:, :-1] = (np.abs(g[:, 1:] - g[:, :-1]).max(axis=2) < 3) & mask[:, 1:] & mask[:, :-1]
    flat_d[:-1, :] = (np.abs(g[1:, :] - g[:-1, :]).max(axis=2) < 3) & mask[1:, :] & mask[:-1, :]
    h, w = mask.shape
    seen = np.zeros(mask.shape, bool)
    best = 0
    ys, xs = np.nonzero(mask)
    for y0, x0 in zip(ys, xs):
        if seen[y0, x0]:
            continue
        stack, size = [(y0, x0)], 0
        seen[y0, x0] = True
        while stack:
            y, x = stack.pop()
            size += 1
            for ny, nx, ok in ((y, x + 1, x + 1 < w and flat_r[y, x]), (y, x - 1, x > 0 and flat_r[y, x - 1]),
                               (y + 1, x, y + 1 < h and flat_d[y, x]), (y - 1, x, y > 0 and flat_d[y - 1, x])):
                if ok and not seen[ny, nx]:
                    seen[ny, nx] = True
                    stack.append((ny, nx))
        best = max(best, size)
    return best / max(1, int(mask.sum()))


def align(f, g, mask, box, r=40, step=2):
    """The (dy, dx) that lines up g's surroundings of the glass with f's (the camera follows the walking player between a
    dev frame and its picture frame): the frame and wall round the glass, the glass itself left out; None when even the
    best shift leaves a mean difference above 8 or the surroundings are too plain to tell (a bare wall matches any shift)."""
    y0, y1, x0, x1 = box
    h, w = mask.shape
    Y0, Y1, X0, X1 = max(0, y0 - 30), min(h, y1 + 30), max(0, x0 - 30), min(w, x1 + 30)
    ref = f[Y0:Y1, X0:X1].astype(np.int16)
    keep = ~mask[Y0:Y1, X0:X1]
    if ref[keep].std() < 8:
        return None
    def cost(dy, dx):
        if Y0 + dy < 0 or X0 + dx < 0 or Y1 + dy > h or X1 + dx > w:
            return 1e9
        return np.abs(g[Y0 + dy:Y1 + dy, X0 + dx:X1 + dx].astype(np.int16) - ref)[keep].mean()
    best, bestv = None, 1e9
    for dy in range(-r, r + 1, 4):  # coarse, then the 7x7 round the best
        for dx in range(-r, r + 1, 4):
            v = cost(dy, dx)
            if v < bestv:
                best, bestv = (dy, dx), v
    if best is None:
        return None
    cy, cx = best
    for dy in range(cy - 3, cy + 4):
        for dx in range(cx - 3, cx + 4):
            v = cost(dy, dx)
            if v < bestv:
                best, bestv = (dy, dx), v
    return best if bestv < 8 else None


def measure(run, toggle=1000, cycle=0):
    """Per mirror: the dev view's kinds, and the picture of the same glass (aligned). With cycle > 0 (devMirrorsAlternate=cycle
    devMirrorsCycle=0,4) the composite is off on the odd cycle periods: those frames show the stock glass at the same places
    ("stock_*")."""
    mm, st = load(run)
    ev = walk_events(run)
    axes = walk_axes(run)
    margin = 150
    def phase_ok(t):
        if not (margin < t % toggle < toggle - margin):
            return False
        return cycle <= 0 or margin < t % cycle < cycle - margin
    devph = ((st // toggle) & 1) == 0
    comp = np.ones(len(st), bool) if cycle <= 0 else ((st // cycle) % 2) == 0
    per = {}
    for i in range(len(st)):
        if not devph[i] or not comp[i] or not phase_ok(st[i]):
            continue
        cur = [m for t, m in ev if t <= st[i]]
        if not cur:
            continue
        f = np.asarray(mm[i])[::-1, :, :3]
        cls = classify(f.astype(np.int16))
        mask = biggest(cls >= 0)
        if mask.sum() < 300:
            continue
        p = per.setdefault(cur[-1], {"glass_px": 0, **{k: 0 for k in KINDS}, "slabs": [], "luma": [], "stock_slabs": [], "stock_luma": [], "scene_slabs": []})
        p["glass_px"] += int(mask.sum())
        for ki, k in enumerate(KINDS):
            p[k] += int(((cls == ki) & mask).sum())
        # the same glass in the picture (reflection on) and, with the cycle, in the stock look (composite off): frames within
        # 0.7 s, lined up on the glass's surroundings (the camera follows the walking player)
        ys, xs = np.nonzero(mask)
        y0, y1, x0, x1 = max(0, ys.min() - 20), ys.max() + 20, max(0, xs.min() - 20), xs.max() + 20
        for key, want in (("", True), ("stock_", False)):
            if key and cycle <= 0:
                continue
            cand = [j for j in range(max(0, i - 20), min(len(st), i + 20))
                    if phase_ok(st[j]) and abs(st[j] - st[i]) < 1200 and comp[j] == want and (not want or not devph[j])]
            cand.sort(key=lambda j: abs(j - i))
            for j in cand:
                g = np.asarray(mm[j])[::-1, :, :3]
                sh = align(f, g, mask, (y0, y1, x0, x1))
                if sh is None:
                    continue  # the surroundings do not line up at any shift (the view changed)
                dy, dx = sh
                gm = np.zeros_like(mask)
                ys2, xs2 = ys + dy, xs + dx
                ok = (ys2 >= 0) & (ys2 < mask.shape[0]) & (xs2 >= 0) & (xs2 < mask.shape[1])
                gm[ys2[ok], xs2[ok]] = True
                p[key + "slabs"].append(flat_slab(g, gm))
                if key == "stock_":
                    # what the mirror's rays pass over on screen (0.75-2 squares out along the reflected ray's screen line,
                    # 128 px across and 64 down a square at zoom 1): how plain the scene it faces is, in the same frame
                    sx = -128 if axes.get(cur[-1]) == "north" else 128
                    sm = np.zeros_like(gm)
                    for t in (0.75, 1.0, 1.25, 1.5, 1.75, 2.0):
                        oy, ox = int(64 * t), int(sx * t)
                        sm[max(0, oy):sm.shape[0] + min(0, oy), max(0, ox):sm.shape[1] + min(0, ox)] |= \
                            gm[max(0, -oy):gm.shape[0] - max(0, oy), max(0, -ox):gm.shape[1] - max(0, ox)]
                    sm &= ~gm
                    if sm.sum() > 200:
                        p["scene_slabs"].append(flat_slab(g, sm))
                p[key + "luma"].append(float((g[gm].astype(np.float32) @ np.array([0.2126, 0.7152, 0.0722], np.float32)).mean()))
                break
    out = {}
    for m, p in per.items():
        s = max(1, sum(p[k] for k in KINDS))
        out[m] = {"glass_px": p["glass_px"], "far_fallback_share": round(p["reach_fallback"] / s, 4),
                  "under_floor_share": round(p["under_own_floor"] / s, 4),
                  "hidden_landing_share": round(p["hidden_landing"] / s, 4), "floor_share": round(p["floor"] / s, 4),
                  "occluder_standin_share": round(p["occluder_standin"] / s, 4),
                  "marched_share": round(p["march"] / s, 4),
                  "flat_slab_share_median": round(float(np.median(p["slabs"])), 4) if p["slabs"] else None,
                  "flat_slab_share_p90": round(float(np.percentile(p["slabs"], 90)), 4) if p["slabs"] else None,
                  "glass_luma_median": round(float(np.median(p["luma"])), 1) if p["luma"] else None,
                  "stock_flat_slab_share_median": round(float(np.median(p["stock_slabs"])), 4) if p["stock_slabs"] else None,
                  "stock_glass_luma_median": round(float(np.median(p["stock_luma"])), 1) if p["stock_luma"] else None,
                  "stock_pairs": len(p["stock_slabs"]),
                  "scene_flat_slab_share_median": round(float(np.median(p["scene_slabs"])), 4) if p["scene_slabs"] else None,
                  "still_pairs": len(p["slabs"])}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", required=True)
    ap.add_argument("--after", required=True)
    ap.add_argument("--stock-ref", default="", help="a second after run (same build) with devMirrorsAlternate=<cycle> devMirrorsCycle=0,4: "
                    "the picture and the stock look of the same glass from the same frames")
    ap.add_argument("--stock-cycle", type=int, default=700)
    ap.add_argument("--after-standin-pct", type=float, default=50, help="mirrorsStandInPct of the after run (before / mid: drawn at 100)")
    ap.add_argument("--mid", default="", help="the intermediate run (devMirrorsSkip=65536: the occluder's colour for a hidden floor landing)")
    ap.add_argument("--json", default="")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    before, after = measure(a.before), measure(a.after)
    if a.stock_ref:
        ref = measure(a.stock_ref, cycle=a.stock_cycle)
        for m, v in after.items():
            r = ref.get(m, {})
            v["picture_vs_stock_same_frames"] = {"flat_slab_share_median": r.get("flat_slab_share_median"),
                                                  "stock_flat_slab_share_median": r.get("stock_flat_slab_share_median"),
                                                  "glass_luma_median": r.get("glass_luma_median"),
                                                  "stock_glass_luma_median": r.get("stock_glass_luma_median"),
                                                  "faced_scene_flat_slab_share_median": r.get("scene_flat_slab_share_median"),
                                                  "pairs": r.get("still_pairs"), "stock_pairs": r.get("stock_pairs")}
    mid = measure(a.mid) if a.mid else None
    for ph, pct in ((before, 100.0), (mid, 100.0), (after, a.after_standin_pct)):
        for v in (ph or {}).values():
            guessed = v["hidden_landing_share"] + v["occluder_standin_share"] + v["far_fallback_share"]
            v["standin_strength"] = pct / 100.0
            v["guessed_weight"] = round(guessed * pct / 100.0, 4)
    state = {
        "what": "Two walks of the same character round every wall mirror of a Project Zomboid house, before and after fixes "
                "to the mirror reflections. Shares are fractions of the mirror glass pixels.",
        "artifacts": {
            "1_far_fallback": "rays that found nothing took a pixel squares away (the house siding as grey bands across a "
                              "wall mirror's upper half); measured by far_fallback_share; fixed means ~0 after",
            "2_under_floor": "rays 'hit' something under the room's own floor (the cut-away outer wall's brick strip as a "
                             "dark brown slab in a medicine cabinet); measured by under_floor_share; fixed means ~0 after",
            "3_occluder_slab": "a hidden floor landing drawn in the colour of the object hiding it, stretched over the "
                               "glass (the dining table's grey top as one flat slab over half the wall mirror). It existed in "
                               "the intermediate state (mid run: the first fix alone). Measured by occluder_standin_share; "
                               "fixed means ~0 after where mid was clearly above zero. flat_slab_share_* (the largest flat "
                               "colour region, camera still) is shown for information: it is also high for a mirror that "
                               "really faces a uniform surface (the medicine cabinet faces a plain lavender bath mat), so "
                               "it is not the test",
            "4_brick_in_cabinet": "the bathroom medicine cabinet reflected the cut-away outer wall's dark brick strip (a "
                                  "false hit just above the floor) instead of the light bathroom floor; measured by the "
                                  "cabinet's glass_luma_median (0-255, the picture's brightness on its glass); fixed means "
                                  "clearly brighter after than before (the bathroom floor is white tiles)",
            "5_flat_guess_pane": "most of the medicine cabinet's glass is guessed content (its view across the bathroom is "
                                 "hidden by the bathtub, the floor seen last stands in: the bath mat), drawn at full "
                                 "strength it read as an opaque lavender pane. Measured by the cabinet's guessed_weight = "
                                 "share of guessed glass x the strength it is drawn at (standin_strength), and by its "
                                 "after.picture_vs_stock_same_frames: the glass's flat_slab_share in the picture against the same "
                                 "glass in the stock look (a second after run of the same build whose reflection composite is "
                                 "switched off every other 0.7 s, both read from its frames); faced_scene_flat_slab_share is "
                                 "the same flatness measure over the screen region the mirror's rays pass over (how plain the "
                                 "scene it reflects is: the cabinet faces a white bathtub and a plain bath mat). Fixed means guessed_weight after "
                                 "clearly below mid, and the picture no flatter than the stock glass (flat_slab_share_median at "
                                 "most stock_flat_slab_share_median + 0.1)",
        },
        "notes": "flat_slab_share_* = the largest region whose neighbouring pixels differ by under 3 levels, as a share of "
                 "the glass; it is high for a plain stock glass too, so only the comparison with stock_* is meaningful",
        "before": before, "mid": mid, "after": after,
    }
    print(json.dumps(state, indent=1))
    verdict = None
    if not a.no_jev:
        from typesafe_client import ask, noul, choice
        qs = {
            "far_fallback_fixed": noul("For every mirror present in both runs, is artifact 1 fixed: far_fallback_share after "
                                       "is near zero (below 0.01) and it was clearly above zero before in at least one mirror?"),
            "under_floor_fixed": noul("For every mirror present in both runs, is artifact 2 fixed: under_floor_share after "
                                      "is near zero (below 0.01), and was it above zero before in at least one mirror?"),
            "occluder_slab_fixed": noul("Is artifact 3 fixed: occluder_standin_share after is near zero (below 0.01) for "
                                        "every mirror, and it was clearly above zero in the mid run for at least one mirror?"),
            "brick_fixed": noul("Is artifact 4 fixed: is the medicine cabinet's (fixtures_bathroom) glass_luma_median after "
                                "clearly higher than before?"),
            "same_coverage": noul("Do both runs measure the same mirrors with a comparable number of glass pixels (within a "
                                  "factor of three), so the comparison is fair?"),
            "guess_pane_fixed": noul("Is artifact 5 fixed: is the medicine cabinet's (fixtures_bathroom) guessed_weight "
                                     "after clearly below its mid value, and in after.picture_vs_stock_same_frames is its "
                                     "flat_slab_share_median at most stock_flat_slab_share_median + 0.1 (no flatter than the "
                                     "stock glass)?"),
            "verdict": choice("Overall, are the five listed mirror reflection artifacts fixed? Judge each artifact by its "
                              "own measure as its description defines it (flat_slab_share is information only, not a test).",
                              {"all_fixed": "each of the five artifacts passes its own test and the comparison is fair",
                               "partly_fixed": "at least one artifact fails its own test",
                               "not_fixed": "none passes or the after run is worse",
                               "invalid": "the runs do not allow the comparison (missing mirror, too few pixels)"}),
        }
        verdict = ask(state, qs)
        print("jev:", json.dumps({k: (v.get("choice"), v.get("confidence")) if "choice" in v else round(v.get("noul", 0), 3) for k, v in verdict.items()}))
    if a.json:
        json.dump({"state": state, "jev": verdict}, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    sys.exit(main())
