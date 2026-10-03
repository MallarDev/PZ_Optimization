package pzopt;

import zombie.characters.AttachedItems.AttachedItem;
import zombie.characters.AttachedItems.AttachedItems;
import zombie.characters.AttachedItems.AttachedLocation;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.Vector3;
import zombie.scripting.objects.ModelAttachment;

/**
 * torchSource (2026-10-03, Enhancements tab): a light a character carries shines from the item, not from the character's
 * feet. Stock places every handheld light at the holder's position (TorchInfo.set: the player's x, y, z) and the renderers
 * guess its height (pixelLight 0.55 levels, the capsule shadows 1.35 squares, the god ray airlight 0.45 levels). Here the
 * light sits at the lens of the drawn model: the item's model instance (the hand prop, the webbing attachment, a weapon's
 * light part) is placed exactly as the game draws it (AnimatedModel.transformToParent: the parent's bone, the parent's
 * attachment, the item's own attachment, the mesh transform, the scale) and mapped to the world as the character's bones
 * are (Model.vectorToWorldCoords); the lens is the mesh box's support point along the beam (a hand torch's front end, the
 * angle head's front face, a lantern's middle for a light without a cone).
 *
 * <p>Three consumers, three positions:
 * <ul>
 *   <li>the native lighting (per square, what the gameplay sees) gets the lens's x / y in {@code TorchInfo.x / y}, kept off
 *       the far side of a wall or a closed door / window of the holder's square ({@code torchSourceWallClamp}) and held while
 *       the holder stands still and the lens moves less than {@code torchSourceHold} hundredths of a square (an idle pose's
 *       hand sway would re-light the torch's squares every frame: lighting re-bakes or pixelLight lattice packs for a light
 *       that does not visibly move);</li>
 *   <li>the per-pixel consumers (pixelLight's beam and its shadow mask, the torch capsule shadows, the god ray airlight)
 *       read the exact lens ({@link #x}, {@link #y}, {@link #z}): recomputed once a frame at render time
 *       ({@code torchSourceFresh}: the pose the frame draws, not the previous frame's the native was handed);</li>
 *   <li>the beam's direction stays the look vector ({@code torchSourceAim=look}, stock's, what the player aims) or follows
 *       the item's own axis ({@code item}).</li>
 * </ul>
 * Cost: a few matrix products per carried light per frame on the game thread (~1-3 us), nothing on the GPU (the shaders
 * read the height they used to assume).
 */
public final class TorchSource {
   private TorchSource() {
   }

   private static final org.joml.Matrix4f M = new org.joml.Matrix4f();
   private static final org.joml.Matrix4f TMP = new org.joml.Matrix4f();
   private static final org.joml.Vector3f C = new org.joml.Vector3f();
   private static final org.joml.Vector3f AX = new org.joml.Vector3f();
   private static final Vector3 W = new Vector3();
   private static final Vector3 W2 = new Vector3();
   private static final float[] OUT = new float[7]; // lens x y z (world squares / levels), beam axis x y z (world, unit), 1 when the axis is valid

   public static long calls, fails, nanos, frames;
   private static int frameStamp = -1;
   private static int logCountdown = 600;

   /** torchSource on for this frame (devGtAlternate / devTorchSourceCycle can switch it per frame). */
   public static boolean on() {
      return cycleN > 0 ? vOn : Config.TORCH_SOURCE && GtAb.on(GtAb.TORCH_SOURCE);
   }

   private static int hold() {
      return cycleN > 0 ? vHold : Config.TORCH_SOURCE_HOLD;
   }

   private static boolean aimItem() {
      return cycleN > 0 ? vAimItem : "item".equals(Config.TORCH_SOURCE_AIM);
   }

   private static boolean fresh() {
      return cycleN > 0 ? vFresh : Config.TORCH_SOURCE_FRESH;
   }

   private static boolean clamp() {
      return cycleN > 0 ? vClamp : Config.TORCH_SOURCE_WALL_CLAMP;
   }

   // ---- devTorchSourceCycle=v1,v2,... (dev, 2026-10-03): the variants take turns every devTorchSourcePeriod ms in one run, each
   // ---- frame accounted to its variant (the first 300 ms after a switch left out): frame time, game-thread CPU, the solve's
   // ---- cost, the lighting work a moving light causes (pixelLight lattice blocks packed, chunk bakes, strong re-bake marks)
   // variants: off (stock position), on (defaults), hold0 (the native follows every sway), item (aim along the item), stale
   // (no render-time re-solve), noclamp (no wall clamp), slow (the game's helpers every solve: no cached chain), nobody (no
   // carrier body for torchSourceSelfShadow)
   private static final String[] CYCLE = Config.DEV_TORCH_SOURCE_CYCLE.isEmpty() ? new String[0] : Config.DEV_TORCH_SOURCE_CYCLE.split(",");
   private static final int cycleN = CYCLE.length;
   private static boolean vOn, vAimItem, vFresh = true, vClamp = true, vFast = true, vBody = true;

