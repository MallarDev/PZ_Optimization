package pzopt;

import java.util.IdentityHashMap;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.textures.TextureDraw;
import zombie.vehicles.BaseVehicle;

/**
 * The people in a car seen through its glass (Config {@code carOccupant}, with {@code carGlass}; write-up
 * docs/findings-car-occupant-2026-10-03.md).
 *
 * <p>Stock never draws a seated character: IsoGameCharacter.render returns when the seat's script has no
 * {@code showPassenger} (no vehicle script sets it), so a car looks empty whoever drives it. The game still animates the
 * seated character (the player-vehicle anim set: idle driving, steering, enter / exit), and its renderer can place a
 * model in a seat (Model.CharacterModelCameraBegin's inVehicle branch).
 *
 * <p>{@code impostor} (default): before the moving objects, each occupant of a car on screen with glass is drawn by the
 * game's own character path into a tile of an offscreen atlas (colour + depth): the world camera with its projection
 * zoomed onto the cabin's screen rect (x, y only: the depths stay the world's), the seat's depth offset (targetDepth)
 * forced to 0.5 so every occupant's depth is the plain projected one. The glass program (CarGlass) maps each window
 * texel's chassis position through the same projection into the tile, takes the occupant's colour and depth there, turns
 * the depth difference into a distance along the view ray and lets the occupant through where it is nearer than what the
 * cabin ray-cast hit (seats, headrests, the dashboard in front of it hide it). One character draw a car (what the same
 * player costs on foot), no read of the world framebuffer, nothing per pixel outside the occupied cars' glass.
 *
 * <p>{@code stock}: the stock showPassenger path forced on (the model drawn into the world among the moving objects,
 * where the car's opaque glass covers it: an A/B baseline). {@code box}: the cabin ray-cast's dark torso box and head
 * ball (the first car glass release). {@code off}: no occupant at all.
 */
public final class CarOccupant {
   private CarOccupant() {
   }

   static final int ATLAS = 1024, SLOT = 512, SLOTS = (ATLAS / SLOT) * (ATLAS / SLOT);
   static final int COLOR_UNIT = 10, DEPTH_UNIT = 13;
   static final String MODE = Config.CAR_OCCUPANT;
   private static volatile boolean failed;
   private static long captured, tiles, frames, skippedNoModel, beginNs, passNs, renderNs, setupNs, waitNs, endNs;

   /** Is the impostor pass on (car glass on, mode impostor)? */
   public static boolean impostor() {
      return "impostor".equals(MODE) && !failed && CarGlass.active();
   }

   /** IsoGameCharacter.render: carOccupant=stock draws a seated character among the moving objects (the stock showPassenger path). */
   public static boolean showStock(IsoGameCharacter c) {
      return "stock".equals(MODE) && Overrides.enabled() && Config.CAR_GLASS;
   }

   /**
    * What the cabin ray-cast draws for an occupied seat without an impostor tile: 4 the capsule body in the occupant's
    * colours (proxy, and impostor until its first tile), 2 the first release's torso box and head ball (box), 1 nothing.
    */
   static float seatProxy() {
      return "proxy".equals(MODE) || "impostor".equals(MODE) ? 4F : "box".equals(MODE) ? 2F : 1F;
   }

   // ------------------------------------------------------------------------------------------------ proxy colours

