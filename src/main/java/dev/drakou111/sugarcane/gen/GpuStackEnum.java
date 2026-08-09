package dev.drakou111.sugarcane.gen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link StackEnumerator} on the GPU.
 *
 * <p>The seed-scanning route walks every decoration seed's 1,230 draws and pays that whatever the
 * seed holds. This constructs the states that yield a wanted y instead, so the y costs nothing
 * and most states die two draws later on the height check.
 *
 * <p><b>Measured at height 10</b> over the full k range with the edge filter on: <b>871
 * confirmed chains/s</b>. Driven through {@code crossfind}'s pass 1, which adds the CPU geometry
 * step, that is <b>621 chains stored per second against the seed scan's 94</b> — 6.6x, same card,
 * same command one flag apart.
 *
 * <p>Rate is the smaller half of it. Every hit here is a real chain where 32.7% of the scan's
 * accepts are, its shift levels granting chains that need an unrelated placement elsewhere in the
 * chunk. Since joins go as the square of the candidate count, that precision compounds.
 *
 * <p>The scan also caps a chain at its shift-level count — four levels stop at height 16, and a
 * fifth halves its throughput — where this tracks placements exactly and has no cap. So the gap
 * widens as the target rises, which is the direction this search is going.
 *
 * <p><b>It is not exhaustive.</b> {@code k} and the y band are swept in full but the 17 low bits
 * of the state are sampled, {@code lows} of 131,072. Those bits drive everything downstream, so
 * the default of 8 sees 6.1e-5 of the states in its band. {@code lows} is the coverage knob and
 * costs linearly, measured at 27,643 / 105,646 / 398,508 chains for 8, 32 and 128.
 */
public final class GpuStackEnum {

    private static final String BUNDLED_RESOURCE = "/cuda/stack_enum.exe";
    private static String lastFailure = "not probed";

    /** The largest k with {@code k * 126 + y} still inside {@code next(31)}'s range. */
    public static final long K_LIMIT = (1L << 31) / StackEnumerator.Y_BOUND;

    private final Path binary;

    private GpuStackEnum(Path binary) {
        this.binary = binary;
    }

    public Path binary() {
        return binary;
    }

    public static String lastFailure() {
        return lastFailure;
    }

    /** One chain the sweep found, already confirmed by the kernel's own {@code runAt}. */
    public record Hit(long decorationSeed, int x, int y, int z, int height) {
    }

    /**
     * The local build if there is one, else the copy inside the jar.
     *
     * <p>Same order as {@link GpuLift}: a developer editing the kernel wants their build used,
     * and everyone else needs the jar to be sufficient on its own.
     */
    public static GpuStackEnum detect() {
        Path local = Path.of("cuda", "stack_enum.exe");
        if (Files.isRegularFile(local)) {
            return probe(local);
        }
        try (InputStream in = GpuStackEnum.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                lastFailure = "no stack_enum.exe, locally or in the jar";
                return null;
            }
            Path tmp = Files.createTempFile("stack_enum-", ".exe");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().setExecutable(true);
            tmp.toFile().deleteOnExit();
            return probe(tmp);
        } catch (IOException e) {
            lastFailure = "could not unpack the bundled kernel: " + e;
            return null;
        }
    }

    /** Runs a trivial sweep, so "no usable card" is found now rather than mid-run. */
    private static GpuStackEnum probe(Path binary) {
        GpuStackEnum enumerator = new GpuStackEnum(binary);
        try {
            enumerator.sweep(0, 256, 16, 17, 16, true, 8);
            lastFailure = "none";
            return enumerator;
        } catch (Exception e) {
            lastFailure = String.valueOf(e.getMessage());
            return null;
        }
    }

    /**
     * Sweeps a range of {@code k}.
     *
     * @param kFrom    first k, so collaborators can split the range between them
     * @param kCount   how many, capped at {@link #K_LIMIT} by the kernel itself
     * @param minY     lowest y a chain's anchor invocation may draw, inclusive
     * @param maxY     highest, inclusive
     * @param target   the run height to report
     * @param edgeOnly keep only columns a neighbouring chunk's jitter can also reach, which is
     *                 every column cross-chunk stacking can use and trims 43% of the work
     * @param lows     how many of the 131,072 low-bit values to sample; a power of two
     */
    public List<Hit> sweep(long kFrom, long kCount, int minY, int maxY, int target,
            boolean edgeOnly, int lows) throws IOException, InterruptedException {
        if (lows <= 0 || (1 << 17) % lows != 0) {
            throw new IllegalArgumentException("lows must be a power of two up to 131072: " + lows);
        }
        Path out = Files.createTempFile("stackenum-", ".bin");
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.toString(),
                    Long.toString(kFrom), Long.toString(kCount),
                    Integer.toString(minY), Integer.toString(maxY),
                    Integer.toString(target), edgeOnly ? "1" : "0",
                    out.toString(), Integer.toString(lows));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            String complaint = new String(proc.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            int rc = proc.waitFor();
            if (rc != 0) {
                // Exit 4 is the output buffer overflowing, which drops hits. A short list would
                // look exactly like a barren sweep, so it has to be an error rather than a
                // quietly truncated result.
                throw new IOException("stack_enum exited " + rc + ": " + complaint);
            }
            ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN);
            int n = bb.getInt();
            List<Hit> hits = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                hits.add(new Hit(bb.getLong(), bb.getInt(), bb.getInt(), bb.getInt(), bb.getInt()));
            }
            return hits;
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
