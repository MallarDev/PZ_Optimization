package pzopt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.Styles.Style;
import zombie.core.opengl.GLState;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.TextureDraw;
import zombie.input.Mouse;
import zombie.ui.UIElement;
import zombie.ui.UIElementInterface;
import zombie.ui.UIManager;

/**
 * uiRetained (2026-09-30, the UI snappiness pass): a retained UI. Stock runs every top-level UI element's Lua
 * prerender / render (and every child's) uiRenderFPS times a second to rebuild the whole UI texture, although most
 * elements draw exactly the same commands as last time (pzopt.UiProfile's "same" count, once the stale a/b/c fields of
 * pooled quads are left out of the comparison). This records each top-level element's draw commands when its Lua runs
 * ("fresh") and, on UI frames where nothing can have changed it, copies the recorded commands into the UI render state
 * instead ("replay"): the render thread gets the same command stream, the game thread skips the Lua.
 *
 * <p>An element renders fresh when: it was never recorded or drew something that cannot be replayed (models, generic
 * drawers such as the world map, shader starts with per-frame data, FBO switches, probes); any mouse button, the wheel,
 * a key or an activated controller changed this frame (then every element is fresh and all back-offs reset); the mouse
 * moves over it (at most uiHoverHz a second) or leaves it; it moved, resized or changed its visible height; it is an
 * inventory page and the Lua flag ISInventoryPage.renderDirty is set; or its timer is due. The timer is the stock rate
 * (uiRenderFPS) while its output keeps changing and doubles with every fresh render that drew exactly the previous
 * commands, up to uiRetainedMaxMs. Elements whose fresh render costs under uiRetainedCheapUs stay at the stock rate.
 *
 * <p>UI frames: at least the stock rate (so world text, tooltips and OnPre/PostUIDraw drawing keep stock timing), plus
 * immediately on the frame of any input that makes an element fresh (uiInstant): stock waited for the next 60 Hz slot.
 *
 * <p>Every top-level element starts with GLState.startFrame() (every cached GL state dirty), so its recorded stream sets
 * all the state it relies on itself and does not depend on which elements ran fresh before it.
 *
 * <p>devUiRetainedCheck: replays are rendered fresh anyway and their commands compared with the recording; a difference is
 * a stale replay (counted, logged as {@code S <name> age_ms} in pzopt-ui.out); the schedule is unchanged by the check.
 */
public final class UiRetained {
   private static final class Rec {
      TextureDraw[] cmds = new TextureDraw[0];
      Style[] styles = new Style[0];
      int n;
      boolean recorded;
      boolean replayable;
      long hash;
      int streak;
      long lastChangeNs;
      long nextFreshNs;
      long lastFreshNs;
      long lastHoverNs;
      long costNs;
      double gx;
      double gy;
      double gw;
      double gh;
      double gmax;
      float bx0;
      float by0;
      float bx1;
      float by1;
      boolean hovered;
      long seenFrame;
      boolean inventory;
      String name;
      boolean child;
      int checkFrom = -1;
      se.krka.kahlua.j2se.KahluaTableImpl table;
      int writes;
      String mod; // uiRetainedMods off: the mod whose function the element (or a class it derives from) carries, else null
      boolean modded; // it or an element drawn inside its last fresh render is mod-drawn: rendered at the stock rate
   }

   private static final IdentityHashMap<UIElementInterface, Rec> recs = new IdentityHashMap<>();
   private static boolean active;
   private static long frameNo;
   private static long now;
   private static int mx;
   private static int my;
   private static boolean mouseMoved;
   private static boolean freshAll;
   private static boolean inventoryDirty;
   private static boolean padActive;
   private static long nextBaseNs;
   private static long nextCleanupNs;
   /** GameKeyboard: counts key state changes (any key pressed or released); the harness UI rig adds its emulated input. */
   public static int keyEvents;
   private static int uiEvents;
   private static int lastUiEvents;
   private static int statUiEvents;
   private static long lastInputNs;

