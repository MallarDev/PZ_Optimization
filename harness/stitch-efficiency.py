#!/usr/bin/env python3
"""Side-by-side Workshop videos from laptop runs that have no screen recorder (2026-10-06, the description rewrite):

    flip  the Ayaneo Flip, 120 km/h drive at a 120 fps cap: stock vs every optimization at its default plus the
          "max efficiency" core and clock choices (corePlacement=efficient, gpuPstate=min_sclk); fps and socket watts
    dell  the Dell (i5-6300HQ / GTX 960M), 120 km/h drive: stock vs the "Low-end hardware" preset; fps
    mac   the MacBook Pro M1 Pro, a night torch walk: OpenGL 2.1 (macGlCore=false, the Enhancements cannot run) vs
          OpenGL 4.1 core with the Mac's own Enhancements settings; fps

Pictures: the game's own presented frames (pzopt.FrameCapture, `--prop devCapture=<start>,<secs>,60,50[,ram]`, overlay
off) of the `*-cap` runs. Reading every frame back slows the game and raises its power, so the burned-in numbers come
from a second, uncaptured run of the same route per side (same label without `-cap`): live fps and watts over the last
second, on the route clock (pzopt-schedule.out route start; every captured frame and log row has its epoch ms). The
optimized capture is shifted by the offset whose frames match the stock capture's best (up to +-1.5 s).

Layout per docs/media-style.md: 3840x1800, 60 fps, header band, two 1918 px panes (the 50 % capture scaled 2x,
Lanczos), live numbers under each pane, a results card; composed in SDR and mapped to PQ / BT.2020 at 203 nits,
AV1 10-bit (av1_nvenc); the poster .jpg the only tone-mapped derivative.

Usage: harness/stitch-efficiency.py flip|flip60|mac|macday|macpond|dell [out.mp4]     (a queued media job: harness/queue.sh submit media ...)
"""
import glob
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

NUMBERS_ONLY = '--numbers' in sys.argv  # print the route means as JSON and stop (harness/efficiency-anim.py's slide)
if NUMBERS_ONLY:
    sys.argv.remove('--numbers')
KIND = sys.argv[1] if len(sys.argv) > 1 else 'flip'
W, H, FPS = 3840, 1800, 60
HEAD = 150
PW = 1918
CARD_S = 8.0
FONT_B = '/usr/share/fonts/noto/NotoSans-SemiBold.ttf'
if not os.path.exists(FONT_B):
    FONT_B = '/usr/share/fonts/noto/NotoSans-Bold.ttf'
FONT_R = '/usr/share/fonts/noto/NotoSans-Regular.ttf'
FONT_M = '/usr/share/fonts/noto/NotoSansMono-Regular.ttf'
BG = (11, 11, 14)
DIV = (38, 38, 44)
WHITE, GREY, MUTED = (240, 240, 244), (180, 180, 184), (138, 138, 144)
STOCK, OPT = (201, 89, 43), (68, 222, 124)

