# Sunrise / sunset: long shadows cut off, the picture flickering bright and dark (2026-10-07)

The maintainer's report: "At sunrise and sunset the shadows of trees or buildings are too long and are cut off, causing the
image to flicker between bright and dark, which looks jarring." Worktree `~/pzopt-wt/lowsun`, branch `low-sun-shadows`
from origin/master a139b676. Runs `lowsun-*` on the desktop (5120x2160, the desktop's tab file: sun shadows, HDR, pixelLight,
AO, god rays on), the Rosewood church lot (`start=8168,11502`, zoom 2), `weather=clear`, the sun 12 -> 5 deg.

## What it is

A sun shadow lives in each chunk texture's kept term (`pzopt.ChunkAo`). When the sun moves a step (1.5 deg) or its strength
a step (1/32), every loaded texture computes again, and the new term goes onto the texture's colour as new / old. Those
recomputes were a trickle, `sunComputeBudget` = 1 texture a frame, the ones on screen first. With ~60 textures on screen a
step took 60+ frames (more at a cap after missed frames, none at all on a heavy frame), and for all that time the screen
was a patchwork of chunk-shaped regions under the old and the new step (runs `lowsun-c`, `lowsun-d-base`: the frame diffs
show light parallelograms appearing tile by tile). At noon a step barely changes a shadow; near the horizon a shadow's length
goes with 1 / tan(elevation), so a 1.5 deg step at 5 deg made every long shadow a third shorter, and the dusk fade of the
strength (2 -> 10 deg) added steps of its own between them. Each wave therefore re-shaded large parts of the picture in
chunk patches every few seconds: the bright / dark flicker. In real time at ~7.7 deg (`lowsun-rt-off`): 39-61 % of the
picture changed abruptly per 10 s, up to 10 % in one frame; the released build's sun steps are visible in every run.

Things that were not it: the parked van's long shadow (the per-frame vehicle pass) is stable frame to frame while walking
(median change 0.01 luma); its only jumps came with the waves. The fan-shaped dark wedges round the player are the stock
line-of-sight darkening. HDR, the far field and the tree cards off (`lowsun-d-nohdr`, `-nofar`, `-notrees`) changed nothing.

The "cut off" ends are the reach of the marches: the far field fades out over its last quarter (24-32 squares,
`sunShadowFarSquares`), tree cards reach 3 chunks, characters' and vehicles' shadows 12 squares (`sunShadowCharacterReach`,
the last 30 % fading). A building's shadow at 5 deg is 50+ squares long. Those ends are soft and stay where they are; what
made them read as flicker was the wave moving them in patches.

## The fix (all default on)

- `sunStepSync`: a sun-step recompute writes its new term into a staging texture of its own (pooled RG8, the AO size)
  instead of the colour. Once no texture on screen waits for the step (or after `sunStepSyncMaxFrames`, 180), one
  `APPLY_STAGED` job puts every staged term onto its colour (the same ratio pass, mipmaps too) and keeps it: the whole screen
  switches in one frame. A texture that moved on meanwhile (another key, a bake with geometry, a compute of its own) is
  skipped on both threads.
- `sunStepFadeMs` (1500): with bindless kept terms (the patched chunk composite, `CloudShadow`), the applied step eases in:
  the term before the step is kept in a pooled texture with a resident handle, and the composite multiplies each pixel by
  `mix(1, old / new, rest)` (`rest` eases 1 -> 0). A step that comes while the last one still eases starts from what shows:
  the old term is blended to the displayed mix first, so nothing jumps. The colour still takes the whole step at once with
  one rounding: easing it in with partial multiplies in RGBA8 would stall on dark texels (a 0.7 % change rounds to none).
  Without bindless (macOS) the step switches in one frame.
- The shown sun: `SunShadow.dir / world / perp` (characters' and vehicles' shadows, relief, the canopy, the bare ground's
  share) now follow the step the textures show and ease with them; the kernel computes with `cDir / cWorld / cPerp`. Before,
  the per-frame shadows switched at the step while the ground waited for its wave: the van's shadow blinked darker and back.
- `sunStepLowPct` (8): near the horizon the step is at most 8 % of the elevation (0.4 deg at 5 deg, 1.5 deg above ~19 deg),
  and the dusk / dawn fade follows the step's height, so a direction step and a fade step come as one change.
