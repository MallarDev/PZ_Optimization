# Plan: the two structural changes left for a locked 120 fps on Louisville (2026-10-06)

Scene and baseline: `--bench louisville` capped at 120 (`--prop frameCapFps=120`, `--option tieredZombieUpdates=true`,
`--flag settle=5`, empty options file) with the 2026-10-05 keys on (docs/findings-louisville-120-2026-10-05.md, release
11916bf): 118 fps, p99 13.7 ms, ~11 % of presented frames over 9 ms, ~2 % over 12 ms. The misses follow the spinning
camera: 2-4 % of frames while it faces the open side, 15-20 % while it faces the horde, where every per-zombie section
grows together (`pzopt-gtab.out` sections, l120-sec7). Stalls were ruled out (GC, SMT siblings, render-thread waits), so
the remaining misses are game-thread work, and the two biggest blocks of it that still run one zombie / one object after
another are:

| block (l120-prof5, ordinary frames) | ms a frame | in a 10.5-13 ms miss | what it is |
|---|---|---|---|
| per-frame tile objects (translucent pass, per-frame walls / doors / glass / windows) | 0.95 | ~1.5 | `FBORenderCell.renderTranslucent` -> `IsoObject.render` -> sprite draws, wall lighting, tile-depth shader setup, GL state |
| rest of `renderTilesInternal` (chunk levels, floors, cutaways) | 0.50 | ~1.0 | the level walk around the bakes |
| zombie postupdate outside the already-parallel parts | 0.62 | 0.81-0.94 | `IsoMovingObject.postupdate`: impulse, vehicle collision, `DoCollide`, square moves, flags |

Target of both changes together: ~1.5-2 ms off the heavy frames, i.e. frames over 9 ms from ~11 % to under 3 %, over
12 ms under 0.5 %. Order: **B first** (smaller; the batch machinery exists), then **A**.

## Measurement rules (both changes)

- Every new key gets a `GtAb` bit and a section timer; per-change decisions come from section times in ABBA in-run A/Bs
  (`devGtAlternate=1150`, `devGtAbba` on). Plain alternation and single whole runs are not enough on this route (an A/A
  placebo read 0.6 ms / 7 points; run-to-run miss rate +-5 points).
- Lock decisions come from 3 + 3 alternating whole capped runs (`~/.cache/pzopt/rep.sh` pattern), compared on frames over
  9 / 10 / 12 / 16.7 ms and p99 / p99.9, plus the miss rate by turn phase (the heavy directions are the point).
- Note the desktop's `~/Zomboid/Lua/layout.ini` (`charinfowindow visible=true` since 2026-10-05: +0.55 ms of Lua UI a
  frame) and keep it the same across compared runs.
- Exactness: each phase ships a dual-evaluation rig (compute both ways on a sample, count differences); 0 differences over
  a route is the bar for an exact phase, and anything else is documented as an intended difference.

## B. Zombie postupdate movement on the workers

### What runs today

