# Game-thread offload pass (2026-09-27, branch `gt-offload`)

The maintainer asked: "What else can we move out of the game thread?", then: implement the four candidates (bake
preparation, the zombie update, our own render preparation, the update scheduler), keep every idea, one by one, profiled.
This file is the pass's record. Code: `pzopt.RenderPrep`, `pzopt.PixelLight` (pack context), `pzopt.SchedulerClassify`,
`pzopt.ZombieStats`, `pzopt.LosPrefetch`, `pzopt.GtAb`, the `IsoAnimal` override, PR #35 merged with a calm-state whitelist;
override edits in `docs/override-edits.md` ("The game-thread offload pass").

## Summary

| item | key (default) | what leaves the game thread | best measured gain |
|---|---|---|---|
| 3 our render prep | `renderPrepParallel` (on) | characters' sun share + water proximity, on the frame workers during the composite | characters draw −0.35..−0.39 ms (desktop Louisville) |
| 3 | `pplPackParallel` + `pplTorchNearChunk` (on) | the pixel-light lattice pack, one chunk per task; torch reach per chunk | −0.45..−0.49 ms desktop, −0.98 ms flip drive |
| 4 scheduler | `schedulerClassifyParallel` (on) | each object's simulation level (0 mismatches in 5.4 M dual checks) | −0.14 ms desktop, −0.34 ms Mac (steady horde) |
| 2 zombie update | `entityUpdateParallel` (off: the maintainer's call) + `entityUpdateSafeStates` (on) + `LightingDefer` | calm zombies' whole update (PR #35 + a whitelist + the light-read race fixed) | −0.92 ms desktop steady horde (+12 % fps) |
| 2 | `zombieStatsFold` (on) | not moved: 2,000 map lookups + achievement checks a frame folded into 3 (bitwise the same totals) | ≈ −0.4 ms desktop steady horde |
| 5 animals | `animalLosFast` (on) | not moved: far-zombie `spotted()` calls folded into their exact bookkeeping | −1.06 ms desktop, −1.35 ms Mac (60 cows) |
| 1 bake / render prep | `visPolyAsync` (on) | the vision cone's polygon, own thread from the top of the tile render | with the next: tile render −0.41 ms flip drive |
| 1 | `aoContextParallel` (on) | every bake's AO / sun-shadow world masks, one batch at `ChunkAo.flush` | (above) |
| 1 | `translucentOrderCache` (on) | not moved: the translucent pass's per-level merge + sort kept while its inputs are unchanged (0 mismatches in 369 k checks) | tile render −0.30 ms flip drive |
| – | `losLightPrefetch` (off) | the player LOS squares' light refreshed ahead on the workers | slower (+0.12..0.14 ms) |
| 6 weather mask | – | not done: it is sprite submission (see "Found on the way") | – |

All keys together: Mac steady horde −3.46 ms game thread a frame (−18.5 %, +16 % fps, with entityUpdateParallel); flip 120 km/h
drive −1.87 ms (+11 % fps); desktop Louisville render sections −0.9..−1.0 ms.

## How it was measured

Run-to-run noise on the Louisville horde is ±1 ms a frame (the route crosses scenes from 19 to 170 fps), so every key was
measured **within one run**: `--prop devGtAlternate=2000 --prop devGtAlternateKeys=<keys>` switches the listed keys off and on
every 2 s from the first world frame (`pzopt.GtAb`, the phase flips only at a frame start). Each frame writes a row of
`pzopt-gtab.out`: the game thread's CPU time (ThreadMXBean), the process CPU time, the wall time, the zombie updates, and the
ns of eight game-thread sections (scheduler start / update / postupdate, animal and player line of sight, the pixel-light
composite prep, the characters draw, the whole tile render). `harness/gtab.py <run>` splits frames, CPU, sections and the
25 Hz stack profile by half and pairs every on period with the mean of the off periods on either side (a scene that changes
along the route moves both halves of a pair alike). Game-thread CPU and the sections are the metric: the frame time of an
enhancements-on Louisville run is bound by the GPU / render thread (the game thread's savings turn into waits there).

Two scenes: the standard Louisville route (25 s, 2 s periods: 5-6 pairs, scene-confounded for the zombie-side keys) and a
steady one, the same preset holding still for 40 s with 60 cows spawned around the player (`--flag route=S:2 --flag hold=40
--flag animals=60:cow`, the new `animals=N[:type]` scene flag): game-thread bound (97 % busy), 12 pairs.

## Results (desktop, 9800X3D / RTX 4090, 5120x2160, the maintainer's tab file: every enhancement on)

| key | what moves | section, paired per period | verdict |
|---|---|---|---|
| `renderPrepParallel` | each on-screen character's sun share (capsule shadows) and water proximity (reflections) computed on the frame workers during the composite | characters draw 0.57 → 0.22 ms (−350 ± 47 µs), 0.66 → 0.27 (−385 ± 74) | on |
| `pplPackParallel` + `pplTorchNearChunk` | the pixel-light lattice packed one chunk per worker task; the torch reach tested per chunk level | composite prep 0.56 → 0.17 ms (−447 ± 88 µs), 0.63 → 0.18 (−492 ± 101) | on |
| both render keys | | whole tile render −879 ± 219 µs, −1,024 ± 316 µs; game-thread CPU −11 % | |
| `schedulerClassifyParallel` | the per-object simulation level on the workers, buckets filled in order (dual-evaluation rig: 0 mismatches in 5.4 M) | route: +2 ± 18 / −37 ± 30 µs; steady scene 0.28 → 0.17 ms (−142 ± 23 µs) | on (small) |
| `animalLosFast` | an animal's `spotted()` calls for zombies beyond 10 squares folded into their exact bookkeeping (vanilla animals all flee zombies) | steady scene with 60 cows: 2.62 → 1.74 ms (−1,060 ± 119 µs); Louisville without animals ≈ 0 | on |
| `zombieStatsFold` | the zombies' travel statistics summed in order and written once a frame | steady scene: part of the scheduler update's −1.47 ms (≈ −0.4 ms after the animal share) | on |
| `losLightPrefetch` | the stale lighting of the player's line-of-sight squares refreshed on the workers ahead of the walk | player LOS +119 ± 30 / +142 ± 42 µs (the walk's own lazy reads cost ~130 ns a square, less than a batch) | off |
| `entityUpdateParallel` (PR #35) + `entityUpdateSafeStates` | the calm zombies' whole update on the workers | steady scene, everything above already on (142 fps): scheduler update 3.23 → 2.44 ms (−915 ± 124 µs), frame −0.89 ms (+12 % fps), game-thread CPU −1.03 ms (−12 %), process CPU +2.3 ms a frame | pending (below) |

All together on the steady scene the first five keys took the game thread from 11.5 to 10.0 ms a frame (−13 %, +15 % fps);
`entityUpdateParallel` another −1.0 ms on top (pipeline off; with `entityUpdatePipeline` on −0.83 ms and a smaller frame gain,
so its default is now off).

### The laptops (every later run: the flip and the Mac, the desktop is shared)

| run | scene | keys | game-thread CPU, paired | frame | notable sections |
|---|---|---|---|---|---|
| `mac-gto-mac-hold2` | Mac M1 Pro, steady Louisville hold + 60 cows | all + entityUpdateParallel | −3.46 ± 0.23 ms (16.9 → 13.8, −18.5 %) | 58.9 → 68.6 fps (+16 %) | scheduler update −2.35 ms, animal LOS −1.35 ms, scheduler start −0.34 ms, player LOS −0.09 ms |
| `flip-gto-flip-drive-ab1` | flip, Rosewood 120 km/h drive | all | −1.87 ± 0.56 ms (−9 %) | 65.5 → 73.0 fps (+11 %) | pixel-light composite prep 1.04 → 0.35 ms, tile render −1.22 ms |
| `flip-gto-flip-drive-bake1` | same | visPolyAsync + aoContextParallel only | −0.84 ± 0.40 ms | +5.5 % fps | tile render −408 ± 153 µs; 1,094 AO mask jobs in 331 batches; the polygon never waited |

Item 1, bake preparation: what the flip's late frames hold (`flip-gto-flip-drive1`, async-profiler wall samples, every frame
late at 120 Hz: game step p50 13 ms): the per-frame translucent pass 20.7 % (≈670 wind-animated vegetation objects a frame
drawn per frame: the game's wind-sprite option), Lua 7.4 %, bakes 8.2 % (sprite recording 5 %, the AO context 1.6 %, render
info 0.8 %), chunk arrival 6.6 %, lighting 6.1 %, Bullet 3.8 %. The bakes' sprite recording cannot leave the game thread in
this engine: every draw goes through the one SpriteRenderer's populating state and the global GL / shader state stacks.
What moved: the vision cone's polygon (`visPolyAsync`, its own thread from the top of the tile render) and every bake's
AO / sun-shadow world masks (`aoContextParallel`, one batch on the frame workers at `ChunkAo.flush`, the shared caches locked,
the job's scratch per job). What was trimmed: the translucent pass's per-level merge + sort (`translucentOrderCache`).
Left: the per-object render info of a baking level (reads light lazily with side effects, cutaway flags, obscuring state;
writes fascia alpha: possible with the LightingDefer pattern, ~1 %), chunk arrival's border recalculation (adjacent border
squares write each other's collision / vision bits: needs a colouring), the render thread's own `glGetInteger` state queries in
the capsule shadows, AO, FSR, fog, DLSS and god rays (9.6 % of the flip's late render submits: a driver sync each on Mesa).

## The zombie update on the workers (PR #35 + a whitelist)

PR #35 (entityUpdateParallel: the bucket's update loop on the frame workers, positions snapshotted at dispatch, Lua events and
emitter ticks captured and replayed in order, per-thread scratch buffers, pathfinding list races guarded) was merged into this
branch file by file. Its `synchronized` methods became lock blocks with the stock signatures (build.sh's member check), and its
`IsoAnimal` / `AnimalLos` were left out (this branch's `IsoAnimal` does the same fold at the stock distance).

Its filter batched every zombie but grappled / reanimated ones, and shooting them crashed the game: Bullet's ballistics target
released on a worker (the PR author's two horde-shoot runs) and, here, a native `double free or corruption` abort 60 s into the
horde-shoot bench (run `gto-hs-pr35filter`). `entityUpdateSafeStates` (default on) batches only a zombie that is idle, walking
toward a point or following a path, with no ballistics target, ragdoll, fall, fire or grapple, no animation player about to be
replaced and no player within 12 squares (`IsoZombie.pzoptBatchSafe`, read on the game thread before the batch). Horde-shoot with
it: 2 of 2 runs clean (`gto-hs-safe1/2`: no crash, 0 exceptions but the stock `LungeState` one, 730 k / 880 k zombie updates on
the workers, every Lua event replayed). `PropertyContainer.initSurface` (the slope parse a batched zombie's fall check reads,
where the PR latched off once) now publishes its "parsed" bit last behind a release fence. The pipeline mode stays off
(`entityUpdatePipeline=false` in these runs; the PR's own notes found its overlap window empty).

A race PR #35 left open: a batched zombie's first read of a square's light (its visibility test, `spottedNew`) is
`JNILighting.update`, which also marks the chunk level dirty, feeds LightDirt / the puddle batch / the cutaway check and runs
the room / meta "square seen" hooks, all from the worker. `pzopt.LightingDefer`: on a batch task the refresh of a square runs
under its lighting object's monitor and every side effect goes to the task's list, run by the game thread at the join in
task order. With it the Mac run's player-LOS section went from +138 µs (on) to −94 µs.

## Desktop check (after the pass, 2026-09-27 evening)

Every default key alternating together (`devGtAlternateKeys=all`), `--launcher direct`. **The alternation period must not divide
the view's turn period**: the Louisville and spin benches turn 90°/s (a turn every 4 s) and a 2 s period put every on half on
one side of the circle and every off half on the other (the first spin run showed the on halves 3 % slower with 0.35 ms more
GPU time); 2.3 s periods walk the phase round. Runs `gtd2-*` (2.3 s), `gtd-drive` (the drive does not turn):

| run | scene | game-thread CPU a frame, paired | frame | notes |
|---|---|---|---|---|
| `gtd2-lou-defaults` | Louisville hold + 60 cows, default keys | 11.04 → 9.78 ms (−1.30 ± 0.08) | 89.1 → 100.3 fps (+12.6 %), p99 17.7 → 15.1 | characters draw −288 µs, animal LOS −329, tile render −368, scheduler start −43, vision polygon −53 |
| `gtd2-lou-eup` | same + `entityUpdateParallel` | 7.25 → 6.03 ms (−1.63 ± 0.28) | 132.4 → 157.4 fps (+18.9 %), p99 18.2 → 14.9 | scheduler update −1.24 ms; 0 exceptions |
| `gtd-drive` | Rosewood 120 km/h, 240 cap | 2.93 → 2.63 ms (−10 %) | pegged at 240 both ways, p99 14.1 → 13.4 | pixel-light prep −259 µs, vision polygon −50 |
| `gtd2-spin` | spin route, uncapped | 4.44 → 4.17 ms (−6 %) | GPU-bound (5.5 ms GPU a frame): unchanged | pixel-light prep −257 µs; the AO mask batch +69 µs at flush, paid back in the bakes |

The earlier 2 s Louisville runs (desktop `gtd-lou-*`, the Mac `mac-gto-mac-*`) carry the rotation aliasing; their numbers are of the
same size as the clean ones here but should be read with that caveat.

## Horde-shoot on the desktop (final build)

Jev shoots the 150-zombie horde at the Riverside pier (`horde-shoot` bench), five runs: stock (`enabled=false`, 60 fps cap), the
default keys ×2, the default keys + `entityUpdateParallel` ×2. Every run completed its route; no crash while shooting (PR #35's
filter crashed there); the only exception is stock's own `LungeState` zero-vector one; no ragdoll off the game thread, 0 abnormal
ragdoll episodes; Jev: ragdolls `same_as_stock` (0.84) for both configurations. 681-688 k zombie updates ran on the workers per
zombie-update run, every Lua event replayed. Two of the four optimized runs (one of each configuration) crashed **at quit**, after
the route: `Ragdoll::deleteRigidBodies` in `IngameState.exit -> WorldSimulation.destroy -> Bullet.destroyWorld`, the quit crash
already open on the released build (memory: 2/5 and 5/5 there, stock 0/5); not introduced by this pass. Found and fixed below.

## The quit crash (`Ragdoll::deleteRigidBodies`)

libPZBullet's `WorldSimulation` destructor deletes the dynamics world first and the ragdolls still in its id → ragdoll map
afterwards; each ragdoll's destructor then removes its bodies from the deleted world (the crash registers show the world's
vtable already reset to `btCollisionWorld`, whose slot for `removeRigidBody` is empty: pc=0). So any ragdoll still registered at
quit crashes the exit, depending on what the freed memory holds (about one quit in two with one there).

`pzopt.RagdollLedger` (`devRagdollLedger=true`) follows every ragdoll controller from creation to release. In every run with
shooting, 1-2 controllers were still registered at quit, all of dead zombies shot 4-6 s earlier, owned by nobody. The creation
and removal stacks gave the chain, which is stock's: the ragdoll settles and its controller is released; the action state moves
to `onground`; `ZombieOnGroundState.enter` calls `die()` and the zombie turns into its IsoDeadBody (removed from the world); the
same `postUpdateAnimating` then runs the model update, which still finds the ragdoll track and builds a new controller for the
corpse. Stock does it too (15 of 40 controllers in one run, `deaths(track/ctl/sim)=[1, 30, ...]`: 30 of 31 zombies turn corpse
with a ragdoll track and no controller); ours 47 of 109, because the optimized game kills more zombies a second, so one is more
often still there when the game quits. The orphans also count against the active-ragdoll cap until the zombie is reused.

Fix: `ragdollCorpseGuard` (AnimationPlayer: no controller for a character that has turned into its corpse) and, as a safety
net, `ragdollQuitSweep` (WorldSimulation.destroy removes whatever is still registered first). Both default on, both off with
`enabled=false`. Checks: two runs with the sweep alone quit clean after removing 2 and 1 leftovers; three runs with the guard
alone and the sweep off (`rql-fix1..3`): 48 / 19 / 26 controllers, 0 made for a corpse, 0 left at quit, no crash, every route
complete, RagdollWatch 0 abnormal episodes, the only exception stock's `LungeState` one.

## Visual parity

In-game screenshots on the flip at one spot of the bench route (max zoom, zombies off, clear weather, `--shot-at 5`),
`harness/shotdiff.py` (share of pixels whose largest channel difference exceeds 8): two runs with every render-side key off
(`gto-flip-shot-off`, `-off2`) differ from each other by 0.46 % (wind-animated bushes, the player's pose, the vision cone's
edges: the first run's moodles appeared in the other order, i.e. its player timeline differed); two keys-on runs by 0.21 %;
keys off (`off2`) vs keys on 0.13-0.18 %, max 81-90; keys on vs `visPolyAsync` off 0.13 %. No key changes the picture beyond
the run-to-run noise.

## Found on the way

- A Louisville run on the 300-tile route (`gto-lou-animal`) died of `NearestWalls.ClosestWallDistance` index 24926 every frame from
  f:7517: the player's current square belonged to a chunk object already reused for another position, and the exception, thrown
  before the world update, kept the player from ever updating again. Not reproduced in two more long-route runs (keys on / off)
  nor in ~1,200 earlier runs; open.
- `IsoChunkMap.getWorldXMinTiles / YMinTiles` memoise lazily into plain fields that `LoadLeft / Right` reset: a worker reading them
  during a shift could write the old origin back. Shifts happen in the update phase, where no render batch of this pass runs; noted.
- The 25 Hz game-thread sampler is safepoint-biased: the scheduler looked like 3.5 % and measured 0.3 ms; the animal loop looked
  slower with the fold on (a call-free loop attracts samples). Section timers settle such questions.
- Zombie updates per frame follow the scene along the route, and the first period of every pair is "on": a whole-run on/off
  mean is biased; only the paired numbers count.
- The weather mask on the spin route (8.4 %) is sprite submission (`DoCutawayShaderSprite` → `IndieGL.bindShader`'s pooled lambda
  + the sprite enqueue), not particles: moving it means drawing the mask on the render thread from a compact list; not done.
- `harness/asprof/libasyncProfiler.so` reached the flip as a dangling symlink: `queue.sh` now syncs `harness/` with
  `--copy-unsafe-links` (live once the shared checkout has it).

## Rigs

`--prop devGtAlternate=2000 --prop devGtAlternateKeys=<keys|all>` + `harness/gtab.py [--all] [--skip S] <run>`;
`--prop devSchedCheck=true` (worker vs game-thread classification); `--flag animals=N[:type]`; `harness/callers.py <runs> --frame
<Class.method> --up N` (caller chains of a method in `pzopt-stacks.out`).
