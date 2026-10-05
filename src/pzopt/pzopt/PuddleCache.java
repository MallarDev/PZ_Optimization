package pzopt;

import java.util.ArrayList;
import java.util.Arrays;
import zombie.core.PerformanceSettings;
import zombie.core.math.PZMath;
import zombie.debug.DebugOptions;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoPuddlesGeometry;
import zombie.iso.PlayerCamera;
import zombie.iso.fboRenderChunk.FBORenderCutaways.ChunkLevelData;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.fboRenderChunk.ObjectRenderLayer;

/**
 * Per chunk-level cache of the packed puddle vertices the game thread builds every frame in
 * FBORenderCell.renderPuddles (Config key {@code puddleCache}).
 *
 * <p>Stock walks every on-screen chunk on every level each frame, filters the chunk's cached puddle squares
 * (cutaway flags, on-screen test, floor layer, geometry flags), re-reads the four vertex lights and packs
 * 32 floats per square into IsoPuddles' RenderData (with four IsoDepthHelper lookups). In a thunderstorm at
 * max zoom on a 5120x2160 desktop that is ~8k squares and 4.5 ms of a 13 ms game-thread frame.
 *
 * <p>Of those 32 floats only three inputs change between frames: the vertex lights (float slot 6), the
 * camera's sub-pixel jiggle added to x/y (slots 4, 5) and the depth (slot 7), which depends on the camera's
 * chunk only and shifts by the same constant for every square when the camera crosses a chunk edge. The
 * geometry itself, its shape flags and the square list are fixed until the chunk level is re-baked. So a
 * batch is packed once per chunk level with the stock code (same bytes), stored on the IsoChunk, and on later
 * frames copied into RenderData with those three slots patched. A batch is rebuilt when its square list is
 * cleared by a bake, its cutaway flags change, or every {@code puddleCacheFrames} frames as a backstop
 * (staggered per chunk so the rebuilds never land on one frame).
 *
 * <p>The per-square on-screen test is not applied to cached batches: every puddle square of an on-screen
 * chunk level is submitted and the ones outside the viewport are clipped by the GPU, which is the same
 * picture.
 */
public final class PuddleCache {
   /** Floats per square in IsoPuddles.RenderData: 4 vertices x (4 dir flags, x, y, colour, depth). */
   static final int FLOATS = 32;

   /**
    * The camera jiggle's share of a puddle vertex's depth (2026-10-05, Discord "white roofs flicker in the rain"). Stock packs
    * each vertex's depth at the square corner moved by the jiggle (camera.fixJigglyModelsSquareX/Y, up to ~0.06 of a square
    * at the widest zoom), and the depth falls by CHUNK_DEPTH / 16 per square along x and along y (linear across chunk edges:
    * the chunk term and calculateDepth's wrap cancel). A cached batch kept the jiggle of the frame it was packed in while the
    * screen position took the current one, so its depth was off by up to ~1.7e-4 against the 1e-4 the puddle sits in front of
    * the floor: on a flat roof (roofs_04_* drawn as the floor, corrugation in its depth texture) the puddle showed through in
    * white dashes along the ridges, a chunk at a time, whenever a batch's jiggle and the frame's differed.
    */
   static float jiggleDepth(PlayerCamera camera) {
      if (!Config.PUDDLE_JIGGLE_DEPTH) {
         return 0.0F; // the batches keep the jiggle of their build (before 2026-10-05)
      }
      return -(camera.fixJigglyModelsSquareX + camera.fixJigglyModelsSquareY) * (IsoDepthHelper.CHUNK_DEPTH / 16.0F);
   }
   private static final int LEVELS = 64;

   public static final class Batch {
      float[] data = new float[0];
      IsoGridSquare[] squares = new IsoGridSquare[0];
      int count;
      int listSize;
      long flagMask;
      float jx;
      float jy;
      float jdepth; // the camera jiggle's share of the packed depth (jiggleDepth at the build; 0 when normalised for the VBO shader)
      int camChunkX;
      int camChunkY;
      int builtFrame;
      int expiryFrame;
      boolean invalid = true;
      // puddleVbo: the batch's GL buffer (render thread only), whether a square's light changed since the last
      // upload (LightingJNI, game thread) and whether the data changed since the last upload
      int vbo;
      boolean lightsDirty;
      boolean dirty;
   }

   /** Per-chunk slot: [playerIndex][z + 32]. Lives in IsoChunk.pzoptPuddles. */
   public static final class Slot {
      final Batch[][] batches = new Batch[4][];

