package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.AirCarveProbe;
import dev.drakou111.sugarcane.gen.BiomeCaneConfig;
import dev.drakou111.sugarcane.gen.BiomeIds;
import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.DirtBlobFilter;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.DecorationLattice;
import dev.drakou111.sugarcane.validate.BiomeSourceValidator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The reverse search: pick the cane RNG first, then solve for a chunk that has it.
 *
 * <p>The box scan in {@link RegionSearcher} pays for a chunk's terrain and only then
 * finds out whether its draws could ever have stacked anything. At height 5 that is
 * the right trade — 61% of decoration seeds could (FINDINGS 6ac) — but at height 8
 * only 3.4% could, so 29 chunks in 30 are generated for nothing.
 *
 * <p>This inverts the order:
 *
 * <ol>
 *   <li><b>Build the target set.</b> Run {@link ChainPrefilter} over decoration seeds
 *       and keep the ones whose draws could chain a tall enough run somewhere. No
 *       terrain is involved, so this is pure RNG work at ~150 us a seed.</li>
 *   <li><b>Solve for coordinates.</b> {@link DecorationLattice} turns a wanted
 *       decoration seed into the chunk inside the world border that has it. The
 *       target set does not depend on the world seed, so step 1 is paid once and
 *       amortises over every seed tried afterwards.</li>
 *   <li><b>Generate only those chunks.</b> One chunk per candidate, its eight
 *       neighbours for the ±4 reach, and the real feature on top.</li>
 * </ol>
 *
 * <p>Two structural costs to know about. Only 1 target in 16 is reachable for a given
 * world seed — the low four bits of a decoration seed are the world seed's own,
 * because the block coordinate is {@code 16*cx} — and of those about 0.8 land inside
 * the border, so a set of size |T| yields |T|/20 candidates per seed. Targets are
 * therefore bucketed by their low four bits and only the matching bucket is walked.
 *
 * <p>Candidates arrive scattered rather than in a box, so each would cost its own 3x3
 * neighbourhood — 7.1 chunks generated per chunk searched against the box scan's 1.57,
 * the handicap FINDINGS 6t predicted. {@link AirCarveProbe} is what removes it: a chain
 * cannot exist unless an AIR-step carver reached every one of its column bases, that
 * question needs no terrain, and it rejects 97.9% of candidates for 49 us. Generated
 * chunks per candidate fall to 0.15.
 *
 * <p>Measured at roughly 95x the box scan. See FINDINGS 6ac, 6ad and 6ae.
 */
public final class ReverseSearcher {

    /** Every ocean biome is count 10, index 5, so one target set covers all of them. */
    private static final int OCEAN_COUNT = SugarCaneFeature.COUNT_DEFAULT;
    private static final int OCEAN_INDEX = 5;

    private static final long DEFAULT_UPDATE_MS = 60_000L;

    private ReverseSearcher() {
    }

