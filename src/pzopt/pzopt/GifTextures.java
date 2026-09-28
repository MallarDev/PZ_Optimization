package pzopt;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import zombie.ZomboidFileSystem;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.ImageData;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureID;
import zombie.core.utils.DirectBufferAllocator;
import zombie.core.math.PZMath;
import zombie.core.utils.WrappedBuffer;

/**
 * Animated GIFs as game textures, for the stock-vs-optimized previews of the "Optimizations" options tab
 * ({@code media/lua/client/pzopt/pzopt_optimizations_options.lua}, the pair of clips under
 * {@code media/ui/pzopt/compare/}). The game only loads PNG (and its own pack formats), so a GIF is decoded here
 * with ImageIO on a daemon thread, composited frame by frame (GIF frames are deltas with a disposal rule each),
 * and turned into one {@link Texture} per frame on the game thread as the Lua asks for frames: at most
 * {@link #UPLOADS_PER_CALL} per call, so a clip fades in over a few UI frames instead of one long one. A
 * frame's texture is created uncompressed whatever the "texture compression" option says (the driver's
 * on-upload compression would cost milliseconds per frame on the game thread).
 *
 * <p>Memory: a texture is padded to a power of two, so frames are capped at {@link #MAX_WIDTH} px wide (a
 * wider GIF is scaled) and {@link #MAX_FRAMES} frames (a longer GIF is thinned, the skipped frames' delays
 * folded into the kept ones), i.e. at most 512x256x4 bytes x 96 = 48 MB of VRAM per clip (the shipped clips are
 * 24 fps x 4 s). Only the last
 * {@link #KEEP_CLIPS} clips asked for stay loaded; the rest are destroyed on the render thread. Nothing here
 * runs unless the tab is open, and {@link #releaseAll()} frees everything when the options screen closes.
 *
 * <p>Exposed to Lua through the {@code PerformanceSettings} override ({@code getPzoptGifFrame} and friends).
 */
public final class GifTextures {
   public static final int MAX_WIDTH = 512;
   public static final int MAX_FRAMES = 96;
   public static final int KEEP_CLIPS = 2;
   static final int UPLOADS_PER_CALL = 4;
   static final int DEFAULT_DELAY_MS = 100;

   /** A decoded clip: composited RGBA frames (row-major, {@code width*4} bytes a row) and their delays. */
   public static final class Decoded {
      public final int width;
      public final int height;
      public final byte[][] rgba;
      public final int[] delaysMs;

      Decoded(int width, int height, byte[][] rgba, int[] delaysMs) {
         this.width = width;
         this.height = height;
         this.rgba = rgba;
         this.delaysMs = delaysMs;
      }

      public int totalMs() {
         int t = 0;
         for (int d : delaysMs) {
            t += d;
         }
         return Math.max(t, 1);
      }
   }

   private static final class Clip {
      final String path;
      volatile String state = "loading"; // loading | ready | missing | error
      volatile Decoded decoded;
      Texture[] frames;
      int uploaded;
      long lastUsedMs;

      Clip(String path) {
         this.path = path;
      }
   }

   private static final Map<String, Clip> clips = new HashMap<>(); // game thread only
   private static ExecutorService decoder;

   private GifTextures() {
   }

