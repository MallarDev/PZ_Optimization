package pzopt;

import zombie.debug.DebugLog;
import zombie.debug.DebugLogStream;
import zombie.debug.DebugType;

/**
 * Logs through the game's DebugLog so lines land in console.txt, prefixed
 * [pzopt]. Outside the game (tests, tools) DebugLog is never initialised and
 * would swallow everything, so fall back to plain stdout there.
 *
 * {@code consoleLog} (Profiler tab, live, 2026-10-01) quiets it for players debugging other mods: all | warnings |
 * errors | off. Config sets the level at init and on every live reload; until then everything is logged. Harness
 * runs pin consoleLog=all (harness/run.sh): the analysis scripts read these lines.
 */
public final class Log {
   private static final String PREFIX = "[pzopt] ";
   static final int ALL = 0;
   static final int WARNINGS = 1;
   static final int ERRORS = 2;
   static final int OFF = 3;
   private static volatile int level = ALL;

   private Log() {
   }

   static boolean gameLogReady() {
      return System.out instanceof DebugLogStream;
   }

   /** The consoleLog setting: all | warnings | errors | off (anything else = all). */
   static void setLevel(String setting) {
      level = switch (setting == null ? "" : setting.trim().toLowerCase(java.util.Locale.ROOT)) {
         case "warnings" -> WARNINGS;
         case "errors" -> ERRORS;
         case "off" -> OFF;
         default -> ALL;
      };
   }

   public static void info(String msg) {
      if (level > ALL) {
         return;
      }
      if (gameLogReady()) {
         DebugLog.log(DebugType.General, PREFIX + msg);
      } else {
         System.out.println("LOG  " + PREFIX + msg);
      }
   }

   public static void warn(String msg) {
      if (level > WARNINGS) {
         return;
      }
      if (gameLogReady()) {
         DebugType.General.warn(PREFIX + msg.replace("%", "%%"));
      } else {
         System.out.println("WARN " + PREFIX + msg);
      }
   }

   public static void error(String msg) {
      if (level > ERRORS) {
         return;
      }
      if (gameLogReady()) {
         DebugType.General.error(PREFIX + msg.replace("%", "%%"));
      } else {
         System.err.println("ERROR " + PREFIX + msg);
      }
   }
}
