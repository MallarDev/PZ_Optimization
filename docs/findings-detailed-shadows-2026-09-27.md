# Detailed shadows (2026-09-27)

Asked by the maintainer: "detailed shadows based on whatever is casting them (trees, zombies, characters, buildings,
vehicles, animals); sun shadows should not clip on the floor and should be consistent in where they are projected (a
tree should not have two shadows, one for the base and one for the foliage, but one continuous detailed shadow);
secondary objective: virtually performance hit free; state of the art, don't discard any idea, implement them one by one
and profile". Worktree `~/pzopt-wt/shadows`, branch `detailed-shadows`, on master efad94d (god rays merged). Runs on the
desktop (RTX 4090, 5120x2160) through the queue, labels `sh-*`. Builds on the sun shadows of
`docs/findings-contact-shadows-2026-09-25.md` and `docs/findings-sky-2026-09-26.md`.

## What was wrong (baseline runs sh-base-*, sh-plant-17-proxy)

1. **A tree had two shadows and neither was its shape.** The crown proxy (an ellipsoid on the tree's camera-facing card)
   cast a soft oval detached from the trunk, which cast nothing; the sky term under the crowns (`aoTreeCanopyPct`) put a
   second dark oval straight under every tree. A tree sprite also carries a painted ground shadow round its foot.
2. **Characters, zombies and cars cast two shadows**: the stock blob under the feet (and the stock car shadow) stayed
   under the sun shadow beside it. The sun shadow itself was ten capsules (a stick figure), blurred into a blob past a
   square and a half by the 3 degree sun (`sunShadowSoftnessPct` 100). **Animals cast no sun shadow** (their skeletons
   lack the human bones the capsules need): only the stock blob.
3. **Shadows cut on the floor.** (a) A texture with nothing but floor in its 3 x 3 chunks is "bare" and takes no sun term
   at all: a building's or a tree's long evening shadow across open ground stopped on the bare chunk's edge. (b) A
   compute sees at most the 16 nearest trees within two chunks: in a wood some textures dropped a tree others kept, so its
   shadow ended on a texture edge. (c) A character's shadow quad ended on its own floor: from a porch or balcony the part
   falling to the ground below was cut. (d) A chunk's trees were cached for 600 frames: a planted or felled tree's shadow
   came or went only at the next sun step (and never on textures that did not bake again).

## What changed

### Trees: one shadow from the sprite itself (`sunShadowTreeCards`, pzopt.TreeSilhouette)

- Every distinct look of a tree (its sprite and its season's foliage overlay) is drawn once into a layer of an R8 array
  texture (64 layers of 512 x 512, mipmapped): the coverage of the whole sprite frame (trunk, branches, leaves). The
  painted ground shadow is left out (half-transparent black texels).
- The chunk AO kernel intersects each receiver's ray towards the sun with a **card through the tree's foot turned to face
  the sun** and reads the layer there (how far along the card, how high: the sprite's own scale, 45.25 px a square across,
  39.19 px a square of height). One continuous shadow from the trunk's foot to the crown's top with the gaps between the
  leaves; the card turns with the sun, so the shadow never collapses to a line (the camera-facing card did). The penumbra
  is the silhouette's mip level at the penumbra's width there (2 t tan a).
- The crown proxy stays for the tree's own shading (its trunk and lower crown in its crown's shade) and the sky term.
- Trees take part when their shadow can reach the texture (their foot to where the top's shadow lands, widened by the
  crown, `sunShadowTreeReach` 3 chunks), up to 32 per compute (was the 16 nearest within 2 chunks).
- The sky term under the crowns fades with the sun's strength (`1 - 2 x strength`, at least 0.1): in full sun the oval under
  every tree read as a second shadow at the foot; overcast, at dusk and at night it is whole.
- A bake that changes a chunk's geometry re-reads its trees; when they differ, every texture in the trees' shadow reach
  computes again (a planted / felled tree).

### The sun's size (`sunShadowSoftnessPct` 100 -> 25)

A 3 degree sun blurred every shadow past a square from its caster into a soft shape (tree crowns into ovals, a body's
arms and head into a stick). 25 % (0.75 degrees, ~3x the real sun) keeps a tree's branches and leaves and a body's limbs;
the penumbra still grows with the distance from the caster. The Options entry offers 10 (the real sun) to 200.

### Bare textures (the floor clip)

A texture is bare (no sun term) only when no wall, solid object, upper floor, roof or tree stands within the far field's
reach (4 chunks) either, not just in its 3 x 3 chunks.

### Characters, animals, vehicles: their own models from the sun (`sunShadowMeshes`, pzopt.ShadowAtlas)

The shadow of what the model draws (limbs, hair, clothes, bags, weapons; an animal's legs, head and tail; a car's body,
wheels and bars), seen from the sun, not the camera. Per caster a 128 x 128 tile of a 2048 x 2048 DEPTH32F atlas:
- the draw: right after a caster's model draw (TextureDraw's DrawModel) the draw is queued (its ModelSlotRenderData stays
  valid until the frame's postRender); at the start of the screen composite (MultiTextureFBO2.render, after the world
  pass) one flush binds the atlas, clears the drawn tiles with one instanced quad draw at the far depth and runs
  `ModelSlotRenderData.render()` again per caster with `ModelCamera.instance` swapped for `SunCamera` (an orthographic
  view along the sun of the model's own world frame: x west, y up, z north, squares; the character's facing, the 1.5 model
  scale; a vehicle's scale and centre of mass). `squareDepth` is set so the shaders' `targetDepth` is 0.5 (no offset); the
  game's tracked GL state (GLStateRenderThread, re-applied after every part) is set for a depth-only draw and put back.
  Core.DoPushIsoStuff's -0.48 model offset is left out: with it the feet stood 0.72 squares under the bones.
- the lookup (the caster pass): the receiver in the tile's view, a blocker search (four textureGather of 2 x 2 depths over
  the widest penumbra it could have), the blockers' mean distance gives the penumbra's width, 12 compared (bilinear PCF)
  Poisson taps over it.
