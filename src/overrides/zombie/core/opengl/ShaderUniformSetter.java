package zombie.core.opengl;

import org.lwjgl.opengl.GL20;
import zombie.core.opengl.ShaderProgram.Uniform;
import zombie.core.textures.TextureDraw.GenericDrawer;

public final class ShaderUniformSetter extends GenericDrawer {
   ShaderUniformSetter.Type type;
   int location;
   float f1;
   float f2;
   float f3;
   float f4;
   int i1;
   int i2;
   int i3;
   int i4;
   ShaderUniformSetter next;
   static ShaderUniformSetter pool;

   private ShaderUniformSetter set(ShaderUniformSetter.Type type, int location) {
      this.type = type;
      this.location = location;
      this.next = null;
      return this;
   }

   public ShaderUniformSetter pzoptNext() { // pzopt: foliage sway appends its uniform to a chain
      return this.next; // pzopt
   } // pzopt

   /** pzopt: tileRecordParallel's draw-list check, whether two chains set the same uniforms to the same values. */
   public boolean pzoptSameChain(ShaderUniformSetter o) { // pzopt
      ShaderUniformSetter a = this; // pzopt
      ShaderUniformSetter b = o; // pzopt
      while (a != null && b != null) { // pzopt
         if (a.type != b.type || a.location != b.location || !pzoptSameValues(a, b)) { // pzopt
            return false; // pzopt
         } // pzopt
         a = a.next; // pzopt
         b = b.next; // pzopt
      } // pzopt
      return a == b; // pzopt
   } // pzopt

   /** pzopt: the fields this entry's type sets (a pooled setter keeps stale values in the others; NIL sets none). */
   private static boolean pzoptSameValues(ShaderUniformSetter a, ShaderUniformSetter b) { // pzopt
      int nf = 0; // pzopt
      int ni = 0; // pzopt
      switch (a.type) { // pzopt
         case Uniform1f: nf = 1; break; // pzopt
         case Uniform2f: nf = 2; break; // pzopt
         case Uniform3f: nf = 3; break; // pzopt
         case Uniform4f: nf = 4; break; // pzopt
         case Uniform1i: ni = 1; break; // pzopt
         case Uniform2i: ni = 2; break; // pzopt
         case Uniform3i: ni = 3; break; // pzopt
         case Uniform4i: ni = 4; break; // pzopt
         default: break; // pzopt
      } // pzopt
      float[] fa = {a.f1, a.f2, a.f3, a.f4}; // pzopt
      float[] fb = {b.f1, b.f2, b.f3, b.f4}; // pzopt
      int[] ia = {a.i1, a.i2, a.i3, a.i4}; // pzopt
      int[] ib = {b.i1, b.i2, b.i3, b.i4}; // pzopt
      for (int k = 0; k < nf; k++) { // pzopt
         if (Float.floatToIntBits(fa[k]) != Float.floatToIntBits(fb[k])) { // pzopt
            return false; // pzopt
         } // pzopt
      } // pzopt
      for (int k = 0; k < ni; k++) { // pzopt
         if (ia[k] != ib[k]) { // pzopt
            return false; // pzopt
         } // pzopt
      } // pzopt
      return true; // pzopt
   } // pzopt

   /** pzopt: tileRecordParallel's draw-list check, the chain as text. */
   public String pzoptDescribe() { // pzopt
      StringBuilder sb = new StringBuilder(); // pzopt
      for (ShaderUniformSetter a = this; a != null; a = a.next) { // pzopt
         sb.append(a.type).append('@').append(a.location).append('=').append(a.f1).append(',').append(a.f2).append(',').append(a.f3).append(',').append(a.f4) // pzopt
            .append('/').append(a.i1).append(',').append(a.i2).append(' '); // pzopt
      } // pzopt
      return sb.toString(); // pzopt
   } // pzopt

   public ShaderUniformSetter setNext(ShaderUniformSetter next) {
      this.next = next;
      return next;
   }

   public void render() {
   }

   public void postRender() {
      ShaderUniformSetter e = this;

      while (e != null) {
         ShaderUniformSetter next1 = e.next;
         release(e);
         e = next1;
      }
   }

   public void invokeAll() {
      for (ShaderUniformSetter e = this; e != null; e = e.next) {
         e.invoke();
      }
   }

   // pzopt: uniformCache (pzopt.UniformCache). The chain of a shader start, skipping every 1f / 1i uniform the program already holds.
   public void pzoptInvokeAllCached() { // pzopt
      for (ShaderUniformSetter e = this; e != null; e = e.next) {
         if (e.type == ShaderUniformSetter.Type.Uniform1f) {
            if (pzopt.UniformCache.same1f(e.location, e.f1)) {
               continue;
            }
         } else if (e.type == ShaderUniformSetter.Type.Uniform1i) {
            if (pzopt.UniformCache.same1i(e.location, e.i1)) {
               continue;
            }
         } else {
            pzopt.UniformCache.forget(e.location);
         }
         e.invoke();
      }
   }

