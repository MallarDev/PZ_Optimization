#!/usr/bin/env python3
"""The Workshop mods comparison as a still card in the AVIFs' template (harness/efficiency-anim.py: the left strip with its
label, Martian Mono, grey before / white after): one table of route fps per mod, the disclaimer as its footer (2026-10-06,
the maintainer). Numbers are read from the runs (pzopt-overlay.out over the route window, as the AVIFs' blocks), newest run
dir of each label: the 2026-10-06 mx-* runs of ~/src/mods-1006/submit.sh, every side as players get it (stock and the mods
on the game's own ZGC launcher, ours on G1 as our install sets it).

    python3 harness/mods-table-card.py [--size 630] [--out workshop-media/mods-table.png] [--label "Vs Other Mods"]
"""
import argparse
import importlib.util
import os

from PIL import Image, ImageDraw, ImageFont

spec = importlib.util.spec_from_file_location('anim', os.path.join(os.path.dirname(__file__), 'efficiency-anim.py'))
anim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anim)

BENCHES = (('DRIVE', 'drive120'), ('STORM', 'storm120'), ('HORDE', 'louisville'))   # horde: Louisville replaced the spin (maintainer)
# shown name, run label per bench ({b} = the bench; a tuple = the mean of those runs' fps); ours last
ROWS = [
    ('Stock', {'drive120': 'mx-drive120-stock', 'storm120': 'mx-storm120-stock-r',
               'louisville': ('mx-louisville-stock', 'mx-louisville-stock-r')}),
    ('PZ Optimiser', {'drive120': 'mx-drive120-pzo-r', 'storm120': 'mx-storm120-pzo', 'louisville': 'mx-louisville-pzo'}),
    ('Tempo', 'mx-{b}-tempo'),
    ('Multi-Cpu Enhance', 'mx-{b}-multicpu'),
    ('Every Texture Opt.', 'mx-{b}-eto'),
    ('Lugli Optimizations', 'mx-{b}-lugli'),
    ("Zed's Better FPS", 'mx-{b}-zeds'),
    ("Zed's Better FPS KA", {'drive120': 'mx-drive120-zedska-r', 'storm120': 'mx-storm120-zedska',
                             'louisville': 'mx-louisville-zedska'}),
    ('Let Me Drive!', 'mx-{b}-lmd'),
    ('PZ Optimization', 'mx-{b}-optg1'),
]
ROUTES = '120 km/h drive / the drive in a thunderstorm / Louisville horde (~2,000 zombies), uncapped, fps mean'
DISCLAIMER = ('Measured 2026-10-06 on one desktop (Ryzen 7 9800X3D, RTX 4090, 5120x2160, Linux), Build 42.21, one run per route (stock re-run in the storm, the mean of two in the horde), each mod '
              'alone and installed as its page says. Stock and the other mods keep the game\'s own launcher (ZGC, 3 GB); '
              'PZ Optimization sets G1, as it does for players. Numbers differ with hardware, settings and save; '
              'frame rate only, not a verdict on any mod\'s other features.')


def label_of(row, b):
    lab = row[1]
    return lab[b] if isinstance(lab, dict) else lab.format(b=b)


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
    ap.add_argument('--out', default='workshop-media/mods-table.png')
    ap.add_argument('--label', default='Vs Other Mods')
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
    cap, name, val, valb, foot = F(10.5), F(12), F(13), F(15), F(9.6)

    def fps_of(lab):
        labs = lab if isinstance(lab, tuple) else (lab,)
        return sum(anim.route_numbers(anim.run_dir(l))['fps'] for l in labs) / len(labs)
    fps = {r[0]: {b: fps_of(label_of(r, b)) for _, b in BENCHES} for r in ROWS}
    for r in ROWS:
        print(f"{r[0]:22s} " + '  '.join(f"{b} {fps[r[0]][b]:6.1f}" for _, b in BENCHES))

    x0, x1 = pad, sw - pad
    cols = [x1 - round(v * s) for v in (176, 92, 8)]   # right edges of the three number columns
    y = pad
    for line in wrap(d, ROUTES, cap, x1 - x0):
        d.text((x0, y), line, font=cap, fill=anim.CAPTION, anchor='lt')
        y += round(15 * s)
    y += round(10 * s)
    for (word, _), cx in zip(BENCHES, cols):
        d.text((cx, y), word, font=cap, fill=anim.CAPTION, anchor='rt')
    d.text((x0, y), 'MOD', font=cap, fill=anim.CAPTION, anchor='lt')
    y += round(20 * s)
    d.line((x0, y, x1, y), fill=(60, 60, 66), width=1)
    row_h = round(33 * s)
    for i, r in enumerate(ROWS):
        ours, stock = i == len(ROWS) - 1, i == 0
        if ours:
            y += round(4 * s)
            d.rectangle((x0 - round(8 * s), y, x1 + round(8 * s), y + row_h + round(4 * s)), fill=(28, 28, 33))
            y += round(2 * s)
        cy = y + row_h // 2
        col = anim.AFTER if ours else anim.BEFORE
        d.text((x0, cy), r[0], font=valb if ours else name, fill=col, anchor='lm')
        for (_, b), cx in zip(BENCHES, cols):
            d.text((cx, cy), f"{fps[r[0]][b]:.0f}", font=valb if ours else val, fill=col, anchor='rm')
        y += row_h
        if stock or i == len(ROWS) - 2:
            d.line((x0, y, x1, y), fill=(40, 40, 46), width=1)
    y += round(6 * s)
    best = {b: max(fps[r[0]][b] for r in ROWS[:-1]) for _, b in BENCHES}
    ratios = '  '.join(f"{w.lower()} {fps[ROWS[-1][0]][b] / best[b]:.1f}x" for w, b in BENCHES)
    d.text((x0, y + round(8 * s)), f'vs the best other mod: {ratios}', font=cap, fill=anim.CAPTION, anchor='lt')
    lines = wrap(d, DISCLAIMER, foot, x1 - x0)
    lh = round(13.5 * s)
    fy = sh - pad - lh * len(lines)
    d.line((x0, fy - round(10 * s), x1, fy - round(10 * s)), fill=(40, 40, 46), width=1)
    for line in lines:
        d.text((x0, fy), line, font=foot, fill=(120, 120, 126), anchor='lt')
        fy += lh
    img.paste(scene, (strip, 0))
    os.makedirs(os.path.dirname(a.out) or '.', exist_ok=True)
    img.save(a.out)
    print('->', a.out, img.size)


if __name__ == '__main__':
    main()
