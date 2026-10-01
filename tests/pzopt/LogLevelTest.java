package pzopt;

import static pzopt.Check.check;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/** consoleLog (pzopt.Log.setLevel): all | warnings | errors | off drop the lines below the level, unknown = all. */
public class LogLevelTest {
   public static void main(String[] args) {
      check(lines("all").equals("IWE"), "all logs info, warnings and errors");
      check(lines("warnings").equals("WE"), "warnings drops info");
      check(lines("errors").equals("E"), "errors drops info and warnings");
      check(lines("off").equals(""), "off drops everything");
      check(lines(" Warnings ").equals("WE"), "the setting is trimmed and case-insensitive");
      check(lines("nonsense").equals("IWE"), "an unknown setting logs everything");
      check(lines(null).equals("IWE"), "no setting logs everything");
      Log.setLevel("all");
      System.out.println("LogLevelTest ok");
   }

   /** Which of info / warn / error reached stdout / stderr at this level, as "IWE" letters. */
   private static String lines(String setting) {
      PrintStream out = System.out;
      PrintStream err = System.err;
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      ByteArrayOutputStream e = new ByteArrayOutputStream();
      try {
         System.setOut(new PrintStream(o, true));
         System.setErr(new PrintStream(e, true));
         Log.setLevel(setting);
         Log.info("i");
         Log.warn("w");
         Log.error("e");
      } finally {
         System.setOut(out);
         System.setErr(err);
      }
      String s = o.toString() + e.toString();
      return (s.contains("LOG  ") ? "I" : "") + (s.contains("WARN ") ? "W" : "") + (s.contains("ERROR ") ? "E" : "");
   }
}
