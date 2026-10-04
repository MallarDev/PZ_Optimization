import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.luaj.kahluafork.compiler.LexState;
import se.krka.kahlua.vm.Prototype;

/**
 * Compiles Lua files with the game's own Kahlua compiler as a game launched with -debug does
 * (Core.debug = true), then lists the functions close to the compiler's local-variable limit.
 *
 * With Core.debug the compiler records each local's line in FuncState.actvarline, an int[200] indexed by the
 * number of locals the function has declared so far (every local, parameter and loop variable of its body,
 * scopes that ended included), not by the ones alive at once. A function declaring more than 200 throws
 * ArrayIndexOutOfBoundsException and the whole file fails to load, in -debug games only (issue #58: the
 * options tab's relayout, 2026-10-04). Without -debug the same file compiles.
 *
 * Usage: java -cp projectzomboid.jar scripts/LuaDebugCompile.java [--warn N] file.lua...
 * Exit status 1 when a file fails to compile.
 */
public class LuaDebugCompile {
    static final int LIMIT = 200;

    record Fn(String file, int line, String name, int locals) {}

    public static void main(String[] args) throws Exception {
        int warn = 180;
        List<String> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--warn")) warn = Integer.parseInt(args[++i]);
            else files.add(args[i]);
        }
        java.lang.reflect.Field debug = Class.forName("zombie.core.Core", false, LuaDebugCompile.class.getClassLoader()).getField("debug");
        int failed = 0;
        List<Fn> near = new ArrayList<>();
        for (String f : files) {
            Path p = Path.of(f);
            try {
                debug.setBoolean(null, true);
                walk(f, compile(p), near, warn);
            } catch (Throwable t) {
                Throwable c = t;
                while (c.getCause() != null) c = c.getCause();
                System.out.println("LUA DEBUG COMPILE FAILED " + f + ": " + c);
                failed++;
                // name the functions over the limit: the compile without -debug keeps every local's name
                try {
                    debug.setBoolean(null, false);
                    walk(f, compile(p), near, LIMIT + 1);
                } catch (Throwable t2) {
                    System.out.println("  (fails without -debug too: " + t2 + ")");
                }
            }
        }
        near.sort((a, b) -> b.locals - a.locals);
        for (Fn fn : near) {
            System.out.println((fn.locals > LIMIT ? "OVER the limit: " : "near the limit: ") + fn.file + ":" + fn.line + " " + fn.name + " declares " + fn.locals
                + " locals (more than " + LIMIT + " fails under -debug)");
        }
        System.out.println("lua debug compile: " + (files.size() - failed) + " of " + files.size() + " files OK");
        System.exit(failed > 0 ? 1 : 0);
    }

    static Prototype compile(Path p) throws Exception {
        try (Reader r = Files.newBufferedReader(p)) {
            return LexState.compile(r.read(), r, p.getFileName().toString(), p.toString());
        }
    }

    static void walk(String file, Prototype p, List<Fn> near, int warn) {
        int n = 0;
        if (p.locvars != null) {
            for (String v : p.locvars) if (v != null) n++;
        }
        if (n >= warn) {
            int line = p.lines != null && p.lines.length > 0 ? p.lines[0] : 0;
            near.add(new Fn(file, line, p.name == null ? "?" : p.name, n));
        }
        if (p.prototypes != null) {
            for (Prototype c : p.prototypes) if (c != null) walk(file, c, near, warn);
        }
    }
}
