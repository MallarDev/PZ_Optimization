# Mirrors and windows: real reflections (2026-10-03, `mirrors`, pzopt.Mirrors)

Maintainer's request: "make in-game mirrors and windows have real reflections; secondary objective: virtually zero
performance cost; implement all the state-of-the-art solutions, don't discard any idea, implement and profile them one by
one". Worktree `~/pzopt-wt/mirrors`, branch `mirrors` (from origin/master 6d23257f).

## What stock does

- **Mirrors** are 44 tile sprites with the `IsMirror` property (`walls_decoration_01_*` wall mirrors, the
  `fixtures_bathroom_01_28/29` medicine cabinets, the `furniture_storage_01_40-43/64-67` dressers). Facing S / E shows the
  glass to the camera (a painted light blue-grey gradient with streaks); facing N / W shows the back. They bake into the
  chunk textures like any wall object. The only game use of `IsMirror` is the "look in the mirror" context menu.
- **Windows** are `IsoWindow` objects (`fixtures_windows_*`, the store fronts in `walls_commercial_*`, location sets),
  drawn per frame (translucent) after the characters; the glass is the sprite's translucent texels (alpha 0.39 on house
  windows, ~0.67 on store fronts, a white-blue tint), the frame opaque. Nothing in them depends on the world.

## The geometry

The camera is orthographic, 30 degrees down. In iso units (u = x - y, v = x + y - 6z from the window, iso depth
w = x + y + 2z, larger = nearer, z in levels) the view ray is (1, 1, 1/3) per square. Its mirror about a vertical plane is
the same for every pixel:

- a north-wall pane (plane y = c, facing +y) sends the ray off along (-1, +1, -1/3): on screen a straight line, 2 u and
  2 v per square (down-left at slope 1/2), the iso depth falling 2/3 a square per square;
- a west-wall pane (x = c, facing +x): (+1, -1, -1/3), down-right.
- the ray falls 1/3 of a level per square: a pane at height h above its floor reflects the floor 3 h squares out. A wall
  mirror (glass 0.3-0.9 levels up) shows the floor and whoever stands within ~2.5 squares; a ground-floor window the
  pavement 1-3 squares out; a first-floor window the street 4-6 squares out. Nothing farther is ever in a mirror.
- Fresnel at the iso angle is the same for every pixel (the view meets either wall orientation at 52 degrees):
  F(glass) = 0.049; a silvered mirror ~0.9.
- the pane point under a pixel follows from the plane alone (no depth read): north pane x = u + c, z = (u + 2c - v) / 6.
- a model point d in front of the pane appears at the pane point (lateral + d, height + d / 3), and behind the pane its
  iso depth is 8 d / 3 lower than the pane's: the composite turns the model layer's depth back into its distance.

## Design (final)

1. **Capture.** Windows draw per frame; mirror tiles are moved to the per-frame pass too (`FBORenderCell
   .pzoptPerFrameTranslucentTile`). While one draws, `TextureDraw.Create` hands its quad and texture to the capture: the
   quad is kept in camera-free iso units, the plane from the object (window north / west edge, mirror facing + how far the
   glass stands off the wall: cabinet 0.22, dresser 0.12, framed 0.03 squares). Same-texture quads are united (walls drawn
   in halves). A closed curtain on the camera side drops the window.
2. **Glass masks.** Windows: the sprite's translucent texels (alpha 0.1-0.97). Mirrors: an R8 atlas of the 24 visible
   mirror sprites (`src/media/ui/pzopt/mirrors/mirror-masks.png`, `harness/mirrors/masks.py`: light, grey, bluish for the
   white cabinet; largest blob, convex hull, eroded a texel).
