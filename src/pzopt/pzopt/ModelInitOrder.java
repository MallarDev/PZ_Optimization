package pzopt;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import zombie.core.skinnedmodel.ModelManager;

/**
 * The order of a model slot's draw init and its re-dress (2026-10-02, the player's "8 errors").
 *
 * <p>TextureDraw.drawModel queues {@code ModelSlotRenderData.init(slot)} on the stock slot-init pool; init reads
 * {@code slot.model} when it runs. ModelManager.Reset (next frame's update, when a character's clothes, hair or timed
 * action change) puts a new ModelInstance into {@code slot.model}, whose per-player lights were never updated. An init
 * still queued at that moment reads the new instance: "Cannot read field effectLightsMain because playerData is null"
 * on the render thread, which drops the rest of that frame (and the weather composite of the frames after it). Reset
 * now waits for the slot's queued init first (bounded: past the limit it goes on as stock did).
 */
public final class ModelInitOrder {
   private static final long LIMIT_NS = 50_000_000L;
   private static long waits;
   private static long waitNs;
   private static long maxWaitNs;
   private static long timeouts;

   private ModelInitOrder() {
   }

   private static final ThreadLocal<long[]> DEV_T = ThreadLocal.withInitial(() -> new long[3]);
   private static int devSlow;

   /** Slot-init pool, dev (devCasterTrace): the init holds its data's lock now; queued at q, its task started at s. */
   public static void devLocked(ModelManager.ModelSlot slot, long q, long s) {
      if (Config.DEV_CASTER_TRACE > 0) {
         long[] t = DEV_T.get();
         t[0] = q;
         t[1] = s;
         t[2] = System.nanoTime();
      }
   }

   /** Slot-init pool, dev: the init finished; slow ones (over 2 ms from queued) logged with where the time went. */
   public static void devDone(ModelManager.ModelSlot slot) {
      if (Config.DEV_CASTER_TRACE > 0 && devSlow < 200) {
         long[] t = DEV_T.get();
         long now = System.nanoTime();
         if (now - t[0] > 2_000_000L) {
            devSlow++;
            Log.info(String.format(java.util.Locale.ROOT, "model init order: dev slow init slot %d (%s): queue %.2f ms, lock %.2f ms, init %.2f ms",
               slot.id, slot.character == null ? "-" : slot.character.getClass().getSimpleName(), (t[1] - t[0]) / 1e6, (t[2] - t[1]) / 1e6, (now - t[2]) / 1e6));
         }
      }
   }

   /** Game thread, TextureDraw.drawModel / DrawQueued: the slot's init just queued (null: none, it ran in place). */
   public static void queued(ModelManager.ModelSlot slot, Future<?> init) {
      slot.pzoptInit = init;
   }

   /** Game thread, ModelManager.Reset: before slot.model is replaced, the slot's queued init has read the old one. */
   public static void beforeReset(ModelManager.ModelSlot slot) {
      Future<?> f = slot.pzoptInit;
      slot.pzoptInit = null;
      if (f == null || f.isDone()) {
         return;
      }
      long t0 = System.nanoTime();
      try {
         f.get(LIMIT_NS, TimeUnit.NANOSECONDS);
      } catch (java.util.concurrent.TimeoutException e) {
         timeouts++;
      } catch (Exception e) {
         // its failure is the render thread's to report (TextureDraw.GetFutureResultThrow)
      }
      long dt = System.nanoTime() - t0;
      waits++;
      waitNs += dt;
      maxWaitNs = Math.max(maxWaitNs, dt);
      if (waits <= 3 || Long.bitCount(waits) == 1) {
         Log.info(String.format(java.util.Locale.ROOT, "model init order: a re-dress waited for its slot's queued draw init %d times (%.1f us mean, %.1f us max, %d past the %d ms limit)",
            waits, waitNs / 1e3 / waits, maxWaitNs / 1e3, timeouts, LIMIT_NS / 1_000_000L));
      }
   }
}
