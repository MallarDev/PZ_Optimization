#!/usr/bin/env python3
"""The Workshop's two feature lists as still cards in the AVIFs' template (harness/efficiency-anim.py: the left strip with its
label, Martian Mono, grey stock / white ours; the layout of harness/mods-table-card.py), 2026-10-08:

  opt  the most prominent optimizations, each with its measured stock -> ours number (the findings docs / Workshop cards
       it comes from are named in ROWS_OPT; the desktop is Ryzen 7 9800X3D + RTX 4090 at 5120x2160)
  enh  the most prominent Visuals enhancements, a line of what each does (all off by default)

    python3 harness/feature-list-card.py opt|enh [--size 630] [--out workshop-media/feature-list-<kind>.png]
"""
import argparse
import importlib.util
import os

from PIL import Image, ImageDraw, ImageFont

spec = importlib.util.spec_from_file_location('anim', os.path.join(os.path.dirname(__file__), 'efficiency-anim.py'))
anim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anim)

# (name, what it does + what the number is, stock, ours with its unit)
ROWS_OPT = [
    ('Driving', '120 km/h drive: trees and tiles baked once',
     '115', '523 fps'),                                            # mods table, 2026-10-06 (mx-drive120-*)
    ('Thunderstorms', 'storm drive: rain drawn once, puddles on the GPU',
     '63', '447 fps'),                                             # mods table (mx-storm120-*)
    ('Hordes on every core', '~2,000 zombies: animation and AI on 8 cores',
     '24', '86 fps'),                                              # mods table (mx-louisville-*)
    ('Heavy fog', "fog's cost a frame: one pass, quarter size",
     '2.3', '0.3 ms'),                                             # fog pass 2026-09-21: 447 clear, 220 -> 389 fps in fog
    ('Lightning', 'longest frame at a strike: re-bakes spread out',
     '88', '19 ms'),                                               # findings-scene-presets §5: storm120-rec vs -spread
    ('Zoom', 'worst frame zooming out: textures kept',
     '375', '25 ms'),                                              # results "Camera zoom changes": 0.25 -> 2.5 jump
    ('Boot and load', 'launch to walking in the world',
     '17.7', '7.8 s'),                                             # boot-load race, 2026-10-06
    ('Smooth driving', 'car judder, 120 Hz laptop: drawn between steps',
     '5.4', '1.0 px'),                                             # findings-car-jitter-2026-09-26 (flip, town)
    ('House alarms', "a ringing alarm's sound, each call",
     '4.4', '0.007 ms'),                                           # findings-world-sound-2026-09-22 (radius 2000)
    ('Handhelds', 'Ayaneo Flip storm, same 21 W: efficient cores',
     '34', '57 fps'),                                              # flipw-storm-*, 2026-10-06 (60 fps cap)
    ('Low-end laptops', '4-core laptop, 120 km/h drive: low-end mode',
     '44', '68 fps'),                                              # Low-end mode release 8259ca9
    ('Clean audio', 'clipped samples in 25 s of gunfire: a limiter',
     '19,000', '0'),                                               # findings-sound-2026-09-24
]
HEAD_OPT = 'Stock game vs PZ Optimization (desktop unless noted)'
FOOT_OPT = ('Desktop: Ryzen 7 9800X3D, RTX 4090, 5120x2160, Linux, uncapped; drive / storm / horde rows from the 2026-10-06 '
            'back-to-back runs, the others from each feature\'s own A/B. Defaults on, except the handheld and low-end rows (their '
            'presets). Each row has its own switch in Options > PZ Optimization. Numbers differ with hardware and save.')

