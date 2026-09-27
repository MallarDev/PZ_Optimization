package pzopt;

import java.util.ArrayList;
import zombie.iso.IsoMovingObject;

/**
 * The per-character reads of the frame's own passes on the frame workers ({@code renderPrepParallel}, 2026-09-27).
 *
 * <p>Drawing a character asks two of pzopt's passes a question that only reads the world: the capsule shadows want its
 * sun share through the grid ({@code SunShadow.visibleAt}: a march of up to 40 squares, times the cloud transmittance),
 * the reflections want to know whether water or a puddle lies within its mirror reach ({@code Ssr.addMoving}: up to
 * ~200 square-map lookups). On the Louisville horde that is ~1,500 characters a frame and 4-5 % of the game thread.
 * Both inputs are final for the frame once {@code ChunkAo.flush} (the sun step), {@code Ssr.beforeComposite} (the water
 * map) and {@code CloudShadow.beforeComposite} (the drift) have run, and nothing moves a character during the render, so
 * {@link #start} hands the characters draw pass's on-screen list ({@code CharDraw}, same objects, same order) to the
 * frame workers right before the chunk composite; they fill one slot per object while the game thread composites and
 * draws the players, corpses, items and puddles. {@link #join} at the top of {@code renderMovingObjects} waits for the
 * tail (normally nothing), and the stock loop, which walks the same list, tells {@link #at} the index it is drawing; the
 * passes then take the slot's answer ({@link #sunVis}, {@link #ssrNear}) instead of computing it. Everything order
 * dependent (the shadow and scatter lists, the puddle count cap) stays in the loop. The answers are the same functions
 * of the same inputs, so the picture is unchanged; an object the list does not hold at that index (the player, a
 * vehicle drawn elsewhere, a failed pass) is simply computed inline as before.
 */
public final class RenderPrep {
   private RenderPrep() {
   }

   private static final int PER_TASK = 32;
   private static final float NONE = Float.NaN; // slot not computed: the caller computes inline

   private static ArrayList<IsoMovingObject> list;
   private static float[] sun = new float[2048];
   private static byte[] ssr = new byte[2048]; // 0 not computed, 1 nothing near, 2 puddles only, 3 water
   private static boolean doSun, doSsr;
   private static boolean inFlight, ready, failed;
   private static int cursor = -1;
   private static int n;
   public static long frames, objects, joinsWaited, inlineSun, inlineSsr, servedSun, servedSsr;

   private static boolean enabled() {
      return Config.RENDER_PREP_PARALLEL && !failed && Overrides.enabled() && GtAb.on(GtAb.RENDER_PREP);
   }

   /** Game thread, right before the chunk composite (after the water map, the sun step and the cloud drift of the frame). */
   public static void start() {
      if (inFlight) {
         FrameBatch.join(); // last frame's batch was never joined (the draw loop was skipped): finish it before reusing the slots
      }
      ready = false;
      cursor = -1;
      if (!enabled()) {
         return;
      }
      ArrayList<IsoMovingObject> l = CharDraw.onScreenForPrep();
      if (l == null || l.isEmpty()) {
         return;
      }
      doSun = CapsuleShadow.wantsSunVis();
      doSsr = Ssr.wantsMoving();
      if (!doSun && !doSsr) {
         return;
      }
      list = l;
      n = l.size();
      if (sun.length < n) {
         sun = new float[n + 512];
         ssr = new byte[n + 512];
      }
      frames++;
      objects += n;
      inFlight = true;
      FrameBatch.runAsync((n + PER_TASK - 1) / PER_TASK, RenderPrep::task, RenderPrep::done);
   }

   private static void task(int t) {
      int from = t * PER_TASK, to = Math.min(n, from + PER_TASK);
      for (int i = from; i < to; i++) {
         IsoMovingObject o = list.get(i);
         float v = NONE;
         if (doSun) {
            v = CapsuleShadow.sunVisFor(o);
         }
         sun[i] = v;
         ssr[i] = (byte)(doSsr ? Ssr.nearCode(o) : 0);
      }
   }

   private static void done(Throwable failure) {
      inFlight = false;
      if (failure != null) {
         failed = true;
         Log.warn("renderPrepParallel: a worker task failed, inline from now on: " + failure);
         return;
      }
      ready = true;
   }

   /** Game thread, the top of renderMovingObjects: the tail of the batch. */
   public static void join() {
      if (inFlight) {
         joinsWaited++;
         FrameBatch.join();
      }
   }

   /** Game thread, the characters draw loop: the list index about to be drawn (-1 after the loop). */
   public static void at(int i) {
      cursor = i;
   }

   /** The precomputed sun share of this object, or NaN (compute inline). */
   static float sunVis(IsoMovingObject o) {
      int i = cursor;
      if (ready && doSun && i >= 0 && i < n && list.get(i) == o) {
         float v = sun[i];
         if (v == v) {
            servedSun++;
            return v;
         }
      }
      inlineSun++;
      return NONE;
   }

   /** The precomputed water proximity code of this object (1 nothing near, 2 puddles only, 3 water) or 0 (compute inline). */
   static int ssrNear(IsoMovingObject o) {
      int i = cursor;
      if (ready && doSsr && i >= 0 && i < n && list.get(i) == o) {
         int c = ssr[i];
         if (c != 0) {
            servedSsr++;
            return c;
         }
      }
      inlineSsr++;
      return 0;
   }

   /** Game thread, after the characters draw loop: nothing is served past it. */
   public static void finish() {
      ready = false;
      cursor = -1;
      list = null;
   }

   public static String describe() {
      return "renderPrep frames=" + frames + " objects=" + objects + " servedSun=" + servedSun + " inlineSun=" + inlineSun + " servedSsr=" + servedSsr + " inlineSsr=" + inlineSsr
            + " joinsWaited=" + joinsWaited + (failed ? " FAILED" : "");
   }
}