   /** torchSourceSelfShadow's body this frame (the cycle's nobody variant sends none: the occluder's own GPU cost). */
   public static boolean body() {
      return cycleN == 0 || vBody;
   }
   private static int vHold = 15;
   private static long cycleT0, lastFrameNs, lastCpu, lastBlocks, lastBakes, lastMarks, lastSolveNs;
   private static int phase = -1;
   private static final long[][] ACC = new long[cycleN][7]; // frames, wall ns, cpu ns, solve ns, blocks, bakes, marks
   private static final float[][] FT = new float[cycleN][20000];
   private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();

   /** Game thread, every frame start (Pacing.stepStart): the cycle's variant for this frame, the last frame's accounting. */
   public static void frameStart() {
      if (cycleN == 0) {
         return;
      }
      if (IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
         return;
      }
      long now = System.nanoTime();
      long cpu = THREADS.getCurrentThreadCpuTime();
      long blocks = PixelLight.blocksPacked(), bakes = zombie.iso.fboRenderChunk.FBORenderCell.pzoptBakesCumulative, marks = LightDirt.strongMarks;
      if (cycleT0 == 0L) {
         cycleT0 = now;
         Log.info("torch source: cycling " + Config.DEV_TORCH_SOURCE_CYCLE + " every " + Config.DEV_TORCH_SOURCE_PERIOD + " ms from epoch_ms " + System.currentTimeMillis());
      } else if (phase >= 0 && (now - phaseT0) > 300_000_000L && now - cycleT0 >= Math.max(100L, Config.DEV_TORCH_SOURCE_PERIOD) * 1_000_000L * cycleN) {
         // (the first full cycle is left out: the JIT and the streaming settle in it, which favoured the later variants)
         long[] a = ACC[phase];
         int k = (int)a[0];
         if (k < FT[phase].length) {
            FT[phase][k] = (now - lastFrameNs) / 1e6F;
         }
         a[0]++;
         a[1] += now - lastFrameNs;
         a[2] += cpu - lastCpu;
         a[3] += nanos - lastSolveNs;
         a[4] += blocks - lastBlocks;
         a[5] += bakes - lastBakes;
         a[6] += marks - lastMarks;
      }
      lastFrameNs = now;
      lastCpu = cpu;
      lastBlocks = blocks;
      lastBakes = bakes;
      lastMarks = marks;
      lastSolveNs = nanos;
      long period = Math.max(100L, Config.DEV_TORCH_SOURCE_PERIOD) * 1_000_000L;
      int ph = (int)((now - cycleT0) / period % cycleN);
      if (ph != phase) {
         if (phase >= 0 && ph == 0) {
            Log.info("torch source cycle: " + cycleReport());
         }
         phase = ph;
         phaseT0 = now;
         String v = CYCLE[ph].trim();
         vOn = !v.equals("off");
         vHold = v.equals("hold0") ? 0 : Config.TORCH_SOURCE_HOLD;
         vAimItem = v.equals("item");
         vFresh = !v.equals("stale");
         vClamp = !v.equals("noclamp");
         vFast = !v.equals("slow");
         vBody = !v.equals("nobody");
         Log.info("torch source cycle: variant " + v + " epoch_ms=" + System.currentTimeMillis());
      }
   }

   private static long phaseT0;

