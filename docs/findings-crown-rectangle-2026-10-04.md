# Striped rectangle on a torch-lit tree crown (2026-10-04)

Maintainer report, 2026-10-04 19:31: at night in the rain, with the torch on (save `Sandbox/2026-10-02_10-30-09`,
player at 11235,6851), a straight-sided rectangle over the right part of an orange crown, holding thick dark
horizontal bands and every other pixel row dark. The rest of the crown was clean.

## Repro and bisect

`--mode bench --source-save Sandbox/2026-10-02_10-30-09 --refresh-template --vmarg -Dpzopt.userOptionsFile=<the
player's options.ini> --flag start=11235,6851 --flag route=S:1 --flag speed=0.033 --route-seconds 8 --flag torch=on
--flag time_of_day=1 --shot-at 5` showed the same rectangle on the first run (run `treelines-repro`).

| Run | Change | Rectangle |
|---|---|---|
| treelines-noppl | `pixelLight=false` | gone |
| treelines-notreebake | `treesInChunkTexture=false` | gone |
| treelines-noshadow | `pplShadows=false` | still there |
| treelines-norelief | `relief=false` | still there |
| treelines-nosway | `foliageSway=false` | still there |

The dev views separated it: `devPplView=16` (the lit point) was smooth across the whole crown, so the depth and the
world position were right; `devPplView=15` (the level the light is read from) was level 1 on the crown and alternated
between 0 and 1, in bands and row by row, inside the rectangle only.

## Cause

A baked tree is drawn into its own chunk texture and, by the tree pass (`pzopt.TreeBake`), as an identical copy into
every neighbour texture its sprite reaches. Where the two textures overlap on screen the two crowns have the same depth
ramp, so the depth test picks a winner row by row (DEPTH16 rounding: a beat pattern of thick bands and alternating
rows). `PixelLight.selectLevels` sent each chunk draw the levels its texture holds, `getTopLevel()` = min(min + 1, the
chunk's top level), and the shader clamps the level it reads to that. The tree's chunk has a level 1; the neighbour is
single-storey, so its copy read level 0 at the crown's points: the ground's light. The rectangle is the neighbour
texture's edge. Without pixelLight both copies carry the same baked colour, so the tie never showed.

## Fix

With `pplAirFill` (default on) the lattice packs every chunk's level above its top, so a texture's two levels are
always readable: `selectLevels` sends the texture's own range (min, min + 1). Every copy of a crown reads the same
level. Runs `treelines-fix` (zoom 1) and `treelines-fix-z05` (zoom 0.5): the crown is whole with the torch's falloff.
Whole-frame diff against `treelines-repro`: besides the crown, an eave along a single-storey house that changed tint at
a chunk border is now one tone (the same clamp, a wall's top row in a chunk without a level 1).
