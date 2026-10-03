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

## Rigs

- `harness/mirrors/tiles.py` (tile definitions by property), `packsprite.py` (sprites out of the .pack files),
  `masks.py` (the mirror mask atlas), `march_sim.py` (the march against an analytic ray cast on a synthetic iso scene:
  thickness 1.5 -> 22 % of mirror pixels wrong, 0.8 -> 5.5 %; the rest are object edges).
- harness `find=mirror` / `find=window` (`find_side=out|in`, `find_dist`, `find_offset`): the player where his own
  reflection falls on the nearest mirror / window, facing it.
- `devMirrorsAlternate`, `devMirrorsView` (1 reflection only, 2 mask / static hit, 3 hit distance, 4 model layer),
  `devMirrorsSkip` (1 static pass, 2 model pass, 4 composite, 8 constant colour), `devMirrorsLog`; GPU sections
  `mirrors.static`, `mirrors.models`, `mirrors.late`.
