# A person in front of a mirror by a wall cut to a slice (2026-10-09)

Maintainer, desktop, two screenshots: "there are still visual artifacts when facing a mirror that is next to a wall"; the
character stood in front of the dresser mirror `furniture_storage_01_43` (8142,11505, level 1, the bedroom of the Rosewood
church's residence, the room with the window wall beside it) and the mirror showed only a slice of them: the image cut by a
hard vertical line through the face, plain white wall where the rest belonged.

**Scene.** The maintainer placed the character and saved (Continue into the harness bench save; copied to
`Sandbox/pzopt-mirror-user`, player 8142.63,11506.33,1). Runs from it with `--source-save Sandbox/pzopt-mirror-user`, zoom
0.25 (the screenshots' zoom: the arch ~375 px tall at 5120x2160).

## Cause

The mirrored people are placed by `Mirrors.planesFor` "straight in front": a person d squares in front of the glass shows
at their own lateral position (`mirrorsViewLateralPct` 0) and d / 6 levels up (`mirrorsViewDropPct` 50). The room behind
the glass (the static march, `marchRay`) is the camera's true reflection: its ray runs one square sideways for each square
out and drops a third of a level. The composite showed a person's texel only where they were nearer than the static ray's
hit (`tM < tS + 0.15`). Two different lines: beside a wall the slanted ray meets that wall (or the furniture beside the
glass) a fraction of a square out, so the person, 1.2 squares out on their own straight line, lost to it on every texel
whose slanted ray hit the wall first: a straight vertical cut along the wall's edge in the reflection.

## Fix (default; `devMirrorsSkip` bit 67108864 = the old test)

The static pass also marches each mirror texel along the people's own line of sight (`losOut`: out, `viewLateral`
sideways, `viewDrop` down per square, through the frame's depth before the characters are drawn, so the person never
hides themselves) and stores the free distance in the glass texture's new G channel (R8 -> RG8: 1 - distance / reach, 0
= nothing in the way, the cleared value); the composite shows a mirror's person unless something stands on that line
before them (the dresser under the glass, a table). Windows and props (people at the true reflection, the static ray's own
line) keep the old test. Row 6 .x of a pane's data carries 1 + viewLateral for a mirror. GL 4.3 image path and the GL 4.1
(macOS) framebuffer path; `MirrorsShaderTest` links all programs on NVIDIA and Mesa. Cost: the static pass marches the
second line only on mirror texels when their pane is re-marched; the composite reads one more texel where a person is.

## Measured

