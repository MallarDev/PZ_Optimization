#!/usr/bin/env python3
"""Dynamic resolution report for a run (pzopt.DynRes, pzopt-dynres.out + pzopt-overlay.out).

    harness/dynres.py <run> [<run> ...] [--all] [--csv]

Per run, over the route window (pzopt-bench.out; --all = the whole log):
  gpu      GPU ms per frame (GL timestamps around the replay): mean / p50 / p95 / p99, share of the frame interval,
           frames over the interval and over the controller's target
  scale    render scale per axis: mean / p5 / min / max; changes per second, total variation per second (sum of
           |delta scale|), direction reversals per second (oscillation)
  load     with the devDynResLoad rig: per load step, frames until the GPU time is back under the interval for 10
           frames in a row (settle) and the worst GPU ms during it (overshoot)
  present  the overlay's presented frames: fps, p99 / p99.9 ms, frames over 1.5x the interval
"""
import argparse
import math
import os
import sys


def route_window(run):
    p = os.path.join(run, "pzopt-bench.out")
    if not os.path.exists(p):
        return None
    kv = dict(l.rstrip("\n").split("=", 1) for l in open(p) if "=" in l)
    if "route_start_epoch_ms" in kv and "route_end_epoch_ms" in kv:
        return int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    return None


def cap_fps(run):
    """The run's frame cap (frameCapFps in its pzopt.properties; dynRes off logs no interval)."""
    p = os.path.join(run, "pzopt.properties")
    if os.path.exists(p):
        for line in open(p):
            if line.startswith("frameCapFps="):
                try:
                    return float(line.split("=", 1)[1])
                except ValueError:
                    pass
    return 0.0


def pct(v, q):
    if not v:
        return float("nan")
    v = sorted(v)
    return v[min(len(v) - 1, int(len(v) * q))]


def mean(v):
    return sum(v) / len(v) if v else float("nan")


def read_dynres(run):
    rows = []
    p = os.path.join(run, "pzopt-dynres.out")
    if not os.path.exists(p):
        return rows, ""
    header = ""
    for line in open(p):
        if line.startswith("#"):
            header = line.strip()
            continue
        f = line.split()
        if len(f) < 14:
            continue
        rows.append({
            "t": int(f[0]), "frame": int(f[1]), "scale": float(f[2]), "budget": float(f[3]), "gpu": float(f[4]),
            "cpu": float(f[5]), "slag": float(f[6]), "elag": float(f[7]), "wish": float(f[8]), "a": float(f[9]),
            "b": float(f[10]), "sigma": float(f[11]), "flags": int(f[12]), "load": int(f[13]),
            "bakes": int(f[14]) if len(f) > 14 else 0, "c": float(f[15]) if len(f) > 15 else 0.0,
        })
    return rows, header


def read_overlay(run):
    p = os.path.join(run, "pzopt-overlay.out")
    out = []
    if not os.path.exists(p):
        return out
    with open(p) as fh:
        head = fh.readline().strip().split(",")
        try:
            ift, iep = head.index("frametime"), head.index("epoch_ms")
        except ValueError:
            return out
        for line in fh:
            f = line.strip().split(",")
            if len(f) <= max(ift, iep):
                continue
            try:
                out.append((int(f[iep]), float(f[ift])))
            except ValueError:
                pass
    return out


