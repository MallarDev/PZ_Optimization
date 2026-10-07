#!/usr/bin/env bash
# Foliage sway pulled the edges of walls / door frames into the plants behind them (2026-10-07, Workshop report: the house
# corner at 14325,4948). Two in-game devCapture sequences of the same scene (player still at 14328,4951, wind 1, zoom 1,
# a 1:1 crop of the corner, --prop devCapture=8,6,60,100,crop=2200:480:960:720): swayOccluderCheck=false | true.
# Writes the bug alone, the fix alone and the two side by side; each plays at 60 fps then the corner 3x enlarged and
# 3x slower. SDR captures mapped to PQ / BT.2020 (203 nits reference white, BT.2408), AV1 10-bit.
#
# Usage: harness/stitch-sway-edge.sh [outdir]      Env: RUN_OFF RUN_ON (run labels, default swedge-vid-off / swedge-vid-on)
set -euo pipefail
cd "$(dirname "$0")/.."

dir="${1:-docs/media}"
RUN_OFF="${RUN_OFF:-swedge-vid-off}"; RUN_ON="${RUN_ON:-swedge-vid-on}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
run() { ls -d harness/runs/$1-* | tail -1; }
size() { head -c 40 "$1/capture/index.txt" | tr ' \n' '  ' | sed -E 's/.*w=([0-9]+) h=([0-9]+).*/\1x\2/'; }

off=$(run "$RUN_OFF"); on=$(run "$RUN_ON")
s=$(size "$off"); [ "$s" = "$(size "$on")" ] || { echo "capture sizes differ" >&2; exit 1; }
SDR2PQ="setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le"
lab() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=26:fontcolor=$2:box=1:boxcolor=black@0.6:boxborderw=8:x=14:y=14"; }
# the corner (x 220..540, y 150..390 of the 960x720 capture) enlarged 3x, 3x slower, after the full view
ZOOM="crop=320:240:220:150,scale=960:720:flags=neighbor"
ENC=(-pix_fmt p010le -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv)
OFF_LAB=$(lab 'Before - foliage sway pulls the wall corner into the grass' 0xFF8A80)
ON_LAB=$(lab 'After - swayOccluderCheck (the fix, default on)' 0x7CFC9A)

one() { # $1 run dir, $2 label filter, $3 out
  ffmpeg -v error -y -f rawvideo -pixel_format rgba -video_size "$s" -framerate 60 -i "$1/capture/frames.rgba" \
    -filter_complex "[0:v]vflip,format=rgb24,split[a][b];[a]$2[full];[b]trim=start=1:end=3,setpts=3*(PTS-STARTPTS),$ZOOM,$2[zoom];[full][zoom]concat=n=2:v=1,format=yuv420p,$SDR2PQ,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]" \
    -map "[v]" "${ENC[@]}" "$3"
}
mkdir -p "$dir"
one "$off" "$OFF_LAB" "$dir/foliage-sway-wall-edge-before.mp4"
one "$on" "$ON_LAB" "$dir/foliage-sway-wall-edge-after.mp4"
ffmpeg -v error -y \
  -f rawvideo -pixel_format rgba -video_size "$s" -framerate 60 -i "$off/capture/frames.rgba" \
  -f rawvideo -pixel_format rgba -video_size "$s" -framerate 60 -i "$on/capture/frames.rgba" \
  -filter_complex "[0:v]vflip,format=rgb24,split[a0][a1];[1:v]vflip,format=rgb24,split[b0][b1];[a0]$OFF_LAB[af];[b0]$ON_LAB[bf];[a1]trim=start=1:end=3,setpts=3*(PTS-STARTPTS),$ZOOM,$OFF_LAB[az];[b1]trim=start=1:end=3,setpts=3*(PTS-STARTPTS),$ZOOM,$ON_LAB[bz];[af][bf]hstack=inputs=2[full];[az][bz]hstack=inputs=2[zoom];[full][zoom]concat=n=2:v=1,format=yuv420p,$SDR2PQ,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]" \
  -map "[v]" "${ENC[@]}" "$dir/foliage-sway-wall-edge-before-after.mp4"
for f in before after before-after; do
  ffmpeg -v error -y -ss 2 -i "$dir/foliage-sway-wall-edge-$f.mp4" -frames:v 1 -vf \
    "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuvj420p" \
    "$dir/foliage-sway-wall-edge-$f.jpg"
done
ls -la "$dir"/foliage-sway-wall-edge-*
