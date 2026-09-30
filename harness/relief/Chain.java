// Runs the ShaderUnit patch chain's chunk-composite steps offline in a hidden GLFW context (pzopt.Relief rig):
//   javac -cp "build/classes:<game>/projectzomboid.jar:<game>/*" -d /tmp/px harness/relief/Chain.java
//   java -Dpzopt.relief=true -Dpzopt.sunShadows=true -Dpzopt.cloudShadows=true -Dpzopt.reliefSunMode=composite -cp "/tmp/px:<same>" Chain <outdir>
//   then /tmp/glslcheck <vert> <outdir>/chunkShader.frag (tools/hdr/glslcheck.c)
import java.nio.file.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
/** Runs the ShaderUnit patch chain's chunk-composite steps (PixelLight, CloudShadow, Relief) in a hidden GL context; writes out/<n>.frag. */
public class Chain {
   public static void main(String[] a) throws Exception {
      GLFW.glfwInit();
      GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
      long w = GLFW.glfwCreateWindow(64, 64, "chain", 0, 0);
      GLFW.glfwMakeContextCurrent(w);
      GL.createCapabilities();
      String S = "/games/steamapps/common/ProjectZomboid/projectzomboid/media/shaders/";
      Files.createDirectories(Path.of(a[0]));
      for (String n : new String[] {"chunkShader.frag", "pzopt_chunkBase.frag"}) {
         String fn = "media/shaders/" + n;
         String src = n.startsWith("pzopt") ? "" : Files.readString(Path.of(S + n));
         String c = pzopt.PixelLight.patchShader(fn, src);
         c = pzopt.CloudShadow.patchShader(fn, c);
         c = pzopt.Relief.patchComposite(fn, c);
         Files.writeString(Path.of(a[0], n), c);
         System.out.println(n + ": " + c.length() + " chars");
      }
   }
}
