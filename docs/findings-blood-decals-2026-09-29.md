# Floor blood decals: profile (2026-09-29)

What the stock floor blood splats cost, on the desktop (5120x2160, RTX 4090), with a new rig: `pzopt.BloodProbe`
(harness flags `blood_fill=N`, `blood_rate=R`, `blood_probe=true`; one row a second in `pzopt-blood.out`,
`blood_probe=` in `pzopt-bench.out`). Branch `blood-probe`, worktree `~/pzopt-wt/blood`.

## How the game draws them (B42, chunk textures on)

- `IsoChunk.floorBloodSplats` is a `BoundedQueue` of 1,000 per chunk (saved). A splat pushed out when the queue is full
  moves to `floorBloodSplatsFade` (not saved, cleared when the chunk unloads).
- The splats are baked into the chunk-level textures: `FBORenderCell.renderOneLevel` → nine `renderOneLevel_Blood` calls
  (the chunk, then its eight neighbours for the splats that reach one tile over the edge). Each call walks both lists of
  that chunk in full and filters by level, rectangle, type and the `bloodDecals` density option (`IsoChunk.renderByIndex`).
  A bake of level 1, 2, ... walks every splat of the nine chunks too and draws none.
- Each drawn splat: a colour hash, four `getVertLight` reads, then `IsoSprite.renderBloodSplat`, which sets the shader,
  turns off the depth test and sets the blend function for every single splat before its quad.
- Every `IsoChunk.addBloodSplat` on the game thread marks the chunk level `DIRTY_BLOOD` (1): a full re-bake of that level.
  This happens with **Blood Decals = None** too (the option is read at draw time only), and the eight neighbour textures
  that show the splat's overhang are not marked (a splat within a tile of a chunk edge is cut there until the neighbour
  bakes for another reason).
- Fading in chunk-texture mode: a fade splat's counter (`lockFPS × 5`, 1,200 at 240 fps) goes down by one per bake that
  draws it, so in practice they never fade out while the chunk stays loaded; the list only grows (6.8k in these runs).

## Runs

Scene: the bench save, a slow walk south (`route=S:30 speed=2`, 15 s, max zoom). `blood_fill=1000` puts 1,000 splats in
every loaded chunk 1 s into the settle (343,904 kept in 361 chunks: only solid floor takes one) = a battlefield;
`blood_rate=20` adds 20 splats a second within 6 squares of the player = fresh blood in a fight. One run each.

Stock game (`--prop enabled=false`, 240 cap; game thread bound, 92-94 % of a core):

| run | fps | p99 | p99.9 | max | >33 ms | bake mean | blood per bake | blood | blood-only re-bakes |
|---|---|---|---|---|---|---|---|---|---|
| no blood (`blood-none-stock`) | 216.4 | 17.0 | 36.5 | 71 | 6 | 0.091 ms | 6 µs | 1.1 ms/s | 0 |
| resident (`blood-fill-stock`) | 205.9 | 19.5 | 54.5 | 167 | 22 | 0.201 ms | 112 µs (max 2.3 ms) | 18.1 ms/s | 0 |
| resident + 20/s (`blood-fillrate-stock240`) | 206.5 | 20.0 | 57.0 | 130 | 20 | 0.206 ms | 127 µs (max 1.1 ms) | 24.4 ms/s | 459 × 0.26 ms = 8.0 ms/s |
| same, Blood Decals = None (`blood-fillrate-off-stock`) | 219.9 | 16.1 | 33.2 | 64 | 4 | 0.074 ms | 0 | 0 | 458 × 0.08 ms = 2.5 ms/s |

Frame times in ms from the in-game overlay. Each bake walks ~6,000-6,500 splats with resident blood (259 without).

The cost sits in the chunk-arrival seconds. In the route's big wave (t = 6 s, 700-900 level bakes in one second):

