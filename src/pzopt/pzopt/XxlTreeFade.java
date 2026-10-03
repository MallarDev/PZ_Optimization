package pzopt;

import java.awt.Rectangle;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.IsoRoom;

/**
 * 42.21's XXL tree cutaway (IsoTree.render): besides the aim key, an XXL tree turns see-through while the player
 * drives (here: within 12 squares, see VEHICLE_RADIUS), stands in a room whose bounds (plus 8 squares to the
 * south-east) hold the tree or is within 24 squares of it, or is outside within 9 squares of it with rooms nearby.
 * A tree baked into the chunk textures (treesInChunkTexture) never reaches IsoTree.render, so FBORenderCell asks here
 * and draws the ones that fade per frame.
 */
public final class XxlTreeFade {
   private XxlTreeFade() {
   }

   /**
    * 42.21 fades every XXL tree while the player drives; with the trees baked into the chunk textures that moves all of
    * them to per-frame drawing (120 km/h drive: mean 4.4 -> 10.2 ms, p99 11.4 -> 29 ms). Only the ones that can hide
    * the car are faded here.
    */
   private static final float VEHICLE_RADIUS = 12.0F;

   private static long devCalls;
   private static long devHits;
   private static long devNextLogMs;

   public static boolean cutaway(IsoGridSquare treeSquare, IsoPlayer player) {
      if (player == null || treeSquare == null) {
         return false;
      }
      boolean hit = player.getVehicle() != null && Config.DEV_XXL_VEHICLE_FADE && withinDistance(treeSquare, VEHICLE_RADIUS) || insideRoom(treeSquare, player) || closeToRoom(treeSquare, player);
      if (Config.DEV_XXL_TREE_LOG) {
         devCalls++;
         if (hit) {
            devHits++;
         }
         long now = System.currentTimeMillis();
         if (now >= devNextLogMs) {
            if (devNextLogMs != 0L) {
               Log.info("xxl trees: " + devHits + " of " + devCalls + " XXL tree checks see-through in 10 s (vehicle="
                  + (player.getVehicle() != null) + " inRoom=" + player.isInARoom() + " nearbyRooms=" + player.numNearbyBuildingsRooms + ")");
            }
            devCalls = 0L;
            devHits = 0L;
            devNextLogMs = now + 10000L;
         }
      }
      return hit;
   }

   private static boolean closeToRoom(IsoGridSquare treeSquare, IsoPlayer player) {
      if (player.numNearbyBuildingsRooms == 0.0F || player.isInARoom()) {
         return false;
      }
      return withinDistance(treeSquare, 9.0F);
   }

   private static boolean insideRoom(IsoGridSquare treeSquare, IsoPlayer player) {
      if (!player.isInARoom() || player.getSquare() == null) {
         return false;
      }
      IsoRoom room = player.getSquare().getRoom();
      if (room == null) {
         return false;
      }
      Rectangle bounds = room.getRectsBounds();
      if (treeSquare.x < bounds.getX() || treeSquare.y < bounds.getY()) {
         return false;
      }
      return treeSquare.x <= bounds.getX() + bounds.getWidth() + 8.0 && treeSquare.y <= bounds.getY() + bounds.getHeight() + 8.0 || withinDistance(treeSquare, 24.0F);
   }

   private static boolean withinDistance(IsoGridSquare treeSquare, float distance) {
      float dx = treeSquare.x - IsoCamera.frameState.camCharacterX;
      float dy = treeSquare.y - IsoCamera.frameState.camCharacterY;
      return !(dx * dx + dy * dy > distance * distance);
   }
}
