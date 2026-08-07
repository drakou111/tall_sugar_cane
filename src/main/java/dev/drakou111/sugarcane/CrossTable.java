package dev.drakou111.sugarcane;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The join table {@code crossfind}'s first pass builds, on disk.
 *
 * <p>The same idea as {@code TargetCache} and for the same reason: the expensive half of a
 * search should not have to be redone. It is worth more here than a plain checkpoint, because
 * joins go as {@code |table| x streamed}. A table that keeps growing makes every later run more
 * productive than the one before it, so a night of searching is not ten independent runs but one
 * that compounds.
 *
 * <p><b>What resuming does and does not cover.</b> Each run scans a fresh slice of sample space:
 * pass 1 extends the table with it, and pass 2 streams the same slice against the <em>whole</em>
 * table. So a run meets every chain stored before it, and the pairs it misses are
 * new-stored x old-streamed. Those are not recovered by symmetry — the two sides run different
 * filters, an ending and a beginning.
 *
 * <p>So k equal slices cover {@code (k+1)/(2k)} of the pairs one run over the same total would:
 * 75% for two, tending to 50% for many. Measured on two 2M slices at height 16, against a single
 * 4M run: the table came out identical at 16,438 chains, and joins were 59 + 79 = 138 against
 * 202, or 68%. <b>Prefer few large slices.</b> The file is worth having as a safety net on a run
 * long enough to lose, and as an accumulator across a campaign; it is not a way to turn ten short
 * runs into one long one.
 *
 * <p>Entries are {@code (key, decoration seed)}, where the key is chunk-relative and carries the
 * decoration seed's low nibble. The header pins everything that would change what a key means;
 * a file written under different settings is refused rather than silently joined against.
 */
public final class CrossTable {

    /** "XCHF" — cross-chunk find. */
    private static final int MAGIC = 0x58434846;

    /**
     * Bumped when the key convention changes.
     *
     * <p>Version 1 keys each side in its own chunk's frame. The version before this file
     * existed folded the neighbour offset into the stored key, which would join a table
     * against the wrong block if it were ever read back by this code.
     */
    private static final int VERSION = 1;

    /** What a key means. A file disagreeing on any of it cannot be joined against. */
    public record Header(boolean storeEndings, int storedMin, int count, int featureIndex,
            long sampledThrough) {

        /** Everything except how far it has scanned, which is what resuming updates. */
        boolean sameShape(Header other) {
            return storeEndings == other.storeEndings && storedMin == other.storedMin
                    && count == other.count && featureIndex == other.featureIndex;
        }

        @Override
        public String toString() {
            return String.format("%s side, min %d, count %d, feature %d, scanned through %d",
                    storeEndings ? "ending" : "beginning", storedMin, count, featureIndex,
                    sampledThrough);
        }
    }

    public record Loaded(Header header, int[] keys, long[] seeds) {
    }

    private CrossTable() {
    }

    public static void save(Path path, Header header, int[] keys, long[] seeds, int n)
            throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(tmp), 1 << 20))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeBoolean(header.storeEndings());
            out.writeInt(header.storedMin());
            out.writeInt(header.count());
            out.writeInt(header.featureIndex());
            out.writeLong(header.sampledThrough());
            out.writeInt(n);
            for (int i = 0; i < n; i++) {
                out.writeInt(keys[i]);
                out.writeLong(seeds[i]);
            }
        }
        // Written aside and moved, so a run killed mid-save leaves the previous table intact
        // rather than a truncated one that loads and silently searches less.
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * @return null when the file does not exist, so a first run needs no special case
     * @throws IOException if it exists but was written under settings that would make its
     *                     keys mean something else
     */
    public static Loaded load(Path path, Header wanted) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(path), 1 << 20))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException(path + " is not a crossfind table");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException(path + " is version " + version + ", expected " + VERSION
                        + " — the key convention changed, so its joins would land on the wrong "
                        + "block. Delete it and rebuild.");
            }
            Header got = new Header(in.readBoolean(), in.readInt(), in.readInt(), in.readInt(),
                    in.readLong());
            if (!got.sameShape(wanted)) {
                throw new IOException(path + " holds a different search: " + got
                        + ", but this run wants " + wanted);
            }
            int n = in.readInt();
            if (n < 0) {
                throw new IOException(path + " reports " + n + " entries");
            }
            int[] keys = new int[n];
            long[] seeds = new long[n];
            for (int i = 0; i < n; i++) {
                keys[i] = in.readInt();
                seeds[i] = in.readLong();
            }
            return new Loaded(got, keys, seeds);
        }
    }
}
