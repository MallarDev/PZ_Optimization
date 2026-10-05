#!/usr/bin/env bash
# The manhole puddle flicker (2026-10-05) before | fix from in-game devCapture sequences of Jev's circle walks at max zoom on the
# maintainer's save (1:1 colour crop, --prop devCapture=7,10,60,100,crop=1700:600:1400:900,ram, harness/manhole-flicker.py rig):
#   1. clear weather, puddles pinned full: before (floorDecalsPerFrame=false / the released build) | fix, real time
#   2. the cover close up (found by template match, tracked per frame by phase correlation of the crop, 7x nearest), 4x slower
#   3. daylight rain (weather=rain): before | fix, real time
# Caption text avoids ':' and ';' (drawtext option separators). SDR captures mapped to PQ / BT.2020 (203 nits reference white, BT.2408), AV1 10-bit; poster .jpg tone-mapped.
#
# Usage: harness/stitch-manhole.sh [out.mp4]
#   Env: BEFORE FIX RAIN_BEFORE RAIN_FIX CONTROL (run labels; defaults manhole-repro4, manhole-fix2-jev, manhole-drizzle-before,
#   manhole-drizzle-fix, manhole-stock: the stock run whose frame 200 gives the cover's template at 1124,262)
set -euo pipefail
cd "$(dirname "$0")/.."

out="${1:-docs/media/manhole-puddle-flicker-before-vs-fix.mp4}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
run() { ls -d harness/runs/$1-* | tail -1; }
b=$(run "${BEFORE:-manhole-repro4}"); f=$(run "${FIX:-manhole-fix2-jev}")
rb=$(run "${RAIN_BEFORE:-manhole-drizzle-before}"); rf=$(run "${RAIN_FIX:-manhole-drizzle-fix}")
S=1400x900
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

# 2. the tracked close-ups, both runs side by side, as one raw rgb24 stream (2800x900, frames 120..299 of each capture)
python3 - "$b" "$f" "$tmp/close.rgb" "$(run "${CONTROL:-manhole-stock}")" <<'EOF'
import sys, numpy as np
from numpy.lib.stride_tricks import sliding_window_view as swv
runs, out = sys.argv[1:3], sys.argv[3]
F0, F1, CW, CH, K = 120, 300, 200, 125, 7
def frames(r): return np.memmap(r + '/capture/frames.rgba', dtype=np.uint8, mode='r').reshape(-1, 900, 1400, 4)
def gray(a, i): return np.flipud(a[i])[:, :, :3].astype(np.float32).mean(axis=2)
def shift(A, B):
    F = np.fft.fft2(A - A.mean()) * np.conj(np.fft.fft2(B - B.mean())); c = np.fft.ifft2(F / (np.abs(F) + 1e-6)).real
    y, x = np.unravel_index(np.argmax(c), c.shape)
    return int(x - c.shape[1] * (x > c.shape[1] // 2)), int(y - c.shape[0] * (y > c.shape[0] // 2))
T = gray(frames(sys.argv[4]), 200)[262 - 12:262 + 12, 1124 - 20:1124 + 20]  # the cover in the stock run's frame 200
tn = (T - T.mean()) / T.std()
def cover(g):  # the cover's centre in a run's reference frame: normalised cross-correlation with the stock template
    sub = g[100:450, 900:1350]; w = swv(sub, T.shape); wm = w - w.mean(axis=(2, 3), keepdims=True)
    ncc = (wm * tn).sum(axis=(2, 3)) / (np.sqrt((wm ** 2).sum(axis=(2, 3))) * np.sqrt(T.size) + 1e-6)
    y, x = np.unravel_index(np.argmax(ncc), ncc.shape)
    return 900 + x + 20, 100 + y + 12
panes = []
for r in runs:
    a = frames(r); R = gray(a, 200); cx, cy = cover(R); seq = []
    for i in range(F0, F1):
        sx, sy = shift(gray(a, i), R)
        x0 = min(max(cx + sx - CW // 2, 0), 1400 - CW); y0 = min(max(cy + sy - CH // 2, 0), 900 - CH)
        c = np.flipud(a[i])[y0:y0 + CH, x0:x0 + CW, :3]
        seq.append(np.repeat(np.repeat(c, K, axis=0), K, axis=1))  # 1400x875
    panes.append(seq)
with open(out, 'wb') as o:
    for p, q in zip(*panes):
        frame = np.zeros((900, 2800, 3), np.uint8)
        frame[25:900, :1400] = p; frame[25:900, 1400:] = q
        o.write(frame.tobytes())
EOF

SDR2PQ="setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le"
lab() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=30:fontcolor=$2:box=1:boxcolor=black@0.6:boxborderw=8:x=14:y=14"; }
cap() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=26:fontcolor=white:box=1:boxcolor=black@0.6:boxborderw=6:x=(w-tw)/2:y=h-th-14"; }
pair() {  # inputs $1 $2 -> [$3]: both captures flipped upright, labelled, 8.5 s, side by side
  echo "[$1:v]vflip,format=rgb24,trim=end=8.5,setpts=PTS-STARTPTS,$(lab 'Before (released build)' 0xFF8A80)[$3a];[$2:v]vflip,format=rgb24,trim=end=8.5,setpts=PTS-STARTPTS,$(lab 'Fix (floorDecalsPerFrame)' 0x7CFC9A)[$3b];[$3a][$3b]hstack=inputs=2"
}

mkdir -p "$(dirname "$out")"
ffmpeg -v error -y \
  -f rawvideo -pixel_format rgba -video_size $S -framerate 60 -i "$b/capture/frames.rgba" \
  -f rawvideo -pixel_format rgba -video_size $S -framerate 60 -i "$f/capture/frames.rgba" \
  -f rawvideo -pixel_format rgb24 -video_size 2800x900 -framerate 15 -i "$tmp/close.rgb" \
  -f rawvideo -pixel_format rgba -video_size $S -framerate 60 -i "$rb/capture/frames.rgba" \
  -f rawvideo -pixel_format rgba -video_size $S -framerate 60 -i "$rf/capture/frames.rgba" \
  -filter_complex "$(pair 0 1 c),$(cap 'Manhole cover in a puddle, max zoom, Jev walking circles (puddles full, clear sky) - the puddle cuts through the cover in moving stripes'),fps=60[s1];\
[2:v]format=rgb24,$(lab 'Before - close up, 7x, 4x slower' 0xFF8A80),drawtext=fontfile=$FONT:text='Fix - close up, 7x, 4x slower':fontsize=30:fontcolor=0x7CFC9A:box=1:boxcolor=black@0.6:boxborderw=8:x=1414:y=14,$(cap 'Baked into the chunk texture the cover sits under the puddle pass and z-fights with it - stock and the fix draw it after the puddles'),fps=60[s2];\
$(pair 3 4 r),$(cap 'Daylight rain (weather=rain) - stripes before, a solid cover with the fix'),fps=60[s3];\
[s1][s2][s3]concat=n=3:v=1,format=yuv420p,$SDR2PQ,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]" \
  -map "[v]" -pix_fmt p010le -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq ${CQ:-30} -b:v 0 \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv "$out"
ffmpeg -v error -y -ss 11 -i "$out" -frames:v 1 -vf \
  "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuvj420p" \
  "${out%.mp4}.jpg"
echo "$out"
