# Dynamic resolution (2026-10-03, branch `dynamic-resolution`)

Goal (maintainer): the world's render resolution follows the frame-rate cap, as stable and seamless as possible, with
the current state of the art tried one by one and profiled. Everything is off by default (`dynRes=false`).

## What was built

| Piece | Where | What it does |
|---|---|---|
| Per-frame scale | `pzopt.RenderScale`, `pzopt.DynRes.beginFrame` | `RenderScale.scale()` is per frame under `dynRes`: the game thread picks the scale of the frame it builds right after `SpriteRenderer.NewFrame` and files it under that frame's `SpriteRenderState`; the render thread latches it when it acquires the frame (`DynRes.gpuBegin`). Both threads agree on every frame (composite rect, fog uniforms, sprite-filter regime on the game thread; viewport, resolve, DLSS sub-rectangle on the render thread). A draw-list marker does not work: `Core.StartFrame` clears the list (`prePopulating`) after the frame began. |
| GPU time per frame | `DynRes.gpuBegin/gpuEnd` (RenderThread around `postRender`) | two `GL_TIMESTAMP` queries per frame, 16 slots, read without a stall a few frames later; also the render-thread replay time and the GPU's lag behind it (start / end, GPU clock mapped onto `System.nanoTime` once a second). |
| Controllers | `dynResController` | `model` (default): GPU ms = a + b·x + c·bakes, x = pixel fraction (scale²), a three-state Kalman filter with innovation gating (3 sigma; three same-sign outliers = a new regime), proportional process noise (a load change keeps the fixed / per-pixel split until the scale moves), a robust headroom (1.25 × the mean absolute residual), and the scale solved from the model; when the per-pixel term is (confidently) under 15 % of the frame the scale stays at its maximum. `pi`: integral control on log GPU time with a Smith-predictor correction for the frames in flight. `step`: the classic engine rule (drop at once over the interval, +2 % after 15 samples under 90 % of the target). |
| Smoothing | `DynRes.beginFrame` | width quantum (`dynResStepPx` 8), deadband (`dynResDeadbandPct` 2), time hysteresis (`dynResUpDelayFrames` 8 up, 2 down), rate limits (`dynResUpPerMille` 5, `dynResDownPerMille` 60), urgency (model: its own prediction at the scale in use exceeds the interval). |
| Starvation gate | `dynResStarveGate` | a frame whose GPU finished within 0.25 ms of the render thread's last command (GPU waited for the CPU) and is over the target does not lower the scale. |
| Bake feedforward | `dynResBakeFeedforward` (off) | the bake scheduler's backlog at the end of a frame predicts the next frame's bakes; that frame alone renders smaller by the model's bake term. |
| Native bypass + sharpen ramp | `dynResNativeBypass`, `dynResSharpenRamp` | fsr1 at 100 % skips the resolve (the stock composite); RCAS fades in from 0 at 100 % to the configured strength at 85 %, so crossing the bypass shows no jump in sharpness. |
| TAAU | `pzopt.Taau`, `upscaler=taau` / `dynResUpscaler=taau` | a GLSL temporal upscaler (FSR 2 / TSR family): Halton jitter, history at the output size (RGBA16F, alpha = accumulated weight), nearest-sample accumulation weighted by distance in output pixels, exact camera reprojection (iso camera = translation + zoom), per-object motion (characters / vehicles via `ObjectMotion` stencil ids), YCoCg variance clipping, Catmull-Rom history fetch, RCAS on the shown image only. |
| DLSS sub-rectangle | `pzngx_subrect` (shim), `Dlss.frame` | DLSS images created at the largest render size; each frame's size goes in as `InRenderSubrectDimensions`, so a scale change neither rebuilds the feature nor drops its history. |
| Supersampling | `dynResMaxPct` > 100 (fsr1 / bicubic) | up to what the offscreen texture (next power of two above the screen) holds; the stock composite's bicubic shrinks it. |
| No driver round trips | `Upscaler.savedState` | under dynRes / taau the resolve takes the bound framebuffer and viewport from the game's records (each `glGet` waited for NVIDIA's driver thread: render-thread replay 2.2 → 1.0 ms). |
| Rigs | `devDynResLoad`, `devDynResForce`, `harness/dynres.py`, `harness/dynres-seam.py` | a synthetic per-pixel GPU load in the world image (square / sine / ramp), a forced open-loop scale pattern, the per-frame log `pzopt-dynres.out`, step-response and seamlessness reports. |