   /** Lua events after which UI elements show something else (inventory, equipment, clothing, skills, health). */
   public static boolean isUiEvent(String name) {
      switch (name) {
         case "OnContainerUpdate":
         case "OnClothingUpdated":
         case "OnEquipPrimary":
         case "OnEquipSecondary":
         case "AddXP":
         case "LevelPerk":
         case "OnPlayerGetDamage":
         case "OnRefreshInventoryWindowContainers":
            return true;
         default:
            return false;
      }
   }

   /** zombie.Lua.Event.trigger of a UI event (any thread that raises Lua events: the game thread). */
   public static void uiEvent() {
      uiEvents++;
   }
   private static int lastKeyEvents;
   private static boolean checking;
   private static int statFresh;
   private static int statReplay;
   private static int statChecked;
   private static int statStale;
   private static int statUiFrames;
   private static int statInputFrames;

   private UiRetained() {
   }

   private static long baseNs() {
      return 1_000_000_000L / Math.max(1, Core.getInstance().getOptionUIRenderFPS());
   }

   /**
    * GameWindow, right after UIManager.update: read this frame's input and decide whether this frame renders the UI
    * (Core.StartFrameUI reads the accumulator we set).
    */
   public static void decideFrame() {
      if (!Config.UI_RETAINED || !UIManager.useUiFbo || !Overrides.enabled()) {
         if (active) {
            active = false;
            recs.clear();
         }
         return;
      }
      active = true;
      checking = Config.DEV_UI_RETAINED_CHECK;
      now = System.nanoTime();
      frameNo++;
      int x = Mouse.getXA();
      int y = Mouse.getYA();
      mouseMoved = x != mx || y != my;
      mx = x;
      my = y;
      boolean click = Mouse.getWheelState() != 0;
      for (int b = 0; b < 3 && !click; b++) {
         click = Mouse.isButtonPressed(b) || Mouse.isButtonReleased(b);
      }
      boolean key = keyEvents != lastKeyEvents;
      lastKeyEvents = keyEvents;
      boolean event = uiEvents != lastUiEvents;
      statUiEvents += uiEvents - lastUiEvents;
      lastUiEvents = uiEvents;
      padActive = GameWindow.activatedJoyPad != null;
      freshAll = click || key || event;
      if (click || key) {
         lastInputNs = now;
      }
      inventoryDirty = luaFlag("ISInventoryPage", "renderDirty");
      boolean due = now >= nextBaseNs;
      boolean input = freshAll || inventoryDirty;
      if (!due && !input) {
         input = anyInputFresh();
      }
      if (due || input) {
         Core.getInstance().uiRenderAccumulator = 1.0e6F;
         if (due) {
            nextBaseNs = now + baseNs();
         }
         if (!due) {
            statInputFrames++;
         }
         statUiFrames++;
      } else {
         Core.getInstance().uiRenderAccumulator = -1.0e6F;
      }
      if (now >= nextCleanupNs) {
         cleanup();
      }
   }

   /** Does the mouse make some recorded element fresh this frame (hover in / out at uiHoverHz, capture)? */
   private static boolean anyInputFresh() {
      if (!mouseMoved) {
         return false;
      }
      ArrayList<UIElementInterface> ui = UIManager.getUI();
      for (int i = 0; i < ui.size(); i++) {
         UIElementInterface e = ui.get(i);
         Rec r = recs.get(e);
         if (r == null || !r.recorded) {
            continue;
         }
         boolean inside = inside(e, r);
         if (inside != r.hovered) {
            return true;
         }
         if ((inside || e.isCapture()) && now - r.lastHoverNs >= hoverNs()) {
            return true;
         }
      }
      return false;
   }

   private static long hoverNs() {
      return 1_000_000_000L / Math.max(1, Config.UI_HOVER_HZ);
   }

   private static boolean inside(UIElementInterface e, Rec r) {
      if (mx >= r.bx0 && mx < r.bx1 && my >= r.by0 && my < r.by1) {
         return true;
      }
      double ex = absX(e);
      double ey = absY(e);
      return mx >= ex && my >= ey && mx < ex + e.getWidth() && my < ey + e.getHeight();
   }

   /** Screen position (a child's getX is relative to its parent; its recorded commands are absolute). */
   private static double absX(UIElementInterface e) {
      return e instanceof UIElement u ? u.getAbsoluteX() : e.getX();
   }