      public void invalidate(int level) {
         int idx = level + 32;
         if (idx < 0 || idx >= LEVELS) {
            return;
         }
         for (Batch[] perPlayer : this.batches) {
            if (perPlayer != null && perPlayer[idx] != null) {
               perPlayer[idx].invalid = true;
            }
         }
      }

      /** Every batch of every player: a reused chunk object. */
      public void invalidateAll() {
         for (Batch[] perPlayer : this.batches) {
            if (perPlayer != null) {
               for (Batch b : perPlayer) {
                  if (b != null) {
                     b.invalid = true;
                  }
               }
            }
         }
      }

      /** puddleVbo: a square of this level changed its vertex lights (called by LightingJNI on the game thread). */
      public void lightsChanged(int level) {
         int idx = level + 32;
         if (idx < 0 || idx >= LEVELS) {
            return;
         }
         for (Batch[] perPlayer : this.batches) {
            if (perPlayer != null && perPlayer[idx] != null) {
               perPlayer[idx].lightsDirty = true;
            }
         }
      }

      Batch get(int playerIndex, int level) {
         int idx = level + 32;
         if (idx < 0 || idx >= LEVELS) {
            return null;
         }
         Batch[] perPlayer = this.batches[playerIndex];
         if (perPlayer == null) {
            perPlayer = this.batches[playerIndex] = new Batch[LEVELS];
         }
         Batch b = perPlayer[idx];
         if (b == null) {
            b = perPlayer[idx] = new Batch();
         }
         return b;
      }
   }

   private static int frame;
   private static int built;
   private static int reused;
   private static int rebuiltInvalid;
   private static int rebuiltFlags;
   private static int rebuiltExpired;
   private static int lightPatches;
   private static int lightUploads;
   private static int depthUploads;
   private static final ArrayList<IsoGridSquare> scratch = new ArrayList<>();

   public static boolean enabled() {
      return Overrides.enabled() && Config.PUDDLE_CACHE;
   }

   /** Called from an IsoChunk bake right after FBORenderLevels.clearCachedSquares(level). */
   public static void invalidate(IsoChunk chunk, int level) {
      Slot s = chunk.pzoptPuddles;
      if (s != null) {
         s.invalidate(level);
      }
   }

   /** puddleVbo: LightingJNI saw a square of this chunk level change its light info or vertex lights. */
   public static void lightsChanged(IsoChunk chunk, int level) {
      Slot s = chunk.pzoptPuddles;
      if (s != null) {
         s.lightsChanged(level);
      }
   }

   /** A chunk object is being reused for another position: its batches belong to the previous chunk. */
   public static void chunkReused(IsoChunk chunk) {
      Slot s = chunk.pzoptPuddles;
      if (s != null) {
         s.invalidateAll();
      }
   }

   /**
    * The cached replacement for the body of FBORenderCell.renderPuddles: same guards, same per-z draw.
    */
   public static void render(int playerIndex, ArrayList<IsoChunk> onScreenChunks, int maxZ) {
      IsoPuddles puddles = IsoPuddles.getInstance();
      if (playerIndex == 0) {
         frame++;
      }
      if (PuddleVbo.enabled()) {
         renderVbo(puddles, playerIndex, onScreenChunks, maxZ);
         return;
      }
      PlayerCamera camera = IsoCamera.cameras[playerIndex];
      float jx = camera.fixJigglyModelsX * camera.zoom;
      float jy = camera.fixJigglyModelsY * camera.zoom;
      float jd = jiggleDepth(camera);
      int camChunkX = PZMath.fastfloor(PZMath.fastfloor(IsoCamera.frameState.camCharacterX) / 8.0F);
      int camChunkY = PZMath.fastfloor(PZMath.fastfloor(IsoCamera.frameState.camCharacterY) / 8.0F);
      boolean noLighting = DebugOptions.instance.fboRenderChunk.nolighting.getValue();
      int interval = Math.max(1, Config.PUDDLE_CACHE_FRAMES);

      for (int z = 0; z <= maxZ; z++) {
         if (!puddles.pzoptCanRender(z)) {
            continue;
         }
         int first = puddles.pzoptNumSquares();

         for (int i = 0; i < onScreenChunks.size(); i++) {
            IsoChunk chunk = onScreenChunks.get(i);
            if (z < chunk.minLevel || z > chunk.maxLevel) {
               continue;
            }
            FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
            if (!renderLevels.isOnScreen(z)) {
               continue;
            }
            java.util.List<IsoGridSquare> squares = renderLevels.getCachedSquares_Puddles(z);
            if (squares.isEmpty()) {
               continue;
            }
            ChunkLevelData levelData = chunk.getCutawayDataForLevel(z);
            long mask = flagMask(levelData, playerIndex);
            Slot slot = chunk.pzoptPuddles;
            if (slot == null) {
               slot = chunk.pzoptPuddles = new Slot();
            }
            Batch b = slot.get(playerIndex, z);
            if (b == null) {
               continue;
            }

            boolean rebuild;
            if (b.invalid || b.listSize != squares.size()) {
               rebuild = true;
               rebuiltInvalid++;
            } else if (b.flagMask != mask) {
               rebuild = true;
               rebuiltFlags++;
            } else if (frame >= b.expiryFrame) {
               rebuild = true;
               rebuiltExpired++;
            } else {
               rebuild = false;
            }

            if (rebuild) {
               build(puddles, b, chunk, z, playerIndex, squares, levelData, mask, jx, jy, camChunkX, camChunkY, interval);
               b.jdepth = jd;
            } else if (b.count > 0) {
               reuse(puddles, b, chunk, z, playerIndex, jx, jy, jd, camChunkX, camChunkY, noLighting);
            }
         }

         int count = puddles.pzoptNumSquares() - first;
         if (count > 0) {
            puddles.pzoptDraw(z, first, count);
         }
      }
   }

