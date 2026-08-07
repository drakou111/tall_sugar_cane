package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.JavaRandom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cross-chunk find, re-derived from nothing but the world seed.
 *
 * <p>{@code crossfind} matches two chains on a chunk-relative key, solves for the world seed and
 * then checks terrain. Every step after the match reuses the numbers the match produced, so the
 * whole thing agrees with itself by construction — including if the join rule is off by one, or
 * if the lift returns a seed that satisfies the equation but not {@code setDecorationSeed}.
 *
 * <p>So this starts from the reported world seed and chunk, asks {@code setDecorationSeed} for
 * both decoration seeds itself, and requires that the two chains meet: chunk A's chain has to
 * stop at exactly the block chunk B's chain starts at, in world coordinates.
 */
class CrossFindTest {

    private static final int OCEAN_INDEX = 5;

    /** A height 13, found by {@code crossfind 2000000 12 10} in under six seconds. */
    private static final long WORLD_SEED = 193737350798884L;
    private static final int CHUNK_AX = 1565331, CHUNK_AZ = -145530;
    private static final int BLOCK_X = 25045312, BLOCK_Z = -2328468;
    private static final int BASE_Y = 20, JOIN_Y = 28, HEIGHT = 13;

    private static long deco(int cx, int cz) {
        return new JavaRandom().setDecorationSeed(WORLD_SEED, cx * 16, cz * 16)
                & ((1L << 48) - 1);
    }

    /** The chain at this relative column with this base, or null. */
    private static long chainAt(ChainPrefilter filter, long decorationSeed, int min,
            int relX, int relZ, int y, boolean matchBase) {
        int n = filter.collectChains(decorationSeed, OCEAN_INDEX, min);
        if (filter.chainsOverflowed()) {
            return 0L;
        }
        for (int i = 0; i < n; i++) {
            long chain = filter.chain(i);
            if (ChainPrefilter.chainX(chain) != relX || ChainPrefilter.chainZ(chain) != relZ) {
                continue;
            }
            int at = matchBase ? ChainPrefilter.chainBaseY(chain, 0)
                    : ChainPrefilter.chainTop(chain);
            if (at == y) {
                return chain;
            }
        }
        return 0L;
    }

    @Test
    void theReportedCrossChunkStackReallyJoins() {
        int cxb = CHUNK_AX + 1, czb = CHUNK_AZ;

        // Chunk A: a chain standing on the sea floor, so the depth-banded filter.
        long chainA = chainAt(new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT),
                deco(CHUNK_AX, CHUNK_AZ), 5,
                BLOCK_X - CHUNK_AX * 16, BLOCK_Z - CHUNK_AZ * 16, JOIN_Y, false);
        assertTrue(chainA != 0L, "chunk A has no chain ending at the reported join");
        assertEquals(BASE_Y, ChainPrefilter.chainBaseY(chainA, 0),
                "chunk A's chain does not start where the find says it does");