| | bakes | blood part | fps that second | worst frame |
|---|---|---|---|---|
| no blood | 112 ms | 7 ms | 158 | 50 ms |
| resident | 204 ms | 114 ms | 124 | 92 ms |
| resident + 20/s | 187 ms | 109 ms | 146 | 78 ms |
| Blood Decals = None | 87 ms | 0 | 180 | 48 ms |

The extra bake time with blood is the blood part alone. At the 1,000-per-chunk cap blood roughly doubles the game-thread
cost of a chunk bake, and so the length of every chunk-arrival hitch.

Optimized build (defaults, own empty `-Dpzopt.userOptionsFile`, uncapped, `uiRenderOffscreen=true`; GPU bound 94-96 %):

| run | fps | p99 | p99.9 | max | blood | blood-only re-bakes |
|---|---|---|---|---|---|---|
| no blood (`blood-none-opt`) | 610.1 | 3.7 | 8.6 | 34.4 | 0.4 ms/s | 0 |
| resident + 20/s (`blood-fillrate-opt2`) | 609.2 | 3.8 | 10.5 | 35.9 | 12.5 ms/s (122 µs a bake, max 1.1 ms) | 464 × 0.28 ms = 8.7 ms/s |

The bake scheduler spreads the waves (≤ 250 bakes a second), so the worst second carries 34 ms of blood instead of 114,
and the GPU is the wall: no fps change, p99.9 +1.9 ms (one run each). The first optimized run (`blood-fillrate-opt`,
118 fps, render thread 94 %) picked up the desktop's own Options-tab file (enhancements on) and is discarded.

## What was done (branch `blood-probe`, same day)

Every idea of the list, one at a time, each profiled (desktop, 15 s walk, max zoom, uncapped, own empty options file,
`gpuSections=true`; "fight" = 1,000 resident splats per chunk + 20 new a second; "realistic" = the 20 a second alone):

| step (keys) | blood part per bake | blood-only re-bakes | blood game-thread total | worst second | bake GPU / render thread per frame |
|---|---|---|---|---|---|
| stock path (`bloodBake=off bloodAppend=false`) | 122-131 µs | 457-465 × 0.27-0.29 ms | 20.8-22.1 ms/s | 32.9 ms | 75.9 / 74-79 µs |
| `bloodBake=cpu` (cache + sprites, no per-splat state) | 65.8 µs | 466 × 0.22 ms | 13.6 ms/s | 15.3 ms | 72.1 / 43.4 µs |
| `bloodBake=gpu` (instanced from the per-chunk cache) | 34.5 µs | 465 × 0.17 ms | 8.7 ms/s | 6.4 ms | 68.4 / 17.2 µs |
| `gpu` + `bloodAppend` (depth-tested append, road squares) | 22.9 µs | 30 × 0.22 ms | 2.1 ms/s | 5.5 ms | 53.1 / 16.0 µs |