Dev view 9 (`devMirrorsView=9`, new): the composite paints the glass flat, green where a mirrored person is shown, red
where they are in the layer but hidden. `harness/mirrors/sliver-judge.py` counts both on the mirror's own glass region
(the connected flat region overlapping the logged `dev rects`, scene red such as the dresser's jewellery box excluded) in
`devMirrorsViewToggleMs` runs, Jev judges.

| runs | hidden share of the person's image (before -> after) | Jev |
|---|---|---|
| still at the maintainer's spot (`route=N:0 hold=12`), `mwa-still-before` / `-after` | 0.326 -> 0.000 | fixed 0.97 |
| walking left / right in front of it (`explore=walk`), `mwa-los-before` / `-after` | 0.114 (4 frames mostly hidden) -> 0.000 | fixed 0.99 |

The picture frames match: before, the maintainer's cut through the face; after, the whole image (face, glasses, arm). The
church's other two mirrors (bathroom walls, walk `mwa-walk-fix` vs `mwa-walk-on`) are unchanged. A person standing half
outside a narrow pane's width now shows as the part of them in front of it, at its edge (before: hidden).

Rig, still: `--mode bench --source-save Sandbox/pzopt-mirror-user --flag zoom=0.25 --flag lights=on --flag zombies=off
--flag time_of_day=14 --flag weather=clear --flag route=N:0 --flag hold=12 --prop devMirrorsLog=true --prop
devMirrorsView=9 --prop devMirrorsViewToggleMs=1000 --prop devCapture=6,9,4,100,crop=2000:150:1500:1350,ram`; walking:
`--flag explore=walk --flag "walk=8140.8,11505.83;8143.0,11505.83" --flag route=S:1 --flag speed=0.033 --route-seconds 30`
(a bench route lasts its length over its speed: `route=N:1 speed=0.0001` ran for hours).

## Second report: a bathroom mirror with no reflection at all (2026-10-10)

With the fix installed the maintainer found `walls_decoration_01_10` over a sink (8082,11526,1, the motel west of the church;
save `Sandbox/pzopt-mirror-user2`, player 8082.8,11527.4,1, zoom 0.25) showing only the stock glass. The pane was captured
and composited every frame, yet even dev views 1, 2 and 2 without occlusion drew nothing on it: it had no tile. 36 panes in
view (windows, glass, sinks) at zoom 0.25 need ~5.4 M px of the 4.2 M px static atlas; a full atlas started over in the
middle of a pass, voiding the tiles handed out before the reset that frame, so the panes marched again next pass and the
atlas thrashed (1,012 resets, 24,300 tiles in 900 frames). The fix (`compactAtlas`, `rank`; dev skip bit 134217728 = the
old way): panes ask for tiles in order (silvered mirrors first, then nearest the screen's centre), a pane that does not fit
goes without a reflection (`panes without room` in the stats), and the atlas starts over only at the top of a pass frame
when tiles of panes no longer on screen hold at least an eighth of it and a pane on screen waits.

| run (still, the maintainer's spot) | atlas resets / 900 frames | tiles | mirror drawn in dev frames | person on it |
|---|---|---|---|---|
| `mwa-atlas-before` (bit 134217728) | 1,012 | 24,300 | 0 | 0 |
| `mwa-atlas-after` | 0 | 28 | 19 / 19 | 19 / 19 (legs hidden by the sink and counter in front: 18 %) |

Jev: before_broken 0.98, after_reflects 0.85, stable 0.94, verdict fixed 0.97. `devSquareTrace` now also lists each
object's attached sprites (`att=`: the map's wall overlays, where wall mirrors live).

## Third report: the sink and the person not layered (2026-10-10)

Same bathroom mirror: "from the corner the reflection shows the sink and the room; in front of the mirror the sink
disappears and the reflection glitches; the character, the faucet and the room must be layered". The sink
(`fixtures_sinks_01_5`) and its tap are drawn per frame (the game's Translucent layer, so with or without `mirrorsProps`),
after the static pass reads the frame: the march hit the counter's cut-out under the basin, (18, 18, 18), a black jagged
patch at the glass's foot, and nothing hid the mirrored person behind the sink. The room geometry (`devMirrorsView=6`) had
the reflected counter, basin and tap all along. Fix (dev skip bit 268435456 = the old way):

- `MirrorGeometry` flags furniture instances (row 6 .w; its texels carry the odd bit of the distance code, cleared by
  `withGeom` before it reaches the atlas);
- `withGeom`: furniture within a square of the glass (or not farther than the march's hit + 0.35) wins over the marched
  hit, and a near-black marched hit (rgb < 0.09) where the room has a texel is a hole: the geometry stands in;
- `losGeom`: furniture within a square of the glass shortens the mirrored people's line of sight (glass G), so the
  reflected basin and tap stand in front of the person's lower body. Walls never do (the first report's slice).

| runs (`Sandbox/pzopt-mirror-user2`, zoom 0.25) | before | after |
|---|---|---|
| black-hole px on the glass, still, no person (`mwa-black-pic` / `mwa-strip4-pic`) | 815 of 40,905 | 0 |
| the corner <-> front walk, no person (`mwa-holes2-before` / `-after`): mean px, frames > 100 px | 812, 57 / 60 | 2.6, 0 / 61 |
| the walk with the person, dev view 9 (`mwa-lay-before-v9` / `mwa-lay-after4v9`): hidden share | 0.196 (over a hole) | 0.235, all in the mirror's lower 45 % (behind the basin and tap) |
| the dresser mirror (report 1, `mwa-still-final2`): person hidden share | 0.326 (released) | 0.0 |

Jev: before_bug 0.95, after_layered 0.92, verdict fixed 0.91. Pitfall on the way: a peer session's `--install opt` job
replaced this build in `/games` between `--install keep` runs; three runs (`mwa-holes-*`, `mwa-still-final`) ran on its
build and were re-run with `--install opt`. Check a run's `mirrors: frames` line for this build's counters (`panes without
room`).