    /**
     * Whether any chain's first column could stand on dirt from this chunk's own
     * ORE_DIRT pass. Accepts without testing when the chains overflowed or the chain
     * sits outside the chunk, where a neighbour's blobs would own the block and their
     * decoration seed needs a world seed that is not chosen yet.
     */
    private static boolean soilPossible(ChainPrefilter filter, DirtBlobFilter dirt,
            long decorationSeed, int chains) {
        if (filter.chainsOverflowed()) {
            return true;
        }
        for (int i = 0; i < chains; i++) {
            long chain = filter.chain(i);
            int x = ChainPrefilter.chainX(chain);
            int z = ChainPrefilter.chainZ(chain);
            int soilY = ChainPrefilter.chainBaseY(chain, 0) - 1;
            if (x < 0 || x > 15 || z < 0 || z > 15) {
                return true;
            }
            if (dirt.couldSupply(decorationSeed, x, soilY, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any chain of this seed has all its column bases inside an AIR-step
     * carve. Accepts without testing when the chains overflowed, and when a chain's
     * position falls outside the walked chunk — both keep the filter sound.
     */
    private static boolean probeAccepts(ChainPrefilter chainFilter, AirCarveProbe probe,
            DirtBlobFilter dirt, boolean biomeOcean, long seed, long decorationSeed,
            int minHeight, int[] chunk, RegionSearcher.Worker worker) {
        int chains = chainFilter.collectChains(decorationSeed, OCEAN_INDEX, minHeight);
        if (chainFilter.chainsOverflowed() || chains == 0) {
            return true;
        }
        for (int i = 0; i < chains; i++) {
            long chain = chainFilter.chain(i);
            int px = chunk[0] * 16 + ChainPrefilter.chainX(chain);
            int pz = chunk[1] * 16 + ChainPrefilter.chainZ(chain);
            int pcx = px >> 4, pcz = pz >> 4;
            // The carve probability is the generating chunk's, at its corner. Inside
            // the candidate chunk that is the biome the gate already looked up; a
            // chain reaching into a neighbour needs that neighbour's, which is a warm
            // lookup now that the pyramid is built.
            boolean ocean = pcx == chunk[0] && pcz == chunk[1]
                    ? biomeOcean
                    : BiomeSourceValidator.isOcean(
                            BiomeIds.noiseGen(worker.biomeSource(), pcx * 4, pcz * 4));
            probe.walk(seed, pcx, pcz, ocean);
            boolean all = true;
            for (int c = 0; c < ChainPrefilter.chainColumns(chain) && all; c++) {
                all = probe.isCarved(px, ChainPrefilter.chainBaseY(chain, c), pz);
            }
            if (!all) {
                continue;
            }
            // Same chain has to have its soil as well, not just its air. The target set
            // guarantees *some* chain does; this pins it to this one, which is free.
            int rx = ChainPrefilter.chainX(chain), rz = ChainPrefilter.chainZ(chain);
            int soilY = ChainPrefilter.chainBaseY(chain, 0) - 1;
            if (rx < 0 || rx > 15 || rz < 0 || rz > 15
                    || dirt.couldSupply(decorationSeed, rx, soilY, rz)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws InterruptedException {
        int minHeight = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int threads = args.length > 1 ? Integer.parseInt(args[1])
                : Runtime.getRuntime().availableProcessors();
        int targets = args.length > 2 ? Integer.parseInt(args[2]) : 20_000;
        long firstSeed = args.length > 3 ? Long.parseLong(args[3]) : 1L;
        long seedCount = args.length > 4 ? Long.parseLong(args[4]) : Long.MAX_VALUE;

        if (minHeight < 5) {
            System.out.println("Note: below height 5 the chain filter accepts almost "
                    + "everything and the box scan in `search` is the better tool.");
        }

        System.out.printf("reverse search for height >= %d, %d threads, target set %d, "
                        + "seeds from %d%n", minHeight, threads, targets, firstSeed);

        long[][] buckets = buildTargets(minHeight, targets, threads);

        RegionSearcher.Stats stats = new RegionSearcher.Stats();
        AtomicLong nextSeed = new AtomicLong(firstSeed);
        // Saturating: the default seedCount is "keep going", and firstSeed + it
        // overflows to a negative bound, which stops the search before it starts.
        long lastSeed = firstSeed + seedCount < firstSeed ? Long.MAX_VALUE
                : firstSeed + seedCount;
        AtomicLong candidates = new AtomicLong();
        AtomicLong oceans = new AtomicLong();
        AtomicLong probed = new AtomicLong();
        // Where the time actually goes. Guessing got it wrong once already.
        AtomicLong nsLattice = new AtomicLong();
        AtomicLong nsGate = new AtomicLong();
        AtomicLong nsProbe = new AtomicLong();
        AtomicLong nsChunk = new AtomicLong();
        AtomicLong reachable = new AtomicLong();
        AtomicLong seedsDone = new AtomicLong();
        long start = System.currentTimeMillis();

        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                // radius 0: the box is the single candidate chunk, which is what
                // searchOneChunk needs.
                RegionSearcher.Worker worker =
                        new RegionSearcher.Worker(minHeight, false, 0, stats, 0);
                ChainPrefilter chainFilter = new ChainPrefilter(OCEAN_COUNT);
                AirCarveProbe probe = new AirCarveProbe();
                DirtBlobFilter dirtFilter = new DirtBlobFilter();
                for (long seed = nextSeed.getAndIncrement(); seed < lastSeed;
                        seed = nextSeed.getAndIncrement()) {
                    DecorationLattice lattice = new DecorationLattice(seed);
                    long[] bucket = buckets[(int) (seed & 15L)];
                    worker.prepare(seed);
                    long solved = 0;
                    for (long target : bucket) {
                        long t0 = System.nanoTime();
                        int[] chunk = lattice.solve(target);
                        long t1 = System.nanoTime();
                        nsLattice.addAndGet(t1 - t0);
                        if (chunk == null) {
                            continue;
                        }
                        solved++;
                        // The one biome lookup that decides everything, before
                        // searchRegion does 36 of them and throws them away.
                        //
                        // A candidate is only searchable if its own chunk is a
                        // searchable ocean, and a cache-cold biome lookup costs 116 us
                        // out here — the box scan never notices because its chunks are
                        // neighbours and the layer caches stay warm (1.7 us), but the
                        // reverse search jumps millions of blocks between candidates,
                        // so every lookup is cold. 36 of them was the whole cost of a
                        // rejected candidate.
                        int biome = BiomeIds.noiseGen(worker.biomeSource(),
                                chunk[0] * 4 + 2, chunk[1] * 4 + 2);
                        long t2 = System.nanoTime();
                        nsGate.addAndGet(t2 - t1);
                        if (!RegionSearcher.isSearchableOcean(biome)
                                || !BiomeCaneConfig.hasSugarCane(biome)) {
                            continue;
                        }
                        boolean biomeOcean = BiomeSourceValidator.isOcean(biome);
                        oceans.incrementAndGet();

                        // The position filter. The chain names an (x, z) and a base y
                        // for each of its columns, and every one of those has to be
                        // air — which below sea level means an AIR-step carver reached
                        // it. The carver walks are pure RNG, so that question needs no
                        // terrain at all, and it rejects most candidates for the price
                        // of one walk instead of nine chunk generations.
                        boolean pass = probeAccepts(chainFilter, probe, dirtFilter,
                                biomeOcean, seed, target, minHeight, chunk, worker);
                        long t3 = System.nanoTime();
                        nsProbe.addAndGet(t3 - t2);
                        if (!pass) {
                            continue;
                        }
                        probed.incrementAndGet();
                        worker.searchOneChunk(chunk[0], chunk[1]);
                        nsChunk.addAndGet(System.nanoTime() - t3);
                    }
                    reachable.addAndGet(bucket.length);
                    candidates.addAndGet(solved);
                    seedsDone.incrementAndGet();
                }
            }, "reverse-" + t);
            pool[t].start();
        }

        Thread progress = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(DEFAULT_UPDATE_MS);
                } catch (InterruptedException e) {
                    return;
                }
                long ms = System.currentTimeMillis() - start;
                System.out.printf("[%4.1f min] seeds %d, candidates %d (%.0f/s), "
                                + "ocean %d, past probe %d, chunks searched %d, cane %d, "
                                + "tallest %d, hits %d%n",
                        ms / 60000.0, seedsDone.get(), candidates.get(),
                        candidates.get() * 1000.0 / Math.max(1, ms), oceans.get(),
                        probed.get(),
                        stats.chunksSearched.get(), stats.caneColumns.get(),
                        stats.tallest.get(), stats.hits.get());
                System.out.flush();
            }
        }, "reverse-progress");
        progress.setDaemon(true);
        progress.start();

        for (Thread thread : pool) {
            thread.join();
        }
        long ms = System.currentTimeMillis() - start;
        System.out.printf("%nseeds %d, targets walked %d, candidates in the border %d "
                        + "(%.3f per target walked)%n",
                seedsDone.get(), reachable.get(), candidates.get(),
                (double) candidates.get() / Math.max(1, reachable.get()));
        // Wall time x threads, so this is comparable with the per-phase figures.
        double usPerCandidate = ms * 1000.0 * threads / Math.max(1, candidates.get());
        System.out.printf("per candidate: lattice %.0f us, biome gate %.0f us, "
                        + "air probe %.0f us, chunk %.0f us"
                        + " -> %.0f us accounted of %.0f thread-us actual%n",
                nsLattice.get() / 1e3 / candidates.get(),
                nsGate.get() / 1e3 / candidates.get(),
                nsProbe.get() / 1e3 / candidates.get(),
                nsChunk.get() / 1e3 / candidates.get(),
                (nsLattice.get() + nsGate.get() + nsProbe.get() + nsChunk.get())
                        / 1e3 / candidates.get(),
                usPerCandidate);
        System.out.printf("per searchable-ocean candidate: chunk %.0f us for %.1f generated "
                        + "chunks%n",
                nsChunk.get() / 1e3 / Math.max(1, oceans.get()),
                (double) stats.chunksGenerated.get() / Math.max(1, oceans.get()));
        System.out.printf("of those %d were searchable ocean (%.1f%%), "
                        + "%d survived the air probe (%.2f%% of ocean)%n",
                oceans.get(), 100.0 * oceans.get() / Math.max(1, candidates.get()),
                probed.get(), 100.0 * probed.get() / Math.max(1, oceans.get()));
        System.out.printf("%.0f candidates/s, %.0f searched chunks/s%n",
                candidates.get() * 1000.0 / Math.max(1, ms),
                stats.chunksSearched.get() * 1000.0 / Math.max(1, ms));
        stats.print(ms);
    }

