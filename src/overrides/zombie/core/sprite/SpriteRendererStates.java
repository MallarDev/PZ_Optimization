package zombie.core.sprite;

public final class SpriteRendererStates {
   private SpriteRenderState populating = new SpriteRenderState(0);
   private SpriteRenderState ready;
   private SpriteRenderState rendering = new SpriteRenderState(2);
   private SpriteRenderState rendered = new SpriteRenderState(1);

   public SpriteRenderState getPopulating() {
      return this.populating;
   }

   public GenericSpriteRenderState getPopulatingActiveState() {
      if (pzopt.DrawRecorder.recording) { // pzopt: tileRecordParallel, a thread recording a tile draw unit appends to its own list
         GenericSpriteRenderState pzoptRec = pzopt.DrawRecorder.stateForThread(); // pzopt
         if (pzoptRec != null) { // pzopt
            return pzoptRec; // pzopt
         } // pzopt
      } // pzopt
      return this.populating.getActiveState();
   }

   public void setPopulating(SpriteRenderState populating) {
      this.populating = populating;
   }

   public SpriteRenderState getReady() {
      return this.ready;
   }

   public void setReady(SpriteRenderState ready) {
      this.ready = ready;
   }

   public SpriteRenderState getRendering() {
      return this.rendering;
   }

   public GenericSpriteRenderState getRenderingActiveState() {
      return this.rendering.getActiveState();
   }

   public void setRendering(SpriteRenderState rendering) {
      this.rendering = rendering;
   }

   public SpriteRenderState getRendered() {
      return this.rendered;
   }

   public void setRendered(SpriteRenderState rendered) {
      this.rendered = rendered;
   }

   public void movePopulatingToReady() {
      this.ready = this.populating;
      this.populating = this.rendered;
      this.rendered = null;
      this.ready.time = System.nanoTime();
      this.ready.onReady();
   }

   public void moveReadyToRendering() {
      this.rendered = this.rendering;
      this.rendering = this.ready;
      this.ready = null;
      this.rendering.onRenderAcquired();
   }
}
