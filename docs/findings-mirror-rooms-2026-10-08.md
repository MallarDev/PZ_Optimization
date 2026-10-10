# Mirrors: other rooms, undiscovered rooms, flicker while walking (2026-10-08)

Maintainer: "the mirror geometry flickers when the player moves, reflection panels are visible even when rooms are
undiscovered, a mirror reflection is not contained to its own room, so the mirror reflects the character when it is in
another room; confirm and fix all of this with Jev". Worktree `~/pzopt-wt/mirror-corners`, branch `mirror-corners`.

## Scene

`--flag mirror_corners=pair` (new): the walk picks a room with mirrors in its corners and another room right behind its south
wall, starts in that room on a spot within the north-wall mirrors' reach (the mirrors' room not seen yet), walks into the
mirrors' room and back (`mirror_laps=2`). Rosewood: the hall 8125,11546-8131,11548 on level 2 and the furniture storage south of
it, spot 8131.5,11549.6. Jev directs the walk (pzopt.Nav, skill `jev-walk`). Before = `--prop devMirrorsSkip=62914560` (the old
ways, one bit each, below), after = the defaults; `harness/mirrors/room-judge.py` compares them.

## Confirmed (runs `mp-before`, `mp-before2`, `mp-final-before`)

1. **Another room's person in the mirror.** `Mirrors.planesFor` mirrored every character standing in front of a pane's plane
   within reach, whatever wall stood between, and panes of two rooms on one wall line shared one plane. 11,494 model-frames
   of the player standing in the storage room mirrored by the hall's planes (one of them a plane of a room on level 1);
   the frames show his reflection in the hall's NE mirror while he is in the storage room.
2. **Reflections in undiscovered rooms.** A pane's reflection was composited at full strength whatever the player had seen:
   the hall's NW pane was never seen for 40 s (the player in the storage room and on his way round) and showed its
   reflection all that time, drawn at light 0.33-0.60. The room geometry behind the glass made it worse: it lit its tiles
   from `IsoGridSquare.getLightInfo`, a per-frame cache filled only for squares drawn that frame, and drew a square
   without one white, so a room never drawn came out lit in the mirror.
3. **Flicker while walking.** Measured per pane as the glass's frame-to-frame change beyond its surroundings', the frames
   aligned on the wall round the pane so the camera's pan is taken out (plain wall beside it: ~0): NE 2.33, NW 0.96, SW 1.38
   (means; NE p95 8.5). The dev ray view (`devMirrorsView=5`) shows where: most of the NE pane's upper half is stand-in
   (the floor seen last where the landing is hidden); every re-march resampled the frame at the camera's new sub-pixel
   offset and the stand-ins and the jagged edge of the hidden area jumped. A pane was re-marched every 30 frames
   (`mirrorsStaticReuse`) plus whenever more of it came on screen: 7,443 marches over the walk. The room geometry's light
   also changed at every march (the vision cone's darkening), up to 4 % of its summed light per step.

## Fixes (all default on; `devMirrorsSkip` bit = the old way)

- **One room per mirror** (4194304): each pane carries its room; panes of different rooms are different planes; a person
  is mirrored only by planes of the room they stand in (windows: any). Counter `people in front of another room's mirror`.
- **No reflection in an unseen pane** (8388608): the pane's square never seen (`isSeen`) shows the stock glass; a pane drawn
  near black fades its reflection out with its own light (vertex colours of its draw).
- **Room geometry from the square's own light** (16777216): `lighting[p].lightInfo()` (refreshed) instead of the frame's cache;
  never-seen squares are left out (`unseen squares left out` in the stats).
- **Re-march when the room changed** (33554432): a pane in a room is re-marched when what its rays reach changed
  (`MirrorGeometry.signature`: the room squares' objects and seen state at once; their light in sixteenths and cutaway flags,
  "soft", at most every 120 frames and only once the camera has stood still 10 frames or after 480 frames), checked every
  16 frames a pane; the age refresh is 20x longer for them (600 frames). Windows and outdoor mirrors keep the age refresh.

Tried and reverted: a re-march whenever the pane's light moved (more marches, no gain); a bound on the march at the room's
depth along the pane's column (the NE pane's grey upper area is inside the hall: the stairwell side; no visible change, and an
L-shaped room would lose reflection).

## Measured (`mp-final-before` vs `mp-final-after4`, people in mirrors on, desktop 5120x2160, ~294 fps uncapped scene)

| | before | after |
|---|---|---|
| people in front of another room's mirror | 11,494 frames, mirrored | 12,171 frames, none mirrored |
| unseen panes showing a reflection (`dev pane` states) | 11 | 0 (10 hidden) |
| pane marches | 7,443 | 537 |
| flicker NE / NW / SW (mean, p95) | 2.33 / 8.5, 0.96 / 3.0, 1.38 / 4.9 | 1.38 / 7.5, 0.63 / 2.6, 1.03 / 4.5 |
| frame mean, p99, p99.9 | 3.4, 5.1, 20.9 ms | 3.4, 4.9, 20.5 ms |
| game / render thread, GPU | 69 % (p90 77) / 48 % / 41 % | 65 % (p90 71) / 47 % / 41 % |

The flicker figures with people on include the player's own reflection moving (real change); without the people pass
(`devMirrorsSkip=2`, runs `mp-static-*`) the static reflection went NE 1.61 -> 0.74, NW 0.81 -> 0.39, SW 0.90 -> 0.51. Jev
(`room-judge.py`): other room fixed 0.98, undiscovered fixed 0.97, flicker reduced 0.84, verdict `fixed`. What is left of the
NE number is the stand-in area itself (the hidden landing round the stairwell): it still resamples on the rare re-marches.

## Rigs

`mirror_corners=pair`, `harness/mirrors/room-judge.py`, dev lines `mirrors: dev character ... in front of plane ...`,
`mirrors: dev pane ...: seen, light, reflection shown`, `mirrors: dev geometry change`, stats `people in front of another
room's mirror`, `unseen pane-frames`, `re-marches for a room change`, `unseen squares left out`.
