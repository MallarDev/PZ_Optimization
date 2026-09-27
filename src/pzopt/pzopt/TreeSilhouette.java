package pzopt;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.Core;
import zombie.core.textures.Texture;
import zombie.iso.objects.IsoTree;
import zombie.iso.sprite.IsoSpriteInstance;

/**
 * The silhouettes of the trees for their sun shadows (Config {@code sunShadowTreeCards}): per distinct look of a tree (its
 * sprite and its foliage overlay of the season), the coverage of the whole sprite frame as the game draws it (trunk,
 * branches, leaves), one layer of an R8 array texture with mipmaps. The chunk AO kernel ({@link ChunkAo}) intersects each
 * receiver's ray towards the sun with a card through the tree's foot turned to face the sun and reads the layer there: one
 * continuous shadow from the trunk's foot to the crown's top, with the leaves' gaps, softened with the distance to the card
 * through the mip level (the penumbra's width). The card turns with the sun, so the shadow never collapses to a line as the
 * camera-facing sprite would.
 *
 * <p>A frame of a tree sprite is {@code 2 offsetX} wide and {@code offsetY + 32 tileScale} tall around the tree's square
 * (TreeBake's JUMBO rules: 1, 3, 5 or 7 floor widths); the foot, the square's centre, sits at ({@code offsetX},
 * {@code offsetY + 16 tileScale}). On a card, one square along it is {@code 32 sqrt(2) tileScale} px of the sprite and one
 * square of height {@code 96 tileScale / 2.449} px (the camera looks down at 30 degrees: heights are drawn shortened).
 *
 * <p>The game thread asks for a tree's layer ({@link #layerFor}); a new look is drawn on the render thread before the next
 * compute that uses it ({@link #bind}). Layers are reused least recently asked first.
 */
public final class TreeSilhouette {
   static final int SIZE = 512;
   static final int LAYERS = 64;
   /** Sprite px per square along a card (32 sqrt 2 per tile scale) and per square of height (96 / 2.449 per tile scale). */
   static final float PX_PER_SQUARE = 45.254834F;
   static final float PX_PER_HEIGHT = 39.191835F;

   private static final HashMap<String, Look> LOOKS = new HashMap<>();
   private static final Look[] BY_LAYER = new Look[LAYERS];
   private static final ArrayList<Look> PENDING = new ArrayList<>();
   private static long frame;
   private static long drawnLayers;
   private static long evictions;
   private static volatile boolean failed;

   private TreeSilhouette() {
   }

   /** One look of a tree: its textures and where they sit in the frame (sprite px, y down from the frame's top). */
   static final class Look {
      final String key;
      int layer;
      long used;
      Texture[] textures;
      float[] rects; // x0, y0, x1, y1 per texture in frame px
      float frameW;
      float frameH;
      float footX;
      float footY;
      volatile boolean drawn;

      Look(String key) {
         this.key = key;
      }
   }

   static String stats() {
      return "tree silhouettes: looks=" + LOOKS.size() + " layers drawn=" + drawnLayers + " evictions=" + evictions + (failed ? " FAILED" : "");
   }

   /** Game thread, once a frame. */
   static void tick() {
      frame++;
   }

   /**
    * Game thread or a ChunkAo mask task (aoContextParallel: the look table is locked): the layer holding this tree's look and its card mapping into out (u per square along the card, v of the
    * foot, v per square of height; u of the foot is 0.5), or -1 (no sprite, a texture not loaded yet, every layer in use
    * this frame). v runs from the frame's bottom (GL rows) upwards: v = 1 - y / frameH.
    */
   static int layerFor(IsoTree tree, float[] out, int o) {
      if (failed || tree == null || tree.getSprite() == null) {
         return -1;
      }
      zombie.iso.IsoDirections dir = tree.getDir();
      Texture base = tree.getSprite().getTextureForCurrentFrame(dir, tree);
      if (base == null || !base.isReady() || base.getTextureId() == null) {
         return -1;
      }
      String spriteName = tree.getSprite().name;
      int ts = Core.tileScale;
      StringBuilder kb = new StringBuilder(64).append(spriteName).append('|').append(base.getName());
      ArrayList<IsoSpriteInstance> attached = tree.attachedAnimSprite;
      int na = attached == null ? 0 : attached.size();
      Texture[] extra = na == 0 ? null : new Texture[na];
      for (int k = 0; k < na; k++) {
         IsoSpriteInstance inst = attached.get(k);
         Texture t = inst == null || inst.parentSprite == null ? null : inst.parentSprite.getTextureForCurrentFrame(dir, tree);
         if (t != null && (!t.isReady() || t.getTextureId() == null)) {
            return -1; // the foliage is still loading: ask again at the next compute
         }
         extra[k] = t;
         kb.append('|').append(t == null ? "-" : t.getName());
      }
      String key = kb.toString();
      synchronized (LOOKS) {
         return lookLayer(key, spriteName, ts, base, extra, na, out, o);
      }
   }