`MovingObjectUpdateScheduler.postupdate` walks the buckets; per zombie `IsoGameCharacter.postupdate` ->
`postUpdateInternal`. Already off the game thread: the action-state evaluation (`actionEvalParallel`), the animator
(`animatorParallel`), bones / skin / palette / shadow (`animBonesParallel` and friends). Still serial, in stock order:
`IsoMovingObject.postupdate` (slide head from walls, `ensureOnTile`, target bookkeeping, collision flags reset, impulse
integration and the one-tile clamp, virtualisation at the loaded area's edge, `PolygonalMap2.resolveCollision` against
vehicles, `DoCollide` against walls / objects / doors via the squares' collision data, `setCurrentSquareFromPosition` and
the square's moving-object lists, `last` / `current`), plus the small tail of `postUpdateInternal` around it.

### Design: compute on the workers, commit in stock order

The same shape as `entityUpdateParallel` (`pzopt.UpdateBatch`), whose pieces it reuses:

1. **Eligibility** (game thread, per zombie, like `ActionEval`'s filter): a calm state (idle / walk / path / lunge-free),
   not ragdolled, not grappled / grappling, not in a vehicle collision, no impulse from a hit this frame, collidable,
   not on the loaded area's edge (virtualisation stays serial: `ZombiePopulationManager.virtualizeZombie`). Everything
   else runs the stock body inline.
2. **Compute** (workers, `FrameBatch`): for each eligible zombie, from its own fields and a frozen world, the new
   next-x / y after impulse, clamp, vehicle resolution and `DoCollide`, the collided-N/S/W/E / door / object results and
   the destination square. Reads of other movers go through `UpdateBatch.frozen()`'s snapshot; the squares' collision
   data does not change during the phase (no chunk hand-off, no object add / remove runs between the scheduler's update and
   postupdate). Writes go to a per-zombie result record only.
3. **Commit** (game thread, stock order): apply each record (position, flags, `last` / `current`), move the zombie
   between squares through `UpdateBatch`'s deferred `setMovingSquare` path, replay anything with side effects
   (`collidedObject` thump hooks, door / window collision callbacks, Lua events) in order, then the rest of
   `postUpdateInternal` as today.

### Hazards, from the earlier passes

- Static scratch on the path: `L_postUpdate` (its `vector2f`), `IsoMovingObject`'s `tempo` and the collision helpers'
  temporaries, `PolygonalMap2.resolveCollision`'s working sets. `tools/StaticAudit` on `postupdate` / `DoCollide` /
  `resolveCollision` first; each mutable static becomes thread-local scratch in the override (`UpdateBatch`'s
  `tempoScratch` pattern) before the first run.
- Order dependence: stock moves zombie k after zombies 0..k-1. Zombie-zombie pushing happens in `separate` (update phase,
  already parallel with a frozen snapshot), so postupdate's collision is mostly against the static world, and the frozen
  read changes only what one zombie sees of another mid-frame (as `entityUpdateParallel` does). Measured by the rig below.
- Lazy lighting on squares (`JNILighting.update` behind light reads) must not run on a worker: route through
  `LightingDefer` as the update batch does.
- `PolygonalMap2` vehicle collision: only call it on a worker if the audit shows it reads immutable vehicle polygons this
  phase; otherwise mark zombies near a vehicle ineligible (cheap bounding test, `pzopt.VehicleCull`'s circle).

### Phases

| phase | content | gate |
|---|---|---|
| B0 | census: share of zombies eligible per frame, cost split of the serial postupdate (section timers inside it), static audit | numbers in the findings |
| B1 | compute / commit split executed serially on the game thread (same code path, no workers) + `devPostupdateCheck` comparing against the stock body on a sample | 0 differences over a route; cost within +0.05 ms |
| B2 | compute on `FrameBatch` workers (key `postupdateParallel`, GtAb bit, section `postupdate`) | 0 differences (or documented frozen-read differences); section time -50 % or better |
| B3 | widen eligibility (more states) where the rig stays at 0 | same |

Expected: postupdate 0.62 -> ~0.25 ms in ordinary frames, 0.9 -> ~0.35 ms in the heavy ones. Kill criterion: B2 under
-0.2 ms or any unexplained difference in the rig.

## A. Recording the per-frame tile draw lists on the workers

### What runs today

`FBORenderCell.performRenderTiles` walks the on-screen chunks; per chunk level the per-frame passes (translucent objects,
cut-away walls / doors / windows / glass tiles kept out of the bake since 2026-10-01, floors not in the texture) call
`IsoObject.render` -> `IsoSprite.render` -> `SpriteRenderer.render(...)`, which appends a `TextureDraw` to the populating
`GenericSpriteRenderState` (`sprite[]`, `numSprites`, `CheckSpriteSlots`), interleaved with GL state entries (depth func,
depth mask, `StartShader` for the tile-depth shader, blend) that `GLState` caches on the game thread. About 800 objects
a frame on this route (`translucent pass per frame` census: ~450 glass tiles, ~100 windows, ~40 doors, ~150 other).
`translucentOrderCache` already gives each level's sorted square order.

### Design: per-worker recorders, spliced in stock order

1. **Recorder**: a `TextureDraw` buffer plus its own GL-state cache, owned by one worker (`pzopt.DrawRecorder`).
   The `SpriteRenderer` / `GenericSpriteRenderState` overrides (new) route `render` / `drawGeneric` / the GL-state entry
   points to the calling thread's recorder when one is set, else the stock state (game thread unchanged).
2. **Units**: one chunk level's per-frame translucent pass is one unit (independent: its squares, its objects, its
   order). The game thread prepares what must stay on it (below), hands the units to `FrameBatch` workers, and while
   they record does its own serial work (players, corpses, items).
3. **Splice**: the game thread appends each unit's buffer to the populating state in stock order (chunk order, level
   order), emitting the GL state the unit's first entry assumes (each recorder starts from a known state and records its
   own transitions, so the splice adds at most a few state entries per unit). `TextureDraw` objects come from per-worker
   pools and are handed to the state's slots (the state recycles them as today).
