package pzopt;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;

/**
 * macOS OpenGL 4.1 core profile ({@code macGlCore}, 2026-10-01, docs/findings-mac-gl41-2026-10-01.md).
 *
 * Stock macOS gets Apple's legacy 2.1 context (GLSL 1.20): no GL 3.x entry points, so the game runs its GL 2.1 shader
 * path and every pzopt enhancement that needs GL 3+ switches itself off. Apple only offers more as a core profile
 * (3.2 - 4.1, forward compatible), where the fixed-function API the game still touches is gone, and LWJGL then loads no
 * deprecated entry point (a call aborts the JVM). This class asks GLFW for 4.1 core and rebuilds LWJGL's function table
 * with a provider that hands Java upcalls for the removed calls, so the game and pzopt run unchanged on top:
 *
 * - shaders: glShaderSource translates every source (pzopt.CoreGlsl): GLSL 1.x / compatibility to 330 core, 4.2+ to 410,
 *   binding qualifiers applied after the link, the alpha test injected into fragment shaders;
 * - alpha test (glAlphaFunc / GL_ALPHA_TEST): a uniform on the bound program, kept current at glUseProgram;
 * - matrix stacks (glMatrixMode ... glOrtho): emulated, fed to translated shaders that read gl_ModelViewProjectionMatrix etc;
 * - immediate mode (glBegin / glVertex / glEnd, incl. GL_QUADS): its own VAO / VBO, the bound program or a fixed-function
 *   emulation program (texture env modulate / replace / add / decal);
 * - glPushAttrib / glPushClientAttrib: the push reads the states of its mask from the driver (Apple keeps them on the CPU
 *   side), the pop sets them back; the setters themselves stay the driver's entry points;
 * - a vertex array object bound at all times (core needs one), glBindVertexArray(0) maps to it;
 * - entry points the driver lacks get a shared no-op (LWJGL's OpenGL33 check wants compatibility-only packed-vertex calls),
 *   glTextureBarrier aliases glTextureBarrierNV, glClearTexImage is emulated, program validation reads "valid";
 * - EXT / ARB entry points alias their core functions; display lists, lights, materials, texture env are no-ops.
 */
public final class CoreGl {
   private CoreGl() {
   }

   static final boolean REQUESTED = HdrMac.MAC && Config.MAC_GL_CORE;
   /** The context is a core profile with this shim installed. */
   public static volatile boolean active;
   private static boolean hinted;

   // ------------------------------------------------------------------ context creation

   private static boolean fellBack;

   /**
    * macOS on Apple's legacy 2.1 context (GLSL 1.20, no GL 3 entry points): the GL 3+ features stay off. Before the window
    * exists this assumes the core context it asked for; after a failed core window it is true again.
    */
   public static boolean legacyMac() {
      return HdrMac.MAC && (!REQUESTED || fellBack);
   }

   /** GL timer queries for the overlay's GPU load and present pacing (macGlTimerQueries on the core context). */
   public static boolean timerQueries() {
      return !active || Config.MAC_GL_TIMER_QUERIES;
   }