3. **Static pass**, right after the chunk composite and the static-world passes (pixel light, AO, sun shadows), before
   any character is drawn, at most every 4th frame (`mirrorsStaticEvery`): last frame's panes whose **atlas tile** is new,
   came further on screen, or is due for its staggered 30-frame refresh. The ortho camera's reflected rays do not move
   with a pan, so a pane's reflection is kept in its own tile (RGBA8 2048^2: colour + hit distance; R8: its glass mask) and
   read through the pane's own coordinates. One instanced draw per sprite page, colour writes off; each glass pixel marches
   its reflected ray along the screen line through the world depth: floor first (its own floor at t = 3 h, then half a
   march's taps to see that nothing stands in the way), else taps every 10 px (at most 32) and 4 bisections, thickness
   0.8 iso-depth units; `imageStore` into the tile (no framebuffer switch). Windows march one pixel per 2x2 block.
4. **Model pass**, right after the moving objects: `TextureDraw.drawModel` marks each character / vehicle within 14
   squares of the camera whose reflection can reach a visible pane (distance d in front, lateral + d inside it, height
   + d/3 overlapping it); after its world draw its slot is queued; the flush draws each into an offscreen layer (colour +
   depth, scissored to the panes) with `Core.DoPushIsoStuff`'s model view post-multiplied by B^-1 R B (B the model's own
   scale / facing / offset, R the plane's reflection in the world frame), `glFrontFace(GL_CW)`; its lighting is computed in
   the model's frame and stays its own. The layer is kept while camera and models stand still (3 frames) and redrawn at
   most 120 times a second (`mirrorsModelHz`). Dev check (`devMirrorsLog`): the mirrored camera flips one model axis's
   depth step (-0.00433 -> +0.00433), the other keeps it: a true mirror image.
5. **Composite**, after every level's translucent objects (where the panes themselves were drawn; `mirrorsCompositeOnce`,
   per level when off): **one draw** for the frame's panes (the glass mask comes from the atlas, no sprite pages); the quad sits at the pane's analytic depth + a
   0.35 slack, so the hardware depth test (early fragment tests) hides whatever stands in front; then the tile's
   reflection and the model layer (nearer hit wins: the layer depth gives the model's distance, t = 3/8 of the iso depth
   below the pane) blended over the glass (window 30 %, mirror 90 %).
6. **Visibility feedback.** Every 8th frame the composite marks each pane that has pixels on screen (a plain store from
   one pixel in 16 into a device-local buffer, copied by the GPU into a persistently mapped, coherent buffer); the game
   thread reads it three frames later from the mapping, no GL call, no wait. A pane hidden under a roof or behind a
   building is neither marched nor mirrors anyone.

## Variants measured

### Offline, against an analytic ray cast (`harness/mirrors/march_sim.py`)

Synthetic iso scene (floor checker, north wall with a mirror, two boxes), 22,176 mirror pixels, 2x tiles at zoom 1.

