#!/usr/bin/env bash
# Stitch three recorded circle walks on the maintainer's Riverside Fossoil save (2026-09-29) side by side, to confirm
# the gas-canopy fix (translucentLightsPerFrame, 8d68485: the lit tubes of the canopy no longer show through its roof):
# fix on | fix off (--prop translucentLightsPerFrame=false) | stock (--prop enabled=false). Each 5120x2160 recording is
# cropped to the pump canopy and the store (CROP, screen pixels) and scaled to a 1280 px wide pane; HDR end to end
# (the gpu-screen-recorder captures are AV1 10-bit PQ / BT.2020, the output is NVENC AV1 10-bit with the same tags).
#
# Usage: harness/stitch-roof-lights.sh [out.mp4]
# Env:   RUN_FIX RUN_NOFIX RUN_STOCK   run labels (latest run dir of each; default roofvid-fix / -nofix / -stock)
#        LEN (clip seconds, 18), CROP (w:h:x:y, 2500:1760:1300:0), LEAD (seconds before the route start, 1)
set -euo pipefail
cd "$(dirname "$0")/.."

out="${1:-docs/media/canopy-roof-lights-fix-vs-off-vs-stock.mp4}"
RUN_FIX="${RUN_FIX:-roofvid-fix}"; RUN_NOFIX="${RUN_NOFIX:-roofvid-nofix}"; RUN_STOCK="${RUN_STOCK:-roofvid-stock}"
LEN="${LEN:-18}"; CROP="${CROP:-2500:1760:1300:0}"; LEAD="${LEAD:-1}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf

run() { ls -d harness/runs/$1-* | tail -1; }
# clip start in the recording: the recorder starts ~1 s after launch_epoch (stitch-triple-hdr.sh)
start() {
  python3 - "$(run "$1")" "$LEAD" <<'PY'
import sys
d, lead = sys.argv[1], float(sys.argv[2])
le = int([l for l in open(d + '/run.opts') if l.startswith('launch_epoch=')][0].split('=')[1])
rs = int([l for l in open(d + '/pzopt-schedule.out') if l.startswith('route_start_epoch_ms=')][0].split('=')[1])
print(f"{max(0.0, rs / 1000 - le - 1.0 - lead):.2f}")
PY
}

label() { # text colour
  echo "drawtext=fontfile=$FONT:text='$1':fontsize=34:fontcolor=$2:box=1:boxcolor=black@0.6:boxborderw=10:x=18:y=18"
}

pane() { # input-index label colour
  echo "[$1:v]crop=$CROP,scale=1280:-2:flags=lanczos,format=yuv420p10le,$(label "$2" "$3"),setsar=1[p$1]"
}

inputs=()
for r in "$RUN_FIX" "$RUN_NOFIX" "$RUN_STOCK"; do
  d=$(run "$r")
  [ -f "$d/recording.mp4" ] || { echo "no recording in $d" >&2; exit 1; }
  inputs+=(-ss "$(start "$r")" -t "$LEN" -i "$d/recording.mp4")
done

mkdir -p "$(dirname "$out")"
ffmpeg -v error -y "${inputs[@]}" -filter_complex \
  "$(pane 0 'Fix on (master\, translucentLightsPerFrame)' '0x7CFC9A');$(pane 1 'Fix off (translucentLightsPerFrame=false)' '0xFF8A80');$(pane 2 'Stock (enabled=false)' 'white');[p0][p1][p2]hstack=inputs=3,format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]" \
  -map "[v]" -an -pix_fmt p010le -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 24 -b:v 0 -maxrate 100M -bufsize 200M \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv "$out"
# tone-mapped poster for a quick look (posters are the only SDR derivative)
ffmpeg -v error -y -ss 6 -i "$out" -frames:v 1 -vf \
  "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuvj420p" \
  "${out%.mp4}.jpg"
echo "$out"
