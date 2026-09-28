# tools/

Standalone Java programs, compiled ad hoc (not part of `scripts/build.sh`).

| File | Use |
|---|---|
| `JfrSamples.java` | dumps a run's `pzopt.jfr` execution samples and wait events as lines for `harness/attribute.py` and `harness/waits.py` |
| `GlfwSwapProbe.java` | tests overlay hooks (MangoHud preload, swap hand-off) in seconds using the game's own LWJGL, without launching the game. MangoHud ignores processes named `java`, so run it under a copied JDK launcher (`/tmp/pjdk/bin/pzprobe` pattern) |
| `ShaderRegs.java` | compiles a vertex + fragment shader pair in a hidden GLFW window (compile only) and prints the fragment program's register use from NVIDIA's program binary (`TEMP R0..Rn` of its assembly): `java -cp "<game>/*" tools/ShaderRegs.java <vert> <frag>... [--dump dir]`; pairs with `devSwayDumpDir` (foliage sway's twins) |
| `StaticAudit.java` | static-state audit of the recalc classes (`docs/recalc-static-audit.md`) |
