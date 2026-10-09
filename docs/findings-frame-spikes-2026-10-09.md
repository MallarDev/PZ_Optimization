# Frame-time spikes on the short drive and spin routes (2026-10-09)

## Note (summary, and what to re-run after the clean restart)

**Findings**
1. **The Enhancements set costs ~2.9x the frame rate while moving** (uncapped, §4): highway 569 → 202 fps, Rosewood
   457 → 147, spin 349 → 121; frames > 20 ms 2 / 6 / 5 → 10 / 112 / 45. Standing still it is only ~+0.5 ms (the peer's
   sweep), so the cost is in streaming (chunk bakes) and on the render thread: with the Enhancements the render thread is
   the wall (88-91 % of a core), without them the GPU (88-96 %). Not HDR (the spin was slower with HDR off).
2. **The worst frame without the Enhancements is ours**: `CutawayMask.read` (treeCutawayReach) decodes the cutaway mask PNG
   and scans 1024 x 1024 texels with `getRGB` on the game thread the first time a cutaway stencil exists: ~1.5 s into every
   drive, 60-64 ms (32-37 ms with the Enhancements). Once per session. Fix: read it at world load on a worker.
3. **Shaders compiled on the render thread mid-play**: foliage sway's twin of a pixelLight variant, built (test compile,
   test link, real build) the first time the variant is drawn: 144 ms; the patched `vehicle` program for the first car of
   a session (a burnt-car story): 428 ms. Fix: build the twins with the variants at load, warm the patched model programs
   in the loading screen.
4. Smaller: an animal type's animation set parsed from XML on the game thread at a chunk hand-off (~15 ms, stock
   behaviour); `pzopt-hdr-light` busy on a full core all the time (power, not frame time).
5. **Setup**: the desktop is at 5120x2160 **165 Hz with VRR** (not 240); with the defaults the game caps itself at 157 fps
   (`vrrCap`), which is why the capped defaults runs read ~153 fps. A run without `-Dpzopt.userOptionsFile` uses the
   desktop's tab file (nearly every Enhancement on, HDR on).

**Machine state during these runs (01:36, before the restart)**: zed-editor at 136 % CPU, Steam client + 3 webhelpers
~28 %, kwin 6 %, kswapd active; RAM 16 / 30 GB used and **14.6 GB in zram swap** (the capped spin swapped 58k pages back in
during its route, major faults 60k); GPU shared with Chrome, Tidal (530 MB), Discord (627 MB), Zed (679 MB), kwin; load
average 3.6-5.2. Machine CPU during the runs 41-52 %, so the CPU was never saturated, but swap-ins and other GPU clients can
add hitches.

**Re-run after the restart** (same six uncapped runs, labels `<base>-<suffix>`, then the side-by-side against these):

```bash
harness/spikes/rerun.sh clean "<session name>"     # queues unc-{hwy,rw,spin}-{tab,def}-clean on the desktop, ~6 min
harness/spikes/compare.sh clean                    # fps / p99 / p99.9 / max / CPU / GPU / frames > 20 ms, old row then new
```

Compare like with like: same build (the working tree of 10-09 01:00; a rebuild or install in between changes it), same
refresh (165 Hz, VRR on), and check `free -h` / `swapon --show` before the runs (swap should be near 0 after a restart).
Per-frame tools: `harness/spikes/table.py <run> 20` (game step / render submit / GPU / bakes per slow frame),
`perframe.py` and `rootside.py` (async-profiler stacks of the slow frames; need `--asprof event=cpu,interval=2ms,wall=2ms`).

Asked: "investigate frame time spikes, use a short version of the 120km drive and south rosewood runs". Desktop (RTX 4090,
5120x2160, build of 2026-10-08 with the working tree's changes), `--launcher direct`, `--option frameRate=240`.

## Setup found on the way

- **The desktop runs at 165 Hz with VRR active now** (`kscreen-doctor`: 5120x2160@165, console `vrr: VRR active (drm, 165 Hz),
  cap 157 fps`), not 240 Hz. With the defaults `vrrCap` caps the game at 157 fps; the maintainer's tab file
  (`~/Zomboid/pzopt/options.ini`) has `vrrCap=false`, so it targets 240.
- A run without `-Dpzopt.userOptionsFile` uses that tab file: nearly every Enhancement on (HDR, pixelLight + relief, AO, sun /
  cloud shadows, god rays, sway, reflections, mirrors, car glass, grading, sprite filter, blood...). That is what the player
  sees, so the first runs kept it.

## Runs

| Run | Settings | fps | p99 | p99.9 | max | frames > 20 ms | GPU | game / render thread |
|---|---|---|---|---|---|---|---|---|
| `spk-drive` (487 tiles of `drive-120-south`, 25 s) | tab file, asprof + schedmon | 152 | 21.1 | 26.8 | 144 | 53 | 74 % | 74 / 92 % |
| `spk-spin` (spin, 15 s route) | tab file, asprof + schedmon | 123 | 21.9 | 30.1 | 428 | 49 | 63 % | 85 / 87 % |
| `spk-spin-tab` | tab file | 130 | 20.8 | 30.8 | 36.5 | 35 | 85 % | 86 / 88 % |
| `spk-spin-nohdr` | tab file, `hdr=false hdrAuto=false` | 110 | 24.7 | 40.9 | 158 | 105 | 62 % | 94 / 55 % |
| `spk-spin-def` | empty options file (defaults) | 153 (at the 157 VRR cap) | 11.7 | 20.4 | 57.6 | 5 | 46 % | 68 / 39 % |

No GC pause overlaps any spike (`gc-spikes.py`: 0 of 8 / 18); the game thread never waited for a CPU (schedmon).
The profiler costs ~5 % (spin 123 vs 130 fps).

## 1. Single big hitches: shaders compiled on the render thread in the middle of play

- **428 ms (spin, +22.9 s)**: a burnt-car vehicle story (`IsoChunk.updateVehicleStory` → `RVSBurntCar` → `BaseVehicle.addToWorld`
  → `ModelManager.loadModelInternal` → `Model.CreateShader`) needed the first vehicle model of the session; the game thread
  waited in `RenderThread.invokeOnRenderContext` while the render thread sat in `glLinkProgram` (`ShaderManager.getOrCreateShader`)
  for the `vehicle` program, which we patch (`entity shadows: vehicle.frag patched`, `hdr: HDR glint output added`). The
  driver's disk cache (`~/.cache/nvidia/GLCache/2560ed...`, rewritten at the run's end) evidently missed: any build that
  changes one of the patches gives a new source.
