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
        if (args.length < 4) {
            System.err.println("usage: spot <relX> <relZ> <baseY> <height> [seeds] [threads]");
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

        System.out.printf("decoration seeds growing a %d-tall stack at chunk-relative "
                + "%d,%d,%d%n", height, wantX, wantY, wantZ);
        System.out.printf("  %d threads, %s seeds%n", threads,
                seeds == Long.MAX_VALUE ? "no limit" : Long.toString(seeds));
        // The band is what makes the ordinary search selective, and it would reject the
        // very block being asked about if that block sits outside it. The caller named the
        // position, so the band has nothing left to decide.
        System.out.println("  depth band ignored -- you named the y, so it is the band");

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
                            report(ds, chain);
                            found.incrementAndGet();
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