Other bakes got cheaper too (0.19 → 0.09 ms: the chunk's own blood was part of every bake). Picture: `cpu` and `gpu`
pixel-identical to stock (runs `bp-*`, `bp2-*`, `bpx-*`: 14-99 pixels > 8 of 11 M, animation), also with pixelLight,
ambient occlusion and the sprite filter on; append identical after two fixes (`bpw-*`: 167-203 pixels):

- **Grass and other flat things drawn after the blood** (`isBush` / `canBeRemoved` / `attachedFloor` sprites - a
  MinusFloor object unless flattened -, bodies, items) sit at the floor's depth: the append cannot go under them. A splat
  whose sprite covers such a square re-bakes its level as stock, `bloodRebakeCoalesceMs` (250) later so the rest of the
  spray shares it (realistic fight 413 → 199 re-bakes; `bloodAppendVegetation=true` appends there too, blood over the grass).
  On the bench save's roadside most splats land near grass: 268 of 292 in the realistic run.
- Floor-pass objects (`solidfloor` / `renderLayer 1` sprites: floors, road markings, rugs) are drawn before the blood and
  do not stop an append (they did at first: every road splat fell back); parity with the final rule `bpu-*`: 127 pixels.
- **Ambient occlusion / sun shadows** multiply a texture after its bake, sometimes frames later: with them on the append
  would miss or double that multiply, so it falls back (`bpz-*`: 53 pixels).
- Density option hides the splat: nothing re-bakes (stock re-baked to draw nothing). Neighbour textures within a tile of
  the edge get the splat too (stock left it cut there). `bloodSettleSec` (30) re-bakes an appended level once later.
- `bloodFadeFix` (off): drops the splats pushed out at the cap instead of drawing them forever.

Final, all defaults (`pf5-*` / `pf6-*`, `rf5-*` / `rf6-*`, game-thread samples containing blood code, the rig's own
probe excluded): fight 1.64 % → 0.67 % (1.26 % with wet blood), realistic 0.26-0.54 %, i.e. in the noise.

## Wet blood (`bloodWet`, Enhancements tab, off by default)

Fresh splats (`bloodWetMinutes`, 120) drawn once more each frame as a thin film over the floor, like the puddles
(`pzopt.BloodWet`): normal from the splat's alpha as a height field (flat pools, a meniscus at the rims, drying from the
edges in), GGX sheen of the sun (leaned toward the view's mirror like the water glint) and of the square's light,
world-anchored micro-facet sparkles, the sky at the film's Fresnel, and with reflections on the scene through pzopt.Ssr
(the wet level-0 squares join the reflection map as puddle squares: zombies standing in a pool are mirrored in it); with
HDR output the glints go above white through HdrGlint's glint-only pass (checked with Hdr debug view 5: glints on the
pools, dropped where a character stands over them). Placement from the baked splat's own screen position and the floor
depth, the puddles' projection (`bw2-v1`: the coverage lines up with the baked splats).

One film per pixel: the main pass writes its (floor - bias) depth with `GL_LESS`, so overlapping splats do not blend the
film in again (dense pools turned pale pink before: five layers made 20 % of sky 67 %); the film darkens the blood under it
by 15 % x wetness. Card: `docs/workshop/images/44-real-blood.gif` (`harness/blood-card-gif.py`, runs `rb5-card-*`).

Cost: realistic fight (≤ 285 wet splats) 8.5-9.1 µs GPU + 3.5-4.6 µs render thread a frame; worst case (every on-screen
chunk wet, 4,096 drawn) 11.7-13.4 µs GPU + 3.8-4.4 µs render thread, `BloodWet.collect` 2.3 % of the game thread. The
set is rebuilt and uploaded only when its chunks, their visible squares (read straight from the cutaway flags), the
camera's chunk change, or every 100 ms (90-95 % of frames reuse it).

Dev: `devBloodWetView` 1 coverage / 2 reflection found / 3 sun term / 4 normal; `devBloodAppendTint` (appended splats
green); rig `blood_burst=N` + `blood_burst_at=S` (deterministic screenshots).

## Left

- Appending under grass: the append would have to draw the footprint's grass again over the splat (twice-blended edges
  at 0 < alpha < 1); not done, the coalesced re-bake stands there.
- macOS: the instanced paths need GLSL 4.00 / 3.30 core: `bloodBake=gpu` runs as `cpu`, wet blood is off.
- One run per setting; the fight tails (max frame) are within run-to-run noise at 500+ fps (GPU-bound).

## Reproduce

```
harness/queue.sh submit run --install opt ... -- --label <l> --mode bench --flag route=S:30 --flag speed=2 \
  --flag zoom=max --route-seconds 15 --option frameRate=240 --option uncappedFPS=false --prop instrument=true \
  --no-dashboard --launcher direct --flag blood_fill=1000 --flag blood_rate=20 [--option bloodDecals=0] [--prop enabled=false]
```

Optimized runs need `--vmarg -Dpzopt.userOptionsFile=<empty file>` on the desktop.
