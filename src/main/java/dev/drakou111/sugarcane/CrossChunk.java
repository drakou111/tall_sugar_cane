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
        // What you actually want to say is how tall a stack you are after. The split is a
        // consequence, and a badly chosen one costs orders of magnitude, so it is picked
        // rather than asked for. Give both explicitly to override.
        int target = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        int minA;
        int minB;
        if (args.length > 4) {
            minA = Integer.parseInt(args[3]);
            minB = Integer.parseInt(args[4]);
        } else {
            int[] best = bestSplit(target);
            minA = best[0];
            minB = best[1];
        }

        System.out.printf("cross-chunk potential over %d world seeds, %d threads%n",
                seeds, threads);
        System.out.printf("  target %d: this chunk contributes >= %d, the neighbour >= %d "
                        + "(estimated %.2e per pair before the strip constraint)%n",
                target, minA, minB, rate(minA) * rate(minB));
        System.out.println("  combined only where both can reach the block");

        // Tallest reachable in one chunk, and tallest reachable across the pair.
        AtomicLongArray single = new AtomicLongArray(64);
        AtomicLongArray joined = new AtomicLongArray(64);
        AtomicLong pairs = new AtomicLong();
        AtomicLong better = new AtomicLong();
        AtomicLong bestJoined = new AtomicLong();
        // heightA of each successful combination: which side carried it.
        AtomicLongArray splitA = new AtomicLongArray(64);

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
                    int na = a.collectChains(dsA, OCEAN_INDEX, minA);
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
                        int nb = b.collectChains(dsB, OCEAN_INDEX, minB);
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
                                if (total > best) {
                                    best = total;
                                    splitA.incrementAndGet(Math.min(heightA, 63));
                                }
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
        System.out.printf("%nsplits that produced a combination (height carried by this "
                + "chunk):%n");
        for (int h = 0; h < 64; h++) {
            if (splitA.get(h) > 0) {
                System.out.printf("  this chunk %2d + neighbour: %d%n", h, splitA.get(h));
            }
        }
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

    /**
     * Measured P(a chunk has a chain of at least this height), from the one-chunk column of
     * this same command over 500M samples.
     *
     * <p>The shape is the whole point: it falls off cliffs at column boundaries rather than
     * decaying smoothly, because a chain of C columns tops out at exactly 4C. 8 is the most
     * two columns can give and 12 the most three can, so each is hundreds of times more
     * common than the height one above it.
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
     * <p>Both sides pay their own rate and the position-matching cost is the same whatever
     * the split, so the product is the whole objective. Because the rate cliffs at multiples
     * of four, the answer is never the even split: reaching 20 as 12+8 is about 55x likelier
     * than as 10+10, and reaching 24 as 16+8 beats 12+12 by about a hundred.
     *
     * <p>The rule that falls out: ask each side for a multiple of four, and never for one
     * more than a multiple of four.
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
