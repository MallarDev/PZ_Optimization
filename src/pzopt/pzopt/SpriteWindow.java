package pzopt;

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The world loader's sprite-table window (2026-09-23). IsoWorld.init disposes the global IsoSpriteManager and refills
 * its maps (stock's tile definition load, or TileDefPreload.install moving ~100k sprites in) on the loader thread. With
 * earlyTilePacks the 218 tile depth-map loads are queued at boot and some finish during the world load; each finish
 * (TileDepthTextures.LoadTask.done -> TileDepthTextureManager.finishedLoadTask) walks the same sprite map on the main
 * thread, and a walk that met the refill threw a ConcurrentModificationException in the loading screen (seen in about a
 * third of the Louisville runs on 2026-09-22/23). The loader holds this lock across the window; a load task finishing
 * meanwhile is kept and finished by the next file-system pump after the window, as if its load had simply taken longer.
 *
 * <p>Since the early world entry (centerFirstLoad, 2026-09-23) sprites are also created on demand while the first chunks
 * load, outside that window, so the last load task's two sprite walks (TileDepthTextureManager.initSprites and
 * TileDepthTextureAssignmentManager.initSprites, both idempotent: the first resets every depth texture and re-applies
 * them) can still meet an insert. Such a walk is repeated at the next pump instead of reaching frameStep as an error.
 */
public final class SpriteWindow {
   private SpriteWindow() {
   }

   private static final ReentrantLock LOCK = new ReentrantLock();
   private static final ArrayList<Runnable> deferred = new ArrayList<>(); // main thread only
   public static int deferredCount;

   /** Loader thread: the sprite maps are about to be disposed and refilled. */
   public static void open() {
      LOCK.lock();
   }

   /** Loader thread: the refill is complete. */
   public static void close() {
      if (LOCK.isHeldByCurrentThread()) {
         LOCK.unlock();
      }
   }

   /** Main thread, a load task's finish that walks the sprite maps: run it now, or after the window. */
   public static void finish(Runnable r) {
      if (LOCK.tryLock()) {
         try {
            r.run();
         } catch (java.util.ConcurrentModificationException e) {
            deferred.add(SpriteWindow::rewalk); // the counter part of the finish is done; only the walks are repeated
            deferredCount++;
         } finally {
            LOCK.unlock();
         }
      } else {
         deferred.add(r);
         deferredCount++;
      }
   }

   /** The two sprite walks of the last depth-map load task, repeated after one met a concurrent sprite insert. */
   private static void rewalk() {
      zombie.tileDepth.TileDepthTextureManager.getInstance().initSprites();
      zombie.tileDepth.TileDepthTextureAssignmentManager.getInstance().initSprites();
      TileDepthFix.reapply();
   }

   /** Main thread, every file-system pump: the finishes kept during the window, once it has closed. */
   public static void drain() {
      if (deferred.isEmpty() || !LOCK.tryLock()) {
         return;
      }
      try {
         ArrayList<Runnable> now = new ArrayList<>(deferred);
         deferred.clear();
         for (Runnable r : now) {
            try {
               r.run();
            } catch (java.util.ConcurrentModificationException e) {
               deferred.add(SpriteWindow::rewalk); // another insert met the walk: again at the next pump
            }
         }
         Log.info("spriteWindow: " + now.size() + " tile depth-map finish(es) run after a sprite-map change" + (deferred.isEmpty() ? "" : ", " + deferred.size() + " again next pump"));
      } finally {
         LOCK.unlock();
      }
   }
}
