# What each visual enhancement costs (2026-10-08)

The maintainer asked for the performance impact of every Enhancements-tab feature. Desktop (RTX 4090, 5120x2160, NVIDIA GL,
build 42.21 install of 2026-10-08), uncapped, zoom 2.5, `uiRenderOffscreen=true`, the player standing still on the bench
save's square (8002,11204, Rosewood) and turning 180°/s, its own `-Dpzopt.userOptionsFile` per run.

## Method

`harness/enhcost.py` (new). One run per scene switches the live keys with the `live_set` rig: 3 s windows, the first 1 s after a
switch dropped (re-bakes, program swaps), 2 s measured = one full turn, each test window against the mean of the windows on its two
sides. Two directions:

- **live-on**: boot with everything off, switch one key on at a time (`enhcost-still-1`, `enhcost-rain-1`);
- **all-on, live-off** (`schedule --invert`): boot with every enhancement on, switch one key off at a time; cost = on top of all
  the others. Needed because god rays, the sun shadows' model shaders and reflections patch their shaders at launch only ("off at
  launch, ... stays stock"): switching them on mid-run measures nothing (`enhcost-allon-day-1`, `enhcost-allon-night-1`).

The keys that only apply at the next launch were measured with their own dev alternation rigs in the all-on setup, 2 s periods
(`enhcost.py alt <run>`: per-period medians, paired on vs neighbouring offs, route window only): `devReliefAlternate`,
`devMirrorsAlternate`, `devCarGlassAlternate`, `devSsrAlternate`. pixelLight has no working in-run switch (`devPplAlternate=10,2,0,256`
ran 3-12 frames of the stock program in 30 s), so it is across runs: all-on vs all-on without pixelLight + relief.

Noise (median difference between neighbouring base windows): 3-15 µs in the still runs. The first try (`enhcost-day-1`, walking
south at 9 tiles/s) drifted 2.8 -> 1.1 ms along the route with 0.24 ms between neighbouring off windows: useless for these sizes.
The first night sweep drifted ~0.3 ms in its first 20 s while the night lighting settled (start the windows after 12-15 s).

## Results (ms per frame, + = slower)

| Feature | Day, alone | Day, on top of all | Night + torch, on top of all | Rain, alone | Toggle hitch |
|---|---|---|---|---|---|
| **Sun shadows** (cloud + moon shadows on) | +0.05 | **+0.27** | +0.04 (moon) | +0.06 | 160-260 ms |
| **Per-pixel lighting + relief** (next launch) | | **~+0.2** (across runs, ±0.1) | **+0.21** (across runs) | | |
| Foliage sway | +0.04 | +0.09 | +0.10 | +0.06 | |
| God rays | (not patched live) | +0.09 | 0.00 | +0.03 | |
| Ambient occlusion | -0.01 | +0.04 | 0.00 | | 190-570 ms |
| Reflections (next launch) | | 0.00 (no water in view) | +0.01 | +0.03 frame / +0.06 GPU (puddles, ±0.01) | ~500 ms |
| Relief alone (with pixelLight) | | -0.01 ±0.01 | | | |
| Mirrors and windows (next launch, 33 panes) | | +0.004 ±0.015 | | | |
| Car glass (next launch, car on screen) | | -0.007 ±0.036 | | | |
| Torch source | | | -0.01 | | |
| Sprite filter (sharp) | -0.01 | 0.00 | | | |
| Darkness floor 20 % | 0.00 | 0.00 | 0.00 | | 110-140 ms |
| Remembered places | -0.03 | -0.06 | 0.00 | | 130-170 ms |
| Wet blood | 0.00 | +0.01 | | 0.00 | |
| Occluded zombie outlines | 0.00 | -0.05 | | | |
| **Colour grading** | **-0.03** | **-0.04** | **-0.05** | **-0.03** | |
| FSR 1 (67 %) | +0.05 | | | | |

Totals, route-window medians across runs (mean in brackets):

| Scene | All off | All on | Cost of everything |
|---|---|---|---|
| Day | 1.14 ms (1.33), 754 fps | 1.68 ms (1.84), 543 fps | **+0.5 ms** |
| Night + torch | 1.33 ms (1.55), 646 fps | 1.54 ms (1.70), 588 fps | **+0.2 ms** (all of it pixelLight) |

Readings:
- Two features are most of the bill: **sun shadows** (0.27 ms with everything on; its per-frame part is the character / vehicle
  capsule pass and the cloud field, both bigger when the other passes are on) and **per-pixel lighting** (~0.2 ms day and night).
  Sway and god rays are ~0.1 ms each; everything else is inside ±0.05 ms, i.e. free on this machine.
- **Colour grading is cheaper than stock** in every scene (its LUT replaces `screen.frag`'s 3D-noise grain), as the darkness /
  grading findings said.
- The baked features (AO, darkness floor, remembered places, sun-shadow terms) cost nothing standing still; the price is a
  one-time re-bake of every on-screen chunk when the setting changes (0.1-0.6 s hitch on Apply) and extra bake work while chunks
  stream in, which these still runs do not measure (earlier: AO ~1.3 % of GPU time in the worst streaming,
  `docs/findings-ambient-occlusion-2026-09-24.md`; sun shadows +33 µs GPU on the drive, `docs/findings-contact-shadows-2026-09-25.md`).
- At 540-800 fps nothing is saturated (game thread 55-80 %, render thread 65-75 %, GPU 1.1-1.8 ms of a 1.3-1.8 ms frame): even
  everything on stays above twice a 240 Hz cap here. The costs are absolute: on a slower GPU the GPU-side ones (sun shadows,
  pixelLight, sway, god rays, reflections) scale with its speed.

Not measured: **HDR** (forces the Wayland window: not switchable in a run; earlier +0.2 ms at 4K, `docs/findings-hdr-2026-09-24.md`),
**DLSS** (this install has no `natives/libpzopt_ngx64.so`; the dlss window ran fsr1), **dynamic resolution** (a controller, not a
cost: it moved with the scene). Upscaling does not pay at this load (the GPU is not the limit).

Runs: `enhcost-still-1`, `enhcost-rain-1`, `enhcost-night-1` (drifted), `enhcost-allon-day-1`, `enhcost-allon-night-1`,
`enhcost-relief`, `enhcost-mirrors`, `enhcost-carglass`, `enhcost-ssr-rain`, `enhcost-allon-noppl`, `enhcost-n-{all,noppl,off}`
(all 2026-10-08, `harness/runs/`).
