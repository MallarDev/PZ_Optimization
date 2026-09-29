#!/usr/bin/env bash
# Issue #38 before / after video: two --record walks past a row of floating wall cabinets (runs cabvid-off / cabvid-on:
# --flag start=8091,11526 --flag route=W:10 --flag speed=1 --flag zoom=1, pixelLight + ambientOcclusion, --prop
# tileDepthFix=off vs the default), each cropped 1:1 around the cabinets, side by side, aligned on the walk's motion onset
# (the off run from its route start, the on run at the offset whose frames match it best: the motion-onset detector of
# showcase-times.py picked moments half a second apart on these walks). AV1 10-bit PQ / BT.2020 end to end; the poster
# .jpg is the only tone-mapped copy. Take of 2026-09-29: runs cabvid2-off / cabvid2-on (zoom 0.5, route W:8 at 0.5
# tiles/s, hold 2), CROP=1920:1080:1760:100 LEN=18.
#
# Usage: harness/tiledepth/stitch-fix.sh <off-label> <on-label> <out.mp4>
# Env:   PRE (default 1)  CROP (default 1920:1080:1760:100)  LEN (default 18)
set -euo pipefail
cd "$(dirname "$0")/../.."
RUN_OFF="${1:?off run label}"; RUN_ON="${2:?on run label}"; out="${3:?out.mp4}"
PRE="${PRE:-1}"; CROP="${CROP:-1920:1080:1760:100}"; LEN="${LEN:-18}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')
run() { ls -d harness/runs/$1-* | tail -1; }
# the off run's route start (recording starts ~1 s after the launch), then the on run's matching moment
starts() {
  python3 - "$1" "$2" "$PRE" <<'PY'
import subprocess, sys
import numpy as np
A, B, pre = sys.argv[1], sys.argv[2], float(sys.argv[3])
def kv(p):
    return dict(l.strip().split('=', 1) for l in open(p) if '=' in l)
def guess(d):
    return int(kv(d + '/pzopt-schedule.out')['route_start_epoch_ms']) / 1000 - int(kv(d + '/run.opts')['launch_epoch']) - 1.0
def frames(path, t0, dur):
    w, h = 256, 108
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-ss', f'{t0:.3f}', '-t', str(dur), '-i', path, '-vf', f'fps=30,scale={w}:{h},format=gray',
                          '-f', 'rawvideo', '-'], capture_output=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, h, w).astype(np.float32)
ga, gb = guess(A), guess(B)
fa = frames(A + '/recording.mp4', ga, 10)
fb = frames(B + '/recording.mp4', gb - 3, 16)
n = min(len(fa), 240)
best = min((np.abs(fa[:n] - fb[s:s + n]).mean(), s) for s in range(0, len(fb) - n))
tb = gb - 3 + best[1] / 30
print(f'{ga - pre:.3f} {tb - pre:.3f} {best[0]:.2f}')
PY
}
A=$(run "$RUN_OFF"); B=$(run "$RUN_ON")
read -r SA SB DIFF < <(starts "$A" "$B")
echo "off $A from ${SA}s, on $B from ${SB}s (mean frame difference ${DIFF}/255), ${LEN}s"
CW=${CROP%%:*}; rest=${CROP#*:}; CH=${rest%%:*}
W=$((2 * CW)); TITLE_H=120; LABEL_H=64; FOOT_H=70; Y1=$((TITLE_H + LABEL_H)); H=$((Y1 + CH + FOOT_H))
TXT=0xb4b4b8; DIM=0x8a8a90; RED=0xb85c5c; GRN=0x5cb878  # PQ code values: ~60 % = comfortable white
panel() { echo "[$1:v]fps=60,crop=${CROP},format=yuv420p10le,setsar=1"; }
filter="
color=c=0x060608:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg];
$(panel 0)[a];
$(panel 1)[b];
[bg][a]overlay=0:${Y1}:shortest=1[b1];
[b1][b]overlay=${CW}:${Y1},drawbox=x=${CW}-2:y=${TITLE_H}:w=4:h=${LABEL_H}+${CH}:color=0x202026:t=fill,
drawtext=fontfile=$FONT:text='Issue 38  \\|  floating wall cabinets with per-pixel lighting + ambient occlusion  \\|  Rosewood, noon, same walk':fontsize=52:fontcolor=$TXT:x=(w-tw)/2:y=34,
drawtext=fontfile=$FONT:text='BEFORE  -  stock shared whole-square depth box  (tileDepthFix=off)':fontsize=40:fontcolor=$RED:x=(${CW}-tw)/2:y=${TITLE_H}+10,
drawtext=fontfile=$FONT:text='AFTER  -  depth fitted to each cabinet (tileDepthFix, default)':fontsize=40:fontcolor=$GRN:x=${CW}+(${CW}-tw)/2:y=${TITLE_H}+10,
drawtext=fontfile=$FONT:text='1\\:1 crops of 5120x2160 captures  -  before\\: the upper doors lit by the floor above (a diagonal band), white and black wedges in the unlit room  -  after\\: each cabinet one surface lit by its own room':fontsize=32:fontcolor=$DIM:x=(w-tw)/2:y=h-th-20,
setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]
"
mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -v error -y \
  -ss "$SA" -t "$LEN" -i "$A/recording.mp4" \
  -ss "$SB" -t "$LEN" -i "$B/recording.mp4" \
  -filter_complex "$filter" -map '[v]' -an \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 120M -bufsize 240M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 "$out"
ffmpeg -hide_banner -v error -y -ss 6 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%%.mp4}.jpg" || true
ls -la "$out" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height,codec_name,color_transfer -of csv=p=0 "$out"
