#!/usr/bin/env python3
"""The still under the reflective props card's AVIF (harness/props-reflect-card.py), in the entity shadows still's layout
(harness/entity-shadows-card.py: the AVIFs' strip and label, Martian Mono, grey stock / white now): what reflects now
against stock, the measured cost (docs/findings-prop-reflections-2026-10-08.md and the 84f875e card), the setting.

    python3 harness/props-reflect-changes.py [--size 630] [--out workshop-media/props-reflect-changes.png]
"""
import argparse
import importlib.util
import os

from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(__file__)
spec = importlib.util.spec_from_file_location('anim', os.path.join(here, 'efficiency-anim.py'))
anim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anim)
spec = importlib.util.spec_from_file_location('ent', os.path.join(here, 'entity-shadows-card.py'))
ent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ent)

INTRO = '434 props that are not windows or mirrors now reflect the scene, the sky and whoever walks past'
# what, stock, now
ROWS = [
    ('Glass doors, store fronts', 'see-through', 'reflect'),
    ('Glass tables, counters, cases', 'see-through', 'reflect'),
    ('Glass-door fridges', 'flat', 'reflect'),
    ('TVs, monitors, arcades', 'flat', 'passers-by'),
    ('Gym mirrors', 'flat', 'mirror'),
    ('Steel counters, sinks, ovens', 'flat', 'passers-by'),
    ('Toilets, sinks, bathtubs', 'flat', 'passers-by'),
]
COST = [('Desktop, 9 props and a car, still', '+0.044 ms'), ('Handheld, walking past store fronts', '+0.115 ms'),
        ('Frame time p99, the same walk', 'unchanged')]
FOOT = ('Options > PZ Optimization > Visuals > Mirrors and windows: "glass, screens and steel reflect too" (with mirrors, '
        'next launch). GPU time per frame, desktop (RTX 4090, 5120x2160) and Ayaneo Flip (Radeon 890M, 1920x1080, 60 fps '
        'cap); each prop\'s faces come from the game\'s own depth maps.')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--size', type=int, default=630)
    ap.add_argument('--out', default='workshop-media/props-reflect-changes.png')
    ap.add_argument('--label', default='Glass Reflections')
    ap.add_argument('--theme', default='light', choices=anim.THEMES)
    a = ap.parse_args()
    cw = sh = a.size
    s = cw / 630
    strip = round(80 * s)
    strip += strip % 2
    k = 0.75 if a.size <= 700 else 1.0
    pad = round(26 * k)
    img = anim.chrome(a.theme, a.label, sh, cw, strip, round(42 * s), pad)
    sw = cw - strip
    scene = Image.new('RGB', (sw, sh), (11, 11, 14))
    d = ImageDraw.Draw(scene)
    F = lambda px: ImageFont.truetype(anim.FONT, max(8, round(px * s)))
    cap, name, val, foot = F(10.5), F(12), F(12), F(9.6)
    x0, x1 = pad, sw - pad
    c_now = x1
    c_before = x1 - max(d.textlength(r[2], font=val) for r in ROWS) - round(22 * s)
    y = pad
    for line in ent.wrap(d, INTRO, cap, x1 - x0):
        d.text((x0, y), line, font=cap, fill=anim.CAPTION, anchor='lt')
        y += round(15 * s)
    y += round(10 * s)
    d.text((x0, y), 'PROP', font=cap, fill=anim.CAPTION, anchor='lt')
    d.text((c_before, y), 'STOCK', font=cap, fill=anim.CAPTION, anchor='rt')
    d.text((c_now, y), 'NOW', font=cap, fill=anim.CAPTION, anchor='rt')
    y += round(20 * s)
    d.line((x0, y, x1, y), fill=(60, 60, 66), width=1)
    row_h = round(33 * s)
    for what, before, now in ROWS:
        cy = y + row_h // 2
        d.text((x0, cy), what, font=name, fill=anim.AFTER, anchor='lm')
        d.text((c_before, cy), before, font=cap, fill=anim.BEFORE, anchor='rm')
        d.text((c_now, cy), now, font=val, fill=anim.AFTER, anchor='rm')
        y += row_h
    d.line((x0, y, x1, y), fill=(40, 40, 46), width=1)
    y += round(14 * s)
    d.text((x0, y), 'COST PER FRAME', font=cap, fill=anim.CAPTION, anchor='lt')
    y += round(18 * s)
    for what, cost in COST:
        cy = y + round(11 * s)
        d.text((x0, cy), what, font=cap, fill=anim.BEFORE, anchor='lm')
        d.text((c_now, cy), cost, font=val, fill=anim.AFTER, anchor='rm')
        y += round(24 * s)
    lines = ent.wrap(d, FOOT, foot, x1 - x0)
    lh = round(13.5 * s)
    fy = sh - pad - lh * len(lines)
    d.line((x0, fy - round(10 * s), x1, fy - round(10 * s)), fill=(40, 40, 46), width=1)
    for line in lines:
        d.text((x0, fy), line, font=foot, fill=(120, 120, 126), anchor='lt')
        fy += lh
    if y > fy - lh * len(lines) - round(14 * s):
        print('WARNING: the rows run into the footer')
    img.paste(scene, (strip, 0))
    os.makedirs(os.path.dirname(a.out) or '.', exist_ok=True)
    img.save(a.out)
    print('->', a.out, img.size)


if __name__ == '__main__':
    main()
