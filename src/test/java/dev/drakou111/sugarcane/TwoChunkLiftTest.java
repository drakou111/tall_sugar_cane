package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.rng.DecorationLattice;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.rng.TwoChunkLift;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A solver that returns most of the answers looks perfect. Everything it hands back satisfies
 * the equation, the count looks sane, and nothing anywhere says that the one seed you wanted
 * was pruned three levels down. So the test that matters is the round trip: build the two
 * decoration seeds from a world seed nobody told the solver, and demand it back.
 *
 * <p>That is not hypothetical. The first working version missed 16 of 40 world seeds while
 * reporting zero bad answers, because {@code nextLong} sign-extends its low word and the
 * full-width check at the end was therefore subtly wrong.
 */
class TwoChunkLiftTest {

    private static final long MASK = (1L << 48) - 1;

    private static long deco(long worldSeed, int cx, int cz) {
        return new JavaRandom().setDecorationSeed(worldSeed, cx * 16, cz * 16) & MASK;
    }

    @Test
    void recoversTheWorldSeedFromTwoNeighbouringChunks() {
        int[][] offsets = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {2, -3}};
        JavaRandom rng = new JavaRandom(20260804L);
        int trips = 0;
        for (int[] step : offsets) {
            for (int t = 0; t < 4; t++) {
                long worldSeed = ((long) rng.nextInt() << 32) ^ rng.nextInt();
                int cx = rng.nextInt(2 * DecorationLattice.BORDER_CHUNKS)
                        - DecorationLattice.BORDER_CHUNKS;
                int cz = rng.nextInt(2 * DecorationLattice.BORDER_CHUNKS)
                        - DecorationLattice.BORDER_CHUNKS;
                long d1 = deco(worldSeed, cx, cz);
                long d2 = deco(worldSeed, cx + step[0], cz + step[1]);

                long[] seeds = TwoChunkLift.solve(d1, d2, step[0], step[1]);
                boolean found = false;
                for (long s : seeds) {
                    assertEquals(0L, TwoChunkLift.residual(s, d1, d2, step[0], step[1]),
                            "returned a seed that does not satisfy the equation");
                    assertEquals(s, s & MASK, "returned a seed with bits above 48");
                    if (s == (worldSeed & MASK)) {
                        found = true;
                    }
                }
                assertTrue(found, "lost the true world seed " + worldSeed + " at offset "
                        + step[0] + "," + step[1] + "; " + seeds.length + " returned");
                trips++;
            }
        }
        System.out.printf("TwoChunkLift: %d round trips, the true world seed recovered every "
                + "time%n", trips);
    }

    /**
     * Every returned seed must place both decoration seeds at chunks the right distance apart,
     * checked through {@code setDecorationSeed} itself rather than through the equation the
     * solver was built from — otherwise the check is a restatement of the algebra.
     */
    @Test
    void everySolutionReallyPlacesBothChunks() {
        long worldSeed = -7585781829663227268L;
        int cx = -24848077 >> 4, cz = 18720986 >> 4;
        long d1 = deco(worldSeed, cx, cz);
        long d2 = deco(worldSeed, cx + 1, cz);

        long[] seeds = TwoChunkLift.solve(d1, d2, 1, 0);
        assertNotEquals(0, seeds.length);
        int placed = 0;
        for (long s : seeds) {
            int[] chunk = new DecorationLattice(s).solve(d1);
            if (chunk == null) {
                continue;       // no chunk inside the border, which is the common case
            }
            assertEquals(d1, deco(s, chunk[0], chunk[1]));
            assertEquals(d2, deco(s, chunk[0] + 1, chunk[1]),
                    "the neighbour of the solved chunk does not have d2, so the seed is wrong");
            placed++;
        }
        System.out.printf("TwoChunkLift: %d seeds, %d put the pair inside the world border%n",
                seeds.length, placed);
    }

    /**
     * The twelve-bit lookahead is the one constant that decides whether lifting is even sound.
     * One too small and the frontier prunes on bits it cannot yet know, which loses seeds
     * silently; the solver would still look fine.
     */
    @Test
    void theLookaheadIsExactlyTwelve() {
        JavaRandom rng = new JavaRandom(99L);
        long d1 = rng.nextLong() & MASK, d2 = rng.nextLong() & MASK;

        for (int look : new int[]{TwoChunkLift.LOOKAHEAD - 1, TwoChunkLift.LOOKAHEAD}) {
            boolean stable = true;
            for (int k = 1; k <= 36 && stable; k++) {
                long kmask = (1L << k) - 1;
                int keep = Math.min(48, k + look);
                for (int t = 0; t < 2000; t++) {
                    long ws = rng.nextLong() & MASK;
                    long other = (ws & ((1L << keep) - 1))
                            | ((rng.nextLong() << keep) & MASK);
                    if ((TwoChunkLift.residual(ws, d1, d2, 1, 0) & kmask)
                            != (TwoChunkLift.residual(other, d1, d2, 1, 0) & kmask)) {
                        stable = false;
                        break;
                    }
                }
            }
            if (look == TwoChunkLift.LOOKAHEAD) {
                assertTrue(stable, "a lookahead of " + look + " is not enough after all");
            } else {
                assertTrue(!stable, "a lookahead of " + look + " would have done, so "
                        + "LOOKAHEAD is bigger than it needs to be");
            }
        }
        System.out.printf("TwoChunkLift: lookahead %d holds, %d does not%n",
                TwoChunkLift.LOOKAHEAD, TwoChunkLift.LOOKAHEAD - 1);
    }

    /** The free 16x: the world seed cancels from D1 ^ D2, so the low nibble must agree. */
    @Test
    void theLowNibbleRuleNeverRejectsARealPair() {
        JavaRandom rng = new JavaRandom(7L);
        for (int t = 0; t < 20000; t++) {
            long worldSeed = ((long) rng.nextInt() << 32) ^ rng.nextInt();
            int cx = rng.nextInt(200000) - 100000;
            int cz = rng.nextInt(200000) - 100000;
            for (int[] step : new int[][]{{1, 0}, {0, 1}, {3, -2}}) {
                long d1 = deco(worldSeed, cx, cz);
                long d2 = deco(worldSeed, cx + step[0], cz + step[1]);
                assertEquals(0L, (d1 ^ d2) & 15L,
                        "a genuine neighbouring pair failed the low-nibble prune");
            }
        }
    }
}
