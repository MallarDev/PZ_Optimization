package pzopt;

import java.util.concurrent.ConcurrentHashMap;
import zombie.core.properties.PropertyContainer;
import zombie.iso.sprite.IsoSprite;

/**
 * glassTilesPerFrame (2026-10-01, bus shelter report: the player and zombies behind glass were hidden). A baked tile writes
 * its depth for every pixel of its sprite that has a depth texel, see-through glass included, so a character behind a baked
 * pane failed the depth test against the composited chunk texture; stock draws Translucent tiles per frame after the
 * characters with depth writes off, and the pane blends over whoever stands behind it. Glass panes are a few hundred of
 * the 16k Translucent tile definitions (shop and restaurant display cases, glass-door fridges, escalator and mall balustrades,
 * glass partitions, shower screens), so they stay per frame as in stock. A tile is glass when its definition names a glass
 * material (MaterialType / Material / Material2 / Material3: Glass, Glass_Light, Glass_Solid), a glass group, or a
 * GlassRemovedOffset; the answer is cached per sprite.
 */
public final class GlassTiles {
   private GlassTiles() {
   }

   private static final ConcurrentHashMap<IsoSprite, Boolean> cache = new ConcurrentHashMap<>(); // IsoSprite keeps Object's identity equals / hashCode

   public static boolean isGlass(IsoSprite sprite) {
      Boolean glass = cache.get(sprite);
      if (glass == null) {
         glass = compute(sprite.getProperties());
         cache.put(sprite, glass);
      }
      return glass;
   }

   static boolean compute(PropertyContainer props) {
      if (props == null) {
         return false;
      }
      if (props.has("GlassRemovedOffset")) {
         return true;
      }
      return glassy(props.get("MaterialType")) || glassy(props.get("Material")) || glassy(props.get("Material2"))
            || glassy(props.get("Material3")) || glassy(props.get("GroupName"));
   }

   private static boolean glassy(String value) {
      return value != null && value.toLowerCase(java.util.Locale.ROOT).contains("glass");
   }
}
