#!/usr/bin/env bash
# The flat-roof puddle flicker (2026-10-05, Discord "Flickering textures on white roofs when it's raining") before | fix from
# in-game devCapture sequences of Jev's circle walks in the GigaMart parking lot, West Point, at max zoom (1:1 colour crop of
# the roof, --prop devCapture=7,10,60,100,crop=2900:80:1500:760,ram, harness/roof-dashes.py rig):
#   1. daylight rain (weather=rain, puddles full): before (puddleJiggleDepth=false swayFloorExact=false) | fix, real time
#   2. the roof close up (tracked per frame by phase correlation of the crop, 3x nearest), 4x slower
#   3. clear sky, puddles full: before | fix, real time
# Caption text avoids ':' and ';' (drawtext option separators). SDR captures mapped to PQ / BT.2020 (203 nits reference white,
# BT.2408), AV1 10-bit; poster .jpg tone-mapped.
#
# Usage: harness/stitch-roof-puddles.sh [out.mp4]
#   Env: RAIN_BEFORE RAIN_FIX CLEAR_BEFORE CLEAR_FIX (run labels; defaults rv-rain-before, rv-rain-fix, rv-clear-before, rv-clear-fix)
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-docs/media/roof-puddle-flicker-before-vs-fix.mp4}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
run() { ls -d harness/runs/$1-* | tail -1; }
rb=$(run "${RAIN_BEFORE:-rv-rain-before}"); rf=$(run "${RAIN_FIX:-rv-rain-fix}")
cb=$(run "${CLEAR_BEFORE:-rv-clear-before}"); cf=$(run "${CLEAR_FIX:-rv-clear-fix}")
S=1500x760
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
# 2. the tracked close-ups, both rain runs side by side, as one raw rgb24 stream (3000x760, frames 120..299 of each capture)
python3 - "$rb" "$rf" "$tmp/close.rgb" <<'EOF'
import sys, numpy as np
runs, out = sys.argv[1:3], sys.argv[3]
F0, F1, CW, CH, K, CX, CY = 120, 300, 500, 245, 3, 520, 240  # the roof round its middle AC unit in frame 200
def frames(r): return np.memmap(r + '/capture/frames.rgba', dtype=np.uint8, mode='r').reshape(-1, 760, 1500, 4)
def gray(a, i): return np.flipud(a[i])[:, :, :3].astype(np.float32).mean(axis=2)
def shift(A, B):
    F = np.fft.fft2(A - A.mean()) * np.conj(np.fft.fft2(B - B.mean())); c = np.fft.ifft2(F / (np.abs(F) + 1e-6)).real
    y, x = np.unravel_index(np.argmax(c), c.shape)
    return int(x - c.shape[1] * (x > c.shape[1] // 2)), int(y - c.shape[0] * (y > c.shape[0] // 2))
panes = []
for r in runs:
    a = frames(r); R = gray(a, 200); seq = []
    for i in range(F0, F1):
        sx, sy = shift(gray(a, i), R)
        x0 = min(max(CX + sx - CW // 2, 0), 1500 - CW); y0 = min(max(CY + sy - CH // 2, 0), 760 - CH)
        c = np.flipud(a[i])[y0:y0 + CH, x0:x0 + CW, :3]
        seq.append(np.repeat(np.repeat(c, K, axis=0), K, axis=1))  # 1500x735
    panes.append(seq)
with open(out, 'wb') as o:
    for p, q in zip(*panes):
        frame = np.zeros((760, 3000, 3), np.uint8)
        frame[25:760, :1500] = p; frame[25:760, 1500:] = q
        o.write(frame.tobytes())
EOF
SDR2PQ="setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le"
lab() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=30:fontcolor=$2:box=1:boxcolor=black@0.6:boxborderw=8:x=14:y=14"; }
cap() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=26:fontcolor=white:box=1:boxcolor=black@0.6:boxborderw=6:x=(w-tw)/2:y=h-th-14"; }
pair() {  # inputs $1 $2 -> [$3]: both captures flipped upright, labelled, 8.5 s, side by side
  echo "[$1:v]vflip,format=rgb24,trim=end=8.5,setpts=PTS-STARTPTS,$(lab 'Before (released build)' 0xFF8A80)[$3a];[$2:v]vflip,format=rgb24,trim=end=8.5,setpts=PTS-STARTPTS,$(lab 'Fix' 0x7CFC9A)[$3b];[$3a][$3b]hstack=inputs=2"
}

mkdir -p "$(dirname "$out")"
ffmpeg -v error -y \
  -f rawvideo -pixel_format rgba -video_size $S -framerate 60 -i "$rb/capture/frames.rgba" \
  -f rawvideo -pixel_format rgba -video_size $S -framerate 60 -i "$rf/capture/frames.rgba" \
  -f rawvideo -pixel_format rgb24 -video_size 3000x760 -framerate 15 -i "$tmp/close.rgb" \
  -f rawvideo -pixel_format rgba -video_size $S -framerate 60 -i "$cb/capture/frames.rgba" \
  -f rawvideo -pixel_format rgba -video_size $S -framerate 60 -i "$cf/capture/frames.rgba" \
  -filter_complex "$(pair 0 1 r),$(cap 'GigaMart roof, West Point, max zoom, rain, Jev walking circles - white dashes flicker over the roof where the puddle and the roof trade places'),fps=60[s1];\
[2:v]format=rgb24,$(lab 'Before - close up, 3x, 4x slower' 0xFF8A80),drawtext=fontfile=$FONT:text='Fix - close up, 3x, 4x slower':fontsize=30:fontcolor=0x7CFC9A:box=1:boxcolor=black@0.6:boxborderw=8:x=1514:y=14,$(cap 'Cached puddle depth kept an old camera jiggle and foliage sway rounded the roof depth - the puddle fought the corrugation'),fps=60[s2];\
$(pair 3 4 c),$(cap 'Clear sky, puddles full - the same dashes before, a steady roof with the fix (as in the stock game)'),fps=60[s3];\
[s1][s2][s3]concat=n=3:v=1,format=yuv420p,$SDR2PQ,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]" \
  -map "[v]" -pix_fmt p010le -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq ${CQ:-30} -b:v 0 \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv "$out"
ffmpeg -v error -y -ss 11 -i "$out" -frames:v 1 -vf \
  "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuvj420p" \
  "${out%.mp4}.jpg"
echo "$out"
