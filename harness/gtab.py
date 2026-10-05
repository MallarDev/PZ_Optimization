#!/usr/bin/env python3
"""Within-run A/B of the game-thread offload keys (devGtAlternate=N devGtAlternateKeys=...): the listed keys switch off
and on every N ms of one run; this splits the in-game overlay's frames (pzopt-overlay.out) by phase and compares them,
and splits the game-thread stack profile (pzopt-stacks.out, whole seconds inside one half) to show which methods moved.

    gtab.py [--skip S] [--all] [--frames F1,F2,...] <run dir> [<run dir>...]

--skip: seconds after the alternation starts that are ignored (default 3). By default only the route window
(pzopt-bench.out) counts; --all takes every frame after the skip. --frames: the methods whose inclusive share is
compared between the halves (default: the offload targets). Frames within 150 ms of a switch are skipped (the render
thread lags the game thread). Paired: each on period against the mean of the off periods on either side of it.
"""
import argparse
import csv
import math
import os
import re
import statistics

DEFAULT_FRAMES = ("pzopt.PixelLight.beforeComposite,pzopt.PixelLight.pack,pzopt.CapsuleShadow.add,pzopt.CapsuleShadow.addAtlas,"
                  "pzopt.Ssr.addMoving,MovingObjectUpdateScheduler.startFrame,IsoAnimal.updateLOS,IsoZombie.update,"
                  "FBORenderCell.renderMovingObjects,IsoWorld.renderWeatherFX,FBORenderCell.renderOneChunk,"
                  "SpriteRenderer.waitForReadySlotToOpen,pzopt.Pacing.limiterWait,GameWindow.logic,IsoWorld.render")


def route_window(run):
    p = os.path.join(run, "pzopt-bench.out")
    if not os.path.exists(p):
        return None
    kv = dict(l.rstrip("\n").split("=", 1) for l in open(p) if "=" in l)
    if "route_start_epoch_ms" in kv and "route_end_epoch_ms" in kv:
        return int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    return None