   private static double absY(UIElementInterface e) {
      return e instanceof UIElement u ? u.getAbsoluteY() : e.getY();
   }

   private static boolean luaFlag(String table, String key) {
      try {
         Object t = LuaManager.env == null ? null : LuaManager.env.rawget(table);
         if (t instanceof KahluaTable kt) {
            return kt.rawget(key) == Boolean.TRUE;
         }
      } catch (Throwable ignored) {
      }
      return false;
   }

   private static GenericSpriteRenderState state() {
      return SpriteRenderer.instance.states.getPopulating().stateUi;
   }

   private static Rec rec(UIElementInterface e) {
      Rec r = recs.get(e);
      if (r == null) {
         r = new Rec();
         r.name = UiProfile.name(e);
         r.inventory = "ISInventoryPage".equals(r.name);
         r.mod = modDrawing(e);
         r.modded = r.mod != null;
         recs.put(e, r);
      }
      r.seenFrame = frameNo;
      return r;
   }

   public static boolean active() {
      return active;
   }

   /**
    * UIManager.render, for each top-level element: true when its recorded commands were copied into the UI render state
    * (the caller skips element.render()); false when the caller must render it fresh between freshBegin / freshEnd.
    */
   public static boolean replay(UIElementInterface e) {
      if (!active) {
         return false;
      }
      GLState.startFrame();
      if (!e.isVisible()) {
         Rec hidden = recs.get(e);
         if (hidden != null) {
            hidden.recorded = false; // shown again later: render it fresh, its old commands may be outdated
         }
         return false;
      }
      Rec r = rec(e);
      boolean inside = inside(e, r);
      boolean fresh = needsFresh(e, r, inside);
      r.hovered = inside;
      if (fresh) {
         return false;
      }
      if (checking) {
         r.checkFrom = state().numSprites;
         return false;
      }
      copyOut(r);
      statReplay++;
      return true;
   }

   /**
    * UIElement.render of a child element (after its own visibility / clipping checks, so it would draw): true when its
    * recorded commands were copied in and the child's Lua and its subtree are skipped (uiRetainedChildren).
    */
   public static boolean replayChild(UIElement e) {
      if (!active || !Config.UI_RETAINED_CHILDREN) {
         return false;
      }
      GLState.startFrame();
      Rec r = rec(e);
      r.child = true;
      boolean inside = inside(e, r);
      boolean fresh = needsFresh(e, r, inside);
      r.hovered = inside;
      if (fresh) {
         return false;
      }
      if (checking) {
         r.checkFrom = state().numSprites;
         return false;
      }
      copyOut(r);
      statReplay++;
      return true;
   }

   /** UIElement.render of a child rendered fresh: start of its commands (-1 when not retaining children). */
   public static int childBegin() {
      return active && Config.UI_RETAINED_CHILDREN ? freshBegin() : -1;
   }

   private static boolean needsFresh(UIElementInterface e, Rec r, boolean inside) {
      if (!r.recorded || !r.replayable || freshAll || padActive || r.modded) {
         return true; // r.modded: a mod draws in it; its drawing may change with state the replay cannot see (stock rate)
      }
      if (r.table != null && r.table.pzoptWrites != r.writes) {
         return true; // its Lua table changed since it was recorded (a field set by its update, its parent, a handler)
      }
      if (r.streak < Config.UI_RETAINED_STREAK || now - r.lastChangeNs < Config.UI_RETAINED_STATIC_MS * 1_000_000L) {
         return true; // changed recently (an animation, a fade): fresh on every UI frame
      }
      if (now - lastInputNs < Config.UI_RETAINED_SETTLE_MS * 1_000_000L) {
         return true; // just after input its consequences arrive (a transfer ends, an item gets equipped): stock rate
      }
      if (r.costNs < (r.child ? Config.UI_RETAINED_CHILD_CHEAP_US : Config.UI_RETAINED_CHEAP_US) * 1000L) {
         return true; // cheap (Java HUD pieces such as the animated moodles): stock behaviour, fresh on every UI frame
      }
      if (r.inventory && inventoryDirty) {
         return true;
      }
      if (absX(e) != r.gx || absY(e) != r.gy || e.getWidth() != r.gw || e.getHeight() != r.gh || e.getMaxDrawHeight() != r.gmax) {
         return true;
      }
      if (now >= r.nextFreshNs) {
         return true;
      }
      if (inside != r.hovered) {
         return true;
      }
      return (inside && mouseMoved || e.isCapture()) && now - r.lastHoverNs >= hoverNs();
   }