   private static synchronized ExecutorService decoder() {
      if (decoder == null) {
         decoder = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pzopt-gif-decode");
            t.setDaemon(true);
            return t;
         });
      }
      return decoder;
   }

   /**
    * Where a game-dir-relative path ({@code media/ui/...}) lives: the game's media folder (the Steam layout, or the
    * .app's on macOS) is {@code ZomboidFileSystem}'s work dir, so the leading {@code media/} is taken off.
    */
   static File resolve(String rel) {
      File f = new File(rel);
      if (f.isAbsolute()) {
         return f;
      }
      try {
         if (ZomboidFileSystem.instance != null && rel.startsWith("media/")) {
            File media = ZomboidFileSystem.instance.getMediaFile(rel.substring("media/".length()));
            if (media != null) {
               return media;
            }
         }
      } catch (Throwable ignored) {
         // the file system is not set up this early; fall through to the working directory
      }
      return f;
   }

   /** "loading", "ready", "missing" or "error"; asking for a clip starts its decode. */
   public static String state(String path) {
      return clip(path, System.currentTimeMillis()).state;
   }

   public static int width(String path) {
      Decoded d = clip(path, System.currentTimeMillis()).decoded;
      return d == null ? 0 : d.width;
   }

   public static int height(String path) {
      Decoded d = clip(path, System.currentTimeMillis()).decoded;
      return d == null ? 0 : d.height;
   }

   public static int frameCount(String path) {
      Decoded d = clip(path, System.currentTimeMillis()).decoded;
      return d == null ? 0 : d.rgba.length;
   }

   /**
    * The frame to show at {@code nowMs} (any clock; the clip loops over its own total delay), or null while it
    * decodes or when the file is missing. Uploads a few pending frames per call.
    */
   public static Texture frame(String path, long nowMs) {
      Clip c = clip(path, nowMs);
      Decoded d = c.decoded;
      if (d == null || c.frames == null) {
         return null;
      }
      if (c.uploaded < c.frames.length) {
         upload(c, d);
      }
      if (c.uploaded == 0) {
         return null;
      }
      int index = frameAt(d, nowMs);
      if (index >= c.uploaded) {
         index = c.uploaded - 1;
      }
      return c.frames[index];
   }

   static int frameAt(Decoded d, long nowMs) {
      long t = Math.floorMod(nowMs, (long) d.totalMs());
      for (int i = 0; i < d.delaysMs.length; i++) {
         t -= d.delaysMs[i];
         if (t < 0) {
            return i;
         }
      }
      return d.delaysMs.length - 1;
   }

   private static Clip clip(String path, long nowMs) {
      Clip c = clips.get(path);
      if (c == null) {
         c = new Clip(path);
         clips.put(path, c);
         final Clip started = c;
         File f = resolve(path);
         if (!f.isFile()) {
            c.state = "missing";
         } else {
            decoder().execute(() -> {
               try (InputStream in = new java.io.FileInputStream(f)) {
                  Decoded d = decode(in, MAX_WIDTH, MAX_FRAMES);
                  started.decoded = d;
                  started.state = "ready";
               } catch (Throwable t) {
                  Log.warn("gif " + path + ": " + t);
                  started.state = "error";
               }
            });
         }
      }
      c.lastUsedMs = nowMs;
      if (c.decoded != null && c.frames == null) {
         c.frames = new Texture[c.decoded.rgba.length];
         evict(nowMs);
      }
      return c;
   }

   /** Keep the {@link #KEEP_CLIPS} most recently used clips with textures; free the rest. */
   private static void evict(long nowMs) {
      while (true) {
         Clip oldest = null;
         int held = 0;
         for (Clip c : clips.values()) {
            if (c.frames == null) {
               continue;
            }
            held++;
            if (oldest == null || c.lastUsedMs < oldest.lastUsedMs) {
               oldest = c;
            }
         }
         if (held <= KEEP_CLIPS || oldest == null) {
            return;
         }
         release(oldest.path);
      }
   }

   private static void upload(Clip c, Decoded d) {
      boolean compress = TextureID.useCompressionOption;
      TextureID.useCompressionOption = false;
      try {
         for (int n = 0; n < UPLOADS_PER_CALL && c.uploaded < c.frames.length; n++) {
            int i = c.uploaded;
            c.frames[i] = texture(c.path + "#" + i, d.width, d.height, d.rgba[i]);
            d.rgba[i] = null; // the pixels live on the GPU now
            c.uploaded++;
         }
      } finally {
         TextureID.useCompressionOption = compress;
      }
   }

   /** One game texture from RGBA rows; blocks on the render thread once (the upload), like a Steam avatar does. */
   private static Texture texture(String name, int width, int height, byte[] rgba) {
      int widthHw = Math.max(2, PZMath.smallestEncompassingPowerOfTwo(width)); // ImageUtils.getNextPowerOfTwoHW (gone in 42.21)
      int heightHw = Math.max(2, PZMath.smallestEncompassingPowerOfTwo(height)); // ImageUtils.getNextPowerOfTwoHW (gone in 42.21)
      WrappedBuffer wb = DirectBufferAllocator.allocate(widthHw * heightHw * 4);
      ByteBuffer buf = wb.getBuffer();
      buf.clear();
      int row = width * 4;
      int stride = widthHw * 4;
      for (int y = 0; y < height; y++) {
         buf.position(y * stride);
         buf.put(rgba, y * row, row);
      }
      buf.position(0);
      buf.limit(widthHw * heightHw * 4);
      ImageData img = new ImageData(width, height, wb);
      TextureID id = new TextureID(img); // uploads on the render thread and disposes the buffer
      return new Texture(id, name);
   }

   /** Free a clip's textures (on the render thread) and forget it; a later ask decodes it again. */
   public static void release(String path) {
      Clip c = clips.remove(path);
      if (c == null || c.frames == null) {
         return;
      }
      final Texture[] frames = c.frames;
      c.frames = null;
      c.decoded = null;
      RenderThread.queueInvokeOnRenderContext(() -> {
         for (Texture t : frames) {
            if (t != null) {
               t.destroy();
            }
         }
      });
   }

   public static void releaseAll() {
      for (String p : new ArrayList<>(clips.keySet())) {
         release(p);
      }
      clips.clear();
   }

   /** Every clip's textures that are loaded right now (for the tab's footer and the log). */
   public static int loadedFrames() {
      int n = 0;
      for (Clip c : clips.values()) {
         n += c.uploaded;
      }
      return n;
   }

   // ---------------------------------------------------------------- decoding (no game classes; unit-tested)

   /**
    * Decode an animated GIF into composited RGBA frames. Frames wider than {@code maxWidth} are scaled down
    * (aspect kept); more than {@code maxFrames} frames are thinned evenly, the dropped frames' delays added to
    * the kept frame before them. A frame with no delay gets {@link #DEFAULT_DELAY_MS}.
    */
   public static Decoded decode(InputStream in, int maxWidth, int maxFrames) throws IOException {
      Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
      if (!readers.hasNext()) {
         throw new IOException("no GIF reader in this JRE");
      }
      ImageReader reader = readers.next();
      try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
         reader.setInput(iis, false, false);
         int count = reader.getNumImages(true);
         if (count <= 0) {
            throw new IOException("no frames");
         }
         int screenW = 0;
         int screenH = 0;
         IIOMetadata stream = reader.getStreamMetadata();
         if (stream != null) {
            Node root = stream.getAsTree("javax_imageio_gif_stream_1.0");
            Node lsd = child(root, "LogicalScreenDescriptor");
            if (lsd != null) {
               screenW = attr(lsd, "logicalScreenWidth", 0);
               screenH = attr(lsd, "logicalScreenHeight", 0);
            }
         }
         // pass 1: frame geometry, so the canvas covers every frame even when the header lies
         BufferedImage first = reader.read(0);
         Node meta0 = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");
         Node desc0 = child(meta0, "ImageDescriptor");
         int w = Math.max(screenW, attr(desc0, "imageLeftPosition", 0) + first.getWidth());
         int h = Math.max(screenH, attr(desc0, "imageTopPosition", 0) + first.getHeight());
         BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
         Graphics2D g = canvas.createGraphics();
         int outW = w;
         int outH = h;
         if (w > maxWidth) {
            outW = maxWidth;
            outH = Math.max(1, (int) Math.round(h * (double) maxWidth / w));
         }
         List<byte[]> frames = new ArrayList<>();
         List<Integer> delays = new ArrayList<>();
         BufferedImage previous = null;
         for (int i = 0; i < count; i++) {
            BufferedImage frame = i == 0 ? first : reader.read(i);
            Node meta = i == 0 ? meta0 : reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
            Node desc = child(meta, "ImageDescriptor");
            Node gce = child(meta, "GraphicControlExtension");
            int left = attr(desc, "imageLeftPosition", 0);
            int top = attr(desc, "imageTopPosition", 0);
            int delay = gce == null ? 0 : attr(gce, "delayTime", 0) * 10;
            String disposal = gce == null ? "none" : gce.getAttributes().getNamedItem("disposalMethod").getNodeValue();
            if (delay <= 0) {
               delay = DEFAULT_DELAY_MS;
            }
            if ("restoreToPrevious".equals(disposal)) {
               previous = copy(canvas);
            }
            g.setComposite(java.awt.AlphaComposite.SrcOver);
            g.drawImage(frame, left, top, null);
            boolean keep = i == 0 || (long) i * maxFrames / count != (long) (i - 1) * maxFrames / count;
            if (keep && frames.size() < maxFrames) {
               frames.add(rgba(canvas, outW, outH));
               delays.add(delay);
            } else {
               delays.set(delays.size() - 1, delays.get(delays.size() - 1) + delay);
            }
            if ("restoreToBackgroundColor".equals(disposal)) {
               g.setComposite(java.awt.AlphaComposite.Clear);
               g.fillRect(left, top, frame.getWidth(), frame.getHeight());
            } else if ("restoreToPrevious".equals(disposal) && previous != null) {
               g.setComposite(java.awt.AlphaComposite.Src);
               g.drawImage(previous, 0, 0, null);
            }
         }
         g.dispose();
         int[] d = new int[delays.size()];
         for (int i = 0; i < d.length; i++) {
            d[i] = delays.get(i);
         }
         return new Decoded(outW, outH, frames.toArray(new byte[0][]), d);
      } finally {
         reader.dispose();
      }
   }

   private static BufferedImage copy(BufferedImage src) {
      BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = c.createGraphics();
      g.setComposite(java.awt.AlphaComposite.Src);
      g.drawImage(src, 0, 0, null);
      g.dispose();
      return c;
   }

   /** The canvas as RGBA bytes at {@code outW x outH} (bilinear when scaled); opaque black where nothing was drawn. */
   private static byte[] rgba(BufferedImage canvas, int outW, int outH) {
      BufferedImage src = canvas;
      if (outW != canvas.getWidth() || outH != canvas.getHeight()) {
         src = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
         Graphics2D g = src.createGraphics();
         g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
         g.drawImage(canvas, 0, 0, outW, outH, null);
         g.dispose();
      }
      int[] argb = src.getRGB(0, 0, outW, outH, null, 0, outW);
      byte[] out = new byte[outW * outH * 4];
      for (int i = 0, o = 0; i < argb.length; i++, o += 4) {
         int p = argb[i];
         int a = p >>> 24;
         if (a == 0) {
            p = 0xFF000000;
            a = 255;
         }
         out[o] = (byte) (p >> 16);
         out[o + 1] = (byte) (p >> 8);
         out[o + 2] = (byte) p;
         out[o + 3] = (byte) a;
      }
      return out;
   }

   private static Node child(Node parent, String name) {
      if (parent == null) {
         return null;
      }
      NodeList kids = parent.getChildNodes();
      for (int i = 0; i < kids.getLength(); i++) {
         if (name.equals(kids.item(i).getNodeName())) {
            return kids.item(i);
         }
      }
      return null;
   }

   private static int attr(Node node, String name, int fallback) {
      if (node == null || node.getAttributes() == null) {
         return fallback;
      }
      Node a = node.getAttributes().getNamedItem(name);
      if (a == null) {
         return fallback;
      }
      try {
         return Integer.parseInt(a.getNodeValue());
      } catch (NumberFormatException e) {
         return fallback;
      }
   }

   /** Build a small GIF in memory (tests): frames of solid colours, {@code delayCs} centiseconds each. */
   static byte[] encode(int width, int height, int[] colours, int delayCs) throws IOException {
      javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
         writer.setOutput(ios);
         writer.prepareWriteSequence(null);
         for (int colour : colours) {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new java.awt.Color(colour));
            g.fillRect(0, 0, width, height);
            g.dispose();
            javax.imageio.ImageTypeSpecifier type = javax.imageio.ImageTypeSpecifier.createFromRenderedImage(img);
            IIOMetadata meta = writer.getDefaultImageMetadata(type, writer.getDefaultWriteParam());
            String format = meta.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
            IIOMetadataNode gce = (IIOMetadataNode) child(root, "GraphicControlExtension");
            if (gce == null) {
               gce = new IIOMetadataNode("GraphicControlExtension");
               root.appendChild(gce);
            }
            gce.setAttribute("disposalMethod", "none");
            gce.setAttribute("userInputFlag", "FALSE");
            gce.setAttribute("transparentColorFlag", "FALSE");
            gce.setAttribute("delayTime", Integer.toString(delayCs));
            gce.setAttribute("transparentColorIndex", "0");
            meta.setFromTree(format, root);
            writer.writeToSequence(new javax.imageio.IIOImage(img, null, meta), writer.getDefaultWriteParam());
         }
         writer.endWriteSequence();
      } finally {
         writer.dispose();
      }
      return bytes.toByteArray();
   }
}