def report(run, use_all, target_pct):
    rows, header = read_dynres(run)
    win = None if use_all else route_window(run)
    if win:
        rows = [r for r in rows if win[0] <= r["t"] <= win[1]]
    name = os.path.basename(run.rstrip("/"))
    if not rows:
        print(f"{name}: no dynres rows" + (" in the route window" if win else ""))
        return None
    if all(r["budget"] <= 0 for r in rows):
        fps = cap_fps(run)
        for r in rows:
            r["budget"] = 1000.0 / fps if fps else 0.0
    rows = [r for r in rows if r["budget"] > 0] or rows
    secs = max(1e-3, (rows[-1]["t"] - rows[0]["t"]) / 1000.0)
    budget = pct([r["budget"] for r in rows], 0.5)
    gpu = [r["gpu"] for r in rows]
    scale = [r["scale"] for r in rows]
    tgt = budget * target_pct / 100.0
    over = sum(1 for g in gpu if g > budget) / len(gpu) * 100.0
    over_t = sum(1 for g in gpu if g > tgt) / len(gpu) * 100.0
    changes = sum(1 for a, b in zip(scale, scale[1:]) if abs(a - b) > 1e-4)
    tv = sum(abs(a - b) for a, b in zip(scale, scale[1:]))
    rev = 0
    last = 0
    for a, b in zip(scale, scale[1:]):
        d = (b > a + 1e-4) - (b < a - 1e-4)
        if d and last and d != last:
            rev += 1
        if d:
            last = d
    starved = sum(1 for r in rows if r["flags"] & 1) / len(rows) * 100.0
    gated = sum(1 for r in rows if r["flags"] & 4) / len(rows) * 100.0
    res = {
        "run": name, "frames": len(rows), "secs": secs, "budget": budget, "gpu_mean": mean(gpu), "gpu_p50": pct(gpu, 0.5),
        "gpu_p95": pct(gpu, 0.95), "gpu_p99": pct(gpu, 0.99), "util": mean(gpu) / budget * 100.0 if budget > 0 else float("nan"),
        "over": over, "over_target": over_t, "scale_mean": mean(scale), "scale_p5": pct(scale, 0.05), "scale_min": min(scale),
        "scale_max": max(scale), "changes_s": changes / secs, "tv_s": tv / secs, "rev_s": rev / secs, "starved": starved, "gated": gated,
    }
    print(f"== {name} ({len(rows)} frames, {secs:.1f} s{', route window' if win else ''})")
    if header:
        h = header[header.find("dynRes="):].rstrip(")") if "dynRes=" in header else ""
        print(f"   {h}")
    print(f"   gpu      mean {res['gpu_mean']:.3f}  p50 {res['gpu_p50']:.3f}  p95 {res['gpu_p95']:.3f}  p99 {res['gpu_p99']:.3f} ms"
          f"  | interval {budget:.3f} ms, filled {res['util']:.1f} %, over interval {over:.2f} %, over target {over_t:.1f} %")
    print(f"   scale    mean {res['scale_mean']:.3f}  p5 {res['scale_p5']:.3f}  min {res['scale_min']:.3f}  max {res['scale_max']:.3f}"
          f"  | changes {res['changes_s']:.1f}/s, variation {res['tv_s']:.3f}/s, reversals {res['rev_s']:.2f}/s")
    print(f"   lag      cpu replay p50 {pct([r['cpu'] for r in rows], 0.5):.3f} ms, gpu end lag p50 {pct([r['elag'] for r in rows], 0.5):.3f} ms,"
          f" starved {starved:.1f} %, gated outliers {gated:.2f} %; model a {rows[-1]['a']:.3f} b {rows[-1]['b']:.3f} sigma {rows[-1]['sigma']:.3f}")
    # load steps (dev rig): the overrun right after a load step (the controller's reaction) vs in the settled second half
    # of each phase (the spikes no scale removes), and the scale each phase settles at
    loads = [r["load"] for r in rows]
    if max(loads) > 0:
        steps = [i for i in range(1, len(rows)) if abs(loads[i] - loads[i - 1]) > 0.25 * max(1, max(loads))]
        bounds = [0] + steps + [len(rows)]
        tr_up, tr_dn, st_hi, st_lo, sc_hi, sc_lo, reach = [], [], [], [], [], [], []
        hi_level = max(loads)
        for k in range(1, len(bounds) - 1):
            i, j = bounds[k], bounds[k + 1]
            up = loads[i] > loads[i - 1]
            seg = rows[i:min(j, i + 30)]
            over = sum(1 for r in seg if r["gpu"] > budget) / max(1, len(seg)) * 100.0
            settled = rows[(i + j) // 2:j]
            sover = sum(1 for r in settled if r["gpu"] > budget) / max(1, len(settled)) * 100.0
            smed = pct([r["scale"] for r in settled], 0.5)
            (tr_up if up else tr_dn).append(over)
            (st_hi if loads[i] >= hi_level * 0.75 else st_lo).append(sover)
            (sc_hi if loads[i] >= hi_level * 0.75 else sc_lo).append(smed)
            # frames until the scale is within 3 % of the phase's settled median
            n = next((m for m in range(i, j) if abs(rows[m]["scale"] - smed) <= 0.03 * smed), j) - i
            reach.append(n)
        print(f"   load     {len(steps)} steps; first 30 frames after a load-up {mean(tr_up):.1f} % over the interval (after a load-down {mean(tr_dn):.1f} %);"
              f" settled halves: heavy {mean(st_hi):.1f} % over at scale {mean(sc_hi):.3f}, light {mean(st_lo):.1f} % over at scale {mean(sc_lo):.3f};"
              f" frames to reach the settled scale {mean(reach):.0f}")
        res.update({"transient_up": mean(tr_up), "steady_hi_over": mean(st_hi), "steady_lo_over": mean(st_lo), "scale_hi": mean(sc_hi), "scale_lo": mean(sc_lo), "reach": mean(reach)})
    ov = read_overlay(run)
    if win:
        ov = [o for o in ov if win[0] <= o[0] <= win[1]]
    if ov:
        ft = [o[1] for o in ov if o[1] < 1000]
        fps = len(ft) / max(1e-3, sum(ft) / 1000.0)
        late = sum(1 for f in ft if f > 1.5 * budget) / len(ft) * 100.0 if budget > 0 else 0.0
        jit = mean([abs(a - b) for a, b in zip(ft, ft[1:])])
        res.update({"fps": fps, "p99": pct(ft, 0.99), "p999": pct(ft, 0.999), "late": late, "jitter": jit})
        print(f"   present  {fps:.1f} fps  p50 {pct(ft, 0.5):.2f}  p99 {res['p99']:.2f}  p99.9 {res['p999']:.2f} ms,"
              f" late (>1.5x interval) {late:.2f} %, jitter {jit:.3f} ms")
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--all", action="store_true", help="the whole log, not the route window")
    ap.add_argument("--target-pct", type=float, default=90.0)
    ap.add_argument("--csv", action="store_true")
    a = ap.parse_args()
    out = [r for r in (report(run, a.all, a.target_pct) for run in a.runs) if r]
    if a.csv and out:
        keys = list(out[0].keys())
        print(",".join(keys))
        for r in out:
            print(",".join(f"{r.get(k):.4f}" if isinstance(r.get(k), float) else str(r.get(k)) for k in keys))


if __name__ == "__main__":
    sys.exit(main())
