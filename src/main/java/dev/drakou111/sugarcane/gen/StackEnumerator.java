package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.LcgSkip;

/**
 * Tall cane chains found by enumerating RNG states rather than sampling decoration seeds.
 *
 * <p>{@link ChainPrefilter} starts from a decoration seed and walks its 1,230 draws forward,
 * which costs the same whatever the seed turns out to hold. This inverts that. A chain's y comes
 * from {@code nextInt(126)}, so states yielding a wanted y can be <em>constructed</em>:
 *
 * <pre>
 *   upper31 = k * 126 + wantedY;   state = (upper31 &lt;&lt; 17) | low17
 * </pre>
 *
 * and the y is then free rather than tested. Most states die two draws later on the height check,
 * against 1,230 draws to reject a seed. The idea is a collaborator's, from a CUDA kernel; this is
 * the CPU transcription that the kernel can be held to.
 *
 * <p><b>Why it reaches further.</b> Enumerating states means starting mid-stream, so a hit can be
 * extended <em>backwards</em> as well as forwards — {@link LcgSkip} steps the LCG either way.
 * Walking forward from a seed can only ever find chains that start where you began looking.
 *
 * <p><b>What it assumes.</b> That a placement happens wherever a jitter lands on the target
 * column. That is terrain-free by design: it answers "could this seed's draws chain a tall
 * column", which is the same question {@code ChainPrefilter} answers, and terrain decides later.
 */
public final class StackEnumerator {

    /** {@code nextInt(2 * heightmap)} with the sea-level heightmap of 63. */
    public static final int Y_BOUND = 126;
    private static final int TRIES = 20;
    /** 3 for the origin (x, z, y) plus 20 tries of 6. */
    public static final int DRAWS_PER_INVOCATION = 123;
    /** A successful placement draws two more for its height. */
    public static final int SUCCESS_DRAWS = 2;
    private static final int INVOCATIONS = 10;
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long MASK = (1L << 48) - 1;
    /** {@code setFeatureSeed(ds, 5, 8)} adds this before the XOR: index*10000 + step*10000. */
    private static final long FEATURE_SALT = 80005L;

    private StackEnumerator() {
    }

    /** {@code next(31)} of the state one step on, which is what {@code nextInt} reads. */
    private static int next31(long state) {
        return (int) (((state * MULTIPLIER + 0xBL) & MASK) >>> 17);
    }

    /**
     * {@code nextInt(bound)}, ignoring only the rejection retry (2 in 2^31 at bound 126).
     *
     * <p>The power-of-two branch is not an optimisation, it is a different answer: Java returns
     * {@code (bound * next(31)) >> 31} there, the TOP bits, where the general path takes
     * {@code next(31) % bound}, the bottom ones. Using {@code %} for {@code nextInt(16)} reads a
     * completely different number, which is what made the first version of this return zero for
     * every seed. It matters for bounds 16, 2 and 1 here — and bound 2 occurs inside the height
     * draw whenever {@code nextInt(3)} yields 1, which is a third of all columns.
     */
    private static int nextIntFast(long state, int bound) {
        int bits = next31(state);
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) bits) >> 31);
        }
        return bits % bound;
    }

    /**
     * A state whose next {@code nextInt(126)} yields {@code y}.
     *
     * @param k     which of the {@code 2^31 / 126} such states, times the free low bits
     * @param low17 the 17 bits {@code next(31)} does not read, free to vary
     * @return the state <em>before</em> the y draw, so drawing y from it gives {@code y}
     */
    public static long stateYielding(int y, long k, long low17) {
        long upper31 = k * Y_BOUND + y;
        long after = (upper31 << 17) | (low17 & 0x1FFFFL);
        return LcgSkip.skip(after, -1);      // step back over the draw that produced it
    }

    /** The decoration seed whose feature stream begins at {@code state}. */
    public static long decorationSeedOf(long state) {
        return ((state ^ MULTIPLIER) - FEATURE_SALT) & MASK;
    }

    /** The raw state {@code setFeatureSeed} leaves for this decoration seed. */
    public static long featureStateOf(long decorationSeed) {
        return ((decorationSeed + FEATURE_SALT) ^ MULTIPLIER) & MASK;
    }

    /**
     * The run this seed's draws could build at one column, assuming every jitter that lands on it
     * places. The oracle the enumeration is checked against, and the same question
     * {@code ChainPrefilter.tallestPossible} answers by a different route.
     *
     * @param rootY the base the column starts at; it climbs as columns stack
     */
    public static int runAt(long decorationSeed, int rootX, int rootY, int rootZ) {
        long state = featureStateOf(decorationSeed);
        int total = 0;
        int y = rootY;
        for (int n = 0; n < INVOCATIONS; n++) {
            int baseX = nextIntFast(state, 16);
            state = LcgSkip.skip(state, 1);
            int baseZ = nextIntFast(state, 16);
            state = LcgSkip.skip(state, 1);
            int drawnY = nextIntFast(state, Y_BOUND);
            state = LcgSkip.skip(state, 1);
            for (int t = 0; t < TRIES; t++) {
                int px = baseX + nextIntFast(state, 5);
                state = LcgSkip.skip(state, 1);
                px -= nextIntFast(state, 5);
                state = LcgSkip.skip(state, 3);        // the two y-jitter draws, always zero
                int pz = baseZ + nextIntFast(state, 5);
                state = LcgSkip.skip(state, 1);
                pz -= nextIntFast(state, 5);
                state = LcgSkip.skip(state, 1);
                if (px == rootX && drawnY == y && pz == rootZ) {
                    int bound = nextIntFast(state, 3) + 1;
                    state = LcgSkip.skip(state, 1);
                    int h = 2 + nextIntFast(state, bound);
                    state = LcgSkip.skip(state, 1);
                    total += h;
                    y += h;
                }
            }
        }
        return total;
    }
}
