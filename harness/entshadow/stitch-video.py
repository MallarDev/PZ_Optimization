#!/usr/bin/env python3
"""Shadows on characters and cars (2026-10-07, docs/findings-entity-shadows-2026-10-07.md): before (left) and after
(right) from matched pairs of runs, then a card with the measured cost.

Pairs (harness/runs/<label>-*): es-vid2-day-off / -on (17:48, the church lot at 8170,11500, 40 pinned zombies and three
cars across the church's long evening shadow) and es-vid2-night-off / -on (01:00, the same spot, the player's torch). "off"
is `entityShadows=false` (the build before: one shade for a whole character, none for cars), "on" the defaults; an empty
options file each, SDR. Then each scene once more from one run that switches before / after every second
(es-vid2-day-flip / -night-flip, `devEntityShadowCycle=off,probe devEntityShadowAlternate=1000`: the same frame, only the
shading changes; variant "off" is the per-body shade of before). The source is the game's own presented frames, 1:1
crops (pzopt.FrameCapture, `--prop devCapture=6,14,30,100,crop=1100:480:1918:1400,ram`, the flips 2560 wide). Both runs of a pair go on one 30 fps timeline measured from
their route start (pzopt-schedule.out); SDR mapped to PQ at 203 nits (as encode-av1-hdr.sh does); AV1 10-bit PQ / BT.2020,
the poster .jpg the only tone-mapped derivative. Layout as harness/stitch-darkness.py (docs/media-style.md).

Usage: harness/entshadow/stitch-video.py <out.mp4>     (a queued media job)
"""
import glob
import os
import subprocess
import sys

import re

import numpy as np
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else 'docs/media/entity-shadows-before-after.mp4'
SEG = 9.0
CARD = 7.0
W, H = 3840, 1800
HEAD = 150
PW, PH = 1918, 1400
CAP_Y = HEAD + PH

FONT_B = '/usr/share/fonts/noto/NotoSans-Bold.ttf'
FONT_R = '/usr/share/fonts/noto/NotoSans-Regular.ttf'
FONT_M = '/usr/share/fonts/noto/NotoSansMono-Regular.ttf'
WHITE, GREY, AMBER, GREEN = '0xB4B4B8', '0x8A8A90', '0xB88A40', '0x5CB878'

FW = 2560  # flip pane width
SCENES = [
    ('es-vid2-day', 'EVENING SUN, 17:48', 'shade where they stand  ·  from each other and the cars  ·  their own body  ·  the sunlit side',
     'Zombies on the edge of the church\'s long shadow are shaded part by part; the car takes the shade too; bodies shade each other.'),
    ('es-vid2-night', 'NIGHT, 01:00, THE PLAYER\'S TORCH', 'torch shadows on characters',
     'A zombie standing in another one\'s shadow from the torch stays dark, as the ground under it does.'),
]


def run_dir(label):
    runs = sorted(glob.glob(f'harness/runs/{label}-2*'))
    if not runs:
        raise SystemExit(f'no run {label}')
    return runs[-1]


def kv(path):
    d = {}
    for line in open(path, errors='replace'):
        for tok in line.split():
            if '=' in tok:
                k, v = tok.split('=', 1)
                d[k] = v
    return d


def capture(d):
    c = os.path.join(d, 'capture')
    lines = open(os.path.join(c, 'index.txt')).read().split('\n')
    head = dict(x.split('=') for x in lines[0].split())
    w, h = int(head['w']), int(head['h'])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()])
    raw = np.memmap(os.path.join(c, 'frames.rgba'), dtype=np.uint8, mode='r')
    n = min(len(stamps), raw.size // (w * h * 4))
    route = int(kv(os.path.join(d, 'pzopt-schedule.out'))['route_start_epoch_ms'])
    return raw[:n * w * h * 4].reshape(n, h, w, 4), stamps[:n], w, h, route


def window(a, b):
    ra, rb = capture(a), capture(b)
    lo = max((ra[1][0] - ra[4]) / 1000.0, (rb[1][0] - rb[4]) / 1000.0) + 0.1
    hi = min((ra[1][-1] - ra[4]) / 1000.0, (rb[1][-1] - rb[4]) / 1000.0) - 0.1
    return lo, hi


def pane(d, t0, n, fps=30):
    frames, stamps, w, h, route = capture(d)
    rel = (stamps - route) / 1000.0
    out = os.path.join(d, 'pane.mkv')
    ff = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{w}x{h}',
                           '-r', str(fps), '-i', '-', '-vf', f'scale={PW}:{PH}:flags=lanczos', '-c:v', 'ffv1', '-pix_fmt', 'gbrp', out],
                          stdin=subprocess.PIPE)
    for i in range(n):
        j = int(np.argmin(np.abs(rel - (t0 + i / fps))))
        ff.stdin.write(np.ascontiguousarray(frames[j, :, :, :3][::-1]).tobytes())  # rows are bottom-up
    ff.stdin.close()
    if ff.wait() != 0:
        raise SystemExit(f'{d}: pane encode failed')
    return out


