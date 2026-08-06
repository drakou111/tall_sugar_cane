package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.OrbitSampler;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Decoration seeds that grow a stack at one <em>named</em> block, rather than anywhere.
 *
 * <p>The reverse search asks "does this seed stack somewhere in the chunk", then goes looking
 * for terrain that suits wherever that turned out to be. This asks the opposite question:
 * given a block you already like -- a ravine wall you found with soil under it and water
 * beside it -- which decoration seeds build a stack exactly there?
 *
 * <p>No world seed and no lattice. The answer is a set of decoration seeds, and turning one
 * into coordinates is the existing `reverse` machinery's job. What this replaces is the part
 * where you accept whatever position the RNG offered.
 *
 * <p>It is much rarer than the ordinary question and that is the point. A chain has to land on
 * one (x, z) of the 24 x 24 a chunk can reach and one base y of the 54 legal ones, so roughly
 * 1,300 times rarer than "somewhere". In exchange every hit is already matched to terrain you
 * have verified, instead of needing terrain to be found for it.
 *
 * <p>Coordinates are chunk-relative, the same frame {@code inspect} prints: x and z in -4..19,
 * because a placement can land four blocks outside its own chunk.
 */
public final class SpotSearch {

    private static final int OCEAN_INDEX = 5;

    private SpotSearch() {
    }

