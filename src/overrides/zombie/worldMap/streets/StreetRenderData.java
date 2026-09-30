package zombie.worldMap.streets;

import gnu.trove.iterator.hash.TObjectHashIterator;
import gnu.trove.list.array.TFloatArrayList;
import gnu.trove.set.hash.THashSet;
import java.util.ArrayList;
import org.joml.Matrix4f;
import zombie.core.Core;
import zombie.core.opengl.VBORenderer;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.core.textures.TextureID;
import zombie.iso.IsoLot;
import zombie.iso.MapFiles;
import zombie.worldMap.UIWorldMap;
import zombie.worldMap.WorldMapRenderer;

public final class StreetRenderData {
   private static final THashSet<WorldMapStreet> tempStreetSet = new THashSet();
   final ArrayList<CharLayout> characters = new ArrayList<>();
   final TFloatArrayList lines = new TFloatArrayList();
   final TFloatArrayList polygon = new TFloatArrayList();
   final TFloatArrayList triangles = new TFloatArrayList();
   float centerWorldX;
   float centerWorldY;
   float worldScale;
   TextureID textureId;
   float sdfThreshold;
   float sdfShadow;
   float sdfOutlineThickness;
   float sdfOutlineR;
   float sdfOutlineG;
   float sdfOutlineB;
   float sdfOutlineA;
   boolean editor;

   public void init(UIWorldMap ui, WorldMapRenderer renderer) {
      WorldMapStreet.s_charLayoutPool.releaseAll(this.characters);
      this.characters.clear();
      this.lines.clear();
      this.worldScale = renderer.getWorldScale(renderer.getDisplayZoomF());
      this.centerWorldX = renderer.getCenterWorldX();
      this.centerWorldY = renderer.getCenterWorldY();
      this.triangles.clear();
      this.editor = ui.isMapEditor();
      if (renderer.getBoolean("ShowStreetNames")) {
         WorldMapStreetsV1 streetsAPI = ui.getAPI().getStreetsAPI();
         if (ui.isMapEditor()) {
            EditStreetsV1 editorAPI = streetsAPI.getEditorAPI();
            WorldMapStreets streets = editorAPI.getStreetData();
            if (streets != null) {
               streets.render(ui, this);
            }
         } else {
            boolean bEdits = false;

            for (int i = 0; i < streetsAPI.getStreetDataCount(); i++) {
               WorldMapStreets streets = streetsAPI.getStreetDataByIndex(i);
               if (streets.checkForEdits()) {
                  bEdits = true;
               }
            }

            boolean pzoptRebuilt = bEdits || ui.getWorldMap().combinedStreets.isDirty(); // pzopt: mapStreetCache
            if (bEdits || ui.getWorldMap().combinedStreets.isDirty()) {
               ui.getWorldMap().combinedStreets.setDirty(false);
               ui.getWorldMap().combinedStreets.clear();

               for (int i = 0; i < streetsAPI.getStreetDataCount(); i++) {
                  WorldMapStreets streets = streetsAPI.getStreetDataByIndex(i);
                  ui.getWorldMap().combinedStreets.combine(streets);
               }
            }

            if (!this.pzoptCachedLabels(ui, renderer, pzoptRebuilt)) { // pzopt: mapStreetCache, same view: the last layout
               ui.getWorldMap().combinedStreets.render(ui, this);
               this.pzoptKeepLabels(ui); // pzopt: mapStreetCache
            } // pzopt: mapStreetCache
         }

         WorldMapStreet mouseOverStreet = streetsAPI.getMouseOverStreet();
         if (mouseOverStreet != null) {
            if (renderer.getBoolean("HighlightStreet")) {
               mouseOverStreet.createHighlightPolygons(this.polygon, this.triangles);
               tempStreetSet.clear();
               mouseOverStreet.getOwner().getConnectedStreets(mouseOverStreet, tempStreetSet);
               TObjectHashIterator var11 = tempStreetSet.iterator();

               while (var11.hasNext()) {
                  WorldMapStreet street = (WorldMapStreet)var11.next();
                  if (street.isOnScreen(ui)) {
                     street.createHighlightPolygons(this.polygon, this.triangles);
                  }
               }
            }

            if (ui.isMapEditor()) {
               this.renderObscuredCells(ui);
            }
         }
      }
   }

