# Mirrors in the four corners of a room (2026-10-08)

Maintainer: "test mirrors placed in the four corners of a room, use Jev to walk the character back and forth and confirm
that there are visual issues present on the reflections". Worktree `~/pzopt-wt/mirror-corners`, branch `mirror-corners`.
First the walk itself had to be clean ("an error free and stuck free Jev powered harness to walk in the game efficiently
without colliding with any obstacle"); that part is in `harness/CLAUDE.md` (mirror walk rig, `pzopt.Nav`, `navjudge.py`).

## Scene

Rosewood, the 7x7 "empty" room 8089,11545-8095,11551 on level 2 (`--flag mirror_corners=8092,11548,2 --flag lights=on`,
14:00, clear): `walls_decoration_01_5` (Large, facing S) on the north wall at NW and NE, `walls_decoration_01_4` (Large,
facing E) on the west wall at SW, `walls_decoration_01_30` (facing W) on the east wall at SE (the camera sees its back: not
a reflector). Each corner square also holds the room's wooden corner pillar (`carpentry_03_*`), which stands in front of
part of every corner mirror (stock drawing, same with mirrors off). Jev walked three passes, back and forth (`mirror_laps=3`):
at every station face the mirror, turn the back to it, one lap per mirror. 6 fps 1:1 `devCapture` crop of the room,
`devMirrorsLog` rects every 24 frames.

| run | what |
|---|---|
| `mc4-room-3` | mirrors on (29 legs, 9 / 9 stations, 0 collisions / stuck / errors) |
| `mc4-room-3-nomirrors` | the same walk, `mirrors=false` (stock glass): the reference |
| `mc4-room-4-devview` | one pass, `devMirrorsView=5` alternating with the picture every second, `colorGrading=false` |
| `mc4-room-1`, `-2` | the derelict room 8129,11436 (unlit: the glass mostly black, not judgeable; run 1's crop missed the room) |

## Confirmed issue: the west-wall (east-facing) mirror is not reflected to its edge

In every visit of the SW mirror (stations 1.0 and 1.8 out, all three passes) the reflection stops short of the pane: the
stock glass stays along the pane's right edge and in its bottom-right corner, with a ragged border (frames 129, 133, 136,
194, 199, 201, 354, 361). The player's reflection is cut off hard at that border (a dark fleck at f133, a bright sliver at
f199; f204-210 in the dev-view run). The NE and NW panes (`_5`, facing S) are reflected over the whole pane (mirrors on vs
off difference: the whole pane).

- Dev view (`devMirrorsView=5`): the SW pane is green (floor shortcut) except that strip and corner, which get no ray colour
  at all: the composite never shades those texels. The NW pane is blue (marched hits) to its edges.
- Cause: the glass mask atlas (`harness/mirrors/masks.py`, `src/media/ui/pzopt/mirrors/mirror-masks.png`, regenerated
  identically today). Its rule (light, grey, largest blob, convex hull, eroded one texel) leaves out the glass's light-blue
  highlight rim: on `walls_decoration_01_4` the rim runs down the right edge and along the bottom (cell 10), on `_5` down
  the left edge (cell 11, the thin bright line beside the NW / NE reflections). Not a corner effect: every mirror of these
  sprites shows it; the east-facing large one most, because its rim is the widest.
- Evidence: `harness/runs/mc4-room-3-20261008-201317/evidence/` (`sw-zoom.png` 6x on / off / difference, `zoom-all.png`
  all three panes, `devview.png`, `mask-4-5.png`).

## Checked and not an issue

- **No blink or pop at the corners** over the three passes. Per mirror, the glass of consecutive frames compared with the
  wall round it (`evidence/blinks.py`, camera-shift aligned): 419 / 216 / 333 frame pairs (NW / NE / SW); the 4 glass jumps
  with a steady wall are all real: at f51 and f267 the NW pane follows the west wall losing its vision-cone darkening when
  the player snaps to face the mirror (`nw-jumps.png`); at f152 and f376 the SW pane shows the player turning on his lap
  (`sw-jumps.png`). Caveat: at 6 fps a pop shorter than ~170 ms can fall between two captured frames, and the
  `dev attached` capture-state log covers only the map's attached mirrors, not these placed tile objects.
- **The NW mirror shows only white wall**: right. In the iso view a north-wall pane's reflected ray runs west, and at the
  NW corner it meets the west wall half a square away; it carries that wall's lighting (the darkened band too).
- **The black panel floating at the SE corner and the thin white window outlines** on the cut-away south / east walls: stock
  drawing of wall objects on cut walls, identical with `mirrors=false` (`sbs-ev21.png`).

## Rigs added on the way

`pzopt.Nav` (the game's walk, collision / stuck counters, door opener, vault detour, self-test push), `harness/navjudge.py`,
`mirror_corners`, `mirror_corners_teleport`, `mirror_laps`, `nav_selftest` (harness/CLAUDE.md, mirror walk rig).
