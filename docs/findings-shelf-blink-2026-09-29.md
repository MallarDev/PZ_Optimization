# Fossoil shelf blink with per-pixel lighting (2026-09-29)

Report: on the maintainer's save `Sandbox/2026-09-28_22-02-56` (Riverside Fossoil at night, the desktop's tab file:
pixelLight, DLSS, HDR, AO, sun shadows, reflections, ...) the goods on the shelves behind the store counter flickered while
the player walked in circles.

## Reproduction and metric

- `explore=circle` on a copy of the save + `--prop devCapture=6,8,240,25,gray`; `harness/dark-blink.py <run>` counts pixels
  that drop to near black (<= 40) for one frame and come back (neighbours agree): shelf crop 11-13 px/frame, ~12 % of frames,
  up to 16 alternating frames in a row. Stock (`--prop enabled=false`): 0 blink frames (2.1 px/frame of film grain before the
  near-black rule).
- The same walk directed by Jev: `--flag explore=circle --flag director=jev` + `harness/explore-director.py` (circles at the
  save's spot, then at up to `circle_spots` aisles beside the shelves of the nearest building; `pzopt.CircleWalk`).

## Bisect (each one run, same walk)

| off | blink px/frame |
|---|---|
| nothing (bug) | 11-13 |
| `pixelLight` | 0.007 |
| `translucentTilesInChunkTexture` | 0.005 |
| DLSS, `pplNormals`, `pplPointLights`, 3D ground items, `translucentLightsPerFrame`, `losLightPrefetch` | 11-29 (no change) |
| reflections + sun shadows + god rays + pplShadows + AO | 12.8 |
| grading + darkness floor + memory tint + sway + sprite filter + HDR | 17.2 |

`devPplProbe=16`: the lattice (corners, light, vision, packed values) never changes frame to frame. `devSquareTrace` (new):
the shelves stay baked (`MinusFloor`), never faded, 7 bakes in 500 frames. `devPplView=13` (height) stable at the blinking
pixels, `devPplView=1` (light) and `devPplView=3` (owner square) flip: on the bad frames the owner square's x fraction
wrapped from ~0.99 to ~0.09, the point had moved into the square beside it.

## Cause (revised 2026-09-29 ~03:40, after the maintainer saw a speckle left in the first fix's video)

Three errors in where pixelLight takes a baked texel's light from; the first alone looked fixed on the near-black metric but
left a dark shimmer (`harness/crop-flicker.py`, any one-frame flip in a crop: 30-55 px/frame vs 1.1 with pixelLight off).

1. **The goods sit on the square edge.** The shelves' depth boxes fill their square, so the goods' visible face lies on
   x = 6083, the plane of the garage wall behind them. pixelLight's owner rule nudges a point on an edge 0.004 towards the
   camera side (right for that wall's own face) and DEPTH16 steps scatter it across: diagonal bands of texels took the
   unlit, never-seen garage's light, and as the upscaler's jitter and the camera's sub-pixel motion moved which texel a pixel
   shows, the bands shimmered. `pplSeenEdge`: a point within 0.03 past a square's west / north edge, across a wall (no
   connectivity bit), in a square never seen (vision bits 0), takes the seen square before the edge. The seen bit rides in
   the connectivity texture's alpha (outdoor 128 / 255, indoor 0 / 64; the outdoor test `a > 0.5` unchanged). The shelf
   squares behind the counter were explored but not in sight (vision 1): a first version keyed on "in sight" missed them.
2. **The camera jiggle** (`pplJiggle`): the game draws chunk textures moved by `fixJigglyModels*` on screen and in depth;
   the mapping left it out, every lit point up to ~0.1 square off, a different amount each frame.
3. **Depth from transparent pixels** (`pplDepthOpaqueOnly`): stock's `tileWithDepth.frag` writes depth where the sprite is
   fully transparent; a goods overlay stamped its box depth into its own gaps. The patched shader writes neither colour
   nor depth for a zero-alpha fragment (colour-neutral: premultiplied).

## Results (counter crop, `crop-flicker.py`, flip px/frame; reference = pixelLight off)

| config | all three off | pplSeenEdge only | all three on | reference |
|---|---|---|---|---|
| maintainer settings (DLSS + sprite filter) | 400.7 | 2.8-4.1 | 3.0 | 1.1 |
| sprite filter off | 498 | 3.3 | | |
| DLSS off | 802 | 135.7 | | 130 (nearest-sampling edge shimmer) |

Jev circle walk through the store (save spot + 3 aisles, dark one-frame blinks): all on 7 of 8265 frames, 0 at every store
spot; pplSeenEdge only 40 (23 and 6 at two store spots); all off 825. The 7 are the pump canopy's roof cutaway (black blocks
for a frame, another artefact). Jev on the crop numbers: `reduced` 0.85 / `fixed` 0.15 (3.0 vs 1.1: one ~8-frame
brightness wobble over the whole counter at one turn of the walk, DLSS-like, the goods never dark).

Dead ends: `pplTexelX` (texel-centre window x: no gain once the banding was understood), white 3D item ambient (`FBORenderItems` only bakes items with the debug option `ItemsInChunkTexture`), whitening
the neighbour chunks during a bake, texel-centre window x (`pplTexelX`: worse, 21.7 px/frame).

Media (local): `docs/media/fossoil-shelf-blink-fix-off-vs-on.mp4` (`harness/stitch-shelf-blink.sh`),
`docs/media/canopy-roof-lights-fix-vs-off-vs-stock.mp4` (`harness/stitch-roof-lights.sh`).