   public static int freshBegin() {
      if (!active) {
         return -1;
      }
      GLState.startFrame();
      spanMods.add(Boolean.FALSE);
      return state().numSprites;
   }

   public static void freshEnd(UIElementInterface e, int from, long costNs) {
      if (!active || from < 0) {
         return;
      }
      boolean innerMods = !spanMods.isEmpty() && spanMods.remove(spanMods.size() - 1);
      GenericSpriteRenderState st = state();
      int to = st.numSprites;
      if (!e.isVisible()) {
         return;
      }
      Rec r = rec(e);
      r.modded = r.mod != null || innerMods;
      if (r.modded && !spanMods.isEmpty()) {
         spanMods.set(spanMods.size() - 1, Boolean.TRUE); // the element whose fresh render drew this one is mod-drawn too
      }
      long h = hash(st.sprite, from, to);
      if (checking && r.checkFrom == from) {
         // devUiRetainedCheck: this render stood in for a replay; compare, keep the schedule as it was
         r.checkFrom = -1;
         statChecked++;
         if (h != r.hash) {
            statStale++;
            UiProfile.staleReplay(r.name, (now - r.lastFreshNs) / 1_000_000L);
            record(r, st, from, to, h);
            r.streak = 0;
            r.nextFreshNs = Math.min(r.nextFreshNs, now + baseNs());
         }
         return;
      }
      r.checkFrom = -1;
      statFresh++;
      boolean same = r.recorded && h == r.hash;
      record(r, st, from, to, h);
      r.streak = same ? r.streak + 1 : 0;
      if (!same) {
         r.lastChangeNs = now;
      }
      r.costNs = costNs;
      r.lastFreshNs = now;
      if (r.hovered || e.isCapture()) {
         r.lastHoverNs = now;
      }
      if (freshAll) {
         r.streak = 0;
      }
      long base = baseNs();
      long interval = base;
      if (r.streak > 0 && costNs >= Config.UI_RETAINED_CHEAP_US * 1000L) {
         interval = Math.min(Config.UI_RETAINED_MAX_MS * 1_000_000L, base << Math.min(r.streak, 6));
      }
      r.nextFreshNs = now + Math.max(base, interval);
      r.gx = absX(e);
      r.gy = absY(e);
      if (e instanceof UIElement u && u.getTable() instanceof se.krka.kahlua.j2se.KahluaTableImpl t) {
         r.table = t;
         r.writes = t.pzoptWrites; // after its own render: what it wrote while rendering does not count
      }
      r.gw = e.getWidth();
      r.gh = e.getHeight();
      r.gmax = e.getMaxDrawHeight();
   }

   /** After the element loop: the tooltip / paused text / fade overlay after it start from a clean GL state cache. */
   public static void loopEnd() {
      if (active) {
         GLState.startFrame();
      }
      spanMods.clear(); // a render that threw between freshBegin and freshEnd leaves its entry behind
   }

   // ── elements a mod draws (uiRetainedMods off, mod compatibility 2026-10-01) ─────────────────────────────────────
   //
   // A mod's drawing may follow state the replay schedule cannot see (its own Lua locals, Java getters), so an element
   // carrying a mod's drawing function (render* / prerender* / postrender*, in its own table or a class up its __index chain:
   // a mod replacing ISInventoryPane.renderdetails makes every inventory pane mod-drawn) renders fresh at the stock rate.
   // So does a vanilla window whose last fresh render drew a mod-drawn child (spanMods: one flag per open freshBegin).
   // Other mod functions do not count: Inventory Tetris adds helpers to ISUIElement, the base of every element, and a
   // button's onclick from a mod draws nothing (the first matrix run, 2026-10-01, had the whole UI at the stock rate).