   /** pzopt: mapStreetCache, one map UI's last street-label layout and what it was laid out for. */
   private static final class PzoptLabels {
      final ArrayList<CharLayout> chars = new ArrayList<>(); // pzopt: mapStreetCache
      Object streets; // pzopt: mapStreetCache
      long stamp; // pzopt: mapStreetCache
      long key; // pzopt: mapStreetCache
      boolean valid; // pzopt: mapStreetCache
      long pendingStamp; // pzopt: mapStreetCache
      long pendingKey; // pzopt: mapStreetCache
   }

   private static final java.util.IdentityHashMap<UIWorldMap, StreetRenderData.PzoptLabels> pzoptLabels = new java.util.IdentityHashMap<>(); // pzopt: mapStreetCache

   /** pzopt: mapStreetCache, copies the kept layout into this.characters when the view, streets, options, style and language are unchanged. */
   private boolean pzoptCachedLabels(UIWorldMap ui, WorldMapRenderer renderer, boolean rebuilt) {
      if (!pzopt.MapStreets.cache()) { // pzopt: mapStreetCache
         return false; // pzopt: mapStreetCache
      } // pzopt: mapStreetCache
      StreetRenderData.PzoptLabels c = pzoptLabels.get(ui); // pzopt: mapStreetCache
      if (c == null) { // pzopt: mapStreetCache
         if (pzoptLabels.size() > 8) { // pzopt: mapStreetCache
            pzoptLabels.clear(); // pzopt: mapStreetCache
         } // pzopt: mapStreetCache
         c = new StreetRenderData.PzoptLabels(); // pzopt: mapStreetCache
         pzoptLabels.put(ui, c); // pzopt: mapStreetCache
      } // pzopt: mapStreetCache
      long stamp = pzopt.MapStreets.viewStamp(ui); // pzopt: mapStreetCache
      long key = pzopt.MapStreets.labelKey(ui, renderer); // pzopt: mapStreetCache
      c.pendingStamp = stamp; // pzopt: mapStreetCache
      c.pendingKey = key; // pzopt: mapStreetCache
      if (rebuilt || !c.valid || c.stamp != stamp || c.key != key || c.streets != ui.getWorldMap().combinedStreets) { // pzopt: mapStreetCache
         c.valid = false; // pzopt: mapStreetCache
         return false; // pzopt: mapStreetCache
      } // pzopt: mapStreetCache
      if (pzopt.Config.DEV_MAP_STREET_CHECK) { // pzopt: devMapStreetCheck, lay out anyway and compare with the kept layout
         ui.getWorldMap().combinedStreets.render(ui, this); // pzopt: devMapStreetCheck
         boolean same = this.characters.size() == c.chars.size(); // pzopt: devMapStreetCheck
         for (int i = 0; same && i < c.chars.size(); i++) { // pzopt: devMapStreetCheck
            same = pzoptSame(c.chars.get(i), this.characters.get(i)); // pzopt: devMapStreetCheck
         } // pzopt: devMapStreetCheck
         pzopt.MapStreets.checked(same, c.chars.size(), this.characters.size()); // pzopt: devMapStreetCheck
         return true; // pzopt: devMapStreetCheck
      } // pzopt: devMapStreetCheck
      for (int i = 0; i < c.chars.size(); i++) { // pzopt: mapStreetCache
         CharLayout src = c.chars.get(i); // pzopt: mapStreetCache
         CharLayout dst = WorldMapStreet.s_charLayoutPool.alloc(); // pzopt: mapStreetCache
         pzoptCopy(src, dst); // pzopt: mapStreetCache
         this.characters.add(dst); // pzopt: mapStreetCache
      } // pzopt: mapStreetCache
      pzopt.MapStreets.cacheHit(); // pzopt: mapStreetCache
      return true; // pzopt: mapStreetCache
   }

