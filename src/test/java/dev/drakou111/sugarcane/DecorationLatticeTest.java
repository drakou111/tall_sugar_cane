package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.rng.DecorationLattice;
import dev.drakou111.sugarcane.rng.JavaRandom;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The lattice has to be both exact and <em>complete</em>. Exactness is easy to see —
 * every answer is checked against {@code setDecorationSeed}. Completeness is the
 * dangerous half: a solver that quietly misses reachable chunks discards real finds
 * and shows nothing but a slightly low candidate rate, which is the same failure mode
 * FINDINGS 6u found in the first cane prefilter.
 *
 * <p>So the congruence is also solved the slow honest way, one cz at a time across the
 * whole border, and the two are required to agree.
 */
class DecorationLatticeTest {

    private static final int BITS = 44;
    private static final long MASK = (1L << BITS) - 1;

    /** {@code cx + m*cz = v (mod 2^44)}, by walking every legal cz. */
    private static int[] exhaustive(long worldSeed, long target) {
        long u = (target ^ worldSeed) & ((1L << 48) - 1);
        if ((u & 15L) != 0) {
            return null;
        }
        JavaRandom random = new JavaRandom();
        random.setSeed(worldSeed);
        long a = random.nextLong() | 1L;
        long b = random.nextLong() | 1L;
        BigInteger modulus = BigInteger.ONE.shiftLeft(BITS);
        long inverseA = BigInteger.valueOf(a).mod(modulus).modInverse(modulus).longValue();
        long m = (b * inverseA) & MASK;
        long v = ((u >>> 4) * inverseA) & MASK;

        int border = DecorationLattice.BORDER_CHUNKS;
        for (int cz = -border; cz <= border; cz++) {
            long r = (v - m * cz) & MASK;
            if (r <= border) {
                return new int[]{(int) r, cz};
            }
            long negative = r - (1L << BITS);
            if (negative >= -border) {
                return new int[]{(int) negative, cz};
            }
        }
        return null;
    }

    @Test
    void agreesWithExhaustiveSearch() {
        long[] worldSeeds = {1L, -7585781829663227268L, 1500050556L, 123456789L, -42L};
        int checked = 0, reachable = 0, solvedBoth = 0;
        for (long worldSeed : worldSeeds) {
            DecorationLattice lattice = new DecorationLattice(worldSeed);
            JavaRandom rng = new JavaRandom(worldSeed ^ 0x5DEECE66DL);
            for (int i = 0; i < 12; i++) {
                // Force the low four bits to match, or nearly every target is
                // unreachable and the test measures nothing.
                long target = (((long) rng.nextInt() << 16 ^ rng.nextInt())
                        & ~15L | (worldSeed & 15L)) & ((1L << 48) - 1);
                checked++;
                int[] slow = exhaustive(worldSeed, target);
                int[] fast = lattice.solve(target);
                if (slow == null) {
                    // Nothing in the border: the fast path must not invent one.
                    assertNull(fast, "solve() returned a chunk where none exists, seed "
                            + worldSeed + " target " + target);
                    continue;
                }
                reachable++;
                assertNotNull(fast, "solve() missed a reachable chunk, seed " + worldSeed
                        + " target " + target + ", exhaustive found "
                        + slow[0] + "," + slow[1]);
                solvedBoth++;
                // They may legitimately pick different chunks; both must be right.
                assertEquals(target, lattice.decorationSeedOf(fast[0], fast[1]),
                        "solve() returned a chunk with the wrong decoration seed");
                assertEquals(target, lattice.decorationSeedOf(slow[0], slow[1]),
                        "the exhaustive search itself is wrong");
            }
        }
        System.out.printf("DecorationLattice: %d targets, %d reachable, %d agreed%n",
                checked, reachable, solvedBoth);
    }

    @Test
    void rejectsTargetsWhoseLowBitsCannotMatch() {
        long worldSeed = 1L;
        DecorationLattice lattice = new DecorationLattice(worldSeed);
        // Every achievable 16*(cx*a + cz*b) is a multiple of 16, so a target that
        // differs from the world seed in its low four bits is unreachable at any
        // coordinate. Not a limit of the solver.
        for (int low = 1; low < 16; low++) {
            long target = (0x123456789ABCL & ~15L) | ((worldSeed & 15L) ^ low);
            assertNull(lattice.solve(target), "low bits " + low + " should be unreachable");
        }
    }

    @Test
    void recoversTheConfirmedEightTallFind() {
        long worldSeed = -7585781829663227268L;
        DecorationLattice lattice = new DecorationLattice(worldSeed);
        int cx = -24848077 >> 4, cz = 18720986 >> 4;
        long decorationSeed = lattice.decorationSeedOf(cx, cz);
        int[] found = lattice.solve(decorationSeed);
        assertNotNull(found);
        assertEquals(decorationSeed, lattice.decorationSeedOf(found[0], found[1]));
    }
}
