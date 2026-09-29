package pzopt;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Harness rig of the stock floor blood decals (2026-09-29). With the chunk textures (B42 default) the floor splats are
 * drawn into the chunk-level bake: {@code FBORenderCell.renderOneLevel_Blood} walks the splat lists of the chunk and its
 * eight neighbours (up to 1,000 live + the fading overflow each) on every bake of a level, and every
 * {@code IsoChunk.addBloodSplat} on the game thread marks the level {@code DIRTY_BLOOD}: a full re-bake of that chunk level.
 * <ul>
 *   <li>{@code blood_fill=N} — 1 s into the settle, N splats (stock types 0-20, fixed seed) at random points of every
 *       loaded chunk's ground level: a battlefield's resident load (1,000 = the per-chunk cap; more spill into the
 *       fade list);</li>
 *   <li>{@code blood_rate=R} — from the route start, R splats a second (fractional ok) on the player's level within
 *       {@code blood_radius} (default 6) squares of the player: fresh blood in a fight;</li>
 *   <li>{@code blood_probe=true} (implied by either) — times, on the game thread, each chunk-level bake by its dirty flags
 *       before the bake (blood only / blood + other / no blood) and the bake's blood part (the nine
 *       renderOneLevel_Blood calls, with the splats they walk), and once a second writes a row of {@code pzopt-blood.out}
 *       (settle rows have t &lt; 0). {@code blood_probe=} in pzopt-bench.out sums the route.</li>
 * </ul>
 */
public final class BloodProbe {
   private static final int FILL = Integer.parseInt(HarnessFlags.get("blood_fill", "0").trim());
   private static final float RATE = Float.parseFloat(HarnessFlags.get("blood_rate", "0").trim());
   private static final int RADIUS = Integer.parseInt(HarnessFlags.get("blood_radius", "6").trim());
   private static final int BURST = Integer.parseInt(HarnessFlags.get("blood_burst", "0").trim());
   private static final float BURST_AT = Float.parseFloat(HarnessFlags.get("blood_burst_at", "2").trim());
   private static boolean burstDone;
   private static final Random burstRng = new Random(0xB1005L);
   public static final boolean ON = FILL > 0 || RATE > 0f || BURST > 0 || Boolean.parseBoolean(HarnessFlags.get("blood_probe", "false").trim());

   private static final int ONLY = 0, MIXED = 1, OTHER = 2;
   private static final Random rng = new Random(0x5B100DL);
   private static final StringBuilder rows = new StringBuilder(1 << 14);

   private static long pendingFlags = -1L; // dirty flags of the level renderOneLevel is baking, -1 = not baking
   private static long firstTickNs, routeStartNs, rowStartNs, lastTickNs;
   private static boolean filled;
   private static int fillAdded, fillChunks;
   private static double rateCarry;

   // per row, then summed over the route
   private static final long[] bakes = new long[3], bakeNs = new long[3];
   private static long sections, sectionNs, sectionMaxNs, walked, frames, frameNs, frameMaxNs, rigAdded;
   private static final long[] routeBakes = new long[3], routeBakeNs = new long[3];
   private static long routeSections, routeSectionNs, routeSectionMaxNs, routeWalked, routeFrames, routeFrameNs, routeRigAdded;
   private static int routeRows, maxResident, maxFade;

   private BloodProbe() {
   }

   /** FBORenderCell.renderOneLevel, game thread: this level is about to bake (after every budget), with these dirty flags. */
   public static void bake(FBORenderLevels levels, int level, float zoom) {
      long flags = 0L;
      for (int bit = 0; bit < 16; bit++) {
         if (levels.isDirty(level, 1L << bit, zoom)) {
            flags |= 1L << bit;
         }
      }
      pendingFlags = flags;
   }

   /** FBORenderCell.renderOneChunk, after each renderOneLevel; t0 = nanoTime before it. */
   public static void levelDone(long t0) {
      if (pendingFlags < 0L) {
         return;
      }
      long ns = System.nanoTime() - t0;
      int k = (pendingFlags & 1L) == 0L ? OTHER : pendingFlags == 1L ? ONLY : MIXED;
      bakes[k]++;
      bakeNs[k] += ns;
      pendingFlags = -1L;
   }