   /** pzopt: mapStreetCache, keeps a copy of the layout just made (this.characters) with its keys. */
   private void pzoptKeepLabels(UIWorldMap ui) {
      StreetRenderData.PzoptLabels c = pzopt.MapStreets.cache() ? pzoptLabels.get(ui) : null; // pzopt: mapStreetCache
      if (c == null) { // pzopt: mapStreetCache
         return; // pzopt: mapStreetCache
      } // pzopt: mapStreetCache
      while (c.chars.size() < this.characters.size()) { // pzopt: mapStreetCache
         c.chars.add(new CharLayout()); // pzopt: mapStreetCache
      } // pzopt: mapStreetCache
      while (c.chars.size() > this.characters.size()) { // pzopt: mapStreetCache
         c.chars.remove(c.chars.size() - 1); // pzopt: mapStreetCache
      } // pzopt: mapStreetCache
      for (int i = 0; i < this.characters.size(); i++) { // pzopt: mapStreetCache
         pzoptCopy(this.characters.get(i), c.chars.get(i)); // pzopt: mapStreetCache
      } // pzopt: mapStreetCache
      c.stamp = c.pendingStamp; // pzopt: mapStreetCache
      c.key = c.pendingKey; // pzopt: mapStreetCache
      c.streets = ui.getWorldMap().combinedStreets; // pzopt: mapStreetCache
      c.valid = true; // pzopt: mapStreetCache
      pzopt.MapStreets.cacheMiss(); // pzopt: mapStreetCache
   }

   private static boolean pzoptSame(CharLayout s, CharLayout d) { // pzopt: devMapStreetCheck
      return s.charDef == d.charDef && s.sdfThreshold == d.sdfThreshold && s.sdfShadow == d.sdfShadow && s.r == d.r && s.g == d.g // pzopt: devMapStreetCheck
         && s.b == d.b && s.a == d.a && s.outlineThickness == d.outlineThickness && s.outlineR == d.outlineR && s.outlineG == d.outlineG // pzopt: devMapStreetCheck
         && s.outlineB == d.outlineB && s.outlineA == d.outlineA && java.util.Arrays.equals(s.leftTop, d.leftTop) // pzopt: devMapStreetCheck
         && java.util.Arrays.equals(s.rightTop, d.rightTop) && java.util.Arrays.equals(s.rightBottom, d.rightBottom) // pzopt: devMapStreetCheck
         && java.util.Arrays.equals(s.leftBottom, d.leftBottom); // pzopt: devMapStreetCheck
   }

   private static void pzoptCopy(CharLayout s, CharLayout d) { // pzopt: mapStreetCache
      d.charDef = s.charDef; // pzopt: mapStreetCache
      d.sdfThreshold = s.sdfThreshold; // pzopt: mapStreetCache
      d.sdfShadow = s.sdfShadow; // pzopt: mapStreetCache
      d.r = s.r; // pzopt: mapStreetCache
      d.g = s.g; // pzopt: mapStreetCache
      d.b = s.b; // pzopt: mapStreetCache
      d.a = s.a; // pzopt: mapStreetCache
      d.outlineThickness = s.outlineThickness; // pzopt: mapStreetCache
      d.outlineR = s.outlineR; // pzopt: mapStreetCache
      d.outlineG = s.outlineG; // pzopt: mapStreetCache
      d.outlineB = s.outlineB; // pzopt: mapStreetCache
      d.outlineA = s.outlineA; // pzopt: mapStreetCache
      System.arraycopy(s.leftTop, 0, d.leftTop, 0, 2); // pzopt: mapStreetCache
      System.arraycopy(s.rightTop, 0, d.rightTop, 0, 2); // pzopt: mapStreetCache
      System.arraycopy(s.rightBottom, 0, d.rightBottom, 0, 2); // pzopt: mapStreetCache
      System.arraycopy(s.leftBottom, 0, d.leftBottom, 0, 2); // pzopt: mapStreetCache
   }