CFG = {
    'flip': dict(
        out='workshop-media/flip-max-efficiency-vs-stock.mp4',
        a='flip-flipeff-stock', b='flip-flipeff-opt',
        title='AYANEO FLIP  ·  120 KM/H DRIVE AT A 120 FPS CAP',
        la='STOCK GAME', lb='PZ OPTIMIZATION  ·  MAX EFFICIENCY',
        sub_b='every optimization at its default + efficient cores + lowest GPU clock',
        watts=True,
        footer='Ayaneo Flip (Ryzen AI 9 HX 370, Radeon 890M, 1920x1080)  ·  the same save, car and path on both sides  ·  '
               'the game\'s own frames; numbers from uncaptured runs of the same drive  ·  watts = the SoC\'s socket power (PPT)',
        card_title='THE SAME DRIVE FOR FEWER WATTS',
        card_note='Options > PZ Optimization > CPU cores and power: "Which cores" = efficient, "AMD GPU clock level" = lowest. '
                  'Runs flipeff-stock / flipeff-opt, 2026-10-06.'),
    'mac': dict(
        out='workshop-media/mac-opengl-2.1-vs-4.1.mp4',
        a='mac-macgl-vid-legacy', b='mac-macgl-vid-core',
        title='MACBOOK PRO M1 PRO  ·  NIGHT, TORCH IN HAND',
        la='OPENGL 2.1 (BEFORE)', lb='OPENGL 4.1 CORE (NOW)',
        sub_a='Apple\'s legacy context: the Enhancements cannot run',
        sub_b='per-pixel light, shadows, AO, reflections, god rays ... running on the Mac',
        watts=False,
        footer='MacBook Pro M1 Pro (3024x1964 Retina)  ·  the same save, spot, hour and walk on both sides  ·  '
               'the game\'s own frames; numbers from uncaptured runs of the same walk',
        card_title='THE ENHANCEMENTS NOW RUN ON A MAC',
        card_note='Options > PZ Optimization > "macOS: OpenGL 4.1" (on by default). The Mac\'s Enhancements settings on both '
                  'sides; on OpenGL 2.1 they switch themselves off. Runs macgl-vid-*, 2026-10-06.'),
    'dell': dict(
        out='workshop-media/dell-low-end-vs-stock.mp4',
        a='dell-delllow-stock', b='dell-delllow-dr-r2', b_cap='dell-delllow-dr-cap',
        title='4-CORE LAPTOP (CORE i5-6300HQ, GTX 960M)  ·  120 KM/H DRIVE',
        la='STOCK GAME', lb='PZ OPTIMIZATION  ·  LOW-END + DYNAMIC RESOLUTION',
        sub_a='the game\'s default options',
        sub_b='the "Low-end hardware" preset + TAAU dynamic resolution held to 60 fps',
        watts=False,
        footer='Dell laptop (Core i5-6300HQ 4 cores, GeForce GTX 960M 4 GB, 8 GB RAM, 1920x1080)  ·  the same save, car and path on both sides  ·  '
               'the game\'s own frames; numbers from uncaptured runs of the same drive',
        card_title='A 4-CORE LAPTOP KEEPS UP WITH THE CAR',
        card_note='Options > PZ Optimization > "Low-end hardware (4 cores or less)": no chunk worker pool, trees baked only while walking, '
                  'lighting 10/s, UI 30/s, texture compression; plus Dynamic resolution (TAAU) held to 60 fps. All runs: stock 28.7 fps; '
                  'preset 40.9 / 38.8; preset + dynamic resolution 32.5 / 41.8 (shown). Runs delllow-*, 2026-10-06.'),
}
CFG['macday'] = dict(CFG['mac'], out='workshop-media/mac-opengl-2.1-vs-4.1-day.mp4', a='mac-macgl-day-legacy', b='mac-macgl-day-core',
                     slide_a='mac-macgl-day-stock2',
                     title='MACBOOK PRO M1 PRO  ·  ROSEWOOD AT 17:00',
                     footer='MacBook Pro M1 Pro (3024x1964 Retina)  ·  the same save, street, hour and walk on both sides  ·  '
                            'the game\'s own frames; numbers from uncaptured runs of the same walk',
                     card_note='Options > PZ Optimization > "macOS: OpenGL 4.1" (on by default). The Mac\'s Enhancements settings on both '
                               'sides; on OpenGL 2.1 they switch themselves off. Runs macgl-day-*, 2026-10-06.')
CFG['macpond'] = dict(CFG['mac'], out='workshop-media/mac-opengl-2.1-vs-4.1-pond.mp4', a='mac-pondmac-stock', b='mac-pondmac-core',
                      stock_a=True, la='STOCK GAME (OPENGL 2.1)', sub_a='the game as it ships: Apple\'s legacy OpenGL 2.1 context',
                      title='MACBOOK PRO M1 PRO  ·  A WALK ROUND THE POND',
                      footer='MacBook Pro M1 Pro (3024x1964 Retina)  ·  the maintainer\'s own save, the same pond and hour on both sides, '
                             'walked by Jev  ·  the game\'s own frames; numbers from uncaptured runs of the same walk',
                      card_note='Options > PZ Optimization > "macOS: OpenGL 4.1" (on by default). Left: the stock game; right: PZ Optimization '
                                'with the Mac\'s own Enhancements settings. Runs pondmac-stock* / pondmac-core*, 2026-10-06.')
