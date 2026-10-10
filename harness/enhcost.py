#!/usr/bin/env python3
"""Cost of each Enhancements-tab feature from ONE run (2026-10-08).

The run switches the live keys on and off with the live_set rig (`--flag live_set=k=v@s,...`): an "off" window, then
one feature on, off again, the next feature on, ... Every on window is compared with the mean of the off windows on its
two sides (same route stretch, same clocks), from pzopt-overlay.out (presented frame time, GPU busy ms, thread loads).

  enhcost.py schedule [--start 3] [--win 2.5] key=value ...   -> the live_set flag value (on at start+2*win*i, off win later)
  enhcost.py <run> [--skip 0.5]                                -> per window and per feature: frame ms, p99, GPU ms, deltas
  enhcost.py alt <run> [--skip 3]                              -> a dev on / off alternation rig's run (devReliefAlternate,
                                                                  devCarGlassAlternate, ...): paired on - off per period

Windows should span a whole number of view turns (`--flag turn=D`: 360/D seconds a turn) so every window sees the same
directions. --skip drops the first seconds after each switch (re-bakes, program swaps); the switch hitch is printed apart.
"""
import re
import statistics
import sys
from pathlib import Path

OFF = {"off", "false", "0", "stock"}


def schedule(args):
    start, win, kv, invert = 3.0, 2.5, [], False
    it = iter(args)
    for a in it:
        if a == "--start":
            start = float(next(it))
        elif a == "--win":
            win = float(next(it))
        elif a == "--invert":
            invert = True  # the run boots with every key on: each goes off for a window, then back on
        else:
            kv.append(a)
    out = []
    for i, item in enumerate(kv):
        key, val = item.split("=", 1)
        off = {"upscaler": "off", "spriteFilter": "stock", "darknessFloorPct": "0"}.get(key, "false")
        t = start + 2 * win * i
        a, b = (off, val) if invert else (val, off)
        out.append(f"{key}={a}@{t:g}")
        out.append(f"{key}={b}@{t + win:g}")
    print(",".join(out))
    print(f"# route needs >= {start + 2 * win * len(kv) + 1:g} s", file=sys.stderr)


def pct(xs, p):
    if not xs:
        return float("nan")
    s = sorted(xs)
    return s[min(len(s) - 1, int(p / 100 * len(s)))]


def analyze(run, skip):
    run = Path(run)
    sets = re.findall(r"harness: live_set (\S+?)=(\S+) at t=", (run / "console.txt").read_text(errors="replace"))
    # marks "# live-<key> <us> <frame>" and anchors "# anchor <epochUs> <us> <frame>" from the frame log
    anchors, marks, route_us = [], [], None
    for line in (run / "pzopt-frames.out").read_text().splitlines():
        if not line.startswith("# "):
            continue
        f = line.split()
        if f[1] == "anchor":
            anchors.append((int(f[2]), int(f[3])))
        elif f[1].startswith("live-"):
            marks.append(int(f[2]))
        elif f[1] == "route-start":
            route_us = int(f[2])
    if not anchors or len(marks) != len(sets):
        sys.exit(f"{run}: {len(marks)} live marks vs {len(sets)} live_set lines, {len(anchors)} anchors")

    def epoch_ms(us):
        a = min(anchors, key=lambda x: abs(x[1] - us))
        return (a[0] + (us - a[1])) / 1000.0

    rows = []
    lines = (run / "pzopt-overlay.out").read_text().splitlines()
    hdr = lines[0].split(",")
    ix = {k: hdr.index(k) for k in ("frametime", "gpu_ms", "game_load", "render_load", "epoch_ms")}
    for l in lines[1:]:
        f = l.split(",")
        try:
            rows.append((float(f[ix["epoch_ms"]]), float(f[ix["frametime"]]), float(f[ix["gpu_ms"]]),
                         float(f[ix["game_load"]]), float(f[ix["render_load"]])))
        except (ValueError, IndexError):
            pass
    bounds = [epoch_ms(route_us)] + [epoch_ms(m) for m in marks]
    # each key is set twice: the first set opens its test window, the second restores the base (off, or with
    # --invert everything on); inverted runs report base - test = what the key costs on top of all the others
    inverted = bool(sets) and sets[0][1] in OFF
    labels = ["base"] + ["base" if i and sets[i - 1][0] == k else f"{k}={v}" for i, (k, v) in enumerate(sets)]
    end = rows[-1][0]
    wins = []
    for i, lab in enumerate(labels):
        t0 = bounds[i]
        t1 = bounds[i + 1] if i + 1 < len(bounds) else min(end, t0 + (bounds[1] - bounds[0] if len(bounds) > 1 else 3000))
        body = [r for r in rows if t0 + skip * 1000 <= r[0] < t1]
        head = [r for r in rows if t0 <= r[0] < t0 + skip * 1000]
        if not body:
            continue
        ft = [r[1] for r in body]
        wins.append(dict(lab=lab, t=(t0 - bounds[0]) / 1000, n=len(body), ft=statistics.fmean(ft), p99=pct(ft, 99),
                         gpu=statistics.fmean(r[2] for r in body), gl=statistics.fmean(r[3] for r in body),
                         rl=statistics.fmean(r[4] for r in body), hitch=max((r[1] for r in head), default=0.0)))
    print(f"{run.name}: {len(wins)} windows, skip {skip} s after each switch")
    print(f"{'window':34} {'t s':>6} {'frames':>6} {'ms':>7} {'fps':>6} {'p99':>7} {'gpu ms':>7} {'game%':>6} {'rend%':>6} {'switch max':>10}")
    for w in wins:
        print(f"{w['lab']:34} {w['t']:6.1f} {w['n']:6d} {w['ft']:7.3f} {1000 / w['ft']:6.0f} {w['p99']:7.2f} {w['gpu']:7.3f} "
              f"{w['gl']:6.1f} {w['rl']:6.1f} {w['hitch']:10.1f}")
    print()
    head = "cost = all on - this key off" if inverted else "cost = this key on - base"
    print(f"{head:34} {'d ms':>8} {'d %':>6} {'d p99':>7} {'d gpu ms':>9} {'fps off->on':>13} {'switch max':>10}")
    for i, w in enumerate(wins):
        if w["lab"] == "base":
            continue
        offs = [wins[j] for j in (i - 1, i + 1) if 0 <= j < len(wins) and wins[j]["lab"] == "base"]
        if not offs:
            continue
        b = {k: statistics.fmean(o[k] for o in offs) for k in ("ft", "p99", "gpu")}
        on, off = (b, w) if inverted else (w, b)
        print(f"{w['lab']:34} {on['ft'] - off['ft']:+8.3f} {100 * (on['ft'] - off['ft']) / off['ft']:+6.1f} {on['p99'] - off['p99']:+7.2f} "
              f"{on['gpu'] - off['gpu']:+9.3f} {1000 / off['ft']:6.0f}->{1000 / on['ft']:<6.0f} {w['hitch']:10.1f}")
    offs = [w for w in wins if w["lab"] == "base"]
    if len(offs) > 2:
        d = [abs(offs[i]["ft"] - offs[i + 1]["ft"]) for i in range(len(offs) - 1)]
        print(f"\nnoise: base-to-base window difference median {statistics.median(d):.3f} ms, max {max(d):.3f} ms")