   private static final ArrayList<Boolean> spanMods = new ArrayList<>();
   private static final IdentityHashMap<KahluaTable, Object[]> tableMods = new IdentityHashMap<>(); // {writes, mod id or ""}
   private static final java.util.HashSet<String> loggedMods = new java.util.HashSet<>();

   /** The mod whose function this element carries, or null (also null with uiRetainedMods on). */
   private static String modDrawing(UIElementInterface e) {
      if (Config.UI_RETAINED_MODS || !(e instanceof UIElement u) || !(u.getTable() instanceof KahluaTable t)) {
         return null;
      }
      KahluaTable cur = t;
      for (int depth = 0; cur != null && depth < 16; depth++) {
         String mod = tableMod(cur, depth == 0);
         if (mod != null) {
            if (loggedMods.size() < 64 && loggedMods.add(UiProfile.name(e) + mod)) {
               Log.info("ui retained: " + UiProfile.name(e) + " carries a function of " + mod + "; rendered at the stock rate (uiRetainedMods=false)");
            }
            return mod;
         }
         KahluaTable meta = cur.getMetatable();
         Object index = meta != null ? meta.rawget("__index") : null;
         cur = index instanceof KahluaTable k && k != cur ? k : null;
      }
      return null;
   }

   /**
    * A function of an element's render path: render / prerender / postrender and their helpers (renderdetails ...). Not
    * the draw primitives (drawText, drawTexture...): they draw what they are given, so their output follows the element's
    * own inputs (Inventory Tetris adds drawTextureCenteredAndSquare to ISUIElement, which no vanilla element calls).
    */
   static boolean drawing(String name) {
      String n = name.toLowerCase(java.util.Locale.ROOT);
      return n.startsWith("render") || n.startsWith("prerender") || n.startsWith("postrender");
   }

   /** The first mod-defined drawing function of a table; class tables are cached until a write (a function replaced). */
   private static String tableMod(KahluaTable t, boolean instance) {
      int writes = t instanceof se.krka.kahlua.j2se.KahluaTableImpl ti ? ti.pzoptWrites : -1;
      Object[] cached = instance ? null : tableMods.get(t);
      if (cached != null && (Integer) cached[0] == writes) {
         return ((String) cached[1]).isEmpty() ? null : (String) cached[1];
      }
      String found = "";
      se.krka.kahlua.vm.KahluaTableIterator it = t.iterator();
      while (it.advance()) {
         Object v = it.getValue();
         if (v instanceof se.krka.kahlua.vm.LuaClosure && it.getKey() instanceof String k && drawing(k) && LuaOrigin.fromMod(v)) {
            String o = LuaOrigin.ofFunction(v);
            found = o.startsWith("mod:") ? o.substring(4) : o;
            break;
         }
      }
      if (!instance) {
         tableMods.put(t, new Object[] {writes, found});
      }
      return found.isEmpty() ? null : found;
   }

   private static void record(Rec r, GenericSpriteRenderState st, int from, int to, long h) {
      int n = Math.max(0, to - from);
      if (r.cmds.length < n) {
         int cap = Math.max(n, r.cmds.length * 3 / 2 + 8);
         TextureDraw[] c = new TextureDraw[cap];
         System.arraycopy(r.cmds, 0, c, 0, r.cmds.length);
         r.cmds = c;
         Style[] s = new Style[cap];
         System.arraycopy(r.styles, 0, s, 0, r.styles.length);
         r.styles = s;
      }
      boolean replayable = true;
      float bx0 = Float.MAX_VALUE;
      float by0 = Float.MAX_VALUE;
      float bx1 = -Float.MAX_VALUE;
      float by1 = -Float.MAX_VALUE;
      for (int i = 0; i < n; i++) {
         TextureDraw src = st.sprite[from + i];
         if (src == null || !replayable(src)) {
            replayable = false;
            continue;
         }
         TextureDraw dst = r.cmds[i];
         if (dst == null) {
            dst = new TextureDraw();
            r.cmds[i] = dst;
         }
         copy(src, dst);
         r.styles[i] = st.style[from + i];
         if (src.type == TextureDraw.Type.glDraw) {
            bx0 = Math.min(bx0, Math.min(Math.min(src.x0, src.x1), Math.min(src.x2, src.x3)));
            by0 = Math.min(by0, Math.min(Math.min(src.y0, src.y1), Math.min(src.y2, src.y3)));
            bx1 = Math.max(bx1, Math.max(Math.max(src.x0, src.x1), Math.max(src.x2, src.x3)));
            by1 = Math.max(by1, Math.max(Math.max(src.y0, src.y1), Math.max(src.y2, src.y3)));
         }
      }
      r.n = n;
      r.replayable = replayable;
      r.recorded = true;
      r.hash = h;
      r.bx0 = bx0;
      r.by0 = by0;
      r.bx1 = bx1;
      r.by1 = by1;
   }

