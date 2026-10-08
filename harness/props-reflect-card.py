#!/usr/bin/env python3
"""The New! card for reflective props (2026-10-09, docs/findings-prop-reflections-2026-10-08.md) in the AVIFs' template
(harness/efficiency-anim.py: 630 square, the left strip with its label, Martian Mono, stock left of the white divider and
enhanced right of it, the New! cards' sweep), replacing the entity shadows card on the Workshop page.

Scene: a Rosewood back yard (the house at 7944-7951,11496-11510 has two sliding glass doors on its west wall, 7951,11503-04 and
11507-08), a glass table (furniture_tables_low_01_8/9, "Fancy Low Glass") placed on the patio at 7952-53,11509, noon, clear.
The player walks on foot (explore=walk, the movement keys) back and forth along both doors, half a square from the glass,
turning round just north-west of the table (3.3 s a leg). Two in-game devCapture runs of the same walk (1:1 crop of the
5120x2160 screen round the player, 30 fps; the legs end within 0.2 s of each other):

    stock     gpc2-stock-cap  --prop enabled=false
    enhanced  gpc2-enh-cap    every Visuals card on but remembered places (memoryTint), HDR and upscaling off (SDR capture)

    common: --mode bench --flag zombies=off --flag time_of_day=12 --flag weather=clear --flag hide_ui=true
            --flag start=7952,11503 --flag place_tile=furniture_tables_low_01_8@7952,11509/furniture_tables_low_01_9@7953,11509
            --flag explore=walk --flag "walk=7951.0,11502.3;7951.0,11508.3"
            --flag route=S:1 --flag speed=0.04 (a 25 s route) --flag zoom=0.5 --prop overlay=false
            --prop devCapture=5,26,30,100,crop=2010:450:1100:1260,ram -Dpzopt.userOptionsFile=<empty file>

Two segments, one divider cycle each (SEGMENTS): the patio with both doors (the whole capture, halved), then 1:1 round the
player, the enhanced holds on his turns at the table (the doors and the sky in its top, his image in the door beside it), the
stock hold at the north door. A turning walk was tried first (runs gpc-*): the turn beside the table overshot into it and the
stock player stuck there. The stock frame shown is the best image match within 0.35 s of the enhanced frame's route time
(never going back), so the two sides stay in step through the ~0.2 s drift between the walks.

    python3 harness/props-reflect-card.py [--still png --seg N --t S] [--gif] [--out workshop-media/props-reflect.avif]
"""
import argparse
import glob
import importlib.util
import os
import shutil

import numpy as np
from PIL import Image

spec = importlib.util.spec_from_file_location('anim', os.path.join(os.path.dirname(__file__), 'efficiency-anim.py'))
anim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anim)

RUNS = ('gpc2-stock-cap', 'gpc2-enh-cap')
LABEL = 'Glass Reflections'
# (route second the segment starts, crop x, y, w, h in the capture (rows top-down)): each crop has the scene's aspect
SEGMENTS = [
    (6.0, (0, 0, 1100, 1260)),     # second lap (the yard's unseen squares are black in the first seconds): south past both
                                   # doors to the table (stock hold), north again
    (9.3, (275, 380, 550, 630)),   # 1:1: turns at the table at 9.9 and 16.5 s (enhanced holds), the north door at 13.2 (stock)
]


def load(label):
    d = sorted(glob.glob(f'harness/runs/{label}-2*'))[-1]
    lines = open(f'{d}/capture/index.txt').read().split('\n')
    head = dict(x.split('=') for x in lines[0].split())
    w, h = int(head['w']), int(head['h'])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()])
    r0 = int(anim.kv(f'{d}/pzopt-schedule.out')['route_start_epoch_ms'])
    raw = np.memmap(f'{d}/capture/frames.rgba', np.uint8, mode='r')
    n = min(len(stamps), raw.size // (w * h * 4))
    print(label, d, n, 'frames')
    return raw[:n * w * h * 4].reshape(n, h, w, 4), (stamps[:n] - r0) / 1000.0


def thumbs(cap):
    """A normalised 55 x 63 grey thumbnail per frame: what the stock frame is matched on (the camera follows the player)."""
    out = []
    for f in cap[0]:
        g = f[::-20, ::20, :3].astype(np.float32).mean(2)
        out.append((g - g.mean()) / (g.std() + 1e-6))
    return np.array(out)


class Matcher:
    """The stock frame for an enhanced one: best thumbnail match within WINDOW s of the same route time, never before the last."""
    WINDOW = 0.35

    def __init__(self, stock, enh):
        self.ts, self.te = stock[1], enh[1]
        self.gs, self.ge = thumbs(stock), thumbs(enh)
        self.last = 0

    def __call__(self, t):
        ie = int(np.argmin(np.abs(self.te - t)))
        js = [j for j in np.flatnonzero(np.abs(self.ts - self.te[ie]) < self.WINDOW) if j >= self.last] or [self.last]
        self.last = min(js, key=lambda j: np.abs(self.gs[j] - self.ge[ie]).mean())
        return self.last, ie


def frame(cap, i, box, sw, sh):
    x, y, w, h = box
    f = cap[0][i][::-1][y:y + h, x:x + w, :3]   # capture rows are bottom-up
    im = Image.fromarray(np.ascontiguousarray(f))
    return np.asarray(im if im.size == (sw, sh) else im.resize((sw, sh), Image.LANCZOS))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--size', type=int, default=630)
    ap.add_argument('--theme', default='light', choices=anim.THEMES)
    ap.add_argument('--crf', default='35')
    ap.add_argument('--gif', action='store_true', help='a review GIF (gifski) instead of the AVIF')
    ap.add_argument('--gif-fps', default='25')
    ap.add_argument('--still', help='one composed frame to this PNG (segment --seg, --t s in, the divider at a third)')
    ap.add_argument('--seg', type=int, default=0)
    ap.add_argument('--t', type=float, default=2.0)
    ap.add_argument('--out')
    a = ap.parse_args()
    cw = sh = a.size
    s = cw / 630
    strip = round(80 * s)
    strip += strip % 2
    k = 0.75 if a.size <= 700 else 1.0
    sw = cw - strip
    base = np.asarray(anim.chrome(a.theme, LABEL, sh, cw, strip, round(42 * s), round(26 * k))).copy()
    ov = anim.visual_overlay(('STOCK', 'ENHANCED'), sw, sh, k)
    caps = [load(l) for l in RUNS]
    match = Matcher(*caps)
    if a.still:
        t0, box = SEGMENTS[a.seg]
        left, right = (frame(c, i, box, sw, sh) for c, i in zip(caps, match(t0 + a.t)))
        Image.fromarray(anim.compose(base, left, right, ov, sw // 3, strip)).save(a.still)
        print('still', a.still)
        return
    seq = anim.divider_positions()
    work = f'build/anim/props-reflect-{cw}'
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(f'{work}/frames')
    i = 0
    for t0, box in SEGMENTS:
        match.last = 0
        for j, pos in enumerate(seq):
            left, right = (frame(c, i, box, sw, sh) for c, i in zip(caps, match(t0 + j / 60.0)))
            Image.fromarray(anim.compose(base, left, right, ov, round(sw * pos), strip)).save(f'{work}/frames/f{i:05d}.png', compress_level=1)
            i += 1
    print(f'== props-reflect: {i} frames {cw}x{sh}')
    out = a.out or ('workshop-media/template-props-reflect.gif' if a.gif else 'workshop-media/props-reflect.avif')
    anim.encode(a, work, base.shape, out)


if __name__ == '__main__':
    main()
