package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The identity the whole ring optimisation rests on: {@code shift(ds)} is the seed whose
 * draw stream is {@code ds}'s, one invocation in.
 *
 * <p>If this ever stops holding, the GPU's ring silently reads the wrong invocations and
 * the target set quietly stops meaning anything, so it is worth pinning rather than
 * trusting the algebra.
 */
class OrbitSamplerTest {

    private static final int INDEX = 5;
    private static final int STEP = SugarCaneFeature.VEGETAL_DECORATION;
    private static final int DRAWS_PER_INVOCATION = 3 + 20 * 6;

    @Test
    void shiftMovesTheStreamOnByExactlyOneInvocation() {
        java.util.Random rnd = new java.util.Random(4242);
        for (int t = 0; t < 64; t++) {
            long ds = rnd.nextLong() & ((1L << 48) - 1);
            long shifted = OrbitSampler.shift(ds, INDEX, STEP);

            JavaRandom a = new JavaRandom();
            a.setFeatureSeed(ds, INDEX, STEP);
            int[] original = new int[DRAWS_PER_INVOCATION * 3];
            for (int i = 0; i < original.length; i++) {
                original[i] = a.nextInt();
            }

            JavaRandom b = new JavaRandom();
            b.setFeatureSeed(shifted, INDEX, STEP);
            for (int i = 0; i < original.length - DRAWS_PER_INVOCATION; i++) {
                assertEquals(original[i + DRAWS_PER_INVOCATION], b.nextInt(),
                        "seed " + ds + ": draw " + i + " of the shifted seed should be draw "
                                + (i + DRAWS_PER_INVOCATION) + " of the original");
            }
        }
    }

    /** Chaining has to work too, since a run applies it {@link OrbitSampler#RUN} times. */
    @Test
    void shiftChainsAcrossAWholeRun() {
        long ds = 123456789L;
        long walked = ds;
        for (int k = 0; k < OrbitSampler.RUN; k++) {
            walked = OrbitSampler.shift(walked, INDEX, STEP);
        }

        JavaRandom a = new JavaRandom();
        a.setFeatureSeed(ds, INDEX, STEP);
        for (int i = 0; i < OrbitSampler.RUN * DRAWS_PER_INVOCATION; i++) {
            a.nextInt();
        }
        JavaRandom b = new JavaRandom();
        b.setFeatureSeed(walked, INDEX, STEP);
        for (int i = 0; i < 256; i++) {
            assertEquals(a.nextInt(), b.nextInt(), "draw " + i + " after a full run of shifts");
        }
    }

    /** The resume path and the iterating path have to agree, or a resumed build skips. */
    @Test
    void sampleAtAgreesWithWalkingTheRun() {
        for (long run = 0; run < 3; run++) {
            long seed = OrbitSampler.runStart(run);
            for (int k = 0; k < OrbitSampler.RUN; k++) {
                assertEquals(seed, OrbitSampler.sampleAt(run * OrbitSampler.RUN + k, INDEX, STEP),
                        "run " + run + " offset " + k);
                seed = OrbitSampler.shift(seed, INDEX, STEP);
            }
        }
    }

    /**
     * The sample still has to spread. A walk that collapsed onto a short cycle, or runs
     * that collided, would quietly test the same few seeds forever and report a q that
     * means nothing.
     */
    @Test
    void theSampleDoesNotRepeatItself() {
        Set<Long> seen = new HashSet<>();
        for (long run = 0; run < 40; run++) {
            long seed = OrbitSampler.runStart(run);
            for (int k = 0; k < OrbitSampler.RUN; k++) {
                seen.add(seed);
                seed = OrbitSampler.shift(seed, INDEX, STEP);
            }
        }
        assertEquals(40 * OrbitSampler.RUN, seen.size(), "sampled seeds should all be distinct");

        // And they should not cluster: the top bits of a 48-bit space ought to be spread
        // over all 16 buckets, which is also what the search's low-4-bit bucketing needs.
        int[] buckets = new int[16];
        for (long s : seen) {
            buckets[(int) (s >>> 44)]++;
        }
        for (int i = 0; i < 16; i++) {
            assertTrue(buckets[i] > 0, "high-nibble bucket " + i + " never sampled");
        }
    }
}