   private static void copyOut(Rec r) {
      GenericSpriteRenderState st = state();
      for (int i = 0; i < r.n; i++) {
         st.CheckSpriteSlots();
         copy(r.cmds[i], st.sprite[st.numSprites]);
         st.style[st.numSprites] = r.styles[i];
         st.numSprites++;
      }
   }

   static boolean replayable(TextureDraw d) {
      if (d.drawer != null || d.future != null || d.imDrawData != null || d.probe != null) {
         return false;
      }
      switch (d.type) {
         case glDraw:
         case glStencilFunc:
         case glAlphaFunc:
         case glStencilOp:
         case glEnable:
         case glDisable:
         case glColorMask:
         case glStencilMask:
         case glClear:
         case glBlendFunc:
         case glBlendFuncSeparate:
         case glBlendEquation:
         case glIgnoreStyles:
         case glClearColor:
         case glDepthMask:
         case glDepthFunc:
         case glClearDepth:
         case glTexParameteri:
         case glBind:
         case glViewport:
         case glLoadIdentity:
            return true;
         case StartShader:
            return d.a == 0;
         default:
            return false;
      }
   }

   /**
    * The draw commands' identity for "did this element draw the same as last time": a quad by its geometry, texture
    * coordinates, textures and colours (its a/b/c/d/f1 are leftovers from the pooled object's last use as a state
    * command), a state command by its arguments.
    */
   static final String[] QUAD_FIELDS = {"tex", "tex1", "tex2", "x0", "y0", "x1", "y1", "x2", "y2", "x3", "y3", "u0", "v0", "u2", "v2",
      "col0", "col1", "col2", "col3", "flipped", "useAttribArray"};

   /** The argument names a state command of this type carries (TextureDraw's builders; the rest are leftovers). */
   static String[] stateFields(TextureDraw.Type t) {
      switch (t) {
         case glStencilFunc:
         case glStencilOp:
         case glTexParameteri:
            return new String[]{"a", "b", "c"};
         case glEnable:
         case glDisable:
         case glClear:
         case glDepthFunc:
         case glStencilMask:
         case glBlendEquation:
         case glIgnoreStyles:
         case StartShader:
            return new String[]{"a"};
         case glBlendFunc:
            return new String[]{"a", "b"};
         case glBlendFuncSeparate:
            return new String[]{"a", "b", "c", "d"};
         case glAlphaFunc:
            return new String[]{"a", "f1"};
         case glColorMask:
            return new String[]{"a", "b", "c", "x0"};
         case glClearColor:
            return new String[]{"col0", "col1", "col2", "col3"};
         case glClearDepth:
            return new String[]{"u0"};
         default:
            return new String[]{"a", "b", "c", "d", "f1"};
      }
   }

   private static long field(TextureDraw d, String f) {
      switch (f) {
         case "a": return d.a;
         case "b": return d.b;
         case "c": return d.c;
         case "d": return d.d;
         case "f1": return Float.floatToRawIntBits(d.f1);
         case "x0": return Float.floatToRawIntBits(d.x0);
         case "u0": return Float.floatToRawIntBits(d.u0);
         case "col0": return d.col0;
         case "col1": return d.col1;
         case "col2": return d.col2;
         case "col3": return d.col3;
         default: return 0L;
      }
   }

