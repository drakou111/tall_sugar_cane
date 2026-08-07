package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.GpuLift;
import dev.drakou111.sugarcane.rng.TwoChunkLift;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Holds the CUDA lift to {@link TwoChunkLift}, seed for seed.
 *
 * <p>6bb's warning is the reason this is a set comparison and a round trip rather than a spot
 * check: a wrong solver returns most of the answers and looks perfect, and 6bc's sign-extension
 * bug did precisely that — it satisfied its own equation and threw away the true seed about half
 * the time. So pairs are derived from a known world seed, and both sides must return that seed
 * as well as agreeing with each other.
 *
 * <p>Skips without a CUDA device, like {@code KernelAgreementTest}.
 */
class GpuLiftTest {

    private static final long MASK = (1L << 48) - 1;
    private static final int[][] DIRS =
            {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    @Test
    void theKernelReturnsExactlyWhatTheCpuDoes() throws Exception {
        GpuLift gpu = GpuLift.detect();
        assumeTrue(gpu != null, "no CUDA device: " + GpuLift.lastFailure());

        int n = 1500;
        Random rng = new Random(4242);
        long[] d1 = new long[n], d2 = new long[n], truth = new long[n];
        int[] dx = new int[n], dz = new int[n];
        for (int i = 0; i < n; i++) {
            long ws = rng.nextLong() & MASK;
            int[] d = DIRS[rng.nextInt(DIRS.length)];
            dx[i] = d[0];
            dz[i] = d[1];
            Random r = new Random(ws);
            long a = r.nextLong() | 1L;
            long b = r.nextLong() | 1L;
            int cx = rng.nextInt(200000) - 100000;
            int cz = rng.nextInt(200000) - 100000;
            d1[i] = ((cx * 16L) * a + (cz * 16L) * b ^ ws) & MASK;
            d2[i] = (((cx + d[0]) * 16L) * a + ((cz + d[1]) * 16L) * b ^ ws) & MASK;
            truth[i] = ws;
        }

        GpuLift.Solved got = gpu.solve(d1, d2, dx, dz, n);
        Map<Integer, Set<Long>> byPair = new HashMap<>();
        for (int i = 0; i < got.count(); i++) {
            byPair.computeIfAbsent(got.pair()[i], k -> new TreeSet<>()).add(got.worldSeed()[i]);
        }

        int differ = 0;
        int cpuMissed = 0;
        int gpuMissed = 0;
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < n; i++) {
            Set<Long> want = new TreeSet<>();
            for (long s : TwoChunkLift.solve(d1[i], d2[i], dx[i], dz[i])) {
                want.add(s);
            }
            Set<Long> mine = byPair.getOrDefault(i, new TreeSet<>());
            if (!want.equals(mine)) {
                if (differ == 0) {
                    first.append(" pair ").append(i).append(" d1=").append(d1[i])
                            .append(" d2=").append(d2[i]).append(" dx=").append(dx[i])
                            .append(" dz=").append(dz[i])
                            .append(" cpu=").append(want).append(" gpu=").append(mine);
                }
                differ++;
            }
            if (!want.contains(truth[i])) {
                cpuMissed++;
            }
            if (!mine.contains(truth[i])) {
                gpuMissed++;
            }
        }
        assertEquals(0, differ, "the kernel and TwoChunkLift returned different seed sets for "
                + differ + " of " + n + " pairs." + first);
        assertEquals(0, cpuMissed, "TwoChunkLift lost the world seed the pair was built from, "
                + "so the test fixture or the CPU solver is wrong before the kernel is asked");
        assertEquals(0, gpuMissed, "the kernel lost the world seed the pair was built from — "
                + "the failure 6bc had, where every seed returned still satisfies the equation");
    }
}
