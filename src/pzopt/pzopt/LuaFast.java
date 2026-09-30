package pzopt;

import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;

/**
 * Kahlua fast paths (2026-09-30, the UI snappiness pass).
 *
 * <p>luaInternConstants: every string constant of a compiled chunk is interned. Lua table keys are Java strings; a
 * method name stored into a class table by one file and looked up by another were two different String objects, so
 * every HashMap hit also ran String.equals over the characters. Interned, the lookup's identity test hits first.
 * Lua semantics are unchanged (strings compare by value in Kahlua either way).
 */
public final class LuaFast {
   private LuaFast() {
   }

   /**
    * luaSkipEmpty: true when {@code fn} is a Lua function whose body is empty (one RETURN instruction, no upvalues
    * touched), so calling it has no effect and the UI element's prerender / render / update call can be skipped
    * (ISUIElement's defaults are empty and most elements inherit at least one of them).
    */
   public static boolean skipEmpty(Object fn) {
      if (!Config.LUA_SKIP_EMPTY || !(fn instanceof LuaClosure c)) {
         return false;
      }
      Prototype p = c.prototype;
      int[] code = p.code;
      return code != null && code.length == 1 && (code[0] & 63) == 30;
   }

   /** Interns the string constants of p and its nested functions (once per prototype; returns p). */
   public static Prototype intern(Prototype p) {
      if (!Config.LUA_INTERN_CONSTANTS || p == null) {
         return p;
      }
      internRec(p, 0);
      return p;
   }

   private static void internRec(Prototype p, int depth) {
      if (p == null || depth > 200) {
         return;
      }
      Object[] c = p.constants;
      if (c != null) {
         for (int i = 0; i < c.length; i++) {
            if (c[i] instanceof String s) {
               c[i] = s.intern();
            }
         }
      }
      Prototype[] children = p.prototypes;
      if (children != null) {
         for (Prototype child : children) {
            internRec(child, depth + 1);
         }
      }
   }
}
