package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.AirCarveProbe;
import dev.drakou111.sugarcane.gen.BiomeCaneConfig;
import dev.drakou111.sugarcane.gen.BiomeIds;
import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.DirtBlobFilter;
import dev.drakou111.sugarcane.gen.LiquidCarveProbe;
import dev.drakou111.sugarcane.gen.OrbitSampler;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.DecorationLattice;
import dev.drakou111.sugarcane.rng.TwoChunkLift;
import dev.drakou111.sugarcane.validate.BiomeSourceValidator;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Finds cross-chunk stacks by solving for the world seed instead of guessing it.
 *
 * <p>{@link CrossChunk} measures how often two neighbours could build one stack between them.
 * This looks for actual ones, and it cannot work the way the single-chunk pipeline does. That
 * one picks a world seed and asks the lattice where a decoration seed lands; asking a random
 * world seed to place <em>two</em> particular decoration seeds in adjacent chunks is hopeless,
 * because the second is then fixed and wrong.
 *
 * <p>{@link TwoChunkLift} inverts it. Given the pair it returns the world seeds that place both,
 * in milliseconds, so the world seed stops being something to search and becomes something the
 * pair produces. The whole search is then: find compatible pairs, solve, check terrain.
 *
 * <h2>The join is chunk-relative, which is what makes it searchable</h2>
 *
 * <p>A chain ending in chunk A at relative {@code (xa, za)} and one beginning in chunk B at
 * {@code (xb, zb)} meet at the same world block exactly when {@code xa == xb + 16dx} and
 * {@code za == zb + 16dz}, with the same y. Both sides of that are chunk-relative, so the
 * matching key never mentions where the chunks are — the coordinate only comes back at the end,
 * from {@link DecorationLattice}, which is also what finally applies the world border.
 *
 * <p>Chains are half-open: {@code chainTop} is one block above the topmost cane, so an ending at
 * y and a beginning at y are contiguous rather than overlapping.
 *
 * <h2>Two passes, storing the rarer side</h2>
 *
 * <p>The two heights are wildly different in frequency — a 12 is 2.9e-7 per seed and an 8 is
 * 3.8e-2, five orders apart — so holding both in memory is pointless. The taller side is
 * collected into a table keyed by the join, and the shorter is streamed against it. Each pass
 * runs one filter rather than both, so two passes cost what one pass with both would.
 *
 * <h2>What is verified and what is not</h2>
 *
 * <p>Verified: both chains exist under their decoration seeds, they meet at one block, the world
 * seed places both chunks, the chunks are cane-bearing ocean, every column base of both chains
 * is inside an air carve, and chunk A's bottom column has soil under it. Chunk B's does not need
 * soil — it stands on chunk A's cane, which is the entire point.
 *
 * <p>Not verified: decoration order. Chunk A must have decorated before chunk B, which depends
 * on how the world was explored rather than on the seed. A hit here is a strong lead that still
 * wants confirming in game.
 */
public final class CrossFind {

    private static final int OCEAN_INDEX = 5;
    private static final int Y = 128;
    /**
     * The join block, times the decoration seed's low nibble.
     *
     * <p>A pair can only solve if the two decoration seeds agree below bit four -- the world
     * seed cancels out of {@code D1 ^ D2} and the difference is a multiple of sixteen. Putting
     * the nibble in the key means the fifteen pairs in sixteen that cannot work are never
     * formed, rather than being formed and rejected. It is the same 16x either way, but the
     * lift is milliseconds and forming a pair is nanoseconds, so it matters where it lands.
     */
    private static final int KEYS = 24 * 24 * Y * 16;

    /** Above this many stored entries the table is closed early rather than eating the heap. */
    private static final int DEFAULT_MAX_STORE = 20_000_000;

    private CrossFind() {
    }

    private static int key(int x, int z, int y, long ds) {
        return ((((x + 4) + (z + 4) * 24) * Y + y) << 4) | (int) (ds & 15L);
    }

    private static boolean inFrame(int x, int z, int y) {
        return x >= -4 && x <= 19 && z >= -4 && z <= 19 && y >= 0 && y < Y;
    }

    /** A chain's full run: half-open top minus the bottom column's base. */
    private static int runOf(long chain) {
        return ChainPrefilter.chainTop(chain) - ChainPrefilter.chainBaseY(chain, 0);
    }

