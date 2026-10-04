package pzopt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import zombie.characters.IsoPlayer;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;

/**
 * Harness scene {@code explore=mirror director=jev} (2026-10-04, the maintainer: "use Jev to walk around all the mirrors in
 * the house" of their latest save, to find every artifact in the reflections): every mirror tile of the building the save
 * starts in (all levels; wall mirrors, the map's mirror overlays on walls, cabinets, dressers) is listed, and Jev walks the
 * player on foot (the movement keys, never teleported; a grid path over the loaded squares with the staircases as edges,
 * doors opened on the way) from one mirror to the next. In front of each, a grid of stations: columns along the wall
 * ({@code mirror_cols}, half a square either side of the glass centre), rows out from it ({@code mirror_rows}, 1.0 / 1.8). At a
 * station Jev faces the mirror, turns his back to it, and once per mirror walks a small lap; then the next station, then
 * the next mirror. Done after the last one or at {@code mirror_secs} (10 + 25 s a mirror); {@code mirror_only=N} walks only
 * the N-th mirror (short fix iterations).
 *
 * <p>The state goes to {@code Zomboid/pzopt-explore-state.json} every 0.3 s ({@code "scene": "mirror"}),
 * harness/explore-director.py answers {@code <seq> <action>} in {@code pzopt-explore-cmd.txt}: left, right, closer, back,
 * face_mirror, turn_around, circle, next_mirror, hold, done. Every station / mirror change and director action is logged
 * with its epoch ms ({@code harness: mirror walk:}) so a devCapture can be cut per mirror and station
 * (harness/mirrors/walkframes.py).
 */
final class MirrorWalk {
   private MirrorWalk() {
   }

   static final String[] ACTIONS = {"left", "right", "closer", "back", "face_mirror", "turn_around", "circle", "next_mirror", "hold", "done"};

   /** A mirror of the house and its stations. */
   private static final class M {
      int x, y, z;
      boolean north;
      float off;
      String name;
      boolean[][] usable, visited, blockedAt, faced, turned;
      boolean circled;
   }

   private static boolean on, finished;
   private static final ArrayList<M> mirrors = new ArrayList<>();
   private static float[] cols = {-1F, 0F, 1F}, rows = {0.8F, 1.5F, 2.2F};
   private static int mi, col, row, commands, commandSeq = -1, doorsOpened, stuckMarks;
   private static String command = "hold";
   private static long startNs, lastStateNs, lastCmdCheckNs, lastLogNs, actionNs, moveStartNs, lastPlanNs, lastProgressNs;
   private static float limitSecs = 300F, circleTurned, lastAngle, progressX, progressY;
   private static boolean angleValid, arrived, travelling;
   private static java.io.File stateFile, cmdFile;
   private static String building = "none";
   private static final java.util.HashSet<Long> blocked = new java.util.HashSet<>();

   static boolean active() {
      return on;
   }

   static boolean finished() {
      return finished;
   }

   static void worldReady(IsoPlayer p) {
      on = true;
      cols = floats(HarnessFlags.get("mirror_cols", "-0.5,0.5"));
      rows = floats(HarnessFlags.get("mirror_rows", "1.0,1.8"));
      java.io.File zd = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
      stateFile = new java.io.File(zd, "pzopt-explore-state.json");
      cmdFile = new java.io.File(zd, "pzopt-explore-cmd.txt");
      cmdFile.delete();
      stateFile.delete();
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
   }

   private static float[] floats(String s) {
      String[] a = s.split(",");
      float[] r = new float[a.length];
      for (int i = 0; i < a.length; i++) r[i] = Float.parseFloat(a[i].trim());
      return r;
   }

   // ---- the house's mirrors ----

