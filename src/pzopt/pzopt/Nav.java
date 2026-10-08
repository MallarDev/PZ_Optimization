package pzopt;

import java.util.Locale;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * The harness's walking (2026-10-08, the maintainer: "an error free and stuck free Jev powered harness to walk in the game
 * efficiently without colliding with any obstacle"). The player walks with the game's own "walk to" (ISWalkToTimedActionF,
 * harness Lua pzopt_harness_walk.lua): the pathfinder routes round the furniture footprints with the character's radius,
 * opens doors on the way and takes stairs; never movement keys, never a teleport. The hand-rolled square path plus movement
 * keys it replaces (MirrorWalk until 2026-10-08) walked into furniture corners, out of the room through a wall gap and stood
 * against a wall for a minute.
 *
 * <p>Every frame {@link #frame} measures what the walk must not do: collisions (the game's own per-frame flags: a wall, an
 * object, a door, a vehicle, the bump state), stuck episodes (walking but under 0.15 squares of progress in 1.5 s; the walk
 * is re-issued once, then failed), and the legs' efficiency (distance walked / straight distance, seconds). One
 * {@code harness: nav:} line per leg and per collision, {@link #summary} at the end (harness/navjudge.py reads them).
 */
final class Nav {
   private Nav() {
   }

   static final int IDLE = 0, WALKING = 1, ARRIVED = 2, FAILED = 3;

   private static int doorsOpened;
   private static int status = IDLE, seq = -1, legs, arrivedN, failedN, retries, collisions, collisionFrames, stuckN, luaErrors, lost;
   private static float legPlanned = -1F; // the pathfinder's route length (squares) once found
   private static final java.util.ArrayDeque<float[]> via = new java.util.ArrayDeque<>(); // a detour's points, the goal last
   private static float gx, gy; // the leg's goal (tx, ty is the current point: the goal or a detour point)
   private static boolean hopChecked;
   private static int detours, climbs;
   private static float tx, ty, legStraight, legWalked, walkedTotal, straightTotal, lastX, lastY, progX, progY;
   private static int tz;
   private static long legStartNs, progressNs, issuedNs, lastPollNs;
   private static float legSecsTotal;
   private static boolean colliding, retried;
   private static String why = "";

   static int status() {
      return status;
   }

   static float targetX() {
      return tx;
   }

   static float targetY() {
      return ty;
   }

   /** Can the player's 0.3-radius body stand at (x, y, z)? The pathfinder's own test (furniture, walls, floor). */
   static boolean canStand(float x, float y, int z) {
      try {
         return zombie.pathfind.PolygonalMap2.instance.canStandAt(x, y, z, null, false, false);
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * canStand with room to turn: the body also fits 0.15 squares further in each direction (a station 0.07 squares from a
    * wall passed canStand, and the player brushed the wall turning to face the mirror: six collisions, run nav-t3).
    */
   static boolean canStandClear(float x, float y, int z) {
      final float m = 0.15F;
      return canStand(x, y, z) && canStand(x + m, y, z) && canStand(x - m, y, z) && canStand(x, y + m, z) && canStand(x, y - m, z);
   }

   /** Is the straight walk from a to b clear for the player's body (the pathfinder's line test)? */
   static boolean lineClear(float ax, float ay, float bx, float by, int z) {
      try {
         return !zombie.pathfind.PolygonalMap2.instance.lineClearCollide(ax, ay, bx, by, z, null, false, false);
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * Face an angle (degrees) on the spot, at once: setDirectionAngle starts the turn-in-place animation, whose steps moved
    * the player up to 0.13 squares and walked him into the wall behind a station (run nav-t4); the game's own instant turn
    * (the TurnAround animation event's) moves nothing.
    */
   static void face(IsoPlayer p, float angleDeg) {
      float d = Math.abs(((p.getDirectionAngle() - angleDeg) % 360F + 540F) % 360F - 180F);
      if (d < 1F) return;
      double a = Math.toRadians(angleDeg);
      p.setTargetAndCurrentDirection((float)Math.cos(a), (float)Math.sin(a));
   }

   /** Walk to (x, y, z). {@code what} names the leg in the log. */
   static void go(IsoPlayer p, float x, float y, int z, String what) {
      tx = x;
      ty = y;
      gx = x;
      gy = y;
      tz = z;
      via.clear();
      why = what;
      retried = false;
      legs++;
      legStraight = (float)Math.hypot(x - p.getX(), y - p.getY()) + Math.abs(z - (int)Math.floor(p.getZ() + 0.01F)) * 3F;
      legWalked = 0F;
      legPlanned = -1F;
      legStartNs = System.nanoTime();
      issue(p);
   }

   private static void issue(IsoPlayer p) {
      hopChecked = false;
      Object r = call("PzoptWalkTo", tx, ty, tz);
      status = WALKING;
      seq = r instanceof Double d ? d.intValue() : -1;
      issuedNs = System.nanoTime();
      progressNs = issuedNs;
      progX = p.getX();
      progY = p.getY();
      lastX = p.getX();
      lastY = p.getY();
   }

   static void cancel() {
      call("PzoptWalkCancel");
      status = IDLE;
   }

   private static Object call(String fn, Object... args) {
      try {
         Object f = zombie.Lua.LuaManager.env.rawget(fn);
         if (f == null) {
            luaErrors++;
            Log.warn("harness: nav: Lua function " + fn + " missing (pzopt_harness_walk.lua not loaded?)");
            return null;
         }
         Object[] a = new Object[args.length];
         for (int i = 0; i < args.length; i++) a[i] = args[i] instanceof Number n ? (Object)Double.valueOf(n.doubleValue()) : args[i];
         Object[] res = zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, f, a);
         if (res == null || res.length == 0 || !Boolean.TRUE.equals(res[0])) {
            luaErrors++;
            Log.warn("harness: nav: Lua " + fn + " failed: " + (res != null && res.length > 1 ? res[1] : "?"));
            return null;
         }
         return res.length > 1 ? res[1] : null;
      } catch (Throwable t) {
         luaErrors++;
         Log.warn("harness: nav: Lua " + fn + " threw " + t);
         return null;
      }
   }

   /** Every frame while a walk rig runs: collisions, progress, the walk's result. */
   static void frame(IsoPlayer p, long nowNs) {
      float step = (float)Math.hypot(p.getX() - lastX, p.getY() - lastY);
      if (step < 2F) { // a level change / teleport is not walking
         walkedTotal += step;
         legWalked += step;
      }
      lastX = p.getX();
      lastY = p.getY();
      boolean hit = p.isCollidedThisFrame() || p.getCollidedObject() != null || p.isCollidedWithDoor() || p.isCollidedWithVehicle()
            || p.getCurrentState() == zombie.ai.states.CollideWithWallState.instance();
      if (hit) {
         collisionFrames++;
         if (!colliding) {
            collisions++;
            IsoObject o = p.getCollidedObject();
            IsoGridSquare sq = p.getCurrentSquare();
            Log.info(String.format(Locale.ROOT, "harness: nav: collision %d at %.2f,%.2f,%.1f (square %s) N%b S%b E%b W%b object %s door %b vehicle %b wallstate %b, leg %d '%s' epoch_ms=%d",
                  collisions, p.getX(), p.getY(), p.getZ(), sq == null ? "-" : sq.x + "," + sq.y, p.isCollidedN(), p.isCollidedS(), p.isCollidedE(), p.isCollidedW(),
                  o == null || o.getSprite() == null ? "-" : o.getSprite().getName(), p.isCollidedWithDoor(), p.isCollidedWithVehicle(),
                  p.getCurrentState() == zombie.ai.states.CollideWithWallState.instance(), legs, why, System.currentTimeMillis()));
            // what blocked: the square the feeler / step went into and both squares' objects, the player's state
            IsoGridSquare ahead = sq == null ? null : sq.getCell().getGridSquare(sq.x + (p.isCollidedE() ? 1 : p.isCollidedW() ? -1 : 0), sq.y + (p.isCollidedS() ? 1 : p.isCollidedN() ? -1 : 0), sq.z);
            Log.info("harness: nav: collision " + collisions + " detail: state " + (p.getCurrentState() == null ? "-" : p.getCurrentState().getClass().getSimpleName())
                  + ", here [" + objects(sq) + "], ahead " + (ahead == null ? "-" : ahead.x + "," + ahead.y + " [" + objects(ahead) + "]")
                  + ", path " + (p.getPath2() == null ? "none" : p.getPath2().size() + " nodes"));
         }
      }
      colliding = hit;
      if (status != WALKING) return;
      if (!hopChecked && p.getFinder().progress == zombie.ai.astar.AStarPathFinder.PathFindProgress.found && p.getPath2() != null) {
         hopChecked = true;
         if (legPlanned < 0F) legPlanned = legWalked + p.getPathFindBehavior2().getPathLength();
         int[] hop = hopOnRoute(p);
         if (hop != null) {
            if (via.isEmpty() && detour(p, hop)) return;
            climbs++;
            Log.info(String.format(Locale.ROOT, "harness: nav: leg %d '%s': the route climbs at %d,%d,%d and no way round was found", legs, why, hop[0], hop[1], hop[2]));
         }
      }
      if (Math.hypot(p.getX() - progX, p.getY() - progY) > 0.15F) {
         progX = p.getX();
         progY = p.getY();
         progressNs = nowNs;
      }
      if (nowNs - lastPollNs >= 50_000_000L) {
         lastPollNs = nowNs;
         Object r = call("PzoptWalkStatus");
         String[] s = r == null ? new String[0] : r.toString().split(" ");
         if (s.length == 2 && Integer.parseInt(s[0]) == seq) {
            switch (s[1]) {
               case "arrived" -> {
                  if (via.isEmpty()) {
                     end(p, nowNs, true, "arrived");
                  } else { // the next point of a detour
                     float[] n = via.poll();
                     tx = n[0];
                     ty = n[1];
                     issue(p);
                  }
               }
               case "failed", "stopped", "lost" -> {
                  if (s[1].equals("lost")) lost++;
                  if (!retried) {
                     retried = true;
                     retries++;
                     Log.info(String.format(Locale.ROOT, "harness: nav: leg %d '%s' %s at %.2f,%.2f,%.1f, walking again", legs, why, s[1], p.getX(), p.getY(), p.getZ()));
                     issue(p);
                  } else {
                     end(p, nowNs, false, s[1]);
                  }
               }
               default -> {
               }
            }
         }
      }
      openDoorAhead(p);
      // a leg far longer than its route takes (~1.9 squares a second walking; a 91-square walk ran past a fixed 40 s)
      float routeLen = legPlanned > 0F ? legPlanned : legStraight;
      if (status == WALKING && nowNs - legStartNs > (long)((15F + 1.5F * routeLen) * 1e9)) {
         call("PzoptWalkCancel");
         end(p, nowNs, false, "timeout");
         return;
      }
      // the pathfinder waits a few frames for its route: progress counts from the issue
      if (status == WALKING && nowNs - progressNs > 1_500_000_000L && nowNs - issuedNs > 1_500_000_000L) {
         stuckN++;
         Log.info(String.format(Locale.ROOT, "harness: nav: stuck %d at %.2f,%.2f,%.1f, leg %d '%s' to %.2f,%.2f,%d epoch_ms=%d", stuckN, p.getX(), p.getY(), p.getZ(), legs, why, tx, ty, tz,
               System.currentTimeMillis()));
         if (!retried) {
            retried = true;
            retries++;
            call("PzoptWalkCancel");
            issue(p);
         } else {
            call("PzoptWalkCancel");
            end(p, nowNs, false, "stuck");
         }
      }
   }

   /**
    * A closed door on the route one square ahead is opened (and unlocked) before the player reaches it: the game's own walk
    * opens it at 0.5 squares, the distance its collision feeler looks ahead, and the feeler touched the closed door first
    * (run nav-t6b).
    */
   private static void openDoorAhead(IsoPlayer p) {
      IsoGridSquare cur = p.getCurrentSquare();
      zombie.pathfind.Path path = p.getPath2();
      if (cur == null || path == null) return;
      int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
      for (int[] d : dirs) {
         IsoGridSquare nb = cur.getCell().getGridSquare(cur.x + d[0], cur.y + d[1], cur.z);
         if (nb == null || !path.crossesSquare(nb.x, nb.y, nb.z)) continue;
         IsoObject o = cur.getDoorTo(nb);
         if (o instanceof zombie.iso.objects.IsoDoor door && !door.IsOpen() && !door.isBarricaded()) {
            door.setLocked(false);
            door.setLockedByKey(false);
            door.ToggleDoor(p);
            doorsOpened++;
            Log.info(String.format(Locale.ROOT, "harness: nav: opened the door ahead at %d,%d,%d (%s), leg %d", door.getSquare().x, door.getSquare().y, door.getSquare().z, door.IsOpen() ? "open" : "STILL CLOSED", legs));
         } else if (o instanceof zombie.iso.objects.IsoThumpable t && t.isDoor() && !t.IsOpen() && !t.isBarricaded()) {
            t.setIsLocked(false);
            t.ToggleDoor(p);
            doorsOpened++;
            Log.info(String.format(Locale.ROOT, "harness: nav: opened the door ahead at %d,%d,%d (%s), leg %d", t.getSquare().x, t.getSquare().y, t.getSquare().z, t.IsOpen() ? "open" : "STILL CLOSED", legs));
         }
      }
   }

   /**
    * The first fence / low wall / window the found route crosses, as {x, y, z} of the square before it, else null. The game's
    * pathfinder lets the player vault low fences and climb through windows (no request flag turns it off for a player), and
    * the vault is a collision frame (run nav-t6d: fencing_01_17 in ClimbOverFenceState).
    */
   private static int[] hopOnRoute(IsoPlayer p) {
      zombie.pathfind.Path path = p.getPath2();
      zombie.iso.IsoCell cell = p.getCell();
      IsoGridSquare last = p.getCurrentSquare();
      for (int i = 0; i + 1 < path.size(); i++) {
         zombie.pathfind.PathNode a = path.getNode(i), b = path.getNode(i + 1);
         float len = (float)Math.hypot(b.x - a.x, b.y - a.y);
         int steps = Math.max(1, (int)Math.ceil(len / 0.1F));
         for (int k = 1; k <= steps; k++) {
            float t = (float)k / steps;
            IsoGridSquare sq = cell.getGridSquare((int)Math.floor(a.x + (b.x - a.x) * t), (int)Math.floor(a.y + (b.y - a.y) * t), (int)Math.floor(a.z + (b.z - a.z) * t + 0.01F));
            if (sq == null || last == null || sq == last) {
               last = sq == null ? last : sq;
               continue;
            }
            if (sq.z == last.z) {
               // a diagonal step crosses two edges: through either side square
               IsoGridSquare side = sq.x != last.x && sq.y != last.y ? cell.getGridSquare(sq.x, last.y, sq.z) : null;
               if (hops(last, sq) || side != null && hops(last, side) && hops(side, sq)) return new int[] {last.x, last.y, last.z};
            }
            last = sq;
         }
      }
      return null;
   }

   private static boolean hops(IsoGridSquare a, IsoGridSquare b) {
      return Math.abs(a.x - b.x) + Math.abs(a.y - b.y) == 1 && (a.isHoppableTo(b) || a.isWindowTo(b));
   }

   /**
    * A way round a vault: breadth-first over the squares of the level (walls, windows, fences and furniture blocking, doors
    * open), the path's corners walked as points of this leg with the game's walk. False when the goal is on another level
    * or no such way exists within 120 squares.
    */
   private static boolean detour(IsoPlayer p, int[] hop) {
      IsoGridSquare start = p.getCurrentSquare();
      int gz = tz;
      if (start == null || start.z != gz) return false;
      zombie.iso.IsoCell cell = start.getCell();
      final int R = 120, W = 2 * R + 1;
      int ox = start.x - R, oy = start.y - R, goalX = (int)Math.floor(gx), goalY = (int)Math.floor(gy);
      if (Math.abs(goalX - start.x) > R || Math.abs(goalY - start.y) > R) return false;
      int[] prev = new int[W * W];
      java.util.Arrays.fill(prev, -2);
      java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
      int s0 = (start.y - oy) * W + (start.x - ox), found = -1;
      prev[s0] = -1;
      q.add(s0);
      int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      while (!q.isEmpty() && found < 0) {
         int i = q.poll();
         int x = ox + i % W, y = oy + i / W;
         IsoGridSquare a = cell.getGridSquare(x, y, gz);
         if (a == null) continue;
         for (int[] d : nb) {
            int nx = x + d[0], ny = y + d[1];
            if (nx < ox || ny < oy || nx >= ox + W || ny >= oy + W) continue;
            int j = (ny - oy) * W + (nx - ox);
            if (prev[j] != -2) continue;
            IsoGridSquare b = cell.getGridSquare(nx, ny, gz);
            if (b == null || b.HasStairs() || !canStand(nx + 0.5F, ny + 0.5F, gz) && !(nx == goalX && ny == goalY)) continue;
            if (hops(a, b) || a.isWallTo(b) || !a.isDoorTo(b) && a.isBlockedTo(b)) continue;
            prev[j] = i;
            if (nx == goalX && ny == goalY) {
               found = j;
               break;
            }
            q.add(j);
         }
      }
      if (found < 0) return false;
      java.util.ArrayList<int[]> sq = new java.util.ArrayList<>();
      for (int i = found; i >= 0; i = prev[i]) sq.add(0, new int[] {ox + i % W, oy + i / W});
      // the corners of the square path (where it turns), then the goal itself
      for (int k = 1; k + 1 < sq.size(); k++) {
         int[] a = sq.get(k - 1), b = sq.get(k), c = sq.get(k + 1);
         if (b[0] - a[0] != c[0] - b[0] || b[1] - a[1] != c[1] - b[1]) via.add(new float[] {b[0] + 0.5F, b[1] + 0.5F});
      }
      via.add(new float[] {gx, gy});
      detours++;
      legPlanned = legWalked + sq.size(); // the detour's length is the leg's reference now
      Log.info(String.format(Locale.ROOT, "harness: nav: leg %d '%s': the route vaults at %d,%d,%d; walking round it, %d squares, %d points", legs, why, hop[0], hop[1], hop[2], sq.size(), via.size()));
      call("PzoptWalkCancel");
      float[] n = via.poll();
      tx = n[0];
      ty = n[1];
      issue(p);
      return true;
   }

   private static String objects(IsoGridSquare sq) {
      if (sq == null) return "";
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < sq.getObjects().size(); i++) {
         IsoObject o = sq.getObjects().get(i);
         b.append(i == 0 ? "" : " ").append(o.getSprite() == null ? o.getClass().getSimpleName() : o.getSprite().getName());
      }
      return b.toString();
   }

   private static void end(IsoPlayer p, long nowNs, boolean ok, String how) {
      status = ok ? ARRIVED : FAILED;
      if (ok) arrivedN++;
      else failedN++;
      float secs = (nowNs - legStartNs) / 1e9F;
      legSecsTotal += secs;
      straightTotal += legStraight;
      float miss = (float)Math.hypot(p.getX() - tx, p.getY() - ty);
      Log.info(String.format(Locale.ROOT, "harness: nav: leg %d '%s' %s in %.2f s, walked %.2f / straight %.2f squares (x%.2f), planned %.2f (x%.2f), %.2f off target, at %.2f,%.2f,%.1f epoch_ms=%d", legs, why,
            how, secs, legWalked, legStraight, legStraight > 0.05F ? legWalked / legStraight : 1F, legPlanned, legPlanned > 0.05F ? legWalked / legPlanned : 1F, miss, p.getX(), p.getY(), p.getZ(),
            System.currentTimeMillis()));
   }

   // ---- nav_selftest=true: the collision counter's control ----

   private static int selfState, selfCollisionsBefore, selfDx, selfDy;
   private static long selfStartNs, selfReleaseNs, selfClearNs;
   private static boolean selfSeen;

   /**
    * nav_selftest=true: before the walk the player is pushed with the movement keys into the nearest wall (scanning N, E, S,
    * W up to 6 squares from his square) until the counter sees a collision or 4 s pass, then the keys are released and
    * the push's collisions are taken off the walk's count. A walk report of 0 collisions means something only when this
    * push was seen ({@code harness: nav: self-test: ... seen}). True while the push runs.
    */
   static boolean selfTest(IsoPlayer p, long nowNs) {
      if (selfState == 2) return false;
      if (selfState == 0) {
         IsoGridSquare cur = p.getCurrentSquare();
         int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
         int best = Integer.MAX_VALUE;
         for (int[] d : dirs) {
            IsoGridSquare a = cur;
            for (int k = 1; k <= 6 && a != null; k++) {
               IsoGridSquare b = a.getCell().getGridSquare(a.x + d[0], a.y + d[1], a.z);
               if (b == null || a.isBlockedTo(b) || !b.isFree(false)) {
                  if (k < best) {
                     best = k;
                     selfDx = d[0];
                     selfDy = d[1];
                  }
                  break;
               }
               a = b;
            }
         }
         if (best == Integer.MAX_VALUE) {
            Log.info("harness: nav: self-test: no wall within 6 squares, skipped");
            selfState = 2;
            return false;
         }
         Log.info(String.format(Locale.ROOT, "harness: nav: self-test: pushing %d,%d into the obstacle %d squares away from %.2f,%.2f", selfDx, selfDy, best, p.getX(), p.getY()));
         selfCollisionsBefore = collisions;
         selfStartNs = nowNs;
         selfState = 1;
      }
      frame(p, nowNs);
      boolean seen = collisions > selfCollisionsBefore;
      if (selfState == 1 && (seen && nowNs - selfStartNs > 300_000_000L || nowNs - selfStartNs > 4_000_000_000L)) {
         Showcase.releaseKeys();
         selfState = 3; // released: the contact ends a few frames later; the walk starts once it has
         selfSeen = seen;
         selfReleaseNs = nowNs;
         selfClearNs = 0L;
         return true;
      }
      if (selfState == 3) {
         if (colliding) selfClearNs = 0L;
         else if (selfClearNs == 0L) selfClearNs = nowNs;
         if (selfClearNs == 0L || nowNs - selfClearNs < 300_000_000L) {
            if (nowNs - selfReleaseNs < 3_000_000_000L) return true;
         }
         int n = collisions - selfCollisionsBefore;
         Log.info(String.format(Locale.ROOT, "harness: nav: self-test: %d collision(s) %s in %.1f s of pushing; not counted in the walk", n, selfSeen ? "seen" : "NOT seen",
               (selfReleaseNs - selfStartNs) / 1e9));
         collisions -= n;
         collisionFrames = 0;
         walkedTotal = 0F;
         colliding = false;
         selfState = 2;
         return false;
      }
      if (selfState == 1) Showcase.moveKeys(selfDx, selfDy);
      return true;
   }

   static String summary() {
      return String.format(Locale.ROOT, "legs %d arrived %d failed %d retries %d lost %d collisions %d (%d frames) stuck %d lua_errors %d doors %d detours %d climbs %d walked %.1f straight %.1f walking %.1f s",
            legs, arrivedN, failedN, retries, lost, collisions, collisionFrames, stuckN, luaErrors, doorsOpened, detours, climbs, walkedTotal, straightTotal, legSecsTotal);
   }
}
