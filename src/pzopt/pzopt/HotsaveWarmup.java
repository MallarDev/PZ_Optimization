package pzopt;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;

/**
 * hotsaveWarmup (2026-09-30, the transfer hitch of the UI snappiness pass): a change to the world (items dropped or
 * looted) makes the next hot save serialise the map metadata on the game thread. The first one of a session ran the
 * serialisers cold: ~17 ms (grid ~11 ms, zones ~5 ms) where the same code takes ~2-3 ms once the JIT has compiled it
 * (measured on the save at quit), so the first transfer of a session stuttered. The loader thread, right after the
 * world loaded, now runs the read-only serialisers (map_meta's grid part, zones, animal zones) three times into a
 * private scratch buffer that is then dropped: the files, the shared save buffer and the game state are untouched.
 */
public final class HotsaveWarmup {
   private HotsaveWarmup() {
   }

   /** GameLoadingState's loader thread, right after IsoWorld.init. */
   public static void run() {
      if (!Config.HOTSAVE_WARMUP || !Overrides.enabled() || GameClient.client) {
         return;
      }
      IsoMetaGrid mg = IsoWorld.instance == null ? null : IsoWorld.instance.metaGrid;
      if (mg == null) {
         return;
      }
      long t0 = System.nanoTime();
      ByteBuffer scratch = ByteBuffer.allocate(16 << 20);
      StringBuilder times = new StringBuilder();
      try {
         for (int i = 0; i < 3; i++) {
            long s = System.nanoTime();
            scratch.clear();
            mg.savePart(scratch, 0, false);
            scratch.clear();
            mg.saveZone(scratch);
            scratch.clear();
            mg.saveAnimalZones(scratch);
            times.append(i == 0 ? "" : " / ").append((System.nanoTime() - s) / 1000L);
         }
      } catch (BufferOverflowException e) {
         Log.warn("hotsaveWarmup: the map metadata is larger than the scratch buffer, stopped");
      } catch (Throwable t) {
         Log.warn("hotsaveWarmup: " + t);
      }
      Log.info("hotsaveWarmup: map metadata serialised in " + times + " us (" + (System.nanoTime() - t0) / 1000L + " us in all, loader thread)");
   }
}