   /**
    * puddleVbo: same batch bookkeeping as {@link #render}, but nothing is copied per frame. A batch is packed with
    * the stock code when it is (re)built, its jiggle normalised to zero, and uploaded to its own GL buffer; later
    * frames only re-upload it when its lights changed (LightingJNI hook), the camera crossed a chunk edge (one depth
    * constant for every vertex) or it was rebuilt. The current jiggle goes to the render thread with the frame.
    */
   private static void renderVbo(IsoPuddles puddles, int playerIndex, ArrayList<IsoChunk> onScreenChunks, int maxZ) {
      PlayerCamera camera = IsoCamera.cameras[playerIndex];
      float jx = camera.fixJigglyModelsX * camera.zoom;
      float jy = camera.fixJigglyModelsY * camera.zoom;
      float jd = jiggleDepth(camera);
      // the earlyZ programs (pzopt_puddles_*) add the frame's jiggle depth as a uniform; stock's programs (puddleEarlyZ=false)
      // have none, so there a batch is patched and re-uploaded when the jiggle moved
      boolean shaderShift = Config.PUDDLE_EARLY_Z;
      int camChunkX = PZMath.fastfloor(PZMath.fastfloor(IsoCamera.frameState.camCharacterX) / 8.0F);
      int camChunkY = PZMath.fastfloor(PZMath.fastfloor(IsoCamera.frameState.camCharacterY) / 8.0F);
      boolean noLighting = DebugOptions.instance.fboRenderChunk.nolighting.getValue();
      int interval = Math.max(1, Config.PUDDLE_CACHE_FRAMES);

      for (int z = 0; z <= maxZ; z++) {
         if (!puddles.pzoptCanRender(z)) {
            continue;
         }
         PuddleVbo.Frame f = PuddleVbo.begin(playerIndex, z, jx, jy, shaderShift ? jd : 0.0F);

         for (int i = 0; i < onScreenChunks.size(); i++) {
            IsoChunk chunk = onScreenChunks.get(i);
            if (z < chunk.minLevel || z > chunk.maxLevel) {
               continue;
            }
            FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
            if (!renderLevels.isOnScreen(z)) {
               continue;
            }
            java.util.List<IsoGridSquare> squares = renderLevels.getCachedSquares_Puddles(z);
            if (squares.isEmpty()) {
               continue;
            }
            ChunkLevelData levelData = chunk.getCutawayDataForLevel(z);
            long mask = flagMask(levelData, playerIndex);
            Slot slot = chunk.pzoptPuddles;
            if (slot == null) {
               slot = chunk.pzoptPuddles = new Slot();
            }
            Batch b = slot.get(playerIndex, z);
            if (b == null) {
               continue;
            }

            boolean rebuild;
            if (b.invalid || b.listSize != squares.size()) {
               rebuild = true;
               rebuiltInvalid++;
            } else if (b.flagMask != mask) {
               rebuild = true;
               rebuiltFlags++;
            } else if (frame >= b.expiryFrame) {
               rebuild = true;
               rebuiltExpired++;
            } else {
               rebuild = false;
            }

            if (rebuild) {
               int before = puddles.pzoptNumSquares();
               build(puddles, b, chunk, z, playerIndex, squares, levelData, mask, jx, jy, camChunkX, camChunkY, interval);
               puddles.pzoptTruncate(before, z); // the RenderData was only scratch space
               // normalise the packed jiggle to zero; the frame's jiggle is a translation on the render thread
               float[] data = b.data;
               float dd = shaderShift ? -jd : 0.0F; // the depth too, when the shader adds the frame's jiggle depth
               for (int v = 0; v < b.count * FLOATS; v += 8) {
                  data[v + 4] -= jx;
                  data[v + 5] -= jy;
                  data[v + 7] += dd;
               }
               b.jx = 0.0F;
               b.jy = 0.0F;
               b.jdepth = shaderShift ? 0.0F : jd;
               b.lightsDirty = false;
               b.dirty = true;
            } else {
               if (b.lightsDirty) {
                  b.lightsDirty = false;
                  if (patchLights(b, playerIndex, noLighting)) {
                     b.dirty = true;
                     lightUploads++;
                  }
                  lightPatches++;
               }
               float ddepth = shaderShift ? 0.0F : jd - b.jdepth;
               b.jdepth = shaderShift ? 0.0F : jd;
               if (camChunkX != b.camChunkX || camChunkY != b.camChunkY) {
                  float now = IsoDepthHelper.getChunkDepthData(camChunkX, camChunkY, chunk.wx, chunk.wy, z).depthStart;
                  float then = IsoDepthHelper.getChunkDepthData(b.camChunkX, b.camChunkY, chunk.wx, chunk.wy, z).depthStart;
                  ddepth += now - then;
                  b.camChunkX = camChunkX;
                  b.camChunkY = camChunkY;
               }
               if (ddepth != 0.0F) {
                  float[] data = b.data;
                  for (int v = 0; v < b.count * FLOATS; v += 8) {
                     data[v + 7] += ddepth;
                  }
                  b.dirty = true;
                  depthUploads++;
               }
            }
            if (b.count > 0) {
               PuddleVbo.add(f, b, b.dirty);
               b.dirty = false;
               reused++;
            }
         }

         PuddleVbo.submit(f);
      }
   }

