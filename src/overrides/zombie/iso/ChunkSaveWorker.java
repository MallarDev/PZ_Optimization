package zombie.iso;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.CRC32;
import zombie.GameTime;
import zombie.MainThread;
import zombie.characters.animals.AnimalPopulationManager;
import zombie.core.Core;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugType;
import zombie.entity.GameEntityManager;
import zombie.inventory.types.MapItem;
import zombie.network.ChunkChecksum;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.savefile.PlayerDB;
import zombie.util.ByteBufferPooledObject;
import zombie.vehicles.VehiclesDB2;
import zombie.worldMap.WorldMapVisited;
import zombie.ZomboidFileSystem;

public class ChunkSaveWorker {
   public static final ChunkSaveWorker instance = new ChunkSaveWorker();
   private final ArrayList<ChunkSaveWorker.QueuedSave> tempList = new ArrayList<>();
   public final ConcurrentLinkedQueue<ChunkSaveWorker.QueuedSave> toSaveQueue = new ConcurrentLinkedQueue<>();
   private final HashMap<IsoChunk, ChunkSaveWorker.QueuedSave> toSaveMap = new HashMap<>();
   private final ConcurrentLinkedQueue<ByteBuffer> byteBufferPool = new ConcurrentLinkedQueue<>();
   public boolean saving;
   private static final SaveBufferMap saveBufferMap = new SaveBufferMap();
   // pzopt: hot-save throttle (see pzopt.Config.HOTSAVE_INTERVAL_SEC)
   private static long pzoptLastHotsaveNs;
   private static int pzoptSkippedHotsaves;

   static {
      pzopt.Overrides.onClassLoaded("zombie.iso.ChunkSaveWorker");
   }

   public void Update(IsoChunk aboutToLoad) {
      if (!GameServer.server) {
         if (this.pzoptHotsaveStage > 0) { // pzopt: continue a staged hot save, one part per call
            this.pzoptHotsaveStep();
         }
         ChunkSaveWorker.QueuedSave qs = null;
         this.saving = !this.toSaveQueue.isEmpty();
         if (this.saving) {
            if (aboutToLoad != null) {
               for (ChunkSaveWorker.QueuedSave qs2 : this.toSaveQueue) {
                  if (qs2.chunk.wx == aboutToLoad.wx && qs2.chunk.wy == aboutToLoad.wy) {
                     if (this.toSaveQueue.remove(qs2)) {
                        qs = qs2;
                     }
                     break;
                  }
               }
            }

            if (qs == null) {
               qs = this.toSaveQueue.poll();
            }

            if (qs != null) {
               this.WriteQueuedSave(qs);
               if (this.toSaveQueue.isEmpty() && !GameClient.client && !GameServer.server) {
                  // pzopt: the ancillary hot save serialises the whole meta grid on the game thread; while moving the
                  // queue drains every chunk row (~0.5 s), so it is rate-limited to one per HOTSAVE_INTERVAL_SEC.
                  int interval = pzopt.Overrides.enabled() ? pzopt.Config.HOTSAVE_INTERVAL_SEC : 0;
                  long now = System.nanoTime();
                  if (interval <= 0 || now - pzoptLastHotsaveNs >= interval * 1_000_000_000L) {
                     if (pzoptSkippedHotsaves > 0) {
                        pzopt.Log.info("hot save: running (" + pzoptSkippedHotsaves + " drains skipped since the last one)");
                        pzoptSkippedHotsaves = 0;
                     }
                     pzoptLastHotsaveNs = now;
                     this.HotsaveAncilliarySystems();
                  } else {
                     pzoptSkippedHotsaves++;
                  }
               }
            }
         }
      }
   }

   // pzopt: staged hot save (Config.HOTSAVE_STAGED). Stock serialises every ancillary system on the game thread in one
   // go (the meta grid alone is ~40 ms: map_meta, zones, animal zones, meta cells), one 55 ms frame per hot save.
   // Staged, each Update call from the streamer runs one part on the game thread, so the same work lands as several
   // short hitches a few frames apart; the buffers accumulate and are written to disk after the last stage.
   private int pzoptHotsaveStage = -1;
   private static final int PZOPT_HOTSAVE_STAGES = 9;