| Variant | Mirror pixels wrong | Notes |
|---|---|---|
| gather march, thickness 1.5 iso-depth units | 22.5 % | rays passing *behind* a box from the camera's view count as hits: reflected objects come out too wide |
| gather, thickness 1.0 / 0.5 / 0.3 / 0.15 | 10.5 / 7.1 / 11.2 / 16.0 % | too thin: the floor under an object is missed and the floor-point fallback samples the object |
| **gather, thickness 0.8** (default) | **5.5 %** | the rest are object edges (any screen-space method's limit) |
| gather + floor first, 3 coarse taps | 12.8 % (55 % of rays shortcut) | coarse taps step over objects in the ray's way |
| gather + floor first, 7 / 11 taps | 7.8 / 5.6 % (46 / 43 %) | |
| **gather + floor first, half a full march's taps** (default) | **5.7 %** (43 % shortcut, no refinement) | floor rays at about half the taps |
| pixel-projected scatter (every visible pixel writes its mirror pixel, nearest wins; the water's method) | 33 % holes, 12 % wrong where written | a vertical pane magnifies the floor 2x along one screen axis: a forward scatter leaves every other row empty; the camera-visible faces are scattered where the mirror shows their other side. Would need 2-px splats to match the gather; rejected unless the gather proves expensive |

### In game

First cost run (`mir-cost1`, moving route through Rosewood, zoom 1, ~30 windows): on/off periods differed by
-264 us median / +0.4 us mean whole-frame GPU: the moving route's own variation (streaming, a different view each
second) swamps the effect. Costs are measured on a still camera and read from the per-pass GPU timers (`gpuSections`,
`mirrors.*`; their own floor is ~1 us, the timestamp resolution); whole-frame on/off deltas wobble +-30 us between
periods even on a still camera.

Desktop, RTX 4090, 5120x2160. Scene A: the bench start, still camera, zoom 2.5, 29 windows on screen. Scene B: the
Rosewood shop front, zoom 1, 28 idle zombies pinned 1.5-2.5 squares in front of its windows, the player 8 squares away.

| Step | Scene | static pass | model pass | composites |
|---|---|---|---|---|
| v1: screen-space static image, every pane every frame | A (`mir-cost2`, `-cost4`) | 22.5 us | - | 7-8 us (one per level) |
| v1, the march replaced by a constant store | A | 15.4 us | | |
| v1, constant store, no texture barrier | A | 15.4 us | | |
| v1, models every frame | B (`mir-cost3`) | (40 us when it runs) | **68.6 us** (24 mirrored zombies, the cap) | 9 us |
| pane atlas (marched when new / more on screen / every 30 frames), walking | Rosewood route, zoom 1 (`mir-cost5`) | 17.4 us on 1 frame in 3: ~6 us a frame | | 10 us |
| atlas, every pane every frame (dev 256), walking | same | 22.5 us every frame | | 9-10 us |
| atlas, no memory barrier (dev 128), walking | same | 14.3 us on 1 frame in 3 | | 10 us |

Reading: the march itself is ~7 us of the 22.5; the rest is the pass's fixed cost. It does not shrink with fewer pixels.

| Step (each on top of the previous) | Scene | Result |
|---|---|---|
| data uploads into one texture per frame and pass, disjoint rows (no driver wait on rows a queued draw reads) | | (part of the steps below) |
| glass masks into an R8 atlas: the composite reads no sprite, **one draw** (`b6`) | A (`mir-cost6`) | composite 7-9 us: the per-page binds were not its cost |
| render thread per pass (`mir-cpu1`) | A | static 7.2 us, composite 3.3 us: the GPU timers of these small passes count the GPU waiting for the commands (the GPU is ~25 % busy here) |
| static pass without its draws (dev 512) (`mir-cost8`) | A | 6.1 us, whole frame +12 us vs no static pass: the draws are ~21 us of 27.6 |
| **static cadence every 4th frame + atlas** (`mir-cost8`) | A | static pass on 1 frame in 10; whole frame **+4 us** vs no static pass |
| composite without the world colour / depth bound (they were bound, unread, while depth-testing against that buffer) | A | no change |
| composite occlusion by the depth test / a depth-texture read / none (`mir17-occl`) | A | 9.2 / 8.2 / 9.2 us: not the occlusion; the composite's ~9 us is fixed per pass |
| visibility feedback v1: R32UI counters read back through a PBO + fence (`mir-vis1`) | B | models 70.7 -> 24.6 us (11 of 24 zombies stand at visible panes), static marches 669 -> 217 frames; **but 137 us of render thread per composite** (every sync / map call waits for NVIDIA's threaded driver) |
| v2: counters in a persistently mapped coherent buffer, read by the game thread with no GL call (`mir-vis2`) | B | render thread 16 us; composite GPU 73 us: an atomic add from every pixel of a pane on one counter |
| v3: a plain store from 1 px in 16 (`mir-vis3`) | B | composite 16 us; whole frame +37 us (feedback off: +67) |
| framebuffer attachments cached per id, viewport re-read on a size / scale change only (a re-query every 120 frames was ~4.7 us a composite on average) | B | composite render thread 7.8 -> (`mir16-*`) 3.8-8 us |
| v4: counters device-local, copied by the GPU into the mapped buffer (walking: the host-memory stores grew the composite 12 -> 300 us as more panes came on screen) (`mir-walk3`, `-walk4`) | Rosewood walk | composite 21 us flat (11 without the writes) |
| v5: visibility written every 8th frame (`b16`) | | the clear + barrier + copy on 1 frame in 8 |
| model layer redrawn at most 120 times a second (`b18`, `mir18-hz`; still-model reuse off as if the crowd moved, 157 fps) | B | models drawn on 3,119 of 6,782 frames instead of 6,320: ~11 instead of ~23 us a frame |

**Final costs (`b16`, defaults, same-run on / off, desktop RTX 4090 at 5120x2160, capped 240 fps):**

| Scene | Whole-frame GPU vs off (median) | composite | static pass | models |
|---|---|---|---|---|
| walking south through Rosewood, zoom 1 (`mir16-walk`) | within noise (-15 us) | 12 us | 19 us on 1 frame in 5 | - |
| still, zoom 2.5, 29 windows (`mir16-still`) | +34 us | 9 us x 2 levels | 24 us on 1 frame in 16 | - |
| 28 idle zombies at the GigaMart front (`mir16-crowd`) | +39 us | 15 us | 27 us on 1 frame in 32 | 26 us on 1 frame in 3 |

Frame time at the cap: unchanged in every scene (median 6.369 ms on and off; the runs show a 157 fps cap from the bench's
own settings). Render thread: static 9-17 us a call, composite 3.8-8 us a call, model flush ~20 us + ~3 us per mirrored
model a drawn frame.

GPU-bound (uncapped, ~900-1,000 fps, `b18`): still camera with 29 windows on two levels (`mir18-unc-still`) GPU +27 us,
frame time +43 us median (1.084 -> 1.127 ms); the crowd scene (`mir18-unc-crowd`) GPU +25 us, frame +37 us. At the 240 cap
that is ~1 % of a frame.

One composite per frame after every level instead of one per level (`mirrorsCompositeOnce`, default on, `mir19-once`,
same still scene uncapped): GPU +13 us, frame time +34 us median (from +27 / +43). The cost of the merge: a translucent
object of a higher level that overlaps a lower pane on screen (and writes no depth) gets the lower pane's reflection
drawn over it; on a facade the upper panes stand right above the lower ones, so it takes a structure at another depth
(a glass balcony three squares in front of a ground-floor window). `mirrorsCompositeOnce=false` restores the per-level
order.

Variant `mirrorsStatic=late` (no static pass; the composite marches every pane every frame on the finished frame after a
texture barrier) (`mir17-late`, scene A): composite 16.4 us x 2, whole frame +45 us, and the characters' backs leak into
the reflection (the frame holds their camera side). The default is cheaper and right.

Upscaler: FSR at 67 % (world framebuffer 3413x1440 for a 5120x2160 screen, `mir17-fsr`): reflections line up as at 100 %.
Night (01:00, torch, `mir17-night`): the mirror shows the torch-lit room and goes dark outside the beam. Vehicles
(`mir16-cars`, car ring + placed mirrors): mirrored car bodies through the same model pass.

## macOS (GL 4.1 core, 2026-10-03)

The first design needed GL 4.3 (image stores, a storage buffer, buffer storage, texture barriers) and switched itself off
on the Mac's 4.1 core context (`macGlCore`). The GL 4.1 path (chosen at start from the context's capabilities; logged as
`mirrors: GL 4.1 path`):
- the static pass renders the due panes' tiles into the atlas **framebuffer** (two colour attachments: reflection RGBA8,
  glass mask R8) in tile space: each texel derives its pane point from the pane coordinates (`vertTile`); it reads the
  frame's colour / depth while another framebuffer is bound (no texture barrier, no image stores) and writes the whole
  tile (no clears). `mirrorsStaticFbo=true` takes the same path on GL 4.3 (not measured against the image stores yet);
