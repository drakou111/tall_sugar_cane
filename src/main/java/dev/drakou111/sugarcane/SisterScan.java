package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Re-rolls the terrain under a known chain by sweeping the seed's upper 16 bits.
 *
 * <p>Seeds sharing their low 48 bits have <em>the same decoration seed at the same chunk,
 * the same lattice solution and the same carver walks</em> — verified directly in FINDINGS
 * 6al. So a chain lives at the same block, with the same column bases, in all 65,536 of
 * them. The only thing the upper bits change is the biome map, and through it the
 * depth/scale field that moves the sea floor.
 *
 * <p>That makes this the natural thing to do with a find the game truncates. A simulated
 * 12 that stands 8 in game lost its upper columns to terrain, and terrain is exactly what
 * a sister re-rolls while leaving the RNG alone. 6al measured the correlation: given one
 * sister has a stackable spot, 56% of the others do too, against a 1.1e-3 base rate. They
 * are not independent tries, but they are cheap ones, and there are 65,536 of them.
 *
 * <p>Usage: {@code sisters <seed> <x> <y> <z> [count] [threads] [minHeight]}
 */
public final class SisterScan {

    private static final long LOW48 = (1L << 48) - 1;

    private SisterScan() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 4) {
            System.err.println("usage: sisters <seed> <x> <y> <z> [count] [threads] [minHeight]");
            System.exit(2);
            return;
        }
        long seed = Long.parseLong(args[0]);
        int tx = Integer.parseInt(args[1]);
        int ty = Integer.parseInt(args[2]);
        int tz = Integer.parseInt(args[3]);
        int count = args.length > 4 ? Integer.parseInt(args[4]) : 65536;
        int threads = args.length > 5 ? Integer.parseInt(args[5])
                : Runtime.getRuntime().availableProcessors();
        int minHeight = args.length > 6 ? Integer.parseInt(args[6]) : 5;

        int chunkX = tx >> 4, chunkZ = tz >> 4;
        long low48 = seed & LOW48;

        System.out.printf("sister scan of %d (low 48 = %d) at %d,%d,%d in chunk %d,%d%n",
                seed, low48, tx, ty, tz, chunkX, chunkZ);
        System.out.printf("  %d upper-16 values, %d threads, reporting height >= %d%n",
                count, threads, minHeight);
        System.out.println("  the decoration seed, the chain and the carvers are identical "
                + "in every one of these; only the terrain differs.");

        // Read by prepare(), so they must be set before any worker starts.
        RegionSearcher.centreOverrideX = chunkX;
        RegionSearcher.centreOverrideZ = chunkZ;
        RegionSearcher.relaxFilters = true;
        RegionSearcher.allBiomes = true;

        AtomicInteger next = new AtomicInteger();
        AtomicLong generated = new AtomicLong();
        AtomicInteger best = new AtomicInteger();
        AtomicInteger shown = new AtomicInteger();
        AtomicLong bestSeed = new AtomicLong();
        int[] histogram = new int[64];
        Object lock = new Object();
        long start = System.currentTimeMillis();

        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                RegionSearcher.Stats stats = new RegionSearcher.Stats();
                // A high report height keeps RegionSearcher's own HIT printing quiet:
                // this command reports its own hits, and a full 65,536 sweep would
                // otherwise print each one twice.
                RegionSearcher.Worker worker =
                        new RegionSearcher.Worker(999, false, 0, stats, 0);
                for (int u = next.getAndIncrement(); u < count; u = next.getAndIncrement()) {
                    long full = low48 | ((long) u << 48);
                    worker.prepare(full);
                    worker.searchOneChunk(chunkX, chunkZ);
                    ArrayWorld world = worker.world;
                    // y=200 is air in anything that generated; SOLID means this sister's
                    // biome has no implemented surface builder and nothing was built.
                    if (world.getBlock(tx, 200, tz) == Blocks.SOLID) {
                        continue;
                    }
                    generated.incrementAndGet();
                    int height = tallestAt(world, tx, tz);
                    synchronized (lock) {
                        histogram[Math.min(height, histogram.length - 1)]++;
                    }
                    if (height >= minHeight && shown.get() < 24) {
                        int base = ty;
                        while (world.getBlock(tx, base - 1, tz) == Blocks.SUGAR_CANE) {
                            base--;
                        }
                        int solo = world.caneRunFromOneChunk(tx, base, tz);
                        synchronized (lock) {
                            if (shown.incrementAndGet() <= 24) {
                                System.out.printf("  upper %5d  seed %-22d height %2d "
                                                + "(base y=%d, %d from this chunk alone)%s%n",
                                        u, full, height, base, solo,
                                        solo < height ? "  <- cross-chunk, not verifiable" : "");
                                System.out.flush();
                            }
                        }
                    }
                    best.accumulateAndGet(height, Math::max);
                    if (best.get() == height) {
                        bestSeed.set(full);
                    }
                }
            }, "sister-" + t);
            pool[t].start();
        }
        for (Thread th : pool) {
            th.join();
        }

        double secs = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("%n%d of %d sisters generated here (%.1f%%), %.0f/s over %.1f s%n",
                generated.get(), count, 100.0 * generated.get() / count,
                generated.get() / Math.max(0.001, secs), secs);
        System.out.println("cane height at this column, over the sisters that generated:");
        for (int h = histogram.length - 1; h >= 1; h--) {
            if (histogram[h] > 0) {
                System.out.printf("  height %2d: %d%n", h, histogram[h]);
            }
        }
        System.out.printf("tallest %d, on seed %d%n", best.get(), bestSeed.get());
    }

    /** The contiguous cane run through this column, wherever in it the cane sits. */
    private static int tallestAt(ArrayWorld world, int x, int z) {
        int best = 0;
        for (int y = 1; y < 120; y++) {
            if (world.getBlock(x, y, z) == Blocks.SUGAR_CANE) {
                best = Math.max(best, world.caneRunThrough(x, y, z));
            }
        }
        return best;
    }
}
