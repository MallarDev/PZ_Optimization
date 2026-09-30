# Lights flickering while zooming, 2026-09-30

Report (maintainer, after the 702fdb5 update): "the light flickers while zooming in and out". Reproduced on their latest save
(`Sandbox/2026-09-30_00-33-03`: night, lamp posts and a porch light, their Enhancements settings: HDR output, pixelLight + torch
shadows, sun shadows, AO, relief, DLSS/FSR). Worktree `~/pzopt-wt/zoomflicker`, runs `zf-*` / `zfc-*` on the desktop queue.

## Rig

`--mode bench --launcher direct --source-save Sandbox/2026-09-30_00-33-03 --flag zoom=1 --flag zoom_cycle=1 --flag route=S:1
--flag speed=0.025 --route-seconds 40 --prop devCapture=8,26,240,100,gray,crop=1920:630:1280:900`: the character stands still
and the camera steps one mouse-wheel notch every second (eased over ~300 ms), 0.25 <-> 2.5; every presented frame of the screen
centre is read back 1:1. `harness/zoom-flicker.py --test <run> [--before <run>] [--control <run>]` reduces each frame to 16x16
block means and counts a block that leaves its 3x3 neighbourhood's range in both the previous and the next frame by more than
4/255 (steady zoom motion stays inside that range, a lamp pool brightening for one frame does not), then asks Jev.

The first runs captured at 25 % (`devCapture=...,25`): that capture is a linear blit, and it aliased the relief's fine texel
pattern into blinks that are not on screen (every relief-on variant "flickered"). Only the 1:1 crops are trusted.

## Bisect (1:1 crops, blinking 16x16 blocks over 26 s)

| Run | Blocks | Largest frame |
|---|---|---|
| 702fdb5, the maintainer's settings (relief on) | 35,318 | 1,433 (34 % of the view) |
| same, relief off | 9,702 | 493 |
| relief on, `reliefSunPct=0` (no baked sun / moon relief) | 7,573 | 492 |
| relief on, `pplShadows=false` / `pixelLight=false` / `upscaler=off` | 36,081 / 33,906 / 36,339 | ~1,200-1,500 |
| relief on, `hdr=false` | 596 | 35 |
| relief on, `hdrBloomPct=0` | 6,397 | 371 |
| relief on, `hdrLightPct=0` (no HDR lamp-light gain) | 633 | 35 |
| relief on, `hdrItmPct=0` / `hdrSunPct=0` | 31,586 / 37,041 | ~1,100-1,200 |

HDR's lamp-light gain is the part that blinks; the bloom it feeds spreads it, and the baked relief (brighter and darker texels
in lamp-lit ground) makes it about four times as visible, which is why it showed up with the relief update. It was there before
(relief off: 9.7k vs 0.6k without the lamp gain). HDR debug view 4 (the bloom alone) showed the whole bloom's intensity dropping
by half for single frames during the ease (relief off too: 18 dips and 22 spikes in 26 s).

## Cause

`pzopt.HdrLight` maps the window to its light map (the squares' lamp light, built on a worker) with a projection computed on the
game thread from three points, as float differences: `px + 2 py` of the camera offset is ~1.3 x 10^6 world px there, whose float
step is 1/8 px, while one window pixel adds `zoom` (0.25 zoomed in) to it. The per-pixel coefficients came out quantised
(traced: u per x pixel 7.63e-6 or 1.144e-5 on consecutive frames, v per y pixel -1.526e-5 or -1.907e-5), so every frame the
camera offset changed (a zoom moves it every frame) the lamp-gain map was stretched by a different error of up to +-50 %: the
lamp pools and their bloom jumped about. Zoomed in is worst (the smaller `zoom`, the larger the relative error); a still camera
keeps one error, so nothing blinks there.

Ruled out on the way (each measured): the light map's content (traced per upload: stable), the worker racing the renderer's
corner colours (`devHdrLightSync` build: same), the live camera vs `IsoCamera.frameState` (identical values), the bloom bright
pass's point-sampled decimation (a 4x4 tent prefilter: same), the relief's zoom gate (`reliefMaxZoom=100`), its step budget.

## Fix

`HdrLight.project`: the same projection in double precision, rounded to float only at the end. Also: on a frame without a newly
finished map the held map is re-projected with that frame's camera (before, the composite kept the last upload's projection).

## Result

| Run | Blocks | Frames >= 50 blocks | Largest frame |
|---|---|---|---|
| 702fdb5 (`zf-crop-on`) | 35,318 | 109 | 1,433 (34 %) |
| fixed build (`zfc-fix2-on`) | 678 | 0 | 36 (0.9 %) |
| fixed build, repeat (`zfc-fix2-on2`) | 661 | 0 | 34 (0.8 %) |
| fixed build, relief off (`zfc-fix2-off`) | 511 | 0 | 27 |
| reference: 702fdb5 without the lamp gain (`zfc-light0`) | 633 | 0 | 35 |

What is left is the zoom motion's background (the same with the lamp gain off or HDR off). Still frames at the same zoom differ
by 0.5/255 on average between the two builds (the lamp gain lands where it did, minus the static error), against 6-10/255
without the lamp gain.

Jev (`zoom-flicker.py`, numbers only): repro, 702fdb5 relief on vs relief off: `test_flickers` yes 0.96; fix, fixed build vs
702fdb5 with the lamp-gain-off reference: `verdict` fixed 0.89 (`test_flickers` 0.29, `before_flickers` 0.93); the repeat run fixed 0.87.
