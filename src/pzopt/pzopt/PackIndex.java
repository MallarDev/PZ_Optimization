package pzopt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;

/**
 * Where each page's PNG data ends inside a version-0 texture pack
 * (docs/plan-instant-load.md, B5). The stock reader finds the end of every
 * page by reading the whole pack byte by byte through a synchronized stream
 * looking for the end marker: half a second of boot over 526 MB of packs. The
 * end offsets never change for a given pack file, so they are stored once in
 * <cache dir>/pzopt/packs/<name>.idx (keyed by size, mtime and the pzopt build;
 * pzopt.CacheDir empties the directory when another build filled it) and the
 * reader seeks instead of scanning.
 */
public final class PackIndex {
   private static final int MAGIC = 0x505A5049; // "PZPI"
   public final HashMap<Long, Long> endByStart = new HashMap<>();
   public boolean dirty;
   private final File file;
   private final long size;
   private final long mtime;

   private PackIndex(File file, long size, long mtime) {
      this.file = file;
      this.size = size;
      this.mtime = mtime;
   }

   public static PackIndex open(String packPath) {
      try {
         File pack = new File(packPath);
         File dir = CacheDir.open("packs");
         PackIndex idx = new PackIndex(new File(dir, pack.getName() + ".idx"), pack.length(), pack.lastModified());
         if (idx.file.isFile()) {
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(idx.file)))) {
               if (in.readInt() == MAGIC && in.readUTF().equals(CacheDir.BUILD_KEY) && in.readLong() == idx.size && in.readLong() == idx.mtime) {
                  int n = in.readInt();
                  for (int i = 0; i < n; i++) {
                     idx.endByStart.put(in.readLong(), in.readLong());
                  }
               }
            } catch (Exception e) {
               idx.endByStart.clear();
            }
         }
         return idx;
      } catch (Exception e) {
         return null;
      }
   }

   public void save() {
      if (!this.dirty) {
         return;
      }
      try {
         File tmp = new File(this.file.getPath() + ".tmp");
         try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeUTF(CacheDir.BUILD_KEY);
            out.writeLong(this.size);
            out.writeLong(this.mtime);
            out.writeInt(this.endByStart.size());
            for (java.util.Map.Entry<Long, Long> e : this.endByStart.entrySet()) {
               out.writeLong(e.getKey());
               out.writeLong(e.getValue());
            }
         }
         java.nio.file.Files.move(tmp.toPath(), this.file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
         this.dirty = false;
      } catch (Exception e) {
         Log.warn("pack index " + this.file + ": " + e);
      }
   }
}
