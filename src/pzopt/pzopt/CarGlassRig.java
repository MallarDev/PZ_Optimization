package pzopt;

import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

/**
 * Harness rig {@code car_rig=N} (2026-10-01, car glass): {@code car_rig_at} s (default 2) after the first tick, N cars of
 * different models are parked around the player on a ring of radius {@code car_rig_r} squares (default 7), headings
 * stepping 45 degrees, so one frame shows windows facing every way. {@code car_rig_seat=true} seats the player in the
 * first car (an occupied driver's seat), {@code car_rig_spin=D} then turns that car D degrees a second; {@code car_rig_broken=true} removes the second car's windshield and front-left
 * window (open frames). {@code car_rig_models} overrides the model list (comma-separated script names).
 */
final class CarGlassRig {
   private static int cars = -1;
   private static long atNs;
   private static boolean done;
   private static BaseVehicle seated; // car_rig_spin: the player's car, turned in place
   private static long seatedNs;
   private static BaseVehicle second; // the ring's second car (car_rig_p2_car=other)
   private static IsoPlayer p2;
   private static final java.util.ArrayList<BaseVehicle> RING = new java.util.ArrayList<>();
   private static final java.util.ArrayList<IsoPlayer> NPCS = new java.util.ArrayList<>();
   private static int p2Stage;
   static final String MODELS = "Base.CarNormal,Base.SportsCar,Base.PickUpTruck,Base.Van,Base.CarLuxury,Base.ModernCar,Base.CarStationWagon,Base.SmallCar,"
         + "Base.OffRoad,Base.PickUpVan,Base.CarTaxi,Base.StepVan";

   private CarGlassRig() {
   }

   static boolean active() {
      if (cars < 0) {
         cars = Integer.parseInt(HarnessFlags.get("car_rig", "0").trim());
      }
      return cars > 0;
   }

   static void tick(IsoPlayer p, long nowNs) {
      if (!active()) {
         return;
      }
      if (done) {
         spin(nowNs);
         try {
            secondPerson(p);
         } catch (Throwable t) {
            p2Stage = 99;
            Log.warn("harness: car rig: second person failed: " + t);
            t.printStackTrace();
         }
         return;
      }
      if (atNs == 0L) {
         atNs = nowNs + (long)(Float.parseFloat(HarnessFlags.get("car_rig_at", "2")) * 1e9);
         return;
      }
      if (nowNs < atNs) {
         return;
      }
      done = true;
      try {
         spawn(p);
      } catch (Throwable t) {
         Log.warn("harness: car rig failed: " + t);
      }
   }

