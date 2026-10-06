#!/usr/bin/env python3
"""Encode one clip several ways and score each: bytes, played fps, SSIM / PSNR against the lossless frames (2026-10-06,
the Workshop headline animation: GIF caps the colours at 256 and the frame delay at 20 ms, so the fluid motion of the
optimized build needs a better container where the page allows one).

    harness/anim-encode.py --frames DIR [--src-fps 60] [--out DIR] VARIANT...

DIR holds the composited lossless frames f00000.png ... (harness/showcase-thumbnail-gif.py with GIF_FRAMES_DIR and
GIF_FRAMES_ONLY=1). A VARIANT is fmt:fps:size[:key=value,...]:
    avif   AV1 still-image-sequence through ffmpeg libsvtav1, 10-bit 4:2:0, BT.709 tags   keys crf (35), preset (4)
    webp   animated lossy WebP through ffmpeg libwebp_anim                                   keys q (75), m (6)
    gifski GIF through gifski (per-frame palettes, temporal dithering)                       keys q (90), mq (motion quality), lq (lossy quality)
    gifpz  GIF the way showcase-thumbnail-gif.py makes it (one palette, no dither, gifsicle) keys colors (96), lossy (100)
Common keys: t0 / len (the clip window in seconds), pre (an ffmpeg filter before the scale, ';' for ':', e.g.
pre=hqdn3d=4;3;6;4.5); the score is always against the plain resample of the same window.
Each output is decoded again (dav1d / libwebp / ffmpeg's GIF decoder) and compared frame by frame with the masters
resampled to the same fps and size (ffmpeg ssim / psnr). GIF plays at most 50 fps (delays are whole centiseconds of at
least 2): `played_fps` says what a browser shows. Prints one table row per variant; --json writes them all.
Run it as a queue `media` job (it is an encode).
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys

GIFSKI = os.environ.get('GIFSKI', os.path.expanduser('~/.cargo/bin/gifski'))
GIFSICLE = os.environ.get('GIFSICLE', os.path.expanduser('~/.local/bin/gifsicle'))


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw)


def reference(frames, src_fps, fps, size, work, t0=0.0, length=0.0, pre=''):
    """The masters at the variant's fps and size (from t0, length seconds, 0 = all; `pre` an ffmpeg filter run before the
    scale, e.g. hqdn3d), as PNGs: the encoder's input. The score compares against the plain resample (no `pre`)."""
    key = re.sub(r'[^A-Za-z0-9.=-]+', '_', f'{fps}-{size}-{t0}-{length}-{pre}')
    d = f'{work}/ref-{key}'
    if os.path.isdir(d) and os.listdir(d):
        return d
    os.makedirs(d, exist_ok=True)
    trim = [f'trim=start={t0}' + (f':duration={length}' if length else ''), 'setpts=PTS-STARTPTS'] if (t0 or length) else []
    chain = ','.join(trim + [f'fps={fps}'] + ([pre] if pre else []) + [f'scale={size}:{size}:flags=lanczos'])
    run(['ffmpeg', '-v', 'error', '-y', '-framerate', str(src_fps), '-i', f'{frames}/f%05d.png',
         '-vf', chain, '-start_number', '0', f'{d}/f%05d.png'])
    return d


def encode(fmt, fps, size, kv, ref, out):
    src = ['-framerate', str(fps), '-i', f'{ref}/f%05d.png']
    if fmt == 'avif':
        run(['ffmpeg', '-v', 'error', '-y'] + src + [
            '-vf', 'scale=out_color_matrix=bt709:out_range=tv,format=yuv420p10le',
            '-c:v', 'libsvtav1', '-preset', kv.get('preset', '4'), '-crf', kv.get('crf', '35'),
            '-svtav1-params', 'tune=0:enable-overlays=1:scd=1',
            '-color_primaries', 'bt709', '-color_trc', 'iec61966-2-1', '-colorspace', 'bt709', '-color_range', 'tv',
            '-loop', '0', '-f', 'avif', out])
    elif fmt == 'webp':
        run(['ffmpeg', '-v', 'error', '-y'] + src + [
            '-c:v', 'libwebp_anim', '-lossless', '0', '-quality', kv.get('q', '75'), '-compression_level', kv.get('m', '6'),
            '-loop', '0', out])
    elif fmt == 'gifski':
        args = [GIFSKI, '-q', '--fps', str(fps), '-Q', kv.get('q', '90')]
        if 'mq' in kv:
            args += ['--motion-quality', kv['mq']]
        if 'lq' in kv:
            args += ['--lossy-quality', kv['lq']]
        if kv.get('extra') == '1':
            args += ['--extra']
        files = sorted(os.listdir(ref))
        run(args + ['-o', out] + [f'{ref}/{f}' for f in files])
    elif fmt == 'gifpz':
        colors, lossy = kv.get('colors', '96'), kv.get('lossy', '100')
        run(['ffmpeg', '-v', 'error', '-y'] + src + [
            '-filter_complex', f'split[a][b];[a]palettegen=max_colors={colors}:stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle',
            '-loop', '0', out])
        n = len(os.listdir(ref))
        delays = [round(100 * (i + 1) / fps) - round(100 * i / fps) for i in range(n)]
        per = [a for i, d in enumerate(delays) for a in (f'-d{d}', f'#{i}')]
        run([GIFSICLE, '-O3'] + ([f'--lossy={lossy}'] if lossy != '0' else []) + [out] + per + ['-o', out])
    else:
        sys.exit(f'unknown format {fmt}')


