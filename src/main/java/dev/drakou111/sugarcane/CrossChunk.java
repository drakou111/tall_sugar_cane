package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.OrbitSampler;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Whether two neighbouring chunks can build one stack between them, and how often.
 *
 * <p>A placement lands at chunk-relative x in -4..19, so a chunk can put cane four blocks over
 * its own border. If one chunk stacks into the neighbour's territory and the neighbour stacks
 * on top of that, the run is the sum -- two ordinary chains rather than one extraordinary one,
 * which is why it is worth asking about at heights a single chunk cannot reach at all.
 *
 * <p><b>Not measured by sampling pairs.</b> The combined rate is far below anything pair
 * sampling can reach, and going faster does not fix that. But a pair only matters through the
 * one block the two chunks share:
 *
 * <pre>
 *   P(cross) = SUM over blocks p of  P(a chain ENDS at p) x P(a chain BEGINS at p)
 * </pre>
 *
 * <p>Both factors are properties of a single seed. So one pass over N seeds, histogramming
 * where chains end and where they begin, evaluates all N^2 pairings at once -- O(N) instead of
 * O(N^2), and the answer sharpens as the histograms fill rather than as pairs coincide. The
 * earlier version of this command formed pairs and could not have seen a 16 in a century.
 *
 * <p>Placement order is assumed, not checked: the first chunk must have decorated before the
 * second, which depends on how the world was explored. A cross-chunk result is a lead.
 */
public final class CrossChunk {

    private static final int OCEAN_INDEX = 5;

    /** Chunk-relative x and z both span -4..19, so a position packs into 24 x 24 x height. */
    private static final int Y = 128;
    private static final int CELLS = 24 * 24 * Y;

    private CrossChunk() {
    }

    private static int cell(int x, int z, int y) {
        return ((x + 4) + (z + 4) * 24) * Y + y;
    }

