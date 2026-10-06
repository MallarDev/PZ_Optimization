import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.Opcode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Static-field reachability audit for a set of entry methods in projectzomboid.jar.
 *
 *   java tools/StaticAudit.java <jar> <package-prefix> <entry>... [--leaf <class-prefix>]...
 *     entry: owner/Class.method(descriptor)   e.g. zombie/iso/IsoChunk.loadInWorldStreamerThread()V
 *     --leaf: class prefixes to treat as opaque (their code is not walked, but calls into them are reported)
 *
 * Walks invoke instructions transitively. Virtual/interface calls include every
 * implementation of that method in any subtype found in the jar (conservative).
 * Reports every GETSTATIC/PUTSTATIC reached with the shortest call chain from an
 * entry, plus the calls into leaf classes so those can be audited by hand.
 */
public class StaticAudit {
   record MethodRef(String owner, String name, String desc) {
      public String toString() { return owner + "." + name + desc; }
   }

   static Map<String, ClassModel> classes = new HashMap<>();
   static Map<String, List<String>> subtypes = new HashMap<>(); // owner -> direct subtypes
   static Map<MethodRef, MethodModel> methods = new HashMap<>();
   static List<String> excludes = new ArrayList<>(); // --exclude: classes neither walked nor dispatched to (types the caller keeps off the audited path)
   static boolean putfields; // --putfield: also report instance-field writes (PUTFIELD), for passes that split objects across threads

   static boolean excluded(String owner) {
      for (String e : excludes) {
         if (owner.startsWith(e)) return true;
      }
      return false;
   }

   public static void main(String[] args) throws Exception {
      Path jar = Path.of(args[0]);
      String pkg = args[1];
      List<MethodRef> entries = new ArrayList<>();
      List<String> leaves = new ArrayList<>();
      for (int i = 2; i < args.length; i++) {
         if (args[i].equals("--leaf")) { leaves.add(args[++i]); continue; }
         if (args[i].equals("--exclude")) { excludes.add(args[++i]); continue; }
         if (args[i].equals("--putfield")) { putfields = true; continue; }
         String a = args[i];
         int p = a.indexOf('('), d = a.lastIndexOf('.', p);
         entries.add(new MethodRef(a.substring(0, d), a.substring(d + 1, p), a.substring(p)));
      }

      try (JarFile jf = new JarFile(jar.toFile())) {
         Enumeration<JarEntry> en = jf.entries();
         while (en.hasMoreElements()) {
            JarEntry e = en.nextElement();
            if (!e.getName().endsWith(".class") || !e.getName().startsWith(pkg)) continue;
            ClassModel cm = ClassFile.of().parse(jf.getInputStream(e).readAllBytes());
            String name = cm.thisClass().asInternalName();
            classes.put(name, cm);
            cm.superclass().ifPresent(s -> subtypes.computeIfAbsent(s.asInternalName(), k -> new ArrayList<>()).add(name));
            for (var itf : cm.interfaces()) subtypes.computeIfAbsent(itf.asInternalName(), k -> new ArrayList<>()).add(name);
            for (MethodModel mm : cm.methods()) {
               methods.put(new MethodRef(name, mm.methodName().stringValue(), mm.methodType().stringValue()), mm);
            }
         }
      }

      // BFS over the call graph, remembering the parent so chains can be printed.
      Map<MethodRef, MethodRef> parent = new LinkedHashMap<>();
      ArrayDeque<MethodRef> queue = new ArrayDeque<>();
      for (MethodRef e : entries) { parent.put(e, null); queue.add(e); }
      TreeMap<String, String> statics = new TreeMap<>();   // "owner.field" -> "R|W|RW"
      Map<String, MethodRef> staticWhere = new HashMap<>();
      TreeMap<String, MethodRef> leafCalls = new TreeMap<>();
      Set<MethodRef> visited = new HashSet<>();

      while (!queue.isEmpty()) {
         MethodRef m = queue.poll();
         if (!visited.add(m)) continue;
         MethodModel mm = resolve(m);
         if (mm == null || mm.code().isEmpty()) continue;
         for (CodeElement ce : mm.code().get()) {
            if (ce instanceof FieldInstruction fi) {
               Opcode op = fi.opcode();
               if (putfields && op == Opcode.PUTFIELD) {
                  String key = "~" + fi.owner().asInternalName() + "." + fi.name().stringValue();
                  statics.merge(key, "W", (a, b) -> a);
                  staticWhere.putIfAbsent(key, m);
               }
               if (op == Opcode.GETSTATIC || op == Opcode.PUTSTATIC) {
                  String key = fi.owner().asInternalName() + "." + fi.name().stringValue();
                  String mode = op == Opcode.GETSTATIC ? "R" : "W";
                  statics.merge(key, mode, (a, b) -> a.contains(b) ? a : "RW");
                  staticWhere.putIfAbsent(key, m);
               }
            } else if (ce instanceof InvokeInstruction ii) {
               String owner = ii.owner().asInternalName();
               MethodRef target = new MethodRef(owner, ii.name().stringValue(), ii.type().stringValue());
               boolean leaf = !owner.startsWith(pkg) || leaves.stream().anyMatch(owner::startsWith);
               if (leaf) { leafCalls.putIfAbsent(target.toString(), m); continue; }
               for (MethodRef t : targets(target, ii.opcode())) {
                  if (excluded(t.owner())) continue;
                  if (!parent.containsKey(t)) { parent.put(t, m); queue.add(t); }
               }
            } else if (ce instanceof InvokeDynamicInstruction idi) {
               // lambdas: walk the synthetic lambda$ body in the same class
               for (var barg : idi.bootstrapArgs()) {
                  if (barg instanceof java.lang.constant.DirectMethodHandleDesc mh && mh.owner().descriptorString().startsWith("L" + pkg)) {
                     String own = mh.owner().descriptorString();
                     own = own.substring(1, own.length() - 1);
                     MethodRef t = new MethodRef(own, mh.methodName(), mh.lookupDescriptor());
                     if (!parent.containsKey(t)) { parent.put(t, m); queue.add(t); }
                  }
               }
            }
         }
      }

      System.out.println("## reachable methods: " + visited.size());
      System.out.println("\n## static fields reached (owner.field  mode  shortest chain from an entry; ~ = instance field write with --putfield)");
      for (var e : statics.entrySet()) {
         System.out.println(e.getKey() + "  " + e.getValue() + "  via " + chain(staticWhere.get(e.getKey()), parent));
      }
      System.out.println("\n## calls into leaf/library classes (not walked)");
      for (var e : leafCalls.entrySet()) {
         System.out.println(e.getKey() + "  from " + e.getValue());
      }
   }

