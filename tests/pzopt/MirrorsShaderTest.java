package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mirrors (windows, mirrors and the reflective props): the static march (image stores and the atlas-framebuffer variant)
 * and the composite compile and link with the real driver, headless (/tmp/glslcheck, tools/hdr/glslcheck.c), on the GL 4.3
 * and the GL 4.1 core paths, and again with Mesa (llvmpipe through glvnd's Mesa EGL vendor) when it is installed: Mesa
 * refuses what NVIDIA lets through (a declaration before an #extension line made the flip switch mirrors off, prop-v12).
 * Skipped without the checker.
 */
public class MirrorsShaderTest {
   public static void main(String[] args) throws Exception {
      Path check = Path.of("/tmp/glslcheck");
      if (!Files.isExecutable(check)) {
         System.out.println("MirrorsShaderTest skipped (no /tmp/glslcheck)");
         return;
      }
      int n = 0;
      for (boolean full : new boolean[] {true, false}) {
         String[][] programs = {
            {"static-image", Mirrors.vert(full), Mirrors.frag(Mirrors.K_STATIC_IMAGE, full)},
            {"static-fbo", Mirrors.vertTile(full), Mirrors.frag(Mirrors.K_STATIC_FBO, full)},
            {"late", Mirrors.vert(full), Mirrors.frag(Mirrors.K_LATE, full)},
            {"late-prop", Mirrors.vert(full), Mirrors.frag(Mirrors.K_LATE_PROP, full)}};
         for (String[] p : programs) {
            if (!full && p[0].equals("static-image")) {
               continue; // (GL 4.1 has no image stores: that path never builds it)
            }
            String tag = p[0] + (full ? "-430" : "-410");
            Path vp = Files.writeString(Path.of("/tmp/pzmirrors_" + tag + ".vert"), p[1]), fp = Files.writeString(Path.of("/tmp/pzmirrors_" + tag + ".frag"), p[2]);
            Process pr = new ProcessBuilder(check.toString(), vp.toString(), fp.toString()).redirectErrorStream(true).start();
            String out = new String(pr.getInputStream().readAllBytes());
            pr.waitFor();
            Check.check(out.contains("link ok"), tag + " links with the driver:\n" + out);
            n++;
            Path mesa = Path.of("/usr/share/glvnd/egl_vendor.d/50_mesa.json");
            if (Files.exists(mesa)) {
               ProcessBuilder pb = new ProcessBuilder(check.toString(), vp.toString(), fp.toString()).redirectErrorStream(true);
               pb.environment().put("__EGL_VENDOR_LIBRARY_FILENAMES", mesa.toString());
               pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
               Process pm = pb.start();
               String om = new String(pm.getInputStream().readAllBytes());
               pm.waitFor();
               Check.check(om.contains("link ok"), tag + " links with Mesa (llvmpipe):\n" + om);
               n++;
            }
         }
      }
      System.out.println("MirrorsShaderTest ok (" + n + " programs)");
   }
}