   private boolean pzoptHotsaveStaged() {
      return pzopt.Overrides.enabled() && pzopt.Config.HOTSAVE_STAGED;
   }

   /** pzopt: runs the next stage of a staged hot save if one is in progress. */
   private void pzoptHotsaveStep() {
      if (this.pzoptHotsaveStage < 0) {
         return;
      }
      int stage = this.pzoptHotsaveStage++;
      MainThread.invokeOnMainThread(() -> {
         IsoMetaGrid mg = IsoWorld.instance.metaGrid;
         switch (stage) {
            case 0 -> mg.saveToSaveBufferMap(saveBufferMap, ZomboidFileSystem.instance.getFileNameInCurrentSave("map_meta.bin"), mg::save);
            case 1 -> mg.saveToSaveBufferMap(saveBufferMap, ZomboidFileSystem.instance.getFileNameInCurrentSave("map_zone.bin"), mg::saveZone);
            case 2 -> mg.saveToSaveBufferMap(saveBufferMap, ZomboidFileSystem.instance.getFileNameInCurrentSave("map_animals.bin"), mg::saveAnimalZones);
            case 3 -> mg.saveCellsToSaveBufferMap(saveBufferMap, "metagrid", "metacell_%d_%d.bin", IsoMetaCell::save);
            case 4 -> AnimalPopulationManager.getInstance().saveToBufferMap(saveBufferMap);
            case 5 -> GameTime.instance.saveToBufferMap(saveBufferMap);
            case 6 -> MapItem.SaveWorldMapToBufferMap(saveBufferMap);
            case 7 -> WorldMapVisited.getInstance().saveToBufferMap(saveBufferMap);
            default -> GameEntityManager.saveToBufferMap(saveBufferMap);
         }
      });
      if (this.pzoptHotsaveStage >= PZOPT_HOTSAVE_STAGES) {
         this.pzoptHotsaveStage = -1;
         if (PlayerDB.isAllow()) {
            PlayerDB.getInstance().savePlayers();
         }

         try {
            saveBufferMap.save(ChunkSaveWorker::writeBufferToDisk);
         } catch (Exception e) {
            ExceptionLogger.logException(e);
         }

         saveBufferMap.clear();
      }
   }

   private void HotsaveAncilliarySystems() {
      if (this.pzoptHotsaveStaged()) { // pzopt: start a staged hot save; stages run on the following Update calls
         if (this.pzoptHotsaveStage < 0) {
            saveBufferMap.clear();
            this.pzoptHotsaveStage = 0;
            pzopt.Log.info("hot save: staged over " + PZOPT_HOTSAVE_STAGES + " streamer updates");
            this.pzoptHotsaveStep();
         }
         return;
      }
      saveBufferMap.clear();
      MainThread.invokeOnMainThread(() -> {
         long pzoptT0 = pzopt.Config.DEV_HOTSAVE_TIMING ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming, the game thread's part per system
         IsoWorld.instance.metaGrid.saveToBufferMap(saveBufferMap);
         long pzoptT1 = pzoptT0 != 0L ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
         AnimalPopulationManager.getInstance().saveToBufferMap(saveBufferMap);
         long pzoptT2 = pzoptT0 != 0L ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
         GameTime.instance.saveToBufferMap(saveBufferMap);
         long pzoptT3 = pzoptT0 != 0L ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
         MapItem.SaveWorldMapToBufferMap(saveBufferMap);
         long pzoptT4 = pzoptT0 != 0L ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
         WorldMapVisited.getInstance().saveToBufferMap(saveBufferMap);
         long pzoptT5 = pzoptT0 != 0L ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
         GameEntityManager.saveToBufferMap(saveBufferMap);
         if (pzoptT0 != 0L) { // pzopt: devHotsaveTiming
            pzopt.Log.info("hotsave timing (game thread): metagrid " + (pzoptT1 - pzoptT0) / 1000L + " us, animals " + (pzoptT2 - pzoptT1) / 1000L // pzopt: devHotsaveTiming
               + " us, gametime " + (pzoptT3 - pzoptT2) / 1000L + " us, mapitems " + (pzoptT4 - pzoptT3) / 1000L + " us, visited " + (pzoptT5 - pzoptT4) / 1000L // pzopt: devHotsaveTiming
               + " us, entities " + (System.nanoTime() - pzoptT5) / 1000L + " us"); // pzopt: devHotsaveTiming
         } // pzopt: devHotsaveTiming
      });
      if (PlayerDB.isAllow()) {
         PlayerDB.getInstance().savePlayers();
      }

      try {
         saveBufferMap.save(ChunkSaveWorker::writeBufferToDisk);
      } catch (Exception e) {
         ExceptionLogger.logException(e);
      }

      saveBufferMap.clear();
   }

