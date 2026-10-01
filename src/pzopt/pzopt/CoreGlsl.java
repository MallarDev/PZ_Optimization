package pzopt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GLSL for the macOS OpenGL 4.1 core profile ({@code macGlCore}, pzopt.CoreGl). Every shader source the game, pzopt or a mod
 * hands to glShaderSource passes through {@link #translate}: GLSL 1.10 / 1.20 / "compatibility" sources become 330 core
 * (attribute / varying, texture2D and friends, gl_FragColor / gl_FragData, the fixed-function inputs and matrices as pz_
 * attributes and uniforms fed by CoreGl), 4.2+ sources become 410 (binding qualifiers stripped and applied after the link),
 * and every fragment shader with one colour output gets the alpha test the core profile dropped (a uniform CoreGl keeps
 * equal to glAlphaFunc / GL_ALPHA_TEST).
 *
 * Pure string work, no GL: tests/pzopt/CoreGlslTest and the Mac compile rig (tools/mac/coreprobe) run it offline.
 */
public final class CoreGlsl {
   private CoreGlsl() {
   }

   /** Attribute locations of the fixed-function inputs (NVIDIA's aliasing table, so generic-attribute feeding agrees). */
   public static final String[][] ATTRIBS = {
      {"pz_Vertex", "0"}, {"pz_Normal", "2"}, {"pz_Color", "3"}, {"pz_SecondaryColor", "4"}, {"pz_FogCoord", "5"},
      {"pz_MultiTexCoord0", "8"}, {"pz_MultiTexCoord1", "9"}, {"pz_MultiTexCoord2", "10"}, {"pz_MultiTexCoord3", "11"},
      {"pz_MultiTexCoord4", "12"}, {"pz_MultiTexCoord5", "13"}, {"pz_MultiTexCoord6", "14"}, {"pz_MultiTexCoord7", "15"},
   };
   public static final int ATTR_VERTEX = 0, ATTR_NORMAL = 2, ATTR_COLOR = 3, ATTR_TEX0 = 8;
   /** The alpha-test uniform: x = 0 off, 1..8 = GL_NEVER..GL_ALWAYS; y = the reference. */
   public static final String ALPHA_UNIFORM = "pz_AlphaTest";
   static final int TEXCOORDS = 4;
   private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

   /** Result of a translation: the source and the layout(binding = N) qualifiers removed from it (name -> unit). */
   public static final class Result {
      public final String source;
      public final Map<String, Integer> bindings;
      public final boolean changed;

      Result(String source, Map<String, Integer> bindings, boolean changed) {
         this.source = source;
         this.bindings = bindings;
         this.changed = changed;
      }
   }

   private static final Pattern VERSION = Pattern.compile("^[ \\t]*#[ \\t]*version[ \\t]+(\\d+)(?:[ \\t]+(\\w+))?[^\\n]*$", Pattern.MULTILINE);
   private static final Pattern EXTENSION = Pattern.compile("^[ \\t]*#[ \\t]*extension[ \\t]+(\\w+)[ \\t]*:[ \\t]*(\\w+)[^\\n]*$", Pattern.MULTILINE);
   private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
   private static final Pattern FRAG_OUT = Pattern.compile(
      "(?:layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*)?\\bout\\s+(?:(?:highp|mediump|lowp)\\s+)?vec4\\s+(\\w+)\\s*;");
   private static final Pattern BINDING = Pattern.compile("layout\\s*\\(([^)]*)\\)\\s*uniform\\s+(\\w+)\\s+(\\w+)");
   private static final Pattern BINDING_IN = Pattern.compile("(?:^|,)\\s*binding\\s*=\\s*(\\d+)\\s*(?=,|$)");
   private static final Pattern BLOCK_BINDING = Pattern.compile("layout\\s*\\(([^)]*)\\)\\s*uniform\\s+(\\w+)\\s*\\{");
   private static final Pattern TEX_INDEX = Pattern.compile("gl_TexCoord\\s*\\[\\s*(\\d+)\\s*\\]");

   /** Texture functions of GLSL 1.x and the extensions, with the overloads the wrappers need. */
   private static final String[][] TEX_FUNCS = {
      {"texture2D", "vec4 pz_texture2D(sampler2D s, vec2 c) { return texture(s, c); }\nvec4 pz_texture2D(sampler2D s, vec2 c, float b) { return texture(s, c, b); }\n"
         + "uvec4 pz_texture2D(usampler2D s, vec2 c) { return texture(s, c); }\nuvec4 pz_texture2D(usampler2D s, vec2 c, float b) { return texture(s, c, b); }\n"
         + "ivec4 pz_texture2D(isampler2D s, vec2 c) { return texture(s, c); }\nivec4 pz_texture2D(isampler2D s, vec2 c, float b) { return texture(s, c, b); }"},
      {"texture2DLod", "vec4 pz_texture2DLod(sampler2D s, vec2 c, float l) { return textureLod(s, c, l); }\n"
         + "uvec4 pz_texture2DLod(usampler2D s, vec2 c, float l) { return textureLod(s, c, l); }\nivec4 pz_texture2DLod(isampler2D s, vec2 c, float l) { return textureLod(s, c, l); }"},
      {"texture2DLodEXT", "vec4 pz_texture2DLodEXT(sampler2D s, vec2 c, float l) { return textureLod(s, c, l); }"},
      {"texture2DLodARB", "vec4 pz_texture2DLodARB(sampler2D s, vec2 c, float l) { return textureLod(s, c, l); }"},
      {"texture2DGrad", "vec4 pz_texture2DGrad(sampler2D s, vec2 c, vec2 x, vec2 y) { return textureGrad(s, c, x, y); }"},
      {"texture2DGradARB", "vec4 pz_texture2DGradARB(sampler2D s, vec2 c, vec2 x, vec2 y) { return textureGrad(s, c, x, y); }"},
      {"texture2DGradEXT", "vec4 pz_texture2DGradEXT(sampler2D s, vec2 c, vec2 x, vec2 y) { return textureGrad(s, c, x, y); }"},
      {"texture2DProj", "vec4 pz_texture2DProj(sampler2D s, vec3 c) { return textureProj(s, c); }\nvec4 pz_texture2DProj(sampler2D s, vec4 c) { return textureProj(s, c); }"},
      {"texture2DProjLod", "vec4 pz_texture2DProjLod(sampler2D s, vec3 c, float l) { return textureProjLod(s, c, l); }\nvec4 pz_texture2DProjLod(sampler2D s, vec4 c, float l) { return textureProjLod(s, c, l); }"},
      {"texture1D", "vec4 pz_texture1D(sampler1D s, float c) { return texture(s, c); }"},
      {"texture3D", "vec4 pz_texture3D(sampler3D s, vec3 c) { return texture(s, c); }\nvec4 pz_texture3D(sampler3D s, vec3 c, float b) { return texture(s, c, b); }"},
      {"texture3DLod", "vec4 pz_texture3DLod(sampler3D s, vec3 c, float l) { return textureLod(s, c, l); }"},
      {"textureCube", "vec4 pz_textureCube(samplerCube s, vec3 c) { return texture(s, c); }\nvec4 pz_textureCube(samplerCube s, vec3 c, float b) { return texture(s, c, b); }"},
      {"textureCubeLod", "vec4 pz_textureCubeLod(samplerCube s, vec3 c, float l) { return textureLod(s, c, l); }"},
      {"shadow2D", "vec4 pz_shadow2D(sampler2DShadow s, vec3 c) { return vec4(texture(s, c)); }"},
      {"shadow2DProj", "vec4 pz_shadow2DProj(sampler2DShadow s, vec4 c) { return vec4(textureProj(s, c)); }"},
      {"texture2DRect", "vec4 pz_texture2DRect(sampler2DRect s, vec2 c) { return texture(s, c); }"},
      {"texture2DArray", "vec4 pz_texture2DArray(sampler2DArray s, vec3 c) { return texture(s, c); }"},
      {"texture2DArrayLod", "vec4 pz_texture2DArrayLod(sampler2DArray s, vec3 c, float l) { return textureLod(s, c, l); }"},
      {"texelFetch2D", "vec4 pz_texelFetch2D(sampler2D s, ivec2 c, int l) { return texelFetch(s, c, l); }"},
      {"textureSize2D", "ivec2 pz_textureSize2D(sampler2D s, int l) { return textureSize(s, l); }"},
   };

   /** Fixed-function uniforms: GLSL builtin -> declaration (CoreGl feeds them from its matrix stacks). */
   private static final String[][] MATRICES = {
      {"gl_ModelViewProjectionMatrixInverse", "uniform mat4 pz_ModelViewProjectionMatrixInverse;"},
      {"gl_ModelViewProjectionMatrix", "uniform mat4 pz_ModelViewProjectionMatrix;"},
      {"gl_ModelViewMatrixInverse", "uniform mat4 pz_ModelViewMatrixInverse;"},
      {"gl_ModelViewMatrix", "uniform mat4 pz_ModelViewMatrix;"},
      {"gl_ProjectionMatrixInverse", "uniform mat4 pz_ProjectionMatrixInverse;"},
      {"gl_ProjectionMatrix", "uniform mat4 pz_ProjectionMatrix;"},
      {"gl_NormalMatrix", "uniform mat3 pz_NormalMatrix;"},
      {"gl_TextureMatrix", "uniform mat4 pz_TextureMatrix[2];"},
   };

   private static final String[] LEGACY_TOKENS = {
      "attribute", "varying", "gl_FragColor", "gl_FragData", "gl_Vertex", "gl_Color", "gl_SecondaryColor", "gl_Normal",
      "gl_MultiTexCoord", "gl_TexCoord", "gl_FrontColor", "gl_BackColor", "gl_FrontSecondaryColor", "gl_FogCoord", "gl_FogFragCoord",
      "gl_ModelView", "gl_Projection", "gl_NormalMatrix", "gl_TextureMatrix", "ftransform", "gl_ClipVertex",
      "texture2D", "texture1D", "texture3D", "textureCube", "shadow2D", "texelFetch2D", "textureSize2D",
   };

   /**
    * Translate one shader stage.
    *
    * @param vertex  vertex stage (else fragment; geometry is passed through)
    * @param exts    extensions the context reports (others are dropped from the source)
    * @param alpha   inject the alpha test into a fragment stage
    */
   public static Result translate(String src, boolean vertex, Set<String> exts, boolean alpha) {
      String s = src.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
      Matcher vm = VERSION.matcher(s);
      int version = 110;
      String profile = "";
      String body = s;
      if (vm.find()) {
         version = Integer.parseInt(vm.group(1));
         profile = vm.group(2) == null ? "" : vm.group(2);
         body = s.substring(0, vm.start()) + s.substring(vm.end());
      }
      boolean legacy = version < 140 || "compatibility".equals(profile);
      if (!legacy) {
         for (String t : LEGACY_TOKENS) {
            if (containsWord(body, t)) {
               legacy = true;
               break;
            }
         }
      }
      int target = version > 410 ? 410 : legacy ? Math.max(330, version) : version;
      if (target > 410) {
         target = 410;
      }
      if (target == 400 || target == 410 || target >= 330) {
         // fine as is
      } else if (target < 330 && legacy) {
         target = 330;
      }
      boolean changed = target != version || legacy || !profile.isEmpty() && !"core".equals(profile);

      // #extension lines go right after #version (Apple's compiler wants them before any other token); unknown ones are dropped
      StringBuilder extLines = new StringBuilder();
      Matcher em = EXTENSION.matcher(body);
      StringBuilder rest = new StringBuilder();
      int last = 0;
      while (em.find()) {
         rest.append(body, last, em.start());
         String name = em.group(1);
         if (exts != null && exts.contains(name) || "all".equals(name)) {
            extLines.append(em.group().trim()).append('\n');
         } else {
            rest.append("// pzopt core: dropped ").append(em.group().trim());
            changed = true;
         }
         last = em.end();
      }
      rest.append(body.substring(last));
      body = rest.toString();

      // layout(binding = N) (GLSL 4.20 / 420pack) -> applied by CoreGl after the link
      Map<String, Integer> bindings = new LinkedHashMap<>();
      if (target <= 410) {
         body = stripBindings(body, bindings);
         if (!bindings.isEmpty()) {
            changed = true;
         }
      }

      StringBuilder decl = new StringBuilder();
      if (legacy) {
         if (vertex) {
            body = replaceWord(body, "attribute", "in");
            body = replaceWord(body, "varying", "out");
         } else {
            body = replaceWord(body, "varying", "in");
         }
         // wrappers get a per-source suffix: a program links several units of one stage, each with its own copy
         String sfx = "_" + SEQ.incrementAndGet();
         for (String[] t : TEX_FUNCS) {
            if (containsWord(body, t[0])) {
               decl.append(t[1].replace("pz_" + t[0] + "(", "pz_" + t[0] + sfx + "(")).append('\n');
               body = replaceWord(body, t[0], "pz_" + t[0] + sfx);
            }
         }
         for (String[] m : MATRICES) {
            if (body.contains(m[0])) {
               decl.append(m[1]).append('\n');
               body = replaceWord(body, m[0], "pz" + m[0].substring(2));
            }
         }
         body = body.replaceAll("\\bgl_ClipVertex\\s*=[^;]*;", "/* gl_ClipVertex */");
         if (vertex) {
            if (body.contains("ftransform")) {
               body = body.replaceAll("\\bftransform\\s*\\(\\s*\\)", "(pz_ModelViewProjectionMatrix * pz_Vertex)");
               if (!decl.toString().contains("pz_ModelViewProjectionMatrix;")) {
                  decl.append("uniform mat4 pz_ModelViewProjectionMatrix;\n");
               }
               if (!containsWord(body, "gl_Vertex")) {
                  decl.append("in vec4 pz_Vertex;\n");
               }
            }
            body = builtin(body, decl, "gl_Vertex", "in vec4 pz_Vertex;");
            body = builtin(body, decl, "gl_Normal", "in vec3 pz_Normal;");
            body = builtin(body, decl, "gl_SecondaryColor", "in vec4 pz_SecondaryColor;");
            body = builtin(body, decl, "gl_Color", "in vec4 pz_Color;");
            body = builtin(body, decl, "gl_FogCoord", "in float pz_FogCoord;");
            for (int i = 0; i < 8; i++) {
               body = builtin(body, decl, "gl_MultiTexCoord" + i, "in vec4 pz_MultiTexCoord" + i + ";");
            }
            body = builtin(body, decl, "gl_FrontColor", "out vec4 pz_FrontColor;");
            if (containsWord(body, "gl_BackColor")) {
               body = replaceWord(body, "gl_BackColor", "pz_BackColorUnused");
               decl.append("vec4 pz_BackColorUnused;\n");
            }
            body = builtin(body, decl, "gl_FrontSecondaryColor", "out vec4 pz_FrontSecondaryColor;");
            body = builtin(body, decl, "gl_TexCoord", "out vec4 pz_TexCoord[" + texCoords(body) + "];");
            body = builtin(body, decl, "gl_FogFragCoord", "out float pz_FogFragCoord;");
         } else {
            body = builtin(body, decl, "gl_Color", "in vec4 pz_FrontColor;", "pz_FrontColor");
            body = builtin(body, decl, "gl_SecondaryColor", "in vec4 pz_FrontSecondaryColor;", "pz_FrontSecondaryColor");
            body = builtin(body, decl, "gl_TexCoord", "in vec4 pz_TexCoord[" + texCoords(body) + "];");
            body = builtin(body, decl, "gl_FogFragCoord", "in float pz_FogFragCoord;");
            body = builtin(body, decl, "gl_FragColor", "out vec4 pz_FragColor;");
            body = builtin(body, decl, "gl_FragData", "out vec4 pz_FragData[8];");
         }
      }

      if (!vertex && alpha) {
         String out = fragmentColour(body);
         Matcher mm = MAIN.matcher(body);
         if (out != null && mm.find()) {
            body = body.substring(0, mm.start()) + "void pz_main()" + body.substring(mm.end());
            body = body + "\nuniform vec2 " + ALPHA_UNIFORM + ";\nvoid main() {\n   pz_main();\n   int pzF = int(" + ALPHA_UNIFORM + ".x);\n"
               + "   if (pzF != 0 && pzF != 8) {\n      float pzA = " + out + ".a, pzR = " + ALPHA_UNIFORM + ".y;\n"
               + "      bool pzP = pzF == 2 ? pzA < pzR : pzF == 3 ? pzA == pzR : pzF == 4 ? pzA <= pzR : pzF == 5 ? pzA > pzR"
               + " : pzF == 6 ? pzA != pzR : pzF == 7 ? pzA >= pzR : false;\n      if (!pzP) discard;\n   }\n}\n";
            changed = true;
         }
      }
      if (!changed) {
         return new Result(src, bindings, false);
      }
      String head = "#version " + target + (target >= 150 ? " core" : "") + "\n" + extLines + (decl.length() == 0 ? "" : decl) + "#line 1\n";
      return new Result(head + body, bindings, true);
   }

   /** The colour the alpha test reads: the single vec4 output (location 0), gl_FragColor, gl_FragData[0]; null = none. */
   static String fragmentColour(String body) {
      if (body.contains("pz_FragColor")) {
         return "pz_FragColor";
      }
      if (body.contains("pz_FragData")) {
         return "pz_FragData[0]";
      }
      Matcher m = FRAG_OUT.matcher(body);
      String first = null;
      int n = 0;
      while (m.find()) {
         n++;
         if (m.group(1) != null && "0".equals(m.group(1))) {
            return m.group(2);
         }
         if (first == null) {
            first = m.group(2);
         }
      }
      return n == 1 ? first : n > 1 && first != null ? first : null;
   }

   private static int texCoords(String body) {
      int max = 0;
      Matcher m = TEX_INDEX.matcher(body);
      while (m.find()) {
         max = Math.max(max, Integer.parseInt(m.group(1)) + 1);
      }
      return max <= TEXCOORDS ? TEXCOORDS : 8;
   }

   private static String builtin(String body, StringBuilder decl, String name, String declaration) {
      return builtin(body, decl, name, declaration, "pz" + name.substring(2));
   }

   private static String builtin(String body, StringBuilder decl, String name, String declaration, String replacement) {
      if (!containsWord(body, name)) {
         return body;
      }
      if (decl.indexOf(declaration) < 0) {
         decl.append(declaration).append('\n');
      }
      return replaceWord(body, name, replacement);
   }

   static String stripBindings(String body, Map<String, Integer> bindings) {
      StringBuilder sb = new StringBuilder();
      Matcher m = BINDING.matcher(body);
      int last = 0;
      while (m.find()) {
         Matcher b = BINDING_IN.matcher(m.group(1));
         if (!b.find()) {
            continue;
         }
         bindings.put(m.group(3), Integer.parseInt(b.group(1)));
         String q = (m.group(1).substring(0, b.start()) + m.group(1).substring(b.end())).replaceAll("^\\s*,|,\\s*$", "").trim();
         sb.append(body, last, m.start());
         sb.append(q.isEmpty() ? "" : "layout(" + q + ") ").append("uniform ").append(m.group(2)).append(' ').append(m.group(3));
         last = m.end();
      }
      sb.append(body.substring(last));
      body = sb.toString();
      sb.setLength(0);
      m = BLOCK_BINDING.matcher(body);
      last = 0;
      while (m.find()) {
         Matcher b = BINDING_IN.matcher(m.group(1));
         if (!b.find()) {
            continue;
         }
         bindings.put("block:" + m.group(2), Integer.parseInt(b.group(1)));
         String q = (m.group(1).substring(0, b.start()) + m.group(1).substring(b.end())).replaceAll("^\\s*,|,\\s*$", "").trim();
         sb.append(body, last, m.start());
         sb.append(q.isEmpty() ? "" : "layout(" + q + ") ").append("uniform ").append(m.group(2)).append(" {");
         last = m.end();
      }
      sb.append(body.substring(last));
      return sb.toString();
   }

   static boolean containsWord(String s, String w) {
      int i = s.indexOf(w);
      while (i >= 0) {
         int e = i + w.length();
         if ((i == 0 || !ident(s.charAt(i - 1))) && (e >= s.length() || !ident(s.charAt(e)))) {
            return true;
         }
         i = s.indexOf(w, i + 1);
      }
      return false;
   }

   static String replaceWord(String s, String w, String r) {
      StringBuilder sb = null;
      int from = 0;
      int i = s.indexOf(w);
      while (i >= 0) {
         int e = i + w.length();
         if ((i == 0 || !ident(s.charAt(i - 1))) && (e >= s.length() || !ident(s.charAt(e)))) {
            if (sb == null) {
               sb = new StringBuilder(s.length() + 64);
            }
            sb.append(s, from, i).append(r);
            from = e;
         }
         i = s.indexOf(w, e);
      }
      if (sb == null) {
         return s;
      }
      sb.append(s, from, s.length());
      return sb.toString();
   }

   private static boolean ident(char c) {
      return c == '_' || Character.isLetterOrDigit(c);
   }

   /** The pz_ attributes a translated vertex stage may declare, for glBindAttribLocation before the link. */
   public static List<String[]> attributeBindings() {
      List<String[]> l = new ArrayList<>();
      for (String[] a : ATTRIBS) {
         l.add(a);
      }
      return l;
   }
}
