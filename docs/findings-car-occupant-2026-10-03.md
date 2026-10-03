# The people inside a car, seen through its glass (2026-10-03)

Maintainer's request: "make the player visible while the character is in a vehicle and the car glass enhancement; make it
cost virtually zero performance; implement state of the art solutions, don't discard any idea, implement them one by one and
profile them". Worktree `~/pzopt-wt/occupant`, branch `car-occupant`. Code: `pzopt.CarOccupant` (key `carOccupant`, Enhancements
tab section "Car glass", next launch), the glass shader in `pzopt.CarGlass`, hooks in `TextureDraw` (DrawModel) and
`IsoGameCharacter.render` (the stock A/B). Rig: harness flags `car_rig=8 car_rig_seat=true [car_rig_spin=D]`, tools
`harness/carglass/occframes.py` (devCapture frames labelled by dev view / cycle entry, cropped) and `harness/carglass/occcost.py`
(per cycle entry: the moving pass and the occupant pass on the GPU, the render thread).

## What stock does

Stock never draws a seated character: `IsoGameCharacter.render` returns when the seat's script has no `showPassenger`, and no
vehicle script sets it. The game still animates the seated character (the `player-vehicle` anim set: `Bob_IdleDriving`,
steering, enter / exit, aim, shove) and has an unused code path for drawing it in the seat
(`Model.CharacterModelCameraBegin`'s inVehicle branch). The first car glass release drew a dark torso box and a head ball in the
cabin ray-cast; from the iso camera it differed by less than 1/255 from an empty seat.

## What it does now (`carOccupant=impostor`, the default with `carGlass`)

Each occupant of a car on screen with glass is drawn by the game's own character path into a tile of an offscreen atlas
(1024², RGBA8 + DEPTH24, four cars) before the moving objects: the world camera with its projection zoomed onto the cabin's
screen rect (x, y only; the depths stay the world view's), the seat placed in the glass's calibrated chassis frame G (the frame
captured from the car's previous glass draw), the character's `targetDepth` forced to 0.5 so the tile holds the plain projected
depth. The glass program maps each window texel's chassis position through the matrix the tile was drawn with (G → the tile's
world NDC, two rows per car) and takes the occupant's colour and depth there; the depth difference to the seat's origin over the
projection's depth per unit along the view ray is the occupant's distance behind the glass. The occupant then shows through the
glass, tinted by the pane, under its reflection and glints, a little darker than outside (`carOccupantLightPct` 80, darker low in
the cabin).

- **Cutaway by default** (`carOccupantOcclusion=false`): from the game's 30° camera the roof and the seats hide most of a driver
  physically (that is why the first release's box was invisible); the occupant is shown over the cabin's seats wherever the glass
  shows the cabin. `carOccupantOcclusion=true` lets the seats, headrests and floor in front hide it (the physical result).
- **Temporal reuse**: a tile is drawn again when the pose moves (any bone more than `carOccupantPoseEpsPct` 1 % of a model unit,
  at most `carOccupantHz` 60 a second), at least `carOccupantMinHz` 4 a second (the light on them), and at once when the car
  turned or tilted more than `carOccupantTurnDeg` 6° (the reprojection's parallax), the zoom or the people changed. Between,
  the glass maps its chassis points through the old tile's matrix: the occupant stays locked to the car however it moved or
  turned since (rigid between refreshes).
- **Other cameras**: the sun shadow pass draws the same car from the sun into the shadow atlas; the glass (and the chassis frame
  capture) now runs only for the world camera's draw (`ModelCamera.instance == VehicleModelCamera.instance`). Before that fix the
  occupant was drawn from the sun camera's frame (hugely zoomed in) whenever sun shadows were on, and every car's glass was drawn
  a second time into the shadow atlas for nothing.

## Ideas implemented and measured

RTX 4090, 5120x2160, empty options file (the stock look plus car glass), same-run alternation (`devCarGlassAlternate` +
`devCarGlassCycle`, dev skip bits 1024 no occupant pass, 2048 the pass drawn but not read, 4096 the tile at 50 % density, 8192
the tile drawn every frame, 16384 the pass without its model draws). GPU per frame of that entry; "occupant" is the atlas pass,
"moving" the cars, their glass and the characters.

| Variant (parked, seated, zoom 0.5, uncapped, ~480 fps) | occupant pass | moving vs no occupant | runs |
|---|---|---|---|
| Impostor drawn every frame | 22.3 us | +1.8 us | occ-cost2, occ-cost3 |
| the pass without the model draws (bind + clear) | 10.6 us | | occ-cost2 |
| the tile at 50 % density | 27.7 us (= 100 %: fill is not the cost) | | occ-cost1 |
| reuse, a tile every 4 frames | 6.1 us | +1.3 us | occ-cost2 |
| **reuse, pose-driven 4..60 Hz (default)** | **0.8 us** | **+0.4 us** | occ-cost3, occ-proxy3 |
| capsule proxy (no pass; `carOccupant=proxy`) | 0 | +2..+3 us (the capsules in the cabin ray-cast) | occ-proxy6 vs occ-proxy3 |

| Rosewood drive 120 km/h with the corner, zoom 0.5, 240 cap (~160 fps there) | occupant pass | moving | runs |
|---|---|---|---|
| no occupant pass | 0 | 23.6 us | occ-drive3 |
| impostor every frame | 27.3 us | 29.5 us | occ-drive3 |
| **impostor, pose-driven reuse (default)** | **2.0 us** | 26.4 us | occ-drive3 |

Whole frames, Rosewood 120 km/h drive at the 240 cap, zoom max, car glass on, `carOccupant=impostor` vs `off` (two pairs,
in-game overlay log over the route window; desktop GPU 38 % busy, game thread 48 % of a core, render thread 33 %: nothing
saturated, the drive is bound by chunk streaming bursts, as before):

| run | fps mean | p99 | p99.9 | max | > 33 ms |
|---|---|---|---|---|---|
| occ-final-impostor | 152.6 | 12.1 ms | 20.1 ms | 34.4 ms | 1 |
| occ-final-off | 153.5 | 11.2 ms | 15.8 ms | 24.1 ms | 0 |
| occ-final2-impostor | 154.5 | 10.7 ms | 17.1 ms | 22.9 ms | 0 |
| occ-final2-off | 154.0 | 11.4 ms | 17.3 ms | 19.9 ms | 0 |

The first pair's 34 ms frame sits in the corner's chunk burst (29.4 s); the repeat shows no difference beyond the drive's own
noise. While driving the occupant's tile is drawn ~4 times a second: the game's driving animation is a still seated pose (the
arms do not follow the steering), so only the slowest refresh and the turns redraw it (166 tiles over the drive, 14 for turns).

Render thread: a tile costs ~30 us drawn every frame (setup ~14, the character's draws ~20, with the stock look) and ~90 us
with HDR, pixel light, sun shadows, grading and AO on (the character's draw is what the same player costs on foot there); at the
idle 4 Hz refresh a tile is ~150-200 us cold (setup ~45, the model ~100-150), ~0.6-0.8 ms a second, ~3 us a frame at 240 fps.

Measured and left as options or dropped:
- **Stock showPassenger** (`carOccupant=stock`): the model among the moving objects; the car's opaque glass covers it (nothing
  visible, run occ-v12-stock) and stock's own inVehicle placement puts the model at the rear of the car (centroid 0.9 units off
  the seat, through the rear window). Kept as a dev A/B; any draw-into-the-world approach pays the every-frame draw (≥ 12 us of
  GPU, the measured draw share) where the reused tile pays 0.8.
- **Merging the occupant refresh into the probe pass's framebuffer switch**: bounded by the bind + clear share (10.6 us) times
  the refresh rate (4 a second parked): ≤ 0.2 us a frame. Not worth the probe atlas rework.
- **Lower tile density** (bit 4096): no change (the cost is the switch and the model's vertex / draw-call work, not the fill).
- **Capsule proxy** (`carOccupant=proxy`): torso, head, arms (hands on the wheel in the front seats), thighs and shins as ray-cast
  capsules, proportions measured on the game's own seated model through its tile (feet 0.44 below the hip point, the crown 0.56
  above, the feet 0.57 forward), colours from the worn clothes (each clothing texture's mean over its opaque texels, read once on a
  worker, times the item's tint), the body texture's skin tone and the hair colour. No pass at all, but 2-3 us more per frame in
  the glass than the reused impostor and far cruder; kept for machines where a character draw is expensive (`impostor` falls back
  to it until a car's first tile).
- **Spinning-car rig** (`car_rig_spin`): physics fights `setAngles`, the heading jumps 20°+ between frames; useful for looks at
  every heading, not for the reuse statistics (drives are).

## Several people, several cars, split screen (2026-10-03 afternoon)

Rig: `car_rig_p2=split|npc` (with `car_rig_seat`), `car_rig_p2_car=same|other`, `car_rig_p2_count=N` (npc: one person in
the driver's seat of each of the ring's next N cars). `split` adds a second local player through LuaManager's
`addPlayerToWorld` (the game's split-screen co-op, no controller: it only sits); `npc` adds IsoPlayers to the cell and un-culls
them the way `ConnectedPacket` does for a remote multiplayer player (the model is kept from the connection on, seated or not;
without that they have no model slot and the cars fall back to the proxy).

| Case (zoom 0.5 / 1, uncapped) | occupant pass, reused | drawn every frame | runs |
|---|---|---|---|
| split screen, driver + passenger in one car, both views | 1.2 us | 24.5 us | occ-mp-split2 |
| six occupied cars on screen (player + 5 others), one view | 1.6 us | 71.5 us | occ-mp-npc2 |

- Driver and passenger in one car share its tile (both drawn into it, the box round the cabin reaches every occupied seat, one depth
  system for all of them): both show in both split-screen views.
- Split screen: each view keeps its own tiles (keyed by car and player index; the zoom and the texel density are the view's). The
  first version keyed by car only and the two views' zooms made every pass redraw (`zoom` refreshes every frame, 2x the
  every-frame cost).
- Four occupied cars a view get the game's model; the fifth and later get the capsule proxy until a tile frees (120 frames after a
  car leaves the screen).
- Not tested: a real dedicated-server game with a second client (needs a second machine); the npc rig takes the remote player's
  model path.

## Pitfalls found on the way

- `Transform.getRotation` hands out an unnormalised quaternion (`getUnnormalizedRotation`): the turn test fired every frame until
  it was normalised.
- The atlas's tiles were keyed by slot with the frame serial only: a stale map entry of another car passed the check (the first
  runs showed the tile on the wrong car). Tiles now carry their vehicle.
- Harness runs do not read `~/Zomboid/pzopt/options.ini`: enhancement A/Bs need the keys as `--prop`.
- The proxy's seat bounding-box early-out skipped the body whenever a nearer seat had been hit; the body is tested first.

## Dev views

`devCarGlassView` / `devCarGlassViewList` (with `devCarGlassViewCycle`): 12 the occupant tile (magenta outside it, dark blue in
it, red behind the cabin's hit, the occupant where it shows), 13 the texel's tile uv, 14 / 15 its world NDC, 16 the occupant's
distance behind the glass vs the cabin hit's, 17 the tile's occupant wherever it covers the glass (no depth test), 18 the proxy
body's hit. `devCarOccupantDump=N` reads the N-th tile back to `~/Zomboid/pzopt-occupant-tile.png` and logs the drawn occupant's
centroid and extent in G.
