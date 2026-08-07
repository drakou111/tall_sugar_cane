package dev.drakou111.sugarcane.gen;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@code TwoChunkLift} on the GPU, in batches.
 *
 * <p>6bc ruled the lift out for the card — "a branchy tree with data-dependent survival ... it
 * runs once per candidate pair, not once per seed". That was right when pairs were rare. Once
 * the chain scan moved to the GPU (6bl) the lift became the whole cost of a run, and a height-10
 * search spends hours on it with the card idle. The premise changed, not the reasoning.
 *
 * <p>Measured against 24 CPU threads: <b>70,400 pairs/s against 16,550, about 4.3x</b>, plus a
 * fixed ~400 ms of CUDA start-up per invocation. So batches want to be large; a few thousand
 * pairs is mostly start-up.
 *
 * <p>The kernel returns the same set as {@link dev.drakou111.sugarcane.rng.TwoChunkLift#solve},
 * not merely a similar one — it walks each blind prefix depth first where the CPU walks levels
 * breadth first, so the order differs and the set does not. {@code GpuLiftTest} pins that both
 * ways round, which matters more than usual here: 6bb warned that a wrong solver returns most of
 * the answers and looks perfect, and 6bc's sign-extension bug did exactly that.
 */
public final class GpuLift {

    private static final String BUNDLED_RESOURCE = "/cuda/two_chunk_lift.exe";
    private static String lastFailure = "not probed";

    private final Path binary;

    private GpuLift(Path binary) {
        this.binary = binary;
    }

    public Path binary() {
        return binary;
    }

    public static String lastFailure() {
        return lastFailure;
    }

    /**
     * The local build if there is one, else the copy inside the jar.
     *
     * <p>Same order as {@link GpuChainFilter}: a developer editing the kernel wants their build
     * used, and everyone else needs the jar to be sufficient on its own.
     */
    public static GpuLift detect() {
        Path local = Path.of("cuda", "two_chunk_lift.exe");
        if (Files.isRegularFile(local)) {
            return probe(local);
        }
        try (InputStream in = GpuLift.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                lastFailure = "no two_chunk_lift.exe, locally or in the jar";
                return null;
            }
            Path tmp = Files.createTempFile("two_chunk_lift-", ".exe");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().setExecutable(true);
            tmp.toFile().deleteOnExit();
            return probe(tmp);
        } catch (IOException e) {
            lastFailure = "could not unpack the bundled kernel: " + e;
            return null;
        }
    }

    /** Runs it on one trivial pair, so "no usable card" is found now rather than mid-run. */
    private static GpuLift probe(Path binary) {
        GpuLift lift = new GpuLift(binary);
        try {
            lift.solve(new long[] {1L}, new long[] {1L}, new int[] {1}, new int[] {0}, 1);
            lastFailure = "none";
            return lift;
        } catch (Exception e) {
            lastFailure = String.valueOf(e.getMessage());
            return null;
        }
    }

    /** One solved world seed, and which pair of the batch produced it. */
    public record Solved(int[] pair, long[] worldSeed, int count) {
    }

    /**
     * Lifts a batch.
     *
     * @param count how many entries of the arrays to use, so a caller can reuse buffers
     * @return every world seed the batch yields, tagged with its pair's index
     */
    public Solved solve(long[] d1, long[] d2, int[] dx, int[] dz, int count)
            throws IOException, InterruptedException {
        Path in = Files.createTempFile("lift-in-", ".bin");
        Path out = Files.createTempFile("lift-out-", ".bin");
        try {
            try (DataOutputStream w = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(in), 1 << 20))) {
                w.write(le32(count));
                for (int i = 0; i < count; i++) {
                    w.write(le64(d1[i]));
                    w.write(le64(d2[i]));
                    w.write(le32(dx[i]));
                    w.write(le32(dz[i]));
                }
            }
            ProcessBuilder pb = new ProcessBuilder(binary.toString(), in.toString(),
                    out.toString());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            String complaint = new String(proc.getErrorStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            int rc = proc.waitFor();
            if (rc != 0) {
                // Exit 4 is the output buffer overflowing, which drops solutions. Silently
                // returning a short list would look exactly like a barren batch.
                throw new IOException("two_chunk_lift exited " + rc + ": " + complaint);
            }
            ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN);
            int n = bb.getInt();
            int[] pair = new int[n];
            long[] seeds = new long[n];
            for (int i = 0; i < n; i++) {
                pair[i] = bb.getInt();
                seeds[i] = bb.getLong();
            }
            return new Solved(pair, seeds, n);
        } finally {
            Files.deleteIfExists(in);
            Files.deleteIfExists(out);
        }
    }

    private static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static byte[] le64(long v) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array();
    }
}
