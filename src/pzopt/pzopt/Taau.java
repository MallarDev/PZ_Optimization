package pzopt;

import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureFBO;
import zombie.iso.PlayerCamera;

/**
 * Temporal upsampling ({@code upscaler=taau}, also {@code dynResUpscaler=taau}; docs/findings-dynamic-resolution-2026-10-03.md):
 * a GLSL temporal upscaler in the family of FSR 2 / TSR, so the world can render at any scale, changing every frame,
 * while the image on screen is built in a history kept at the output size.
 *
 * <ul>
 * <li>The world pass is jittered by a sub-pixel viewport offset (Halton 2, 3; {@link RenderScale#setJitter}).</li>
 * <li>Each output pixel takes the input sample nearest its centre with a weight that falls with the sample's distance
 * in output pixels, and accumulates it into the history (RGBA16F, alpha = the accumulated weight, capped at
 * {@code taauMaxFrames}): a still image converges to a supersampled one whatever the render scale.</li>
 * <li>The history is reprojected with the camera's exact motion (the iso camera is an orthographic translation plus a
 * zoom: world pixel = offset + screen pixel * zoom) and fetched with Catmull-Rom.</li>
 * <li>It is clipped to the variance box (YCoCg, mean +- {@code taauClipPct}/100 sigma) of the 3x3 input texels around the
 * pixel, which stops ghosting from moving characters, particles and lighting; the further it had to move, the more of
 * its weight it loses.</li>
 * <li>RCAS (FSR 1.0) sharpens the output, not the history (no feedback).</li>
 * </ul>
 * A cut (first frame, a teleport, a zoom jump of more than a factor 2) restarts the history from a bilinear upscale.
 */
public final class Taau {
   private Taau() {
   }

   private static int histTex0, histTex1, histFbo0, histFbo1, texW, texH;
   private static int objTex, objFbo, objDepthTex, rectProgram, rectMvLoc; // characters' and vehicles' own motion (upscalerObjectMv)
   private static long objectRects;
   private static int current; // the history written last frame: 0 or 1
   private static boolean haveHistory;
   private static int program;
   private static int[] u;
   private static int quadVbo;
   private static boolean failed;
   private static float lastOffX, lastOffY, lastZoom;
   private static int haltonIndex;
   private static long lastFrameNo;
   private static boolean lastWasKeep;

   /** Game thread: a warm copy is due on this native frame (every taauWarmEvery frames). */
   static boolean keepDue(long frameNo) {
      int every = Math.max(1, Config.TAAU_WARM_EVERY);
      return frameNo % every == 0;
   }
   private static long frames;
   private static final FloatBuffer QUAD = BufferUtils.createFloatBuffer(8);
   private static final int[] SAVED_VIEWPORT = new int[4];

   static long frames() {
      return frames;
   }