   private void renderObscuredCells(UIWorldMap ui) {
      for (MapFiles mapFiles : IsoLot.MapFiles) {
         for (int cellY = mapFiles.minCell300Y; cellY <= mapFiles.maxCell300Y; cellY++) {
            for (int cellX = mapFiles.minCell300X; cellX <= mapFiles.maxCell300X; cellX++) {
               if (mapFiles.hasCell300(cellX, cellY)) {
                  for (int i = 0; i < mapFiles.priority; i++) {
                     MapFiles mapFiles1 = (MapFiles)IsoLot.MapFiles.get(i);
                     if (mapFiles1.hasCell300(cellX, cellY)) {
                        int x1 = cellX * 300;
                        int y1 = cellY * 300;
                        int x2 = (cellX + 1) * 300;
                        int y2 = (cellY + 1) * 300;
                        this.addLine(ui, x1, y1, x2, y1, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F);
                        this.addLine(ui, x2, y1, x2, y2, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F);
                        this.addLine(ui, x2, y2, x1, y2, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F);
                        this.addLine(ui, x1, y2, x1, y1, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F);
                     }
                  }
               }
            }
         }
      }
   }

   private void addLine(UIWorldMap ui, float x1, float y1, float x2, float y2, float r, float g, float b, float a, float thickness) {
      this.lines.add(ui.getAPI().worldToUIX(x1, y1));
      this.lines.add(ui.getAPI().worldToUIY(x1, y1));
      this.lines.add(ui.getAPI().worldToUIX(x2, y2));
      this.lines.add(ui.getAPI().worldToUIY(x2, y2));
      this.lines.add(r);
      this.lines.add(g);
      this.lines.add(b);
      this.lines.add(a);
      this.lines.add(thickness);
   }

   public void render(WorldMapRenderer renderer, VBORenderer vbor) {
      this.textureId = null;
      this.sdfThreshold = Float.NaN;
      this.sdfShadow = Float.NaN;
      this.sdfOutlineThickness = Float.NaN;
      this.sdfOutlineR = Float.NaN;
      this.sdfOutlineG = Float.NaN;
      this.sdfOutlineB = Float.NaN;
      this.sdfOutlineA = Float.NaN;
      Shader shader = ShaderManager.instance.getOrCreateShader("vboRenderer_SDF", true, false);
      if (shader.getShaderProgram().isCompiled()) {
         float z = 0.0F;
         if (!this.triangles.isEmpty()) {
            float f = 0.66F;
            float r = 0.12941177F;
            float g = 0.33647063F;
            float b = 0.63670594F;
            float a = this.editor ? 0.25F : 1.0F;
            vbor.startRun(vbor.formatPositionColor);
            vbor.setMode(4);

            for (int i = 0; i < this.triangles.size(); i += 6) {
               int n = i;
               float x0 = (this.triangles.get(n++) - this.centerWorldX) * this.worldScale;
               float y0 = (this.triangles.get(n++) - this.centerWorldY) * this.worldScale;
               float x1 = (this.triangles.get(n++) - this.centerWorldX) * this.worldScale;
               float y1 = (this.triangles.get(n++) - this.centerWorldY) * this.worldScale;
               float x2 = (this.triangles.get(n++) - this.centerWorldX) * this.worldScale;
               float y2 = (this.triangles.get(n) - this.centerWorldY) * this.worldScale;
               vbor.addTriangle(x0, y0, 0.0F, x1, y1, 0.0F, x2, y2, 0.0F, 0.12941177F, 0.33647063F, 0.63670594F, a);
            }

            vbor.endRun();
         }

         Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
         projection.setOrtho2D(0.0F, renderer.getWidth(), renderer.getHeight(), 0.0F);
         vbor.cmdPushAndLoadMatrix(5889, projection);
         Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
         modelView.identity();
         vbor.cmdPushAndLoadMatrix(5888, modelView);
         boolean bRunInProgress = false;

         for (int i = 0; i < this.characters.size(); i++) {
            CharLayout charLayout = this.characters.get(i);
            boolean bStartRun = this.checkShaderUniforms(charLayout);
            if (bStartRun) {
               if (bRunInProgress) {
                  vbor.endRun();
               }

               vbor.startRun(vbor.formatPositionColorUv);
               vbor.setMode(7);
               vbor.setShaderProgram(shader.getShaderProgram());
               this.checkShaderUniforms(charLayout, vbor);
               bRunInProgress = true;
            }

            float u0 = charLayout.charDef.image.xStart;
            float v0 = charLayout.charDef.image.yStart;
            float u1 = charLayout.charDef.image.xEnd;
            float v1 = charLayout.charDef.image.yStart;
            float u2 = charLayout.charDef.image.xEnd;
            float v2 = charLayout.charDef.image.yEnd;
            float u3 = charLayout.charDef.image.xStart;
            float v3 = charLayout.charDef.image.yEnd;
            vbor.addQuad(
               (float)charLayout.leftTop[0],
               (float)charLayout.leftTop[1],
               u0,
               v0,
               (float)charLayout.rightTop[0],
               (float)charLayout.rightTop[1],
               u1,
               v1,
               (float)charLayout.rightBottom[0],
               (float)charLayout.rightBottom[1],
               u2,
               v2,
               (float)charLayout.leftBottom[0],
               (float)charLayout.leftBottom[1],
               u3,
               v3,
               0.0F,
               charLayout.r,
               charLayout.g,
               charLayout.b,
               charLayout.a
            );
         }

         if (bRunInProgress) {
            vbor.endRun();
         }

         vbor.startRun(vbor.formatPositionColor);
         vbor.setMode(1);

         for (int i = 0; i < this.lines.size() / 9; i++) {
            int n = i * 9;
            float x1 = this.lines.get(n++);
            float y1 = this.lines.get(n++);
            float x2 = this.lines.get(n++);
            float y2 = this.lines.get(n++);
            float r = this.lines.get(n++);
            float g = this.lines.get(n++);
            float b = this.lines.get(n++);
            float a = this.lines.get(n++);
            float thickness = this.lines.get(n++);
            vbor.addLine(x1, y1, 0.0F, x2, y2, 0.0F, r, g, b, a);
         }

         vbor.endRun();
         vbor.cmdPopMatrix(5889);
         vbor.cmdPopMatrix(5888);
         Core.getInstance().projectionMatrixStack.release(projection);
         Core.getInstance().modelViewMatrixStack.release(modelView);
         vbor.flush();
      }
   }