def flip_pane(d, n, fps=30):
    """<run>/flip.mkv: n frames at fps from the capture's start, each labelled BEFORE / AFTER by the variant it was drawn with
    (frames within 50 ms of a switch skipped: they may straddle it)."""
    frames, stamps, w, h, route = capture(d)
    m = re.search(r'entity shadows: cycling ([\w,]+) every (\d+) ms from epoch_ms (\d+)', open(os.path.join(d, 'console.txt'), errors='replace').read())
    order, per, c0 = m.group(1).split(','), int(m.group(2)), int(m.group(3))
    ph = ((stamps - c0) % per) / per
    ok = np.where((ph > 0.05) & (ph < 0.95))[0]
    font = ImageFont.truetype(FONT_B, 64)
    out = os.path.join(d, 'flip.mkv')
    ff = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{w}x{h}',
                           '-r', str(fps), '-i', '-', '-c:v', 'ffv1', '-pix_fmt', 'gbrp', out], stdin=subprocess.PIPE)
    t0 = stamps[ok[0]]
    for i in range(n):
        j = ok[int(np.argmin(np.abs(stamps[ok] - (t0 + i * 1000.0 / fps))))]
        v = order[int((stamps[j] - c0) // per) % len(order)]
        im = Image.fromarray(np.ascontiguousarray(frames[j, :, :, :3][::-1]))
        dr = ImageDraw.Draw(im)
        after = v != 'off'
        label = 'AFTER' if after else 'BEFORE'
        dr.rectangle([24, 24, 24 + 330, 24 + 96], fill=(6, 6, 8))
        dr.text((48, 30), label, font=font, fill=(0x5C, 0xB8, 0x78) if after else (0xB8, 0x8A, 0x40))
        ff.stdin.write(im.tobytes())
    ff.stdin.close()
    if ff.wait() != 0:
        raise SystemExit(f'{d}: flip encode failed')
    return out


def esc(s):
    return s.replace('\\', '\\\\').replace(':', '\\:').replace("'", '’')


def text(t, x, y, size, color, font=FONT_R):
    return f"drawtext=fontfile={font}:expansion=none:text='{esc(t)}':fontsize={size}:fontcolor={color}:x={x}:y={y}"


inputs, chains, segs = [], [], []
for i, (label, title, what, line) in enumerate(SCENES):
    a, b = run_dir(label + '-off'), run_dir(label + '-on')
    lo, hi = window(a, b)
    n = int(min(SEG, hi - lo) * 30)
    if n < 90:
        raise SystemExit(f'{label}: the captures overlap only {hi - lo:.1f} s')
    pa, pb = pane(a, lo, n), pane(b, lo, n)
    print(f'{label}: {a} + {b}, route +{lo:.2f} s, {n} frames')
    ia, ib = len(inputs) // 2, len(inputs) // 2 + 1
    inputs += ['-i', pa, '-i', pb]
    sdr2pq = ('fps=60,scale=out_color_matrix=bt709:out_range=tv,format=yuv444p10le,'
              'setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,'
              'zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le,setsar=1')
    chains.append(f'[{ia}:v]{sdr2pq}[a{i}]')
    chains.append(f'[{ib}:v]{sdr2pq}[b{i}]')
    chains.append(
        f'color=c=0x060608:s={W}x{H}:r=60:d={n / 30.0},format=yuv420p10le[bg{i}];'
        f'[bg{i}][a{i}]overlay=0:{HEAD}:shortest=1[s{i}a];[s{i}a][b{i}]overlay={PW + 4}:{HEAD}[s{i}b];'
        f'[s{i}b]drawbox=x={PW}:y={HEAD - 60}:w=4:h={PH + 60}:color=0x202026:t=fill,'
        + text(title, '(w-tw)/2', 22, 60, WHITE, FONT_B) + ','
        + text('BEFORE', f'({PW}-tw)/2', HEAD - 56, 42, AMBER, FONT_B) + ','
        + text('AFTER: SHADOWS ON CHARACTERS AND CARS', f'{PW + 4}+({PW}-tw)/2', HEAD - 56, 42, GREEN, FONT_B) + ','
        + text(what, '(w-tw)/2', CAP_Y + 40, 44, WHITE) + ','
        + text(line, '(w-tw)/2', CAP_Y + 108, 36, GREY) + ','
        + text('Project Zomboid B42.21  ·  Riverside church lot, zoom 0.5  ·  the same save, spot and hour on both sides  ·  the game\'s own frames, 1:1, RTX 4090',
               '(w-tw)/2', H - 58, 30, GREY)
        + f',fade=t=in:st=0:d=0.3,fade=t=out:st={n / 30.0 - 0.3}:d=0.3[seg{i}]')
    segs.append(f'[seg{i}]')
    # the same scene from one run switching every second
    fd = run_dir(label + '-flip')
    nf = int(min(9.0, (capture(fd)[1][-1] - capture(fd)[1][0]) / 1000.0 - 0.5) * 30)
    pf = flip_pane(fd, nf)
    print(f'{label}: flip {fd}, {nf} frames')
    fi = len(inputs) // 2
    inputs += ['-i', pf]
    chains.append(f'[{fi}:v]{sdr2pq}[f{i}]')
    chains.append(
        f'color=c=0x060608:s={W}x{H}:r=60:d={nf / 30.0},format=yuv420p10le[fbg{i}];'
        f'[fbg{i}][f{i}]overlay={(W - FW) // 2}:{HEAD}:shortest=1,'
        + text(title + '  ·  THE SAME FRAME, SWITCHED EVERY SECOND', '(w-tw)/2', 22, 60, WHITE, FONT_B) + ','
        + text(what, '(w-tw)/2', CAP_Y + 40, 44, WHITE) + ','
        + text('One run, the shading switched between before and after every second: watch the bodies and the car, not the ground.', '(w-tw)/2', CAP_Y + 108, 36, GREY)
        + f',fade=t=in:st=0:d=0.3,fade=t=out:st={nf / 30.0 - 0.3}:d=0.3[fseg{i}]')
    segs.append(f'[fseg{i}]')

# results card (docs/findings-entity-shadows-2026-10-07.md: paired 250 ms alternation against off, uncapped, 5120x2160)
rows = [
    ('40 zombies + 3 cars at a shadow edge, day', '+14 to +29 µs', 'of 2.9-3.1 ms'),
    ('120 km/h drive, Louisville horde', 'within noise', ''),
    ('Night, the player\'s torch, 40 zombies', '+7 µs', 'of 2.2 ms'),
    ('Packed crowd, bodies shading each other', '+13 µs', 'of 2.6 ms'),
    ('Game thread (the rest runs on a worker)', '~2 µs', ''),
]
card = [f'color=c=0x060608:s={W}x{H}:r=60:d={CARD},format=yuv420p10le,'
        + text('WHAT IT COSTS', '(w-tw)/2', 150, 64, WHITE, FONT_B) + ','
        + text('Frame time added at 5120x2160 on an RTX 4090, uncapped, on and off every 250 ms of the same run', '(w-tw)/2', 250, 34, GREY)]
cx = [520, 2300, 2900]
for r, (name, cost, of) in enumerate(rows):
    yy = 420 + r * 110
    card.append(text(name, cx[0], yy, 46, WHITE))
    card.append(text(cost, cx[1], yy, 46, GREEN, FONT_M))
    card.append(text(of, cx[2], yy, 40, GREY, FONT_M))
card.append(text('Probe volumes traced once per square through the god rays\' grid, capsule shadows in the model shaders, each body\'s own',
                 '(w-tw)/2', 1060, 36, GREY))
card.append(text('sun shadow map, cloud and torch shadows, capsule ambient occlusion; one uniform upload a draw, the gather on a worker.',
                 '(w-tw)/2', 1110, 36, GREY))
card.append(text('Runs es-cost29 / es-cost30 / es-memo2, es-drive1, es-lou1, es-torchcost1, es-aocost1 (worktree entity-shadows).',
                 '(w-tw)/2', H - 90, 30, GREY))
chains.append(','.join(card) + f',fade=t=in:st=0:d=0.3[seg{len(SCENES)}]')
segs.append(f'[seg{len(SCENES)}]')

fc = ';'.join(chains) + ';' + ''.join(segs) + f'concat=n={len(segs)}:v=1:a=0,' \
     + 'setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]'
os.makedirs(os.path.dirname(OUT) or '.', exist_ok=True)
cmd = ['ffmpeg', '-hide_banner', '-v', 'error', '-y'] + inputs + [
    '-filter_complex', fc, '-map', '[v]',
    '-c:v', 'av1_nvenc', '-preset', 'p7', '-tune', 'hq', '-rc', 'vbr', '-cq', '22', '-b:v', '0', '-maxrate', '120M', '-bufsize', '240M',
    '-pix_fmt', 'p010le', '-color_primaries', 'bt2020', '-color_trc', 'smpte2084', '-colorspace', 'bt2020nc', '-color_range', 'tv',
    '-movflags', '+faststart+write_colr', '-r', '60', OUT]
subprocess.run(cmd, check=True)
poster = OUT[:-4] + '.jpg'
subprocess.run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-ss', '4', '-i', OUT, '-frames:v', '1', '-vf',
                'zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
                'zscale=p=bt709:t=bt709:m=bt709,format=yuv420p', '-q:v', '2', poster], check=False)
print(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration:stream=width,height,codec_name,color_transfer',
                      '-of', 'csv=p=0', OUT], capture_output=True, text=True).stdout.strip())