def stats(v):
    v = sorted(v)
    n = len(v)
    if not n:
        return 0, 0, 0, 0
    return sum(v) / n, v[n // 2], v[min(n - 1, int(n * 0.99))], v[min(n - 1, int(n * 0.999))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--skip", type=float, default=3.0)
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--frames", default=DEFAULT_FRAMES)
    a = ap.parse_args()
    want = [f for f in a.frames.split(",") if f]
    for run in a.runs:
        per = t0 = None
        keys = ""
        for line in open(os.path.join(run, "console.txt"), errors="replace"):
            m = re.search(r"gt ab: alternating every (\d+) ms from epoch_ms (\d+) \(on first\), keys (\S*)", line)
            if m:
                per, t0, keys = int(m.group(1)), int(m.group(2)), m.group(3)
        if per is None:
            print(run, ": no alternation")
            continue
        win = None if a.all else route_window(run)
        lo = max(t0 + a.skip * 1000, win[0] if win else 0)
        hi = win[1] if win else 1 << 62
        halves = {True: [], False: []}
        gpu = {True: [], False: []}
        periods, pgpu = {}, {}
        with open(os.path.join(run, "pzopt-overlay.out")) as fh:
            for r in csv.DictReader(fh):
                e = int(r["epoch_ms"])
                if e < lo or e > hi:
                    continue
                ph = (e - t0) % per
                if ph < 150 or ph > per - 150:
                    continue
                k = (e - t0) // per
                on = k % 2 == 0
                ft, g = float(r["frametime"]), float(r["gpu_ms"])
                halves[on].append(ft)
                gpu[on].append(g)
                periods.setdefault(k, []).append(ft)
                pgpu.setdefault(k, []).append(g)
        if not halves[True] or not halves[False]:
            print(run, ": no frames in the window")
            continue
        (m1, p1, q1, r1), (m0, p0, q0, r0) = stats(halves[True]), stats(halves[False])
        g1, g0 = statistics.mean(gpu[True]), statistics.mean(gpu[False])
        print("%s  (keys %s, every %d ms)" % (os.path.basename(run.rstrip("/")), keys, per))
        print("  on : %6d frames  mean %.3f ms (%.1f fps)  p50 %.3f  p99 %.3f  p99.9 %.3f  gpu %.3f ms" % (len(halves[True]), m1, 1000 / m1, p1, q1, r1, g1))
        print("  off: %6d frames  mean %.3f ms (%.1f fps)  p50 %.3f  p99 %.3f  p99.9 %.3f  gpu %.3f ms" % (len(halves[False]), m0, 1000 / m0, p0, q0, r0, g0))
        print("  on - off: %+.1f us a frame (%+.2f %% frame time, %+.2f %% fps)" % ((m1 - m0) * 1000, (m1 / m0 - 1) * 100, (m0 / m1 - 1) * 100))
        last = max(periods)
        med = {k: statistics.mean(v) for k, v in periods.items() if len(v) >= 10 and k < last}
        d = []
        for k in med:
            if k % 2 == 0 and k - 1 in med and k + 1 in med:
                d.append((med[k] - (med[k - 1] + med[k + 1]) / 2) * 1000)
        if len(d) >= 3:
            se = statistics.stdev(d) / math.sqrt(len(d))
            print("  paired means (%d on periods): %+.1f us +- %.1f (SE), median %+.1f us" % (len(d), statistics.mean(d), se, statistics.median(d)))
        # the same pairing on each period's median frame (robust to the hitches a horde run is full of)
        pm = {k: statistics.median(v) for k, v in periods.items() if len(v) >= 10 and k < last}
        dm = [(pm[k] - (pm[k - 1] + pm[k + 1]) / 2) * 1000 for k in pm if k % 2 == 0 and k - 1 in pm and k + 1 in pm]
        if len(dm) >= 3:
            se = statistics.stdev(dm) / math.sqrt(len(dm))
            print("  paired period medians: %+.1f us +- %.1f (SE), median %+.1f us  (%+.2f %% of the off median %.3f ms)"
                  % (statistics.mean(dm), se, statistics.median(dm), statistics.mean(dm) / 10 / p0, p0))
        # the game thread's CPU per frame (pzopt-gtab.out): what the keys take off it, and where the process CPU went
        gp = os.path.join(run, "pzopt-gtab.out")
        if os.path.exists(gp):
            cpu = {1: [], 0: []}
            proc = {1: [], 0: []}
            wall = {1: [], 0: []}
            zu = {1: [], 0: []}
            sec = {1: [], 0: []}
            sec_k = {}
            per_k = {}
            names = ["startFrame", "schedUpdate", "animalLos", "playerLos", "pplBeforeComposite", "renderMovingObjects", "performRenderTiles", "postupdate", "visPolyRenderMain", "aoFlush", "chunkMapUpdate", "popmanUpdate", "lightingUpdate", "logic", "finishAnimation", "renderInternal", "sceneCull", "atlases", "cellRender"]
            for line in open(gp):
                if line.startswith("#"):
                    # the header names the section columns (newer builds append columns: read them from here)
                    m = re.search(r"ns per section: (.*?)\s+\(", line)
                    if m:
                        names = m.group(1).split()
                    continue
                f = line.split()
                if len(f) < 5:
                    continue
                e, ph, c, pc, w = int(f[0]), int(f[1]), int(f[2]), int(f[3]), int(f[4])
                if e < lo or e > hi:
                    continue
                k = (e - t0) // per
                if (k % 2 == 0) != (ph == 1):
                    continue  # the frame that straddles a switch
                cpu[ph].append(c / 1e6)
                proc[ph].append(pc / 1e6)
                wall[ph].append(w / 1e6)
                if len(f) >= 6:
                    zu[ph].append(int(f[5]))
                if len(f) >= 14:
                    v = [int(x) / 1e6 for x in f[6:6 + len(names)]]
                    v += [0.0] * (len(names) - len(v))
                    sec[ph].append(v)
                    sec_k.setdefault(k, []).append(v)
                per_k.setdefault(k, []).append(c / 1e6)
            if cpu[1] and cpu[0]:
                c1, c0 = statistics.mean(cpu[1]), statistics.mean(cpu[0])
                q1, q0 = statistics.mean(proc[1]), statistics.mean(proc[0])
                w1, w0 = statistics.mean(wall[1]), statistics.mean(wall[0])
                print("  game-thread CPU a frame: on %.3f ms (%d frames, wall %.3f)  off %.3f ms (%d frames, wall %.3f)  on - off %+.1f us (%+.1f %%)"
                      % (c1, len(cpu[1]), w1, c0, len(cpu[0]), w0, (c1 - c0) * 1000, (c1 / c0 - 1) * 100))
                print("  game-thread CPU / wall: on %.1f %%  off %.1f %%;  process CPU a frame: on %.3f ms  off %.3f ms  (%+.1f us)"
                      % (100 * c1 / w1, 100 * c0 / w0, q1, q0, (q1 - q0) * 1000))
                if zu[1] and zu[0]:
                    print("  zombie updates a frame: on %.1f  off %.1f" % (statistics.mean(zu[1]), statistics.mean(zu[0])))
                if sec[1] and sec[0]:
                    print("  sections, ms a frame (count columns pu_zombies / pu_moved / pu_collided: millionths of a count) (on / off / on - off, paired per period +- SE):")
                    for j, nm in enumerate(names):
                        a1 = statistics.mean(r[j] for r in sec[1])
                        a0 = statistics.mean(r[j] for r in sec[0])
                        pm_ = {k: statistics.mean(r[j] for r in v) for k, v in sec_k.items() if len(v) >= 10}
                        dd = [(pm_[k] - (pm_[k - 1] + pm_[k + 1]) / 2) * 1000 for k in pm_ if k % 2 == 0 and k - 1 in pm_ and k + 1 in pm_]
                        extra = ""
                        if len(dd) >= 3:
                            extra = "   paired %+.1f us +- %.1f" % (statistics.mean(dd), statistics.stdev(dd) / math.sqrt(len(dd)))
                        print("    %-22s %8.3f %8.3f %+8.1f us%s" % (nm, a1, a0, (a1 - a0) * 1000, extra))
                pk = {k: statistics.mean(v) for k, v in per_k.items() if len(v) >= 10}
                dk = [(pk[k] - (pk[k - 1] + pk[k + 1]) / 2) * 1000 for k in pk if k % 2 == 0 and k - 1 in pk and k + 1 in pk]
                if len(dk) >= 3:
                    print("  paired game-thread CPU (%d on periods): %+.1f us +- %.1f (SE), median %+.1f us"
                          % (len(dk), statistics.mean(dk), statistics.stdev(dk) / math.sqrt(len(dk)), statistics.median(dk)))
        # the game-thread profile per half: whole seconds inside one half
        sp = os.path.join(run, "pzopt-stacks.out")
        if not os.path.exists(sp):
            continue
        names = {}
        tot = {True: 0, False: 0}
        inc = {True: {}, False: {}}
        side = None
        for line in open(sp):
            if line.startswith("#"):
                continue
            if line.startswith("f "):
                _, i, n = line.rstrip("\n").split(" ", 2)
                names[i] = n
                continue
            if line.startswith("t "):
                t = int(line.split()[1])
                side = None
                if lo <= t and t + 1000 <= hi:
                    k0, k1 = (t - t0) // per, (t + 999 - t0) // per
                    if k0 == k1:
                        side = k0 % 2 == 0
                continue
            if side is None:
                continue
            ids, n = line.rsplit(" ", 1)
            n = int(n)
            tot[side] += n
            fr = set(names.get(x, x) for x in ids.split(";"))
            for f in want:
                if f in fr:
                    inc[side][f] = inc[side].get(f, 0) + n
        if tot[True] and tot[False]:
            print("  game-thread samples: on %d, off %d (whole seconds inside a half); inclusive share of a frame, ms a frame at the half's mean:" % (tot[True], tot[False]))
            for f in want:
                s1 = inc[True].get(f, 0) / tot[True]
                s0 = inc[False].get(f, 0) / tot[False]
                if s1 == 0 and s0 == 0:
                    continue
                print("    %-50s on %5.1f%% (%.2f ms)  off %5.1f%% (%.2f ms)" % (f, 100 * s1, s1 * m1, 100 * s0, s0 * m0))


if __name__ == "__main__":
    main()
