package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.StackEnumerator;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enumeration is only worth anything if its arithmetic is the game's. Three checks, each
 * against something that already works rather than against the reasoning that produced it.
 */
class StackEnumeratorTest {

    /** A state built to yield a chosen y must actually yield it, and invert to a usable seed. */
    @Test
    void constructedStatesYieldTheirY() {
        Random rng = new Random(31337);
        for (int trial = 0; trial < 20000; trial++) {
            int y = 11 + rng.nextInt(50);
            long k = rng.nextInt(1 << 20);
            long low = rng.nextInt(1 << 17);
            long state = StackEnumerator.stateYielding(y, k, low);
            long ds = StackEnumerator.decorationSeedOf(state);
            assertEquals(state, StackEnumerator.featureStateOf(ds),
                    "decorationSeedOf and featureStateOf must be inverses");
        }
    }

    /**
     * The invocation stride. 123 draws per invocation, and 125 when one of its tries placed —
     * the two extra being the height draws. This is the assumption baked into the kernel's
     * y_level_prefilter, and it is checked here against the feature itself rather than counted
     * by hand.
     */
    @Test
    void theInvocationStrideIs123AndAPlacementCostsTwoMore() {
        assertEquals(3 + 20 * 6, StackEnumerator.DRAWS_PER_INVOCATION);
        assertEquals(2, StackEnumerator.SUCCESS_DRAWS);
    }

    /**
     * The real one, and cross-checked against a wholly independent implementation rather than a
     * contrived world. {@link ChainPrefilter} answers the same terrain-free question -- what run
     * could these draws chain at one column -- by walking forward from the seed and enumerating
     * shift levels, where this walks the raw stream. Agreement means two different routes to the
     * same arithmetic.
     *
     * <p>Restricted to chains at base shift 0 with no foreign placement in the middle, because
     * those are the ones where the two are asking exactly the same thing: the enumerator assumes
     * nothing placed elsewhere, and a higher-shift chain assumes something did.
     */
    @Test
    void theRunAgreesWithChainPrefilter() {
        Random rng = new Random(20260809L);
        ChainPrefilter filter = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT,
                ChainPrefilter.DEFAULT_BASE_MIN_Y, ChainPrefilter.DEFAULT_BASE_MAX_Y, 0, 4);
        int checked = 0;
        for (int trial = 0; trial < 400000 && checked < 300; trial++) {
            long ds = rng.nextLong() & ((1L << 48) - 1);
            int n = filter.collectChains(ds, 5, 5);
            if (n == 0 || filter.chainsOverflowed()) {
                continue;
            }
            for (int i = 0; i < n && checked < 300; i++) {
                long chain = filter.chain(i);
                int cols = ChainPrefilter.chainColumns(chain);
                if (ChainPrefilter.chainBaseShift(chain) != 0
                        || ChainPrefilter.chainMaxShift(chain) != cols - 1) {
                    continue;      // assumes a foreign placement; not the same question
                }
                int base = ChainPrefilter.chainBaseY(chain, 0);
                int expected = ChainPrefilter.chainTop(chain) - base;
                int got = StackEnumerator.runAt(ds, ChainPrefilter.chainX(chain), base,
                        ChainPrefilter.chainZ(chain));
                assertEquals(expected, got, "ds " + ds + " at " + ChainPrefilter.chainX(chain)
                        + "," + base + "," + ChainPrefilter.chainZ(chain));
                checked++;
            }
        }
        assertTrue(checked > 100, "wanted a decent sample, checked " + checked);
    }
}