ROWS_ENH = [
    ('Sun, moon and cloud shadows', 'soft shadows of walls, trees, people and cars from the real sky'),
    ('God rays', 'light shafts through windows, doorways, trees and fog'),
    ('Per-pixel lighting', 'smooth light; torch and headlight beams drawn per pixel'),
    ('Ambient occlusion', 'soft shading in corners, along wall bases and under furniture'),
    ('Reflections', 'rivers, lakes and puddles mirror the scene'),
    ('Mirrors and windows', 'wall mirrors and window panes reflect the room and you'),
    ('Car glass', 'windows reflect the sky and show the people inside'),
    ('Relief', 'bricks, stones, planks and shingles catch the light'),
    ('Foliage sway', 'grass, bushes and trees move in the wind'),
    ('Colour grading, darkness floor', 'a look for each hour and weather; seen places never pitch black'),
    ('Sharp sprites', 'crisp world art at any zoom level'),
    ('HDR and upscaling', 'real highlights on HDR screens; DLSS, FSR, dynamic resolution'),
]
HEAD_ENH = 'Options > PZ Optimization > Visuals, each off by default'
FOOT_ENH = ('Built for the game\'s own renderer: most cost a fraction of a millisecond a frame on the desktop, and the '
            'master switch turns them all off at once. macOS runs them too (OpenGL 4.1), DLSS aside.')


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
    ap.add_argument('kind', choices=('opt', 'enh'))
    ap.add_argument('--size', type=int, default=630)
    ap.add_argument('--out')
    ap.add_argument('--label')
    ap.add_argument('--theme', default='light', choices=anim.THEMES)
    a = ap.parse_args()
    opt = a.kind == 'opt'
    label = a.label or ('Optimizations' if opt else 'Enhancements')
    out = a.out or f'workshop-media/feature-list-{a.kind}.png'
    cw = sh = a.size
    s = cw / 630
    strip = round(80 * s)
    strip += strip % 2
    k = 0.75 if a.size <= 700 else 1.0
    pad = round(26 * k)
    img = anim.chrome(a.theme, label, sh, cw, strip, round(42 * s), pad)
    sw = cw - strip
    scene = Image.new('RGB', (sw, sh), (11, 11, 14))
    d = ImageDraw.Draw(scene)
    F = lambda px: ImageFont.truetype(anim.FONT, max(8, round(px * s)))
    cap, name, desc, val, foot = F(10.5), F(12.5), F(9.2), F(13), F(9.2)
    dim = (120, 120, 126)

    x0, x1 = pad, sw - pad
    col_stock, col_ours = x1 - round(92 * s), x1   # right edges of the two number columns
    y = pad
    for line in wrap(d, HEAD_OPT if opt else HEAD_ENH, cap, x1 - x0):
        d.text((x0, y), line, font=cap, fill=anim.CAPTION, anchor='lt')
        y += round(15 * s)
    y += round(8 * s)
    d.text((x0, y), 'OPTIMIZATION' if opt else 'ENHANCEMENT', font=cap, fill=anim.CAPTION, anchor='lt')
    if opt:
        d.text((col_stock, y), 'STOCK', font=cap, fill=anim.CAPTION, anchor='rt')
        d.text((col_ours, y), 'OURS', font=cap, fill=anim.CAPTION, anchor='rt')
    y += round(18 * s)
    d.line((x0, y, x1, y), fill=(60, 60, 66), width=1)

    rows = ROWS_OPT if opt else ROWS_ENH
    foot_lines = wrap(d, FOOT_OPT if opt else FOOT_ENH, foot, x1 - x0)
    lh = round(12.5 * s)
    foot_top = sh - pad - lh * len(foot_lines)
    row_h = (foot_top - round(16 * s) - y) / len(rows)
    desc_w = (col_stock - round(60 * s) if opt else x1) - x0
    for i, r in enumerate(rows):
        top = y + i * row_h
        cy = top + row_h / 2
        d.text((x0, cy - round(1 * s)), r[0], font=name, fill=anim.AFTER, anchor='ls')
        dl = wrap(d, r[1], desc, desc_w)
        if len(dl) > 1:
            print(f'warning: {r[0]}: description wraps ({len(dl)} lines)')
        d.text((x0, cy + round(3 * s)), dl[0], font=desc, fill=dim, anchor='lt')
        if opt:
            d.text((col_stock, cy), r[2], font=val, fill=anim.BEFORE, anchor='rm')
            d.text((col_ours, cy), r[3], font=val, fill=anim.AFTER, anchor='rm')
        if i < len(rows) - 1:
            ly = round(top + row_h)
            d.line((x0, ly, x1, ly), fill=(32, 32, 37), width=1)
    fy = foot_top
    d.line((x0, fy - round(10 * s), x1, fy - round(10 * s)), fill=(40, 40, 46), width=1)
    for line in foot_lines:
        d.text((x0, fy), line, font=foot, fill=dim, anchor='lt')
        fy += lh
    img.paste(scene, (strip, 0))
    os.makedirs(os.path.dirname(out) or '.', exist_ok=True)
    img.save(out)
    print('->', out, img.size)


if __name__ == '__main__':
    main()
