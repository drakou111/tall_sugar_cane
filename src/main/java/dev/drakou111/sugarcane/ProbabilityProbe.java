package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.DirtBlobFilter;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures P: given terrain that offers a stackable spot, how often does the
 * cane RNG actually build a column taller than 4?
 *
 * <p>The search cost is R x P per chunk, and R is now measured (the stackable
 * spot rate), but P was only ever measured on hand-built terrain — FINDINGS
 * section 4, where an isolated spot gave about 1.1e-5. Terrain the generator
 * actually produces is not one isolated spot: a carved cave wall offers a whole
 * run of adjacent positions, and P scales with that. The difference decides
 * whether the search takes hours or weeks, so it is worth measuring on real
 * geometry rather than assuming.
 *
 * <p>Method: take the 24x24 window around a chunk that has at least one
 * stackable spot, then replay {@code patch_sugar_cane} over it with many
 * synthetic decoration seeds, restoring the window each time. The decoration
 * seed is the only thing that varies, which is exactly the quantity a search
 * over seeds and chunk positions samples.
 */
public final class ProbabilityProbe {

    public static final int MARGIN = 4;
    public static final int WINDOW = 16 + 2 * MARGIN;

    private final ArrayWorld window;
    private final ArrayWorld pristine;
    private final JavaRandom random = new JavaRandom();

    /**
     * Why real finds are the only honest way to rank targets.
     *
     * <p>The reverse search keeps a decoration seed when some chain of the right height
     * exists, and weights every such seed equally. They are plainly not equal: a chain
     * assuming three earlier placements in the same chunk, or four columns instead of
     * two, or a base at the thin end of the depth band, is much less likely ever to
     * meet terrain that suits it. Which of those matters, and by how much, is not
     * something to reason about -- it is something to read off finds that happened.
     *
     * <p>So on every hit the chain the filter would have recorded is inspected and its
     * properties counted. The same properties are counted over the accepted population,
     * and the ratio of the two is the weight. Counters are static because the probe is
     * one instance per worker.
     */
    private static final AtomicLong[] hitBaseShift = newCounters(4);
    private static final AtomicLong[] popBaseShift = newCounters(4);
    private static final AtomicLong[] hitColumns = newCounters(6);
    private static final AtomicLong[] popColumns = newCounters(6);
    private static final AtomicLong[] hitBaseY = newCounters(72);
    private static final AtomicLong[] popBaseY = newCounters(72);
    /** Finds whose chain the filter never records at all: pure coverage loss. */
    private static final AtomicLong hitsMissedByFilter = new AtomicLong();
    private static final AtomicLong hitsSeen = new AtomicLong();
    private static final AtomicLong hitsSoilRejected = new AtomicLong();

    private static AtomicLong[] newCounters(int n) {
        AtomicLong[] a = new AtomicLong[n];
        for (int i = 0; i < n; i++) {
            a[i] = new AtomicLong();
        }
        return a;
    }

    private final ChainPrefilter chainFilter = new ChainPrefilter(SugarCaneFeature.COUNT_DESERT);
    private final DirtBlobFilter dirtFilter = new DirtBlobFilter();

    public ProbabilityProbe() {
        this.window = new ArrayWorld(0, 0, WINDOW, WINDOW);
        this.pristine = new ArrayWorld(0, 0, WINDOW, WINDOW);
    }

    /**
     * Copies the window around (chunkX, chunkZ) out of {@code source} and returns
     * how many of {@code trials} decoration seeds produce a column taller than
     * {@code minHeight}.
     *
     * <p>The chunk keeps its real coordinates so the placement draws land in the
     * same place; only the seed changes.
     */
    public int measure(ArrayWorld source, int chunkX, int chunkZ, int count, int index,
                       int trials, int minHeight, long probeSeed) {
        int originX = chunkX * 16 - MARGIN;
        int originZ = chunkZ * 16 - MARGIN;
        pristine.reset(originX, originZ);
        for (int x = originX; x < originX + WINDOW; x++) {
            for (int z = originZ; z < originZ + WINDOW; z++) {
                for (int y = 0; y < ArrayWorld.HEIGHT; y++) {
                    byte b = source.getBlock(x, y, z);
                    if (b != Blocks.AIR) {
                        pristine.setBlock(x, y, z, b);
                    }
                }
            }
        }
        window.reset(originX, originZ);

        JavaRandom seeds = new JavaRandom(probeSeed);
        int hits = 0;
        for (int t = 0; t < trials; t++) {
            window.restoreFrom(pristine);
            long decorationSeed = seeds.nextLong();
            int tallest = 0;
            for (SugarCaneFeature.Column c : SugarCaneFeature.place(
                    window, decorationSeed, index, count, chunkX, chunkZ)) {
                tallest = Math.max(tallest, window.caneRunThrough(c.x(), c.y(), c.z()));
            }
            if (tallest >= minHeight) {
                hits++;
                recordHit(decorationSeed, index, minHeight);
            }
        }
        return hits;
    }

