package pzopt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.TextureDraw;
import zombie.iso.fboRenderChunk.FBORenderChunk;

/**
 * The relief of each chunk texture (Config {@code reliefAux}), written once after the texture bakes, so a frame reads at
 * most one byte where a moving light needs it (the chunk textures overlap ~4x on screen: every fragment's work is paid a
 * few times per pixel).
 *
 * <p>The code (R8): 0 = no relief (an object, an edge, an empty texel); else {@code 1 + plane * 81 + (ku + 4) * 9 + (kv + 4)}:
 * the texel's plane (0 floor, 1 the wall facing +x, 2 the wall facing +y) from the chunk depth two texels around it, and the
 * art's height slope along u and v (the green of the four 2x2 blocks around the texel, soft-limited at reliefGmaxPct) in nine
 * levels each on a square-root scale.
 *
 * <p>Sun and moon ({@code reliefSunMode=bake}, the default): the relief under the key light is baked into the texture's
 * colour like the AO / sun terms, so the composite pays nothing for it. At the end of a bake (before the stock mipmap
 * build): the encode pass, then one relight pass that computes the relief factor from the codes (the facing of the relief
 * normal against the plane's, times a self-shadow ray that rebuilds the height along the light from the stored slopes),
 * weighted by the texel's direct-sun share (the AO pass's kept term, or the full share on bare open ground), and writes it
 * both onto the colour (2 src dst blend) and into G (a second R8, the factor applied / 2; its own framebuffer with the
 * colour as attachment 0). When the key light steps (SunShadow's steps, as the sun shadows), the textures on screen get new /
 * old, a few a frame (reliefStepBudget), texel by texel exact against G, their mip levels too. A texture shown without a code
 * bakes again. Zoomed out past reliefMaxZoom nothing is baked, read or held.
 *
 * <p>The torch, headlights and lamps read the code per fragment in pixelLight's composite (their direction changes every
 * frame): one fetch, and no depth reads for floor and wall texels.
 */
public final class ReliefAux {
   // the composite's samplers: DIFFUSE 0, DEPTH 1, fog 4, HDR 5-8, pixel light 9-12, cloud shadows 13-14, sway 28-31, god rays'
   // haze 29; units 2 and 3 are bound only by passes outside the composite (the AO kernel, ours), which bind before use
   // (13 was the cloud field's: with a cloud up, each read the other's texture)
   static final int AUX_UNIT = 2, HORIZON_UNIT = 3;
   private static final int K_ENCODE = 0, K_BAKE = 1, K_STEP = 2;

   private ReliefAux() {
   }

   static boolean wanted() {
      return Config.RELIEF && Config.RELIEF_AUX && !CoreGl.legacyMac() && Overrides.enabled() && (Relief.on() || Relief.sunWanted() || sunBake());
   }

   /** The sun / moon relief is baked into the chunk textures (needs the AO pass's kept term for the direct-sun share). */
   static boolean sunBake() {
      return Config.RELIEF && Config.RELIEF_AUX && "bake".equals(Config.RELIEF_SUN_MODE) && Config.RELIEF_SUN_PCT > 0 && Config.SUN_SHADOWS && !CoreGl.legacyMac()
         && Overrides.enabled() && ChunkAo.enabled();
   }

   // ------------------------------------------------------------------------------------------------ game thread

   // The game thread keys textures by FBORenderChunk.index (as the AO pass): a new texture's GL names are created on the render
   // thread (TexDeferedCreation), so its depth's id is not known yet when its first bake ends. The render thread keys the codes
   // by the depth texture's GL id: the composite's per-draw hook runs on the StartShader op, whose tex1 is the chunk's depth
   // (its colour is on the next op), as pixelLight's light lists and the AO terms key theirs.
   private static final class State {
      long chunkKey; // the chunk / levels / size the texture held when its code was made (a pooled texture moves on)
      long applied = Long.MIN_VALUE; // bake mode: the key light step its factor is for
      boolean known; // a code was made (queued) for this chunk key and is held
      boolean had; // bake mode: its colour carries the baked relief (a dropped code keeps it; the light steps skip it until it bakes again)
      boolean rebakeAsked;
      boolean marked; // composite mode: baked since its last encode
   }

   private static final HashMap<Integer, State> STATES = new HashMap<>();
   private static final HashMap<Integer, String> LAST_BAKE = new HashMap<>(); // dev (devReliefTrace)
   private static long rebakesDone;
   private static long marked, encoded, baked, stepped, rebakes, dropped, deferredFrames, stepDeferredFrames, flushes;
   private static final float[] LIGHT = new float[4];

   private static long chunkKey(FBORenderChunk rc) {
      zombie.iso.IsoChunk c = rc.chunk;
      return c == null ? 0L : (((long)c.wx * 73856093L) ^ ((long)c.wy * 19349663L) ^ ((long)rc.getMinLevel() * 83492791L)) * 31L + rc.w * 7919L + rc.h;
   }

   private static State state(FBORenderChunk rc) {
      State s = STATES.get(rc.index);
      if (s == null) {
         if (STATES.size() > 8192) {
            STATES.clear();
         }
         s = new State();
         STATES.put(rc.index, s);
      }
      long k = chunkKey(rc);
      if (s.chunkKey != k) {
         s.chunkKey = k;
         s.applied = Long.MIN_VALUE;
         s.known = false;
         s.had = false;
         s.rebakeAsked = false;
         s.marked = false;
      }
      return s;
   }

