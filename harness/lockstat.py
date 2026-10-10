#!/usr/bin/env python3
"""How close a capped run is to a locked frame rate (2026-10-07, the Dell low-end mode sweep).

usage: harness/lockstat.py [--fps 60] [--skip S] <run dir>... [--csv]

Reads pzopt-overlay.out (presented frame time per frame, epoch_ms) inside the route window of pzopt-bench.out
(route_start_epoch_ms .. route_end_epoch_ms; whole log without one), skips the first S seconds of the route, and
prints one row per run: mean fps, the share of frames that missed the cap's frame period (late = frame time over
1.05 x the period) and the share that missed it for sure (miss: over 1.15 x the period, ~19.2 ms at 60), frames over 25 / 33 / 50 ms, p50 / p99 / p99.9 / max,
the longest run of consecutive late frames, and CPU / GPU / game / render thread load from the same log.
rep% = display refreshes that showed the previous frame again (present.txt on-glass times; the honest lock measure
with vsync on). A run is "locked" when late <= 1 % and nothing passed 33 ms.
"""
import os
import sys


def kv_file(path):
    kv = {}
    try:
        with open(path) as f:
            for line in f:
                if "=" in line:
                    k, v = line.strip().split("=", 1)
                    kv[k] = v
    except OSError:
        pass
    return kv


def pct(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    i = min(len(sorted_vals) - 1, max(0, int(round(p / 100.0 * (len(sorted_vals) - 1)))))
    return sorted_vals[i]


def glass(run, a, b):
    """Share of display refreshes in [a, b] epoch ms that repeated the previous frame, from present.txt (the present
    timestamps of each frame on glass) and the mode refresh in vrr.txt; None without them."""
    pf, vf = os.path.join(run, "present.txt"), os.path.join(run, "vrr.txt")
    if not (os.path.exists(pf) and os.path.exists(vf)):
        return None
    hz = None
    with open(vf) as f:
        for line in f:
            p = line.split()
            if len(p) >= 5 and not line.startswith("#"):
                hz = float(p[4])
    if not hz:
        return None
    off, ts = 0, set()
    with open(pf) as f:
        for line in f:
            if line.startswith("# mono_to_epoch_us"):
                off = int(line.split()[-1])
                continue
            p = line.split()
            if line.startswith("#") or len(p) < 4:
                continue
            t = (int(p[0]) + off) / 1000.0
            if (a is None or t >= a) and (b is None or t <= b):
                ts.add(t)
    ts = sorted(ts)
    if len(ts) < 10:
        return None
    period = 1000.0 / hz
    slots = sum(max(1, round((y - x) / period)) for x, y in zip(ts, ts[1:]))
    return 100.0 * (slots - (len(ts) - 1)) / slots


def stats(run, fps, skip):
    path = os.path.join(run, "pzopt-overlay.out")
    if not os.path.exists(path):
        return None
    kv = kv_file(os.path.join(run, "pzopt-bench.out"))
    a = int(kv["route_start_epoch_ms"]) + skip * 1000 if "route_start_epoch_ms" in kv else None
    b = int(kv["route_end_epoch_ms"]) if "route_end_epoch_ms" in kv else None
    ft, cpu, gpu, game, rend = [], [], [], [], []
    with open(path) as f:
        head = f.readline().strip().split(",")
        ix = {n: i for i, n in enumerate(head)}
        for line in f:
            p = line.strip().split(",")
            if len(p) < len(head):
                continue
            try:
                t = int(p[ix["epoch_ms"]])
                v = float(p[ix["frametime"]])
            except ValueError:
                continue
            if (a is not None and t < a) or (b is not None and t > b):
                continue
            ft.append(v)
            for arr, name in ((cpu, "cpu_load"), (gpu, "gpu_load"), (game, "game_load"), (rend, "render_load")):
                try:
                    arr.append(float(p[ix[name]]))
                except (KeyError, ValueError):
                    pass
    if len(ft) < 10:
        return None
    period = 1000.0 / fps
    late_ms = period * 1.05
    s = sorted(ft)
    late = [v > late_ms for v in ft]
    run_len = best = 0
    for x in late:
        run_len = run_len + 1 if x else 0
        best = max(best, run_len)
    secs = sum(ft) / 1000.0
    mean = lambda arr: sum(arr) / len(arr) if arr else float("nan")
    r = {
        "run": os.path.basename(run.rstrip("/")),
        "frames": len(ft),
        "fps": len(ft) / secs if secs > 0 else 0,
        "late%": 100.0 * sum(late) / len(ft),
        "miss%": 100.0 * sum(v > period * 1.15 for v in ft) / len(ft),
        ">25": sum(v > 25 for v in ft),
        ">33": sum(v > 33.4 for v in ft),
        ">50": sum(v > 50 for v in ft),
        "p50": pct(s, 50), "p99": pct(s, 99), "p99.9": pct(s, 99.9), "max": s[-1],
        "lateRun": best,
        "cpu": mean(cpu), "gpu": mean(gpu), "game": mean(game), "render": mean(rend),
        "secs": secs,
    }
    g = glass(run, a, b)
    r["rep%"] = g if g is not None else float("nan")
    r["locked"] = r["late%"] <= 1.0 and r[">33"] == 0
    return r


def main():
    args = sys.argv[1:]
    fps, skip, csv = 60.0, 0.0, False
    runs = []
    i = 0
    while i < len(args):
        if args[i] == "--fps":
            fps = float(args[i + 1]); i += 2
        elif args[i] == "--skip":
            skip = float(args[i + 1]); i += 2
        elif args[i] == "--csv":
            csv = True; i += 1
        else:
            runs.append(args[i]); i += 1
    if not runs:
        print(__doc__)
        sys.exit(2)
    cols = ["fps", "rep%", "late%", "miss%", ">25", ">33", ">50", "p50", "p99", "p99.9", "max", "lateRun", "cpu", "gpu", "game", "render"]
    rows = [r for r in (stats(x, fps, skip) for x in runs) if r]
    if csv:
        print("run,frames,secs," + ",".join(cols) + ",locked")
        for r in rows:
            print(f"{r['run']},{r['frames']},{r['secs']:.1f}," + ",".join(f"{r[c]:.2f}" if isinstance(r[c], float) else str(r[c]) for c in cols) + f",{int(r['locked'])}")
        return
    w = max([len(r["run"]) for r in rows] + [4])
    print(f"{'run':<{w}}  {'fps':>5} {'rep%':>5} {'late%':>6} {'miss%':>6} {'>25':>4} {'>33':>4} {'>50':>4} {'p50':>5} {'p99':>5} {'p99.9':>6} {'max':>6} {'lrun':>4} {'cpu':>4} {'gpu':>4} {'game':>4} {'rend':>4}")
    for r in rows:
        print(f"{r['run']:<{w}}  {r['fps']:5.1f} {r['rep%']:5.1f} {r['late%']:6.2f} {r['miss%']:6.2f} {r['>25']:4d} {r['>33']:4d} {r['>50']:4d} {r['p50']:5.1f} {r['p99']:5.1f} {r['p99.9']:6.1f} {r['max']:6.1f} {r['lateRun']:4d} {r['cpu']:4.0f} {r['gpu']:4.0f} {r['game']:4.0f} {r['render']:4.0f}" + ("  LOCKED" if r["locked"] else ""))


if __name__ == "__main__":
    main()
