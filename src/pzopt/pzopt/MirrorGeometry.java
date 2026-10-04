package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import zombie.core.Core;
import zombie.core.properties.PropertyContainer;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.IsoDirections;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteGrid;
import zombie.iso.sprite.IsoSpriteInstance;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.tileDepth.TileDepthMapManager;
import zombie.tileDepth.TileDepthTexture;

/**
 * The room in front of a wall mirror, rebuilt behind the glass from the game's own tiles (key {@code mirrorsGeometry},
 * 2026-10-04: the maintainer's medicine cabinet showed a guess for three quarters of its glass, its view across the
 * bathroom hidden from the camera by the bathtub).
 *
 * <p>A reflection in a vertical plane is a quarter turn followed by the screen's left-right flip (x = y swaps u = x - y
 * for -u and keeps the iso depth x + y + 2z): an object's mirror image is its sprite <i>of the turned facing</i>, the
 * side the artists drew for that facing, flipped and set down where the plane sends its square. The game links every
 * movable's four facings ({@code <F>offset} properties, the sprite grids of multi-square ones), so the bathtub's far side
 * is a sprite that exists. Things the plane maps onto themselves (floors, rugs, walls across the plane) keep their own
 * sprite unflipped at the mirrored square; the wall facing the mirror, whose room side the camera never sees, is drawn
 * with the paint of the mirror's own wall.
 *
 * <p>Every texel gets the distance its reflected ray travels to it: the tile's depth map gives its iso depth
 * (w = x + y + 2z + 4 d, as the stock tileWithDepth shader blends between the square's far and near corner), and a ray
 * from the pane point of iso depth wM meets it at t = 3 / 8 (wM - w). The texels are drawn into the pane's place in a
 * geometry atlas with that distance as depth (nearest wins); the static march then takes the geometry wherever it found
 * nothing, only a stand-in, or a hit farther than the geometry (it passed behind something the camera cannot see round),
 * and keeps its own hit, the frame's real pixels, where the camera does see what the ray meets.
 */
final class MirrorGeometry {
   private MirrorGeometry() {
   }

   static final int GT = 8, MAXG = 1024;

   /** One frame's geometry instances (game thread fills, render thread draws). */
   static final class Batch {
      final float[] data = new float[MAXG * GT * 4];
      final Texture[] tex = new Texture[MAXG];
      final Texture[] depth = new Texture[MAXG];
      final int[] clear = new int[Mirrors.MAXR * 4];
      int n, nClear;

      void reset() {
         for (int i = 0; i < this.n; i++) {
            this.tex[i] = null;
            this.depth[i] = null;
         }
         this.n = 0;
         this.nClear = 0;
      }
   }