## Measurements

Desktop (RTX 4090, 5120x2160), queue runs, `--no-mangohud`, an empty options file
(`-Dpzopt.userOptionsFile`, the desktop's tab file turns HDR / AO / DLSS on), `harness/dynres.py` over the route window.
"late" = presented frames over 1.5x the interval (overlay log).

### Controller step response (synthetic load, `devDynResLoad`)

Bench route S:450 at zoom 2.5, 25 s, cap 240 (4.17 ms), a square-wave per-pixel GPU load every 3 s.
Calibration: 0.0039 ms per load iteration at 5120x2160 (1.16 ms base on a still scene).

Round 2 (load 200 <-> 1400, infeasible even at 50 % in the heavy half):

| run | fps | late | p99 ms | mean scale | reversals/s |
|---|---|---|---|---|---|
| off (`dr-sq-off2`) | 167.9 | 33.5 % | 14.8 | 1.00 | 0 |
| model (`dr-sq-model2`) | 228.7 | 4.7 % | 9.1 | 0.63 | 12.4 |
| pi (`dr-sq-pi2`) | 227.8 | 5.0 % | 9.7 | 0.61 | 19.9 |
| step (`dr-sq-step2`) | 230.9 | 4.2 % | 8.6 | 0.50 (pinned at the minimum) | 0.2 |

Reading: even pinned at 50 %, 26 % of the frames are over the interval: the route's chunk-bake / streaming spikes,
which no render scale removes. A controller that chases them only costs resolution: the step rule's creep-up never
fires (its "15 samples under 90 %" never happens with spikes), the PI oscillates. The model holds the same pacing at a
much higher resolution.

Round 3/4 fixes, each from a log read: (1) the "urgent" drop on a single spike made the scale saw-tooth (12 reversals/s)
-> urgency is now the model's own prediction at the scale in use; (2) the headroom from the RMS residual was inflated by
the spikes -> 1.25 x the mean absolute residual; (3) time hysteresis (8 frames up, 2 down); (4) on a load step at one
scale the filter cannot tell fixed from per-pixel cost: it put the jump on the fixed term (b -> 0), the "pixels do not
matter" rule fired and the wish flipped 0.5 <-> 1.0 -> proportional process noise (a load change keeps the split until
the scale moves) and the rule only applies when the model is sure (sigma of b small). (5) An over-budget regime is
taken after two outliers in a row (three for lighter).

Round 4/5 (load 200 <-> 900, feasible):

| run | fps | late | p99 ms | p99.9 ms | mean scale | reversals/s |
|---|---|---|---|---|---|---|
| off (`dr-sq-off4`) | 184.1 | 28.0 % | 12.6 | 25.6 | 1.00 | 0 |
| model + fsr1 (`dr-sq-model4` / `model5`) | 227.0 / 233.2 | 5.8 / 3.6 % | 9.9 / 8.5 | 22.5 / 19.6 | 0.61 / 0.60 | 3.9 / 3.9 |
| model + taau (`dr-sq-model4-taau` / `model5-taau`) | 235.1 / 222.1 | 2.9 / 7.8 % | 8.1 / 12.1 | 15.8 / 18.2 | 0.60 / 0.57 | 3.3 / 3.4 |
| model + taau + bake feedforward (`dr-sq-model4-taau-ff`) | 212.3 | 10.8 % | 13.3 | 19.6 | 0.70 | 29.7 |
| model + dlss (`dr-sq-dlss5`) | 200.4 | 14.4 % | 13.7 | 19.7 | 0.50 (pinned) | 0.1 |

