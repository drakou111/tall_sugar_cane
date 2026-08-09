package dev.drakou111.sugarcane;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The join table {@code crossfind}'s first pass builds, on disk.
 *
 * <p>The same idea as {@code TargetCache} and for the same reason: the expensive half of a
 * search should not have to be redone. It is worth more here than a plain checkpoint, because
 * joins go as {@code |table| x streamed}. A table that keeps growing makes every later run more
 * productive than the one before it, so a campaign is not ten independent runs but one that
 * compounds — and the table does not depend on the world seed at all, so it is the natural thing
 * for several people to build together.
 *
 * <h2>Coverage is a set of ranges, not a high-water mark</h2>
 *
 * <p>A single {@code sampledThrough} would be a lie the moment two machines contributed: their
 * ranges are disjoint and scattered, not a prefix. So the header carries the actual list of
 * sample ranges scanned. That makes {@link #covered} an honest total, {@link #nextFrom} a place
 * to continue that nobody has taken, and {@link #overlap} able to say when two contributors
 * duplicated each other's work.
 *
 * <h2>What resuming does and does not cover</h2>
 *
 * <p>Each run scans a fresh slice: pass 1 extends the table with it, and pass 2 streams that
 * slice against the <em>whole</em> table. So a run meets every chain stored before it, and the
 * pairs it misses are new-stored against old-streamed. Symmetry does not recover them, because
 * the two sides run different filters — an ending and a beginning.
 *
 * <p>So k equal slices cover {@code (k+1)/(2k)} of the pairs one run over the same total would:
 * 75% for two, tending to 50% for many. Measured on two 2M slices at height 16 against a single
 * 4M run: the table came out identical at 16,438 chains, and joins were 59 + 79 = 138 against
 * 202, or 68%. <b>Prefer few large slices.</b> The file is a safety net and an accumulator; it is
 * not a way to turn ten short runs into one long one.
 *
 * <p>Entries are {@code (key, decoration seed)}, where the key is chunk-relative and carries the
 * decoration seed's low nibble. The header pins everything that would change what a key means;
 * a file written under different settings is refused rather than silently joined against.
 */
public final class CrossTable {

    /** "XCHF" — cross-chunk find. */
    private static final int MAGIC = 0x58434846;

    /**
     * Bumped when the key convention or the header changes.
     *
     * <p>1 keyed each side in its own chunk's frame and recorded a single high-water mark.
     * 2 records the ranges scanned instead, because a high-water mark cannot describe several
     * people scanning different ground. 3 adds the enum sweeps, which cover a different space
     * and cannot be counted in the same list.
     *
     * <p>Version 2 files still load, with no enum sweeps. The chains in them mean exactly what
     * they meant when written — only the coverage bookkeeping grew — so refusing them would
     * throw away real work for nothing.
     */
    private static final int VERSION = 3;

    /** A half-open span of sample indices somebody scanned. */
    public record Range(long from, long count) {
        public long end() {
            return from + count;
        }
    }

    /**
     * A span of the state enumerator's {@code k}, which is not the sampler's space.
     *
     * <p>Kept apart from {@link Range} rather than folded into it because the two index
     * different things: a sample range names decoration seeds the sampler visited, a sweep names
     * {@code k} values whose states were constructed. Adding a sweep's {@code kCount} to
     * {@link Header#covered} would be a straightforwardly false number, and it is the number
     * collaborators use to decide what ground is still free.
     *
     * <p>{@code lows} and the y band decide what one k actually covers, so they travel with the
     * sweep. Sweeps are nested in {@code lows} — the low-bit sampler multiplies by a fixed odd
     * constant, so a lows=32 sweep visits every state a lows=8 sweep over the same k did — which
     * is why comparing two sweeps means comparing all four fields, not just the k range.
     */
    public record EnumSweep(long kFrom, long kCount, int lows, int minY, int maxY) {
        public long end() {
            return kFrom + kCount;
        }

        /** How many RNG states this sweep actually visited. */
        public long states() {
            return kCount * lows * (long) (maxY - minY + 1);
        }

        /** Whether two sweeps cover k the same way, so their k ranges are comparable at all. */
        public boolean sameShape(EnumSweep other) {
            return lows == other.lows && minY == other.minY && maxY == other.maxY;
        }
    }

    /** What a key means. A file disagreeing on any of it cannot be joined against. */
    public record Header(boolean storeEndings, int storedMin, int count, int featureIndex,
            List<Range> ranges, List<EnumSweep> enumSweeps) {

        /** A table with no enum content, which is every table written before version 3. */
        public Header(boolean storeEndings, int storedMin, int count, int featureIndex,
                List<Range> ranges) {
            this(storeEndings, storedMin, count, featureIndex, ranges, List.of());
        }

        /**
         * Everything except which ground has been covered, which is what contributing adds.
         *
         * <p>Deliberately blind to how the chains were found. A chain is a chain: one the
         * enumerator constructed and one the sampler stumbled on are the same (key, seed) pair
         * and join identically. Making the source part of the shape would split a collaborating
         * group into two pools that cannot pool, for no gain in correctness.
         */
        boolean sameShape(Header other) {
            return storeEndings == other.storeEndings && storedMin == other.storedMin
                    && count == other.count && featureIndex == other.featureIndex;
        }

        /** Total states the enum sweeps visited, which is not comparable to {@link #covered}. */
        public long enumStates() {
            long n = 0;
            for (EnumSweep e : enumSweeps) {
                n += e.states();
            }
            return n;
        }

        /** Past every sweep of this shape — a k nobody with these settings has taken. */
        public long nextEnumFrom(EnumSweep shape) {
            long at = 0;
            for (EnumSweep e : enumSweeps) {
                if (e.sameShape(shape)) {
                    at = Math.max(at, e.end());
                }
            }
            return at;
        }

        /** Total samples scanned, counting any overlap once per contributor. */
        public long covered() {
            long n = 0;
            for (Range r : ranges) {
                n += r.count();
            }
            return n;
        }

        /** Past everything scanned so far — a start no existing range has taken. */
        public long nextFrom() {
            long at = 0;
            for (Range r : ranges) {
                at = Math.max(at, r.end());
            }
            return at;
        }

        @Override
        public String toString() {
            String base = String.format(
                    "%s side, min %d, count %d, feature %d, %d range(s) covering %d",
                    storeEndings ? "ending" : "beginning", storedMin, count, featureIndex,
                    ranges.size(), covered());
            return enumSweeps.isEmpty() ? base
                    : base + String.format(", plus %d enum sweep(s) over %d states",
                            enumSweeps.size(), enumStates());
        }
    }

    public record Loaded(Header header, int[] keys, long[] seeds) {
    }

    private CrossTable() {
    }

    /**
     * How much of {@code a} is also in {@code b}, so a merge can report duplicated effort
     * rather than quietly counting it twice.
     */
    public static long overlap(List<Range> a, List<Range> b) {
        long total = 0;
        for (Range x : a) {
            for (Range y : b) {
                total += Math.max(0L, Math.min(x.end(), y.end()) - Math.max(x.from(), y.from()));
            }
        }
        return total;
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
            out.writeInt(header.ranges().size());
            for (Range r : header.ranges()) {
                out.writeLong(r.from());
                out.writeLong(r.count());
            }
            out.writeInt(header.enumSweeps().size());
            for (EnumSweep e : header.enumSweeps()) {
                out.writeLong(e.kFrom());
                out.writeLong(e.kCount());
                out.writeInt(e.lows());
                out.writeInt(e.minY());
                out.writeInt(e.maxY());
            }
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
        Loaded got = loadAny(path);
        if (got != null && wanted != null && !got.header().sameShape(wanted)) {
            throw new IOException(path + " holds a different search: " + got.header()
                    + ", but this run wants " + wanted);
        }
        return got;
    }

    /** Without a shape check, for merging tables against each other rather than against a run. */
    public static Loaded loadAny(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(path), 1 << 20))) {
            if (in.readInt() != MAGIC) {
                throw new IOException(path + " is not a crossfind table");
            }
            int version = in.readInt();
            // 2 is readable: 3 only appended the enum sweeps, and a version 2 file simply has
            // none. Everything that decides what a key means is unchanged, so its chains join
            // exactly as they always did. Older than that did change the key convention.
            if (version != VERSION && version != 2) {
                throw new IOException(path + " is version " + version + ", expected " + VERSION
                        + " — the format changed, so its joins could land on the wrong block. "
                        + "Delete it and rebuild.");
            }
            boolean storeEndings = in.readBoolean();
            int storedMin = in.readInt();
            int count = in.readInt();
            int featureIndex = in.readInt();
            int rangeCount = in.readInt();
            if (rangeCount < 0 || rangeCount > 1 << 20) {
                throw new IOException(path + " reports " + rangeCount + " ranges");
            }
            List<Range> ranges = new ArrayList<>(rangeCount);
            for (int i = 0; i < rangeCount; i++) {
                ranges.add(new Range(in.readLong(), in.readLong()));
            }
            List<EnumSweep> sweeps = new ArrayList<>();
            if (version >= 3) {
                int sweepCount = in.readInt();
                if (sweepCount < 0 || sweepCount > 1 << 20) {
                    throw new IOException(path + " reports " + sweepCount + " enum sweeps");
                }
                for (int i = 0; i < sweepCount; i++) {
                    sweeps.add(new EnumSweep(in.readLong(), in.readLong(),
                            in.readInt(), in.readInt(), in.readInt()));
                }
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
            return new Loaded(new Header(storeEndings, storedMin, count, featureIndex, ranges,
                    sweeps), keys, seeds);
        }
    }
}
