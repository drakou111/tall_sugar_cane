package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.GpuChainFilter;
import dev.drakou111.sugarcane.gen.OrbitSampler;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the CUDA chain filter to the CPU one, which nothing did before.
 *
 * <p>{@code BundledKernelTest} checks that the binary is present and not older than its
 * source. Neither says anything about what it computes, and the target builder runs on the
 * pairing, so a kernel that quietly dropped targets would have shown up only as the search
 * being slower than it should be -- which is indistinguishable from the search being hard.
 *
 * <p>Skips rather than fails without a CUDA device, because most machines running the tests
 * do not have one and the CPU path is the fallback the jar ships for.
 */
class KernelAgreementTest {

    private static final int OCEAN_COUNT = SugarCaneFeature.COUNT_DEFAULT;
    private static final int OCEAN_INDEX = 5;
    private static final long SAMPLES = 2_000_000L;

    /** Exactly {@code ReverseSearcher.rankedFilter}, including the maxSlack it is run at. */
    private static ChainPrefilter cpuFilter(int minHeight) {
        int levels = ChainPrefilter.shiftLevelsFor(minHeight);
        return new ChainPrefilter(OCEAN_COUNT, ChainPrefilter.DEFAULT_BASE_MIN_Y,
                ChainPrefilter.DEFAULT_BASE_MAX_Y, 0, ChainPrefilter.minimumColumns(minHeight),
                levels - 1, levels)
                .maxSlack(0);
    }

    private static long[] cpuAccepts(int minHeight, long samples) {
        ChainPrefilter filter = cpuFilter(minHeight);
        long[] out = new long[1024];
        int n = 0;
        for (long i = 0; i < samples; i++) {
            long z = OrbitSampler.sampleAt(i, OCEAN_INDEX, SugarCaneFeature.VEGETAL_DECORATION);
            int chains = filter.collectChains(z, OCEAN_INDEX, minHeight);
            if (chains == 0 && !filter.chainsOverflowed()) {
                continue;
            }
            if (n == out.length) {
                out = Arrays.copyOf(out, n * 2);
            }
            out[n++] = z;
        }
        return Arrays.copyOf(out, n);
    }

    private static long[] gpuAccepts(GpuChainFilter gpu, int minHeight, long samples)
            throws Exception {
        int levels = ChainPrefilter.shiftLevelsFor(minHeight);
        return gpu.run(minHeight, OCEAN_COUNT, OCEAN_INDEX, ChainPrefilter.DEFAULT_BASE_MIN_Y,
                ChainPrefilter.DEFAULT_BASE_MAX_Y, 0, ChainPrefilter.minimumColumns(minHeight),
                0, levels, -1, -1, 0L, samples);
    }

    /**
     * The kernel may not miss much that the CPU keeps: a seed it drops is never re-tested, so
     * it is a target lost outright. This is the direction that costs finds.
     *
     * <p><b>It does miss some, and this test documents rather than forbids it.</b> At min 7
     * the kernel drops 13 of 7,090 at 2M samples and 16 of 14,818 at 4M -- 0.11% to 0.18% --
     * while min 8 and min 9 agree exactly. The dropped chains share a shape: two columns,
     * base shift 0, max shift 1, bases high in the depth band. FINDINGS 6bi has the
     * measurement and the suspect, which is the incremental "chain ending in the newest
     * invocation" path rather than the full DP.
     *
     * <p>The bound is set at 1%, an order above what is observed, so this fails on a
     * regression rather than on noise. Tightening it to zero is the fix, not the test.
     */
    @Test
    void theKernelDropsAlmostNothingTheFilterKeeps() throws Exception {
        GpuChainFilter gpu = GpuChainFilter.detect();
        assumeTrue(gpu != null, "no CUDA device: " + GpuChainFilter.lastFailure());

        for (int minHeight : new int[] {7, 8, 9}) {
            long[] cpu = cpuAccepts(minHeight, SAMPLES);
            long[] fromGpu = gpuAccepts(gpu, minHeight, SAMPLES);
            Arrays.sort(fromGpu);
            StringBuilder missing = new StringBuilder();
            int lost = 0;
            for (long z : cpu) {
                if (Arrays.binarySearch(fromGpu, z) < 0) {
                    lost++;
                    if (missing.length() < 200) {
                        missing.append(z).append(' ');
                    }
                }
            }
            assertTrue(lost * 100 <= cpu.length,
                    "min height " + minHeight + ": the kernel dropped " + lost + " of "
                            + cpu.length + " seeds the ranked filter keeps, over the 1% this "
                            + "pins. A dropped seed is never re-tested, so a GPU-built target "
                            + "set is missing it and no later stage can recover it. "
                            + "Examples: " + missing);
        }
    }

    /**
     * The two agree exactly wherever the greedy path is out of the picture and the
     * incremental one has nothing to slide -- so the disagreement is localised rather than
     * pervasive, which is what makes 0.11% believable as a bug and not as a redefinition.
     */
    @Test
    void theyAgreeExactlyAtMinNine() throws Exception {
        GpuChainFilter gpu = GpuChainFilter.detect();
        assumeTrue(gpu != null, "no CUDA device: " + GpuChainFilter.lastFailure());

        long[] cpu = cpuAccepts(9, SAMPLES);
        long[] fromGpu = gpuAccepts(gpu, 9, SAMPLES);
        Arrays.sort(cpu);
        Arrays.sort(fromGpu);
        assertEquals(Arrays.toString(cpu), Arrays.toString(fromGpu),
                "min height 9 uses neither the greedy path nor any parameter the kernel "
                        + "lacks, so the two implementations must return the same set");
    }

    /**
     * The other direction is survivable but bounded. {@code ReverseSearcher} re-tests every
     * seed the kernel returns, so over-acceptance costs soil-filter work rather than
     * correctness -- but the greedy path is only allowed to be a little loose, and a
     * regression that made it very loose would quietly undo the GPU's whole advantage.
     */
    @Test
    void theKernelDoesNotOverAcceptWildly() throws Exception {
        GpuChainFilter gpu = GpuChainFilter.detect();
        assumeTrue(gpu != null, "no CUDA device: " + GpuChainFilter.lastFailure());

        int minHeight = 8;      // divisible by four, so the greedy path runs
        long[] cpu = cpuAccepts(minHeight, SAMPLES);
        long[] fromGpu = gpuAccepts(gpu, minHeight, SAMPLES);
        long[] sortedCpu = cpu.clone();
        Arrays.sort(sortedCpu);
        int extra = 0;
        for (long z : fromGpu) {
            if (Arrays.binarySearch(sortedCpu, z) < 0) {
                extra++;
            }
        }
        assertTrue(extra <= fromGpu.length / 4,
                "the kernel returned " + extra + " of " + fromGpu.length + " seeds the ranked "
                        + "filter rejects. Some is expected -- the greedy walk over-accepts by "
                        + "design and the CPU sifts it out -- but a quarter of the output is "
                        + "the point at which the GPU is doing more harm than good.");
    }
}
