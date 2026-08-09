package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.rng.LcgSkip;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stepping the LCG backwards is the piece the whole enumeration search rests on, and it is easy
 * to get subtly wrong in a way that still looks plausible — the failure mode 6bb warned about for
 * the two-chunk solver, and 6bc's sign-extension bug in practice. So it is checked against
 * {@link JavaRandom} itself rather than against an argument.
 */
class LcgSkipTest {

    @Test
    void forwardSkipsMatchDrawingOneAtATime() {
        Random rng = new Random(99);
        for (int trial = 0; trial < 2000; trial++) {
            long seed = rng.nextLong() & ((1L << 48) - 1);
            int n = rng.nextInt(300);
            JavaRandom jr = new JavaRandom();
            jr.setSeed(seed ^ 0x5DEECE66DL);      // setSeed XORs, so undo it to set raw state
            for (int i = 0; i < n; i++) {
                jr.nextInt();
            }
            assertEquals(jr.getRawSeed(), LcgSkip.skip(seed, n),
                    "skip(" + n + ") must equal " + n + " draws");
        }
    }

    @Test
    void backwardSkipsUndoForwardOnes() {
        Random rng = new Random(1234);
        for (int trial = 0; trial < 5000; trial++) {
            long seed = rng.nextLong() & ((1L << 48) - 1);
            int n = rng.nextInt(500);
            long there = LcgSkip.skip(seed, n);
            assertEquals(seed, LcgSkip.skip(there, -n), "skip(-n) must undo skip(n)");
        }
    }

    /** The strides the enumeration actually uses: an invocation, and one with a placement in it. */
    @Test
    void theInvocationStridesRoundTrip() {
        Random rng = new Random(7);
        for (int trial = 0; trial < 2000; trial++) {
            long seed = rng.nextLong() & ((1L << 48) - 1);
            for (int stride : new int[] {123, 125, 2, 6, 120}) {
                assertEquals(seed, LcgSkip.skip(LcgSkip.skip(seed, stride), -stride),
                        "stride " + stride);
                assertEquals(seed, LcgSkip.skip(LcgSkip.skip(seed, -stride), stride),
                        "stride -" + stride);
            }
        }
    }
}