CFG['flipstorm'] = dict(CFG['flip'], out='workshop-media/flip-storm-120fps-vs-stock.mp4', a='flip-flipw120-storm-stock',
                        b='flip-flipw120-storm-eff', title='AYANEO FLIP  ·  THUNDERSTORM AT A 120 FPS CAP',
                        lb='PZ OPTIMIZATION  ·  EFFICIENT CORES',
                        sub_b='every optimization at its default + efficient cores; GPU clock left to the driver',
                        footer='Ayaneo Flip (Ryzen AI 9 HX 370, Radeon 890M, 1920x1080)  ·  the storm preset (rain, lightning, '
                               'the spinning walk through Rosewood) on both sides  ·  the game\'s own frames; numbers from uncaptured runs  ·  '
                               'watts = the SoC\'s socket power (PPT)',
                        card_title='TWICE THE FRAMES, HALF THE ENERGY PER FRAME', card_fixed=True,
                        card_note='Options > PZ Optimization > CPU cores and power: "Which cores" = efficient, "AMD GPU clock level" = off. '
                                  'At the 60 fps cap the same storm: stock 34 fps / 21.0 W, efficient 57 fps / 21.0 W. '
                                  'Runs flipw120-storm-* / flipw-storm-*, 2026-10-06.')
CFG['flip60'] = dict(CFG['flip'], out='workshop-media/flip-efficient-cores-vs-stock-60fps.mp4', a='flip-flip60-stock', b='flip-flip60-nopstate',
                     b_cap='flip-flip60-eff-cap',
                     title='AYANEO FLIP  ·  120 KM/H DRIVE AT A 60 FPS CAP',
                     lb='PZ OPTIMIZATION  ·  EFFICIENT CORES',
                     sub_b='every optimization at its default + efficient cores; GPU clock left to the driver',
                     card_note='Options > PZ Optimization > CPU cores and power: "Which cores" = efficient, "AMD GPU clock level" = off. '
                               'Repeat runs: stock 11.4 W, efficient cores 10.4 W; holding a GPU clock level (auto / lowest) drew 13.8-13.9 W here. '
                               'Runs flip60-*, 2026-10-06.')
CFG = CFG[KIND]
OUT = sys.argv[2] if len(sys.argv) > 2 else CFG['out']


def run_dir(label):
    runs = sorted(glob.glob(f'harness/runs/{label}-2*'))
    if not runs:
        raise SystemExit(f'no run {label}')
    return runs[-1]


def kv(path):
    return dict(l.strip().split('=', 1) for l in open(path) if '=' in l)


def route_epoch(d):
    return int(kv(os.path.join(d, 'pzopt-schedule.out'))['route_start_epoch_ms'])