   /** The key light for the baked relief now: its (stepped) direction and the relief's strength (0: none, the sun is down). */
   private static long light(float[] out) {
      boolean up = SunShadow.dir[3] > 0F && SunShadow.world[2] > 0F;
      out[0] = SunShadow.world[0];
      out[1] = SunShadow.world[1];
      out[2] = SunShadow.world[2];
      out[3] = up ? Config.RELIEF_SUN_PCT / 100F : 0F;
      return up ? SunShadow.stepSerial() : 0L;
   }

   /** A chunk texture finished a bake (its top level, framebuffer still bound, before FBORenderChunkManager.endRenderChunkLevel). */
   public static void baked(FBORenderChunk rc) {
      if (rc == null || rc.tex == null || rc.depth == null || !wanted()) {
         return;
      }
      marked++;
      State s = state(rc);
      if (Config.DEV_RELIEF_TRACE) {
         LAST_BAKE.put(rc.index, rc.chunk == null ? "null" : rc.chunk.wx + "," + rc.chunk.wy + " lv " + rc.getMinLevel() + ".." + rc.getTopLevel() + " " + rc.w + "x" + rc.h + " f" + flushes + " zoom " + zombie.core.Core.getInstance().getZoom(0) + (s.known ? "" : " (no relief: zoomed out)"));
      }
      if (zombie.core.Core.getInstance().getZoom(0) >= Config.RELIEF_MAX_ZOOM) {
         s.known = false; // zoomed out past the relief (its detail under half a pixel): none, and no code held for it
         return;
      }
      if (sunBake()) {
         // encode and multiply now, inside the bake: the mipmap build that follows carries the factor to every level
         long key = light(LIGHT);
         Batch b = new Batch();
         b.add(K_BAKE, rc, 0, LIGHT);
         GpuSections.begin("relief.bake");
         SpriteRenderer.instance.drawGeneric(b);
         GpuSections.end("relief.bake");
         s.applied = key;
         s.known = true;
         s.had = true;
         if (s.rebakeAsked) {
            s.rebakeAsked = false;
            rebakesDone++;
         }
         baked++;
         return;
      }
      s.marked = true;
      INVALID.add(rc);
   }

   private static final ArrayList<FBORenderChunk> INVALID = new ArrayList<>();
   private static final ArrayList<FBORenderChunk> NONE = new ArrayList<>();

   /** Game thread, before the composite (after the bakes and ChunkAo.flush): the frame's encodes / light steps, textures on screen. */
   public static void flush(int playerIndex) {
      if (!wanted()) {
         return;
      }
      Relief.devFrame();
      if (++flushes % 1200L == 60L) {
         Log.info(Relief.stats());
      }
      synchronized (DROPPED) {
         for (int index : DROPPED) {
            State s = STATES.get(index);
            if (s != null) {
               s.known = false; // the render thread freed its code: baked (encoded) again when it shows
               s.applied = Long.MIN_VALUE;
            }
         }
         DROPPED.clear();
      }
      SpriteRenderer.instance.drawGeneric(TICK); // the render thread's frame count (the codes' last use, the bind cache)
      Batch b = null;
      ArrayList<FBORenderChunk> shown = zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.toRenderThisFrame;
      boolean bake = sunBake();
      if (zombie.core.Core.getInstance().getZoom(playerIndex) >= Config.RELIEF_MAX_ZOOM) {
         shown = NONE; // zoomed out: no codes, steps or re-bakes (the composite fades the torch's relief out; codes not drawn are trimmed)
      }
      if (bake) {
         long key = light(LIGHT);
         int budget = Math.max(1, Config.RELIEF_STEP_BUDGET), asks = 2;
         boolean mips = zombie.debug.DebugOptions.instance.fboRenderChunk.mipMaps.getValue();
         for (int i = 0; i < shown.size(); i++) {
            FBORenderChunk rc = shown.get(i);
            if (rc == null || rc.tex == null || rc.depth == null || !rc.isInit || rc.chunk == null) {
               continue;
            }
            State s = state(rc);
            if (!s.known) {
               // never baked with relief (before relief was on, or zoomed out): it bakes again (the bake scheduler spreads
               // them); a texture whose code was dropped keeps the relief it has and skips the light steps until it bakes
               if (asks > 0 && !s.rebakeAsked && !s.had) {
                  asks--;
                  if (Config.DEV_RELIEF_TRACE && (rebakes < 20 || rebakes % 100 == 0)) {
                     Log.info("relief: re-bake asked, index " + rc.index + " now " + rc.chunk.wx + "," + rc.chunk.wy + " lv " + rc.getMinLevel() + ".." + rc.getTopLevel() + " " + rc.w + "x" + rc.h
                        + " f" + flushes + " zoom " + zombie.core.Core.getInstance().getZoom(playerIndex) + ", last bake " + LAST_BAKE.get(rc.index) + ", dirty " + rc.chunk.getRenderLevels(playerIndex).isDirty(rc.getMinLevel(), zombie.core.Core.getInstance().getZoom(playerIndex)));
                  }
                  s.rebakeAsked = true;
                  rc.chunk.getRenderLevels(playerIndex).invalidateLevel(rc.getMinLevel(), FBORenderChunk.DIRTY_REDRAW);
                  rebakes++;
               }
               continue;
            }
            if (s.applied == key) {
               continue;
            }
            if (budget == 0) {
               stepDeferredFrames++;
               break;
            }
            if (b == null) {
               b = new Batch();
            }
            b.add(K_STEP, rc, mips && !rc.highRes ? Config.BAKE_MIP_LEVELS : 0, LIGHT);
            s.applied = key;
            budget--;
         }
      } else {
         int budget = Math.max(1, Config.RELIEF_AUX_BUDGET);
         for (int i = 0; i < shown.size() && budget > 0; i++) {
            FBORenderChunk rc = shown.get(i);
            if (rc == null || rc.tex == null || rc.depth == null || !rc.isInit) {
               continue;
            }
            State s = state(rc);
            if (s.known && !s.marked) {
               continue;
            }
            if (b == null) {
               b = new Batch();
            }
            b.add(K_ENCODE, rc, 0, null);
            s.known = true;
            s.marked = false;
            budget--;
         }
         if (budget == 0) {
            deferredFrames++;
         }
      }
      if (b == null && INVALID.isEmpty()) {
         return;
      }
      if (b == null) {
         b = new Batch();
      }
      b.invalid.addAll(INVALID);
      INVALID.clear();
      boolean timed = b.n > 0;
      if (timed) {
         GpuSections.begin(bake ? "relief.steps" : "relief.codes"); // the passes' GPU time (gpuSections=true)
      }
      SpriteRenderer.instance.drawGeneric(b);
      if (timed) {
         GpuSections.end(bake ? "relief.steps" : "relief.codes");
      }
   }

