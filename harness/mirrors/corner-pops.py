#!/usr/bin/env python3
"""Does a wall mirror's reflection pop ahead of its wall at a cutaway flip? (2026-10-06, flip save: the upstairs bathroom
mirror by the corner blinked as the player walked near it.)

The game cuts a room's walls away round the player; at a corner the cut flag flips every few tenths of a second while the
player walks. The wall on screen is the chunk texture, which re-bakes frames after the flag changes. When the reflection
followed the live flag it switched first: the bare stock glass for a few frames before the wall went, the reflection over
the gap before it came back. Here, from an explore=mirror run with devMirrorsLog (the "dev attached ... captured -> cut
away (... screen X,Y, epoch_ms=T)" lines) and a full-frame devCapture:

  per flip, the glass's box (its window-px rect from the dev rects log, devMirrorsRectsEvery=1, interpolated while the
  mirror is cut away) over T-0.6 s .. T+0.6 s (cut at the midpoints to the neighbouring flips), and the abrupt changes of the box (mean |difference| to the previous frame's box above a threshold, consecutive frames one event) are counted. The wall and its reflection changing in one frame
  is one event; the reflection first and the wall frames later is two or more.

  python3 harness/mirrors/corner-pops.py <run> [--mirror 6765,5405,1] [--thr 14] [--sheet DIR] [--json out.json]
"""
import argparse, json, os, re, sys
import numpy as np


def load(run):
    cap = os.path.join(run, "capture")
    lines = open(os.path.join(cap, "index.txt")).read().split("\n")
    kv = dict(p.split("=") for p in lines[0].split())
    w, h = int(kv["w"]), int(kv["h"])
    st = np.array([int(x) for x in lines[1:] if x.strip()], dtype=np.int64)
    mm = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(st), mm.size // (w * h * 4))
    return mm[: n * w * h * 4].reshape(n, h, w, 4), st[:n], w, h


def flips(run, mirror):
    out = []
    pat = re.compile(r"mirrors: dev attached \S+ on \S+ at " + re.escape(mirror) + r": (.*?) -> (.*?) \(frame \d+ screen (-?\d+),(-?\d+), epoch_ms=(\d+)\)")
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        m = pat.search(line)
        if not m:
            continue
        a, b = m.group(1).split(" ")[0], m.group(2).split(" ")[0]
        if {a, b} == {"captured", "cut"}:
            out.append({"from": a, "to": b, "sx": int(m.group(3)), "sy": int(m.group(4)), "t": int(m.group(5))})
    return out


def rects(run, mirror):
    """The mirror's window-px rect over time, from the dev rects log (devMirrorsRectsEvery=1): [(t, x, y, w, h)]."""
    out = []
    pat = re.compile(r"\[mirror (-?\d+),(-?\d+) (\d+)x(\d+) @" + re.escape(mirror) + r"\]")
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        if "dev rects" not in line:
            continue
        m, e = pat.search(line), re.search(r"epoch_ms=(\d+)", line)
        if m and e:
            out.append((int(e.group(1)),) + tuple(int(g) for g in m.groups()))
    return out


def rect_at(rs, t):
    """The rect at time t: interpolated between the logged ones round it (the camera moves smoothly; the mirror is not
    logged while it is cut away)."""
    ts = [r[0] for r in rs]
    k = int(np.searchsorted(ts, t))
    if k == 0:
        return rs[0][1:]
    if k >= len(rs):
        return rs[-1][1:]
    a, b = rs[k - 1], rs[k]
    if b[0] - a[0] > 2000:
        return (a if t - a[0] < b[0] - t else b)[1:]
    f = (t - a[0]) / max(1, b[0] - a[0])
    return tuple(int(round(a[i] + (b[i] - a[i]) * f)) for i in range(1, 5))


def analyse(run, mirror, thr, sheet):
    frames, st, w, h = load(run)
    scale = w / 1920.0
    rs = rects(run, mirror)
    if not rs:
        sys.exit("no dev rects for %s (run with --prop devMirrorsLog=true --prop devMirrorsRectsEvery=1)" % mirror)
    res = []
    fls = flips(run, mirror)
    for j, fl in enumerate(fls):
        # the window: 0.6 s each side, cut at the midpoint to a neighbouring flip (they come 0.1-0.7 s apart at a corner)
        lo = fl["t"] - 600 if j == 0 else max(fl["t"] - 600, (fls[j - 1]["t"] + fl["t"]) // 2)
        hi = fl["t"] + 600 if j == len(fls) - 1 else min(fl["t"] + 600, (fl["t"] + fls[j + 1]["t"]) // 2)
        i0 = int(np.searchsorted(st, lo))
        i1 = int(np.searchsorted(st, hi))
        if i0 < 1 or i1 >= len(st):
            continue
        diffs, crops, prev = [], [], None
        for i in range(i0, i1 + 1):
            x, y, bw, bh = rect_at(rs, int(st[i]))
            xa, ya, bw, bh = int(x * scale), int(y * scale), max(4, int(bw * scale)), max(4, int(bh * scale))
            if xa < 0 or ya < 0 or xa + bw > w or ya + bh > h:
                prev = None
                continue
            box = np.ascontiguousarray(frames[i][::-1][ya:ya + bh, xa:xa + bw, :3])
            if prev is not None and prev.shape == box.shape:
                diffs.append(float(np.abs(box.astype(np.float32) - prev).mean()))
            prev = box.astype(np.float32)
            crops.append(box)
        ev, on = [], False
        for k, d in enumerate(diffs):
            if d > thr and not on:
                ev.append(k)
            on = d > thr
        res.append({"t": fl["t"], "flip": fl["from"] + "->" + fl["to"], "frames": len(diffs), "events": len(ev),
                    "event_frames": ev, "max_diff": round(max(diffs) if diffs else 0.0, 1)})
        if sheet and crops:
            from PIL import Image
            os.makedirs(sheet, exist_ok=True)
            hh = min(c.shape[0] for c in crops)
            Image.fromarray(np.concatenate([c[:hh] for c in crops], axis=1)).save(os.path.join(sheet, "flip-%d.png" % fl["t"]))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--mirror", default="6765,5405,1")
    ap.add_argument("--thr", type=float, default=14.0)
    ap.add_argument("--sheet")
    ap.add_argument("--json")
    a = ap.parse_args()
    res = analyse(a.run, a.mirror, a.thr, a.sheet)
    for r in res:
        print(r)
    n = len(res)
    multi = sum(1 for r in res if r["events"] >= 2)
    summary = {"run": os.path.basename(a.run.rstrip("/")), "flips": n, "flips_with_2plus_changes": multi,
               "mean_changes_per_flip": round(sum(r["events"] for r in res) / n, 2) if n else None}
    print(json.dumps(summary))
    if a.json:
        json.dump({"summary": summary, "flips": res}, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    sys.exit(main())
