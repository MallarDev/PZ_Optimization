package zombie.viewCone;

import org.lwjgl.opengl.GL13;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

public class ChunkRenderShader extends Shader {
   public ChunkRenderShader(String name) {
      super(name);
      pzopt.Overrides.onClassLoadedQuiet("zombie.viewCone.ChunkRenderShader"); // pzopt: marker so the game log shows the loose class was loaded
   }

   public void startMainThread(TextureDraw texd, int playerIndex) {
   }

   public void startRenderThread(TextureDraw texd) {
      if (pzopt.CompositeTiming.ON) pzopt.CompositeTiming.begin(); // pzopt: devCompositeTiming
      this.getProgram().setValue("DEPTH", texd.tex1, 1);
      GL13.glActiveTexture(33984);
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      Texture.lastTextureID = 0;
      SpriteRenderer.ringBuffer.shaderChangedTexture1();
      this.getProgram().setValue("chunkDepth", texd.chunkDepth);
      if (pzopt.CompositeTiming.ON) pzopt.CompositeTiming.step(0); // pzopt
      pzopt.PixelLight.chunkDraw(this, texd); // pzopt: pixelLight, a chunk texture no light reaches switches to the light-free variant; the first draw of a frame on a program sets its light uniforms (viewport as drawn)
      if (pzopt.CompositeTiming.ON) pzopt.CompositeTiming.step(1); // pzopt
      pzopt.Ssr.chunkDraw(texd); // pzopt: reflections, the composite's scatter uniforms (once per program per frame)
      if (pzopt.CompositeTiming.ON) pzopt.CompositeTiming.step(2); // pzopt
      pzopt.CloudShadow.chunkDraw(texd); // pzopt: cloudShadows, the cloud uniforms (once per program per frame) and this texture's direct-sun share
      if (pzopt.CompositeTiming.ON) pzopt.CompositeTiming.step(3); // pzopt
      pzopt.Relief.chunkDraw(texd); // pzopt: relief, this texture's relief code and the key light for the sun / moon relief (set when they change)
      if (pzopt.CompositeTiming.ON) pzopt.CompositeTiming.step(4); // pzopt
      pzopt.GodRays.chunkDraw(); // pzopt: god rays, the haze uniforms (once per program per frame)
      if (pzopt.CompositeTiming.ON) pzopt.CompositeTiming.step(5); // pzopt
      pzopt.Sway.chunkDraw(this, texd); // pzopt: foliage sway, the variant for a texture with swaying plants, the wind (once per program per frame) and its sway attributes
      if (pzopt.CompositeTiming.ON) pzopt.CompositeTiming.step(6); // pzopt
      pzopt.SpriteFilter.afterChunkStart(); // pzopt: sprite filter, the chunk texture's bind that follows takes the composite's magnification filter
      if (pzopt.CompositeTiming.ON) { pzopt.CompositeTiming.step(7); pzopt.CompositeTiming.end(); } // pzopt
   }

   public void onCompileSuccess(ShaderProgram sender) {
      this.Start();
      sender.setSamplerUnit("DIFFUSE", 0);
      sender.setSamplerUnit("DEPTH", 1);
      this.End();
   }
}