   /** An occupant's colours for the capsule proxy (packed 8-bit RGB in a float each): top, legs, skin, hair. */
   private static final java.util.concurrent.ConcurrentHashMap<IsoGameCharacter, float[]> PALETTES = new java.util.concurrent.ConcurrentHashMap<>();
   private static final java.util.concurrent.ConcurrentHashMap<String, float[]> TEXTURE_MEANS = new java.util.concurrent.ConcurrentHashMap<>();
   private static final java.util.concurrent.ExecutorService MEANS = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "pzopt-occupant-colours");
      t.setDaemon(true);
      return t;
   });
   private static final java.util.Set<String> TOPS = java.util.Set.of("FullSuit", "Boilersuit", "FullTop", "BathRobe", "FullRobe", "Dress", "Jacket", "JacketHat", "JacketSuit",
         "Jacket_Bulky", "Jacket_Down", "JacketHat_Bulky", "Sweater", "SweaterHat", "Shirt", "ShortSleeveShirt", "Tshirt", "TankTop", "Torso1Legs1", "TorsoExtra", "TorsoExtraVest");
   private static final java.util.Set<String> LEGS = java.util.Set.of("FullSuit", "Boilersuit", "Dress", "Pants", "Pants_Skinny", "PantsExtra", "Legs1", "ShortPants", "ShortsShort",
         "Skirt", "LongSkirt", "Torso1Legs1");
   private static final java.util.Set<String> HATS = java.util.Set.of("Hat", "FullHat", "FullSuitHead", "JacketHat", "SweaterHat", "JacketHat_Bulky", "MaskFull");

   /** Render thread, CarGlass.drawUniforms: an occupant's packed colours (cached; refreshed every 2 s; textures averaged on a worker). */
   static float[] palette(IsoGameCharacter c) {
      float[] p = PALETTES.get(c);
      long now = System.nanoTime();
      if (p != null && now - (long)p[4] < 2_000_000_000L && p[5] > 0.5F) {
         return p;
      }
      float[] n = new float[6];
      float[] top = {0.18F, 0.20F, 0.24F}, legs = {0.16F, 0.17F, 0.22F}, skin = {0.80F, 0.62F, 0.50F}, hair = {0.25F, 0.17F, 0.10F};
      boolean ready = true;
      int topRank = Integer.MAX_VALUE, legRank = Integer.MAX_VALUE;
      try {
         zombie.core.skinnedmodel.visual.HumanVisual hv = c instanceof zombie.core.skinnedmodel.visual.IHumanVisual h ? h.getHumanVisual() : null;
         if (hv != null) {
            // the skin: the body texture's own colour (the tone is in the texture) under the visual's skin tint
            String st = hv.getSkinTexture();
            float[] sm = st == null ? null : textureMean("Body/" + st);
            if (sm != null) {
               System.arraycopy(sm, 0, skin, 0, 3);
            } else if (st != null) {
               ready = false;
            }
            zombie.core.ImmutableColor sc = hv.getSkinColor();
            if (sc != null && sm != null) {
               skin[0] *= sc.r;
               skin[1] *= sc.g;
               skin[2] *= sc.b;
            }
            set(hair, hv.getHairColor());
         }
         // the worn clothes (a player's are its worn items; a zombie's only item visuals)
         zombie.characters.WornItems.WornItems worn = c.getWornItems();
         int count = worn != null ? worn.size() : 0;
         for (int i = 0; i < count; i++) {
            zombie.characters.WornItems.WornItem wi = worn.get(i);
            zombie.inventory.InventoryItem invItem = wi == null ? null : wi.getItem();
            zombie.core.skinnedmodel.visual.ItemVisual iv = invItem == null ? null : invItem.getVisual();
            zombie.core.skinnedmodel.population.ClothingItem ci = invItem == null ? null : invItem.getClothingItem();
            if (iv == null || ci == null || wi.getLocation() == null) {
               continue;
            }
            zombie.scripting.objects.Item item = invItem.getScriptItem();
            String loc = wi.getLocation().getTranslationName();
            // the outermost layer wins: the worn list runs inner to outer
            boolean isTop = TOPS.contains(loc), isLegs = LEGS.contains(loc), isHat = HATS.contains(loc);
            if (!isTop && !isLegs && !isHat) {
               continue;
            }
            String tex = !ci.textureChoices.isEmpty() ? iv.getTextureChoice(ci) : iv.getBaseTexture(ci);
            float[] mean = tex == null ? null : textureMean(tex);
            if (mean == null) {
               ready = tex == null && ready;
               continue;
            }
            float[] col = mean.clone();
            zombie.core.ImmutableColor tint = iv.getTint(ci);
            if (tint != null && (ci.allowRandomTint || tint.r < 0.99F || tint.g < 0.99F || tint.b < 0.99F)) {
               col[0] *= tint.r;
               col[1] *= tint.g;
               col[2] *= tint.b;
            }
            if (isTop && -i < topRank) {
               topRank = -i;
               System.arraycopy(col, 0, top, 0, 3);
            }
            if (isLegs && -i < legRank) {
               legRank = -i;
               System.arraycopy(col, 0, legs, 0, 3);
            }
            if (isHat) {
               System.arraycopy(col, 0, hair, 0, 3);
            }
         }
      } catch (Throwable t) {
         ready = false; // (the worn list changed under the read: next frame)
      }
      n[0] = pack(top);
      n[1] = pack(legs);
      n[2] = pack(skin);
      n[3] = pack(hair);
      n[4] = now;
      n[5] = ready ? 1F : 0F;
      if (PALETTES.size() > 64) {
         PALETTES.clear();
      }
      PALETTES.put(c, n);
      return n;
   }

   private static void set(float[] d, zombie.core.ImmutableColor c) {
      if (c != null) {
         d[0] = c.r;
         d[1] = c.g;
         d[2] = c.b;
      }
   }

   static float pack(float[] c) {
      int r = Math.max(0, Math.min(255, Math.round(c[0] * 255F))), g = Math.max(0, Math.min(255, Math.round(c[1] * 255F))), b = Math.max(0, Math.min(255, Math.round(c[2] * 255F)));
      return r * 65536F + g * 256F + b;
   }

   /** A clothing texture's mean colour over its opaque texels (read from the game's files on a worker, once), or null while pending. */
   private static float[] textureMean(String name) {
      float[] m = TEXTURE_MEANS.get(name);
      if (m != null) {
         return m.length == 3 ? m : null;
      }
      TEXTURE_MEANS.put(name, new float[0]);
      MEANS.submit(() -> {
         float[] r = {0.2F, 0.2F, 0.22F};
         try {
            String path = zombie.ZomboidFileSystem.instance.getString("media/textures/" + name + ".png");
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(new java.io.File(path));
            if (im != null) {
               double sr = 0, sg = 0, sb = 0, n = 0;
               int step = Math.max(1, Math.min(im.getWidth(), im.getHeight()) / 64);
               for (int y = 0; y < im.getHeight(); y += step) {
                  for (int x = 0; x < im.getWidth(); x += step) {
                     int argb = im.getRGB(x, y);
                     double a = (argb >>> 24) / 255.0;
                     if (a < 0.5) {
                        continue;
                     }
                     sr += ((argb >> 16) & 255) / 255.0;
                     sg += ((argb >> 8) & 255) / 255.0;
                     sb += (argb & 255) / 255.0;
                     n++;
                  }
               }
               if (n > 0) {
                  r = new float[] {(float)(sr / n), (float)(sg / n), (float)(sb / n)};
               }
            }
         } catch (Throwable t) {
            Log.info("car occupant: no colour for " + name + ": " + t);
         }
         TEXTURE_MEANS.put(name, r);
      });
      return null;
   }

   // ------------------------------------------------------------------------------------------------ game thread

   private static final Begin[][] BEGINS = new Begin[8][SLOTS]; // (a row a pass: several views a frame, frames in flight)
   private static final End END = new End();
   private static int ring;

   static {
      for (int i = 0; i < BEGINS.length; i++) {
         for (int j = 0; j < SLOTS; j++) {
            BEGINS[i][j] = new Begin();
         }
      }
   }

   /**
    * Game thread, CarGlass.beforeMoving after the frame drawer (the cars on screen known): the occupants' draws into the
    * atlas, queued ahead of every moving object (the glass that reads them draws later in the same frame).
    */
   static void queue(CarGlass.Frame f, int playerIndex) {
      if (!impostor() || !f.on || f.probeIndex.isEmpty() || (f.skip & 1024) != 0) {
         return;
      }
      Begin[] row = BEGINS[ring = (ring + 1) & 7];
      boolean sec = false;
      long period = (f.skip & 8192) != 0 ? 0L : 1_000_000_000L / Math.max(1, Config.CAR_OCCUPANT_HZ);
      long now = System.nanoTime();
      float zoom = Core.getInstance().getZoom(playerIndex);
      for (Object o : f.probeIndex.keySet()) {
         BaseVehicle v = (BaseVehicle)o;
         int seats = v.getMaxPassengers();
         int drawn = 0;
         for (int i = 0; i < seats; i++) {
            if (drawable(v.getCharacter(i), v)) {
               drawn++;
            }
         }
         if (drawn == 0) {
            continue;
         }
         Keep k = keep(v, f.serial, playerIndex);
         if (k == null) {
            continue; // every tile taken
         }
         // a tile is drawn again carOccupantHz times a second (the pose moves on; at most 30 frames apart), at once when what it shows can no longer
         // be moved into place (the car turned or tilted, the zoom or the people changed); between, the glass shifts the
         // last tile by how far the car moved on screen
         v.jniTransform.getRotation(Q).normalize(); // (Transform.getRotation hands out an unnormalised quaternion)
         boolean turned = Math.abs(Q.x * k.q[0] + Q.y * k.q[1] + Q.z * k.q[2] + Q.w * k.q[3]) < TURN_COS; // (the reprojection is exact up to the parallax of a turn)
         // the pose: the occupants' bones against the drawn tile's (a still driver is drawn carOccupantMinHz times a second,
         // one turning the wheel up to carOccupantHz)
         boolean moved = poseMoved(v, seats, k);
         long since = now - k.drawnNs;
         boolean due = k.drawnAt < 0L || turned || zoom != k.zoom || drawn != k.people || since >= MIN_PERIOD || f.serial - k.drawnAt > 120
               || since >= period - 500_000L && (moved || period == 0L);
         if (!due) {
            reused++;
            continue;
         }
         why[k.drawnAt < 0L ? 0 : turned ? 1 : zoom != k.zoom ? 2 : drawn != k.people ? 3 : since >= MIN_PERIOD ? 4 : 5]++;
         savePose(v, seats, k);
         if (turned && Config.INSTRUMENT && why[1] % 3000 == 1) {
            Log.info(String.format(java.util.Locale.ROOT, "car occupant: dev turned: q now %.5f %.5f %.5f %.5f, then %.5f %.5f %.5f %.5f, frames %d, angle y %.2f", Q.x, Q.y, Q.z, Q.w, k.q[0], k.q[1], k.q[2], k.q[3], f.serial - k.drawnAt, v.getAngleY()));
         }
         k.drawnAt = f.serial;
         k.drawnNs = now;
         k.q[0] = Q.x;
         k.q[1] = Q.y;
         k.q[2] = Q.z;
         k.q[3] = Q.w;
         k.zoom = zoom;
         k.people = drawn;
         if (!sec) {
            sec = true;
            GpuSections.begin(CarGlass.section("carOccupant"));
         }
         Begin b = row[k.slot];
         b.set(v, k.slot, f.serial, playerIndex, (f.skip & 4096) != 0 ? 50 : Config.CAR_OCCUPANT_SCALE_PCT);
         for (int i = 0; i < seats; i++) {
            IsoGameCharacter c = v.getCharacter(i);
            if (drawable(c, v)) {
               b.seatRadius(v, i);
            }
         }
         SpriteRenderer.instance.drawGeneric(b);
         for (int i = 0; i < seats; i++) {
            IsoGameCharacter c = v.getCharacter(i);
            if (drawable(c, v)) {
               c.checkUpdateModelTextures();
               c.legsSprite.renderActiveModel(); // the camera drawer + the model: TextureDraw hands the model to capture()
            }
         }
         SpriteRenderer.instance.drawGeneric(END);
      }
      if (sec) {
         GpuSections.end(CarGlass.section("carOccupant"));
      }
      if (++frames % 1200L == 130L) {
         Log.info(stats());
      }
   }

   /** A car's tile slot, kept while the car is on screen (freed 120 frames after it was last seen), and what its tile shows. */
   static final class Keep {
      int slot, people;
      long seen, drawnAt = -1L, drawnNs;
      float zoom;
      final float[] q = new float[4];
      float[] pose = new float[0]; // the occupants' bone positions when the tile was drawn
   }

   private static final long MIN_PERIOD = 1_000_000_000L / Math.max(1, Config.CAR_OCCUPANT_MIN_HZ);
   private static final float POSE_EPS = Config.CAR_OCCUPANT_POSE_EPS_PCT / 100F;

   /** Has any occupant bone moved more than carOccupantPoseEpsPct (model units) since the tile was drawn? */
   private static boolean poseMoved(BaseVehicle v, int seats, Keep k) {
      int o = 0;
      for (int i = 0; i < seats; i++) {
         IsoGameCharacter c = v.getCharacter(i);
         if (!drawable(c, v) || c.getAnimationPlayer() == null) {
            continue;
         }
         zombie.core.skinnedmodel.animation.AnimationPlayer ap = c.getAnimationPlayer();
         int n = ap.getModelTransformsCount();
         for (int b = 0; b < n; b++, o += 3) {
            org.lwjgl.util.vector.Matrix4f m = ap.getModelTransformAt(b);
            if (m == null) {
               continue;
            }
            if (o + 3 > k.pose.length || Math.abs(m.m30 - k.pose[o]) > POSE_EPS || Math.abs(m.m31 - k.pose[o + 1]) > POSE_EPS || Math.abs(m.m32 - k.pose[o + 2]) > POSE_EPS) {
               return true;
            }
         }
      }
      return o != k.pose.length;
   }

   private static void savePose(BaseVehicle v, int seats, Keep k) {
      int n = 0;
      for (int i = 0; i < seats; i++) {
         IsoGameCharacter c = v.getCharacter(i);
         if (drawable(c, v) && c.getAnimationPlayer() != null) {
            n += c.getAnimationPlayer().getModelTransformsCount() * 3;
         }
      }
      if (k.pose.length != n) {
         k.pose = new float[n];
      }
      int o = 0;
      for (int i = 0; i < seats; i++) {
         IsoGameCharacter c = v.getCharacter(i);
         if (!drawable(c, v) || c.getAnimationPlayer() == null) {
            continue;
         }
         zombie.core.skinnedmodel.animation.AnimationPlayer ap = c.getAnimationPlayer();
         for (int b = 0, m = ap.getModelTransformsCount(); b < m; b++, o += 3) {
            org.lwjgl.util.vector.Matrix4f t = ap.getModelTransformAt(b);
            if (t != null) {
               k.pose[o] = t.m30;
               k.pose[o + 1] = t.m31;
               k.pose[o + 2] = t.m32;
            }
         }
      }
   }

   // per view (split screen: each player's view keeps its own tiles; the zoom and the density are the view's)
   @SuppressWarnings("unchecked")
   private static final IdentityHashMap<BaseVehicle, Keep>[] KEEP = new IdentityHashMap[] {new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>()};
   private static final boolean[] SLOT_USED = new boolean[SLOTS];
   private static final org.joml.Quaternionf Q = new org.joml.Quaternionf();
   private static long reused;
   private static final long[] why = new long[6]; // new, turned, zoom, people, 30 frames, the refresh period
   private static final float TURN_COS = (float)Math.cos(Math.toRadians(Config.CAR_OCCUPANT_TURN_DEG) / 2.0);

   private static Keep keep(BaseVehicle v, long serial, int player) {
      IdentityHashMap<BaseVehicle, Keep> keepOf = KEEP[player & 3];
      Keep k = keepOf.get(v);
      if (k != null) {
         k.seen = serial;
         return k;
      }
      for (IdentityHashMap<BaseVehicle, Keep> m : KEEP) {
         m.entrySet().removeIf(en -> {
            if (serial - en.getValue().seen > 120) {
               SLOT_USED[en.getValue().slot] = false;
               return true;
            }
            return false;
         });
      }
      for (int i = 0; i < SLOTS; i++) {
         if (!SLOT_USED[i]) {
            SLOT_USED[i] = true;
            k = new Keep();
            k.slot = i;
            k.seen = serial;
            keepOf.put(v, k);
            return k;
         }
      }
      return null;
   }

   private static boolean drawable(IsoGameCharacter c, BaseVehicle v) {
      if (c == null || c.getVehicle() != v || !c.isSeatedInVehicle() || c.legsSprite == null || !c.legsSprite.hasActiveModel()) {
         if (c != null) {
            skippedNoModel++;
         }
         return false;
      }
      if (!c.getDoRender() || c.isSpriteInvisible() || c.isAlphaZero()) {
         return false;
      }
      zombie.core.skinnedmodel.ModelManager.ModelSlot ms = c.legsSprite.modelSlot;
      return ms != null && ms.model != null && ms.model.object == c;
   }

   // ------------------------------------------------------------------------------------------------ render thread

   /** A car's tile this frame (render thread; read by CarGlass.drawUniforms). */
   static final class Tile {
      long serial;
      BaseVehicle v;
      int player; // the view it was drawn for
      float td = 0.5F; // the occupants' targetDepth (the depth offset their draw added)
      int seat = -1; // the first occupant's seat
      final float[] rx = new float[4], ry = new float[4]; // G -> the tile's world NDC x, y (rows)
      float seatDepth; // its seat origin's depth in the tile (the anchor of the glass's depth test)
      float minX, minY, invW, invH; // the cabin's NDC rect in the world view
      float u0, v0, du, dv; // its texels in the atlas (atlas uv)
      int drawn;
   }

   @SuppressWarnings("unchecked")
   private static final IdentityHashMap<BaseVehicle, Tile>[] TILES = new IdentityHashMap[] {new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>()};
   private static final Tile[] TILE_OF_SLOT = new Tile[SLOTS];
   private static int fbo, colorTex, depthTex;
   private static Begin capturing;
   private static int prevFbo;
   private static final OccCamera CAMERA = new OccCamera();
   static final float[] devCentroid = new float[3];
   static final float[] DEV_SAMPLES = new float[3 * 20000]; // dev: the dumped tile's drawn texels (world NDC x, y, depth)
   static int devSampleN; // dev: the dumped tile's drawn centroid (world NDC x, y, window depth)
   static final float[] lastSeatClip = new float[3]; // dev: the last occupant's seat origin, tile clip x, y, world clip z
   private static final Matrix4f PV = new Matrix4f(), TILE_M = new Matrix4f();
   private static final Vector4f V4 = new Vector4f();

   private static final IdentityHashMap<BaseVehicle, Matrix4f> CHASSIS = new IdentityHashMap<>();

   /**
    * Render thread, CarGlass.drawUniforms (the car's glass draw, its model view on the stack): the chassis frame G in the
    * frame Core.DoPushIsoStuff sets up for the car's position (the camera base both draws start from), kept for the next
    * frame's occupant draw (the seats are placed in G, calibrated by the glass; stock's own inVehicle placement puts the
    * model at the wrong end of the car).
    */
   static void chassis(BaseVehicle v, Matrix4f gToEye) {
      ModelCamera cam = ModelCamera.instance;
      if (cam == null) {
         return;
      }
      Core core = Core.getInstance();
      core.DoPushIsoStuff(cam.x, cam.y, cam.z, cam.useAngle, true);
      Matrix4f base = core.modelViewMatrixStack.peek();
      Matrix4f m = CHASSIS.get(v);
      if (m == null) {
         if (CHASSIS.size() > 64) {
            CHASSIS.clear();
         }
         CHASSIS.put(v, m = new Matrix4f());
      }
      m.set(base).invert().mul(gToEye);
      core.projectionMatrixStack.pop();
      core.modelViewMatrixStack.pop();
   }

   /** Render thread, CarGlass.drawUniforms: this car's occupant tile drawn this frame, or null. */
   static Tile tileFor(BaseVehicle v, long serial, int player) {
      if (failed || colorTex == 0) {
         return null;
      }
      Tile t = TILES[player & 3].get(v);
      return t != null && t.v == v && t.player == player && serial - t.serial <= 120 && t.drawn > 0 ? t : null;
   }

   /** Render thread: binds the atlas for the glass's reads (colour, depth). */
   static void bindAtlas() {
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + COLOR_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }

   private static boolean ensureAtlas() {
      if (fbo != 0) {
         return true;
      }
      colorTex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, ATLAS, ATLAS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      depthTex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, ATLAS, ATLAS, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, (java.nio.ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      zombie.core.textures.Texture.lastTextureID = -1;
      fbo = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);
      int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
      if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
         Log.warn("car occupant: atlas framebuffer incomplete (" + status + "), off");
         failed = true;
         return false;
      }
      Log.info("car occupant: impostor atlas " + ATLAS + "x" + ATLAS + " (" + SLOTS + " cars of " + SLOT + "^2)");
      return true;
   }

   /** Starts a car's tile: the cabin's screen rect from the world camera, the atlas bound, the tile cleared. */
   static final class Begin extends TextureDraw.GenericDrawer {
      BaseVehicle v;
      int slot, player, scalePct;
      long serial;
      float x, y, z, r;

      void set(BaseVehicle v, int slot, long serial, int player, int scalePct) {
         this.v = v;
         this.slot = slot;
         this.serial = serial;
         this.player = player;
         this.scalePct = scalePct;
         this.x = v.getX();
         this.y = v.getY();
         this.z = v.getZ();
         this.r = 0F;
      }

      /** Game thread: the box round the cabin reaches this seat (its distance from the car's centre + an arm's length). */
      void seatRadius(BaseVehicle v, int seat) {
         zombie.scripting.objects.VehicleScript.Passenger p = v.getScript() == null ? null : v.getScript().getPassenger(seat);
         zombie.scripting.objects.VehicleScript.Position pos = p == null ? null : p.getPositionById("inside");
         float d = 1.2F;
         if (pos != null) {
            org.joml.Vector3f o = pos.getOffset();
            org.joml.Vector3f mo = v.getScript().getModel().getOffset();
            float sx = o.x + mo.x, sz = o.z + mo.z;
            d = (float)Math.sqrt(sx * sx + sz * sz);
         }
         this.r = Math.max(this.r, Math.min(3.5F, d + 0.75F));
      }

      @Override
      public void render() {
         if (failed) {
            return;
         }
         beginNs = System.nanoTime();
         try {
            begin(this);
            setupNs += System.nanoTime() - beginNs;
         } catch (Throwable t) {
            failed = true;
            capturing = null;
            Log.warn("car occupant: begin failed, off: " + t);
         }
      }
   }

   static final class End extends TextureDraw.GenericDrawer {
      @Override
      public void render() {
         if (capturing == null) {
            return;
         }
         Begin b = capturing;
         capturing = null;
         long e0 = System.nanoTime();
         try {
            if (Config.DEV_CAR_OCCUPANT_DUMP > 0 && tiles == Config.DEV_CAR_OCCUPANT_DUMP) {
               dump(b);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glPopAttrib();
            GL11.glDepthRange(0.0, 1.0);
            zombie.core.textures.Texture.lastTextureID = -1;
            passNs += System.nanoTime() - beginNs;
            endNs += System.nanoTime() - e0;
         } catch (Throwable t) {
            failed = true;
            Log.warn("car occupant: end failed, off: " + t);
         }
      }
   }

   private static void begin(Begin b) {
      if (!ensureAtlas()) {
         return;
      }
      // the cabin box (r squares round the car's centre, the floor to above a seated head) through the world camera
      Core core = Core.getInstance();
      core.DoPushIsoStuff(b.x, b.y, b.z, 0F, true);
      PV.set(core.projectionMatrixStack.peek()).mul(core.modelViewMatrixStack.peek());
      if (Config.INSTRUMENT && tiles % 600 == 5) {
         PV.transform(V4.set(0F, 0F, 0F, 1F));
         Log.info("car occupant: begin, the car's origin at ndc " + V4.x / V4.w + "," + V4.y / V4.w + " z " + V4.z / V4.w + " (" + b.x + "," + b.y + "," + b.z + ")");
      }
      core.projectionMatrixStack.pop();
      core.modelViewMatrixStack.pop();
      GL11.glDepthRange(0.0, 1.0);
      float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
      float r = b.r > 0F ? b.r : 1.5F;
      for (int i = 0; i < 8; i++) {
         PV.transform(V4.set((i & 1) == 0 ? -r : r, (i & 2) == 0 ? -0.6F : 3.0F, (i & 4) == 0 ? -r : r, 1F));
         float nx = V4.x / V4.w, ny = V4.y / V4.w;
         minX = Math.min(minX, nx);
         maxX = Math.max(maxX, nx);
         minY = Math.min(minY, ny);
         maxY = Math.max(maxY, ny);
      }
      if (maxX <= minX || maxY <= minY || maxX < -1F || minX > 1F || maxY < -1F || minY > 1F) {
         return;
      }
      // texels: the world view's own density (its viewport over 2 NDC units) x carOccupantScalePct, at most a slot
      float vw = CarGlass.viewportW(), vh = CarGlass.viewportH();
      int tw = Math.max(16, Math.min(SLOT, (int)Math.ceil((maxX - minX) * 0.5F * vw * b.scalePct / 100F)));
      int th = Math.max(16, Math.min(SLOT, (int)Math.ceil((maxY - minY) * 0.5F * vh * b.scalePct / 100F)));
      int ox = (b.slot % (ATLAS / SLOT)) * SLOT, oy = (b.slot / (ATLAS / SLOT)) * SLOT;
      Tile t = TILE_OF_SLOT[b.slot];
      if (t == null) {
         t = TILE_OF_SLOT[b.slot] = new Tile();
      }
      t.serial = b.serial;
      t.v = b.v;
      t.player = b.player;
      // the car's chassis origin on screen now: the glass shifts the tile by how far it has moved when it reads it later
      // G -> the world NDC the tile was drawn in (rows x, y): the glass maps its chassis position through it, so the tile
      // follows the car however it moved or turned since (the occupant is rigid with the chassis between refreshes)
      Matrix4f ch = CHASSIS.get(b.v);
      if (ch != null) {
         TILE_M.set(PV).mul(ch);
         t.rx[0] = TILE_M.m00(); t.rx[1] = TILE_M.m10(); t.rx[2] = TILE_M.m20(); t.rx[3] = TILE_M.m30();
         t.ry[0] = TILE_M.m01(); t.ry[1] = TILE_M.m11(); t.ry[2] = TILE_M.m21(); t.ry[3] = TILE_M.m31();
      }
      t.minX = minX;
      t.minY = minY;
      t.invW = 1F / (maxX - minX);
      t.invH = 1F / (maxY - minY);
      t.u0 = ox / (float)ATLAS;
      t.v0 = oy / (float)ATLAS;
      t.du = tw / (float)ATLAS;
      t.dv = th / (float)ATLAS;
      t.drawn = 0;
      t.seat = -1;
      IdentityHashMap<BaseVehicle, Tile> tilesOf = TILES[b.player & 3];
      for (IdentityHashMap<BaseVehicle, Tile> m : TILES) {
         m.entrySet().removeIf(en -> en.getValue() == TILE_OF_SLOT[b.slot] && en.getKey() != b.v); // (the slot changed hands)
      }
      tilesOf.put(b.v, t);
      // world NDC -> tile NDC: x' = (x - min) * 2 / (max - min) - 1
      float sx = 2F / (maxX - minX), sy = 2F / (maxY - minY);
      TILE_M.translation(-1F - minX * sx, -1F - minY * sy, 0F).scale(sx, sy, 1F);
      CAMERA.tile.set(TILE_M);
      prevFbo = ShadowAtlas.worldFbo();
      GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT | GL11.GL_SCISSOR_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_ENABLE_BIT);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL11.glViewport(ox, oy, tw, th);
      GL11.glEnable(GL11.GL_SCISSOR_TEST);
      GL11.glScissor(ox, oy, tw, th);
      GL11.glColorMask(true, true, true, true);
      GL11.glDepthMask(true);
      GL11.glClearColor(0F, 0F, 0F, 0F);
      GL11.glClearDepth(1.0);
      GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      CAMERA.vx = ox;
      CAMERA.vy = oy;
      CAMERA.vw = tw;
      CAMERA.vh = th;
      capturing = b;
      tiles++;
   }

   /**
    * Render thread, TextureDraw's DrawModel: a seated character's model queued by {@link #queue} goes into the car's tile
    * (true: drawn here, the world draw skipped).
    */
   private static long markNs;

   /** Render thread, TextureDraw's DrawModel before its init wait (dev timing of the occupants' draws). */
   public static void markDrawModel() {
      if (capturing != null) {
         markNs = System.nanoTime();
      }
   }

   public static boolean capture(TextureDraw texd) {
      Begin b = capturing;
      if (b != null && markNs != 0L) {
         waitNs += System.nanoTime() - markNs;
         markNs = 0L;
      }
      if (b == null || !(texd.drawer instanceof ModelSlotRenderData slot) || slot.character == null || !slot.inVehicle) {
         return false;
      }
      Tile t = TILE_OF_SLOT[b.slot];
      ModelCamera cam = ModelCamera.instance;
      if (cam == null || t == null || (CarGlass.skip() & 16384) != 0) {
         return true; // queued for the tile: never into the world (dev bit 16384: the pass without the model draws)
      }
      float sd = slot.squareDepth;
      Matrix4f g = slot.character.getVehicle() != null ? CHASSIS.get(slot.character.getVehicle()) : null;
      BaseVehicle cv = slot.character.getVehicle();
      if (g == null || cv == null || cv.getScript() == null) {
         return true; // no chassis frame yet (the car's first glass draw comes later this frame): nothing this frame
      }
      CarGlass.CabinData cd = CarGlass.cabinData(cv.getScript());
      int seat = cv.getSeat(slot.character), si = -1;
      for (int i = 0; i < cd.n; i++) {
         if (cd.index[i] == seat) {
            si = i;
         }
      }
      if (si < 0) {
         return true;
      }
      CAMERA.g.set(g);
      CAMERA.seat.set(cd.seat[si * 3], cd.seat[si * 3 + 1] - Config.CAR_GLASS_SEAT_HIP_PCT / 100F + Config.DEV_CAR_OCCUPANT_Y_PCT / 100F, cd.seat[si * 3 + 2]);
      boolean inVehicle = slot.inVehicle;
      try {
         slot.inVehicle = false; // Model.CharacterModelCameraBegin adds nothing: the camera places the seat
         // (a model texture made in between may have bound its own framebuffer: the tile again)
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GL11.glViewport(CAMERA.vx, CAMERA.vy, CAMERA.vw, CAMERA.vh);
         CAMERA.from(cam, slot);
         ModelCamera.instance = CAMERA;
         long r0 = System.nanoTime();
         synchronized (slot) {
            slot.render();
         }
         renderNs += System.nanoTime() - r0;
         t.drawn++;
         if (slot.modelSlot != null && slot.modelSlot.model != null) {
            t.td = slot.modelSlot.model.targetDepth; // what the draw used (0.5 unless the seat's origin was missed)
         }
         if (t.drawn == 1 && slot.character != null && slot.character.getVehicle() != null) {
            t.seat = slot.character.getVehicle().getSeat(slot.character);
            t.seatDepth = (lastSeatClip[2] + 1F) / 2F;
         }
         captured++;
      } finally {
         slot.inVehicle = inVehicle;
         slot.squareDepth = sd;
         ModelCamera.instance = cam;
      }
      return true;
   }

   /**
    * The world camera of a seated character with its projection zoomed onto the tile (x, y only), and the seat's depth
    * offset made 0.5: the depths in the tile are the world view's plain projected ones.
    */
   static final class OccCamera extends ModelCamera {
      final Matrix4f tile = new Matrix4f();
      final Matrix4f g = new Matrix4f(); // the chassis frame G in the camera base (CarOccupant.chassis)
      final org.joml.Vector3f seat = new org.joml.Vector3f(); // the seat's origin in G
      private final Matrix4f scratch = new Matrix4f();
      ModelSlotRenderData slot;
      int vx, vy, vw, vh;

      void from(ModelCamera c, ModelSlotRenderData slot) {
         this.x = c.x;
         this.y = c.y;
         this.z = c.z;
         this.useAngle = c.useAngle;
         this.useWorldIso = true;
         this.inVehicle = c.inVehicle;
         this.depthMask = true;
         this.slot = slot;
      }

      @Override
      public void Begin() {
         Core core = Core.getInstance();
         core.DoPushIsoStuff(this.x, this.y, this.z, this.useAngle, true);
         Matrix4f p = core.projectionMatrixStack.peek();
         p.mulLocal(this.tile);
         // the seat in the chassis frame G (the glass's calibrated cabin), the character upright in it facing forward
         Matrix4f mv = core.modelViewMatrixStack.peek();
         mv.mul(this.g).translate(this.seat).rotateY((float)Math.PI + Config.DEV_CAR_OCCUPANT_TURN_DEG * 0.017453292F)
               .scale(Config.DEV_CAR_OCCUPANT_MIRROR ? -1.5F : 1.5F, 1.5F, 1.5F);
         ModelSlotRenderData s = this.slot;
         if (s != null) {
            // the seat's origin's clip z: the character's targetDepth = squareDepth - (z + 1) / 2 + 0.5 - 1e-4 becomes 0.5
            Matrix4f m = this.scratch.set(p).mul(mv);
            float oz = m.m32() / m.m33();
            lastSeatClip[0] = m.m30() / m.m33();
            lastSeatClip[1] = m.m31() / m.m33();
            lastSeatClip[2] = oz;
            s.squareDepth = (oz + 1F) / 2F + 1.0E-4F;
         }
         GL11.glDepthRange(0.0, 1.0);
         GL11.glDepthMask(true);
      }

      @Override
      public void End() {
         Core.getInstance().DoPopIsoStuff();
      }
   }

   /** dev (devCarOccupantDump=N): the N-th tile read back (a sync) to ~/Zomboid/pzopt-occupant-tile.png, its depth range logged. */
   private static void dump(Begin b) {
      int w = CAMERA.vw, h = CAMERA.vh;
      java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
      java.nio.FloatBuffer dp = org.lwjgl.BufferUtils.createFloatBuffer(w * h);
      GL11.glReadPixels(CAMERA.vx, CAMERA.vy, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
      GL11.glReadPixels(CAMERA.vx, CAMERA.vy, w, h, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, dp);
      java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
      int covered = 0;
      float mn = 1F, mx = 0F;
      double cu = 0, cv = 0, cd = 0;
      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 4;
            int a = px.get(i + 3) & 255;
            im.setRGB(x, h - 1 - y, (a << 24) | ((px.get(i) & 255) << 16) | ((px.get(i + 1) & 255) << 8) | (px.get(i + 2) & 255));
            float d = dp.get(y * w + x);
            if (d < 1F) {
               covered++;
               cu += (x + 0.5) / w;
               cv += (y + 0.5) / h;
               cd += d;
               if (devSampleN < DEV_SAMPLES.length / 3 && ((x ^ y) & 3) == 0) {
                  Tile tt = TILE_OF_SLOT[b.slot];
                  DEV_SAMPLES[devSampleN * 3] = (float)(tt.minX + (x + 0.5) / w / tt.invW);
                  DEV_SAMPLES[devSampleN * 3 + 1] = (float)(tt.minY + (y + 0.5) / h / tt.invH);
                  DEV_SAMPLES[devSampleN * 3 + 2] = d;
                  devSampleN++;
               }
               mn = Math.min(mn, d);
               mx = Math.max(mx, d);
            }
         }
      }
      try {
         java.io.File f = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-occupant-tile.png");
         javax.imageio.ImageIO.write(im, "png", f);
         Tile t = TILE_OF_SLOT[b.slot];
         if (covered > 0) {
            // the drawn texels' centroid: world NDC x, y and window depth (the occupant's own depth terms)
            devCentroid[0] = (float)(t.minX + cu / covered / t.invW);
            devCentroid[1] = (float)(t.minY + cv / covered / t.invH);
            devCentroid[2] = (float)(cd / covered);
         }
         Log.info(String.format(java.util.Locale.ROOT, "car occupant: dev tile %dx%d at %d,%d -> %s: %d texels drawn, depth %.6f..%.6f, ndc rect %.4f,%.4f 1/size %.3f,%.3f, %d draws",
               w, h, CAMERA.vx, CAMERA.vy, f, covered, mn, mx, t.minX, t.minY, t.invW, t.invH, t.drawn));
      } catch (Throwable e) {
         Log.warn("car occupant: dev dump failed: " + e);
      }
   }

   public static String stats() {
      return "car occupant: mode " + MODE + ", " + tiles + " tiles (" + reused + " frames shifted the last one; drawn for: new " + why[0] + ", turned " + why[1] + ", zoom " + why[2] + ", people " + why[3] + ", the slowest refresh " + why[4] + ", the pose " + why[5] + "), " + captured + " occupant draws, " + skippedNoModel + " seated without a model, render thread "
            + (tiles > 0 ? String.format(java.util.Locale.ROOT, "%.1f (setup %.1f, init wait %.1f, the models %.1f, end %.1f)", passNs / 1000.0 / tiles, setupNs / 1000.0 / tiles, waitNs / 1000.0 / tiles, renderNs / 1000.0 / tiles, endNs / 1000.0 / tiles) : "-") + " us a tile" + (failed ? ", FAILED" : "");
   }
}