- `sunStrengthFine`: the strength's hysteresis only holds a turn back (clouds drifting over a quantum), and none in the fade
  band, so the dusk fade moves one 1/32 at a time instead of jumps of two.

## Results (`harness/lowsun-flicker.py`, still camera, 16 x 16 blocks, the first 4 s of a capture skipped: settling)

| run | abrupt (% of picture / 10 s) | worst frame | patchwork waves (% / 10 s) | patchwork s / 10 s |
|---|---|---|---|---|
| sweep, released (`lowsun-d-base`) | 152.2 | 3.7 % | 134.1 | 7.36 |
| sweep, sync without the fade (`lowsun-e-fix`) | one-frame pops of up to 45 % of the picture | | | |
| sweep, fix (`lowsun-m-fix7`) | 0.6 | 0.6 % | 0 | 0 |
| sweep, sun shadows off (`lowsun-d-nosun`) | 0.0 | 0 % | 0 | 0 |
| real time, keys off (`lowsun-rt-off`, `lowsun-rt-off2`) | 39.0, 18.8 | 3.2 %, 2.9 % | 55.0, 26.8 | 1.43, 1.57 |
| real time, fix (`lowsun-rt-on7`) | 0.2 | 0.3 % | 0 | 0 |

(sweep = `--prop devSunHour=19.6 --prop devSunHourSpeed=0.03`, the sun ~6x the game's speed; real time = the game's clock
from 20:00, the sun 7.7 -> 6.4 deg, 36 s.) Jev (`--judge`): the repro `flicker_confirmed` 0.84 (test flickers 0.98, against
sun shadows off); the fix in real time `fixed` 0.91 (test flickers 0.07, the released behaviour 0.72); in the 6x sweep
`fixed` 0.51 (test flickers 0.09; the sweep's larger sun-driven swings keep the verdict close).

Cost, worst case (uncapped, the sun swept so a step is always in flight, `lowsun-cost-off` / `-on`): 322 / 343 fps mean,
p99 5.21 / 5.32 ms, p99.9 23.2 / 20.3 ms: inside the run spread. Staged terms cost one RG8 copy each, the fade two texture
reads per composited pixel for 1.5 s after a step.

Remaining by design: the shadows still move with the sun (a step every few seconds at dusk, each eased over 1.5 s), and
the far ends of very long shadows fade out at the reaches above.

## The shadows' ends (the maintainer's follow-up: "fix the shadows end")

At a low sun every shadow is long (a 3-level building's at 5 deg: 80 squares) and each kind ended at a limit of its own
(screenshots `ends-a-*`; the sun term alone `ends-b-*` with `--prop devAoView=1 --prop devSunView=1`):

- characters' and vehicles' shadows (the per-frame pass) faded out over 8.4-12 squares (`sunShadowCharacterReach` 12): the
  parked van's dusk shadow ended blunt and pale;
- walls, roofs and solid objects through the far field faded out over 24-32 squares (`sunShadowFarSquares` 32): a
  building's shadow stopped in the middle of a car park;
- bushes and hedges cast only through the near march over the neighbour textures, capped at 8 squares
  (`sunShadowLengthPct` 800): their shadows stopped on a dotted comb;
- trees cast through cards taken within 3 chunks (32 a texture): a long tree shadow stopped where a texture no longer took
  the tree.

Changes (default on):

- `sunShadowCharacterReach` 40: the quad ends where the caster's own top projects (its tip); the wall-edge scan behind it
  stays at 24 squares (its cost).
- `sunShadowFarSquares` 96, `ChunkAo.FAR_MARGIN` 96 (a 200 x 200-square window, filled only towards the sun as far as a
  24-square caster's shadow reaches: a high sun reads the neighbours only; 64 march steps). `sunShadowReachFade`: the
  strength fades out while a 3-level caster's shadow grows from 85 to 115 % of the reach (~5 -> ~4 deg), so a shadow longer
  than the far field can draw fades as a whole instead of ending on the field's edge (the dusk fade takes it to 0 at 2 deg
  anyway). The bare-texture scan stays at 8 chunks (its cost); `downwindRefresh` reaches the rest.
- The far field's map is RGB: R columns, G tree crowns (`sunShadowFarTrees`: every tree on the texture's levels splatted
  over its crown's radius; past `sunShadowTreeCardFar` 16 squares along the ground the cards hand over to these crowns, a
  soft medium of `sunShadowTreeFarDensityPct` 35 % optical depth a square), B bushes (`isBush` squares, half a level,
  spread 3 x 3 against a ladder of rungs along their shadows). The same map serves every chunk texture, so a tree or bush
  shadow continues across chunk lines.
- The near march, where a low sun's shadows outrun it (`farPar.w < 0`), fades its last 40 % (dithered) while the far field
  takes over the same stretch for walls, columns and bushes: no dotted end.
- Columns as tall as their casters: a wall edge's height from its sprite (`CapsuleShadow.edgeTop`: building walls one
  level, picket fences half, chain-link and railings none; see-through `WallNTrans` / `WallWTrans` edges out), solid
  objects half a level (they were a whole level: a dumpster threw an 18-square shadow at 8 deg).