   /** Render thread, from Upscaler.resolve (the composite of the frame): the low-res world image into the screen-size history. */
   static boolean resolve(ObjectMotion.Frame objects) {
      if (failed) {
         return false;
      }
      TextureFBO world = Core.getInstance().getOffscreenBuffer();
      if (world == null || world.getTexture() == null) {
         return false;
      }
      int w = Core.width, h = Core.height;
      if (!ensure(w, h)) {
         return false;
      }
      GpuSections.markNow("upscale.taau", false);
      int previousFbo = Upscaler.savedState(SAVED_VIEWPORT);
      int[] r = RenderScale.scaledRect(0);
      PlayerCamera camera = SpriteRenderer.instance.getRenderingPlayerCamera(0);
      float offX = camera.offX, offY = camera.offY, zoom = camera.zoom <= 0.0F ? 1.0F : camera.zoom;
      long frameNo = DynRes.renderFrameNo();
      // frames shown without the resolve (native bypass): the history is stale unless a warm copy is at most
      // taauWarmEvery frames old (the camera reprojection covers the gap; what changed in it is clipped)
      long gap = frameNo - lastFrameNo;
      boolean skipped = frameNo != 0L && lastFrameNo != 0L && (gap < 1 || gap > (lastWasKeep ? Math.max(1, Config.TAAU_WARM_EVERY) : 1));
      lastWasKeep = false;
      lastFrameNo = frameNo;
      boolean reset = !haveHistory || skipped || lastZoom <= 0.0F || zoom / lastZoom > 2.0F || lastZoom / zoom > 2.0F
         || Math.abs(offX - lastOffX) > w * zoom * 0.5F || Math.abs(offY - lastOffY) > h * zoom * 0.5F;
      if (reset) {
         lastOffX = offX;
         lastOffY = offY;
         lastZoom = zoom;
      }
      int src = current, dst = current ^ 1;
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL11.glDepthMask(false);
      GL11.glColorMask(true, true, true, true);
      boolean objectMv = drawObjectMotion(objects, world.getBufferId(), r, w, h);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
      for (int i = 1; i < 5; i++) {
         GL20.glDisableVertexAttribArray(i);
      }
      GL20.glEnableVertexAttribArray(0);
      GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, dst == 0 ? histFbo0 : histFbo1);
      GL11.glViewport(0, 0, w, h);
      GL20.glUseProgram(program);
      GL13.glActiveTexture(GL13.GL_TEXTURE1);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, src == 0 ? histTex0 : histTex1);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, ((Texture)world.getTexture()).getID());
      GL20.glUniform1i(u[0], 0);
      GL20.glUniform1i(u[1], 1);
      GL20.glUniform4i(u[2], r[0], r[1], r[2], r[3]);
      GL20.glUniform4f(u[3], (float)r[2] / w, (float)r[3] / h, RenderScale.frameJitterX(), RenderScale.frameJitterY());
      GL20.glUniform3f(u[4], offX, offY, zoom);
      GL20.glUniform3f(u[5], lastOffX, lastOffY, lastZoom);
      GL20.glUniform2f(u[6], w, h);
      float sigma = Math.max(0.15F, Config.TAAU_SAMPLE_SIGMA_PCT / 100.0F);
      GL20.glUniform4f(u[7], reset ? 1.0F : 0.0F, Math.max(1.0F, Config.TAAU_MAX_FRAMES), Config.TAAU_CLIP_PCT / 100.0F, 1.0F / (2.0F * sigma * sigma));
      GL20.glUniform1i(u[8], Config.DEV_TAAU_VIEW);
      GL20.glUniform1i(u[9], 2);
      GL20.glUniform1i(u[10], objectMv ? 1 : 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE2);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, objectMv ? objTex : 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
      GL13.glActiveTexture(GL13.GL_TEXTURE2);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE1);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      current = dst;
      haveHistory = true;
      lastOffX = offX;
      lastOffY = offY;
      lastZoom = zoom;
      frames++;
      GpuSections.markNow("upscale.taau", true);

      // the next frame's jitter: Halton 2, 3 over 8 x (output / render)^2 phases at the largest render size
      float s = Math.max(0.25F, RenderScale.maxScale());
      int phases = Math.max(8, Math.round(8.0F / (s * s)));
      haltonIndex = (haltonIndex + 1) % phases;
      // taauJitterAdaptive: no jitter at the screen size (point-sampled art stays crisp: every frame samples it where the
      // stock game does), full jitter from 75 % down, a ramp between; the history follows without a seam
      float amp = !Config.TAAU_JITTER ? 0.0F : Config.TAAU_JITTER_ADAPTIVE ? Math.max(0.0F, Math.min(1.0F, (1.0F - RenderScale.scale()) / 0.25F)) : 1.0F;
      RenderScale.setJitter(amp * (Dlss.halton(haltonIndex + 1, 2) - 0.5F), amp * (Dlss.halton(haltonIndex + 1, 3) - 0.5F));

      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
      GL11.glViewport(SAVED_VIEWPORT[0], SAVED_VIEWPORT[1], SAVED_VIEWPORT[2], SAVED_VIEWPORT[3]);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      Texture.lastTextureID = -1;
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      for (int i = 0; i < 5; i++) {
         GL20.glEnableVertexAttribArray(i);
      }
      GL20.glUseProgram(0);
      GL11.glDepthMask(true);
      GL11.glEnable(GL11.GL_BLEND);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GLStateRenderThread.restore();
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      return true;
   }

   /**
    * upscalerObjectMv: characters and vehicles carry a stencil id in the world's depth-stencil (pzopt.ObjectMotion, as
    * for DLSS); their screen motion is written where the id sits into a render-size RGBA16F image (xy = output pixels,
    * y up, current -> previous; z = 1 where an object is), which the resolve prefers over the camera's motion.
    */
   private static boolean drawObjectMotion(ObjectMotion.Frame objects, int worldFbo, int[] r, int w, int h) {
      if (objects == null || objects.count == 0 || !Config.UPSCALER_OBJECT_MV) {
         return false;
      }
      int depth = Dlss.sceneDepthTexture(worldFbo);
      if (depth == 0) {
         return false;
      }
      if (objTex == 0) {
         rectProgram = Shaders.program("taau object motion", Upscaler.QUAD_VERT, RECT_FRAG);
         if (rectProgram == 0) {
            return false;
         }
         rectMvLoc = GL20.glGetUniformLocation(rectProgram, "mv");
         objTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, objTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         objFbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, objFbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, objTex, 0);
      }
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, objFbo);
      if (objDepthTex != depth) {
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
         objDepthTex = depth;
      }
      int inW = Math.min(r[2], w), inH = Math.min(r[3], h);
      GL11.glViewport(0, 0, inW, inH);
      GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
      GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
      GL11.glEnable(GL11.GL_STENCIL_TEST);
      GL11.glStencilMask(0);
      GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
      for (int i = 1; i < 5; i++) {
         GL20.glDisableVertexAttribArray(i);
      }
      GL20.glEnableVertexAttribArray(0);
      GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
      GL20.glUseProgram(rectProgram);
      float sx = (float)r[2] / w, sy = (float)r[3] / h;
      for (int i = 0; i < objects.count; i++) {
         float rx = objects.rect[i * 4] * sx, ry = objects.rect[i * 4 + 1] * sy, rw = objects.rect[i * 4 + 2] * sx, rh = objects.rect[i * 4 + 3] * sy;
         int x0 = Math.max(0, (int)Math.floor(rx)), x1 = Math.min(inW, (int)Math.ceil(rx + rw));
         int y0 = Math.max(0, (int)Math.floor(inH - ry - rh)), y1 = Math.min(inH, (int)Math.ceil(inH - ry));
         if (x1 <= x0 || y1 <= y0) {
            continue;
         }
         GL11.glViewport(x0, y0, x1 - x0, y1 - y0);
         GL11.glStencilFunc(GL11.GL_EQUAL, i + 1, 0x7F);
         GL20.glUniform2f(rectMvLoc, objects.motion[i * 2], -objects.motion[i * 2 + 1]); // output pixels, y up
         GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
         objectRects++;
      }
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glStencilMask(0xFF);
      return true;
   }

   static final String RECT_FRAG = String.join("\n",
      "#version 330",
      "uniform vec2 mv;",
      "out vec4 frag;",
      "void main() { frag = vec4(mv, 1.0, 0.0); }");

   /** taauWarmBypass: queued (game thread) for a frame the native bypass shows without the resolve. */
   static final zombie.core.textures.TextureDraw.GenericDrawer KEEP = new zombie.core.textures.TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         try {
            keep();
         } catch (Throwable t) {
            Log.warn("taau: keep failed: " + t);
         }
      }
   };

   private static int keepProgram;
   private static int[] keepU;
   private static long keeps;

   /**
    * Render thread: copies the native frame into the history with the full accumulated weight (one texel per pixel, no
    * filtering), so the next resolve below 100 % reprojects it instead of starting from a bilinear upscale.
    */
   static void keep() {
      if (failed) {
         return;
      }
      TextureFBO world = Core.getInstance().getOffscreenBuffer();
      if (world == null || world.getTexture() == null) {
         return;
      }
      int w = Core.width, h = Core.height;
      if (!ensure(w, h)) {
         return;
      }
      if (keepProgram == 0) {
         keepProgram = Shaders.program("taau keep", Upscaler.QUAD_VERT, KEEP_FRAG);
         if (keepProgram == 0) {
            failed = true;
            return;
         }
         keepU = new int[]{GL20.glGetUniformLocation(keepProgram, "Color"), GL20.glGetUniformLocation(keepProgram, "origin"), GL20.glGetUniformLocation(keepProgram, "weight")};
      }
      GpuSections.markNow("upscale.taaukeep", false);
      int previousFbo = Upscaler.savedState(SAVED_VIEWPORT);
      int[] r = RenderScale.scaledRect(0);
      PlayerCamera camera = SpriteRenderer.instance.getRenderingPlayerCamera(0);
      int dst = current ^ 1;
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL11.glDepthMask(false);
      GL11.glColorMask(true, true, true, true);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
      for (int i = 1; i < 5; i++) {
         GL20.glDisableVertexAttribArray(i);
      }
      GL20.glEnableVertexAttribArray(0);
      GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, dst == 0 ? histFbo0 : histFbo1);
      GL11.glViewport(0, 0, w, h);
      GL20.glUseProgram(keepProgram);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, ((Texture)world.getTexture()).getID());
      GL20.glUniform1i(keepU[0], 0);
      GL20.glUniform2i(keepU[1], r[0], r[1]);
      GL20.glUniform1f(keepU[2], Math.max(1.0F, Config.TAAU_MAX_FRAMES));
      GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
      current = dst;
      haveHistory = true;
      lastOffX = camera.offX;
      lastOffY = camera.offY;
      lastZoom = camera.zoom <= 0.0F ? 1.0F : camera.zoom;
      lastFrameNo = DynRes.renderFrameNo();
      lastWasKeep = true;
      keeps++;
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
      GL11.glViewport(SAVED_VIEWPORT[0], SAVED_VIEWPORT[1], SAVED_VIEWPORT[2], SAVED_VIEWPORT[3]);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      Texture.lastTextureID = -1;
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      for (int i = 0; i < 5; i++) {
         GL20.glEnableVertexAttribArray(i);
      }
      GL20.glUseProgram(0);
      GL11.glDepthMask(true);
      GL11.glEnable(GL11.GL_BLEND);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GLStateRenderThread.restore();
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      GpuSections.markNow("upscale.taaukeep", true);
   }

   static final String KEEP_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D Color;",
      "uniform ivec2 origin;",
      "uniform float weight;",
      "out vec4 frag;",
      "void main() { frag = vec4(texelFetch(Color, ivec2(gl_FragCoord.xy) + origin, 0).rgb, weight); }");

   /** The history texture written this frame (screen size, RGBA16F). */
   static int outputTexture() {
      return current == 0 ? histTex0 : histTex1;
   }

   /** The settings changed or the pass was switched off: the next frame starts a fresh history. */
   static void invalidate() {
      haveHistory = false;
   }

   private static boolean ensure(int w, int h) {
      if (program == 0) {
         program = Shaders.program("taau", Upscaler.QUAD_VERT, FRAG);
         if (program == 0) {
            fail("taau shader refused");
            return false;
         }
         u = new int[]{GL20.glGetUniformLocation(program, "Color"), GL20.glGetUniformLocation(program, "History"), GL20.glGetUniformLocation(program, "inRect"),
            GL20.glGetUniformLocation(program, "map"), GL20.glGetUniformLocation(program, "cur"), GL20.glGetUniformLocation(program, "prev"),
            GL20.glGetUniformLocation(program, "outSize"), GL20.glGetUniformLocation(program, "params"), GL20.glGetUniformLocation(program, "view"),
            GL20.glGetUniformLocation(program, "ObjMv"), GL20.glGetUniformLocation(program, "useObjMv")};
         quadVbo = GL15.glGenBuffers();
         QUAD.clear();
         QUAD.put(-1.0F).put(-1.0F).put(1.0F).put(-1.0F).put(-1.0F).put(1.0F).put(1.0F).put(1.0F).flip();
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, QUAD, GL15.GL_STATIC_DRAW);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      }
      if (histTex0 != 0 && texW == w && texH == h) {
         return true;
      }
      if (histTex0 != 0) {
         GL30.glDeleteFramebuffers(histFbo0);
         GL30.glDeleteFramebuffers(histFbo1);
         GL11.glDeleteTextures(histTex0);
         GL11.glDeleteTextures(histTex1);
      }
      histTex0 = historyTexture(w, h);
      histTex1 = historyTexture(w, h);
      histFbo0 = Upscaler.framebufferOf(histTex0);
      histFbo1 = Upscaler.framebufferOf(histTex1);
      texW = w;
      texH = h;
      haveHistory = false;
      if (histFbo0 == 0 || histFbo1 == 0) {
         fail("taau history targets incomplete");
         return false;
      }
      Log.info("taau: history " + w + "x" + h + " RGBA16F");
      return true;
   }

   private static int historyTexture(int w, int h) {
      int tex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      return tex;
   }

   private static void fail(String why) {
      failed = true;
      RenderScale.fallback("fsr1", why);
   }

   /**
    * map.xy = render pixels per output pixel, map.zw = the frame's jitter (render pixels, y up like the images); cur / prev
    * = camera offX, offY, zoom; params = (reset, max accumulated frames, clip sigma, 1 / (2 sigma_sample^2) in output px).
    * An output pixel centre q sits at the render-space point p = q * map.xy + map.zw.
    */
   static final String FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D Color;",
      "uniform sampler2D History;",
      "uniform ivec4 inRect;",
      "uniform vec4 map;",
      "uniform vec3 cur;",
      "uniform vec3 prev;",
      "uniform vec2 outSize;",
      "uniform vec4 params;",
      "uniform int view; // devTaauView: 1 history weight, 2 current sample weight, 3 clip distance",
      "uniform sampler2D ObjMv; // render size: xy an object's own motion (output px, y up, current -> previous), z = 1 where one is",
      "uniform int useObjMv;",
      "out vec4 frag;",
      "vec3 toY(vec3 c) { return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b); }",
      "vec3 fromY(vec3 c) { float t = c.x - c.z; return vec3(t + c.y, c.x + c.z, t - c.y); }",
      "vec3 tap(ivec2 t) { return texelFetch(Color, clamp(t, ivec2(0), inRect.zw - 1) + inRect.xy, 0).rgb; }",
      "vec4 historyCatmullRom(vec2 uv) {",
      "   vec2 sp = uv * outSize;",
      "   vec2 t1 = floor(sp - 0.5) + 0.5;",
      "   vec2 f = sp - t1;",
      "   vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));",
      "   vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);",
      "   vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));",
      "   vec2 w3 = f * f * (-0.5 + 0.5 * f);",
      "   vec2 w12 = w1 + w2;",
      "   vec2 p0 = (t1 - 1.0) / outSize, p3 = (t1 + 2.0) / outSize, p12 = (t1 + w2 / w12) / outSize;",
      "   vec4 r = texture(History, vec2(p12.x, p0.y)) * w12.x * w0.y + texture(History, vec2(p0.x, p12.y)) * w0.x * w12.y",
      "      + texture(History, vec2(p12.x, p12.y)) * w12.x * w12.y + texture(History, vec2(p3.x, p12.y)) * w3.x * w12.y",
      "      + texture(History, vec2(p12.x, p3.y)) * w12.x * w3.y;",
      "   float wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;",
      "   r /= wsum;",
      "   r.a = texture(History, uv).a; // the weight is not sharpened",
      "   return max(r, vec4(0.0));",
      "}",
      "void main() {",
      "   vec2 q = gl_FragCoord.xy;",
      "   vec2 p = q * map.xy + map.zw;",
      "   ivec2 k = ivec2(floor(p));",
      "   vec3 m1 = vec3(0.0), m2 = vec3(0.0);",
      "   vec3 bmin = vec3(1e9), bmax = vec3(-1e9);",
      "   vec3 recon = vec3(0.0);",
      "   float rw = 0.0;",
      "   for (int j = -1; j <= 1; j++) {",
      "      for (int i = -1; i <= 1; i++) {",
      "         vec3 c = tap(k + ivec2(i, j));",
      "         vec3 y = toY(c);",
      "         m1 += y;",
      "         m2 += y * y;",
      "         bmin = min(bmin, y);",
      "         bmax = max(bmax, y);",
      "         vec2 d = vec2(k + ivec2(i, j)) + 0.5 - p;",
      "         float w = max(0.0, 1.0 - abs(d.x)) * max(0.0, 1.0 - abs(d.y)); // bilinear: the fallback image",
      "         recon += c * w;",
      "         rw += w;",
      "      }",
      "   }",
      "   recon /= max(rw, 1e-4);",
      "   vec3 nearest = tap(k);",
      "   vec2 dn = (vec2(k) + 0.5 - p) / map.xy; // the nearest sample's offset from this pixel centre, output px",
      "   float wc = exp(-dot(dn, dn) * params.w);",
      "   vec3 mean = m1 / 9.0;",
      "   vec3 sd = sqrt(max(m2 / 9.0 - mean * mean, vec3(0.0)));",
      // the box: the neighbourhood's min / max (pixel art has hard two-colour edges a variance box cuts into), tightened
      // towards mean +- clip sigma only where that is wider than the min / max box anyway
      "   vec3 lo = max(bmin, mean - params.z * sd * 2.0), hi = min(bmax, mean + params.z * sd * 2.0);",
      "   lo = min(lo, bmin + 0.5 * (bmax - bmin)); hi = max(hi, bmax - 0.5 * (bmax - bmin));",
      // the camera's motion: output px q (y up) is the screen point (q.x, H - q.y), world = cur.xy + screen * cur.z
      "   vec2 screen = vec2(q.x, outSize.y - q.y);",
      "   vec2 world = cur.xy + screen * cur.z;",
      "   vec2 ps = (world - prev.xy) / prev.z;",
      "   vec2 pq = vec2(ps.x, outSize.y - ps.y);",
      "   if (useObjMv != 0) {",
      "      vec4 o = texelFetch(ObjMv, clamp(k, ivec2(0), inRect.zw - 1), 0);",
      "      if (o.z > 0.5) pq = q + o.xy;",
      "   }",
      "   vec2 huv = pq / outSize;",
      "   bool inside = all(greaterThanEqual(huv, vec2(0.0))) && all(lessThanEqual(huv, vec2(1.0)));",
      "   if (params.x > 0.5 || !inside) {",
      "      frag = vec4(recon, 1.0);",
      "      return;",
      "   }",
      "   vec4 h = historyCatmullRom(huv);",
      "   vec3 hy = toY(h.rgb);",
      // confidence-weighted rectification: the box only knows this pixel when a sample landed near its centre; detail the
      // current frame did not sample (a line between two samples) is kept unless the scene moved under the pixel
      "   float motion = length(pq - q);",
      "   float trust = max(smoothstep(0.15, 0.7, wc), smoothstep(0.02, 0.25, motion));",
      "   vec3 cy = mix(hy, clamp(hy, lo, hi), trust);",
      // weight lost only in proportion to how far outside the box the history was (relative to the box), so a small
      // clip on a converged edge keeps most of what was accumulated
      "   float moved = length(cy - hy) / max(0.01, length(bmax - bmin));",
      "   float hw = min(h.a, params.y) * clamp(1.0 - moved * 2.0, 0.0, 1.0);",
      "   vec3 hc = fromY(cy);",
      "   float wcur = max(wc, 0.02);",
      "   vec3 outc = (hc * hw + nearest * wcur) / (hw + wcur);",
      "   if (hw + wcur < 0.25) outc = mix(recon, outc, (hw + wcur) * 4.0); // little known here yet: lean on the bilinear image",
      "   frag = vec4(outc, min(hw + wcur, params.y));",
      "   if (view == 1) frag = vec4(vec3(hw / params.y), frag.a);",
      "   else if (view == 2) frag = vec4(vec3(wc), frag.a);",
      "   else if (view == 3) frag = vec4(vec3(min(1.0, moved * 0.25)), frag.a);",
      "}");
}