    /**
     * Decoration seeds whose draws alone could chain a run of {@code minHeight},
     * bucketed by their low four bits — the only ones a given world seed can reach.
     *
     * <p>Sampled forward rather than reversed off the LCG. The acceptance rate is the
     * q of FINDINGS 6ac, 3.4% at height 8, so this costs about 150 us / 0.034 = 4.4 ms
     * per member and is a one-off: the set is world-seed-independent.
     */
    private static long[][] buildTargets(int minHeight, int targets, int threads)
            throws InterruptedException {
        long start = System.currentTimeMillis();
        AtomicLong tested = new AtomicLong();
        AtomicLong found = new AtomicLong();
        List<long[]> collected = new ArrayList<>();

        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final long stride = t;
            pool[t] = new Thread(() -> {
                ChainPrefilter filter = new ChainPrefilter(OCEAN_COUNT);
                DirtBlobFilter dirt = new DirtBlobFilter();
                long[] mine = new long[64];
                int n = 0;
                for (long i = stride; found.get() < targets; i += threads) {
                    // splitmix64 so the sampled seeds spread over the 48-bit space.
                    long z = i * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
                    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
                    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
                    z = (z ^ (z >>> 31)) & ((1L << 48) - 1);
                    tested.incrementAndGet();
                    int chains = filter.collectChains(z, OCEAN_INDEX, minHeight);
                    if (chains == 0 && !filter.chainsOverflowed()) {
                        continue;
                    }
                    // The chain's first column needs soil, and 98% of real spots stand
                    // on ore-blob dirt seeded from this same decoration seed. Asking
                    // here is free: it costs target-set build time, which amortises,
                    // and buys 7.2x selectivity at search time.
                    if (!soilPossible(filter, dirt, z, chains)) {
                        continue;
                    }
                    if (n == mine.length) {
                        mine = Arrays.copyOf(mine, n * 2);
                    }
                    mine[n++] = z;
                    found.incrementAndGet();
                }
                synchronized (collected) {
                    collected.add(Arrays.copyOf(mine, n));
                }
            }, "targets-" + t);
            pool[t].start();
        }
        for (Thread thread : pool) {
            thread.join();
        }

        int[] sizes = new int[16];
        for (long[] part : collected) {
            for (long target : part) {
                sizes[(int) (target & 15L)]++;
            }
        }
        long[][] buckets = new long[16][];
        int[] at = new int[16];
        for (int i = 0; i < 16; i++) {
            buckets[i] = new long[sizes[i]];
        }
        for (long[] part : collected) {
            for (long target : part) {
                int b = (int) (target & 15L);
                buckets[b][at[b]++] = target;
            }
        }

        double seconds = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("target set: %d seeds from %d tested (q = %.4e) in %.1f s, "
                        + "%d per low-4-bit bucket%n%n",
                found.get(), tested.get(), (double) found.get() / tested.get(),
                seconds, sizes[0]);
        return buckets;
    }
}