   /**
    * car_rig_p2=split|npc (with car_rig_seat): a second person in a car. split: a second local player (the game's split-screen
    * co-op, added through LuaManager's addPlayerToWorld without a controller: it only sits); npc: an IsoPlayer added to the cell
    * like a remote player (no second view). car_rig_p2_car=same (the passenger seat of the player's car, default) | other (the
    * driver's seat of the ring's second car).
    */
   private static void secondPerson(IsoPlayer p) throws Exception {
      String mode = HarnessFlags.get("car_rig_p2", "");
      if (mode.isEmpty() || seated == null || p2Stage >= 3) {
         return;
      }
      long since = System.nanoTime() - seatedNs;
      int count = Integer.parseInt(HarnessFlags.get("car_rig_p2_count", "1"));
      if ("npc".equals(mode) && count > 1) {
         // car_rig_p2_count=N: N people, one in the driver's seat of each of the ring's next N cars
         if (p2Stage == 0 && since > 1_000_000_000L) {
            for (int i = 1; i <= count && i < RING.size(); i++) {
               BaseVehicle v = RING.get(i);
               IsoPlayer old = IsoPlayer.getInstance();
               IsoPlayer n = new IsoPlayer(IsoWorld.instance.currentCell, zombie.characters.SurvivorFactory.CreateSurvivor(), (int)v.getX(), (int)v.getY(), (int)v.getZ());
               IsoPlayer.setInstance(old);
               n.dressInRandomOutfit();
               n.setSceneCulled(false); // a remote player's model is kept from its connection on (ConnectedPacket), seated or not
               NPCS.add(n);
            }
            p2Stage = 1;
            Log.info("harness: car rig: " + NPCS.size() + " people created");
         } else if (p2Stage == 1 && since > 2_500_000_000L) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < NPCS.size(); i++) {
               BaseVehicle v = RING.get(i + 1);
               NPCS.get(i).getInventory().AddItem(v.createVehicleKey());
               sb.append(' ').append(v.getScriptName()).append('=').append(v.enter(0, NPCS.get(i)));
            }
            p2Stage = 3;
            Log.info("harness: car rig: people seated:" + sb);
         }
         return;
      }
      if (p2Stage == 0 && since > 1_000_000_000L) {
         BaseVehicle v = "other".equals(HarnessFlags.get("car_rig_p2_car", "same")) && second != null ? second : seated;
         IsoCell cell = IsoWorld.instance.currentCell;
         IsoPlayer old = IsoPlayer.getInstance();
         zombie.characters.SurvivorDesc desc = zombie.characters.SurvivorFactory.CreateSurvivor();
         p2 = new IsoPlayer(cell, desc, (int)v.getX(), (int)v.getY(), (int)v.getZ());
         IsoPlayer.setInstance(old);
         p2.dressInRandomOutfit();
         if ("split".equals(mode)) {
            cell.getAddList().remove(p2);
            cell.getObjectList().remove(p2);
            p2.saveFileName = IsoPlayer.getUniqueFileName();
            java.lang.reflect.Method m = zombie.Lua.LuaManager.GlobalObject.class.getDeclaredMethod("addPlayerToWorld", int.class, IsoPlayer.class, boolean.class);
            m.setAccessible(true);
            m.invoke(null, 1, p2, false);
         }
         p2Stage = 1;
         Log.info("harness: car rig: second person (" + mode + ") created at " + v.getX() + "," + v.getY());
         return;
      }
      if (p2Stage == 1 && since > 2_500_000_000L && ("npc".equals(mode) || IsoPlayer.players[1] == p2 && IsoWorld.instance.addCoopPlayers.isEmpty())) {
         BaseVehicle v = "other".equals(HarnessFlags.get("car_rig_p2_car", "same")) && second != null ? second : seated;
         int seat = v == seated ? 1 : 0;
         if (v != seated) {
            p2.getInventory().AddItem(v.createVehicleKey());
         }
         boolean in = v.enter(seat, p2);
         p2Stage = 3;
         Log.info("harness: car rig: second person (" + mode + ") seated in " + v.getScriptName() + " seat " + seat + ": " + in + ", players " + IsoPlayer.numPlayers);
      }
   }

   /** car_rig_spin=D: the player's car turns D degrees a second about the vertical (every heading in one run). */
   private static void spin(long nowNs) {
      float d = Float.parseFloat(HarnessFlags.get("car_rig_spin", "0"));
      if (seated == null || d == 0F) {
         return;
      }
      float a = (float)((System.nanoTime() - seatedNs) / 1e9 * d) % 360F;
      seated.setAngles(0F, a, 0F);
   }

   /** A 3x5 block of loaded, outdoor, walkable squares with no vehicle (a car body fits however it turns). */
   private static boolean clear(IsoCell cell, int x, int y, int z) {
      for (int dy = -2; dy <= 2; dy++) {
         for (int dx = -2; dx <= 2; dx++) {
            IsoGridSquare q = cell.getGridSquare(x + dx, y + dy, z);
            if (q == null || q.chunk == null || !q.isOutside() || q.getFloor() == null || q.isSolid() || q.isSolidTrans() || q.getVehicleContainer() != null
                  || q.getBuilding() != null) {
               return false;
            }
         }
      }
      return true;
   }

   private static void spawn(IsoPlayer p) {
      String[] models = HarnessFlags.get("car_rig_models", MODELS).split(",");
      float r = Float.parseFloat(HarnessFlags.get("car_rig_r", "7"));
      IsoCell cell = IsoWorld.instance.currentCell;
      int px = p.getXi(), py = p.getYi(), pz = p.getZi();
      int placed = 0;
      BaseVehicle first = null;
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < cars; i++) {
         double a = 2.0 * Math.PI * i / cars;
         IsoGridSquare sq = null;
         int x = 0, y = 0;
         for (float rr = r; rr <= r + 4F && sq == null; rr += 1F) {
            x = px + (int)Math.round(rr * Math.cos(a));
            y = py + (int)Math.round(rr * Math.sin(a));
            if (clear(cell, x, y, pz)) {
               sq = cell.getGridSquare(x, y, pz);
            }
         }
         if (sq == null) {
            continue;
         }
         String script = models[i % models.length].trim();
         BaseVehicle v = zombie.Lua.LuaManager.GlobalObject.addVehicleDebug(script, IsoDirections.N, 0, sq);
         if (v == null || v.getSquare() == null) {
            Log.warn("harness: car rig: could not place " + script + " at " + x + "," + y);
            continue;
         }
         float angle = (float)(i * Math.PI / 4.0);
         v.savedRot.setAngleAxis(angle, 0F, 1F, 0F);
         v.jniTransform.setRotation(v.savedRot);
         v.repair();
         RING.add(v);
         if (first == null) {
            first = v;
         } else if (second == null) {
            second = v;
         }
         if (v == first) {
            // (the first car)
         } else if (placed == 1 && Boolean.parseBoolean(HarnessFlags.get("car_rig_broken", "false"))) {
            for (String id : new String[] {"Windshield", "WindowFrontLeft"}) {
               VehiclePart part = v.getPartById(id);
               if (part != null) {
                  part.setInventoryItem(null);
                  v.transmitPartItem(part);
               }
            }
         }
         sb.append(' ').append(script).append('@').append(x).append(',').append(y).append('/').append(i * 45);
         placed++;
      }
      if (first != null && Boolean.parseBoolean(HarnessFlags.get("car_rig_seat", "false"))) {
         p.getInventory().AddItem(first.createVehicleKey());
         boolean in = first.enter(0, p);
         seated = in ? first : null;
         seatedNs = System.nanoTime();
         Log.info("harness: car rig: player seated in " + first.getScriptName() + ": " + in);
      }
      Log.info("harness: car rig: " + placed + " cars around " + px + "," + py + ":" + sb);
   }
}