        // Chunk B: standing on A's cane, so no depth band -- the join is at y=28, far above
        // the y<=35 the banded filter would allow only by accident.
        long chainB = chainAt(new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT, 11, 64, 3, 4),
                deco(cxb, czb), 5,
                BLOCK_X - cxb * 16, BLOCK_Z - czb * 16, JOIN_Y, true);
        assertTrue(chainB != 0L, "chunk B has no chain beginning at the reported join");

        int runA = ChainPrefilter.chainTop(chainA) - ChainPrefilter.chainBaseY(chainA, 0);
        int runB = ChainPrefilter.chainTop(chainB) - ChainPrefilter.chainBaseY(chainB, 0);
        assertEquals(HEIGHT, runA + runB, "the two runs do not add up to the reported height");

        // The join itself: half-open tops mean contiguous, not overlapping.
        assertEquals(ChainPrefilter.chainTop(chainA), ChainPrefilter.chainBaseY(chainB, 0),
                "chunk B does not start exactly where chunk A stops");
        // Both chains name the same world column from their own chunk's frame, which is the
        // whole join rule: A's relative x is B's plus sixteen, and both are inside the -4..19
        // a placement can reach.
        assertEquals(ChainPrefilter.chainX(chainA), ChainPrefilter.chainX(chainB) + 16);
        assertEquals(ChainPrefilter.chainZ(chainA), ChainPrefilter.chainZ(chainB));
        assertEquals(BLOCK_X, cxb * 16 + ChainPrefilter.chainX(chainB));
        assertEquals(BLOCK_Z, czb * 16 + ChainPrefilter.chainZ(chainB));
        assertTrue(ChainPrefilter.chainX(chainB) >= -4 && ChainPrefilter.chainX(chainB) <= 19,
                "chunk B could not have placed cane there at all");

        System.out.printf("CrossFind: seed %d, chunk %d,%d gives %d from y=%d, neighbour adds "
                        + "%d from y=%d -- %d tall at %d,%d%n",
                WORLD_SEED, CHUNK_AX, CHUNK_AZ, runA, BASE_Y, runB, JOIN_Y, runA + runB,
                BLOCK_X, BLOCK_Z);
    }

    /**
     * The height is the point. A single chunk reaching 13 has rate 2e-9; this pair was one of
     * 43 found in 5.6 seconds, which is the whole argument for the command existing.
     */
    @Test
    void neitherChunkCouldHaveDoneItAlone() {
        assertTrue(HEIGHT > 8, "a cross-chunk find below 9 proves nothing about the method");
        long dsA = deco(CHUNK_AX, CHUNK_AZ);
        ChainPrefilter filter = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT);
        int n = filter.collectChains(dsA, OCEAN_INDEX, HEIGHT);
        assertEquals(0, n, "chunk A reaches the full height by itself, so this is not a "
                + "cross-chunk find at all");
    }

    /**
     * Flags must not shift the positional arguments. The GUI always passes --dx and --dz, so
     * before this every GUI run died with NumberFormatException on "--dx=1" being read as minA,
     * while every command line that left the flags off worked -- which is exactly why the smoke
     * tests missed it.
     */
    @Test
    void flagsDoNotDisplaceThePositionalArguments() {
        String[] fromTheGui = {"10000000000", "12", "20", "--dx=1", "--dz=0"};
        String[] positional = CrossFind.positional(fromTheGui);
        assertEquals(3, positional.length,
                "flags leaked into the positionals: " + String.join(" ", positional));
        assertEquals("10000000000", positional[0]);
        assertEquals("12", positional[1]);
        assertEquals("20", positional[2]);

        // With minA and minB given as well, they must still land in slots 3 and 4.
        String[] withSplit = {"1000", "4", "20", "8", "12", "--dx=1", "--dz=0", "--water-probe"};
        String[] both = CrossFind.positional(withSplit);
        assertEquals(5, both.length);
        assertEquals("8", both[3]);
        assertEquals("12", both[4]);
    }

    /** {@code bestSplit} has to prefer the cliff-aligned split, which is the tuning that matters. */
    @Test
    void twentySplitsTwelveAndEight() {
        int[] split = CrossChunk.bestSplit(20);
        assertNotNull(split);
        assertEquals(20, split[0] + split[1]);
        assertTrue((split[0] == 12 && split[1] == 8) || (split[0] == 8 && split[1] == 12),
                "20 should split at a column boundary, got " + split[0] + "+" + split[1]);
    }

    /**
     * Omitting the neighbour means all eight, and naming one means only that one.
     *
     * <p>The GUI sent {@code --dx=1 --dz=0} on every run. While a join table served a single
     * direction that restated the default; once one table served all eight it silently pinned
     * the search to an eighth of its reach. Nothing would have failed -- the run just finds
     * 3.5x less -- so this pins the rule rather than the GUI.
     */
    @Test
    void omittingTheNeighbourSweepsAllEight() {
        assertEquals(8, CrossFind.directions(false, 1, 0).length);
        assertEquals(1, CrossFind.directions(true, 1, 0).length);
        assertArrayEquals(new int[] {-1, 1}, CrossFind.directions(true, -1, 1)[0]);

        // Every one is a real neighbour, and no duplicates -- a repeat would double-count
        // joins and inflate the funnel without finding anything.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int[] d : CrossFind.directions(false, 0, 0)) {
            assertTrue(Math.abs(d[0]) <= 1 && Math.abs(d[1]) <= 1, "not a neighbour");
            assertTrue(d[0] != 0 || d[1] != 0, "0,0 is one chunk, not two");
            assertTrue(seen.add(d[0] + "," + d[1]), "duplicate direction " + d[0] + "," + d[1]);
        }
        assertEquals(8, seen.size());
    }
}
