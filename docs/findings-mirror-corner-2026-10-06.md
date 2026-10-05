# A mirror by a room corner blinked in and out (2026-10-06)

Maintainer, on the flip: "where a mirror is next to a 90 degree wall, the mirror flickers and the reflection has visual
artifacts"; asked which, they chose the upstairs bathroom mirror blinking as the player walks near it. Worktree
`~/pzopt-wt/mirror-corner`, branch `mirror-corner`.

**Scene.** The flip's latest save `Sandbox/2026-09-26_03-37-09`, house 6755,5400-6768,5420 (the walk lists six mirrors).
The mirror: `walls_decoration_01_5` over the sink on the north wall of 6765,5405,1, a map wall-mirror overlay on the
corner wall piece `walls_interior_house_04_86` (its square carries the north and the west wall).

## What happens

- The game cuts a room's walls away round the player (`FBORenderCutaways`, points of interest = the squares the player
  can see). At this corner the cut of the mirror's square flips between 0 and 3 every 0.1-0.7 s while the player walks or
  turns near it, and the wall with its baked mirror and the reflection over it went with every flip.
- Stock behaviour: with every pzopt cutaway key off (`cutawayFast=false cutawayRadius=0 cutawayVisitPrefilter=false
  cutawayInvalidateChanged=false`) the walk flipped as often (3 cuts per walk either way, `mc-m4-stockcut*`). Stock's
  `IsoGridSquare.setPlayerCutawayFlag` keeps a 750 ms lock, but with `fboRenderChunk` `getPlayerCutawayFlag` returns the
  target flag: the lock is never read. On an ordinary wall the flips pass unnoticed; the mirror's reflection made them a
  visible blink.
- Standing at the sink, every wall of the bathroom is cut (stock): the mirror is not on screen there at all. The blinking
  is while approaching, leaving and turning by the corner.
- Not the cause, checked on the way: the reflection did not run ahead of the wall's re-bake (the drawn and the live flag
  changed in the same frame at every flip); the capture now follows the drawn cut anyway (`mirrorsDrawnCut`).

## Fix

`mirrorsCutawayHoldMs` (default 750 = stock's lock, 0 = stock flipping; Optimizations tab, Mirrors section): a wall square
carrying a mirror (a map overlay or a mirror tile; both walls of a corner square) changes its cut at most once per hold
(the first cut at once, so the player is never hidden behind it), and the wall comes back only after the visits have not
wanted it for twice the hold. Applied in the visitor's result sets, so stock's flag loops and the change detection run
unchanged; a change due without a visit is applied by a per-frame tick (`FBORenderCutaways.pzoptHoldTick`). Versions
that failed on the way: a forced cutaway visit to release a hold gave another verdict than the last visit (the wall came
back for 0.1 s); holding only the return left a 0.77 s blink when the corner wanted the cut 0.4 s, dropped it 0.75 s and
wanted it again.

## Measured

Jev walk of the mirror alone (`explore=mirror director=jev mirror_only=4`), the mirror's capture state logged on every
change and its window rect every frame (`devMirrorsLog`, `devMirrorsRectsEvery=1`), 30 fps full-frame capture;
`harness/mirrors/corner-judge.py` (pops = on / off states under 0.75 s; glass blinks from the frames).

| | before (`mirrorsCutawayHoldMs=0`, runs `mc-h3-0a`, `-0b`, 94 s) | after (default, runs `mc-h4-a`, `-b`, `-c`, 144 s) |
|---|---|---|
| the mirror's on / off flips | 16 | 12 (per walk: the approach, one cut, one return) |
| pops (states under 0.75 s) | 6 | 0 |
| glass blinks in the frames at those flips | 6 | 0 |
| glass blinks, any cause (the player's reflection too) | 7 | 1 (not at a flip) |
| share of the walk the mirror reflected | 0.32 | 0.27 (held cut while the player is by the corner) |

Jev (`corner-judge.py`): `fixed` (0.72; partly_fixed 0.28); comparable 0.93, pops fixed 0.96, the frames' pops fewer 0.97,
mirror still shown 0.92. The versions on the way: hold v1 (forced release visit) 3 -> 5 pops, not_fixed 0.92; hold v2
(return only) 1 left; v3 (symmetric 750 ms) 0 pops but a 0.77 s return-and-cut in two of three walks, fixed 0.57; v4
(return after 1.5 s) above.

## Rigs

- `devMirrorsLog`: `mirrors: dev attached <overlay> on <wall> at x,y,z: A -> B (drawn C, live L ..., screen X,Y,
  epoch_ms=T)` whenever a wall mirror's capture state changes (captured, cut away, square skipped, render layer None,
  absent); dev rects now carry each reflector's square and `epoch_ms`, every N frames with `devMirrorsRectsEvery`.
- `harness/mirrors/corner-pops.py`, `corner-judge.py` (harness/CLAUDE.md).
- The queue's copy-back of a large flip capture died silently three times this session (job left `running`, or `done`
  with an empty `capture/`); the runs were pulled with rsync by hand.
