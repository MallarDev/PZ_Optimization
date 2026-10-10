#!/usr/bin/env bash
# The low-sun shadows before / after video (2026-10-07, the maintainer's "at sunrise and sunset the shadows are too long, cut
# off, and the picture flickers between bright and dark"): two `--record` runs of one still camera over the Rosewood church
# lot with the sun swept from ~10 to ~5 deg (`--prop devSunHour=19.7 --prop devSunHourSpeed=0.02`, ~6x the game's speed so
# several shadow steps fall in the clip), the released build on top, the fix below. Each 5120x2160 capture is scaled to a
# 2560 px pane, stacked, labelled; HDR end to end (AV1 10-bit PQ / BT.2020, NVENC), plus a tone-mapped poster.
#
# Usage: harness/stitch-lowsun.sh [out.mp4]
# Env:   BEFORE AFTER   run dirs (default: the latest lsvid-before in ~/pzopt-wt/lowsun-base, lsvid-after here)
#        LEN (clip seconds, 26), LEAD (seconds before the route start, 0)
set -euo pipefail
cd "$(dirname "$0")/.."

out="${1:-docs/media/low-sun-shadows-before-after.mp4}"
BEFORE="${BEFORE:-$(ls -d "$HOME"/pzopt-wt/lowsun-base/harness/runs/lsvid-before-* | tail -1)}"
AFTER="${AFTER:-$(ls -d harness/runs/lsvid-after-* | tail -1)}"
LEN="${LEN:-26}"; LEAD="${LEAD:-0}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf

# clip start in the recording: the recorder starts ~1 s after launch_epoch (stitch-triple-hdr.sh)
start() {
  python3 - "$1" "$LEAD" <<'PY'
import sys
d, lead = sys.argv[1], float(sys.argv[2])
le = int([l for l in open(d + '/run.opts') if l.startswith('launch_epoch=')][0].split('=')[1])
rs = int([l for l in open(d + '/pzopt-schedule.out') if l.startswith('route_start_epoch_ms=')][0].split('=')[1])
print(f"{max(0.0, rs / 1000 - le - 1.0 - lead):.2f}")
PY
}

label() { # text colour y
  echo "drawtext=fontfile=$FONT:text='$1':fontsize=40:fontcolor=$2:box=1:boxcolor=black@0.65:boxborderw=12:x=24:y=$3"
}

pane() { # input-index title colour subtitle
  echo "[$1:v]scale=2560:-2:flags=lanczos,format=yuv420p10le,$(label "$2" "$3" 24),$(label "$4" white 92),setsar=1[p$1]"
}

for d in "$BEFORE" "$AFTER"; do
  [ -f "$d/recording.mp4" ] || { echo "no recording in $d" >&2; exit 1; }
done

mkdir -p "$(dirname "$out")"
ffmpeg -v error -y -ss "$(start "$BEFORE")" -t "$LEN" -i "$BEFORE/recording.mp4" -ss "$(start "$AFTER")" -t "$LEN" -i "$AFTER/recording.mp4" \
  -filter_complex \
  "$(pane 0 'Before (released)' '0xFF8A80' 'Sun shadows re-shade chunk by chunk at every sun step\; long shadows stop at 12 / 32 squares');$(pane 1 'After (fix)' '0x7CFC9A' 'Each step applied together and eased in over 1.5 s\; shadows reach their tips (40 / 96 squares)');[p0][p1]vstack=inputs=2,format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]" \
  -map "[v]" -an -pix_fmt p010le -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 24 -b:v 0 -maxrate 100M -bufsize 200M \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv -movflags +faststart+write_colr "$out"
# tone-mapped poster (posters are the only SDR derivative)
ffmpeg -v error -y -ss 12 -i "$out" -frames:v 1 -vf \
  "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuvj420p" \
  "${out%.mp4}.jpg"
echo "$out"