   private static final TextureDraw.GenericDrawer TICK = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         frame++;
      }
   };

   private static final HashSet<Integer> DROPPED = new HashSet<>(); // render -> game thread: codes freed over the budget (FBORenderChunk.index)

   static String stats() {
      return String.format(java.util.Locale.ROOT, "relief codes: marked %d, encoded %d, baked %d, light steps %d (frames over budget %d), re-bakes asked %d (done %d), frames over the encode budget %d, entries %d (%.0f MB), dropped %d%s",
         marked, encoded, baked, stepped, stepDeferredFrames, rebakes, rebakesDone, deferredFrames, ENTRIES.size(), bytes / 1048576.0, dropped, failed ? ", FAILED" : "")
         + String.format(java.util.Locale.ROOT, ", draws with a code %d, without %d (stale %d)", hits, misses, stale);
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static final class Entry {
      int codeTex, codeFbo, gTex, comboFbo, hTex, hFbo, colour, w, h, index; // + the horizons (RGBA4, reliefHorizon) // the code (R8), the factor applied / 2 (R8, bake mode), the framebuffer onto the colour + G
      boolean valid;
      long lastDrawn;
   }

   private static final HashMap<Integer, Entry> ENTRIES = new HashMap<>();
   private static long bytes, frame, bakePasses;
   private static volatile boolean failed;
   private static int vbo, mipFbo;
   private static final Program ENCODE = new Program("encode"), RELIGHT = new Program("relight"), HORIZON = new Program("horizon");
   private static final int[] VIEWPORT = new int[4];

   private static final class Batch extends TextureDraw.GenericDrawer {
      final ArrayList<FBORenderChunk> invalid = new ArrayList<>();
      int n;
      int[] kind = new int[8], mips = new int[8], index = new int[8];
      FBORenderChunk[] rc = new FBORenderChunk[8];
      float[] light = new float[32];

      void add(int k, FBORenderChunk r, int m, float[] l) {
         if (this.n == this.kind.length) {
            int n2 = this.n * 2;
            this.kind = java.util.Arrays.copyOf(this.kind, n2);
            this.mips = java.util.Arrays.copyOf(this.mips, n2);
            this.index = java.util.Arrays.copyOf(this.index, n2);
            this.rc = java.util.Arrays.copyOf(this.rc, n2);
            this.light = java.util.Arrays.copyOf(this.light, n2 * 4);
         }
         this.kind[this.n] = k;
         this.rc[this.n] = r;
         this.index[this.n] = r.index;
         this.mips[this.n] = m;
         if (l != null) {
            System.arraycopy(l, 0, this.light, this.n * 4, 4);
         }
         this.n++;
      }

      @Override
      public void render() {
         for (FBORenderChunk r : this.invalid) {
            Entry e = r.depth != null ? ENTRIES.get(r.depth.getID()) : null; // the GL names exist on the render thread
            if (e != null) {
               e.valid = false;
            }
         }
         if (this.n > 0 && !failed) {
            try {
               run(this);
            } catch (Throwable t) {
               failed = true;
               Log.warn("relief: the relief passes failed, relief off: " + t);
            }
         }
         trim();
      }
   }

   private static void run(Batch b) {
      if (vbo == 0 && !init()) {
         failed = true;
         return;
      }
      int prevFbo = zombie.core.textures.TextureFBO.lastID; // in a bake: the texture's own framebuffer
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_ALPHA_TEST); // the sprite renderer leaves it on: a code of 0 would be discarded
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDepthMask(false);
      GL11.glColorMask(true, true, true, true);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
      GL20.glEnableVertexAttribArray(0);
      for (int i = 1; i < 5; i++) {
         GL20.glDisableVertexAttribArray(i);
      }
      GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
      for (int i = 0; i < b.n; i++) {
         FBORenderChunk rc = b.rc[i];
         int colour = rc.tex != null ? rc.tex.getID() : -1, depth = rc.depth != null ? rc.depth.getID() : -1;
         if (colour <= 0 || depth <= 0) {
            continue;
         }
         Entry e = entry(depth, colour, rc.w, rc.h);
         if (e == null) {
            continue;
         }
         e.index = b.index[i];
         e.lastDrawn = frame;
         int term = b.kind[i] == K_ENCODE ? -2 : ChunkAo.cloudTerm(depth); // the direct-sun share: kept term, -1 bare open ground, -2 none
         switch (b.kind[i]) {
            case K_ENCODE:
               encode(e, colour, depth);
               encoded++;
               break;
            case K_BAKE:
               Timing.stamp(0);
               encode(e, colour, depth);
               encoded++;
               Timing.stamp(1);
               // the factor for this light into the colour (before the bake's mipmap build) and G in one pass; no share known
               // yet (a deferred AO compute): the factor 1, the next light step brings it
               relight(e, term == -2 ? -3 : term, b.light, i * 4, 0, 0);
               Timing.stamp(2);
               Timing.collect();
               bakePasses++;
               break;
            default: // K_STEP: new / old into the mips (they read the old G), then level 0 and G
               if (!e.valid) {
                  break;
               }
               if (b.mips[i] > 0) {
                  if (mipFbo == 0) {
                     mipFbo = GL30.glGenFramebuffers();
                  }
                  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mipFbo);
                  GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
                  int levels = Math.min(b.mips[i] + 1, 32 - Integer.numberOfLeadingZeros(Math.max(e.w, e.h)));
                  for (int k = 1; k < levels; k++) {
                     GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colour, k);
                     relight(e, term == -2 ? -3 : term, b.light, i * 4, 2, k);
                  }
                  GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
               }
               relight(e, term == -2 ? -3 : term, b.light, i * 4, 1, 0);
               stepped++;
               break;
         }
      }
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
      GL11.glViewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glColorMask(true, true, true, true);
      for (int u = 3; u >= 0; u--) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      }
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      for (int i = 0; i < 5; i++) {
         GL20.glEnableVertexAttribArray(i);
      }
      ShaderHelper.forgetCurrentlyBound(); // raw binds bypass ShaderHelper's cache
      ShaderHelper.glUseProgramObjectARB(0);
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      GL11.glDepthMask(true);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GLStateRenderThread.restore();
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
   }

   /** dev (devReliefTiming): GPU time of a bake's two passes (encode, relight), a log line every 300 bakes. */
   private static final class Timing {
      private static int[] q;
      private static int slot;
      private static final boolean[] pending = new boolean[16];
      private static final double[] sum = new double[2];
      private static long n;

      static void stamp(int i) {
         if (!Config.DEV_RELIEF_TIMING) {
            return;
         }
         if (q == null) {
            q = new int[16 * 3];
            GL15.glGenQueries(q);
         }
         org.lwjgl.opengl.GL33.glQueryCounter(q[slot * 3 + i], org.lwjgl.opengl.GL33.GL_TIMESTAMP);
      }

      static void collect() {
         if (!Config.DEV_RELIEF_TIMING) {
            return;
         }
         pending[slot] = true;
         slot = (slot + 1) % 16;
         if (pending[slot] && GL15.glGetQueryObjecti(q[slot * 3 + 2], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            long t0 = org.lwjgl.opengl.GL33.glGetQueryObjecti64(q[slot * 3], GL15.GL_QUERY_RESULT);
            for (int k = 1; k < 3; k++) {
               long t = org.lwjgl.opengl.GL33.glGetQueryObjecti64(q[slot * 3 + k], GL15.GL_QUERY_RESULT);
               sum[k - 1] += (t - t0) / 1e3;
               t0 = t;
            }
            pending[slot] = false;
            if (++n % 300L == 0L) {
               Log.info(String.format(java.util.Locale.ROOT, "relief bake gpu: encode %.1f relight %.1f us (%d bakes)", sum[0] / n, sum[1] / n, n));
            }
         }
      }
   }

   private static void bind(int unit, int tex) {
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
   }

   private static void commonUniforms(Program p) {
      float ts = zombie.core.Core.tileScale;
      GL20.glUniform4f(p.u("P"), 1F / (32F * ts), (Config.AO_CHUNK_FLIP ? 1F : -1F) / (16F * ts), (float)(-1.0 / PixelLight.DEPTH_PER_XY), Config.RELIEF_GMAX_PCT / 100F);
      GL20.glUniform4f(p.u("Q"), Config.RELIEF_SNAP_PCT / 100F, 0.05F * Config.RELIEF_DEPTH_PCT / 100F, Config.RELIEF_SHADOW_STEPS, Config.RELIEF_SHADOW_PCT / 100F);
   }

   /** The code of every texel into the entry's code texture. */
   private static void encode(Entry e, int colour, int depth) {
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, e.codeFbo);
      GL11.glViewport(0, 0, e.w, e.h);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glColorMask(true, true, true, true);
      ENCODE.use();
      commonUniforms(ENCODE);
      GL20.glUniform1i(ENCODE.u("C"), 0);
      GL20.glUniform1i(ENCODE.u("D"), 1);
      bind(1, depth);
      bind(0, colour);
      GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
      e.valid = true;
      if (Config.RELIEF_HORIZON && e.hFbo != 0) {
         // the horizons along +u, -u, +v, -v from the codes just written (the torch's self-shadow in one fetch)
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, e.hFbo);
         HORIZON.use();
         commonUniforms(HORIZON);
         GL20.glUniform1i(HORIZON.u("A"), 0);
         GL20.glUniform2f(HORIZON.u("H"), Config.RELIEF_HORIZON_STEPS, 1F / HORIZON_TAN_MAX);
         bind(0, e.codeTex);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
      }
   }

   static final float HORIZON_TAN_MAX = 2F; // the RGBA4 horizons store tan(elevation) / this

   private static final float[] HALF = {0.5F, 0.5F, 0.5F, 0.5F};

   /**
    * The relief factor for this light from the entry's codes (term: the kept term, -1 bare = the full share, -3 none = 1) onto
    * the colour: mode 0 (a bake) the factor, into the colour (2 src dst blend) and G (0.5 cleared first) in one pass; mode 1
    * (a light step) new / old against G, G = new where applied; mode 2 (a light step's mip level {@code level}, the mip
    * framebuffer bound) new / old only.
    */
   private static void relight(Entry e, int term, float[] light, int at, int mode, int level) {
      if (mode != 2) {
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, e.comboFbo);
         if (mode == 0) {
            GL30.glColorMaski(1, true, true, true, true);
            GL30.glClearBufferfv(GL11.GL_COLOR, 1, HALF); // G: the factor 1 where the pass leaves it
         }
      }
      GL11.glViewport(0, 0, Math.max(1, e.w >> level), Math.max(1, e.h >> level));
      RELIGHT.use();
      commonUniforms(RELIGHT);
      GL20.glUniform1i(RELIGHT.u("A"), 0);
      GL20.glUniform1i(RELIGHT.u("T"), 1);
      GL20.glUniform1i(RELIGHT.u("G"), 2);
      float share = term == -1 ? SunShadow.dir[3] : term == -3 ? 0F : -1F;
      GL20.glUniform4f(RELIGHT.u("L"), light[at], light[at + 1], light[at + 2], term == -3 ? 0F : light[at + 3]);
      GL20.glUniform1f(RELIGHT.u("share"), share);
      GL20.glUniform2f(RELIGHT.u("M"), mode, 1 << level);
      bind(2, e.gTex);
      bind(1, term > 0 ? term : 0);
      bind(0, e.codeTex);
      GL30.glEnablei(GL11.GL_BLEND, 0);
      org.lwjgl.opengl.GL40.glBlendFunci(0, GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR); // 2 src dst: src = factor / 2 darkens and lightens
      GL30.glColorMaski(0, true, true, true, false);
      if (mode != 2) {
         GL30.glDisablei(GL11.GL_BLEND, 1);
         GL30.glColorMaski(1, true, false, false, false);
      }
      if (mode == 1) {
         textureBarrier(); // the pass reads each texel's own G before it writes it
      }
      GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
      GL30.glDisablei(GL11.GL_BLEND, 0);
      GL30.glColorMaski(0, true, true, true, true);
      if (mode != 2) {
         GL30.glColorMaski(1, true, true, true, true);
      }
   }

   private static int barrierKind = -1;

   private static void textureBarrier() {
      if (barrierKind < 0) {
         org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
         barrierKind = caps.OpenGL45 || caps.GL_ARB_texture_barrier ? 1 : caps.GL_NV_texture_barrier ? 2 : 0;
      }
      if (barrierKind == 1) {
         org.lwjgl.opengl.GL45.glTextureBarrier();
      } else if (barrierKind == 2) {
         org.lwjgl.opengl.NVTextureBarrier.glTextureBarrierNV();
      }
   }

   private static int texture(int w, int h, int internal, int format, int type) {
      int t = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F); // GL_CLAMP_TO_EDGE
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, (java.nio.ByteBuffer)null);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      return t;
   }

   private static int framebuffer(int... tex) {
      int f = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, f);
      for (int i = 0; i < tex.length; i++) {
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i, GL11.GL_TEXTURE_2D, tex[i], 0);
      }
      if (tex.length > 1) {
         GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
      }
      if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
         GL30.glDeleteFramebuffers(f);
         return 0;
      }
      return f;
   }

   /** The entry of a chunk texture (by its depth), its code texture and, in bake mode, G and the framebuffer onto its colour. */
   private static Entry entry(int key, int colour, int w, int h) {
      Entry e = ENTRIES.get(key);
      if (e != null && (e.w != w || e.h != h)) {
         free(e);
         ENTRIES.remove(key);
         e = null;
      }
      if (e == null) {
         e = new Entry();
         e.w = w;
         e.h = h;
         e.codeTex = texture(w, h, GL30.GL_R8, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE);
         e.codeFbo = framebuffer(e.codeTex);
         if (e.codeFbo == 0) {
            Log.warn("relief: code framebuffer incomplete (" + w + "x" + h + ")");
            free(e);
            return null;
         }
         ENTRIES.put(key, e);
         bytes += (long)w * h;
         if (Config.RELIEF_HORIZON) {
            e.hTex = texture(w, h, GL11.GL_RGBA4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            e.hFbo = framebuffer(e.hTex);
            bytes += 2L * w * h;
         }
      }
      if (sunBake() && (e.gTex == 0 || e.colour != colour)) {
         if (e.gTex == 0) {
            e.gTex = texture(w, h, GL30.GL_R8, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE);
            bytes += (long)w * h;
         }
         if (e.comboFbo != 0) {
            GL30.glDeleteFramebuffers(e.comboFbo);
         }
         e.colour = colour;
         e.comboFbo = framebuffer(colour, e.gTex);
         if (e.comboFbo == 0) {
            Log.warn("relief: relight framebuffer incomplete (" + w + "x" + h + ")");
            free(e);
            ENTRIES.remove(key);
            return null;
         }
      }
      return e;
   }

   private static void free(Entry e) {
      if (e.codeFbo != 0) {
         GL30.glDeleteFramebuffers(e.codeFbo);
      }
      if (e.comboFbo != 0) {
         GL30.glDeleteFramebuffers(e.comboFbo);
      }
      if (e.codeTex != 0) {
         GL11.glDeleteTextures(e.codeTex);
         bytes -= (long)e.w * e.h;
      }
      if (e.gTex != 0) {
         GL11.glDeleteTextures(e.gTex);
         bytes -= (long)e.w * e.h;
      }
      if (e.hFbo != 0) {
         GL30.glDeleteFramebuffers(e.hFbo);
      }
      if (e.hTex != 0) {
         GL11.glDeleteTextures(e.hTex);
         bytes -= 2L * e.w * e.h;
      }
      e.codeFbo = e.comboFbo = e.codeTex = e.gTex = e.hFbo = e.hTex = 0;
   }

   /** Over reliefAuxBudgetMb: the codes of textures not drawn for the longest time go (baked / encoded again when they show). */
   private static void trim() {
      long budget = Math.max(16L, Config.RELIEF_AUX_BUDGET_MB) * 1048576L;
      if (bytes <= budget) {
         return;
      }
      ArrayList<java.util.Map.Entry<Integer, Entry>> all = new ArrayList<>(ENTRIES.entrySet());
      all.sort((a, b) -> Long.compare(a.getValue().lastDrawn, b.getValue().lastDrawn));
      for (java.util.Map.Entry<Integer, Entry> me : all) {
         if (bytes <= budget * 3 / 4 || frame - me.getValue().lastDrawn < 240) {
            break;
         }
         free(me.getValue());
         ENTRIES.remove(me.getKey());
         synchronized (DROPPED) {
            DROPPED.add(me.getValue().index);
         }
         dropped++;
      }
   }

   private static long hits, misses, stale;
   private static int dsa = -1;

   /** Render thread, a chunk composite draw: this texture's code on AUX_UNIT, or 0 (none, stale). */
   static int bind(TextureDraw texd) {
      if (failed || texd.tex1 == null || zombie.iso.IsoCamera.frameState.zoom >= Config.RELIEF_MAX_ZOOM) {
         return 0;
      }
      Entry e = ENTRIES.get(texd.tex1.getID());
      if (e == null || !e.valid) {
         if (e == null) {
            misses++;
         } else {
            stale++;
         }
         return 0;
      }
      hits++;
      e.lastDrawn = frame;
      { // every draw: another hook or pass may have used the unit since (one call with DSA)
         if (dsa < 0) {
            dsa = org.lwjgl.opengl.GL.getCapabilities().OpenGL45 ? 1 : 0;
         }
         if (dsa == 1) {
            org.lwjgl.opengl.GL45.glBindTextureUnit(AUX_UNIT, e.codeTex); // one call, the active unit untouched
            if (e.hTex != 0) {
               org.lwjgl.opengl.GL45.glBindTextureUnit(HORIZON_UNIT, e.hTex);
            }
         } else {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + AUX_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, e.codeTex);
            if (e.hTex != 0) {
               GL13.glActiveTexture(GL13.GL_TEXTURE0 + HORIZON_UNIT);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, e.hTex);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
         }
      }
      return e.codeTex;
   }

   private static boolean init() {
      if (!ENCODE.build(ENCODE_FRAG) || !RELIGHT.build(RELIGHT_FRAG) || Config.RELIEF_HORIZON && !HORIZON.build(HORIZON_FRAG)) {
         return false;
      }
      vbo = GL15.glGenBuffers();
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
      GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-1F, -1F, 1F, -1F, 1F, 1F, -1F, 1F}, GL15.GL_STATIC_DRAW);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      Log.info("relief: code programs ready" + (sunBake() ? " (sun / moon relief baked into the chunk textures)" : ""));
      return true;
   }

   /** A pass program of our own (raw GL, bound with glUseProgram; run() hands the cache back to ShaderHelper). */
   private static final class Program {
      final String name;
      int id;
      final HashMap<String, Integer> locs = new HashMap<>();

      Program(String name) {
         this.name = name;
      }

      boolean build(String frag) {
         int vs = shader(GL20.GL_VERTEX_SHADER, VERT, this.name);
         int fs = shader(GL20.GL_FRAGMENT_SHADER, frag, this.name);
         if (vs == 0 || fs == 0) {
            return false;
         }
         int p = GL20.glCreateProgram();
         GL20.glAttachShader(p, vs);
         GL20.glAttachShader(p, fs);
         GL20.glBindAttribLocation(p, 0, "pos");
         GL20.glLinkProgram(p);
         if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            Log.warn("relief: " + this.name + " program does not link: " + GL20.glGetProgramInfoLog(p, 4096));
            return false;
         }
         this.id = p;
         return true;
      }

      void use() {
         GL20.glUseProgram(this.id);
      }

      int u(String n) {
         Integer l = this.locs.get(n);
         if (l == null) {
            l = GL20.glGetUniformLocation(this.id, n);
            this.locs.put(n, l);
         }
         return l;
      }
   }

   private static int shader(int type, String src, String name) {
      int s = GL20.glCreateShader(type);
      GL20.glShaderSource(s, src);
      GL20.glCompileShader(s);
      if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
         Log.warn("relief: " + name + " shader does not compile: " + GL20.glGetShaderInfoLog(s, 4096));
         return 0;
      }
      return s;
   }

   static final String VERT = String.join("\n",
      "#version 330",
      "in vec2 pos;",
      "void main() { gl_Position = vec4(pos, 0.0, 1.0); }");

   /** The code of one texel (see the class comment). */
   static final String ENCODE_FRAG = String.join("\n",
      "#version 420",
      "uniform sampler2D C;", // the chunk texture's colour
      "uniform sampler2D D;", // its depth
      "uniform vec4 P;", // x - y per texel along u, x + y - 6z per texel along v (signed), x + y + 2z per unit of depth, the soft limit
      "uniform vec4 Q;", // x: the plane snap (cosine)
      "out vec4 o;",
      "vec3 pos(float a, float b, float c) { float z = (c - b) * 0.125; float s = c - 2.0 * z; return vec3((s + a) * 0.5, (s - a) * 0.5, z * 2.4494897); }",
      "float blk(vec2 corner, vec2 is) { return dot(textureGather(C, corner * is, 1), vec4(0.25)); }",
      "void main() {",
      "   ivec2 ti = ivec2(gl_FragCoord.xy);",
      "   ivec2 sz = textureSize(D, 0), mx = sz - 1;",
      "   o = vec4(0.0);",
      "   if (texelFetch(C, ti, 0).a < 0.5) return;",
      "   const int K = 2;",
      "   float d0 = texelFetch(D, ti, 0).r;",
      "   float xp = texelFetch(D, min(ti + ivec2(K, 0), mx), 0).r, xm = texelFetch(D, max(ti - ivec2(K, 0), ivec2(0)), 0).r;",
      "   float yp = texelFetch(D, min(ti + ivec2(0, K), mx), 0).r, ym = texelFetch(D, max(ti - ivec2(0, K), ivec2(0)), 0).r;",
      "   bool planar = d0 < 1.0 && xp < 1.0 && xm < 1.0 && abs(xp + xm - 2.0 * d0) < 4.0 / 65535.0 + 0.25 * abs(xp - xm)",
      "      && yp < 1.0 && ym < 1.0 && abs(yp + ym - 2.0 * d0) < 4.0 / 65535.0 + 0.25 * abs(yp - ym);",
      "   if (!planar) return;",
      "   vec3 n = cross(pos(float(2 * K) * P.x, 0.0, P.z * (xp - xm)), pos(0.0, float(2 * K) * P.y, P.z * (yp - ym)));",
      "   n *= (dot(n, vec3(3.0, 3.0, 2.4494897)) < 0.0 ? -1.0 : 1.0) / max(length(n), 1e-12);",
      "   int cls = n.z > Q.x ? 0 : n.x > Q.x ? 1 : n.y > Q.x ? 2 : -1;",
      "   if (cls < 0) return;", // furniture, slopes: no relief
      "   vec2 is = 1.0 / vec2(sz), c = vec2(ti);",
      "   float tl = blk(c, is), tr = blk(c + vec2(1.0, 0.0), is), bl = blk(c + vec2(0.0, 1.0), is), br = blk(c + vec2(1.0, 1.0), is);",
      "   vec2 g = vec2(0.5 * (tr + br - tl - bl), 0.5 * (bl + br - tl - tr));",
      "   g *= P.w / (P.w + length(g));", // the soft limit (edges between sprites are not cliffs)
      "   vec2 t = sign(g) * sqrt(clamp(abs(g) / P.w, 0.0, 1.0));", // square-root scale, -1..1
      "   ivec2 k = ivec2(round(t * 4.0)) + 4;",
      "   o = vec4(float(1 + cls * 81 + k.x * 9 + k.y) / 255.0);",
      "}");

   /** The codes' decoding (shared by the factor pass). */
   static final String DECODE = String.join("\n",
      "uniform sampler2D A;", // the codes
      "uniform vec4 P;", // x - y per texel along u, x + y - 6z per texel along v (signed), x + y + 2z per unit of depth, the soft limit
      "uniform vec4 Q;", // x: the plane snap, y: squares of height per unit of slope, z: self-shadow steps, w: self-shadow strength
      "float code(ivec2 t) { return texelFetch(A, t, 0).r * 255.0; }",
      "float cls(float v) { return floor((v - 0.5) * (1.0 / 81.0)); }",
      "vec2 slopes(float v, float c) {", // green per texel along u, v
      "   float r = v - 1.0 - c * 81.0;",
      "   float ku = floor((r + 0.5) * (1.0 / 9.0));",
      "   vec2 t = vec2(ku - 4.0, r - ku * 9.0 - 4.0) * 0.25;",
      "   return sign(t) * t * t * P.w;",
      "}",
      "void frame(float c, out vec3 n0, out vec3 tu, out vec3 tv) {",
      "   float dA = P.x, dB = P.y;",
      "   const float L6 = 2.4494897 / 6.0;",
      "   if (c < 0.5) { n0 = vec3(0.0, 0.0, 1.0); tu = vec3(0.5 * dA, -0.5 * dA, 0.0); tv = vec3(0.5 * dB, 0.5 * dB, 0.0); }",
      "   else if (c < 1.5) { n0 = vec3(1.0, 0.0, 0.0); tu = vec3(0.0, -dA, -dA * L6); tv = vec3(0.0, 0.0, -dB * L6); }",
      "   else { n0 = vec3(0.0, 1.0, 0.0); tu = vec3(dA, 0.0, dA * L6); tv = vec3(0.0, 0.0, -dB * L6); }",
      "}");

   /**
    * The relief factor of one texel under the key light L (world, z in squares; w: strength): the relief normal's facing over
    * the plane's, times the self-shadow ray (the height along the light rebuilt from the stored slopes: parallax occlusion
    * mapping's shadow ray on the code), weighted by the direct-sun share (the kept term's G, or share >= 0).
    */
   static final String RELIGHT_FRAG = String.join("\n",
      "#version 420",
      DECODE,
      "uniform sampler2D T;", // the AO pass's kept term: G the direct-sun share
      "uniform vec4 L;",
      "uniform float share;", // >= 0: this share everywhere (bare open ground; 0 with L.w = 0: none)
      "uniform sampler2D G;", // the factor applied / 2
      "uniform vec2 M;", // x: 0 a bake (the factor), 1 a light step (new / old, G = new), 2 a step's mip level; y: level-0 texels a texel
      "layout(location = 0) out vec4 o0;", // onto the colour (2 src dst)
      "layout(location = 1) out vec4 o1;", // G
      "float factorAt(ivec2 ti) {",
      "   ivec2 mx = textureSize(A, 0) - 1;",
      "   float v = code(ti);",
      "   if (v < 0.5 || L.w <= 0.0) return 1.0;",
      "   float c = cls(v);",
      "   vec3 n0, tu, tv;",
      "   frame(c, n0, tu, tv);",
      "   float f0 = dot(n0, L.xyz);",
      "   if (f0 <= 0.0) return 1.0;", // the plane faces away: its own shade
      "   vec2 g = slopes(v, c);",
      "   float s = Q.y;",
      "   vec3 nr = normalize(cross(tu + n0 * (s * g.x), tv + n0 * (s * g.y)));",
      "   nr = dot(nr, n0) < 0.0 ? -nr : nr;",
      "   float r = max(dot(nr, L.xyz), 0.0) / max(f0, 0.15);",
      "   int steps = int(Q.z);",
      "   if (steps > 0) {",
      // the light's direction across the plane in texel steps (one texel along the major axis a step)
      "      vec3 lp = L.xyz - n0 * f0;",
      "      float g00 = dot(tu, tu), g01 = dot(tu, tv), g11 = dot(tv, tv);",
      "      float det = g00 * g11 - g01 * g01;",
      "      vec2 st = vec2(g11 * dot(tu, lp) - g01 * dot(tv, lp), g00 * dot(tv, lp) - g01 * dot(tu, lp)) / det;",
      "      float m = max(abs(st.x), abs(st.y));",
      "      if (m > 1e-6) {",
      "         st /= m;",
      "         float rise = length(st.x * tu + st.y * tv) * f0 / max(length(lp), 1e-6);", // squares the ray climbs a step
      "         float h = 0.0, vis = 1.0;",
      "         vec2 gp = g;",
      "         vec2 p = vec2(ti) + 0.5;",
      "         for (int k = 1; k <= steps; k++) {",
      "            p += st;",
      "            ivec2 t = clamp(ivec2(floor(p)), ivec2(0), mx);",
      "            float vk = code(t);",
      "            if (vk < 0.5 || abs(cls(vk) - c) > 0.5) break;", // off the plane: the ray leaves the relief
      "            vec2 gk = slopes(vk, c);",
      "            h += 0.5 * dot(gp + gk, st);", // the height rebuilt from the slopes (trapezoid)
      "            gp = gk;",
      "            vis = min(vis, 1.0 - smoothstep(0.0, 0.25 * s, s * h - rise * float(k)));",
      "         }",
      "         r *= mix(1.0, vis, Q.w);",
      "      }",
      "   }",
      "   float q = share >= 0.0 ? share : textureLod(T, (vec2(ti) + 0.5) / vec2(mx + 1), 0.0).g;",
      "   return clamp(1.0 + q * L.w * (r - 1.0), 0.0, 1.99);",
      "}",
      "void main() {",
      "   ivec2 t = ivec2(gl_FragCoord.xy) * int(M.y);",
      "   float f = factorAt(t);",
      "   float old = M.x > 0.5 ? texelFetch(G, t, 0).r * 2.0 : 1.0;",
      "   float ratio = f / max(old, 0.01);",
      "   if (abs(ratio - 1.0) < 0.004) discard;", // 8-bit colour: a ratio this close to 1 would only churn its rounding (G keeps the old)
      "   o0 = vec4(vec3(clamp(ratio, 0.0, 1.99) * 0.5), 1.0);",
      "   o1 = vec4(f * 0.5);",
      "}");


   /**
    * The horizons of one texel's relief (horizon mapping, Max 1988 / Sloan and Cohen 2000): along +u, -u, +v, -v across its
    * plane, the steepest rise of the height (rebuilt from the stored slopes) seen from the texel, as tan(elevation) / H.y.
    */
   static final String HORIZON_FRAG = String.join("\n",
      "#version 420",
      DECODE,
      "uniform vec2 H;", // x steps, y 1 / the largest tan stored
      "out vec4 o;",
      "float horizon(ivec2 ti, vec2 st, float c, vec2 g0, float wl, ivec2 mx) {",
      "   float h = 0.0, best = 0.0;",
      "   vec2 p = vec2(ti) + 0.5, gp = g0;",
      "   int steps = int(H.x);",
      "   for (int k = 1; k <= steps; k++) {",
      "      p += st;",
      "      float vk = code(clamp(ivec2(floor(p)), ivec2(0), mx));",
      "      if (vk < 0.5 || abs(cls(vk) - c) > 0.5) break;",
      "      vec2 gk = slopes(vk, c);",
      "      h += 0.5 * dot(gp + gk, st);",
      "      gp = gk;",
      "      best = max(best, Q.y * h / (wl * float(k)));",
      "   }",
      "   return clamp(best * H.y, 0.0, 1.0);",
      "}",
      "void main() {",
      "   ivec2 ti = ivec2(gl_FragCoord.xy);",
      "   ivec2 mx = textureSize(A, 0) - 1;",
      "   o = vec4(0.0);",
      "   float v = code(ti);",
      "   if (v < 0.5) return;",
      "   float c = cls(v);",
      "   vec3 n0, tu, tv;",
      "   frame(c, n0, tu, tv);",
      "   vec2 g0 = slopes(v, c);",
      "   float lu = length(tu), lv = length(tv);",
      "   o = vec4(horizon(ti, vec2(1.0, 0.0), c, g0, lu, mx), horizon(ti, vec2(-1.0, 0.0), c, g0, lu, mx),",
      "            horizon(ti, vec2(0.0, 1.0), c, g0, lv, mx), horizon(ti, vec2(0.0, -1.0), c, g0, lv, mx));",
      "}");
}