4. **Before the units start** (game thread, like `CharDraw`'s pre-pass): the lazy per-square lighting refresh of every
   square a unit will light (`JNILighting.update`, game-thread only), cutaway / fade alpha steps that write object fields
   (`IsoObject.updateAlpha` and the cutaway decisions are already computed earlier in the frame), and any texture load a
   draw could trigger (the unit falls back to the game thread when a sprite's texture is not ready).

### Hazards

- Static scratch on the render path is the main risk: `IsoGridSquare`'s static `Color` corners (`tr / tl / br / bl /
  interp1 / interp2 / finalCol`), `IsoSprite` / `IsoObject` render temporaries, `defColorInfo`, the tile-depth shader
  setup (`IsoSprite.startTileDepthShader`, `setupTileDepth`), `FBORenderCell` instance flags read during a pass
  (`renderTranslucentOnly`, `renderAnimatedAttachments`, ...). `tools/StaticAudit` over `renderTranslucent` and
  everything it reaches; each mutable static becomes thread-local in an override, each pass flag a recorder field.
- Shared mutable state behind "read" calls: square light caches, `IsoObject.getRenderInfo` (writes the object's cached
  info), sprite instance animation state. Each needs either a pre-pass on the game thread or proof it is per-object.
- GL state equivalence: the spliced stream must issue the same effective state per draw as stock. The rig compares
  effective state per draw, not entry counts.
- Mods: a mod's Lua `render` hooks on objects stay on the game thread (`pzopt.LuaOrigin`; such objects make their unit
  serial).

### Phases

| phase | content | gate |
|---|---|---|
| A0 | census per unit (objects, draws, state entries), static audit of the render path, list of game-thread-only reads | findings |
| A1 | recorder + splice with every unit recorded **on the game thread** through the recorder; `devDrawListCheck` compares the spliced stream with a stock recording draw by draw (texture, coords, colours, depth, effective GL state) | 0 differences over a route, overhead <= 0.05 ms |
| A2 | translucent units on `FrameBatch` workers (key `tileRecordParallel`, GtAb bit, section `performRenderTiles` split into prep / wait / splice) | 0 differences; translucent 0.95 -> <= 0.3 ms |
| A3 | the other per-frame tile passes (floors not in the texture, per-frame walls / doors) as units | same |
| A4 | optional: characters' submission through recorders (their draw data is already prepared by `CharDraw`) | same |

Expected: ~0.7-1.0 ms off ordinary frames, ~1.5 ms off the heavy ones. Kill criterion: A1 overhead above 0.15 ms or a
static that cannot be made thread-local without touching more than the render path.

## After both

Re-measure the lock (3 + 3 whole capped runs, by turn phase). If the heavy directions still miss, the next candidates in
size order are the scheduler's per-frame classification (`startFrame`, 0.35 ms: every object re-classified every frame),
`sceneCullZombies` (0.25 ms), player LOS (0.34 ms, the per-object read pass is parallel-friendly with a serial apply), and
the open character panel's Lua UI if it is part of the test scene.