   static void routeStart(IsoPlayer p) {
      startNs = System.nanoTime();
      moveStartNs = startNs;
      lastProgressNs = startNs;
      IsoCell cell = IsoWorld.instance.getCell();
      BuildingDef b = null;
      IsoGridSquare sq0 = p.getCurrentSquare();
      if (sq0 != null && sq0.getBuilding() != null) b = sq0.getBuilding().getDef();
      if (b == null) { // outdoors: the nearest building
         float best = Float.MAX_VALUE;
         for (BuildingDef d : IsoWorld.instance.getMetaGrid().getBuildings()) {
            float dx = Math.max(Math.max(d.getX() - p.getX(), p.getX() - d.getX2()), 0F), dy = Math.max(Math.max(d.getY() - p.getY(), p.getY() - d.getY2()), 0F);
            float dd = (float)Math.hypot(dx, dy);
            if (dd < best) {
               best = dd;
               b = d;
            }
         }
      }
      if (b == null) {
         Log.warn("harness: mirror walk: no building near the player; nothing to walk");
         finished = true;
         return;
      }
      building = b.getX() + "," + b.getY() + "-" + b.getX2() + "," + b.getY2();
      StringBuilder backs = new StringBuilder();
      for (int z = -1; z <= 7; z++) {
         for (int y = b.getY() - 1; y <= b.getY2() + 1; y++) {
            for (int x = b.getX() - 1; x <= b.getX2() + 1; x++) {
               IsoGridSquare sq = cell.getGridSquare(x, y, z);
               if (sq == null) continue;
               for (int i = 0; i < sq.getObjects().size(); i++) {
                  IsoObject o = sq.getObjects().get(i);
                  zombie.iso.sprite.IsoSprite spr = o.getSprite();
                  float[] info = Mirrors.mirrorInfo(spr);
                  if (info == null) {
                     zombie.iso.sprite.IsoSpriteInstance att = Mirrors.attachedMirror(o);
                     if (att != null) {
                        spr = att.getParentSprite();
                        info = Mirrors.mirrorInfo(spr);
                     }
                  }
                  if (info == null) {
                     // a mirror the camera sees the back of (Facing N / W) or without a glass mask: listed, not walked to
                     if (spr != null && spr.getProperties() != null && spr.getProperties().has("IsMirror")) {
                        backs.append(' ').append(spr.getName()).append('@').append(x).append(',').append(y).append(',').append(z)
                              .append(" facing ").append(spr.getProperties().get("Facing"));
                     }
                     if (o.getAttachedAnimSprite() != null) {
                        for (zombie.iso.sprite.IsoSpriteInstance a : o.getAttachedAnimSprite()) {
                           zombie.iso.sprite.IsoSprite ps = a == null ? null : a.getParentSprite();
                           if (ps != null && ps.getProperties() != null && ps.getProperties().has("IsMirror")) {
                              backs.append(' ').append(ps.getName()).append("(overlay)@").append(x).append(',').append(y).append(',').append(z)
                                    .append(" facing ").append(ps.getProperties().get("Facing"));
                           }
                        }
                     }
                     continue;
                  }
                  M m = new M();
                  m.x = x;
                  m.y = y;
                  m.z = z;
                  m.north = info[0] == 0F;
                  m.off = info[1];
                  m.name = spr.getName();
                  stations(cell, m);
                  mirrors.add(m);
               }
            }
         }
      }
      // mirror_only=N (1-based, in the walking order below): one mirror per run, for short fix iterations
      int only = Integer.parseInt(HarnessFlags.get("mirror_only", "0").trim());
      // walking order: nearest first from the player, then nearest from each mirror
      ArrayList<M> left = new ArrayList<>(mirrors);
      mirrors.clear();
      float ax = p.getX(), ay = p.getY(), az = p.getZ();
      while (!left.isEmpty()) {
         int bi = 0;
         for (int i = 1; i < left.size(); i++) {
            if (dist(left.get(i), ax, ay, az) < dist(left.get(bi), ax, ay, az)) bi = i;
         }
         M m = left.remove(bi);
         mirrors.add(m);
         ax = m.x;
         ay = m.y;
         az = m.z;
      }
      if (only > 0 && only <= mirrors.size()) {
         M keep = mirrors.get(only - 1);
         mirrors.clear();
         mirrors.add(keep);
      }
      String lim = HarnessFlags.get("mirror_secs", "").trim();
      limitSecs = lim.isEmpty() ? 10F + 25F * mirrors.size() : Float.parseFloat(lim);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < mirrors.size(); i++) {
         M m = mirrors.get(i);
         sb.append(String.format(Locale.ROOT, " #%d %s@%d,%d,%d %s (%d stations)", i + 1, m.name, m.x, m.y, m.z, m.north ? "north" : "west", count(m.usable)));
      }
      Log.info(String.format(Locale.ROOT, "harness: mirror walk: building %s, %d mirrors with glass the camera sees:%s; time limit %.0f s", building, mirrors.size(), sb, limitSecs));
      if (backs.length() > 0) Log.info("harness: mirror walk: mirrors seen from the back / without a glass mask (not reflecting):" + backs);
      if (mirrors.isEmpty()) {
         finished = true;
         return;
      }
      mi = 0;
      startTravel();
   }

   private static float dist(M m, float x, float y, float z) {
      return (float)Math.hypot(m.x - x, m.y - y) + Math.abs(m.z - z) * 8F;
   }

   private static void stations(IsoCell cell, M m) {
      int nc = cols.length, nr = rows.length;
      m.usable = new boolean[nc][nr];
      m.visited = new boolean[nc][nr];
      m.blockedAt = new boolean[nc][nr];
      m.faced = new boolean[nc][nr];
      m.turned = new boolean[nc][nr];
      IsoGridSquare front = cell.getGridSquare(m.x, m.y, m.z);
      for (int c = 0; c < nc; c++) {
         for (int r = 0; r < nr; r++) {
            float[] w = world(m, c, r);
            IsoGridSquare sq = cell.getGridSquare((int)Math.floor(w[0]), (int)Math.floor(w[1]), m.z);
            m.usable[c][r] = sq != null && sq.isFree(false) && !sq.HasStairs() && (front == null || front.getRoom() == null || sq.getRoom() == front.getRoom());
         }
      }
   }

   /** Station (c, r) of mirror m: c squares along the wall from the glass centre, rows[r] out from the glass. */
   private static float[] world(M m, int c, int r) {
      return m.north ? new float[] {m.x + 0.5F + cols[c], m.y + m.off + rows[r]} : new float[] {m.x + m.off + rows[r], m.y + 0.5F + cols[c]};
   }

   private static float mirrorAngle(M m) {
      return m.north ? 270F : 180F;
   }

   /** Walking to the current mirror: its usable station nearest the glass centre (the middle column, nearest row first). */
   private static void startTravel() {
      M m = mirrors.get(mi);
      int bc = -1, br = -1;
      float best = Float.MAX_VALUE;
      for (int c = 0; c < cols.length; c++) {
         for (int r = 0; r < rows.length; r++) {
            if (!m.usable[c][r] || m.blockedAt[c][r]) continue;
            float s = Math.abs(cols[c]) * 2F + rows[r];
            if (s < best) {
               best = s;
               bc = c;
               br = r;
            }
         }
      }
      if (bc < 0) { // no free square in front: the square of the mirror itself
         Log.info("harness: mirror walk: mirror " + (mi + 1) + " has no free station in front");
         col = cols.length / 2;
         row = 0;
         m.usable[col][row] = true;
      } else {
         col = bc;
         row = br;
      }
      travelling = true;
      arrived = false;
      pathLen = 0;
      moveStartNs = System.nanoTime();
      stationEvent("to mirror " + (mi + 1) + " " + m.name + " at " + m.x + "," + m.y + "," + m.z + ", station");
   }

   // ---- per frame ----

   static void tick(IsoPlayer p, long nowNs) {
      if (finished || mirrors.isEmpty()) return;
      if (nowNs - lastCmdCheckNs >= 100_000_000L) {
         lastCmdCheckNs = nowNs;
         readCommand(nowNs);
      }
      Showcase.releaseKeys();
      M m = mirrors.get(mi);
      float[] w = world(m, col, row);
      float dx = w[0] - p.getX(), dy = w[1] - p.getY();
      float dist = (float)Math.hypot(dx, dy);
      boolean sameLevel = (int)Math.floor(p.getZ() + 0.01F) == m.z;
      if (command.equals("done")) {
         if (!finished) {
            finished = true;
            int v = 0, u = 0;
            for (M k : mirrors) {
               v += count(k.visited);
               u += count(k.usable);
            }
            Log.info(String.format(Locale.ROOT, "harness: mirror walk: done at +%.1f s, mirror %d of %d, %d of %d stations visited, %d commands, %d doors opened, %d stuck marks epoch_ms=%d",
                  (nowNs - startNs) / 1e9, mi + 1, mirrors.size(), v, u, commands, doorsOpened, stuckMarks, System.currentTimeMillis()));
         }
         return;
      }
      if (!arrived) {
         if (travelling && (!sameLevel || dist > 1.2F)) {
            walkPath(p, (int)Math.floor(w[0]), (int)Math.floor(w[1]), m.z, nowNs);
            if (nowNs - moveStartNs > 60_000_000_000L) {
               Log.info(String.format(Locale.ROOT, "harness: mirror walk: mirror %d not reached in 60 s (at %.2f,%.2f,%.1f)", mi + 1, p.getX(), p.getY(), p.getZ()));
               arrived = true;
               travelling = false;
            }
         } else if (dist > 0.3F) {
            Showcase.moveKeys(dx, dy);
            if (nowNs - moveStartNs > (travelling ? 60_000_000_000L : 4_000_000_000L)) { // furniture in the way: blocked, back to the last station
               m.blockedAt[col][row] = true;
               Log.info(String.format(Locale.ROOT, "harness: mirror walk: mirror %d station %d,%d blocked at %.2f,%.2f", mi + 1, col, row, p.getX(), p.getY()));
               // stay where the furniture stopped him (walking back to the last station could block the same way)
               arrived = true;
               travelling = false;
               moveStartNs = nowNs;
            }
         } else {
            arrived = true;
            travelling = false;
            m.visited[col][row] = true;
            stationEvent("arrived at mirror " + (mi + 1) + " station");
         }
      } else {
         switch (command) {
            case "face_mirror" -> {
               p.setDirectionAngle(mirrorAngle(m));
               if (nowNs - actionNs > 800_000_000L && !m.faced[col][row]) {
                  m.faced[col][row] = true;
                  stationEvent("faced mirror " + (mi + 1) + " at station");
               }
            }
            case "turn_around" -> {
               p.setDirectionAngle((mirrorAngle(m) + 180F) % 360F);
               if (nowNs - actionNs > 800_000_000L && !m.turned[col][row]) {
                  m.turned[col][row] = true;
                  stationEvent("back to mirror " + (mi + 1) + " at station");
               }
            }
            case "circle" -> {
               float r = 0.5F;
               float a = (float)Math.atan2(p.getY() - w[1], p.getX() - w[0]);
               if (angleValid && Math.hypot(p.getX() - w[0], p.getY() - w[1]) > r * 0.6F) {
                  float da = a - lastAngle;
                  da = (float)(((da + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI);
                  if (da > 0) circleTurned += da;
               }
               lastAngle = a;
               angleValid = true;
               float t = a + (float)Math.PI / 4F;
               Showcase.moveKeys(w[0] + r * (float)Math.cos(t) - p.getX(), w[1] + r * (float)Math.sin(t) - p.getY());
               if (!m.circled && (circleTurned >= 2 * Math.PI || nowNs - actionNs > 8_000_000_000L)) {
                  m.circled = true;
                  stationEvent((circleTurned >= 2 * Math.PI ? "circled" : "circle blocked") + " at mirror " + (mi + 1) + " station");
               }
            }
            default -> {
            }
         }
      }
      if (nowNs - lastLogNs >= 2_000_000_000L) {
         lastLogNs = nowNs;
         Log.info(String.format(Locale.ROOT, "harness: mirror walk: t=%.0fs %s mirror %d station %d,%d pos=%.2f,%.2f,%.1f dir=%.0f dist=%.2f",
               (nowNs - startNs) / 1e9, command, mi + 1, col, row, p.getX(), p.getY(), p.getZ(), p.getDirectionAngle(), dist));
      }
      if (nowNs - lastStateNs >= 300_000_000L) {
         lastStateNs = nowNs;
         writeState(p, nowNs, dist);
      }
   }

   private static int count(boolean[][] a) {
      int n = 0;
      for (boolean[] r : a) for (boolean b : r) if (b) n++;
      return n;
   }

   private static int left(M m) {
      int n = 0;
      for (int c = 0; c < cols.length; c++) for (int r = 0; r < rows.length; r++) if (m.usable[c][r] && !m.blockedAt[c][r] && !m.visited[c][r]) n++;
      return n;
   }

   private static void stationEvent(String what) {
      M m = mirrors.get(mi);
      float[] w = world(m, col, row);
      Log.info(String.format(Locale.ROOT, "harness: mirror walk: %s %d,%d (along %.1f, out %.1f) at %.2f,%.2f,%d epoch_ms=%d (+%.1f s)", what, col, row,
            cols[col], rows[row], w[0], w[1], m.z, System.currentTimeMillis(), (System.nanoTime() - startNs) / 1e9));
   }

   /**
    * The station a move goes to: the nearest unvisited one that way (any row for left / right, any column for closer /
    * back: the state's unvisited_* flags count those), else the next usable one straight that way; null when none.
    */
   private static int[] step(M m, String dir) {
      int dc = dir.equals("left") ? -1 : dir.equals("right") ? 1 : 0;
      int dr = dir.equals("closer") ? -1 : dir.equals("back") ? 1 : 0;
      int[] best = null;
      int bestD = Integer.MAX_VALUE;
      for (int c = 0; c < cols.length; c++) {
         for (int r = 0; r < rows.length; r++) {
            if (!m.usable[c][r] || m.blockedAt[c][r] || m.visited[c][r]) continue;
            if (dc != 0 && Integer.signum(c - col) != dc || dr != 0 && Integer.signum(r - row) != dr) continue;
            int d = Math.abs(c - col) + Math.abs(r - row);
            if (d < bestD) {
               bestD = d;
               best = new int[] {c, r};
            }
         }
      }
      if (best != null) return best;
      int c = col + dc, r = row + dr;
      while (c >= 0 && r >= 0 && c < cols.length && r < rows.length) {
         if (m.usable[c][r] && !m.blockedAt[c][r]) return new int[] {c, r};
         c += dc;
         r += dr;
      }
      return null;
   }

   private static boolean unvisitedThatWay(M m, String dir) {
      int dc = dir.equals("left") ? -1 : dir.equals("right") ? 1 : 0;
      int dr = dir.equals("closer") ? -1 : dir.equals("back") ? 1 : 0;
      for (int c = 0; c < cols.length; c++) {
         for (int r = 0; r < rows.length; r++) {
            if (!m.usable[c][r] || m.visited[c][r] || m.blockedAt[c][r]) continue;
            if (dc != 0 && Integer.signum(c - col) == dc || dr != 0 && Integer.signum(r - row) == dr) return true;
         }
      }
      return false;
   }

   // ---- walking between mirrors: a grid path over the loaded squares of every level, staircases as edges ----

   private static int[] pathX = new int[0], pathY = new int[0], pathZ = new int[0];
   private static int pathIdx, pathLen;
   private static final int R = 50, W = 2 * R + 1;
   private static final int[][] CLIMB = {{0, -1}, {-1, 0}};

   private static void walkPath(IsoPlayer p, int gx, int gy, int gz, long nowNs) {
      IsoGridSquare cur = p.getCurrentSquare();
      if (cur == null) return;
      boolean onStairs = cur.HasStairs();
      boolean stuck = pathLen > 0 && nowNs - lastProgressNs > 1_500_000_000L;
      if (stuck && !onStairs) {
         int k = Math.min(pathIdx, pathLen - 1);
         if (pathX[k] != gx || pathY[k] != gy) blocked.add(pack(pathX[k], pathY[k], pathZ[k]));
         stuckMarks++;
         Log.info("harness: mirror walk: stuck at " + cur.x + "," + cur.y + "," + cur.z + ", square " + pathX[k] + "," + pathY[k] + " marked blocked");
      }
      if (!onStairs && (pathLen == 0 || stuck || pathIdx >= pathLen || nowNs - lastPlanNs > 2_000_000_000L)) {
         lastPlanNs = nowNs;
         lastProgressNs = nowNs;
         progressX = p.getX();
         progressY = p.getY();
         plan(cur, gx, gy, gz);
         if (pathLen == 0) {
            Showcase.moveKeys(gx + 0.5F - p.getX(), gy + 0.5F - p.getY()); // no path over the loaded squares: head straight for it
            return;
         }
      }
      if (Math.hypot(p.getX() - progressX, p.getY() - progressY) > 0.4) {
         progressX = p.getX();
         progressY = p.getY();
         lastProgressNs = nowNs;
      }
      while (pathIdx < pathLen && Math.hypot(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY()) < 0.35F) {
         pathIdx++;
      }
      if (pathIdx >= pathLen) return;
      if (!onStairs) {
         IsoGridSquare next = cur.getCell().getGridSquare(pathX[pathIdx], pathY[pathIdx], pathZ[pathIdx]);
         if (next != null && next.z == cur.z) openDoorBetween(p, cur, next);
      }
      Showcase.moveKeys(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY());
   }

   private static void openDoorBetween(IsoPlayer p, IsoGridSquare a, IsoGridSquare b) {
      if (a == b) return;
      IsoObject o = a.getDoorTo(b);
      if (o instanceof IsoDoor d && !d.IsOpen() && !d.isBarricaded()) {
         d.setLocked(false);
         d.setLockedByKey(false);
         d.ToggleDoor(p);
         doorsOpened++;
         Log.info("harness: mirror walk: opened a door at " + d.getSquare().x + "," + d.getSquare().y + "," + d.getSquare().z);
      } else if (o instanceof IsoThumpable t && t.isDoor() && !t.IsOpen()) {
         t.ToggleDoor(p);
         doorsOpened++;
      }
   }

   private static long pack(int x, int y, int z) {
      return ((long)x << 40) ^ ((long)(y & 0xFFFFF) << 20) ^ (z & 0xFFFFF);
   }

   private static boolean passable(IsoGridSquare a, IsoGridSquare b) {
      if (b == null || b.z != a.z || blocked.contains(pack(b.x, b.y, b.z))) return false;
      if (b.HasStairs() || a.HasStairs() || !b.isFree(false)) return false;
      if (a.isWallTo(b) || a.isWindowBlockedTo(b) || a.isStairBlockedTo(b)) return false;
      if (a.isDoorBlockedTo(b)) {
         IsoObject o = a.getDoorTo(b);
         return o instanceof IsoDoor d && !d.isBarricaded() || o instanceof IsoThumpable t && t.isDoor();
      }
      return true;
   }

   private static boolean has(IsoCell cell, int x, int y, int z, IsoObjectType t) {
      IsoGridSquare s = cell.getGridSquare(x, y, z);
      return s != null && s.has(t);
   }

   /** Breadth-first from the player's square to (gx, gy, gz), or the reachable square nearest it on that level; staircases join the levels. */
   private static void plan(IsoGridSquare start, int gx, int gy, int gz) {
      IsoCell cell = start.getCell();
      int ox = start.x - R, oy = start.y - R, oz = Math.min(start.z, gz) - 1, nz = Math.max(start.z, gz) + 2 - oz;
      int[] prev = new int[W * W * nz];
      byte[] via = new byte[prev.length];
      java.util.Arrays.fill(prev, -2);
      ArrayDeque<Integer> q = new ArrayDeque<>();
      int s = idx(start.x - ox, start.y - oy, start.z - oz);
      prev[s] = -1;
      q.add(s);
      int found = -1, near = -1;
      float nearD = Float.MAX_VALUE;
      int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      while (!q.isEmpty()) {
         int i = q.poll();
         int x = ox + i % W, y = oy + i / W % W, z = oz + i / (W * W);
         IsoGridSquare a = cell.getGridSquare(x, y, z);
         if (a == null) continue;
         if (x == gx && y == gy && z == gz) {
            found = i;
            break;
         }
         if (z == gz) {
            float d = (float)Math.hypot(x - gx, y - gy);
            if (d < nearD) {
               nearD = d;
               near = i;
            }
         }
         for (int[] d : nb) {
            IsoGridSquare b = cell.getGridSquare(x + d[0], y + d[1], z);
            if (!passable(a, b)) continue;
            visit(prev, via, q, i, b.x - ox, b.y - oy, z - oz, 0);
         }
         for (int c = 0; c < 2; c++) {
            int dx = CLIMB[c][0], dy = CLIMB[c][1];
            IsoObjectType bottom = c == 0 ? IsoObjectType.stairsBN : IsoObjectType.stairsBW, mid = c == 0 ? IsoObjectType.stairsMN : IsoObjectType.stairsMW,
                  topT = c == 0 ? IsoObjectType.stairsTN : IsoObjectType.stairsTW;
            if (has(cell, x + dx, y + dy, z, bottom) && has(cell, x + 2 * dx, y + 2 * dy, z, mid) && has(cell, x + 3 * dx, y + 3 * dy, z, topT)) {
               IsoGridSquare e = cell.getGridSquare(x + 4 * dx, y + 4 * dy, z + 1);
               if (e != null && e.isFree(false) && !blocked.contains(pack(e.x, e.y, e.z))) visit(prev, via, q, i, e.x - ox, e.y - oy, e.z - oz, 1 + c);
            }
            if (has(cell, x - dx, y - dy, z - 1, topT) && has(cell, x - 2 * dx, y - 2 * dy, z - 1, mid) && has(cell, x - 3 * dx, y - 3 * dy, z - 1, bottom)) {
               IsoGridSquare e = cell.getGridSquare(x - 4 * dx, y - 4 * dy, z - 1);
               if (e != null && e.isFree(false) && !e.HasStairs() && !blocked.contains(pack(e.x, e.y, e.z))) visit(prev, via, q, i, e.x - ox, e.y - oy, e.z - oz, 3 + c);
            }
         }
      }
      pathIdx = 0;
      pathLen = 0;
      if (found < 0) found = near;
      if (found < 0) return;
      int[] bx = new int[4096], by = new int[4096], bz = new int[4096];
      int k = 0;
      for (int i = found; i >= 0 && k < 4090; i = prev[i]) {
         int x = ox + i % W, y = oy + i / W % W, z = oz + i / (W * W);
         bx[k] = x;
         by[k] = y;
         bz[k++] = z;
         int v = via[i];
         if (v != 0) {
            int c = (v - 1) % 2, dx = CLIMB[c][0], dy = CLIMB[c][1];
            for (int m = 3; m >= 1; m--) {
               if (v <= 2) { // up: the stair squares at +3, +2, +1 on the lower level
                  bx[k] = x - (4 - m) * dx;
                  by[k] = y - (4 - m) * dy;
                  bz[k++] = z - 1;
               } else { // down: the stair squares at -1, -2, -3 from the upper square
                  bx[k] = x + (4 - m) * dx;
                  by[k] = y + (4 - m) * dy;
                  bz[k++] = z;
               }
            }
         }
      }
      if (pathX.length < k) {
         pathX = new int[k];
         pathY = new int[k];
         pathZ = new int[k];
      }
      for (int j = 0; j < k; j++) {
         pathX[j] = bx[k - 1 - j];
         pathY[j] = by[k - 1 - j];
         pathZ[j] = bz[k - 1 - j];
      }
      pathLen = k <= 1 ? 0 : k;
      pathIdx = Math.min(1, k);
   }

   private static int idx(int x, int y, int z) {
      return (z * W + y) * W + x;
   }

   private static void visit(int[] prev, byte[] via, ArrayDeque<Integer> q, int from, int x, int y, int z, int v) {
      if (x < 0 || y < 0 || x >= W || y >= W || z < 0 || idx(x, y, z) >= prev.length) return;
      int j = idx(x, y, z);
      if (prev[j] != -2) return;
      prev[j] = from;
      via[j] = (byte)v;
      q.add(j);
   }

   // ---- the director (Jev) ----

   private static void readCommand(long nowNs) {
      try {
         if (cmdFile == null || !cmdFile.isFile()) return;
         String[] parts = java.nio.file.Files.readString(cmdFile.toPath()).trim().split("\\s+");
         if (parts.length < 2) return;
         int seq = Integer.parseInt(parts[0]);
         if (seq == commandSeq) return;
         commandSeq = seq;
         String c = parts[1];
         if (!java.util.Arrays.asList(ACTIONS).contains(c) || c.equals("hold")) return; // hold: keep doing the current action
         boolean move = c.equals("left") || c.equals("right") || c.equals("closer") || c.equals("back");
         if (!move && !c.equals("next_mirror") && c.equals(command)) return;
         if ((move || c.equals("next_mirror")) && !arrived) return; // still walking
         M m = mirrors.get(mi);
         // a started check runs to its end before another command replaces it (Jev alternated face_mirror / turn_around
         // every 0.3 s, neither reached its 1.5 s)
         if (!c.equals("done") && arrived && (command.equals("face_mirror") && !m.faced[col][row] || command.equals("turn_around") && !m.turned[col][row]
               || command.equals("circle") && !m.circled)) return;
         if (move) {
            int[] s = step(m, c);
            if (s == null) return;
            col = s[0];
            row = s[1];
            arrived = false;
            moveStartNs = nowNs;
            stationEvent("to mirror " + (mi + 1) + " station");
         } else if (c.equals("next_mirror")) {
            if (mi + 1 >= mirrors.size()) return;
            mi++;
            startTravel();
         }
         Log.info("harness: mirror walk director: " + command + " -> " + c + " (#" + seq + ") epoch_ms=" + System.currentTimeMillis());
         commands++;
         command = c;
         actionNs = nowNs;
         angleValid = false;
         circleTurned = 0F;
      } catch (Exception e) {
         // a half-written file: the next check reads it
      }
   }

   private static void writeState(IsoPlayer p, long nowNs, float dist) {
      M m = mirrors.get(mi);
      float secs = (nowNs - startNs) / 1e9F;
      StringBuilder grid = new StringBuilder("[");
      for (int r = 0; r < rows.length; r++) {
         grid.append(r == 0 ? "\"" : ",\"");
         for (int c = 0; c < cols.length; c++) {
            grid.append(!m.usable[c][r] || m.blockedAt[c][r] ? '#' : c == col && r == row ? '@' : m.visited[c][r] ? 'v' : '.');
         }
         grid.append('"');
      }
      grid.append(']');
      boolean checks = m.faced[col][row] && m.turned[col][row];
      boolean mirrorDone = arrived && left(m) == 0 && checks && m.circled;
      String json = String.format(Locale.ROOT,
            "{\"t\":%d,\"scene\":\"mirror\",\"seconds_since_start\":%.1f,\"time_limit_seconds\":%.0f,\"time_is_up\":%b,"
                  + "\"current_action\":\"%s\",\"player\":{\"arrived_at_station\":%b,\"walking_to_this_mirror\":%b,\"distance_to_station_tiles\":%.2f,\"moving\":%b},"
                  + "\"mirror\":{\"number\":%d,\"mirrors_total\":%d,\"mirrors_left_after_this\":%d,\"is_last_mirror\":%b,\"level\":%d,"
                  + "\"circled_at_this_mirror\":%b,\"stations_left_to_visit\":%d,\"all_done_here\":%b,"
                  + "\"map_rows_near_to_far_columns_left_to_right\":%s,\"map_legend\":\"@ here, v visited, . not visited yet, # blocked or no floor\"},"
                  + "\"station\":{\"faced_mirror_here\":%b,\"turned_back_here\":%b,\"checks_done_here\":%b,"
                  + "\"can_go_left\":%b,\"can_go_right\":%b,\"can_go_closer\":%b,\"can_go_back\":%b,"
                  + "\"unvisited_to_the_left\":%b,\"unvisited_to_the_right\":%b,\"unvisited_closer\":%b,\"unvisited_further_back\":%b}}",
            System.currentTimeMillis(), secs, limitSecs, secs >= limitSecs, command, arrived, travelling, dist, p.isPlayerMoving(),
            mi + 1, mirrors.size(), mirrors.size() - mi - 1, mi + 1 >= mirrors.size(), m.z,
            m.circled, left(m), mirrorDone, grid,
            m.faced[col][row], m.turned[col][row], checks,
            step(m, "left") != null, step(m, "right") != null, step(m, "closer") != null, step(m, "back") != null,
            unvisitedThatWay(m, "left"), unvisitedThatWay(m, "right"), unvisitedThatWay(m, "closer"), unvisitedThatWay(m, "back"));
      try {
         java.io.File tmp = new java.io.File(stateFile.getPath() + ".tmp");
         java.nio.file.Files.writeString(tmp.toPath(), json);
         java.nio.file.Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception e) {
         Log.warn("harness: mirror walk: state write failed: " + e);
      }
   }
}
