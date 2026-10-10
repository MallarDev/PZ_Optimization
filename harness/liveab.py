#!/usr/bin/env python3
"""Per-window frame cost of a run that switched live keys with --flag live_set (2026-10-07, the Dell low-end sweep).

usage: harness/liveab.py [--fps 60] [--skip 2] [--pair] <run dir>...

The harness logs `harness: live_set <key>=<value> at t=<s>s` (seconds after the route start) for every switch. The run
is cut at those switches; each window is labelled with the keys that differ from the run's starting state ("base" when
none), its first --skip seconds dropped (the switch's own re-bakes), and scored from pzopt-overlay.out: frames, mean frame
time, late % (frame time over 1.05 x the cap period), p99, GPU ms a frame (gpu_ms, the GL timer query), CPU / GPU /
game / render load. --pair also prints, for every non-base window, the difference against the mean of the base
windows right before and after it (trend-free cost of that state).
"""
import os
import re
import sys

LIVE = re.compile(r"harness: live_set (\S+?)=(\S*) at t=([0-9.]+)s")


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


def overlay(run):
    rows = []
    with open(os.path.join(run, "pzopt-overlay.out")) as f:
        head = f.readline().strip().split(",")
        ix = {n: i for i, n in enumerate(head)}
        for line in f:
            p = line.strip().split(",")
            if len(p) < len(head):
                continue
            try:
                rows.append({n: float(p[i]) for n, i in ix.items()})
            except ValueError:
                continue
    return rows


def score(rows, fps):
    if len(rows) < 5:
        return None
    ft = sorted(r["frametime"] for r in rows)
    n = len(ft)
    late = sum(v > 1050.0 / fps for v in ft)
    m = lambda k: sum(r.get(k, 0.0) for r in rows) / n
    return {"n": n, "ft": sum(ft) / n, "late": 100.0 * late / n, "p99": ft[min(n - 1, int(0.99 * (n - 1)))],
            "gpu_ms": m("gpu_ms"), "cpu": m("cpu_load"), "gpu": m("gpu_load"), "game": m("game_load"), "render": m("render_load")}


def windows(run, fps, skip):
    kv = kv_file(os.path.join(run, "pzopt-bench.out"))
    t0 = int(kv["route_start_epoch_ms"])
    t_end = int(kv.get("route_end_epoch_ms", 0)) or None
    events = []
    with open(os.path.join(run, "console.txt"), errors="replace") as f:
        for line in f:
            m = LIVE.search(line)
            if m:
                events.append((float(m.group(3)), m.group(1), m.group(2)))
    rows = overlay(run)
    state, base = {}, {}
    for _, k, v in events:
        base.setdefault(k, None)
    bounds = [0.0] + [e[0] for e in events] + [((t_end - t0) / 1000.0) if t_end else 1e9]
    out = []
    for i in range(len(bounds) - 1):
        if i > 0:
            _, k, v = events[i - 1]
            if base[k] is None:  # the first value a key is set to after its start is "on"; its start state is "off"
                base[k] = "?"
            state[k] = v
        a, b = bounds[i] + skip, bounds[i + 1]
        sel = [r for r in rows if t0 + a * 1000 <= r["epoch_ms"] < t0 + b * 1000]
        s = score(sel, fps)
        label = " ".join(f"{k}={v}" for k, v in sorted(state.items()) if v not in ("false", "off", "0", "none", "stock")) or "base"
        if s:
            out.append((bounds[i], label, s))
    return out


def main():
    args = sys.argv[1:]
    fps, skip, pair, runs = 60.0, 2.0, False, []
    i = 0
    while i < len(args):
        if args[i] == "--fps":
            fps = float(args[i + 1]); i += 2
        elif args[i] == "--skip":
            skip = float(args[i + 1]); i += 2
        elif args[i] == "--pair":
            pair = True; i += 1
        else:
            runs.append(args[i]); i += 1
    for run in runs:
        ws = windows(run, fps, skip)
        print(f"== {os.path.basename(run.rstrip('/'))}")
        print(f"  {'t':>5} {'frames':>6} {'ft ms':>6} {'late%':>6} {'p99':>6} {'gpu_ms':>6} {'cpu':>4} {'gpu':>4} {'game':>4} {'rend':>4}  state")
        for t, label, s in ws:
            print(f"  {t:5.1f} {s['n']:6d} {s['ft']:6.2f} {s['late']:6.1f} {s['p99']:6.1f} {s['gpu_ms']:6.2f} {s['cpu']:4.0f} {s['gpu']:4.0f} {s['game']:4.0f} {s['render']:4.0f}  {label}")
        if pair:
            print("  paired against the neighbouring base windows:")
            for j, (t, label, s) in enumerate(ws):
                if label == "base":
                    continue
                nb = [ws[k][2] for k in (j - 1, j + 1) if 0 <= k < len(ws) and ws[k][1] == "base"]
                if not nb:
                    continue
                d = lambda key: s[key] - sum(x[key] for x in nb) / len(nb)
                print(f"    {label:<40} ft {d('ft'):+6.2f} ms  late {d('late'):+6.1f}  gpu_ms {d('gpu_ms'):+6.2f}  game {d('game'):+5.1f}  render {d('render'):+5.1f}")


if __name__ == "__main__":
    main()
