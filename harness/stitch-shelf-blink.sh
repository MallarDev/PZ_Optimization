#!/usr/bin/env bash
# The Fossoil shelf blink (2026-09-29) side by side from two in-game devCapture sequences of the same circle walk (a 1:1
# colour crop of the counter, --prop devCapture=6,3,240,100,crop=3000:1150:900:650): fix off (pplJiggle=false) | fix on.
# Every presented frame (~155 fps) plays at 60 fps, ~2.6x slowed down, so the one-frame black blinks are visible; then the same seconds
# again frame by frame at 12 fps. SDR captures mapped to PQ / BT.2020 (203 nits reference white, BT.2408), AV1 10-bit.
#
# Usage: harness/stitch-shelf-blink.sh [out.mp4]      Env: RUN_OFF RUN_ON (run labels, default shelfvid-off / shelfvid-on)
set -euo pipefail
cd "$(dirname "$0")/.."

out="${1:-docs/media/fossoil-shelf-blink-fix-off-vs-on.mp4}"
RUN_OFF="${RUN_OFF:-shelfall-off}"; RUN_ON="${RUN_ON:-shelfall-on}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
run() { ls -d harness/runs/$1-* | tail -1; }
size() { head -c 40 "$1/capture/index.txt" | tr ' \n' '  ' | sed -E 's/.*w=([0-9]+) h=([0-9]+).*/\1x\2/'; }

off=$(run "$RUN_OFF"); on=$(run "$RUN_ON")
s=$(size "$off"); [ "$s" = "$(size "$on")" ] || { echo "capture sizes differ" >&2; exit 1; }
SDR2PQ="setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le"
lab() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=30:fontcolor=$2:box=1:boxcolor=black@0.6:boxborderw=8:x=14:y=14"; }

mkdir -p "$(dirname "$out")"
ffmpeg -v error -y \
  -f rawvideo -pixel_format rgba -video_size "$s" -framerate 60 -i "$off/capture/frames.rgba" \
  -f rawvideo -pixel_format rgba -video_size "$s" -framerate 60 -i "$on/capture/frames.rgba" \
  -filter_complex "[0:v]vflip,format=rgb24,$(lab 'Fix off (pplSeenEdge, pplJiggle, pplDepthOpaqueOnly off)' 0xFF8A80)[a];[1:v]vflip,format=rgb24,$(lab 'Fix on (all three, defaults)' 0x7CFC9A)[b];[a][b]hstack=inputs=2,drawtext=fontfile=$FONT:text='every presented frame (~155 fps) played at 60 fps, then 5x slower':fontsize=24:fontcolor=white:box=1:boxcolor=black@0.6:boxborderw=6:x=(w-tw)/2:y=h-th-14,split[s1][s2];[s2]trim=start=0.5:end=1.5,setpts=5*(PTS-STARTPTS)[slow];[s1][slow]concat=n=2:v=1,format=yuv420p,$SDR2PQ,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]" \
  -map "[v]" -pix_fmt p010le -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv "$out"
ffmpeg -v error -y -ss 2 -i "$out" -frames:v 1 -vf \
  "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuvj420p" \
  "${out%.mp4}.jpg"
echo "$out"
