package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Car glass: the glass programs generated from the installed game's vehicle shaders (vertex unit + compact fragment unit)
 * compile and link with the real driver, headless (/tmp/glslcheck, tools/hdr/glslcheck.c). Skipped when the checker or the
 * game shaders are missing.
 */
public class CarGlassShaderTest {
   public static void main(String[] args) throws Exception {
      Path check = Path.of("/tmp/glslcheck");
      Path dir = Path.of(System.getenv().getOrDefault("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid"), "media", "shaders");
      if (!Files.isExecutable(check) || !Files.isDirectory(dir)) {
         System.out.println("CarGlassShaderTest skipped (no /tmp/glslcheck or game shaders)");
         return;
      }
      CarGlass.noCompileCheck = true;
      String[][] programs = {{"vehicle_multiuv_static.vert", "vehicle_multiuv.frag"}, {"vehicle_norandom_multiuv_static.vert", "vehicle_norandom_multiuv.frag"},
         {"vehicle_static.vert", "vehicle.frag"}, {"vehicle.vert", "vehicle.frag"}, {"vehicle_multiuv_noreflect_static.vert", "vehicle_multiuv_noreflect.frag"}};
      for (String[] pr : programs) {
         if (!Files.exists(dir.resolve(pr[0])) || !Files.exists(dir.resolve(pr[1]))) {
            continue;
         }
         String v = CarGlass.patchShader("media/shaders/pzopt_glass_" + pr[0], Files.readString(dir.resolve(pr[0])));
         String f = CarGlass.patchShader("media/shaders/pzopt_glass_" + pr[1], Files.readString(dir.resolve(pr[1])));
         Check.check(v.contains("pzGP ="), pr[0] + ": the vertex unit was patched");
         Check.check(f.contains("pzGlass(") && f.startsWith("#version 120"), pr[1] + ": the fragment unit is the compact glass program");
         Path vp = Files.writeString(Path.of("/tmp/pzglass_" + pr[0]), v), fp = Files.writeString(Path.of("/tmp/pzglass_" + pr[1]), f);
         Process p = new ProcessBuilder(check.toString(), vp.toString(), fp.toString()).redirectErrorStream(true).start();
         String out = new String(p.getInputStream().readAllBytes());
         p.waitFor();
         Check.check(out.contains("link ok"), pr[0] + " + " + pr[1] + " link with the driver:\n" + out);
      }
      System.out.println("CarGlassShaderTest ok");
   }
}