- no visibility feedback (every pane counts as visible), no `mirrorsStatic=late`;
- plain `glTexImage2D` textures (no immutable storage), shaders `#version 410 core` (the 4.3 ones `#version 430`);
- `glPushAttrib` in the model pass is emulated by the core shim.
`harness/run-mac.sh install` now also copies `*.png` (the mirror masks were missing on the Mac).

MacBook Pro M1 Pro, 1920x1200 window (`macmir2`, `macmir-cost2`): all programs link (0 shaders failed), the facing check
passes, the bedroom mirror shows the room and the mirrored player as on the desktop. Bedroom scene (20 panes: 19 windows +
the mirror, the player mirrored), on / off every second: frame time median 9.80 vs 9.95 ms, within noise (the scene runs
~100 fps, bound elsewhere). Render thread per composite ~45 us (upload 15.5, set-up 10.5, draw 14.8: Apple's GL-over-
Metal call cost), static pass and model flush higher too; not visible in the frame time there.

## Not done / open

- **Per-model impostor tiles** (each mirrored model in its own atlas tile, refreshed at a lower rate and moved with the
  model between refreshes, as `pzopt.CarOccupant` does): not built. The model pass is ~2.5 us GPU + ~3 us render thread
  per mirrored model on a drawn frame; the still-model reuse, the 120 Hz cap, the 14-square range and the visibility
  feedback already cut the crowd case to ~11 us a frame. Worth it only if a scene with many people moving at visible
  panes shows up in a profile.
