package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.BiomeCaneConfig;
import dev.drakou111.sugarcane.gen.BiomeIds;
import dev.drakou111.sugarcane.gen.CanyonCarver;
import dev.drakou111.sugarcane.gen.Carver;
import dev.drakou111.sugarcane.gen.CarverConfig;
import dev.drakou111.sugarcane.gen.CaveCarver;
import dev.drakou111.sugarcane.gen.Disk;
import dev.drakou111.sugarcane.gen.OreBlob;
import dev.drakou111.sugarcane.gen.SpawnFinder;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.gen.SurfaceBuilder;
import dev.drakou111.sugarcane.gen.SurfaceConfig;
import dev.drakou111.sugarcane.gen.Terrain;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.validate.BiomeSourceValidator;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.version.MCVersion;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The search for a sugar cane column taller than 4, generating whole regions of
 * chunks at a time.
 *
 * <p>Why regions rather than one chunk at a time: the cane feature reaches ±4
 * blocks outside its chunk, so a chunk can only be judged once its eight
 * neighbours have been surfaced and carved too. Generating a 16x16-chunk region
 * and searching only its interior computes each column once instead of 2.25
 * times, and — unlike the earlier per-chunk window — the neighbours really are
 * carved, so the geometry in the ±4 margin is right.
 *
 * <p>Order within a region follows the game: noise and surface for every chunk,
 * then the carvers (each writes only inside its own chunk, so this is exact),
 * then per chunk in raster order the dirt blobs and the cane. That last part is
 * the one place order matters — a neighbour decorated before us can leave dirt
 * inside our margin — and it matches a world pregenerated in raster order.
 *
 * <p>Restricted to ocean biomes, which is where every stackable spot has ever
 * been observed (FINDINGS 5c) — only oceans register the underwater carvers that
 * cut a tall water face. Frozen oceans are left out because their surface builder
 * is not implemented; see {@link #isSearchableOcean}.
 *
 * <p>Known gaps: no lakes, no structures, no sand/clay/gravel disks, and chunks
 * whose neighbourhood contains a biome with an unimplemented surface builder are
 * skipped outright. Most of those can only lose finds. The disks are the
 * exception - they turn shallow ocean-floor dirt into clay or gravel, which cane
 * cannot stand on - so a hit sitting on the sea floor surface rather than inside
 * the terrain has to be checked against the real game.
 */
public final class RegionSearcher {

    static final int SEA = 63;
    /** {@code MineshaftConfiguration(0.004f, NORMAL)}, from BiomeDefaultFeatures. */
    private static final double MINESHAFT_PROBABILITY = 0.004;
    private static final int MINESHAFT_RADIUS = 3;
    static final int CHUNK = 16;
    /**
     * Largest region side in chunks. A region is generated whole, and only its
     * interior is searched, so bigger wastes less on the border — at 65 KB per
     * chunk of memory. A small search box uses a smaller region instead of this.
     */
    static final int MAX_REGION = 32;

    static SeedReporter reporter = new SeedReporter();

    /** How often the progress line prints, unless {@code --update=<minutes>} says otherwise. */
    private static final long DEFAULT_UPDATE_MS = 60_000L;
    private static final String UPDATE_FLAG = "--update=";

    /**
     * Region side for a search box of the given chunk radius: big enough that the
     * whole box lands in the interior, since border chunks are never searched.
     */
    static int regionFor(int radius) {
        return Math.min(MAX_REGION, Math.max(6, 2 * radius + 3));
    }

    /**
     * Diagnostic only ("diag-all"): drop the ocean restriction so the geometry can
     * be counted on land as well. FINDINGS 5c justified searching oceans only, using
     * a spot definition that turned out stricter than the game's.
     */
    static boolean allBiomes = false;

    /**
     * "--spawn": centre each seed's search box on that world's spawn chunk instead
     * of on 0,0. A fresh world drops the player at the spawn point, not the origin,
     * and over 300 seeds that is a mean 196 blocks apart — so a find inside the box
     * is a find you can walk to.
     *
     * <p>It is not free, and the cost is in two parts. Reproducing
     * {@code setInitialSpawn} means sweeping a 129x129 square of quart cells for a
     * player-spawn biome, which measures ~14 ms against the ~35 ms a seed otherwise
     * costs one thread. And spawn is always in a land biome, so fewer of the
     * surrounding chunks are the ocean this search needs: 30.8 per seed against 45.0
     * at the origin. Together, about half the chunks per second.
     */
    static boolean centreOnSpawn = false;

    /**
     * Set by {@link Inspect} to dump the placement trace for one chunk. The RNG
     * stream only parts company with the terrain at a successful placement, so the
     * trace is what shows where a simulated chunk diverged from the real one.
     */
    static int traceChunkX = Integer.MIN_VALUE;
    static int traceChunkZ = Integer.MIN_VALUE;

    /**
     * Centre the box on this chunk instead of 0,0. Without it a position far from
     * the origin is outside the box at "keep the find near the origin" below, so no
     * chunk is searchable, nothing is generated, and every read comes back as the
     * out-of-window SOLID — which reads as solid terrain rather than as an error.
     * Both {@link Inspect} and {@link ReverseSearcher} work far from the origin,
     * since the world border allows chunk coordinates out to ±1,874,999.
     */
    static int centreOverrideX = Integer.MIN_VALUE;
    static int centreOverrideZ = Integer.MIN_VALUE;

    /**
     * {@link Inspect} only: drop the filters that exist to make a <em>search</em>
     * cheap rather than correct, so any position can be looked at. A search must
     * leave this alone — the mineshaft skip in particular is there because those
     * chunks are simulated wrongly, not because they are unpromising.
     */
    static boolean relaxFilters = false;

    private RegionSearcher() {
    }

    /**
     * The ocean biomes this search accepts: every one except frozen and deep
     * frozen, whose surface builder is not implemented.
     *
     * <p>Warm and lukewarm oceans are included even though they grow coral, which
     * is motion-blocking and would perturb the heightmap the placement samples:
     * their coral features are registered <em>after</em>
     * {@code addDefaultExtraVegetation}, so they run after the cane and cannot
     * affect its RNG. Nothing before the cane in any of these biomes places a
     * motion-blocking block above sea level, so the MOTION_BLOCKING heightmap is
     * exactly 63 and the placement draws are reproducible.
     */
    static boolean isSearchableOcean(int biome) {
        return biome == 0 || biome == 24 || biome == 44 || biome == 45
                || biome == 46 || biome == 47 || biome == 48 || biome == 49;
    }

    public static void main(String[] args) throws InterruptedException {
        long firstSeed = args.length > 0 ? Long.parseLong(args[0]) : 1L;
        long seeds = args.length > 1 ? Long.parseLong(args[1]) : 100L;
        int radius = args.length > 2 ? Integer.parseInt(args[2]) : 16;
        int threads = args.length > 3 ? Integer.parseInt(args[3])
                : Runtime.getRuntime().availableProcessors();
        int report = args.length > 4 ? Integer.parseInt(args[4]) : 5;
        // "probe:<trials>": on every chunk that offers a stackable spot, replay the
        // cane feature over that many synthetic decoration seeds. Measures P.
        final int probeTrials = args.length > 5 && args[5].startsWith("probe:")
                ? Integer.parseInt(args[5].substring("probe:".length())) : 0;
        allBiomes = args.length > 5 && args[5].equals("diag-all");
        // Flags rather than positions, so they can sit anywhere after the mode slot.
        long updateMs = DEFAULT_UPDATE_MS;
        for (String arg : args) {
            if (arg.equals("--spawn")) {
                centreOnSpawn = true;
            } else if (arg.startsWith(UPDATE_FLAG)) {
                updateMs = updateMillis(arg.substring(UPDATE_FLAG.length()));
            }
        }
        final boolean diagnose = probeTrials > 0
                || (args.length > 5 && args[5].startsWith("diag"))
                || (args.length > 5 && args[5].equals("spots"));
        // "spots": print the coordinates of the stackable spots themselves, so the
        // geometry can be looked at in a real world.
        final boolean printSpots = args.length > 5 && args[5].equals("spots");

        System.out.printf("seeds %d..%d, chunk radius %d, %d threads, reporting height >= %d%s%s%s%n",
                firstSeed, firstSeed + seeds - 1, radius, threads, report,
                diagnose ? ", counting geometry" : "",
                centreOnSpawn ? ", centred on world spawn" : "",
                updateMs != DEFAULT_UPDATE_MS
                        ? ", progress every " + updateMs / 60_000.0 + " min" : "");

        AtomicLong nextSeed = new AtomicLong(firstSeed);
        long lastSeed = firstSeed + seeds;
        Stats stats = new Stats();
        long start = System.currentTimeMillis();

        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                Worker worker = new Worker(report, diagnose, probeTrials, stats, radius);
                worker.printSpots = printSpots;
                for (long seed = nextSeed.getAndIncrement(); seed < lastSeed;
                     seed = nextSeed.getAndIncrement()) {
                    worker.searchSeed(seed);
                }
            }, "search-" + t);
            pool[t].start();
        }
        Progress progress = new Progress(stats, start, nextSeed, lastSeed, firstSeed, updateMs);
        progress.setDaemon(true);
        progress.start();
        for (Thread thread : pool) {
            thread.join();
        }
        stats.print(System.currentTimeMillis() - start);
    }

    /**
     * The minutes written after {@code --update=}, in milliseconds. Fractions are
     * allowed so a short run still shows progress; anything under a second is a
     * typo rather than a request to spin, so it is rounded up to one.
     */
    private static long updateMillis(String minutes) {
        double m = Double.parseDouble(minutes);
        if (!(m > 0)) {
            throw new IllegalArgumentException(
                    UPDATE_FLAG + " needs a positive number of minutes, got " + minutes);
        }
        return Math.max(1000L, Math.round(m * 60_000.0));
    }

    private static String biomeLabel(int biome) {
        return switch (biome) {
            case 0 -> "ocean (gravel)";
            case 24 -> "deep_ocean (gravel)";
            case 44 -> "warm_ocean (sand)";
            case 45 -> "lukewarm_ocean (sand)";
            case 46 -> "cold_ocean (gravel)";
            case 47 -> "deep_warm (sand)";
            case 48 -> "deep_lukewarm (sand)";
            case 49 -> "deep_cold (gravel)";
            default -> "biome " + biome;
        };
    }

    /** Shared counters. */
    static final class Stats {
        final AtomicLong chunksGenerated = new AtomicLong();
        final AtomicLong chunksSearched = new AtomicLong();
        final AtomicLong caneColumns = new AtomicLong();
        final AtomicLong stackedColumns = new AtomicLong();
        final AtomicInteger tallest = new AtomicInteger();
        final AtomicLong hits = new AtomicLong();
        /** Positions the feature would accept: air, cane soil below, water beside it. */
        final AtomicLong legalSpots = new AtomicLong();
        /**
         * Legal spots that could also carry a second column on top of the first:
         * water beside the block two above the soil as well, which is the whole
         * problem reduced to one test (FINDINGS section 2).
         */
        final AtomicLong stackableSpots = new AtomicLong();
        /**
         * The same question asked the way the game actually asks it. A stack needs
         * {@code needWater} to pass at the soil, and again at the top of the first
         * column — two heights exactly h1 apart, h1 being 2, 3 or 4. They do not have
         * to be joined, and the blocks between do not have to be air, because
         * {@code ColumnPlacer} overwrites upward unconditionally. {@link #stackableSpots}
         * tests only the connected h1=2 case and so undercounts.
         */
        final AtomicLong stackableRelaxed = new AtomicLong();
        /**
         * Spots and chunks per biome. Ocean biomes that share depth and scale make
         * identical terrain and register identical carvers, so any difference here
         * is the surface configuration: CONFIG_GRASS floors deep water in gravel,
         * which cane cannot stand on, while OCEAN_SAND and FULL_SAND floor it in
         * sand, which it can.
         */
        final java.util.concurrent.ConcurrentHashMap<Integer, long[]> perBiome =
                new java.util.concurrent.ConcurrentHashMap<>();

        void countBiome(int biome, int chunks, int spots) {
            long[] row = perBiome.computeIfAbsent(biome, k -> new long[2]);
            synchronized (row) {
                row[0] += chunks;
                row[1] += spots;
            }
        }
        /**
         * Where the stackable spots sit, which decides how much the unimplemented
         * disk features matter: DISK_CLAY and DISK_GRAVEL turn shallow ocean-floor
         * dirt into blocks cane cannot stand on, but they only ever touch the top
         * solid block of a column, so a spot inside the terrain is safe.
         */
        final AtomicLong spotsOnFloorTop = new AtomicLong();
        final AtomicLong spotsInside = new AtomicLong();
        final AtomicLong spotDepthSum = new AtomicLong();
        /**
         * Spots by the tallest run the terrain permits there, which is the exact
         * condition rather than the contiguous-face proxy. Index 0 collects runs of 20+.
         */
        private final AtomicLong[] runs = new AtomicLong[24];
        /** Chunks whose best permitted run reaches the reported height: the true R. */
        final AtomicLong chunksPermitting = new AtomicLong();

        void runHistogram(int run) {
            runs[Math.min(run, runs.length - 1)].incrementAndGet();
        }

        final AtomicLong probedChunks = new AtomicLong();
        final AtomicLong probeTrials = new AtomicLong();
        final AtomicLong probeHits = new AtomicLong();

        Stats() {
            for (int i = 0; i < runs.length; i++) {
                runs[i] = new AtomicLong();
            }
        }

        void tallest(int height) {
            tallest.accumulateAndGet(height, Math::max);
        }

        void print(long ms) {
            System.out.printf("%nchunks generated %d, searched %d%n",
                    chunksGenerated.get(), chunksSearched.get());
            System.out.printf("cane columns %d, of which stacked on cane %d, tallest %d, hits %d%n",
                    caneColumns.get(), stackedColumns.get(), tallest.get(), hits.get());
            if (!perBiome.isEmpty()) {
                System.out.printf("%nstackable spots by biome (surface config decides the floor):%n");
                perBiome.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                        .forEach(e -> System.out.printf(
                                "  biome %-3d %-22s chunks %-8d spots %-5d  %.2e/chunk%n",
                                e.getKey(), biomeLabel(e.getKey()),
                                e.getValue()[0], e.getValue()[1],
                                (double) e.getValue()[1] / Math.max(1, e.getValue()[0])));
            }
            if (legalSpots.get() > 0 || stackableSpots.get() > 0) {
                System.out.printf("legal spots %d (%.2e/chunk), stackable %d (%.2e/chunk), "
                                + "stackable relaxed %d (%.2e/chunk)%n",
                        legalSpots.get(), (double) legalSpots.get() / Math.max(1, chunksSearched.get()),
                        stackableSpots.get(),
                        (double) stackableSpots.get() / Math.max(1, chunksSearched.get()),
                        stackableRelaxed.get(),
                        (double) stackableRelaxed.get() / Math.max(1, chunksSearched.get()));
            }
            long spots = spotsOnFloorTop.get() + spotsInside.get();
            if (spots > 0) {
                System.out.printf("stackable spots: %d on the sea floor surface (at risk from the "
                                + "missing disks), %d inside the terrain; mean soil y %.1f%n",
                        spotsOnFloorTop.get(), spotsInside.get(),
                        (double) spotDepthSum.get() / spots);
            }
            long runTotal = 0;
            for (AtomicLong a : runs) {
                runTotal += a.get();
            }
            if (runTotal > 0) {
                System.out.printf("%nspots by the tallest run the terrain permits "
                        + "(exact, not the face proxy):%n");
                long tail = 0;
                for (int h = runs.length - 1; h >= 2; h--) {
                    tail += runs[h].get();
                    if (tail > 0) {
                        System.out.printf("  >= %2d: %-10d %.3e per searched chunk%n",
                                h, tail, (double) tail / Math.max(1, chunksSearched.get()));
                    }
                }
                System.out.printf("chunks permitting the reported height: %d (R = %.3e)%n",
                        chunksPermitting.get(),
                        (double) chunksPermitting.get() / Math.max(1, chunksSearched.get()));
            }
            if (probeTrials.get() > 0) {
                double r = (double) probedChunks.get() / Math.max(1, chunksSearched.get());
                double p = (double) probeHits.get() / probeTrials.get();
                System.out.printf("probe: %d chunks with a spot x %d seeds -> %d tall columns%n",
                        probedChunks.get(), probeTrials.get() / Math.max(1, probedChunks.get()),
                        probeHits.get());
                System.out.printf("   R (chunks with a spot) %.3e, P (per decoration seed) %.3e%n", r, p);
                System.out.printf("   => %.3e per chunk, about %.3e chunks per find%n",
                        r * p, r * p > 0 ? 1.0 / (r * p) : Double.POSITIVE_INFINITY);
            }
            System.out.printf("%.0f searched chunks/s over %.1f s%n",
                    chunksSearched.get() * 1000.0 / Math.max(1, ms), ms / 1000.0);
        }
    }

    private static final class Progress extends Thread {
        private final Stats stats;
        private final long start;
        private final AtomicLong nextSeed;
        private final long lastSeed;
        private final long firstSeed;
        private final long updateMs;

        Progress(Stats stats, long start, AtomicLong nextSeed, long lastSeed, long firstSeed,
                long updateMs) {
            this.stats = stats;
            this.start = start;
            this.nextSeed = nextSeed;
            this.lastSeed = lastSeed;
            this.firstSeed = firstSeed;
            this.updateMs = updateMs;
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(updateMs);
                } catch (InterruptedException e) {
                    return;
                }
                long ms = System.currentTimeMillis() - start;
                long seedsSearched = (nextSeed.get() - firstSeed);
                long totalSeeds = (lastSeed - firstSeed - 1);

                System.out.printf("[%4.1f min] seeds done ~%d/%d, searched %d chunks (%.0f/s), "
                                + "cane %d, stacked %d, tallest %d, currentSeed %d\n",
                        ms / 60000.0,
                        seedsSearched,
                        totalSeeds,
                        stats.chunksSearched.get(),
                        stats.chunksSearched.get() * 1000.0 / Math.max(1, ms),
                        stats.caneColumns.get(),
                        stats.stackedColumns.get(),
                        stats.tallest.get(),
                        nextSeed.get());
                System.out.flush();
            }
        }
    }

    /** One thread's worth of reusable buffers. */
    static final class Worker {
        private final int report;
        private final boolean diagnose;
        private final int probeTrials;
        /** Region side for this worker, and the radius that bounds a reportable find. */
        private final int region;
        private final int radius;
        boolean printSpots;
        private final ProbabilityProbe probe = new ProbabilityProbe();
        private final Stats stats;
        final ArrayWorld world;
        private final int[] biomeMap;
        private final byte[] column = new byte[ArrayWorld.HEIGHT];
        private final short[] surfaceStart = new short[CHUNK * CHUNK];
        private final boolean[] candidate;
        private final boolean[] supported;
        private final boolean[] examined;
        private final boolean[] searchable;
        private final boolean[] needed;
        private final JavaRandom random = new JavaRandom();
        private final BitSet airMask = new BitSet(65536);
        private final BitSet liquidMask = new BitSet(65536);

        private OverworldBiomeSource biomes;
        private Terrain terrain;
        private long seed;
        private int regionChunkX;
        private int regionChunkZ;

        Worker(int report, boolean diagnose, int probeTrials, Stats stats, int radius) {
            this.report = report;
            this.diagnose = diagnose;
            this.probeTrials = probeTrials;
            this.stats = stats;
            this.radius = radius;
            this.region = regionFor(radius);
            this.world = new ArrayWorld(0, 0, region * CHUNK, region * CHUNK);
            this.biomeMap = new int[region * CHUNK * region * CHUNK];
            this.candidate = new boolean[region * region];
            this.supported = new boolean[region * region];
            this.examined = new boolean[region * region];
            this.searchable = new boolean[region * region];
            this.needed = new boolean[region * region];
        }

        /** Chunk the search box is centred on: 0,0 unless --spawn moves it. */
        private int centreChunkX;
        private int centreChunkZ;

        /**
         * Bases already reported in this region, so one stack produces one line.
         * Anything over 4 is necessarily built by two or more placements — a single
         * one writes at most 4 — and every placement in a stack walks down to the
         * same base, so reporting per placement prints, counts and uploads a find
         * once per placement that went into it.
         *
         * <p>Per region rather than per chunk: when a stack crosses a border, each
         * side reaches the threshold during its own decoration and reports the same
         * base. Allocated on first use, which for most runs is never.
         */
        private java.util.Set<Long> reportedBases;

        /**
         * This seed's spawn chunk, for reporting how far a find is from where the
         * player arrives. Computed on demand and cached for the seed, because
         * {@code spawnChunk} costs ~14 ms against the ~35 ms a whole seed takes —
         * a third of the search if paid every time, and nothing at all if paid only
         * when something is worth printing. {@code --spawn} has already paid it.
         */
        private long spawnPacked;
        private boolean spawnKnown;

        /** Prepares this worker for a seed without searching anything yet. */
        void prepare(long seed) {
            this.seed = seed;
            this.biomes = new OverworldBiomeSource(MCVersion.v1_16_1, seed);
            dev.drakou111.sugarcane.gen.LayerCaches.enlarge(this.biomes);
            this.terrain = new Terrain(biomes);
            spawnKnown = false;
            if (centreOverrideX != Integer.MIN_VALUE) {
                centreChunkX = centreOverrideX;
                centreChunkZ = centreOverrideZ;
            } else if (centreOnSpawn) {
                spawnPacked = SpawnFinder.spawnChunk(biomes, seed);
                spawnKnown = true;
                centreChunkX = SpawnFinder.chunkX(spawnPacked);
                centreChunkZ = SpawnFinder.chunkZ(spawnPacked);
            } else {
                centreChunkX = 0;
                centreChunkZ = 0;
            }
        }

        /** For {@link Inspect}, which reports the biome when nothing generated. */
        OverworldBiomeSource biomeSource() {
            return biomes;
        }

        private long spawnChunk() {
            if (!spawnKnown) {
                spawnPacked = SpawnFinder.spawnChunk(biomes, seed);
                spawnKnown = true;
            }
            return spawnPacked;
        }

        void searchSeed(long seed) {
            prepare(seed);
            // Start one chunk before the box so its edge is not on the region
            // border, which is never searched.
            // A region whose interior starts past the box cannot hold anything
            // searchable, so stop before generating it.
            for (int cx = centreChunkX - radius - 1; cx + 1 <= centreChunkX + radius;
                    cx += region - 2) {
                for (int cz = centreChunkZ - radius - 1; cz + 1 <= centreChunkZ + radius;
                        cz += region - 2) {
                    searchRegion(cx, cz);
                    terrain.clearCaches();
                }
            }
        }

        /**
         * Generates and searches exactly one chunk, wherever in the world it is.
         * {@link ReverseSearcher} arrives at isolated chunks rather than boxes, so
         * there is nothing to amortise a region over: the eight neighbours are
         * generated because the feature reaches ±4 blocks into them and their dirt
         * blobs leak back, and the chunk itself is the only one searched.
         *
         * <p>Requires a worker built with radius 0, which makes the box exactly this
         * chunk and the region the minimum 6 — the candidate then sits at local 1,1,
         * off the region border that is never searched.
         */
        void searchOneChunk(int chunkX, int chunkZ) {
            centreChunkX = chunkX;
            centreChunkZ = chunkZ;
            searchRegion(chunkX - 1, chunkZ - 1);
            terrain.clearCaches();
        }

        private int regionIndex(int localChunkX, int localChunkZ) {
            return localChunkX * region + localChunkZ;
        }

        void searchRegion(int chunkX0, int chunkZ0) {
            regionChunkX = chunkX0;
            regionChunkZ = chunkZ0;

            boolean any = false;
            if (reportedBases != null) {
                reportedBases.clear();
            }
            for (int i = 0; i < region * region; i++) {
                candidate[i] = false;
                supported[i] = false;
                examined[i] = false;
                searchable[i] = false;
                needed[i] = false;
            }
            // Cheap first pass: the biome at the chunk centre decides the feature
            // list, so it alone says whether a chunk is worth searching.
            for (int lx = 0; lx < region; lx++) {
                for (int lz = 0; lz < region; lz++) {
                    int cx = chunkX0 + lx, cz = chunkZ0 + lz;
                    int biome = BiomeIds.noiseGen(biomes, cx * 4 + 2, cz * 4 + 2);
                    if ((allBiomes || isSearchableOcean(biome))
                            && BiomeCaneConfig.hasSugarCane(biome)) {
                        candidate[regionIndex(lx, lz)] = true;
                        any = true;
                    }
                }
            }
            if (!any) {
                return;
            }

            // A candidate is searchable only if it and all eight neighbours are
            // inside the region and have a surface builder we implement.
            for (int lx = 0; lx < region; lx++) {
                for (int lz = 0; lz < region; lz++) {
                    if (!candidate[regionIndex(lx, lz)]) {
                        continue;
                    }
                    if (lx == 0 || lz == 0 || lx == region - 1 || lz == region - 1) {
                        continue;
                    }
                    // Keep the find near the origin: what a search costs is the
                    // number of chunks examined, not where they are, so bounding
                    // the distance is free as long as the seed count makes up for
                    // the smaller box.
                    int cx = regionChunkX + lx, cz = regionChunkZ + lz;
                    if (cx < centreChunkX - radius || cx > centreChunkX + radius
                            || cz < centreChunkZ - radius || cz > centreChunkZ + radius) {
                        continue;
                    }
                    // Mineshafts are not simulated, and their air is written with
                    // setBlock at UNDERGROUND_STRUCTURES - before the ores and the
                    // cane - so it never floods and this project simply cannot see
                    // it. Measured against pre-flood chunks, 81% of all the air
                    // this simulation misses lies within three chunks of a
                    // mineshaft start, for 19% of the chunks. Missing air there
                    // flips cane tries and desynchronises the whole chunk's stream,
                    // so those chunks are skipped rather than searched wrongly.
                    if (!relaxFilters && mineshaftNear(cx, cz)) {
                        continue;
                    }
                    boolean ok = true;
                    for (int dx = -1; dx <= 1 && ok; dx++) {
                        for (int dz = -1; dz <= 1 && ok; dz++) {
                            ok = columnBiomesSupported(lx + dx, lz + dz);
                        }
                    }
                    searchable[regionIndex(lx, lz)] = ok;
                }
            }
            boolean anySearchable = false;
            for (int lx = 1; lx < region - 1; lx++) {
                for (int lz = 1; lz < region - 1; lz++) {
                    if (!searchable[regionIndex(lx, lz)]) {
                        continue;
                    }
                    anySearchable = true;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            needed[regionIndex(lx + dx, lz + dz)] = true;
                        }
                    }
                }
            }
            if (!anySearchable) {
                return;
            }

            world.reset(chunkX0 * CHUNK, chunkZ0 * CHUNK);
            terrain.beginRegion(chunkX0 * CHUNK, chunkZ0 * CHUNK, region * CHUNK);
            int generated = 0;
            for (int lx = 0; lx < region; lx++) {
                for (int lz = 0; lz < region; lz++) {
                    if (needed[regionIndex(lx, lz)]) {
                        buildChunk(chunkX0 + lx, chunkZ0 + lz);
                        generated++;
                    }
                }
            }
            for (int lx = 0; lx < region; lx++) {
                for (int lz = 0; lz < region; lz++) {
                    if (needed[regionIndex(lx, lz)]) {
                        runCarvers(chunkX0 + lx, chunkZ0 + lz);
                    }
                }
            }
            int searched = 0;
            for (int lx = 0; lx < region; lx++) {
                for (int lz = 0; lz < region; lz++) {
                    if (!searchable[regionIndex(lx, lz)]) {
                        continue;
                    }
                    decorate(chunkX0 + lx, chunkZ0 + lz);
                    searched++;
                }
            }
            stats.chunksGenerated.addAndGet(generated);
            stats.chunksSearched.addAndGet(searched);
        }

        /**
         * Fills the per-block biome map for one chunk and reports whether every
         * column has a surface builder this project implements. Memoised: the
         * neighbourhood test asks about the same chunk up to nine times.
         */
        private boolean columnBiomesSupported(int localChunkX, int localChunkZ) {
            int index = regionIndex(localChunkX, localChunkZ);
            if (examined[index]) {
                return supported[index];
            }
            examined[index] = true;
            boolean ok = true;
            int originX = (regionChunkX + localChunkX) * CHUNK;
            int originZ = (regionChunkZ + localChunkZ) * CHUNK;
            for (int x = 0; x < CHUNK; x++) {
                for (int z = 0; z < CHUNK; z++) {
                    // FuzzyOffsetConstantColumnBiomeZoomer: the Voronoi biome at
                    // y=0, which is what WorldGenRegion.getBiome hands the surface
                    // builder. The quart-resolution noise biome is a different
                    // value and would be wrong here.
                    int biome = BiomeIds.voronoi(biomes, originX + x, originZ + z);
                    biomeMap[mapIndex(originX + x, originZ + z)] = biome;
                    if (!SurfaceConfig.supported(biome)) {
                        ok = false;
                    }
                }
            }
            supported[index] = ok;
            return ok;
        }

        private int mapIndex(int x, int z) {
            int lx = x - regionChunkX * CHUNK;
            int lz = z - regionChunkZ * CHUNK;
            return lx * region * CHUNK + lz;
        }

        private int biomeAt(int x, int z) {
            return biomeMap[mapIndex(x, z)];
        }

        private void buildChunk(int chunkX, int chunkZ) {
            int originX = chunkX * CHUNK;
            int originZ = chunkZ * CHUNK;
            for (int x = 0; x < CHUNK; x++) {
                for (int z = 0; z < CHUNK; z++) {
                    int height = terrain.column(originX + x, originZ + z, column);
                    world.setNoiseColumn(originX + x, originZ + z, column, height);
                    surfaceStart[x * CHUNK + z] = (short) (height + 1);
                }
            }
            SurfaceBuilder.buildChunk(world, chunkX, chunkZ, new SurfaceBuilder.Context() {
                @Override
                public int surfaceStart(int x, int z) {
                    return RegionSearcher.Worker.this.surfaceStart[(x - originX) * CHUNK + (z - originZ)];
                }

                @Override
                public double noise(int x, int z, int localX) {
                    return terrain.surfaceNoise(x, z, localX);
                }

                @Override
                public int biome(int x, int z) {
                    return biomeAt(x, z);
                }
            });
        }

        private void runCarvers(int chunkX, int chunkZ) {
            // The carver list belongs to the biome at the GENERATING chunk corner,
            // not to the start chunk's.
            int cornerBiome = BiomeIds.noiseGen(biomes, chunkX * 4, chunkZ * 4);
            boolean ocean = BiomeSourceValidator.isOcean(cornerBiome);

            Carver.Target airTarget = new Carver.Target() {
                @Override
                public boolean canReplace(int x, int y, int z) {
                    return Blocks.isCarverReplaceable(world.getBlock(x, y, z),
                            world.getBlock(x, y + 1, z));
                }

                @Override
                public boolean isGrassLike(int x, int y, int z) {
                    // Mycelium also counts in vanilla, but the reduced palette
                    // folds it into SOLID; mushroom islands are not the target.
                    return world.getBlock(x, y, z) == Blocks.GRASS_BLOCK;
                }

                @Override
                public void convertDirtToTopMaterial(int x, int y, int z) {
                    if (world.getBlock(x, y, z) == Blocks.DIRT) {
                        world.setBlock(x, y, z, SurfaceConfig.config(biomeAt(x, z)).top());
                    }
                }

                @Override
                public boolean isWater(int x, int y, int z) {
                    return world.isWaterFluid(x, y, z);
                }

                @Override
                public boolean isAir(int x, int y, int z) {
                    return world.isAir(x, y, z);
                }

                @Override
                public void setCaveAir(int x, int y, int z) {
                    world.setBlock(x, y, z, Blocks.AIR);
                }

                @Override
                public void setWater(int x, int y, int z, boolean scheduleTick) {
                    world.setBlock(x, y, z, Blocks.WATER);
                }
            };
            // The underwater carvers widen replaceableBlocks to almost everything
            // - air and water included - so they tunnel straight through an
            // existing cave, and they are what leaves a tall water face beside
            // one. Plain ice is not in either set.
            Carver.Target liquidTarget = new Carver.Target() {
                @Override
                public boolean canReplace(int x, int y, int z) {
                    byte b = world.getBlock(x, y, z);
                    return b != Blocks.SUGAR_CANE && b != Blocks.ICE;
                }

                @Override
                public boolean isWater(int x, int y, int z) {
                    return world.isWaterFluid(x, y, z);
                }

                @Override
                public boolean isAir(int x, int y, int z) {
                    return world.isAir(x, y, z);
                }

                @Override
                public void setCaveAir(int x, int y, int z) {
                    world.setBlock(x, y, z, Blocks.AIR);
                }

                @Override
                public void setWater(int x, int y, int z, boolean scheduleTick) {
                    world.setBlock(x, y, z, Blocks.WATER);
                }
            };

            // One carving mask per generation step, shared by both carvers of that
            // step, so whichever reaches a block first owns it. That makes the
            // iteration order matter: vanilla walks start chunks on the outside
            // and the biome's carver list on the inside.
            airMask.clear();
            liquidMask.clear();
            Carver cave = new CaveCarver(airTarget, chunkX, chunkZ, false, SEA, airMask);
            Carver canyon = new CanyonCarver(airTarget, chunkX, chunkZ, false, SEA, airMask);
            Carver underwaterCanyon =
                    new CanyonCarver(liquidTarget, chunkX, chunkZ, true, SEA, liquidMask);
            Carver underwaterCave =
                    new CaveCarver(liquidTarget, chunkX, chunkZ, true, SEA, liquidMask);
            float caveProbability = ocean ? CarverConfig.CAVE_OCEAN : CarverConfig.CAVE_LAND;

            for (int sx = chunkX - CarverConfig.CARVE_RADIUS; sx <= chunkX + CarverConfig.CARVE_RADIUS; sx++) {
                for (int sz = chunkZ - CarverConfig.CARVE_RADIUS; sz <= chunkZ + CarverConfig.CARVE_RADIUS; sz++) {
                    // GenerationStep.Carving.AIR: cave at index 0, canyon at 1.
                    if (CarverConfig.isStartChunk(random, seed, 0, sx, sz, caveProbability)) {
                        cave.carve(random, sx, sz);
                    }
                    if (CarverConfig.isStartChunk(random, seed, 1, sx, sz, CarverConfig.CANYON)) {
                        canyon.carve(random, sx, sz);
                    }
                }
            }
            if (!ocean) {
                return;
            }
            for (int sx = chunkX - CarverConfig.CARVE_RADIUS; sx <= chunkX + CarverConfig.CARVE_RADIUS; sx++) {
                for (int sz = chunkZ - CarverConfig.CARVE_RADIUS; sz <= chunkZ + CarverConfig.CARVE_RADIUS; sz++) {
                    // GenerationStep.Carving.LIQUID: the underwater canyon is
                    // index 0 and the underwater cave index 1, and the salt
                    // restarts from 0 for the step.
                    if (CarverConfig.isStartChunk(random, seed, 0, sx, sz,
                            CarverConfig.UNDERWATER_CANYON)) {
                        underwaterCanyon.carve(random, sx, sz);
                    }
                    if (CarverConfig.isStartChunk(random, seed, 1, sx, sz,
                            CarverConfig.UNDERWATER_CAVE)) {
                        underwaterCave.carve(random, sx, sz);
                    }
                }
            }
        }

        /** UNDERGROUND_ORES then VEGETAL_DECORATION for one chunk, as the game does. */
        private void decorate(int chunkX, int chunkZ) {
            world.setDecoratingChunk(chunkX, chunkZ);
            long decorationSeed = random.setDecorationSeed(seed, chunkX * CHUNK, chunkZ * CHUNK);
            runDirtBlobs(decorationSeed, chunkX, chunkZ);
            runDisks(decorationSeed, chunkX, chunkZ);
            if (diagnose) {
                int bestRun = countGeometry(chunkX, chunkZ);
                stats.countBiome(biomes.getBiomeForNoiseGen(
                        chunkX * 4 + 2, 0, chunkZ * 4 + 2).getId(), 1,
                        bestRun >= report ? 1 : 0);
                if (bestRun >= report) {
                    stats.chunksPermitting.incrementAndGet();
                }
                if (bestRun >= report && probeTrials > 0) {
                    int probeBiome = biomes.getBiomeForNoiseGen(
                            chunkX * 4 + 2, 0, chunkZ * 4 + 2).getId();
                    int probeHits = probe.measure(world, chunkX, chunkZ,
                            BiomeCaneConfig.count(probeBiome), BiomeCaneConfig.index(probeBiome),
                            probeTrials, report, seed * 31 + chunkX * 7L + chunkZ);
                    stats.probedChunks.incrementAndGet();
                    stats.probeTrials.addAndGet(probeTrials);
                    stats.probeHits.addAndGet(probeHits);
                }
            }

            int biome = BiomeIds.noiseGen(biomes, chunkX * 4 + 2, chunkZ * 4 + 2);
            int count = BiomeCaneConfig.count(biome);
            int index = BiomeCaneConfig.index(biome);
            java.util.List<String> trace =
                    chunkX == traceChunkX && chunkZ == traceChunkZ
                            ? new java.util.ArrayList<>() : null;
            for (SugarCaneFeature.Column c : SugarCaneFeature.place(
                    world, decorationSeed, index, count, chunkX, chunkZ, true, trace)) {
                stats.caneColumns.incrementAndGet();
                int height = world.caneRunThrough(c.x(), c.y(), c.z());
                stats.tallest(height);
                if (height > c.height()) {
                    stats.stackedColumns.incrementAndGet();
                }
                if (height >= report) {
                    int base = c.y();
                    while (world.getBlock(c.x(), base - 1, c.z()) == Blocks.SUGAR_CANE) {
                        base--;
                    }
                    if (reportedBases == null) {
                        reportedBases = new java.util.HashSet<>();
                    }
                    if (!reportedBases.add(ArrayWorld.key(c.x(), base, c.z()))) {
                        continue;
                    }
                    int solid = world.caneRunFromOneChunk(c.x(), base, c.z());
                    // Where the player arrives, and how far they then have to travel.
                    // SpawnFinder resolves the spawn chunk, not the block inside it
                    // (PlayerRespawnLogic picks that from real terrain), so this is
                    // the chunk centre and the distance is good to about ±8 blocks.
                    long spawn = spawnChunk();
                    int spawnX = SpawnFinder.chunkX(spawn) * CHUNK + CHUNK / 2;
                    int spawnZ = SpawnFinder.chunkZ(spawn) * CHUNK + CHUNK / 2;
                    long away = Math.round(Math.hypot(c.x() - spawnX, c.z() - spawnZ));
                    if (solid >= report) {
                        stats.hits.incrementAndGet();
                        System.out.printf(
                                "HIT seed %d  x=%d y=%d z=%d  height %d  biome %d  chunk %d,%d"
                                        + "  spawn ~%d,%d (~%d blocks away)%n",
                                seed, c.x(), base, c.z(), solid, biome, chunkX, chunkZ,
                                spawnX, spawnZ, away);
                        if (solid >= 5 && Cli.reportFinds) {
                            reporter.reportToDataBase(seed, c.x(), base, c.z(), biome, chunkX, chunkZ, false, solid, spawnX, spawnZ, away);
                        }
                    } else {
                        System.out.printf(
                                "cross-chunk seed %d  x=%d y=%d z=%d  spawn ~%d,%d (~%d blocks "
                                        + "away)  height %d only with a neighbour's help, %d on "
                                        + "its own - not verifiable%n",
                                seed, c.x(), base, c.z(), spawnX, spawnZ, away, height, solid);
                        if (Cli.reportFinds) {
                            reporter.reportToDataBase(seed, c.x(), base, c.z(), biome, chunkX, chunkZ, true, solid, spawnX, spawnZ, away);
                        }
                    }
                    System.out.flush();
                }
            }
            if (trace != null) {
                System.out.printf("%nplacement trace for chunk %d,%d "
                        + "(decoration seed %d, count %d, index %d):%n",
                        chunkX, chunkZ, decorationSeed, count, index);
                trace.forEach(line -> System.out.println("  " + line));
            }
        }

        /**
         * How often the terrain offers a spot at all, and how often that spot
         * could take a second column. Diagnostic only: it is the R of FINDINGS
         * section 6, and multiplying it by the measured P says how long a search
         * has to run.
         */
        /**
         * The tallest contiguous run this terrain permits from a base at
         * {@code (x, baseY, z)}, maximised over every column composition.
         *
         * <p>This is the exact terrain condition, as opposed to the contiguous
         * air-and-water "face" that {@code printSpots} reports. Two things make it
         * looser than the face:
         * <ul>
         *   <li>{@code ColumnPlacer} writes upward <em>unconditionally</em>, so only
         *       each column's <b>base</b> needs to be air — the blocks above it may be
         *       solid and get overwritten;</li>
         *   <li>{@code needWater} is checked at the block below each base, so water is
         *       needed at {@code base-1}, {@code base+h1-1}, ... and not in between.</li>
         * </ul>
         * So a 3+4 chain needs water beside {@code base+2}, which a face-based test
         * that insists on water beside {@code base+1} never sees. Using the face as a
         * proxy for this is what made the height-7 rate estimate ~5x optimistic.
         */
        private int maxRun(int x, int baseY, int z, int depth) {
            int best = 0;
            for (int h = 2; h <= 4; h++) {
                int next = baseY + h;
                int total = h;
                if (depth < 6 && next < ArrayWorld.HEIGHT - 1
                        && world.isAir(x, next, z)
                        && SugarCaneFeature.hasWaterBeside(world, x, next - 1, z)) {
                    total += maxRun(x, next, z, depth + 1);
                }
                if (total > best) {
                    best = total;
                }
            }
            return best;
        }

        private int countGeometry(int chunkX, int chunkZ) {
            int legal = 0, stackable = 0, relaxed = 0;
            int chunkBestRun = 0;
            for (int x = chunkX * CHUNK; x < chunkX * CHUNK + CHUNK; x++) {
                for (int z = chunkZ * CHUNK; z < chunkZ * CHUNK + CHUNK; z++) {
                    for (int y = 1; y < SEA + 8; y++) {
                        if (!SugarCaneFeature.canPlace(world, x, y, z)) {
                            continue;
                        }
                        legal++;
                        // The exact condition, not a proxy: the tallest run this
                        // terrain permits from here over every column composition.
                        int run = maxRun(x, y, z, 0);
                        stats.runHistogram(run);
                        if (run > chunkBestRun) {
                            chunkBestRun = run;
                        }
                        // The shortest first column is 2 tall, so the water face
                        // has to reach at least soil+2 for anything to stack.
                        // What the game needs: air where the second column starts,
                        // and water beside the block under it. h1 is whatever the
                        // first column's ColumnPlacer draws.
                        for (int h1 = 2; h1 <= 4; h1++) {
                            if (world.isAir(x, y + h1, z)
                                    && SugarCaneFeature.hasWaterBeside(world, x, y + h1 - 1, z)) {
                                relaxed++;
                                break;
                            }
                        }
                        if (world.isAir(x, y + 1, z) && world.isAir(x, y + 2, z)
                                && SugarCaneFeature.hasWaterBeside(world, x, y + 1, z)) {
                            stackable++;
                            if (world.getHeightOceanFloor(x, z) == y) {
                                stats.spotsOnFloorTop.incrementAndGet();
                            } else {
                                stats.spotsInside.incrementAndGet();
                            }
                            stats.spotDepthSum.addAndGet(y - 1);
                            if (printSpots) {
                                int face = 0;
                                while (SugarCaneFeature.hasWaterBeside(world, x, y + face, z)
                                        && world.isAir(x, y + face, z)) {
                                    face++;
                                }
                                System.out.printf("SPOT seed %d  soil %s at %d,%d,%d  "
                                                + "air pocket %d tall  biome %d%n",
                                        seed, blockName(world.getBlock(x, y - 1, z)),
                                        x, y - 1, z, face,
                                        biomes.getBiomeForNoiseGen(
                                                chunkX * 4 + 2, 0, chunkZ * 4 + 2).getId());
                                System.out.flush();
                            }
                        }
                    }
                }
            }
            stats.legalSpots.addAndGet(legal);
            stats.stackableSpots.addAndGet(stackable);
            stats.stackableRelaxed.addAndGet(relaxed);
            // The chunk's best permitted run, so the probe can be gated on the exact
            // condition instead of on `stackable`, which is not a superset of it.
            return chunkBestRun;
        }

        /**
         * Whether any chunk within three starts a mineshaft. Spacing is 1 and
         * separation 0, so every chunk is a candidate and the only gate is
         * {@code setLargeFeatureSeed(seed, cx, cz); nextDouble() < 0.004}.
         */
        private boolean mineshaftNear(int chunkX, int chunkZ) {
            for (int x = chunkX - MINESHAFT_RADIUS; x <= chunkX + MINESHAFT_RADIUS; x++) {
                for (int z = chunkZ - MINESHAFT_RADIUS; z <= chunkZ + MINESHAFT_RADIUS; z++) {
                    random.setLargeFeatureSeed(seed, x, z);
                    if (random.nextDouble() < MINESHAFT_PROBABILITY) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static String blockName(byte b) {
            return switch (b) {
                case Blocks.DIRT -> "dirt";
                case Blocks.GRASS_BLOCK -> "grass_block";
                case Blocks.SAND -> "sand";
                case Blocks.COARSE_DIRT -> "coarse_dirt";
                case Blocks.PODZOL -> "podzol";
                case Blocks.RED_SAND -> "red_sand";
                default -> "block" + b;
            };
        }

        /**
         * The sand, clay and gravel patches, which land on the sea floor right
         * where the cane feature does its tries. Two of the three remove soil, and
         * all three can flip a try's outcome and so shift the rest of the chunk's
         * RNG stream.
         */
        private void runDisks(long decorationSeed, int chunkX, int chunkZ) {
            Disk.OceanFloor floor = world::getHeightOceanFloor;
            Disk.place(world, random, decorationSeed, Disk.INDEX_SAND, Disk.SAND,
                    chunkX, chunkZ, floor);
            Disk.place(world, random, decorationSeed, Disk.INDEX_CLAY, Disk.CLAY,
                    chunkX, chunkZ, floor);
            Disk.place(world, random, decorationSeed, Disk.INDEX_GRAVEL, Disk.GRAVEL,
                    chunkX, chunkZ, floor);
        }

        private void runDirtBlobs(long decorationSeed, int chunkX, int chunkZ) {
            OreBlob blob = new OreBlob(new OreBlob.Target() {
                @Override
                public boolean isNaturalStone(int x, int y, int z) {
                    // NATURAL_STONE is stone, granite, diorite and andesite; the
                    // reduced palette has them all as SOLID, along with a few
                    // blocks that are not (sandstone, terracotta). Those only
                    // occur at the surface, where blobs rarely land.
                    return world.getBlock(x, y, z) == Blocks.SOLID;
                }

                @Override
                public void setDirt(int x, int y, int z) {
                    world.setBlock(x, y, z, Blocks.DIRT);
                }

                @Override
                public int oceanFloorHeight(int x, int z) {
                    return world.getHeightOceanFloor(x, z);
                }
            }, OreBlob.DIRT_SIZE);

            // ORE_DIRT is the first feature of UNDERGROUND_ORES for every
            // overworld biome (addDefaultUndergroundVariety runs before
            // addDefaultOres), and no structure generates in that step, so the
            // feature index is 0.
            random.setFeatureSeed(decorationSeed, 0, 6);
            for (int i = 0; i < OreBlob.DIRT_COUNT; i++) {
                int x = chunkX * CHUNK + random.nextInt(CHUNK);
                int z = chunkZ * CHUNK + random.nextInt(CHUNK);
                int y = random.nextInt(256);
                blob.place(random, x, y, z);
            }
        }
    }
}
