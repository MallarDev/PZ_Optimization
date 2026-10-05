# White flat roofs flicker in the rain (2026-10-05)

Discord bug-reports thread "Flickering textures on white roofs when it's raining, and white horizontal stripes when
scrolling" (thread 1556294830517264414, 2026-10-04/05, two players with RTX 5070 Ti / Windows 11 and others, our mod only,
a new save too): after a few minutes of driving from the GigaMart (West Point, ~12020,6855) to the car wash (12050,7150) the
white corrugated flat roofs flicker; still there with the 2026-10-05 `floorDecalsPerFrame` release, on or off. A second
player sees it on roofs and road puddles.

## What it is

Frame by frame (the reporter's 60 fps video, our captures) whole chunk-sized parts of the roof light up in short white
horizontal dashes along the corrugation ridges for one frame and are gone the next. It is the puddle pass and the roof
trading places: a flat roof is the floor of its level (`roofs_04_*`, layer Floor, the corrugation in its depth texture), the
square draws puddles, and the puddle sits 1e-4 in front of the floor (`IsoPuddles`: `depthStart - 1e-4`). The roof's depth
lies right at that lift, so any error near 1e-4 decides texel by texel whether the wet layer or the bright ridges show.
Stock is steady (the puddle patches on the roof keep their stripes); clear weather with puddles pinned shows the same dashes,
so the rig needs no rain.

## Rig

`harness/roof-dashes.py` on 1:1 `devCapture` crops of the roof (60 fps) while the player walks circles in the parking lot
(`--flag start=12032,6904 --flag explore=circle`, Jev directing for the verdict runs, the autopilot for bisects; full
arguments in harness/CLAUDE.md): dashes = px > 40 brighter than two rows above and below in a horizontal run >= 6. Stock 33
px a frame (clear) / 49 (rain); the released build 1,741 / 2,803, 68-76 % of frames with >= 200.

## Two causes, both needed for the full effect

Bisect (one key off at a time, clear weather): `puddleCache=false` 263, `enhancementsEnabled=false` 247, both 14; no single
Enhancement mattered while the cache error dominated.

1. **The cached puddle depth kept an old camera jiggle** (`puddleCache` / `puddleVbo`, since 2026-09-20). Stock packs each
   vertex's depth at the corner moved by `fixJigglyModelsSquareX/Y` (up to ~0.06 of a square at max zoom), and the chunk
   composite shifts its `chunkDepth` by the same jiggle (`FBORenderChunk.renderInWorldMainThread`), so the two stay in step.
   The cache patched the jiggle into x/y but kept the build frame's in the depth: up to 1.7e-4 off, more than the lift.
   Fix `puddleJiggleDepth`: the depth falls by `CHUNK_DEPTH / 16` per square along x and y (linear across chunk edges), so the
   delta is exact; the VBO path packs it at zero jiggle and its earlyZ shader adds the frame's as a uniform. 1,741 -> 263.
2. **Foliage sway's rigid flag rounded every floor's depth.** The sway bake marks each texel's "sways" bit in the lowest
   DEPTH16 bit; rigid draws clear it (`d16 - mod(d16, 2)`), moving half the floor texels one step (1.5e-5) nearer. Small,
   but the roof's depth sits at the puddle's lift, and with the camera moving the composite's nearest-texel lookups walk
   over the rounded texels: dashes on alternate frames (709 / 24 / 887 / 20 px). `devSwayAlternate` (the composite's sway
   on and off each second, the bakes unchanged) changed nothing, `foliageSway=false` brought it to 61: the bake side.
   Fix `swayFloorExact`: floors bake with sway "off" (`pzSwObj.w` 0, the stock depth, a zero-weight attribute); an odd floor
   texel reads as "sways" to the composite and finds weight 0. 263 -> 29.

## Result

| run (Jev walking circles) | dash px a frame | p90 | frames >= 200 |
|---|---|---|---|
| rain, before (`rv-rain-before`) | 2,803 | 7,721 | 76 % |
| rain, fix (`rv-rain-fix`) | 33 | 49 | 1.5 % |
| rain, stock (`rv-rain-stock`) | 49 | 64 | 0.7 % |
| clear, before (`rv-clear-before`) | 2,118 | 5,990 | 75 % |
| clear, fix (`rv-clear-fix`) | 48 | 66 | 0 % |
| clear, stock (`rb-stock`, autopilot) | 33 | 46 | 0 % |

Jev (`roof-dashes.py --test/--before/--control`): rain fixed 0.71 (conf 0.63), clear fixed 0.92 (autopilot clear: 1.00).
Cost: `swayFloorExact` on / off on spin-uncapped (Rosewood grass, sway on): 118.7-119.1 vs 118.0 fps, GPU composite
151-173 us both; `puddleJiggleDepth` is one multiply-add per vertex on the CPU path, a uniform on the VBO path.

Video: `docs/media/roof-puddle-flicker-before-vs-fix.mp4` (`harness/stitch-roof-puddles.sh`): rain before | fix, the roof
close up 3x and 4x slower, clear before | fix.

Not checked separately: the second player's "road puddles". A road is a floor too and both fixes apply to every floor
with puddles, but no road rig was run.