   private static int lookLayer(String key, String spriteName, int ts, Texture base, Texture[] extra, int na, float[] out, int o) {
      Look look = LOOKS.get(key);
      if (look == null) {
         int layer = freeLayer();
         if (layer < 0) {
            return -1;
         }
         look = new Look(key);
         look.layer = layer;
         float offX = TreeBake.offsetX(spriteName, ts), offY = TreeBake.offsetY(spriteName, ts);
         look.frameW = 2.0F * offX;
         look.frameH = offY + 32.0F * ts;
         look.footX = offX;
         look.footY = offY + 16.0F * ts;
         ArrayList<Texture> tex = new ArrayList<>();
         ArrayList<float[]> rects = new ArrayList<>();
         addTexture(base, ts, tex, rects);
         for (int k = 0; k < na; k++) {
            if (extra[k] != null) {
               addTexture(extra[k], ts, tex, rects);
            }
         }
         look.textures = tex.toArray(new Texture[0]);
         look.rects = new float[rects.size() * 4];
         for (int k = 0; k < rects.size(); k++) {
            System.arraycopy(rects.get(k), 0, look.rects, k * 4, 4);
         }
         Look old = BY_LAYER[layer];
         if (old != null) {
            LOOKS.remove(old.key);
            evictions++;
         }
         BY_LAYER[layer] = look;
         LOOKS.put(key, look);
         synchronized (PENDING) {
            PENDING.add(look);
         }
      }
      look.used = frame;
      out[o] = PX_PER_SQUARE * ts / look.frameW;
      out[o + 1] = 1.0F - look.footY / look.frameH;
      out[o + 2] = PX_PER_HEIGHT * ts / look.frameH;
      return look.layer;
   }

   /** The texture's rectangle in the frame, placed like IsoSprite.performRenderFrame (TreeBake's scale for 1x textures). */
   private static void addTexture(Texture t, int ts, ArrayList<Texture> tex, ArrayList<float[]> rects) {
      float scale = TreeBake.spriteScale(ts, t.getWidthOrig(), t.getHeightOrig());
      float x0 = t.getOffsetX() * scale, y0 = t.getOffsetY() * scale;
      tex.add(t);
      rects.add(new float[] {x0, y0, x0 + t.getWidth() * scale, y0 + t.getHeight() * scale});
   }