- tiles are kept per caster from frame to frame: a caster's pose is drawn again `sunShadowMeshHz` (15) times a second on
  average, in bursts of at least `sunShadowMeshBurst` (6) draws (a flush's fixed cost shared), new casters at once; the pass
  places the last pose at the character's position now (only the pose lags), the tile's parameters double-buffered
  (scheduled -> drawn at the frame's end -> read by the next frame's pass).
- 2026-09-29, the maintainer: the shadows looked choppy next to the character (a 15 Hz pose one frame late under a model
  animated every frame). New Enhancements setting "Sun shadows: update rate", `sunShadowRate` (live): `frame` (default,
  recommended) draws every caster's pose again every frame, at most `sunShadowMeshFrameBudget` (32) a frame; past it the
  casters take turns (a drawn one waits casters / budget frames) and a player's own shadow never waits; with
  `sunShadowMeshSameFrame` (on) the flush is queued right before the caster pass (the models are all drawn by then;
  `ShadowAtlas.FLUSH_WORLD` restores the pass's cached world framebuffer, not TextureFBO.lastID) and the tile's parameters
  go straight to the pass, so the shadow shows the pose the model shows in the same frame. `15` (any number: that many a
  second) is the earlier behaviour: `sunShadowMeshBudget` (12) / `sunShadowMeshBurst`, flushed at the composite, read a
  frame later (it replaces the dev key `sunShadowMeshHz`). Desktop, the sh-cost-mesh8b scene (40 zombies, 8 animals, the
  car, uncapped ~850 fps, `devSunAlternate=1000`, `harness/shadows/abframes.py`, paired): 15 a second +27 +- 9 us a frame
  (GPU +31, shsync-cost-old), every frame + same frame +91 +- 2 us (GPU +87, shsync-cost-new), every frame with the flush
  left at the composite +129 +- 15 us (shsync-cost-nosame): the extra cost is the redraws, the flush in the middle of the
  world costs nothing more. ~1.5 % of a 240 fps frame. Recordings shsync-rec-old / -new (60 fps capture, a walking
  zombie's shadow box, mean abs change a frame): 15 a second 0.00 for 3-4 frames then a 0.3-0.5 jump (42 of 89 frames
  steps); every frame 0.01-0.16 each frame (1 of 89). Live switch (shsync-live, `live_set=sunShadowRate=15@5,
  sunShadowRate=frame@10`): unchanged shadow frames 4-5 % -> 60-70 % -> 3-16 %.
  The flip (~25 us of render thread a sun draw) is not measured yet: up to 32 draws is ~0.8 ms of its render thread.
- costs found and removed on the way: a glGet per caster (the bound framebuffer / viewport / scissor; ~90 us each on
  NVIDIA's threaded driver: the world framebuffer from a TextureFBO.lastID cache as GodRays does, the viewport on the
  attribute stack); a render target switch and a scissored clear per caster in the middle of the world pass (~30 us of GPU
  each: the flush batches them after the world pass).

### Characters, animals, vehicles: the pass (`sunShadowSilhouette`, pzopt.CapsuleShadow)

- The caster pass is drawn after the moving objects (it used to run right after the chunk composite, before them): the
  scene depth then holds the casters' own drawn surfaces (models: their depth is in the sprites' depth units, 0.00236 a
  square of view depth in both).
- Per pixel of a caster's quad: the capsules' analytic soft shadow (the body's volume; for a low sun the ten capsules stay
  while the penumbra is narrower than a limb, the bounding capsule only past that), then a march of the sun ray's stretch
  inside the caster's bounding capsule through the depth, each sample tested against a thin shell (0.12 squares, a car
  0.3) behind the drawn surface of the caster: hair, clothes, bags, weapons, a car's mirrors, what the capsules miss. A
  pixel of the caster itself takes none of its own shadow. First version used a thick shell alone (0.35-0.6 squares): the
  space under a deer's belly and between the legs came out solid (blobs, sh-sil1).
