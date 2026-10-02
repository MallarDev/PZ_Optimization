# The flip's driving report: whole-scene flicker and striped crowns (2026-10-02)

Report (maintainer, flip = Radeon 890M on Mesa, 1920x1080, their latest save `Sandbox/2026-09-26_03-37-09` and their
Optimizations / Enhancements options: HDR, FSR1 quality, AO, sun shadows, pixelLight + pplShadows, relief, reflections,
god rays, colour grading, foliage sway, sprite filter sharp, car glass): "when zoomed out to the max there are visual
artifacts in the trees on the right side of the screen ... there is also heavy flickering of the whole scene while
driving". Asked which tree artifact: horizontal stripes. Every run below is on the flip through the run queue, with the
save and a copy of the options file (`-Dpzopt.userOptionsFile=/home/diego/pzopt-repro-user.ini`).

## Whole-scene flicker: HDR light map built during a chunk-map shift

- Drive (Jev-planned with `drive-path.py plan`): Riverside south to north on Alan Road / Station St, west on Main St,
  south on Rock Ridge Road, west into the forest on Rag Road / Long Branch Road, 80 km/h, max zoom, 1,495 tiles.
  Capture `--prop devCapture=8,140,120,12,gray`, `harness/frame-dips.py`.
- 32 single frames in 94 s with the whole world ~12 % darker (the HUD not), ~0.34/s. Bisect on a 23 s stretch: 17 dips
  with the player's options, 0 with `hdr=false`, 0 with `hdrSunPct=0`; bloom, glints, colour grading, the upscaler,
  pixelLight and sun shadows off left them.
- `devHdrFrameLog` showed every dark frame drew a light map whose worker had read 22-1,500 of its 2,401 squares: built
  while the game thread re-centred the chunk map (`cell.getChunk` null for most of the grid), its missing squares carry
  sun exposure 0, so the composite lost the HDR sun gain for that frame.
- Fix (pzopt.HdrLight): drop a build that read under 90 % of the last kept share (held map re-projected; a persisting
  lower share is kept after three builds); unread squares of a kept map take the mean sun exposure. Town drive: 32 -> 0
  dark frames (58 short maps dropped), 86.0 -> 86.2 fps, p99 24.8 -> 25.6 ms, p99.9 35.4 -> 32.5 ms (one run each).
- Found on the way, fixed too: `HdrExposure.sample` took the floor from `player.getCurrentSquare()`, null ~10 times in 23 s
  while driving; it now uses `floor(player.getZ())` like the map.

## Striped crowns: treeAppend under the chunk AO / sun term

- Rig: the 2026-10-01 forest spot, `--flag start=9300,12900 --flag route=S:1 --flag speed=0.06 --shot-at 4 --prop
  devCapture=13,1,5,100`; the pale crown at screen `1660,680,140,180` shows bright bands ~10 px apart in its lower part
  (the part in the lower chunk-level texture).
- Bisect: gone with `sunShadowTrees=false`, `sunShadowTreeCards=false`, the kernel's crown sun path off
  (`devAoDefines=TREE_NO_SUNPATH`) or the own-crown chord off, and with `treeAppend=false`; kept with pixelLight, the
  upscaler, HDR, colour grading off. Kernel changes to how a texel picks its tree (best depth match, the bake's row
  stagger in the card depth, cards before the plane snap, the clamp, a tie rule) changed nothing at that crown;
  `devAoDefines=TREE_DEBUG_OWNID` still banded, so the term was banded but not by the kernel's own choice.
- Cause: `treeAppend` draws a new chunk's trees into finished neighbour textures. A deferred AO / sun recompute of such a
  texture applies new / old term to its colour, assuming every texel holds the old term; the appended crown never got it,
  so it came out divided by the old term of the ground under it (bright where that term was dark). A full re-bake
  (`treeAppend=false`) recomputes the term over the crown.
- Fix (FBORenderCell + `pzopt.TreeBake.appendAllowed()`): no append while `ChunkAo` (AO or sun shadows) or relief keeps a
  per-texel term of the texture; the texture re-bakes as before `treeAppend`. Crown band energy (`harness/crown-bands.py`):
  before 8.37, fix 4.98, control `sunShadowTrees=false` 5.41; Jev `fixed` (0.89). No frame-time cost on the town drive.

## Not bugs (stock behaviour), seen on the way

- Trees near a driving player turn into dotted outlines: 42.21's see-through trees (stock does it to every XXL tree while
  driving; we limit it to 12 squares). With FSR at 67 % the dots are coarser.
- Buildings the car passes switch to a black block for a frame or more: stock's cutaway of unexplored interiors does the
  same on the same drive (`enabled=false` run).
- A big crown whose foot is just off screen appears a few frames before stock draws it (our baked trees reach into the
  neighbour textures), once with a vertical seam for one frame.
