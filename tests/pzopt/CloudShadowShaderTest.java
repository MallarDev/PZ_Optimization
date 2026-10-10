package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cloud shadows (cloudHandleInts): the composite's bindless path declares no sampler uniform of ours in the game's chunk
 * program, only int halves the samplers are built from (with a bindless sampler uniform for the sun-step fade's old term,
 * AMD's Windows driver drew the screen black for ~0.4 s at every step, 2026-10-08). With /tmp/glslcheck
 * (tools/hdr/glslcheck.c) and the game shaders, the stock chunk composite with the patch links and validates with the
 * real driver.
 */
public class CloudShadowShaderTest {
   public static void main(String[] args) throws Exception {
      String g = CloudShadow.COMPOSITE_GLSL;
      int a = g.indexOf("#if defined(PZC_BINDLESS) && defined(PZC_HANDLE_INTS)");
      Check.check(a >= 0, "the composite has a PZC_HANDLE_INTS branch");
      int n = 0;
      for (int at = a; at >= 0; at = g.indexOf("#if defined(PZC_BINDLESS) && defined(PZC_HANDLE_INTS)", at + 1)) {
         String branch = g.substring(at, g.indexOf("#elif", at));
         Check.check(!Pattern.compile("uniform\\s+\\w*sampler").matcher(branch).find(), "no sampler uniform in the ints branch:\n" + branch);
         Matcher u = Pattern.compile("uniform\\s+(\\w+)\\s").matcher(branch);
         while (u.find()) {
            Check.check(u.group(1).equals("int"), "the ints branch declares only int uniforms (ShaderBufferData knows no unsigned types): " + u.group(1));
         }
         n++;
      }
      Check.check(n == 2, "both kept-term samplers (term, step old) have the ints branch: " + n);
      for (String name : new String[] {"pzCloudTermLo", "pzCloudTermHi", "pzStepOldLo", "pzStepOldHi"}) {
         Check.check(java.util.Arrays.asList(CloudShadow.UNIFORMS).contains(name), "location looked up: " + name);
      }
      Path check = Path.of("/tmp/glslcheck");
      Path dir = Path.of(System.getenv().getOrDefault("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid"), "media", "shaders");
      if (!Files.isExecutable(check) || !Files.exists(dir.resolve("chunkShader.frag"))) {
         System.out.println("CloudShadowShaderTest ok (text only: no /tmp/glslcheck or game shaders)");
         return;
      }
      String code = Files.readString(dir.resolve("chunkShader.frag"));
      int nl = code.indexOf('\n');
      String out = code.contains("out vec4 fragColor;") ? "fragColor" : "gl_FragColor";
      // as CloudShadow.patchComposite builds it for the stock GLSL 1.20 composite on the bindless path
      String c = "#version 420 compatibility\n#extension GL_ARB_bindless_texture : require\n#define PZC_BINDLESS\n#define PZC_HANDLE_INTS"
         + "\n#define PZC_OUT " + out + "\n#define PZC_TEX texture2D\n#define PZC_TERM(uv) textureLod(pzCloudTerm, uv, float(PZC_TERM_LOD))\n#define PZC_TERM_LOD 1"
         + code.substring(nl).replace("void main()", "void pzCloudInner()") + "\n" + g + "\n";
      Path f = Files.writeString(Path.of("/tmp/pzcs_chunkShader.frag"), c);
      Process p = new ProcessBuilder(check.toString(), dir.resolve("chunkShader.vert").toString(), f.toString()).redirectErrorStream(true).start();
      String log = new String(p.getInputStream().readAllBytes());
      p.waitFor();
      Check.check(log.contains("link ok") && log.contains("validate ok"), "the patched chunk composite links with the driver:\n" + log);
      System.out.println("CloudShadowShaderTest ok (linked)");
   }
}
