package se.krka.kahlua.integration;

import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.vm.KahluaThread;

public class LuaCaller {
   private final KahluaConverterManager converterManager;

   public LuaCaller(KahluaConverterManager converterManager) {
      this.converterManager = converterManager;
   }

   public void pcallvoid(KahluaThread thread, Object functionObject, Object arg) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         pzopt.LuaGate.callVoid(functionObject, () -> this.pcallvoid(thread, functionObject, pzoptArg)); // pzopt
         return; // pzopt
      } // pzopt

      thread.pcallvoid(functionObject, arg);
   }

   public void pcallvoid(KahluaThread thread, Object functionObject, Object arg, Object arg2) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         Object pzoptArg2 = arg2; // pzopt: the lambda needs effectively final copies
         pzopt.LuaGate.callVoid(functionObject, () -> this.pcallvoid(thread, functionObject, pzoptArg, pzoptArg2)); // pzopt
         return; // pzopt
      } // pzopt

      thread.pcallvoid(functionObject, arg, arg2);
   }

   public void pcallvoid(KahluaThread thread, Object functionObject, Object arg, Object arg2, Object arg3) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         Object pzoptArg2 = arg2; // pzopt: the lambda needs effectively final copies
         Object pzoptArg3 = arg3; // pzopt: the lambda needs effectively final copies
         pzopt.LuaGate.callVoid(functionObject, () -> this.pcallvoid(thread, functionObject, pzoptArg, pzoptArg2, pzoptArg3)); // pzopt
         return; // pzopt
      } // pzopt

      thread.pcallvoid(functionObject, arg, arg2, arg3);
   }

   public Boolean pcallBoolean(KahluaThread thread, Object functionObject, Object arg, Object arg2) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         Object pzoptArg2 = arg2; // pzopt: the lambda needs effectively final copies
         return (Boolean) pzopt.LuaGate.call(functionObject, () -> this.pcallBoolean(thread, functionObject, pzoptArg, pzoptArg2)); // pzopt
      } // pzopt

      return thread.pcallBoolean(functionObject, arg, arg2);
   }

   public Boolean pcallBoolean(KahluaThread thread, Object functionObject, Object arg, Object arg2, Object arg3) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         Object pzoptArg2 = arg2; // pzopt: the lambda needs effectively final copies
         Object pzoptArg3 = arg3; // pzopt: the lambda needs effectively final copies
         return (Boolean) pzopt.LuaGate.call(functionObject, () -> this.pcallBoolean(thread, functionObject, pzoptArg, pzoptArg2, pzoptArg3)); // pzopt
      } // pzopt

      return thread.pcallBoolean(functionObject, arg, arg2, arg3);
   }

   public void pcallvoid(KahluaThread thread, Object functionObject, Object[] args) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         pzopt.LuaGate.callVoid(functionObject, () -> this.pcallvoid(thread, functionObject, args)); // pzopt
         return; // pzopt
      } // pzopt

      if (args != null) {
         for (int i = args.length - 1; i >= 0; i--) {
            args[i] = this.converterManager.fromJavaToLua(args[i]);
         }
      }

      thread.pcallvoid(functionObject, args);
   }

   public Object[] pcall(KahluaThread thread, Object functionObject, Object... args) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         return (Object[]) pzopt.LuaGate.call(functionObject, () -> this.pcall(thread, functionObject, args)); // pzopt
      } // pzopt

      if (args != null) {
         for (int i = args.length - 1; i >= 0; i--) {
            args[i] = this.converterManager.fromJavaToLua(args[i]);
         }
      }

      return thread.pcall(functionObject, args);
   }

   public Object[] pcall(KahluaThread thread, Object functionObject, Object arg) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         return (Object[]) pzopt.LuaGate.call(functionObject, () -> this.pcall(thread, functionObject, pzoptArg)); // pzopt
      } // pzopt

      if (arg != null) {
         arg = this.converterManager.fromJavaToLua(arg);
      }

      return thread.pcall(functionObject, new Object[]{arg});
   }

   public Boolean protectedCallBoolean(KahluaThread thread, Object functionObject, Object arg) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         return (Boolean) pzopt.LuaGate.call(functionObject, () -> this.protectedCallBoolean(thread, functionObject, pzoptArg)); // pzopt
      } // pzopt

      arg = this.converterManager.fromJavaToLua(arg);
      return thread.pcallBoolean(functionObject, arg);
   }

   public Boolean protectedCallBoolean(KahluaThread thread, Object functionObject, Object arg, Object arg2) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         Object pzoptArg2 = arg2; // pzopt: the lambda needs effectively final copies
         return (Boolean) pzopt.LuaGate.call(functionObject, () -> this.protectedCallBoolean(thread, functionObject, pzoptArg, pzoptArg2)); // pzopt
      } // pzopt

      arg = this.converterManager.fromJavaToLua(arg);
      arg2 = this.converterManager.fromJavaToLua(arg2);
      return thread.pcallBoolean(functionObject, arg, arg2);
   }

   public Boolean protectedCallBoolean(KahluaThread thread, Object functionObject, Object arg, Object arg2, Object arg3) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         Object pzoptArg2 = arg2; // pzopt: the lambda needs effectively final copies
         Object pzoptArg3 = arg3; // pzopt: the lambda needs effectively final copies
         return (Boolean) pzopt.LuaGate.call(functionObject, () -> this.protectedCallBoolean(thread, functionObject, pzoptArg, pzoptArg2, pzoptArg3)); // pzopt
      } // pzopt

      arg = this.converterManager.fromJavaToLua(arg);
      arg2 = this.converterManager.fromJavaToLua(arg2);
      arg3 = this.converterManager.fromJavaToLua(arg3);
      return thread.pcallBoolean(functionObject, arg, arg2, arg3);
   }

   public Boolean pcallBoolean(KahluaThread thread, Object functionObject, Object[] args) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         return (Boolean) pzopt.LuaGate.call(functionObject, () -> this.pcallBoolean(thread, functionObject, args)); // pzopt
      } // pzopt

      if (args != null) {
         for (int i = args.length - 1; i >= 0; i--) {
            args[i] = this.converterManager.fromJavaToLua(args[i]);
         }
      }

      return thread.pcallBoolean(functionObject, args);
   }

   public LuaReturn protectedCall(KahluaThread thread, Object functionObject, Object... args) {
      return LuaReturn.createReturn(this.pcall(thread, functionObject, args));
   }

   public void protectedCallVoid(KahluaThread thread, Object functionObject, Object arg) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         pzopt.LuaGate.callVoid(functionObject, () -> this.protectedCallVoid(thread, functionObject, pzoptArg)); // pzopt
         return; // pzopt
      } // pzopt

      arg = this.converterManager.fromJavaToLua(arg);
      thread.pcallvoid(functionObject, arg);
   }

   public void protectedCallVoid(KahluaThread thread, Object functionObject, Object arg, Object arg2) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         Object pzoptArg2 = arg2; // pzopt: the lambda needs effectively final copies
         pzopt.LuaGate.callVoid(functionObject, () -> this.protectedCallVoid(thread, functionObject, pzoptArg, pzoptArg2)); // pzopt
         return; // pzopt
      } // pzopt

      arg = this.converterManager.fromJavaToLua(arg);
      arg2 = this.converterManager.fromJavaToLua(arg2);
      thread.pcallvoid(functionObject, arg, arg2);
   }

   public void protectedCallVoid(KahluaThread thread, Object functionObject, Object arg, Object arg2, Object arg3) {
      if (pzopt.LuaGate.onWorker()) { // pzopt: luaWorkerGate, a frame worker's Lua runs on the game thread
         Object pzoptArg = arg; // pzopt: the lambda needs effectively final copies
         Object pzoptArg2 = arg2; // pzopt: the lambda needs effectively final copies
         Object pzoptArg3 = arg3; // pzopt: the lambda needs effectively final copies
         pzopt.LuaGate.callVoid(functionObject, () -> this.protectedCallVoid(thread, functionObject, pzoptArg, pzoptArg2, pzoptArg3)); // pzopt
         return; // pzopt
      } // pzopt

      arg = this.converterManager.fromJavaToLua(arg);
      arg2 = this.converterManager.fromJavaToLua(arg2);
      arg3 = this.converterManager.fromJavaToLua(arg3);
      thread.pcallvoid(functionObject, arg, arg2, arg3);
   }

   public void protectedCallVoid(KahluaThread thread, Object functionObject, Object[] args) {
      this.pcallvoid(thread, functionObject, args);
   }

   public Boolean protectedCallBoolean(KahluaThread thread, Object functionObject, Object[] args) {
      return this.pcallBoolean(thread, functionObject, args);
   }
}
