package pzopt;

import java.util.Map;
import java.util.Set;

/**
 * pzopt.CoreGlsl, the GLSL translation of macGlCore (macOS OpenGL 4.1 core): legacy sources become 330 core, the
 * fixed-function inputs / outputs / matrices become pz_ names, 4.2+ sources become 410 with their binding qualifiers
 * moved out, and fragment shaders with one colour output get the alpha test. The Mac compile rig
 * (tools/mac/coreprobe/ShaderRig.java) checks the results against Apple's compiler.
 */
public class CoreGlslTest {
   public static void main(String[] args) {
      Set<String> exts = Set.of("GL_ARB_texture_gather");

      // GLSL 1.20 vertex stage: attribute / varying, gl_Vertex, gl_MultiTexCoord0, gl_ModelViewProjectionMatrix, gl_TexCoord
      String v = "#version 120\nattribute vec2 pos;\nvarying vec2 uv;\nvoid main() {\n  gl_TexCoord[0] = gl_MultiTexCoord0;\n"
         + "  uv = pos;\n  gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n}\n";
      String tv = CoreGlsl.translate(v, true, exts, true).source;
      Check.check(tv.startsWith("#version 330 core\n"), "1.20 becomes 330 core");
      Check.check(tv.contains("in vec2 pos;") && tv.contains("out vec2 uv;"), "attribute -> in, varying -> out in the vertex stage");
      Check.check(tv.contains("in vec4 pz_Vertex;") && tv.contains("in vec4 pz_MultiTexCoord0;"), "fixed-function inputs declared");
      Check.check(tv.contains("uniform mat4 pz_ModelViewProjectionMatrix;") && !tv.contains("gl_ModelViewProjectionMatrix"), "the MVP is a uniform");
      Check.check(tv.contains("out vec4 pz_TexCoord[4];") && tv.contains("pz_TexCoord[0] = pz_MultiTexCoord0;"), "gl_TexCoord is an output array");
      Check.check(!tv.contains("pz_AlphaTest"), "no alpha test in a vertex stage");

      // GLSL 1.20 fragment stage: varying, texture2D (a sampler named "texture"), gl_FragColor, the alpha test
      String f = "#version 120\nuniform sampler2D texture;\nvarying vec2 uv;\nvoid main() {\n  gl_FragColor = texture2D(texture, uv) * gl_Color;\n}\n";
      String tf = CoreGlsl.translate(f, false, exts, true).source;
      Check.check(tf.contains("in vec2 uv;"), "varying -> in in the fragment stage");
      Check.check(tf.contains("out vec4 pz_FragColor;") && !tf.contains("gl_FragColor"), "gl_FragColor is a declared output");
      Check.check(tf.contains("in vec4 pz_FrontColor;") && !tf.contains("gl_Color"), "gl_Color reads the vertex stage's front colour");
      int wrapper = tf.indexOf("pz_texture2D_");
      Check.check(wrapper > 0 && tf.indexOf("return texture(s, c)") > 0 && tf.indexOf("return texture(s, c)") < tf.indexOf("uniform sampler2D texture;"),
         "texture2D wraps texture() ahead of the user's own 'texture' sampler");
      Check.check(tf.contains("void pz_main()") && tf.contains("uniform vec2 pz_AlphaTest;") && tf.contains("pz_FragColor.a"), "alpha test around main");
      String tf2 = CoreGlsl.translate(f, false, exts, true).source;
      Check.check(!tf2.substring(tf2.indexOf("pz_texture2D_"), tf2.indexOf("pz_texture2D_") + 20).equals(tf.substring(wrapper, wrapper + 20)),
         "each source gets its own wrapper names (several units link into one program)");

      // a clean 330 shader: only the alpha test; 330 vertex stage unchanged
      String c = "#version 330\nin vec2 uv;\nuniform sampler2D tex;\nout vec4 colour;\nvoid main() { colour = texture(tex, uv); }\n";
      CoreGlsl.Result rc = CoreGlsl.translate(c, false, exts, true);
      Check.check(rc.source.startsWith("#version 330 core") && rc.source.contains("colour.a"), "330: alpha test on its single output");
      String cv = "#version 330\nlayout (location = 0) in vec3 p;\nvoid main() { gl_Position = vec4(p, 1.0); }\n";
      Check.check(!CoreGlsl.translate(cv, true, exts, true).changed, "a core-clean 330 vertex stage stays as it is");
      Check.check(CoreGlsl.translate(c, false, exts, false).source.equals(c) || !CoreGlsl.translate(c, false, exts, false).source.contains("pz_AlphaTest"),
         "no alpha test when it is off");

      // 4.20 with binding qualifiers and an extension the context lacks
      String g = "#version 420\n#extension GL_ARB_shading_language_420pack : enable\n#extension GL_ARB_texture_gather : enable\n"
         + "layout(binding = 5) uniform sampler2DArray lattice;\nlayout(std140, binding = 2) uniform Lights { vec4 l[4]; };\n"
         + "out vec4 fragColor;\nvoid main() { fragColor = texture(lattice, vec3(0.0)) + l[0]; }\n";
      CoreGlsl.Result rg = CoreGlsl.translate(g, false, exts, true);
      Check.check(rg.source.startsWith("#version 410 core\n#extension GL_ARB_texture_gather : enable\n"), "420 -> 410, supported extensions kept first");
      Check.check(!rg.source.contains("\n#extension GL_ARB_shading_language_420pack"), "unsupported extension dropped");
      Check.check(rg.source.contains("uniform sampler2DArray lattice;") && !rg.source.contains("binding = 5"), "sampler binding moved out");
      Check.check(rg.source.contains("layout(std140) uniform Lights {"), "block binding moved out, other qualifiers kept");
      Map<String, Integer> b = rg.bindings;
      Check.check(Integer.valueOf(5).equals(b.get("lattice")) && Integer.valueOf(2).equals(b.get("block:Lights")), "bindings reported for after the link");

      // compatibility profile with gl_FragData and ftransform
      String cf = "#version 150 compatibility\nvoid main() { gl_FragData[0] = vec4(1.0); gl_FragData[1] = vec4(0.0); }\n";
      String tcf = CoreGlsl.translate(cf, false, exts, true).source;
      Check.check(tcf.startsWith("#version 330 core") && tcf.contains("out vec4 pz_FragData[8];") && tcf.contains("pz_FragData[0].a"), "gl_FragData array, alpha on target 0");
      String ft = "#version 110\nvoid main() { gl_Position = ftransform(); }\n";
      String tft = CoreGlsl.translate(ft, true, exts, true).source;
      Check.check(tft.contains("(pz_ModelViewProjectionMatrix * pz_Vertex)") && tft.contains("in vec4 pz_Vertex;"), "ftransform() expanded");
      System.out.println("CoreGlslTest ok");
   }
}
