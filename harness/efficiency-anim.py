#!/usr/bin/env python3
"""Workshop description animations of the stitch-efficiency.py videos (2026-10-06): a 10 s window of each video as one
revealing scene, encoded as animated AVIF (harness/anim-encode.py, 60 fps).

Layout (the maintainer's specimen-card reference, 2026-10-06: no header, one slide): a vertical strip on the left edge with
the video's feature ("Handheld Efficiency", "MacOS Upgrade", "Low End Mode") set in Martian Mono, rotated; right of it one
full-width view of the scene, stock left of a white divider and optimized right of it, the divider moving as on the
Workshop's "New!" cards (carglass-card-gif.py, ao-card-gif.py): hold on the optimized picture, sweep to stock, hold,
sweep back (cosine ease), one cycle per loop. Both panes come from the stitched video, already aligned
in time. Bottom left the old number (grey), bottom right the new one (white): the route means of the clean runs behind the
video (stitch-efficiency.py --numbers), a secondary line under each (the flip's socket watts; the Mac's OpenGL version).
Tone-map as the headline GIF (hable, 200 nits). Font: ~/.local/share/fonts/specimens/MartianMono-sWdRg.ttf (OFL), from
github.com/nicoverbruggen/ebook-fonts.

    harness/efficiency-anim.py [--theme light|dark] [--still png] [--crf 35] [--size 630 --gif] [name ...]     (queue `media` job)

--size N makes an N x N canvas like the headline animation (630): the scene is cropped square from the panes' centre and the
strip and text scale with it; --gif writes workshop-media/template-<name>.gif through gifski instead of the AVIF.
--font <ttf> sets the strip's and the numbers' type (default Martian Mono).
--single <mp4> --t0 S [--crop x:y:w] [--len 10] --out <file>: one view of any HDR capture in the same look (strip, scene, no
divider and no numbers), e.g. the headline: --single "~/Videos/Project Zomboid/Video_2026-10-06_16-23-03.mp4" --t0 23.3
--crop 1750:464:1620 --len 10 --label PZ_Optimization --size 630. The crop is the headline's square; the scene takes the
same centre at its own aspect (the strip takes some of the width). --numbers A:B [--second "x:y"] [--words "BEFORE:AFTER"]
adds the comparison videos' numbers (A fps grey bottom left, B fps white bottom right, the second line under each), e.g. the
showcase drive: --numbers 156:512 --second "p99 16.5 ms:p99 5.8 ms". --header puts the label in a bar across the top
instead of the strip (13 % of the height, the label centred, --label-px sets its size, default 42 at 630); the scene takes the
rest of the canvas round the same centre; --align left puts the label at the numbers' left padding. --label-px without
--header sets the strip label's size (the strip is 80 px with a 42 px label at 630 by default, the header bar's band).
"""
import argparse
import json
import os
import shutil
import subprocess

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONTS = os.path.expanduser('~/.local/share/fonts/specimens')
MONO = f'{FONTS}/MartianMono-sWdRg.ttf'
FONT = MONO                        # --font replaces it
TONEMAP = ('zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
           'zscale=p=bt709:t=bt709:m=bt709,format=rgb24')
W, STRIP = 1260, 46
SWEEP, HOLD = 2.0, 1.2             # the New! cards' divider: seconds per sweep, seconds on each whole picture
LINE_FADE = 0.12                   # the divider fades over the last 12 % of the width at each edge
END_HOLD = 1.5                     # the optimized picture held after the sweep back, so the loop does not cut off right after it
BEFORE, AFTER, CAPTION = (150, 150, 156), (240, 240, 244), (190, 190, 196)   # grey before, white after