def score(out, ref, fps):
    """SSIM (all, dB) and PSNR of the decoded output against the reference, frame for frame (pts reset on both)."""
    # an animated AVIF carries the still primary image as its own 1-frame stream: score the longest video stream
    probe = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v', '-show_entries', 'stream=index,nb_frames',
                            '-of', 'csv=p=0', out], capture_output=True, text=True).stdout.split()
    best = max(probe, key=lambda l: int(l.split(',')[1]) if l.split(',')[1].isdigit() else 0).split(',')[0] if probe else '0'
    lav = f'[0:{best}]setpts=N/TB,format=rgb24[a];[1:v]setpts=N/TB,format=rgb24[b];[a]split[a1][a2];[b]split[b1][b2];[a1][b1]ssim;[a2][b2]psnr'
    r = subprocess.run(['ffmpeg', '-hide_banner', '-nostats', '-i', out, '-framerate', str(fps), '-i', f'{ref}/f%05d.png',
                        '-lavfi', lav, '-fps_mode', 'passthrough', '-f', 'null', '-'], capture_output=True, text=True)
    ssim = re.search(r'SSIM .*All:([0-9.]+) \(([0-9.inf]+)\)', r.stderr)
    psnr = re.search(r'PSNR .*average:([0-9.inf]+)', r.stderr)
    frames = re.findall(r'frame=\s*(\d+)', r.stderr)
    return (float(ssim.group(1)) if ssim else None, float(ssim.group(2)) if ssim and ssim.group(2) != 'inf' else None,
            float(psnr.group(1)) if psnr and psnr.group(1) != 'inf' else None, r.stderr[-400:] if not ssim else '')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--frames', required=True)
    ap.add_argument('--src-fps', type=float, default=60)
    ap.add_argument('--out', default='build/showcase/anim')
    ap.add_argument('--json')
    ap.add_argument('variants', nargs='+')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    work = f'{a.out}/work'
    rows = []
    print(f'{"variant":<34} {"bytes":>10} {"KB":>7} {"played fps":>10} {"SSIM":>7} {"dB":>6} {"PSNR":>6}')
    for v in a.variants:
        parts = v.split(':')
        fmt, fps, size = parts[0], int(parts[1]), int(parts[2])
        kv = dict(p.split('=', 1) for p in parts[3].split(',')) if len(parts) > 3 and parts[3] else {}
        ext = {'avif': 'avif', 'webp': 'webp'}.get(fmt, 'gif')
        name = re.sub(r'[^A-Za-z0-9=.-]+', '_', v)
        out = f'{a.out}/{name}.{ext}'
        t0, length, pre = float(kv.pop('t0', 0)), float(kv.pop('len', 0)), kv.pop('pre', '').replace(';', ':')
        src_frames = reference(a.frames, a.src_fps, fps, size, work, t0, length, pre)
        ref = reference(a.frames, a.src_fps, fps, size, work, t0, length)
        try:
            encode(fmt, fps, size, kv, src_frames, out)
        except subprocess.CalledProcessError as e:
            print(f'{v:<34} encode failed: {e.stderr[-300:]}')
            continue
        played = fps if not ext == 'gif' else 100 / max(2, round(100 / fps))
        ssim, db, psnr, err = score(out, ref, fps)
        size_b = os.path.getsize(out)
        row = dict(variant=v, file=out, bytes=size_b, fps=fps, played_fps=round(played, 1), size=size, ssim=ssim, ssim_db=db, psnr=psnr)
        rows.append(row)
        print(f'{v:<34} {size_b:>10} {size_b // 1024:>7} {played:>10.1f} {ssim if ssim else 0:>7.4f} {db if db else 0:>6.2f} {psnr if psnr else 0:>6.2f}'
              + (f'  !! {err}' if err else ''))
    if a.json:
        json.dump(rows, open(a.json, 'w'), indent=1)


if __name__ == '__main__':
    main()
