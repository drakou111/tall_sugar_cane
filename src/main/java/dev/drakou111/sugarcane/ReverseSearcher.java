package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.AirCarveProbe;
import dev.drakou111.sugarcane.gen.BiomeCaneConfig;
import dev.drakou111.sugarcane.gen.BiomeIds;
import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.DirtBlobFilter;
import dev.drakou111.sugarcane.gen.GpuChainFilter;
import dev.drakou111.sugarcane.gen.OrbitSampler;
import dev.drakou111.sugarcane.gen.LiquidCarveProbe;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.gen.TargetCache;
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

    private static long updateMs = 60_000L;
    private static final String UPDATE_FLAG = "--update=";
    private static final String SISTERS_FLAG = "--sisters=";
    private static final String WATER_FLAG = "--water-probe";
    /**
     * Whether to require a chain's water to come from a LIQUID-step carver. Off by
     * default: it is the one position test that trades coverage, because a spot on the
     * sea floor is supplied by the noise fill and no carver ever touches it.
     */
    private static boolean waterProbe = false;
    /**
     * How many upper-16 values to sweep per low-48 seed. Default 64; {@code --sisters=1}
     * restores the original one-seed-at-a-time loop.
     *
     * <p>Note what the default changes for a caller: {@code firstSeed} and
     * {@code seedCount} now count <em>low-48</em> seeds, and each one is searched at 64
     * different upper-16 values. The seeds examined are therefore
     * {@code low48 | (u << 48)} for u in 0..63, not a run of consecutive longs.
     *
     * <p>Sisters share their low 48 bits, and the lattice, the decoration seed at the
     * solved chunk and the carver walk all depend on nothing else — verified directly —
     * so with n > 1 the target sweep and the air probe run once and amortise over n
     * sisters. The gate then runs only on what the probe kept, instead of on everything.
     *
     * <p>Measured at n = 64, 8 threads, against the same run at n = 1:
     *
     * <pre>
     *                candidates/s   gate   probe   chunk   total
     *   n = 1              52,639   93 us   26 us   31 us   151 us
     *   n = 64            220,995    3 us    2 us   29 us    34 us     4.2x
     * </pre>
     *
     * <p>4.2x, not the 44x first projected: that estimate counted the gate and the setup
     * and forgot the chunk generation, which is per candidate and does not amortise at
     * all. It is now 85% of what is left, so this is close to the end of what the
     * technique can give.
     *
     * <p>The permissive probe accepts a superset, which costs ~17% more generated chunks
     * per candidate. That is already inside the 4.2x.
     *
     * <p>Clustering is the thing to know about: sisters are strongly correlated on the
     * rare geometry (P(another sister has a stackable spot | one does) measured at 0.56
     * against a 1.1e-3 base rate), so finds arrive in bursts. That does not change the
     * expected number of finds, only their variance.
     */
    private static int sisters = 64;

    /**
     * Minutes between progress lines, as {@code search} spells it. Fractions are allowed,
     * and anything under a second is a typo rather than a request to spin.
     */
    private static long updateMillis(String minutes) {
        double m;
        try {
            m = Double.parseDouble(minutes);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(UPDATE_FLAG
                    + " needs a number of minutes, got \"" + minutes + "\"");
        }
        if (!(m > 0)) {
            throw new IllegalArgumentException(UPDATE_FLAG
                    + " needs a positive number of minutes, got " + minutes);
        }
        return Math.max(1000L, Math.round(m * 60_000.0));
    }

    static final AtomicLong WHY_OVERFLOW = new AtomicLong();
    static final AtomicLong WHY_NOCHAIN = new AtomicLong();
    static final AtomicLong WHY_OUTSIDE = new AtomicLong();
    static final AtomicLong WHY_CARVED = new AtomicLong();
    static final AtomicLong WHY_NOWATER = new AtomicLong();
    static final AtomicLong WHY_NOISE = new AtomicLong();

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
    /**
     * @param permissive when true the walk uses the LAND cave probability everywhere and
     *                   never asks for a biome. {@code CAVE_LAND = 0.1429} against
     *                   {@code CAVE_OCEAN = 0.0667} on the same {@code nextFloat()}, so
     *                   land fires on a strict superset of the ocean start chunks and
     *                   therefore carves a superset — the result accepts everything the
     *                   biome-correct walk would, and no find can be lost to it. That is
     *                   what makes the walk independent of the upper 16 seed bits and so
     *                   shareable across all 65,536 sisters.
     */
    private static boolean probeAccepts(ChainPrefilter chainFilter, AirCarveProbe probe,
            DirtBlobFilter dirt, boolean biomeOcean, long seed, long decorationSeed,
            int minHeight, int[] chunk, RegionSearcher.Worker worker, boolean permissive,
            LiquidCarveProbe liquid, int[] accepted) {
        int chains = chainFilter.collectChains(decorationSeed, OCEAN_INDEX, minHeight);
        if (chainFilter.chainsOverflowed()) {
            WHY_OVERFLOW.incrementAndGet();
            if (accepted != null) {
                accepted[2] = 0;    // nothing to test downstream
            }
            return true;
        }
        if (chains == 0) {
            WHY_NOCHAIN.incrementAndGet();
            if (accepted != null) {
                accepted[2] = 0;
            }
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
            boolean ocean = permissive ? false
                    : pcx == chunk[0] && pcz == chunk[1]
                    ? biomeOcean
                    : BiomeSourceValidator.isOcean(
                            BiomeIds.noiseGen(worker.biomeSource(), pcx * 4, pcz * 4));
            probe.walk(seed, pcx, pcz, ocean);
            boolean outside = pcx != chunk[0] || pcz != chunk[1];
            boolean all = true;
            for (int c = 0; c < ChainPrefilter.chainColumns(chain) && all; c++) {
                all = probe.isCarved(px, ChainPrefilter.chainBaseY(chain, c), pz);
            }
            if (all) {
                (outside ? WHY_OUTSIDE : WHY_CARVED).incrementAndGet();
            }
            if (all && liquid != null) {
                // The water half: needWater is checked under every base, so a chain
                // names water positions as well as air ones. Unlike the air test this
                // one is lossy -- a spot on the sea floor gets its water from the noise
                // fill and no carver -- so it is opt-in until measured.
                liquid.walk(seed, pcx, pcz);
                for (int c = 0; c < ChainPrefilter.chainColumns(chain) && all; c++) {
                    all = liquid.waterBeside(px, ChainPrefilter.chainBaseY(chain, c) - 1, pz);
                }
                if (!all) {
                    WHY_NOWATER.incrementAndGet();
                    continue;
                }
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
                if (accepted != null) {
                    int cols = ChainPrefilter.chainColumns(chain);
                    accepted[0] = px;
                    accepted[1] = pz;
                    accepted[2] = cols;
                    for (int c = 0; c < cols; c++) {
                        accepted[3 + c] = ChainPrefilter.chainBaseY(chain, c);
                    }
                }
                return true;
            }
        }
        return false;
    }

    /** Where to keep the target set, so its build cost is paid once ever. */
    private static final String CACHE_FLAG = "--targets=";
    /** Force the CPU chain filter even where a GPU is present. */
    private static final String CPU_FLAG = "--cpu";
    /**
     * Report threshold, separate from the height the target set was built for.
     *
     * <p>A set built for height 9 holds seeds whose chain is, say, 4+3+2. If the terrain
     * suits the first two columns and not the third, what actually grows is 7 -- which is
     * still worth having and was previously thrown away, because the report threshold was
     * the same number as the target height.
     *
     * <p>It changes the position filter too, not just the printout: the air probe demands
     * that <em>every</em> column base of a chain be carved, so testing the 9-chain would
     * reject a candidate whose 7 was there for the taking. So the search side asks for
     * chains reaching the report height, and the build side keeps asking for the target
     * height.
     */
    private static final String REPORT_FLAG = "--report=";
    /**
     * Overrides for the ranked filter, so a set built under one configuration can be
     * rebuilt later. Needed the first time to recover a find lost to a stale jar: the run
     * that produced it used the pre-ranking defaults, and without a way to ask for those
     * again its target set was unreproducible.
     */
    private static final String MAX_SHIFT_FLAG = "--max-shift=";
    private static final String MAX_COLUMNS_FLAG = "--max-columns=";
    private static final String MAX_SLACK_FLAG = "--max-slack=";
    private static final String SHIFT_LEVELS_FLAG = "--shift-levels=";
    private static final String SAMPLE_FROM_FLAG = "--sample-from=";
    private static boolean forceCpu = false;
    private static int reportHeight = 0;
    private static java.nio.file.Path cacheOverride = null;
    private static int maxBaseShiftOverride = -1;
    private static int maxColumnsOverride = -1;
    /**
     * Foreign placements allowed between a chain's own columns. 0 -- the default -- is
     * the contiguous window: the chain's columns must be consecutive successful
     * placements, with nothing else in the chunk landing between them.
     *
     * <p>Measured at a net 2.20x (FINDINGS 6ao): it keeps 40% of the target set and 87.9%
     * of real finds. It is a coverage trade rather than a free one, so it is a flag --
     * {@code --max-slack=99} restores the plain ascending rule. The confirmed 5-tall is
     * in the 12% it drops.
     */
    private static int maxSlack = 0;
    private static int shiftLevelsOverride = -1;
    /**
     * Where decoration-seed sampling starts, as a sample index. Advance it between runs on
     * different machines and they cover disjoint ground instead of duplicating each other;
     * a resumed cache still wins, since its own cursor is the one thing that knows what has
     * actually been tested.
     */
    private static long sampleFromOverride = -1L;

    /**
     * Builds or extends a target set and stops, without searching anything.
     *
     * <p>Worth its own command because the set is the expensive, reusable half and the
     * search is the cheap, disposable half. The set depends only on the height, the
     * depth band and the soil filter -- never on the world seed -- so it can be built
     * once on whatever machine has the cores, then handed to every search afterwards.
     * At height 8 a member costs about 5.9 ms and the cost per member rises as the
     * heights go up, because q falls faster than the per-test cost does.
     */
    /**
     * Pulls the flags out and returns what is left, in order.
     *
     * <p>Shared by {@code reverse} and {@code targets} because they had a loop each, and the
     * copy in {@code targets} only knew about {@code --cpu} -- so {@code --update=} there
     * was parsed as the thread count and died on a number format error.
     *
     * <p>{@code --report=} has no effect on a build; it selects what a search prints.
     */
    private static String[] stripFlags(String[] args) {
        java.util.List<String> positional = new java.util.ArrayList<>(args.length);
        for (String arg : args) {
            if (arg.startsWith(CACHE_FLAG)) {
                cacheOverride = java.nio.file.Path.of(arg.substring(CACHE_FLAG.length()));
            } else if (arg.equals(CPU_FLAG)) {
                forceCpu = true;
            } else if (arg.startsWith(REPORT_FLAG)) {
                reportHeight = Integer.parseInt(arg.substring(REPORT_FLAG.length()));
            } else if (arg.equals(WATER_FLAG)) {
                waterProbe = true;
            } else if (arg.startsWith(SISTERS_FLAG)) {
                sisters = Integer.parseInt(arg.substring(SISTERS_FLAG.length()));
                if (sisters < 1 || sisters > 65536) {
                    throw new IllegalArgumentException(
                            "--sisters must be 1..65536, got " + sisters);
                }
            } else if (arg.startsWith(MAX_SHIFT_FLAG)) {
                maxBaseShiftOverride = Integer.parseInt(arg.substring(MAX_SHIFT_FLAG.length()));
            } else if (arg.startsWith(MAX_COLUMNS_FLAG)) {
                maxColumnsOverride = Integer.parseInt(arg.substring(MAX_COLUMNS_FLAG.length()));
            } else if (arg.startsWith(SAMPLE_FROM_FLAG)) {
                sampleFromOverride = Long.parseLong(arg.substring(SAMPLE_FROM_FLAG.length()));
                if (sampleFromOverride < 0) {
                    throw new IllegalArgumentException(
                            "--sample-from must be >= 0, got " + sampleFromOverride);
                }
            } else if (arg.startsWith(SHIFT_LEVELS_FLAG)) {
                shiftLevelsOverride =
                        Integer.parseInt(arg.substring(SHIFT_LEVELS_FLAG.length()));
            } else if (arg.startsWith(MAX_SLACK_FLAG)) {
                maxSlack = Integer.parseInt(arg.substring(MAX_SLACK_FLAG.length()));
                if (maxSlack < 0) {
                    throw new IllegalArgumentException(
                            "--max-slack must be >= 0, got " + maxSlack);
                }
            } else if (arg.startsWith(UPDATE_FLAG)) {
                try {
                    updateMs = updateMillis(arg.substring(UPDATE_FLAG.length()));
                } catch (IllegalArgumentException e) {
                    // A stack trace for a mistyped flag tells the user nothing they can use.
                    System.err.println(e.getMessage());
                    System.exit(2);
                }
            } else if (arg.startsWith("--")) {
                // Silently keeping an unknown flag would make it a positional argument and
                // fail somewhere far away, as a misspelled --update did.
                System.err.println("unknown flag: " + arg);
                System.exit(2);
            } else {
                positional.add(arg);
            }
        }
        return positional.toArray(new String[0]);
    }

    public static void targetsMain(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: targets <minHeight> <count> <file> [threads]");
            System.exit(2);
            return;
        }
        args = stripFlags(args);
        if (args.length < 3) {
            System.err.println("usage: targets <minHeight> <count> <file> [threads] [--cpu] "
                    + "[--update=<minutes>] [--max-shift=<n>] [--max-columns=<n>]");
            System.exit(2);
            return;
        }
        int minHeight = Integer.parseInt(args[0]);
        int count = Integer.parseInt(args[1]);
        java.nio.file.Path file = java.nio.file.Path.of(args[2]);
        int threads = Cli.clampThreads(args.length > 3 ? Integer.parseInt(args[3])
                : Runtime.getRuntime().availableProcessors());

        System.out.printf("building a target set for height >= %d, %d wanted, %d threads%n",
                minHeight, count, threads);
        try {
            buildTargets(minHeight, count, threads, file);
        } catch (java.io.IOException e) {
            System.err.println("target set: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.printf("done. reuse it with: reverse %d <threads> %d <firstSeed> "
                        + "--targets=%s%n", minHeight, count, file);
    }

    public static void main(String[] args) throws Exception {
        int minHeight;
        int threads;
        int targets;
        // Flags first, so the positional arguments below are not thrown off by them.
        args = stripFlags(args);
        java.nio.file.Path cache = cacheOverride;
        int minHeightArg = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        minHeight = minHeightArg;
        if (reportHeight <= 0) {
            reportHeight = minHeight;
        }
        if (reportHeight > minHeight) {
            System.err.printf("--report=%d is above the target height %d, so nothing could "
                    + "ever reach it%n", reportHeight, minHeight);
            System.exit(2);
            return;
        }
        threads = Cli.clampThreads(args.length > 1 ? Integer.parseInt(args[1])
                : Runtime.getRuntime().availableProcessors());
        targets = args.length > 2 ? Integer.parseInt(args[2]) : 20_000;
        long firstSeed = args.length > 3 ? Long.parseLong(args[3]) : 1L;
        // 0 or negative means the same as leaving it off: keep going.
        long seedCount = args.length > 4 ? Long.parseLong(args[4]) : Long.MAX_VALUE;
        if (seedCount <= 0) {
            seedCount = Long.MAX_VALUE;
        }

        if (minHeight < 5) {
            System.out.println("Note: below height 5 the chain filter accepts almost "
                    + "everything and the box scan in `search` is the better tool.");
        }

        System.out.printf("reverse search: targets for height >= %d, reporting height >= %d, "
                        + "%d threads, target set %d, seeds from %d%n",
                minHeight, reportHeight, threads, targets, firstSeed);
        System.out.printf("  filter: base shift <= %d, columns <= %d, depth band y %d..%d, "
                        + "slack <= %d (%s)%n",
                maxBaseShift(), maxColumns(minHeight),
                ChainPrefilter.DEFAULT_BASE_MIN_Y, ChainPrefilter.DEFAULT_BASE_MAX_Y,
                maxSlack, maxSlack == 0 ? "contiguous placements" : "foreign placements allowed");
        if (reportHeight < minHeight) {
            System.out.printf("  a %d-chain whose last column finds no terrain still leaves "
                    + "a shorter run, and those now count%n", minHeight);
        }

        // Resolve everything the reporting path needs before the search starts.
        //
        // Class loading is lazy, and SpawnFinder is only touched when a hit is being
        // printed. Repackaging the jar under a live search therefore leaves the process
        // able to search for hours and unable to report the one thing it finds -- which is
        // exactly what happened after 206 minutes and cost a 7-tall. Touching the classes
        // up front turns that into an error at startup instead of at the worst moment.
        try {
            Class.forName("dev.drakou111.sugarcane.gen.SpawnFinder");
            Class.forName("dev.drakou111.sugarcane.SeedReporter");
            Class.forName("dev.drakou111.sugarcane.world.ArrayWorld");
        } catch (ClassNotFoundException e) {
            System.err.println("the reporting path is not loadable, refusing to start: " + e);
            System.exit(2);
            return;
        }

        long[][] buckets;
        try {
            buckets = buildTargets(minHeight, targets, threads, cache);
        } catch (java.io.IOException e) {
            // A cache built for other parameters cannot hold what this run is looking
            // for, so searching it would be a run that can never succeed. Say so
            // plainly rather than unwinding a stack trace over it.
            System.err.println("target set: " + e.getMessage());
            System.exit(2);
            return;
        }

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
        AtomicLong nsPrepare = new AtomicLong();
        AtomicLong seedsDone = new AtomicLong();
        long start = System.currentTimeMillis();

        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                // radius 0: the box is the single candidate chunk, which is what
                // searchOneChunk needs.
                RegionSearcher.Worker worker =
                        new RegionSearcher.Worker(reportHeight, false, 0, stats, 0);
                // The search side must apply the same ranking as the build, or it tests
                // chains the target set was never selected for.
                ChainPrefilter chainFilter = rankedFilter(minHeight);   // column cap from
                // the target height, which is the looser one; the height asked of it below
                // is the report height.
                AirCarveProbe probe = new AirCarveProbe();
                LiquidCarveProbe liquidProbe = waterProbe ? new LiquidCarveProbe() : null;
                DirtBlobFilter dirtFilter = new DirtBlobFilter();
                // Reused across sisters: the chunks whose carvers already accepted.
                int[] keepX = new int[0];
                int[] keepZ = new int[0];
                long[] keepTarget = new long[0];
                int[][] keepChain = new int[0][];
                int[] accepted = new int[16];
                for (long seed = nextSeed.getAndIncrement(); seed < lastSeed;
                        seed = nextSeed.getAndIncrement()) {
                    if (sisters > 1) {
                        // The lattice, the decoration seed at the solved chunk and the
                        // carver walk are all functions of the low 48 bits alone, so one
                        // pass over the target set serves every upper-16 value. Verified
                        // directly: sisters give the same chunk, the same decoration seed
                        // and the same carve.
                        long low48 = seed & ((1L << 48) - 1);
                        DecorationLattice lattice = new DecorationLattice(low48);
                        long[] bucket = buckets[(int) (low48 & 15L)];
                        if (keepX.length < bucket.length) {
                            keepX = new int[bucket.length];
                            keepZ = new int[bucket.length];
                            keepTarget = new long[bucket.length];
                            keepChain = new int[bucket.length][];
                        }
                        int kept = 0;
                        long solvedHere = 0;
                        for (long target : bucket) {
                            long t0 = System.nanoTime();
                            int[] chunk = lattice.solve(target);
                            long t1 = System.nanoTime();
                            nsLattice.addAndGet(t1 - t0);
                            if (chunk == null) {
                                continue;
                            }
                            solvedHere++;
                            boolean pass = probeAccepts(chainFilter, probe, dirtFilter,
                                    false, low48, target, reportHeight, chunk, null, true,
                                    liquidProbe, accepted);
                            nsProbe.addAndGet(System.nanoTime() - t1);
                            if (pass) {
                                keepX[kept] = chunk[0];
                                keepZ[kept] = chunk[1];
                                keepTarget[kept] = target;
                                keepChain[kept] = accepted.clone();
                                kept++;
                            }
                        }
                        reachable.addAndGet(bucket.length);
                        for (int u = 0; u < sisters; u++) {
                            long full = low48 | ((long) u << 48);
                            long tp = System.nanoTime();
                            worker.prepareBiomesOnly(full);
                            nsPrepare.addAndGet(System.nanoTime() - tp);
                            candidates.addAndGet(solvedHere);
                            for (int i = 0; i < kept; i++) {
                                long tg = System.nanoTime();
                                int biome = BiomeIds.noiseGen(worker.biomeSource(),
                                        keepX[i] * 4 + 2, keepZ[i] * 4 + 2);
                                long tg2 = System.nanoTime();
                                nsGate.addAndGet(tg2 - tg);
                                if (!RegionSearcher.isSearchableOcean(biome)
                                        || !BiomeCaneConfig.hasSugarCane(biome)) {
                                    continue;
                                }
                                oceans.incrementAndGet();
                                int[] ch = keepChain[i];
                                if (ch[2] > 0 && !worker.noiseCouldHoldChain(
                                        ch[0], ch[1], java.util.Arrays.copyOfRange(ch, 3, 3 + ch[2]),
                                        ch[2])) {
                                    WHY_NOISE.incrementAndGet();
                                    continue;
                                }
                                probed.incrementAndGet();
                                worker.searchOneChunk(keepX[i], keepZ[i]);
                                nsChunk.addAndGet(System.nanoTime() - tg2);
                            }
                            seedsDone.incrementAndGet();
                        }
                        continue;
                    }
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
                                biomeOcean, seed, target, reportHeight, chunk, worker, false,
                                liquidProbe, null);
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
                    // Woken often, printing rarely: the gate decides, so a checkpoint
                    // that just printed suppresses the next tick rather than doubling it.
                    Thread.sleep(Math.min(updateMs, PROGRESS_MS));
                } catch (InterruptedException e) {
                    return;
                }
                long ms = System.currentTimeMillis() - start;
                // The seed frontier, not a seed any thread is on: `nextSeed` is the next
                // one to be handed out, so up to `threads` seeds below it are still in
                // flight. It is printed because it is the resume point — the run is
                // usually killed rather than finished, and without it the only way to
                // continue without repeating work is to guess from the elapsed rate.
                // Resuming from it can drop the few unfinished seeds behind it; pass
                // `firstSeed` a little lower to overlap instead.
                System.out.printf("[%4.1f min] seed %d (%d done, %.0f/s), "
                                + "candidates %d (%.0f/s), "
                                + "ocean %d, past probe %d, chunks searched %d, cane %d, "
                                + "tallest %d, hits %d%n",
                        ms / 60000.0, nextSeed.get(), seedsDone.get(),
                        seedsDone.get() * 1000.0 / Math.max(1, ms),
                        candidates.get(),
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
        if (sisters > 1) {
            System.out.printf("  %d sisters per low-48 seed, per-sister setup total %.1f s%n",
                    sisters, nsPrepare.get() / 1e9);
        }
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
        System.out.printf("probe accepts by reason: overflow %d, no chain %d, "
                        + "chain in a neighbour chunk %d, in the candidate chunk %d; "
                        + "rejected for no carver water %d, for noise-not-solid %d%n",
                WHY_OVERFLOW.get(), WHY_NOCHAIN.get(), WHY_OUTSIDE.get(), WHY_CARVED.get(),
                WHY_NOWATER.get(), WHY_NOISE.get());
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
    /**
     * Samples handed out between checkpoints, and therefore how much work a Ctrl-C costs.
     *
     * <p>Was 50M, chosen when the chain filter ran at a few million seeds a second and an
     * epoch was tens of seconds. The greedy path does 383M/s, which made an epoch 0.13 s of
     * GPU work wrapped in host work that does not shrink -- the card ran the pipeline at
     * 104M/s against its own 383M/s, waiting between epochs rather than searching.
     *
     * <p>One constant for both devices, so a GPU-built and a CPU-built set still sample the
     * same boundaries and stay comparable. That is the reason this is fixed rather than
     * adapted to whatever rate a machine happens to measure.
     */
    private static final long EPOCH_SAMPLES = 1_000_000_000L;

    /**
     * The ranked target filter: a chain may assume no earlier placement, and use no more
     * columns than the height needs.
     *
     * <p>Measured off 256 real finds rather than argued for. Shift 0 alone holds 94.1% of
     * finds against 60.7% of the accepted set, and minimum-column chains 89.8% against
     * 77.7%; jointly they keep 93.4% of finds while cutting the set 4.04x at height 7 and
     * 4.41x at height 8. Net 3.77x. Multiplying the two signals separately would have
     * predicted 1.80x, less than half the truth -- they are correlated, because the set
     * is full of chains that are both high-shift and long.
     */
    private static final int MAX_BASE_SHIFT = 0;

    private static int maxBaseShift() {
        return maxBaseShiftOverride >= 0 ? maxBaseShiftOverride : MAX_BASE_SHIFT;
    }

    /** Overridable so the five-level path can be exercised at a height that finds things.
     *  Heights up to 16 need four and get four, which is why every existing set stays valid. */
    private static int shiftLevels(int minHeight) {
        return shiftLevelsOverride > 0 ? shiftLevelsOverride
                : ChainPrefilter.shiftLevelsFor(minHeight);
    }

    /**
     * Every seed that carries this one's chain, itself included.
     *
     * <p>A chain lives in some window of invocations. Sliding the whole stream by an
     * invocation moves the chain within that window without touching its geometry -- same
     * x, z, base y, heights and shifts -- so it stays a chain until it slides off one end.
     * Those seeds are targets the build never had to sample for, which matters because
     * the size of the set is the search's real bottleneck.
     *
     * <p>Going backwards is the productive direction: {@link OrbitSampler#shift} eats
     * invocations off the front, so a chain at invocations [a..b] survives only a of those,
     * while {@link OrbitSampler#unshift} prepends and it survives until b runs off the end.
     * A chain spanning w invocations therefore has {@code count - w} relatives.
     *
     * <p>Each is re-tested rather than assumed. The argument says the geometry survives,
     * but membership also turns on the depth band and the soil filter, and re-testing costs
     * a few microseconds on a seed that only turns up once in millions.
     *
     * @return how many were written into {@code buf}
     */
    private static int family(long z, ChainPrefilter filter, DirtBlobFilter dirt,
            int minHeight, long[] buf) {
        int n = 0;
        buf[n++] = z;
        // Both directions, and without stopping at the first miss: a seed can carry more
        // than one chain, and the one that slides off first need not be the only one.
        for (int dir = 0; dir < 2; dir++) {
            long s = z;
            for (int j = 1; j < OCEAN_COUNT; j++) {
                s = dir == 0
                        ? OrbitSampler.unshift(s, OCEAN_INDEX,
                                SugarCaneFeature.VEGETAL_DECORATION)
                        : OrbitSampler.shift(s, OCEAN_INDEX,
                                SugarCaneFeature.VEGETAL_DECORATION);
                int chains = filter.collectChains(s, OCEAN_INDEX, minHeight);
                if (chains == 0 && !filter.chainsOverflowed()) {
                    continue;
                }
                if (soilPossible(filter, dirt, s, chains)) {
                    buf[n++] = s;
                }
            }
        }
        return n;
    }

    private static int maxColumns(int minHeight) {
        return maxColumnsOverride >= 0 ? maxColumnsOverride
                : ChainPrefilter.minimumColumns(minHeight);
    }

    private static TargetCache.Header header(int minHeight, long tested, long sampledThrough) {
        return new TargetCache.Header(minHeight, OCEAN_COUNT, OCEAN_INDEX,
                ChainPrefilter.DEFAULT_BASE_MIN_Y, ChainPrefilter.DEFAULT_BASE_MAX_Y, true,
                maxBaseShift(), maxColumns(minHeight), maxSlack, tested, sampledThrough);
    }

    private static ChainPrefilter rankedFilter(int minHeight) {
        int levels = shiftLevels(minHeight);
        return new ChainPrefilter(OCEAN_COUNT, ChainPrefilter.DEFAULT_BASE_MIN_Y,
                ChainPrefilter.DEFAULT_BASE_MAX_Y, maxBaseShift(), maxColumns(minHeight),
                levels - 1, levels)
                .maxSlack(maxSlack);
    }

    /**
     * How often the target build reports itself. Follows {@code --update} but capped at a
     * minute: a long search can reasonably be quiet, whereas a build that says nothing for
     * fifteen minutes is the problem the ticker exists to solve.
     */
    private static final long PROGRESS_MS = 5_000L;

    /** 12,345,678 as 12.3M, so a progress line stays the same width as it grows. */
    private static String compact(long n) {
        if (n < 1_000_000L) {
            return String.format("%,d", n);
        }
        if (n < 1_000_000_000L) {
            return String.format("%.1fM", n / 1e6);
        }
        return String.format("%.2fB", n / 1e9);
    }

    private static String human(double seconds) {
        if (seconds < 90) {
            return String.format("%.0fs", seconds);
        }
        if (seconds < 5400) {
            return String.format("%.1fmin", seconds / 60);
        }
        if (seconds < 172800) {
            return String.format("%.1fh", seconds / 3600);
        }
        return String.format("%.1fd", seconds / 86400);
    }

    /**
     * Decoration seeds whose draws alone could chain a run of {@code minHeight} and
     * whose soil its own chunk's blobs could supply, bucketed by their low four bits --
     * the only ones a given world seed can reach.
     *
     * <p>Built in epochs with a checkpoint after each, because at the heights where this
     * is expensive it is expensive in hours: q falls faster than the per-seed cost does,
     * so a height-15 set is a day's work and losing it to a stray Ctrl-C is not
     * acceptable. Each epoch hands out a fixed span of sample indices and runs it to
     * completion before saving, so the file's {@code sampledThrough} always describes
     * exactly what has been tested -- a ragged boundary would either re-test seeds or,
     * worse, skip them silently.
     */
    private static long[][] buildTargets(int minHeight, int targets, int threads,
            java.nio.file.Path cache) throws Exception {
        long start = System.currentTimeMillis();
        TargetCache.Header wanted = header(minHeight, 0L, 0L);

        long[] all = new long[0];
        // Rounded down to a run boundary: the kernel processes whole runs of orbit
        // neighbours, so a cursor landing mid-run would have it start a run early and
        // re-test the seeds before the cursor.
        // Random by default, so two people running the same command do not build the same
        // set. The target set is seed-independent and meant to be pooled, and starting
        // everyone at index 0 made every machine test the same decoration seeds in the same
        // order -- the one arrangement where extra hardware adds nothing.
        //
        // Always printed, because a random start is only acceptable if the run can be
        // repeated: pass the index back as --sample-from to reproduce it exactly.
        boolean randomStart = sampleFromOverride < 0;
        long chosen = randomStart
                ? Math.floorMod(new java.security.SecureRandom().nextLong(), 1L << 48)
                : sampleFromOverride;
        long sampleAt = chosen - Math.floorMod(chosen, (long) OrbitSampler.RUN);
        long totalTested = 0L;
        System.out.printf("target set: sampling decoration seeds from index %d%s%n",
                sampleAt, randomStart
                        ? " (random; pass --sample-from=" + sampleAt + " to repeat this run)"
                        : "");
        if (cache != null) {
            TargetCache.Loaded loaded = TargetCache.load(cache, wanted);
            if (loaded != null) {
                all = loaded.targets();
                totalTested = loaded.header().tested();
                sampleAt = Math.max(sampleAt, loaded.header().sampledThrough());
                System.out.printf("target set: loaded %d from %s (%.1f%% of the %d wanted)%n",
                        all.length, cache, 100.0 * all.length / targets, targets);
                if (all.length >= targets) {
                    return bucket(java.util.Arrays.copyOf(all, targets), targets,
                            totalTested, System.currentTimeMillis() - start);
                }
                System.out.printf("            extending by %d, resuming past sample %d%n",
                        targets - all.length, sampleAt);
            }
        }

        // Detected once, by actually running a small batch: a binary that exists is not
        // the same as a device that works, and every way of not having one -- no CUDA, no
        // driver, wrong toolkit, missing file -- should fall back quietly rather than
        // fail the run.
        GpuChainFilter gpu = forceCpu ? null : GpuChainFilter.detect();
        if (forceCpu) {
            System.out.println("target set: --cpu given, using the CPU chain filter");
        } else if (gpu != null) {
            System.out.printf("target set: using the GPU chain filter at %s "
                    + "(soil filter stays on the CPU)%n", gpu.binary());
        } else {
            System.out.println("target set: no usable GPU filter, using the CPU");
            System.out.println("            reason: " + GpuChainFilter.lastFailure());
        }

        // Progress on whatever interval --update asks for. It used to be
        // min(updateMs, 60 s), which quietly ignored any setting above a minute: asking
        // for --update=5 still printed every minute. The checkpoint lines are a different
        // thing and still land per epoch, because those are saves rather than progress --
        // moving them would change how much work a Ctrl-C costs.
        final java.util.concurrent.atomic.AtomicLong testedBase =
                new java.util.concurrent.atomic.AtomicLong(totalTested);
        final java.util.concurrent.atomic.AtomicLong epochDone =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicInteger foundNow =
                new java.util.concurrent.atomic.AtomicInteger(all.length);
        final java.util.concurrent.atomic.AtomicReference<String> phase =
                new java.util.concurrent.atomic.AtomicReference<>("starting");
        final long buildStart = System.currentTimeMillis();
        // Saving happens every epoch; PRINTING does not. An epoch is 50M samples, which at
        // 10M/s is a line every five seconds -- at a height that finds nothing for hours
        // that is thousands of identical lines. Every progress line shares this gate, so
        // one lands per --update interval whichever produced it, and the save cadence is
        // left alone because that is what a Ctrl-C costs.
        final java.util.concurrent.atomic.AtomicLong lastPrintAt =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        final long testedAtStart = totalTested;
        final int startedWith = all.length;
        // Seconds per target, measured over whole epochs, so it does not drift within one.
        final java.util.concurrent.atomic.AtomicReference<Double> epochRate =
                new java.util.concurrent.atomic.AtomicReference<>(0.0);
        final java.util.concurrent.atomic.AtomicReference<Double> epochQ =
                new java.util.concurrent.atomic.AtomicReference<>(0.0);
        Thread ticker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(updateMs);
                } catch (InterruptedException e) {
                    return;
                }
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastPrintAt.get() < updateMs) {
                    continue;
                }
                lastPrintAt.set(nowMs);
                long tested = testedBase.get() + epochDone.get();
                long thisRun = tested - testedAtStart;
                double secs = (System.currentTimeMillis() - buildStart) / 1000.0;
                int found = foundNow.get();
                double rate = secs > 0 ? thisRun / secs : 0;
                int left = targets - found;
                // From completed epochs only. Between checkpoints `found` is flat while
                // elapsed grows, so a naive seconds-per-target inflates and the estimate
                // visibly crawls upward while nothing is actually going wrong.
                double perTarget = epochRate.get();
                String eta = perTarget > 0 && left > 0
                        ? String.format(", eta %s", human(left * perTarget))
                        : "";
                // q from completed epochs only, for the same reason as the eta: within an
                // epoch `found` is flat while `tested` climbs, so the instantaneous ratio
                // slides downward and looks like the yield collapsing.
                double q = epochQ.get();
                System.out.printf("  [%s] %s decoration seeds tested, %d/%d targets, "
                                + "%.2fM/s%s%s%n",
                        phase.get(), compact(tested), found, targets, rate / 1e6,
                        q > 0 ? String.format(", q = %.3e", q) : "", eta);
                System.out.flush();
            }
        }, "targets-progress");
        ticker.setDaemon(true);
        ticker.start();

        int epoch = 0;
        while (all.length < targets) {
            final long epochFrom = sampleAt;
            final long epochTo = sampleAt + EPOCH_SAMPLES;
            final java.util.concurrent.atomic.AtomicLong next =
                    new java.util.concurrent.atomic.AtomicLong(epochFrom);
            final List<long[]> collected = new ArrayList<>();
            final long chunk = 4096L;

            if (gpu != null) {
                // The GPU takes the chain filter over the whole epoch; the CPU applies
                // the soil filter to the ~1.6% it hands back, in parallel.
                phase.set("gpu chain filter");
                epochDone.set(0);
                long[] chainPassed = gpu.run(minHeight, OCEAN_COUNT, OCEAN_INDEX,
                        ChainPrefilter.DEFAULT_BASE_MIN_Y, ChainPrefilter.DEFAULT_BASE_MAX_Y,
                        maxBaseShift(), maxColumns(minHeight), maxSlack,
                        shiftLevels(minHeight),
                        epochFrom, EPOCH_SAMPLES, epochDone::set);
                epochDone.set(EPOCH_SAMPLES);
                phase.set("cpu soil filter");
                if (System.currentTimeMillis() - lastPrintAt.get() >= updateMs) {
                    lastPrintAt.set(System.currentTimeMillis());
                    System.out.printf("  epoch %d: gpu passed %d of %d seeds (%.2f%%), "
                                    + "sifting soil%n",
                            epoch + 1, chainPassed.length, EPOCH_SAMPLES,
                            100.0 * chainPassed.length / EPOCH_SAMPLES);
                    System.out.flush();
                }
                final long[] toSift = chainPassed;
                final java.util.concurrent.atomic.AtomicInteger cursor =
                        new java.util.concurrent.atomic.AtomicInteger();
                Thread[] sifters = new Thread[threads];
                for (int t = 0; t < threads; t++) {
                    sifters[t] = new Thread(() -> {
                        ChainPrefilter filter = rankedFilter(minHeight);
                        DirtBlobFilter dirt = new DirtBlobFilter();
                        long[] mine = new long[64];
                        long[] kin = new long[2 * OCEAN_COUNT];
                        int n = 0;
                        while (true) {
                            int k = cursor.getAndIncrement();
                            if (k >= toSift.length) {
                                break;
                            }
                            long z = toSift[k];
                            int chains = filter.collectChains(z, OCEAN_INDEX, minHeight);
                            if (chains == 0 && !filter.chainsOverflowed()) {
                                continue;   // cannot happen; the GPU already agreed
                            }
                            if (!soilPossible(filter, dirt, z, chains)) {
                                continue;
                            }
                            int got = family(z, filter, dirt, minHeight, kin);
                            while (n + got > mine.length) {
                                mine = Arrays.copyOf(mine, mine.length * 2);
                            }
                            System.arraycopy(kin, 0, mine, n, got);
                            n += got;
                            foundNow.incrementAndGet();
                        }
                        synchronized (collected) {
                            collected.add(Arrays.copyOf(mine, n));
                        }
                    }, "soil-" + t);
                    sifters[t].start();
                }
                for (Thread thread : sifters) {
                    thread.join();
                }
            } else {
            phase.set("cpu chain + soil");
            Thread[] pool = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                pool[t] = new Thread(() -> {
                    ChainPrefilter filter = rankedFilter(minHeight);
                    DirtBlobFilter dirt = new DirtBlobFilter();
                    long[] mine = new long[64];
                    long[] kin = new long[2 * OCEAN_COUNT];
                    int n = 0;
                    while (true) {
                        long from = next.getAndAdd(chunk);
                        if (from >= epochTo) {
                            break;
                        }
                        long to = Math.min(from + chunk, epochTo);
                        epochDone.addAndGet(to - from);
                        for (long i = from; i < to; i++) {
                            // Runs of orbit neighbours from a splitmix-scattered start.
                            // The kernel keeps a ring of invocations and pays for one new
                            // invocation per seed instead of `count`; this ordering is what
                            // makes that possible. The CPU gains nothing from it and
                            // recomputes every seed from scratch, which is the point -- the
                            // two paths must still produce the same set.
                            long z = OrbitSampler.sampleAt(i, OCEAN_INDEX,
                                    SugarCaneFeature.VEGETAL_DECORATION);
                            int chains = filter.collectChains(z, OCEAN_INDEX, minHeight);
                            if (chains == 0 && !filter.chainsOverflowed()) {
                                continue;
                            }
                            if (!soilPossible(filter, dirt, z, chains)) {
                                continue;
                            }
                            int got = family(z, filter, dirt, minHeight, kin);
                            while (n + got > mine.length) {
                                mine = Arrays.copyOf(mine, mine.length * 2);
                            }
                            System.arraycopy(kin, 0, mine, n, got);
                            n += got;
                            foundNow.incrementAndGet();
                        }
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
            }

            int grown = all.length;
            for (long[] part : collected) {
                grown += part.length;
            }
            long[] merged = new long[grown];
            System.arraycopy(all, 0, merged, 0, all.length);
            int at = all.length;
            for (long[] part : collected) {
                System.arraycopy(part, 0, merged, at, part.length);
                at += part.length;
            }
            // Sorted, so the file is identical whichever device built it. Without this
            // the GPU's atomicAdd order and the CPU's chunk order survive into the
            // array, and truncating an over-full final epoch then keeps a different
            // subset on each -- the filters agree, but the caches would not.
            java.util.Arrays.sort(merged);
            // Families overlap. Sampling walks runs of orbit neighbours, so two accepted
            // seeds in the same run are often relatives of each other and their families
            // coincide -- a duplicate target is a wasted slot in every search that loads
            // the file, so they are dropped here rather than counted as coverage.
            int unique = 0;
            for (int i = 0; i < merged.length; i++) {
                if (i == 0 || merged[i] != merged[i - 1]) {
                    merged[unique++] = merged[i];
                }
            }
            if (unique != merged.length) {
                merged = java.util.Arrays.copyOf(merged, unique);
            }
            all = merged;
            totalTested += EPOCH_SAMPLES;
            testedBase.set(totalTested);
            epochDone.set(0);
            foundNow.set(all.length);
            if (all.length > startedWith) {
                epochRate.set((System.currentTimeMillis() - buildStart) / 1000.0
                        / (all.length - startedWith));
            }
            epochQ.set((double) all.length / totalTested);
            sampleAt = epochTo;
            epoch++;

            if (cache != null) {
                TargetCache.save(cache, header(minHeight, totalTested, sampleAt), all, null);
            }
            double secs = (System.currentTimeMillis() - start) / 1000.0;
            // Saved every epoch regardless; printed on the --update interval, or when the
            // build is finished and the last line is the one worth having.
            boolean finished = all.length >= targets;
            if (finished || System.currentTimeMillis() - lastPrintAt.get() >= updateMs) {
                lastPrintAt.set(System.currentTimeMillis());
                double sinceStart = totalTested - testedAtStart;
                System.out.printf("  checkpoint %d: %d/%d targets, %d decoration seeds "
                                + "tested, %.2fM/s, %.0f s, q = %.3e%s%n",
                        epoch, all.length, targets, totalTested,
                        secs > 0 ? sinceStart / secs / 1e6 : 0.0, secs,
                        (double) all.length / totalTested,
                        cache != null ? " (saved)" : "");
                System.out.flush();
            }
        }

        ticker.interrupt();
        if (all.length > targets) {
            System.out.printf("  trimming %d found to the %d wanted%n", all.length, targets);
            all = java.util.Arrays.copyOf(all, targets);
        }
        if (cache != null) {
            TargetCache.save(cache, header(minHeight, totalTested, sampleAt), all, null);
            System.out.printf("target set: saved %d to %s%n", all.length, cache);
        }
        return bucket(all, targets, totalTested, System.currentTimeMillis() - start);
    }

    /** Splits a target set by low four bits, which is the only slice a world seed uses. */
    private static long[][] bucket(long[] all, int targets, long tested, long ms) {
        int[] sizes = new int[16];
        for (long target : all) {
            sizes[(int) (target & 15L)]++;
        }
        long[][] buckets = new long[16][];
        int[] at = new int[16];
        for (int i = 0; i < 16; i++) {
            buckets[i] = new long[sizes[i]];
        }
        for (long target : all) {
            int b = (int) (target & 15L);
            buckets[b][at[b]++] = target;
        }

        System.out.printf("target set: %d seeds from %d tested (q = %.4e) in %.1f s, "
                        + "%d per low-4-bit bucket%n%n",
                all.length, tested, tested > 0 ? (double) all.length / tested : 0.0,
                ms / 1000.0, sizes[0]);
        return buckets;
    }
}
