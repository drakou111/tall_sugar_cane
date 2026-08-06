package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;

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
                // A's chain stands on real soil, so the depth band applies to it.
                ChainPrefilter a = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT);
                // B's stands on A's CANE. The band exists because a base needs soil the
                // terrain actually put there; a column landing on cane needs nothing of the
                // sort, so B gets the whole legal column. Enumerating B with the band was
                // the flaw in the first version of this measurement: a join above y=35 was
                // invisible, and for tall combinations the join is necessarily high -- an
                // 8-tall A from y=25 tops out at 33, a 12-tall from 25 at 37. It excluded
                // exactly the case worth asking about.
                ChainPrefilter b = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT,
                        11, 64, 3, 4);
                for (long i = next.getAndIncrement(); i < seeds; i = next.getAndIncrement()) {
                    // Decoration seeds sampled directly rather than through a world seed
                    // and a lattice. Two adjacent chunks have decoration seeds related by
                    // D' = ((D ^ ws) + 16a) ^ ws, which for a random world seed is no more
                    // predictable from D than an independent draw -- and this is a rate
                    // measurement, so what matters is the joint distribution, not that a
                    // particular pair is genuinely adjacent. Building a lattice per pair was
                    // most of the run time and bought nothing.
                    long dsA = mix(i) & ((1L << 48) - 1);
                    int soloA = a.tallestPossible(dsA, OCEAN_INDEX);
                    int solo = soloA;
                    int best = soloA;
                    single.incrementAndGet(Math.min(solo, 63));

                    // minPart is what each side must contribute, and it matters more than it
                    // looks: collectChains records the SHORTEST chain reaching it, so
                    // minPart=4 makes every chain a single column and no combination can
                    // exceed 8. Asking about 16 means asking each side for 8.
                    int na = a.collectChains(dsA, OCEAN_INDEX, minPart);
                    boolean aOk = !a.chainsOverflowed();

                    // Nothing of A's reaches a strip a neighbour could continue from, so no
                    // neighbour can help. Skipping here is most of the run time, since the
                    // four neighbours are eight more filter evaluations and this is false
                    // almost always.
                    boolean anyInStrip = false;
                    for (int ia = 0; ia < na && aOk && !anyInStrip; ia++) {
                        int ax = ChainPrefilter.chainX(a.chain(ia));
                        int az = ChainPrefilter.chainZ(a.chain(ia));
                        anyInStrip = ax >= 12 || ax <= 3 || az >= 12 || az <= 3;
                    }
                    if (!anyInStrip) {
                        joined.incrementAndGet(Math.min(best, 63));
                        pairs.incrementAndGet();
                        continue;
                    }

                    // All four orthogonal neighbours. The overhang is four blocks in every
                    // direction, so each shares an eight-wide strip with this chunk, and a
                    // chain can be continued from any of them.
                    for (int dir = 0; dir < 4; dir++) {
                        int nx = dir == 0 ? 1 : dir == 1 ? -1 : 0;
                        int nz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
                        long dsB = mix(i ^ (0x9E3779B9L * (dir + 1))) & ((1L << 48) - 1);
                        int nb = b.collectChains(dsB, OCEAN_INDEX, minPart);
                        if (b.chainsOverflowed()) {
                            continue;
                        }
                        int shiftX = nx * 16;
                        int shiftZ = nz * 16;
                        for (int ia = 0; ia < na; ia++) {
                            long ca = a.chain(ia);
                            int ax = ChainPrefilter.chainX(ca);
                            int az = ChainPrefilter.chainZ(ca);
                            // The same world block, in the neighbour's frame.
                            int bx = ax - shiftX;
                            int bz = az - shiftZ;
                            if (bx < -4 || bx > 19 || bz < -4 || bz > 19) {
                                continue;   // outside the strip both can reach
                            }
                            int topA = ChainPrefilter.chainTop(ca);
                            int heightA = topA - ChainPrefilter.chainBaseY(ca, 0);
                            for (int ib = 0; ib < nb; ib++) {
                                long cb = b.chain(ib);
                                if (ChainPrefilter.chainX(cb) != bx
                                        || ChainPrefilter.chainZ(cb) != bz) {
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