   /** The values the hash covers for one command, after its type (QUAD_FIELDS for a quad, stateFields otherwise). */
   static long[] key(TextureDraw d) {
      if (d.type == TextureDraw.Type.glDraw) {
         return new long[]{d.type.ordinal(), System.identityHashCode(d.tex), System.identityHashCode(d.tex1), System.identityHashCode(d.tex2),
            Float.floatToRawIntBits(d.x0), Float.floatToRawIntBits(d.y0), Float.floatToRawIntBits(d.x1), Float.floatToRawIntBits(d.y1),
            Float.floatToRawIntBits(d.x2), Float.floatToRawIntBits(d.y2), Float.floatToRawIntBits(d.x3), Float.floatToRawIntBits(d.y3),
            Float.floatToRawIntBits(d.u0), Float.floatToRawIntBits(d.v0), Float.floatToRawIntBits(d.u2), Float.floatToRawIntBits(d.v2),
            d.col0, d.col1, d.col2, d.col3, d.flipped ? 1 : 0, d.useAttribArray};
      }
      String[] f = stateFields(d.type);
      long[] k = new long[f.length + 1];
      k[0] = d.type.ordinal();
      for (int i = 0; i < f.length; i++) {
         k[i + 1] = field(d, f[i]);
      }
      return k;
   }

   /**
    * The draw commands' identity for "did this element draw the same as last time": a quad by its geometry, texture
    * coordinates, textures and colours, a state command by the arguments its type carries (pooled TextureDraws keep
    * the fields of their previous use, e.g. a quad's a/b/c or a stencil mask's b/c).
    */
   static long hash(TextureDraw[] sprites, int from, int to) {
      long h = 1125899906842597L;
      for (int i = from; i < to; i++) {
         TextureDraw d = sprites[i];
         if (d == null) {
            continue;
         }
         h = 31L * h + d.type.ordinal();
         if (d.type == TextureDraw.Type.glDraw) {
            h = 31L * h + System.identityHashCode(d.tex);
            h = 31L * h + System.identityHashCode(d.tex1);
            h = 31L * h + System.identityHashCode(d.tex2);
            h = 31L * h + Float.floatToRawIntBits(d.x0);
            h = 31L * h + Float.floatToRawIntBits(d.y0);
            h = 31L * h + Float.floatToRawIntBits(d.x1);
            h = 31L * h + Float.floatToRawIntBits(d.y1);
            h = 31L * h + Float.floatToRawIntBits(d.x2);
            h = 31L * h + Float.floatToRawIntBits(d.y2);
            h = 31L * h + Float.floatToRawIntBits(d.x3);
            h = 31L * h + Float.floatToRawIntBits(d.y3);
            h = 31L * h + Float.floatToRawIntBits(d.u0);
            h = 31L * h + Float.floatToRawIntBits(d.v0);
            h = 31L * h + Float.floatToRawIntBits(d.u2);
            h = 31L * h + Float.floatToRawIntBits(d.v2);
            h = 31L * h + d.col0;
            h = 31L * h + d.col1;
            h = 31L * h + d.col2;
            h = 31L * h + d.col3;
            h = 31L * h + (d.flipped ? 1 : 0);
            h = 31L * h + d.useAttribArray;
         } else {
            switch (d.type) {
               case glStencilFunc:
               case glStencilOp:
               case glTexParameteri:
                  h = 31L * (31L * (31L * h + d.a) + d.b) + d.c;
                  break;
               case glEnable:
               case glDisable:
               case glClear:
               case glDepthFunc:
               case glStencilMask:
               case glBlendEquation:
               case glIgnoreStyles:
               case StartShader:
                  h = 31L * h + d.a;
                  break;
               case glBlendFunc:
                  h = 31L * (31L * h + d.a) + d.b;
                  break;
               case glBlendFuncSeparate:
                  h = 31L * (31L * (31L * (31L * h + d.a) + d.b) + d.c) + d.d;
                  break;
               case glAlphaFunc:
                  h = 31L * (31L * h + d.a) + Float.floatToRawIntBits(d.f1);
                  break;
               case glColorMask:
                  h = 31L * (31L * (31L * (31L * h + d.a) + d.b) + d.c) + Float.floatToRawIntBits(d.x0);
                  break;
               case glClearColor:
                  h = 31L * (31L * (31L * (31L * h + d.col0) + d.col1) + d.col2) + d.col3;
                  break;
               case glClearDepth:
                  h = 31L * h + Float.floatToRawIntBits(d.u0);
                  break;
               default:
                  h = 31L * (31L * (31L * (31L * (31L * h + d.a) + d.b) + d.c) + d.d) + Float.floatToRawIntBits(d.f1);
            }
         }
      }
      return h;
   }