   private void invoke() {
      switch (this.type) {
         case Uniform1f:
            GL20.glUniform1f(this.location, this.f1);
            break;
         case Uniform2f:
            GL20.glUniform2f(this.location, this.f1, this.f2);
            break;
         case Uniform3f:
            GL20.glUniform3f(this.location, this.f1, this.f2, this.f3);
            break;
         case Uniform4f:
            GL20.glUniform4f(this.location, this.f1, this.f2, this.f3, this.f4);
            break;
         case Uniform1i:
            GL20.glUniform1i(this.location, this.i1);
            break;
         case Uniform2i:
            GL20.glUniform2i(this.location, this.i1, this.i2);
            break;
         case Uniform3i:
            GL20.glUniform3i(this.location, this.i1, this.i2, this.i3);
            break;
         case Uniform4i:
            GL20.glUniform4i(this.location, this.i1, this.i2, this.i3, this.i4);
      }
   }

   public static ShaderUniformSetter uniform1f(int location, float f1) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform1f, location);
      e.f1 = f1;
      return e;
   }

   public static ShaderUniformSetter uniform2f(int location, float f1, float f2) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform2f, location);
      e.f1 = f1;
      e.f2 = f2;
      return e;
   }

   public static ShaderUniformSetter uniform3f(int location, float f1, float f2, float f3) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform3f, location);
      e.f1 = f1;
      e.f2 = f2;
      e.f3 = f3;
      return e;
   }

   public static ShaderUniformSetter uniform4f(int location, float f1, float f2, float f3, float f4) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform4f, location);
      e.f1 = f1;
      e.f2 = f2;
      e.f3 = f3;
      e.f4 = f4;
      return e;
   }

   private static Uniform getShaderUniform(Shader shader, String uniformName, int uniformType) {
      if (shader == null) {
         return null;
      }

      ShaderProgram program = shader.getProgram();
      return program == null ? null : program.getUniform(uniformName, uniformType, false);
   }

   public static ShaderUniformSetter uniform1f(Shader shader, String location, float f1) {
      Uniform u = getShaderUniform(shader, location, 5126);
      return u == null ? alloc().set(ShaderUniformSetter.Type.NIL, -1) : uniform1f(u.loc, f1);
   }

   public static ShaderUniformSetter uniform1i(int location, int i1) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform1i, location);
      e.i1 = i1;
      return e;
   }

   public static ShaderUniformSetter uniform2i(int location, int i1, int i2) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform2i, location);
      e.i1 = i1;
      e.i2 = i2;
      return e;
   }

   public static ShaderUniformSetter uniform3i(int location, int i1, int i2, int i3) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform3i, location);
      e.i1 = i1;
      e.i2 = i2;
      e.i3 = i3;
      return e;
   }

   public static ShaderUniformSetter uniform4i(int location, int i1, int i2, int i3, int i4) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform4i, location);
      e.i1 = i1;
      e.i2 = i2;
      e.i3 = i3;
      e.i4 = i4;
      return e;
   }

   public static ShaderUniformSetter uniform1i(Shader shader, String location, int i1) {
      Uniform u = getShaderUniform(shader, location, 5124);
      return u == null ? alloc().set(ShaderUniformSetter.Type.NIL, -1) : uniform1i(u.loc, i1);
   }

   public static ShaderUniformSetter alloc() {
      if (pzopt.DrawRecorder.recording) { // pzopt: tileRecordParallel, a recording thread takes from its recorder's own pool (refilled by the game thread before each pass)
         pzopt.DrawRecorder r = pzopt.DrawRecorder.currentRecorder(); // pzopt
         if (r != null) { // pzopt
            ShaderUniformSetter e = (ShaderUniformSetter)r.uniformPool; // pzopt
            if (e == null) { // pzopt
               return new ShaderUniformSetter(); // pzopt
            } // pzopt
            r.uniformPool = e.next; // pzopt
            r.uniformPoolSize--; // pzopt
            return e; // pzopt
         } // pzopt
      } // pzopt
      if (pool == null) {
         return new ShaderUniformSetter();
      }

      ShaderUniformSetter e = pool;
      pool = e.next;
      return e;
   }

   /** pzopt: tileRecordParallel, game thread before a recording pass: tops {@code r}'s own pool up to {@code n} from the shared one. */
   public static void pzoptRefill(pzopt.DrawRecorder r, int n) { // pzopt
      while (r.uniformPoolSize < n) { // pzopt
         ShaderUniformSetter e = pool; // pzopt
         if (e == null) { // pzopt
            e = new ShaderUniformSetter(); // pzopt
         } else { // pzopt
            pool = e.next; // pzopt
         } // pzopt
         e.next = (ShaderUniformSetter)r.uniformPool; // pzopt
         r.uniformPool = e; // pzopt
         r.uniformPoolSize++; // pzopt
      } // pzopt
   } // pzopt

   public static void release(ShaderUniformSetter e) {
      e.next = pool;
      pool = e;
   }

   public enum Type {
      NIL,
      Uniform1f,
      Uniform2f,
      Uniform3f,
      Uniform4f,
      Uniform1i,
      Uniform2i,
      Uniform3i,
      Uniform4i;
   }
}
