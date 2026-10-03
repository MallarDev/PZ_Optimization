# Occluded zombie outlines

PR #48 (novakovicdavid), reworked on 2026-10-03 so that the feature costs next to nothing. Options > Enhancements >
**Occluded zombie outlines**, off by default; every key applies at once.

| Key | Default | Meaning |
|---|---|---|
| `occludedZombieOutlines` | `false` | contour the parts of a seen zombie that scenery hides |
| `occludedOutlineIgnorePlants` | `true` | grass and bushes in front of a zombie (nothing solid) do not outline its legs |
| `occludedOutlineWidth` | `1` | contour width in render pixels, 1..4 |
| `occludedOutlineColour` | `FFC740` | RGB hex from the colour picker (the PR's `ISColorPickerHSB` button) |
| `occludedOutlineOpacityPct` | `70` | opacity in daylight; the zombie's light and fade scale it |

## What is outlined

- Only zombies the player sees right now: `isVisibleToPlayer` and the character alpha, snapshotted on the game thread
  with the draw (`OccludedOutline.eligible`); carried corpses (`isReanimatedForGrappleOnly`) never. No unseen,
  remembered or nearby zombie, nothing through the dark: the contour's alpha follows the light on the zombie (the
  square light of an atlas zombie, the model's ambient) times its fade.
- Scenery and vehicles occlude; characters do not (a zombie behind the player or behind another zombie is not
  outlined there). Only the zombie's own silhouette edge is drawn, never a line along the edge of what hides it.
- Overlapping zombies keep their own edges where the front one is visible; two hidden ones merge into one contour.

## How (no scene drawn twice, no material patched)

Everything rides the world framebuffer's stencil, bits 0x7F (bit 0x80 is the game's player-mask bit, untouched),
written by the draws the game already makes. Codes: `S` silhouette (0x40), `V` visible (0x20), `b` 4-bit brightness.

1. **Model zombies** (`Model.DrawSolid`): the mesh's own draw writes `S|V|b` where it passes the depth test. Right after
   it, with the program, uniforms and buffers still bound, the same mesh is drawn once more with colour and depth
   writes off, depth test `GREATER` and stencil test "V clear": it writes `S|b` where something nearer is already in
   the depth buffer and no character shows. DrawChar's `GLStateRenderThread.restore()` puts the state back after each
   mesh, as it already does. Cost: one extra draw call and ~8 state calls per mesh, a few hidden fragments.
2. **Other characters** (the player, animals, unseen zombies) write `V` only: not outlined, not occluders.
3. **Vehicles** clear `V` where they draw (`TextureDraw` DrawModel): the cell draws its objects in list order, so a car
   drawn after the zombie behind it turns that zombie's visible pixels there into hidden ones.
4. **Atlas zombies** (the far crowd, one textured quad each through `DeadBodyAtlas.BodyTextureDepthDrawer`) write
   `S|V|b` in their own draw; their hidden part is the same quad once more in a single pass at the end of the moving
   objects (`finish`), against the final depth (the quads need nothing but their atlas textures).
5. **Contour** (`finish`, before the fog): one full-screen triangle with the stencil test `S` set and `V` clear, so
   only hidden pixels are shaded; each reads the stencil (the scene's DEPTH24_STENCIL8 texture as `STENCIL_INDEX`)
   within the width and draws the colour where a pixel outside every silhouette is near. Premultiplied, the
   destination alpha kept; auxiliary draw buffers (HDR, sway) masked.

The occluder prefilter (`OccludedOutline.classify`, game thread): the camera looks from +x+y, a square (a, b, level) is
drawn a + b - 3·level diagonal rows down the screen and a - b columns across. Only what stands in the band in front of the
zombie can cover it: anything up to 4 rows ahead on its level (a one-level object covers three rows), trees up to 9 rows
(three levels tall, crowns about three columns wide), and any square on the levels above in the rows it is drawn over.
When that band holds floors only and no car is within 8 squares, the zombie is CLEAR: it still writes `S|V|b` (other
zombies' tests need it) but skips its hidden-part draw / quad, and a frame without any hidden-part draw skips the contour
pass. Cached per zombie for its square, refreshed every second; cars are checked every frame.

The plant slack: grass and bushes (`isBush` / `canBeRemoved`, not trees) on the zombie's square or the three next to it
toward the camera, with nothing solid there (a wall, a fence, a tree, furniture, a car), give that zombie a depth
slack of 2.5 square steps in its hidden test (polygon offset for models, the quad's depth for the atlas), so the plants
at its feet do not outline its legs and a wall further in front still does. The test is cached per zombie per square
(`IsoZombie.pzoptOutlineSquare`, refreshed every 120 frames). Every other zombie gets 4e-5 (the DEPTH16 chunk depth
composited into D24 rounds by up to 1.5e-5).

Off while a temporal upscaler's per-object motion vectors are on (`ObjectMotion` writes its ids into the same bits).
Needs OpenGL 4.3 (stencil texturing) and the scene depth texture (`fogPass`, default on); otherwise it does nothing.

## Against the PR's version

The PR replayed every eligible zombie into private targets (clear, skinned draw, a full-screen mask pass each),
patched every world material shader to `imageAtomicMin` an opaque-depth image (toggled through a uniform buffer with a
`glGetInteger` around every character draw), forced windows and translucent tiles out of the chunk textures, kept a
second depth cache in every chunk texture for the plant filter (4 bytes a texel, written by every bake and composite)
and baked a lighting atlas beside the corpse atlas. None of that is left; the colour picker, the eligibility rules and
the option texts' intent are the PR's.

## Measurements

See `docs/findings-occluded-outlines-2026-10-03.md`.