- Animals: capsules from their own skeleton (the bounding capsule thinned to the trunk plus the nine longest bone-to-parent
  segments: legs, neck, head, tail), sized by the animal (`getAnimalSize`). `sunShadowAnimals`.
- The stock blob under a character or animal and the stock vehicle shadow fade by `sunShadowStockFadePct` (85 %) x the
  caster's sun share where it casts a real sun shadow (new override `FBORenderShadows`); overcast, at night and indoors
  they stay.
- A caster above the ground (porch, balcony) gets a quad down to the level below.

### Torches and headlights: the models from the lamp (`sunShadowLampMeshes`)

- **Lamp slots (a bug of the 09-25 pass):** the pass takes four lamps a frame and took the first four LightingJNI listed.
  Next to a car those were its two headlights and two tail lights (reach 3, strength 0.2), so the player's torch cast
  nothing (flip-sh-night-lights: 4 lights, 2 caster x light quads a frame). Now the strongest four (strength x reach), and
  of the lamps the game sends at one spot (every lit item of a player) the strongest: the torch got its slot, 5 -> 14
  pairs (flip-sh-night-fix).
- **Lamp views:** a character in a lamp's light (the two strongest lamps that reach it) gets a perspective view of its
  model from the lamp in a tile of its own. `ShadowAtlas.SunCamera`'s lamp branch: a look-at from the lamp at the tile's
  centre, the frustum just round the bounding sphere (`lampTan`); the model's depth offset from the origin's clip z
  undivided (VertexBufferObject.getDepthValueAt). The views are scheduled on the game thread beside the sun's
  (`CapsuleShadow.lampTiles`). A view is drawn again when new, when its lamp moved round the character by a fifth of a
  square, or when its pose is older than `sunShadowMeshHz` allows, at most `sunShadowLampBudget` (8) a frame. The lamp at
  the draw goes with the tile, so the pass reads a consistent view in between. The light pass's pair carries the view
  (`lampVis`: 4 blocker taps, a penumbra from the lamp's size, 8 hardware-PCF taps of at least 1.5 texels); no view: the
  capsules as before.
- Two bugs on the way. At the far plane `ref` rounded to a hair over 1, so beyond it every cleared texel blocked: whole
  frustums went black (flip-sh-lampcone2); `ref` is now capped at 0.99999. And a lamp's shadow widens with its cone, so
  the quads (built from the capsule's two end points + a constant pad) cut it on straight edges; the capsule shadows had
  the same cut. The far corners now widen by up to `sunShadowLampSpreadPct` (100) and the quad's last 10-15 % fades
  out, instead of a hard edge.
- Cost (flip, 8 zombies 2-6 squares in the beam, GPU timer of the whole pass): capsules 300-715 us, lamp views
  ~250-270 us at spread 100 (one atlas lookup is cheaper than ten capsule tests), 420-700 at 200, 500-830 at 400. Render
  thread: 30-70 us a lamp view drawn (1.4 views a frame in the headlight + torch scene).

### Mesa / the flip

The branch started from efad94d, where the god rays merge patched the chunk composite for everyone; on Mesa its samplers
moved off the game's units and the composite wrote a flat depth of 0.5 for the ground (models kept theirs): every caster
pass (the 09-26 one too) found its receivers far away and drew nothing. Master's 683e1be / 3f8f7dc fix it; merged
(a1b21f3). The dark smudges near zombies with pixelLight + pplShadows on the flip are master's too (the player's torch
shadows; flip-sh-master-ref). On the flip the atlas holds every kind of caster (car, chicken, pig, sheep, deer, zombies, the
player).

## Rigs

- Harness flags: `plant_trees=N` (river birch XL / dogwood JUMBO with foliage on bare pavement 5-14 squares from the player),
  `animals=N` (cows, sheep, pigs, chickens, deer, turkeys, rabbits), `tree_map=N` (logs every tree / vegetation object
  near the player and whether `getTree()` finds it), with the existing `crowd=N`.
- `harness/trees/card_rig.py`: one tree on flat ground in a dumped texture's geometry, its silhouette from the dump, any
  sun; `harness/trees/tree_rig.py` replays a dumped compute with the silhouettes (`--no-cards`: the proxies).
  `harness/contact/kernel_rig.py`'s GLSL extractor now joins int constants into the shader lines (`" + FAR_MARGIN + "`).
- `crowd_min=` / `crowd_max=` / `crowd_ahead=D` (the crowd's ring and a cone of D degrees round the player's facing:
  zombies in a torch beam, `crowd=8 crowd_min=2 crowd_max=6 crowd_ahead=35 time_of_day=23 torch=on`); `devSilCost=8`
  tints the light pass's quads (blue outside the lamp's light, red by its share) and logs the lamps and casters every 600
  frames; `devShadowAtlasDump=N` also logs each lamp view whose tile comes back a third full (a view clipped wrong).
  Pitfall: on the flip `--route-seconds 20 --flag speed=0.05` left the torch dark for the whole run (no lamp reached the
  pass, "capsule shadows: pass ready" never logged); 12 s at 0.08 is lit. Check the shot's brightness before reading a
  night run.