   /** Re-reads the four vertex lights of every square; true when any colour changed. */
   private static boolean patchLights(Batch b, int playerIndex, boolean noLighting) {
      float[] data = b.data;
      boolean changed = false;
      int o = 0;
      for (int s = 0; s < b.count; s++) {
         IsoGridSquare sq = b.squares[s];
         // vertex order of IsoPuddlesGeometry.updateLighting: light verts 0, 3, 2, 1
         int c0;
         int c1;
         int c2;
         int c3;
         if (noLighting) {
            c0 = c1 = c2 = c3 = -1;
         } else {
            c0 = sq.getVertLight(0, playerIndex);
            c1 = sq.getVertLight(3, playerIndex);
            c2 = sq.getVertLight(2, playerIndex);
            c3 = sq.getVertLight(1, playerIndex);
         }
         if (Float.floatToRawIntBits(data[o + 6]) != c0) { data[o + 6] = Float.intBitsToFloat(c0); changed = true; }
         if (Float.floatToRawIntBits(data[o + 14]) != c1) { data[o + 14] = Float.intBitsToFloat(c1); changed = true; }
         if (Float.floatToRawIntBits(data[o + 22]) != c2) { data[o + 22] = Float.intBitsToFloat(c2); changed = true; }
         if (Float.floatToRawIntBits(data[o + 30]) != c3) { data[o + 30] = Float.intBitsToFloat(c3); changed = true; }
         o += FLOATS;
      }
      return changed;
   }

   private static long flagMask(ChunkLevelData levelData, int playerIndex) {
      byte[] flags = levelData.squareFlags[playerIndex];
      long mask = 0L;
      for (int i = 0; i < 64; i++) {
         if ((flags[i] & 1) != 0) {
            mask |= 1L << i;
         }
      }
      return mask;
   }