   /** Display.create, before glfwCreateWindow: ask for 4.1 core. */
   public static void windowHints() {
      if (!REQUESTED) {
         return;
      }
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, 1);
      hinted = true;
   }

   /** Display.create: the window failed with the core hints; drop them so the retry gets the stock legacy context. */
   public static boolean retryWithoutCore() {
      if (!hinted) {
         return false;
      }
      hinted = false;
      fellBack = true;
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 1);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 0);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_ANY_PROFILE);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, 0);
      Log.warn("macGlCore: no OpenGL 4.1 core window, falling back to the legacy 2.1 context");
      return true;
   }

   /** Display.create, right after GL.createCapabilities(): install the shim table when the context is core. */
   public static GLCapabilities capabilities(GLCapabilities stock) {
      if (!hinted) {
         return stock;
      }
      try {
         int profile = GL11C.glGetInteger(GL32C.GL_CONTEXT_PROFILE_MASK);
         if ((profile & GL32C.GL_CONTEXT_CORE_PROFILE_BIT) == 0 || !stock.OpenGL32) {
            Log.warn("macGlCore: the context is not a core profile (" + GL11C.glGetString(GL11C.GL_VERSION) + "), shim off");
            fellBack = true;
            return stock;
         }
         GLCapabilities caps = install(stock);
         Log.info("macGlCore: OpenGL " + GL11C.glGetString(GL11C.GL_VERSION) + ", GLSL " + GL11C.glGetString(GL20C.GL_SHADING_LANGUAGE_VERSION)
            + "; core shim on (" + hooks.size() + " hooked entry points, " + aliases + " EXT/ARB aliases, " + missing + " absent as no-ops, OpenGL33 " + caps.OpenGL33 + ", alpha test in shaders "
            + (Config.CORE_ALPHA_INJECT ? "on" : "off") + ")");
         return caps;
      } catch (Throwable t) {
         Log.error("macGlCore: shim install failed: " + t);
         t.printStackTrace();
         fellBack = true;
         GL.setCapabilities(stock);
         return stock;
      }
   }

   // ------------------------------------------------------------------ function table

   private static FunctionProvider base;
   private static PointerBuffer table;
   private static final Map<String, Long> hooks = new HashMap<>();
   private static final List<Object> keep = new ArrayList<>();
   private static int aliases;
   static final Set<String> exts = new HashSet<>();
   private static long extensionsString;

   /** ARB / EXT names whose core function differs (generic object calls of ARB_shader_objects): never aliased. */
   private static final Set<String> NO_ALIAS = Set.of("glGetObjectParameterivARB", "glGetObjectParameterfvARB", "glGetInfoLogARB",
      "glDeleteObjectARB", "glGetHandleARB", "glCreateShaderObjectARB", "glCreateProgramObjectARB", "glAttachObjectARB",
      "glDetachObjectARB", "glGetAttachedObjectsARB");

   /** Extensions a 4.1 core context covers with core functions (the game and pzopt test the flags). */
   private static final String[] CORE_EXTS = {"GL_ARB_framebuffer_object", "GL_EXT_framebuffer_object", "GL_EXT_framebuffer_blit",
      "GL_EXT_framebuffer_multisample", "GL_EXT_packed_depth_stencil", "GL_ARB_vertex_buffer_object", "GL_ARB_map_buffer_range",
      "GL_ARB_texture_compression", "GL_ARB_vertex_array_object", "GL_ARB_draw_instanced", "GL_ARB_depth_buffer_float",
      "GL_EXT_texture_array", "GL_ARB_texture_float", "GL_ARB_half_float_pixel", "GL_ARB_texture_rg", "GL_EXT_texture_sRGB",
      "GL_ARB_sync", "GL_ARB_occlusion_query", "GL_ARB_texture_non_power_of_two", "GL_ARB_multitexture", "GL_ARB_vertex_shader",
      "GL_ARB_fragment_shader", "GL_ARB_shading_language_100", "GL_EXT_blend_func_separate", "GL_EXT_blend_equation_separate",
      "GL_ARB_copy_buffer", "GL_ARB_uniform_buffer_object", "GL_ARB_draw_elements_base_vertex", "GL_ARB_seamless_cube_map",
      "GL_ARB_depth_clamp", "GL_EXT_texture_swizzle", "GL_ARB_texture_rectangle", "GL_ARB_texture_multisample",
      "GL_ARB_provoking_vertex", "GL_ARB_texture_buffer_object", "GL_ARB_get_program_binary", "GL_ARB_draw_buffers",
      "GL_ARB_pixel_buffer_object", "GL_EXT_texture_integer", "GL_ARB_color_buffer_float", "GL_ARB_depth_texture",
      "GL_ARB_shadow", "GL_ARB_point_sprite", "GL_EXT_gpu_shader4", "GL_ARB_texture_border_clamp", "GL_ARB_texture_mirrored_repeat",
      "GL_EXT_blend_minmax", "GL_EXT_blend_subtract", "GL_EXT_texture_lod_bias", "GL_ARB_texture_cube_map", "GL_EXT_bgra",
      "GL_EXT_texture3D", "GL_EXT_draw_range_elements", "GL_ARB_vertex_program", "GL_ARB_fragment_program_shadow",
      "GL_ARB_framebuffer_sRGB", "GL_EXT_framebuffer_sRGB", "GL_ARB_texture_compression_rgtc", "GL_ARB_fragment_coord_conventions"};

   private static GLCapabilities install(GLCapabilities stock) throws Exception {
      base = GL.getFunctionProvider();
      int major = GL11C.glGetInteger(GL30C.GL_MAJOR_VERSION), minor = GL11C.glGetInteger(GL30C.GL_MINOR_VERSION);
      int[] versions = {10, 11, 12, 13, 14, 15, 20, 21, 30, 31, 32, 33, 40, 41, 42, 43, 44, 45, 46};
      for (int v : versions) {
         if (v / 10 < major || v / 10 == major && v % 10 <= minor) {
            exts.add("OpenGL" + v);
         }
      }
      int n = GL11C.glGetInteger(GL30C.GL_NUM_EXTENSIONS);
      StringBuilder all = new StringBuilder();
      for (int i = 0; i < n; i++) {
         String e = GL30C.glGetStringi(GL11C.GL_EXTENSIONS, i);
         exts.add(e);
         all.append(e).append(' ');
      }
      if (exts.contains("GL_NV_texture_barrier")) {
         EXPLICIT.put("glTextureBarrier", "glTextureBarrierNV"); // GL 4.5 / ARB_texture_barrier: the NV entry point does the same
         exts.add("GL_ARB_texture_barrier");
         all.append("GL_ARB_texture_barrier ");
      }
      exts.add("GL_ARB_clear_texture"); // emulated (clearTexImage)
      all.append("GL_ARB_clear_texture ");
      for (String e : CORE_EXTS) {
         if (exts.add(e)) {
            all.append(e).append(' ');
         }
      }
      extensionsString = MemoryUtil.memAddress(MemoryUtil.memUTF8(all.toString().trim(), true));
      registerHooks();
      FunctionProvider ours = new FunctionProvider() {
         @Override
         public long getFunctionAddress(ByteBuffer name) {
            return getFunctionAddress(MemoryUtil.memASCII(MemoryUtil.memAddress(name)));
         }

         @Override
         public long getFunctionAddress(CharSequence cs) {
            return address(cs.toString());
         }
      };
      Constructor<GLCapabilities> c = GLCapabilities.class.getDeclaredConstructor(FunctionProvider.class, Set.class, boolean.class, IntFunction.class);
      c.setAccessible(true);
      IntFunction<PointerBuffer> bf = PointerBuffer::allocateDirect;
      GLCapabilities caps = c.newInstance(ours, exts, false, bf);
      Field f = GLCapabilities.class.getDeclaredField("addresses");
      f.setAccessible(true);
      table = (PointerBuffer) f.get(caps);
      GL.setCapabilities(caps);
      initState();
      active = true;
      return caps;
   }

   private static final Map<String, String> EXPLICIT = new HashMap<>();

   private static long address(String name) {
      Long h = hooks.get(name);
      if (h != null) {
         return h;
      }
      String target = EXPLICIT.get(name);
      if (target != null) {
         aliases++;
         return base.getFunctionAddress(target);
      }
      if (Config.DEV_CORE_GL_TRACE && DEPRECATED_TRACE.contains(name)) {
         return traceStub(name);
      }
      if ((name.endsWith("ARB") || name.endsWith("EXT")) && !NO_ALIAS.contains(name)) {
         String core = name.substring(0, name.length() - 3);
         h = hooks.get(core);
         if (h != null) {
            aliases++;
            return h;
         }
         long a = base.getFunctionAddress(core);
         if (a != 0L) {
            aliases++;
            return a;
         }
      }
      long a = base.getFunctionAddress(name);
      if (a == 0L) {
         // a function the core driver lacks (the compatibility-only GL 3.3 packed-vertex calls LWJGL's OpenGL33 check
         // wants, removed legacy calls, extensions not on this context): one shared no-op instead of LWJGL's JVM abort.
         // The version and extension flags still follow the extension set, so nothing that checks them calls these.
         missing++;
         if (noop == 0L) {
            V0 cb = () -> { };
            keep.add(cb);
            noop = cb.address();
         }
         return noop;
      }
      return a;
   }

   private static long noop;
   private static int missing;

   private static long real(String name) {
      long a = base.getFunctionAddress(name);
      if (a == 0L) {
         throw new IllegalStateException("macGlCore: no driver entry point " + name);
      }
      return a;
   }

   private static void hook(String name, CallbackI cb) {
      keep.add(cb);
      hooks.put(name, cb.address());
   }

   // real driver entry points of hooked functions
   static long rEnable, rDisable, rUseProgram, rLinkProgram, rShaderSource, rCompileShader, rBindVertexArray, rGetString;
   // driver entry points of the state setters (the attribute stacks restore through them)
   static long rBlendFunc, rBlendFuncSeparate, rBlendEquation, rBlendEquationSeparate, rDepthFunc, rDepthMask, rColorMask, rViewport,
      rScissor, rCullFace, rFrontFace, rPolygonOffset, rPolygonMode, rStencilFunc, rStencilOp, rStencilMask, rLineWidth, rDepthRange,
      rClearColor, rBindTexture, rActiveTexture, rEnableVAA, rDisableVAA, rBindBuffer, rVertexAttribPointer, rVertexAttribDivisor,
      rPixelStorei;

   private static void registerHooks() {
      rEnable = real("glEnable");
      rDisable = real("glDisable");
      rUseProgram = real("glUseProgram");
      rLinkProgram = real("glLinkProgram");
      rShaderSource = real("glShaderSource");
      rCompileShader = real("glCompileShader");
      rBindVertexArray = real("glBindVertexArray");
      rGetString = real("glGetString");
      hook("glEnable", (VI) CoreGl::enable);
      hook("glDisable", (VI) CoreGl::disable);
      hook("glUseProgram", (VI) CoreGl::useProgram);
      hook("glLinkProgram", (VI) CoreGl::linkProgram);
      hook("glShaderSource", (VIIPP) CoreGl::shaderSource);
      hook("glCompileShader", (VI) CoreGl::compileShader);
      hook("glBindVertexArray", (VI) CoreGl::bindVertexArray);
      hook("glGetString", (PI) CoreGl::getString);
      hook("glAlphaFunc", (VIF) CoreGl::alphaFunc);
      // the game validates right after the link, before it points any sampler at a unit: Apple's validation fails a
      // program whose samplers of different types (2D, 2D array, 3D) all still sit on unit 0, which NVIDIA and Mesa pass.
      // The units are set before every draw, so the validation result reported is "valid".
      long getProgramiv = real("glGetProgramiv");
      hook("glValidateProgram", (VI) p -> { });
      hook("glGetProgramiv", (VIIP) (p, pname, out) -> {
         if (pname == GL20C.GL_VALIDATE_STATUS) {
            MemoryUtil.memPutInt(out, 1);
         } else {
            JNI.callPV(p, pname, out, getProgramiv);
         }
      });
      hook("glClearTexImage", (VIIIIP) (t, l, f, ty, d) -> clearTexture(t, l, 0, 0, 0, -1, -1, -1, f, ty, d));
      hook("glClearTexSubImage", (V11) CoreGl::clearTexture);
      // matrices
      hook("glMatrixMode", (VI) CoreGl::matrixMode);
      hook("glLoadIdentity", (V0) CoreGl::loadIdentity);
      hook("glPushMatrix", (V0) CoreGl::pushMatrix);
      hook("glPopMatrix", (V0) CoreGl::popMatrix);
      hook("glLoadMatrixf", (VP) p -> loadMatrix(p, false));
      hook("glMultMatrixf", (VP) p -> loadMatrix(p, true));
      hook("glOrtho", (V6D) CoreGl::ortho);
      hook("glFrustum", (V6D) CoreGl::frustum);
      hook("glTranslatef", (VFFF) (x, y, z) -> translate(x, y, z));
      hook("glTranslated", (VDDD) (x, y, z) -> translate((float) x, (float) y, (float) z));
      hook("glScalef", (VFFF) (x, y, z) -> scale(x, y, z));
      hook("glScaled", (VDDD) (x, y, z) -> scale((float) x, (float) y, (float) z));
      hook("glRotatef", (VFFFF) (a, x, y, z) -> rotate(a, x, y, z));
      hook("glRotated", (VDDDD) (a, x, y, z) -> rotate((float) a, (float) x, (float) y, (float) z));
      // immediate mode
      hook("glBegin", (VI) CoreGl::begin);
      hook("glEnd", (V0) CoreGl::end);
      hook("glVertex2f", (VFF) (x, y) -> vertex(x, y, 0.0F, 1.0F));
      hook("glVertex2i", (VII) (x, y) -> vertex(x, y, 0.0F, 1.0F));
      hook("glVertex2d", (VDD) (x, y) -> vertex((float) x, (float) y, 0.0F, 1.0F));
      hook("glVertex3f", (VFFF) (x, y, z) -> vertex(x, y, z, 1.0F));
      hook("glVertex3d", (VDDD) (x, y, z) -> vertex((float) x, (float) y, (float) z, 1.0F));
      hook("glColor3f", (VFFF) (r, g, b) -> color(r, g, b, 1.0F));
      hook("glColor4f", (VFFFF) CoreGl::color);
      hook("glColor3d", (VDDD) (r, g, b) -> color((float) r, (float) g, (float) b, 1.0F));
      hook("glColor4d", (VDDDD) (r, g, b, a) -> color((float) r, (float) g, (float) b, (float) a));
      hook("glTexCoord2f", (VFF) CoreGl::texCoord);
      hook("glTexCoord2d", (VDD) (s, t) -> texCoord((float) s, (float) t));
      hook("glNormal3f", (VFFF) CoreGl::normal);
      // texture env (fixed-function draws only), client state, lists, lighting
      hook("glTexEnvi", (VIII) CoreGl::texEnv);
      hook("glTexEnvf", (VIIF) (t, p, v) -> texEnv(t, p, (int) v));
      hook("glEnableClientState", (VI) c -> clientState(c, true));
      hook("glDisableClientState", (VI) c -> clientState(c, false));
      hook("glVertexPointer", (VIIIP) CoreGl::vertexPointer);
      hook("glGenLists", (II) r -> 0);
      hook("glNewList", (VII) (l, m) -> { });
      hook("glEndList", (V0) () -> { });
      hook("glCallList", (VI) l -> { });
      hook("glDeleteLists", (VII) (l, r) -> { });
      hook("glLightf", (VIIF) (a, b, v) -> { });
      hook("glLightfv", (VIIP) (a, b, p) -> { });
      hook("glMaterialfv", (VIIP) (a, b, p) -> { });
      hook("glColorMaterial", (VII) (a, b) -> { });
      hook("glShadeModel", (VI) m -> { });
      // attribute stacks
      hook("glPushAttrib", (VI) CoreGl::pushAttrib);
      hook("glPopAttrib", (V0) CoreGl::popAttrib);
      hook("glPushClientAttrib", (VI) CoreGl::pushClientAttrib);
      hook("glPopClientAttrib", (V0) CoreGl::popClientAttrib);

      if (Config.DEV_CORE_GL_TRACE) {
         traceHooks();
         zombie.debug.DebugLog.setLogEnabled(zombie.debug.DebugType.Shader, true); // the game's own shader errors (off in release)
      }
      rBlendFunc = real("glBlendFunc");
      rBlendFuncSeparate = real("glBlendFuncSeparate");
      rBlendEquation = real("glBlendEquation");
      rBlendEquationSeparate = real("glBlendEquationSeparate");
      rDepthFunc = real("glDepthFunc");
      rDepthMask = real("glDepthMask");
      rColorMask = real("glColorMask");
      rViewport = real("glViewport");
      rScissor = real("glScissor");
      rCullFace = real("glCullFace");
      rFrontFace = real("glFrontFace");
      rPolygonOffset = real("glPolygonOffset");
      rPolygonMode = real("glPolygonMode");
      rStencilFunc = real("glStencilFunc");
      rStencilOp = real("glStencilOp");
      rStencilMask = real("glStencilMask");
      rLineWidth = real("glLineWidth");
      rDepthRange = real("glDepthRange");
      rClearColor = real("glClearColor");
      rActiveTexture = real("glActiveTexture");
      rBindTexture = real("glBindTexture");
      rEnableVAA = real("glEnableVertexAttribArray");
      rDisableVAA = real("glDisableVertexAttribArray");
      rBindBuffer = real("glBindBuffer");
      rVertexAttribPointer = real("glVertexAttribPointer");
      rVertexAttribDivisor = real("glVertexAttribDivisor");
      rPixelStorei = real("glPixelStorei");
   }

   // ------------------------------------------------------------------ state

   private static int defaultVao;
   private static final boolean[] texture2D = new boolean[32];
   private static final int[] texEnvMode = new int[32];
   private static boolean alphaOn;
   private static int alphaFunc = GL11C.GL_ALWAYS;
   private static float alphaRef;
   private static int alphaGen = 1;
   private static boolean lightingOn, fogOn, colorMaterialOn, normalizeOn;
   private static int curProgram;

   private static void initState() {
      defaultVao = GL30C.glGenVertexArrays();
      JNI.callV(defaultVao, rBindVertexArray);
      java.util.Arrays.fill(texEnvMode, GL11.GL_MODULATE);
      for (int m = 0; m < 3; m++) {
         identity(stacks[m][0]);
      }
      // the core context's "current" generic attributes stand in for glColor / glTexCoord / glNormal
      GL20C.glVertexAttrib4f(CoreGlsl.ATTR_COLOR, 1.0F, 1.0F, 1.0F, 1.0F);
      GL20C.glVertexAttrib4f(CoreGlsl.ATTR_TEX0, 0.0F, 0.0F, 0.0F, 1.0F);
      GL20C.glVertexAttrib4f(CoreGlsl.ATTR_NORMAL, 0.0F, 0.0F, 1.0F, 1.0F);
   }

   /** Legacy enable caps the core profile rejects: kept here (glEnable / glDisable / push / pop). */
   private static boolean legacyCap(int cap) {
      switch (cap) {
         case 0x0BC0: // GL_ALPHA_TEST
         case 0x0DE1: // GL_TEXTURE_2D
         case 0x0DE0: // GL_TEXTURE_1D
         case 0x806F: // GL_TEXTURE_3D
         case 0x8513: // GL_TEXTURE_CUBE_MAP (not a core enable)
         case 0x0B50: // GL_LIGHTING
         case 0x0B60: // GL_FOG
         case 0x0B57: // GL_COLOR_MATERIAL
         case 0x0BA1: // GL_NORMALIZE
         case 0x803A: // GL_RESCALE_NORMAL
         case 0x0B10: // GL_POINT_SMOOTH
         case 0x8861: // GL_POINT_SPRITE
         case 0x0B24: // GL_LINE_STIPPLE
         case 0x0B42: // GL_POLYGON_STIPPLE
         case 0x0C60: case 0x0C61: case 0x0C62: case 0x0C63: // GL_TEXTURE_GEN_S/T/R/Q
         case 0x0D90: case 0x0D91: case 0x0D92: case 0x0D93: case 0x0D94: case 0x0D95: case 0x0D96: case 0x0D97: // map1
         case 0x0DB0: case 0x0DB1: case 0x0DB2: case 0x0DB3: case 0x0DB4: case 0x0DB5: case 0x0DB6: case 0x0DB7: // map2
         case 0x0D80: case 0x0D82: // auto normal, (map1 color)
         case 0x4000: case 0x4001: case 0x4002: case 0x4003: case 0x4004: case 0x4005: case 0x4006: case 0x4007: // lights
            return true;
         default:
            return false;
      }
   }

   private static void enable(int cap) {
      setCap(cap, true);
   }

   private static void disable(int cap) {
      setCap(cap, false);
   }

   /** Upcalls per hot hook since the last report (enable, disable, useProgram, bindVertexArray, attribute pushes, -). */
   static final long[] calls = new long[6];

   private static void setCap(int cap, boolean on) {
      calls[on ? 0 : 1]++;
      if (!legacyCap(cap)) {
         JNI.callV(cap, on ? rEnable : rDisable);
         if (Config.DEV_CORE_GL_TRACE) {
            chk(on ? "glEnable" : "glDisable", cap);
         }
         return;
      }
      switch (cap) {
         case 0x0BC0:
            if (alphaOn != on) {
               alphaOn = on;
               alphaChanged();
            }
            break;
         case 0x0DE1:
            texture2D[activeUnit()] = on;
            break;
         case 0x0B50:
            lightingOn = on;
            break;
         case 0x0B60:
            fogOn = on;
            break;
         case 0x0B57:
            colorMaterialOn = on;
            break;
         case 0x0BA1:
            normalizeOn = on;
            break;
         default:
            break;
      }
   }

   private static boolean capOn(int cap) {
      switch (cap) {
         case 0x0BC0:
            return alphaOn;
         case 0x0DE1:
            return texture2D[activeUnit()];
         case 0x0B50:
            return lightingOn;
         case 0x0B60:
            return fogOn;
         case 0x0B57:
            return colorMaterialOn;
         case 0x0BA1:
            return normalizeOn;
         default:
            return legacyCap(cap) ? false : JNI.callZ(cap, isEnabledAddr());
      }
   }

   private static long isEnabledAddr;

   private static long isEnabledAddr() {
      if (isEnabledAddr == 0L) {
         isEnabledAddr = real("glIsEnabled");
      }
      return isEnabledAddr;
   }

   private static int activeUnit() {
      return (GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE) - GL13C.GL_TEXTURE0) & 31;
   }

   private static void alphaFunc(int func, float ref) {
      if (func != alphaFunc || ref != alphaRef) {
         alphaFunc = func;
         alphaRef = ref;
         alphaChanged();
      }
   }

   private static void alphaChanged() {
      alphaGen++;
      Prog p = prog(curProgram);
      if (p != null && p.alphaLoc >= 0) {
         uploadAlpha(p);
      }
   }

   private static void uploadAlpha(Prog p) {
      float f = alphaOn ? alphaFunc - GL11C.GL_NEVER + 1 : 0.0F;
      GL20C.glUniform2f(p.alphaLoc, f, Math.max(0.0F, Math.min(1.0F, alphaRef)));
      p.alphaGen = alphaGen;
   }

   private static void texEnv(int target, int pname, int param) {
      if (target == 0x2300 && pname == 0x2200) { // GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE
         texEnvMode[activeUnit()] = param;
      }
   }

   // ------------------------------------------------------------------ programs and shaders

   static final class Prog {
      int alphaLoc = -1, mvp = -1, mv = -1, proj = -1, normal = -1, texMat = -1, mvInv = -1, projInv = -1, mvpInv = -1;
      int alphaGen = -1, matGen = -1;

      boolean matrices() {
         return mvp >= 0 || mv >= 0 || proj >= 0 || normal >= 0 || texMat >= 0 || mvInv >= 0 || projInv >= 0 || mvpInv >= 0;
      }
   }

   private static Prog[] progs = new Prog[1024];
   private static final Map<Integer, Map<String, Integer>> shaderBindings = new HashMap<>();
   private static final Map<Integer, String> shaderSources = new HashMap<>();

   private static Prog prog(int id) {
      return id > 0 && id < progs.length ? progs[id] : null;
   }

   private static void useProgram(int id) {
      calls[2]++;
      JNI.callV(id, rUseProgram);
      if (Config.DEV_CORE_GL_TRACE) {
         chk("glUseProgram", id);
      }
      curProgram = id;
      Prog p = prog(id);
      if (p != null) {
         if (p.alphaLoc >= 0 && p.alphaGen != alphaGen) {
            uploadAlpha(p);
         }
         if (p.matGen != matGen && p.matrices()) {
            uploadMatrices(p);
         }
      }
   }

   private static void shaderSource(int shader, int count, long strings, long lengths) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < count; i++) {
         long sp = MemoryUtil.memGetAddress(strings + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE);
         int len = lengths == 0L ? -1 : MemoryUtil.memGetInt(lengths + 4L * i);
         sb.append(len < 0 ? MemoryUtil.memUTF8(sp) : MemoryUtil.memUTF8(sp, len));
      }
      String src = sb.toString();
      int type = GL20C.glGetShaderi(shader, GL20C.GL_SHADER_TYPE);
      if (type == GL20C.GL_VERTEX_SHADER || type == GL20C.GL_FRAGMENT_SHADER) {
         CoreGlsl.Result r = CoreGlsl.translate(src, type == GL20C.GL_VERTEX_SHADER, exts, Config.CORE_ALPHA_INJECT);
         src = r.source;
         if (r.bindings.isEmpty()) {
            shaderBindings.remove(shader);
         } else {
            shaderBindings.put(shader, r.bindings);
         }
      }
      shaderSources.put(shader, src);
      ByteBuffer text = MemoryUtil.memUTF8(src, false);
      long block = MemoryUtil.nmemAlloc(16);
      try {
         MemoryUtil.memPutAddress(block, MemoryUtil.memAddress(text));
         MemoryUtil.memPutInt(block + 8, text.remaining());
         JNI.callPPV(shader, 1, block, block + 8, rShaderSource);
      } finally {
         MemoryUtil.nmemFree(block);
         MemoryUtil.memFree(text);
      }
   }

   private static void compileShader(int shader) {
      JNI.callV(shader, rCompileShader);
      if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
         String src = shaderSources.get(shader);
         StringBuilder sb = new StringBuilder("macGlCore: shader " + shader + " failed to compile: "
            + GL20C.glGetShaderInfoLog(shader).trim() + "\n--- translated source ---\n");
         if (src != null) {
            String[] lines = src.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
               sb.append(String.format("%4d  %s%n", i + 1, lines[i]));
            }
         }
         Log.warn(sb.toString());
         failedShaders++;
      } else {
         shaderSources.remove(shader);
      }
   }

   static int failedShaders, linkedPrograms;

   private static void linkProgram(int program) {
      for (String[] a : CoreGlsl.ATTRIBS) {
         GL20C.glBindAttribLocation(program, Integer.parseInt(a[1]), a[0]);
      }
      GL30C.glBindFragDataLocation(program, 0, "pz_FragColor");
      GL30C.glBindFragDataLocation(program, 0, "pz_FragData");
      JNI.callV(program, rLinkProgram);
      if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
         Log.warn("macGlCore: program " + program + " failed to link: " + GL20C.glGetProgramInfoLog(program).trim());
         return;
      }
      linkedPrograms++;
      Prog p = new Prog();
      p.alphaLoc = GL20C.glGetUniformLocation(program, CoreGlsl.ALPHA_UNIFORM);
      p.mvp = GL20C.glGetUniformLocation(program, "pz_ModelViewProjectionMatrix");
      p.mv = GL20C.glGetUniformLocation(program, "pz_ModelViewMatrix");
      p.proj = GL20C.glGetUniformLocation(program, "pz_ProjectionMatrix");
      p.normal = GL20C.glGetUniformLocation(program, "pz_NormalMatrix");
      p.texMat = GL20C.glGetUniformLocation(program, "pz_TextureMatrix");
      p.mvInv = GL20C.glGetUniformLocation(program, "pz_ModelViewMatrixInverse");
      p.projInv = GL20C.glGetUniformLocation(program, "pz_ProjectionMatrixInverse");
      p.mvpInv = GL20C.glGetUniformLocation(program, "pz_ModelViewProjectionMatrixInverse");
      if (program >= progs.length) {
         progs = java.util.Arrays.copyOf(progs, Math.max(program + 1, progs.length * 2));
      }
      progs[program] = p.alphaLoc >= 0 || p.matrices() ? p : null;
      // binding qualifiers the translation removed (GLSL 4.20 layout(binding = N))
      int[] count = new int[1];
      int[] shaders = new int[8];
      GL20C.glGetAttachedShaders(program, count, shaders);
      for (int i = 0; i < count[0]; i++) {
         Map<String, Integer> b = shaderBindings.get(shaders[i]);
         if (b == null) {
            continue;
         }
         for (Map.Entry<String, Integer> e : b.entrySet()) {
            if (e.getKey().startsWith("block:")) {
               int index = GL31C.glGetUniformBlockIndex(program, e.getKey().substring(6));
               if (index != -1) {
                  GL31C.glUniformBlockBinding(program, index, e.getValue());
               }
            } else {
               int loc = GL20C.glGetUniformLocation(program, e.getKey());
               if (loc >= 0) {
                  GL41C.glProgramUniform1i(program, loc, e.getValue());
               }
            }
         }
      }
   }

   private static void bindVertexArray(int vao) {
      calls[3]++;
      JNI.callV(vao == 0 ? defaultVao : vao, rBindVertexArray);
      if (Config.DEV_CORE_GL_TRACE) {
         chk("glBindVertexArray", vao);
      }
   }

   private static long getString(int name) {
      if (name == GL11C.GL_EXTENSIONS) {
         return extensionsString;
      }
      return JNI.callP(name, rGetString);
   }

   // ------------------------------------------------------------------ matrix stacks

   private static final int DEPTH = 32;
   private static final float[][][] stacks = new float[3][DEPTH][16];
   private static final int[] top = new int[3];
   private static int matrixMode;
   private static int matGen = 1;
   private static final float[] tmp = new float[16], tmp2 = new float[16];
   private static final FloatBuffer matBuf = MemoryUtil.memAllocFloat(16);

   private static float[] cur() {
      return stacks[matrixMode][top[matrixMode]];
   }

   private static void matrixMode(int mode) {
      matrixMode = mode == GL11.GL_PROJECTION ? 1 : mode == GL11.GL_TEXTURE ? 2 : 0;
   }

   private static void matrixChanged() {
      matGen++;
      Prog p = prog(curProgram);
      if (p != null && p.matrices()) {
         uploadMatrices(p);
      }
   }

   private static void loadIdentity() {
      identity(cur());
      matrixChanged();
   }

   private static void pushMatrix() {
      int m = matrixMode;
      if (top[m] + 1 < DEPTH) {
         System.arraycopy(stacks[m][top[m]], 0, stacks[m][top[m] + 1], 0, 16);
         top[m]++;
      }
   }

   private static void popMatrix() {
      int m = matrixMode;
      if (top[m] > 0) {
         top[m]--;
         matrixChanged();
      }
   }

   private static void loadMatrix(long p, boolean mult) {
      for (int i = 0; i < 16; i++) {
         tmp[i] = MemoryUtil.memGetFloat(p + 4L * i);
      }
      if (mult) {
         multiply(tmp);
      } else {
         System.arraycopy(tmp, 0, cur(), 0, 16);
         matrixChanged();
      }
   }

   private static void multiply(float[] m) {
      float[] c = cur();
      mul(c, m, tmp2);
      System.arraycopy(tmp2, 0, c, 0, 16);
      matrixChanged();
   }

   private static void ortho(double l, double r, double b, double t, double n, double f) {
      java.util.Arrays.fill(tmp, 0.0F);
      tmp[0] = (float) (2.0 / (r - l));
      tmp[5] = (float) (2.0 / (t - b));
      tmp[10] = (float) (-2.0 / (f - n));
      tmp[12] = (float) (-(r + l) / (r - l));
      tmp[13] = (float) (-(t + b) / (t - b));
      tmp[14] = (float) (-(f + n) / (f - n));
      tmp[15] = 1.0F;
      multiply(tmp);
   }

   private static void frustum(double l, double r, double b, double t, double n, double f) {
      java.util.Arrays.fill(tmp, 0.0F);
      tmp[0] = (float) (2.0 * n / (r - l));
      tmp[5] = (float) (2.0 * n / (t - b));
      tmp[8] = (float) ((r + l) / (r - l));
      tmp[9] = (float) ((t + b) / (t - b));
      tmp[10] = (float) (-(f + n) / (f - n));
      tmp[11] = -1.0F;
      tmp[14] = (float) (-2.0 * f * n / (f - n));
      multiply(tmp);
   }

   private static void translate(float x, float y, float z) {
      identity(tmp);
      tmp[12] = x;
      tmp[13] = y;
      tmp[14] = z;
      multiply(tmp);
   }

   private static void scale(float x, float y, float z) {
      identity(tmp);
      tmp[0] = x;
      tmp[5] = y;
      tmp[10] = z;
      multiply(tmp);
   }

   private static void rotate(float angle, float x, float y, float z) {
      float len = (float) Math.sqrt(x * x + y * y + z * z);
      if (len == 0.0F) {
         return;
      }
      x /= len;
      y /= len;
      z /= len;
      double a = Math.toRadians(angle);
      float c = (float) Math.cos(a), s = (float) Math.sin(a), ic = 1.0F - c;
      identity(tmp);
      tmp[0] = x * x * ic + c;
      tmp[1] = y * x * ic + z * s;
      tmp[2] = x * z * ic - y * s;
      tmp[4] = x * y * ic - z * s;
      tmp[5] = y * y * ic + c;
      tmp[6] = y * z * ic + x * s;
      tmp[8] = x * z * ic + y * s;
      tmp[9] = y * z * ic - x * s;
      tmp[10] = z * z * ic + c;
      multiply(tmp);
   }

   static void identity(float[] m) {
      java.util.Arrays.fill(m, 0.0F);
      m[0] = m[5] = m[10] = m[15] = 1.0F;
   }

   /** out = a * b, column-major. */
   static void mul(float[] a, float[] b, float[] out) {
      for (int c = 0; c < 4; c++) {
         for (int r = 0; r < 4; r++) {
            out[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
         }
      }
   }

   static boolean invert(float[] m, float[] inv) {
      float[] t = new float[16];
      t[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
      t[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
      t[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
      t[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
      t[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
      t[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
      t[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
      t[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
      t[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
      t[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
      t[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
      t[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
      t[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
      t[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
      t[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
      t[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
      float det = m[0] * t[0] + m[1] * t[4] + m[2] * t[8] + m[3] * t[12];
      if (det == 0.0F) {
         identity(inv);
         return false;
      }
      for (int i = 0; i < 16; i++) {
         inv[i] = t[i] / det;
      }
      return true;
   }

   private static void uploadMatrices(Prog p) {
      float[] mv = stacks[0][top[0]], pr = stacks[1][top[1]];
      if (p.mv >= 0) {
         upload(p.mv, mv);
      }
      if (p.proj >= 0) {
         upload(p.proj, pr);
      }
      if (p.mvp >= 0 || p.mvpInv >= 0) {
         float[] mvp = new float[16];
         mul(pr, mv, mvp);
         if (p.mvp >= 0) {
            upload(p.mvp, mvp);
         }
         if (p.mvpInv >= 0) {
            float[] inv = new float[16];
            invert(mvp, inv);
            upload(p.mvpInv, inv);
         }
      }
      if (p.mvInv >= 0 || p.normal >= 0) {
         float[] inv = new float[16];
         invert(mv, inv);
         if (p.mvInv >= 0) {
            upload(p.mvInv, inv);
         }
         if (p.normal >= 0) {
            // normal matrix = transpose of the inverse's upper 3x3
            float[] n = {inv[0], inv[4], inv[8], inv[1], inv[5], inv[9], inv[2], inv[6], inv[10]};
            GL20C.glUniformMatrix3fv(p.normal, false, n);
         }
      }
      if (p.projInv >= 0) {
         float[] inv = new float[16];
         invert(pr, inv);
         upload(p.projInv, inv);
      }
      if (p.texMat >= 0) {
         upload(p.texMat, stacks[2][top[2]]);
      }
      p.matGen = matGen;
   }

   private static void upload(int loc, float[] m) {
      matBuf.clear();
      matBuf.put(m).flip();
      GL20C.glUniformMatrix4fv(loc, false, matBuf);
   }

   // ------------------------------------------------------------------ immediate mode

   private static final int STRIDE = 15; // xyzw rgba stpq nxnynz
   private static float[] imm = new float[STRIDE * 256];
   private static int immCount, immMode = -1;
   private static final float[] curColor = {1.0F, 1.0F, 1.0F, 1.0F}, curTex = {0.0F, 0.0F, 0.0F, 1.0F}, curNormal = {0.0F, 0.0F, 1.0F};
   private static int immVao, immVbo, ffProgram;
   private static Prog ffProg;
   private static int ffTexLoc, ffEnvLoc, ffSamplerLoc;
   private static ByteBuffer immBuf;

   private static void begin(int mode) {
      immMode = mode;
      immCount = 0;
   }

   private static void vertex(float x, float y, float z, float w) {
      if (immMode < 0) {
         return;
      }
      int o = immCount * STRIDE;
      if (o + STRIDE > imm.length) {
         imm = java.util.Arrays.copyOf(imm, imm.length * 2);
      }
      imm[o] = x;
      imm[o + 1] = y;
      imm[o + 2] = z;
      imm[o + 3] = w;
      System.arraycopy(curColor, 0, imm, o + 4, 4);
      System.arraycopy(curTex, 0, imm, o + 8, 4);
      System.arraycopy(curNormal, 0, imm, o + 12, 3);
      immCount++;
   }

   private static void color(float r, float g, float b, float a) {
      curColor[0] = r;
      curColor[1] = g;
      curColor[2] = b;
      curColor[3] = a;
      if (immMode < 0) {
         GL20C.glVertexAttrib4f(CoreGlsl.ATTR_COLOR, r, g, b, a);
      }
   }

   private static void texCoord(float s, float t) {
      curTex[0] = s;
      curTex[1] = t;
      curTex[2] = 0.0F;
      curTex[3] = 1.0F;
      if (immMode < 0) {
         GL20C.glVertexAttrib4f(CoreGlsl.ATTR_TEX0, s, t, 0.0F, 1.0F);
      }
   }

   private static void normal(float x, float y, float z) {
      curNormal[0] = x;
      curNormal[1] = y;
      curNormal[2] = z;
      if (immMode < 0) {
         GL20C.glVertexAttrib3f(CoreGlsl.ATTR_NORMAL, x, y, z);
      }
   }

   static int immediateDraws;

   private static void end() {
      int mode = immMode;
      immMode = -1;
      if (immCount == 0) {
         return;
      }
      float[] v = imm;
      int count = immCount;
      if (mode == 7) { // GL_QUADS -> triangles
         int quads = count / 4;
         float[] t = new float[quads * 6 * STRIDE];
         int[] order = {0, 1, 2, 0, 2, 3};
         for (int q = 0; q < quads; q++) {
            for (int k = 0; k < 6; k++) {
               System.arraycopy(v, (q * 4 + order[k]) * STRIDE, t, (q * 6 + k) * STRIDE, STRIDE);
            }
         }
         v = t;
         count = quads * 6;
         mode = GL11C.GL_TRIANGLES;
      } else if (mode == 8) { // GL_QUAD_STRIP
         mode = GL11C.GL_TRIANGLE_STRIP;
      } else if (mode == 9) { // GL_POLYGON
         mode = GL11C.GL_TRIANGLE_FAN;
      }
      if (immVao == 0) {
         immVao = GL30C.glGenVertexArrays();
         immVbo = GL15C.glGenBuffers();
      }
      int prevVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
      int prevBuf = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
      JNI.callV(immVao, rBindVertexArray);
      JNI.callV(GL15C.GL_ARRAY_BUFFER, immVbo, rBindBuffer);
      int bytes = count * STRIDE * 4;
      if (immBuf == null || immBuf.capacity() < bytes) {
         if (immBuf != null) {
            MemoryUtil.memFree(immBuf);
         }
         immBuf = MemoryUtil.memAlloc(Math.max(bytes, 64 * 1024));
      }
      immBuf.clear();
      immBuf.asFloatBuffer().put(v, 0, count * STRIDE);
      immBuf.limit(bytes);
      GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, immBuf, GL15C.GL_STREAM_DRAW);
      int s = STRIDE * 4;
      attrib(0, 4, s, 0);
      attrib(CoreGlsl.ATTR_COLOR, 4, s, 16);
      attrib(CoreGlsl.ATTR_TEX0, 4, s, 32);
      attrib(CoreGlsl.ATTR_NORMAL, 3, s, 48);
      boolean ff = curProgram == 0;
      if (ff) {
         useFixedFunction();
      }
      GL11C.glDrawArrays(mode, 0, count);
      immediateDraws++;
      if (ff) {
         JNI.callV(0, rUseProgram);
      }
      JNI.callV(GL15C.GL_ARRAY_BUFFER, prevBuf, rBindBuffer);
      JNI.callV(prevVao, rBindVertexArray);
   }

   private static void attrib(int index, int size, int stride, long offset) {
      JNI.callV(index, rEnableVAA);
      JNI.callPV(index, size, GL11C.GL_FLOAT, 0, stride, offset, rVertexAttribPointer);
   }

   private static final String FF_VERT = "#version 330 core\n"
      + "layout(location = 0) in vec4 pz_Vertex;\nlayout(location = 3) in vec4 pz_Color;\nlayout(location = 8) in vec4 pz_MultiTexCoord0;\n"
      + "uniform mat4 pz_ModelViewProjectionMatrix;\nuniform mat4 pz_TextureMatrix;\nout vec4 pzColor;\nout vec4 pzTex;\n"
      + "void main() {\n   gl_Position = pz_ModelViewProjectionMatrix * pz_Vertex;\n   pzColor = pz_Color;\n   pzTex = pz_TextureMatrix * pz_MultiTexCoord0;\n}\n";
   private static final String FF_FRAG = "#version 330 core\n"
      + "in vec4 pzColor;\nin vec4 pzTex;\nuniform sampler2D pzFfTex;\nuniform int pzFfEnv;\nout vec4 pz_FragColor;\n"
      + "void main() {\n   vec4 c = pzColor;\n   if (pzFfEnv != 0) {\n      vec4 t = texture(pzFfTex, pzTex.st / pzTex.q);\n"
      + "      if (pzFfEnv == 2) c = t;\n      else if (pzFfEnv == 3) c = vec4(c.rgb + t.rgb, c.a * t.a);\n"
      + "      else if (pzFfEnv == 4) c = vec4(mix(c.rgb, t.rgb, t.a), c.a);\n      else c *= t;\n   }\n   pz_FragColor = c;\n}\n";

   private static void useFixedFunction() {
      if (ffProgram == 0) {
         int vs = GL20C.glCreateShader(GL20C.GL_VERTEX_SHADER), fs = GL20C.glCreateShader(GL20C.GL_FRAGMENT_SHADER);
         GL20C.glShaderSource(vs, FF_VERT);
         GL20C.glCompileShader(vs);
         GL20C.glShaderSource(fs, FF_FRAG);
         GL20C.glCompileShader(fs);
         int p = GL20C.glCreateProgram();
         GL20C.glAttachShader(p, vs);
         GL20C.glAttachShader(p, fs);
         GL20C.glLinkProgram(p);
         ffProgram = p;
         ffProg = prog(p);
         ffSamplerLoc = GL20C.glGetUniformLocation(p, "pzFfTex");
         ffEnvLoc = GL20C.glGetUniformLocation(p, "pzFfEnv");
      }
      JNI.callV(ffProgram, rUseProgram);
      if (ffProg != null) {
         if (ffProg.alphaLoc >= 0) {
            uploadAlpha(ffProg);
         }
         uploadMatrices(ffProg);
      }
      int unit = activeUnit();
      int env = 0;
      if (texture2D[0]) {
         int m = texEnvMode[0];
         env = m == GL11.GL_REPLACE ? 2 : m == GL11.GL_ADD ? 3 : m == GL11.GL_DECAL ? 4 : 1;
      }
      GL20C.glUniform1i(ffEnvLoc, env);
      GL20C.glUniform1i(ffSamplerLoc, 0);
      if (unit != 0) {
         // the fixed-function stage samples unit 0, whatever the active unit is
      }
   }

   // ------------------------------------------------------------------ client state (glVertexPointer / glEnableClientState)

   private static boolean vertexArrayViaClient;

   private static void clientState(int array, boolean on) {
      if (array != 0x8074) { // only GL_VERTEX_ARRAY has a user (WorldMapVBOs); colour / texcoord arrays are never pointed
         return;
      }
      if (on) {
         JNI.callV(0, rEnableVAA);
         vertexArrayViaClient = true;
      } else if (vertexArrayViaClient) {
         JNI.callV(0, rDisableVAA);
         vertexArrayViaClient = false;
      }
   }

   private static void vertexPointer(int size, int type, int stride, long pointer) {
      JNI.callPV(0, size, type, 0, stride, pointer, rVertexAttribPointer);
   }

   // ------------------------------------------------------------------ attribute stacks

   // state ids; per-index families get a base + index
   static final int ST_BLEND_FUNC = 1, ST_BLEND_EQ = 2, ST_DEPTH_FUNC = 3, ST_DEPTH_MASK = 4, ST_COLOR_MASK = 5, ST_VIEWPORT = 6,
      ST_SCISSOR = 7, ST_CULL = 8, ST_FRONT_FACE = 9, ST_POLY_OFFSET = 10, ST_POLY_MODE = 11, ST_STENCIL = 12, ST_LINE_WIDTH = 13,
      ST_DEPTH_RANGE = 14, ST_CLEAR_COLOR = 15, ST_ALPHA_FUNC = 16, ST_MATRIX_MODE = 17, ST_ACTIVE_TEXTURE = 18,
      ST_TEXTURE = 32, // + unit (32)
      ST_ENABLE = 64, // + capIndex (64)
      CL_ATTRIB = 128, CL_POINTER = 160, CL_DIVISOR = 192, CL_ARRAY_BUFFER = 224, CL_ELEMENT_BUFFER = 225,
      CL_PIXEL = 256, // + pname & 0xFF
      STATES = 512;
   private static final int[] CAPS = {0x0BE2, 0x0B71, 0x0B44, 0x0C11, 0x0B90, 0x8037, 0x0B20, 0x0BD0, 0x0BF2, 0x809E, 0x864F, 0x8DB9,
      0x0B41, 0x809D, 0x8642, 0x2A01, 0x2A02, 0x0B10, 0x8C36, 0x884F,
      0x0BC0, 0x0DE1, 0x0B50, 0x0B60, 0x0B57, 0x0BA1};
   private static final int[] pixelPname = new int[256];

   private static int capIndex(int cap) {
      for (int i = 0; i < CAPS.length; i++) {
         if (CAPS[i] == cap) {
            return i;
         }
      }
      return 63; // untracked cap: shares a slot that restores nothing
   }

   private static final class Frame {
      final boolean[] has = new boolean[STATES];
      final int[][] ints = new int[STATES][];
      final float[][] floats = new float[STATES][];
      int mask;
      boolean client;
      int savedUnit = -1;
   }

   private static final Frame[] server = new Frame[16], client = new Frame[16];
   private static int serverDepth, clientDepth;

   private static void pushAttrib(int mask) {
      if (serverDepth >= server.length) {
         return;
      }
      Frame f = server[serverDepth] == null ? server[serverDepth] = new Frame() : server[serverDepth];
      f.mask = mask;
      f.client = false;
      snapshot(f);
      serverDepth++;
      pushes++;
      calls[4]++;
   }

   private static void pushClientAttrib(int mask) {
      if (clientDepth >= client.length) {
         return;
      }
      Frame f = client[clientDepth] == null ? client[clientDepth] = new Frame() : client[clientDepth];
      f.mask = mask;
      f.client = true;
      snapshot(f);
      clientDepth++;
      pushes++;
      calls[4]++;
   }

   private static void popAttrib() {
      if (serverDepth == 0) {
         return;
      }
      restore(server[--serverDepth]);
   }

   private static void popClientAttrib() {
      if (clientDepth == 0) {
         return;
      }
      restore(client[--clientDepth]);
   }

   static int pushes;


   private static boolean inMask(Frame f, int state) {
      if (f.client) {
         return state >= CL_ATTRIB;
      }
      if (state >= CL_ATTRIB) {
         return false;
      }
      int m = f.mask;
      if (m == 0xFFFFF || m == -1) {
         return true;
      }
      if (state >= ST_ENABLE) {
         int cap = CAPS.length > state - ST_ENABLE ? CAPS[state - ST_ENABLE] : 0;
         if ((m & 0x2000) != 0) {
            return true; // GL_ENABLE_BIT
         }
         return cap == 0x0BE2 || cap == 0x0BC0 ? (m & 0x4000) != 0 : cap == 0x0B71 ? (m & 0x100) != 0 : cap == 0x0C11 ? (m & 0x80000) != 0
            : cap == 0x0B90 ? (m & 0x400) != 0 : cap == 0x0B44 ? (m & 0x8) != 0 : cap == 0x0DE1 ? (m & 0x40000) != 0 : false;
      }
      if (state >= ST_TEXTURE) {
         return (m & 0x40000) != 0;
      }
      switch (state) {
         case ST_BLEND_FUNC:
         case ST_BLEND_EQ:
         case ST_COLOR_MASK:
         case ST_CLEAR_COLOR:
         case ST_ALPHA_FUNC:
            return (m & 0x4000) != 0;
         case ST_DEPTH_FUNC:
         case ST_DEPTH_MASK:
            return (m & 0x100) != 0;
         case ST_VIEWPORT:
         case ST_DEPTH_RANGE:
            return (m & 0x800) != 0;
         case ST_SCISSOR:
            return (m & 0x80000) != 0;
         case ST_CULL:
         case ST_FRONT_FACE:
         case ST_POLY_OFFSET:
         case ST_POLY_MODE:
            return (m & 0x8) != 0;
         case ST_STENCIL:
            return (m & 0x400) != 0;
         case ST_LINE_WIDTH:
            return (m & 0x4) != 0;
         case ST_MATRIX_MODE:
            return (m & 0x1000) != 0;
         case ST_ACTIVE_TEXTURE:
            return (m & 0x40000) != 0;
         default:
            return false;
      }
   }

   private static final int[] i4 = new int[4];
   private static final float[] f4 = new float[4];

   /** Pixel store parameters a client push saves (pack / unpack alignment, row length, skips). */
   private static final int[] PIXEL_PNAMES = {0x0CF5, 0x0D05, 0x0CF2, 0x0CF3, 0x0CF4, 0x0D02};
   private static final int SNAPSHOT_UNITS = 16, SNAPSHOT_ATTRIBS = 16;

   /**
    * glPushAttrib / glPushClientAttrib: the frame takes every state of its mask now, read from the driver (Apple keeps it
    * on the CPU side: ~120 reads a push, no GPU sync). Cheaper than intercepting the setters inside the push: the game
    * pushes around each model draw and calls ~10,000 setters a frame inside those pushes.
    */
   private static void snapshot(Frame f) {
      java.util.Arrays.fill(f.has, false);
      if (f.client) {
         for (int i = 0; i < SNAPSHOT_ATTRIBS; i++) {
            take(f, CL_ATTRIB + i);
            if (f.ints[CL_ATTRIB + i][0] != 0) {
               take(f, CL_POINTER + i);
               take(f, CL_DIVISOR + i);
            }
         }
         take(f, CL_ARRAY_BUFFER);
         take(f, CL_ELEMENT_BUFFER);
         for (int p : PIXEL_PNAMES) {
            pixelPname[p & 0xFF] = p;
            take(f, CL_PIXEL + (p & 0xFF));
         }
         return;
      }
      for (int st = ST_BLEND_FUNC; st <= ST_ACTIVE_TEXTURE; st++) {
         if (inMask(f, st)) {
            take(f, st);
         }
      }
      for (int i = 0; i < CAPS.length; i++) {
         if (inMask(f, ST_ENABLE + i)) {
            take(f, ST_ENABLE + i);
         }
      }
      if (inMask(f, ST_TEXTURE)) {
         int active = activeUnit();
         for (int u = 0; u < SNAPSHOT_UNITS; u++) {
            JNI.callV(GL13C.GL_TEXTURE0 + u, rActiveTexture);
            int[] v = f.ints[ST_TEXTURE + u];
            if (v == null) {
               v = f.ints[ST_TEXTURE + u] = new int[1];
            }
            v[0] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            f.has[ST_TEXTURE + u] = true;
         }
         JNI.callV(GL13C.GL_TEXTURE0 + active, rActiveTexture);
      }
   }

   private static void take(Frame f, int state) {
      int[] iv = readInts(state);
      f.ints[state] = iv;
      f.floats[state] = iv == null ? readFloats(state) : null;
      f.has[state] = true;
   }

   private static int[] readInts(int state) {
      if (state >= CL_PIXEL) {
         int pname = pixelPname[state - CL_PIXEL];
         return pname == 0 ? null : new int[] {pname, GL11C.glGetInteger(pname)};
      }
      if (state == CL_ARRAY_BUFFER) {
         return new int[] {GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING)};
      }
      if (state == CL_ELEMENT_BUFFER) {
         return new int[] {GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING)};
      }
      if (state >= CL_DIVISOR) {
         return new int[] {GL20C.glGetVertexAttribi(state - CL_DIVISOR, GL33C.GL_VERTEX_ATTRIB_ARRAY_DIVISOR)};
      }
      if (state >= CL_POINTER) {
         int i = state - CL_POINTER;
         long ptr = GL20C.glGetVertexAttribPointer(i, GL20C.GL_VERTEX_ATTRIB_ARRAY_POINTER);
         return new int[] {GL20C.glGetVertexAttribi(i, GL20C.GL_VERTEX_ATTRIB_ARRAY_SIZE), GL20C.glGetVertexAttribi(i, GL20C.GL_VERTEX_ATTRIB_ARRAY_TYPE),
            GL20C.glGetVertexAttribi(i, GL20C.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED), GL20C.glGetVertexAttribi(i, GL20C.GL_VERTEX_ATTRIB_ARRAY_STRIDE),
            GL20C.glGetVertexAttribi(i, GL15C.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING), (int) (ptr >>> 32), (int) ptr,
            GL20C.glGetVertexAttribi(i, GL30C.GL_VERTEX_ATTRIB_ARRAY_INTEGER)};
      }
      if (state >= CL_ATTRIB) {
         return new int[] {GL20C.glGetVertexAttribi(state - CL_ATTRIB, GL20C.GL_VERTEX_ATTRIB_ARRAY_ENABLED)};
      }
      if (state >= ST_ENABLE) {
         int idx = state - ST_ENABLE;
         if (idx >= CAPS.length) {
            return new int[0];
         }
         return new int[] {capOn(CAPS[idx]) ? 1 : 0};
      }
      if (state >= ST_TEXTURE) {
         int unit = state - ST_TEXTURE;
         int active = activeUnit();
         if (active == unit) {
            return new int[] {GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D)};
         }
         JNI.callV(GL13C.GL_TEXTURE0 + unit, rActiveTexture);
         int b = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
         JNI.callV(GL13C.GL_TEXTURE0 + active, rActiveTexture);
         return new int[] {b};
      }
      switch (state) {
         case ST_BLEND_FUNC:
            return new int[] {GL11C.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11C.glGetInteger(GL14.GL_BLEND_DST_RGB),
               GL11C.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11C.glGetInteger(GL14.GL_BLEND_DST_ALPHA)};
         case ST_BLEND_EQ:
            return new int[] {GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB), GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA)};
         case ST_DEPTH_FUNC:
            return new int[] {GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC)};
         case ST_DEPTH_MASK:
            return new int[] {GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK) ? 1 : 0};
         case ST_COLOR_MASK: {
            java.nio.ByteBuffer b = org.lwjgl.BufferUtils.createByteBuffer(16);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, b);
            return new int[] {b.get(0), b.get(1), b.get(2), b.get(3)};
         }
         case ST_VIEWPORT:
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, i4);
            return i4.clone();
         case ST_SCISSOR:
            GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, i4);
            return i4.clone();
         case ST_CULL:
            return new int[] {GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE)};
         case ST_FRONT_FACE:
            return new int[] {GL11C.glGetInteger(GL11C.GL_FRONT_FACE)};
         case ST_POLY_MODE: {
            int[] pm = new int[2];
            GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE, pm);
            return new int[] {pm[0]};
         }
         case ST_STENCIL:
            return new int[] {GL11C.glGetInteger(GL11C.GL_STENCIL_FUNC), GL11C.glGetInteger(GL11C.GL_STENCIL_REF),
               GL11C.glGetInteger(GL11C.GL_STENCIL_VALUE_MASK), GL11C.glGetInteger(GL11C.GL_STENCIL_FAIL),
               GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_FAIL), GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_PASS),
               GL11C.glGetInteger(GL11C.GL_STENCIL_WRITEMASK)};
         case ST_ALPHA_FUNC:
            return new int[] {alphaFunc, Float.floatToIntBits(alphaRef)};
         case ST_MATRIX_MODE:
            return new int[] {matrixMode};
         case ST_ACTIVE_TEXTURE:
            return new int[] {activeUnit()};
         default:
            return null;
      }
   }

   private static float[] readFloats(int state) {
      switch (state) {
         case ST_POLY_OFFSET:
            return new float[] {GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_FACTOR), GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_UNITS)};
         case ST_LINE_WIDTH:
            return new float[] {GL11C.glGetFloat(GL11C.GL_LINE_WIDTH)};
         case ST_DEPTH_RANGE: {
            float[] r = new float[2];
            GL11C.glGetFloatv(GL11C.GL_DEPTH_RANGE, r);
            return r;
         }
         case ST_CLEAR_COLOR:
            GL11C.glGetFloatv(GL11C.GL_COLOR_CLEAR_VALUE, f4);
            return f4.clone();
         default:
            return null;
      }
   }

   private static void restore(Frame f) {
      int activeBefore = -1;
      for (int s = 0; s < STATES; s++) {
         if (!f.has[s]) {
            continue;
         }
         int[] v = f.ints[s];
         float[] fv = f.floats[s];
         if (s >= CL_PIXEL) {
            if (v != null) {
               JNI.callV(v[0], v[1], rPixelStorei);
            }
         } else if (s == CL_ARRAY_BUFFER || s == CL_ELEMENT_BUFFER) {
            // after the pointers below (they rebind ARRAY_BUFFER)
         } else if (s >= CL_DIVISOR) {
            JNI.callV(s - CL_DIVISOR, v[0], rVertexAttribDivisor);
         } else if (s >= CL_POINTER) {
            int i = s - CL_POINTER;
            long ptr = ((long) v[5] << 32) | (v[6] & 0xFFFFFFFFL);
            if (v[4] != 0 || ptr == 0L) {
               JNI.callV(GL15C.GL_ARRAY_BUFFER, v[4], rBindBuffer);
               if (v[7] != 0) {
                  GL30C.glVertexAttribIPointer(i, v[0], v[1], v[3], ptr);
               } else {
                  JNI.callPV(i, v[0], v[1], v[2], v[3], ptr, rVertexAttribPointer);
               }
            }
         } else if (s >= CL_ATTRIB) {
            JNI.callV(s - CL_ATTRIB, v[0] != 0 ? rEnableVAA : rDisableVAA);
         } else if (s >= ST_ENABLE) {
            int idx = s - ST_ENABLE;
            if (idx < CAPS.length && v.length > 0) {
               int cap = CAPS[idx];
               boolean on = v[0] != 0;
               if (legacyCap(cap)) {
                  if (cap == 0x0BC0) {
                     if (alphaOn != on) {
                        alphaOn = on;
                        alphaChanged();
                     }
                  } else if (cap == 0x0DE1) {
                     texture2D[activeUnit()] = on;
                  } else if (cap == 0x0B50) {
                     lightingOn = on;
                  } else if (cap == 0x0B60) {
                     fogOn = on;
                  } else if (cap == 0x0B57) {
                     colorMaterialOn = on;
                  } else if (cap == 0x0BA1) {
                     normalizeOn = on;
                  }
               } else {
                  JNI.callV(cap, on ? rEnable : rDisable);
               }
            }
         } else if (s >= ST_TEXTURE) {
            if (activeBefore < 0) {
               activeBefore = activeUnit();
            }
            JNI.callV(GL13C.GL_TEXTURE0 + (s - ST_TEXTURE), rActiveTexture);
            JNI.callV(GL11C.GL_TEXTURE_2D, v[0], rBindTexture);
         } else {
            switch (s) {
               case ST_BLEND_FUNC:
                  JNI.callV(v[0], v[1], v[2], v[3], rBlendFuncSeparate);
                  break;
               case ST_BLEND_EQ:
                  JNI.callV(v[0], v[1], rBlendEquationSeparate);
                  break;
               case ST_DEPTH_FUNC:
                  JNI.callV(v[0], rDepthFunc);
                  break;
               case ST_DEPTH_MASK:
                  JNI.callV(v[0], rDepthMask);
                  break;
               case ST_COLOR_MASK:
                  JNI.callV(v[0] != 0, v[1] != 0, v[2] != 0, v[3] != 0, rColorMask);
                  break;
               case ST_VIEWPORT:
                  JNI.callV(v[0], v[1], v[2], v[3], rViewport);
                  break;
               case ST_SCISSOR:
                  JNI.callV(v[0], v[1], v[2], v[3], rScissor);
                  break;
               case ST_CULL:
                  JNI.callV(v[0], rCullFace);
                  break;
               case ST_FRONT_FACE:
                  JNI.callV(v[0], rFrontFace);
                  break;
               case ST_POLY_MODE:
                  JNI.callV(GL11C.GL_FRONT_AND_BACK, v[0], rPolygonMode);
                  break;
               case ST_STENCIL:
                  JNI.callV(v[0], v[1], v[2], rStencilFunc);
                  JNI.callV(v[3], v[4], v[5], rStencilOp);
                  JNI.callV(v[6], rStencilMask);
                  break;
               case ST_ALPHA_FUNC:
                  alphaFunc(v[0], Float.intBitsToFloat(v[1]));
                  break;
               case ST_MATRIX_MODE:
                  matrixMode = v[0];
                  break;
               case ST_ACTIVE_TEXTURE:
                  activeBefore = v[0];
                  break;
               case ST_POLY_OFFSET:
                  JNI.callV(fv[0], fv[1], rPolygonOffset);
                  break;
               case ST_LINE_WIDTH:
                  JNI.callV(fv[0], rLineWidth);
                  break;
               case ST_DEPTH_RANGE:
                  JNI.callV((double) fv[0], (double) fv[1], rDepthRange);
                  break;
               case ST_CLEAR_COLOR:
                  JNI.callV(fv[0], fv[1], fv[2], fv[3], rClearColor);
                  break;
               default:
                  break;
            }
         }
      }
      if (f.has[CL_ARRAY_BUFFER]) {
         JNI.callV(GL15C.GL_ARRAY_BUFFER, f.ints[CL_ARRAY_BUFFER][0], rBindBuffer);
      } else if (f.client) {
         // pointers rebound ARRAY_BUFFER: put the current binding back
      }
      if (f.has[CL_ELEMENT_BUFFER]) {
         JNI.callV(GL15C.GL_ELEMENT_ARRAY_BUFFER, f.ints[CL_ELEMENT_BUFFER][0], rBindBuffer);
      }
      if (activeBefore >= 0) {
         JNI.callV(GL13C.GL_TEXTURE0 + activeBefore, rActiveTexture);
      }
   }

   // ------------------------------------------------------------------ glClearTexImage (GL 4.4) through a framebuffer

   private static int clearFbo;

   /**
    * glClearTexImage / glClearTexSubImage: the level attached to a scratch draw framebuffer (layered for 3D / array
    * textures, one layer at a time for a z range) and cleared with glClearBuffer*, scissored to the sub-rectangle. w < 0
    * = the whole level. The draw framebuffer, scissor and colour mask are put back.
    */
   static void clearTexture(int tex, int level, int x, int y, int z, int w, int h, int d, int format, int type, long data) {
      if (clearFbo == 0) {
         clearFbo = GL30C.glGenFramebuffers();
      }
      int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
      boolean scissor = JNI.callZ(GL11C.GL_SCISSOR_TEST, isEnabledAddr());
      int[] box = new int[4];
      GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, box);
      java.nio.ByteBuffer mask = org.lwjgl.BufferUtils.createByteBuffer(16);
      GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, mask);
      boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
      GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, clearFbo);
      boolean depth = format == GL11C.GL_DEPTH_COMPONENT || format == GL30C.GL_DEPTH_STENCIL;
      int attach = depth ? (format == GL30C.GL_DEPTH_STENCIL ? GL30C.GL_DEPTH_STENCIL_ATTACHMENT : GL30C.GL_DEPTH_ATTACHMENT) : GL30C.GL_COLOR_ATTACHMENT0;
      JNI.callV(1, 1, 1, 1, rColorMask);
      JNI.callV(1, rDepthMask);
      if (w >= 0) {
         JNI.callV(GL11C.GL_SCISSOR_TEST, rEnable);
         JNI.callV(x, y, w, h, rScissor);
      } else {
         JNI.callV(GL11C.GL_SCISSOR_TEST, rDisable);
      }
      int layers = d < 0 ? 1 : d;
      for (int layer = 0; layer < layers; layer++) {
         if (d < 0) {
            GL32C.glFramebufferTexture(GL30C.GL_DRAW_FRAMEBUFFER, attach, tex, level); // all layers of a layered texture
         } else if (z == 0 && d == 1) {
            GL32C.glFramebufferTexture(GL30C.GL_DRAW_FRAMEBUFFER, attach, tex, level);
         } else {
            GL30C.glFramebufferTextureLayer(GL30C.GL_DRAW_FRAMEBUFFER, attach, tex, level, z + layer);
         }
         if (!depth) {
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
         }
         clearBuffer(depth, format, type, data);
      }
      GL32C.glFramebufferTexture(GL30C.GL_DRAW_FRAMEBUFFER, attach, 0, 0);
      GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
      JNI.callV(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0, rColorMask);
      JNI.callV(depthMask ? 1 : 0, rDepthMask);
      JNI.callV(box[0], box[1], box[2], box[3], rScissor);
      JNI.callV(GL11C.GL_SCISSOR_TEST, scissor ? rEnable : rDisable);
   }

   private static void clearBuffer(boolean depth, int format, int type, long data) {
      int comps = format == GL11C.GL_RED || format == GL30C.GL_RED_INTEGER || format == GL11C.GL_DEPTH_COMPONENT ? 1
         : format == GL30C.GL_RG || format == GL30C.GL_RG_INTEGER ? 2 : format == GL11C.GL_RGB || format == GL30C.GL_RGB_INTEGER ? 3 : 4;
      if (depth) {
         float v = data == 0L ? 0.0F : type == GL11C.GL_FLOAT ? MemoryUtil.memGetFloat(data)
            : type == GL11C.GL_UNSIGNED_INT ? (float) ((MemoryUtil.memGetInt(data) & 0xFFFFFFFFL) / 4294967295.0) : 0.0F;
         GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {v});
         return;
      }
      boolean integer = format == GL30C.GL_RED_INTEGER || format == GL30C.GL_RG_INTEGER || format == GL30C.GL_RGB_INTEGER
         || format == GL30C.GL_RGBA_INTEGER;
      if (integer) {
         int[] v = new int[4];
         for (int i = 0; i < comps && data != 0L; i++) {
            v[i] = type == GL11C.GL_UNSIGNED_BYTE || type == GL11C.GL_BYTE ? MemoryUtil.memGetByte(data + i)
               : type == GL11C.GL_UNSIGNED_SHORT || type == GL11C.GL_SHORT ? MemoryUtil.memGetShort(data + 2L * i) : MemoryUtil.memGetInt(data + 4L * i);
            if (type == GL11C.GL_UNSIGNED_BYTE) {
               v[i] &= 0xFF;
            } else if (type == GL11C.GL_UNSIGNED_SHORT) {
               v[i] &= 0xFFFF;
            }
         }
         if (type == GL11C.GL_UNSIGNED_INT || type == GL11C.GL_UNSIGNED_SHORT || type == GL11C.GL_UNSIGNED_BYTE) {
            GL30C.glClearBufferuiv(GL11C.GL_COLOR, 0, v);
         } else {
            GL30C.glClearBufferiv(GL11C.GL_COLOR, 0, v);
         }
         return;
      }
      float[] v = new float[4];
      if (comps < 4) {
         v[3] = 0.0F;
      }
      for (int i = 0; i < comps && data != 0L; i++) {
         v[i] = type == GL11C.GL_FLOAT ? MemoryUtil.memGetFloat(data + 4L * i)
            : type == GL11C.GL_UNSIGNED_BYTE ? (MemoryUtil.memGetByte(data + i) & 0xFF) / 255.0F
            : type == GL11C.GL_UNSIGNED_SHORT ? (MemoryUtil.memGetShort(data + 2L * i) & 0xFFFF) / 65535.0F
            : type == GL30C.GL_HALF_FLOAT ? halfToFloat(MemoryUtil.memGetShort(data + 2L * i)) : 0.0F;
      }
      GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, v);
   }

   private static float halfToFloat(short h) {
      int s = (h >> 15) & 1, e = (h >> 10) & 0x1F, m = h & 0x3FF;
      float v = e == 0 ? m / 1024.0F * (float) Math.pow(2, -14) : e == 31 ? (m == 0 ? Float.POSITIVE_INFINITY : Float.NaN)
         : (1.0F + m / 1024.0F) * (float) Math.pow(2, e - 15);
      return s == 1 ? -v : v;
   }

   // ------------------------------------------------------------------ helpers for overrides

   private static int quadIbo;
   private static final int QUAD_MAX = 16384;

   /**
    * VBORenderer: draw a GL_QUADS run whose indices are sequential from {@code startVertex} as triangles (the core profile
    * has no GL_QUADS). The caller's element buffer binding is put back.
    */
   public static void drawQuads(int startVertex, int vertexCount, int callerIbo) {
      if (quadIbo == 0) {
         quadIbo = GL15C.glGenBuffers();
         ByteBuffer b = MemoryUtil.memAlloc(QUAD_MAX * 6 * 4);
         for (int q = 0; q < QUAD_MAX; q++) {
            int v = q * 4;
            b.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v).putInt(v + 2).putInt(v + 3);
         }
         b.flip();
         GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, quadIbo);
         GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, b, GL15C.GL_STATIC_DRAW);
         MemoryUtil.memFree(b);
      }
      GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, quadIbo);
      int quads = Math.min(vertexCount / 4, QUAD_MAX);
      if (Config.DEV_CORE_GL_TRACE) {
         noteDraw();
      }
      GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, quads * 6, GL11C.GL_UNSIGNED_INT, 0L, startVertex);
      GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, callerIbo);
   }

   /** glDrawArrays(GL_QUADS, first, count) on either context: triangles from the shared quad index buffer under macGlCore. */
   public static void drawArraysQuads(int first, int count) {
      if (!active) {
         GL11C.glDrawArrays(7, first, count);
         return;
      }
      drawQuads(first, count, GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING));
   }

   // ------------------------------------------------------------------ dev trace: which call raised a GL error

   private static final Map<String, int[]> errorSites = new HashMap<>();

   private static final String[] recent = new String[16];
   private static int recentAt;

   static void chk(String name, int arg) {
      recent[recentAt++ & 15] = name;
      int e = GL11C.glGetError();
      if (e == 0) {
         return;
      }
      String key = name + "(0x" + Integer.toHexString(arg) + ") -> 0x" + Integer.toHexString(e);
      int[] c;
      synchronized (errorSites) {
         c = errorSites.computeIfAbsent(key, k -> new int[1]);
      }
      if (c[0]++ < 2) {
         Log.warn("macGlCore trace: GL error at " + key);
         new Throwable("macGlCore trace: " + key).printStackTrace();
      }
   }

   /** trace: per program, draws with the alpha test on (all / with depth writes on): which shaders need the discard. */
   private static final Map<Integer, long[]> alphaDraws = new HashMap<>();

   private static void noteDraw() {
      if (!alphaOn || alphaFunc == GL11C.GL_ALWAYS) {
         return;
      }
      long[] c = alphaDraws.computeIfAbsent(curProgram, k -> new long[2]);
      c[0]++;
      if (GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK) && JNI.callZ(GL11C.GL_DEPTH_TEST, isEnabledAddr())) {
         c[1]++;
      }
   }

   private static String programName(int id) {
      try {
         zombie.core.opengl.ShaderProgram sp = zombie.core.opengl.ShaderPrograms.getInstance().getProgramByID(id);
         return sp != null ? sp.getName() : "#" + id;
      } catch (Throwable t) {
         return "#" + id;
      }
   }

   private static void traceHooks() {
      long texParameteri = real("glTexParameteri"), texParameterf = real("glTexParameterf"), hint = real("glHint"),
         drawArrays = real("glDrawArrays"), drawElements = real("glDrawElements"), drawRange = real("glDrawRangeElements"),
         getIntegerv = real("glGetIntegerv"), getFloatv = real("glGetFloatv"), getBooleanv = real("glGetBooleanv"),
         texImage2D = real("glTexImage2D"), texSubImage2D = real("glTexSubImage2D"), genMipmap = real("glGenerateMipmap"),
         clear = real("glClear"), drawBuffer = real("glDrawBuffer"), readBuffer = real("glReadBuffer"),
         texLevel = real("glGetTexLevelParameteriv"), readPixels = real("glReadPixels"), bindFb = real("glBindFramebuffer"),
         fbTex = real("glFramebufferTexture2D"), getTexParam = real("glGetTexParameteriv");
      hook("glTexParameteri", (VIII) (t, p, v) -> { JNI.callV(t, p, v, texParameteri); chk("glTexParameteri", p); });
      hook("glTexParameterf", (VIIF) (t, p, v) -> { JNI.callV(t, p, v, texParameterf); chk("glTexParameterf", p); });
      hook("glHint", (VII) (t, m) -> { JNI.callV(t, m, hint); chk("glHint", t); });
      hook("glDrawArrays", (VIII) (m, f, c) -> { noteDraw(); JNI.callV(m, f, c, drawArrays); chk("glDrawArrays", m); });
      hook("glDrawElements", (VIIIP) (m, c, t, p) -> { noteDraw(); JNI.callPV(m, c, t, p, drawElements); chk("glDrawElements", m); });
      hook("glDrawRangeElements", (VIIIIIP) (m, a, b, c, t, p) -> { noteDraw(); JNI.callPV(m, a, b, c, t, p, drawRange); chk("glDrawRangeElements", m); });
      hook("glGetIntegerv", (VIP) (n, p) -> { JNI.callPV(n, p, getIntegerv); chk("glGetIntegerv", n); });
      hook("glGetFloatv", (VIP) (n, p) -> { JNI.callPV(n, p, getFloatv); chk("glGetFloatv", n); });
      hook("glGetBooleanv", (VIP) (n, p) -> { JNI.callPV(n, p, getBooleanv); chk("glGetBooleanv", n); });
      hook("glTexImage2D", (V8IP) (a, b, c, d, e, f, g, h, p) -> { JNI.callPV(a, b, c, d, e, f, g, h, p, texImage2D); chk("glTexImage2D", c); });
      hook("glTexSubImage2D", (V8IP) (a, b, c, d, e, f, g, h, p) -> { JNI.callPV(a, b, c, d, e, f, g, h, p, texSubImage2D); chk("glTexSubImage2D", g); });
      hook("glGenerateMipmap", (VI) t -> { JNI.callV(t, genMipmap); chk("glGenerateMipmap", t); });
      hook("glClear", (VI) m -> { JNI.callV(m, clear); chk("glClear", m); });
      hook("glDrawBuffer", (VI) m -> { JNI.callV(m, drawBuffer); chk("glDrawBuffer", m); });
      hook("glReadBuffer", (VI) m -> { JNI.callV(m, readBuffer); chk("glReadBuffer", m); });
      hook("glGetTexLevelParameteriv", (VIIIP) (t, l, n, p) -> { JNI.callPV(t, l, n, p, texLevel); chk("glGetTexLevelParameteriv", n); });
      hook("glGetTexParameteriv", (VIIP) (t, n, p) -> { JNI.callPV(t, n, p, getTexParam); chk("glGetTexParameteriv", n); });
      hook("glReadPixels", (V6IP) (x, y, w, h, f, t, p) -> { JNI.callPV(x, y, w, h, f, t, p, readPixels); chk("glReadPixels", f); });
      hook("glBindFramebuffer", (VII) (t, f) -> { JNI.callV(t, f, bindFb); chk("glBindFramebuffer", t); });
      hook("glFramebufferTexture2D", (VIIIII) (a, b, c, d, e) -> { JNI.callV(a, b, c, d, e, fbTex); chk("glFramebufferTexture2D", b); });
      long attach = real("glAttachShader"), u1i = real("glUniform1i"), u1f = real("glUniform1f"), u2f = real("glUniform2f"),
         u3f = real("glUniform3f"), u4f = real("glUniform4f"), u4i = real("glUniform4i"), u1fv = real("glUniform1fv"), u4fv = real("glUniform4fv"),
         um4 = real("glUniformMatrix4fv"), drawBuffers = real("glDrawBuffers"), blit = real("glBlitFramebuffer"),
         copySub = real("glCopyTexSubImage2D"), arraysInst = real("glDrawArraysInstanced"), elemInst = real("glDrawElementsInstanced"),
         fbLayer = real("glFramebufferTextureLayer"), fbRb = real("glFramebufferRenderbuffer"), rbStorage = real("glRenderbufferStorage"),
         bindRb = real("glBindRenderbuffer"), bindSampler = real("glBindSampler"), bufBase = real("glBindBufferBase"),
         texStorage2D = real("glTexStorage2D"), texStorage3D = real("glTexStorage3D"), texSub3D = real("glTexSubImage3D"),
         vaip = real("glVertexAttribIPointer"), clearBf = real("glClearBufferfv");
      hook("glAttachShader", (VII) (p, sh) -> {
         int e = GL11C.glGetError();
         if (e != 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 16; i++) {
               String n = recent[(recentAt - i) & 15];
               if (n != null) {
                  sb.append(n).append(' ');
               }
            }
            Log.warn("macGlCore trace: GL error 0x" + Integer.toHexString(e) + " pending before glAttachShader (the game's check fails the program); last checked calls, newest first: " + sb);
            new Throwable("macGlCore trace: pending error before glAttachShader").printStackTrace();
         }
         JNI.callV(p, sh, attach);
         chk("glAttachShader", 0);
      });
      hook("glUniform1i", (VII) (l, v) -> { JNI.callV(l, v, u1i); chk("glUniform1i", l); });
      hook("glUniform1f", (VIF) (l, v) -> { JNI.callV(l, v, u1f); chk("glUniform1f", l); });
      hook("glUniform2f", (VIFF) (l, a, b) -> { JNI.callV(l, a, b, u2f); chk("glUniform2f", l); });
      hook("glUniform3f", (VIFFF) (l, a, b, c) -> { JNI.callV(l, a, b, c, u3f); chk("glUniform3f", l); });
      hook("glUniform4f", (VIFFFF) (l, a, b, c, d) -> { JNI.callV(l, a, b, c, d, u4f); chk("glUniform4f", l); });
      hook("glUniform4i", (VIIIII) (l, a, b, c, d) -> { JNI.callV(l, a, b, c, d, u4i); chk("glUniform4i", l); });
      hook("glUniform1fv", (VIIP) (l, n, p) -> { JNI.callPV(l, n, p, u1fv); chk("glUniform1fv", l); });
      hook("glUniform4fv", (VIIP) (l, n, p) -> { JNI.callPV(l, n, p, u4fv); chk("glUniform4fv", l); });
      hook("glUniformMatrix4fv", (VIIZP) (l, n, t, p) -> { JNI.callPV(l, n, t ? 1 : 0, p, um4); chk("glUniformMatrix4fv", l); });
      hook("glDrawBuffers", (VIP) (n, p) -> { JNI.callPV(n, p, drawBuffers); chk("glDrawBuffers", n); });
      hook("glBlitFramebuffer", (V10I) (a, b, c, d, e, f, g, h, m, fl) -> { JNI.callV(a, b, c, d, e, f, g, h, m, fl, blit); chk("glBlitFramebuffer", m); });
      hook("glCopyTexSubImage2D", (V8I) (a, b, c, d, e, f, g, h) -> { JNI.callV(a, b, c, d, e, f, g, h, copySub); chk("glCopyTexSubImage2D", a); });
      hook("glDrawArraysInstanced", (VIIII) (m, f, c, n) -> { noteDraw(); JNI.callV(m, f, c, n, arraysInst); chk("glDrawArraysInstanced", m); });
      hook("glDrawElementsInstanced", (VIIIPI) (m, c, t, p, n) -> { noteDraw(); JNI.callPV(m, c, t, p, n, elemInst); chk("glDrawElementsInstanced", m); });
      hook("glFramebufferTextureLayer", (VIIIII) (a, b, c, d, e) -> { JNI.callV(a, b, c, d, e, fbLayer); chk("glFramebufferTextureLayer", b); });
      hook("glFramebufferRenderbuffer", (VIIII) (a, b, c, d) -> { JNI.callV(a, b, c, d, fbRb); chk("glFramebufferRenderbuffer", b); });
      hook("glRenderbufferStorage", (VIIII) (a, b, c, d) -> { JNI.callV(a, b, c, d, rbStorage); chk("glRenderbufferStorage", b); });
      hook("glBindRenderbuffer", (VII) (a, b) -> { JNI.callV(a, b, bindRb); chk("glBindRenderbuffer", a); });
      hook("glBindSampler", (VII) (a, b) -> { JNI.callV(a, b, bindSampler); chk("glBindSampler", a); });
      hook("glBindBufferBase", (VIII) (a, b, c) -> { JNI.callV(a, b, c, bufBase); chk("glBindBufferBase", a); });
      hook("glTexStorage2D", (VIIIII) (a, b, c, d, e) -> { JNI.callV(a, b, c, d, e, texStorage2D); chk("glTexStorage2D", c); });
      hook("glTexStorage3D", (V6I) (a, b, c, d, e, f) -> { JNI.callV(a, b, c, d, e, f, texStorage3D); chk("glTexStorage3D", c); });
      hook("glTexSubImage3D", (V10IP) (a, b, c, d, e, f, g, h, i, j, p) -> { JNI.callPV(a, b, c, d, e, f, g, h, i, j, p, texSub3D); chk("glTexSubImage3D", i); });
      hook("glVertexAttribIPointer", (VIIIIP) (i, sz, t, st, p) -> { JNI.callPV(i, sz, t, st, p, vaip); chk("glVertexAttribIPointer", i); });
      hook("glClearBufferfv", (VIIP) (b, d, p) -> { JNI.callPV(b, d, p, clearBf); chk("glClearBufferfv", b); });
   }

   // ------------------------------------------------------------------ per-frame report / trace

   private static long lastReport;
   private static final Map<String, int[]> traceCounts = new HashMap<>();
   static final Set<String> DEPRECATED_TRACE = new HashSet<>();

   /** Render thread, once a frame (Display.update): GL error and shim counters, every 10 s. */
   private static long framesSince;

   public static void frame() {
      if (!active) {
         return;
      }
      framesSince++;
      if (Config.DEV_CORE_GL_TRACE) {
         int e = GL11C.glGetError();
         if (e != 0) {
            errors++;
            if (errors <= 20) {
               Log.warn("macGlCore: GL error 0x" + Integer.toHexString(e) + " this frame");
            }
         }
      }
      long now = System.currentTimeMillis();
      if (now - lastReport > 10000L) {
         lastReport = now;
         StringBuilder sb = new StringBuilder("macGlCore: ").append(linkedPrograms).append(" programs linked, ").append(failedShaders)
            .append(" shaders failed, ").append(pushes).append(" attrib pushes, ").append(immediateDraws).append(" immediate draws")
            .append(String.format(java.util.Locale.ROOT, "; upcalls/frame: enable %.0f, disable %.0f, useProgram %.0f, bindVertexArray %.0f, pushes %.0f (%d frames)",
               calls[0] / (double)Math.max(1, framesSince), calls[1] / (double)Math.max(1, framesSince), calls[2] / (double)Math.max(1, framesSince),
               calls[3] / (double)Math.max(1, framesSince), calls[4] / (double)Math.max(1, framesSince), framesSince));
         java.util.Arrays.fill(calls, 0L);
         framesSince = 0;
         if (Config.DEV_CORE_GL_TRACE) {
            sb.append(", errors ").append(errors);
            sb.append("\nmacGlCore trace: draws with the alpha test on (program: all / depth writes on):");
            for (Map.Entry<Integer, long[]> en : alphaDraws.entrySet()) {
               sb.append(' ').append(programName(en.getKey())).append('=').append(en.getValue()[0]).append('/').append(en.getValue()[1]);
            }
            synchronized (traceCounts) {
               for (Map.Entry<String, int[]> en : traceCounts.entrySet()) {
                  sb.append(", ").append(en.getKey()).append('=').append(en.getValue()[0]);
               }
            }
         }
         Log.info(sb.toString());
      }
   }

   static int errors;

   private static long traceStub(String name) {
      V0 cb = () -> {
         int[] c;
         synchronized (traceCounts) {
            c = traceCounts.computeIfAbsent(name, k -> new int[1]);
         }
         if (c[0]++ == 0) {
            Log.warn("macGlCore trace: unemulated " + name + " called");
            new Throwable("macGlCore trace: " + name).printStackTrace();
         }
      };
      keep.add(cb);
      return cb.address();
   }

   static {
      if (Config.DEV_CORE_GL_TRACE) {
         for (String n : ("glAccum glArrayElement glBitmap glCallLists glClearAccum glClearIndex glClipPlane glColor3b glColor3s glColor3i "
            + "glColor3ub glColor3us glColor3ui glColor3bv glColor3sv glColor3iv glColor3fv glColor3dv glColor3ubv glColor4b glColor4s glColor4i "
            + "glColor4ub glColor4us glColor4ui glColor4bv glColor4sv glColor4iv glColor4fv glColor4dv glColor4ubv glColorPointer glCopyPixels "
            + "glDrawPixels glEdgeFlag glEdgeFlagPointer glEvalCoord1f glEvalMesh1 glEvalMesh2 glFeedbackBuffer glFogi glFogiv glFogf glFogfv "
            + "glGetClipPlane glGetLightiv glGetLightfv glGetMaterialfv glGetTexEnviv glGetTexEnvfv glIndexi glIndexf glIndexPointer "
            + "glInitNames glInterleavedArrays glIsList glLightModeli glLightModelf glLightModelfv glLighti glLightiv glLineStipple "
            + "glListBase glLoadMatrixd glLoadName glMap1f glMap2f glMapGrid1f glMateriali glMaterialf glMaterialiv glMultMatrixd "
            + "glNormal3b glNormal3s glNormal3i glNormal3d glNormal3fv glNormalPointer glPassThrough glPixelMapfv glPixelTransferi "
            + "glPixelTransferf glPixelZoom glPolygonStipple glPopName glPrioritizeTextures glPushName glRasterPos2i glRasterPos2f "
            + "glRasterPos3f glRecti glRectf glRectd glRenderMode glSelectBuffer glTexCoord1f glTexCoord2s glTexCoord2i glTexCoord2fv "
            + "glTexCoord3f glTexCoord4f glTexCoordPointer glTexEnviv glTexEnvfv glTexGeni glTexGenf glTexGenfv glVertex2s glVertex2fv "
            + "glVertex2iv glVertex3s glVertex3i glVertex3fv glVertex4f glVertex4i glVertex4d glVertex4fv glClientActiveTexture "
            + "glMultiTexCoord2f glMultiTexCoord2fv glSecondaryColor3f glFogCoordf glWindowPos2f glWindowPos2i").split(" ")) {
            DEPRECATED_TRACE.add(n);
         }
      }
   }

   // ------------------------------------------------------------------ upcall signatures

   private static final FFIType I = LibFFI.ffi_type_sint32, F = LibFFI.ffi_type_float, D = LibFFI.ffi_type_double, P = LibFFI.ffi_type_pointer,
      Z = LibFFI.ffi_type_uint8, V = LibFFI.ffi_type_void;

   private static long arg(long args, int i) {
      return MemoryUtil.memGetAddress(args + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE);
   }

   private static int ai(long args, int i) {
      return MemoryUtil.memGetInt(arg(args, i));
   }

   private static float af(long args, int i) {
      return MemoryUtil.memGetFloat(arg(args, i));
   }

   private static double ad(long args, int i) {
      return MemoryUtil.memGetDouble(arg(args, i));
   }

   private static long ap(long args, int i) {
      return MemoryUtil.memGetAddress(arg(args, i));
   }

   private static boolean az(long args, int i) {
      return MemoryUtil.memGetByte(arg(args, i)) != 0;
   }

   @FunctionalInterface
   interface V0 extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(); }
      void invoke();
   }

   @FunctionalInterface
   interface VI extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0)); }
      void invoke(int a);
   }

   @FunctionalInterface
   interface VII extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1)); }
      void invoke(int a, int b);
   }

   @FunctionalInterface
   interface VIII extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ai(args, 2)); }
      void invoke(int a, int b, int c);
   }

   @FunctionalInterface
   interface VIIII extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, I));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ai(args, 2), ai(args, 3)); }
      void invoke(int a, int b, int c, int d);
   }

   @FunctionalInterface
   interface VIF extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, F));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), af(args, 1)); }
      void invoke(int a, float b);
   }

   @FunctionalInterface
   interface VIIF extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, F));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), af(args, 2)); }
      void invoke(int a, int b, float c);
   }

   @FunctionalInterface
   interface VIIP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ap(args, 2)); }
      void invoke(int a, int b, long p);
   }

   @FunctionalInterface
   interface VIIPP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, P, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ap(args, 2), ap(args, 3)); }
      void invoke(int a, int b, long p, long q);
   }

   @FunctionalInterface
   interface VIIIP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ai(args, 2), ap(args, 3)); }
      void invoke(int a, int b, int c, long p);
   }

   @FunctionalInterface
   interface VIIIZIP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, Z, I, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ai(args, 2), az(args, 3), ai(args, 4), ap(args, 5)); }
      void invoke(int a, int b, int c, boolean d, int e, long p);
   }

   @FunctionalInterface
   interface VF extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, F));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(af(args, 0)); }
      void invoke(float a);
   }

   @FunctionalInterface
   interface VFF extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, F, F));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(af(args, 0), af(args, 1)); }
      void invoke(float a, float b);
   }

   @FunctionalInterface
   interface VFFF extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, F, F, F));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(af(args, 0), af(args, 1), af(args, 2)); }
      void invoke(float a, float b, float c);
   }

   @FunctionalInterface
   interface VFFFF extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, F, F, F, F));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(af(args, 0), af(args, 1), af(args, 2), af(args, 3)); }
      void invoke(float a, float b, float c, float d);
   }

   @FunctionalInterface
   interface VDD extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, D, D));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ad(args, 0), ad(args, 1)); }
      void invoke(double a, double b);
   }

   @FunctionalInterface
   interface VDDD extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, D, D, D));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ad(args, 0), ad(args, 1), ad(args, 2)); }
      void invoke(double a, double b, double c);
   }

   @FunctionalInterface
   interface VDDDD extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, D, D, D, D));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ad(args, 0), ad(args, 1), ad(args, 2), ad(args, 3)); }
      void invoke(double a, double b, double c, double d);
   }

   @FunctionalInterface
   interface V6D extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, D, D, D, D, D, D));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ad(args, 0), ad(args, 1), ad(args, 2), ad(args, 3), ad(args, 4), ad(args, 5)); }
      void invoke(double a, double b, double c, double d, double e, double f);
   }

   @FunctionalInterface
   interface VP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ap(args, 0)); }
      void invoke(long p);
   }

   @FunctionalInterface
   interface VZ extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, Z));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(az(args, 0)); }
      void invoke(boolean a);
   }

   @FunctionalInterface
   interface VZZZZ extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, Z, Z, Z, Z));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(az(args, 0), az(args, 1), az(args, 2), az(args, 3)); }
      void invoke(boolean a, boolean b, boolean c, boolean d);
   }

   @FunctionalInterface
   interface VIP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ap(args, 1)); }
      void invoke(int a, long p);
   }

   @FunctionalInterface
   interface VIIIIIP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, I, I, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ai(args, 2), ai(args, 3), ai(args, 4), ap(args, 5)); }
      void invoke(int a, int b, int c, int d, int e, long p);
   }

   @FunctionalInterface
   interface V6IP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, I, I, I, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ai(args, 2), ai(args, 3), ai(args, 4), ai(args, 5), ap(args, 6)); }
      void invoke(int a, int b, int c, int d, int e, int f, long p);
   }

   @FunctionalInterface
   interface V8IP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, I, I, I, I, I, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) {
         invoke(ai(args, 0), ai(args, 1), ai(args, 2), ai(args, 3), ai(args, 4), ai(args, 5), ai(args, 6), ai(args, 7), ap(args, 8));
      }
      void invoke(int a, int b, int c, int d, int e, int f, int g, int h, long p);
   }

   @FunctionalInterface
   interface VIIIII extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, I, I));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ai(args, 2), ai(args, 3), ai(args, 4)); }
      void invoke(int a, int b, int c, int d, int e);
   }

   @FunctionalInterface
   interface VIIIIP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, I, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ai(args, 2), ai(args, 3), ap(args, 4)); }
      void invoke(int a, int b, int c, int d, long p);
   }

   @FunctionalInterface
   interface V11 extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, I, I, I, I, I, I, I, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) {
         invoke(ai(args, 0), ai(args, 1), ai(args, 2), ai(args, 3), ai(args, 4), ai(args, 5), ai(args, 6), ai(args, 7), ai(args, 8), ai(args, 9), ap(args, 10));
      }
      void invoke(int tex, int level, int x, int y, int z, int w, int h, int d, int format, int type, long p);
   }

   @FunctionalInterface
   interface VIFF extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, F, F));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), af(args, 1), af(args, 2)); }
      void invoke(int a, float b, float c);
   }

   @FunctionalInterface
   interface VIFFF extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, F, F, F));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), af(args, 1), af(args, 2), af(args, 3)); }
      void invoke(int a, float b, float c, float d);
   }

   @FunctionalInterface
   interface VIFFFF extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, F, F, F, F));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), af(args, 1), af(args, 2), af(args, 3), af(args, 4)); }
      void invoke(int a, float b, float c, float d, float e);
   }

   @FunctionalInterface
   interface VIIZP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, Z, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), az(args, 2), ap(args, 3)); }
      void invoke(int a, int b, boolean c, long p);
   }

   @FunctionalInterface
   interface VIIIPI extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, P, I));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ai(args, 2), ap(args, 3), ai(args, 4)); }
      void invoke(int a, int b, int c, long p, int d);
   }

   @FunctionalInterface
   interface V6I extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, I, I, I));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ai(args, 2), ai(args, 3), ai(args, 4), ai(args, 5)); }
      void invoke(int a, int b, int c, int d, int e, int f);
   }

   @FunctionalInterface
   interface V8I extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, I, I, I, I, I));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { invoke(ai(args, 0), ai(args, 1), ai(args, 2), ai(args, 3), ai(args, 4), ai(args, 5), ai(args, 6), ai(args, 7)); }
      void invoke(int a, int b, int c, int d, int e, int f, int g, int h);
   }

   @FunctionalInterface
   interface V10I extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, I, I, I, I, I, I, I));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) {
         invoke(ai(args, 0), ai(args, 1), ai(args, 2), ai(args, 3), ai(args, 4), ai(args, 5), ai(args, 6), ai(args, 7), ai(args, 8), ai(args, 9));
      }
      void invoke(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j);
   }

   @FunctionalInterface
   interface V10IP extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(V, I, I, I, I, I, I, I, I, I, I, P));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) {
         invoke(ai(args, 0), ai(args, 1), ai(args, 2), ai(args, 3), ai(args, 4), ai(args, 5), ai(args, 6), ai(args, 7), ai(args, 8), ai(args, 9), ap(args, 10));
      }
      void invoke(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j, long p);
   }

   @FunctionalInterface
   interface II extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(I, I));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { MemoryUtil.memPutLong(ret, invoke(ai(args, 0))); }
      int invoke(int a);
   }

   @FunctionalInterface
   interface PI extends CallbackI {
      Callback.Descriptor DESC = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(P, I));
      default Callback.Descriptor getDescriptor() { return DESC; }
      default void callback(long ret, long args) { MemoryUtil.memPutAddress(ret, invoke(ai(args, 0))); }
      long invoke(int a);
   }

   // GL11 constants the core classes do not carry
   private static final class GL11 {
      static final int GL_MODULATE = 0x2100, GL_REPLACE = 0x1E01, GL_ADD = 0x0104, GL_DECAL = 0x2101, GL_PROJECTION = 0x1701,
         GL_TEXTURE = 0x1702;
   }

   private static final class GL14 {
      static final int GL_BLEND_DST_RGB = 0x80C8, GL_BLEND_SRC_RGB = 0x80C9, GL_BLEND_DST_ALPHA = 0x80CA, GL_BLEND_SRC_ALPHA = 0x80CB;
   }
}
