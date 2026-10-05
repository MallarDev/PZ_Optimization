#!/usr/bin/env python3
"""A JVM heap / collector sweep as one table: every run whose label starts with PREFIX, grouped by its label without the
trailing -rN, the reps averaged (frame tail from the in-game overlay log, GC from every gc.log segment, both inside the
route window; boot from schedule.log). Usage: gc-sweep.py PREFIX [PREFIX...]   e.g. gc-sweep.py gch-drive- gch-spin-

Columns: fps mean, p99 / p99.9 / max frame (ms), frames > 2x the median, 1%-low fps; collector pauses in the route
(count, total ms, max ms; ZGC: its allocation stalls, its pauses are sub-millisecond), the highest heap the log shows
in use before a collection and committed (MB), CPU % (sysmon, whole machine), world-ready seconds after launch.
"""
import re, sys, statistics
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import analyze  # noqa: E402

RUNS = Path(__file__).resolve().parent / "runs"
HEAP = re.compile(r"(\d+)M(?:\(\d+%\))?->(\d+)M(?:\(\d+%\))?(?:\((\d+)M\))?")


def heap_peaks(run):
    """(highest heap in use before a collection, highest committed) in MB over the whole run, from every gc.log segment."""
    used = committed = 0
    for g in sorted(run.glob("gc.log*")):
        for line in g.read_text(errors="replace").splitlines():
            m = HEAP.search(line)
            if m:
                used = max(used, int(m.group(1)))
                if m.group(3):
                    committed = max(committed, int(m.group(3)))
    return used, committed


def stalls(run, a, b):
    import datetime
    n = ms = 0.0
    for g in sorted(run.glob("gc.log*")):
        for line in g.read_text(errors="replace").splitlines():
            if "Allocation Stall" not in line:
                continue
            m = re.match(r"^\[(\S+?)\].* ([\d.]+)ms$", line)
            if m and a <= datetime.datetime.fromisoformat(m.group(1)).timestamp() <= b:
                n += 1
                ms += float(m.group(2))
    return n, ms


def one(run):
    s = analyze.summarize(run)
    o = s.get("overlay") or s.get("mangohud")
    if not o or not s.get("valid", True):
        return None
    lines = (run / "pzopt-overlay.out").read_text().splitlines() if (run / "pzopt-overlay.out").exists() else []
    kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
    a, b = int(kv["route_start_epoch_ms"]) / 1000, int(kv["route_end_epoch_ms"]) / 1000
    spikes = 0
    if lines:
        h = lines[0].split(",")
        it, ie = h.index("frametime"), h.index("epoch_ms")
        ft = []
        for l in lines[1:]:
            p = l.split(",")
            try:
                if a * 1000 <= int(p[ie]) <= b * 1000:
                    ft.append(float(p[it]))
            except (ValueError, IndexError):
                pass
        if ft:
            med = statistics.median(ft)
            spikes = sum(1 for f in ft if f > 2 * med)
    g = s.get("gc") or {}
    sn, sms = stalls(run, a, b)
    used, committed = heap_peaks(run)
    ready = None
    sched = run / "schedule.log"
    if sched.exists():
        m = re.search(r"world ready (\d+) s after launch", sched.read_text())
        ready = int(m.group(1)) if m else None
    u = o["us"]
    sm = s.get("sysmon") or {}
    cpu = sm.get("cpu_pct", {}).get("mean") if isinstance(sm.get("cpu_pct"), dict) else None
    return dict(fps=o["count"] / o["seconds"], p99=u["p99"] / 1000, p999=u["p99_9"] / 1000, max=u["max"] / 1000, spikes=spikes,
                low=o["fps_1pct_low"], collector=g.get("collector") or "?",
                pauses=g.get("pauses_in_route", sn), pause_ms=g.get("pause_ms_in_route", sms), pause_max=g.get("max_pause_ms", 0),
                used=used, committed=committed, cpu=cpu, ready=ready)


def main():
    groups = {}
    for prefix in sys.argv[1:]:
        for run in sorted(RUNS.glob(prefix + "*")):
            label = re.sub(r"-\d{8}-\d{6}$", "", run.name)
            key = re.sub(r"-r\d+$", "", label)
            try:
                r = one(run)
            except Exception as e:  # an unfinished or broken run
                print(f"skip {run.name}: {e}", file=sys.stderr)
                continue
            if r:
                groups.setdefault(key, []).append(r)
    cols = ("fps", "p99", "p999", "max", "spikes", "low", "pauses", "pause_ms", "pause_max", "used", "committed", "cpu", "ready")
    print(f"{'config':32s} n  {'fps':>6s} {'p99':>6s} {'p99.9':>6s} {'max':>6s} {'spk':>4s} {'1%low':>6s}  gc  {'paus':>4s} {'ms':>6s} {'max':>5s}  "
          f"{'used':>5s} {'comm':>5s} {'cpu%':>5s} {'ready':>5s}")
    for key, rs in groups.items():
        m = {}
        for c in cols:
            v = [r[c] for r in rs if r[c] is not None]
            m[c] = statistics.mean(v) if v else float("nan")
        print(f"{key:32s} {len(rs)}  {m['fps']:6.1f} {m['p99']:6.2f} {m['p999']:6.2f} {m['max']:6.1f} {m['spikes']:4.0f} {m['low']:6.0f}  "
              f"{rs[0]['collector'][:3]:3s} {m['pauses']:4.0f} {m['pause_ms']:6.1f} {m['pause_max']:5.1f}  {m['used']:5.0f} {m['committed']:5.0f} "
              f"{m['cpu']:5.1f} {m['ready']:5.0f}")


if __name__ == "__main__":
    main()
