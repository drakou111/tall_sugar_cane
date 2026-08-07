package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.GpuChainFilter;
import dev.drakou111.sugarcane.gen.OrbitSampler;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the CUDA chain filter and the CPU one over the same sample range and diffs them.
 *
 * <p>6bg found them disagreeing in both directions at min 8 -- {@code gpu 2937, cpu 7669,
 * gpu-only 358, cpu-only 5090} -- and left it open, because nothing pinned the kernel to the
 * filter and the target builder runs on that pairing. Which way the error runs matters a lot:
 * if the kernel under-accepts, every GPU-built target set is missing most of its targets; if
 * the CPU over-accepts, the CPU path wastes most of its work.
 *
 * <p>It is neither, and the answer is in the harness rather than in either implementation.
 * {@code ChainPrefilter.ranked} leaves {@code maxSlack} at {@code Integer.MAX_VALUE} while
 * {@code ReverseSearcher} runs the whole pipeline at {@code maxSlack = 0} -- the contiguous
 * window of 6ap -- and passes that 0 to the kernel. Comparing the static against a kernel run
 * configured from the searcher compares two different filters, and the 5,090 "cpu-only" seeds
 * are the ones the contiguous rule is supposed to reject.
 *
 * <p>So this takes every parameter from one place and hands the same numbers to both sides.
 * Any residual disagreement is a real kernel bug; {@code --slack} reproduces the old reading.
 */
public final class KernelAgreement {

    private static final int OCEAN_COUNT = SugarCaneFeature.COUNT_DEFAULT;
    private static final int OCEAN_INDEX = 5;

    private KernelAgreement() {
    }

    /** Exactly {@code ReverseSearcher.rankedFilter}, which is what the builder actually uses. */
    private static ChainPrefilter cpuFilter(int minHeight, int maxSlack) {
        int levels = ChainPrefilter.shiftLevelsFor(minHeight);
        return new ChainPrefilter(OCEAN_COUNT, ChainPrefilter.DEFAULT_BASE_MIN_Y,
                ChainPrefilter.DEFAULT_BASE_MAX_Y, 0, ChainPrefilter.minimumColumns(minHeight),
                levels - 1, levels)
                .maxSlack(maxSlack);
    }