   /** Stock filter + stock packing (IsoPuddles.pzoptPack) into RenderData, then a copy of the block into the batch. */
   private static void build(IsoPuddles puddles, Batch b, IsoChunk chunk, int z, int playerIndex, java.util.List<IsoGridSquare> squares,
                             ChunkLevelData levelData, long mask, float jx, float jy, int camChunkX, int camChunkY, int interval) {
      ArrayList<IsoGridSquare> list = scratch;
      list.clear();
      boolean anyFloor = PerformanceSettings.puddlesQuality >= 2;
      for (int j = 0; j < squares.size(); j++) {
         IsoGridSquare square = squares.get(j);
         if (square.getZ() != z || !levelData.shouldRenderSquare(playerIndex, square)) {
            continue;
         }
         IsoObject floor = square.getFloor();
         if (floor == null) {
            continue;
         }
         if (!anyFloor && floor.getRenderInfo(playerIndex).layer == ObjectRenderLayer.TranslucentFloor) {
            continue;
         }
         IsoPuddlesGeometry pg = square.getPuddles();
         if (pg != null && pg.shouldRender()) {
            list.add(square);
         }
      }

      int before = puddles.pzoptNumSquares();
      puddles.pzoptPack(list, z);
      int count = puddles.pzoptNumSquares() - before;
      if (b.data.length < count * FLOATS) {
         b.data = new float[count * FLOATS];
      }
      if (count > 0) {
         System.arraycopy(puddles.pzoptData(), before * FLOATS, b.data, 0, count * FLOATS);
      }
      if (b.squares.length < count) {
         b.squares = new IsoGridSquare[count];
      }
      // pzoptPack keeps only the squares whose geometry passed shouldRender(), in list order
      int k = 0;
      for (int j = 0; j < list.size() && k < count; j++) {
         IsoPuddlesGeometry pg = list.get(j).getPuddles();
         if (pg != null && pg.shouldRender()) {
            b.squares[k++] = list.get(j);
         }
      }
      Arrays.fill(b.squares, k, b.squares.length, null);
      b.count = k == count ? count : 0; // a mismatch means the geometry changed under us: rebuild next frame
      b.listSize = squares.size();
      b.flagMask = mask;
      b.jx = jx;
      b.jy = jy;
      b.camChunkX = camChunkX;
      b.camChunkY = camChunkY;
      b.builtFrame = frame;
      int stagger = Math.floorMod(chunk.wx * 31 + chunk.wy * 17 + z * 7, interval);
      b.expiryFrame = frame + interval + stagger;
      b.invalid = k != count;
      list.clear();
      built++;
   }

   /** Copy the batch into RenderData and patch lights, jiggle and depth for this frame. */
   private static void reuse(IsoPuddles puddles, Batch b, IsoChunk chunk, int z, int playerIndex,
                             float jx, float jy, float jd, int camChunkX, int camChunkY, boolean noLighting) {
      int n = b.count;
      int base = puddles.pzoptAppend(b.data, n, z);
      float[] data = puddles.pzoptData();
      float dx = jx - b.jx;
      float dy = jy - b.jy;
      float ddepth = jd - b.jdepth;
      if (camChunkX != b.camChunkX || camChunkY != b.camChunkY) {
         float now = IsoDepthHelper.getChunkDepthData(camChunkX, camChunkY, chunk.wx, chunk.wy, z).depthStart;
         float then = IsoDepthHelper.getChunkDepthData(b.camChunkX, b.camChunkY, chunk.wx, chunk.wy, z).depthStart;
         ddepth += now - then;
      }
      boolean move = dx != 0.0F || dy != 0.0F;
      int o = base * FLOATS;
      for (int s = 0; s < n; s++) {
         IsoGridSquare sq = b.squares[s];
         // vertex order of IsoPuddlesGeometry.updateLighting: light verts 0, 3, 2, 1
         if (noLighting) {
            data[o + 6] = data[o + 14] = data[o + 22] = data[o + 30] = Float.intBitsToFloat(-1);
         } else {
            data[o + 6] = Float.intBitsToFloat(sq.getVertLight(0, playerIndex));
            data[o + 14] = Float.intBitsToFloat(sq.getVertLight(3, playerIndex));
            data[o + 22] = Float.intBitsToFloat(sq.getVertLight(2, playerIndex));
            data[o + 30] = Float.intBitsToFloat(sq.getVertLight(1, playerIndex));
         }
         if (move) {
            for (int v = o; v < o + FLOATS; v += 8) {
               data[v + 4] += dx;
               data[v + 5] += dy;
            }
         }
         if (ddepth != 0.0F) {
            data[o + 7] += ddepth;
            data[o + 15] += ddepth;
            data[o + 23] += ddepth;
            data[o + 31] += ddepth;
         }
         o += FLOATS;
      }
      reused++;
   }

   public static String stats() {
      String s = "puddle cache: frames=" + frame + " batches built=" + built + " reused=" + reused
         + " rebuilt (bake=" + rebuiltInvalid + " cutaway=" + rebuiltFlags + " expired=" + rebuiltExpired + ")";
      if (PuddleVbo.enabled()) {
         s += " light patches=" + lightPatches + " uploads (light=" + lightUploads + " depth=" + depthUploads + ") | " + PuddleVbo.stats();
      }
      return s;
   }
}
