package zombie.characters;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import zombie.UsedFromLua;
import zombie.debug.DebugType;

@UsedFromLua
public class Stats {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoadedQuiet("zombie.characters.Stats"); // pzopt
   }

   private static final float ENDURANCE_DANGER_WARNING = 0.25F;
   private static final float ENDURANCE_WARNING = 0.5F;
   private final Map<CharacterStat, Float> stats = new HashMap<>();
   private final boolean enduranceRecharging = false;
   private float lastEndurance = 1.0F;
   public int numVisibleZombies;
   public int lastNumVisibleZombies;
   private boolean tripping;
   private float trippingRotAngle;
   public int numChasingZombies;
   public int lastVeryCloseZombies;
   private int lastNumChasingZombies;
   public int musicZombiesVisible;
   public int musicZombiesTargetingDistantNotMoving;
   public int musicZombiesTargetingNearbyNotMoving;
   public int musicZombiesTargetingDistantMoving;
   public int musicZombiesTargetingNearbyMoving;

   public void load(DataInputStream input) throws IOException {
      for (CharacterStat stat : CharacterStat.ORDERED_STATS) {
         this.set(stat, input.readFloat());
      }
   }

   public void load(ByteBuffer input, int worldVersion) throws IOException {
      for (CharacterStat stat : CharacterStat.ORDERED_STATS) {
         this.set(stat, input.getFloat());
      }
   }

   public void save(DataOutputStream output) throws IOException {
      for (CharacterStat stat : CharacterStat.ORDERED_STATS) {
         output.writeFloat(this.get(stat));
      }
   }

   public void save(ByteBuffer output) throws IOException {
      for (CharacterStat stat : CharacterStat.ORDERED_STATS) {
         output.putFloat(this.get(stat));
      }
   }

   public void parse(ByteBuffer b, byte field) {
      if (field >= 0 && field < CharacterStat.ORDERED_STATS.length) {
         this.set(CharacterStat.ORDERED_STATS[field], b.getFloat());
      } else {
         DebugType.Objects.warn("Wrong field %d provided for Stats::parse method", new Object[]{field});
      }
   }

   public void write(ByteBuffer b, byte field) {
      if (field >= 0 && field < CharacterStat.ORDERED_STATS.length) {
         b.putFloat(this.get(CharacterStat.ORDERED_STATS[field]));
      } else {
         DebugType.Objects.warn("Wrong field %d provided for Stats::write method", new Object[]{field});
      }
   }

   public float get(CharacterStat stat) {
      // pzopt: statsNoBox. getOrDefault boxed the default into a new Float on every call (10 % of the sampled allocation
      // on the Louisville horde, from the player's line of sight); a plain get, and the default only for an absent key,
      // returns the same value (a key mapped to null still throws on the unboxing, as the stock call does).
      if (pzopt.Config.STATS_NO_BOX && pzopt.Overrides.enabled()) { // pzopt
         Float v = this.stats.get(stat); // pzopt
         return v != null || this.stats.containsKey(stat) ? v : stat.getDefaultValue(); // pzopt
      } // pzopt
      return this.stats.getOrDefault(stat, stat.getDefaultValue());
   }

   public boolean set(CharacterStat stat, float value) {
      float oldValue = this.get(stat);
      float newValue = stat.clamp(value);
      this.stats.put(stat, newValue);
      return newValue != oldValue;
   }

   public boolean add(CharacterStat stat, float amount) {
      return this.set(stat, this.get(stat) + amount);
   }

   public boolean remove(CharacterStat stat, float amount) {
      return this.add(stat, -amount);
   }

   public boolean reset(CharacterStat stat) {
      return this.set(stat, stat.getDefaultValue());
   }

   public boolean isAtMinimum(CharacterStat stat) {
      return stat.isAtMinimum(this.get(stat));
   }

   public boolean isAtMaximum(CharacterStat stat) {
      return stat.isAtMaximum(this.get(stat));
   }

   public boolean isAboveMinimum(CharacterStat stat) {
      return !this.isAtMinimum(stat);
   }

   public void resetStats() {
      for (CharacterStat stat : CharacterStat.REGISTRY.values()) {
         this.reset(stat);
      }
   }

   @Override
   public String toString() {
      StringBuilder sb = new StringBuilder("Stats{\n");

      for (Entry<CharacterStat, Float> entry : this.stats.entrySet()) {
         sb.append("  ").append(entry.getKey().getId()).append(" = ").append(entry.getValue()).append("\n");
      }

      sb.append("}");
      return sb.toString();
   }

   public float getNicotineStress() {
      float nicotineStress = this.get(CharacterStat.STRESS) + this.get(CharacterStat.NICOTINE_WITHDRAWAL);
      return CharacterStat.STRESS.clamp(nicotineStress);
   }

   public int getNumVisibleZombies() {
      return this.numVisibleZombies;
   }

   public int getNumChasingZombies() {
      return this.lastNumChasingZombies;
   }

   public void setLastNumberChasingZombies(int chasingZombies) {
      this.lastNumChasingZombies = chasingZombies;
   }

   public int getNumVeryCloseZombies() {
      return this.lastVeryCloseZombies;
   }

   public float getLastEndurance() {
      return this.lastEndurance;
   }

   public void setLastEndurance(float endurance) {
      this.lastEndurance = endurance;
   }

   public float getEnduranceDangerWarning() {
      return 0.25F;
   }

   public float getEnduranceWarning() {
      return 0.5F;
   }

   public boolean isEnduranceRecharging() {
      return false;
   }

   public int getVisibleZombies() {
      return this.numVisibleZombies;
   }

   public void setNumVisibleZombies(int numVisibleZombies) {
      this.numVisibleZombies = numVisibleZombies;
   }

   public boolean isTripping() {
      return this.tripping;
   }

   public void setTripping(boolean tripping) {
      this.tripping = tripping;
   }

   public float getTrippingRotAngle() {
      return this.trippingRotAngle;
   }

   public void setTrippingRotAngle(float trippingRotAngle) {
      this.trippingRotAngle = trippingRotAngle;
   }

   public void addTrippingRotAngle(float value) {
      this.trippingRotAngle += value;
   }
}
