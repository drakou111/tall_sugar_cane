package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.DecorationLattice;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real 18-tall, found by someone else, at seed 4351644413651977918.
 *
 * <p>It reads as 14 in raster order and 18 once chunk B decorates after chunk A. That is not a
 * detail: {@code searchRegion} decorates x-major, so B follows A only for four of the eight
 * neighbours, and this find's neighbour is at dx = -1 — in the half that was invisible. Half of
 * every cross-chunk candidate this project has ever generated was unverifiable for the same
 * reason (FINDINGS 6by).
 *
 * <p>So this is the ground truth the whole pipeline is pinned to. If it ever reads 14 again, the
 * ordering fix has regressed and the search is blind to half its own directions.
 */
class DecorationOrderTest {

    private static final long SEED = 4351644413651977918L;
    private static final int X = -3323517;
    private static final int Z = 18778246;

    @Test
    void theEighteenTallNeedsChunkBToDecorateSecond() {
        int cx = X >> 4;
        int cz = Z >> 4;

        RegionSearcher.Stats stats = new RegionSearcher.Stats();
        RegionSearcher.Worker w = new RegionSearcher.Worker(999, false, 0, stats, 0);
        w.prepare(SEED);
        w.searchOneChunk(cx, cz);

        int top = 0;
        for (int y = 1; y < 200; y++) {
            if (w.world.getBlock(X, y, Z) == Blocks.SUGAR_CANE) {
                top = y;
            }
        }
        int base = top;
        while (w.world.getBlock(X, base - 1, Z) == Blocks.SUGAR_CANE) {
            base--;
        }
        assertEquals(14, top - base + 1,
                "raster order builds only chunk A's share; if this changes the fixture moved");
        assertEquals(18, base, "the stack stands on dirt at y=18");

        // The neighbour to the west, decorated after rather than before.
        ArrayWorld ordered = w.world.copy();
        ordered.setDecoratingChunk(cx - 1, cz);
        SugarCaneFeature.place(ordered,
                new DecorationLattice(SEED).decorationSeedOf(cx - 1, cz),
                5, SugarCaneFeature.COUNT_DEFAULT, cx - 1, cz);

        int top2 = 0;
        for (int y = 1; y < 200; y++) {
            if (ordered.getBlock(X, y, Z) == Blocks.SUGAR_CANE) {
                top2 = y;
            }
        }
        assertEquals(18, top2 - base + 1, "with the right order it is the 18 that was reported");
        assertTrue(ordered.caneRunFromOneChunk(X, base, Z) < top2 - base + 1,
                "and no single chunk built it, which is what makes it cross-chunk");
    }
}
