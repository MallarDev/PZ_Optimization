# Wall cabinets and their depth (issue #38, 2026-09-29)

Report (Linux, c316dbc): with per-pixel lighting the kitchen's upper cabinets show a light seam through their doors, and
the reporter's dump of the depth map is "just a cube"; a comment adds that with ambient occlusion the cabinets are too
dark at their edges and where sections meet, some cabinets fine.

## Cause

- The dump is byte-identical to the game's `media/depthmaps/DEPTH_fixtures_counters_01.png`, which holds two boxes only.
  `tileDepthTextureAssignments.txt` maps 88 tiles to its cell 16 (`fixtures_counters_01_16`): 71 of `fixtures_counters_01`,
  8 of `location_hospitality_sunstarmotel_02`, 8 of `location_trailer_02`, every one a "Floating" wall cabinet
  (`MoveType=WallObject`, `ContainerPosition=High`, straight and corner pieces, four facings).
- Cell 16 is `tileGeometry.txt`'s box -0.5..0.5 x 1.8..2.4495 x -0.5..0.5: the whole square, up to the ceiling. The sprites
  are 0.55 squares deep against one wall (corner pieces an L). Stock only sorts with the depth, and its tile shader discards
  sprite texels outside the box (`OpaquePixelsOnly` tiles: texels under alpha 0.8 too), so every cabinet pixel lies on that
  box: the upper half of a door on its top, the lower half on its front a full square out from the wall.
- Per-pixel lighting rebuilds each pixel's position from that depth. The top sits on the ceiling plane, where
  `pplLight` takes the level above (a texel within 0.006 levels under a level, then the brighter of the two: meant for a
  wall's top row), i.e. the roof's daylight: the pale band through the doors, and in an unlit room white doors with black
  triangles. Ambient occlusion sees a box sticking a square out of the wall and shades round it.
- The Spiffo's cabinets (`location_restaurant_spiffos_03_32/36/44/45`) have fitted half-square boxes of their own and were
  the ones that looked right.

## Fix (`tileDepthFix`, `pzopt.TileDepthFix`)

`harness/tiledepth/fit-boxes.py` reads every affected sprite from `Tiles2x.pack`, keeps the texels the depth shader writes,
and fits one box against the wall behind the doors (`Facing` S = north wall, E = west, N = south, W = east) or an L of two
for a corner piece: depth 0.40-0.70, inset, bottom height, trimmed ends, IoU 0.942-0.985 (check sheet `--sheet`). Its
projection is `TileGeometryUtils`' ortho view (IoU 0.98 against the shipped cell 16). The table goes into
`TileDepthFix.BOXES`; at run time the game's own box depth draws them on exactly the stock box's texels (the stock depth where
the boxes miss, ≤ 3.1 % of a sprite's opaque texels), no texel nearer than 0.04 units under the ceiling (0.1 left AO smudges
on the tops). 17 distinct textures, ~130 ms once. `auto` (default) = only while pixelLight, AO, sun shadows or reflections
read the depth, so the plain game sorts as stock.

## Runs (desktop, zoom 1, 12:00 clear, `--shot-at`, runs `cab-*`)

- Rosewood kitchen cabinet (`find=cabinets`, 8186,11549), pixelLight: fix off = a lighter diagonal band across the upper
  left of the doors (the box top), fix on = even doors (`cab-ppl-fixoff` / `cab-ppl-fixon`).
- Same cabinet, AO: fix off = a dark halo over the tiles and wallpaper left of and under the cabinet, fix on = shading
  close to the cabinet; nothing else in the frame changed (`cab-ao-*`).
- Rosewood medical room row (8083-8090, 11522, corner pieces), pixelLight + AO: fix off = in the unlit room white upper
  doors, black triangles; first fix = even doors but pale tops (the ceiling-plane rule); tops 0.1 under = right light but a
  one-pixel bright line on each top edge (front rows still within the tolerance) and AO smudges on lit tops; final (every
  texel ≥ 0.04 under) = even, room-lit cabinets (`cab-row-fixoff`, `-fixon`, `-fixon2`, `-fixon3`).
- Live switch (`live_set=ambientOcclusion=true@2`, every feature off at boot): nothing swapped at boot; at the switch the
  textures built on the game thread and the re-bake ran before their upload: the cabinets were missing from those chunk
  textures (`cab-live-ao`). The swap now waits for the render thread (`cab-live-ao2`: cabinets drawn).

## Store canopies (follow-up, same day)

The maintainer saw artifacts on the store canopies (awnings) zoomed out. Max-zoom shots at 8091,11526, pixel-aligned,
measured per region against the same shot with pixelLight and AO off (`harness/tiledepth/region-judge.py`, Jev): Denny's
canopies at 0.69 / 0.87 of the reference's luminance with 54 % / 22 % of their pixels 25+ levels darker (dark bands across
the lower stripes), the bakery canopy 0.85 / 29 %; brick wall and sidewalk controls 0.97-1.00. Identical with the cabinet fix
on and off (Jev: pre_existing, 0.89). Cause: 52 of the 84 canopy tiles borrow flat boxes up to the ceiling
(`walls_decoration_01_68/69/84/85/86/144-147`) and are Translucent without OpaquePixelsOnly, so the stock shader writes the
box's depth on the canopy's transparent texels too.