def capture(d):
    """(frames (n, h, w, 4) rows bottom-up, route-relative seconds per frame, w, h)."""
    c = os.path.join(d, 'capture')
    lines = open(os.path.join(c, 'index.txt')).read().split('\n')
    head = dict(x.split('=') for x in lines[0].split())
    w, h = int(head['w']), int(head['h'])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()])
    stamps = stamps[:int(np.argmax(stamps < 10**12)) or len(stamps)] if (stamps < 10**12).any() else stamps  # a line cut off at exit
    raw = np.memmap(os.path.join(c, 'frames.rgba'), dtype=np.uint8, mode='r')
    n = min(len(stamps), raw.size // (w * h * 4))
    return raw[:n * w * h * 4].reshape(n, h, w, 4), (stamps[:n] - route_epoch(d)) / 1000.0, w, h


def csv(path):
    rows = [l.strip().split(',') for l in open(path) if l.strip()]
    head = rows[0]
    out = {k: [] for k in head}
    for r in rows[1:]:
        if len(r) != len(head):
            continue
        for k, v in zip(head, r):
            try:
                out[k].append(float(v))
            except ValueError:
                out[k].append(np.nan)
    return {k: np.array(v) for k, v in out.items()}


class Numbers:
    """The uncaptured run's presented frames (pzopt-overlay.out) and power (pzopt-power.out) on its route clock."""

    def __init__(self, d, watts):
        r0 = route_epoch(d)
        ov = csv(os.path.join(d, 'pzopt-overlay.out'))
        self.t = (ov['epoch_ms'] - r0) / 1000.0
        self.ft = ov['frametime']
        sched = kv(os.path.join(d, 'pzopt-schedule.out'))
        end = self.t[-1] - 1.5  # the last rows are the quit (a few fps)
        if 'route_end_epoch_ms' in sched:
            end = (int(sched['route_end_epoch_ms']) - r0) / 1000.0
        self.end = end
        self.pw_t = self.pw = None
        p = os.path.join(d, 'pzopt-power.out')
        if watts and os.path.exists(p):
            pw = csv(p)
            col = 'soc_w' if np.isfinite(pw.get('soc_w', np.array([np.nan]))).any() else 'total_w'
            self.pw_t, self.pw = (pw['epoch_ms'] - r0) / 1000.0, pw[col]
            self.pw_col = col
        m = (self.t >= 0) & (self.t <= end)
        ft = self.ft[m]
        self.fps = m.sum() / end
        self.p99 = float(np.percentile(ft, 99))
        self.low1 = 1000.0 / float(np.mean(np.sort(ft)[-max(1, len(ft) // 100):]))
        if self.pw is not None:
            pm = (self.pw_t >= 0) & (self.pw_t <= end) & np.isfinite(self.pw)
            self.watts = float(np.mean(self.pw[pm]))
            self.jpf = self.watts / self.fps

    def live(self, t):
        fps = int(((self.t > t - 1.0) & (self.t <= t)).sum())
        w = None
        if self.pw is not None:
            m = (self.pw_t > t - 1.0) & (self.pw_t <= t) & np.isfinite(self.pw)
            w = float(np.mean(self.pw[m])) if m.any() else None
        return fps, w


def small(frames, j):
    f = frames[j, ::16, ::16, :3].astype(np.float32)
    return f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114


def align(ca, cb):
    """Seconds to add to b's route clock so its frames show what a's show."""
    lo, hi = max(ca[1][0], cb[1][0]) + 1.6, min(ca[1][-1], cb[1][-1]) - 1.6
    probes = np.linspace(max(lo, 0.5), hi, 8)
    step = float(np.median(np.diff(cb[1])))
    best, best_err = 0.0, None
    for d in np.arange(-1.5, 1.5 + 1e-6, step):
        err = 0.0
        for t in probes:
            ja = int(np.argmin(np.abs(ca[1] - t)))
            jb = int(np.argmin(np.abs(cb[1] - (t + d))))
            err += float(np.abs(small(ca[0], ja) - small(cb[0], jb)).mean())
        if best_err is None or err < best_err:
            best, best_err = float(d), err
    return best


_fonts = {}


def font(path, size):
    k = (path, size)
    if k not in _fonts:
        _fonts[k] = ImageFont.truetype(path, size)
    return _fonts[k]


def text(draw, xy, s, size, color, path=FONT_R, anchor='la'):
    draw.text(xy, s, font=font(path, size), fill=color, anchor=anchor)


ca_dir, cb_dir = run_dir(CFG.get('a_cap', CFG['a'] + '-cap')), run_dir(CFG.get('b_cap', CFG['b'] + '-cap'))
na, nb = Numbers(run_dir(CFG['a']), CFG['watts']), Numbers(run_dir(CFG['b']), CFG['watts'])
if NUMBERS_ONLY:
    import json
    if CFG.get('slide_a'):   # the slide's "before" is the stock game (enabled=false), the video's left pane OpenGL 2.1 with the build
        na = Numbers(run_dir(CFG['slide_a']), CFG['watts'])
    cw, ch = (int(v.split('=')[1]) for v in open(os.path.join(ca_dir, 'capture', 'index.txt')).readline().split()[:2])
    out = {k: dict(fps=n.fps, low1=n.low1, p99=n.p99, watts=n.watts if n.pw is not None else None,
                   jpf=n.jpf if n.pw is not None else None) for k, n in (('a', na), ('b', nb))}
    out['panes'] = dict(head=HEAD, pw=PW, ph=int(round(ch * PW / cw)), gap=4)   # where the video's two panes sit
    print(json.dumps(out))
    sys.exit(0)
ca, cb = capture(ca_dir), capture(cb_dir)
shift = align(ca, cb)
t0 = max(0.0, ca[1][0] + 0.1, cb[1][0] - shift + 0.1)
t1 = min(ca[1][-1], cb[1][-1] - shift, na.end, nb.end) - 0.1
PH = int(round(ca[3] * PW / ca[2]))
STRIP = HEAD + PH
print(f'{KIND}: {ca_dir} + {cb_dir}, route +{t0:.2f} .. +{t1:.2f} s, b shifted {shift:+.3f} s, panes {PW}x{PH}')
print(f'  numbers a: {na.fps:.1f} fps p99 {na.p99:.1f} ms 1%low {na.low1:.0f}' + (f' {na.watts:.1f} W' if na.pw is not None else ''))
print(f'  numbers b: {nb.fps:.1f} fps p99 {nb.p99:.1f} ms 1%low {nb.low1:.0f}' + (f' {nb.watts:.1f} W' if nb.pw is not None else ''))

# static parts
base = Image.new('RGB', (W, H), BG)
dr = ImageDraw.Draw(base)
text(dr, (W // 2, 24), CFG['title'], 60, WHITE, FONT_B, 'ma')
text(dr, (PW // 2, HEAD - 50), CFG['la'], 42, STOCK, FONT_B, 'ma')
text(dr, (PW + 4 + PW // 2, HEAD - 50), CFG['lb'], 42, OPT, FONT_B, 'ma')
dr.rectangle([PW, HEAD - 60, PW + 3, H - 70], fill=DIV)
if CFG.get('sub_a'):
    text(dr, (PW // 2, STRIP + 24), CFG['sub_a'], 34, GREY, FONT_R, 'ma')
if CFG.get('sub_b'):
    text(dr, (PW + 4 + PW // 2, STRIP + 24), CFG['sub_b'], 34, GREY, FONT_R, 'ma')
text(dr, (W // 2, H - 52), CFG['footer'], 28, MUTED, FONT_R, 'ma')
base_np = np.asarray(base).copy()

ff = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}',
                       '-r', str(FPS), '-i', '-',
                       '-vf', 'scale=out_color_matrix=bt709:out_range=tv,format=yuv444p10le,'
                       'setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,'
                       'zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le,setsar=1,'
                       'setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv',
                       '-c:v', 'av1_nvenc', '-preset', 'p7', '-tune', 'hq', '-rc', 'vbr', '-cq', '28', '-b:v', '0',
                       '-maxrate', '40M', '-bufsize', '80M', '-pix_fmt', 'p010le', '-color_primaries', 'bt2020',
                       '-color_trc', 'smpte2084', '-colorspace', 'bt2020nc', '-color_range', 'tv',
                       '-movflags', '+faststart+write_colr', '-r', str(FPS), OUT + '.part.mp4'], stdin=subprocess.PIPE)


def pane(c, t):
    j = int(np.argmin(np.abs(c[1] - t)))
    f = Image.fromarray(np.ascontiguousarray(c[0][j, ::-1, :, :3]))
    return np.asarray(f.resize((PW, PH), Image.LANCZOS))


def readout(img, x0, num, color):
    """Live numbers under a pane: big fps, watts beside it."""
    fps, w = num
    d = ImageDraw.Draw(img)
    cx = x0 + PW // 2
    y = 88
    if w is None:
        text(d, (cx, y), f'{fps}', 150, color, FONT_M, 'ma')
        text(d, (cx, y + 190), 'fps, last second', 32, MUTED, FONT_R, 'ma')
    else:
        text(d, (cx - 40, y), f'{fps}', 150, color, FONT_M, 'ra')
        text(d, (cx - 40, y + 190), 'fps, last second', 32, MUTED, FONT_R, 'ra')
        text(d, (cx + 40, y), f'{w:.1f}', 150, color, FONT_M, 'la')
        text(d, (cx + 40, y + 190), 'watts, last second', 32, MUTED, FONT_R, 'la')


strip_h = H - 70 - STRIP
n = int((t1 - t0) * FPS)
fade = int(0.3 * FPS)
cache = {}
for i in range(n):
    t = t0 + i / FPS
    fr = base_np.copy()
    fr[HEAD:HEAD + PH, 0:PW] = pane(ca, t)
    fr[HEAD:HEAD + PH, PW + 4:PW + 4 + PW] = pane(cb, t + shift)
    key = (na.live(t), nb.live(t))
    key = ((key[0][0], None if key[0][1] is None else round(key[0][1], 1)),
           (key[1][0], None if key[1][1] is None else round(key[1][1], 1)))
    if key not in cache:
        if len(cache) > 256:
            cache.clear()
        s = Image.fromarray(base_np[STRIP:STRIP + strip_h].copy())
        readout(s, 0, key[0], STOCK)
        readout(s, PW + 4, key[1], OPT)
        cache[key] = np.asarray(s)
    fr[STRIP:STRIP + strip_h] = cache[key]
    k = min(1.0, (i + 1) / fade, (n - i) / fade)
    if k < 1.0:
        fr = (fr.astype(np.float32) * k).astype(np.uint8)
    ff.stdin.write(fr.tobytes())

# results card
card = Image.new('RGB', (W, H), BG)
d = ImageDraw.Draw(card)
title = CFG['card_title']
if CFG['watts'] and not CFG.get('card_fixed') and na.pw is not None and nb.pw is not None and nb.watts > na.watts * 0.97:
    # no watt saving to claim: say what the numbers do show
    title = 'MORE FRAMES, SMOOTHER, THE SAME ENERGY PER FRAME' if nb.jpf <= na.jpf * 1.03 else 'MORE FRAMES AND SMOOTHER'
text(d, (W // 2, 130), title, 64, WHITE, FONT_B, 'ma')
text(d, (W // 2, 230), f'over the whole route ({t1 - 0:.0f} s shown, {na.end:.0f} s measured), uncaptured runs', 34, GREY, FONT_R, 'ma')
cx = [420, 2000, 2560, 3120]
text(d, (cx[1], 350), CFG['la'].split(' (')[0] if KIND.startswith('mac') else 'STOCK', 34, MUTED, FONT_B)
text(d, (cx[2], 350), 'OPENGL 4.1' if KIND.startswith('mac') else ('LOW-END PRESET' if KIND == 'dell' else ('EFFICIENT CORES' if KIND in ('flip60', 'flipstorm') else 'MAX EFFICIENCY')), 34, MUTED, FONT_B)
text(d, (cx[3], 350), 'CHANGE', 34, MUTED, FONT_B)
d.rectangle([cx[0], 410, W - cx[0], 411], fill=(80, 80, 88))


def pct(a, b):
    return f'{(b - a) / a * 100:+.0f} %'


rows = [('Frames per second, mean', f'{na.fps:.0f} fps', f'{nb.fps:.0f} fps', pct(na.fps, nb.fps), nb.fps >= na.fps * 0.98),
        ('1 % low', f'{na.low1:.0f} fps', f'{nb.low1:.0f} fps', pct(na.low1, nb.low1), nb.low1 >= na.low1 * 0.98),
        ('99th percentile frame time', f'{na.p99:.1f} ms', f'{nb.p99:.1f} ms', pct(na.p99, nb.p99), nb.p99 <= na.p99 * 1.02)]
if na.pw is not None and nb.pw is not None:
    rows += [('Socket power, mean', f'{na.watts:.1f} W', f'{nb.watts:.1f} W', pct(na.watts, nb.watts), nb.watts <= na.watts),
             ('Energy per frame', f'{na.jpf * 1000:.0f} mJ', f'{nb.jpf * 1000:.0f} mJ', pct(na.jpf, nb.jpf), nb.jpf <= na.jpf)]
if KIND.startswith('mac'):
    rows.append(('Enhancements that can run', 'none', 'all 15', '+15', True) if CFG.get('stock_a') else
                ('Enhancements that can run', '1 (HDR)', 'all 15', '+14', True))
for r, (name, a, b, ch, good) in enumerate(rows):
    y = 440 + r * 110
    text(d, (cx[0], y), name, 46, WHITE)
    text(d, (cx[1], y), a, 46, STOCK, FONT_M)
    text(d, (cx[2], y), b, 46, OPT, FONT_M)
    text(d, (cx[3], y), ch, 46, OPT if good else STOCK, FONT_M)
text(d, (W // 2, H - 170), CFG['card_note'], 32, GREY, FONT_R, 'ma')
text(d, (W // 2, H - 52), CFG['footer'], 28, MUTED, FONT_R, 'ma')
card_np = np.asarray(card)
nc = int(CARD_S * FPS)
for i in range(nc):
    k = min(1.0, (i + 1) / fade)
    ff.stdin.write((card_np if k >= 1.0 else (card_np.astype(np.float32) * k).astype(np.uint8)).tobytes())
ff.stdin.close()
if ff.wait() != 0:
    raise SystemExit('encode failed')
os.replace(OUT + '.part.mp4', OUT)
poster = OUT[:-4] + '.jpg'
subprocess.run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-ss', f'{min(8.0, (t1 - t0) / 2):.2f}', '-i', OUT, '-frames:v', '1', '-vf',
                'zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
                'zscale=p=bt709:t=bt709:m=bt709,format=yuv420p', '-q:v', '2', poster], check=False)
print(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration:stream=width,height,codec_name,color_transfer',
                      '-of', 'csv=p=0', OUT], capture_output=True, text=True).stdout.strip())
