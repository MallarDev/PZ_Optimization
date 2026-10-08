#!/usr/bin/env bash
# Reflective props (2026-10-08, mirrorsProps): the stock look (reflections off) | reflections on, from in-game devCapture
# sequences of the flip (--prop devCapture=6,9,30,50,ram: the whole frame at 50 %, 960x540, 30 fps), four scenes of two runs
# each (labels pv-<scene>-false / pv-<scene>-true, same route, same seed):
#   show     the showroom row (glass counters, glass table, TV, steel counter, gym mirror, glass-door fridge, glass door,
#            toilet) walked along, the sheriff car parked in front
#   kitchen  a real kitchen near the bench save (steel counters and sink)
#   store    a real store front (glass panes and doors)
#   tables   low glass tables round the player (the tops reflect the sky outdoors)
# Each scene: both side by side in real time, then its first 4 s as a 2x close-up where the props stand (ZOOM).
# Caption text avoids ':' and ';' (drawtext option separators). SDR captures mapped to PQ / BT.2020 (203 nits reference
# white), AV1 10-bit; poster .jpg tone-mapped.
#
# Usage: harness/stitch-props.sh [out.mp4]      (env SCENES, default "show kitchen store tables"; CQ)
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-docs/media/prop-reflections-stock-vs-on.mp4}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
run() { ls -d harness/runs/*$1-2* | tail -1; }
SDR2PQ="setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le"
lab() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=30:fontcolor=$2:box=1:boxcolor=black@0.6:boxborderw=8:x=(w-tw)/2:y=40"; }
cap() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=32:fontcolor=white:box=1:boxcolor=black@0.6:boxborderw=8:x=(w-tw)/2:y=h-th-40"; }
declare -A CAPTION=(
  [show]='Glass counters, glass table, TV, steel counter, gym mirror, glass-door fridge, glass door - the sheriff car mirrored in them'
  [kitchen]='A kitchen - brushed steel counters and sink, blurred and tinted, fading where the player cannot see'
  [store]='A store front - the glass panes and doors reflect the pavement'
  [tables]='Glass tables outdoors - the tops reflect the sky and whoever stands right behind them'
)
# the close-up's top-left corner in the 960x540 capture (a 480x270 crop, scaled 2x): where the props stand in each scene
declare -A ZOOM=([show]="470:120" [kitchen]="240:110" [store]="60:120" [tables]="240:150")
inputs=(); filters=""; segs=""; i=0; k=0
for sc in ${SCENES:-show kitchen store tables}; do
  ro=$(run "pv-$sc-false"); rn=$(run "pv-$sc-true")
  read -r W H _ < <(head -1 "$rn/capture/index.txt" | tr -c '0-9\n' ' ')
  n=$(( $(stat -c %s "$rn/capture/frames.rgba") / (W * H * 4) )); no=$(( $(stat -c %s "$ro/capture/frames.rgba") / (W * H * 4) ))
  (( no < n )) && n=$no
  inputs+=(-f rawvideo -pixel_format rgba -video_size ${W}x$H -framerate 30 -i "$ro/capture/frames.rgba")
  inputs+=(-f rawvideo -pixel_format rgba -video_size ${W}x$H -framerate 30 -i "$rn/capture/frames.rgba")
  zw=$((W / 2)); zh=$((H / 2)); zf=$((n < 120 ? n : 120)); zx=${ZOOM[$sc]%%:*}; zy=${ZOOM[$sc]##*:}
  filters+="[$i:v]vflip,format=rgb24,trim=end_frame=$n,setpts=PTS-STARTPTS,split[o$k][oz$k];"
  filters+="[$((i+1)):v]vflip,format=rgb24,trim=end_frame=$n,setpts=PTS-STARTPTS,split[n$k][nz$k];"
  filters+="[o$k]scale=1920:-2:flags=lanczos,$(lab 'Stock look (reflections off)' 0xFFB0A0)[ol$k];[n$k]scale=1920:-2:flags=lanczos,$(lab 'Prop reflections on' 0x7CFC9A)[nl$k];"
  filters+="[ol$k][nl$k]hstack=inputs=2,$(cap "${CAPTION[$sc]}"),fps=60,setsar=1[s$k];"
  filters+="[oz$k]trim=end_frame=$zf,crop=$zw:$zh:$zx:$zy,scale=1920:-2:flags=lanczos,$(lab 'Stock look - close up 2x' 0xFFB0A0)[ozl$k];"
  filters+="[nz$k]trim=end_frame=$zf,crop=$zw:$zh:$zx:$zy,scale=1920:-2:flags=lanczos,$(lab 'Reflections on - close up 2x' 0x7CFC9A)[nzl$k];"
  filters+="[ozl$k][nzl$k]hstack=inputs=2,fps=60,setsar=1[z$k];"
  segs+="[s$k][z$k]"
  i=$((i + 2)); k=$((k + 1))
done
mkdir -p "$(dirname "$out")"
ffmpeg -v error -y "${inputs[@]}" -filter_complex "${filters}${segs}concat=n=$((k * 2)):v=1,format=yuv420p,$SDR2PQ,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]" \
  -map "[v]" -pix_fmt p010le -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq ${CQ:-26} -b:v 0 \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv "$out"
ffmpeg -v error -y -ss 4 -i "$out" -frames:v 1 -vf \
  "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuvj420p" \
  "${out%.mp4}.jpg"
echo "$out"