   private static void writeBufferToDisk(String outFilePath, ByteBufferPooledObject buffer) throws IOException {
      if (!Core.getInstance().isNoSave()) {
         File outFile = new File(outFilePath);

         try (
            FileOutputStream fos = new FileOutputStream(outFile);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
         ) {
            for (int i = 0; i < buffer.capacity(); i++) {
               bos.write(buffer.get(i));
            }
         }
      }
   }

   private void WriteQueuedSave(ChunkSaveWorker.QueuedSave qs) {
      try {
         if (qs.isHotSave) {
            DebugType.Saving.debugln("ChunkSaveWorker.WriteQueuedSave - Saving (ch=%d, %d) isHotSave=%b", new Object[]{qs.chunk.wx, qs.chunk.wy, qs.isHotSave});
         }

         if (qs.byteBuffer != null) {
            long crc = ChunkChecksum.getChecksumIfExists(qs.chunk.wx, qs.chunk.wy);
            if (crc == qs.crc.getValue()) {
               DebugType.Saving
                  .debugln("ChunkSaveWorker.WriteQueuedSave - Aborted Saving Unchanged Chunk (ch=%d, %d) crc=%d", new Object[]{qs.chunk.wx, qs.chunk.wy, crc});
            } else {
               ChunkChecksum.setChecksum(qs.chunk.wx, qs.chunk.wy, qs.crc.getValue());
               IsoChunk.SafeWrite(qs.chunk.wx, qs.chunk.wy, qs.byteBuffer);
            }
         } else {
            qs.chunk.Save(qs.isHotSave);
         }
      } catch (Exception e) {
         ExceptionLogger.logException(e);
      } finally {
         qs.chunk = null;
         qs.releaseBuffer();
      }
   }

   public void SaveNow(ArrayList<IsoChunk> aboutToLoad) {
      this.tempList.clear();

      for (ChunkSaveWorker.QueuedSave qs2 = this.toSaveQueue.poll(); qs2 != null; qs2 = this.toSaveQueue.poll()) {
         boolean saved = false;

         for (int i = 0; i < aboutToLoad.size(); i++) {
            IsoChunk ch = aboutToLoad.get(i);
            if (qs2.chunk.wx == ch.wx && qs2.chunk.wy == ch.wy) {
               this.WriteQueuedSave(qs2);
               saved = true;
               break;
            }
         }

         if (!saved) {
            this.tempList.add(qs2);
         }
      }

      for (int i = 0; i < this.tempList.size(); i++) {
         this.toSaveQueue.add(this.tempList.get(i));
      }

      this.tempList.clear();
   }

   public void SaveNow() {
      DebugType.ExitDebug.debugln("ChunkSaveWorker.SaveNow 1");

      for (ChunkSaveWorker.QueuedSave qs = this.toSaveQueue.poll(); qs != null; qs = this.toSaveQueue.poll()) {
         DebugType.ExitDebug.debugln("ChunkSaveWorker.SaveNow 2 (ch=" + qs.chunk.wx + ", " + qs.chunk.wy + ")");
         this.WriteQueuedSave(qs);
      }

      this.removeCompletedJobs();
      this.saving = false;
      DebugType.ExitDebug.debugln("ChunkSaveWorker.SaveNow 3");
   }

