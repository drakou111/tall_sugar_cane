package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.DecorationLattice;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Can two neighbouring chunks build one stack between them, and is it worth searching for?
 *
 * <p>A placement lands at chunk-relative x in -4..19, so a chunk can put cane up to four
 * blocks over its own border. If chunk A stacks into B's territory and B later stacks on top
 * of that, the run is the sum of the two -- and the RNG requirement is two ordinary chains
 * instead of one extraordinary one, which is the whole appeal.
 *
 * <p>This measures rather than searches. Reaching a given height by combining two chunks is
 * only worth building a pipeline for if it happens meaningfully more often than reaching it
 * in one, and that is a number, not an argument.
 *
 * <p>Two constraints make it much rarer than "two chains that happen to line up":
 * <ul>
 *   <li>the shared column must be reachable from both, which is an eight-block strip along
 *       the border -- chunk-relative x 12..19 for A, the same block being -4..3 for B;</li>
 *   <li>B's chain must start exactly where A's stops, and B reads its own RNG stream, so
 *       the two y values have to agree by luck.</li>
 * </ul>
 *
 * <p>Placement order is assumed, not checked: A has to have decorated before B, or B's column
 * has nothing to stand on. That depends on how the world was explored, which is why
 * {@code SisterScan} calls these "not verifiable". A cross-chunk find is a lead, not a find.
 */
public final class CrossChunk {

    private static final int OCEAN_INDEX = 5;

    private CrossChunk() {
    }

    public static void main(String[] args) throws Exception {
        long seeds = args.length > 0 ? Long.parseLong(args[0]) : 200_000L;
        int threads = Cli.clampThreads(args.length > 1 ? Integer.parseInt(args[1])
                : Runtime.getRuntime().availableProcessors());
        int minPart = args.length > 2 ? Integer.parseInt(args[2]) : 5;

        System.out.printf("cross-chunk potential over %d world seeds, %d threads%n",
                seeds, threads);
        System.out.printf("  each seed: one chunk pair, chains of >= %d per side, "
                + "combined only where both can reach the block%n", minPart);

        // Tallest reachable in one chunk, and tallest reachable across the pair.
        AtomicLongArray single = new AtomicLongArray(64);
        AtomicLongArray joined = new AtomicLongArray(64);
        AtomicLong pairs = new AtomicLong();
        AtomicLong better = new AtomicLong();
        AtomicLong bestJoined = new AtomicLong();

        Thread[] pool = new Thread[threads];
        AtomicLong next = new AtomicLong();
        long start = System.currentTimeMillis();
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                ChainPrefilter a = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT);
                ChainPrefilter b = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT);
                for (long i = next.getAndIncrement(); i < seeds; i = next.getAndIncrement()) {
                    long worldSeed = mix(i);
                    DecorationLattice lattice = new DecorationLattice(worldSeed);
                    int cx = (int) (mix(i ^ 0x9E37L) % 1000) - 500;
                    int cz = (int) (mix(i ^ 0x1234L) % 1000) - 500;
                    long dsA = lattice.decorationSeedOf(cx, cz);
                    long dsB = lattice.decorationSeedOf(cx + 1, cz);

                    int soloA = a.tallestPossible(dsA, OCEAN_INDEX);
                    int soloB = b.tallestPossible(dsB, OCEAN_INDEX);
                    int solo = Math.max(soloA, soloB);
                    single.incrementAndGet(Math.min(solo, 63));

                    int best = solo;
                    int na = a.collectChains(dsA, OCEAN_INDEX, minPart);
                    int nb = b.collectChains(dsB, OCEAN_INDEX, minPart);
                    if (!a.chainsOverflowed() && !b.chainsOverflowed()) {
                        for (int ia = 0; ia < na; ia++) {
                            long ca = a.chain(ia);
                            // A's column, in B's coordinates. Only the eight-block strip
                            // along the shared border is reachable from both.
                            int ax = ChainPrefilter.chainX(ca);
                            int bx = ax - 16;
                            if (bx < -4 || bx > 19 || ax < -4 || ax > 19) {
                                continue;
                            }
                            int topA = ChainPrefilter.chainTop(ca);
                            int heightA = topA - ChainPrefilter.chainBaseY(ca, 0);
                            for (int ib = 0; ib < nb; ib++) {
                                long cb = b.chain(ib);
                                if (ChainPrefilter.chainX(cb) != bx
                                        || ChainPrefilter.chainZ(cb) != ChainPrefilter.chainZ(ca)) {
                                    continue;
                                }
                                if (ChainPrefilter.chainBaseY(cb, 0) != topA) {
                                    continue;   // B has to start exactly where A stops
                                }
                                int total = heightA + ChainPrefilter.chainTop(cb)
                                        - ChainPrefilter.chainBaseY(cb, 0);
                                best = Math.max(best, total);
                            }
                        }
                    }
                    joined.incrementAndGet(Math.min(best, 63));
                    pairs.incrementAndGet();
                    if (best > solo) {
                        better.incrementAndGet();
                        bestJoined.accumulateAndGet(best, Math::max);
                    }
                }
            }, "cross-" + t);
            pool[t].start();
        }
        for (Thread th : pool) {
            th.join();
        }

        double secs = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("%n%d chunk pairs in %.1f s%n", pairs.get(), secs);
        System.out.printf("  pairs where combining beats either chunk alone: %d (%.4f%%)%n",
                better.get(), 100.0 * better.get() / Math.max(1, pairs.get()));
        System.out.printf("  tallest combined seen: %d%n", bestJoined.get());
        System.out.printf("%n%-8s %14s %14s%n", "height", "one chunk", "two chunks");
        for (int h = 63; h >= 4; h--) {
            long s = tailFrom(single, h);
            long j = tailFrom(joined, h);
            if (s == 0 && j == 0) {
                continue;
            }
            System.out.printf(">= %-5d %14s %14s%n", h, rate(s, pairs.get()), rate(j, pairs.get()));
        }
    }

    private static long tailFrom(AtomicLongArray a, int from) {
        long n = 0;
        for (int i = from; i < a.length(); i++) {
            n += a.get(i);
        }
        return n;
    }

    private static String rate(long hits, long of) {
        return hits == 0 ? "0" : String.format("%.3e", hits / (double) of);
    }

    /** splitmix64, so the sampled world seeds and positions spread. */
    private static long mix(long i) {
        long z = i * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return Math.abs(z ^ (z >>> 31));
    }
}
