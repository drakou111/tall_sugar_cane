package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.world.Blocks;
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

import java.nio.file.Files;
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

    /**
     * How many of a solved seed's 65,536 sisters to try.
     *
     * <p>Only the low 48 bits of a world seed reach {@code setSeed}, so the lift recovers
     * exactly those and the upper 16 are free. They move the biome map and, through depth and
     * scale, the sea floor — which is to say they re-roll the terrain, the one thing that has
     * ever rejected a cross-chunk candidate. The reverse search caps its own sweep at 64
     * because chunk generation dominates there and amortises over nothing; here the thing
     * being amortised is a 1.45 ms lift, so it pays much further up.
     *
     * <p>Measured at height 10 over the same 20M seeds: 64 sisters gave 3,041 candidates in
     * 400 s, 4,096 gave 200,379 in 506 s. That is <b>66x the candidates for 1.27x the
     * wall-clock</b>, because the lift is the whole cost and the sweep rides along on it. The
     * remaining 16x up to the full family is available with {@code --sisters}; it is not the
     * default only because candidates are held in memory until verification.
     */
    private static final int DEFAULT_SISTERS = 4096;

    /**
     * Above this many candidates, stop collecting rather than exhaust the heap.
     *
     * <p>Candidates are verified after the scan, so they accumulate for the whole run, and a
     * high sister count makes that a real number: 4,096 sisters produce them 66x faster than
     * 64 do. Truncating is honest as long as it is reported — the run has then searched less
     * ground than asked for, which is different from having searched it and found nothing.
     */
    private static final int DEFAULT_MAX_CANDIDATES = 4_000_000;

    private CrossFind() {
    }

    static int key(int x, int z, int y, long ds) {
        return ((((x + 4) + (z + 4) * 24) * Y + y) << 4) | (int) (ds & 15L);
    }

    /**
     * Which neighbours to join against: the one named, or all eight.
     *
     * <p>Extracted so it can be tested without a display. The GUI used to send
     * {@code --dx=1 --dz=0} unconditionally, which was harmless when a table served one
     * direction and became a silent 3.5x loss the moment one table served all of them -- the
     * flag went from restating the default to overriding it. A GUI-only regression is exactly
     * the kind this project has shipped before, and the command line never sees it.
     */
    static int[][] directions(boolean named, int dx, int dz) {
        if (named) {
            return new int[][] {{dx, dz}};
        }
        return new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    }

    static boolean inFrame(int x, int z, int y) {
        return x >= -4 && x <= 19 && z >= -4 && z <= 19 && y >= 0 && y < Y;
    }

    /** A chain's full run: half-open top minus the bottom column's base. */
    private static int runOf(long chain) {
        return ChainPrefilter.chainTop(chain) - ChainPrefilter.chainBaseY(chain, 0);
    }

    /**
     * How many earlier placements a chain's first column may assume.
     *
     * <p>3 is unrestricted for four shift levels and is what this command has always used.
     * `ReverseSearcher`'s ranked filter uses 0, because 6ah measured shift 0 holding 94.1% of
     * real finds against 60.7% of the accepted set — a chain assuming three prior successes in
     * its own chunk is assuming something that mostly does not happen.
     *
     * <p>The histogram says the same thing from the other end: 41% of chain A's second-column
     * failures are PLACEABLE_BUT_EMPTY, terrain willing and the placement absent, which is
     * exactly a shift assumption that did not hold.
     *
     * <p><b>Measured, and it is not a tendency but a wall</b> (FINDINGS 6bx). Over 242,455
     * candidates, every one of the 44 first columns that placed came from a shift-0 chain;
     * shift 1 and 2 placed nothing at all across 122,244 candidates where the shift-0 rate
     * predicts 45. So half of every run was being spent on chains that cannot place, and 0 is
     * the default.
     *
     * <p>The evidence is chain A's. Chain B is inferred from the same mechanism rather than
     * measured, because reaching chain B at all is rare -- six candidates in that run. Raise it
     * with {@code --max-shift} if that inference ever needs testing.
     */
    private static int maxBaseShift = 0;

    /** Chains that END here keep the depth band: the bottom column stands on soil. */
    private static ChainPrefilter endingFilter() {
        return new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT,
                ChainPrefilter.DEFAULT_BASE_MIN_Y, ChainPrefilter.DEFAULT_BASE_MAX_Y,
                maxBaseShift, 4);
    }

    /**
     * Chains that BEGIN here do not. The bottom column stands on the neighbour's cane, so the
     * usual base-y band would hide every join above y=35 — and a tall combination joins high by
     * construction. This was the bug in the first cross-chunk measurement.
     */
    private static ChainPrefilter beginningFilter() {
        return new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT, 11, 64, maxBaseShift, 4);
    }

    /**
     * A pair that survived the RNG and the carve probe, awaiting real terrain.
     *
     * <p>Kept rather than printed. The probe answers "did a carver's walk reach this block",
     * which every stack needs and no stack is proved by: the first batch this command printed
     * as finds turned out to be solid stone at the base, with no ravine and no water anywhere
     * near. Only generating the chunks settles it.
     */
    private record Candidate(long ws, int cxa, int cza, int cxb, int czb, int px, int pz,
            int baseY, int joinY, int runA, int runB, int predicted, long chainA, long chainB) {
    }

    /** Why a column of a predicted chain has no cane in the finished world. */
    private enum ColumnFate {
        /** It does — this column worked. */
        GREW,
        /** Something solid or liquid is in the block the base needed. */
        BLOCKED,
        /** Air, but nothing under it to stand on: not soil, and not the cane below it. */
        NO_SUPPORT,
        /** Air on soil or cane, but no water beside the block below. */
        NO_WATER,
        /**
         * Everything the feature asks for was there and it still did not place.
         *
         * <p>This is the interesting one. Terrain cannot be blamed, so what is left is the RNG:
         * the chain assumed a shift level — a number of earlier successful placements — that the
         * real chunk did not produce, so the draws never line up. Or, for chunk B, the decoration
         * order this whole command assumes and has never verified.
         */
        PLACEABLE_BUT_EMPTY
    }

    /**
     * Everything verification learns, kept across epochs.
     *
     * <p>Verification used to run once, after the whole scan. On a six-hour run that means the
     * diagnostic it exists to produce is trapped behind six hours, which is exactly backwards
     * for a run whose purpose is the diagnostic. It now runs per epoch, so the totals have to
     * live somewhere that outlives one call.
     */
    private static final class Tally {
        final AtomicLong ungenerated = new AtomicLong();
        final AtomicLong tallest = new AtomicLong();
        final AtomicLong whyNotAir = new AtomicLong();
        final AtomicLong whyNoSoil = new AtomicLong();
        final AtomicLong whyNoWater = new AtomicLong();
        final AtomicLong whyPlaceable = new AtomicLong();
        final AtomicLong soilWasCarved = new AtomicLong();
        final AtomicLong soilWasWater = new AtomicLong();
        final AtomicLong grewSomething = new AtomicLong();
        final AtomicLong trueCrossChunk = new AtomicLong();
        /** Stacks only visible once chunk B is decorated after chunk A rather than before. */
        final AtomicLong neededReorder = new AtomicLong();
        /**
         * Whether a chain's assumed base shift predicts its first column actually placing.
         * The ranked filter caps shift at 0 on 6ah's measurement -- 94.1% of real finds against
         * 60.7% of the set -- and crossfind has always run 3. Measuring it settles --max-shift
         * without rebuilding a table at each setting to find out.
         */
        final AtomicLong[] shiftSeen = new AtomicLong[4];
        final AtomicLong[] shiftPlaced = new AtomicLong[4];
        /**
         * The same question one level up. A chain's own placements consume shift levels -- its
         * second column necessarily sits at least one above its first -- so what matters is the
         * SLACK: how many foreign placements the chain assumes land between its own columns.
         * Slack 0 is `ReverseSearcher`'s contiguous window (6ao), which crossfind has never
         * applied. Indexed by slack, counted where column 0 placed, so the conditional is
         * "given the chain got started, did the slack it assumed predict it continuing".
         */
        final AtomicLong[] slackSeen = new AtomicLong[8];
        final AtomicLong[] slackContinued = new AtomicLong[8];

        Tally() {
            for (int i = 0; i < 4; i++) {
                shiftSeen[i] = new AtomicLong();
                shiftPlaced[i] = new AtomicLong();
            }
            for (int i = 0; i < 8; i++) {
                slackSeen[i] = new AtomicLong();
                slackContinued[i] = new AtomicLong();
            }
        }
        final AtomicLong generated = new AtomicLong();
        final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> stopped =
                new java.util.concurrent.ConcurrentHashMap<>();
    }

    private static final Tally TALLY = new Tally();

    /** Where confirmed finds are appended, if {@code --out} named one. */
    private static java.nio.file.Path FINDS_FILE;

    /**
     * Appends one confirmed find, flushed immediately.
     *
     * <p>Separate from the table: that is work banked, this is results. A run long enough to
     * want a table is long enough that its console scrollback is not where a find should live.
     */
    private static synchronized void recordFind(Candidate c, int grown) {
        if (FINDS_FILE == null) {
            return;
        }
        String line = String.format(
                "height=%d seed=%d x=%d y=%d z=%d chunkA=%d,%d chunkB=%d,%d runA=%d runB=%d "
                        + "joinY=%d at=%s%n",
                grown, c.ws(), c.px(), c.baseY(), c.pz(), c.cxa(), c.cza(), c.cxb(), c.czb(),
                c.runA(), c.runB(), c.joinY(), java.time.Instant.now());
        try {
            Files.writeString(FINDS_FILE, line, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            System.out.printf("  could not append to %s: %s%n", FINDS_FILE, e);
        }
    }

    /**
     * Appends a stack two chunks built together, whatever height it reached.
     *
     * <p>Separate from {@link #recordFind}: that wants the predicted height, this wants the
     * mechanism. A run of 8 that only one chunk could not have made is worth more to this
     * project right now than a run of 8 that one chunk did, and it is the thing to look at in
     * game — so it gets a seed and a coordinate rather than a tally mark.
     */
    private static synchronized void recordCrossChunk(Candidate c, int grown, int alone,
            int base) {
        // static synchronized already holds CrossFind.class, which is the same monitor the
        // find printer uses, so the two cannot interleave mid-line.
        System.out.printf("%nCROSS-CHUNK %d tall at %d,%d,%d (one chunk alone would give "
                        + "%d), seed %d, chunks %d,%d and %d,%d%n",
                grown, c.px(), base, c.pz(), alone, c.ws(), c.cxa(), c.cza(),
                c.cxb(), c.czb());
        System.out.flush();
        if (FINDS_FILE == null) {
            return;
        }
        String line = String.format(
                "crosschunk height=%d aloneWouldBe=%d seed=%d x=%d y=%d z=%d chunkA=%d,%d "
                        + "chunkB=%d,%d predicted=%d at=%s%n",
                grown, alone, c.ws(), c.px(), base, c.pz(), c.cxa(), c.cza(),
                c.cxb(), c.czb(), c.predicted(), java.time.Instant.now());
        try {
            Files.writeString(FINDS_FILE, line, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            System.out.printf("  could not append to %s: %s%n", FINDS_FILE, e);
        }
    }

    private static final java.util.List<Candidate> CANDIDATES =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Set once the candidate cap is reached, so the run can say it searched less ground. */
    private static volatile boolean candidatesTruncated;

    /**
     * Joins held back so the lift can run on the card instead of one pair at a time.
     *
     * <p>The lift is the whole cost of a run once the scan is on the GPU (6bo), and the kernel
     * is 4.3x the 24-thread CPU rate — but only in bulk: it carries ~400 ms of CUDA start-up per
     * invocation, against 14.2 us per pair. So joins accumulate per thread and go over in one
     * batch, which is why {@code examine} was split at the lift.
     *
     * <p>Only one thread runs the kernel at a time. They would otherwise queue on the card
     * anyway, and serialising here keeps 24 processes from being spawned at once.
     */
    private static final class LiftBatch {
        /**
         * Pairs per kernel call. Each call costs ~375 ms of CUDA start-up whatever its size,
         * and only one thread holds the card at a time, so that cost is serialised: at 200,000
         * a ten-million-join run spends 52 spawns, and the other threads cannot lift while any
         * one of them is happening. Bigger batches trade memory for fewer spawns --
         * {@code cap * 24 bytes * threads}, so a million costs about 576 MB across 24 threads.
         */
        static int cap = 200_000;
        private static final Object GPU_TURN = new Object();

        private final dev.drakou111.sugarcane.gen.GpuLift lift;
        private final long[] d1 = new long[cap];
        private final long[] d2 = new long[cap];
        private final int[] dx = new int[cap];
        private final int[] dz = new int[cap];
        private int n;

        LiftBatch(dev.drakou111.sugarcane.gen.GpuLift lift) {
            this.lift = lift;
        }

        void add(long a, long b, int x, int z) {
            d1[n] = a;
            d2[n] = b;
            dx[n] = x;
            dz[n] = z;
            n++;
        }

        boolean full() {
            return n == d1.length;
        }

        void flush(int minA, int minB, int sisters, ChainPrefilter endA, ChainPrefilter beginB,
                AirCarveProbe probe, LiquidCarveProbe liquid, DirtBlobFilter dirt,
                RegionSearcher.Worker worker, boolean useWater, boolean floorOnly,
                int maxCandidates, AtomicLong solvedSeeds, AtomicLong inBorder,
                AtomicLong carved, AtomicLong oceanPairs, AtomicLong solidNoise) {
            if (n == 0) {
                return;
            }
            dev.drakou111.sugarcane.gen.GpuLift.Solved solved;
            try {
                synchronized (GPU_TURN) {
                    solved = lift.solve(d1, d2, dx, dz, n);
                }
            } catch (Exception e) {
                // Falling back keeps a run alive rather than losing the batch, and the CPU
                // path returns the same seeds -- GpuLiftTest pins that.
                synchronized (CrossFind.class) {
                    System.out.printf("  GPU lift failed (%s); this batch goes to the CPU%n", e);
                }
                for (int i = 0; i < n; i++) {
                    examine(d1[i], d2[i], dx[i], dz[i], minA, minB, sisters, endA, beginB, probe,
                            liquid, dirt, worker, useWater, floorOnly, maxCandidates,
                            solvedSeeds, inBorder, carved, oceanPairs, solidNoise);
                }
                n = 0;
                return;
            }
            solvedSeeds.addAndGet(solved.count());
            for (int i = 0; i < solved.count(); i++) {
                int p = solved.pair()[i];
                examineSolved(solved.worldSeed()[i], d1[p], d2[p], dx[p], dz[p], minA, minB,
                        sisters, endA, beginB, probe, liquid, dirt, worker, useWater, floorOnly,
                        maxCandidates, inBorder, carved, oceanPairs, solidNoise);
            }
            n = 0;
        }
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

    /**
     * The arguments that are not flags, in order.
     *
     * <p>Positionals are read by index, so a flag sitting among them shifts everything after
     * it. The GUI always passes {@code --dx} and {@code --dz}, which put {@code --dx=1} exactly
     * where {@code minA} is read — every GUI run died on it while every command line that
     * omitted the flags worked, which is why the smoke tests never saw it.
     */
    static String[] positional(String[] args) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                out.add(arg);
            }
        }
        return out.toArray(new String[0]);
    }

    public static void main(String[] rawArgs) throws Exception {
        String[] args = positional(rawArgs);
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
        int dx = 1, dz = 0, maxStore = DEFAULT_MAX_STORE, sisterCount = DEFAULT_SISTERS;
        boolean dirNamed = false;
        boolean forceCpu = false;
        String tablePath = null;
        String outPath = null;
        long sampleFrom = -1L;
        boolean noGrow = false;
        int candidateCap = DEFAULT_MAX_CANDIDATES;
        boolean water = false;
        boolean floor = false;
        boolean exactCarveFlag = false;
        boolean useEnum = false;
        int enumLows = 8;
        // The anchor invocation's y, not the chain's base: a chain rooted at 13 has anchors at
        // 13, 17, 21... so this band is wider than the base band the stored side filters on.
        // 16..36 is what the pass-through was measured with -- 84.9% of enumerated seeds
        // survive the ending filter, 94.2% the beginning one -- so widening it is a knob, not
        // a fix, and it costs time linearly in the number of y values.
        int enumMinY = 16;
        int enumMaxY = 36;
        long enumFrom = -1L;
        long enumK = -1L;
        for (String arg : rawArgs) {
            if (arg.startsWith("--sisters=")) {
                sisterCount = Integer.parseInt(arg.substring(10));
            } else if (arg.startsWith("--dx=")) {
                dx = Integer.parseInt(arg.substring(5));
                dirNamed = true;
            } else if (arg.startsWith("--dz=")) {
                dz = Integer.parseInt(arg.substring(5));
                dirNamed = true;
            } else if (arg.startsWith("--max-candidates=")) {
                candidateCap = Integer.parseInt(arg.substring(17));
            } else if (arg.startsWith("--max-store=")) {
                maxStore = Integer.parseInt(arg.substring(12));
            } else if (arg.equals("--water-probe")) {
                water = true;
            } else if (arg.startsWith("--max-shift=")) {
                maxBaseShift = Integer.parseInt(arg.substring(12));
            } else if (arg.startsWith("--lift-batch=")) {
                LiftBatch.cap = Integer.parseInt(arg.substring(13));
            } else if (arg.equals("--no-grow")) {
                noGrow = true;
            } else if (arg.equals("--floor")) {
                floor = true;
            } else if (arg.equals("--cpu")) {
                forceCpu = true;
            } else if (arg.startsWith("--table=")) {
                tablePath = arg.substring(8);
            } else if (arg.startsWith("--out=")) {
                outPath = arg.substring(6);
            } else if (arg.startsWith("--sample-from=")) {
                sampleFrom = Long.parseLong(arg.substring(14));
                if (sampleFrom < 0) {
                    System.err.println("--sample-from must be >= 0, got " + sampleFrom);
                    return;
                }
            } else if (arg.equals("--exact-carve")) {
                exactCarveFlag = true;
            } else if (arg.equals("--enum")) {
                useEnum = true;
            } else if (arg.startsWith("--enum-lows=")) {
                enumLows = Integer.parseInt(arg.substring(12));
                useEnum = true;
            } else if (arg.startsWith("--enum-y=")) {
                String[] band = arg.substring(9).split(":");
                if (band.length != 2) {
                    System.err.println("--enum-y wants min:max, got " + arg.substring(9));
                    return;
                }
                enumMinY = Integer.parseInt(band[0]);
                enumMaxY = Integer.parseInt(band[1]);
                useEnum = true;
            } else if (arg.startsWith("--enum-from=")) {
                enumFrom = Long.parseLong(arg.substring(12));
                useEnum = true;
            } else if (arg.startsWith("--enum-k=")) {
                enumK = Long.parseLong(arg.substring(9));
                useEnum = true;
            }
        }
        if (useEnum && (enumLows <= 0 || (1 << 17) % enumLows != 0)) {
            System.err.println("--enum-lows must be a power of two up to 131072, got " + enumLows);
            return;
        }
        if (useEnum && (enumMinY > enumMaxY || enumMinY < 0 || enumMaxY > 125)) {
            System.err.println("--enum-y must be a band inside 0:125, got "
                    + enumMinY + ":" + enumMaxY);
            return;
        }
        if (dx == 0 && dz == 0) {
            System.err.println("--dx and --dz cannot both be zero: that is one chunk, not two");
            return;
        }
        // All eight neighbours unless one was named. Pass 1 keys each side in its own chunk's
        // frame, so the table it builds is direction-free and every extra direction costs one
        // frame test and a lookup on the streamed side -- there is no second pass 1 to pay
        // for. Eight times the positions, and positions are the currency: the terrain filters
        // are what reject cross-chunk candidates, and they turn on where the chunk IS.
        final int[][] dirs = directions(dirNamed, dx, dz);

        // Store whichever side is rarer, and stream the other. Height is monotone in rarity,
        // so the taller minimum wins without having to measure anything.
        final boolean storeEndings = minA >= minB;
        final int storedMin = storeEndings ? minA : minB;
        final int streamedMin = storeEndings ? minB : minA;
        final int fdx = dx, fdz = dz;
        final boolean useWater = water;
        final boolean useFloor = floor;
        final int sisters = sisterCount;
        final int maxCandidates = candidateCap;
        exactCarve = exactCarveFlag;

        System.out.printf("cross-chunk SEARCH for height %d, %d seeds, %d threads%n",
                target, seeds, threads);
        if (dirs.length == 1) {
            System.out.printf("  chunk A contributes >= %d, chunk B at %+d,%+d contributes "
                    + ">= %d%n", minA, dx, dz, minB);
        } else {
            System.out.printf("  chunk A contributes >= %d, chunk B >= %d, over all %d "
                    + "neighbours (one shared table)%n", minA, minB, dirs.length);
        }
        System.out.printf("  storing the %s side (>= %d, the rarer), streaming the other%n",
                storeEndings ? "ending" : "beginning", storedMin);
        System.out.printf("  %d sister%s per solved seed (the upper 16 bits re-roll the "
                        + "terrain and nothing else)%n",
                sisters, sisters == 1 ? "" : "s");

        FINDS_FILE = outPath == null ? null : java.nio.file.Path.of(outPath);

        // Resume: everything already scanned stays in the table, and this run takes the next
        // slice. Joins go as |table| x streamed, so the table is the thing worth keeping --
        // a second run against a table twice the size is twice as productive per seed.
        final java.nio.file.Path table = tablePath == null ? null
                : java.nio.file.Path.of(tablePath);
        CrossTable.Header wanted = new CrossTable.Header(storeEndings, storedMin,
                SugarCaneFeature.COUNT_DEFAULT, OCEAN_INDEX, java.util.List.of());
        CrossTable.Loaded prior;
        try {
            prior = table == null ? null : CrossTable.load(table, wanted);
        } catch (java.io.IOException e) {
            // A user-facing mistake -- wrong height, wrong file, an older key convention --
            // not a crash. The message already says what differs; a stack trace only buries it.
            System.err.println("cannot use " + table + ": " + e.getMessage());
            System.err.println("give a different --table, or delete that one to start over");
            return;
        }
        // Where this run's slice starts. Named wins; otherwise continue past whatever the
        // table already covers; otherwise random.
        //
        // Random matters for more than variety. The table does not depend on the world seed,
        // so several people can build one together -- but only if they scan different ground.
        // Starting everyone at 0 would have them all rediscover the same chains, and the
        // duplicates would inflate the table without adding a single new join. Always printed,
        // because a random start is only acceptable if the run can be repeated.
        boolean randomStart = false;
        long chosen;
        if (sampleFrom >= 0) {
            chosen = sampleFrom;
        } else if (prior != null) {
            chosen = prior.header().nextFrom();
        } else {
            randomStart = true;
            chosen = Math.floorMod(new java.security.SecureRandom().nextLong(), 1L << 48);
        }
        // Slices start on a run boundary so the CPU walk can be expressed as a run offset.
        final long scanFrom = chosen - Math.floorMod(chosen, (long) OrbitSampler.RUN);
        if (prior != null) {
            System.out.printf("  resuming from %s: %d chains already stored over %d range(s) "
                            + "covering %d samples%n",
                    table, prior.keys().length, prior.header().ranges().size(),
                    prior.header().covered());
        } else if (table != null) {
            System.out.printf("  %s does not exist yet; it will be written at the end of "
                    + "pass 1%n", table);
        }
        System.out.printf("  this run scans samples [%d, %d)%s%n", scanFrom, scanFrom + seeds,
                randomStart ? " (random; pass --sample-from=" + scanFrom + " to repeat it, "
                        + "and let collaborators take their own)" : "");
        if (prior != null) {
            long dup = CrossTable.overlap(
                    java.util.List.of(new CrossTable.Range(scanFrom, seeds)),
                    prior.header().ranges());
            if (dup > 0 && useEnum) {
                System.out.printf("  warning: %d of those samples have already been streamed "
                        + "against this table, so that much of pass 2 is repeated work%n", dup);
            } else if (dup > 0) {
                System.out.printf("  warning: %d of those samples are already in the table, "
                        + "so that much of pass 1 is redone and its chains stored twice%n", dup);
            }
        }

        long start = System.currentTimeMillis();
        long runs = (seeds + OrbitSampler.RUN - 1) / OrbitSampler.RUN;
        final long runOffset = scanFrom / OrbitSampler.RUN;

        // The kernel scans 4.47e7 seeds/s against 3.15e6 for 22 CPU threads, and joins go as
        // the SQUARE of seeds scanned, so this is worth ~200x the positions rather than 14x.
        // It is only a pre-filter: it says which seeds carry a chain and the CPU re-derives
        // the geometry for those, which is cheap because acceptance is 2e-5 on the stored
        // side and 2e-2 on the streamed one.
        dev.drakou111.sugarcane.gen.GpuChainFilter gpu = forceCpu ? null
                : dev.drakou111.sugarcane.gen.GpuChainFilter.detect();
        // Pass 1 only. It is the rarer, more expensive side by construction (storedMin is the
        // taller of the two minimums), which is exactly where constructing states beats
        // scanning for them -- and pass 2's minimum is low enough that the scan is already
        // cheap and abundant there.
        dev.drakou111.sugarcane.gen.GpuStackEnum enumerator = !useEnum ? null
                : dev.drakou111.sugarcane.gen.GpuStackEnum.detect();
        if (useEnum && enumerator == null) {
            System.err.println("--enum needs the state enumerator: "
                    + dev.drakou111.sugarcane.gen.GpuStackEnum.lastFailure());
            return;
        }
        final dev.drakou111.sugarcane.gen.GpuLift gpuLift = forceCpu ? null
                : dev.drakou111.sugarcane.gen.GpuLift.detect();
        if (gpuLift != null) {
            System.out.println("  lifting on the GPU too, in batches of " + LiftBatch.cap);
        }
        if (gpu != null) {
            System.out.printf("  scanning on the GPU (%s); the CPU re-derives geometry for "
                    + "what it keeps%n", gpu.binary());
        } else {
            System.out.printf("  scanning on the CPU%s%n", forceCpu ? " (--cpu)"
                    : ": " + dev.drakou111.sugarcane.gen.GpuChainFilter.lastFailure());
        }

        // ---- pass 1: collect the rare side, keyed by where the join would be ----
        final Hits[] collected = new Hits[threads];
        AtomicLong nextRun = new AtomicLong();
        AtomicLong stored = new AtomicLong();
        // --no-grow: the table is the expensive half only while it is small. Once it holds
        // millions of chains the marginal one is worth far less than the streaming time spent
        // making it -- joins go as |table| x streamed, so with the table fixed, joins go as
        // streamed alone. The last run spent 44% of its wall clock in pass 1 to grow a 1.8M
        // table to 4.9M, which is time that could have been streaming against 4.9M throughout.
        final boolean skipPass1 = noGrow && prior != null;
        if (noGrow && prior == null) {
            System.err.println("--no-grow needs a --table that already has chains in it");
            return;
        }
        if (skipPass1) {
            System.out.printf("  --no-grow: skipping pass 1, streaming against the %d chains "
                    + "already in the table%n", prior.keys().length);
        }
        // Where this run's k slice starts, on the same rules as the sample slice: named wins,
        // then past whatever sweeps of this exact shape the table already holds, then 0. Only
        // sweeps of the same shape count, because lows and the y band decide what a k covers.
        final CrossTable.EnumSweep sweepShape =
                new CrossTable.EnumSweep(0, 0, enumLows, enumMinY, enumMaxY);
        final long kFrom = !useEnum ? 0
                : enumFrom >= 0 ? enumFrom
                : prior != null ? prior.header().nextEnumFrom(sweepShape) : 0;
        final long kCount = !useEnum ? 0
                : Math.max(0, Math.min(enumK >= 0 ? enumK : Long.MAX_VALUE,
                        dev.drakou111.sugarcane.gen.GpuStackEnum.K_LIMIT - kFrom));
        // Not when pass 1 is skipped: --no-grow never enumerates anything, so a table that
        // already covers the whole k range is the normal state of a streaming campaign, not a
        // reason to refuse to run.
        if (useEnum && !skipPass1 && kCount == 0) {
            System.err.printf("nothing left to enumerate: k starts at %d and the limit is %d. "
                    + "Raise --enum-lows for more coverage of the same k, or pass "
                    + "--enum-from=0 to sweep it again.%n",
                    kFrom, dev.drakou111.sugarcane.gen.GpuStackEnum.K_LIMIT);
            return;
        }

        final long[] accepted1;
        if (skipPass1) {
            accepted1 = null;
        } else if (enumerator != null) {
            System.out.printf("  pass 1: enumerating states, k [%d, %d) of %d, lows %d, "
                            + "anchor y %d..%d -- %d states%n",
                    kFrom, kFrom + kCount, dev.drakou111.sugarcane.gen.GpuStackEnum.K_LIMIT,
                    enumLows, enumMinY, enumMaxY,
                    new CrossTable.EnumSweep(kFrom, kCount, enumLows, enumMinY, enumMaxY)
                            .states());
            // edgeOnly, and it costs no coverage here. A column both chunks can reach sits at
            // x in [12,19] relative to A when B is at dx=+1, [-4,3] when dx=-1, and likewise on
            // z -- every direction puts at least one coordinate outside [4,11], and the
            // kernel's test is an OR. It trims 43% of the work and no cross-chunk candidate.
            java.util.List<dev.drakou111.sugarcane.gen.GpuStackEnum.Hit> hits =
                    enumerator.sweep(kFrom, kCount, enumMinY, enumMaxY, storedMin,
                            true, enumLows);
            // One chain can be reached from every height-4 column in it, so the same seed comes
            // back more than once. Measured at about 2%, but collectOne would store its
            // geometry twice over and the table would carry the duplicate into every join.
            java.util.LinkedHashSet<Long> distinct = new java.util.LinkedHashSet<>();
            for (dev.drakou111.sugarcane.gen.GpuStackEnum.Hit h : hits) {
                distinct.add(h.decorationSeed());
            }
            accepted1 = new long[distinct.size()];
            int at = 0;
            for (long ds : distinct) {
                accepted1[at++] = ds;
            }
            System.out.printf("  pass 1: %d confirmed chains, %d distinct seeds%n",
                    hits.size(), accepted1.length);
        } else if (gpu == null) {
            accepted1 = null;
        } else {
            accepted1 = gpuEpoch(gpu, storedMin, storeEndings, scanFrom, seeds);
            System.out.printf("  pass 1: the kernel kept %d of %d seeds (%.4f%%)%n",
                    accepted1.length, seeds, 100.0 * accepted1.length / seeds);
        }
        final AtomicLong cursor1 = new AtomicLong();
        Thread[] pool = new Thread[threads];
        if (skipPass1) {
            // Empty results rather than nulls. Skipping the thread creation left both this
            // array and the pool full of nulls, and every downstream loop over them is a
            // separate NullPointerException waiting to be guarded one at a time -- two of them
            // found the hard way. Filling it once makes the whole of pass 1 a no-op instead of
            // a special case.
            for (int t = 0; t < threads; t++) {
                collected[t] = new Hits();
            }
        }
        for (int t = 0; !skipPass1 && t < threads; t++) {
            final int id = t;
            final int cap = maxStore;
            pool[t] = new Thread(() -> {
                Hits hits = new Hits();
                ChainPrefilter filter = storeEndings ? endingFilter() : beginningFilter();
                if (accepted1 != null) {
                    // GPU path: the kernel already said which seeds carry a chain, so the
                    // CPU only re-derives geometry for those. Acceptance at the stored
                    // side's height is ~2e-5, so this is a rounding error of the work.
                    for (int k = (int) cursor1.getAndIncrement(); k < accepted1.length;
                            k = (int) cursor1.getAndIncrement()) {
                        if (stored.get() >= cap) {
                            break;
                        }
                        collectOne(accepted1[k], filter, storedMin, storeEndings, hits,
                                stored);
                    }
                } else {
                    for (long run = nextRun.getAndIncrement(); run < runs;
                            run = nextRun.getAndIncrement()) {
                        if (stored.get() >= cap) {
                            break;
                        }
                        long ds = OrbitSampler.runStart(runOffset + run);
                        for (int k = 0; k < OrbitSampler.RUN; k++) {
                            collectOne(ds, filter, storedMin, storeEndings, hits, stored);
                            ds = OrbitSampler.shift(ds, OCEAN_INDEX,
                                    SugarCaneFeature.VEGETAL_DECORATION);
                        }
                    }
                }
                collected[id] = hits;
            }, "crossfind-collect-" + t);
            pool[t].start();
        }
        // Only the threads that were actually started. Guarding the creation loop and not this
        // one left the array full of nulls and --no-grow died here on its first real run; the
        // check that "verified" it only read the banner line before a timeout killed it.
        if (!skipPass1) {
            for (Thread th : pool) {
                th.join();
            }
        }

        long fresh = 0;
        for (Hits h : collected) {
            fresh += h.size;
        }
        int priorCount = prior == null ? 0 : prior.keys().length;
        long total = fresh + priorCount;
        boolean truncated = stored.get() >= maxStore;
        System.out.printf("%n  pass 1: %d chains stored in %.1f s%s%n", fresh,
                (System.currentTimeMillis() - start) / 1000.0,
                truncated ? " (table filled up -- raise --max-store, raise the stored side's "
                        + "minimum, or use fewer seeds; the search below is still valid, just "
                        + "over less ground)" : "");
        if (priorCount > 0) {
            System.out.printf("  plus %d from %s, so %d in the join table%n",
                    priorCount, table, total);
        }

        // Written before pass 2, which is the long half: a run killed there still leaves its
        // pass 1 banked, which is the whole point of having the file.
        if (table != null && !skipPass1) {
            int[] flatKeys = new int[(int) total];
            long[] flatSeeds = new long[(int) total];
            int at2 = 0;
            for (int i = 0; i < priorCount; i++) {
                flatKeys[at2] = prior.keys()[i];
                flatSeeds[at2++] = prior.seeds()[i];
            }
            for (Hits h : collected) {
                for (int i = 0; i < h.size; i++) {
                    flatKeys[at2] = h.keys[i];
                    flatSeeds[at2++] = h.seeds[i];
                }
            }
            java.util.List<CrossTable.Range> ranges = new java.util.ArrayList<>();
            java.util.List<CrossTable.EnumSweep> sweeps = new java.util.ArrayList<>();
            if (prior != null) {
                ranges.addAll(prior.header().ranges());
                sweeps.addAll(prior.header().enumSweeps());
            }
            // The sample range records the slice pass 2 streamed, which it does in either
            // mode; under --enum the chains came from the sweep instead, so both are recorded
            // and neither is added to the other. Mixing them would make covered() a number
            // that means nothing, and covered() is what collaborators split ground by.
            ranges.add(new CrossTable.Range(scanFrom, seeds));
            if (useEnum) {
                sweeps.add(new CrossTable.EnumSweep(kFrom, kCount, enumLows,
                        enumMinY, enumMaxY));
            }
            CrossTable.Header written = new CrossTable.Header(storeEndings, storedMin,
                    SugarCaneFeature.COUNT_DEFAULT, OCEAN_INDEX, ranges, sweeps);
            CrossTable.save(table, written, flatKeys, flatSeeds, at2);
            System.out.printf("  wrote %d chains to %s, now %d range(s) covering %d samples%n",
                    at2, table, ranges.size(), written.covered());
            if (!sweeps.isEmpty()) {
                System.out.printf("  plus %d enum sweep(s) over %d states; next unclaimed k "
                                + "for these settings is --enum-from=%d%n",
                        sweeps.size(), written.enumStates(),
                        written.nextEnumFrom(sweepShape));
            }
        }
        if (total == 0) {
            System.out.println("  nothing to join against, so nothing to search");
            return;
        }

        // Counting sort into CSR: at most 24*24*128 distinct joins, so this is a small table
        // however many chains landed in it.
        int[] offset = new int[KEYS + 1];
        for (int i = 0; i < priorCount; i++) {
            offset[prior.keys()[i] + 1]++;
        }
        for (Hits h : collected) {
            for (int i = 0; i < h.size; i++) {
                offset[h.keys[i] + 1]++;
            }
        }
        for (int i = 0; i < KEYS; i++) {
            offset[i + 1] += offset[i];
        }
        long[] joinTable = new long[(int) total];
        int[] cursor = offset.clone();
        for (int i = 0; i < priorCount; i++) {
            joinTable[cursor[prior.keys()[i]]++] = prior.seeds()[i];
        }
        for (Hits h : collected) {
            for (int i = 0; i < h.size; i++) {
                joinTable[cursor[h.keys[i]]++] = h.seeds[i];
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
        final long[] tab = joinTable;
        AtomicLong joins = new AtomicLong();
        AtomicLong solvedSeeds = new AtomicLong();
        AtomicLong inBorder = new AtomicLong();
        AtomicLong oceanPairs = new AtomicLong();
        AtomicLong solidNoise = new AtomicLong();
        AtomicLong carved = new AtomicLong();
        AtomicLong found = new AtomicLong();
        AtomicLong streamed = new AtomicLong();
        nextRun.set(0);
        long pass2Start = System.currentTimeMillis();

        // The streamed side keeps ~2% of seeds, so its accepted list is far too big to hold
        // for a whole run; the kernel is asked for it an epoch at a time. The CPU path is one
        // epoch with a null list, which makes the worker below read from the orbit walk.
        final AtomicLong epochCursor = new AtomicLong();
        // Sized against the kernel's output buffer, not against the seed count, and adaptive
        // because acceptance swings by two orders of magnitude with the streamed side's
        // minimum: a >=12 chain keeps 0.003% of seeds and a >=4 one -- an ordinary cane column
        // -- keeps most of them. A fixed 1e9 is fine for the first and overflows on the second,
        // which is how a light chunk A used to fail outright instead of running.
        long epochSize = 1_000_000_000L;
        AtomicLong kernelKept = new AtomicLong();

        Thread progress = progressThread(pass2Start, streamed, joins, solvedSeeds, inBorder,
                carved, oceanPairs, solidNoise);
        progress.setDaemon(true);
        progress.start();

        for (long epochFrom = 0; epochFrom < (gpu == null ? 1 : seeds); ) {
            final long[] epochSeeds;
            if (gpu == null) {
                epochSeeds = null;
                epochFrom = seeds;          // the CPU path streams the whole range in one go
            } else {
                long[] got = null;
                while (got == null) {
                    try {
                        got = gpuEpoch(gpu, streamedMin, !storeEndings, scanFrom + epochFrom,
                                Math.min(epochSize, seeds - epochFrom));
                    } catch (java.io.IOException ex) {
                        // The kernel says when it dropped seeds rather than returning a short
                        // list, so this is recoverable exactly once it is heard: halve and
                        // retry. Silently keeping the short list would look like a barren epoch.
                        if (epochSize <= 1_000_000L
                                || !String.valueOf(ex.getMessage()).contains("smaller epoch")) {
                            throw ex;
                        }
                        epochSize /= 4;
                        System.out.printf("  epoch too large for the kernel's buffer; "
                                + "retrying at %d seeds%n", epochSize);
                    }
                }
                epochSeeds = got;
                kernelKept.addAndGet(epochSeeds.length);
                epochCursor.set(0);
                epochFrom += Math.min(epochSize, seeds - epochFrom);
            }
            for (int t = 0; t < threads; t++) {
                pool[t] = new Thread(() -> {
                ChainPrefilter filter = storeEndings ? beginningFilter() : endingFilter();
                ChainPrefilter endA = endingFilter();
                ChainPrefilter beginB = beginningFilter();
                LiftBatch batch = gpuLift == null ? null : new LiftBatch(gpuLift);
                AirCarveProbe probe = new AirCarveProbe().ravinesOnly(true);
                LiquidCarveProbe liquid = useWater ? new LiquidCarveProbe() : null;
                DirtBlobFilter dirt = new DirtBlobFilter();
                RegionSearcher.Stats stats = new RegionSearcher.Stats();
                RegionSearcher.Worker worker =
                        new RegionSearcher.Worker(999, false, 0, stats, 0);

                while (true) {
                    long ds;
                    if (epochSeeds != null) {
                        // GPU path: walk the seeds the kernel kept for this epoch. The outer
                        // loop refills it, so a thread that runs dry waits at the barrier
                        // rather than finishing early.
                        int k2 = (int) epochCursor.getAndIncrement();
                        if (k2 >= epochSeeds.length) {
                            break;
                        }
                        ds = epochSeeds[k2];
                    } else {
                        long run = nextRun.getAndIncrement();
                        if (run >= runs) {
                            break;
                        }
                        ds = OrbitSampler.runStart(runOffset + run);
                    }
                    int reps = epochSeeds != null ? 1 : OrbitSampler.RUN;
                    for (int k = 0; k < reps; k++) {
                        int n = filter.collectChains(ds, OCEAN_INDEX, streamedMin);
                        if (!filter.chainsOverflowed()) {
                            for (int i = 0; i < n; i++) {
                                long chain = filter.chain(i);
                                int cx = ChainPrefilter.chainX(chain);
                                int cz = ChainPrefilter.chainZ(chain);
                                int y = storeEndings ? ChainPrefilter.chainBaseY(chain, 0)
                                        : ChainPrefilter.chainTop(chain);
                                // The join is one world block seen from two chunks, so the
                                // partner's coordinate is this one shifted by the offset
                                // between them -- towards chunk A when we hold a beginning,
                                // away from it when we hold an ending. Both must land inside
                                // the +-4..19 frame a cane column can occupy, which is most
                                // of the pruning: a direction that pushes it out is skipped
                                // before a key is even formed.
                                for (int d = 0; d < dirs.length; d++) {
                                    int ddx = dirs[d][0], ddz = dirs[d][1];
                                    int x = storeEndings ? cx + 16 * ddx : cx - 16 * ddx;
                                    int z = storeEndings ? cz + 16 * ddz : cz - 16 * ddz;
                                    if (!inFrame(x, z, y)) {
                                        continue;
                                    }
                                    int key = key(x, z, y, ds);
                                    for (int p = off[key]; p < off[key + 1]; p++) {
                                        long dsA = storeEndings ? tab[p] : ds;
                                        long dsB = storeEndings ? ds : tab[p];
                                        joins.incrementAndGet();
                                        if (batch == null) {
                                            examine(dsA, dsB, ddx, ddz, minA, minB, sisters,
                                                    endA, beginB, probe, liquid, dirt, worker,
                                                    useWater, useFloor, maxCandidates,
                                                    solvedSeeds, inBorder, carved, oceanPairs,
                                                    solidNoise);
                                        } else {
                                            batch.add(dsA, dsB, ddx, ddz);
                                            if (batch.full()) {
                                                batch.flush(minA, minB, sisters, endA, beginB,
                                                        probe, liquid, dirt, worker, useWater,
                                                        useFloor, maxCandidates, solvedSeeds,
                                                        inBorder, carved, oceanPairs,
                                                        solidNoise);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (epochSeeds == null) {
                            ds = OrbitSampler.shift(ds, OCEAN_INDEX,
                                    SugarCaneFeature.VEGETAL_DECORATION);
                        }
                        streamed.incrementAndGet();
                    }
                }
                if (batch != null) {
                    batch.flush(minA, minB, sisters, endA, beginB, probe, liquid, dirt, worker,
                            useWater, useFloor, maxCandidates, solvedSeeds, inBorder, carved,
                            oceanPairs, solidNoise);
                }
                }, "crossfind-solve-" + t);
                pool[t].start();
            }
            for (Thread th : pool) {
                th.join();
            }
            // Per epoch, so a long run answers as it goes instead of holding its whole
            // diagnostic hostage to the last seed. Also keeps the candidate list from growing
            // for the length of the run, which is what --max-candidates was guarding against.
            verify(found, threads, target);
            // A compact running picture after each epoch. The full tally prints once at the
            // end, but a run that takes hours should not keep its diagnostic to itself for all
            // of them -- that was the whole point of verifying per epoch.
            printRunningTally();
        }
        progress.interrupt();
        if (gpu != null) {
            System.out.printf("  pass 2: the kernel kept %d of %d seeds (%.4f%%)%n",
                    kernelKept.get(), seeds, 100.0 * kernelKept.get() / seeds);
        }

        verify(found, threads, target);   // the CPU path runs one epoch, so this is its only call
        printTally(found.get());

        double secs = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("%ndone in %.1f s%n", secs);
        System.out.printf("  chains stored           : %d%n", total);
        System.out.printf("  joins tried             : %d%n", joins.get());
        System.out.printf("  world seeds solved      : %d%n", solvedSeeds.get());
        System.out.printf("  pairs inside the border : %d%n", inBorder.get());
        System.out.printf("  past carve + soil       : %d   (sister-invariant)%n", carved.get());
        System.out.printf("  both chunks cane ocean  : %d   (x%d sisters)%n",
                oceanPairs.get(), sisters);
        System.out.printf("  every base solid noise  : %d%n", solidNoise.get());
        System.out.printf("  CONFIRMED IN TERRAIN    : %d%n", found.get());
        if (joins.get() == 0) {
            System.out.println("  no pair of chains ever met at a block -- more seeds, or a "
                    + "split whose two halves are both reachable");
        }
    }

    /**
     * Generate the terrain for each candidate and see what actually grows.
     *
     * <p>This is the step {@code ReverseSearcher} has always had and this command shipped
     * without: there, the carve probe is a cheap gate and {@code searchOneChunk} decides. Here
     * everything that passed the probe was printed as a find, and the first four checked were
     * solid stone at the base -- no ravine, no water, no cane. The probe was doing its job; it
     * was being asked the wrong question.
     *
     * <p>{@code searchOneChunk} builds the 3x3 around chunk A, which contains chunk B, and
     * decorates every searchable chunk in it. So the world it leaves behind already models
     * whatever cross-chunk stacking the simulator believes in, and the honest measurement is
     * simply the tallest cane run standing at the join column afterwards.
     *
     * <p>Serial, because the region searcher keeps state in statics and because a candidate
     * that survives the probe is rare enough that generating a few thousand regions costs less
     * than the scan that produced them.
     */
    private static void verify(AtomicLong found, int threads, int target)
            throws InterruptedException {
        java.util.List<Candidate> pending;
        synchronized (CANDIDATES) {
            pending = new java.util.ArrayList<>(CANDIDATES);
            // Drained, not snapshotted: this now runs once per epoch, and a candidate verified
            // twice would double every count it lands in.
            CANDIDATES.clear();
        }
        if (pending.isEmpty()) {
            return;
        }
        System.out.printf("%ngenerating terrain for %d candidates on %d threads...%n",
                pending.size(), threads);
        if (candidatesTruncated) {
            System.out.println("  (the candidate cap was hit, so later ones were dropped "
                    + "unexamined -- raise --max-candidates or use fewer seeds; this run "
                    + "searched less ground than it was asked for)");
        }
        long start = System.currentTimeMillis();

        // Parallel because this is now the cost that decides how many candidates the run can
        // afford to test, and how many it tests is the only way to learn the conversion rate.
        // Safe to run wide: the region searcher's centre and window are per-Worker fields, and
        // the statics it does have (relaxFilters, allBiomes, centreOverride) are left alone.
        AtomicLong cursor = new AtomicLong();
        AtomicLong done = new AtomicLong();
        // Per epoch, so the line below can say what THIS batch did. Everything else is
        // cumulative in TALLY, and mixing the two printed "7869 of 3617 were never generated".
        AtomicLong ungenerated = new AtomicLong();
        AtomicLong tallest = TALLY.tallest;
        AtomicLong whyNotAir = TALLY.whyNotAir;
        AtomicLong whyNoSoil = TALLY.whyNoSoil;
        AtomicLong whyNoWater = TALLY.whyNoWater;
        AtomicLong whyPlaceable = TALLY.whyPlaceable;
        AtomicLong soilWasCarved = TALLY.soilWasCarved;
        AtomicLong soilWasWater = TALLY.soilWasWater;
        AtomicLong neededReorder = TALLY.neededReorder;
        AtomicLong grewSomething = TALLY.grewSomething;
        AtomicLong trueCrossChunk = TALLY.trueCrossChunk;
        java.util.concurrent.ConcurrentHashMap<String, AtomicLong> stopped = TALLY.stopped;
        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                RegionSearcher.Stats stats = new RegionSearcher.Stats();
                // Report at the target height rather than at 999, which is a threshold nothing
                // reaches. The region search already walks every cane column in the 3x3 it
                // builds and already compares the full run against caneRunFromOneChunk -- that
                // is exactly the cross-chunk test -- and this command was suppressing all of it
                // and then reading one column by hand. 6bs's two stacks were incidental rather
                // than the predicted chain, so looking only where the prediction pointed is
                // looking in the wrong place by construction.
                RegionSearcher.Worker worker =
                        new RegionSearcher.Worker(target, false, 0, stats, 0);
                for (int i = (int) cursor.getAndIncrement(); i < pending.size();
                        i = (int) cursor.getAndIncrement()) {
                    Candidate c = pending.get(i);
                    int grown;
                    int grownBase = -1;
                    dev.drakou111.sugarcane.world.ArrayWorld orderedWorld = null;
                    try {
                        worker.prepare(c.ws);
                        worker.searchOneChunk(c.cxa, c.cza);
                        // y=200 is air in every chunk that was actually built, so SOLID there
                        // means nothing was generated -- a filter skipped the chunk, or it fell
                        // outside the window. Indistinguishable from "grew no cane" if you only
                        // count cane, and it is the difference between the candidate being
                        // wrong and the check being wrong.
                        if (worker.world.getBlock(c.px, 200, c.pz)
                                == dev.drakou111.sugarcane.world.Blocks.SOLID) {
                            ungenerated.incrementAndGet();
                            continue;
                        }
                        long run = tallestRun(worker.world, c.px, c.pz);
                        grown = (int) run;
                        grownBase = (int) (run >> 32);
                        // The region decorates in raster order, x-major, so chunk B only runs
                        // after chunk A when dx > 0, or dx == 0 and dz > 0. For the other four
                        // neighbours B decorates FIRST, finds no cane to stand on, and the
                        // stack it would have built is invisible -- half of every candidate
                        // this command produces (FINDINGS 6by). A real 18-tall reads as 14
                        // here for exactly that reason: its neighbour is at dx = -1.
                        //
                        // So B is re-run against the finished world, which is the order the
                        // candidate actually needs. Re-running is safe at this column: B's
                        // raster-order pass could not place here, because what it would stand
                        // on did not exist yet.
                        if (c.cxb < c.cxa || (c.cxb == c.cxa && c.czb < c.cza)) {
                            dev.drakou111.sugarcane.world.ArrayWorld ordered =
                                    worker.world.copy();
                            ordered.setDecoratingChunk(c.cxb, c.czb);
                            SugarCaneFeature.place(ordered,
                                    new DecorationLattice(c.ws).decorationSeedOf(c.cxb, c.czb),
                                    OCEAN_INDEX, SugarCaneFeature.COUNT_DEFAULT,
                                    c.cxb, c.czb);
                            long reordered = tallestRun(ordered, c.px, c.pz);
                            if ((int) reordered > grown) {
                                grown = (int) reordered;
                                grownBase = (int) (reordered >> 32);
                                orderedWorld = ordered;
                                neededReorder.incrementAndGet();
                            }
                        }
                    } catch (RuntimeException e) {
                        synchronized (CrossFind.class) {
                            System.out.printf("  seed %d at %d,%d: generation failed (%s)%n",
                                    c.ws, c.px, c.pz, e);
                        }
                        continue;
                    } finally {
                        done.incrementAndGet();
                    }
                    tallest.accumulateAndGet(grown, Math::max);
                    int shift = ChainPrefilter.chainBaseShift(c.chainA());
                    boolean col0Grew = fateOf(worker.world, c.px, c.pz,
                            ChainPrefilter.chainBaseY(c.chainA(), 0)) == ColumnFate.GREW;
                    if (shift >= 0 && shift < 4) {
                        TALLY.shiftSeen[shift].incrementAndGet();
                        if (col0Grew) {
                            TALLY.shiftPlaced[shift].incrementAndGet();
                        }
                    }
                    if (col0Grew) {
                        // Foreign placements the chain assumes between its own columns: its
                        // own contribute one level each, so anything above that is slack.
                        int cols = ChainPrefilter.chainColumns(c.chainA());
                        int slack = ChainPrefilter.chainMaxShift(c.chainA())
                                - ChainPrefilter.chainBaseShift(c.chainA()) - (cols - 1);
                        if (slack >= 0 && slack < 8) {
                            TALLY.slackSeen[slack].incrementAndGet();
                            if (cols > 1 && fateOf(worker.world, c.px, c.pz,
                                    ChainPrefilter.chainBaseY(c.chainA(), 1)) == ColumnFate.GREW) {
                                TALLY.slackContinued[slack].incrementAndGet();
                            }
                        }
                    }
                    if (grown > 0) {
                        grewSomething.incrementAndGet();
                        // A run taller than any one chunk built is the thing this command is
                        // for, even when it falls short of the prediction. These are rare
                        // enough to be precious -- two in a nine-hour night -- and used to be
                        // counted and then thrown away, leaving no seed and no coordinate for
                        // the only genuine two-chunk stacks the search has ever produced.
                        // At the run's OWN base, not at the predicted one. Asking
                        // caneRunFromOneChunk about a y where no cane stands returns 0, which
                        // beats any positive height and flags every column whose cane grew
                        // somewhere other than predicted -- three phantom stacks before this
                        // was noticed (6bu).
                        dev.drakou111.sugarcane.world.ArrayWorld measured =
                                orderedWorld != null ? orderedWorld : worker.world;
                        int alone = measured.caneRunFromOneChunk(c.px, grownBase, c.pz);
                        if (alone < grown) {
                            trueCrossChunk.incrementAndGet();
                            recordCrossChunk(c, grown, alone, grownBase);
                        }
                    }
                    // The bar is the height being hunted, not the chain's own prediction.
                    // predicted is minA + minB or more, so it is always at least target, and
                    // testing against it throws away exactly the runs worth having: a candidate
                    // predicting 18 that grows 17 was being filed as a failure.
                    if (grown < target) {
                        // First column that has no cane, over chain A then chain B in the order
                        // they must be built.
                        String where = null;
                        for (int side = 0; side < 2 && where == null; side++) {
                            long chain = side == 0 ? c.chainA() : c.chainB();
                            int cols = ChainPrefilter.chainColumns(chain);
                            for (int k = 0; k < cols; k++) {
                                ColumnFate fate = fateOf(worker.world, c.px, c.pz,
                                        ChainPrefilter.chainBaseY(chain, k));
                                if (fate != ColumnFate.GREW) {
                                    where = (side == 0 ? "A" : "B") + " col " + k + ": " + fate;
                                    break;
                                }
                            }
                        }
                        stopped.computeIfAbsent(where == null ? "every column grew, run short"
                                : where, k -> new AtomicLong()).incrementAndGet();
                    }
                    if (grown < target) {
                        // The world did not provide. Which part of it did not is the whole
                        // question, so read the base back rather than only counting the miss.
                        // The chunk is decorated by now, but a base that never got cane still
                        // holds whatever the carvers left, which is what is being asked about.
                        byte at = worker.world.getBlock(c.px, c.baseY, c.pz);
                        byte below = worker.world.getBlock(c.px, c.baseY - 1, c.pz);
                        if (!dev.drakou111.sugarcane.world.Blocks.isAir(at)) {
                            whyNotAir.incrementAndGet();
                        } else if (!dev.drakou111.sugarcane.world.Blocks.isCaneSoil(below)) {
                            whyNoSoil.incrementAndGet();
                            // A ravine tall enough to hold the stack usually carved the block
                            // under it too, and dirt cannot be blobbed into air. Splitting
                            // "carved away" from "still stone" says whether the fix is a
                            // tighter soil filter or a floor condition on the carve.
                            if (dev.drakou111.sugarcane.world.Blocks.isAir(below)) {
                                soilWasCarved.incrementAndGet();
                            } else if (dev.drakou111.sugarcane.world.Blocks
                                    .isWaterFluid(below)) {
                                soilWasWater.incrementAndGet();
                            }
                        } else if (!SugarCaneFeature.hasWaterBeside(
                                worker.world, c.px, c.baseY - 1, c.pz)) {
                            whyNoWater.incrementAndGet();
                        } else {
                            whyPlaceable.incrementAndGet();
                        }
                        continue;
                    }
                    found.incrementAndGet();
                    recordFind(c, grown);   // grown >= target, whatever the chain predicted
                    synchronized (CrossFind.class) {
                        System.out.printf("%nCONFIRMED height %d at %d,%d,%d%n",
                                grown, c.px, c.baseY, c.pz);
                        System.out.printf("  world seed %d%n", c.ws);
                        System.out.printf("  chunk A %d,%d gives %d, chunk B %d,%d adds %d "
                                        + "from y=%d%n",
                                c.cxa, c.cza, c.runA, c.cxb, c.czb, c.runB, c.joinY);
                        System.out.println("  grown from generated terrain, so the only thing "
                                + "left unchecked is decoration order in your world");
                        System.out.flush();
                    }
                }
            }, "crossfind-verify-" + t);
            pool[t].start();
        }
        Thread ticker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    return;
                }
                System.out.printf("  verified %d/%d, confirmed %d%n",
                        done.get(), pending.size(), found.get());
                System.out.flush();
            }
        }, "crossfind-verify-progress");
        ticker.setDaemon(true);
        ticker.start();
        for (Thread th : pool) {
            th.join();
        }
        ticker.interrupt();

        double secs = (System.currentTimeMillis() - start) / 1000.0;
        long built = pending.size() - ungenerated.get();
        TALLY.generated.addAndGet(built);
        TALLY.ungenerated.addAndGet(ungenerated.get());
        System.out.printf("  %d generated in %.1f s (%.2f/s)%n", built, secs, built / secs);
        if (ungenerated.get() > 0) {
            System.out.printf("  %d of %d were never generated at all (a filter skipped the "
                    + "chunk), so they were neither confirmed nor refuted%n",
                    ungenerated.get(), pending.size());
        }
    }

    /** One line of where stacks are stopping, for a run long enough to want to be asked. */
    private static void printRunningTally() {
        long built = TALLY.generated.get();
        if (built == 0) {
            return;
        }
        String worst = TALLY.stopped.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue().get(), x.getValue().get()))
                .limit(3)
                .map(e -> e.getKey() + " " + e.getValue().get())
                .reduce((x, y) -> x + ", " + y)
                .orElse("none");
        System.out.printf("  so far: %d generated, %d grew cane, %d beat one chunk; "
                        + "stopping at %s%n",
                built, TALLY.grewSomething.get(), TALLY.trueCrossChunk.get(), worst);
        System.out.flush();
    }

    /**
     * Everything verification learned, over every epoch.
     *
     * <p>Printed once at the end rather than per epoch. These are running totals, and a running
     * total labelled as though it belonged to one batch is how "7869 of 3617 were never
     * generated" got printed.
     */
    private static void printTally(long found) {
        long built = TALLY.generated.get();
        if (built == 0 && TALLY.ungenerated.get() == 0) {
            return;
        }
        System.out.printf("%nover every epoch: %d candidates generated, %d skipped by a "
                        + "filter (neither confirmed nor refuted)%n",
                built, TALLY.ungenerated.get());
        if (found == 0) {
            System.out.printf("  none survived; the tallest cane actually grown was %d%n",
                    TALLY.tallest.get());
        }
        System.out.printf("  %d grew some cane, of which %d ran taller than any one chunk "
                        + "built%n", TALLY.grewSomething.get(), TALLY.trueCrossChunk.get());
        if (TALLY.neededReorder.get() > 0) {
            System.out.printf("  %d only stood up once chunk B decorated after chunk A, which "
                    + "raster order does not do for four of the eight neighbours%n",
                    TALLY.neededReorder.get());
        }
        if (!TALLY.stopped.isEmpty()) {
            System.out.println("  where the predicted stack stops, first column with no cane:");
            TALLY.stopped.entrySet().stream()
                    .sorted((x, y) -> Long.compare(y.getValue().get(), x.getValue().get()))
                    .limit(12)
                    .forEach(e -> System.out.printf("    %-32s %d%n", e.getKey(),
                            e.getValue().get()));
        }
        System.out.println("  chain A's first column, by the base shift it assumed:");
        for (int i = 0; i < 4; i++) {
            long seen = TALLY.shiftSeen[i].get();
            if (seen > 0) {
                System.out.printf("    shift %d: %,12d candidates, %5d placed  (%.2e)%n",
                        i, seen, TALLY.shiftPlaced[i].get(),
                        TALLY.shiftPlaced[i].get() / (double) seen);
            }
        }
        boolean anySlack = false;
        for (int i = 0; i < 8; i++) {
            anySlack |= TALLY.slackSeen[i].get() > 0;
        }
        if (anySlack) {
            System.out.println("  of the chains that started, by the foreign placements they "
                    + "assume between their own columns:");
            for (int i = 0; i < 8; i++) {
                long seen = TALLY.slackSeen[i].get();
                if (seen > 0) {
                    System.out.printf("    slack %d: %,7d started, %4d continued to column 1%n",
                            i, seen, TALLY.slackContinued[i].get());
                }
            }
        }
        System.out.printf("  at chunk A's bottom base: not air %d, air but no soil under it %d, "
                        + "soil but no water beside %d, placeable and the RNG still did not %d%n",
                TALLY.whyNotAir.get(), TALLY.whyNoSoil.get(), TALLY.whyNoWater.get(),
                TALLY.whyPlaceable.get());
        if (TALLY.whyNoSoil.get() > 0) {
            System.out.printf("  of the %d with no soil: the block under the base was carved "
                            + "away %d, was water %d, was stone the blobs missed %d%n",
                    TALLY.whyNoSoil.get(), TALLY.soilWasCarved.get(), TALLY.soilWasWater.get(),
                    TALLY.whyNoSoil.get() - TALLY.soilWasCarved.get()
                            - TALLY.soilWasWater.get());
        }
    }

    /**
     * What became of one predicted column, read out of the finished world.
     *
     * <p>The base is the only block the feature tests — {@code ColumnPlacer} writes upward over
     * whatever is there — so this asks exactly what {@code canPlace} asks, and in the same order.
     */
    private static ColumnFate fateOf(dev.drakou111.sugarcane.world.ArrayWorld world,
            int px, int pz, int y) {
        byte at = world.getBlock(px, y, pz);
        if (at == dev.drakou111.sugarcane.world.Blocks.SUGAR_CANE) {
            return ColumnFate.GREW;
        }
        if (!dev.drakou111.sugarcane.world.Blocks.isAir(at)) {
            return ColumnFate.BLOCKED;
        }
        byte below = world.getBlock(px, y - 1, pz);
        if (below != dev.drakou111.sugarcane.world.Blocks.SUGAR_CANE
                && !dev.drakou111.sugarcane.world.Blocks.isCaneSoil(below)) {
            return ColumnFate.NO_SUPPORT;
        }
        if (!SugarCaneFeature.hasWaterBeside(world, px, y - 1, pz)) {
            return ColumnFate.NO_WATER;
        }
        return ColumnFate.PLACEABLE_BUT_EMPTY;
    }

    /**
     * The tallest contiguous cane run in this column, packed as {@code base << 32 | height}.
     *
     * <p>The base is the point. Comparing a run's height against
     * {@code caneRunFromOneChunk} at some <em>other</em> y compares two different columns of
     * cane and means nothing -- which is exactly the bug that produced three phantom
     * cross-chunk stacks (FINDINGS 6bu). The two must be measured at the same block.
     */
    private static long tallestRun(dev.drakou111.sugarcane.world.ArrayWorld world, int x, int z) {
        int best = 0;
        int base = -1;
        for (int y = 1; y < Y; y++) {
            int h = world.caneHeightAt(x, y, z);
            if (h > best) {
                best = h;
                base = y;
            }
        }
        return ((long) base << 32) | (best & 0xFFFFFFFFL);
    }

    /** The tallest contiguous cane run standing anywhere in this column. */
    private static int tallestAt(dev.drakou111.sugarcane.world.ArrayWorld world, int x, int z) {
        return (int) tallestRun(world, x, z);
    }

    /**
     * One candidate pair, from the RNG all the way to terrain.
     *
     * <p>Cheap tests first and each one rarer than the last: solve, border, biome, carve. The
     * solve is milliseconds and everything after it is microseconds, so the ordering that
     * matters is that the solve happens once per pair rather than once per world seed.
     */
    private static void examine(long dsA, long dsB, int dx, int dz, int minA, int minB,
            int sisters, ChainPrefilter endA, ChainPrefilter beginB, AirCarveProbe probe,
            LiquidCarveProbe liquid, DirtBlobFilter dirt, RegionSearcher.Worker worker,
            boolean useWater, boolean floorOnly, int maxCandidates, AtomicLong solvedSeeds,
            AtomicLong inBorder, AtomicLong carved, AtomicLong oceanPairs,
            AtomicLong solidNoise) {

        long[] worldSeeds = TwoChunkLift.solve(dsA, dsB, dx, dz);
        if (worldSeeds.length == 0) {
            return;
        }
        solvedSeeds.addAndGet(worldSeeds.length);
        for (long ws : worldSeeds) {
            examineSolved(ws, dsA, dsB, dx, dz, minA, minB, sisters, endA, beginB, probe,
                    liquid, dirt, worker, useWater, floorOnly, maxCandidates, inBorder,
                    carved, oceanPairs, solidNoise);
        }
    }

    /**
     * Everything after the lift, for one solved world seed.
     *
     * <p>Split out so the seed can arrive from {@link TwoChunkLift} or from the GPU kernel
     * without the rest of the pipeline knowing which. The lift is the whole cost of a run once
     * the scan is on the card (6bo), and it batches — which the inline call could not.
     */
    private static void examineSolved(long ws, long dsA, long dsB, int dx, int dz, int minA,
            int minB, int sisters, ChainPrefilter endA, ChainPrefilter beginB, AirCarveProbe probe,
            LiquidCarveProbe liquid, DirtBlobFilter dirt, RegionSearcher.Worker worker,
            boolean useWater, boolean floorOnly, int maxCandidates, AtomicLong inBorder,
            AtomicLong carved, AtomicLong oceanPairs, AtomicLong solidNoise) {
        DecorationLattice lattice = new DecorationLattice(ws);
        {
            int[] chunk = lattice.solve(dsA);
            if (chunk == null) {
                return;
            }
            int cxa = chunk[0], cza = chunk[1];
            int cxb = cxa + dx, czb = cza + dz;
            if (Math.abs(cxb) > DecorationLattice.BORDER_CHUNKS
                    || Math.abs(czb) > DecorationLattice.BORDER_CHUNKS) {
                return;
            }
            // The lift solved an equation; this asks setDecorationSeed itself. If they ever
            // disagree the lift is wrong, and it should be caught here rather than believed.
            if (lattice.decorationSeedOf(cxb, czb) != dsB) {
                return;
            }
            inBorder.incrementAndGet();

            // Recover the two chains. The pair was matched on a join key, so the chains that
            // produced it are found again by re-running the filters and looking for the block
            // where one stops and the other starts.
            int na = endA.collectChains(dsA, OCEAN_INDEX, minA);
            if (endA.chainsOverflowed()) {
                return;
            }
            int nb = beginB.collectChains(dsB, OCEAN_INDEX, minB);
            if (beginB.chainsOverflowed()) {
                return;
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
                    // Sister-invariant terrain first. The carver walk and the dirt blobs are
                    // both driven by seeds that get masked to 48 bits, so they answer for all
                    // 65,536 sisters of this world seed at once (FINDINGS 6al) — and if they
                    // say no, every sister is dead and the lift that produced them is wasted.
                    if (!allCarved(probe, liquid, dirt, lattice, ca, px, pz, useWater,
                            true, floorOnly)
                            || !allCarved(probe, liquid, dirt, lattice, cb, px, pz,
                                    useWater, false, floorOnly)) {
                        continue;
                    }
                    carved.incrementAndGet();
                    int height = runOf(ca) + runOf(cb);

                    // Everything the upper 16 bits DO move: the biome map, and through its
                    // depth and scale the sea floor. Both of the gates below are terrain, and
                    // terrain is what kills cross-chunk candidates (6bf), so this is the whole
                    // point — one 1.45 ms lift used to buy one roll of it.
                    for (int u = 0; u < sisters; u++) {
                        long full = ws | ((long) u << 48);
                        worker.prepareBiomesOnly(full);
                        int biomeA = BiomeIds.noiseGen(worker.biomeSource(),
                                cxa * 4 + 2, cza * 4 + 2);
                        int biomeB = BiomeIds.noiseGen(worker.biomeSource(),
                                cxb * 4 + 2, czb * 4 + 2);
                        if (!RegionSearcher.isSearchableOcean(biomeA)
                                || !BiomeCaneConfig.hasSugarCane(biomeA)
                                || !RegionSearcher.isSearchableOcean(biomeB)
                                || !BiomeCaneConfig.hasSugarCane(biomeB)) {
                            continue;
                        }
                        oceanPairs.incrementAndGet();
                        // The whole stack is one world column, so one noise column answers both
                        // chains. This is the check 6bf found missing: the carve probe's stub
                        // replaces anything, but the real carver will not turn water into air,
                        // so a base the noise left as water can never be carved. A cross-chunk
                        // join is high by construction, which puts these bases exactly where
                        // the noise is most likely to have stopped — 6bf's "probe says carved
                        // 31, actually air 7, overlap 0".
                        if (!noiseHolds(worker, px, pz, ca, cb)) {
                            continue;
                        }
                        solidNoise.incrementAndGet();
                        // The same question the sister-invariant probe answered with a stub,
                        // asked again now that this sister's noise is known and the carvers'
                        // water guard can actually fire. 6cg measured it as sound and 307x
                        // more selective; it is here rather than beside allCarved because the
                        // answer moves with the sister and that one does not.
                        if (exactCarve && !exactlyCarved(worker, full, px, pz, ca, cb)) {
                            continue;
                        }
                        // NOT a find. The carve probe only says a carver's walk reached these
                        // blocks, which is a necessary condition and nothing more -- the
                        // finished world can still be solid stone there, and was for the first
                        // batch this command printed. Real terrain decides, in verify() below.
                        synchronized (CANDIDATES) {
                            if (CANDIDATES.size() >= maxCandidates) {
                                candidatesTruncated = true;
                            } else {
                                        CANDIDATES.add(new Candidate(full, cxa, cza, cxb, czb, px, pz,
                                        ChainPrefilter.chainBaseY(ca, 0), top, runOf(ca),
                                        runOf(cb), height, ca, cb));
                            }
                        }
                    }
                }
            }
        }
    }

    /** The pass-2 ticker, hoisted so the epoch loop can restart its workers underneath it. */
    private static Thread progressThread(long pass2Start, AtomicLong streamed, AtomicLong joins,
            AtomicLong solvedSeeds, AtomicLong inBorder, AtomicLong carved,
            AtomicLong oceanPairs, AtomicLong solidNoise) {
        return new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    return;
                }
                long ms = System.currentTimeMillis() - pass2Start;
                System.out.printf("[%4.1f min] %d seeds examined (%.0f/s), joins %d, "
                                + "world seeds solved %d, in border %d, past carve %d, "
                                + "ocean %d, solid noise %d "
                                + "(candidates, terrain not generated yet)%n",
                        ms / 60000.0, streamed.get(),
                        streamed.get() * 1000.0 / Math.max(1, ms), joins.get(),
                        solvedSeeds.get(), inBorder.get(), carved.get(),
                        oceanPairs.get(), solidNoise.get());
                System.out.flush();
            }
        }, "crossfind-progress");
    }

    /**
     * One decoration seed's contribution to the join table.
     *
     * <p>Shared by the CPU and GPU scanners so the two cannot drift: the GPU one simply calls
     * it on fewer seeds. Each side is keyed in its OWN chunk's frame, with no direction folded
     * in -- that is what lets one table serve all eight neighbours.
     */
    private static void collectOne(long ds, ChainPrefilter filter, int storedMin,
            boolean storeEndings, Hits hits, AtomicLong stored) {
        int n = filter.collectChains(ds, OCEAN_INDEX, storedMin);
        if (filter.chainsOverflowed()) {
            return;
        }
        for (int i = 0; i < n; i++) {
            long chain = filter.chain(i);
            int x = ChainPrefilter.chainX(chain);
            int z = ChainPrefilter.chainZ(chain);
            int y = storeEndings ? ChainPrefilter.chainTop(chain)
                    : ChainPrefilter.chainBaseY(chain, 0);
            if (inFrame(x, z, y)) {
                hits.add(key(x, z, y, ds), ds);
                stored.incrementAndGet();
            }
        }
    }

    /**
     * The kernel run as a pre-filter, over one epoch.
     *
     * <p>It is not asked for chain geometry -- it cannot report any -- only for which seeds
     * carry a chain at all. {@code KernelAgreement --config=crossfind-ending} and
     * {@code --config=crossfind-beginning} hold it to exactly this command's two filters, at
     * every height the command uses; both agree seed for seed, because these configs run at
     * {@code maxBaseShift 3} and so take neither the greedy path nor the incremental one that
     * 6bi caught dropping seeds at {@code maxBaseShift 0}.
     */
    private static long[] gpuEpoch(dev.drakou111.sugarcane.gen.GpuChainFilter gpu,
            int minHeight, boolean endingSide, long from, long count) throws Exception {
        int baseMinY = endingSide ? ChainPrefilter.DEFAULT_BASE_MIN_Y : 11;
        int baseMaxY = endingSide ? ChainPrefilter.DEFAULT_BASE_MAX_Y : 64;
        return gpu.run(minHeight, SugarCaneFeature.COUNT_DEFAULT, OCEAN_INDEX,
                baseMinY, baseMaxY, 3, 4, Integer.MAX_VALUE,
                ChainPrefilter.DEFAULT_SHIFT_LEVELS, -1, -1, from, count);
    }

    /**
     * Whether the AIR carvers' water guard is answered from the noise instead of stubbed out.
     *
     * <p>6cg: {@code waterGuard()} is {@code !underwater}, and the AIR-step carvers are the only
     * source of air below sea level, so the carvers that matter are exactly the ones that run it.
     * With {@code isWater} stubbed false a ravine carves straight through water the real one
     * stops at, which is why 58.6% of this command's candidates used to die at chunk A's first
     * column with the base not air. Measured against generated terrain over 332,800 blocks: it
     * loses no block the stub kept, and cuts false positives 4,914 to 16.
     *
     * <p><b>Off by default, because the block-level ratio is not the run-level one.</b> By the
     * time a candidate reaches here it has already passed the stub probe, the biome gate and
     * the noise check, so the only population left to remove is the BLOCKED one: measured
     * 13,191 terrain generations down to 5,577, a 2.4x cut that matches the 58.6% those runs
     * were losing at chunk A's first column. But the walk costs about what it saves -- 218.3 s
     * against 228.5 s on the same slice, 4.7% the wrong way -- because the guard fills a
     * chunk's 256 noise columns per sister where a generation would have paid once. It wins
     * only where terrain dominates a run, so it is a flag and not a default.
     *
     * <p>Getting past that means evaluating the guard for the spheres that touch the block
     * rather than blanketing the chunk: the stub walk already finds them for 74 us, and it is
     * the 618 us of columns behind it that costs.
     */
    private static boolean exactCarve = false;

    /**
     * The probe that reads the noise, one per thread.
     *
     * <p>Per thread because it memoises its walk on (seed, chunk) and the sister sweep asks about
     * one chunk at a time, and because the oracle binds to that thread's worker. Deliberately not
     * the {@code probe} passed around beside it: that one is walked once per solved seed because
     * it is sister-invariant, and this one cannot be — the noise moves with the upper 16 bits.
     */
    private static final ThreadLocal<AirCarveProbe> EXACT_PROBE = new ThreadLocal<>();

    private static AirCarveProbe exactProbe(RegionSearcher.Worker worker) {
        AirCarveProbe p = EXACT_PROBE.get();
        if (p == null) {
            p = new AirCarveProbe().ravinesOnly(true)
                    .water((x, y, z) -> Blocks.isWaterFluid(worker.noiseAt(x, y, z)));
            EXACT_PROBE.set(p);
        }
        return p;
    }

    /**
     * Every column base of both chains, against the carvers as they really run for this sister.
     *
     * <p>Costs a walk — 692 us against the 1,992 us chunk generation it saves, and it rejects
     * about 98% of what reaches it.
     */
    private static boolean exactlyCarved(RegionSearcher.Worker worker, long full,
            int px, int pz, long ca, long cb) {
        AirCarveProbe p = exactProbe(worker);
        p.walk(full, px >> 4, pz >> 4, true);
        int na = ChainPrefilter.chainColumns(ca);
        for (int c = 0; c < na; c++) {
            if (!p.isCarved(px, ChainPrefilter.chainBaseY(ca, c), pz)) {
                return false;
            }
        }
        int nb = ChainPrefilter.chainColumns(cb);
        for (int c = 0; c < nb; c++) {
            if (!p.isCarved(px, ChainPrefilter.chainBaseY(cb, c), pz)) {
                return false;
            }
        }
        return true;
    }

    /** Every column base of both chains sits in a block the noise made solid. */
    private static boolean noiseHolds(RegionSearcher.Worker worker, int px, int pz,
            long ca, long cb) {
        int na = ChainPrefilter.chainColumns(ca);
        int nb = ChainPrefilter.chainColumns(cb);
        int[] bases = new int[na + nb];
        for (int c = 0; c < na; c++) {
            bases[c] = ChainPrefilter.chainBaseY(ca, c);
        }
        for (int c = 0; c < nb; c++) {
            bases[na + c] = ChainPrefilter.chainBaseY(cb, c);
        }
        return worker.noiseCouldHoldChain(px, pz, bases, bases.length);
    }

    /**
     * Every column base of one chain is air, has water beside it, and — for the chain that
     * stands on the sea floor rather than on cane — soil beneath it.
     */
    private static boolean allCarved(AirCarveProbe probe, LiquidCarveProbe liquid,
            DirtBlobFilter dirt, DecorationLattice lattice, long chain, int px, int pz,
            boolean useWater, boolean needsSoil, boolean floorOnly) {
        long ws = lattice.worldSeed();
        int pcx = px >> 4, pcz = pz >> 4;
        // Biome-blind on purpose, so one walk serves every sister. Only the cave carver's
        // start probability depends on the biome, and CAVE_LAND (0.1429) fires on a strict
        // superset of CAVE_OCEAN (0.0667) start chunks off the same nextFloat, so claiming
        // land carves a superset and can lose no find (FINDINGS 6al). With --ravines-only,
        // which is this command's default, the cave carver does not run at all.
        probe.walk(ws, pcx, pcz, false);
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
        int soilY = ChainPrefilter.chainBaseY(chain, 0) - 1;
        // The bottom column has to stand ON the ravine floor, not inside the ravine. A cut
        // tall enough to hold a cross-chunk stack usually took the block below the base as
        // well, and dirt cannot be blobbed into air: measured, that is 62% of every
        // candidate that reached real terrain with air at its base and nothing under it.
        //
        // A flag rather than the default because the probe over-approximates carving, so it
        // can say "carved" where the real carver was stopped, and the find that was standing
        // there is then thrown away. The over-approximation is mostly above the noise floor
        // and this block is below it, but "mostly" is not the same as measured.
        if (floorOnly && probe.isCarved(px, soilY, pz)) {
            return false;
        }
        return couldHaveSoil(dirt, lattice, px, soilY, pz);
    }

    /**
     * Whether any chunk's ORE_DIRT pass could have put soil under the bottom column.
     *
     * <p>The single-chunk pipeline asks only the decorating chunk, because when a target set
     * is built there is no world seed yet and therefore no way to name a neighbour's
     * decoration seed. Here the world seed is already solved, so the neighbours are free —
     * which matters, because the join geometry puts the column outside its own chunk about a
     * third of the time. That case used to be waved through unchecked (FINDINGS 6bf): the
     * chain most in need of the test was the one that skipped it.
     *
     * <p>A blob reaches {@code REACH} blocks from a draw inside its own chunk, so at most two
     * chunk columns in x and two in z can supply any one block. Asking all of them is both
     * tighter than the wave-through and more permissive than the single-chunk filter, whose
     * measured 18% coverage loss is exactly the neighbour blobs this now sees.
     */
    private static boolean couldHaveSoil(DirtBlobFilter dirt, DecorationLattice lattice,
            int px, int soilY, int pz) {
        int cx0 = Math.floorDiv(px - (16 + DirtBlobFilter.REACH - 1), 16);
        int cx1 = Math.floorDiv(px + DirtBlobFilter.REACH, 16);
        int cz0 = Math.floorDiv(pz - (16 + DirtBlobFilter.REACH - 1), 16);
        int cz1 = Math.floorDiv(pz + DirtBlobFilter.REACH, 16);
        for (int qcx = cx0; qcx <= cx1; qcx++) {
            for (int qcz = cz0; qcz <= cz1; qcz++) {
                long deco = lattice.decorationSeedOf(qcx, qcz);
                if (dirt.couldSupply(deco, px - qcx * 16, soilY, pz - qcz * 16)) {
                    return true;
                }
            }
        }
        return false;
    }
}
