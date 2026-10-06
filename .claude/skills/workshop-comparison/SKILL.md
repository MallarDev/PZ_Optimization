---
name: workshop-comparison
description: Make the Workshop page's stock-vs-enhanced comparison animations (the 630x630 AVIFs of the Features section, the headline's template) from laptop or desktop runs - capture and numbers runs, the side-by-side video, the animation, publishing on the page. Use when asked for a new comparison video / GIF / AVIF for the Workshop description, to restyle the existing ones, or to swap the scene behind one.
---

# Workshop comparison animations

What ships (2026-10-06, maintainer's choices): `docs/workshop/images/60-feature-handheld-efficiency.avif`,
`61-feature-macos-opengl41.avif`, `62-feature-low-end-hw-mode.avif` in the page's **Features** section, plus the headline
(`00-headline-*.avif`, the same template in `--single` mode). Pipeline:

```
runs (per side: a clean numbers run + a devCapture run)  ->  harness/stitch-efficiency.py <kind>   -> workshop-media/<name>.mp4 (3840x1800 AV1 HDR, local only)
                                                          ->  harness/efficiency-anim.py --size 630 -> workshop-media/<name>.avif  -> docs/workshop/images/NN-*.avif
```
`workshop-media/` holds the masters (mp4, posters, AVIFs, review GIFs); it is not committed (100+ MB videos). Every
encode / stitch / render is a queue `media` job (`run-queue` skill), never beside a run.

## 1. The runs

Two runs per side and scene, same arguments except the capture:
- **numbers run** (no capture): the fps / watts / energy on the slide come from it (`pzopt-overlay.out`,
  `pzopt-power.out`, route window). Add `--schedmon 0.25` on laptops.
- **capture run** (label = numbers label + `-cap`): `--prop overlay=false --prop devCapture=<start>,<secs>,60,50[,ram]`
  (`pzopt.FrameCapture`: the game's own frames, half size; laptops have no screen recorder that works). Reading frames
  back slows the game and raises its power, which is why the numbers come from the other run.

Stock side = `--prop enabled=false` on the installed build (the harness needs the overrides; an uninstalled dir never
presses click-to-start). Enhanced side = the defaults or the setting being shown.

Recipes that produced the published ones (copy the queue job's argv: `~/.local/state/pzopt-queue/jobs/<id>-<label>/argv`):
- **Flip, storm at a 120 fps cap** (`flipstorm`): `--preset storm --prop instrument=true --no-dashboard --option frameRate=120
  --option uncappedFPS=false --vmarg -Dpzopt.userOptionsFile=/home/diego/pzopt-defaults.ini` (empty file: the flip's tab file has
  every Enhancement on), enhanced `--prop enabled=true --prop corePlacement=efficient --prop gpuPstate=off`, capture
  `devCapture=0,70,60,50,ram`. Labels `flipw120-storm-{stock,eff}[-cap]`.
- **Mac, the maintainer's pond** (`macpond`): `--mode bench --template Sandbox/pzopt-template-pond --flag start=8174,11692
  --flag explore=circle --flag director=jev --flag circle_center=8179,11692 --flag circle_radius=5 --flag circle_lead=20
  --flag circle_spots=0 --flag circle_laps=1 --flag zombies=off --flag zoom=1 --flag route=S:1 --flag speed=0.02 --quit-after 70
  --prop instrument=true --prop hdr=false --prop overlay=false`, enhanced `--prop macGlCore=true` (the Mac's own tab file
  left on), stock `--prop enabled=false --prop macGlCore=false --quit-after 110`, capture `devCapture=4,30,60,50` (stock
  `4,45,60,50`). Start one `python3 harness/explore-director.py --machine mac --wait 1800` in the background before each job
  (Jev walks the lap; one director per run). The local wrappers that did this are `build/showcase/pond-runs.sh` /
  `pond-stock-runs.sh` (gitignored). As of 2026-10-06 `circle_center` / `circle_lead` (`pzopt.CircleWalk`) and
  `run-mac.sh --template` are uncommitted changes in the main checkout (pz-optimization-86's): check they are in the build
  before relying on them.
- **Dell, low-end mode** (`dell`): drive-120; stock `--prop enabled=false --option lightFPS=15 --option uiRenderFPS=60
  --option textureCompression=false`, enhanced = empty options file + the "Low-end hardware" preset keys + `dynRes=true
  dynResUpscaler=taau dynResFps=60`, capture `devCapture=0,100,30,50` (no `ram`: 8 GB RAM).

Traps (each cost a run on 2026-10-06):
- `--quit-after` counts from **launch**; the stock game boots slower, so give stock runs ~40 s more (the first Mac stock run
  quit 17 s into the route). Check `route done` in the console of every run.
- `enabled=false` does **not** turn off `macGlCore` (it applies at window creation): a Mac stock run needs `--prop
  macGlCore=false` too, else it runs on the 4.1 context. Check the console's `OpenGL version`.
- `devCapture`'s start counts from the first world frame, and stock's world comes up earlier relative to the harness
  world-ready: make stock captures longer (45 s instead of 30 for the pond).
- Jev's verdict on a lone stock run is often `invalid` at low confidence: the queue's generic goal wants the cap or a
  saturated machine. Not a broken run; check route completion and the console instead.
- Flip: `gpuPstate=auto|min_sclk` costs 2-3 W on the drive; the efficient setup is `corePlacement=efficient gpuPstate=off`.
  Watts per scene at the 60 cap (runs `flipw-*`): storm 21 W both sides, night torch 19.4 -> 16.7, spin 19.8 -> 18.3.
- Several machines at once: give the second machine's jobs their own queue session (`PZQ_SESSION=$CLAUDE_CODE_SESSION_ID-flip`,
  `--name` on its first submit) so a running script bound to another machine keeps its binding.
- Dell: a 2.7 GB capture copy-back drops its Wi-Fi (exit 70); rsync the run dir by hand with `--bwlimit`.

## 2. The side-by-side video

Add a `CFG['<kind>']` entry in `harness/stitch-efficiency.py`: `out='workshop-media/<name>.mp4'`, `a` / `b` = the numbers
runs' labels with the machine prefix (`flip-flipw120-storm-stock`; the capture is `a + '-cap'` unless `a_cap` / `b_cap`),
title, pane labels `la` / `lb`, `sub_a` / `sub_b`, `watts`, footer, `card_title` (+ `card_fixed=True` to keep it when watts
rise), `card_note`. `stock_a=True` when the left side is the stock game (the Mac card's Enhancements row). Then:
```bash
python3 harness/stitch-efficiency.py <kind> --numbers     # route means + pane geometry as JSON: check them first
harness/queue.sh submit media --wait --label <kind>-stitch --out workshop-media/<name>.mp4 --intent ... --progress ... -- python3 harness/stitch-efficiency.py <kind>
```
Check two frames (one in the route, one on the results card) tone-mapped: `ffmpeg -ss T -i <mp4> -frames:v 1 -vf
'zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=rgb24' x.png`.

## 3. The animation (the template)

Add the video to `VIDEOS` in `harness/efficiency-anim.py`: `name: (t0, strip label, kind, second line)`, second line
`'watts'`, `'jpf'` (energy per frame), `('text before', 'text after')` or `None`.

The template (maintainer, 2026-10-06; do not change without asking): 630x630 like the headline, light theme, a black strip
on the left 80 px wide with the label in Martian Mono 42 px, rotated, reading from the top, top-aligned at the numbers'
padding; one view of the scene with **stock left of a white divider and enhanced right of it**; the divider moves as on the
New! cards (hold enhanced 1.2 s, cosine sweep to stock 2 s, hold 1.2 s, sweep back 2 s, hold enhanced 1.5 s more; one 7.9 s
loop) and fades out over the last 12 % of the width at each edge; each side's numbers are wiped with its picture (stock grey
bottom left, enhanced white bottom right; fps big, the second line under it); the captions are always **STOCK** and
**ENHANCED**, never before / after. AVIF, 60 fps, CRF 35, ~0.5-1 MB.

Strip labels in use: "Handheld Efficiency", "MacOS OpenGL 4.1", "Low End HW Mode".

```bash
python3 harness/efficiency-anim.py --size 630 --still /tmp/x.png <name>                # layout check, one frame (no queue needed)
harness/queue.sh submit media --wait --label <name>-tpl -- python3 harness/efficiency-anim.py --size 630 --gif <name>   # review GIF (12-23 MB)
harness/queue.sh submit media --wait --label <name>-avif --out workshop-media/<name>.avif -- python3 harness/efficiency-anim.py --size 630 <name>
```
Read the result back with Pillow (`Image.open(avif).seek(k)`): ffmpeg reads an animated AVIF's still primary item only.
`--single <mp4> --t0 S --crop x:y:w [--numbers A:B --second "x:y"] [--header --align left] --out f` is the one-view mode
(the headline: `--single "$HOME/Videos/Project Zomboid/Video_2026-10-06_16-23-03.mp4" --t0 23.3 --crop 1750:464:1620 --len 10
--label PZ_Optimization --numbers 156:512 --second "p99 16.5 ms:p99 5.8 ms"`); `--font` another TTF.

**Pick the window (t0).** Both panes must show the same view (Jev-directed walks drift apart late in the route) and the
enhanced picture must not stutter or show unloaded chunks. A laptop capture runs at ~17-21 fps and freezes where the game
hitched; score every 7.9 s window by the enhanced capture's gaps and the black 16 px blocks of both panes:
```python
import numpy as np
def kv(p): return dict(l.strip().split('=',1) for l in open(p) if '=' in l)
d='harness/runs/<enhanced cap run>'; shift=+0.099          # the stitch prints "b shifted ... s"
r0=int(kv(d+'/pzopt-schedule.out')['route_start_epoch_ms'])
st=np.array([int(x) for x in open(d+'/capture/index.txt').read().split('\n')[1:] if x.strip()]); st=st[st>10**12]
B=(st-r0)/1000.0-shift                                     # video seconds (the stitch starts at route +0)
for v0 in np.arange(0, B[-1]-7.9, 0.25):
    g=np.diff(B[(B>=v0)&(B<=v0+7.9)])*1000; print(v0, int(g.max()), (g>100).sum())
```
plus black blocks from a 4 fps decode of the mp4's panes (`crop=3840:1072:0:150`, `max(rgb) < 8` per block). The Dell's
26 s window had an 890 ms freeze; 2.0-9.9 s has 107 ms at most and no black chunks.

## 4. On the Workshop page

1. `cp workshop-media/<name>.avif docs/workshop/images/NN-<slug>.avif` (next free number; a new name, Steam and browsers cache
   the old one) and put `[img]https://raw.githubusercontent.com/xD3I/PZ_Optimization/master/docs/workshop/images/NN-<slug>.avif[/img]`
   in `docs/workshop/description.txt` (page limit ~7,900 characters after the short links).
2. Commit only those files (+ the scripts if changed), push master. raw.githubusercontent serves `image/avif` with
   `Access-Control-Allow-Origin: *`; check with a HEAD request before going on.
3. Stage: `scripts/workshop.sh --tag <current release tag> --out /tmp/ws` (or `--zip build/workshop/<tag>/pzopt-*-classes.zip
   --commit <sha>` when `gh` cannot reach GitHub). It creates and checks the da.gd short links (the only shortener whose
   redirect sends ACAO `*`, which Steam's CORS image fetch needs) and adds them to `docs/workshop/short-urls.txt`: commit that.
4. Steam really logged on (`grep -E 'Logged On|Session Replaced' ~/.local/share/Steam/logs/connection_log.txt | tail -2`),
   then `python3 scripts/workshop-upload.py --dir /tmp/ws --description-only --notes "..."` (`--with-preview` only when the
   preview GIF changes). `upload OK: EResult 1`.
5. Verify through the keyless Web API (the page itself answers 429 to scripts): POST
   `https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/` with `itemcount=1&publishedfileids[0]=3805285544`
   and look for the section and the da.gd links in `description`.

A page-only update is not a release: no tag, no Discord post. GitHub's README and blob view show nothing for AVIF (keep GIFs
there). When this machine's resolver loses github.com (2026-10-06: 1.1.1.1 returned no records while quad9 did),
`git -c http.curloptResolve=github.com:443:<ip from dig @9.9.9.9 github.com>` pushes without touching the system DNS.