   boolean checkShaderUniforms(CharLayout charLayout) {
      if (charLayout.charDef.image.getTextureId() != this.textureId) {
         return true;
      } else if (this.sdfThreshold != charLayout.sdfThreshold) {
         return true;
      } else if (this.sdfShadow != charLayout.sdfShadow) {
         return true;
      } else {
         return this.sdfOutlineThickness != charLayout.outlineThickness
            ? true
            : this.sdfOutlineR != charLayout.outlineR
               || this.sdfOutlineG != charLayout.outlineG
               || this.sdfOutlineB != charLayout.outlineB
               || this.sdfOutlineA != charLayout.outlineA;
      }
   }

   void checkShaderUniforms(CharLayout charLayout, VBORenderer vbor) {
      if (charLayout.charDef.image.getTextureId() != this.textureId) {
         vbor.setTextureID(this.textureId = charLayout.charDef.image.getTextureId());
      }

      if (this.sdfThreshold != charLayout.sdfThreshold) {
         vbor.cmdShader1f("sdfThreshold", this.sdfThreshold = charLayout.sdfThreshold);
      }

      if (this.sdfShadow != charLayout.sdfShadow) {
         vbor.cmdShader1f("sdfShadow", this.sdfShadow = charLayout.sdfShadow);
      }

      if (this.sdfOutlineThickness != charLayout.outlineThickness) {
         vbor.cmdShader1f("sdfOutlineThick", this.sdfOutlineThickness = charLayout.outlineThickness);
      }

      if (this.sdfOutlineR != charLayout.outlineR
         || this.sdfOutlineG != charLayout.outlineG
         || this.sdfOutlineB != charLayout.outlineB
         || this.sdfOutlineA != charLayout.outlineA) {
         vbor.cmdShader4f(
            "sdfOutlineColor",
            this.sdfOutlineR = charLayout.outlineR,
            this.sdfOutlineG = charLayout.outlineG,
            this.sdfOutlineB = charLayout.outlineB,
            this.sdfOutlineA = charLayout.outlineA
         );
      }
   }
}
