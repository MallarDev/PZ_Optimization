# Window tiles drawn as a dark vertical column (pixelLight, 2026-10-03)

Report (maintainer, screenshots 2026-10-03 16:13, save `Sandbox/2026-10-02_10-30-09`, Riverside shed east of 11298,6861):
"the windows do not have proper shadows vertically". Every wall tile holding a window was a full-height darker column (the
siding under, above and around the window), with a thin coloured strip on its right edge.

## Bisect (runs `ws-*`, desktop, `--source-save Sandbox/2026-10-02_10-30-09 --refresh-template --shot-at 2`)

| run | key | shed window tile / neighbours |
|---|---|---|
| ws-base2 | the maintainer's options file | 0.63 |
| ws-nosun2 / ws-noao2 / ws-norelief2 | sunShadows / ambientOcclusion / relief off | 0.68 / 0.63 / 0.64 |
| ws-noppl2 | `pixelLight=false` | 1.00 |

`devPplView=3` (owner squares) and `16` (the lit point) were the same on the window tile as on its neighbours: the pixels
were lit at the right place, by the right square. `devPplView=1` (the light alone) showed the window tile grey between
white neighbours.

## Cause

pixelLight interpolates each pixel's light between square centres, but only across neighbours whose corner colours the
native shares (it breaks them at walls). By day the native shares them across a **window** (light passes through it) and a
door frame. A wall pixel sits on its square's west / north edge, so half of its light came from the square behind the
wall: the room inside. Plain wall tiles kept their own light (no shared corners), the window tile took half the darker
room light: the column. The right-edge strip was the diagonal blend into the next room square.

## Fix: `pplWallEdge` (default on, `pzopt.PixelLight`)

The pack marks a square whose west / north edge carries a wall (`ChunkAo.edgeW / edgeN`: wall, window, door frame, cut
wall) and whose corners connect across it (bits 0 / 1 of the connection texture's g byte, torch visibility moved to bit 7;
such a square is not "simple", so the shader takes the edge path there). The shader drops the connection across that
wall (and the diagonal) for a point above the floor (`fz > 0.02`) on the wall's side of the square. Floors keep the blend
through windows and doorways. That is stock's rule for a wall sprite as well: it is lit by its own square.

## Verification (Jev, `harness/wall-column.py`)

Four window walls in the scene (shed under / above the window, the south wall of the house below, the grey house's east
wall), two shots per run, per-column medians; signed brightness step at the window tile's edges.

| scene | before (key off) | fix | reference (pixelLight off) | Jev |
|---|---|---|---|---|
| day 15:30 (ws-base2, ws-fix2-off / ws-fix2 / ws-noppl2) | ratio 0.63, edges -26 / -24 | 0.99, -1.6 / -0.4 | 1.00 | eliminated 1.00, every site fixed 0.92 |
| night 22:00, rain, lamps (ws-night-off / -fix, -fix-b / -noppl, -noppl-b) | 0.67, -31 / -30 | 0.98-1.00, -1 to -2.5 | 0.98 | eliminated 0.96, every site fixed 0.81 |

The night ratio above the shed window is taken against the left neighbour only (`--left-only`): the right one carries the
wall lamp's per-pixel glow, equally with the key on and off. Diff of fix vs key off over the whole frame: only window and
door-frame tiles change (plus sway / rain noise), each towards the reference.
