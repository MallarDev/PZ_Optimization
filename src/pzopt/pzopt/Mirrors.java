package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.properties.PropertyContainer;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoWindow;
import zombie.iso.sprite.IsoSprite;
import zombie.vehicles.BaseVehicle;

/**
 * Mirror and window reflections (key {@code mirrors}, 2026-10-03).
 *
 * <p>The iso camera is orthographic and looks down at 30 degrees, so the mirror of every view ray about a vertical plane is
 * the same ray for every pixel: a mirror on a north wall (facing +y) sends the view ray off along world (-1, +1, -1/3) per
 * square, which on screen is a straight line of slope 1/2 down-left (down-right for a west-wall plane), and along it the
 * iso depth w = x + y + 2z falls by 2/3 a square. A reflected ray from a pane at height h reaches the floor after 3 h
 * squares: wall mirrors show the floor and whoever stands within two squares of them, an upper-floor window the street
 * four to six squares out.
 *
 * <p>Per frame:
 * <ol>
 * <li>capture: windows and mirror tiles are drawn per frame (the mirrors moved out of the chunk textures); while one draws,
 * TextureDraw.Create hands its quad and texture here; the quad is kept in camera-free iso units (u = x - y,
 * v = x + y - 6z).</li>
 * <li>static pass, right after the chunk composite (the frame holds the static world alone), at most every
 * mirrorsStaticEvery frames: the panes captured last frame whose atlas tile is new, came further on screen or is due for
 * its staggered refresh. Every glass pixel marches its reflected ray along the screen line through the world depth (floor
 * first, then taps and bisection) and imageStores colour + hit distance into the pane's tile of an RGBA8 atlas, its glass
 * mask into an R8 atlas. The ortho camera's reflected rays do not move with a pan: a tile holds while the scene does.</li>
 * <li>model pass, right after the moving objects: every character / vehicle a visible pane can see is drawn once more,
 * its model view post-multiplied by the plane's reflection (its lighting stays its own), into an offscreen layer; kept
 * for a few frames while nothing moved.</li>
 * <li>composite, after each level's translucent objects (where the panes themselves were drawn): one draw; the hardware
 * depth test at the pane's analytic depth hides whatever stands in front, then the tile's reflection and the model layer
 * (nearer hit wins: the layer depth gives the model's distance) blend over the glass. Every 8th frame it also marks which
 * panes have pixels on screen (read back by the game thread from a persistent buffer three frames later, no sync): hidden
 * panes are neither marched nor mirror anyone.</li>
 * </ol>
 * Measurements and the variants tried: docs/findings-mirrors-2026-10-03.md.
 */
public final class Mirrors {
   private Mirrors() {
   }

   static volatile boolean failed;
   private static boolean frameOn, altLogged;
   private static int skipNow;
   private static long frames;

   public static boolean active() {
      return Config.MIRRORS && !failed && Overrides.enabled() && !CoreGl.legacyMac();
   }

   private static final String[] CYCLE = Config.DEV_MIRRORS_CYCLE.isBlank() ? new String[0] : Config.DEV_MIRRORS_CYCLE.split(",");

   /** dev: the alternation / cycle entry of this moment (-1: no cycle). */
   private static int cycleIndex() {
      int ms = Config.DEV_MIRRORS_ALTERNATE;
      return ms <= 0 || CYCLE.length == 0 ? -1 : (int)(System.currentTimeMillis() / ms % CYCLE.length);
   }

   private static boolean altOn() {
      int ms = Config.DEV_MIRRORS_ALTERNATE;
      int c = cycleIndex();
      if (c >= 0) {
         return !"off".equals(CYCLE[c].trim());
      }
      return ms <= 0 || (System.currentTimeMillis() / ms & 1L) == 0L;
   }

   /** The skip bits now: devMirrorsSkip, or the cycle's entry. */
   static int skip() {
      int c = cycleIndex();
      if (c >= 0 && !"off".equals(CYCLE[c].trim())) {
         return Integer.parseInt(CYCLE[c].trim());
      }
      return Config.DEV_MIRRORS_SKIP;
   }

   static String section(String name) {
      int c = cycleIndex();
      if (c >= 0) {
         return name + ".c" + c;
      }
      return Config.DEV_MIRRORS_ALTERNATE <= 0 ? name : name + (altOn() ? ".mon" : ".moff");
   }

   // ------------------------------------------------------------------------------------------------ mirror tiles

   private static final float[] NONE = new float[0];
   private static final ConcurrentHashMap<IsoSprite, float[]> SPRITES = new ConcurrentHashMap<>();
   private static HashMap<String, Integer> maskCells;
   private static int maskRows = 1;

   /** A mirror tile the camera sees the glass of: {axis (0 north wall, 1 west wall), plane offset from the wall, mask cell}, else null. */
   static float[] mirrorInfo(IsoSprite s) {
      if (s == null) {
         return null;
      }
      float[] v = SPRITES.get(s);
      if (v == null) {
         v = compute(s);
         SPRITES.put(s, v);
      }
      return v.length == 0 ? null : v;
   }

   private static float[] compute(IsoSprite s) {
      PropertyContainer p = s.getProperties();
      if (p == null || !p.has("IsMirror") || s.getName() == null) {
         return NONE;
      }
      String facing = p.get("Facing");
      int axis = "S".equals(facing) ? 0 : "E".equals(facing) ? 1 : -1;
      if (axis < 0) {
         return NONE; // N / W facing: the camera sees the back
      }
      Integer cell = masks().get(s.getName());
      if (cell == null) {
         return NONE;
      }
      String n = s.getName();
      // where the glass stands in front of its wall (squares): a medicine cabinet's door, a dresser's mirror, a framed pane
      float off = n.startsWith("fixtures_bathroom") ? 0.22F : n.startsWith("furniture_storage") ? 0.12F : 0.03F;
      return new float[] {axis, off, cell};
   }

   /** Is this tile drawn per frame for the mirrors (out of the chunk textures)? FBORenderCell's per-frame tile test. */
   public static boolean perFrame(IsoSprite s) {
      return Config.MIRRORS && !failed && Overrides.enabled() && mirrorInfo(s) != null;
   }

