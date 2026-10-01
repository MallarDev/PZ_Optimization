import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntFunction;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.LibFFI;

/**
 * macOS core-profile feasibility probe (2026-10-01): a 4.1 core forward-compatible context, the GL function table
 * rebuilt with a provider that hands Java upcalls (libffi closures) for chosen entry points, then the cost of an
 * upcall and which GLSL versions the driver takes. Run with the game's JRE and jar: -XstartOnFirstThread.
 */
public final class CoreProbe {
   static int beginCalls;
   static int alphaCalls;
   static int enableIntercepted;
   static long realEnable;

   @FunctionalInterface
   interface VoidI extends CallbackI {
      Callback.Descriptor D = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(LibFFI.ffi_type_void, LibFFI.ffi_type_uint32));
      default Callback.Descriptor getDescriptor() { return D; }
      default void callback(long ret, long args) { invoke(MemoryUtil.memGetInt(MemoryUtil.memGetAddress(args))); }
      void invoke(int a);
   }

   @FunctionalInterface
   interface VoidIF extends CallbackI {
      Callback.Descriptor D = new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(LibFFI.ffi_type_void, LibFFI.ffi_type_uint32, LibFFI.ffi_type_float));
      default Callback.Descriptor getDescriptor() { return D; }
      default void callback(long ret, long args) {
         invoke(MemoryUtil.memGetInt(MemoryUtil.memGetAddress(args)), MemoryUtil.memGetFloat(MemoryUtil.memGetAddress(args + 8)));
      }
      void invoke(int a, float b);
   }

   public static void main(String[] argv) throws Throwable {
      if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit");
      GLFW.glfwDefaultWindowHints();
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, 1);
      long w = GLFW.glfwCreateWindow(320, 200, "pzopt core probe", 0L, 0L);
      if (w == 0L) throw new IllegalStateException("window");
      GLFW.glfwMakeContextCurrent(w);
      GLCapabilities stock = GL.createCapabilities();
      System.out.println("GL_VERSION " + GL11C.glGetString(GL11C.GL_VERSION));
      System.out.println("GLSL " + GL11C.glGetString(GL20C.GL_SHADING_LANGUAGE_VERSION));
      System.out.println("RENDERER " + GL11C.glGetString(GL11C.GL_RENDERER));
      int n = GL11C.glGetInteger(GL30C.GL_NUM_EXTENSIONS);
      StringBuilder sb = new StringBuilder();
      Set<String> ext = new HashSet<>();
      for (int i = 0; i < n; i++) { String e = GL30C.glGetStringi(GL11C.GL_EXTENSIONS, i); ext.add(e); sb.append(e).append(' '); }
      System.out.println("EXT(" + n + ") " + sb);
      System.out.println("stock OpenGL41=" + stock.OpenGL41 + " OpenGL11=" + stock.OpenGL11 + " glBegin=" + stock.glBegin + " glEnable=" + stock.glEnable);
      for (String v : new String[]{"10", "11", "12", "13", "14", "15", "20", "21", "30", "31", "32", "33", "40", "41"}) ext.add("OpenGL" + v);

      FunctionProvider base = GL.getFunctionProvider();
      realEnable = base.getFunctionAddress("glEnable");
      long beginCb = ((VoidI) mode -> beginCalls++).address();
      long alphaCb = ((VoidIF) (f, r) -> alphaCalls++).address();
      long enableCb = ((VoidI) cap -> {
         if (cap == 3008) { enableIntercepted++; return; }
         JNI.callV(cap, realEnable);
      }).address();
      FunctionProvider ours = new FunctionProvider() {
         public long getFunctionAddress(ByteBuffer name) { return base.getFunctionAddress(name); }
         public long getFunctionAddress(CharSequence s) {
            switch (s.toString()) {
               case "glBegin": return beginCb;
               case "glAlphaFunc": return alphaCb;
               case "glEnable": return enableCb;
               default: return base.getFunctionAddress(s);
            }
         }
      };
      Constructor<GLCapabilities> c = GLCapabilities.class.getDeclaredConstructor(FunctionProvider.class, Set.class, boolean.class, IntFunction.class);
      c.setAccessible(true);
      IntFunction<PointerBuffer> bf = PointerBuffer::allocateDirect;
      GLCapabilities caps = c.newInstance(ours, ext, false, bf);
      GL.setCapabilities(caps);
      System.out.println("ours OpenGL41=" + caps.OpenGL41 + " OpenGL11=" + caps.OpenGL11 + " OpenGL20=" + caps.OpenGL20 + " glBegin=" + caps.glBegin);
      GL11.glBegin(4);
      GL11.glAlphaFunc(516, 0.5f);
      GL11.glEnable(3008);
      GL11.glEnable(GL11C.GL_BLEND);
      System.out.println("begin=" + beginCalls + " alpha=" + alphaCalls + " enable3008=" + enableIntercepted + " blendOn=" + GL11C.glIsEnabled(GL11C.GL_BLEND) + " err=" + GL11C.glGetError());

      for (int round = 0; round < 3; round++) {
         long t0 = System.nanoTime();
         for (int i = 0; i < 1_000_000; i++) GL11.glEnable(GL11C.GL_BLEND);
         long t1 = System.nanoTime();
         for (int i = 0; i < 1_000_000; i++) JNI.callV(GL11C.GL_BLEND, realEnable);
         long t2 = System.nanoTime();
         for (int i = 0; i < 1_000_000; i++) GL11.glBegin(4);
         long t3 = System.nanoTime();
         System.out.printf("ns/call upcall-enable=%.1f direct-enable=%.1f upcall-noop=%.1f%n", (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6);
      }

      String[][] tests = {
         {"410 core", "#version 410 core\nin vec2 v; out vec4 c; void main(){ c = vec4(v,0,1); }"},
         {"330", "#version 330\nin vec2 v; out vec4 c; void main(){ c = vec4(v,0,1); }"},
         {"330 gl_FragColor", "#version 330\nvoid main(){ gl_FragColor = vec4(1); }"},
         {"330 texture2D", "#version 330\nuniform sampler2D s; out vec4 c; void main(){ c = texture2D(s, vec2(0)); }"},
         {"150", "#version 150\nout vec4 c; void main(){ c = vec4(1); }"},
         {"140", "#version 140\nout vec4 c; void main(){ c = vec4(1); }"},
         {"120", "#version 120\nvoid main(){ gl_FragColor = vec4(1); }"},
         {"400 textureGather", "#version 400\nuniform sampler2D s; out vec4 c; void main(){ c = textureGather(s, vec2(0)); }"},
         {"410 image", "#version 410\n#extension GL_ARB_shader_image_load_store : enable\nlayout(r32ui) uniform uimage2D im; out vec4 c; void main(){ imageAtomicMax(im, ivec2(0), 1u); c = vec4(1); }"},
      };
      for (String[] t : tests) {
         int sh = GL20C.glCreateShader(GL20C.GL_FRAGMENT_SHADER);
         GL20C.glShaderSource(sh, t[1]);
         GL20C.glCompileShader(sh);
         int ok = GL20C.glGetShaderi(sh, GL20C.GL_COMPILE_STATUS);
         String log = GL20C.glGetShaderInfoLog(sh).trim().replace('\n', ' ');
         System.out.println("GLSL " + t[0] + ": " + (ok != 0 ? "OK" : "FAIL") + (log.isEmpty() ? "" : " | " + log));
         GL20C.glDeleteShader(sh);
      }
      int[] v = new int[1];
      GL11C.glGetIntegerv(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS, v); System.out.println("MAX_TEXTURE_IMAGE_UNITS " + v[0]);
      GL11C.glGetIntegerv(GL20C.GL_MAX_VERTEX_ATTRIBS, v); System.out.println("MAX_VERTEX_ATTRIBS " + v[0]);
      GL11C.glGetIntegerv(GL30C.GL_MAX_COLOR_ATTACHMENTS, v); System.out.println("MAX_COLOR_ATTACHMENTS " + v[0]);
      System.out.println("err end " + GL11C.glGetError());
      GLFW.glfwDestroyWindow(w);
      GLFW.glfwTerminate();
      System.out.println("PROBE DONE");
   }
}