    public static void main(String[] args) throws Exception {
        int minHeight = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        long samples = args.length > 1 ? Long.parseLong(args[1]) : 4_000_000L;
        long from = args.length > 2 ? Long.parseLong(args[2]) : 0L;
        int maxSlack = 0;
        int threads = Cli.clampThreads(Runtime.getRuntime().availableProcessors());
        for (String arg : args) {
            if (arg.startsWith("--slack=")) {
                maxSlack = Integer.parseInt(arg.substring(8));
            } else if (arg.startsWith("--threads=")) {
                threads = Cli.clampThreads(Integer.parseInt(arg.substring(10)));
            }
        }

        GpuChainFilter gpu = GpuChainFilter.detect();
        if (gpu == null) {
            System.out.println("no CUDA kernel available: " + GpuChainFilter.lastFailure());
            System.out.println("nothing to compare against, so nothing to say");
            return;
        }

        int levels = ChainPrefilter.shiftLevelsFor(minHeight);
        int maxColumns = ChainPrefilter.minimumColumns(minHeight);
        System.out.printf("min height %d, %d samples from %d%n", minHeight, samples, from);
        System.out.printf("  count %d, index %d, baseY %d..%d, maxBaseShift 0, maxColumns %d, "
                        + "maxSlack %d, shiftLevels %d%n",
                OCEAN_COUNT, OCEAN_INDEX, ChainPrefilter.DEFAULT_BASE_MIN_Y,
                ChainPrefilter.DEFAULT_BASE_MAX_Y, maxColumns, maxSlack, levels);
        System.out.printf("  greedy path eligible: %s%n",
                minHeight % 4 == 0 ? "yes (minHeight divisible by 4)" : "no");

        long t0 = System.currentTimeMillis();
        long[] gpuSeeds = gpu.run(minHeight, OCEAN_COUNT, OCEAN_INDEX,
                ChainPrefilter.DEFAULT_BASE_MIN_Y, ChainPrefilter.DEFAULT_BASE_MAX_Y,
                0, maxColumns, maxSlack, levels, -1, -1, from, samples);
        double gpuSecs = (System.currentTimeMillis() - t0) / 1000.0;
        Arrays.sort(gpuSeeds);

        t0 = System.currentTimeMillis();
        final int fSlack = maxSlack;
        final int fMin = minHeight;
        AtomicLong next = new AtomicLong(from);
        java.util.List<long[]> parts = java.util.Collections.synchronizedList(
                new java.util.ArrayList<>());
        Thread[] pool = new Thread[threads];
        long end = from + samples;
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                ChainPrefilter filter = cpuFilter(fMin, fSlack);
                long[] mine = new long[1024];
                int n = 0;
                while (true) {
                    long lo = next.getAndAdd(4096L);
                    if (lo >= end) {
                        break;
                    }
                    long hi = Math.min(lo + 4096L, end);
                    for (long i = lo; i < hi; i++) {
                        long z = OrbitSampler.sampleAt(i, OCEAN_INDEX,
                                SugarCaneFeature.VEGETAL_DECORATION);
                        int chains = filter.collectChains(z, OCEAN_INDEX, fMin);
                        if (chains == 0 && !filter.chainsOverflowed()) {
                            continue;
                        }
                        if (n == mine.length) {
                            mine = Arrays.copyOf(mine, n * 2);
                        }
                        mine[n++] = z;
                    }
                }
                parts.add(Arrays.copyOf(mine, n));
            }, "agree-" + t);
            pool[t].start();
        }
        for (Thread th : pool) {
            th.join();
        }
        double cpuSecs = (System.currentTimeMillis() - t0) / 1000.0;

        int total = 0;
        for (long[] p : parts) {
            total += p.length;
        }
        long[] cpuSeeds = new long[total];
        int at = 0;
        for (long[] p : parts) {
            System.arraycopy(p, 0, cpuSeeds, at, p.length);
            at += p.length;
        }
        Arrays.sort(cpuSeeds);

        long[] gpuOnly = difference(gpuSeeds, cpuSeeds);
        long[] cpuOnly = difference(cpuSeeds, gpuSeeds);
        System.out.printf("%n  gpu %d in %.1f s, cpu %d in %.1f s%n",
                gpuSeeds.length, gpuSecs, cpuSeeds.length, cpuSecs);
        System.out.printf("  agreed %d, gpu-only %d, cpu-only %d%n",
                gpuSeeds.length - gpuOnly.length, gpuOnly.length, cpuOnly.length);
        if (gpuOnly.length == 0 && cpuOnly.length == 0) {
            System.out.println("  the two sets are identical");
            return;
        }
        show("gpu-only", gpuOnly, minHeight, maxSlack);
        show("cpu-only", cpuOnly, minHeight, maxSlack);
    }

    /** What the CPU filter makes of a seed the two sides disagreed about. */
    private static void show(String label, long[] seeds, int minHeight, int maxSlack) {
        if (seeds.length == 0) {
            return;
        }
        ChainPrefilter strict = cpuFilter(minHeight, maxSlack);
        ChainPrefilter loose = cpuFilter(minHeight, Integer.MAX_VALUE);
        int looseAccepts = 0;
        for (long z : seeds) {
            int chains = loose.collectChains(z, OCEAN_INDEX, minHeight);
            if (chains > 0 || loose.chainsOverflowed()) {
                looseAccepts++;
            }
        }
        System.out.printf("%n  %d %s seeds; %d of them the CPU accepts once maxSlack is "
                        + "unrestricted, so that many are the contiguous-window rule%n",
                seeds.length, label, looseAccepts);
        for (int i = 0; i < Math.min(8, seeds.length); i++) {
            long z = seeds[i];
            int chains = strict.collectChains(z, OCEAN_INDEX, minHeight);
            System.out.printf("    %d: strict chains %d (overflow %s), tallest %d%n",
                    z, chains, strict.chainsOverflowed(),
                    strict.tallestPossible(z, OCEAN_INDEX));
            for (int c = 0; c < chains; c++) {
                long chain = strict.chain(c);
                int cols = ChainPrefilter.chainColumns(chain);
                StringBuilder geom = new StringBuilder();
                for (int k = 0; k < cols; k++) {
                    geom.append(String.format(" y%d+%d", ChainPrefilter.chainBaseY(chain, k),
                            ChainPrefilter.chainHeight(chain, k)));
                }
                System.out.printf("        chain %d: x=%d z=%d cols=%d baseShift=%d "
                                + "maxShift=%d top=%d run=%d%s%n",
                        c, ChainPrefilter.chainX(chain), ChainPrefilter.chainZ(chain), cols,
                        ChainPrefilter.chainBaseShift(chain),
                        ChainPrefilter.chainMaxShift(chain), ChainPrefilter.chainTop(chain),
                        ChainPrefilter.chainTop(chain) - ChainPrefilter.chainBaseY(chain, 0),
                        geom);
            }
        }
    }

    private static long[] difference(long[] a, long[] b) {
        long[] out = new long[a.length];
        int n = 0;
        for (long v : a) {
            if (Arrays.binarySearch(b, v) < 0) {
                out[n++] = v;
            }
        }
        return Arrays.copyOf(out, n);
    }
}