Two fixes, run twice each (runs `can-*`, `can2-*`, 5120x2160, uncapped):

| option | Denny's L / R, bakery (ratio, darker share) | fps (two runs) | build at load |
|---|---|---|---|
| base (cabinets only) | 0.69 / 0.87 / 0.85; 54 / 22 / 29 % | 350, 313 | ~190 ms |
| A `tileDepthCanopies`: fitted slabs + the sprite's alpha mask | 0.73 / 0.92 / 0.87; 49 / 11 / 22 % | 352, 292 | ~390 ms |
| B `tileDepthCeiling`: every texture's top rows under the ceiling | 0.69 / 0.88 / 0.84; 54 / 21 / 28 % | 341, 278 | ~190 ms |
| A + B | = A | 314, 348 | ~360 ms |

Jev (numbers only): looks best A (0.80), B helps no (0.17), A+B better than A no (0.07), ship A (0.81). The frame rates
overlap within the ±12 % run-to-run noise (per-frame work is the same by construction: only the bound depth texture
changes); Jev read a difference there (0.81), the two runs of each option do not support it. A is on by default (inside
`tileDepthFix=auto`: only with a depth-reading feature on), B stays off. `harness/tiledepth/fit-canopies.py` fits 32 straight
canopies (IoU 0.88-0.93; domes, curved hoods and the pie / crepe restaurants' sets fit under 0.85 and keep the stock depth).
No fitted texel is nearer than the stock box: Denny's valance hangs 0.05 squares past the square, up to 9 depth steps in front
of it, where stock sorts a character's head in front. What is left on the canopies (Denny's left at 0.73) is the ambient
occlusion against the wall, which the reference has none of; the dark oval on the pavement next to them is the tree crown's
AO (issue #40), unchanged by every option.

## Canopies, second pass (the maintainer still saw artifacts)

Zoomed crops with difference maps against the features-off shot showed three things left after the first canopy fix: dark
tongues at the valances' scalloped ends and a line along some top edges (sprite texels the fitted shape misses fell back
to the borrowed box's depth), the book naked canopies' crease (curved domes, `walls_decoration_01_118`, not fitted), and a
general darkening that splits by feature as AO 0.91-0.94 / pixel light 0.96-1.00 of the reference.

- Missed sprite texels now take the fitted surface's depth: the fitted texel above in the column continued down at a vertical
  face's depth step (a valance hangs straight), else the nearest fitted texel; the stock box only when none is near.
- `fit-canopies.py` also tries the outward half of an ellipsoid on the wall line (ray-cast per texel, the game's projection;
  Java: two plane hits give the texel's ray, a plane through the hit point its depth; Java and Python agree texel for texel):
  9 domes more (86, 102, 118, 130, 146, 154, 162, the pie / crepe restaurants' 5), 41 canopies in all. Bare frames and corner
  wedges (no depth texture of their own, IoU under 0.8) keep the default depth.
- `aoEdgeAware` (ChunkAo's multiply reads the half-resolution AO weighted by depth similarity): no change at max zoom (the
  composite shows the mip levels, which keep the plain read), small elsewhere; off.

Runs `can-v3` (current), `can-fill`, `can-ppl` / `can-ao` (feature split), `aoedge1-*`. Edge error (mean |gradient difference|
vs the reference, seams and creases) went down on Denny's right 6.42 -> 3.03, bakery 6.17 -> 5.59, striped 5.76 -> 5.30, book
naked right 4.01 -> 3.71; book naked left 3.08 -> 3.82 (worse); controls 0.39 / 1.02 unchanged. Jev: current build best
(0.91), creases reduced (0.93), artifacts remaining (0.97): the canopies still differ from the no-AO reference clearly more
than plain surfaces do. What is left is mostly the AO the reference does not have (contact shading under and behind each
canopy, blended into the canopy by the mip levels at wide zoom) and the unfitted canopy types.

Rebased onto 3a33c68 (2026-09-29): upstream's `pplDepthOpaqueOnly` (the shelf-blink fix, 0b7f075) makes stock's
tileWithDepth write depth only where the sprite is not fully transparent when pixelLight is on, for every tile. The canopy
textures' sprite-mask trim does the same for the fitted tiles and still matters with AO or sun shadows and pixelLight off.