    public static void main(String[] args) throws Exception {
        // --cpu anywhere in the arguments, stripped before the positional parse.
        boolean forceCpu = false;
        java.util.List<String> positional = new java.util.ArrayList<>(args.length);
        for (String arg : args) {
            if (arg.equals("--cpu")) {
                forceCpu = true;
            } else {
                positional.add(arg);
            }
        }
        args = positional.toArray(new String[0]);

        if (args.length < 4) {
            System.err.println("usage: spot <relX> <relZ> <baseY> <height> [seeds] [threads] [--cpu]");
            System.err.println("  relX and relZ are chunk-relative, -4..19 (a placement can");
            System.err.println("  land four blocks outside its own chunk). baseY is where the");
            System.err.println("  bottom of the stack sits.");
            System.exit(2);
            return;
        }

        int wantX = Integer.parseInt(args[0]);
        int wantZ = Integer.parseInt(args[1]);
        int wantY = Integer.parseInt(args[2]);
        int height = Integer.parseInt(args[3]);
        long seeds = args.length > 4 ? Long.parseLong(args[4]) : 0L;
        int threads = Cli.clampThreads(args.length > 5 ? Integer.parseInt(args[5])
                : Runtime.getRuntime().availableProcessors());
        if (seeds <= 0) {
            seeds = Long.MAX_VALUE;
        }

        if (wantX < -4 || wantX > 19 || wantZ < -4 || wantZ > 19) {
            System.err.printf("x and z must be -4..19 chunk-relative, got %d,%d%n",
                    wantX, wantZ);
            System.exit(2);
            return;
        }

        // Named, not positional: the command takes relX relZ baseY but a bare "3,21,10"
        // reads as x,z,y to anyone who has just typed it in that order.
        System.out.printf("decoration seeds growing a %d-tall stack at chunk-relative "
                + "x=%d z=%d baseY=%d%n", height, wantX, wantZ, wantY);
        System.out.printf("  %d threads, %s seeds%n", threads,
                seeds == Long.MAX_VALUE ? "no limit" : Long.toString(seeds));
        // The band is what makes the ordinary search selective, and it would reject the
        // very block being asked about if that block sits outside it. The caller named the
        // position, so the band has nothing left to decide.
        System.out.println("  depth band ignored -- you named the y, so it is the band");

        // (x+4) + (z+4)*24, the same packing the kernel uses.
        final int wantKey = (wantX + 4) + (wantZ + 4) * 24;
        final java.util.Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();

        dev.drakou111.sugarcane.gen.GpuChainFilter gpu = forceCpu ? null
                : dev.drakou111.sugarcane.gen.GpuChainFilter.detect();
        if (gpu != null) {
            System.out.printf("  using the GPU at %s%n", gpu.binary());
            runOnGpu(gpu, wantX, wantZ, wantY, height, wantKey, seeds, seen);
            return;
        }
        System.out.println(forceCpu
                ? "  --cpu given, running on the CPU (about 15x slower)"
                : "  no usable GPU, running on the CPU (about 15x slower)");
        AtomicLong tested = new AtomicLong();
        AtomicLong found = new AtomicLong();
        AtomicLong next = new AtomicLong();
        long start = System.currentTimeMillis();
        final long limit = seeds;

        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                // Full column and every shift: the position is the constraint now, so
                // narrowing anything else would only lose seeds that would have worked.
                ChainPrefilter filter = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT,
                        11, 64, 3, 4);
                // Shared, because the scan walks orbit neighbours: without it every member
                // of a family would print the whole family again as the walk reaches it.
                for (long run = next.getAndIncrement(); run * OrbitSampler.RUN < limit;
                        run = next.getAndIncrement()) {
                    long ds = OrbitSampler.runStart(run);
                    for (int k = 0; k < OrbitSampler.RUN; k++) {
                        int chains = filter.collectChains(ds, OCEAN_INDEX, height);
                        if (filter.chainsOverflowed()) {
                            chains = 0;     // cannot tell which chain is where
                        }
                        for (int i = 0; i < chains; i++) {
                            long chain = filter.chain(i);
                            if (ChainPrefilter.chainX(chain) != wantX
                                    || ChainPrefilter.chainZ(chain) != wantZ
                                    || ChainPrefilter.chainBaseY(chain, 0) != wantY) {
                                continue;
                            }
                            found.addAndGet(reportFamily(filter, ds, chain,
                                    wantX, wantZ, wantY, height, seen));
                            break;
                        }
                        tested.incrementAndGet();
                        ds = OrbitSampler.shift(ds, OCEAN_INDEX,
                                SugarCaneFeature.VEGETAL_DECORATION);
                    }
                }
            }, "spot-" + t);
            pool[t].start();
        }

        Thread progress = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException e) {
                    return;
                }
                long n = tested.get();
                double secs = (System.currentTimeMillis() - start) / 1000.0;
                System.out.printf("[%4.1f min] %d tested (%.2fM/s), %d found%n",
                        secs / 60.0, n, n / secs / 1e6, found.get());
                System.out.flush();
            }
        }, "spot-progress");
        progress.setDaemon(true);
        progress.start();

        for (Thread th : pool) {
            th.join();
        }
        progress.interrupt();
        double secs = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("%n%d seeds tested in %.1f s, %d found (%.3e)%n",
                tested.get(), secs, found.get(),
                found.get() / (double) Math.max(1, tested.get()));
    }

    /**
     * The scan on the GPU, families expanded here.
     *
     * <p>The kernel answers one question per seed -- is there a chain based at this exact
     * block -- which is the expensive part and the part that parallelises. Expanding each hit
     * into its orbit family is a handful of filter evaluations on a seed that turns up once in
     * millions, so it stays on the CPU where it is easier to get right.
     *
     * <p>Verified against the CPU path over the same 200M seeds: the kernel found 147 and Java
     * 153, and all six of the difference are orbit relatives of kernel hits that lie outside
     * the scanned range -- found by family expansion, not by disagreeing about any seed.
     */
    private static void runOnGpu(dev.drakou111.sugarcane.gen.GpuChainFilter gpu,
            int wantX, int wantZ, int wantY, int height, int wantKey, long seeds,
            java.util.Set<Long> seen) throws Exception {
        ChainPrefilter filter = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT,
                11, 64, 3, 4);
        long batch = 200_000_000L;
        long done = 0;
        long found = 0;
        long start = System.currentTimeMillis();
        while (done < seeds) {
            long n = Math.min(batch, seeds - done);
            long[] hits = gpu.run(height, SugarCaneFeature.COUNT_DEFAULT, OCEAN_INDEX,
                    11, 64, 3, 4, Integer.MAX_VALUE, ChainPrefilter.DEFAULT_SHIFT_LEVELS,
                    wantKey, wantY, done, n);
            for (long ds : hits) {
                long chain = chainAt(filter, ds, wantX, wantZ, wantY, height);
                if (chain != 0) {
                    found += reportFamily(filter, ds, chain, wantX, wantZ, wantY, height, seen);
                }
            }
            done += n;
            double secs = (System.currentTimeMillis() - start) / 1000.0;
            System.out.printf("[%4.1f min] %d tested (%.1fM/s), %d found%n",
                    secs / 60.0, done, done / secs / 1e6, found);
            System.out.flush();
        }
        double secs = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("%n%d seeds tested in %.1f s, %d found (%.3e)%n",
                done, secs, found, found / (double) Math.max(1, done));
    }

    /**
     * A hit and its orbit family, together.
     *
     * <p>Sliding a stream by whole invocations moves a chain's invocation indices without
     * touching its geometry, so a brother builds at the <em>same block</em> -- same x, same z,
     * same base y, same heights. For a spot query that makes every brother an answer to the
     * same question, and each is a different decoration seed, so each is a separate chance for
     * the lattice to land it somewhere real.
     *
     * <p>The scan already walks orbit neighbours, so it was finding these and printing them as
     * if unrelated. Grouping them says what they are, and expanding explicitly also catches the
     * ones that fall across a run boundary and would otherwise be missed.
     *
     * <p>Both directions: {@code shift} drops the earliest invocation and {@code unshift}
     * prepends one, so a chain occupying invocations [a..b] survives a of the first and
     * count-1-b of the second. Which direction pays depends on where in the stream the chain
     * happens to sit.
     *
     * @return how many were reported, or 0 if this family has already been printed
     */
    private static int reportFamily(ChainPrefilter filter, long ds, long chain,
            int wantX, int wantZ, int wantY, int height,
            java.util.Set<Long> seen) {
        if (!seen.add(ds)) {
            return 0;
        }
        java.util.List<long[]> family = new java.util.ArrayList<>();
        family.add(new long[]{0, ds, chain});
        for (int dir = 0; dir < 2; dir++) {
            long s = ds;
            for (int j = 1; j < SugarCaneFeature.COUNT_DEFAULT; j++) {
                s = dir == 0
                        ? OrbitSampler.unshift(s, OCEAN_INDEX, SugarCaneFeature.VEGETAL_DECORATION)
                        : OrbitSampler.shift(s, OCEAN_INDEX, SugarCaneFeature.VEGETAL_DECORATION);
                long match = chainAt(filter, s, wantX, wantZ, wantY, height);
                if (match == 0) {
                    break;      // the chain has slid off this end; it will not come back
                }
                seen.add(s);
                family.add(new long[]{dir == 0 ? -j : j, s, match});
            }
        }
        family.sort((p, q) -> Long.compare(p[0], q[0]));
        printFamily(family);
        return family.size();
    }

    /** The chain at exactly this spot, or 0. */
    private static long chainAt(ChainPrefilter filter, long ds, int wantX, int wantZ,
            int wantY, int height) {
        int chains = filter.collectChains(ds, OCEAN_INDEX, height);
        if (filter.chainsOverflowed()) {
            return 0;
        }
        for (int i = 0; i < chains; i++) {
            long c = filter.chain(i);
            if (ChainPrefilter.chainX(c) == wantX && ChainPrefilter.chainZ(c) == wantZ
                    && ChainPrefilter.chainBaseY(c, 0) == wantY) {
                return c;
            }
        }
        return 0;
    }

    private static synchronized void printFamily(java.util.List<long[]> family) {
        long[] first = family.get(0);
        System.out.printf("FAMILY of %d, all building the same block:%n", family.size());
        for (long[] member : family) {
            System.out.printf("  %+3d  SEED %-18d %s%n",
                    (int) member[0], member[1], describe(member[2]));
        }
        System.out.flush();
    }

    private static String describe(long chain) {
        int columns = ChainPrefilter.chainColumns(chain);
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < columns; i++) {
            sb.append(ChainPrefilter.chainBaseY(chain, i))
                    .append('+').append(ChainPrefilter.chainHeight(chain, i)).append(' ');
            total += ChainPrefilter.chainHeight(chain, i);
        }
        return String.format("height %d, %d columns, bases %s", total, columns, sb);
    }

    private static synchronized void report(long decorationSeed, long chain) {
        int columns = ChainPrefilter.chainColumns(chain);
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < columns; i++) {
            sb.append(ChainPrefilter.chainBaseY(chain, i))
                    .append('+').append(ChainPrefilter.chainHeight(chain, i)).append(' ');
            total += ChainPrefilter.chainHeight(chain, i);
        }
        System.out.printf("SEED %d  height %d  columns %d  bases %s baseShift %d%n",
                decorationSeed, total, columns, sb, ChainPrefilter.chainBaseShift(chain));
        System.out.flush();
    }
}