   /** A layer never used, else the least recently used one not asked for in the last 120 frames, else -1. */
   private static int freeLayer() {
      int best = -1;
      long oldest = Long.MAX_VALUE;
      for (int i = 0; i < LAYERS; i++) {
         Look l = BY_LAYER[i];
         if (l == null) {
            return i;
         }
         if (l.used < oldest) {
            oldest = l.used;
            best = i;
         }
      }
      return frame - oldest > 120L ? best : -1;
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static int array;
   private static int fbo;
   private static int program;
   private static int vao;
   private static int uRect;
   private static int uUv;

   /** Render thread: every GL entry point init() and draw() use exists in this context. */
   private static boolean glSupported() {
      org.lwjgl.opengl.GLCapabilities c = org.lwjgl.opengl.GL.getCapabilities();
      return c.OpenGL30 || c.GL_ARB_vertex_array_object && c.GL_ARB_framebuffer_object && c.GL_EXT_texture_array;
   }

   /**
    * Render thread, before a compute that reads the silhouettes: draws the looks asked for since the last call, then binds
    * the array on the unit (the active unit is left at 0). False when the array could not be made (the kernel then gets
    * no card: the caller passes no trees with a layer).
    */
   static boolean bind(int unit) {
      if (failed) {
         return false;
      }
      try {
         if (array == 0 && !glSupported()) {
            failed = true; // LWJGL aborts the JVM on a GL function the context lacks, so check before init() (macOS: GL 2.1)
            Log.warn("tree silhouettes: needs OpenGL 3.0 or its extensions (vertex arrays, framebuffers, texture arrays), this context is "
               + GL11.glGetString(GL11.GL_VERSION) + "; tree shadows fall back to the crown proxies");
            return false;
         }
         if (array == 0 && !init()) {
            failed = true;
            Log.warn("tree silhouettes: setup failed; tree shadows fall back to the crown proxies");
            return false;
         }
         Look[] todo;
         synchronized (PENDING) {
            todo = PENDING.isEmpty() ? null : PENDING.toArray(new Look[0]);
            PENDING.clear();
         }
         if (todo != null) {
            draw(todo);
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, array);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         return true;
      } catch (Throwable t) {
         failed = true;
         Log.warn("tree silhouettes: " + t + "; off for the rest of the session");
         return false;
      }
   }

   /** Render thread, after the compute: the unit back to no array. */
   static void unbind(int unit) {
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
      GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }

   private static final int[] VIEWPORT = new int[4];

   private static void draw(Look[] todo) {
      boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
      boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
      int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
      int previousFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
      int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL11.glViewport(0, 0, SIZE, SIZE);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_ALPHA_TEST);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL11.glColorMask(true, true, true, true);
      GL11.glEnable(GL11.GL_BLEND);
      GL14.glBlendEquation(GL14.GL_MAX); // trunk sprite and foliage overlay: their union
      GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
      GL20.glUseProgram(program);
      GL30.glBindVertexArray(vao);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      for (Look look : todo) {
         if (BY_LAYER[look.layer] != look) {
            continue; // evicted before it was ever drawn
         }
         GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, array, 0, look.layer);
         GL11.glClearColor(0F, 0F, 0F, 0F);
         GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
         for (int k = 0; k < look.textures.length; k++) {
            Texture t = look.textures[k];
            if (t.getTextureId() == null || t.getTextureId().getID() <= 0) {
               continue;
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.getTextureId().getID());
            // frame px (y down) -> NDC: the frame's top row lands in the layer's top row (v = 1)
            float x0 = look.rects[k * 4] / look.frameW * 2F - 1F, x1 = look.rects[k * 4 + 2] / look.frameW * 2F - 1F;
            float y0 = 1F - look.rects[k * 4 + 1] / look.frameH * 2F, y1 = 1F - look.rects[k * 4 + 3] / look.frameH * 2F;
            GL20.glUniform4f(uRect, x0, y0, x1, y1);
            GL20.glUniform4f(uUv, t.getXStart(), t.getYStart(), t.getXEnd(), t.getYEnd());
            GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         }
         look.drawn = true;
         drawnLayers++;
      }
      GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, 0, 0, 0);
      GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, array);
      GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
      GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
      // back to the caller's state (a compute about to run: no GLStateRenderThread.restore, which would turn the game's state on)
      GL14.glBlendEquation(GL14.GL_FUNC_ADD);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      toggle(GL11.GL_BLEND, blend);
      toggle(GL11.GL_SCISSOR_TEST, scissor);
      toggle(GL11.GL_STENCIL_TEST, stencil);
      toggle(GL11.GL_DEPTH_TEST, depth);
      toggle(GL11.GL_CULL_FACE, cull);
      GL30.glBindVertexArray(previousVao);
      GL20.glUseProgram(previousProgram);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      zombie.core.textures.Texture.lastTextureID = -1;
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
      GL11.glViewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
      zombie.core.SpriteRenderer.ringBuffer.restoreBoundTextures = true;
   }

   /** Render thread, dev (devAoDumpTree): level 0 of every layer, R8 rows bottom-up, layer after layer. */
   static void dump(java.io.File f) {
      try {
         ByteBuffer b = org.lwjgl.BufferUtils.createByteBuffer(SIZE * SIZE * LAYERS);
         int previous = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, array);
         GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
         GL11.glGetTexImage(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, b);
         GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previous);
         byte[] out = new byte[b.remaining()];
         b.get(out);
         java.nio.file.Files.write(f.toPath(), out);
      } catch (Throwable t) {
         Log.warn("tree silhouettes: dump failed: " + t);
      }
   }

   private static void toggle(int cap, boolean on) {
      if (on) {
         GL11.glEnable(cap);
      } else {
         GL11.glDisable(cap);
      }
   }

   private static boolean init() {
      program = AmbientOcclusion.link(VERT, FRAG);
      if (program == 0) {
         return false;
      }
      uRect = GL20.glGetUniformLocation(program, "rect");
      uUv = GL20.glGetUniformLocation(program, "uv");
      GL20.glUseProgram(program);
      GL20.glUniform1i(GL20.glGetUniformLocation(program, "Tex"), 0);
      GL20.glUseProgram(0);
      int previous = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
      array = GL11.glGenTextures();
      GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, array);
      int levels = 32 - Integer.numberOfLeadingZeros(SIZE);
      for (int k = 0; k < levels; k++) {
         int s = Math.max(1, SIZE >> k);
         GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, k, GL30.GL_R8, s, s, LAYERS, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
      }
      GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
      GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previous);
      fbo = GL30.glGenFramebuffers();
      vao = GL30.glGenVertexArrays();
      Log.info("tree silhouettes: " + LAYERS + " layers of " + SIZE + "x" + SIZE + " R8 ready");
      return true;
   }

   /** The quad from gl_VertexID: rect = NDC x0, y0 (top), x1, y1 (bottom); uv = the texture's atlas rectangle. */
   private static final String VERT = String.join("\n",
      "#version 140",
      "uniform vec4 rect;",
      "uniform vec4 uv;",
      "out vec2 vUv;",
      "void main() {",
      "   int v = gl_VertexID;",
      "   vec2 k = vec2((v == 1 || v == 2) ? 1.0 : 0.0, (v >= 2) ? 1.0 : 0.0);",
      "   vUv = mix(uv.xy, uv.zw, k);",
      "   gl_Position = vec4(mix(rect.x, rect.z, k.x), mix(rect.y, rect.w, k.y), 0.0, 1.0);",
      "}");

   private static final String FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D Tex;",
      "in vec2 vUv;",
      "out vec4 fragColor;",
      // the sprites of the bigger trees carry a painted ground shadow round the trunk's foot (black, half transparent): not
      // part of the tree; the leaves' soft edges keep their colour, so a dark texel only counts when it is (nearly) opaque
      "void main() {",
      "   vec4 c = texture(Tex, vUv);",
      "   float lum = dot(c.rgb, vec3(0.299, 0.587, 0.114));",
      "   fragColor = vec4(c.a > 0.8 || lum > 0.08 ? c.a : 0.0);",
      "}");
}