- **144 ms (drive, +0.9 s)**: foliage sway built the twin of pixelLight variant 100 lazily on its first draw
  (`PixelLight.chunkDraw` → `Sway.twinOrSelf` → `twinFor` → `new ChunkRenderShader("pzopt_swChunk")`), and
  `Sway.patchShader` test-compiles and test-links the twin before the real build: three compiles inside one frame. The spin did
  the same twice (f:587, f:782). pixelLight compiles its 8 variants in frames 1-7, but the twins only appear as each variant
  is first drawn (torch, wet, ...), so a player meets several of these hitches per session.
- Fix directions: build every twin when pixelLight compiles its variants (or with `GL_KHR_parallel_shader_compile`, drawing
  the base variant until the twin's `COMPLETION_STATUS` is true); drop the test compile/link when the real build reports
  its own errors; warm the patched model programs (vehicle, vehicle_multiuv, vehicle_norandom_multiuv, ...) during the
  loading screen.

## 2. Dense 20-35 ms frames: no headroom with the Enhancements set, bake bursts overflow

- With the tab file both threads are nearly full every frame: render submit p50 6.1 ms and game step p50 7.1 ms against a
  4.17 ms (240) / 6.06 ms (165 Hz) budget; GPU 1-2 ms. Late frames: 52 % (drive) / 91 % (spin). The spikes are the frames
  with bake bursts on top: game step and render submit grow ~0.3 ms per bake (spin: 1 bake 5.1 / 4.6 ms, 8+ bakes 9.6 / 9.0,
  20+ 11.0 / 10.7). The drive's spikes cluster from the turn into Rosewood (+14 s) with `create`/`object`/`cutaway`/`trees`
  bakes, as in `docs/plan-drive-game-thread.md`.
- With the defaults the same bursts are absorbed: game step flat 6.4-7.8 ms (it includes the VRR-cap wait), render submit
  p50 1.4 ms, 5 frames > 20 ms.
- Render thread with the tab file (asprof cpu, inclusive): PixelLight 6.2 %, Sway 3.0 %, ShadowAtlas 2.6 %, ChunkAo 2.3 %,
  Hdr 1.2 %, CloudShadow 1.2 %, then < 1 % each, ~20 % in all; the rest is the stock draw path inside the NVIDIA driver
  (`glDrawRangeElements` 16 %, `glUseProgram` 7 %, `glPush/PopAttrib` in `IsoObjectModelDrawer`). Per frame it is ~6.8 ms
  with the tab file, ~5 ms without HDR, ~2.5 ms with the defaults.
- HDR moves the wall: on, the render thread is the limit (the game thread waits 10 % in `InputLatch.gate`); off, the game
  thread is (94 %, 105 frames > 20 ms). HDR picks the native Wayland window, where NVIDIA has no GL worker thread (the driver
  work stays on our render thread); why the game thread costs more per frame without HDR is open.
- Game thread with the tab file: render phase 57-64 % vs 37 % with the defaults; `FBORenderCell.calculateOccludingSquares`
  ~0.07 → ~0.5 ms a frame (cutawayFast re-tests every dirty level, and the extra re-bakes dirty more of them),
  `PixelLight.beforeComposite` 4 %, `ChunkAo.flush` 2 %.
- The peer's cost sweep (`docs/findings-enhancement-costs-2026-10-08.md`: +0.5 ms for everything) measured a still camera,
  uncapped, HDR off: it does not see the bake-time cost of the features while chunks stream, which is where these spikes come from.
- `pzopt-hdr-light` (`HdrLight.build` → `HdrExposure.LocalAmbient.prepare` / `readLight`) runs a full core all the time: not on
  the frame path, but power and a core taken from the frame workers.

## 3. The straight highway (`drive-120`, first 600 tiles, 24 s)

| Run | Settings | fps | p99 | p99.9 | max | frames > 20 ms | GPU | game / render thread |
|---|---|---|---|---|---|---|---|---|
| `spk-hwy` | tab file, asprof 2 ms + schedmon | 185 | 14.9 | 23.4 | 32.1 | 9 | 81 % | 72 / 91 % |
| `spk-hwy-def` | defaults | 157 (at the VRR cap) | 7.7 | 13.4 | 64.1 | 2 | 40 % | 36 / 29 % |

Both drives valid (599 / 600 tiles, no impacts; Jev's `invalid` on `spk-hwy` is the headroom question at 0.24 confidence).
Far calmer than town: with the defaults the drive sits on the cap (jitter 0.3 ms) except one frame.

- **The worst frame of both runs is ours: `CutawayMask.read`** (treeCutawayReach, 2026-10-03). The first frame a cutaway
  stencil exists (42.21 counts driving as aiming, so ~1.5 s into every drive) `FBORenderCell.pzoptReachBoxes` →
  `CutawayMask.frameBoxes` decodes `media/mask_transparency_player.png` with ImageIO and scans its 1024 x 1024 texels with
  `getRGB` on the game thread, inside the bake preparation: console `cutaway mask ...` at route +1495 ms (defaults: the
  64 ms frame), +1548 ms (tab: the 32 ms frame), +1665 ms on the Rosewood drive (its 37 ms frame). Once per session (and
  again if a mod changes the mask size). Fix: read it at boot / world load on a worker (or scan the alpha raster in bulk).
- At +5.0 s a chunk hand-off loads its animals (`AnimalPopulationManager.addChunkToWorld` → `AnimalCell.load` →
  `IsoAnimal.init` → `AnimationSet.Load` → `AnimState.Parse`): an animal type's animation set parsed from XML on the game
  thread the first time it appears, ~15 ms of a 28 ms frame. Stock behaviour; the boot-time anim-set parse does not cover
  the animal sets.
- The first 0.3 s after the start are 18-24 ms frames on the tab file (the grid shifting as the car leaves, `LoadRight` →
  `IsoChunk.removeFromWorld`, plus create / object bakes); the rest are single 16-25 ms frames with bake bursts on top of
  a render thread at 91 %, the same pattern as in town but rarer (fewer bakes per tile on the highway).

## 4. Uncapped: the Enhancements set vs the defaults (same three routes)

`--prop uncappedFps=true --prop vrr=off`, no profiler; "tab" = the desktop's options file, "def" = an empty one (every
Enhancement off). All drives valid (Jev's `invalid` on `unc-rw-tab` = the headroom question at 0.20).

| Route | Settings | fps | p50 | p99 | p99.9 | max | frames > 20 ms | 1%-low | GPU (smi) | game / render thread |
|---|---|---|---|---|---|---|---|---|---|---|
| Highway (600 tiles) | tab | 202 | 4.2 | 14.4 | 21.2 | 28.2 | 10 | 69 | 89 % | 69 / 88 % |
| | def | **569** | 1.5 | 4.8 | 8.8 | 60.4 | 2 | 209 | 96 % | 70 / 54 % |
| Rosewood (487 tiles) | tab | 147 | 4.7 | 23.1 | 31.6 | 47.7 | 112 | 43 | 73 % | 77 / 91 % |
| | def | **457** | 1.4 | 11.2 | 17.2 | 60.5 | 6 | 89 | 89 % | 87 / 64 % |
| Spin (15 s) | tab | 121 | 7.1 | 21.5 | 29.7 | 41.3 | 45 | 47 | 68 % | 87 / 88 % |
| | def | **349** | 2.3 | 9.6 | 16.1 | 64.3 | 5 | 104 | 88 % | 91 / 52 % |

- The Enhancements set costs ~2.9x the frame rate while moving (+3.2 ms highway, +4.6 Rosewood, +5.4 spin a frame), against
  ~+0.5 ms standing still (`findings-enhancement-costs-2026-10-08.md`): the cost is in streaming (bakes) and in the render
  thread, which is the wall with it (88-91 %) while the defaults are GPU-bound (88-96 %). HDR is not the cause (capped spin:
  130 fps with HDR, 110 without, §2).
- The defaults are smooth except their single worst frame, ~60 ms, which is `CutawayMask.read` in both drives (console
  stamp +1466 / +1525 ms vs the frames at +1.48 / +1.53 s) and very likely in the spin (its read is at console frame 5412,
  ~9 s in, where the walk first gets a cutaway; the load trace that stamps it ends 3 s into the route).

## 5. Re-run after a clean restart, default CachyOS scheduler (2026-10-09 01:59-02:06)

Same build (install of 10-09 00:39), same args (`harness/spikes/rerun.sh clean`), after a reboot with the sched_ext
scheduler off (`/sys/kernel/sched_ext/state` = disabled, `scx_loader` idle: the kernel's own scheduler). Swap 576 KiB
used, load 0.07, no other game. Still 165 Hz with VRR; irrelevant here (uncapped, `vrr=off`). All six routes complete,
overrides loaded, Jev `achieved` on each.

| Route | Settings | fps before → after | p99 ms | p99.9 ms | max ms | frames > 20 ms | machine CPU | GPU |
|---|---|---|---|---|---|---|---|---|
| Highway | tab | 202 → **220** | 14.4 → 13.4 | 21.2 → 19.3 | 28.2 → 35.4 | 10 → 5 | 48 → 37 % | 89 → 94 % |
| | def | 569 → **590** | 4.8 → 4.6 | 8.8 → 8.1 | 60.4 → 52.0 | 2 → 1 | 41 → 26 % | 96 → 99 % |
| Rosewood | tab | 147 → **173** | 23.1 → 18.8 | 31.6 → 24.4 | 47.7 → 33.2 | 112 → 26 | 50 → 29 % | 73 → 83 % |
| | def | 457 → **499** | 11.2 → 9.0 | 17.2 → 18.2 | 60.5 → 51.3 | 6 → 10 | 44 → 23 % | 89 → 98 % |
| Spin | tab | 121 → **144** | 21.5 → 18.1 | 29.7 → 25.6 | 41.3 → 38.2 | 45 → 20 | 47 → 31 % | 68 → 75 % |
| | def | 349 → **399** | 9.6 → 7.8 | 16.1 → 14.3 | 64.3 → 60.1 | 5 → 2 | 45 → 25 % | 88 → 96 % |

- The machine was the drag: +4-14 % with the defaults, +9-18 % with the Enhancements, the dense 20 ms frames of the
  Enhancements set cut 2-4x (Rosewood 112 → 26), the CPU share the other processes took is gone (machine CPU ~45 → ~25 %
  in the defaults runs). The defaults are now GPU-bound at 96-99 %: the objective's "hardware at the max" holds there.
- Not changed: each defaults run's single worst frame (52 / 51 / 60 ms) is still the game step at the same spot (+1.46 s,
  +1.55 s, +9.24 s), i.e. `CutawayMask.read` (§4, finding 2, not fixed yet). The Enhancements set is still 2.7x the frame
  rate while moving (render thread 86 % of a core, GPU 75-94 %): findings 1 and 3 stand.
- The spin with the defaults is 399, not 500: GPU-bound (96 %), so not a CPU / scheduler limit. The ~470-510 fps spin
  figures in CLAUDE.md are from 09-20 / 09-22 builds (42.20) and are not this build's baseline.

Runs: `unc-{hwy,rw,spin}-{tab,def}-clean-20261009-0159..0206` (queue jobs 10026-10031).

## 6. The one-off hitches fixed, and what the Visuals cost while moving (2026-10-09 02:10-02:50)

All on the uncapped Rosewood drive of §4 (`harness/spikes/ablate.sh <suffix> <session> <variant>...`, table with
`harness/spikes/summary.sh <label>...`), build of 02:43 (working tree + the fixes below).

### Fixes

- **`CutawayMask`** decodes both mask PNGs on a worker thread at the first game state change (the main menu,
  `NoLoadingScreen.afterStateUpdate` -> `CutawayMask.prefetch`), alpha raster read row by row; until the worker is done
  a mask counts as its whole square (conservative, nothing cached). Console: `cutaway mask ... marked texels` at f:0. The
  50-64 ms game-step frame ~1.5 s into every drive is gone (no frame > 40 ms in the route of `abl-rw-cold-on-a4`).
- **`shaderWarmup`** (new key, default on, the tab's "World load" section beside `shaderCache`): programs that were built on their first use mid-play
  are built ahead.
  - every model shader the model scripts name (distinct shader / static pairs, listed at the main menu, 9 on vanilla) is
    built on the render thread during the world load, one queued job each (`ModelShaders.warmup`; the loader's own round
    trips interleave). Console `model shaders: warmed 9 during the world load, X s`: 2.4-2.5 s with an empty driver cache,
    0.03-0.43 s with a warm one; world ready 12-13 s both ways cold, 9 s warm (unchanged). The 428 ms `vehicle` link of
    the first burnt car (§1) can no longer happen in play.
  - foliage sway's twin of each precompiled pixelLight variant, one a frame right after the variants (f:10-15) instead
    of on its first draw; `Sway.patchShader` no longer test-compiles and test-links a twin before the real build (the
    real build fails the same way: the game marks the program uncompiled and `twinFor` never uses it). Empty cache:
    one twin still built mid-play without the warm-up = a 123 ms frame at route +0.2 s (`abl-rw-cold-off-a1`).
  - god rays' direct light-volume program (`GodRays.Gl.warm`, was linked in a 46 ms frame at +16.8 s) and wet blood's
    two programs (`BloodWet.Gpu.warm`, a 62 ms frame at +18.0 s, both found with asprof in `abl-rw-cold-on-a2`) are
    queued on the render thread when the world is entered; wet blood waits for the reflections patch when reflections
    are on (it bakes their mode in).
  - Result, empty driver shader cache (`--env __GL_SHADER_DISK_CACHE_PATH=<empty dir>`, the first launch after an
    update): frames > 40 ms in the route 3 -> **0** (`abl-rw-cold-on-a2` vs `-a4`). What is left: the twins' ~120 ms
    frames 10-15 after the world appears with a cold cache (next step: build the variants and twins in the loading screen).

### What the Visuals cost while moving

| Run (Rosewood drive, uncapped) | fps | p99 | frames > 20 ms | GPU | game / render thread |
|---|---|---|---|---|---|
| defaults (`def-a1`) | 504 | 8.8 | 6 | 97 % | 82 / 62 % |
| tab file (`tab-a1`) | 172 | 20.1 | 45 | 81 % | 73 / 91 % |
| tab file's Visuals keys only (`enh-a1`, `enh-a3`) | 178 / 173 | 17.6 / 18.8 | 18 / 28 | 94 / 90 % | 63-68 / 92-94 % |
| tab file without its Visuals keys (`opt-a1`) | 402 | 13.8 | 3 | 78 % | 85 / 56 % |

- The Visuals are the moving cost (+3.7 ms a frame). The tab file's other non-default keys cost 504 -> 402 uncapped, almost
  all `reflexSleep` (`LowLatency.beforeInputSample` 14 % + `InputLatch.gate` 8 % of the game thread): the input-latency
  sleep trades throughput by design; capped at 240 it is not a moving cost.
- Leave-one-out from the Visuals-only file (`abl-rw-no-*-a3`; the two baselines differ by 3 %): **pixelLight 229 fps (-1.3 ms
  a frame), sun shadows 218 (-1.1), HDR 212 (-1.0; render thread 94 -> 77 %)**, foliage sway 191 (-0.5), sprite filter 187
  (-0.35), car glass 186 (-0.3); AO, god rays, reflections, mirrors, wet blood, grading + darkness, outlines, relief 176-180
  (within the noise).
- Why: with the defaults the render thread needs 1.3 ms a frame and the GPU 2.4 (GPU-bound); with the Visuals the render
  thread needs 6.1 ms and the GPU timeline follows its submission (`gpuSections`, `abl-rw-{enh,def}-a4p`, both slowed by the
  profilers to 148 / 368 fps). Render-thread time per frame, Visuals vs defaults: chunk bakes 1443 vs 86 us (7.6 vs 2.2
  bakes a frame, 190 vs 40 us each), chunk composite 1225 vs 142, translucent tiles 1379 vs 386 (193 vs 56 us per level),
  characters / vehicles 367 vs 64, items 287 vs 77. Render-thread samples (asprof cpu 2 ms): 53 % inside the NVIDIA driver
  (`libnvidia-eglcore`: HDR's native Wayland window has no NVIDIA GL worker thread, so the driver's work stays on ours),
  `glDrawRangeElements` 18 %, `glUseProgram` 7 % (the composite switches between pixelLight variants and their sway twins
  per chunk), `TextureID.assignFilteringFlags` 3.4 %; pzopt's own code ~20 %: PixelLight 6.1 %, ChunkAo 4.6 % (half of it
  `ChunkAo$Gl.uploadFar`, the sun's far-field column heights uploaded from the render thread per baked texture), ShadowAtlas
  4.0 %, Sway 2.5 %, CloudShadow 1.3 %, Hdr ~2 %.
- **pixelLight doubles the lighting re-bakes while moving**: lighting-only bakes 303/s (defaults) -> 615/s (Visuals),
  245/s with pixelLight off (`pzopt-bakes.out`; trees 34 -> 72/s). In `LightingJNI` (pixelLight active) every change of a
  square's visibility bits (seen / canSee / couldSee, which set object alphas in the bake) invalidates its chunk level at
  once, outside `LightDirt`'s classification and budgets, and the vision changes on hundreds of squares a second while
  driving. Each of those bakes then costs ~5x a stock one on the render thread (ChunkAo / sun kernel, mips, the patched
  programs).
- Next steps, by expected gain: (1) re-bake on a visibility change only for the bits / squares whose bake can change (a
  floor-only square, couldSee flicker) or route it through `LightDirt`'s spread; (2) `ChunkAo` far-field upload off the
  render thread (worker + PBO); (3) fewer program switches in the composite (sort chunk draws by program, or one program
  with the light kinds as uniforms where the register cost allows); (4) HDR without losing NVIDIA's threaded GL (an X11
  path for HDR, or pace the driver work); (5) build pixelLight's variants and sway twins in the loading screen.

Runs: `abl-rw-*-a1` (02:16-02:23), `abl-rw-cold-on-a2` (asprof), `abl-rw-*-a3` (leave-one-out, 02:25-02:42),
`abl-rw-cold-on-a4`, `abl-rw-{enh,def}-a4p` (asprof + gpuSections), queue jobs 10033-10058.

## 7. The 300 fps loop (2026-10-09 03:15-, in progress)

Goal (maintainer): the tab file without `reflexSleep` / VRR at >= 300 fps mean on the uncapped Rosewood drive
(`harness/spikes/ablate.sh <suffix> <session> goal`, = tab file + `reflexSleep=false vrrCap=false`). Every change is a
key (default off while measured), picture-checked with `harness/spikes/shotcheck.sh <label> --prop ...` against the
stock-path still shot (Rosewood house, no wind / sway; same-path pair 0.015 % px > 8), per-section render-thread issue
time with `--prop gpuSections=true` + `harness/spikes/sections.py`.

Why the render thread is the wall: HDR runs the game in a native Wayland window (EGL). NVIDIA's EGL has no threaded
optimizations (`__GL_THREADED_OPTIMIZATIONS=1` reached the process: no driver worker thread; under GLX the driver's
worker takes ~48 % of a core next to the render thread's 56 %). SDR in the same Wayland window costs the same as HDR
(world issue 5.7 ms vs 3.8 ms in GLX), so every GL call counts. No Vulkan in the game's LWJGL, KWin's HDR protocols are
Wayland-surface only: HDR stays on EGL; the work is cutting GL calls.

| Step (key) | what | fps (2 runs) |
|---|---|---|
| baseline `goal-g0` | | 166 / 162 |
| `tileVertexDepth` | tile-depth programs read (front, far) from vertex attribute 4 (RingBuffer.add writes it in the empty tex2 slot; new override SpriteRenderer), one start for consecutive tiles: uniform uploads 4.4M -> 0.2M / 10 s | (render issue -0.22 ms) |
| + `tileStateFold` | shader ends and self-cancelling depth mask / func entries between merged tiles dropped (tile starts 2.83M -> 0.82M / 10 s) | 168 / 174 |
| + `texParamCache` | TextureID.assignFilteringFlags sends min / mag only on change (6.9M glTexParameteri skipped / 10 s) | 182 / 190 |
| + `pplRemap` + `pplVisRebakeFilter` | pixelLight's variant bound in place of the full program; a visibility-bit change re-bakes only squares whose bake reads the bits (85 % skipped; lighting bakes 240 -> 143 / s) | 194 / 207 (198 / 207 without MangoHud) |

| + `sunShadowStaticVehicles` | a vehicle that has not moved since its shadow-atlas tile was drawn (same sun step) keeps the tile (43k redraws skipped / run; GPU world 4.44 -> 3.99 ms, = no meshes at all) | 218 / 224 |

All measured without MangoHud from here on (`ABL_NOMH=1`): its EGL hook costs ~0.5 ms of render thread a frame in the
Wayland window (none in GLX); players do not run it. Baseline without MangoHud: 167 / 166.

**The GPU is the wall now.** With the six keys and HDR off (GLX: render thread issue 2.96 ms, GPU world 3.52 ms) the drive
runs 241 fps GPU-bound (gpu 94 %, game / render thread 77 / 76 %); HDR adds ~0.25 ms of GPU (screen pass + bloom) and
the EGL driver cost on the render thread. GPU leave-one-out (`abl-rw-*-g1`, HDR, keys on, before the vehicle skip; world
4.44 ms): sun shadows -1.02 ms (shadow meshes -0.53, of which parked cars ~0.46 now saved; entity shadows -0.46; clouds
-0.07; far field -0.03), pixelLight -0.38, foliage sway -0.34, reflections -0.22, sprite filter -0.20 (composite), god
rays -0.17, relief -0.07, AO / grading / outlines ~0. The composite is 1.4 ms of GPU (0.7 stock).

Dead ends: `__GL_THREADED_OPTIMIZATIONS=1` (NVIDIA's EGL never starts the worker), `tileRecordVisuals` (the translucent
recorder with mirrors / sway / pixelLight: implemented, no fps change, the game thread is not the wall; its draw-list check
cannot compare against the folded stream yet), `devCompositeEmptySkip` (empty chunk texels discarded first: -50 us, they
were cheap already).

**Decision (maintainer, 05:35): keep the picture exact.** 300 is out of reach without less GPU work; the loop continued with
lossless cuts only.

- `pplVisRebakeFilter` is **wrong** and stays off: the bake also takes each square's fog-of-war fade (`darkMulti`), which
  follows the visibility bits; a skipped re-bake left a whole chunk at its old brightness (`harness/spikes/shotpair.sh`
  day pairs: the filter alone 0.4-1.4 % px > 32, the other keys 0 %; single-bit masks rarely skip: a square entering the
  view flips canSee and couldSee together). Without it the keys give 200 fps instead of 221.
- `ChunkAo.packFar`: the far field interleaved on the game thread, one bulk upload on the render thread (~3 % of it).
- **Defaults on since 10:10** (picture pairs 0 % px > 32): `tileVertexDepth`, `tileStateFold`, `texParamCache`, `pplRemap`,
  `sunShadowStaticVehicles` (plus `shaderWarmup`). The tab file as it is (`goal`, no extra props, no MangoHud): **208 / 221
  fps** (baseline 167 / 166, +29 %). Lossless ceiling in HDR ~227 (GPU 4.15 ms + HDR 0.25 ms).

Pitfalls: a first fold left the next quad unstamped under a merged start (pzoptVD = 1 with a (0, 0) pair: dark slabs);
now every draw recorded after a merged start is stamped (harmless under other programs) and a separate "bound" flag gates
skipping / folding. zsh: an unquoted `$P` of several `--prop` is one word (use the scripts).

## 8. Every Enhancement on its own (2026-10-09 10:20-11:05)

Uncapped Rosewood drive, build of 10:10 (the five lossless keys on by default), no MangoHud, two runs each (suffixes
`e1`, `e2`, interleaved). Each variant = the defaults + one Enhancement exactly as the desktop's tab file sets it
(`~/src/pzo-work/feat-<name>.ini`, cut from the tab file; `harness/spikes/ablate.sh <sfx> <session> feat-<name>`);
dependent features on top of their prerequisite: `torch` = pixelLight + the torch light source, `pplsun` = pixelLight +
sun shadows, `relief` = pplsun + relief. Table: `harness/spikes/feattable.py e1 e2` (+ms = frame time added to the
defaults; loads from the in-game overlay).

```
variant             fps           runs     ms    +ms     +%    p99  game  rend   gpu
feat-blood        533.3          [533]   1.88  -0.01     -1    7.9   82%   37%   93%
def               530.2     [522, 539]   1.89  +0.00     +0    7.8   83%   38%   93%
feat-grade        523.5     [528, 519]   1.91  +0.02     +1    8.3   85%   40%   92%
feat-godrays      516.2     [508, 524]   1.94  +0.05     +3    8.2   85%   40%   92%
feat-dark         514.0     [515, 513]   1.95  +0.06     +3    8.2   85%   40%   92%
feat-outlines     514.0     [516, 512]   1.95  +0.06     +3    8.4   85%   40%   92%
feat-sprite       512.2     [512, 513]   1.95  +0.07     +4    8.3   81%   38%   94%
feat-ssr          496.4     [494, 499]   2.01  +0.13     +7    8.8   84%   67%   92%
feat-mirrors      496.1     [498, 495]   2.02  +0.13     +7    9.2   86%   38%   92%
feat-ao           490.1     [491, 489]   2.04  +0.15     +8    9.2   82%   42%   93%
feat-sway         470.2     [470, 471]   2.13  +0.24    +13    8.8   82%   39%   94%
feat-carglass     445.2     [440, 451]   2.25  +0.36    +19    8.4   77%   77%   94%
feat-ppl          417.1     [430, 404]   2.40  +0.51    +27   10.6   88%   39%   90%
feat-torch        416.8     [426, 408]   2.40  +0.51    +27   10.2   88%   40%   90%
feat-hdr          380.1     [384, 376]   2.63  +0.74    +39    9.3   72%   90%   92%
feat-sun          361.5     [381, 342]   2.77  +0.88    +47   11.8   74%   39%   94%
feat-pplsun       298.6     [293, 304]   3.35  +1.46    +78   12.2   77%   67%   94%
feat-relief       297.6     [292, 303]   3.36  +1.47    +78   13.1   76%   66%   92%
feat-all          232.8     [236, 230]   4.30  +2.41   +128   14.6   72%   88%   94%
```

- The defaults run GPU-bound at 530 fps (GPU 93 %). What costs: **sun shadows +0.88 ms (+47 %)**, **HDR +0.74 ms (+39 %;
  the render thread goes to 90 %: the native Wayland window, no NVIDIA driver thread)**, **pixelLight +0.51 ms (+27 %)**,
  pixelLight + sun shadows together +1.46 ms, **car glass +0.36 ms (+19 %; render thread 77 %)**, foliage sway +0.24,
  AO +0.15, reflections +0.13 (render thread 67 %), mirrors +0.13, sprite filter +0.07, outlines / darkness floor / god
  rays +0.05-0.06, colour grading +0.02. Free (within the run spread): wet blood, relief on top of pixelLight + sun,
  pixelLight's torch light source.
- The whole set: +2.41 ms (233 fps, 236 / 230); the single costs add to ~3.5 ms because they overlap on the same walls (GPU,
  render thread). Run spread: pixelLight 430 / 404, sun shadows 381 / 342 (the busiest variants vary most).

## 9. The four costly Enhancements, lossless only (2026-10-09 11:00-11:40)

- **Zink (Mesa 26.2.4, `--renderer zink --env mesa_glthread=true`) does not help HDR**: no FP16 back buffer on its Wayland
  EGL (`hdr: no FP16 back buffer (component type 0x8c17, red 10 bits)`: the HDR path cannot output its picture), and it is
  slower everywhere: defaults 395 fps (NVIDIA 530), HDR alone 302 (380), the tab file 164 with p99 54 ms (214-233).
- **Car glass** (+0.36 ms alone, render thread 38 -> 77 %): 37 % of the render thread was one `glGetInteger` per frame in
  `CarGlass.liveSetup` (NVIDIA's threaded GLX driver drains its queue for it). `carGlassNoGet` (default on): the framebuffer
  from `TextureFBO.lastID`, the viewport = the screen (`Core.width` x `Core.height`; not the 8192 x 4096 texture, not the
  12800 x 5400 zoomed offscreen size: two wrong first tries, caught by `devCarGlassGetCheck`), one player, no render scale;
  else the queries. Check: 0 mismatches in 600 compares (car glass alone in GLX, the whole set in HDR). Car glass alone:
  render thread 77 -> 37 %, fps unchanged (446 vs 446: its remaining cost is GPU); the tab file unchanged too (HDR's EGL
  has no driver thread, a glGet is cheap there).
- Where the others go (GPU sections, each feature alone, instrumented): **sun shadows +0.71 ms GPU** = composite +320 us
  (cloud shadows and the sun share, already early-out on padding, cloud-free and unshaded pixels), bakes +114 us (2.58 vs
  1.92 a frame: sun-step re-shades), characters / cars +117 us (entity shadows), ~160 us unsectioned (caster pass, atlas,
  far field); **HDR +0.47 ms GPU** = screen pass +148, bloom +98, car bodies' glint output +100, the glint-only water /
  puddle redraw ~70, plus its CPU cost: the EGL driver's work on the render thread (HDR's own code is 5 % of it);
  **pixelLight +0.31 ms GPU** = bakes +149 us (4.0 vs 1.92 a frame: its visibility re-bakes, already held and spread, and
  not removable exactly: the bake reads the fog-of-war fade, §7) + composite +115 us. None of these has idle overhead left
  to remove without changing pixels; they are the features' own shading.

## 10. The same per-Enhancement table on the full drives, and the laptops (2026-10-09 12:00-13:40)

Same build and method as §8 (the defaults + one Enhancement as the tab file sets it, 2 runs each, uncapped, no MangoHud,
GLX unless HDR, all 120 runs judged valid drives), on three routes: the short Rosewood path of §7-§9 (`e1/e2`), the full
`drive-120` highway (1,200 tiles of KY-60, `abl-hw-*-f1/f2`) and the full `drive-120-south` Rosewood drive (KY-60 then
North Main St + Jacks Lane, `abl-rs-*-f1/f2`). Frame time each adds to the defaults:

| Enhancement | short Rosewood | full Rosewood | highway |
|---|---|---|---|
| defaults (fps) | 530 | 599 | 633 |
| sun shadows + pixelLight (+ relief, + torch: ~0) | +1.46 ms | +0.94 ms | +0.70 ms |
| sun shadows | +0.88 | +0.59 | +0.33 |
| HDR | +0.74 | +0.57 | +0.33 |
| pixelLight (torch alone ~ the same) | +0.51 | +0.28 | +0.20 |
| car glass | +0.36 | +0.23 | +0.09 |
| foliage sway | +0.24 | +0.14 | +0.20 |
| ambient occlusion | +0.15 | +0.09 | +0.08 |
| reflections / mirrors | +0.13 / +0.13 | +0.06 / +0.07 | +0.01 / +0.01 |
| sprite filter, outlines, darkness, god rays, grading, blood | <= 0.07 | <= 0.04 | ~0 (noise) |
| all of them (fps) | 233 (+2.41 ms) | 266 (+2.09) | 280 (+2.00) |
| the tab file, `goal` (fps) | - | 239 | 264 |

- Same order on every route; town costs more than the highway (more walls, characters and cars on screen, more bakes):
  pixelLight and car glass cost twice to four times as much in town, sway is the one that costs as much on the open
  highway (trees and grass fill the roadside). The short Rosewood path is the worst case of the three: all of it is in town.
- The whole set costs ~2.0-2.4 ms on every route, so the full drives land at 266-280 fps with every Enhancement and
  239-264 with the tab file (its other settings, not measured apart here, cost the last 0.2-0.4 ms); the GPU is at
  91-96 % in every row: the remaining cost is GPU shading, as §9 found.

**Laptops** (`harness/spikes/laptopab.sh`, the loop's keys off vs on, the tab file's Visuals as props on an empty options file):
- **Mac (M1 Pro, GL 4.1 core)**: short Rosewood drive 45.8 / 47.7 fps -> 51.6 / 49.8 (+8 %), p99 better, all four drives
  valid. Tile vertex depth / state folding were off on the Mac then (`TileBatch.ON`; on since the next release, below), so this is the texture-parameter
  cache, pplRemap, the static-vehicle shadow skip, car glass without `glGet` and the shader warm-up. 0 shaders failed on
  either side. Picture: the first pairs differed by 1.7 % of pixels > 32 levels, but in both directions between runs:
  cloud shadows drift in real time and are never the same in two launches. With `--prop cloudShadows=false` (`m3`) the
  interior matches; left is a smooth 1-2 level outdoor field (weather / ambient drift between launches), no edge or depth
  difference.
- **Tile vertex depth on the Mac (4.1 core), `devTileVertexDepthMac=true`** (2026-10-09 16:40-17:15, every other key at its
  default, runs `mac-lab-mac-*-vd1..3`): batching active (tile shader starts in a drive ~300k -> ~65k), 79 programs linked,
  0 failed, the same console error lines both sides. Pictures (two pairs, cloud shadows off): off vs on differs no more than
  off vs off or on vs on (> 32 levels: 0.0007-0.28 % cross, 0.13-0.22 % same side; the window-frame lines and the wall corner
  differ between launches either way). Drives, 5 per side, alternating: off 64.4 / 43.5 / 47.0 / 44.2 / 53.8 fps (median 47.0,
  p99 median 61.9 ms), on 54.5 / 50.3 / 51.9 / 55.7 / 55.8 (median 54.5, p99 median 55.6 ms): +6 % mean, steadier; the Mac's
  run-to-run spread is ~20 fps, so a sixth pair could move it. No sign the Mac needed the exclusion: on by default on the 4.1
  core context since this test (the dev switch was removed again; still off on the 2.1 context, whose GLSL has no `layout`).
- **Flip (Radeon, Mesa 26.2.4, balanced profile)**: short Rosewood drive 155.9 / 155.4 fps -> 162.3 / 156.9 / 161.7
  (+3 %), p99 21.7-22.1 ms on both sides, all five drives complete (485 tiles), no shader errors or exceptions; tile vertex
  depth and state folding are active there. (The first drive pair was lost: the flip dropped off the network at 12:42 and
  the orphaned run's clean-up removed the next run's `pzopt.properties`.)
- **One wall corner, bistable on both laptops, not the keys.** In the cloud-free pairs one spot differed (the inside of the
  north wall by the top-left window, ~0.2 % of pixels): shaded (luma ~62) or lit (~104), two states, nothing between. A
  flip bisect (all keys on but one) gave lit or shaded per key with no plausible link (car glass without `glGet` "shading" a
  house wall), and keys-off runs show both states too: Mac `m2` lit, flip `late-off1` shaded and `late-off2` lit (a shot 6 s
  later; both shots of a run always agree, so it is fixed per launch and does not converge). A per-launch choice in the
  lighting of a cut-away wall (sun shadows / pixelLight at the cutaway edge), which the keys only tip through timing; not
  investigated further here.

## Tooling

- `spike-wall.py` summed every thread named `MainThread`: native threads started from the game thread inherit the name,
  and their idle `syscall < libstdc++` stacks were 74 % of the "game thread" samples. It now keeps only stacks with Java frames.
- asprof wall sampling at 5 ms gets 5-7 samples per thread in a 25 ms frame: enough to name a 140-430 ms hitch, too few for
  the 20-35 ms frames; use `interval=2ms,wall=2ms` (`frame-causes.py`'s default advice) for those.

Runs: `unc-{hwy,rw,spin}-{tab,def}` (2026-10-09 01:15-01:23), `spk-hwy-20261009-005503`, `spk-hwy-def-20261009-005609`, `spk-drive-20261008-235836`, `spk-spin-20261008-235941`, `spk-spin-tab-20261009-000606`, `spk-spin-nohdr-20261009-000708`,
`spk-spin-def-20261009-000810`. Scripts: `harness/spikes/perframe.py` (per spike frame, the top wall stacks per thread),
`harness/spikes/table.py` (per frame > N ms: interval, game step, render submit, GPU, bakes by kind).
