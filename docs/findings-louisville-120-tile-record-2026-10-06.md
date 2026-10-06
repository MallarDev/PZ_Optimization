# Louisville 120, plan A: the translucent tile pass recorded on the workers (2026-10-06, branch `lou120-tilerecord`)

Change A of docs/plan-louisville-120-structural.md. Scene and measurement as in the plan: `--bench louisville`, capped
at 120 (`frameCapFps=120`), `tieredZombieUpdates=true`, `settle=5`, empty options file, `entityUpdateParallel=true
zombieSimLodTiles=10 zombieSimLodSteps=4 zombieLodDynamic=true zombieLodMin3d=32`; the lock runs also had the 10-05 keys
on (`slackWork lootDefer zombieSpawnSpread animalLosSnapshot zombieModelAddBudgetUs=500`).

## Result

| | stock pass | recorded |
|---|---|---|
| translucent pass, game thread, ms a frame (in-run ABBA, a3-ab1) | 0.80 | 0.33 |
| performRenderTiles, ms a frame | 1.84 | 1.45 |
| game-thread CPU, ms a frame | 6.75 | 6.39 |
| translucent pass in the frames over 9 ms | 1.06 | 0.39 |
| exactness (devDrawListCheck, async, a3-acheck1) | | 0 differences in 253,392 entries / 281 passes |

Whole capped runs, 3 + 3 alternating (l120a-rep3, clean machine): off 117.8 fps, p99 14.7 ms, 11.3 % of frames over 9 ms,
2.3 % over 12; on 118.0 fps, p99 14.2 ms, 11.0 % / 2.1 %. Inside the run noise: the miss frames average ~11.9 ms of
game-thread CPU against ~5.9 in ordinary frames, and their excess is mostly logic bursts (chunk map / popman / lighting /
scheduler, +3.4 to +4.2 ms) and the rest of the render (+2.4 ms). A takes 0.67 ms out of a miss frame's translucent pass,
which does not bring a 12 ms frame under 9; the lock needs the bursts (item 3).

## How it works

- **Units**: one chunk of one level of the per-frame translucent pass (`FBORenderCell.renderOneChunk_Translucent`). Chunks
  with nothing at that level run the stock call (a few state entries) at their place in the splice.
- **Recording** (`pzopt.DrawRecorder`, one per thread, units appended): `SpriteRendererStates.getPopulatingActiveState`
  hands the thread its own render state; `IOpenGLState.set` checks the recorder's own GL cache. The first set of a state
  in a segment is conditional: the splice issues it against the game thread's cache, as stock's dedupe would.
- **Deferred objects**: what a worker may not draw is noted and drawn by the game thread at its place in the stream
  (with the GL values the segment set before it, and a fresh segment after it). Eligible (`TileRecord.deferReason`):
  plain tiles, light switches (once their "on" overlay exists), windows, walls, door frames, IsoDoors without curtains,
  double doors or models; their sprites drawn once on a non-recording thread (lazy state), not flipped, no self-fading
  shared instance, no roof seam joins, no 3D model, no clock, no fascia, no children / wall splats. Left: 3D-model doors
  (~11 a pass), stoves, TVs, broken glass, curtains, lamps (a plain object's power check), clocks.
- **Splice**: entries copied into the frame's list in stock chunk order; StartShader postRender ownership moves with the
  copy; tree flush points flush the game thread's pending trees; the unit's lazy-lighting effects (`LightingDefer`) run
  first; the next-draw depth stock leaves behind a skipped draw is carried through (events where a segment takes the
  inherited value and what it leaves).
- **Asynchronous** (`tileRecordAsync`): every level's units are handed to the frame workers right after the chunk
  composite and record while the game thread draws players, corpses, moving objects, water and attachments; the first
  translucent pass joins (the join cost is ~0). Render-mode flags the game thread flips meanwhile are per thread.

## Hazards found (each confirmed by the rig, then fixed)

- A recorded TextureDraw swapped into the frame's list kept its uniform chain on both sides: released twice, the pool
  looped and the game hung at frame 39 (fixed: entries are copied, the recorder's side lets go of the drawer).
- `IsoSpriteInstance.renderprep` copies the object's alpha into the instance, and a tile's instance is its sprite's
  shared def: two workers swapped alphas, and a zero alpha dropped a draw (fixed: computed locally).
- Stock leaks an unconsumed next-draw depth into the next draw of the stream, across objects and units.
- `FBORenderCell`'s sort scratch, `renderWindowFrameOutline`, `lowestCutawayObjectN/W`, IsoGridSquare's wall colours and
  stencil flag, IsoObject's / IsoSprite's colour scratch, the depth / seam / cutaway modifiers and wall shapers (shared
  singletons compared by identity), IndieGL's temps, the uniform-setter pool, the cutaway mask texture lookups (an
  unsynchronized map), the dev census map.
- A window's sprite never reaches its lazy roof init on its own path (deferred objects now warm it).
- Overhead traps: issuing then dropping conditional entries cost more than stock's whole state handling (now issued at
  splice); one recorder per unit polluted the caches (one per thread now); the blocking per-level batches paid a wake
  and join five times a frame (the asynchronous dispatch removed it).

## Rigs

- `devDrawListCheck=N`: every Nth frame each level's stock pass runs first (from the objects' state before recording in
  async mode), is rolled back (entries, postRender, GL caches, pending trees, next-draw depth, object alpha) and compared
  entry by entry with the spliced stream, per entry type's own fields. Lamp-post colours can differ by the check itself
  (the stock pass creates a lamp's "on" overlay lazily).
- `devTileRecordSerial`: A1, every unit recorded on the game thread. Instrumented runs print the defer census (reasons
  and classes) on the `tile record:` line. gtab columns `tl_pass`, `tl_record`, `tl_splice`.
- `tools/StaticAudit.java` gained `--exclude` (types kept off the audited path) and `--putfield` (instance writes).
