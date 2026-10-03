# The 42.20.4 -> 42.21 drive regression (2026-10-03)

Since the port to Build 42.21 (2026-09-28) the optimized 120 km/h drive was 38 % slower on the desktop: the daily
history re-measured on 2026-10-02 (`~/pzopt-wt/daily-<MMDD>`, E:1200, kmh=193, max zoom, uncapped, defaults) has every
build up to 09-27 (on the 42.20.4 client copy) at 470-503 fps and every build from 09-28 on (42.21) at 294-312 fps; the
storm drive 352-362 -> 230-241. On-foot benches did not move (Rosewood spin 273-285 vs 255-283, the horde 268-337
both). Stock 42.21 is also 7-12 % slower than stock 42.20.4 (memory `port-4221-stable`), a smaller and separate effect.

## Cause: 42.21 counts driving as aiming for the tree cutaway

`FBORenderCell.isTranslucentTree` (42.21): `isAiming = isAnyAimKeyDown() || player.getVehicle() != null`. A tree is
see-through when (aiming, or south-east of the player) and its base square lies in the cutaway's stencil rectangle
(`IsoCell.isInStencil`: the 1024 x 1024 mask at tileScale 2 = 2048 px round the player, plus margins). In 42.20 a
driver only got the south-east quarter; in 42.21 every tree in that square. Stock draws every tree per frame anyway,
so for stock this is only the extra stencil passes. With `treesInChunkTexture` the trees are baked, and every tree
crossing that moving square while driving

- left the bake: its chunk texture re-baked, and the textures of the neighbours holding a copy of it (tree pass,
  issue #5) re-baked too (~7 per tree),
- re-dirtied the same textures three more times: when its fade started (`wasFaded`), when the cutaway let go while it
  was still fading, and when the fade ended; only the first and the last change what the texture holds,
- and was drawn per frame at world resolution in three passes (outside the cutaway, inside faded, inside outline).

Same-build A/B (E:600 drive, `devDriveTreeAim`, now `driveTreeCutaway`): 42.21 rule 408 fps / 12.9k bakes / 6,351
neighbour re-bakes vs 42.20 rule 556 fps / 9.4k / 1,829. 42.21's XXL fade while driving (`pzopt.XxlTreeFade`, 12 squares)
measured nothing (558 vs 556 with it off). With the 42.20 rule and the fixes below the full route runs at 507 fps, the
42.20.4 level: nothing else in 42.21 slows the optimized drive.

GPU sections (`gpuSections=true`, per sampled frame): the rule added 0.37 ms in `translucent` (the per-frame see-through
trees) and 0.18 ms in the bakes. Per pass (in-run cycle `devTreePassCycle=7,6,5,3,0`): outside 57 us, inside faded 54 us,
outline 27 us per level-0 instance. Early stencil rejection works; the cost is fill.

## The cutaway mask is an ellipse (and a scissor of ours that hid it, fixed)

`mask_transparency_player.png` is binary: alpha 1 on an ellipse over 21 % of its square (texels 97..927 x 284..742 with a
one-texel guard), 0 elsewhere. `isInStencil` tests the tree's base point against the whole square, so most see-through
trees cannot reach a single marked pixel: their inside passes draw nothing and their outside pass draws them exactly as
an opaque tree.

Correction (same day). A first reading of the stencil (`devStencilProbe`, a 4,096 px row and column through the player)
found the cutaway bit in only ~2 % of frames and was taken for a stock defect. It was a probe error: the world framebuffer
is screen-sized at every zoom (5120x2160 here; the projection divides the offscreen coordinates by the zoom), and the probe
read offscreen coordinates, outside the buffer at max zoom. A full read of the stencil after `drawStencilMask` shows the
ellipse in every frame on both paths (972,394 px at zoom 1; 662x365 px, 154,716 px at max zoom), and it is still there when
the trees draw. The stock game's cutaway works.

What really hid it on our path was `treeCutawayScissor`, released in be50962: it scissored the inside passes to the mask's
box in offscreen coordinates, so at any zoom but 1 the box missed the ellipse and the see-through trees drew opaque (stock
control 786 trees with inside samples, ours 0; with the scissor removed ours 2,219 on a longer probe). The scissor saved
9 us and is gone (hotfix release). Cost of the cutaway drawn again: see Results.

## Fixes (all default on, all exact)

- `treeRebakeLazy`: a tree re-dirties its texture only when it leaves the bake or comes back (fade start / cutaway exit
  mid-fade skipped); coming back is deferred (`treeRebakeLingerMs`, default 0 = the next check, which merges a chunk's
  trees; a 3 s stay measured slower: a tree drawn per frame costs more than the re-bake).
- `treeCutawayReach` (`pzopt.CutawayMask`): a see-through tree stays in the bake unless a generous screen box of its
  sprites comes within `treeCutawayReachPx` (256 px at tileScale 2) of the mask's marked box; kept in the bake, its fade
  is stepped exactly as `IsoTree.render` steps it (same constants, 30 fps multiplier, layer check so a tree drawn per
  frame is never stepped twice), so it has the stock fade when it gets there. Proof (occlusion queries round the inside passes of every tree the rule would keep baked):
  0 samples inside the cutaway on the stock path (47,720 probes at margin 0, 35,502 at 256; control 786) and, after the
  scissor fix, on ours (109,103 probes at 256; control 2,219). Margin 0 / 128 / 256 measured the
  same, 256 kept for bake latency.
- `treeCutawayScissor` (be50962 only): removed, it hid the cutaway at zoom != 1 (above).
- `driveTreeCutaway`: on = 42.21's rule; off (the default since 2026-10-03, maintainer's decision) restores 42.20's rule, the
  last 15-20 %.