- **Mirrored static sprites** (the furniture in front of a mirror drawn again at its mirrored square with the opposite
  facing's sprite, the truly "other side" of the static world): not built. From the game's 30-degree camera a wall
  mirror mostly shows the floor, which the march gets right; furniture beside the mirror comes out with the side the
  camera sees.
- **Hi-Z march**: not built. A pane's ray is 4-32 taps over a few thousand glass pixels, and the floor-first shortcut ends
  ~45 % of them in half the taps; the water's Hi-Z build alone measured 150 us (findings-reflections-2026-09-25.md).
- Ran on the desktop (RTX 4090, Linux, NVIDIA, GL 4.3 path) and the MacBook (GL 4.1 path). Mesa (flip) and Windows are
  untested.
- Window strength (30 %) is a look choice: physical glass reflects ~5 % at the camera's 52-degree angle; the stock
  window sprite's own white-blue tint stays underneath.

## Walking round the mirrors of a real house (2026-10-04)

Maintainer: "use Jev to walk around all the mirrors in the house [of my latest save], analyze all the visual artifacts and
glitches in the reflection, loop until Jev confirms them fixed". Worktree `~/pzopt-wt/mirrorwalk`, branch `mirror-walk`.

**Rig.** `explore=mirror director=jev` (`pzopt.MirrorWalk` + `harness/explore-director.py`, scene `mirror`): every mirror
of the building the save starts in (save `Sandbox/2026-10-02_10-30-09`, house 11243,6861-11252,6873: the wall mirror
`walls_decoration_01_9` overlay on 11249,6864 and the medicine cabinet `fixtures_bathroom_01_29` on 11249,6871; the
template must be refreshed, `--refresh-template`, or the walk loads an older copy of the save), Jev walks the player on
foot from mirror to mirror and over the stations in front of each (face the mirror, back to it, one lap), ~33 s for the
house. Dev view 5 (`devMirrorsView=5`, toggled with the picture every second) paints each glass pixel by how its static
ray ended; `harness/mirrors/kinds.py`, `walkframes.py`, `mirror-judge.py` (Jev). The first walk (a neighbour's house,
`find=mirror`) and the first passes of the director (it alternated face / turn every 0.3 s and ping-ponged between
visited stations) are not counted.

**What was wrong** (screen-space limits that showed as glitches; the reflected player and the floor were right):

| # | Seen | Cause | Fix |
|---|---|---|---|
| 1 | grey horizontal bands over the wall mirror's upper half | rays whose floor landing the dining table / chairs hide from the camera found no hit and took the pixel at their reach end, one level *under* the floor, 5-6 squares away: the house's siding | the hidden landing gets a stand-in: the last floor pixel the ray passed over (the floor goes on under the table) |
| 2 | the medicine cabinet a dark brown slab | its rays marched on under the bathroom floor (`zMin` = level - 1) and "hit" the cut-away outer wall's brick strip | mirrors and ground-floor panes end their rays at their own floor (`d[o + 14]`) |
| 3 | (intermediate state, fix 1 alone with the landing pixel as stand-in) the dining table's grey top as one flat slab over half the wall mirror | the hiding object's own colour stretched over every hidden ray | the last floor seen stands in, not the hiding object |
| 4 | the cabinet still showing the brick | a "hit" in the last 0.15 levels above the floor: the 0.8 thickness takes the paper-thin wall stub for a solid | such near-floor hits continue the floor like a hidden landing |
| 5 | the cabinet a flat lavender pane (Jev: the cabinet the least fixed) | three quarters of its rays are stand-ins (its view across the bathroom is hidden by the bathtub; the floor seen last is the bath mat), drawn at full strength | stand-ins carry a flag (alpha low bit) and draw at `mirrorsStandInPct` (50): the glass's own look shows under the guess |

**Measured** (`mirror-judge.py`, whole-house walks of 33 s, dev view 5 toggled with the picture, windows off; before =
`devMirrorsSkip=98304`, mid = `65536`, after = defaults; runs `mw-final-before-20261004-024226`, `mw-final-mid3-20261004-025353`,
`mw-final-after4-*`, stock reference `mw-final-after5-*` with `devMirrorsAlternate=700 devMirrorsCycle=0,4`):

| Mirror | measure | before | mid | after |
|---|---|---|---|---|
| wall mirror | glass from a pixel squares away (1) | 46 % | 0 | 0 |
| wall mirror | hits under its own floor (2) | 3.4 % | 0 | 0 |
| wall mirror | hidden landing drawn in the hiding object's colour (3) | 0 | 54 % | 0.03 % |
| wall mirror | largest flat region in the picture / of the stock glass, same frames | 0.03 / - | 0.28 / - | 0.07 / 0.41 |
| medicine cabinet | glass from a pixel squares away (1) | 40 % | 0 | 0 |
| medicine cabinet | hits under its own floor (2) | 2.1 % | 0 | 0 |
| medicine cabinet | glass brightness, luma (4: brick 77 vs the bathroom) | 77 | 119 | 128 |
| medicine cabinet | guessed glass x drawn strength (5) | 0.40 | 0.71 | 0.36 |
| medicine cabinet | largest flat region in the picture / stock glass / the scene its rays pass over | 0.29 / - / - | 0.64 / - / - | 0.78 / 0.90 / 0.06 |

Jev (`mirror-judge.py`): artifact 1 fixed 0.96, 2 0.95, 3 0.97, 4 0.93, 5 0.46; overall `partly_fixed` 0.55 (all_fixed 0.41).
Jev's remaining doubt is artifact 5: the cabinet's picture is no flatter than its stock glass, but much flatter than the
bathroom it faces (0.78 vs 0.06): two thirds of its rays have their floor landing hidden (the bathtub, the cut-away wall
stub in front), and what stands in is one floor pixel per ray, faded to half over the stock glass. The surface a ray went
behind as the stand-in (tried: `mw-final-after6*`) made the wall mirror flatter (0.07 -> 0.28) and the cabinet no better
(0.79), reverted. What would fix it is geometry the frame does not hold (the bathtub's side, the room's far walls seen
from the mirror): the "mirrored static sprites" item below, or a second view.

Dev skip bits for A/Bs in one build: 32768 (the old ray end and stand-in), 65536 (the hiding object's colour as stand-in).
Dev view 5 makes every pane re-march every pass frame (it paints the tiles themselves). The model layer was checked with
dev view 4: the mirrored character is drawn whole; a body that seems missing is the iso geometry (a point d in front of a
north pane shows d further along it, so standing in front of the glass puts the reflection off its side).

## The room behind the glass: mirrored geometry (2026-10-04, `mirrorsGeometry`, `pzopt.MirrorGeometry`)

Maintainer: "build the geometry for the cabinet mirror". The walk above left the medicine cabinet a guess: two thirds of its
rays land where the camera cannot see (behind the bathtub, on the cut-away wall's room side). The frame holds no more, so
the mirror's room is now rebuilt from the game's own tiles and drawn into the pane's place before the march.

- **A reflection in a vertical plane = a quarter turn + the screen's left-right flip** (x <-> y swaps u = x - y for -u, keeps
  the iso depth x + y + 2z). So an object's mirror image is the sprite of its *turned facing*, flipped, at the plane's image
  of its square. The game links every movable's four facings (`<F>offset` properties, the sprite grids of multi-square
  ones), so the bathtub's far side is a sprite that exists. West-wall plane: E -> N, S -> E, W -> S, N -> W, grid piece
  (gx, gy) -> (gy, W - 1 - gx); north-wall plane: E -> S, S -> W, W -> N, N -> E, (gx, gy) -> (H - 1 - gy, gx).
- What the plane maps onto itself keeps its own sprite, unflipped, at the mirrored square: floors and rugs, walls across the
  plane, and objects without a turned facing that hang on a square edge (door frames, light switches, wall trims: they
  mirror as a wall of that orientation; drawn mid-square they were up to a square off, `mw-geo3`). Walls along the plane
  are skipped (the mirror's own wall stands behind the glass).
- **The far wall** (the one facing the mirror, whose room side the camera never sees): the paint of the mirror's own wall (a
  plain wall of the same orientation found along it, with its trims) at the plane's image, every texel at the wall's
  distance; a window or a closed door in it drawn as itself 0.03 squares in front of the paint (an exterior wall's frame
  sprite is its siding: skipped, the paint shows round the window).
- **Distance per texel** from the tile's depth map (as the stock `tileWithDepth` shader: w = x + y + 2z + 4 d between the
  square's far and near corner; floors the Floor preset), t = 3 / 8 (wM - w) for the pane point under the atlas texel.
  The texels go into a geometry atlas (same tile layout as the static atlas, RGBA8 + depth, nearest t wins), drawn on
  the render thread right before the static march, only for the panes due that pass. The march then takes the geometry
  where it found nothing, a stand-in, or a hit more than 0.25 squares farther than the geometry (it passed behind
  something), and keeps its own hit, the frame's real pixel, where the camera sees what the ray meets.
- Bounds: the mirror's room only (same `IsoRoom`), within 3 h + 1.5 squares (nothing farther reaches the glass), the
  mirror's own level; no geometry for windows or mirrors outdoors. Cost: the instances are built on the game thread only
  for a pane due for its march (new / every 30 frames), 7-11 tiles a mirror in this house; ~32 MB of VRAM for the atlas.
- Dev: `devMirrorsView=5` paints geometry texels cyan (`harness/mirrors/kinds.py` class `geometry`), `devMirrorsView=6`
  shows the geometry alone, `devMirrorsLog` lists each pane's tiles once (`turned->turned/flip@x,y`), skip bit 131072 =
  geometry off for that moment (same-run A/B: `devMirrorsAlternate=1000 devMirrorsCycle=256,131328`).

**Measured** (whole-house walks, dev view 5 toggled, `mw-geo4-*` against the released build's `mw-final-after4-*`; Jev with
`mirror-judge.py`, before = `mw-final-before-*`, stock reference `mw-final-after5-*`):

| Mirror | measure | released (fc866c5) | geometry |
|---|---|---|---|
| medicine cabinet | guessed glass x drawn strength | 0.36 | 0.005 |
| medicine cabinet | glass from the room geometry / the frame's floor / marched hits | - / - / - | 82 % / 12 % / 5 % |
| wall mirror | guessed glass x drawn strength | 0.26 | 0.008 |
| wall mirror | glass from the room geometry / the frame's floor | - | 74 % / 24 % |

(The released build's weights are its guessed shares at the 50 % it draws them; the judge's `mid` column counts them at
100 %: 0.73 / 0.52.) Jev: artifacts 1-4 stay fixed (0.87-0.96); the guess-pane artifact 0.37 "fixed": its own measure (the picture's largest flat
region, 0.59) is unchanged, because the cabinet, in a bathroom two squares wide, mostly faces a plain painted wall with
the tub's rim under it, which the picture now shows. Same-run A/B (`mw-geo5-*`): geometry on = the far wall's paint, the
window in it and the tub's white rim; off = the floor seen last at half strength over the stock glass.

Not done: the far wall's own interior sprite where it has one (the paint of the mirror's wall stands in for it, so a room
with two colours of wall shows the mirror wall's), mirrored objects on the far wall (pictures, switches), overlays on
furniture (items on a counter), lighting beyond the square's light colour (no pixel light, AO or shadows on the geometry).

## Rigs

- `harness/mirrors/tiles.py` (tile definitions by property), `packsprite.py` (sprites out of the .pack files),
  `masks.py` (the mirror mask atlas), `march_sim.py` (the march against an analytic ray cast on a synthetic iso scene:
  thickness 1.5 -> 22 % of mirror pixels wrong, 0.8 -> 5.5 %; the rest are object edges).
- harness `find=mirror` / `find=window` (`find_side=out|in`, `find_dist`, `find_offset`): the player where his own
  reflection falls on the nearest mirror / window, facing it.
- `devMirrorsAlternate`, `devMirrorsView` (1 reflection only, 2 mask / static hit, 3 hit distance, 4 model layer, 5 how
  each static ray ended),
  `devMirrorsSkip` (1 static pass, 2 model pass, 4 composite, 8 constant colour), `devMirrorsLog`; GPU sections
  `mirrors.static`, `mirrors.models`, `mirrors.late`.
