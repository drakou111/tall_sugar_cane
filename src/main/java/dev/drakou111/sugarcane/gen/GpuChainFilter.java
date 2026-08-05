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

    /**
     * The kernel shipped inside the jar, so a user needs the jar and nothing else.
     *
     * <p>It is a normal resource that gets unpacked to a temp file and run. That works
     * because nvcc links the CUDA runtime statically: the binary imports only
     * {@code kernel32.dll} and {@code nvcuda.dll}, and the latter arrives with every
     * NVIDIA driver. So no CUDA toolkit, no compiler, no build script on the user's
     * machine -- which is the point, since asking someone to run a .bat to get the fast
     * path means most people silently get the slow one.
     *
     * <p>A binary next to the jar still wins (see {@link #CANDIDATE_PATHS}), so a locally
     * rebuilt kernel overrides the shipped one during development.
     */
    private static final String BUNDLED_RESOURCE = "/cuda/find_targets.exe";

    /** Unpacked once per JVM; null once unpacking has been tried and failed. */
    private static Path unpacked;
    private static boolean unpackTried;

    private static synchronized Path bundled() {
        if (unpackTried) {
            return unpacked;
        }
        unpackTried = true;
        try (java.io.InputStream in =
                     GpuChainFilter.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                return null;
            }
            String suffix = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? ".exe" : "";
            Path dir = Files.createTempDirectory("sugarcane-cuda");
            Path exe = dir.resolve("find_targets" + suffix);
            Files.copy(in, exe);
            exe.toFile().setExecutable(true);
            // Best effort: a searcher can run for days, so leaving one file per run in
            // the temp directory is untidy rather than harmful, and deleting it while a
            // child process still holds it would be worse.
            exe.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            unpacked = exe;
        } catch (IOException e) {
            lastFailure = "could not unpack the bundled kernel: " + e.getMessage();
        }
        return unpacked;
    }

    private final Path binary;

    private GpuChainFilter(Path binary) {
        this.binary = binary;
    }

    /**
     * Why the last {@link #detect} found nothing. A silent fallback runs about 4.5x
     * slower with no indication, which is exactly the kind of thing nobody notices -- a
     * binary built for the wrong architecture fails identically to having no card at all.
     */
    private static volatile String lastFailure = "no binary found in cuda/ or the working directory";

    public static String lastFailure() {
        return lastFailure;
    }

    /** @return a usable filter, or null if this machine has none */
    public static GpuChainFilter detect() {
        boolean sawBinary = false;
        // A local build first, then the copy inside the jar.
        java.util.List<Path> tries = new java.util.ArrayList<>(CANDIDATE_PATHS.length + 1);
        java.util.Collections.addAll(tries, CANDIDATE_PATHS);
        Path shipped = bundled();
        if (shipped != null) {
            tries.add(shipped);
        }
        for (Path p : tries) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            sawBinary = true;
            GpuChainFilter candidate = new GpuChainFilter(p.toAbsolutePath());
            try {
                // A real batch, because a binary that exists is not the same as a device
                // that works. Height 2 accepts nearly everything, so a plausible answer
                // here means the whole path is alive.
                // Slack 4 rather than 0: at height 2 a single column suffices, so the
                // slack rule never fires either way, and an unbounded probe also proves
                // the binary is new enough to take the argument at all. An old one stops
                // on the argument count instead of silently reading sampleFrom as slack.
                long[] probe = candidate.run(2, SugarCaneFeature.COUNT_DEFAULT, 5,
                        ChainPrefilter.DEFAULT_BASE_MIN_Y, ChainPrefilter.DEFAULT_BASE_MAX_Y,
                        3, 4, 4, 0L, 4096L);
                if (probe.length > 0) {
                    return candidate;
                }
                lastFailure = p + " ran but accepted nothing, which should be impossible at "
                        + "height 2 -- suspect an argument mismatch between this build and "
                        + "the binary";
            } catch (Exception e) {
                lastFailure = p + ": " + e.getMessage();
            }
        }
        if (!sawBinary) {
            lastFailure = "no CUDA binary: none beside the jar, and none bundled inside it";
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
            int baseMaxY, int maxBaseShift, int maxColumns, int maxSlack,
            long sampleFrom, long samples)
            throws IOException, InterruptedException {
        return run(minHeight, count, featureIndex, baseMinY, baseMaxY, maxBaseShift,
                maxColumns, maxSlack, sampleFrom, samples, null);
    }

    /**
     * @param onProgress if non-null, called with (seeds done, seeds total, accepted so
     *                   far) as the kernel reports them, so a long epoch is not silent
     */
    public long[] run(int minHeight, int count, int featureIndex, int baseMinY,
            int baseMaxY, int maxBaseShift, int maxColumns, int maxSlack,
            long sampleFrom, long samples,
            java.util.function.LongConsumer onProgress) throws IOException,
            InterruptedException {
        Path out = Files.createTempFile("targets-gpu-", ".bin");
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.toString(),
                    Integer.toString(minHeight), Integer.toString(count),
                    Integer.toString(featureIndex), Integer.toString(baseMinY),
                    Integer.toString(baseMaxY), Integer.toString(maxBaseShift),
                    Integer.toString(maxColumns), Integer.toString(maxSlack),
                    Long.toString(sampleFrom),
                    Long.toString(samples), out.toString());
            pb.redirectErrorStream(false);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            // Always drain stderr, even with no progress consumer. Discarding it threw away
            // the kernel's own diagnosis of why it failed, which is the one case where the
            // detection probe has something worth saying.
            StringBuilder complaints = new StringBuilder();
            Thread relay = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("progress ")) {
                            if (onProgress != null) {
                                String[] f = line.split(" ");
                                onProgress.accept(Long.parseLong(f[1]));
                            }
                        } else if (!line.startsWith("tested=")
                                && complaints.length() < 400) {
                            synchronized (complaints) {
                                if (complaints.length() > 0) {
                                    complaints.append("; ");
                                }
                                complaints.append(line.trim());
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // The process is finishing; progress reporting is not worth a
                    // failure of its own.
                }
            }, "gpu-progress");
            relay.setDaemon(true);
            relay.start();
            if (!proc.waitFor(6, TimeUnit.HOURS)) {
                proc.destroyForcibly();
                throw new IOException("the GPU filter did not finish within six hours");
            }
            int code = proc.exitValue();
            relay.join(2000);
            String said;
            synchronized (complaints) {
                said = complaints.toString();
            }
            if (code == 4) {
                // The kernel's own signal that its output buffer overflowed, so the
                // answer would be silently incomplete.
                throw new IOException("the GPU filter dropped accepted seeds; "
                        + "use a smaller epoch");
            }
            if (code != 0) {
                throw new IOException("exited " + code
                        + (said.isEmpty() ? "" : ": " + said));
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