   static MethodModel resolve(MethodRef m) {
      String owner = m.owner();
      while (owner != null) {
         MethodModel mm = methods.get(new MethodRef(owner, m.name(), m.desc()));
         if (mm != null) return mm;
         ClassModel cm = classes.get(owner);
         if (cm == null) return null;
         owner = cm.superclass().map(s -> s.asInternalName()).orElse(null);
      }
      return null;
   }

   /** Declared target plus every override in subtypes for virtual/interface dispatch. */
   static List<MethodRef> targets(MethodRef t, Opcode op) {
      List<MethodRef> out = new ArrayList<>();
      out.add(t);
      if (op == Opcode.INVOKEVIRTUAL || op == Opcode.INVOKEINTERFACE) {
         ArrayDeque<String> q = new ArrayDeque<>(subtypes.getOrDefault(t.owner(), List.of()));
         Set<String> seen = new HashSet<>();
         while (!q.isEmpty()) {
            String s = q.poll();
            if (!seen.add(s)) continue;
            MethodRef o = new MethodRef(s, t.name(), t.desc());
            if (methods.containsKey(o)) out.add(o);
            q.addAll(subtypes.getOrDefault(s, List.of()));
         }
      }
      return out;
   }

   static String chain(MethodRef m, Map<MethodRef, MethodRef> parent) {
      List<String> parts = new ArrayList<>();
      for (MethodRef c = m; c != null; c = parent.get(c)) parts.add(0, c.owner().substring(c.owner().lastIndexOf('/') + 1) + "." + c.name());
      return String.join(" > ", parts);
   }
}
