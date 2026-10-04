#!/usr/bin/env python3
"""How a mirror's reflected rays ended, from an explore=mirror run with devMirrorsView=5 + devMirrorsViewToggleMs (2026-10-04).

The dev view paints each glass pixel by how its static ray ended: green the floor shortcut, blue a marched hit, magenta a
marched hit under the pane's own floor, yellow the floor landing hidden from the camera (the floor seen last stands
in), orange the same with the hiding object's own colour (its pixel stands in), red the reach fallback (a pixel squares away), black a miss.
For every dev-phase frame: the glass found by those colours, the shares per kind, and a crop of it beside the nearest
normal-phase frame (same place on screen) into <run>/kinds/. Prints per-run totals (JSON with --json).

  python3 harness/mirrors/kinds.py <run> [--every 1] [--json out.json] [--sheet N]
"""
import argparse, json, os, re, sys
import numpy as np
from PIL import Image

KINDS = {"floor": (0, 255, 0), "march": (0, 77, 255), "hidden_landing": (255, 255, 0), "reach_fallback": (255, 0, 0), "under_own_floor": (255, 0, 255), "occluder_standin": (255, 128, 0)}


def classify(f):
    """Kind per pixel by hue (colour grading shifts the dev colours): 0 floor, 1 march, 2 hidden landing, 3 reach,
    4 a marched hit under the pane's own floor, 5 a hidden landing with the landing pixel, -1 none."""
    r, g, b = f[..., 0], f[..., 1], f[..., 2]
    out = np.full(r.shape, -1, np.int8)
    out[(g > 170) & (r < 140) & (b < 110)] = 0
    out[(b > 170) & (r < 90) & (g < 150)] = 1
    out[(r > 190) & (g > 190) & (b < 90)] = 2
    out[(r > 190) & (g > 90) & (g < 170) & (b < 70)] = 5
    out[(r > 190) & (g < 90) & (b < 90)] = 3
    out[(r > 190) & (g < 90) & (b > 190)] = 4
    return out


def biggest(mask, b=8):
    """The largest blob of glass (8 px blocks joined by their neighbours): the mirror, not the windows around it."""
    h, w = mask.shape
    bh, bw = (h + b - 1) // b, (w + b - 1) // b
    pad = np.zeros((bh * b, bw * b), bool)
    pad[:h, :w] = mask
    occ = pad.reshape(bh, b, bw, b).any(axis=(1, 3))
    seen = np.zeros_like(occ)
    best, bestn = None, 0
    for sy, sx in zip(*np.nonzero(occ)):
        if seen[sy, sx]:
            continue
        comp, stack = [], [(sy, sx)]
        seen[sy, sx] = True
        while stack:
            y, x = stack.pop()
            comp.append((y, x))
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < bh and 0 <= nx < bw and occ[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((ny, nx))
        if len(comp) > bestn:
            best, bestn = comp, len(comp)
    keep = np.zeros_like(occ)
    for y, x in best or []:
        keep[y, x] = True
    return mask & np.repeat(np.repeat(keep, b, 0), b, 1)[:h, :w]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--json", default="")
    ap.add_argument("--sheet", type=int, default=12)
    ap.add_argument("--toggle-ms", type=int, default=1000)
    a = ap.parse_args()
    cap = os.path.join(a.run, "capture")
    lines = open(os.path.join(cap, "index.txt")).read().split("\n")
    kv = dict(p.split("=") for p in lines[0].split())
    w, h = int(kv["w"]), int(kv["h"])
    st = np.array([int(x) for x in lines[1:] if x.strip()], dtype=np.int64)
    mm = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(st), mm.size // (w * h * 4))
    mm = mm[: n * w * h * 4].reshape(n, h, w, 4)
    devph = ((st[:n] // a.toggle_ms) & 1) == 0  # devMirrorsViewToggleMs: the dev view on the even periods
    frac = st[:n] % a.toggle_ms
    out = os.path.join(a.run, "kinds")
    os.makedirs(out, exist_ok=True)
    # the mirror Jev is at: the walk's "to mirror N <sprite> at x,y,z" events (run with mirrorsWindows=false so the glass
    # on screen is the mirrors' alone)
    walk = []
    for line in open(os.path.join(a.run, "console.txt"), errors="replace"):
        mt = re.search(r"mirror walk: to mirror \d+ (\S+) at (\S+), station.*epoch_ms=(\d+)", line)
        if mt:
            walk.append((int(mt.group(3)), mt.group(1) + "@" + mt.group(2)))
    tot = {k: 0 for k in KINDS}
    per = {}
    rows, crops = [], []
    for i in range(n):
        if not devph[i] or not (150 < frac[i] < a.toggle_ms - 150):
            continue
        f = np.asarray(mm[i])[::-1, :, :3].astype(np.int16)
        cls = classify(f)
        mask = cls >= 0
        mask = biggest(mask)
        if mask.sum() < 200:
            continue
        cnt = {k: int(((cls == ki) & mask).sum()) for ki, k in enumerate(KINDS)}
        s = sum(cnt.values())
        cur = [m for t, m in walk if t <= st[i]]
        mname = cur[-1] if cur else "before the walk"
        pm = per.setdefault(mname, {k: 0 for k in KINDS})
        for k in KINDS:
            tot[k] += cnt[k]
            pm[k] += cnt[k]
        ys, xs = np.nonzero(mask)
        x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
        rows.append({"frame": int(i), "epoch_ms": int(st[i]), "mirror": mname, "px": s, **{k: round(cnt[k] / s, 3) for k in KINDS}})
        # the nearest normal-phase frame
        cand = [j for j in range(max(0, i - 15), min(n, i + 15)) if not devph[j] and 150 < frac[j] < a.toggle_ms - 150]
        if cand:
            j = min(cand, key=lambda j: abs(st[j] - st[i]))
            g = np.asarray(mm[j])[::-1, :, :3]
            m = 30
            box = (max(0, x0 - m), max(0, y0 - m), min(w, x1 + m), min(h, y1 + m))
            crops.append((Image.fromarray(f.astype(np.uint8)).crop(box), Image.fromarray(g).crop(box), i, j))
    s = sum(tot.values()) or 1
    summary = {"dev_frames": len(rows), "glass_px": s, **{k + "_share": round(tot[k] / s, 4) for k in KINDS},
               "per_mirror": {m: {"glass_px": sum(v.values()), **{k + "_share": round(v[k] / max(1, sum(v.values())), 4) for k in KINDS}} for m, v in per.items()}}
    print(json.dumps(summary))
    if a.json:
        json.dump({"summary": summary, "frames": rows}, open(a.json, "w"), indent=1)
    if crops:
        pick = [crops[int(k * (len(crops) - 1) / max(1, a.sheet - 1))] for k in range(min(a.sheet, len(crops)))]
        cw = max(c[0].width for c in pick) * 3
        ch = max(c[0].height for c in pick) * 3
        sheet = Image.new("RGB", (cw * 2 * min(4, len(pick)), ch * ((len(pick) + 3) // 4)))
        for k, (d, g, i, j) in enumerate(pick):
            x, y = (k % 4) * cw * 2, (k // 4) * ch
            sheet.paste(d.resize((d.width * 3, d.height * 3), Image.NEAREST), (x, y))
            sheet.paste(g.resize((g.width * 3, g.height * 3), Image.NEAREST), (x + cw, y))
        sheet.save(os.path.join(out, "sheet.png"))
        print(os.path.join(out, "sheet.png"))


if __name__ == "__main__":
    sys.exit(main())