Reading: run-to-run noise on this route is about +-2 % late frames and +-6 fps (the repeats), so fsr1 and taau do not
differ in timing; both take the late frames from 28 % to 3-8 %. The bake feedforward learnt 0.34 ms per chunk bake,
but its bake term soaked up variance (headroom 0.72 -> 0.49 ms), the base scale ran hotter and the per-frame dips came
too late for the spikes: worse, left off. DLSS follows the scale without a single rebuild (sub-rectangle; `dlss: rebuilt`
never logged, history kept), but at a 5120x2160 output its 0.63 ms + 1-1.6 ms of CPU preparation pin the scale at the
minimum here; on this desktop DLSS stays an image-quality option.

### Seamlessness (still scene, scale forced 60 <-> 100 % every second, `devDynResForce`, `devCapture` 960x540 1:1 crop)

`harness/dynres-seam.py`; SSIM against the native capture of the same spot (`dr-seam-floor`, a still frame).

| run | SSIM at 60 % | SSIM at 100 % | sharpness jump between frames p99 | frame noise (mean abs diff) |
|---|---|---|---|---|
| native, fixed 100 % (`dr-seam-floor`) | - | 0.997 (self) | 0.03 % | 0.173 |
| bicubic | 0.739 | 0.996 | 110 % | 0.238 |
| fsr1 | 0.768 | 0.993 | 87 % | 0.222 |
| taau v1 (variance clip 1.25 sigma) | - | - | 13.5 % | 0.347 (shimmer) |
| taau v2 (min/max box, sigma 0.35, 16 frames) | 0.793 | 0.888 | 13.2 % | 0.343 |
| taau v3 (adaptive jitter, confidence-weighted rectification) | 0.819 (switching), 0.783 (fixed 60 %) | 0.969 (RCAS on), 0.965 fixed | 29.6 % | 0.144 at 100 % |

Reading: a spatial upscaler shows every scale change in full on the next frame (fsr1: an 87 % jump in detail between
two frames); the temporal one spreads it over the history. TAAU v1 shimmered on a still scene (twice the native frame
noise): a variance box cuts into pixel art's two-colour edges and every clip threw the accumulated weight away. v3: no
jitter at the screen size (point-sampled art stays where the stock game samples it; full jitter from 75 % down), the box
is the neighbourhood's min / max, and history is clipped only where a current sample fell near the pixel's centre or the
scene moved under it: at 100 % it is steadier than the stock frame (0.144 vs 0.173) with RCAS off at native
(`dynResSharpenRamp`), and at 60 % it is the closest to native of the three (0.819 while switching: the low phases keep
detail gathered in the high ones). The Laplacian "sharpness" alone mis-ranks them: RCAS-sharpened blotches score high
(fsr1 at 60 %, `/tmp` crops compared by eye: fsr1 blotchy asphalt and jagged hair, taau smooth edges, finer grain).

### When a smaller image buys nothing (rounds 6-8)

Three ways the controller gave resolution away for no frame time, each found in a run and fixed:

| scene | before | fix | after |
|---|---|---|---|
| storm + stock fog (`fogPass=false`) at a 400 fps target, CPU-bound (game thread ~5-7 ms a frame) | scale pinned at 0.50 (`dr-sf-taau`), 0.62 (`dr-sf-taau4`); fps the same as off (off 154 / 175 in two runs: this scene's run-to-run noise) | `dynResCpuAware`: the GPU's interval is the longer of the cap's and the game thread's own time per frame (step start to hand-off, without the limiter and the ready-slot wait; median of 16), and when the game thread sets the pace the target is that whole interval, no noise headroom (the GPU's spikes go into the frames in flight) | mean scale 0.985 (`dr-sf-taau5`) |
| 120 km/h drive at the 240 cap (bakes dominate the GPU frame; pixels ~10 %) | mean scale 0.675 / 0.71 (`dr-drive-taau`, `-taau2`): every chunk-row bake burst (two frames over the interval) was taken for a heavier scene, the scale dropped and crept back | `dynResBakeTerm`: each frame's granted chunk-level bakes are a term of the model (c ms a bake; it learns 0.27-0.34 ms a bake on the 4090 in every scene), so a burst is explained, not a regime change; the scale is solved for a frame without bakes | mean scale 0.996, 4.5 changes/s (`dr-drive-taau3`) |
| a frame that is all fixed cost, pinned at the minimum (offline: `tests/pzopt/DynResModelTest`) | at one scale the model cannot tell fixed from per-pixel cost and never learns that the pixels are cheap | `dynResProbe`: a second at the minimum with the wish there too renders 8 frames 30 % larger (persistent excitation); per-term proportional drift (the split can move once the scale varies) | the test learns b ~ 0.2 and returns to native |

The 120 km/h drive with dynRes is not slower than without (`dr-drive-off` 226.6 fps / late 6.6 %, `dr-drive-taau` 230.6 /
5.1 %). Rejected on the way: **horizontal-only scaling** (`dynResAxes=x`, the frame's pixel fraction spent on the width,
TAAU): SSIM to native 0.710 against 0.803 for both axes at the same pixel count (60 % x 60 %), and its 100 % phases kept a
smear (0.939 vs 0.969); kept as an A/B key. **DLSS** follows the scale (see above) but costs more than it saves at 4K.

### TAAU at the native size (rounds 9-11)

| run | GPU mean (drive) | p99 sharpness jump (seam rig) | SSIM at 60 % (seam rig) |
|---|---|---|---|
| no dynRes (`dr-drive-off`) | 3.46 ms | - | - |
| taau always on at 100 % (`dr-drive-taau3`, `dr-seam-taau3`) | 3.73 ms (+0.28: a 5K resolve for nothing) | 29.6 % | 0.819 |
| `dynResNativeBypass` for taau, cold history (`dr-drive-taau4`, `dr-seam-taau-bypass`) | 3.50 ms | 88.6 % (the history restarts from a bilinear upscale: as visible as fsr1) | - |
| bypass + `taauWarmBypass` (the native frame copied into the history, one texel per pixel) (`dr-drive-taau5`, `dr-seam-taau-warm`) | 3.57 ms | 36.0 % | sharper low frames (Laplacian 11.8 vs 11.3) |

### Supersampling

`dynResMaxPct=150` with fsr1 on a still scene at the 240 cap (`dr-ss150`): the scale went to 1.5 (mean 1.475) with the
GPU at 1.9 ms of the 4.17 ms interval; the stock composite's bicubic shrinks the 7680x3240 image (the offscreen
texture is 8192x4096). The capture looks sane (anti-aliased hair and edges). Opt-in: off by default (`dynResMaxPct`
100), not with DLSS or taau, and the Enhancements passes that size buffers to the screen were not checked under it.

### Final state (round 11, same build as the defaults below)

| run | fps | late | p99 ms | p99.9 ms | mean scale |
|---|---|---|---|---|---|
| square-wave load, off (`dr-sq-off4`) | 184.1 | 28.0 % | 12.6 | 25.6 | 1.00 |
| square-wave load, dynRes + taau (`dr-sq-model10-taau`) | 223.7 | 7.3 % | 11.1 | 18.2 | 0.59 (heavy halves 0.53, light 0.66) |
| 120 km/h drive, off (`dr-drive-off`) | 226.6 | 6.6 % | 8.8 | 11.5 | 1.00 |
| 120 km/h drive, dynRes + taau (`dr-drive-taau6`) | 223.3 | 7.7 % | 8.9 | 11.1 | 0.996 |

On this route the late-frame share moves 3-8 % between identical runs; the drive is unchanged within that, and the
square-wave load goes from 28 % late to the noise band. The first 30 frames after a load step are 40 % over the interval
(the floor is the pipeline: the GPU timestamps arrive ~3 frames late and the game thread is 1-2 frames ahead).

## Defaults (all live on the Enhancements tab, section "Dynamic resolution")

`dynRes=false` (opt-in, like every enhancement), `dynResUpscaler=taau`, `dynResController=model`, `dynResTargetPct=90`,
`dynResMinPct=50`, `dynResMaxPct=100`, `dynResFps=0` (the cap in force; VRR cap with VRR on; uncapped: max scale),
`dynResCpuAware`, `dynResBakeTerm`, `dynResProbe`, `dynResStarveGate`, `dynResNativeBypass`, `dynResSharpenRamp`,
`taauJitterAdaptive`, `taauWarmBypass` on; `dynResBakeFeedforward` off (measured worse), `dynResAxes=both`
(horizontal-only measured worse). The overlay's first line shows `res NN %` while the scale is dynamic.

## Open

- The laptops (flip: Radeon 890M, the case dynamic resolution is for) were disconnected the whole session; the desktop's
  4090 is GPU-bound only under the synthetic load or a 400 fps target. A flip run (`--install opt`, a fog / storm preset
  at its 120 Hz cap, dynRes off vs on) is the next measurement.
- TAAU has no reactive mask (FSR 2's per-pixel "do not trust history" for particles / animated water): rain and water
  rely on the neighbourhood clip. Not looked at in rain yet.
- DLSS under dynRes was only measured at the full 5K output; with `dlssOutputPct` below the screen the output stays
  fixed at the largest render size (the code keeps it), not measured.
- The Options-tab entries were syntax-checked (luac), not clicked through in game.

## The flip (2026-10-03 05:00, after pulling origin/master 726308a)

diego-flip (Radeon 890M, Mesa 26.2, 1920x1080, internal panel 120 Hz), balanced power profile on AC, `--launcher direct`,
cap 120, storm + heavy fog spin bench (S:450, turn 90, zoom max, 25 s) and the 120 km/h drive (E:1200).

**Build defaults** (empty options file): not GPU-bound at 1080p (GPU 4.4 ms of 8.33); dynRes stays at native, same
frame rate (spin 114.7 / 114.5 fps, drive 119.8 / 119.7, `dr-fl-spin-*`, `dr-fl-drive-*`).

**The player's own Enhancements settings** (the flip's tab file: AO, HDR, god rays, pixel light + shadows, reflections,
relief, sun shadows, sharp sprite filter, fsr1 at a fixed 67 %): GPU-bound, ~100 fps.

| run | fps | late | p99 ms | p99.9 ms | mean scale |
|---|---|---|---|---|---|
| fixed fsr1 67 % (`dr-fl-pc-fixed`, `-fixed2`) | 102.9 / 98.7 | 9.0 / 15.4 % | 19.5 / 21.9 | 63.2 / 30.2 | 0.667 |
| dynRes + taau, before the fixes below (`dr-fl-pc-dyn-taau2`) | 89.9 | 8.5 % | 20.5 | 33.6 | 0.993 |
| dynRes + taau (`dr-fl-pc-dyn-taau3`) | 101.5 | 9.9 % | 19.1 | 35.2 | 0.648 (0.50-1.00) |
| dynRes + fsr1 (`dr-fl-pc-dyn-fsr1-2`) | 101.6 | 10.4 % | 18.7 | 29.9 | 0.671 (0.50-1.00) |

What the flip found (fixed in `DynRes`):
1. **The model learnt from the loading frames.** The first frames in the world take 24-29 ms of GPU with a 40 ms "interval"
   (the game thread finishing the load); the model started from them with a nonsense split, settled on "the pixels are
   cheap" (b 0.9) and held the native scale while the GPU filled 99.5 % of the frame (89.9 fps). Now the first 120 frames in
   a world are not controlled or learnt (`WARMUP_FRAMES`).
2. **No probe from the top.** Pinned at the maximum with the GPU over its target, the model could never test its belief;
   `dynResProbe` now also renders 8 frames 30 % smaller there.
3. **Shared-power hardware.** On an APU the GPU's load can slow the game thread (power and memory bandwidth), which would
   feed the CPU-aware interval. `DynRes.CpuModel` fits the game thread's own time against the pixel fraction (RLS); when the
   frame is CPU-paced the scale stops where the game thread would be 3 % slower than at the lowest scale. In these runs it
   measured no slowdown (q <= 0), so it did not engage; on the desktop q ~ 0.
Also from the flip: the TAAU warm copy cost 0.24-0.36 ms a frame on the 890M at 1080p (0.07 on the 4090): now every 4th
native frame (`taauWarmEvery`). Seamlessness on Mesa / 890M (`dr-fl-seam-*`): p99 sharpness jump 24 % for taau switching
60 <-> 100 %, the low phase at 46 % of native sharpness.

Reading: on the flip, dynRes keeps the fixed-67 % frame rate (+3 % in these runs, inside the noise) while the resolution
follows the scene (50-100 %) instead of sitting at 67 %; the tails stay in the fixed setting's range.