VIDEOS = {  # name: (t0 s, strip label, stitch-efficiency.py kind, secondary line: 'watts', 'jpf', (before, after) text or None
           #        [, 'visual': no numbers, the (before, after) texts alone label the two sides of the divider])
    'flip-efficient-cores-vs-stock-60fps': (12, 'Handheld Efficiency', 'flip60', 'watts'),
    'flip-max-efficiency-vs-stock': (12, 'Handheld Efficiency', 'flip', 'watts'),
    'flip-storm-120fps-vs-stock': (6, 'Handheld Efficiency', 'flipstorm', 'jpf'),   # energy per frame under the fps
    'mac-opengl-2.1-vs-4.1-day': (4, 'MacOS Upgrade', 'macday', ('OpenGL 2.1', 'OpenGL 4.1')),
    'mac-opengl-2.1-vs-4.1-pond': (5, 'MacOS OpenGL 4.1', 'macpond', ('OpenGL 2.1', 'OpenGL 4.1')),
    'dell-low-end-vs-stock': (2.0, 'Low End HW Mode', 'dell', None),   # 2.0-9.9 s: longest enhanced-capture freeze 107 ms, no black chunks
}
THEMES = {  # strip, strip text
    'dark': ((240, 240, 244), (11, 11, 14)),
    'light': ((0, 0, 0), (255, 255, 255)),
}


def chrome(theme, label, h, W=W, STRIP=STRIP, px=None, top=None):
    """The static frame: the left strip with its label; the scene area right of it left empty."""
    c_strip, c_strip_txt = THEMES[theme]
    img = Image.new('RGB', (W, h), c_strip)
    lab = Image.new('RGBA', (1600, STRIP), (0, 0, 0, 0))
    ImageDraw.Draw(lab).text((0, STRIP // 2), label, font=ImageFont.truetype(FONT, px or max(13, round(20 * STRIP / 46))), fill=c_strip_txt, anchor='lm')
    lab = lab.crop(lab.getbbox()).rotate(-90, expand=True)   # reads top to bottom, as on the specimen card
    img.paste(lab, ((STRIP - lab.width) // 2, round(26 * STRIP / 46) if top is None else top), lab)
    return img


def visual_overlay(second, sw, sh, k=1.0):
    """Layers over the scene of a visual comparison: (rgba, side) with side None = always, 'a' = while the stock side
    shows (left of the divider), 'b' = while the optimized side shows. A light gradient and the two side labels."""
    g = np.zeros((sh, sw, 4), np.uint8)
    rows = int(sh * 0.25)
    g[sh - rows:, :, 3] = (np.linspace(0, 1, rows) ** 1.6 * 170).astype(np.uint8)[:, None]
    layers = [(g, None)]
    f, pad = ImageFont.truetype(FONT, max(11, round(40 * k))), round(26 * k)
    for side, col, right in (('a', BEFORE, False), ('b', AFTER, True)):
        img = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
        ImageDraw.Draw(img).text((sw - pad if right else pad, sh - pad), second[side == 'b'], font=f, fill=col,
                                 anchor='rs' if right else 'ls')
        layers.append((np.asarray(img), side))
    return layers


def numbers_overlay(n, second, sw, sh, k=1.0, words=('STOCK', 'ENHANCED')):
    """Layers over the scene (see visual_overlay): a dark gradient along the bottom (always), the stock block bottom left
    (side 'a') and the enhanced block bottom right (side 'b'); the divider wipes each block with its picture."""
    g = np.zeros((sh, sw, 4), np.uint8)
    rows = int(sh * 0.42)
    g[sh - rows:, :, 3] = (np.linspace(0, 1, rows) ** 1.6 * 200).astype(np.uint8)[:, None]
    layers = [(g, None)]
    F = lambda px: ImageFont.truetype(FONT, max(11, round(px * k)))
    cap, big, unit, sec, sec_u = F(15), F(64), F(32), F(34), F(18)
    pad = round(26 * k)
    for side, col, word, right in (('a', BEFORE, words[0], False), ('b', AFTER, words[1], True)):
        img = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        v = n[side]
        lines = [(f"{v['fps']:.0f}", 'fps', big, unit)]
        if second == 'watts':
            lines.append((f"{v['watts']:.1f}", 'W', sec, sec_u))
        elif second == 'jpf':
            lines.append((f"{v['jpf'] * 1000:.0f}", 'mJ/frame', sec, sec_u))
        elif second:
            lines.append((second[side == 'b'], '', sec, sec_u))
        y = sh - pad
        for num, u, f, fu in reversed(lines):   # baselines bottom-up
            gap = round(8 * k) if u else 0
            wn, wu = d.textlength(num, font=f), d.textlength(u, font=fu) if u else 0
            x0 = sw - pad - (wn + gap + wu) if right else pad
            d.text((x0, y), num, font=f, fill=col, anchor='ls')
            if u:
                d.text((x0 + wn + gap, y), u, font=fu, fill=col, anchor='ls')
            y -= f.size + round(14 * k)
        d.text((sw - pad if right else pad, y + round(4 * k)), word, font=cap, fill=CAPTION, anchor='rs' if right else 'ls')
        layers.append((np.asarray(img), side))
    return layers


def panes(video, t0, secs, geo, sw, sh, square=False):
    """Tone-mapped (stock, optimized) pane pairs of the stitched video, each scaled to the scene size."""
    head, pw, ph, gap = geo['head'], geo['pw'], geo['ph'], geo['gap']
    fit = f'scale=-2:{sh}:flags=lanczos,crop={sw}:{sh}' if square else f'scale={sw}:{sh}:flags=lanczos'   # square: the centre
    fc = (f'[0:v]fps=60,{TONEMAP},split[a][b];'
          f'[a]crop={pw}:{ph}:0:{head},{fit}[l];'
          f'[b]crop={pw}:{ph}:{pw + gap}:{head},{fit}[r];[l][r]vstack,format=rgb24')
    p = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-ss', str(t0), '-t', str(secs), '-i', video,
                          '-filter_complex', fc, '-f', 'rawvideo', '-'], stdout=subprocess.PIPE)
    n = sw * sh * 2 * 3
    while True:
        b = p.stdout.read(n)
        if len(b) < n:
            break
        f = np.frombuffer(b, np.uint8).reshape(2 * sh, sw, 3)
        yield f[:sh], f[sh:]
    p.stdout.close()
    p.wait()


def single_frames(video, t0, secs, crop, sw, sh):
    """Tone-mapped frames of one HDR capture: the crop's centre at the scene's aspect (crop x:y:w = a square of side w, the
    headline's notation; empty = the whole frame), scaled to the scene size."""
    if crop:
        x, y, w = (int(v) for v in crop.split(':'))
        cw = round(w * sw / sh)
        cut = f'crop={cw}:{w}:{x + (w - cw) // 2}:{y},'
    else:
        cut = f'scale={sw}:{sh}:force_original_aspect_ratio=increase,crop={sw}:{sh},'
    p = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-ss', str(t0), '-t', str(secs), '-i', video,
                          '-vf', f'fps=60,{TONEMAP},{cut}scale={sw}:{sh}:flags=lanczos,format=rgb24', '-f', 'rawvideo', '-'],
                         stdout=subprocess.PIPE)
    n = sw * sh * 3
    while True:
        b = p.stdout.read(n)
        if len(b) < n:
            break
        yield np.frombuffer(b, np.uint8).reshape(sh, sw, 3)
    p.stdout.close()
    p.wait()