   static void copy(TextureDraw s, TextureDraw d) {
      d.type = s.type;
      d.flipped = s.flipped;
      d.a = s.a;
      d.b = s.b;
      d.f1 = s.f1;
      d.vars = s.vars;
      d.c = s.c;
      d.d = s.d;
      d.col0 = s.col0;
      d.col1 = s.col1;
      d.col2 = s.col2;
      d.col3 = s.col3;
      d.x0 = s.x0;
      d.x1 = s.x1;
      d.x2 = s.x2;
      d.x3 = s.x3;
      d.y0 = s.y0;
      d.y1 = s.y1;
      d.y2 = s.y2;
      d.y3 = s.y3;
      d.u0 = s.u0;
      d.u1 = s.u1;
      d.u2 = s.u2;
      d.u3 = s.u3;
      d.v0 = s.v0;
      d.v1 = s.v1;
      d.v2 = s.v2;
      d.v3 = s.v3;
      d.z = s.z;
      d.chunkDepth = s.chunkDepth;
      d.tex = s.tex;
      d.tex1 = s.tex1;
      d.tex2 = s.tex2;
      d.useAttribArray = s.useAttribArray;
      d.tex1U0 = s.tex1U0;
      d.tex1U1 = s.tex1U1;
      d.tex1U2 = s.tex1U2;
      d.tex1U3 = s.tex1U3;
      d.tex1V0 = s.tex1V0;
      d.tex1V1 = s.tex1V1;
      d.tex1V2 = s.tex1V2;
      d.tex1V3 = s.tex1V3;
      d.tex1Col0 = s.tex1Col0;
      d.tex1Col1 = s.tex1Col1;
      d.tex1Col2 = s.tex1Col2;
      d.tex1Col3 = s.tex1Col3;
      d.tex2U0 = s.tex2U0;
      d.tex2U1 = s.tex2U1;
      d.tex2U2 = s.tex2U2;
      d.tex2U3 = s.tex2U3;
      d.tex2V0 = s.tex2V0;
      d.tex2V1 = s.tex2V1;
      d.tex2V2 = s.tex2V2;
      d.tex2V3 = s.tex2V3;
      d.singleCol = s.singleCol;
      d.imDrawData = null;
      d.probe = null;
      d.drawer = null;
      d.future = null;
      d.pzoptShadowTile = s.pzoptShadowTile;
      d.pzoptShadowX = s.pzoptShadowX;
      d.pzoptShadowY = s.pzoptShadowY;
      d.pzoptShadowZ = s.pzoptShadowZ;
      d.pzoptShadowHalf = s.pzoptShadowHalf;
      d.pzoptLampN = s.pzoptLampN;
   }

   /** Forget elements not rendered for 2 s (closed windows, dropped tooltips) and write the per-second counters. */
   private static void cleanup() {
      nextCleanupNs = now + 1_000_000_000L;
      Iterator<Map.Entry<UIElementInterface, Rec>> it = recs.entrySet().iterator();
      while (it.hasNext()) {
         Rec r = it.next().getValue();
         if (frameNo - r.seenFrame > 600) {
            it.remove();
         }
      }
      UiProfile.retainedStats(statUiFrames, statInputFrames, statFresh, statReplay, statChecked, statStale, recs.size(), statUiEvents);
      statUiEvents = 0;
      statUiFrames = 0;
      statInputFrames = 0;
      statFresh = 0;
      statReplay = 0;
      statChecked = 0;
      statStale = 0;
   }
}
