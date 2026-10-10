#!/usr/bin/env python3
"""The New! card's still for shadows on entities (2026-10-07, docs/findings-entity-shadows-2026-10-07.md), in the AVIFs'
template (harness/efficiency-anim.py: the left strip with its label, Martian Mono, grey before / white now): what an entity
takes now against before, the measured cost, the setting. Shown under the card's AVIF
(desktop-rosewood-golden-hour-visuals-vs-stock).

    python3 harness/entity-shadows-card.py [--size 630] [--out workshop-media/entity-shadows-changes.png] [--label "Entities Shadows"]
"""
import argparse
import importlib.util
import os

from PIL import Image, ImageDraw, ImageFont

spec = importlib.util.spec_from_file_location('anim', os.path.join(os.path.dirname(__file__), 'efficiency-anim.py'))
anim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anim)

INTRO = 'The player, zombies, animals and cars now take the shadows around them, as the ground does'
# what, before, now
ROWS = [
    ('Buildings, roofs, trees', 'one shade a body', 'part by part'),
    ('Cars in the shade', 'lit', 'shaded'),
    ('Other bodies and cars', 'none', 'cast on them'),
    ('Their own body', 'none', 'arm, brim, cabin'),
    ('Clouds', 'one value', 'per pixel'),
    ('Torch, headlights', 'none', 'beam shadows'),
    ('Bodies close together', 'none', 'occlusion'),
]
COST = [('40 zombies + 3 cars, shadow edge', '+0.01-0.03 ms'), ('120 km/h drive, Louisville horde', 'within noise'),
        ('Night, the player\'s torch', '+0.007 ms')]
FOOT = ('Options > PZ Optimization > Visuals > Sun, moon and clouds: "Shadows on characters and vehicles" (with sun '
        'shadows). Cost per frame on a desktop (Ryzen 7 9800X3D, RTX 4090, 5120x2160, Linux), uncapped, switched on and off '
        'every 250 ms of the same run; the work runs on a worker and in the model shaders, ~2 us of the game thread.')


def wrap(d, text, font, width):
    lines, cur = [], ''
    for w in text.split():
        t = f'{cur} {w}'.strip()
        if d.textlength(t, font=font) <= width:
            cur = t
        else:
            lines.append(cur)
            cur = w
    return lines + [cur]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--size', type=int, default=630)
    ap.add_argument('--out', default='workshop-media/entity-shadows-changes.png')
    ap.add_argument('--label', default='Entities Shadows')
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
    c_now = x1   # right edge of NOW; BEFORE right-aligned a gap left of the widest NOW
    c_before = x1 - max(d.textlength(r[2], font=val) for r in ROWS) - round(22 * s)
    y = pad
    for line in wrap(d, INTRO, cap, x1 - x0):
        d.text((x0, y), line, font=cap, fill=anim.CAPTION, anchor='lt')
        y += round(15 * s)
    y += round(10 * s)
    d.text((x0, y), 'SHADE ON AN ENTITY', font=cap, fill=anim.CAPTION, anchor='lt')
    d.text((c_before, y), 'BEFORE', font=cap, fill=anim.CAPTION, anchor='rt')
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
    lines = wrap(d, FOOT, foot, x1 - x0)
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