    public static void main(String[] args) throws Exception {
        long seeds = args.length > 0 ? Long.parseLong(args[0]) : 200_000_000L;
        int threads = Cli.clampThreads(args.length > 1 ? Integer.parseInt(args[1])
                : Runtime.getRuntime().availableProcessors());
        int target = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        final int minA;
        final int minB;
        if (args.length > 4) {
            minA = Integer.parseInt(args[3]);
            minB = Integer.parseInt(args[4]);
        } else {
            int[] best = bestSplit(target);
            minA = best[0];
            minB = best[1];
        }

        System.out.printf("cross-chunk rate for height %d, over %d seeds, %d threads%n",
                target, seeds, threads);
        System.out.printf("  split: one chunk contributes >= %d, the neighbour >= %d%n",
                minA, minB);
        System.out.println("  histogramming where chains end and where they begin, then "
                + "pairing -- every pair of the sample at once, without forming any");

        final long[][] tops = new long[threads][];
        final long[][] bases = new long[threads][];
        AtomicLong next = new AtomicLong();
        AtomicLong done = new AtomicLong();
        long start = System.currentTimeMillis();

        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool[t] = new Thread(() -> {
                long[] top = new long[CELLS];
                long[] base = new long[CELLS];
                // A chain that ENDS somewhere stands on soil, so it keeps the depth band.
                ChainPrefilter a = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT);
                // One that BEGINS there stands on the neighbour's cane, so it does not. This
                // was the flaw in the first version: banding both sides made any join above
                // y=35 invisible, and a tall combination joins high by construction.
                ChainPrefilter b = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT,
                        11, 64, 3, 4);
                for (long run = next.getAndIncrement(); run * OrbitSampler.RUN < seeds;
                        run = next.getAndIncrement()) {
                    long ds = OrbitSampler.runStart(run);
                    for (int k = 0; k < OrbitSampler.RUN; k++) {
                        int na = a.collectChains(ds, OCEAN_INDEX, minA);
                        if (!a.chainsOverflowed()) {
                            for (int i = 0; i < na; i++) {
                                long c = a.chain(i);
                                int y = ChainPrefilter.chainTop(c);
                                if (y >= 0 && y < Y) {
                                    top[cell(ChainPrefilter.chainX(c),
                                            ChainPrefilter.chainZ(c), y)]++;
                                }
                            }
                        }
                        int nb = b.collectChains(ds, OCEAN_INDEX, minB);
                        if (!b.chainsOverflowed()) {
                            for (int i = 0; i < nb; i++) {
                                long c = b.chain(i);
                                int y = ChainPrefilter.chainBaseY(c, 0);
                                if (y >= 0 && y < Y) {
                                    base[cell(ChainPrefilter.chainX(c),
                                            ChainPrefilter.chainZ(c), y)]++;
                                }
                            }
                        }
                        ds = OrbitSampler.shift(ds, OCEAN_INDEX,
                                SugarCaneFeature.VEGETAL_DECORATION);
                        done.incrementAndGet();
                    }
                }
                tops[id] = top;
                bases[id] = base;
            }, "cross-" + t);
            pool[t].start();
        }
        for (Thread th : pool) {
            th.join();
        }

        long[] top = new long[CELLS];
        long[] base = new long[CELLS];
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < CELLS; i++) {
                top[i] += tops[t][i];
                base[i] += bases[t][i];
            }
        }

        long n = done.get();
        long topTotal = 0;
        long baseTotal = 0;
        for (int i = 0; i < CELLS; i++) {
            topTotal += top[i];
            baseTotal += base[i];
        }

        // Every ordered pairing of the sample, summed exactly. A chain ending at a block in
        // this chunk's frame meets one beginning at the same world block in the neighbour's:
        // the same (x, z) shifted by sixteen, and only where both frames can reach it.
        double pairs = 0;
        long blocks = 0;
        for (int dir = 0; dir < 4; dir++) {
            int sx = dir == 0 ? 16 : dir == 1 ? -16 : 0;
            int sz = dir == 2 ? 16 : dir == 3 ? -16 : 0;
            for (int x = -4; x <= 19; x++) {
                for (int z = -4; z <= 19; z++) {
                    int bx = x - sx;
                    int bz = z - sz;
                    if (bx < -4 || bx > 19 || bz < -4 || bz > 19) {
                        continue;
                    }
                    for (int y = 0; y < Y; y++) {
                        long ca = top[cell(x, z, y)];
                        long cb = base[cell(bx, bz, y)];
                        if (ca != 0 && cb != 0) {
                            pairs += (double) ca * cb;
                            blocks++;
                        }
                    }
                }
            }
        }

        double secs = (System.currentTimeMillis() - start) / 1000.0;
        double rate = pairs / ((double) n * n);
        System.out.printf("%n%d seeds in %.1f s (%.2fM/s)%n", n, secs, n / secs / 1e6);
        System.out.printf("  chains ending, height >= %d  : %d (%.3e per seed)%n",
                minA, topTotal, topTotal / (double) n);
        System.out.printf("  chains beginning, height >= %d: %d (%.3e per seed)%n",
                minB, baseTotal, baseTotal / (double) n);
        System.out.printf("  blocks that were both an end and a beginning: %d%n", blocks);
        System.out.printf("%n  P(a chunk pair reaches %d across the border) = %.3e%n",
                target, rate);
        System.out.printf("  P(one chunk reaches %d on its own)            = %.3e%n",
                target, rate(target));
        if (rate > 0 && rate(target) > 0) {
            System.out.printf("  cross-chunk is %.1fx%n", rate / rate(target));
        }
        if (blocks == 0) {
            System.out.println("  nothing lined up in this sample, so the estimate is 0 for "
                    + "want of data rather than because it cannot happen -- use more seeds");
        }
    }

    /**
     * Measured P(a chunk has a chain of at least this height).
     *
     * <p>The shape is the point: it falls off cliffs at column boundaries rather than decaying
     * smoothly, because a chain of C columns tops out at exactly 4C. 8 is the most two columns
     * can give and 12 the most three can, so each is hundreds of times more common than the
     * height one above it.
     */
    private static double rate(int height) {
        switch (Math.max(0, Math.min(height, 17))) {
            case 0: case 1: case 2: case 3: case 4: return 1.0;
            case 5: return 7.687e-1;
            case 6: return 5.437e-1;
            case 7: return 1.976e-1;
            case 8: return 3.773e-2;
            case 9: return 4.797e-5;
            case 10: return 1.495e-5;
            case 11: return 2.872e-6;
            case 12: return 2.940e-7;
            case 13: return 2.000e-9;
            case 14: return 2.000e-9;
            case 15: return 5.0e-10;
            case 16: return 3.0e-10;
            default: return 0.0;    // 17+ needs a fifth column, and a fifth shift level
        }
    }

    /**
     * The split of {@code target} that maximises P(A) x P(B).
     *
     * <p>Both sides pay their own rate and the position matching costs the same either way, so
     * the product is the whole objective. Because the rate cliffs at multiples of four the
     * answer is rarely the even split: 20 as 12+8 beats 10+10 by about 55x. It is not a law
     * though -- 12 comes out 6+6, because 6 is still common enough that two beat one 8.
     */
    static int[] bestSplit(int target) {
        int bestA = 4;
        int bestB = Math.max(4, target - 4);
        double best = -1;
        for (int a = 4; a <= target - 4; a++) {
            int b = target - a;
            double p = rate(a) * rate(b);
            if (p > best) {
                best = p;
                bestA = a;
                bestB = b;
            }
        }
        return new int[]{bestA, bestB};
    }
}