   /** Per variant: frames, mean / p99 frame ms, game-thread CPU us a frame, solve us a frame, lattice blocks, bakes, strong marks a frame. */
   public static String cycleReport() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < cycleN; i++) {
         long[] a = ACC[i];
         if (a[0] == 0) {
            continue;
         }
         int n = (int)Math.min(a[0], FT[i].length);
         float[] ft = java.util.Arrays.copyOf(FT[i], n);
         java.util.Arrays.sort(ft);
         double f = a[0];
         sb.append(String.format(java.util.Locale.ROOT, "%s[frames=%d mean_ms=%.3f p99_ms=%.3f gt_cpu_us=%.1f solve_us=%.2f blocks=%.2f bakes=%.3f marks=%.2f] ",
            CYCLE[i].trim(), a[0], a[1] / 1e6 / f, ft[Math.min(n - 1, (int)(n * 0.99))], a[2] / 1e3 / f, a[3] / 1e3 / f, a[4] / f, a[5] / f, a[6] / f));
      }
      return sb.toString().trim();
   }

   /** The light's x for the per-pixel consumers: the lens, or the native position (stock: the holder). */
   public static float x(IsoGameCharacter.TorchInfo t) {
      return t.pzoptSrc ? t.pzoptLx : t.x;
   }

   public static float y(IsoGameCharacter.TorchInfo t) {
      return t.pzoptSrc ? t.pzoptLy : t.y;
   }

   /**
    * torchSourcePitch (with torchSourceAim=item): the per-pixel beam's reach, shortened where the item points down: a lens h
    * squares above the floor tilted down by a slope s lights the ground out to about h / s (plus the spill past it); the
    * native keeps the item's reach (what the game lights stays the same).
    */
   public static float reach(IsoGameCharacter.TorchInfo t, float reach) {
      if (!t.pzoptSrc || t.pzoptOut == null || t.pzoptOut[6] < 0.5F || !aimItem() || !Config.TORCH_SOURCE_PITCH) {
         return reach;
      }
      float[] o = t.pzoptOut;
      float flat = (float)Math.sqrt(o[3] * o[3] + o[4] * o[4]);
      float down = -o[5] * METRIC_Z / Math.max(flat, 1e-3F); // squares down per square out
      if (down < 0.05F) {
         return reach;
      }
      float h = Math.max(0.05F, (t.pzoptLz - t.z) * METRIC_Z);
      return Math.max(1.0F, Math.min(reach, h / down + 0.35F * reach));
   }

   private static final float METRIC_Z = 2.4494897F; // squares of height per level

   /** The light's height above {@code t.z} in levels: the lens's, or the caller's assumption {@code def}. */
   public static float height(IsoGameCharacter.TorchInfo t, float def) {
      return t.pzoptSrc ? t.pzoptLz - t.z : def;
   }

   /**
    * TorchInfo.set(IsoPlayer, InventoryItem), after the stock body (game thread, LightingJNI.checkLights): the lens, the
    * position the native gets, the direction.
    */
   public static void onSet(IsoGameCharacter.TorchInfo t, IsoPlayer p, InventoryItem item) {
      boolean sameHolder = t.pzoptHolder == p && t.pzoptItem == item;
      t.pzoptHolder = p;
      t.pzoptItem = item;
      t.pzoptPart = null;
      if (!on()) {
         t.pzoptSrc = false;
         t.pzoptHoldValid = false;
         return;
      }
      long t0 = System.nanoTime();
      if (sameHolder && t.pzoptSrc && t.pzoptFrame == frameNo() && t.pzoptOut != null) {
         System.arraycopy(t.pzoptOut, 0, OUT, 0, 7); // this frame's render already solved the same pose
         reused++;
      } else {
         calls++;
         if (!solve(p, item)) {
            fails++;
            t.pzoptSrc = false;
            t.pzoptHoldValid = false;
            nanos += System.nanoTime() - t0;
            return;
         }
      }
      float hx = t.x, hy = t.y, hz = t.z; // the stock position: the holder
      t.pzoptSrc = true;
      t.pzoptLx = OUT[0];
      t.pzoptLy = OUT[1];
      t.pzoptLz = OUT[2];
      t.pzoptFrame = frameNo();
      aim(t);
      if (Config.DEV_TORCH_SOURCE_PUSH != 0) { // dev: the lens pushed along the look (forces a lens across a wall: the clamp rig)
         float ll = (float)Math.hypot(t.angleX, t.angleY);
         if (ll > 1e-4F) {
            OUT[0] += t.angleX / ll * Config.DEV_TORCH_SOURCE_PUSH / 100F;
            OUT[1] += t.angleY / ll * Config.DEV_TORCH_SOURCE_PUSH / 100F;
            t.pzoptLx = OUT[0];
            t.pzoptLy = OUT[1];
         }
      }
      // the native's position: the lens, not across a wall of the holder's square
      float nx = OUT[0], ny = OUT[1];
      if (clamp()) {
         float bx = OUT[0], by = OUT[1];
         clampWalls(hx, hy, hz);
         if (OUT[0] != bx || OUT[1] != by) {
            clamped++;
         }
         nx = OUT[0];
         ny = OUT[1];
      }
      // held while the holder stands still and the lens sways less than torchSourceHold
      float hold = hold() / 100F;
      if (hold > 0F && sameHolder && t.pzoptHoldValid && hx == t.pzoptHx && hy == t.pzoptHy && hz == t.pzoptHz
         && Math.abs(t.angleX - t.pzoptHax) < 1e-3F && Math.abs(t.angleY - t.pzoptHay) < 1e-3F
         && Math.abs(nx - t.pzoptNx) < hold && Math.abs(ny - t.pzoptNy) < hold) {
         nx = t.pzoptNx;
         ny = t.pzoptNy;
         held++;
      } else {
         t.pzoptNx = nx;
         t.pzoptNy = ny;
         t.pzoptHx = hx;
         t.pzoptHy = hy;
         t.pzoptHz = hz;
         t.pzoptHax = t.angleX;
         t.pzoptHay = t.angleY;
         t.pzoptHoldValid = true;
      }
      t.x = nx;
      t.y = ny;
      nanos += System.nanoTime() - t0;
   }

   public static long held, reused, clamped;

   /** FBORenderCell, ahead of the chunk composite: the frame's re-solve in every mode (pixelLight or not). */
   public static void renderFrame() {
      if (Config.TORCH_SOURCE || cycleN > 0) {
         refresh(zombie.iso.LightingJNI.pzoptTorches());
      }
   }

   /**
    * Render time, game thread, once a frame (the first per-pixel consumer to ask): the lens again from the pose this frame
    * draws (torchSourceFresh). The native keeps the position it was handed.
    */
   public static void refresh(java.util.ArrayList<IsoGameCharacter.TorchInfo> torches) {
      int frame = frameNo();
      if (frame == frameStamp || Thread.currentThread() != zombie.GameWindow.gameThread) {
         return;
      }
      frameStamp = frame;
      frames++;
      boolean fresh = fresh() && on();
      for (int i = 0; i < torches.size(); i++) {
         IsoGameCharacter.TorchInfo t = torches.get(i);
         if (t.id == 0) {
            continue;
         }
         if (t.pzoptPart != null) {
            // a vehicle light: where the car is drawn this frame (vehicleSmooth shows it between two physics steps; the
            // native and TorchInfo hold the last step's)
            if (fresh && Config.TORCH_SOURCE_VEHICLES) {
               long t0 = System.nanoTime();
               vehicleLens(t, t.pzoptPart, frame);
               nanos += System.nanoTime() - t0;
            }
            continue;
         }
         if (!t.pzoptSrc) {
            continue;
         }
         if (fresh && t.pzoptFrame != frame && t.pzoptHolder instanceof IsoPlayer p && t.pzoptItem != null) {
            long t0 = System.nanoTime();
            calls++;
            if (solve(p, t.pzoptItem)) {
               t.pzoptLx = OUT[0];
               t.pzoptLy = OUT[1];
               t.pzoptLz = OUT[2];
               t.pzoptFrame = frame;
               if (t.pzoptOut == null) {
                  t.pzoptOut = new float[7];
               }
               System.arraycopy(OUT, 0, t.pzoptOut, 0, 7);
               if (aimItem()) {
                  aim(t);
               }
            } else {
               fails++;
            }
            nanos += System.nanoTime() - t0;
         }
         if (Config.DEV_TORCH_SOURCE_VIEW) {
            float lx = t.pzoptLx, ly = t.pzoptLy, lz = t.pzoptLz;
            zombie.debug.LineDrawer.DrawIsoCircle(lx, ly, lz, 0.06F, 12, 1F, 1F, 0F, 1F);
            zombie.debug.LineDrawer.DrawIsoLine(lx, ly, lz, lx + t.angleX * 1.5F, ly + t.angleY * 1.5F, lz, 1F, 0.3F, 0F, 1F, 2);
            zombie.debug.LineDrawer.DrawIsoCircle(t.x, t.y, t.z, 0.08F, 12, 0F, 1F, 1F, 1F); // the native's position
         }
      }
      if (--logCountdown <= 0) {
         logCountdown = 3600;
         Log.info("torch source: " + stats());
      }
   }

   private static final org.joml.Vector3f VL = new org.joml.Vector3f();
   public static double vehOff;
   public static float vehOffMax;
   public static long vehN;

   private static void vehicleLens(IsoGameCharacter.TorchInfo t, zombie.vehicles.VehiclePart part, int frame) {
      zombie.vehicles.BaseVehicle v = part.getVehicle();
      zombie.vehicles.VehicleLight light = part.getLight();
      zombie.scripting.objects.VehicleScript script = v == null ? null : v.getScript();
      if (light == null || script == null) {
         return;
      }
      VL.set(light.offset.x * script.getExtents().x / 2.0F, 0.0F, light.offset.y * script.getExtents().z / 2.0F);
      v.getWorldPos(VL, VL);
      if (!(VL.x == VL.x) || Math.abs(VL.x - t.x) > 3F || Math.abs(VL.y - t.y) > 3F) {
         return; // the shown car is never more than a step from the simulated one
      }
      float off = (float)Math.hypot(VL.x - t.x, VL.y - t.y);
      vehOff += off; // how far the drawn car's light is from the step's (the correction torchSourceVehicles makes)
      vehOffMax = Math.max(vehOffMax, off);
      vehN++;
      t.pzoptLx = VL.x;
      t.pzoptLy = VL.y;
      t.pzoptLz = VL.z;
      t.pzoptSrc = true;
      t.pzoptFrame = frame;
      v.getForwardVector(VL);
      if (part.getId().contains("Rear")) {
         VL.negate();
      }
      t.angleX = VL.x; // render-time readers only: LightingJNI hands the native set(part)'s next pass
      t.angleY = VL.z;
   }

   /**
    * torchSourceSelfShadow: whether the carrier's body (a disc of radius r) can be in light t's beam, seen from the lens: the
    * angle between the beam and the carrier, less the body's angular radius, inside the cone (+ its ramp and a margin). A
    * torch held out in front, its carrier behind it, never is: the shader is not asked (bodyCulled counts them).
    */
   static boolean bodyInCone(IsoGameCharacter.TorchInfo t, float lx, float ly, float r) {
      if (!t.cone) {
         return true; // a lantern lights all round: its carrier always shades one side
      }
      float hx = t.pzoptHolder.getX() - lx, hy = t.pzoptHolder.getY() - ly;
      float dh = (float)Math.hypot(hx, hy), dl = (float)Math.hypot(t.angleX, t.angleY);
      r = Math.min(r, 0.7F * dh); // the shader's disc: never round the lamp itself
      if (dh <= 1e-3F || dl < 1e-4F) {
         return true;
      }
      double toBody = Math.acos(Math.max(-1.0, Math.min(1.0, (hx * t.angleX + hy * t.angleY) / (dh * dl))));
      double cone = Math.acos(Math.max(-1.0, Math.min(1.0, t.dot - 0.05)));
      boolean in = toBody - Math.asin(r / dh) < cone + 0.1;
      if (!in) {
         bodyCulled++;
      }
      return in;
   }

   public static long bodyCulled;

   public static String stats() {
      return String.format(java.util.Locale.ROOT, "calls=%d reused=%d fails=%d held=%d clamped=%d mean_us=%.2f frames=%d links=%d check=%d bad=%d max=%.6f vehicle_lights=%d off_mean=%.3f off_max=%.3f body_culled=%d", calls, reused, fails, held, clamped,
         calls > 0 ? nanos / 1000.0 / calls : 0.0, frames, linkBuilds, checks, checkBad, checkMax, vehN, vehN > 0 ? vehOff / vehN : 0.0, vehOffMax, bodyCulled);
   }

   private static int frameNo() {
      return zombie.iso.IsoCamera.frameState.frameCount;
   }

   /** torchSourceAim=item: the beam follows the item's axis (when it is not pointing straight up or down). */
   private static void aim(IsoGameCharacter.TorchInfo t) {
      if (!aimItem() || OUT[6] < 0.5F) {
         return;
      }
      float len = (float)Math.sqrt(OUT[3] * OUT[3] + OUT[4] * OUT[4]);
      if (len > 0.35F) {
         t.angleX = OUT[3] / len;
         t.angleY = OUT[4] / len;
      }
   }

   /**
    * The lens of {@code item} carried by {@code p} into OUT: false when the item has no model drawn (not equipped with a
    * model, the character not set up for drawing yet, the mesh still loading).
    */
   static boolean solve(IsoPlayer p, InventoryItem item) {
      boolean fast = cycleN > 0 ? vFast : Config.TORCH_SOURCE_FAST;
      boolean ok = solve(p, item, fast);
      if (Config.DEV_TORCH_SOURCE_CHECK && ok && fast) {
         // dev: the reference path (the game's own helpers, Model.vectorToWorldCoords) on the same pose
         System.arraycopy(OUT, 0, CHECK, 0, 7);
         if (solve(p, item, false)) {
            float d = 0F;
            for (int i = 0; i < 6; i++) {
               d = Math.max(d, Math.abs(CHECK[i] - OUT[i]));
            }
            checks++;
            checkMax = Math.max(checkMax, d);
            if (d > 2e-3F && ++checkBad <= 5) {
               Log.info("torch source check: fast " + java.util.Arrays.toString(CHECK) + " reference " + java.util.Arrays.toString(OUT));
            }
         }
         System.arraycopy(CHECK, 0, OUT, 0, 7);
      }
      return ok;
   }

   private static final float[] CHECK = new float[7];
   public static long checks, checkBad;
   public static float checkMax;

   private static boolean solve(IsoPlayer p, InventoryItem item, boolean fast) {
      if (p.legsSprite == null) {
         return false;
      }
      ModelManager.ModelSlot slot = p.legsSprite.modelSlot;
      if (slot == null || slot.model == null || slot.character != p) {
         return false;
      }
      AnimationPlayer ap = slot.model.animPlayer;
      if (ap == null || !ap.isReady()) {
         return false;
      }
      ModelInstance mi = find(p, slot, item);
      if (mi == null || mi.model == null) {
         return false;
      }
      ModelMesh mesh = mi.model.mesh;
      if (mesh == null || !mesh.isReady()) {
         return false;
      }
      if (fast) {
         if (!chainFast(mi, M, 0)) {
            return false;
         }
      } else {
         chain(mi, M, 0);
      }
      float angle = ap.getRenderedAngle();
      float px = p.getX(), py = p.getY(), pz = p.getZ();
      float cosA = (float)Math.cos(angle), sinA = (float)Math.sin(angle);
      // the beam's world direction: the look vector (the item's axis is resolved below for torchSourceAim=item)
      zombie.iso.Vector2 look = p.getLookVector(LOOK);
      org.joml.Vector3f mn = mesh.minXyz, mx = mesh.maxXyz;
      boolean box = mn.x <= mx.x && mn.y <= mx.y && mn.z <= mx.z;
      C.set(box ? 0.5F * (mn.x + mx.x) : 0F, box ? 0.5F * (mn.y + mx.y) : 0F, box ? 0.5F * (mn.z + mx.z) : 0F);
      float hx = box ? 0.5F * (mx.x - mn.x) : 0F, hy = box ? 0.5F * (mx.y - mn.y) : 0F, hz = box ? 0.5F * (mx.z - mn.z) : 0F;
      // the box centre and its three half axes in the world
      float cx, cy, cz;
      float ax0 = 0, ay0 = 0, az0 = 0, ax1 = 0, ay1 = 0, az1 = 0, ax2 = 0, ay2 = 0, az2 = 0;
      if (fast) {
         // Model.vectorToWorldCoords with one sin / cos: x' = -x, rotated about y by the rendered angle, (x, z) -> the
         // ground at 1.5 squares a unit, y -> height at 0.6124 levels a unit
         M.transformPosition(C.x, C.y, C.z, AX);
         cx = px + 1.5F * (-AX.x * cosA - AX.z * sinA);
         cy = py + 1.5F * (-AX.x * sinA + AX.z * cosA);
         cz = pz + 0.61237234F * AX.y;
         if (box) {
            float m00 = M.m00() * hx, m01 = M.m01() * hx, m02 = M.m02() * hx; // column 0 x hx: the box's x half axis in model space
            ax0 = 1.5F * (-m00 * cosA - m02 * sinA); ay0 = 1.5F * (-m00 * sinA + m02 * cosA); az0 = 0.61237234F * m01;
            float m10 = M.m10() * hy, m11 = M.m11() * hy, m12 = M.m12() * hy;
            ax1 = 1.5F * (-m10 * cosA - m12 * sinA); ay1 = 1.5F * (-m10 * sinA + m12 * cosA); az1 = 0.61237234F * m11;
            float m20 = M.m20() * hz, m21 = M.m21() * hz, m22 = M.m22() * hz;
            ax2 = 1.5F * (-m20 * cosA - m22 * sinA); ay2 = 1.5F * (-m20 * sinA + m22 * cosA); az2 = 0.61237234F * m21;
         }
      } else {
      toWorld(C.x, C.y, C.z, px, py, pz, angle, W);
      cx = W.x; cy = W.y; cz = W.z;
      if (box) {
         // the half axes as differences about the origin (at the world position a float step is ~0.001 squares: the
         // axis of a 0.05-square box came out 2 % off)
         toWorld(C.x, C.y, C.z, 0F, 0F, 0F, angle, W);
         float ox = W.x, oy = W.y, oz = W.z;
         toWorld(C.x + hx, C.y, C.z, 0F, 0F, 0F, angle, W2);
         ax0 = W2.x - ox; ay0 = W2.y - oy; az0 = W2.z - oz;
         toWorld(C.x, C.y + hy, C.z, 0F, 0F, 0F, angle, W2);
         ax1 = W2.x - ox; ay1 = W2.y - oy; az1 = W2.z - oz;
         toWorld(C.x, C.y, C.z + hz, 0F, 0F, 0F, angle, W2);
         ax2 = W2.x - ox; ay2 = W2.y - oy; az2 = W2.z - oz;
      }
      }
      // the item's long axis (world), signed towards the look: a torch's beam axis
      float l0 = ax0 * ax0 + ay0 * ay0 + az0 * az0, l1 = ax1 * ax1 + ay1 * ay1 + az1 * az1, l2 = ax2 * ax2 + ay2 * ay2 + az2 * az2;
      float ux, uy, uz;
      if (l0 >= l1 && l0 >= l2) {
         ux = ax0; uy = ay0; uz = az0;
      } else if (l1 >= l2) {
         ux = ax1; uy = ay1; uz = az1;
      } else {
         ux = ax2; uy = ay2; uz = az2;
      }
      float ul = (float)Math.sqrt(ux * ux + uy * uy + uz * uz);
      boolean axis = box && ul > 1e-5F;
      if (axis) {
         ux /= ul; uy /= ul; uz /= ul;
         if (ux * look.x + uy * look.y < 0F) {
            ux = -ux; uy = -uy; uz = -uz;
         }
      }
      OUT[3] = ux;
      OUT[4] = uy;
      OUT[5] = uz;
      OUT[6] = axis ? 1F : 0F;
      if (item.isTorchCone() && box) {
         // the lens: the box's support point along the beam (the look on the ground plane, or the item's axis), from the
         // centre: a hand torch's front end, the angle head's front face
         float dx, dy, dz;
         if (aimItem() && axis) {
            dx = ux; dy = uy; dz = uz;
         } else {
            float ll = (float)Math.sqrt(look.x * look.x + look.y * look.y);
            dx = ll > 1e-5F ? look.x / ll : 0F;
            dy = ll > 1e-5F ? look.y / ll : 0F;
            dz = 0F;
         }
         // h(d) = sum |d . a_i| over the half axes, as a distance along d (the world axes are not unit: z is in levels,
         // x / y in squares; the support is taken in that space, as the light's position is)
         float s = Math.abs(dx * ax0 + dy * ay0 + dz * az0) + Math.abs(dx * ax1 + dy * ay1 + dz * az1) + Math.abs(dx * ax2 + dy * ay2 + dz * az2);
         OUT[0] = cx + dx * s;
         OUT[1] = cy + dy * s;
         OUT[2] = cz + dz * s;
      } else {
         OUT[0] = cx;
         OUT[1] = cy;
         OUT[2] = cz;
      }
      if (!(OUT[0] == OUT[0] && OUT[1] == OUT[1] && OUT[2] == OUT[2]) || Math.abs(OUT[0] - px) > 2F || Math.abs(OUT[1] - py) > 2F
         || Math.abs(OUT[2] - pz) > 2F) {
         return false; // NaN or a model drawn elsewhere (a stale slot): the holder's position stays
      }
      return true;
   }

   private static final zombie.iso.Vector2 LOOK = new zombie.iso.Vector2();

   /** A point of the character's model space in the world (Model.vectorToWorldCoords: the bones' mapping). */
   private static void toWorld(float x, float y, float z, float px, float py, float pz, float angle, Vector3 out) {
      org.joml.Vector3f v = M.transformPosition(x, y, z, AX);
      out.set(v.x, v.y, v.z);
      Model.vectorToWorldCoords(px, py, pz, angle, out);
   }

   /** The item's model instance: the hand props, the attached model at its location, a weapon's light part. */
   private static ModelInstance find(IsoPlayer p, ModelManager.ModelSlot slot, InventoryItem item) {
      ModelInstance mi = null;
      if (item == p.getPrimaryHandItem() && p.primaryHandModel != null) {
         mi = p.primaryHandModel;
      } else if (item == p.getSecondaryHandItem() && p.secondaryHandModel != null) {
         mi = p.secondaryHandModel;
      } else {
         AttachedItems attached = p.getAttachedItems();
         for (int i = 0; attached != null && i < attached.size(); i++) {
            AttachedItem a = attached.get(i);
            if (a.getItem() != item) {
               continue;
            }
            AttachedLocation loc = attached.getGroup() == null ? null : attached.getGroup().getLocation(a.getLocation());
            String name = loc == null ? null : loc.getAttachmentName();
            for (int j = 0; name != null && j < slot.sub.size(); j++) {
               ModelInstance s = slot.sub.get(j);
               if (s != null && s.parent == slot.model && name.equals(s.attachmentNameSelf)) {
                  mi = s;
                  break;
               }
            }
            break;
         }
      }
      if (mi != null && item instanceof zombie.inventory.types.HandWeapon) {
         // a weapon's light: its part model (GunLight), else the weapon itself
         for (int j = 0; j < mi.sub.size(); j++) {
            ModelInstance s = mi.sub.get(j);
            if (s != null && s.modelScript != null && s.modelScript.getName() != null && s.modelScript.getName().toLowerCase(java.util.Locale.ROOT).contains("light")) {
               return s;
            }
         }
      }
      return mi;
   }

   /**
    * One level of an item's model chain: what transformToParent multiplies after the parent's bone (the parent's attachment,
    * the item's own attachment, the mesh transform, the scale) is fixed for a model instance; kept here, rebuilt when any
    * input changes (a pooled instance reused for another item).
    */
   private static final class Link {
      ModelInstance parent;
      Model model;
      ModelMesh mesh;
      zombie.scripting.objects.ModelScript script, parentScript;
      String self, parentName, parentBoneName;
      float scale;
      AnimationPlayer ap;
      int bone; // the parent's bone (-1: none)
      final org.joml.Matrix4f suffix = new org.joml.Matrix4f();
   }

   private static final java.util.IdentityHashMap<ModelInstance, Link> LINKS = new java.util.IdentityHashMap<>();
   private static final org.joml.Matrix4f BONE = new org.joml.Matrix4f();
   public static long linkBuilds;

   /** chain() with the fixed part of every level cached: per frame one bone matrix a level. */
   private static boolean chainFast(ModelInstance mi, org.joml.Matrix4f out, int depth) {
      ModelInstance parent = mi.parent;
      if (parent == null || depth > 4) {
         out.identity();
         return true;
      }
      if (!chainFast(parent, out, depth + 1)) {
         return false;
      }
      Link l = LINKS.get(mi);
      if (l == null || l.parent != parent || l.model != mi.model || l.mesh != mi.model.mesh || l.script != mi.modelScript || l.parentScript != parent.modelScript
         || l.self != mi.attachmentNameSelf || l.parentName != mi.attachmentNameParent || l.parentBoneName != mi.parentBoneName || l.scale != mi.scale
         || l.ap != parent.animPlayer) {
         if (mi.model.mesh == null || !mi.model.mesh.isReady()) {
            return false; // postMultiplyMeshTransform scales a loading mesh to nothing: not cached
         }
         if (LINKS.size() > 64) {
            LINKS.clear();
         }
         l = l == null ? new Link() : l;
         l.parent = parent;
         l.model = mi.model;
         l.mesh = mi.model.mesh;
         l.script = mi.modelScript;
         l.parentScript = parent.modelScript;
         l.self = mi.attachmentNameSelf;
         l.parentName = mi.attachmentNameParent;
         l.parentBoneName = mi.parentBoneName;
         l.scale = mi.scale;
         l.ap = parent.animPlayer;
         ModelAttachment pa = parent.getAttachmentById(mi.attachmentNameParent);
         if (pa == null && mi.parentBoneName != null) {
            pa = parent.getAttachmentById(mi.parentBoneName);
         }
         String boneName = pa == null ? (mi.parentBoneName != null && parent.animPlayer != null ? mi.parentBoneName : null) : pa.getBone();
         l.bone = boneName == null || boneName.isBlank() || parent.animPlayer == null ? -1 : parent.animPlayer.getSkinningBoneIndex(boneName, -1);
         l.suffix.identity();
         if (pa != null) {
            ModelInstanceRenderData.makeAttachmentTransform(pa, TMP);
            l.suffix.mul(TMP);
         }
         ModelAttachment sa = mi.getAttachmentById(mi.attachmentNameSelf);
         if (sa == null && mi.parentBoneName != null) {
            sa = mi.getAttachmentById(mi.parentBoneName);
         }
         if (sa != null) {
            ModelInstanceRenderData.makeAttachmentTransform(sa, TMP);
            if (ModelInstanceRenderData.invertAttachmentSelfTransform) {
               TMP.invert();
            }
            l.suffix.mul(TMP);
         }
         ModelInstanceRenderData.postMultiplyMeshTransform(l.suffix, mi.model.mesh);
         if (mi.scale != 1.0F) {
            l.suffix.scale(mi.scale);
         }
         LINKS.put(mi, l);
         linkBuilds++;
      }
      if (l.bone >= 0) {
         // applyBoneTransform: the bone's model transform (transposed into JOML's order) in front of the parent's
         zombie.core.math.PZMath.convertMatrix(parent.animPlayer.getModelTransformAt(l.bone), BONE);
         BONE.transpose();
         BONE.mul(out, out);
      }
      out.mul(l.suffix);
      return true;
   }

   /** AnimatedModel.AnimatedModelInstanceRenderData.transformToParent, from the character's model (identity) down. */
   private static org.joml.Matrix4f chain(ModelInstance mi, org.joml.Matrix4f out, int depth) {
      ModelInstance parent = mi.parent;
      if (parent == null || depth > 4) {
         return out.identity();
      }
      chain(parent, out, depth + 1);
      ModelAttachment pa = parent.getAttachmentById(mi.attachmentNameParent);
      if (pa == null && mi.parentBoneName != null) {
         pa = parent.getAttachmentById(mi.parentBoneName);
      }
      if (pa == null) {
         if (mi.parentBoneName != null && parent.animPlayer != null) {
            ModelInstanceRenderData.applyBoneTransform(parent, mi.parentBoneName, out);
         }
      } else {
         ModelInstanceRenderData.applyBoneTransform(parent, pa.getBone(), out);
         ModelInstanceRenderData.makeAttachmentTransform(pa, TMP);
         out.mul(TMP);
      }
      ModelAttachment sa = mi.getAttachmentById(mi.attachmentNameSelf);
      if (sa == null && mi.parentBoneName != null) {
         sa = mi.getAttachmentById(mi.parentBoneName);
      }
      if (sa != null) {
         ModelInstanceRenderData.makeAttachmentTransform(sa, TMP);
         if (ModelInstanceRenderData.invertAttachmentSelfTransform) {
            TMP.invert();
         }
         out.mul(TMP);
      }
      ModelInstanceRenderData.postMultiplyMeshTransform(out, mi.model.mesh);
      if (mi.scale != 1.0F) {
         out.scale(mi.scale);
      }
      return out;
   }

   /**
    * OUT[0..1] (the lens) kept on the holder's side of its square's walls: an axis that leaves the holder's square across a
    * wall, a closed door or a window (the vision test between the two squares) stops a hair inside the edge. The native
    * lights from the square its light stands in: a lens through the wall lit the room behind it.
    */
   private static void clampWalls(float hx, float hy, float hz) {
      IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
      if (cell == null) {
         return;
      }
      int sx = (int)Math.floor(hx), sy = (int)Math.floor(hy), sz = (int)Math.floor(hz + 0.01F);
      IsoGridSquare sq = cell.getGridSquare(sx, sy, sz);
      if (sq == null) {
         OUT[0] = hx;
         OUT[1] = hy;
         return;
      }
      int lx = (int)Math.floor(OUT[0]), ly = (int)Math.floor(OUT[1]);
      if (lx != sx && blocked(sq, lx - sx, 0)) {
         OUT[0] = lx > sx ? sx + 0.995F : sx + 0.005F;
         lx = sx;
      }
      if (ly != sy && blocked(sq, 0, ly - sy)) {
         OUT[1] = ly > sy ? sy + 0.995F : sy + 0.005F;
         ly = sy;
      }
      if (lx != sx && ly != sy && blocked(sq, lx - sx, ly - sy)) { // a corner: the nearer edge
         float ex = Math.abs(OUT[0] - (lx > sx ? sx + 1F : sx)), ey = Math.abs(OUT[1] - (ly > sy ? sy + 1F : sy));
         if (ex < ey) {
            OUT[0] = lx > sx ? sx + 0.995F : sx + 0.005F;
         } else {
            OUT[1] = ly > sy ? sy + 0.995F : sy + 0.005F;
         }
      }
   }

   private static boolean blocked(IsoGridSquare sq, int dx, int dy) {
      if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
         return true;
      }
      LosUtil.TestResults r = sq.testVisionAdjacent(dx, dy, 0, false, false);
      return r != LosUtil.TestResults.Clear && r != LosUtil.TestResults.ClearThroughOpenDoor;
   }
}