def encode(a, work, shape, dst):
    """The frames in work/frames to an AVIF (or with --gif a GIF) at dst; the frames are removed afterwards."""
    if a.gif:
        variant = f'gifski:{a.gif_fps}:{shape[1]}x{shape[0]}:q=80'
    else:
        variant = f'avif:60:{shape[1]}x{shape[0]}:crf={a.crf}'
    subprocess.run(['python3', 'harness/anim-encode.py', '--frames', f'{work}/frames', '--src-fps', '60', '--out', work, variant],
                   check=True)
    ext = '.gif' if a.gif else '.avif'
    shutil.copy(next(f'{work}/{f}' for f in sorted(os.listdir(work)) if f.endswith(ext)), dst)
    shutil.rmtree(f'{work}/frames')
    print('->', dst, os.path.getsize(dst))


def compose(base, left, right, ov, x, STRIP=STRIP):
    """One frame: stock (`left`) left of the divider at x, optimized (`right`) right of it, the divider, the overlay layers."""
    fr = base.copy()
    sc = fr[:, STRIP:]
    sc[:, :x] = left[:, :x]
    sc[:, x:] = right[:, x:]
    for layer, side in ov:   # each side's labels only over its own picture: the divider wipes them with it
        cols = slice(0, x) if side == 'a' else slice(x, None) if side == 'b' else slice(None)
        a = layer[:, cols, 3:4].astype(np.float32) / 255.0
        sc[:, cols] = (sc[:, cols] * (1 - a) + layer[:, cols, :3] * a).astype(np.uint8)
    w = sc.shape[1]
    fade = ease(min(1.0, min(x, w - x) / (LINE_FADE * w)))   # the line fades out towards the edges instead of vanishing
    if 0 < x < w and fade > 0:
        sh_ = slice(max(0, x - 3), x + 3)
        sc[:, sh_] = (sc[:, sh_] * (1 - 0.45 * fade)).astype(np.uint8)   # a soft shadow round the line
        ln = slice(max(0, x - 1), x + 1)
        sc[:, ln] = (sc[:, ln] * (1 - fade) + 255 * fade).astype(np.uint8)
    return fr