- Characters and vehicles in a far shadow: `SunShadow.march` continues past its 9-square grid walk over the column map
  (soft, the same penumbra and end fade), so someone standing in a building's long shadow is shaded too. Its first version
  counted a chain-link fence as a full wall and put the van beside it in shade (no shadow at all): bisected with
  `sunShadowFar=false`, traced with the new `devFarShadeLog`.
- `downwindRefresh`: a chunk baked with new geometry at a low sun queues the textures downwind of it within the reach for
  a sun-step recompute (staged, eased in), so the shadows of a chunk that streamed in appear.
- Found on the way: the staging textures' pool held 96 while a step at zoom 2 stages ~200, and every miss allocated with
  a `glGetInteger` and a framebuffer status check (threaded-driver syncs: the staged compute's slot read 200-500 us of GPU
  time); allocations are query-free now and the pools hold 160 (`sync-cost-*`: AO GPU 300-500 -> 90-150 us/frame in the
  sweep, as with `sunStepSync=false`).

Rigs: `harness/shadow-seams.py` with dev views `devSunView=9` / `10` (the texel's x / 8, y / 8 in its chunk: exact chunk
seams), `11` (the sun's visibility alone, whatever the strength) and `5` (height: ground only), still camera at
`devSunHour=20.2` (5.4 deg). It reports the visibility step across chunk seams against inside chunks (cuts on chunk lines),
the ground's end edges (visibility rising by > 0.12 within 6 px going away from the sun: where a shadow stops) and the
ground's mean visibility (more shadow reaching further).

Results, church lot (zoom 2, the visibility view, ground only, `harness/shadow-reach.py`): the share of the ground lit where
the longest reach (96, the default) has shadow, i.e. shadow cut off:

| sun | released (32 squares, no far crowns) | 64 | 80 |
|---|---|---|---|
| 7.7 deg | 14.8 % | 2.0 % | 0.13 % |
| 5.4 deg | 18.4 % | | 0.56 % |

The fix has converged at both heights (a longer reach no longer changes the ground). Jev: `fixed` 0.94 (7.7 deg) and 0.92
(5.4 deg); released reach cuts shadows 0.97 / 0.98, fix converged 0.94. `harness/shadow-seams.py` (5.4 deg): ground end
edges (where a shadow stops) park 29,113 -> 23,327 / Mpx, church 8,284 -> 4,007; chunk-seam steps at the inner level before
and after (the cuts were reach limits, not chunk lines; the forest's crown seams are another artifact: crown copies in
neighbouring textures shading apart). Vehicles: the van's dusk shadow runs at full strength to the tip of its roof's
projection (`vanfix3-*`). Cost (`endscost-*`, the sun swept so a step is always in flight, uncapped): the kernel ~85-108 us
a compute vs ~81-95 with the released reaches (+~10 us, low sun only), 311 vs 280 fps (noise). The sun-step flicker fix
holds on this build (`lowsun-rt-on10`: abrupt 0.5 % / 10 s).

## Rig

`--mode bench --launcher direct --flag start=8168,11502 --flag route=E:0 --flag hold=14 --flag time_of_day=20.0
--flag weather=clear --flag zombies=off --flag zoom=2 --route-seconds 16 --prop devCapture=6,14,30,25,gray` (+ the sweep
props), then `harness/lowsun-flicker.py <run>...` and `--judge TEST CONTROL --before RUN`. The released behaviour on the
same build: `--prop sunStepSync=false --prop sunStepLowPct=0 --prop sunStrengthFine=false --prop sunStepFadeMs=0`.
Scattered reversals in sunlit runs are leaves swaying in the wind (isolated blocks oscillating every ~1.2 s, also in the
released build); the judge names them so.
