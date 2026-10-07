#!/usr/bin/env bash
# The Louisville checkpoint tent roofs with ambient occlusion + sun shadows (2026-10-07, Discord 2026-10-06): before (the
# committed build) | fix (aoRoofSkip reaches the sun program, the tents' WestRoof tiles count as roofs), from in-game devCapture
# sequences (--prop devCapture=8,9,30,40,ram: the whole frame at 40 %, 2048x864, 30 fps) of the same walk west between the
# tent rows at 15:00 (cloud shadows off):
#   1. both walks side by side, real time
#   2. one tent roof close up, 3x nearest, a still from the middle of the walk
#   3. god rays at 09:00 from inside a tent (--shot-at stills, --prop godRays=true): before (the tents' roof tiles not roofs to the
#      god-ray volume: the morning sun passed through their roofs into the haze) | fix (the tents shade the haze)
# Caption text avoids ':' and ';' (drawtext option separators). SDR captures mapped to PQ / BT.2020 (203 nits reference white),
# AV1 10-bit; poster .jpg tone-mapped.
#
# Usage: harness/stitch-tent-roofs.sh [out.mp4]
#   Env: BEFORE AFTER (run labels; defaults tvid-before, tvid-after), FRAME (close-up frame, 135), CROP (w:h:x:y, 682:288:259:381),
#        GR_BEFORE GR_AFTER (god-ray still runs; defaults gi9-before, gi9-after), GR_CROP (w:h:x:y, 1800:1000:1500:500)
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-docs/media/tent-roofs-ao-before-vs-fix.mp4}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
run() { ls -d harness/runs/$1-* | tail -1; }
rb=$(run "${BEFORE:-tvid-before}"); ra=$(run "${AFTER:-tvid-after}")
gb=$(run "${GR_BEFORE:-gi9-before}"); ga=$(run "${GR_AFTER:-gi9-after}")
GR_CROP=${GR_CROP:-1800:1000:1500:500}
S=2048x864
FRAME=${FRAME:-135}
CROP=${CROP:-682:288:259:381}
SDR2PQ="setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le"
lab() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=34:fontcolor=$2:box=1:boxcolor=black@0.6:boxborderw=8:x=(w-tw)/2:y=60"; }
cap() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=30:fontcolor=white:box=1:boxcolor=black@0.6:boxborderw=6:x=(w-tw)/2:y=h-th-60"; }
n=$(( $(stat -c %s "$ra/capture/frames.rgba") / (2048 * 864 * 4) ))
nb=$(( $(stat -c %s "$rb/capture/frames.rgba") / (2048 * 864 * 4) ))
(( nb < n )) && n=$nb

mkdir -p "$(dirname "$out")"
ffmpeg -v error -y \
  -f rawvideo -pixel_format rgba -video_size $S -framerate 30 -i "$rb/capture/frames.rgba" \
  -f rawvideo -pixel_format rgba -video_size $S -framerate 30 -i "$ra/capture/frames.rgba" \
  -loop 1 -framerate 60 -t 5 -i "$gb/shot-game.png" -loop 1 -framerate 60 -t 5 -i "$ga/shot-game.png" \
  -filter_complex "\
[0:v]vflip,format=rgb24,trim=end_frame=$n,setpts=PTS-STARTPTS,split[b1][b2];\
[1:v]vflip,format=rgb24,trim=end_frame=$n,setpts=PTS-STARTPTS,split[a1][a2];\
[b1]$(lab 'Before (released build)' 0xFF8A80)[bl];[a1]$(lab 'Fix' 0x7CFC9A)[al];\
[bl][al]hstack=inputs=2,$(cap 'Louisville checkpoint tents, ambient occlusion + sun shadows on, 15h00, walking west between the rows'),fps=60[s1];\
[b2]select=eq(n\\,$FRAME),crop=$CROP,scale=iw*3:ih*3:flags=neighbor,$(lab 'Before - close up 3x' 0xFF8A80),loop=loop=239:size=1:start=0,setpts=N/60/TB[bc];\
[a2]select=eq(n\\,$FRAME),crop=$CROP,scale=iw*3:ih*3:flags=neighbor,$(lab 'Fix - close up 3x' 0x7CFC9A),loop=loop=239:size=1:start=0,setpts=N/60/TB[ac];\
[bc][ac]hstack=inputs=2,scale=4096:-2,pad=4096:864:0:(oh-ih)/2,$(cap 'The roof skip of the AO kernel never reached its sun-shadow program, and the tents roof tiles were not roofs to it'),fps=60[s2];\
[2:v]format=rgb24,crop=$GR_CROP,scale=-2:864,$(lab 'God rays before' 0xFF8A80)[gbl];[3:v]format=rgb24,crop=$GR_CROP,scale=-2:864,$(lab 'God rays fix' 0x7CFC9A)[gal];\
[gbl][gal]hstack=inputs=2,pad=4096:864:(ow-iw)/2:0,$(cap 'God rays at 9h00 - the morning sun passed through the tent roofs into the haze, now the tents shade it'),fps=60[s3];\
[s1][s2][s3]concat=n=3:v=1,format=yuv420p,$SDR2PQ,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]" \
  -map "[v]" -pix_fmt p010le -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq ${CQ:-28} -b:v 0 \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv "$out"
ffmpeg -v error -y -ss 4 -i "$out" -frames:v 1 -vf \
  "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuvj420p" \
  "${out%.mp4}.jpg"
echo "$out"
