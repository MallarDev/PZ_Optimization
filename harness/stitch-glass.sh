#!/usr/bin/env bash
# Glass before / after video (2026-10-01, the maintainer's bus-shelter report: characters behind glass not shown). Two
# segments, each two --record runs side by side, 2x crops of the 5120x2160 capture around the player, from their route
# starts (static scenes: the player held, the zombies pinned):
#   1. the bus shelter (Riverside 6209-6213,5290-5292; its panes are windows), runs gv-shelter-before / -after:
#      --flag start=6207,5288 --flag face=45 --flag pin_zombies=6208,5290/6209,5289/6211,5289
#   2. a glass balustrade placed on the bench save copy (location_shop_mall_01_24, a Translucent glass tile), runs
#      gv-rail-before / -after: --flag start=6206,5287 --flag face=180 --flag place_tile=...@6203,5285..5288
#      --flag pin_zombies=6202,5285/6202,5286/6202,5288
#   both with --mode bench --flag zoom=1 --flag time_of_day=14 --flag weather=clear --flag route=S:1 --flag speed=0.05
#   --route-seconds 14 --record --prop overlay=false; before = --prop windowsInChunkTexture=true --prop glassTilesPerFrame=false
#   (the released defaults), after = the new defaults.
# AV1 10-bit PQ / BT.2020 end to end; the poster .jpg is the only tone-mapped copy.
#
# Usage: harness/stitch-glass.sh <out.mp4>
# Env:   LEN (seconds per segment, default 8)  PRE (seconds after the route start, default 1)
#        CROP1 / CROP2 (w:h:x:y of the shelter / balustrade crops in the 5120x2160 capture, default 960:540:2150:870 /
#        960:540:1920:760)  ZOOM (nearest-neighbour scale of the crops, default 2)
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:?out.mp4}"
LEN="${LEN:-8}"; PRE="${PRE:-1}"
CROP1="${CROP1:-960:540:2150:870}"; CROP2="${CROP2:-960:540:1920:760}"; ZOOM="${ZOOM:-2}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')
run() { ls -d harness/runs/$1-* | tail -1; }
# seconds into the recording (it starts ~1 s after the launch) of the route start, plus PRE
start() {
  python3 - "$1" "$PRE" <<'PY'
import sys
d, pre = sys.argv[1], float(sys.argv[2])
kv = lambda p: dict(l.strip().split('=', 1) for l in open(p) if '=' in l)
print(f"{int(kv(d + '/pzopt-schedule.out')['route_start_epoch_ms']) / 1000 - int(kv(d + '/run.opts')['launch_epoch']) - 1.0 + pre:.3f}")
PY
}
A1=$(run gv-shelter-before); B1=$(run gv-shelter-after); A2=$(run gv-rail-before); B2=$(run gv-rail-after)
SA1=$(start "$A1"); SB1=$(start "$B1"); SA2=$(start "$A2"); SB2=$(start "$B2")
echo "shelter: before $A1 from ${SA1}s, after $B1 from ${SB1}s; balustrade: before $A2 from ${SA2}s, after $B2 from ${SB2}s; ${LEN}s each"
CW=$((${CROP1%%:*} * ZOOM)); rest=${CROP1#*:}; CH=$((${rest%%:*} * ZOOM))
W=$((2 * CW)); TITLE_H=120; LABEL_H=64; FOOT_H=70; Y1=$((TITLE_H + LABEL_H)); H=$((Y1 + CH + FOOT_H))
TXT=0xb4b4b8; DIM=0x8a8a90; RED=0xb85c5c; GRN=0x5cb878  # PQ code values: ~60 % = comfortable white
panel() { echo "[$1:v]fps=60,crop=$2,scale=iw*${ZOOM}:ih*${ZOOM}:flags=neighbor,format=yuv420p10le,setsar=1"; }
segment() {  # $1 $2 input indices, $3 crop, $4 title, $5 before label, $6 after label, $7 footer, $8 output pad
  cat <<EOF
color=c=0x060608:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg$8];
$(panel "$1" "$3")[a$8];
$(panel "$2" "$3")[b$8];
[bg$8][a$8]overlay=0:${Y1}:shortest=1[o$8];
[o$8][b$8]overlay=${CW}:${Y1}:shortest=1,drawbox=x=${CW}-2:y=${TITLE_H}:w=4:h=${LABEL_H}+${CH}:color=0x202026:t=fill,
drawtext=fontfile=$FONT:text='$4':fontsize=52:fontcolor=$TXT:x=(w-tw)/2:y=34,
drawtext=fontfile=$FONT:text='$5':fontsize=40:fontcolor=$RED:x=(${CW}-tw)/2:y=${TITLE_H}+10,
drawtext=fontfile=$FONT:text='$6':fontsize=40:fontcolor=$GRN:x=${CW}+(${CW}-tw)/2:y=${TITLE_H}+10,
drawtext=fontfile=$FONT:text='$7':fontsize=32:fontcolor=$DIM:x=(w-tw)/2:y=h-th-20[$8];
EOF
}
filter="
$(segment 0 1 "$CROP1" 'Characters behind glass  \|  Riverside bus shelter (its panes are windows)  \|  same spot, three zombies behind the glass' \
  'BEFORE  -  windows baked into the chunk textures' 'AFTER  -  windows drawn every frame, as in the stock game' \
  '2x crops (nearest) of 5120x2160 captures  -  before\: the baked pane writes its depth and hides the zombie behind it  -  after\: the glass blends over it  -  the player behind the opaque top strip is hidden in stock too' s1)
$(segment 2 3 "$CROP2" 'Characters behind glass  \|  glass balustrade (a Translucent glass tile)  \|  same spot, three zombies behind the glass' \
  'BEFORE  -  glass tiles baked into the chunk textures' 'AFTER  -  glass tiles drawn every frame (glassTilesPerFrame)' \
  '2x crops (nearest) of 5120x2160 captures  -  before\: the zombies are cut off at the rail  -  after\: their legs show through the glass, as in the stock game' s2)
[s1][s2]concat=n=2:v=1:a=0,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]
"
if [ -n "${DRY:-}" ]; then  # DRY=1: parse the filter graph against generated inputs, write nothing
  src=(); for i in 0 1 2 3; do src+=(-f lavfi -t 0.2 -i "color=c=gray:s=5120x2160:r=60"); done
  ffmpeg -hide_banner -v error -y "${src[@]}" -filter_complex "${filter//d=${LEN}/d=0.2}" -map '[v]' -f null - && echo "filter OK"
  exit
fi
mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -v error -y \
  -ss "$SA1" -t "$LEN" -i "$A1/recording.mp4" -ss "$SB1" -t "$LEN" -i "$B1/recording.mp4" \
  -ss "$SA2" -t "$LEN" -i "$A2/recording.mp4" -ss "$SB2" -t "$LEN" -i "$B2/recording.mp4" \
  -filter_complex "$filter" -map '[v]' -an \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 120M -bufsize 240M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 "$out"
for t in 4 $((LEN + 4)); do
  ffmpeg -hide_banner -v error -y -ss "$t" -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%%.mp4}-${t}s.jpg" || true
done
cp -f "${out%%.mp4}-4s.jpg" "${out%%.mp4}.jpg" || true
ls -la "$out" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height,codec_name,color_transfer -of csv=p=0 "$out"