def alternation(run, skip_s, skip_frac):
    """A dev on / off alternation rig's run (devReliefAlternate, devCarGlassAlternate, devMirrorsAlternate, ...): the
    console's '<what>: [dev ]alternating every P ms from epoch_ms T (on first)' line gives the clock (T 0 = periods of the
    epoch itself). Paired per period: each on period against the mean of the off periods on its two sides."""
    run = Path(run)
    con = (run / "console.txt").read_text(errors="replace")
    m = re.search(r"(\w[\w ]*): (?:dev )?alternating every (\d+) ms from epoch_ms (\d+)", con)
    if not m:
        sys.exit(f"{run}: no alternation line")
    what, P, T = m.group(1), int(m.group(2)), int(m.group(3))
    start = end = None
    for l in (run / "pzopt-bench.out").read_text(errors="replace").splitlines():
        mm = re.search(r"route_end_epoch_ms=(\d+)", l)
        end = int(mm.group(1)) if mm else end
        mm = re.search(r"route_start_epoch_ms=(\d+)", l)
        start = int(mm.group(1)) if mm else start
    lines = (run / "pzopt-overlay.out").read_text().splitlines()
    hdr = lines[0].split(",")
    ie, ift, ig = hdr.index("epoch_ms"), hdr.index("frametime"), hdr.index("gpu_ms")
    per = {}
    first = None
    for l in lines[1:]:
        f = l.split(",")
        try:
            e, ft, g = float(f[ie]), float(f[ift]), float(f[ig])
        except (ValueError, IndexError):
            continue
        if e < T or (end and e > end) or (start and e < start):
            continue
        first = e if first is None else first
        if e - first < skip_s * 1000 or (e - T) % P < skip_frac * P:
            continue
        per.setdefault(int((e - T) // P), []).append((ft, g))
    ks = sorted(per)
    # medians per period: one stall (a hitch, the frames after the route end) would swamp a period's mean
    mean = {k: (statistics.median(x[0] for x in per[k]), statistics.median(x[1] for x in per[k]), len(per[k])) for k in ks}
    dft, dg = [], []
    for k in ks:
        if k % 2 or k - 1 not in mean or k + 1 not in mean:
            continue  # on periods are the even ones ("on first")
        dft.append(mean[k][0] - (mean[k - 1][0] + mean[k + 1][0]) / 2)
        dg.append(mean[k][1] - (mean[k - 1][1] + mean[k + 1][1]) / 2)
    on = [mean[k] for k in ks if k % 2 == 0]
    off = [mean[k] for k in ks if k % 2]
    se = lambda xs: statistics.stdev(xs) / len(xs) ** 0.5 if len(xs) > 1 else float("nan")
    oft = statistics.fmean(x[0] for x in off)
    print(f"{run.name}: {what} every {P} ms, {len(on)} on / {len(off)} off periods, skip {skip_s} s + {skip_frac:.0%} of each period")
    print(f"  off: {oft:.3f} ms ({1000 / oft:.0f} fps), gpu {statistics.fmean(x[1] for x in off):.3f} ms")
    print(f"  on : {statistics.fmean(x[0] for x in on):.3f} ms, gpu {statistics.fmean(x[1] for x in on):.3f} ms")
    print(f"  paired on - off: frame {statistics.fmean(dft) * 1000:+.0f} +- {se(dft) * 1000:.0f} us ({100 * statistics.fmean(dft) / oft:+.1f} %), "
          f"gpu {statistics.fmean(dg) * 1000:+.0f} +- {se(dg) * 1000:.0f} us  (n={len(dft)})")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "schedule":
        schedule(sys.argv[2:])
    elif len(sys.argv) > 2 and sys.argv[1] == "alt":
        a = sys.argv[2:]
        skip = float(a[a.index("--skip") + 1]) if "--skip" in a else 3.0
        alternation(a[0], skip, 0.15)
    elif len(sys.argv) > 1:
        a = sys.argv[1:]
        skip = float(a[a.index("--skip") + 1]) if "--skip" in a else 0.5
        analyze(a[0], skip)
    else:
        print(__doc__)
