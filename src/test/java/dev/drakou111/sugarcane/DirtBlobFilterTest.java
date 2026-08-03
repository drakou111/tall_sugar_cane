package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.DirtBlobFilter;
import dev.drakou111.sugarcane.gen.OreBlob;
import dev.drakou111.sugarcane.rng.JavaRandom;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DirtBlobFilter} against the real {@link OreBlob}.
 *
 * <p>The filter reads blob geometry out of a flat array of {@code next()} values at
 * computed offsets, which is exactly the kind of reasoning that is wrong in silence:
 * a bad offset makes it reject real dirt, the search quietly stops finding things, and
 * no rate looks obviously off. So the test drives the real feature and requires the
 * filter to accept <em>every</em> block it actually placed.
 *
 * <p>The interesting case is the mixed one. {@code place()} only draws its 33 radii
 * when the blob's box reaches the ocean floor, so a floor height that lets some blobs
 * place and not others is what exercises the {@code 6k + 66m} offset enumeration. A
 * floor that accepts everything (or nothing) only tests one path through the stream.
 */
class DirtBlobFilterTest {

    /** Records what the real feature placed, and lets the caller pick the floor. */
    private static final class Recorder implements OreBlob.Target {
        final List<int[]> dirt = new ArrayList<>();
        final int floor;

        Recorder(int floor) {
            this.floor = floor;
        }

        @Override
        public boolean isNaturalStone(int x, int y, int z) {
            return true;        // the filter ignores this too
        }

        @Override
        public void setDirt(int x, int y, int z) {
            dirt.add(new int[]{x, y, z});
        }

        @Override
        public int oceanFloorHeight(int x, int z) {
            return floor;
        }
    }

    /** Runs the real ORE_DIRT pass for chunk 0,0 and returns the dirt it placed. */
    private static List<int[]> realDirt(long decorationSeed, int floor) {
        Recorder recorder = new Recorder(floor);
        OreBlob blob = new OreBlob(recorder, OreBlob.DIRT_SIZE);
        JavaRandom random = new JavaRandom();
        random.setFeatureSeed(decorationSeed, DirtBlobFilter.ORE_INDEX, DirtBlobFilter.ORE_STEP);
        for (int i = 0; i < OreBlob.DIRT_COUNT; i++) {
            int x = random.nextInt(16);
            int z = random.nextInt(16);
            int y = random.nextInt(256);
            blob.place(random, x, y, z);
        }
        return recorder.dirt;
    }

    private static long spread(long i) {
        long z = i * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (z ^ (z >>> 31)) & ((1L << 48) - 1);
    }

    /**
     * Every block the real pass placed must be accepted. Three floor heights: one
     * where every blob places, one where about a quarter do, and one in between.
     */
    @Test
    void acceptsEveryBlockTheRealFeaturePlaces() {
        DirtBlobFilter filter = new DirtBlobFilter();
        int checked = 0;
        for (int floor : new int[]{255, 120, 60}) {
            for (long i = 0; i < 300; i++) {
                long ds = spread(i);
                for (int[] p : realDirt(ds, floor)) {
                    // Only blocks inside the chunk are the filter's business; the
                    // feature reaches outside it and a neighbour would own those.
                    if (p[0] < 0 || p[0] > 15 || p[2] < 0 || p[2] > 15) {
                        continue;
                    }
                    checked++;
                    assertTrue(filter.couldSupply(ds, p[0], p[1], p[2]),
                            "filter rejected dirt the real feature placed at "
                                    + p[0] + "," + p[1] + "," + p[2]
                                    + " (decorationSeed " + ds + ", floor " + floor + ")");
                }
            }
        }
        System.out.printf("DirtBlobFilter: accepted %d real dirt blocks, 0 rejected%n", checked);
        assertTrue(checked > 5000, "not enough coverage, only checked " + checked);
    }

    /** It has to actually reject things, or it is not a filter. */
    @Test
    void rejectsBlocksNoBlobCouldReach() {
        DirtBlobFilter filter = new DirtBlobFilter();
        int accepted = 0, total = 0;
        for (long i = 0; i < 2000; i++) {
            long ds = spread(i);
            // A fixed block, so this measures the per-block acceptance rate.
            total++;
            if (filter.couldSupply(ds, 8, 22, 8)) {
                accepted++;
            }
        }
        double rate = (double) accepted / total;
        System.out.printf("DirtBlobFilter: one fixed block accepted for %.2f%% of seeds%n",
                100.0 * rate);
        assertTrue(rate < 0.30, "acceptance " + rate + " is too high to be worth anything");
        assertTrue(rate > 0.001, "acceptance " + rate + " is suspiciously low, likely a bug");
    }

    /**
     * The confirmed 8-tall stands on dirt at y=20, chunk-relative (3,10) of chunk
     * -1553005,1170061 — read off a real 1.16.1 server. The filter must keep it.
     */
    @Test
    void keepsTheConfirmedEightTall() {
        assertTrue(new DirtBlobFilter().couldSupply(72846194777308L, 3, 20, 10),
                "the filter would have discarded the only real 8-tall");
    }

    /**
     * How much coverage the neighbour-blob blind spot costs: of all the dirt that can
     * land at a block, how much of it came from a blob centred in another chunk? That
     * fraction of finds is lost, and it is the difference between the filter's raw
     * selectivity and what it is actually worth.
     */
    @Test
    void measuresTheNeighbourBlobLoss() {
        long own = 0, foreign = 0;
        for (long i = 0; i < 4000; i++) {
            long ds = spread(i);
            for (int[] p : realDirt(ds, 255)) {
                // Placed by chunk 0,0's pass. Which chunk does the block land in?
                if (p[0] >= 0 && p[0] <= 15 && p[2] >= 0 && p[2] <= 15) {
                    own++;
                } else {
                    foreign++;
                }
            }
        }
        double leak = (double) foreign / (own + foreign);
        System.out.printf("DirtBlobFilter: %.1f%% of placed dirt lands outside its own chunk "
                        + "(%d of %d) -> the same share of real soil comes from neighbours, "
                        + "so raw selectivity is discounted by that much%n",
                100.0 * leak, foreign, own + foreign);
        assertEquals(true, leak > 0.05 && leak < 0.60,
                "leak fraction " + leak + " outside the plausible range");
    }
}
