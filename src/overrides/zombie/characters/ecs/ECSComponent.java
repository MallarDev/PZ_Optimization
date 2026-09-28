package zombie.characters.ecs;

import zombie.util.Type;

public abstract class ECSComponent {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.characters.ecs.ECSComponent");
   }

   private final Class<? extends ECSComponent> ecsClass = getECSClass((Class<? extends ECSComponent>)this.getClass());
   private ECSEntity ecsOwner;

   // pzopt: ecsLookupFast. The walk from a component class up to the one just below ECSComponent (the map key) ran on every
   // component lookup, i.e. on every getStateMachine / getActionContext / getVariable of every character; the answer per
   // class never changes, so it is memoised in a ClassValue (one cache probe per lookup).
   private static final ClassValue<Class<?>> PZOPT_ECS_CLASS = new ClassValue<Class<?>>() {
      @Override
      protected Class<?> computeValue(Class<?> clazz) {
         return pzoptWalkEcsClass(clazz);
      }
   };

   private static Class<?> pzoptWalkEcsClass(Class<?> clazz) {
      Class<?> foundEcsClass = null;

      for (Class<?> c = clazz; c != null && c != ECSComponent.class; c = c.getSuperclass()) {
         foundEcsClass = c;
      }

      return foundEcsClass;
   }

   public Class<? extends ECSComponent> getECSClass() {
      return this.ecsClass;
   }

   public static Class<? extends ECSComponent> getECSClass(Class<? extends ECSComponent> clazz) {
      if (clazz != null && pzopt.Overrides.enabled() && pzopt.Config.ECS_LOOKUP_FAST) { // pzopt: ecsLookupFast
         return (Class<? extends ECSComponent>)PZOPT_ECS_CLASS.get(clazz);
      }

      Class<?> foundEcsClass = null;

      for (Class<?> c = clazz; c != null && c != ECSComponent.class; c = c.getSuperclass()) {
         foundEcsClass = c;
      }

      return (Class<? extends ECSComponent>)foundEcsClass;
   }

   public ECSEntity getECSOwnerEntity() {
      return this.ecsOwner;
   }

   public <EntityType extends ECSEntity> void setECSOwnerEntity(EntityType ownerEntity) {
      if (this.ecsOwner != ownerEntity) {
         ECSEntity prevOwner = this.ecsOwner;
         this.ecsOwner = ownerEntity;
         if (prevOwner != null) {
            prevOwner.removeECSComponent(this);
         }

         if (this.ecsOwner != null) {
            this.ecsOwner.setECSComponent(this);
         }
      }
   }

   public <EntityType extends ECSEntity> EntityType getECSOwnerEntity(Class<EntityType> entityTypeClass) {
      return entityTypeClass.cast(this.getECSOwnerEntity());
   }

   public <OwnerType> OwnerType tryGetECSOwnerEntityAs(Class<? extends OwnerType> ownerTypeClass) {
      return (OwnerType)Type.tryCastTo(this.getECSOwnerEntity(), ownerTypeClass);
   }
}