   public void AddHotSave(IsoChunk ch) {
      this.removeCompletedJobs();
      ChunkSaveWorker.QueuedSave qs = this.findQueuedSaveForChunk(ch);
      if (qs == null) {
         qs = new ChunkSaveWorker.QueuedSave(ch, true);

         try {
            qs.allocBuffer();
            qs.byteBuffer = qs.chunk.Save(qs.byteBuffer, qs.crc, true);
         } catch (Exception e) {
            qs.releaseBuffer();
            DebugType.Saving.error("ChunkSaveWorker.AddHotSave FAILED - (ch=%d, %d)", new Object[]{ch.wx, ch.wy});
            return;
         }

         DebugType.Saving.debugln("ChunkSaveWorker.AddHotSave - (ch=%d, %d)", new Object[]{ch.wx, ch.wy});
         this.toSaveMap.put(ch, qs);
         this.toSaveQueue.add(qs);
      } else {
         if (qs.isHotSave) {
            try {
               qs.byteBuffer = qs.chunk.Save(qs.byteBuffer, qs.crc, true);
            } catch (Exception e) {
               qs.releaseBuffer();
               DebugType.Saving.error("ChunkSaveWorker.AddHotSave UPDATE FAILED - (ch=%d, %d)", new Object[]{ch.wx, ch.wy});
               return;
            }

            this.toSaveMap.put(ch, qs);
            this.toSaveQueue.add(qs);
            DebugType.Saving.debugln("ChunkSaveWorker.AddHotSave UPDATED - (ch=%d, %d)", new Object[]{ch.wx, ch.wy});
         }
      }
   }

   public void Add(IsoChunk ch) {
      if (Core.getInstance().isNoSave()) {
         for (int i = 0; i < ch.vehicles.size(); i++) {
            VehiclesDB2.instance.updateVehicle(ch.vehicles.get(i));
         }
      }

      this.removeCompletedJobs();
      ChunkSaveWorker.QueuedSave qs = this.findQueuedSaveForChunk(ch);
      if (qs == null) {
         qs = new ChunkSaveWorker.QueuedSave(ch, false);
      } else {
         qs.isHotSave = false;
         qs.releaseBuffer();
         qs.crc = null;
      }

      this.toSaveMap.put(ch, qs);
      this.toSaveQueue.add(qs);
   }

   private void removeCompletedJobs() {
      this.toSaveMap.entrySet().removeIf(entry -> entry.getValue().chunk == null);
   }

   private ChunkSaveWorker.QueuedSave findQueuedSaveForChunk(IsoChunk ch) {
      ChunkSaveWorker.QueuedSave qs = this.toSaveMap.remove(ch);
      if (qs == null) {
         return null;
      }

      boolean removed = this.toSaveQueue.remove(qs);
      return removed ? qs : null;
   }

   private static final class QueuedSave {
      public IsoChunk chunk;
      public boolean isHotSave;
      public ByteBuffer byteBuffer;
      public CRC32 crc;

      QueuedSave(IsoChunk chunk, boolean isHotSave) {
         this.chunk = chunk;
         this.isHotSave = isHotSave;
         this.byteBuffer = null;
         this.crc = isHotSave ? new CRC32() : null;
      }

      void allocBuffer() {
         if (this.byteBuffer == null) {
            this.byteBuffer = ChunkSaveWorker.instance.byteBufferPool.poll();
            if (this.byteBuffer == null) {
               this.byteBuffer = ByteBuffer.allocate(65536);
            }
         }
      }

      void releaseBuffer() {
         if (this.byteBuffer != null) {
            if (ChunkSaveWorker.instance.byteBufferPool.size() < 30) {
               ChunkSaveWorker.instance.byteBufferPool.add(this.byteBuffer);
            }

            this.byteBuffer = null;
         }
      }
   }
}