   /** FBORenderCell.renderOneLevel, after the nine renderOneLevel_Blood calls of a bake. */
   public static void section(IsoChunk c, int level, long ns) {
      sections++;
      sectionNs += ns;
      sectionMaxNs = Math.max(sectionMaxNs, ns);
      IsoCell cell = IsoWorld.instance.currentCell;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            IsoChunk n = dx == 0 && dy == 0 ? c : cell.getChunk(c.wx + dx, c.wy + dy);
            if (n != null) {
               walked += n.floorBloodSplats.size() + n.floorBloodSplatsFade.size();
            }
         }
      }
   }

   /** Harness, game thread, every frame of settle and route. */
   static void tick(IsoPlayer p, long nowNs) {
      if (!ON) {
         return;
      }
      if (firstTickNs == 0L) {
         firstTickNs = nowNs;
         rowStartNs = nowNs;
      }
      if (lastTickNs != 0L) {
         long d = nowNs - lastTickNs;
         frames++;
         frameNs += d;
         frameMaxNs = Math.max(frameMaxNs, d);
      }
      lastTickNs = nowNs;
      if (!filled && FILL > 0 && nowNs - firstTickNs >= 1_000_000_000L) {
         filled = true;
         fill();
      }
      if (nowNs - rowStartNs >= 1_000_000_000L) {
         row(nowNs);
      }
   }

   /** Harness, game thread, per frame after tick: the fight-rate splats (kept apart so the frame delta above is clean). */
   static void spray(IsoPlayer p, float dt) {
      if (ON && routeStartNs != 0L && BURST > 0 && !burstDone && System.nanoTime() - routeStartNs >= (long)(BURST_AT * 1e9)) {
         // blood_burst=N: N splats in one frame at a fixed seed around the player (deterministic screenshots)
         burstDone = true;
         int z = (int)Math.floor(p.getZ()), added = 0;
         float px = (float)Math.floor(p.getX()) + 0.5F, py = (float)Math.floor(p.getY()) + 0.5F;
         for (int i = 0; i < BURST; i++) {
            float x = px + (burstRng.nextFloat() * 2f - 1f) * RADIUS;
            float y = py + (burstRng.nextFloat() * 2f - 1f) * RADIUS;
            IsoChunk c = IsoWorld.instance.currentCell.getChunkForGridSquare((int)Math.floor(x), (int)Math.floor(y), z);
            if (c != null) {
               int before = c.floorBloodSplats.size();
               c.addBloodSplat(x, y, z, burstRng.nextInt(20));
               added += c.floorBloodSplats.size() != before || c.floorBloodSplats.isFull() ? 1 : 0;
            }
         }
         rigAdded += added;
         Log.info("harness: blood_burst=" + BURST + ": " + added + " splats around " + px + "," + py + " at route +" + BURST_AT + " s");
      }
      if (!ON || routeStartNs == 0L || RATE <= 0f) {
         return;
      }
      rateCarry += RATE * dt;
      int z = (int)Math.floor(p.getZ());
      while (rateCarry >= 1.0) {
         rateCarry -= 1.0;
         float x = p.getX() + (rng.nextFloat() * 2f - 1f) * RADIUS;
         float y = p.getY() + (rng.nextFloat() * 2f - 1f) * RADIUS;
         IsoChunk c = IsoWorld.instance.currentCell.getChunkForGridSquare((int)Math.floor(x), (int)Math.floor(y), z);
         if (c != null) {
            int before = c.floorBloodSplats.size() + c.floorBloodSplatsFade.size();
            c.addBloodSplat(x, y, z, rng.nextInt(20));
            if (c.floorBloodSplats.size() + c.floorBloodSplatsFade.size() != before || c.floorBloodSplats.isFull()) {
               rigAdded++;
            }
         }
      }
   }

   static void routeStart(long nowNs) {
      if (!ON) {
         return;
      }
      row(nowNs); // the settle's last partial second
      routeStartNs = nowNs;
      rateCarry = 0.0;
      for (int k = 0; k < 3; k++) {
         routeBakes[k] = 0L;
         routeBakeNs[k] = 0L;
      }
      routeSections = routeSectionNs = routeSectionMaxNs = routeWalked = routeFrames = routeFrameNs = routeRigAdded = 0L;
      routeRows = 0;
   }

   private static void fill() {
      IsoChunkMap map = IsoWorld.instance.currentCell.getChunkMap(0);
      int w = IsoChunkMap.chunkGridWidth;
      for (int cx = 0; cx < w; cx++) {
         for (int cy = 0; cy < w; cy++) {
            IsoChunk c = map.getChunk(cx, cy);
            if (c == null) {
               continue;
            }
            fillChunks++;
            int before = c.floorBloodSplats.size();
            for (int i = 0; i < FILL; i++) {
               c.addBloodSplat(c.wx * 8 + rng.nextFloat() * 8f, c.wy * 8 + rng.nextFloat() * 8f, 0f, rng.nextInt(20));
            }
            fillAdded += c.floorBloodSplats.size() - before;
         }
      }
      Log.info("harness: blood_fill=" + FILL + ": " + fillAdded + " splats kept in " + fillChunks + " chunks (only solid floor squares take one)");
   }

   private static void row(long nowNs) {
      int resident = 0, fade = 0, chunks = 0, bloody = 0;
      IsoChunkMap map = IsoWorld.instance.currentCell == null ? null : IsoWorld.instance.currentCell.getChunkMap(0);
      if (map != null) {
         int w = IsoChunkMap.chunkGridWidth;
         for (int cx = 0; cx < w; cx++) {
            for (int cy = 0; cy < w; cy++) {
               IsoChunk c = map.getChunk(cx, cy);
               if (c != null) {
                  chunks++;
                  resident += c.floorBloodSplats.size();
                  fade += c.floorBloodSplatsFade.size();
                  if (!c.floorBloodSplats.isEmpty()) {
                     bloody++;
                  }
               }
            }
         }
      }
      boolean route = routeStartNs != 0L;
      rows.append(String.format(Locale.ROOT, "%.1f\t%d\t%.2f\t%.1f\t%d\t%d\t%d\t%d\t%d\t%d\t%.2f\t%d\t%.2f\t%d\t%.2f\t%d\t%.2f\t%.0f\t%d\n",
            route ? (nowNs - routeStartNs) / 1e9f : -1f, frames, frames == 0 ? 0f : frameNs / 1e6f / frames, frameMaxNs / 1e6f, chunks, bloody, resident, fade, rigAdded,
            bakes[ONLY], bakeNs[ONLY] / 1e6f, bakes[MIXED], bakeNs[MIXED] / 1e6f, bakes[OTHER], bakeNs[OTHER] / 1e6f,
            sections, sectionNs / 1e6f, sectionMaxNs / 1e3f, walked));
      if (route) {
         routeRows++;
         for (int k = 0; k < 3; k++) {
            routeBakes[k] += bakes[k];
            routeBakeNs[k] += bakeNs[k];
         }
         routeSections += sections;
         routeSectionNs += sectionNs;
         routeSectionMaxNs = Math.max(routeSectionMaxNs, sectionMaxNs);
         routeWalked += walked;
         routeFrames += frames;
         routeFrameNs += frameNs;
         routeRigAdded += rigAdded;
         maxResident = Math.max(maxResident, resident);
         maxFade = Math.max(maxFade, fade);
      }
      for (int k = 0; k < 3; k++) {
         bakes[k] = 0L;
         bakeNs[k] = 0L;
      }
      sections = sectionNs = sectionMaxNs = walked = frames = frameNs = frameMaxNs = rigAdded = 0L;
      rowStartNs = nowNs;
   }

   /** Line for pzopt-bench.out; also writes pzopt-blood.out. */
   static String summary() {
      if (!ON) {
         return "";
      }
      if (routeStartNs != 0L) {
         row(System.nanoTime());
      }
      File f = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-blood.out");
      try (FileWriter w = new FileWriter(f)) {
         w.write("# pzopt blood probe: blood_fill=" + FILL + " (" + fillAdded + " kept in " + fillChunks + " chunks) blood_rate=" + RATE + " blood_radius=" + RADIUS
               + " bloodDecals option=" + Core.getInstance().getOptionBloodDecals() + "; one row a second, t=-1 = settle; bake ms = game-thread time of the"
               + " chunk-level bakes by their dirty flags (only = DIRTY_BLOOD alone), blood_* = the nine renderOneLevel_Blood calls of those bakes\n");
         w.write("t\tframes\tframe_ms\tframe_max_ms\tchunks\tbloody_chunks\tsplats\tfade\trig_added\tbakes_only\tms_only\tbakes_mixed\tms_mixed\tbakes_other\tms_other"
               + "\tblood_sections\tblood_ms\tblood_max_us\tsplats_walked\n");
         w.write(rows.toString());
      } catch (IOException e) {
         Log.warn("harness: could not write pzopt-blood.out: " + e);
      }
      double secs = routeFrameNs / 1e9;
      return String.format(Locale.ROOT,
            "\nblood_probe=fill=%d rate=%.1f option=%d route_s=%.1f frame_ms=%.3f rig_added=%d max_splats=%d max_fade=%d"
                  + " bakes_only=%d (%.3f ms each, %.2f ms/s) bakes_mixed=%d (%.3f ms each) bakes_other=%d (%.3f ms each)"
                  + " blood_sections=%d (%.1f us each, max %.0f us, %.2f ms/s, %.0f splats walked each)",
            FILL, RATE, Core.getInstance().getOptionBloodDecals(), secs, routeFrames == 0 ? 0.0 : routeFrameNs / 1e6 / routeFrames, routeRigAdded, maxResident, maxFade,
            routeBakes[ONLY], each(routeBakeNs[ONLY], routeBakes[ONLY]) / 1e3, secs > 0 ? routeBakeNs[ONLY] / 1e6 / secs : 0.0,
            routeBakes[MIXED], each(routeBakeNs[MIXED], routeBakes[MIXED]) / 1e3, routeBakes[OTHER], each(routeBakeNs[OTHER], routeBakes[OTHER]) / 1e3,
            routeSections, each(routeSectionNs, routeSections), routeSectionMaxNs / 1e3, secs > 0 ? routeSectionNs / 1e6 / secs : 0.0,
            routeSections == 0 ? 0.0 : (double)routeWalked / routeSections) + "\nblood_decals=" + BloodDecals.stats() + "\nblood_wet=" + BloodWet.stats();
   }

   private static double each(long ns, long n) {
      return n == 0 ? 0.0 : ns / 1e3 / n;
   }
}
