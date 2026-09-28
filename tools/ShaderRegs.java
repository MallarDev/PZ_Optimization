import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;

/**
 * Compiles vertex + fragment shader files in a hidden GLFW window (compile only, no drawing) and prints what the driver's
 * program binary says about the fragment program: on NVIDIA the binary carries the program's assembly, whose TEMP / register
 * lines show how many registers each variant needs (a patched composite's dead code still costs occupancy).
 *
 *    java -cp "<game>/*" tools/ShaderRegs.java <vert> <frag>... [--dump dir]
 */
public class ShaderRegs {
   public static void main(String[] a) throws Exception {
      if (!GLFW.glfwInit()) {
         throw new IllegalStateException("glfwInit");
      }
      GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
      long w = GLFW.glfwCreateWindow(64, 64, "shaderregs", 0, 0);
      GLFW.glfwMakeContextCurrent(w);
      GL.createCapabilities();
      String dump = null;
      String vert = Files.readString(Path.of(a[0]));
      for (int i = 1; i < a.length; i++) {
         if (a[i].equals("--dump")) {
            dump = a[++i];
            continue;
         }
         String frag = Files.readString(Path.of(a[i]));
         int vs = compile(GL20C.GL_VERTEX_SHADER, vert, a[0]);
         int fs = compile(GL20C.GL_FRAGMENT_SHADER, frag, a[i]);
         int p = GL20C.glCreateProgram();
         GL20C.glAttachShader(p, vs);
         GL20C.glAttachShader(p, fs);
         GL41C.glProgramParameteri(p, GL41C.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, 1);
         GL20C.glLinkProgram(p);
         if (GL20C.glGetProgrami(p, GL20C.GL_LINK_STATUS) == 0) {
            System.out.println(a[i] + ": link failed: " + GL20C.glGetProgramInfoLog(p));
            continue;
         }
         int len = GL20C.glGetProgrami(p, GL41C.GL_PROGRAM_BINARY_LENGTH);
         ByteBuffer bin = BufferUtils.createByteBuffer(len);
         IntBuffer fmt = BufferUtils.createIntBuffer(1);
         GL41C.glGetProgramBinary(p, (IntBuffer)null, fmt, bin);
         byte[] b = new byte[len];
         bin.get(b);
         String text = new String(b, StandardCharsets.ISO_8859_1);
         if (dump != null) {
            Files.createDirectories(Path.of(dump));
            Files.write(Path.of(dump, Path.of(a[i]).getFileName() + ".bin"), b);
         }
         // the fragment program's assembly: its header (!!NVfp...) and the TEMP / register statements
         int at = text.indexOf("!!NVfp");
         StringBuilder out = new StringBuilder();
         if (at >= 0) {
            String fp = text.substring(at);
            for (String line : fp.split("\n")) {
               if (line.startsWith("TEMP") || line.contains("register") || line.contains("# ") && line.toLowerCase().contains("reg")) {
                  out.append("   ").append(line.length() > 160 ? line.substring(0, 160) + "..." : line).append('\n');
               }
            }
            int temps = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bR(\\d+)\\b").matcher(fp.length() > 400000 ? fp.substring(0, 400000) : fp);
            while (m.find()) {
               temps = Math.max(temps, Integer.parseInt(m.group(1)) + 1);
            }
            out.append("   highest R register named: ").append(temps).append('\n');
         } else {
            out.append("   (no NV assembly in the binary; ").append(len).append(" bytes, format ").append(fmt.get(0)).append(")\n");
         }
         System.out.println(a[i] + ":");
         System.out.print(out);
      }
      GLFW.glfwDestroyWindow(w);
      GLFW.glfwTerminate();
   }

   private static int compile(int type, String src, String name) {
      int s = GL20C.glCreateShader(type);
      GL20C.glShaderSource(s, src);
      GL20C.glCompileShader(s);
      if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0) {
         throw new IllegalStateException(name + ": " + GL20C.glGetShaderInfoLog(s));
      }
      return s;
   }
}
