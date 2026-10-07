package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Entity shadows: every model program the patch touches (character, animal, vehicle, wheel; skinned and static vertex
 * units) compiles, links and validates with the real driver, headless (/tmp/glslcheck, tools/hdr/glslcheck.c), the
 * includes resolved as ShaderUnit does (the .h inline, each .glsl a unit of its own), with and without the HDR glint patch
 * of the vehicle fragments on top. Skipped when the checker or the game shaders are missing.
 */
public class EntityShadowShaderTest {
   static Path dir;

   public static void main(String[] args) throws Exception {
      Path check = Path.of("/tmp/glslcheck");
      dir = Path.of(System.getenv().getOrDefault("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid"), "media", "shaders");
      if (!Files.isExecutable(check) || !Files.isDirectory(dir)) {
         System.out.println("EntityShadowShaderTest skipped (no /tmp/glslcheck or game shaders)");
         return;
      }
      EntityShadow.noCompileCheck = true;
      EntityShadow.testBindless = Boolean.getBoolean("es.bindless");
      EntityShadow.testGl43 = Boolean.getBoolean("es.gl43");
      String[][] programs = {{"basicEffect.vert", "basicEffect.frag"}, {"basicEffect_static.vert", "basicEffect.frag"}, {"animalEffect.vert", "animalEffect.frag"},
         {"vehicle.vert", "vehicle.frag"}, {"vehicle_static.vert", "vehicle.frag"}, {"vehicle_multiuv_static.vert", "vehicle_multiuv.frag"},
         {"vehicle_norandom_multiuv_static.vert", "vehicle_norandom_multiuv.frag"}, {"vehicle_multiuv_noreflect_static.vert", "vehicle_multiuv_noreflect.frag"},
         {"vehicle_norandom_multiuv_noreflect_static.vert", "vehicle_norandom_multiuv_noreflect.frag"}, {"vehicle_noreflect.vert", "vehicle_noreflect.frag"},
         {"vehicle_noreflect_static.vert", "vehicle_noreflect.frag"}, {"vehiclewheel.vert", "vehiclewheel.frag"}, {"vehiclewheel_static.vert", "vehiclewheel.frag"}};
      int n = 0;
      for (String[] pr : programs) {
         if (!Files.exists(dir.resolve(pr[0])) || !Files.exists(dir.resolve(pr[1]))) {
            continue;
         }
         List<String> vUnits = new ArrayList<>(), fUnits = new ArrayList<>();
         UNIT_NAMES.clear();
         String v = EntityShadow.patchShader("media/shaders/" + pr[0], resolve(pr[0], vUnits));
         String f = EntityShadow.patchShader("media/shaders/" + pr[1], resolve(pr[1], fUnits));
         Check.check(v.contains("pzEsW = (pzEsClipToWorld"), pr[0] + ": the vertex unit was patched");
         // the game's ShaderBufferData (every program on GL 4.3) builds a parameter per active uniform and has no entry for
         // the shadow samplers: one made the game fail at start (es-self1, 2026-10-07)
         for (String bad : new String[] {"sampler2DShadow", "samplerCubeShadow", "sampler2DArrayShadow", "sampler1DShadow"}) {
            Check.check(!v.contains(bad) && !f.contains(bad), pr[1] + ": no " + bad + " (ShaderBufferData has no entry for it)");
         }
         Check.check(f.contains("pzEsAmbient(AmbientColour)") && (f.startsWith("#version 330 compatibility") || f.startsWith("#version 420 compatibility") || f.startsWith("#version 430 compatibility")), pr[1] + ": the fragment unit was patched");
         if (pr[1].startsWith("basicEffect") || pr[1].startsWith("animalEffect")) {
            Check.check(f.contains("lighting *= pzEsTorchShade();"), pr[1] + ": the torch shadows on the clamped lighting");
            Check.check(f.contains("pzEsAmbient(AmbientColour) * pzEsAoAt()"), pr[1] + ": the capsule occlusion on the ambient");
         }
         for (int hdr = 0; hdr < 2; hdr++) {
            String ff = f;
            if (hdr == 1) {
               String h = Hdr.patchVehicle(f);
               if (h == null) {
                  continue;
               }
               ff = h;
            }
            List<String> files = new ArrayList<>();
            files.add(check.toString());
            files.add(Files.writeString(Path.of("/tmp/pzes_" + pr[0]), v).toString());
            files.add(Files.writeString(Path.of("/tmp/pzes_" + hdr + "_" + pr[1]), ff).toString());
            for (int i = 0; i < fUnits.size(); i++) {
               files.add(Files.writeString(Path.of("/tmp/pzes_inc" + i + "_" + pr[1]), fUnits.get(i)).toString());
            }
            Process p = new ProcessBuilder(files).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            Check.check(out.contains("link ok") && out.contains("validate ok"), pr[0] + " + " + pr[1] + (hdr == 1 ? " (+ HDR glint)" : "") + " link with the driver:\n" + out);
            n++;
         }
      }
      Check.check(n >= 13, "programs linked: " + n);
      System.out.println("EntityShadowShaderTest ok (" + n + " programs)");
   }

   /** ShaderUnit's preprocessing: #include "x" -> x.h inline (once), x.glsl as a unit of its own (into units, resolved too). */
   static final List<String> UNIT_NAMES = new ArrayList<>();

   static String resolve(String name, List<String> units) throws Exception {
      return resolve(name, units, new ArrayList<>());
   }

   private static String resolve(String name, List<String> units, List<String> seen) throws Exception {
      String code = Files.readString(find(name));
      StringBuilder out = new StringBuilder();
      for (String line : code.split("\n", -1)) {
         String t = line.trim();
         if (t.startsWith("#include ")) {
            String inc = t.substring(9).trim().replace("\"", "").toLowerCase(java.util.Locale.ROOT);
            int slash = name.lastIndexOf('/');
            if (slash >= 0) {
               inc = name.substring(0, slash + 1).toLowerCase(java.util.Locale.ROOT) + inc; // relative to the including file's folder
            }
            if (!seen.contains(inc)) {
               seen.add(inc);
               out.append(resolve(inc + ".h", units, seen)).append('\n');
               Path g = findOrNull(inc + ".glsl");
               if (g != null && !UNIT_NAMES.contains(inc)) {
                  UNIT_NAMES.add(inc); // a program holds each .glsl unit once
                  units.add(resolve(inc + ".glsl", units, new ArrayList<>()));
               }
            }
            continue;
         }
         out.append(line).append('\n');
      }
      return out.toString();
   }

   private static Path find(String name) throws Exception {
      Path p = findOrNull(name);
      if (p == null) {
         throw new IllegalStateException("no shader file " + name);
      }
      return p;
   }

   /** Case-insensitive, as the game's media lookup. */
   private static Path findOrNull(String name) throws Exception {
      Path p = dir.resolve(name);
      if (Files.exists(p)) {
         return p;
      }
      Path parent = dir.resolve(name).getParent();
      String leaf = dir.resolve(name).getFileName().toString();
      if (!Files.isDirectory(parent)) {
         return null;
      }
      try (Stream<Path> s = Files.list(parent)) {
         return s.filter(q -> q.getFileName().toString().equalsIgnoreCase(leaf)).findFirst().orElse(null);
      }
   }
}
