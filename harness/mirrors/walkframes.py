#!/usr/bin/env python3
"""Frames of an explore=mirror walk (pzopt.MirrorWalk) out of its devCapture (2026-10-04).

Reads <run>/capture (rgba or gray, any crop), the console's `harness: mirror walk:` events (epoch_ms) and writes PNGs:
one per station arrival / director action (+ --after s), plus every --every-th frame with --all, into <run>/walkframes/.
--sheet writes a contact sheet of the event frames. --at EPOCH_MS[,..] writes the frames nearest those times.

  python3 harness/mirrors/walkframes.py <run> [--after 0.8] [--all --every 8] [--sheet] [--at ms,ms]
"""
import argparse, os, re, sys
import numpy as np
from PIL import Image


def load(run):
    cap = os.path.join(run, "capture")
    lines = open(os.path.join(cap, "index.txt")).read().split("\n")
    kv = dict(p.split("=") for p in lines[0].split())
    w, h = int(kv["w"]), int(kv["h"])
    gray = kv.get("fmt") == "gray"
    stamps = np.array([int(x) for x in lines[1:] if x.strip()], dtype=np.int64)
    path = os.path.join(cap, "frames.gray" if gray else "frames.rgba")
    ch = 1 if gray else 4
    n = min(len(stamps), os.path.getsize(path) // (w * h * ch))
    mm = np.memmap(path, dtype=np.uint8, mode="r", shape=(n, h, w, ch) if ch > 1 else (n, h, w))
    return mm, stamps[:n], gray


def frame(mm, i, gray):
    f = np.asarray(mm[i])[::-1]
    return Image.fromarray(f if gray else f[..., :3])


def events(run):
    ev = []
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        if "harness: mirror walk" not in line:
            continue
        m = re.search(r"epoch_ms=(\d+)", line)
        if not m:
            continue
        txt = line.split("harness: ", 1)[1].strip()
        ev.append((int(m.group(1)), re.sub(r"\s*epoch_ms=\d+.*", "", txt)))
    return ev


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--after", type=float, default=0.8, help="seconds after each event")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--every", type=int, default=8)
    ap.add_argument("--sheet", action="store_true")
    ap.add_argument("--at", default="")
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    mm, st, gray = load(a.run)
    out = a.out or os.path.join(a.run, "walkframes")
    os.makedirs(out, exist_ok=True)
    print(f"{len(st)} frames, {(st[-1] - st[0]) / 1000:.1f} s, {mm.shape}")
    picks = []
    for t, txt in events(a.run):
        i = int(np.argmin(np.abs(st - (t + a.after * 1000))))
        if abs(st[i] - t - a.after * 1000) < 2000:
            picks.append((i, txt))
    for s in [x for x in a.at.split(",") if x.strip()]:
        i = int(np.argmin(np.abs(st - int(s))))
        picks.append((i, f"at {s}"))
    names = []
    for k, (i, txt) in enumerate(picks):
        p = os.path.join(out, f"ev{k:02d}-f{i:05d}.png")
        frame(mm, i, gray).save(p)
        names.append(p)
        print(f"{p}  +{(st[i] - st[0]) / 1000:6.1f}s  {txt}")
    if a.all:
        for i in range(0, len(st), a.every):
            frame(mm, i, gray).save(os.path.join(out, f"all-f{i:05d}.png"))
    if a.sheet and names:
        ims = [Image.open(p) for p in names]
        tw = 480
        th = int(ims[0].height * tw / ims[0].width)
        cols = 4
        rows = (len(ims) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * tw, rows * th))
        for k, im in enumerate(ims):
            sheet.paste(im.convert("RGB").resize((tw, th)), ((k % cols) * tw, (k // cols) * th))
        sheet.save(os.path.join(out, "sheet.png"))
        print(os.path.join(out, "sheet.png"))


if __name__ == "__main__":
    sys.exit(main())