- The Workshop card (`docs/workshop/images/42-pixel-perfect-shadows.gif`, `harness/shadows-card-gif.py`):
  `--prop devDetailTogglePeriod=4000` switches between the detailed shadows and the previous release's (capsules, crown
  ovals, the stock blob, softness 100) every 4 s with a logged epoch; in-game capture on the flip
  (`devCapture=6,17,12,100,crop=560:100:900:700,ram`), runs flip-card-day2 / flip-card-night2. A switch takes ~1.6 s to
  show everywhere on the flip (every chunk picture bakes again), so the card cuts each phase from there.
- `harness/shadows/abframes.py`: the within-run paired A/B of the god rays session for any `alternating every` line
  (`devSunAlternate` for the caster pass).

## Log

- sh-plant2-17 vs sh-plant2-17-proxy (5 planted trees on the church lot, 17 h): cards give the leaves and branches with
  the trunk's shadow joined to the foot; proxies an oval under each tree and a detached crown oval.
- sh-sil1: thick depth shell alone: animals and people as blobs. sh-sil2: capsules + thin shell: a deer's legs in its
  shadow.
- sh-crowd-new vs sh-crowd-old (30 zombies, 19.5 h): no stock blobs under the feet; sharper long shadows; the body past
  1.5 squares was still the bounding capsule (a stick): the LOD is now the penumbra's width.
- sh-cost-crowd1 (36 casters, uncapped ~900 fps): the pass 45 us of GPU a frame, frame +49 +- 8 us paired (the 09-25
  capsule pass: +10 us at 70 casters). Too much: the march is skipped where the capsules already shade fully and its steps
  follow the stretch's length.

## Costs (2026-09-27 evening)

Desktop (RTX 4090, 5120x2160, uncapped ~870 fps, 39 casters: 40 zombies, 8 animals, the car; within-run A/B,
`devSunAlternate=1000`, `harness/shadows/abframes.py`, no dev timers: their query polls are syncs themselves):

| | frame (paired) | GPU |
|---|---|---|
| mesh shadow maps (sh-cost-mesh8b) | +46 +- 15 us | +50 us |
| capsules only, the same pass (sh-cost-caps8b) | +32 +- 10 us | +27 us |
| the 09-25 capsule pass (sh-cost-crowd2-old, with timers) | +17 +- 7 us | |