   private static synchronized HashMap<String, Integer> masks() {
      if (maskCells != null) {
         return maskCells;
      }
      HashMap<String, Integer> m = new HashMap<>();
      try {
         File f = zombie.ZomboidFileSystem.instance.getMediaFile("ui/pzopt/mirrors/mirror-masks.txt");
         int max = 0;
         try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
               line = line.trim();
               if (line.isEmpty() || line.startsWith("#")) {
                  continue;
               }
               String[] parts = line.split("\\s+");
               int cell = Integer.parseInt(parts[1]);
               m.put(parts[0], cell);
               max = Math.max(max, cell);
            }
         }
         maskRows = max / 8 + 1;
         Log.info("mirrors: " + m.size() + " mirror tiles with glass masks");
      } catch (Throwable t) {
         Log.warn("mirrors: no mirror masks (" + t + "): wall mirrors stay stock");
      }
      maskCells = m;
      return m;
   }

   // ------------------------------------------------------------------------------------------------ capture (game thread)

   /** A reflector seen this frame: its pane's plane and its sprite quad in camera-free iso units. */
   static final class Refl {
      Object key;
      int axis;
      float c, z, area, alpha;
      float u0, v0, u1, v1, s0, t0, s1, t1, m0x, m0y, m1x, m1y;
      boolean mirror;
      Texture tex;
   }

   private static ArrayList<Refl> cur = new ArrayList<>(), prev = new ArrayList<>();
   private static final ArrayList<Refl> POOL = new ArrayList<>();
   private static IdentityHashMap<Object, Refl> curKey = new IdentityHashMap<>(), prevKey = new IdentityHashMap<>();
   private static IsoObject capturing;
   public static boolean capturingNow; // (TextureDraw.Create's one test)
   private static int capAxis;
   private static float capC, capZ;
   private static boolean capMirror;
   private static float[] capInfo;
   private static Refl capRefl;
   private static float capAlpha = -1F; // an attached mirror's own alpha (its capture draw is transparent), else -1
   private static int lateFrom;

   /**
    * The mirror among a wall's attached sprites, else null. The map's wall mirrors (walls_decoration_01_*, 18 of the 24
    * masked tiles) are WallOverlay tiles: CellLoader adds them to their wall's attachedAnimSprite list instead of making
    * an object, so they bake with the wall and the object test never saw them (player save, 2026-10-04).
    */
   public static zombie.iso.sprite.IsoSpriteInstance attachedMirror(IsoObject o) {
      if (!Config.MIRRORS || failed || o == null) {
         return null;
      }
      ArrayList<zombie.iso.sprite.IsoSpriteInstance> a = o.getAttachedAnimSprite();
      if (a == null) {
         return null;
      }
      for (int i = 0, n = a.size(); i < n; i++) {
         zombie.iso.sprite.IsoSpriteInstance s = a.get(i);
         if (s != null && mirrorInfo(s.getParentSprite()) != null) {
            return s;
         }
      }
      return null;
   }

   /**
    * Game thread, FBORenderCell's animated-attachments pass: a wall's mirror overlay about to be drawn transparent, for its
    * quad (the baked copy is what shows; the reflection is composited over it like over a mirror object).
    */
   public static boolean beginCaptureAttached(IsoObject wall, zombie.iso.sprite.IsoSpriteInstance s) {
      if (!frameOn || wall == null || wall.square == null || s == null || curKey.containsKey(wall)) {
         return false;
      }
      float[] info = mirrorInfo(s.getParentSprite());
      if (info == null) {
         return false;
      }
      IsoGridSquare sq = wall.square;
      capAxis = (int)info[0];
      capC = (capAxis == 0 ? sq.y : sq.x) + info[1];
      capMirror = true;
      capInfo = info;
      capZ = sq.z;
      capAlpha = s.alpha;
      capturing = wall;
      capturingNow = true;
      capRefl = null;
      return true;
   }

   /** Game thread, FBORenderCell.renderTranslucent: a window / mirror tile about to draw; its quads go to {@link #captured}. */
   public static boolean beginCapture(IsoObject o) {
      if (!frameOn || o == null || o.square == null || curKey.containsKey(o)) {
         return false;
      }
      IsoGridSquare sq = o.square;
      if (o instanceof IsoWindow w) {
         if (!Config.MIRRORS_WINDOWS) {
            return false;
         }
         IsoCurtain curtain = w.HasCurtains();
         if (curtain != null && !curtain.IsOpen() && curtain.square == sq) {
            return false; // a closed curtain on the camera side of the pane (drawn over it in the same pass)
         }
         capAxis = w.getNorth() ? 0 : 1;
         capC = capAxis == 0 ? sq.y : sq.x;
         capMirror = false;
         capInfo = null;
      } else {
         float[] info = mirrorInfo(o.getSprite());
         if (info == null) {
            return false;
         }
         capAxis = (int)info[0];
         capC = (capAxis == 0 ? sq.y : sq.x) + info[1];
         capMirror = true;
         capInfo = info;
      }
      capZ = sq.z;
      capturing = o;
      capturingNow = true;
      capRefl = null;
      return true;
   }

   /** Game thread, TextureDraw.Create while a reflector draws: the largest quad with a texture is its sprite. */
   public static void captured(TextureDraw texd) {
      if (capturing == null || texd.tex == null) {
         return;
      }
      float xa = Math.min(Math.min(texd.x0, texd.x1), Math.min(texd.x2, texd.x3));
      float xb = Math.max(Math.max(texd.x0, texd.x1), Math.max(texd.x2, texd.x3));
      float ya = Math.min(Math.min(texd.y0, texd.y1), Math.min(texd.y2, texd.y3));
      float yb = Math.max(Math.max(texd.y0, texd.y1), Math.max(texd.y2, texd.y3));
      float area = (xb - xa) * (yb - ya);
      if (area <= 1F) {
         return;
      }
      if (capRefl != null && capRefl.tex != null && texd.tex.getID() == capRefl.tex.getID() && capRefl.tex != texd.tex) {
         // (another sub-texture of the same page: a different sprite, e.g. an overlay; the larger wins below)
      } else if (capRefl != null && capRefl.tex == texd.tex) {
         unionInto(capRefl, texd, xa, xb, ya, yb);
         return;
      }
      if (capRefl != null && area <= capRefl.area) {
         return;
      }
      Refl r = capRefl != null ? capRefl : POOL.isEmpty() ? new Refl() : POOL.remove(POOL.size() - 1);
      capRefl = r;
      r.key = capturing;
      r.area = area;
      r.axis = capAxis;
      r.c = capC;
      r.z = capZ;
      r.mirror = capMirror;
      r.tex = texd.tex;
      r.alpha = capAlpha >= 0F ? capAlpha : ((texd.col0 >>> 24) & 0xFF) / 255F;
      double a32 = 32.0 * Core.tileScale, a16 = 16.0 * Core.tileScale;
      float offX = IsoCamera.frameState.offX, offY = IsoCamera.frameState.offY;
      r.u0 = (float)((xa + offX) / a32);
      r.u1 = (float)((xb + offX) / a32);
      r.v0 = (float)((ya + offY) / a16);
      r.v1 = (float)((yb + offY) / a16);
      boolean flip = texd.flipped;
      r.s0 = flip ? texd.u1 : texd.u0;
      r.s1 = flip ? texd.u0 : texd.u1;
      r.t0 = texd.v0;
      r.t1 = texd.v2;
      if (capInfo != null) {
         Texture t = texd.tex;
         float wo = Math.max(1F, t.getWidthOrig()), ho = Math.max(1F, t.getHeightOrig());
         float fx0 = t.getOffsetX() / wo, fx1 = (t.getOffsetX() + t.getWidth()) / wo;
         float fy0 = t.getOffsetY() / ho, fy1 = (t.getOffsetY() + t.getHeight()) / ho;
         int cell = (int)capInfo[2];
         float col = cell % 8, row = cell / 8;
         float mx0 = (col + fx0) / 8F, mx1 = (col + fx1) / 8F;
         r.m0x = flip ? mx1 : mx0;
         r.m1x = flip ? mx0 : mx1;
         r.m0y = (row + fy0) / maskRows;
         r.m1y = (row + fy1) / maskRows;
      } else {
         r.m0x = r.m0y = 0F;
         r.m1x = r.m1y = -1F; // a window: its glass is the sprite's translucent texels
      }
   }

   /** A second quad of the same sprite (a wall drawn as left / right halves): the reflector covers both. */
   private static void unionInto(Refl r, TextureDraw texd, float xa, float xb, float ya, float yb) {
      double a32 = 32.0 * Core.tileScale, a16 = 16.0 * Core.tileScale;
      float offX = IsoCamera.frameState.offX, offY = IsoCamera.frameState.offY;
      float u0 = (float)((xa + offX) / a32), u1 = (float)((xb + offX) / a32);
      float v0 = (float)((ya + offY) / a16), v1 = (float)((yb + offY) / a16);
      float su = (r.s1 - r.s0) / Math.max(1e-6F, r.u1 - r.u0), sv = (r.t1 - r.t0) / Math.max(1e-6F, r.v1 - r.v0);
      float mu = (r.m1x - r.m0x) / Math.max(1e-6F, r.u1 - r.u0), mv = (r.m1y - r.m0y) / Math.max(1e-6F, r.v1 - r.v0);
      float nu0 = Math.min(r.u0, u0), nu1 = Math.max(r.u1, u1), nv0 = Math.min(r.v0, v0), nv1 = Math.max(r.v1, v1);
      r.s0 += (nu0 - r.u0) * su;
      r.s1 += (nu1 - r.u1) * su;
      r.t0 += (nv0 - r.v0) * sv;
      r.t1 += (nv1 - r.v1) * sv;
      if (r.m1x >= 0F) {
         r.m0x += (nu0 - r.u0) * mu;
         r.m1x += (nu1 - r.u1) * mu;
         r.m0y += (nv0 - r.v0) * mv;
         r.m1y += (nv1 - r.v1) * mv;
      }
      r.u0 = nu0;
      r.u1 = nu1;
      r.v0 = nv0;
      r.v1 = nv1;
      r.area = (nu1 - nu0) * (nv1 - nv0) * (float)(a32 * a16);
   }

   public static void endCapture() {
      if (capturing != null && capRefl != null) {
         cur.add(capRefl);
         curKey.put(capturing, capRefl);
      }
      capturing = null;
      capturingNow = false;
      capRefl = null;
      capAlpha = -1F;
   }

   // ------------------------------------------------------------------------------------------------ the frame (game thread)

   static final int TEX = 8, MAXR = 256, MAXP = 32, MAX_BATCHES = 16;

   static final class Frame {
      final Ssr.View view = new Ssr.View();
      long serial;
      boolean on;
      // static pass: last frame's reflectors under this frame's camera
      final float[] sData = new float[MAXR * TEX * 4];
      final Texture[] sTex = new Texture[MAXR];
      int nS;
      // composites: this frame's reflectors, one batch per level
      final float[] lData = new float[MAXR * TEX * 4];
      final Tile[] lTile = new Tile[MAXR]; // the pane of each composite row (its visibility count comes back here)
      final Texture[] lTex = new Texture[MAXR];
      int nL, nBatches;
      final LateDrawer[] late = new LateDrawer[MAX_BATCHES];
      // planes the models are mirrored in: axis, c (relative), lateral lo / hi (absolute), zlo, zhi, iso rect u0 v0 u1 v1 (relative)
      final float[] planes = new float[MAXP * 10];
      final boolean[] planeMirror = new boolean[MAXP]; // a mirror's plane (else windows only): people placed by mirrorsView*
      int nP;
      int modelsQueued, skip, nClear;
      float ppu, ppv; // this frame's px per iso unit (a tile marched at another zoom is read scaled by the ratio)
      final int[] clear = new int[MAXR * 4]; // atlas tiles new this frame: cleared to "no reflection" before the march
      long modelSig;
      final StaticDrawer stat = new StaticDrawer();
      final ModelFlush flush = new ModelFlush();
      final MirrorGeometry.Batch geo = new MirrorGeometry.Batch(); // the due mirrors' rooms, drawn before the march

      Frame() {
         for (int i = 0; i < MAX_BATCHES; i++) {
            this.late[i] = new LateDrawer();
         }
         this.stat.f = this;
         this.flush.f = this;
      }
   }

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static Frame frame;

   /**
    * Render thread, at the top of a frame's cell render: a model queued after the previous frame's flush (or in a frame
    * without one) is dropped. Its slot data is released with that frame's state, so the next flush drew freed data:
    * "model pass failed, off: NullPointerException ... modelInstance is null" turned the mirrors off for the session
    * (player save, 2026-10-04).
    */
   private static final TextureDraw.GenericDrawer DROP_STALE = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         recyclePending();
      }
   };

   /** Game thread, FBORenderCell.performRenderTiles before the chunks: this frame's reflectors from last frame's capture. */
   public static void beginFrame(int playerIndex) {
      if (Config.MIRRORS && !failed) {
         SpriteRenderer.instance.drawGeneric(DROP_STALE);
      }
      // last frame's capture becomes the static pass's list
      for (Refl r : prev) {
         r.key = null;
         r.tex = null;
         POOL.add(r);
      }
      prev.clear();
      ArrayList<Refl> t = prev;
      prev = cur;
      cur = t;
      IdentityHashMap<Object, Refl> tk = prevKey;
      prevKey = curKey;
      curKey = tk;
      curKey.clear();
      capturing = null;
      capturingNow = false;
      frameOn = false;
      if (Config.DEV_MIRRORS_ALTERNATE > 0 && !altLogged && active()) {
         altLogged = true;
         Log.info("mirrors: dev alternating every " + Config.DEV_MIRRORS_ALTERNATE + " ms from epoch_ms 0 (on first)" + (CYCLE.length > 0 ? ", cycle " + Config.DEV_MIRRORS_CYCLE : ""));
      }
      if (!active() || !altOn()) {
         frame = null;
         return;
      }
      frames++;
      skipNow = skip();
      Frame f = FRAMES[(int)(frames & 3)];
      frame = f;
      f.serial = frames;
      f.view.capture(playerIndex);
      f.nS = 0;
      f.nL = 0;
      f.nBatches = 0;
      f.nP = 0;
      f.modelsQueued = 0;
      f.modelSig = Float.floatToIntBits(f.view.offX) * 31L + Float.floatToIntBits(f.view.offY) * 17L + Float.floatToIntBits(f.view.zoom);
      f.skip = skipNow;
      f.nL = 0;
      visGame(f);
      lateFrom = 0;
      frameOn = true;
      boolean staticPass = "pass".equals(Config.MIRRORS_STATIC);
      // the static reflection lives in each pane's own tile of the atlas: the ortho camera's reflected rays do not move with
      // a pan, so a tile holds while the scene does; a pane is marched when its tile is new, when more of it came on
      // screen, and every mirrorsStaticReuse frames (staggered: at most mirrorsRefreshBudget a frame, every 4th frame)
      float vw = lastVw > 0F ? lastVw : f.view.screenW, vh = lastVh > 0F ? lastVh : f.view.screenH;
      VPG[2] = vw;
      VPG[3] = vh;
      f.view.mapping(VPG, MAPG);
      float ppu = 1F / MAPG[0], ppv = 1F / Math.abs(MAPG[2]);
      f.ppu = ppu;
      f.ppv = ppv;
      // a zoom in motion keeps every pane's tile (read scaled by the composite); the zoom it settles at is marched once
      boolean zoomMoving = ppu != lastPpu || ppv != lastPpv;
      lastPpu = ppu;
      lastPpv = ppv;
      float ou = f.view.ox - f.view.oy, ov = f.view.ox + f.view.oy;
      int budget = Config.MIRRORS_REFRESH_BUDGET;
      int every = Math.max(1, Config.MIRRORS_STATIC_EVERY);
      // the pass's fixed cost (the frame's colour / depth made readable) is paid at most every mirrorsStaticEvery frames:
      // a new pane waits that long for its reflection
      boolean passFrame = frames % every == 0 || (skipNow & 256) != 0;
      f.nClear = 0;
      f.geo.reset();
      boolean geometry = Config.MIRRORS_GEOMETRY && (skipNow & 131072) == 0;
      for (Refl r : prev) {
         Tile known = TILES.get(r.key);
         // visibility feedback: a pane the composite drew no pixel of (under a roof, behind a building) is neither marched
         // nor a reason to mirror anyone (dev bit 1024: off)
         if (known != null && known.visPx == 0 && known.visSerial >= 0L && frames - known.visSerial < 30 && (skipNow & 1024) == 0) {
            hiddenSkips++;
            continue;
         }
         addPlane(f, r);
         if (!staticPass || f.nS >= MAXR) {
            continue;
         }
         if (!passFrame) {
            // marches, and so new tiles, only on a pass frame: a tile allocated between them replaced the pane's marched
            // one and the composite found nothing in it, so the reflection showed one frame in four while zooming
            continue;
         }
         Tile tl = tileFor(r, ppu, ppv, zoomMoving);
         if (tl == null || tl.ppu != ppu || tl.ppv != ppv) {
            continue; // (zooming: the kept tile, read scaled, until the zoom settles)
         }
         float pa = ((r.u0 - ou) - MAPG[1]) / MAPG[0], pb = ((r.u1 - ou) - MAPG[1]) / MAPG[0];
         float qa = ((r.v0 - ov) - MAPG[3]) / MAPG[2], qb = ((r.v1 - ov) - MAPG[3]) / MAPG[2];
         float area = Math.max(1F, Math.abs(pb - pa) * Math.abs(qb - qa));
         float ix = Math.max(0F, Math.min(vw, Math.max(pa, pb)) - Math.max(0F, Math.min(pa, pb)));
         float iy = Math.max(0F, Math.min(vh, Math.max(qa, qb)) - Math.max(0F, Math.min(qa, qb)));
         float vis = ix * iy / area;
         boolean fresh = tl.refreshed < 0L;
         // (dev views 5 and 6 paint the tiles themselves: re-marched every pass frame so a toggled picture is never the dev one)
         boolean due = passFrame && (fresh || vis > tl.vis + 0.05F || Config.MIRRORS_STATIC_REUSE <= 0 || (skipNow & 256) != 0 || Config.DEV_MIRRORS_VIEW == 5 || Config.DEV_MIRRORS_VIEW == 6
               || frames - tl.refreshed >= Config.MIRRORS_STATIC_REUSE && budget-- > 0);
         if (!due) {
            continue;
         }
         if (fresh && f.nClear < MAXR) {
            f.clear[f.nClear * 4] = tl.x;
            f.clear[f.nClear * 4 + 1] = tl.y;
            f.clear[f.nClear * 4 + 2] = tl.w;
            f.clear[f.nClear * 4 + 3] = tl.h;
            f.nClear++;
         }
         tl.refreshed = frames;
         tl.vis = vis;
         int geo = geometry ? MirrorGeometry.collect(f.geo, r, tl) : 0;
         pack(f, r, f.sData, f.nS, false);
         f.sData[f.nS * TEX * 4 + 28] = geo > 0 ? 1F : 0F;
         f.sTex[f.nS++] = r.tex;
      }
      if (f.geo.n > 1) {
         MirrorGeometry.sort(f.geo);
      }
      if (frames % 1800 == 900) {
         Log.info(stats());
      }
      if (Config.DEV_MIRRORS_LOG && frames % 300 == 5) {
         StringBuilder sb = new StringBuilder("mirrors: frame " + frames + ", " + prev.size() + " reflectors, " + f.nP + " planes:");
         for (int i = 0; i < Math.min(prev.size(), 8); i++) {
            Refl r = prev.get(i);
            sb.append(String.format(java.util.Locale.ROOT, " [%s axis %d c %.2f z %.0f u %.2f..%.2f v %.2f..%.2f a %.2f]", r.mirror ? "mirror" : "window", r.axis, r.c, r.z, r.u0, r.u1, r.v0, r.v1, r.alpha));
         }
         Log.info(sb.toString());
      }
   }

   /** One reflector's rows (TEX texels of RGBA32F), relative to the frame's origin square. */
   private static void pack(Frame f, Refl r, float[] d, int i, boolean stat) {
      int o = i * TEX * 4;
      float ou = f.view.ox - f.view.oy, ov = f.view.ox + f.view.oy;
      d[o] = r.u0 - ou;
      d[o + 1] = r.v0 - ov;
      d[o + 2] = r.u1 - ou;
      d[o + 3] = r.v1 - ov;
      d[o + 4] = r.s0;
      d[o + 5] = r.t0;
      d[o + 6] = r.s1;
      d[o + 7] = r.t1;
      d[o + 8] = r.m0x;
      d[o + 9] = r.m0y;
      d[o + 10] = r.m1x;
      d[o + 11] = r.m1y;
      d[o + 12] = r.axis;
      d[o + 13] = r.c - (r.axis == 0 ? f.view.oy : f.view.ox);
      // the lowest floor a ray may reach. A mirror hangs in a room with its floor in front, a ground-floor pane has no level
      // under it: their rays end at their own floor (marched further, a ray that passed behind the bathtub "hit" the
      // cut-away outer wall's brick strip under the bathroom floor: a medicine cabinet showed a dark brown slab, maintainer's
      // save 2026-10-04). An upper-floor window looks down at the street below its level. (dev skip bit 32768: the old rule)
      d[o + 14] = (r.mirror || r.z <= 0F) && (skipNow & 32768) == 0 ? r.z : Math.min(r.z, 0F) - 1F;
      d[o + 15] = r.z;
      d[o + 16] = (r.mirror ? Config.MIRRORS_MIRROR_PCT : Config.MIRRORS_WINDOW_PCT) / 100F;
      d[o + 17] = r.alpha;
      d[o + 18] = Math.max(1, Config.MIRRORS_REACH);
      d[o + 19] = stat ? 1F : 0F;
      Tile t = r.key == null ? null : TILES.get(r.key);
      d[o + 20] = t == null ? 0F : t.x; // the pane's atlas tile
      d[o + 21] = t == null ? 0F : t.y;
      d[o + 22] = t == null ? 0F : t.w;
      d[o + 23] = t == null ? 0F : t.h;
      d[o + 24] = r.mirror ? 1F : 0F;
      d[o + 25] = t == null ? 1F : t.scale; // texels per px: 1, or 0.5 (a window's half-resolution march)
      d[o + 26] = t == null || t.ppu <= 0F || f.ppu <= 0F ? 1F : t.ppu / f.ppu; // tile px per px of this frame (1 unless the tile was marched at another zoom)
      d[o + 27] = t == null || t.ppv <= 0F || f.ppv <= 0F ? 1F : t.ppv / f.ppv;
      d[o + 28] = 0F; // the pane's room geometry is in the geometry atlas (static pass only; set by beginFrame)
      d[o + 29] = 0.25F; // a marched hit this much farther than the geometry is the ray passing behind it (squares)
      // a mirrored person's distance on the scale of the room behind the glass: the room is marched with the camera's drop
      // (the floor d squares out shows d / 3 levels up), people with viewDrop (d S levels up); scaled by 3 S their feet meet
      // the reflected floor where they stand on it instead of sinking into it (the floor covered the shins at S = 1/6)
      d[o + 30] = 3F * viewDrop(r.mirror, skipNow);
      d[o + 31] = 0F;
   }

   // ------------------------------------------------------------------------------------------------ the static atlas (game thread)

   static final int ATLAS = 2048;
   static final class Tile {
      Texture tex;
      int x, y, w, h, gen;
      float ppu, ppv, scale, vis;
      long refreshed = -1L;
      volatile int visPx = -1; // the pane's pixels the composite drew (visibility feedback, 1-3 frames late; -1 unknown)
      volatile long visSerial = -1L;
   }

   private static final IdentityHashMap<Object, Tile> TILES = new IdentityHashMap<>();
   private static int shelfX, shelfY, shelfH, atlasGen;
   static volatile float lastVw, lastVh;
   private static float lastPpu, lastPpv;
   private static final float[] VPG = new float[4], MAPG = new float[6];
   static long tileAllocs, atlasResets, tileMarches, hiddenSkips;

   /**
    * The pane's tile at this frame's px per iso unit (a zoom change or a too small tile gets a new one; a full atlas starts
    * over). With {@code keep} (the zoom is still moving) a marched tile of the same sprite is returned at its own zoom:
    * the composite reads it scaled, and the atlas is not filled with a tile per pane every pass frame of a zoom.
    */
   private static Tile tileFor(Refl r, float ppu, float ppv, boolean keep) {
      Tile t = TILES.get(r.key);
      float scale = !r.mirror && Config.MIRRORS_WINDOW_HALF_RES ? 0.5F : 1F;
      int w = (int)Math.ceil((r.u1 - r.u0) * ppu) + 2, h = (int)Math.ceil((r.v1 - r.v0) * ppv) + 2; // (px of the quad: the colour and the glass mask texel per px)
      if (t != null && t.gen == atlasGen && t.ppu == ppu && t.ppv == ppv && t.scale == scale && t.w >= w && t.h >= h && t.tex == r.tex) {
         return t;
      }
      if (keep && t != null && t.gen == atlasGen && t.scale == scale && t.tex == r.tex && t.refreshed >= 0L) {
         return t;
      }
      if (w > ATLAS || h > ATLAS) {
         return null;
      }
      if (shelfX + w > ATLAS) {
         shelfX = 0;
         shelfY += shelfH;
         shelfH = 0;
      }
      if (shelfY + h > ATLAS) {
         atlasGen++; // full: start over, every pane marched again
         atlasResets++;
         TILES.clear();
         shelfX = shelfY = shelfH = 0;
      }
      t = new Tile();
      t.x = shelfX;
      t.y = shelfY;
      t.w = w;
      t.h = h;
      t.gen = atlasGen;
      t.ppu = ppu;
      t.ppv = ppv;
      t.scale = scale;
      t.tex = r.tex; // (a smashed / opened window draws another sprite: a new tile, marched at once)
      shelfX += w + 1;
      shelfH = Math.max(shelfH, h + 1);
      TILES.put(r.key, t);
      tileAllocs++;
      return t;
   }

   /** A reflector's lateral extent on its plane (absolute squares) and height range (levels): {lo, hi, zlo, zhi}. */
   static void paneExtent(Refl r, float[] out) {
      float c = r.c;
      float lo, hi;
      float zA, zB, zC, zD;
      if (r.axis == 0) {
         lo = r.u0 + c;
         hi = r.u1 + c;
         zA = (r.u0 + 2F * c - r.v0) / 6F;
         zB = (r.u1 + 2F * c - r.v1) / 6F;
         zC = (r.u0 + 2F * c - r.v1) / 6F;
         zD = (r.u1 + 2F * c - r.v0) / 6F;
      } else {
         lo = c - r.u1;
         hi = c - r.u0;
         zA = (2F * c - r.u0 - r.v0) / 6F;
         zB = (2F * c - r.u1 - r.v1) / 6F;
         zC = (2F * c - r.u0 - r.v1) / 6F;
         zD = (2F * c - r.u1 - r.v0) / 6F;
      }
      out[0] = lo;
      out[1] = hi;
      out[2] = Math.min(Math.min(zA, zB), Math.min(zC, zD));
      out[3] = Math.max(Math.max(zA, zB), Math.max(zC, zD));
   }

   /** Lateral extent of a reflector on its plane (absolute squares) and its height range (levels). */
   private static void addPlane(Frame f, Refl r) {
      float c = r.c;
      float lo, hi;
      float zA, zB, zC, zD;
      if (r.axis == 0) {
         lo = r.u0 + c;
         hi = r.u1 + c;
         zA = (r.u0 + 2F * c - r.v0) / 6F;
         zB = (r.u1 + 2F * c - r.v1) / 6F;
         zC = (r.u0 + 2F * c - r.v1) / 6F;
         zD = (r.u1 + 2F * c - r.v0) / 6F;
      } else {
         lo = c - r.u1;
         hi = c - r.u0;
         zA = (2F * c - r.u0 - r.v0) / 6F;
         zB = (2F * c - r.u1 - r.v1) / 6F;
         zC = (2F * c - r.u0 - r.v1) / 6F;
         zD = (2F * c - r.u1 - r.v0) / 6F;
      }
      float zlo = Math.min(Math.min(zA, zB), Math.min(zC, zD)), zhi = Math.max(Math.max(zA, zB), Math.max(zC, zD));
      float cRel = c - (r.axis == 0 ? f.view.oy : f.view.ox);
      float ou = f.view.ox - f.view.oy, ov = f.view.ox + f.view.oy;
      for (int i = 0; i < f.nP; i++) {
         int o = i * 10;
         if ((int)f.planes[o] == r.axis && Math.abs(f.planes[o + 1] - cRel) < 0.05F && zlo < f.planes[o + 5] + 0.5F && zhi > f.planes[o + 4] - 0.5F) {
            f.planes[o + 2] = Math.min(f.planes[o + 2], lo);
            f.planes[o + 3] = Math.max(f.planes[o + 3], hi);
            f.planes[o + 4] = Math.min(f.planes[o + 4], zlo);
            f.planes[o + 5] = Math.max(f.planes[o + 5], zhi);
            f.planes[o + 6] = Math.min(f.planes[o + 6], r.u0 - ou);
            f.planes[o + 7] = Math.min(f.planes[o + 7], r.v0 - ov);
            f.planes[o + 8] = Math.max(f.planes[o + 8], r.u1 - ou);
            f.planes[o + 9] = Math.max(f.planes[o + 9], r.v1 - ov);
            f.planeMirror[i] |= r.mirror;
            return;
         }
      }
      if (f.nP >= MAXP) {
         return;
      }
      f.planeMirror[f.nP] = r.mirror;
      int o = f.nP++ * 10;
      f.planes[o] = r.axis;
      f.planes[o + 1] = cRel;
      f.planes[o + 2] = lo;
      f.planes[o + 3] = hi;
      f.planes[o + 4] = zlo;
      f.planes[o + 5] = zhi;
      f.planes[o + 6] = r.u0 - ou;
      f.planes[o + 7] = r.v0 - ov;
      f.planes[o + 8] = r.u1 - ou;
      f.planes[o + 9] = r.v1 - ov;
   }


   /** Game thread, FBORenderCell after the chunk composite: the static pass over last frame's reflectors. */
   public static void afterComposite() {
      Frame f = frame;
      if (f == null || f.nS == 0 || (skipNow & 1) != 0) {
         return;
      }
      tileMarches += f.nS;
      GpuSections.begin(section("mirrors.static"));
      SpriteRenderer.instance.drawGeneric(f.stat);
      GpuSections.end(section("mirrors.static"));
   }

   /**
    * Where a person d squares in front of a plane shows on it: lateral + viewLateral d, height + viewDrop d. The game camera's
    * true reflection is (1, 1/3): d squares to the side and d / 3 levels up, so a player at the sink saw their head above
    * and beside the medicine cabinet (maintainer, 2026-10-04: "the cabinet mirror should show the character's head when the
    * character is in front of the sink"). Mirrors use mirrorsViewLateralPct / mirrorsViewDropPct of it (default 0, 50: the
    * person straight in front of where they stand, a little higher, as one sees oneself looking slightly down into a mirror;
    * at their own height the head stayed under the game's high-hung cabinets); windows keep the true one.
    */
   static float viewLateral(boolean mirror, int skip) {
      return !mirror || (skip & 1048576) != 0 ? 1F : Config.MIRRORS_VIEW_LATERAL_PCT / 100F;
   }

   static float viewDrop(boolean mirror, int skip) {
      return !mirror || (skip & (1048576 | 2097152)) != 0 ? 1F / 3F : Config.MIRRORS_VIEW_DROP_PCT / 300F;
   }

   /**
    * Game thread, TextureDraw.drawModel: the planes (bit mask over the frame's planes) a character / vehicle shows in. A model
    * at distance d in front of a plane is seen at the pane's points (lateral + L d, height + S d, viewLateral / viewDrop);
    * those must fall on a pane.
    */
   public static void planesFor(ModelManager.ModelSlot slot, TextureDraw texd) {
      texd.pzoptMirrorPlanes = 0;
      Frame f = frame;
      if (f == null || f.nP == 0 || slot == null || !Config.MIRRORS_MODELS || (skipNow & 2) != 0 || f.modelsQueued >= Config.MIRRORS_MAX_MODELS) {
         return;
      }
      Object o = slot.character != null ? slot.character : slot.model != null ? slot.model.object : null;
      float x, y, z, r, h;
      if (o instanceof IsoGameCharacter c) {
         x = c.getX();
         y = c.getY();
         z = c.getZ();
         r = 0.6F;
         h = 0.85F;
      } else if (o instanceof BaseVehicle v) {
         x = v.getX();
         y = v.getY();
         z = v.getZ();
         r = 3.0F;
         h = 0.8F;
      } else {
         return;
      }
      float cdx = x - IsoCamera.frameState.camCharacterX, cdy = y - IsoCamera.frameState.camCharacterY;
      if (cdx * cdx + cdy * cdy > (float)Config.MIRRORS_MODEL_RANGE * Config.MIRRORS_MODEL_RANGE) {
         return;
      }
      int mask = 0;
      for (int i = 0; i < f.nP; i++) {
         int p = i * 10;
         float cAbs = f.planes[p + 1] + (f.planes[p] == 0F ? f.view.oy : f.view.ox);
         float d = f.planes[p] == 0F ? y - cAbs : x - cAbs;
         float vl = viewLateral(f.planeMirror[i], skipNow), vs = viewDrop(f.planeMirror[i], skipNow);
         float dMax = vs > 0.001F ? (f.planes[p + 5] - z) / vs : Config.MIRRORS_REACH;
         if (d < 0.05F - r * 0.5F || d > dMax + r + 0.5F) {
            continue;
         }
         float lat = f.planes[p] == 0F ? x + vl * d : y + vl * d;
         if (lat < f.planes[p + 2] - r || lat > f.planes[p + 3] + r) {
            continue;
         }
         float zi = z + Math.max(d, 0F) * vs;
         if (zi > f.planes[p + 5] || zi + h < f.planes[p + 4]) {
            continue;
         }
         mask |= 1 << i;
      }
      if (mask != 0) {
         texd.pzoptMirrorPlanes = mask;
         f.modelsQueued++;
         float ang = o instanceof IsoGameCharacter c2 ? c2.getAnimAngleRadians() : ((BaseVehicle)o).getAngleY();
         f.modelSig = f.modelSig * 1000003L + System.identityHashCode(o) + Float.floatToIntBits(x) * 31L + Float.floatToIntBits(y) * 17L + Float.floatToIntBits(z) * 7L
               + Float.floatToIntBits(ang) * 3L + mask + (skipNow & (1048576 | 2097152));
      }
   }

   /** Game thread, FBORenderCell after the moving objects: the mirrored models into the layer. */
   private static long modelSigLast, modelFrameLast, modelNsLast;
   static long modelReuses;

   public static void afterMoving() {
      Frame f = frame;
      if (f == null || f.modelsQueued == 0) {
         return;
      }
      // the same models in the same places under the same camera: last frame's layer still holds (renewed every mirrorsModelReuse frames)
      long now = System.nanoTime();
      boolean still = Config.MIRRORS_MODEL_REUSE > 1 && f.modelSig == modelSigLast && frames - modelFrameLast < Config.MIRRORS_MODEL_REUSE;
      // a rate cap even while they move: at 240 fps a mirrored model redrawn 120 times a second is 4 ms behind at most
      boolean capped = Config.MIRRORS_MODEL_HZ > 0 && now - modelNsLast < 1_000_000_000L / Config.MIRRORS_MODEL_HZ && layerSerial >= 0 && (skipNow & 16384) == 0;
      f.flush.reuse = still || capped;
      if (f.flush.reuse) {
         modelReuses++;
         SpriteRenderer.instance.drawGeneric(f.flush); // (recycles the frame's queued draws, keeps the layer)
         return;
      }
      modelSigLast = f.modelSig;
      modelFrameLast = frames;
      modelNsLast = now;
      GpuSections.begin(section("mirrors.models"));
      SpriteRenderer.instance.drawGeneric(f.flush);
      GpuSections.end(section("mirrors.models"));
   }

   /** Game thread, FBORenderCell after a level's translucent objects: the composite over the reflectors that drew in it. */
   public static void afterTranslucent() {
      if (!Config.MIRRORS_COMPOSITE_ONCE) {
         composite();
      }
   }

   /** Game thread, FBORenderCell after every level's translucent objects: with mirrorsCompositeOnce, one composite for all. */
   public static void afterLevels() {
      if (Config.MIRRORS_COMPOSITE_ONCE) {
         composite();
      }
   }

   private static void composite() {
      Frame f = frame;
      if (f == null || lateFrom >= cur.size()) {
         return;
      }
      int from = lateFrom;
      lateFrom = cur.size();
      if ((skipNow & 4) != 0 || f.nBatches >= MAX_BATCHES) {
         return;
      }
      // sorted by texture so the composite binds each sprite page once
      ArrayList<Refl> batch = SORT;
      batch.clear();
      for (int i = from; i < cur.size(); i++) {
         batch.add(cur.get(i));
      }
      batch.sort((a, b) -> Integer.compare(System.identityHashCode(a.tex), System.identityHashCode(b.tex)));
      int start = f.nL;
      for (Refl r : batch) {
         if (f.nL >= MAXR) {
            break;
         }
         Tile t = TILES.get(r.key);
         f.lTile[f.nL] = t;
         pack(f, r, f.lData, f.nL, t != null && t.refreshed >= 0L && t.gen == atlasGen && (skipNow & 1) == 0);
         f.lTex[f.nL++] = r.tex;
      }
      if (f.nL == start) {
         return;
      }
      LateDrawer d = f.late[f.nBatches++];
      d.f = f;
      d.start = start;
      d.count = f.nL - start;
      d.last = true;
      if (f.nBatches > 1) {
         f.late[f.nBatches - 2].last = false; // (the render thread runs it later: this frame's queue is still being built)
      }
      GpuSections.begin(section("mirrors.late"));
      SpriteRenderer.instance.drawGeneric(d);
      GpuSections.end(section("mirrors.late"));
   }

   private static final ArrayList<Refl> SORT = new ArrayList<>();

   // ------------------------------------------------------------------------------------------------ render thread: resources

   private static int staticProg, lateProg, vao, maskTex, staticTex, glassTex;
   private static final int[] DATA = new int[6]; // [frame slot (3) x pass (static, composites)]
   private static int layerFbo, layerColor, layerDepth, layerW, layerH;
   private static int[] uS, uL;
   private static final FloatBuffer UP = BufferUtils.createFloatBuffer(MAXR * TEX * 4);
   private static int worldFbo = -1, worldColor, worldDepth;
   private static long vpAge = Long.MIN_VALUE;
   private static final HashMap<Integer, int[]> FBO_ATTACH = new HashMap<>();
   static long requeries;
   private static final int[] VPI = new int[4];
   private static final float[] VPF = new float[4];
   private static final float[] MAP = new float[6];
   static final int IMAGE_UNIT = 6;
   static final boolean VIS_ON = Config.MIRRORS_VISIBILITY;
   private static final ByteBuffer MISS = BufferUtils.createByteBuffer(4).put(new byte[] {0, 0, 0, (byte)255}).flip(); // "no reflection here"
   // visibility feedback (2026-10-03): the composite adds each pane's visible pixels into a persistently mapped, coherent
   // storage buffer (4 frame slots of MAXR counters); the game thread zeroes this frame's slot and reads the slot of three
   // frames ago straight from the mapping: no GL call, no wait (a slot the GPU has not finished reads as partial counts,
   // harmless for a "has it any pixel" heuristic). The first design read an R32UI texture back through a PBO + fence: 137 us
   // of render thread per composite (every sync / map call waits for NVIDIA's threaded driver).
   static final int VIS_SLOTS = 4, VIS_EVERY = 8;
   private static int visBuf, visDev; // host-visible readback ring (persistent, coherent); the counters the shader writes (device local)
   static volatile java.nio.IntBuffer visMap;
   private static long visReads;

   private static void visCreate() {
      org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
      if (!(caps.OpenGL44 || caps.GL_ARB_buffer_storage) || !(caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object)) {
         return;
      }
      int flags = org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT | GL30.GL_MAP_READ_BIT | GL30.GL_MAP_WRITE_BIT;
      visBuf = GL15.glGenBuffers();
      GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, visBuf);
      org.lwjgl.opengl.GL44.glBufferStorage(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, (long)VIS_SLOTS * MAXR * 4L, flags);
      ByteBuffer m = GL30.glMapBufferRange(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0L, (long)VIS_SLOTS * MAXR * 4L, flags);
      GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0);
      // the shader writes device-local counters (writes into the coherent host mapping went over PCIe from every sampled
      // fragment: the composite grew 12 -> 300 us walking into a street of windows); once a frame they are copied over
      visDev = GL15.glGenBuffers();
      GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, visDev);
      org.lwjgl.opengl.GL44.glBufferStorage(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, (long)MAXR * 4L, 0);
      GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0);
      if (m != null) {
         java.nio.IntBuffer ib = m.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
         for (int i = 0; i < VIS_SLOTS * MAXR; i++) {
            ib.put(i, 0);
         }
         visMap = ib;
         int block = org.lwjgl.opengl.GL43.glGetProgramResourceIndex(lateProg, org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BLOCK, "VisBuf");
         if (block >= 0) {
            org.lwjgl.opengl.GL43.glShaderStorageBlockBinding(lateProg, block, 3);
         }
         Log.info("mirrors: visibility feedback buffer (persistent, coherent) " + visBuf);
      }
   }

   /** Game thread, beginFrame: this frame's counters zeroed, the counts of three frames ago into their panes. */
   private static void visGame(Frame f) {
      java.nio.IntBuffer m = visMap;
      if (m == null) {
         return;
      }
      long s3 = f.serial - 3L;
      if (s3 < 0L || s3 % VIS_EVERY != 0) {
         return; // (only every VIS_EVERY-th frame wrote counts; read three frames after it)
      }
      Frame g = FRAMES[(int)(s3 & 3)];
      if (s3 < 0L || g.serial != s3) {
         return;
      }
      int gs = (int)(s3 % VIS_SLOTS);
      for (int i = 0; i < g.nL; i++) {
         Tile t = g.lTile[i];
         if (t != null) {
            t.visPx = m.get(gs * MAXR + i);
            t.visSerial = s3;
         }
      }
      visReads++;
   }

   private static final ByteBuffer ZERO4 = BufferUtils.createByteBuffer(4);
   private static final ByteBuffer NO_GLASS = BufferUtils.createByteBuffer(4).put(new byte[] {0, 0, 0, 0}).flip();
   /** The composite reads the frame in place (a texture barrier each): only for mirrorsStatic=late. */
   static final boolean LIVE_READS = "late".equals(Config.MIRRORS_STATIC);
   private static boolean FULL, STATIC_FBO, liveReads; // (decided once from the context's capabilities)
   private static int atlasFbo;

   private static final String[] UNIFORMS = {"Data", "WorldColor", "WorldDepth", "Sprite", "Masks", "StaticTex", "LayerColor", "LayerDepth", "mapA", "mapC", "vp", "march", "layerMap", "dev", "Static", "first", "depthTest", "Glass", "GlassTex", "Vis", "visRow", "visOn", "GeomTex"};

   private static boolean ensurePrograms() {
      if (staticProg != 0) {
         return true;
      }
      org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
      boolean images = caps.OpenGL42 || caps.GL_ARB_shader_image_load_store;
      boolean barrier = caps.OpenGL45 || caps.GL_ARB_texture_barrier || caps.GL_NV_texture_barrier;
      // full: GL 4.3 + image stores + texture barriers + buffer storage (the static pass stores into the atlas images, the
      // composite marks visible panes); else the GL 4.1 path (macOS core): the static pass renders into the atlas
      // framebuffer, no visibility feedback, no live-read variant
      FULL = images && barrier && caps.OpenGL43 && (caps.OpenGL44 || caps.GL_ARB_buffer_storage) && !CoreGl.active;
      STATIC_FBO = !FULL || Config.MIRRORS_STATIC_FBO;
      liveReads = LIVE_READS && barrier;
      if (!caps.OpenGL33) {
         failed = true;
         Log.warn("mirrors: needs OpenGL 3.3 / 4.1 core; mirrors and window reflections off");
         return false;
      }
      Log.info("mirrors: " + (FULL ? "GL 4.3 path" : "GL 4.1 path") + ", static pass " + (STATIC_FBO ? "into the atlas framebuffer" : "image stores")
            + (LIVE_READS && !liveReads ? " (no texture barrier: mirrorsStatic=late runs as pass)" : ""));
      staticProg = AmbientOcclusion.link(STATIC_FBO ? vertTile(FULL) : vert(FULL), frag(STATIC_FBO ? K_STATIC_FBO : K_STATIC_IMAGE, FULL));
      lateProg = AmbientOcclusion.link(vert(FULL), frag(K_LATE, FULL));
      if (staticProg == 0 || lateProg == 0) {
         failed = true;
         Log.warn("mirrors: shaders did not link; off");
         return false;
      }
      uS = new int[UNIFORMS.length];
      uL = new int[UNIFORMS.length];
      for (int i = 0; i < UNIFORMS.length; i++) {
         uS[i] = GL20.glGetUniformLocation(staticProg, UNIFORMS[i]);
         uL[i] = GL20.glGetUniformLocation(lateProg, UNIFORMS[i]);
      }
      // the samplers' units never change: set once here, not per pass
      for (int[] u : new int[][] {uS, uL}) {
         GL20.glUseProgram(u == uS ? staticProg : lateProg);
         GL20.glUniform1i(u[0], 2);
         GL20.glUniform1i(u[1], 0);
         GL20.glUniform1i(u[2], 1);
         GL20.glUniform1i(u[3], 4);
         GL20.glUniform1i(u[4], 3);
         GL20.glUniform1i(u[5], 5);
         GL20.glUniform1i(u[6], 7);
         GL20.glUniform1i(u[7], 8);
         GL20.glUniform1i(u[14], IMAGE_UNIT);
         GL20.glUniform1i(u[17], IMAGE_UNIT + 1);
         GL20.glUniform1i(u[18], 6);
         GL20.glUniform1i(u[22], 15);
      }
      GL20.glUseProgram(0);
      vao = GL30.glGenVertexArrays();
      // one data texture per frame in flight and pass (static / composites): an upload never rewrites rows a queued draw
      // still reads (the driver would wait for it or copy the texture); the composites of a frame use disjoint rows
      for (int i = 0; i < DATA.length; i++) {
         DATA[i] = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, DATA[i]);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, TEX, MAXR, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      }
      maskTex = loadMasks();
      if (VIS_ON && FULL) {
         visCreate();
      }
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      Log.info("mirrors: programs linked (static " + staticProg + ", composite " + lateProg + "), mask texture " + maskTex);
      return true;
   }

   private static int loadMasks() {
      int tex = GL11.glGenTextures();
      int w = 8, h = 8;
      ByteBuffer px = BufferUtils.createByteBuffer(w * h);
      try {
         File f = zombie.ZomboidFileSystem.instance.getMediaFile("ui/pzopt/mirrors/mirror-masks.png");
         java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
         w = img.getWidth();
         h = img.getHeight();
         px = BufferUtils.createByteBuffer(w * h);
         java.awt.image.Raster r = img.getRaster();
         int[] row = new int[w];
         for (int y = 0; y < h; y++) {
            r.getSamples(0, y, w, 1, 0, row);
            for (int x = 0; x < w; x++) {
               px.put((byte)row[x]);
            }
         }
         px.flip();
      } catch (Throwable t) {
         Log.warn("mirrors: mask texture unreadable: " + t);
      }
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, w, h, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, px);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      return tex;
   }

   /** The world framebuffer (the game's tracked binding), its attachments and the viewport: re-read when it changes or every 120 frames. */
   private static boolean world() {
      int fbo = zombie.core.textures.TextureFBO.lastID;
      // a framebuffer / viewport query is a round trip to NVIDIA's threaded driver (hundreds of us): only when the framebuffer,
      // the offscreen size or the render scale changed (a re-query every 120 frames cost ~4.7 us a composite on average)
      long key = ((long)Core.getInstance().getOffscreenWidth(0) << 32 | Core.getInstance().getOffscreenHeight(0)) * 31L
            + Float.floatToIntBits(RenderScale.scaleX()) * 7L + Float.floatToIntBits(RenderScale.scaleY());
      if (key != vpAge) {
         vpAge = key;
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, VPI);
         GL11.glGetFloatv(GL11.GL_VIEWPORT, VPF);
         lastVw = VPF[2];
         lastVh = VPF[3];
         FBO_ATTACH.clear(); // (a resize re-creates the framebuffers' textures)
         requeries++;
      }
      if (fbo != worldFbo) {
         worldFbo = fbo;
         int[] a = FBO_ATTACH.get(fbo); // attachments per framebuffer: other passes bind theirs between mine, the id alternates
         if (a == null) {
            int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            int c = attachment(GL30.GL_COLOR_ATTACHMENT0);
            int d = attachment(GL30.GL_DEPTH_STENCIL_ATTACHMENT);
            if (d == 0) {
               d = attachment(GL30.GL_DEPTH_ATTACHMENT);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            a = new int[] {c, d};
            FBO_ATTACH.put(fbo, a);
            requeries++;
            if (Config.DEV_MIRRORS_LOG) {
               Log.info("mirrors: world framebuffer " + fbo + " colour " + c + " depth " + d + " viewport " + VPI[0] + "," + VPI[1] + " " + VPI[2] + "x" + VPI[3]);
            }
         }
         worldColor = a[0];
         worldDepth = a[1];
      }
      return worldColor != 0 && worldDepth != 0 && VPI[2] > 0 && VPI[3] > 0;
   }

   /** glTextureBarrier through whichever entry point the context has (GL 4.5, ARB or NV). */
   private static void barrier() {
      org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
      if (caps.OpenGL45) {
         org.lwjgl.opengl.GL45.glTextureBarrier();
      } else if (caps.GL_ARB_texture_barrier) {
         org.lwjgl.opengl.ARBTextureBarrier.glTextureBarrier();
      } else {
         org.lwjgl.opengl.NVTextureBarrier.glTextureBarrierNV();
      }
   }

   private static int attachment(int attachment) {
      if (GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11.GL_TEXTURE) {
         return 0;
      }
      return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
   }

   private static void ensureStatic() {
      if (staticTex != 0) {
         return;
      }
      int w = ATLAS, h = ATLAS;
      glassTex = GL11.glGenTextures(); // each pane's glass mask, written by the static pass (the composite reads no sprite)
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glassTex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, w, h, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      staticTex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, staticTex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      if (STATIC_FBO) {
         atlasFbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, atlasFbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, staticTex, 0);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, glassTex, 0);
         GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
         int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zombie.core.textures.TextureFBO.lastID);
         if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            failed = true;
            Log.warn("mirrors: atlas framebuffer incomplete (" + status + "); off");
         }
      }
      Log.info("mirrors: static reflection atlas " + w + "x" + h);
   }

   private static boolean ensureLayer() {
      int w = VPI[0] + VPI[2], h = VPI[1] + VPI[3];
      if (layerFbo != 0 && layerW == w && layerH == h) {
         return true;
      }
      if (layerFbo != 0) {
         GL30.glDeleteFramebuffers(layerFbo);
         GL11.glDeleteTextures(layerColor);
         GL11.glDeleteTextures(layerDepth);
      }
      layerColor = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerColor);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      layerDepth = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerDepth);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, w, h, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      layerFbo = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, layerFbo);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, layerColor, 0);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, layerDepth, 0);
      int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zombie.core.textures.TextureFBO.lastID);
      if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
         Log.warn("mirrors: model layer incomplete (" + status + "); models off");
         GL30.glDeleteFramebuffers(layerFbo);
         layerFbo = 0;
         return false;
      }
      layerW = w;
      layerH = h;
      Log.info("mirrors: model layer " + w + "x" + h);
      return true;
   }

   /** Window px -> iso (relative to the frame's origin square), iso depth from window depth. */
   private static void mapping(Frame f) {
      f.view.mapping(VPF, MAP);
   }

   private static void commonUniforms(int[] u, Frame f) {
      GL20.glUniform4f(u[8], MAP[0], MAP[1], MAP[2], MAP[3]);
      GL20.glUniform4f(u[9], MAP[4], MAP[5], 0F, Math.max(1, Config.MIRRORS_REACH));
      GL20.glUniform4f(u[10], VPF[0], VPF[1], VPF[2], VPF[3]);
      GL20.glUniform4f(u[11], Math.max(4, Math.min(64, Config.MIRRORS_STEPS)), Config.MIRRORS_THICKNESS_PCT / 100F, Config.MIRRORS_STAND_IN_PCT / 100F, Math.max(2, Config.MIRRORS_STEP_PX));
      int view = Config.DEV_MIRRORS_VIEW_TOGGLE_MS > 0 && (System.currentTimeMillis() / Config.DEV_MIRRORS_VIEW_TOGGLE_MS & 1L) == 1L ? 0 : Config.DEV_MIRRORS_VIEW;
      GL20.glUniform4f(u[13], view, f.skip, "off".equals(Config.MIRRORS_STATIC) ? 0F : 1F, 0F);
   }

   /** Rows [start, start + n) of the frame's data texture for the pass (0 static, 1 composites). */
   private static void upload(Frame f, int pass, float[] d, int start, int n) {
      UP.clear();
      UP.put(d, start * TEX * 4, n * TEX * 4).flip();
      GL13.glActiveTexture(GL13.GL_TEXTURE2);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, DATA[(int)(f.serial % 3) * 2 + pass]);
      GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, start, TEX, n, GL11.GL_RGBA, GL11.GL_FLOAT, UP);
   }

   /** Instanced quads per sprite page: instance i reads data row (first + i); sprite on unit 4. */
   private static void drawGroups(int[] u, Texture[] texs, int start, int n) {
      int uFirst = u[15];
      int i = 0;
      while (i < n) {
         Texture t = texs[start + i];
         int j = i + 1;
         while (j < n && texs[start + j] == t) {
            j++;
         }
         int id = t == null ? 0 : t.getID();
         if (id > 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            GL20.glUniform1f(uFirst, start + i);
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, j - i);
         }
         i = j;
      }
   }

   private static void bindWorld() {
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldColor);
      GL13.glActiveTexture(GL13.GL_TEXTURE1);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldDepth);
      GL13.glActiveTexture(GL13.GL_TEXTURE3);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTex);
   }

   private static void restore() {
      GL30.glBindVertexArray(0);
      GL20.glUseProgram(0);
      for (int unit : new int[] {15, 14, 8, 7, 6, 5, 4, 3, 2, 1}) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      GL11.glColorMask(true, true, true, true);
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      GL11.glDepthMask(true);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GLStateRenderThread.restore();
      zombie.core.ShaderHelper.forgetCurrentlyBound();
      Texture.lastTextureID = -1;
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
   }

   // ------------------------------------------------------------------------------------------------ render thread: passes

   static long staticDraws, lateDraws, modelDraws, layerFrames;
   static volatile long staticCpuNs, lateCpuNs, flushCpuNs;
   static final long[] lateSplit = new long[4]; // dev: composite render thread: upload, set-up, draw, restore

   /** The static pass: last frame's reflectors, the reflected rays marched through the static world into the image. */
   static final class StaticDrawer extends TextureDraw.GenericDrawer {
      Frame f;

      @Override
      public void render() {
         if (failed) {
            return;
         }
         long t0 = System.nanoTime();
         try {
            if (!ensurePrograms() || !world()) {
               return;
            }
            ensureStatic();
            mapping(this.f);
            // the due mirrors' rooms into the geometry atlas first (its own framebuffer), read by the march below
            boolean geo = (this.f.skip & 131072) == 0 && MirrorGeometry.draw(this.f.geo, this.f.serial, FULL, (this.f.skip & 524288) != 0);
            if (geo) {
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, worldFbo);
               GL11.glViewport(VPI[0], VPI[1], VPI[2], VPI[3]);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE15);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, geo ? MirrorGeometry.texture() : 0);
            upload(this.f, 0, this.f.sData, 0, this.f.nS);
            if (STATIC_FBO) {
               fboPass(this.f);
               staticDraws++;
               return;
            }
            if ((this.f.skip & 64) == 0) {
               barrier(); // the composite's colour / depth readable (dev bit 64: without, the fixed cost of the pass alone)
            }
            bindWorld();
            if (this.f.nClear > 0 && org.lwjgl.opengl.GL.getCapabilities().OpenGL44) {
               for (int i = 0; i < this.f.nClear; i++) {
                  org.lwjgl.opengl.GL44.glClearTexSubImage(staticTex, 0, this.f.clear[i * 4], this.f.clear[i * 4 + 1], 0, this.f.clear[i * 4 + 2], this.f.clear[i * 4 + 3], 1,
                     GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MISS);
                  org.lwjgl.opengl.GL44.glClearTexSubImage(glassTex, 0, this.f.clear[i * 4], this.f.clear[i * 4 + 1], 0, this.f.clear[i * 4 + 2], this.f.clear[i * 4 + 3], 1,
                     GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, NO_GLASS);
               }
            }
            GL42.glBindImageTexture(IMAGE_UNIT, staticTex, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL42.glBindImageTexture(IMAGE_UNIT + 1, glassTex, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R8);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColorMask(false, false, false, false);
            GL20.glUseProgram(staticProg);
            commonUniforms(uS, this.f);

            GL30.glBindVertexArray(vao);
            if ((this.f.skip & 512) == 0) {
               drawGroups(uS, this.f.sTex, 0, this.f.nS); // (dev bit 512: the pass without its draws)
            }
            if ((this.f.skip & 128) == 0) {
               GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT); // (dev bit 128: without; the composites read the image much later)
            }

            GL42.glBindImageTexture(IMAGE_UNIT, 0, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL42.glBindImageTexture(IMAGE_UNIT + 1, 0, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R8);
            staticDraws++;
         } catch (Throwable t) {
            failed = true;
            Log.warn("mirrors: static pass failed, off: " + t);
         } finally {
            restore();
            staticCpuNs += System.nanoTime() - t0;
         }
      }
   }

   /**
    * The static pass through the atlas framebuffer (GL 4.1, or mirrorsStaticFbo): every texel of a due pane's tile drawn
    * in tile space (each knows its pane coordinates), reading the frame's colour / depth while another framebuffer is
    * bound (no texture barrier, no image stores, no clears: the whole tile is written).
    */
   private static void fboPass(Frame f) {
      bindWorld();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, atlasFbo);
      GL11.glViewport(0, 0, ATLAS, ATLAS);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDepthMask(false);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glColorMask(true, true, true, true);
      GL20.glUseProgram(staticProg);
      commonUniforms(uS, f);
      GL30.glBindVertexArray(vao);
      if ((f.skip & 512) == 0) {
         drawGroups(uS, f.sTex, 0, f.nS);
      }
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, worldFbo);
      GL11.glViewport(VPI[0], VPI[1], VPI[2], VPI[3]);
   }

   /** The composite over one level's reflectors: static reflection + model layer, blended over the glass. */
   static final class LateDrawer extends TextureDraw.GenericDrawer {
      Frame f;
      int start, count;
      boolean last; // the frame's last composite (set when the frame ends)

      @Override
      public void render() {
         if (failed) {
            return;
         }
         long t0 = System.nanoTime();
         try {
            if (!ensurePrograms() || !world()) {
               return;
            }
            ensureStatic();
            mapping(this.f);
            if (Config.DEV_MIRRORS_LOG && this.f.serial % 120 < 3) {
               // dev: each reflector's window px rect (GL origin bottom-left; image rows = viewport height - y), for crops
               StringBuilder sb = new StringBuilder("mirrors: dev rects (viewport " + VPI[2] + "x" + VPI[3] + "):");
               for (int k = 0, shown = 0; k < 2 * this.count && shown < 16; k++) {
                  int i = k % this.count;
                  int o = (this.start + i) * TEX * 4;
                  if ((this.f.lData[o + 24] > 0.5F) != (k < this.count)) {
                     continue; // mirrors first, then windows
                  }
                  shown++;
                  float x0 = (this.f.lData[o] - MAP[1]) / MAP[0], x1 = (this.f.lData[o + 2] - MAP[1]) / MAP[0];
                  float y0 = (this.f.lData[o + 1] - MAP[3]) / MAP[2], y1 = (this.f.lData[o + 3] - MAP[3]) / MAP[2];
                  sb.append(String.format(java.util.Locale.ROOT, " [%s %.0f,%.0f %.0fx%.0f]", this.f.lData[o + 24] > 0.5F ? "mirror" : "window",
                     Math.min(x0, x1), VPI[3] - Math.max(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0)));
               }
               Log.info(sb.toString());
            }
            long tu = System.nanoTime();
            upload(this.f, 1, this.f.lData, this.start, this.count);
            long ts = System.nanoTime();
            lateSplit[0] += ts - tu;
            boolean live = liveReads;
            boolean texOcclusion = (this.f.skip & 4096) != 0, noOcclusion = (this.f.skip & 8192) != 0; // dev: occlusion by a depth texture read / none
            if (live) {
               barrier(); // the frame read in place (static mode "late", or a reflector new this frame)
               bindWorld();
            } else if (texOcclusion) {
               bindWorld();
            } else {
               GL13.glActiveTexture(GL13.GL_TEXTURE3);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTex); // (the world's colour / depth stay unbound: depth is being tested against that very buffer)
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE5);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, staticTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE6);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glassTex);
            boolean layer = layerSerial == this.f.serial && layerFbo != 0;
            GL13.glActiveTexture(GL13.GL_TEXTURE7);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, layer ? layerColor : 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE8);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, layer ? layerDepth : 0);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            if (live || texOcclusion || noOcclusion) {
               GL11.glDisable(GL11.GL_DEPTH_TEST);
            } else {
               GL11.glEnable(GL11.GL_DEPTH_TEST);
               GL11.glDepthFunc(GL11.GL_LEQUAL);
            }
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            GL11.glColorMask(true, true, true, false);
            GL20.glUseProgram(lateProg);
            commonUniforms(uL, this.f);
            GL20.glUniform4f(uL[12], layer ? 1F : 0F, layerK, layerZ0, layerW0);
            GL20.glUniform4f(uL[16], live || texOcclusion || noOcclusion ? 0F : 1F, 0.35F, 0F, 0F);
            GL20.glUniform4f(uL[13], (Config.DEV_MIRRORS_VIEW_TOGGLE_MS > 0 && (System.currentTimeMillis() / Config.DEV_MIRRORS_VIEW_TOGGLE_MS & 1L) == 1L) ? 0 : Config.DEV_MIRRORS_VIEW,
               this.f.skip, "off".equals(Config.MIRRORS_STATIC) ? 0F : 1F, live ? 0F : texOcclusion ? 2F : 1F);
            boolean vis = VIS_ON && !live && visMap != null && (this.f.skip & 2048) == 0 && this.f.serial % VIS_EVERY == 0; // (every 8th frame: the clear, barrier and copy cost ~10 us)
            if (vis) {
               if (this.start == 0) {
                  org.lwjgl.opengl.GL45.glClearNamedBufferData(visDev, GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (ByteBuffer)null); // (GPU-side zero)
               }
               org.lwjgl.opengl.GL30.glBindBufferBase(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 3, visDev);
               GL20.glUniform1i(uL[20], 0);
            }
            GL20.glUniform1i(uL[21], vis ? 1 : 0);
            GL30.glBindVertexArray(vao);
            long td = System.nanoTime();
            lateSplit[1] += td - ts;
            if (live) {
               drawGroups(uL, this.f.lTex, this.start, this.count); // (a reflector new this frame marches here: its sprite is needed)
            } else {
               GL20.glUniform1f(uL[15], this.start);
               GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, this.count); // one draw: the glass masks and reflections come from the atlas
            }
            if (vis && this.last) {
               GL42.glMemoryBarrier(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
               org.lwjgl.opengl.GL45.glCopyNamedBufferSubData(visDev, visBuf, 0L, (long)(this.f.serial % VIS_SLOTS) * MAXR * 4L, (long)MAXR * 4L); // (async, the game thread reads it frames later)
            }
            lateSplit[2] += System.nanoTime() - td;
            lateDraws++;
         } catch (Throwable t) {
            failed = true;
            Log.warn("mirrors: composite failed, off: " + t);
         } finally {
            long tr = System.nanoTime();
            restore();
            long te = System.nanoTime();
            lateSplit[3] += te - tr;
            lateCpuNs += te - t0;
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ the model layer

   /** A mirrored model draw waiting for the frame's flush: its slot (valid until the frame's postRender) and camera. */
   private static final class Pending {
      ModelSlotRenderData slot;
      int planes;
      boolean vehicle;
      float camX, camY, camZ, angle, ox, oy, oz;
   }

   private static final ArrayList<Pending> PENDING = new ArrayList<>();
   private static final ArrayList<Pending> PPOOL = new ArrayList<>();
   private static long layerSerial = -1;
   private static float layerK, layerZ0, layerW0;
   private static final MirrorCamera CAMERA = new MirrorCamera();

   /** Render thread, TextureDraw's DrawModel right after the model's own draw: queued for the frame's mirrored draws. */
   public static void renderModel(TextureDraw texd) {
      if (failed || texd.pzoptMirrorPlanes == 0 || !(texd.drawer instanceof ModelSlotRenderData slot)) {
         return;
      }
      ModelCamera cam = ModelCamera.instance;
      if (cam == null || cam.inVehicle || slot.renderToTexture || !cam.useWorldIso) {
         return;
      }
      Pending p = PPOOL.isEmpty() ? new Pending() : PPOOL.remove(PPOOL.size() - 1);
      p.slot = slot;
      p.planes = texd.pzoptMirrorPlanes;
      p.vehicle = slot.character == null;
      p.camX = cam.x;
      p.camY = cam.y;
      p.camZ = cam.z;
      p.angle = cam.useAngle;
      PENDING.add(p);
   }

   private static void recyclePending() {
      for (Pending p : PENDING) {
         p.slot = null;
         PPOOL.add(p);
      }
      PENDING.clear();
   }

   static final class ModelFlush extends TextureDraw.GenericDrawer {
      Frame f;
      boolean reuse;

      @Override
      public void render() {
         if (failed || PENDING.isEmpty()) {
            recyclePending();
            return;
         }
         if (this.reuse) {
            recyclePending();
            if (layerSerial >= 0) {
               layerSerial = this.f.serial; // last frame's layer, read again
            }
            return;
         }
         long t0 = System.nanoTime();
         int previousFbo = zombie.core.textures.TextureFBO.lastID;
         ModelCamera prevCam = ModelCamera.instance;
         ShadowAtlas.Tracked saved = ShadowAtlas.Tracked.save();
         GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT | GL11.GL_SCISSOR_BIT);
         try {
            if (!world() || !ensureLayer()) {
               return;
            }
            mapping(this.f);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, layerFbo);
            GL11.glViewport(VPI[0], VPI[1], VPI[2], VPI[3]);
            GLStateRenderThread.ScissorTest.set(false);
            GLStateRenderThread.StencilTest.set(false);
            GLStateRenderThread.ColorMask.set(true, true, true, true);
            GLStateRenderThread.DepthMask.set(true);
            // the union of the planes' panes on screen: what the composite can read
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (int i = 0; i < this.f.nP; i++) {
               int o = i * 10;
               float pa = (this.f.planes[o + 6] - MAP[1]) / MAP[0], pb = (this.f.planes[o + 8] - MAP[1]) / MAP[0];
               float qa = (this.f.planes[o + 7] - MAP[3]) / MAP[2], qb = (this.f.planes[o + 9] - MAP[3]) / MAP[2];
               x0 = Math.min(x0, Math.min(pa, pb));
               x1 = Math.max(x1, Math.max(pa, pb));
               y0 = Math.min(y0, Math.min(qa, qb));
               y1 = Math.max(y1, Math.max(qa, qb));
            }
            int sx = Math.max(0, (int)Math.floor(x0) - 2), sy = Math.max(0, (int)Math.floor(y0) - 2);
            int sw = Math.max(0, Math.min(layerW, (int)Math.ceil(x1) + 2) - sx), sh = Math.max(0, Math.min(layerH, (int)Math.ceil(y1) + 2) - sy);
            if (sw <= 0 || sh <= 0) {
               return;
            }
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(sx, sy, sw, sh);
            GL11.glClearColor(0F, 0F, 0F, 0F);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GLStateRenderThread.ScissorTest.set(true);
            GLStateRenderThread.DepthTest.set(true);
            GLStateRenderThread.DepthFunc.set(GL11.GL_LEQUAL);
            GL11.glFrontFace(GL11.GL_CW); // a reflection turns the triangles' winding over
            boolean calibrated = false;
            for (int i = 0; i < PENDING.size(); i++) {
               Pending p = PENDING.get(i);
               if (ShadowAtlas.texturesPending(p.slot)) {
                  continue;
               }
               for (int k = 0; k < this.f.nP; k++) {
                  if ((p.planes & (1 << k)) == 0) {
                     continue;
                  }
                  int o = k * 10;
                  float cAbs = this.f.planes[o + 1] + (this.f.planes[o] == 0F ? this.f.view.oy : this.f.view.ox);
                  CAMERA.setUp(p, (int)this.f.planes[o], cAbs, viewLateral(this.f.planeMirror[k], this.f.skip), viewDrop(this.f.planeMirror[k], this.f.skip));
                  if (!calibrated) {
                     calibrated = CAMERA.calibrate(this.f);
                  }
                  float squareDepth = p.slot.squareDepth;
                  boolean outline = ShadowAtlas.outline(p.slot, false); // the aim outline is a screen overlay: once, not per plane
                  ModelCamera.instance = CAMERA;
                  GLStateRenderThread.ScissorTest.set(true);
                  GL11.glScissor(sx, sy, sw, sh);
                  try {
                     synchronized (p.slot) {
                        p.slot.render();
                     }
                  } finally {
                     p.slot.squareDepth = squareDepth;
                     ShadowAtlas.outline(p.slot, outline);
                  }
                  modelDraws++;
               }
            }
            layerSerial = calibrated ? this.f.serial : -1;
            layerFrames++;
         } catch (Throwable t) {
            failed = true;
            Log.warn("mirrors: model pass failed, off: " + t);
         } finally {
            GL11.glFrontFace(GL11.GL_CCW);
            ModelCamera.instance = prevCam;
            saved.restore();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
            GL11.glPopAttrib();
            GL11.glDepthRange(0.0, 1.0);
            recyclePending();
            Texture.lastTextureID = -1;
            flushCpuNs += System.nanoTime() - t0;
         }
      }
   }

   /**
    * The stock world camera of a model (Core.DoPushIsoStuff) with the plane's reflection between the world and the model:
    * modelview = A T B with B the model's own scale / facing / offset; the reflection R acts in T's frame, so the draw uses
    * A T R B = MV B^-1 R B. Its lighting is computed in the model's frame and stays the real one. The depth is the plain
    * projected one (squareDepth set as ShadowAtlas' sun camera does), which the composite turns back into iso depth.
    */
   static final class MirrorCamera extends ModelCamera {
      private Pending p;
      private int axis;
      private float c, viewL = 1F, viewS = 1F / 3F;
      private final Matrix4f b = new Matrix4f(), bi = new Matrix4f(), r = new Matrix4f(), scratch = new Matrix4f(), scratch2 = new Matrix4f();
      private int devFacingLogs;
      private final Vector4f v = new Vector4f();

      void setUp(Pending p, int axis, float cAbs, float viewL, float viewS) {
         this.p = p;
         this.viewL = viewL;
         this.viewS = viewS;
         this.axis = axis;
         this.c = cAbs;
         this.useAngle = p.angle;
         this.useWorldIso = true;
         this.inVehicle = false;
         this.x = p.camX;
         this.y = p.camY;
         this.z = p.camZ;
         this.depthMask = true;
      }

      private Matrix4f modelB() {
         this.b.identity();
         if (this.p.vehicle) {
            this.b.scale(-1.0F, 1.0F, 1.0F).rotate(this.useAngle + (float)Math.PI, 0F, 1F, 0F);
         } else {
            this.b.scale(-1.5F, 1.5F, 1.5F).rotate(this.useAngle + (float)Math.PI, 0F, 1F, 0F).translate(0F, -0.48F, 0F);
         }
         return this.b;
      }

      @Override
      public void Begin() {
         Core core = Core.getInstance();
         core.DoPushIsoStuff(this.x, this.y, this.z, this.useAngle, this.p.vehicle);
         Matrix4f mv = core.modelViewMatrixStack.peek();
         // T's frame: (-(wx - x), (wz - z) * 2.449, -(wy - y)); the plane wy = c is W.z = -(c - y), wx = c is W.x = -(c - x)
         // A point X d squares in front of the plane is to show at the pane point P = X - d D, D = the reflected view ray per
         // square out (north pane (-L, 1, -S), west (1, -L, -S) in world x, y, levels; L = 1, S = 1/3 the camera's true
         // reflection), with the iso depth 8/3 d under P's (the composite turns the layer's depth back into d): Y = P - d (1, 1, 1/3)
         // (the view axis), i.e. Y = X - d K, K = D + (1, 1, 1/3). With L = 1, S = 1/3 that is the plane's reflection. In T's
         // frame (x, y, z) = (-(wx - x), 2.449 (wz - z), -(wy - y)): north d = zc - W.z, west d = xc - W.x. det = -1 for every
         // L, S (still a mirror image: the winding turns over)
         this.r.identity();
         float kDrop = -2.449F * (1F / 3F - this.viewS);
         if (this.axis == 0) {
            float zc = -(this.c - this.y);
            float k0 = 1F - this.viewL, k1 = kDrop, k2 = 2F;
            this.r.m20(-k0).m21(-k1).m22(1F - k2).m30(zc * k0).m31(zc * k1).m32(zc * k2);
         } else {
            float xc = -(this.c - this.x);
            float k0 = 2F, k1 = kDrop, k2 = 1F - this.viewL;
            this.r.m00(1F - k0).m01(-k1).m02(-k2).m30(xc * k0).m31(xc * k1).m32(xc * k2);
         }
         modelB();
         this.bi.set(this.b).invert();
         mv.mul(this.scratch.set(this.bi).mul(this.r).mul(this.b));
         Matrix4f pv = this.scratch.set(core.projectionMatrixStack.peek()).mul(mv);
         if (this.p.vehicle && this.p.slot != null) {
            pv.translate(0.0F, this.p.slot.centerOfMassY, 0.0F);
         }
         float originZ = pv.m32() / pv.m33();
         if (Config.DEV_MIRRORS_LOG && devFacingLogs < 6) {
            // dev: the model's forward axis (model z) under the mirrored camera against the real one: a character facing
            // the pane must turn its front to the camera in the mirror, the depth step of the axis flips sign
            devFacingLogs++;
            Matrix4f real = new Matrix4f(core.projectionMatrixStack.peek()).mul(new Matrix4f(mv).mul(this.scratch2.set(this.bi).mul(this.r).mul(this.b).invert()));
            Vector4f a0 = real.transform(new Vector4f(0F, 0F, 0F, 1F)), ax = real.transform(new Vector4f(1F, 0F, 0F, 1F)), az = real.transform(new Vector4f(0F, 0F, 1F, 1F));
            Vector4f m0 = pv.transform(new Vector4f(0F, 0F, 0F, 1F)), mx = pv.transform(new Vector4f(1F, 0F, 0F, 1F)), mz = pv.transform(new Vector4f(0F, 0F, 1F, 1F));
            Log.info(String.format(java.util.Locale.ROOT, "mirrors: dev facing check (%s, angle %.2f, plane %s %.2f): model x ndc dz real %+.5f mirrored %+.5f; model z ndc dz real %+.5f mirrored %+.5f (one axis flips sign: the mirror image)",
               this.p.vehicle ? "vehicle" : "character", this.useAngle, this.axis == 0 ? "y" : "x", this.c, ax.z - a0.z, mx.z - m0.z, az.z - a0.z, mz.z - m0.z));
         }
         if (this.p.slot != null) {
            this.p.slot.squareDepth = (originZ + 1.0F) / 2.0F + (this.p.vehicle ? 0.0F : 1.0E-4F);
         }
         GL11.glDepthRange(0.0, 1.0);
         GL11.glDepthMask(true);
      }

      @Override
      public void End() {
         Core.getInstance().DoPopIsoStuff();
      }

      /**
       * The layer's depth as iso depth: ndc z = z0 + k (w - w0) for any world point (the ortho view's depth runs along the
       * iso view axis), measured on this camera's unmirrored frame at the model's square and one square east of it.
       */
      boolean calibrate(Frame f) {
         Core core = Core.getInstance();
         core.DoPushIsoStuff(this.x, this.y, this.z, this.useAngle, this.p.vehicle);
         Matrix4f mv = core.modelViewMatrixStack.peek();
         modelB();
         this.bi.set(this.b).invert();
         Matrix4f at = this.scratch.set(core.projectionMatrixStack.peek()).mul(mv).mul(this.bi); // P A T: T's frame -> clip
         at.transform(this.v.set(0F, 0F, 0F, 1F));
         float z0 = this.v.z / this.v.w;
         at.transform(this.v.set(-1F, 0F, 0F, 1F)); // one square east: w + 1
         float z1 = this.v.z / this.v.w;
         core.DoPopIsoStuff();
         float k = z1 - z0;
         if (Math.abs(k) < 1e-9F) {
            return false;
         }
         layerK = 1F / k;
         layerZ0 = z0;
         layerW0 = (this.x - f.view.ox) + (this.y - f.view.oy) + 2F * this.z;
         return true;
      }
   }

   public static String stats() {
      return "mirrors: frames " + frames + ", static draws " + staticDraws + " (" + tileMarches + " pane marches, " + tileAllocs + " tiles, " + atlasResets + " atlas resets, " + hiddenSkips + " hidden pane-frames skipped)" + ", composites " + lateDraws + ", model layer frames " + layerFrames + " (kept " + modelReuses + "), mirrored model draws " + modelDraws
            + ", framebuffer queries " + requeries + String.format(java.util.Locale.ROOT, ", render thread us per call: static %.1f, composite %.1f, model flush %.1f per drawn frame", staticCpuNs / 1e3 / Math.max(1, staticDraws),
               lateCpuNs / 1e3 / Math.max(1, lateDraws), flushCpuNs / 1e3 / Math.max(1, layerFrames))
            + String.format(java.util.Locale.ROOT, " (composite: upload %.1f, set-up %.1f, draw %.1f, restore %.1f)", lateSplit[0] / 1e3 / Math.max(1, lateDraws),
               lateSplit[1] / 1e3 / Math.max(1, lateDraws), lateSplit[2] / 1e3 / Math.max(1, lateDraws), lateSplit[3] / 1e3 / Math.max(1, lateDraws))
            + ", " + MirrorGeometry.stats() + (failed ? ", failed" : "");
   }

   // ------------------------------------------------------------------------------------------------ shaders

   static String vert(boolean full) {
      return (full ? "#version 430\n" : "#version 410 core\n") + VERT_BODY;
   }

   static final String VERT_BODY = String.join("\n",
         "uniform sampler2D Data;",
         "uniform vec4 mapA;", // u = mapA.x px + mapA.y, v = mapA.z py + mapA.w (window px, iso relative to the origin square)
         "uniform vec4 vp;",
         "uniform vec4 mapC;", // kC, cC: iso depth w = kC depth + cC
         "uniform vec4 depthTest;", // x on: the quad at the pane's own depth, y the slack towards the camera (iso depth units)
         "uniform float first;",
         "flat out int inst;",
         "out vec2 sUv;",
         "out vec2 mUv;",
         "void main() {",
         "   inst = gl_InstanceID + int(first);",
         "   vec4 q = texelFetch(Data, ivec2(0, inst), 0);",
         "   vec4 s = texelFetch(Data, ivec2(1, inst), 0);",
         "   vec4 m = texelFetch(Data, ivec2(2, inst), 0);",
         "   int k = gl_VertexID;",
         "   vec2 c = vec2((k == 1 || k == 2) ? 1.0 : 0.0, k >= 2 ? 1.0 : 0.0);",
         "   vec2 iso = mix(q.xy, q.zw, c);",
         "   vec2 px = vec2((iso.x - mapA.y) / mapA.x, (iso.y - mapA.w) / mapA.z);",
         "   sUv = mix(s.xy, s.zw, c);",
         "   mUv = mix(m.xy, m.zw, c);",
         "   float z = 0.0;",
         "   if (depthTest.x > 0.5) {", // the pane's depth under this corner (the plane is linear on screen): the hardware test hides it behind whatever stands in front
         "      vec4 pl = texelFetch(Data, ivec2(3, inst), 0);",
         "      vec3 P = pl.x < 0.5 ? vec3(iso.x + pl.y, pl.y, 0.0) : vec3(pl.y, pl.y - iso.x, 0.0);",
         "      P.z = (P.x + P.y - iso.y) / 6.0;",
         "      float w = P.x + P.y + 2.0 * P.z + depthTest.y;",
         "      z = 2.0 * (w - mapC.y) / mapC.x - 1.0;",
         "   }",
         "   gl_Position = vec4((px - vp.xy) / vp.zw * 2.0 - 1.0, z, 1.0);",
         "}");

   /** GL 4.1 static pass: the due panes' tiles rasterised in the atlas (tile space), with the pane coordinates per texel. */
   static String vertTile(boolean full) {
      return String.join("\n",
         full ? "#version 430" : "#version 410 core",
         "uniform sampler2D Data;",
         "uniform vec4 mapA;",
         "uniform float first;",
         "flat out int inst;",
         "out vec2 sUv;",
         "out vec2 mUv;",
         "out vec2 lpx;",
         "out vec2 tq;",
         "void main() {",
         "   inst = gl_InstanceID + int(first);",
         "   vec4 q = texelFetch(Data, ivec2(0, inst), 0);",
         "   vec4 s = texelFetch(Data, ivec2(1, inst), 0);",
         "   vec4 m = texelFetch(Data, ivec2(2, inst), 0);",
         "   vec4 tile = texelFetch(Data, ivec2(5, inst), 0);",
         "   int k = gl_VertexID;",
         "   vec2 c = vec2((k == 1 || k == 2) ? 1.0 : 0.0, k >= 2 ? 1.0 : 0.0);",
         "   vec2 quadPx = max(vec2((q.z - q.x) / mapA.x, (q.w - q.y) / abs(mapA.z)), vec2(1.0));",
         "   lpx = c * tile.zw;", // px from the quad's top-left (the composite reads texel floor(local))
         "   tq = lpx / quadPx;",
         "   sUv = mix(s.xy, s.zw, tq);",
         "   mUv = mix(m.xy, m.zw, tq);",
         "   gl_Position = vec4((tile.xy + lpx) / " + ATLAS + ".0 * 2.0 - 1.0, 0.0, 1.0);",
         "}");
   }

   static final int K_STATIC_IMAGE = 0, K_STATIC_FBO = 1, K_LATE = 2;

   /** The fragment programs: the static march storing into the atlas images (GL 4.3), the same rendering into the atlas
    * framebuffer (GL 4.1: macOS), the composite. full: GL 4.3 (image stores, the visibility buffer), else GLSL 4.10 core. */
   static String frag(int kind, boolean full) {
      boolean stat = kind != K_LATE, fbo = kind == K_STATIC_FBO;
      return String.join("\n",
         full ? "#version 430" : "#version 410 core\n#define PZ_NO_VIS",
         stat ? "#define PZ_STATIC" : "",
         kind == K_STATIC_IMAGE ? "#extension GL_ARB_shader_image_load_store : require" : "",
         !stat && full ? "#extension GL_ARB_shader_storage_buffer_object : require" : "",
         !stat && full ? "layout(early_fragment_tests) in;" : "", // (the composite runs only where the pane is in front: the depth test first)
         !stat && full ? "layout(std430) buffer VisBuf { uint vis[]; };" : "",
         stat ? "" : "uniform int visRow;",
         stat ? "" : "uniform int visOn;",
         "uniform sampler2D Data;",
         "uniform sampler2D WorldColor;",
         "uniform sampler2D WorldDepth;",
         "uniform sampler2D Sprite;",
         "uniform sampler2D Masks;",
         "uniform sampler2D StaticTex;",
         "uniform sampler2D LayerColor;",
         "uniform sampler2D LayerDepth;",
         kind == K_STATIC_IMAGE ? "layout(rgba8) writeonly uniform image2D Static;" : "",
         kind == K_STATIC_IMAGE ? "layout(r8) writeonly uniform image2D Glass;" : "",
         stat ? "" : "uniform sampler2D GlassTex;",
         stat ? "uniform sampler2D GeomTex;" : "",
         "uniform vec4 mapA;", // kA, cA, kB, cB
         "uniform vec4 mapC;", // kC, cC (iso depth w = kC depth + cC), -, the longest ray (squares)
         "uniform vec4 vp;",
         "uniform vec4 march;", // most taps, thickness (iso depth units), stand-in strength, px between taps
         "uniform vec4 layerMap;", // on, 1 / k, z0, w0: iso depth of a layer texel = w0 + (ndc z - z0) / k
         "uniform vec4 dev;", // view, skip bits, static on
         "flat in int inst;",
         "in vec2 sUv;",
         "in vec2 mUv;",
         fbo ? "in vec2 lpx;" : "", // the texel's px offset in its pane (tile space)
         fbo ? "in vec2 tq;" : "", // its place in the sprite's quad (0..1 inside)
         fbo ? "layout(location = 0) out vec4 fragColor;" : "out vec4 fragColor;",
         fbo ? "layout(location = 1) out vec4 glassOut;" : "",
         FUNCS,
         fbo ? MAIN_STATIC_FBO : (stat ? MAIN_STATIC_IMAGE : MAIN_LATE));
   }

   static final String FUNCS = String.join("\n",
         "float isoDepth(vec2 px) { return mapC.x * texelFetch(WorldDepth, ivec2(px), 0).r + mapC.y; }",
         "bool inside(vec2 px) { return px.x >= vp.x && px.y >= vp.y && px.x < vp.x + vp.z && px.y < vp.y + vp.w; }",
         // the reflected ray from pane point P (x, y, z relative; z in levels): screen line px0 + pxPerT t, iso depth wM - 2t/3,
         // height z - t/3; the first tap where the scene stands in front of the ray within the thickness, refined
         // dev view 5: how each ray ended (green floor shortcut, blue marched hit, magenta a marched hit under the pane's own
         // floor, yellow floor landing hidden (the floor seen last stands in), orange the same with the landing pixel, red
         // reach fallback, black miss)
         "vec4 kindOut(vec4 r, vec3 k) { return dev.x == 5.0 ? vec4(k, r.a) : r; }",
         // a hit: colour, and in alpha the distance (t / reach, 127 steps) with a stand-in flag in its lowest bit (the
         // composite draws a stand-in, the floor guessed where the camera cannot see it, at mirrorsStandInPct); 255 = none
         "vec4 hitOut(vec3 c, float t, float standIn) { return vec4(c, (floor(clamp(t / mapC.w, 0.0, 0.99) * 126.0 + 0.5) * 2.0 + standIn) / 255.0); }",
         "vec4 marchRay(vec3 P, float axis, float tMax, float zMin, float floorZ) {",
         "   float tEnd = min(tMax, max(0.05, (P.z - zMin) * 3.0));",
         "   vec2 pxPerT = vec2((axis < 0.5 ? -2.0 : 2.0) / mapA.x, 2.0 / mapA.z);",
         "   vec2 px0 = vec2((P.x - P.y - mapA.y) / mapA.x, (P.x + P.y - 6.0 * P.z - mapA.w) / mapA.z);",
         "   float wM = P.x + P.y + 2.0 * P.z;",
         // floor first: the ray meets the pane's own floor at tf; when the frame shows that floor there and half a march's
         // taps find nothing standing in the ray's way before it, that is the hit (no refinement needed)
         "   float tf = (P.z - floorZ) * 3.0;",
         // the ray's first 0.2 squares are the pane's own surroundings: the mirror sprite and its wall stand there in the
         // frame's depth, within the thickness of the ray's own depth; at zoom 0.25 the taps (10 px apart) are 0.08 squares
         // apart and the first ones "hit" the mirror itself: its glass colour speckled over the reflection in triangles
         // (maintainer's screenshot 2026-10-04). Nothing else stands that close in front of the glass (dev bit 262144: off)
         "   float t0 = (int(dev.y) & 262144) != 0 ? 0.0 : min(0.2, 0.5 * tEnd);",
         "   vec2 pf = px0 + pxPerT * tf;",
         "   if ((int(dev.y) & 16) == 0 && tf > 0.05 && tf < tEnd + 0.01) {",
         "      if (inside(pf) && abs(isoDepth(pf) - (wM - 0.6666667 * tf)) < 0.25) {",
         "         bool clear = true;",
         "         int kk = max(3, int(clamp(length(pxPerT) * tf / march.w, 4.0, march.x)) / 2);", // half the taps a full march of tf takes (harness/mirrors/march_sim.py: same error as the full march)
         "         for (int k = 1; k <= 32; k++) {",
         "            if (k > kk) break;",
         "            float t = t0 + max(tf - t0, 0.0) * float(k) / (float(kk) + 1.0);",
         "            float d = isoDepth(px0 + pxPerT * t) - (wM - 0.6666667 * t);",
         "            if (d >= 0.0 && d < march.y) { clear = false; break; }",
         "         }",
         "         if (clear) return kindOut(hitOut(texelFetch(WorldColor, ivec2(pf), 0).rgb, tf, 0.0), vec3(0.0, 1.0, 0.0));",
         "      }",
         "   }",
         "   int n = int(clamp(length(pxPerT) * tEnd / march.w, 4.0, march.x));",
         "   float dt = (tEnd - t0) / float(n);",
         "   float tLo = t0, tHit = -1.0;",
         "   vec2 lastFloor = vec2(-1.0);", // the last tap whose pixel shows the pane's own floor (iso depth of that floor there)
         "   for (int i = 1; i <= 64; i++) {",
         "      if (i > n) break;",
         "      float t = t0 + dt * float(i);",
         "      vec2 px = px0 + pxPerT * t;",
         "      if (!inside(px)) return vec4(0.0, 0.0, 0.0, 1.0);",
         "      float iz = isoDepth(px);",
         "      float d = iz - (wM - 0.6666667 * t);",
         "      if (d >= 0.0 && d < march.y) { tHit = t; break; }",
         "      if (t < tf && abs(iz - (P.x + P.y - 6.0 * P.z + 2.0 * t + 8.0 * floorZ)) < 0.25) lastFloor = px;",
         "      tLo = t;",
         "   }",
         "   if (tHit < 0.0) {",
         "      if ((P.z - zMin) * 3.0 > tMax) return vec4(0.0, 0.0, 0.0, 1.0);", // out of reach before any floor
         // nothing in the ray's way and its landing on the pane's own floor hidden from the camera (a table, chairs, a
         // bathtub standing there): the floor goes on under what hides it, the last floor pixel the ray passed over stands
         // in, flagged as a stand-in (drawn at mirrorsStandInPct); without one, the landing pixel. (Maintainer's save,
         // 2026-10-04: the ray's end one level under the floor, the old stand-in, showed a pixel squares away, the house's
         // siding as grey bands across a wall mirror's upper half; the landing pixel alone made the dining table's grey top
         // one flat slab over it.)
         "      if ((int(dev.y) & 16) == 0 && (int(dev.y) & 32768) == 0 && tf > 0.05 && tf < tEnd + 0.01 && inside(pf)) {",
         "         vec2 sp = lastFloor.x >= 0.0 && (int(dev.y) & 65536) == 0 ? lastFloor : pf;",
         "         return kindOut(hitOut(texelFetch(WorldColor, ivec2(sp), 0).rgb, tf, 1.0), sp == pf ? vec3(1.0, 0.5, 0.0) : vec3(1.0, 1.0, 0.0));",
         "      }",
         "      tHit = tEnd;", // the floor under the ray (hidden from the camera: its colour on screen stands in)
         "      vec2 pe = px0 + pxPerT * tHit;",
         "      if (!inside(pe)) return vec4(0.0, 0.0, 0.0, 1.0);",
         "      return kindOut(hitOut(texelFetch(WorldColor, ivec2(pe), 0).rgb, tHit, 1.0), vec3(1.0, 0.0, 0.0));",
         "   } else {",
         "      float a = tLo, b = tHit;",
         "      for (int k = 0; k < 4; k++) {",
         "         float m = 0.5 * (a + b);",
         "         float d = isoDepth(px0 + pxPerT * m) - (wM - 0.6666667 * m);",
         "         if (d >= 0.0 && d < march.y) b = m; else a = m;",
         "      }",
         "      tHit = b;",
         // A "hit" in the last 0.15 levels above the pane's own floor, with a floor seen on the way: the ray reached the
         // floor behind what hides it. The thickness test took a thin cut-away wall stub there for a solid (a medicine
         // cabinet reflected the bathroom's outer brick strip, maintainer's save 2026-10-04): the floor goes on instead.
         "      if ((int(dev.y) & 32768) == 0 && tf > 0.05 && tHit > tf - 0.45 && lastFloor.x >= 0.0) return kindOut(hitOut(texelFetch(WorldColor, ivec2(lastFloor), 0).rgb, tf, 1.0), vec3(1.0, 1.0, 0.0));",
         "   }",
         "   vec2 px = px0 + pxPerT * tHit;",
         "   if (!inside(px)) return vec4(0.0, 0.0, 0.0, 1.0);",
         "   return kindOut(hitOut(texelFetch(WorldColor, ivec2(px), 0).rgb, tHit, 0.0), tf > 0.05 && tHit > tf + 0.1 ? vec3(1.0, 0.0, 1.0) : vec3(0.0, 0.3, 1.0));",
         "}",
         // the room geometry (MirrorGeometry) where the march could not see what the ray meets: no hit, a stand-in, or a hit
         // farther than the geometry (the march passed behind something the camera cannot see round: the bathtub's far
         // side); the march's own hit, the frame's real pixel, where the camera sees that surface. Dev view 5: cyan, 6: the
         // geometry alone
         "#ifdef PZ_STATIC",
         "vec4 withGeom(vec4 r, ivec2 at) {",
         "   vec4 g7 = texelFetch(Data, ivec2(7, inst), 0);",
         "   if (g7.x < 0.5 || (int(dev.y) & 131072) != 0) return dev.x == 6.0 ? vec4(0.0, 0.0, 0.0, 1.0) : r;",
         "   vec4 g = texelFetch(GeomTex, at, 0);",
         "   float gc = floor(g.a * 255.0 + 0.5);",
         "   if (dev.x == 6.0) return gc < 254.5 ? g : vec4(0.0, 0.0, 0.0, 1.0);",
         "   if (gc > 254.5) return r;",
         "   float rc = floor(r.a * 255.0 + 0.5);",
         "   bool real = rc < 254.5 && mod(rc, 2.0) < 0.5;",
         "   float tR = floor(rc * 0.5) / 126.0 * mapC.w, tG = floor(gc * 0.5) / 126.0 * mapC.w;",
         "   if (real && tR <= tG + g7.y) return r;",
         "   return dev.x == 5.0 ? vec4(0.0, 1.0, 1.0, g.a) : g;",
         "}",
         "#endif");

   private static String oldMain(boolean stat) {
      return String.join("\n",
         "void main() {",
         "   vec4 m = texelFetch(Data, ivec2(2, inst), 0);",
         "   vec4 pl = texelFetch(Data, ivec2(3, inst), 0);", // axis, c, lowest floor, level
         "   vec4 st = texelFetch(Data, ivec2(4, inst), 0);", // strength, alpha, reach, static
         "   float mask;",
         // visibility feedback: "this pane has pixels on screen" as a plain store from one px in 16 (an atomic add from every
         // px of a pane on one counter serialised them: the composite went 8 -> 73 us)
         stat ? "" : "#ifndef PZ_NO_VIS\n   if (visOn != 0 && ((int(gl_FragCoord.x) | int(gl_FragCoord.y)) & 3) == 0) vis[visRow + inst] = 1u;\n#endif",
         "   vec2 fc = gl_FragCoord.xy;",
         "   float u = mapA.x * fc.x + mapA.y, v = mapA.z * fc.y + mapA.w;",
         // the pane's own coordinates (px from its quad's top-left at this zoom, pan-free) and its atlas tile
         "   vec4 q0 = texelFetch(Data, ivec2(0, inst), 0);",
         "   vec4 tile = texelFetch(Data, ivec2(5, inst), 0);",
         "   vec4 t6 = texelFetch(Data, ivec2(6, inst), 0);", // mirror, texels per px
         "   vec2 local = vec2((u - q0.x) / mapA.x, (v - q0.y) / abs(mapA.z));",
         "   ivec2 lp = ivec2(floor(local * t6.zw));", // (t6.zw: tile px per px, 1 unless the tile was marched at another zoom)
         "   ivec2 texel = clamp(lp, ivec2(0), ivec2(tile.zw) - 1);",
         stat ? String.join("\n",
         "   vec4 sp = texture(Sprite, sUv);",
         "   mask = m.z < 0.0 ? smoothstep(0.1, 0.2, sp.a) * (1.0 - smoothstep(0.9, 0.97, sp.a)) : texture(Masks, mUv).r * step(0.5, sp.a);",
         "   if (all(greaterThanEqual(lp, ivec2(0)))) imageStore(Glass, ivec2(tile.xy) + texel, vec4(mask));", // the pane's glass, for the composite
         "   if (mask < 0.004 && (t6.y > 0.75 || ((lp.x | lp.y) & 1) != 0)) discard;")
         : String.join("\n",
         "   mask = st.w > 0.5 ? texelFetch(GlassTex, ivec2(tile.xy) + texel, 0).r : 0.0;",
         "   if (dev.w < 0.5) { vec4 sp = texture(Sprite, sUv); mask = m.z < 0.0 ? smoothstep(0.1, 0.2, sp.a) * (1.0 - smoothstep(0.9, 0.97, sp.a)) : texture(Masks, mUv).r * step(0.5, sp.a); }",
         "   if (mask < 0.004) discard;"),
         "   vec3 P = pl.x < 0.5 ? vec3(u + pl.y, pl.y, 0.0) : vec3(pl.y, pl.y - u, 0.0);",
         "   P.z = (P.x + P.y - v) / 6.0;",
         stat ? String.join("\n",
         "   fragColor = vec4(0.0);",
         "   if (any(lessThan(lp, ivec2(0)))) return;",
         "   if ((int(dev.y) & 32) != 0) { imageStore(Static, ivec2(tile.xy) + texel, vec4(0.5, 0.5, 0.5, 0.5)); return; }", // dev: the pass without the march (the glass mask still written)
         "   if (t6.y > 0.75) { imageStore(Static, ivec2(tile.xy) + texel, withGeom(marchRay(P, pl.x, st.z, pl.z, pl.w), ivec2(tile.xy) + texel)); return; }",
         "   if (((lp.x | lp.y) & 1) != 0) return;", // half resolution: the even px of each 2x2 marches and stores the block
         "   vec4 r2 = marchRay(P, pl.x, st.z, pl.z, pl.w);",
         "   ivec2 b = ivec2(tile.xy) + texel;",
         "   ivec2 hi = ivec2(tile.xy + tile.zw) - 1;",
         "   imageStore(Static, b, r2);",
         "   imageStore(Static, min(b + ivec2(1, 0), hi), r2);",
         "   imageStore(Static, min(b + ivec2(0, 1), hi), r2);",
         "   imageStore(Static, min(b + ivec2(1, 1), hi), r2);",
         "}")
         : String.join("\n",
         "   float wM = P.x + P.y + 2.0 * P.z;",
         "   if ((dev.w < 0.5 || dev.w > 1.5) && isoDepth(fc) > wM + 0.35) discard;", // something stands in front of the pane (dev.w 1: the hardware depth test did it)
         "   if ((int(dev.y) & 8) != 0) { fragColor = vec4(1.0, 0.0, 1.0, 0.5 * mask); return; }",
         "   vec4 r = vec4(0.0, 0.0, 0.0, 1.0);",
         "   if (dev.z > 0.5) r = st.w > 0.5 ? texelFetch(StaticTex, ivec2(tile.xy) + texel, 0) : dev.w < 0.5 ? marchRay(P, pl.x, st.z, pl.z, pl.w) : r;", // (no static yet, live reads off: the reflection starts next frame)
         "   float code = floor(r.a * 255.0 + 0.5);", // hitOut's alpha: distance steps and the stand-in bit; 255 = no reflection
         "   bool hit = code < 254.5;",
         "   float conf = mod(code, 2.0) > 0.5 ? march.z : 1.0;",
         "   float tS = hit ? floor(code * 0.5) / 126.0 * mapC.w : 1e9;",
         "   vec3 col = r.rgb;",
         "   vec4 lc = vec4(0.0);",
         "   if (layerMap.x > 0.5) {",
         "      lc = texelFetch(LayerColor, ivec2(fc), 0);",
         "      if (lc.a > 0.01) {",
         "         float wP = layerMap.w + (texelFetch(LayerDepth, ivec2(fc), 0).r * 2.0 - 1.0 - layerMap.z) * layerMap.y;",
         "         float tM = 0.375 * (wM - wP) * texelFetch(Data, ivec2(7, inst), 0).z;", // the model's distance in front of the pane (iso depth falls 8/3 a square behind it), on the room's scale (pack: 3 viewDrop)
         "         if (tM < tS + 0.15) { col = mix(col, lc.rgb, lc.a); conf = mix(conf, 1.0, lc.a); hit = true; tS = min(tS, tM); }",
         "      }",
         "   }",
         "   if (!hit) discard;",
         "   float fade = 1.0 - smoothstep(0.75, 1.0, tS / mapC.w);",
         "   float a = mask * st.x * st.y * fade * conf;",
         "   if (dev.x == 1.0 || dev.x == 5.0 || dev.x == 6.0) a = mask;",
         "   if (dev.x == 2.0) { fragColor = vec4(mask, code < 254.5 ? 1.0 : 0.0, 0.0, 1.0); return; }",
         "   if (dev.x == 3.0) { fragColor = vec4(vec3(tS / mapC.w), mask); return; }",
         "   if (dev.x == 4.0) { fragColor = vec4(lc.rgb, mask * lc.a); return; }",
         "   fragColor = vec4(col, a);",
         "}"));
   }

   static final String MAIN_STATIC_IMAGE = oldMain(true), MAIN_LATE = oldMain(false);

   /** GL 4.1 static pass: each atlas texel of a due pane's tile, rasterised in tile space; colour + hit distance, glass mask. */
   static final String MAIN_STATIC_FBO = String.join("\n",
         "void main() {",
         "   vec4 m = texelFetch(Data, ivec2(2, inst), 0);",
         "   vec4 pl = texelFetch(Data, ivec2(3, inst), 0);",
         "   vec4 st = texelFetch(Data, ivec2(4, inst), 0);",
         "   vec4 q0 = texelFetch(Data, ivec2(0, inst), 0);",
         "   vec2 local = lpx;",
         "   float u = q0.x + local.x * mapA.x, v = q0.y + local.y * abs(mapA.z);",
         "   float mask = 0.0;",
         "   if (all(greaterThanEqual(tq, vec2(0.0))) && all(lessThanEqual(tq, vec2(1.0)))) {",
         "      vec4 sp = texture(Sprite, sUv);",
         "      mask = m.z < 0.0 ? smoothstep(0.1, 0.2, sp.a) * (1.0 - smoothstep(0.9, 0.97, sp.a)) : texture(Masks, mUv).r * step(0.5, sp.a);",
         "   }",
         "   glassOut = vec4(mask);",
         "   fragColor = vec4(0.0, 0.0, 0.0, 1.0);", // no reflection here (every texel of the tile is written: no clear)
         "   if (mask < 0.004) return;",
         "   if ((int(dev.y) & 32) != 0) { fragColor = vec4(0.5, 0.5, 0.5, 0.5); return; }",
         "   vec3 P = pl.x < 0.5 ? vec3(u + pl.y, pl.y, 0.0) : vec3(pl.y, pl.y - u, 0.0);",
         "   P.z = (P.x + P.y - v) / 6.0;",
         "   fragColor = withGeom(marchRay(P, pl.x, st.z, pl.z, pl.w), ivec2(gl_FragCoord.xy));",
         "}");

}