   static long panes, instances, turned, turnMisses;
   private static final java.util.Set<Object> DEV_SEEN = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()); // dev: panes logged once
   private static final String[] DEV_NAMES = new String[MAXG]; // dev: what each instance is (devMirrorsLog)

   // ------------------------------------------------------------------------------------------------ game thread

   private static final float[] EXT = new float[4];
   private static final ColorInfo WHITE = new ColorInfo(1F, 1F, 1F, 1F);

   /**
    * The room of a mirror pane due for its static march: its tiles as instances in the pane's atlas tile. Returns the
    * number added (0: no geometry for this pane, the march alone).
    */
   static int collect(Batch b, Mirrors.Refl r, Mirrors.Tile tl) {
      if (!r.mirror || !(r.key instanceof IsoObject o) || o.square == null || tl == null || tl.scale != 1F) {
         return 0;
      }
      IsoGridSquare msq = o.square;
      IsoRoom room = msq.getRoom();
      IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
      if (room == null || cell == null) {
         return 0; // (a mirror outdoors: no room to bound the geometry, the march alone)
      }
      Mirrors.paneExtent(r, EXT);
      int z = msq.z;
      float lo = EXT[0], hi = EXT[1], zhi = EXT[3] - z;
      float dmax = Math.min(Config.MIRRORS_REACH, 3F * zhi + 1.5F); // nothing standing on the floor farther than 3 h out reaches the glass
      float c = r.c;
      int playerIndex = IsoCamera.frameState.playerIndex;
      int start = b.n;
      Ctx k = CTX;
      k.b = b;
      k.r = r;
      k.tl = tl;
      k.axis = r.axis;
      k.c = c;
      k.z = z;
      k.playerIndex = playerIndex;
      int pa = (int)Math.floor(c), pb = (int)Math.floor(c + dmax);
      int la = (int)Math.floor(lo - dmax) - 1, lb = (int)Math.floor(hi) + 1;
      for (int p = pa; p <= pb; p++) {
         for (int l = la; l <= lb; l++) {
            IsoGridSquare sq = r.axis == 0 ? cell.getGridSquare(l, p, z) : cell.getGridSquare(p, l, z);
            if (sq == null || sq.getRoom() != room) {
               continue;
            }
            square(k, sq, o);
         }
      }
      farWall(k, cell, room, msq, la, lb, dmax);
      int added = b.n - start;
      if (Config.DEV_MIRRORS_LOG && added > 0 && DEV_SEEN.add(r.key) && DEV_SEEN.size() < 16) {
         StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "mirrors: dev room geometry of %s at %d,%d,%d (axis %d, c %.2f, lateral %.2f..%.2f, reach %.2f): %d tiles:", r.key instanceof IsoObject ko && ko.getSprite() != null ? ko.getSprite().getName() : "?", msq.x, msq.y, z, r.axis, c, lo, hi, dmax, added));
         for (int i = start; i < b.n && i < start + 40; i++) {
            sb.append(' ').append(DEV_NAMES[i]);
         }
         Log.info(sb.toString());
      }
      if (added > 0) {
         panes++;
         instances += added;
         if (b.nClear < Mirrors.MAXR) {
            int i = b.nClear++ * 4;
            b.clear[i] = tl.x;
            b.clear[i + 1] = tl.y;
            b.clear[i + 2] = tl.w;
            b.clear[i + 3] = tl.h;
         }
      }
      return added;
   }

   private static final class Ctx {
      Batch b;
      Mirrors.Refl r;
      Mirrors.Tile tl;
      int axis, z, playerIndex;
      float c;
   }

   private static final Ctx CTX = new Ctx();

   /** A room square's objects, mirrored. */
   private static void square(Ctx k, IsoGridSquare sq, IsoObject mirror) {
      ColorInfo light = sq.getLightInfo(k.playerIndex);
      if (light == null) {
         light = WHITE;
      }
      // the plane's image of this square: its north corner (continuous: the plane stands off the wall)
      float mx = k.axis == 1 ? 2F * k.c - sq.x - 1F : sq.x, my = k.axis == 0 ? 2F * k.c - sq.y - 1F : sq.y;
      for (int i = 0, n = sq.getObjects().size(); i < n; i++) {
         IsoObject obj = sq.getObjects().get(i);
         if (obj == null || obj == mirror || obj instanceof IsoMovingObject || obj instanceof IsoWindow || obj instanceof IsoDoor || obj instanceof IsoCurtain
               || obj instanceof IsoWorldInventoryObject || obj instanceof IsoTree) {
            continue;
         }
         IsoSprite s = obj.getSprite();
         if (s == null || s.getProperties() == null) {
            continue;
         }
         PropertyContainer p = s.getProperties();
         if (p.has("IsMirror")) {
            continue;
         }
         float ryo = obj.getRenderYOffset();
         boolean floor = p.has(IsoFlagType.solidfloor);
         boolean wallW = p.has(IsoFlagType.WallW) || p.has(IsoFlagType.DoorWallW) || p.has(IsoFlagType.WindowW) || s.cutW;
         boolean wallN = p.has(IsoFlagType.WallN) || p.has(IsoFlagType.DoorWallN) || p.has(IsoFlagType.WindowN) || s.cutN;
         if (p.has(IsoFlagType.WallNW) || p.has(IsoFlagType.WallSE) || wallW && wallN) {
            continue; // (a corner holds both orientations: one across the plane, one along it)
         }
         if (wallW || wallN) {
            // a wall across the plane maps onto a wall of the same orientation (its texture mirrored along it, the shape
            // the same); one along the plane is the far wall (farWall), the mirror's own wall stands behind the glass
            if (k.axis == 1 ? wallW : wallN) {
               continue;
            }
            add(k, s, depthOf(s, k.axis == 1 ? TileDepthMapManager.TileDepthPreset.NWall : TileDepthMapManager.TileDepthPreset.WWall), mx, my, ryo, false, light, Float.NaN);
            attached(k, obj, mx, my, ryo, light, k.axis == 1 ? TileDepthMapManager.TileDepthPreset.NWall : TileDepthMapManager.TileDepthPreset.WWall);
            continue;
         }
         if (floor) {
            add(k, s, depthOf(s, TileDepthMapManager.TileDepthPreset.Floor), mx, my, ryo, false, light, Float.NaN);
            attached(k, obj, mx, my, ryo, light, TileDepthMapManager.TileDepthPreset.Floor);
            continue;
         }
         IsoSprite t = turnedSprite(s, k.axis);
         // an object without its turned facing that hangs on a square edge (a door frame, a light switch, a wall trim)
         // lies on that edge, not mid-square: it mirrors as a wall of that orientation does
         boolean onW = p.has(IsoFlagType.attachedW) || p.has(IsoFlagType.attachedE) || p.has("doorFrW");
         boolean onN = p.has(IsoFlagType.attachedN) || p.has(IsoFlagType.attachedS) || p.has("doorFrN");
         if (t == null && (onW || onN)) {
            if (onW && onN || (k.axis == 1 ? onW : onN)) {
               continue; // (along the plane: on the mirror's own wall, behind the glass, or on the far wall)
            }
            add(k, s, depthOf(s, k.axis == 1 ? TileDepthMapManager.TileDepthPreset.NWall : TileDepthMapManager.TileDepthPreset.WWall), mx, my, ryo, false, light, Float.NaN);
            continue;
         }
         if (t != null) {
            turned++;
            add(k, t, depthOf(t, null), mx, my, ryo, true, light, Float.NaN);
            if (Config.DEV_MIRRORS_LOG && k.b.n > 0 && DEV_NAMES[k.b.n - 1] != null && DEV_NAMES[k.b.n - 1].startsWith(String.valueOf(t.getName()))) {
               DEV_NAMES[k.b.n - 1] = s.getName() + "->" + DEV_NAMES[k.b.n - 1];
            }
         } else {
            if (p.has("Facing")) {
               turnMisses++;
            }
            add(k, s, depthOf(s, null), mx, my, ryo, false, light, Float.NaN);
         }
      }
   }

   /** A floor's / wall's overlays (rugs, wallpaper, trims) drawn with it; never a mirror. */
   private static void attached(Ctx k, IsoObject obj, float mx, float my, float ryo, ColorInfo light, TileDepthMapManager.TileDepthPreset preset) {
      java.util.ArrayList<IsoSpriteInstance> a = obj.getAttachedAnimSprite();
      if (a == null) {
         return;
      }
      for (int i = 0, n = a.size(); i < n; i++) {
         IsoSpriteInstance si = a.get(i);
         IsoSprite s = si == null ? null : si.getParentSprite();
         if (s == null || s.getProperties() == null || s.getProperties().has("IsMirror")) {
            continue;
         }
         add(k, s, depthOf(s, preset), mx, my, ryo, false, light, Float.NaN);
      }
   }

   /**
    * The wall facing the mirror across the room: the camera never sees its room side (it is cut away or turned from the
    * camera), so it is drawn in the paint of the mirror's own wall, a plain wall of the same orientation, at the plane's
    * image of it; every texel of it lies at the wall's distance from the glass.
    */
   private static void farWall(Ctx k, IsoCell cell, IsoRoom room, IsoGridSquare msq, int la, int lb, float dmax) {
      IsoSprite paint = null;
      IsoObject paintObj = null;
      int mp = k.axis == 1 ? msq.x : msq.y, ml = k.axis == 1 ? msq.y : msq.x;
      for (int d = 0; d <= 6 && paint == null; d++) {
         for (int sgn = -1; sgn <= 1 && paint == null; sgn += 2) {
            int l = ml + sgn * d;
            IsoGridSquare sq = k.axis == 1 ? cell.getGridSquare(mp, l, k.z) : cell.getGridSquare(l, mp, k.z);
            if (sq == null || sq.getRoom() != room) {
               continue;
            }
            IsoObject w = plainWall(sq, k.axis);
            if (w != null) {
               paint = w.getSprite();
               paintObj = w;
            }
         }
      }
      if (paint == null) {
         return;
      }
      for (int l = la; l <= lb; l++) {
         // from the mirror's row across the room to the first wall of the mirror's orientation
         for (int p = mp; p <= mp + (int)Math.ceil(dmax) + 1; p++) {
            IsoGridSquare sq = k.axis == 1 ? cell.getGridSquare(p, l, k.z) : cell.getGridSquare(l, p, k.z);
            if (sq == null || sq.getRoom() != room) {
               break;
            }
            IsoGridSquare next = k.axis == 1 ? cell.getGridSquare(p + 1, l, k.z) : cell.getGridSquare(l, p + 1, k.z);
            if (next == null) {
               break;
            }
            int kind = wallKind(next, k.axis);
            if (kind == 0) {
               continue;
            }
            float dist = p + 1 - k.c;
            if (kind == 1 && dist > 0F && dist <= dmax + 1F) {
               ColorInfo light = sq.getLightInfo(k.playerIndex);
               if (light == null) {
                  light = WHITE;
               }
               float wx = k.axis == 1 ? 2F * k.c - (p + 1) : l, wy = k.axis == 0 ? 2F * k.c - (p + 1) : l;
               add(k, paint, null, wx, wy, paintObj.getRenderYOffset(), false, light, dist);
               java.util.ArrayList<IsoSpriteInstance> a = paintObj.getAttachedAnimSprite();
               if (a != null) {
                  for (IsoSpriteInstance si : a) {
                     IsoSprite s = si == null ? null : si.getParentSprite();
                     if (s != null && s.getProperties() != null && !s.getProperties().has("IsMirror")) {
                        add(k, s, null, wx, wy, paintObj.getRenderYOffset(), false, light, dist);
                     }
                  }
               }
            }
            if (kind == 2 && dist > 0F && dist <= dmax + 1F) {
               // a door frame / window in the far wall: its own frame, a closed door and a window as they are (both
               // sides of a door look alike; the window's glass texels are translucent and leave the march its view)
               ColorInfo light = sq.getLightInfo(k.playerIndex);
               if (light == null) {
                  light = WHITE;
               }
               float wx = k.axis == 1 ? 2F * k.c - (p + 1) : l, wy = k.axis == 0 ? 2F * k.c - (p + 1) : l;
               boolean framed = false;
               for (int i = 0, n = next.getObjects().size(); i < n; i++) {
                  IsoObject obj = next.getObjects().get(i);
                  IsoSprite s = obj == null ? null : obj.getSprite();
                  if (s == null || s.getProperties() == null) {
                     continue;
                  }
                  PropertyContainer pp = s.getProperties();
                  boolean edge;
                  if (obj instanceof IsoDoor door) {
                     edge = door.getNorth() == (k.axis == 0) && !door.IsOpen();
                  } else if (obj instanceof IsoWindow win) {
                     edge = win.getNorth() == (k.axis == 0);
                  } else {
                     edge = k.axis == 1 ? pp.has(IsoFlagType.DoorWallW) || pp.has(IsoFlagType.WindowW) : pp.has(IsoFlagType.DoorWallN) || pp.has(IsoFlagType.WindowN);
                  }
                  if (!edge) {
                     continue;
                  }
                  boolean frame = !(obj instanceof IsoDoor) && !(obj instanceof IsoWindow);
                  if (frame && s.getName() != null && s.getName().startsWith("walls_exterior")) {
                     continue; // (an exterior wall's frame is its outside, the siding: the room side is the paint below)
                  }
                  framed |= frame;
                  // a hair in front of the paint, so it wins the depth test whatever the draw order
                  add(k, s, null, wx, wy, obj.getRenderYOffset(), false, light, dist - 0.03F);
               }
               if (!framed) {
                  add(k, paint, null, wx, wy, paintObj.getRenderYOffset(), false, light, dist);
               }
            }
            break;
         }
      }
   }

   /** 0 no wall of the mirror's orientation on this square's near edge, 1 a plain wall, 2 a door frame / window / corner. */
   private static int wallKind(IsoGridSquare sq, int axis) {
      int kind = 0;
      for (int i = 0, n = sq.getObjects().size(); i < n; i++) {
         IsoObject obj = sq.getObjects().get(i);
         IsoSprite s = obj == null ? null : obj.getSprite();
         if (s == null || s.getProperties() == null) {
            continue;
         }
         PropertyContainer p = s.getProperties();
         boolean on = axis == 1 ? p.has(IsoFlagType.WallW) || p.has(IsoFlagType.DoorWallW) || p.has(IsoFlagType.WindowW) || s.cutW
               : p.has(IsoFlagType.WallN) || p.has(IsoFlagType.DoorWallN) || p.has(IsoFlagType.WindowN) || s.cutN;
         if (p.has(IsoFlagType.WallNW)) {
            on = true;
         }
         if (!on) {
            continue;
         }
         boolean plain = !(axis == 1 ? p.has(IsoFlagType.DoorWallW) || p.has(IsoFlagType.WindowW) : p.has(IsoFlagType.DoorWallN) || p.has(IsoFlagType.WindowN));
         kind = Math.max(kind, plain ? 1 : 2);
      }
      return kind;
   }

   private static IsoObject plainWall(IsoGridSquare sq, int axis) {
      for (int i = 0, n = sq.getObjects().size(); i < n; i++) {
         IsoObject obj = sq.getObjects().get(i);
         IsoSprite s = obj == null ? null : obj.getSprite();
         if (s == null || s.getProperties() == null) {
            continue;
         }
         PropertyContainer p = s.getProperties();
         if (p.has(IsoFlagType.WallNW) || p.has(IsoFlagType.WallSE)) {
            continue;
         }
         if (axis == 1 ? p.has(IsoFlagType.WallW) && !p.has(IsoFlagType.DoorWallW) && !p.has(IsoFlagType.WindowW)
               : p.has(IsoFlagType.WallN) && !p.has(IsoFlagType.DoorWallN) && !p.has(IsoFlagType.WindowN)) {
            return obj;
         }
      }
      return null;
   }

   /**
    * The sprite of this object's facing turned the way the plane's reflection turns it (then flipped on screen). A west-wall
    * plane (x = c): the reflection is the turn (x, y) -> (y, 2c - x) and the flip, facings E -> N, S -> E, W -> S, N -> W; a
    * north-wall plane (y = c): (x, y) -> (2c - y, x), E -> S, S -> W, W -> N, N -> E. A multi-square object's piece is the
    * turned grid's piece at the turned position. Null when the tile set has no such facing.
    */
   static IsoSprite turnedSprite(IsoSprite s, int axis) {
      PropertyContainer p = s.getProperties();
      String f = p.get("Facing");
      if (f == null) {
         return null;
      }
      String tf = switch (f) {
         case "E" -> axis == 1 ? "N" : "S";
         case "S" -> axis == 1 ? "E" : "W";
         case "W" -> axis == 1 ? "S" : "N";
         case "N" -> axis == 1 ? "W" : "E";
         default -> null;
      };
      String off = tf == null ? null : p.get(tf + "offset");
      if (off == null) {
         return null;
      }
      int delta;
      try {
         delta = Integer.parseInt(off.trim());
      } catch (NumberFormatException e) {
         return null;
      }
      IsoSprite t = IsoSprite.getSprite(IsoSpriteManager.instance, s, delta);
      if (t == null) {
         return null;
      }
      IsoSpriteGrid g = s.getSpriteGrid();
      if (g == null) {
         return t;
      }
      IsoSpriteGrid tg = t.getSpriteGrid();
      if (tg == null || tg.getWidth() != g.getHeight() || tg.getHeight() != g.getWidth()) {
         return null;
      }
      int gx = g.getSpriteGridPosX(s), gy = g.getSpriteGridPosY(s), gz = g.getSpriteGridPosZ(s);
      int w = g.getWidth(), h = g.getHeight();
      int tx = axis == 1 ? gy : h - 1 - gy;
      int ty = axis == 1 ? w - 1 - gx : gx;
      return tg.isValidXYZ(tx, ty, gz) ? tg.getSprite(tx, ty, gz) : null;
   }

   /** The depth map a tile is drawn with: its own, else the preset of its kind (null: none, a constant distance). */
   private static Texture depthOf(IsoSprite s, TileDepthMapManager.TileDepthPreset preset) {
      TileDepthTexture d = s.depthTexture;
      if (d != null && !d.isEmpty()) {
         return d.getTexture();
      }
      return preset == null ? null : TileDepthMapManager.instance.getTextureForPreset(preset);
   }

   /**
    * One sprite drawn at the square whose north corner is (sx, sy) on the pane's level, flipped left-right or not, into
    * the pane's atlas tile. wallDist: NaN = the texels' distances from the depth map, else that one distance.
    */
   private static void add(Ctx k, IsoSprite s, Texture depth, float sx, float sy, float ryo, boolean flip, ColorInfo light, float wallDist) {
      Batch b = k.b;
      if (b.n >= MAXG) {
         return;
      }
      Texture tex = s.getTextureForCurrentFrame(IsoDirections.N);
      if (tex == null || !tex.isReady() || tex.getID() <= 0) {
         return;
      }
      if (depth != null && (!depth.isReady() || depth.getID() <= 0)) {
         depth = null;
      }
      Mirrors.Refl r = k.r;
      Mirrors.Tile tl = k.tl;
      int ts = Core.tileScale;
      double a32 = 32.0 * ts, a16 = 16.0 * ts;
      float scale = ts == 2 && tex.getWidthOrig() == 64 && tex.getHeightOrig() == 128 ? 2F : 1F;
      double w = tex.getWidth() * scale, h = tex.getHeight() * scale;
      double fx0 = s.soffX + tex.getOffsetX() * scale; // the texels' place in the sprite's frame (px)
      double fy0 = s.soffY + tex.getOffsetY() * scale;
      // the stock placement (IsoObject.render -> prepareToRenderSprite -> Texture.render), camera-free, in iso units:
      // u = (x - y) - 1 + frame x / a32, v = (x + y - 6z) - 6 - renderYOffset / 16 + frame y / a16
      double un = sx - sy, vn = sx + sy - 6.0 * k.z - 6.0 - ryo / 16.0;
      double u0 = flip ? un + 1.0 - (fx0 + w) / a32 : un - 1.0 + fx0 / a32;
      double v0 = vn + fy0 / a16;
      double u1 = u0 + w / a32, v1 = v0 + h / a16;
      float ax0 = (float)(tl.x + (u0 - r.u0) * tl.ppu), ax1 = (float)(tl.x + (u1 - r.u0) * tl.ppu);
      float ay0 = (float)(tl.y + (v0 - r.v0) * tl.ppv), ay1 = (float)(tl.y + (v1 - r.v0) * tl.ppv);
      if (ax1 < tl.x || ax0 > tl.x + tl.w || ay1 < tl.y || ay0 > tl.y + tl.h) {
         return; // (nowhere on the pane's glass)
      }
      int o = b.n * GT * 4;
      float[] d = b.data;
      d[o] = ax0;
      d[o + 1] = ay0;
      d[o + 2] = ax1;
      d[o + 3] = ay1;
      // colour uv at the quad's left / right edge (flipped: the sprite's right edge on the left)
      float sl = flip ? tex.getXEnd() : tex.getXStart(), sr = flip ? tex.getXStart() : tex.getXEnd();
      d[o + 4] = sl;
      d[o + 5] = tex.getYStart();
      d[o + 6] = sr;
      d[o + 7] = tex.getYEnd();
      if (depth != null && scale == 1F) {
         // the depth map's texel of a sprite-frame px (TileDepthModifier's mapping), outside its rectangle nothing is drawn
         double dw = Math.max(1, depth.getWidthHW()), dh = Math.max(1, depth.getHeightHW());
         double fxl = flip ? fx0 + w : fx0, fxr = flip ? fx0 : fx0 + w;
         d[o + 8] = (float)(depth.getXStart() + (fxl - depth.offsetX) / dw);
         d[o + 9] = (float)(depth.getYStart() + (fy0 - depth.offsetY) / dh);
         d[o + 10] = (float)(depth.getXStart() + (fxr - depth.offsetX) / dw);
         d[o + 11] = (float)(depth.getYStart() + (fy0 + h - depth.offsetY) / dh);
         d[o + 12] = depth.getXStart();
         d[o + 13] = depth.getYStart();
         d[o + 14] = (float)(depth.getXStart() + depth.getWidth() / dw);
         d[o + 15] = (float)(depth.getYStart() + depth.getHeight() / dh);
      } else {
         depth = null;
         d[o + 8] = d[o + 9] = d[o + 10] = d[o + 11] = 0F;
         d[o + 12] = d[o + 13] = 1F;
         d[o + 14] = d[o + 15] = 0F; // (max < min: no depth map)
      }
      // the distance: t = 3/8 (wM - wBase) - 3/2 d, wM the pane point's iso depth under the atlas px (linear on the pane)
      double wBase = sx + sy + 2.0 * k.z;
      double c = r.c;
      double wM0 = r.axis == 0 ? u0 + 2.0 * c + (u0 + 2.0 * c - v0) / 3.0 : 2.0 * c - u0 + (2.0 * c - u0 - v0) / 3.0;
      d[o + 16] = (float)(wM0 - wBase);
      d[o + 17] = (float)((r.axis == 0 ? 4.0 / 3.0 : -4.0 / 3.0) / tl.ppu);
      d[o + 18] = (float)(-1.0 / 3.0 / tl.ppv);
      boolean wall = !Float.isNaN(wallDist);
      d[o + 19] = wall ? 0F : 1F;
      d[o + 20] = wall ? wallDist : depth == null ? -0.75F : 0F; // (no depth map: the square's middle, d = 0.5)
      d[o + 21] = wall || depth == null ? 0F : -1.5F;
      d[o + 22] = 0F;
      d[o + 23] = 0F;
      d[o + 24] = light.r;
      d[o + 25] = light.g;
      d[o + 26] = light.b;
      d[o + 27] = 0F;
      d[o + 28] = tl.x;
      d[o + 29] = tl.y;
      d[o + 30] = tl.x + tl.w;
      d[o + 31] = tl.y + tl.h;
      b.tex[b.n] = tex;
      b.depth[b.n] = depth;
      if (Config.DEV_MIRRORS_LOG) {
         DEV_NAMES[b.n] = s.getName() + (flip ? "/flip" : "") + (wall ? "/wall" : "") + (depth == null ? "/nodepth" : "") + String.format(java.util.Locale.ROOT, "@%.1f,%.1f", sx, sy);
      }
      b.n++;
   }

   /** The instances sorted by sprite page and depth map, so each pair binds once (insertion sort: tens of entries). */
   static void sort(Batch b) {
      float[] row = new float[GT * 4];
      for (int i = 1; i < b.n; i++) {
         long key = key(b.tex[i], b.depth[i]);
         int j = i - 1;
         if (key(b.tex[j], b.depth[j]) <= key) {
            continue;
         }
         Texture t = b.tex[i], dt = b.depth[i];
         System.arraycopy(b.data, i * GT * 4, row, 0, GT * 4);
         while (j >= 0 && key(b.tex[j], b.depth[j]) > key) {
            b.tex[j + 1] = b.tex[j];
            b.depth[j + 1] = b.depth[j];
            System.arraycopy(b.data, j * GT * 4, b.data, (j + 1) * GT * 4, GT * 4);
            j--;
         }
         b.tex[j + 1] = t;
         b.depth[j + 1] = dt;
         System.arraycopy(row, 0, b.data, (j + 1) * GT * 4, GT * 4);
      }
   }

   private static long key(Texture t, Texture d) {
      return ((long)(t == null ? 0 : t.getID()) << 32) | (d == null ? 0 : d.getID());
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static int prog, fbo, colorTex, depthRb;
   private static final int[] DATA = new int[3];
   private static int uGeo, uFirst, uParams, uSprite, uDepth;
   private static final FloatBuffer UP = BufferUtils.createFloatBuffer(MAXG * GT * 4);
   static boolean broken;

   /** The geometry atlas (same layout as the static atlas), read by the static march. 0 until the first geometry. */
   static int texture() {
      return colorTex;
   }

   private static boolean ensure(boolean full) {
      if (prog != 0) {
         return true;
      }
      if (broken) {
         return false;
      }
      String ver = full ? "#version 430\n" : "#version 410 core\n";
      prog = AmbientOcclusion.link(ver + VERT, ver + FRAG);
      if (prog == 0) {
         broken = true;
         Log.warn("mirrors: room geometry shaders did not link; geometry off");
         return false;
      }
      uGeo = GL20.glGetUniformLocation(prog, "Geo");
      uSprite = GL20.glGetUniformLocation(prog, "Sprite");
      uDepth = GL20.glGetUniformLocation(prog, "DepthMap");
      uFirst = GL20.glGetUniformLocation(prog, "first");
      uParams = GL20.glGetUniformLocation(prog, "params");
      GL20.glUseProgram(prog);
      GL20.glUniform1i(uGeo, 2);
      GL20.glUniform1i(uSprite, 4);
      GL20.glUniform1i(uDepth, 14);
      GL20.glUseProgram(0);
      for (int i = 0; i < DATA.length; i++) {
         DATA[i] = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, DATA[i]);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, GT, MAXG, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      }
      int a = Mirrors.ATLAS;
      colorTex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, a, a, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      depthRb = GL30.glGenRenderbuffers();
      GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRb);
      GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, a, a);
      GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
      fbo = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);
      GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depthRb);
      int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zombie.core.textures.TextureFBO.lastID);
      if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
         broken = true;
         Log.warn("mirrors: room geometry framebuffer incomplete (" + status + "); geometry off");
         return false;
      }
      Log.info("mirrors: room geometry atlas " + a + "x" + a + ", program " + prog);
      return true;
   }

   /**
    * Render thread, the static pass before its march: the due panes' tiles of the geometry atlas cleared ("nothing") and
    * the frame's instances drawn into them, nearest texel kept. Leaves the geometry atlas' framebuffer bound.
    */
   static boolean draw(Batch b, long serial, boolean full) {
      if (b.n == 0 || !ensure(full)) {
         return false;
      }
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL11.glViewport(0, 0, Mirrors.ATLAS, Mirrors.ATLAS);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glColorMask(true, true, true, true);
      GL11.glDepthMask(true);
      GL11.glEnable(GL11.GL_SCISSOR_TEST);
      GL11.glClearColor(0F, 0F, 0F, 1F); // (alpha 255: no geometry here)
      GL11.glClearDepth(1.0);
      for (int i = 0; i < b.nClear; i++) {
         GL11.glScissor(b.clear[i * 4], b.clear[i * 4 + 1], b.clear[i * 4 + 2], b.clear[i * 4 + 3]);
         GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
      }
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      GL11.glDepthFunc(GL11.GL_LESS);
      UP.clear();
      UP.put(b.data, 0, b.n * GT * 4).flip();
      GL13.glActiveTexture(GL13.GL_TEXTURE2);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, DATA[(int)(serial % 3)]);
      GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, GT, b.n, GL11.GL_RGBA, GL11.GL_FLOAT, UP);
      GL20.glUseProgram(prog);
      GL20.glUniform4f(uParams, Math.max(1, Config.MIRRORS_REACH), 0F, 0F, 0F);
      int i = 0;
      while (i < b.n) {
         Texture t = b.tex[i], dt = b.depth[i];
         int j = i + 1;
         while (j < b.n && b.tex[j] == t && b.depth[j] == dt) {
            j++;
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE4);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.getID());
         GL13.glActiveTexture(GL13.GL_TEXTURE14);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, dt == null ? 0 : dt.getID());
         GL20.glUniform1f(uFirst, i);
         GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, j - i);
         i = j;
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE14);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDepthMask(false);
      return true;
   }

   static final String VERT = String.join("\n",
         "uniform sampler2D Geo;",
         "uniform float first;",
         "flat out int inst;",
         "out vec2 cUv;",
         "out vec2 dUv;",
         "void main() {",
         "   inst = gl_InstanceID + int(first);",
         "   vec4 r = texelFetch(Geo, ivec2(0, inst), 0);",
         "   vec4 s = texelFetch(Geo, ivec2(1, inst), 0);",
         "   vec4 d = texelFetch(Geo, ivec2(2, inst), 0);",
         "   int k = gl_VertexID;",
         "   vec2 c = vec2((k == 1 || k == 2) ? 1.0 : 0.0, k >= 2 ? 1.0 : 0.0);",
         "   vec2 apx = mix(r.xy, r.zw, c);",
         "   cUv = mix(s.xy, s.zw, c);",
         "   dUv = mix(d.xy, d.zw, c);",
         "   gl_Position = vec4(apx / " + Mirrors.ATLAS + ".0 * 2.0 - 1.0, 0.0, 1.0);",
         "}");

   static final String FRAG = String.join("\n",
         "uniform sampler2D Geo;",
         "uniform sampler2D Sprite;",
         "uniform sampler2D DepthMap;",
         "uniform vec4 params;", // the longest ray (squares)
         "flat in int inst;",
         "in vec2 cUv;",
         "in vec2 dUv;",
         "out vec4 fragColor;",
         "void main() {",
         "   vec2 ap = gl_FragCoord.xy;",
         "   vec4 clip = texelFetch(Geo, ivec2(7, inst), 0);", // the pane's tile: nothing spills into a neighbour's
         "   if (ap.x < clip.x || ap.y < clip.y || ap.x > clip.z || ap.y > clip.w) discard;",
         "   vec4 c = texture(Sprite, cUv);",
         "   if (c.a < 0.5) discard;",
         "   vec4 dr = texelFetch(Geo, ivec2(3, inst), 0);",
         "   float dd = 0.5;",
         "   if (dr.z >= dr.x) {",
         "      if (dUv.x < dr.x || dUv.x > dr.z || dUv.y < dr.y || dUv.y > dr.w) discard;",
         "      dd = texture(DepthMap, dUv).r;",
         "      if (dd <= 0.0) discard;", // (as tileWithDepth: no depth, no texel)
         "   }",
         "   vec4 r0 = texelFetch(Geo, ivec2(0, inst), 0);",
         "   vec4 tw = texelFetch(Geo, ivec2(4, inst), 0);",
         "   vec4 tb = texelFetch(Geo, ivec2(5, inst), 0);",
         "   vec4 li = texelFetch(Geo, ivec2(6, inst), 0);",
         "   float W = tw.x + tw.y * (ap.x - r0.x) + tw.z * (ap.y - r0.y);",
         "   float t = tw.w * 0.375 * W + tb.x + tb.y * dd;",
         "   if (t < 0.0 || t > params.x) discard;", // behind the glass, or past the longest ray
         "   gl_FragDepth = t / params.x;",
         "   vec3 col = c.rgb / max(c.a, 0.001) * li.rgb;", // (the game's textures are premultiplied)
         "   fragColor = vec4(col, floor(clamp(t / params.x, 0.0, 0.99) * 126.0 + 0.5) * 2.0 / 255.0);", // the static atlas' code: distance, no stand-in bit
         "}");

   static String stats() {
      return "room geometry: " + panes + " pane marches with geometry, " + instances + " tiles drawn (" + turned + " turned, " + turnMisses + " without their turned facing)" + (broken ? ", broken" : "");
   }
}
