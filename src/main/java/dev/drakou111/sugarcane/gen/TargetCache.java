package dev.drakou111.sugarcane.gen;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Saves and reloads a reverse-search target set.
 *
 * <p>Worth doing because the set does not depend on the world seed at all — only on the
 * height, the depth band and the soil filter. Whatever it cost to build, it can be
 * reused against every world seed anyone ever runs. That cost is not small at the
 * heights that matter: about 5.9 ms per member at height 8 against 0.4 ms at height 5,
 * because the acceptance rate q falls faster than the per-test cost does.
 *
 * <p>The header records every parameter that changes what a member <em>means</em>, and
 * loading refuses a file whose header disagrees. A set built for height 7 silently
 * reused for height 8 would look like a working search that could never find anything,
 * which is the failure mode this project keeps running into.
 *
 * <p>{@code sampledThrough} lets a set be extended rather than rebuilt: sampling
 * resumes past the point the last build reached, so growing a set from 200k to 500k
 * costs only the difference.
 *
 * <p>Each target carries a score byte, currently always 0. Not all targets are equally
 * likely to cash in — a chain assuming three earlier placements in the same chunk is
 * worth much less than one assuming none — but the weights have to be measured off real
 * finds rather than guessed, so the field is reserved and unused until they are.
 */
public final class TargetCache {

    private static final int MAGIC = 0x54475431;   // "TGT1"
    /**
     * 4: chains carry a slack budget -- how many foreign placements may land between
     * their own columns. The default is 0, the contiguous window, which keeps 40% of the
     * set for 87.9% of real finds (FINDINGS 6ao). A version 3 file was built with no
     * budget at all, so it is a different set and cannot be extended into this one.
     *
     * <p>3: chains must have strictly increasing shifts. A version 2 file was built when a
     * continuation could read the stream at the same offset as the column under it, which
     * is physically impossible, so most of its members are chains that could never be
     * placed. They are not wrong to search, only wasted, and the file no longer means what
     * its header says — so it is rejected rather than silently reused.
     */
    private static final int VERSION = 4;

    /** Everything that changes what membership means. Loading checks all of it. */
    public record Header(int minHeight, int count, int featureIndex,
                         int baseMinY, int baseMaxY, boolean soilFilter,
                         int maxBaseShift, int maxColumns, int maxSlack,
                         long tested, long sampledThrough) {
    }

    public record Loaded(Header header, long[] targets, byte[] scores) {
    }

    private TargetCache() {
    }

    public static void save(Path path, Header header, long[] targets, byte[] scores)
            throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(header.minHeight());
            out.writeInt(header.count());
            out.writeInt(header.featureIndex());
            out.writeInt(header.baseMinY());
            out.writeInt(header.baseMaxY());
            out.writeBoolean(header.soilFilter());
            out.writeInt(header.maxBaseShift());
            out.writeInt(header.maxColumns());
            out.writeInt(header.maxSlack());
            out.writeLong(header.tested());
            out.writeLong(header.sampledThrough());
            out.writeInt(targets.length);
            for (long target : targets) {
                out.writeLong(target);
            }
            for (int i = 0; i < targets.length; i++) {
                out.writeByte(scores == null ? 0 : scores[i]);
            }
        }
    }

    /**
     * @return the cached set, or null if the file does not exist
     * @throws IOException if it exists but is unreadable, or was built for different
     *                     parameters — better to stop than to search a set that cannot
     *                     contain what is being looked for
     */
    public static Loaded load(Path path, Header wanted) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path), 1 << 16))) {
            if (in.readInt() != MAGIC) {
                throw new IOException(path + " is not a target set file");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException(path + " is version " + version + ", expected " + VERSION);
            }
            Header found = new Header(in.readInt(), in.readInt(), in.readInt(),
                    in.readInt(), in.readInt(), in.readBoolean(),
                    in.readInt(), in.readInt(), in.readInt(),
                    in.readLong(), in.readLong());
            requireSame(path, "height", wanted.minHeight(), found.minHeight());
            requireSame(path, "invocation count", wanted.count(), found.count());
            requireSame(path, "feature index", wanted.featureIndex(), found.featureIndex());
            requireSame(path, "band minimum y", wanted.baseMinY(), found.baseMinY());
            requireSame(path, "band maximum y", wanted.baseMaxY(), found.baseMaxY());
            requireSame(path, "maximum base shift", wanted.maxBaseShift(), found.maxBaseShift());
            requireSame(path, "maximum columns", wanted.maxColumns(), found.maxColumns());
            requireSame(path, "slack budget", wanted.maxSlack(), found.maxSlack());
            if (wanted.soilFilter() != found.soilFilter()) {
                throw new IOException(path + " was built with the soil filter "
                        + (found.soilFilter() ? "on" : "off") + ", this run wants it "
                        + (wanted.soilFilter() ? "on" : "off"));
            }
            int n = in.readInt();
            if (n < 0) {
                throw new IOException(path + " declares " + n + " targets");
            }
            long[] targets = new long[n];
            for (int i = 0; i < n; i++) {
                targets[i] = in.readLong();
            }
            byte[] scores = new byte[n];
            try {
                in.readFully(scores);
            } catch (EOFException e) {
                throw new IOException(path + " is truncated: " + n
                        + " targets declared but the scores are incomplete", e);
            }
            return new Loaded(found, targets, scores);
        }
    }

    private static void requireSame(Path path, String what, int wanted, int found)
            throws IOException {
        if (wanted != found) {
            throw new IOException(path + " was built with " + what + " " + found
                    + ", this run wants " + wanted
                    + " - delete it or point --targets somewhere else");
        }
    }
}