    /**
     * Reads the chain properties off a decoration seed that really produced a find.
     */
    private void recordHit(long decorationSeed, int index, int minHeight) {
        long ds = decorationSeed & ((1L << 48) - 1);
        hitsSeen.incrementAndGet();
        int chains = chainFilter.collectChains(ds, index, minHeight);
        if (chains == 0 && !chainFilter.chainsOverflowed()) {
            // The feature built it and the filter cannot see it, so the target set
            // would never have held this seed.
            hitsMissedByFilter.incrementAndGet();
            return;
        }
        if (chainFilter.chainsOverflowed()) {
            return;
        }
        long best = cheapest(chains);
        hitBaseShift[ChainPrefilter.chainBaseShift(best)].incrementAndGet();
        hitColumns[Math.min(ChainPrefilter.chainColumns(best), hitColumns.length - 1)]
                .incrementAndGet();
        int baseY = ChainPrefilter.chainBaseY(best, 0);
        hitBaseY[Math.min(baseY, hitBaseY.length - 1)].incrementAndGet();

        // Exactly what buildTargets asks: does ANY chain of this seed have soil its own
        // chunk's blobs could supply? Testing only the cheapest chain, as an earlier
        // version of this did, overstates the rejection badly.
        boolean soilOk = false;
        for (int i = 0; i < chains && !soilOk; i++) {
            long c = chainFilter.chain(i);
            int rx = ChainPrefilter.chainX(c), rz = ChainPrefilter.chainZ(c);
            int sy = ChainPrefilter.chainBaseY(c, 0) - 1;
            soilOk = rx < 0 || rx > 15 || rz < 0 || rz > 15
                    || dirtFilter.couldSupply(ds, rx, sy, rz);
        }
        if (!soilOk) {
            hitsSoilRejected.incrementAndGet();
        }
    }

    /** The chain assuming the fewest earlier placements, which is the search's pick. */
    private long cheapest(int chains) {
        long best = chainFilter.chain(0);
        for (int i = 1; i < chains; i++) {
            long c = chainFilter.chain(i);
            if (ChainPrefilter.chainBaseShift(c) < ChainPrefilter.chainBaseShift(best)) {
                best = c;
            }
        }
        return best;
    }

    /** The same properties over accepted seeds generally: the baseline to divide by. */
    public void samplePopulation(int index, int minHeight, long trials) {
        for (long i = 0; i < trials; i++) {
            long z = i * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = (z ^ (z >>> 31)) & ((1L << 48) - 1);
            int chains = chainFilter.collectChains(z, index, minHeight);
            if (chains == 0 || chainFilter.chainsOverflowed()) {
                continue;
            }
            long best = cheapest(chains);
            popBaseShift[ChainPrefilter.chainBaseShift(best)].incrementAndGet();
            popColumns[Math.min(ChainPrefilter.chainColumns(best), popColumns.length - 1)]
                    .incrementAndGet();
            popBaseY[Math.min(ChainPrefilter.chainBaseY(best, 0), popBaseY.length - 1)]
                    .incrementAndGet();
        }
    }

    public static void printRanking() {
        long hits = hitsSeen.get();
        if (hits == 0) {
            return;
        }
        System.out.printf("%n=== which target properties predict a real find (%d finds) ===%n",
                hits);
        System.out.printf("finds whose chain the filter cannot see: %d (%.1f%%)"
                        + "   <- pure coverage loss%n",
                hitsMissedByFilter.get(), 100.0 * hitsMissedByFilter.get() / hits);
        System.out.printf("finds the soil filter would reject:     %d (%.1f%%)%n",
                hitsSoilRejected.get(), 100.0 * hitsSoilRejected.get() / hits);
        report("earlier placements assumed", hitBaseShift, popBaseShift);
        report("columns in the chain", hitColumns, popColumns);
        reportBanded("base y", hitBaseY, popBaseY);
    }

    private static void report(String what, AtomicLong[] hit, AtomicLong[] pop) {
        long hitAll = 0, popAll = 0;
        for (int i = 0; i < hit.length; i++) {
            hitAll += hit[i].get();
            popAll += pop[i].get();
        }
        if (hitAll == 0 || popAll == 0) {
            return;
        }
        System.out.printf("%n  %-30s %10s %10s %9s%n", what, "of finds", "of pop", "weight");
        for (int i = 0; i < hit.length; i++) {
            if (hit[i].get() == 0 && pop[i].get() == 0) {
                continue;
            }
            double h = (double) hit[i].get() / hitAll;
            double q = (double) pop[i].get() / popAll;
            System.out.printf("    %-28d %9.3f%% %9.3f%% %9s%n", i, 100 * h, 100 * q,
                    q > 0 ? String.format("%.2fx", h / q) : "-");
        }
    }

    private static void reportBanded(String what, AtomicLong[] hit, AtomicLong[] pop) {
        long hitAll = 0, popAll = 0;
        for (int i = 0; i < hit.length; i++) {
            hitAll += hit[i].get();
            popAll += pop[i].get();
        }
        if (hitAll == 0 || popAll == 0) {
            return;
        }
        System.out.printf("%n  %-30s %10s %10s %9s%n", what, "of finds", "of pop", "weight");
        for (int lo = 8; lo < 56; lo += 6) {
            long h = 0, q = 0;
            for (int i = lo; i < lo + 6 && i < hit.length; i++) {
                h += hit[i].get();
                q += pop[i].get();
            }
            if (h == 0 && q == 0) {
                continue;
            }
            double hf = (double) h / hitAll, qf = (double) q / popAll;
            System.out.printf("    %-28s %9.3f%% %9.3f%% %9s%n", lo + ".." + (lo + 5),
                    100 * hf, 100 * qf, qf > 0 ? String.format("%.2fx", hf / qf) : "-");
        }
    }

    public JavaRandom random() {
        return random;
    }
}