- Tried and dropped: conditional rendering of the inside passes on an occlusion query of the mask draw (exact, but no
  measurable gain once the reach rule had removed most see-through trees).

## Results (desktop, 5120x2160, RTX 4090, same session, uncapped, defaults)

Daily drive (E:1200, kmh=193, max zoom, `instrument=true`, empty tab file), runs of 2026-10-03 on this branch's builds of the day (they differ only in dev rigs and the dropped conditional-render trial):

| run | fixes | mean fps | p99 | p99.9 | bakes | neighbour re-bakes |
|---|---|---|---|---|---|---|
| `r4221-full-off-1`, `-final-drive-off` | off (42.21 port) | 326 / 320 | 9.2-9.3 ms | 11.4-12.2 ms | 31.3-31.6k | 19.9-20.3k |
| `r4221-full-fix-1/2`, `-nocond-1`, `-cond-1/2` | on | 428-438 | 6.3-6.4 ms | 8.6-9.2 ms | 21.5-21.7k | 7.1-7.4k |
| `r4221-full-noaim-1`, `-final-drive-42.20rule` | on, `driveTreeCutaway=false` | 507 / 514 | 5.4-5.5 ms | 7.8-9.0 ms | 17.3-17.4k | 3.5k |
| daily history 09-25..09-27 (42.20.4 client) | - | 471-503 | | | ~20k | |

GPU 94-97 % busy in every run (GPU-bound). E:600 (21 s): 408 -> 526-531 fps with the fixes (margin 0 / 128 / 256 the
same), 556-588 with the 42.20 rule. Storm spin on foot (`final-spin` vs `-off`): 271.6 vs 271.8 fps, no change. Recorded
pair `final-drive-rec` / `-rec-off`: `harness/holes.py` enclosed black 1.38 % vs 1.45 % of the frame, the same dark
streaming edges at max zoom in matching frames.

MacBook Pro M1 Pro (apple-gl, `vsync=false`, `pzopt-defaults.ini`, same daily drive, runs `mac-r4221-*`, interleaved):
fixes off 96 / 115 / 119 fps (p99 20.6-24.1 ms, p99.9 27-32 ms, bakes 20-25k); fixes on 137 / 149 / 152 fps (p99
16.6-17.1 ms, p99.9 23.8-24.2 ms, bakes 12-14k); `driveTreeCutaway=false` 175 fps (p99 13.8 ms); the released 10-02 build
in the same session 118 fps (the Mac ran ~20 % under its 10-02 daily history all session, both builds alike). Reach proof on
Apple's GL, stock path: 0 violations in 31,012 kept-baked probes at margin 256, control 444.

Rigs: `--prop devTreePassCycle=7,6,5,3,0 devTreePassAlternate=500 gpuSections=true` (GPU cost per pass, sections
`translucent.m<mask>`), `--prop devReachCheck=true [treeCutawayReachPx=N]` (`reachProbes` / `reachViolations` in the bake
counters; `-100000` probes every see-through tree, the positive control; run it with `enabled=false` too, our path never
writes the mask), `--prop devStencilProbe=true` (stencil row / column at the player at three points of the frame, every
2 s in the console, with the GL state at the mask draw), `driveTreeCutaway=false` / `devXxlVehicleFade=false` (42.20's
rules). Bake counters gained `treeFlipsOn/Off`, `treeLevelInvalidations`, `treeNeighboursInvalidated`,
`treeRebakesSkipped`, `treeLingers`, `treeLingerRebakes`, `treeReachLeaves`, `treeReachTicks`, `perFrameTrees`,
`perFrameStencilTrees`.

## 42.21's edge-object wall tests (CPU side)

42.21 rewrote `IsoGridSquare`'s wall / window / door tests round new edge objects (`GridSquareEdge`, `isEdgeElementTo`
with `TriPredicate` / `BiPredicate` method references, `findObject` scans through `Type.tryCastTo`): `isBlockedTo` went
from flag checks and `instanceof` loops to megamorphic predicate calls. In the stock Louisville horde that path is ~3 %
of the game thread (`isEdgeElementTo` / `findObject`, new in the 25 Hz profile), in ours 1.5-2 %: our own
`SeparateMask.blocked` (zombie separation on the frame batch) and `IsoMovingObject.separate`, plus stock callers inside
`IsoGridSquare` (`testCollideSpecialObjects` from `testCollideAdjacent`, `getBendableTo`, `CollideWithObstacles`).

`edgeTestFast` (`pzopt.EdgeFast`) writes 42.21's `isBlockedTo` out (the same four edge cases in the same order, the
same neighbour lookups through the current cell, the same flags, `IsoWindow.isBlocked(facing)`,
`IsoDoor.isBlocked(facing)`, `IsoThumpable.isBlockedDoor(facing)`) for our two callers. `devEdgeFastCheck`: 23,864,066
calls in the horde, 0 different from the game's (runs `r4221-edgecheck*`). The saving is under 1 % of the game thread
and inside the horde bench's run-to-run spread (46-69 fps for the same settings). Per call, timed on the same
22.3 M calls (`r4221-edgetime`, timer overhead in both): written out 154 ns, the game's 181 ns; most of the cost is the
object scans and neighbour lookups both do, so the gain is small (~0.3 % of the game thread at most). The stock callers inside `IsoGridSquare` would
need that 10,000-line class overridden and are left.

The stock profile also shows `ECSComponent.getECSClass` under `getStateMachine` up from 3.4 to 4.9 % of the samples
(our `ecsLookupFast` memo covers it on the optimized path).
