package dev.drakou111.sugarcane.gen;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs the chain filter on a GPU if there is one, by shelling out to
 * {@code cuda/find_targets.exe}.
 *
 * <p>Only the chain filter moves. It is 99.4% of the target build (measured: 5.927 us a
 * seed against the soil filter's 0.036) and it is integer arithmetic on Java's LCG, so
 * the port is exact by construction -- verified against the Java filter over 16M seeds,
 * identical sets, zero mismatches. The soil filter stays here, where its doubles and its
 * sine table are already validated, and only has to run on the ~1.6% of seeds the GPU
 * hands back.
 *
 * <p>The kernel samples with the same splitmix over the same index space as
 * {@code ReverseSearcher}, so a set built on the GPU and a set built on the CPU are
 * interchangeable and either can extend the other's cache. That is the reason for
 * porting our own kernel rather than one that enumerates sequentially: enumeration is
 * the better shape for an exhaustive 2^48 scan, but it does not share bookkeeping with
 * the sampled cache.
 *
 * <p>Detection is a real run, not a version probe: a 4,096-seed batch has to come back
 * with a plausible answer before the device is used for anything. A machine with no
 * CUDA, no driver, a mismatched toolkit or a missing binary all fail the same way and
 * fall back to the CPU without comment.
 */
public final class GpuChainFilter {

    private static final Path[] CANDIDATE_PATHS = {
            Path.of("cuda", "find_targets.exe"),
            Path.of("cuda", "find_targets"),
            Path.of("find_targets.exe"),
            Path.of("find_targets"),
    };

    private final Path binary;

    private GpuChainFilter(Path binary) {
        this.binary = binary;
    }

    /** @return a usable filter, or null if this machine has none */
    public static GpuChainFilter detect() {
        for (Path p : CANDIDATE_PATHS) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            GpuChainFilter candidate = new GpuChainFilter(p.toAbsolutePath());
            try {
                // A real batch, because a binary that exists is not the same as a device
                // that works. Height 2 accepts nearly everything, so a plausible answer
                // here means the whole path is alive.
                long[] probe = candidate.run(2, SugarCaneFeature.COUNT_DEFAULT, 5,
                        ChainPrefilter.DEFAULT_BASE_MIN_Y, ChainPrefilter.DEFAULT_BASE_MAX_Y,
                        0L, 4096L);
                if (probe.length > 0) {
                    return candidate;
                }
            } catch (Exception e) {
                // Fall through: no device, no driver, wrong toolkit, unreadable binary.
            }
        }
        return null;
    }

    public Path binary() {
        return binary;
    }

    /**
     * Chain-filter accepted seeds for sample indices {@code [sampleFrom, sampleFrom +
     * samples)}. These still need the soil filter applied before they are targets.
     */
    public long[] run(int minHeight, int count, int featureIndex, int baseMinY,
            int baseMaxY, long sampleFrom, long samples) throws IOException,
            InterruptedException {
        Path out = Files.createTempFile("targets-gpu-", ".bin");
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.toString(),
                    Integer.toString(minHeight), Integer.toString(count),
                    Integer.toString(featureIndex), Integer.toString(baseMinY),
                    Integer.toString(baseMaxY), Long.toString(sampleFrom),
                    Long.toString(samples), out.toString());
            pb.redirectErrorStream(false);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            if (!proc.waitFor(6, TimeUnit.HOURS)) {
                proc.destroyForcibly();
                throw new IOException("the GPU filter did not finish within six hours");
            }
            int code = proc.exitValue();
            if (code == 4) {
                // The kernel's own signal that its output buffer overflowed, so the
                // answer would be silently incomplete.
                throw new IOException("the GPU filter dropped accepted seeds; "
                        + "use a smaller epoch");
            }
            if (code != 0) {
                throw new IOException("the GPU filter exited " + code);
            }
            byte[] raw = Files.readAllBytes(out);
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            long[] seeds = new long[raw.length / 8];
            for (int i = 0; i < seeds.length; i++) {
                seeds[i] = bb.getLong();
            }
            return seeds;
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