    /** Chains that END here keep the depth band: the bottom column stands on soil. */
    private static ChainPrefilter endingFilter() {
        return new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT);
    }

    /**
     * Chains that BEGIN here do not. The bottom column stands on the neighbour's cane, so the
     * usual base-y band would hide every join above y=35 — and a tall combination joins high by
     * construction. This was the bug in the first cross-chunk measurement.
     */
    private static ChainPrefilter beginningFilter() {
        return new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT, 11, 64, 3, 4);
    }

    /** Growable (key, seed) pairs, per thread, so collection never synchronises. */
    private static final class Hits {
        int[] keys = new int[1024];
        long[] seeds = new long[1024];
        int size;

        void add(int key, long seed) {
            if (size == keys.length) {
                int grown = keys.length * 2;
                int[] k = new int[grown];
                long[] s = new long[grown];
                System.arraycopy(keys, 0, k, 0, size);
                System.arraycopy(seeds, 0, s, 0, size);
                keys = k;
                seeds = s;
            }
            keys[size] = key;
            seeds[size] = seed;
            size++;
        }
    }

    public static void main(String[] args) throws Exception {
        long seeds = args.length > 0 ? Long.parseLong(args[0]) : 1_000_000_000L;
        int threads = Cli.clampThreads(args.length > 1 ? Integer.parseInt(args[1])
                : Runtime.getRuntime().availableProcessors());
        int target = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        final int minA;
        final int minB;
        if (args.length > 4) {
            minA = Integer.parseInt(args[3]);
            minB = Integer.parseInt(args[4]);
        } else {
            int[] split = CrossChunk.bestSplit(target);
            minA = split[0];
            minB = split[1];
        }
        int dx = 1, dz = 0, maxStore = DEFAULT_MAX_STORE;
        boolean water = false;
        for (String arg : args) {
            if (arg.startsWith("--dx=")) {
                dx = Integer.parseInt(arg.substring(5));
            } else if (arg.startsWith("--dz=")) {
                dz = Integer.parseInt(arg.substring(5));
            } else if (arg.startsWith("--max-store=")) {
                maxStore = Integer.parseInt(arg.substring(12));
            } else if (arg.equals("--water-probe")) {
                water = true;
            }
        }
        if (dx == 0 && dz == 0) {
            System.err.println("--dx and --dz cannot both be zero: that is one chunk, not two");
            return;
        }

        // Store whichever side is rarer, and stream the other. Height is monotone in rarity,
        // so the taller minimum wins without having to measure anything.
        final boolean storeEndings = minA >= minB;
        final int storedMin = storeEndings ? minA : minB;
        final int streamedMin = storeEndings ? minB : minA;
        final int fdx = dx, fdz = dz;
        final boolean useWater = water;

        System.out.printf("cross-chunk SEARCH for height %d, %d seeds, %d threads%n",
                target, seeds, threads);
        System.out.printf("  chunk A contributes >= %d, chunk B at %+d,%+d contributes >= %d%n",
                minA, dx, dz, minB);
        System.out.printf("  storing the %s side (>= %d, the rarer), streaming the other%n",
                storeEndings ? "ending" : "beginning", storedMin);

        long start = System.currentTimeMillis();
        long runs = (seeds + OrbitSampler.RUN - 1) / OrbitSampler.RUN;

        // ---- pass 1: collect the rare side, keyed by where the join would be ----
        final Hits[] collected = new Hits[threads];
        AtomicLong nextRun = new AtomicLong();
        AtomicLong stored = new AtomicLong();
        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            final int cap = maxStore;
            pool[t] = new Thread(() -> {
                Hits hits = new Hits();
                ChainPrefilter filter = storeEndings ? endingFilter() : beginningFilter();
                for (long run = nextRun.getAndIncrement(); run < runs;
                        run = nextRun.getAndIncrement()) {
                    if (stored.get() >= cap) {
                        break;
                    }
                    long ds = OrbitSampler.runStart(run);
                    for (int k = 0; k < OrbitSampler.RUN; k++) {
                        int n = filter.collectChains(ds, OCEAN_INDEX, storedMin);
                        if (!filter.chainsOverflowed()) {
                            for (int i = 0; i < n; i++) {
                                long chain = filter.chain(i);
                                int x = ChainPrefilter.chainX(chain);
                                int z = ChainPrefilter.chainZ(chain);
                                int y;
                                if (storeEndings) {
                                    y = ChainPrefilter.chainTop(chain);
                                } else {
                                    // Express a beginning in chunk A's frame, so both sides
                                    // key on the same number.
                                    y = ChainPrefilter.chainBaseY(chain, 0);
                                    x += 16 * fdx;
                                    z += 16 * fdz;
                                }
                                if (inFrame(x, z, y)) {
                                    hits.add(key(x, z, y, ds), ds);
                                    stored.incrementAndGet();
                                }
                            }
                        }
                        ds = OrbitSampler.shift(ds, OCEAN_INDEX,
                                SugarCaneFeature.VEGETAL_DECORATION);
                    }
                }
                collected[id] = hits;
            }, "crossfind-collect-" + t);
            pool[t].start();
        }
        for (Thread th : pool) {
            th.join();
        }

        long total = 0;
        for (Hits h : collected) {
            total += h.size;
        }
        boolean truncated = stored.get() >= maxStore;
        System.out.printf("%n  pass 1: %d chains stored in %.1f s%s%n", total,
                (System.currentTimeMillis() - start) / 1000.0,
                truncated ? " (table filled up -- raise --max-store, raise the stored side's "
                        + "minimum, or use fewer seeds; the search below is still valid, just "
                        + "over less ground)" : "");
        if (total == 0) {
            System.out.println("  nothing to join against, so nothing to search");
            return;
        }

        // Counting sort into CSR: at most 24*24*128 distinct joins, so this is a small table
        // however many chains landed in it.
        int[] offset = new int[KEYS + 1];
        for (Hits h : collected) {
            for (int i = 0; i < h.size; i++) {
                offset[h.keys[i] + 1]++;
            }
        }
        for (int i = 0; i < KEYS; i++) {
            offset[i + 1] += offset[i];
        }
        long[] table = new long[(int) total];
        int[] cursor = offset.clone();
        for (Hits h : collected) {
            for (int i = 0; i < h.size; i++) {
                table[cursor[h.keys[i]]++] = h.seeds[i];
            }
        }
        for (int i = 0; i < collected.length; i++) {
            collected[i] = null;      // the per-thread arrays are the big allocation, not this
        }

        int occupied = 0;
        for (int i = 0; i < KEYS; i++) {
            if (offset[i + 1] > offset[i]) {
                occupied++;
            }
        }
        System.out.printf("  spread over %d distinct (join block, nibble) keys%n", occupied);

        // ---- pass 2: stream the common side, join, solve, verify ----
        final int[] off = offset;
        final long[] tab = table;
        AtomicLong joins = new AtomicLong();
        AtomicLong solvedSeeds = new AtomicLong();
        AtomicLong inBorder = new AtomicLong();
        AtomicLong oceanPairs = new AtomicLong();
        AtomicLong carved = new AtomicLong();
        AtomicLong found = new AtomicLong();
        AtomicLong streamed = new AtomicLong();
        nextRun.set(0);
        long pass2Start = System.currentTimeMillis();

        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                ChainPrefilter filter = storeEndings ? beginningFilter() : endingFilter();
                ChainPrefilter endA = endingFilter();
                ChainPrefilter beginB = beginningFilter();
                AirCarveProbe probe = new AirCarveProbe().ravinesOnly(true);
                LiquidCarveProbe liquid = useWater ? new LiquidCarveProbe() : null;
                DirtBlobFilter dirt = new DirtBlobFilter();
                RegionSearcher.Stats stats = new RegionSearcher.Stats();
                RegionSearcher.Worker worker =
                        new RegionSearcher.Worker(999, false, 0, stats, 0);

                for (long run = nextRun.getAndIncrement(); run < runs;
                        run = nextRun.getAndIncrement()) {
                    long ds = OrbitSampler.runStart(run);
                    for (int k = 0; k < OrbitSampler.RUN; k++) {
                        int n = filter.collectChains(ds, OCEAN_INDEX, streamedMin);
                        if (!filter.chainsOverflowed()) {
                            for (int i = 0; i < n; i++) {
                                long chain = filter.chain(i);
                                int x = ChainPrefilter.chainX(chain);
                                int z = ChainPrefilter.chainZ(chain);
                                int y;
                                if (storeEndings) {
                                    y = ChainPrefilter.chainBaseY(chain, 0);
                                    x += 16 * fdx;
                                    z += 16 * fdz;
                                } else {
                                    y = ChainPrefilter.chainTop(chain);
                                }
                                if (!inFrame(x, z, y)) {
                                    continue;
                                }
                                int key = key(x, z, y, ds);
                                for (int p = off[key]; p < off[key + 1]; p++) {
                                    long dsA = storeEndings ? tab[p] : ds;
                                    long dsB = storeEndings ? ds : tab[p];
                                    joins.incrementAndGet();
                                    examine(dsA, dsB, fdx, fdz, minA, minB, endA, beginB,
                                            probe, liquid, dirt, worker, useWater,
                                            solvedSeeds, inBorder, oceanPairs, carved, found);
                                }
                            }
                        }
                        ds = OrbitSampler.shift(ds, OCEAN_INDEX,
                                SugarCaneFeature.VEGETAL_DECORATION);
                        streamed.incrementAndGet();
                    }
                }
            }, "crossfind-solve-" + t);
            pool[t].start();
        }

        Thread progress = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    return;
                }
                long ms = System.currentTimeMillis() - pass2Start;
                System.out.printf("[%4.1f min] %d seeds streamed (%.0f/s), joins %d, "
                                + "world seeds solved %d, in border %d, ocean %d, "
                                + "past carve %d, FINDS %d%n",
                        ms / 60000.0, streamed.get(),
                        streamed.get() * 1000.0 / Math.max(1, ms), joins.get(),
                        solvedSeeds.get(), inBorder.get(), oceanPairs.get(),
                        carved.get(), found.get());
                System.out.flush();
            }
        }, "crossfind-progress");
        progress.setDaemon(true);
        progress.start();

        for (Thread th : pool) {
            th.join();
        }
        progress.interrupt();

        double secs = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("%ndone in %.1f s%n", secs);
        System.out.printf("  chains stored           : %d%n", total);
        System.out.printf("  joins tried             : %d%n", joins.get());
        System.out.printf("  world seeds solved      : %d%n", solvedSeeds.get());
        System.out.printf("  pairs inside the border : %d%n", inBorder.get());
        System.out.printf("  both chunks cane ocean  : %d%n", oceanPairs.get());
        System.out.printf("  every base carved       : %d%n", carved.get());
        System.out.printf("  FINDS                   : %d%n", found.get());
        if (joins.get() == 0) {
            System.out.println("  no pair of chains ever met at a block -- more seeds, or a "
                    + "split whose two halves are both reachable");
        }
    }

    /**
     * One candidate pair, from the RNG all the way to terrain.
     *
     * <p>Cheap tests first and each one rarer than the last: solve, border, biome, carve. The
     * solve is milliseconds and everything after it is microseconds, so the ordering that
     * matters is that the solve happens once per pair rather than once per world seed.
     */
    private static void examine(long dsA, long dsB, int dx, int dz, int minA, int minB,
            ChainPrefilter endA, ChainPrefilter beginB, AirCarveProbe probe,
            LiquidCarveProbe liquid, DirtBlobFilter dirt, RegionSearcher.Worker worker,
            boolean useWater, AtomicLong solvedSeeds, AtomicLong inBorder,
            AtomicLong oceanPairs, AtomicLong carved, AtomicLong found) {

        long[] worldSeeds = TwoChunkLift.solve(dsA, dsB, dx, dz);
        if (worldSeeds.length == 0) {
            return;
        }
        solvedSeeds.addAndGet(worldSeeds.length);

        for (long ws : worldSeeds) {
            DecorationLattice lattice = new DecorationLattice(ws);
            int[] chunk = lattice.solve(dsA);
            if (chunk == null) {
                continue;
            }
            int cxa = chunk[0], cza = chunk[1];
            int cxb = cxa + dx, czb = cza + dz;
            if (Math.abs(cxb) > DecorationLattice.BORDER_CHUNKS
                    || Math.abs(czb) > DecorationLattice.BORDER_CHUNKS) {
                continue;
            }
            // The lift solved an equation; this asks setDecorationSeed itself. If they ever
            // disagree the lift is wrong, and it should be caught here rather than believed.
            if (lattice.decorationSeedOf(cxb, czb) != dsB) {
                continue;
            }
            inBorder.incrementAndGet();

            worker.prepare(ws);
            int biomeA = BiomeIds.noiseGen(worker.biomeSource(), cxa * 4 + 2, cza * 4 + 2);
            int biomeB = BiomeIds.noiseGen(worker.biomeSource(), cxb * 4 + 2, czb * 4 + 2);
            if (!RegionSearcher.isSearchableOcean(biomeA) || !BiomeCaneConfig.hasSugarCane(biomeA)
                    || !RegionSearcher.isSearchableOcean(biomeB)
                    || !BiomeCaneConfig.hasSugarCane(biomeB)) {
                continue;
            }
            oceanPairs.incrementAndGet();

            // Recover the two chains. The pair was matched on a join key, so the chains that
            // produced it are found again by re-running the filters and looking for the block
            // where one stops and the other starts.
            int na = endA.collectChains(dsA, OCEAN_INDEX, minA);
            if (endA.chainsOverflowed()) {
                continue;
            }
            int nb = beginB.collectChains(dsB, OCEAN_INDEX, minB);
            if (beginB.chainsOverflowed()) {
                continue;
            }
            for (int i = 0; i < na; i++) {
                long ca = endA.chain(i);
                int ax = ChainPrefilter.chainX(ca), az = ChainPrefilter.chainZ(ca);
                int top = ChainPrefilter.chainTop(ca);
                for (int j = 0; j < nb; j++) {
                    long cb = beginB.chain(j);
                    if (ChainPrefilter.chainX(cb) + 16 * dx != ax
                            || ChainPrefilter.chainZ(cb) + 16 * dz != az
                            || ChainPrefilter.chainBaseY(cb, 0) != top) {
                        continue;
                    }
                    int px = cxa * 16 + ax;
                    int pz = cza * 16 + az;
                    if (!allCarved(probe, liquid, dirt, worker, ws, cxa, cza, ca, px, pz,
                            useWater, true)
                            || !allCarved(probe, liquid, dirt, worker, ws, cxb, czb, cb, px, pz,
                                    useWater, false)) {
                        continue;
                    }
                    carved.incrementAndGet();
                    found.incrementAndGet();
                    int height = runOf(ca) + runOf(cb);
                    synchronized (CrossFind.class) {
                        System.out.printf("%nFIND height %d at %d,%d,%d%n",
                                height, px, ChainPrefilter.chainBaseY(ca, 0), pz);
                        System.out.printf("  world seed %d (low 48 bits; the top 16 do not "
                                + "affect decoration and only shift the biome map)%n", ws);
                        System.out.printf("  chunk A %d,%d gives %d, chunk B %d,%d adds %d "
                                        + "starting at y=%d%n",
                                cxa, cza, runOf(ca), cxb, czb, runOf(cb), top);
                        System.out.println("  decoration order is assumed, not checked: A has "
                                + "to have decorated before B");
                        System.out.flush();
                    }
                }
            }
        }
    }

    /**
     * Every column base of one chain is air, has water beside it, and — for the chain that
     * stands on the sea floor rather than on cane — soil beneath it.
     */
    private static boolean allCarved(AirCarveProbe probe, LiquidCarveProbe liquid,
            DirtBlobFilter dirt, RegionSearcher.Worker worker, long ws, int cx, int cz,
            long chain, int px, int pz, boolean useWater, boolean needsSoil) {
        int pcx = px >> 4, pcz = pz >> 4;
        boolean ocean = BiomeSourceValidator.isOcean(
                BiomeIds.noiseGen(worker.biomeSource(), pcx * 4, pcz * 4));
        probe.walk(ws, pcx, pcz, ocean);
        int columns = ChainPrefilter.chainColumns(chain);
        for (int c = 0; c < columns; c++) {
            if (!probe.isCarved(px, ChainPrefilter.chainBaseY(chain, c), pz)) {
                return false;
            }
        }
        if (useWater && liquid != null) {
            liquid.walk(ws, pcx, pcz);
            for (int c = 0; c < columns; c++) {
                if (!liquid.waterBeside(px, ChainPrefilter.chainBaseY(chain, c) - 1, pz)) {
                    return false;
                }
            }
        }
        if (!needsSoil) {
            // Chunk B's bottom column stands on chunk A's topmost cane, so asking for dirt
            // under it would reject exactly the case this whole command exists to find.
            return true;
        }
        int rx = px - cx * 16, rz = pz - cz * 16;
        int soilY = ChainPrefilter.chainBaseY(chain, 0) - 1;
        long deco = new DecorationLattice(ws).decorationSeedOf(cx, cz);
        return rx < 0 || rx > 15 || rz < 0 || rz > 15 || dirt.couldSupply(deco, rx, soilY, rz);
    }
}