def ease(t):
    return 0.5 - 0.5 * np.cos(np.pi * t)


def divider_positions(fps=60):
    """Share of the width showing stock (left of the divider), per frame, as the New! cards: hold on the optimized picture,
    sweep to stock, hold, sweep back, hold on the optimized picture again (END_HOLD)."""
    n, h = round(SWEEP * fps), round(HOLD * fps)
    return ([0.0] * h + [ease((i + 1) / n) for i in range(n)] + [1.0] * h + [1 - ease((i + 1) / n) for i in range(n)] + [0.0] * round(END_HOLD * fps))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--theme', default='light', choices=THEMES)
    ap.add_argument('--still', help='write one composed frame (2 s in, line mid-sweep at a third) to this PNG and stop')
    ap.add_argument('--crf', default='35')
    ap.add_argument('--len', type=float, default=2 * (SWEEP + HOLD) + END_HOLD, help='clip seconds (default one divider cycle)')
    ap.add_argument('--size', type=int, help='square canvas N x N (the headline animation is 630)')
    ap.add_argument('--gif', action='store_true', help='write workshop-media/template-<name>.gif (gifski) instead of the AVIF')
    ap.add_argument('--gif-fps', default='25')
    ap.add_argument('--label', help='strip text instead of the VIDEOS entry\'s (e.g. PZ_Optimization)')
    ap.add_argument('--out', help='output file instead of workshop-media/<name>.avif / template-<name>.gif (one name only)')
    ap.add_argument('--font', help='TTF for the strip and the numbers (default Martian Mono)')
    ap.add_argument('--single', help='one HDR capture instead of a stitched comparison (no divider, no numbers); needs --out')
    ap.add_argument('--t0', type=float, default=0.0, help='with --single: start second')
    ap.add_argument('--crop', default='', help='with --single: x:y:w square in the capture whose centre the scene keeps')
    ap.add_argument('--numbers', help='with --single: before:after fps, drawn as in the comparison videos')
    ap.add_argument('--second', help='with --numbers: before:after second lines, e.g. "p99 16.5 ms:p99 5.8 ms"')
    ap.add_argument('--words', default='STOCK:ENHANCED', help='with --numbers: the captions over the numbers')
    ap.add_argument('--header', action='store_true', help='with --single: the label in a bar across the top instead of the strip')
    ap.add_argument('--label-px', type=int, help='with --single: the label size in px (header default 42 at 630); without --header '
                    'it also widens the strip to the header bar\'s size')
    ap.add_argument('--align', default='center', choices=('center', 'left'), help='with --header: the label centred or at the left padding')
    ap.add_argument('names', nargs='*')
    a = ap.parse_args()
    global FONT
    FONT = os.path.expanduser(a.font) if a.font else MONO
    cw = a.size or W
    # the strip (maintainer, 2026-10-06): 80 px wide with a 42 px label at 630, top-aligned at the numbers' padding, the same
    # band as --header's bar; scaled with the canvas for other sizes
    s630 = cw / 630 if a.size else W / 1260 * 1.0
    strip = round(80 * s630)
    strip += strip % 2
    label_px = a.label_px or round(42 * s630)
    k = 0.75 if a.size and a.size <= 700 else 1.0
    sw = cw - strip
    if a.single:
        if not a.out:
            raise SystemExit('--single needs --out')
        label = a.label or 'PZ_Optimization'
        if a.header:   # the bar across the top: the scene is the full width under it
            hh = round(cw * 0.127)
            hh += hh % 2
            strip, sw = 0, cw
            sh = (a.size or int(round(cw * 9 / 16)) + hh) - hh
            c_bar, c_txt = THEMES[a.theme]
            img = Image.new('RGB', (cw, hh + sh), c_bar)
            hf = ImageFont.truetype(FONT, a.label_px or round(42 * cw / 630))
            d = ImageDraw.Draw(img)
            x0, y0, x1, y1 = d.textbbox((0, 0), label, font=hf, anchor='ls')   # centre the ink, not the font's metrics
            lx = round(26 * k) - x0 if a.align == 'left' else (cw - (x1 - x0)) // 2 - x0   # left: the numbers' padding
            d.text((lx, (hh - (y1 - y0)) // 2 - y0), label, fill=c_txt, font=hf, anchor='ls')
            base = np.asarray(img).copy()
            top = hh
        else:
            sh = a.size or int(round(sw * 9 / 16))
            sh -= sh % 2
            base = np.asarray(chrome(a.theme, label, sh, cw, strip, label_px, round(26 * k))).copy()
            top = 0
        secs = a.len if '--len' in os.sys.argv else 10.0
        ov = []
        if a.numbers:
            fa, fb = (float(v) for v in a.numbers.split(':'))
            ov = numbers_overlay({'a': {'fps': fa}, 'b': {'fps': fb}}, tuple(a.second.split(':')) if a.second else None,
                                 sw, sh, k, tuple(a.words.split(':')))

        def put(fr, f):
            fr[top:, strip:] = f
            sc = fr[top:, strip:]
            for layer, _ in ov:
                al = layer[:, :, 3:4].astype(np.float32) / 255.0
                sc[:] = (sc * (1 - al) + layer[:, :, :3] * al).astype(np.uint8)
            return fr
        if a.still:
            fr = put(base.copy(), next(single_frames(os.path.expanduser(a.single), a.t0 + 2, 0.1, a.crop, sw, sh)))
            Image.fromarray(fr).save(a.still)
            print('still', a.still, fr.shape)
            return
        work = f'build/anim/single-{os.path.splitext(os.path.basename(a.out))[0]}'
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(f'{work}/frames')
        i = 0
        for f in single_frames(os.path.expanduser(a.single), a.t0, secs, a.crop, sw, sh):
            Image.fromarray(put(base.copy(), f)).save(f'{work}/frames/f{i:05d}.png', compress_level=1)
            i += 1
        print(f'== single {a.single}: {i} frames {base.shape[1]}x{base.shape[0]} from +{a.t0} s')
        encode(a, work, base.shape, a.out)
        return
    for name in a.names or list(VIDEOS):
        t0, label, kind, second, *mode = VIDEOS[name]
        label = a.label or label
        video = f'workshop-media/{name}.mp4'
        n = json.loads(subprocess.run(['python3', 'harness/stitch-efficiency.py', kind, '--numbers'],
                                      capture_output=True, text=True, check=True).stdout)
        geo = n['panes']
        sh = a.size or int(round(geo['ph'] * sw / geo['pw']))
        sh -= sh % 2
        base = np.asarray(chrome(a.theme, label, sh, cw, strip, label_px, round(26 * k))).copy()
        ov = visual_overlay(second, sw, sh, k) if 'visual' in mode else numbers_overlay(n, second, sw, sh, k)
        if a.still:
            left, right = next(panes(video, t0 + 2, 0.1, geo, sw, sh, bool(a.size)))
            Image.fromarray(compose(base, left, right, ov, sw * 2 // 3, strip)).save(a.still)
            print('still', a.still, base.shape)
            return
        work = f'build/anim/{name}-{a.theme}' + (f'-{cw}' if a.size else '')
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(f'{work}/frames')
        seq = divider_positions()
        i = 0
        for left, right in panes(video, t0, a.len, geo, sw, sh, bool(a.size)):
            x = round(sw * seq[i % len(seq)])
            Image.fromarray(compose(base, left, right, ov, x, strip)).save(f'{work}/frames/f{i:05d}.png', compress_level=1)
            i += 1
        print(f'== {name}: {i} frames {base.shape[1]}x{base.shape[0]} from +{t0} s')
        encode(a, work, base.shape, a.out or (f'workshop-media/template-{name}.gif' if a.gif else f'workshop-media/{name}.avif'))


if __name__ == '__main__':
    main()