At the 240 cap that is ~1 % of a frame for 40 casters; the capsule pass alone was ~0.4 %.

Flip (Radeon 890M, 1920x1080, ~180 fps, CPU-bound: 5.6 ms frames against 2.25 ms of GPU, so frame deltas are +-90 us of
noise; 16-40 casters):
- GPU: the pass +92 us a frame (flip-sh-ab-late / -early: placement makes no difference); its timer 155-160 us of which
  85-99 us is fixed (every fragment discarded at once: the first read of the live depth target, a decompress on AMD);
  the 09-25 capsule pass +110 us.
- render thread: a sun draw 23-26 us of model draw, a flush 44 us of setup / clear / restore: bursts of >= 6 draws
  (flip-sh-burst) put it at ~82 us a frame on average (114 before).
- game thread: the caster collection ~0.5 % of it (stack samples).
- streaming (the static changes, tree cards + bare textures within the far field): 120 km/h drive ABAB, 16 h, on 69.4 /
  68.5 fps, p99 38.5 / 39.3, p99.9 48.7 / 46.7 ms; off 68.6 / 67.0, p99 41.0 / 41.0, p99.9 50.8 / 55.5: parity.

## macOS abort (2026-09-28)

The release aborted the JVM on the Mac on world entry with `sunShadows` on (found by the PR #35 profile, run
`mac-pr35-hs-pr1`): `ShadowAtlas.init` -> `GL33.glGenSamplers`, `FATAL ERROR in native method: ... a function that is not
available in the current context`. macOS gives the game a GL 2.1 context (Metal): framebuffers exist through
`ARB_framebuffer_object`, sampler objects do not, and LWJGL aborts on a missing entry point instead of throwing, so the
`catch (Throwable)` around the setup never ran. `ShadowAtlas`, `TreeSilhouette` and `CapsuleShadow` now check the
context's capabilities on the render thread before their first GL call and switch themselves off with a log line
(`needs OpenGL 3.x or its extensions ..., this context is <GL_VERSION>`), as ChunkAo already did through its GLSL 1.40
compile.

## Open

- Zombies drawn as atlas sprites (far, culled; no model) keep one upright capsule.
- A caster in the sun whose shadow reaches into a static shadow darkens it again there (the pass knows the caster's own
  sun share, not the receiver's); the static term per pixel would need the composite to write it out.
- Far-field building shadows (low sun, tall buildings) are columns of whole squares, roofs flat at half a level: stepped
  edges; a heightfield from the upper levels' depth would give the roofs' shapes.
- Vehicles and animals in a lamp's light keep capsules (lamp views are characters only).
- A torch shadow's cost is its quads' area: long quads overlap in a crowd. One pass per lamp over its lit area, looping
  the lamp's casters with a bounding test each, would read the depth once a pixel instead of once a quad (~4-8x fewer
  fragments for a crowd in a beam).

## Shadows through fences (2026-10-02, `sunShadowWallCut`)

Player report (screenshot at 10751,6977, 19:20, evening sun from the west): the character beside a low wooden fence
(`fencing_01_34/35`) laid its sun shadow on the fence's far face and on the grass behind it. The caster pass draws on
whatever the scene depth shows and only tested the caster's own sun share (`sunShadowMarch`, the per-pixel test, is off
for cost), so a receiver hidden from the sun by a wall or fence still took the shadow.

`sunShadowWallCut` (default on, live): per caster, the opaque wall / fence edges on its level within its shadow's strip
(`CapsuleShadow.collectWalls`, cached per square, renewed after a second or a sun step, at most 8 a frame) merged into up
to 4 runs (texels `WALL0..`); `wallCut` in the sun shaders drops the shadow where the receiver's ray to the sun crosses a
run below its top, or where the receiver lies on a seen face (south / east) turned away from the sun. Heights come from
the sprite's mask (`edgeShape`: the median top of 8 columns along the face, a post does not count; a face less than half
drawn, chain-link or railings, blocks nothing): the low fence measures 0.49 level. A low fence's `transparentN/W` means
the eye passes over it, not the sun (the first try skipped them and changed nothing).

Runs `fencecut-false` / `fencecut-v2` (the reported spot, 19:20): the dark column on the fence face and the streak on the
grass behind are gone, the shadow on the caster's side unchanged. `fencecut-am-false` / `-true` (08:30, the caster east
of the fence, the sun on its seen face): the shadow on the face is kept, identical.
