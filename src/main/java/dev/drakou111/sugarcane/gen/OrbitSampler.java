package dev.drakou111.sugarcane.gen;

/**
 * Which decoration seeds a target build tests, and in what order.
 *
 * <p>Seeds are sampled in <b>runs</b>. A run starts at a scattered seed and then walks the
 * LCG orbit one invocation at a time, because of this identity: for any decoration seed
 * there is another whose draw stream is the first one's <em>shifted by one invocation</em>.
 *
 * <pre>
 * shift(ds) = ((LCG^123((ds + C) ^ M)) ^ M) - C   (mod 2^48)
 *             C = featureIndex + 10000 * step,  M = 0x5DEECE66D
 * </pre>
 *
 * <p>Verified directly in {@code OrbitSamplerTest}: every draw of {@code shift(ds)} equals
 * a draw of {@code ds} one invocation later, and it chains.
 *
 * <p>That is what lets the GPU filter keep a ring of invocations and pay for one new
 * invocation per seed instead of {@code count} of them. Sampling has to be arranged for it
 * -- consecutive seeds must be orbit neighbours -- but only the kernel exploits it. The CPU
 * builder walks the same seeds in the same order and computes each one from scratch, which
 * is slower and produces the identical set. That equality is the only check that the two
 * paths have not drifted, so it is worth more than the speed the CPU gives up.
 *
 * <p>Runs rather than one long walk, so the sample stays scattered. A single orbit walk
 * would test 2^48 seeds in strict succession, and neighbouring streams share all but one
 * invocation -- fine for throughput, but their acceptances are not independent, and q is
 * measured off exactly this sample. {@link #RUN} seeds per run keeps the correlation inside
 * a short window and the runs themselves splitmix-scattered.
 */
public final class OrbitSampler {

    /**
     * Seeds per run. The ring costs {@code count} invocations to fill and one per seed
     * after, so a run of n averages {@code (count + n - 1) / n} invocations per seed:
     * 1.14 at n=64 against 10 unrolled, which is 8.8x of a possible 10x. Doubling to 128
     * buys 0.07x more and doubles the correlated window, so this is the flat part.
     */
    public static final int RUN = 64;

    private static final long MULT = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    /** 3 for the origin and y, then 20 tries of 6. Must match {@code ChainPrefilter}. */
    private static final int DRAWS_PER_INVOCATION = 3 + 20 * 6;

    /** LCG^123 collapsed to a single step: {@code state -> JUMP_A * state + JUMP_C}. */
    private static final long JUMP_A;
    private static final long JUMP_C;

    static {
        // (a, c)^n by repeated composition: composing (a1,c1) then (a2,c2) gives
        // (a1*a2, a2*c1 + c2).
        long a = 1;
        long c = 0;
        for (int i = 0; i < DRAWS_PER_INVOCATION; i++) {
            a = a * MULT;
            c = c * MULT + ADDEND;
        }
        JUMP_A = a & MASK;
        JUMP_C = c & MASK;
    }

    private OrbitSampler() {
    }

    private static long constant(int featureIndex, int step) {
        return featureIndex + 10000L * step;
    }

    /** The internal LCG state {@code java.util.Random} starts this seed's stream from. */
    private static long stateOf(long decorationSeed, int featureIndex, int step) {
        return ((decorationSeed + constant(featureIndex, step)) ^ MULT) & MASK;
    }

    /** The inverse: the decoration seed whose stream starts from this state. */
    private static long seedOf(long state, int featureIndex, int step) {
        return ((state ^ MULT) - constant(featureIndex, step)) & MASK;
    }

    /**
     * The seed whose draw stream is this one's, one invocation in.
     *
     * <p>Invocation n of the result is invocation n+1 of the input, exactly -- same origin,
     * same y, same twenty try offsets -- because all of those are derived from a block of
     * 123 consecutive draws and nothing about the block depends on where in the stream it
     * sits.
     */
    public static long shift(long decorationSeed, int featureIndex, int step) {
        long state = stateOf(decorationSeed, featureIndex, step);
        return seedOf((JUMP_A * state + JUMP_C) & MASK, featureIndex, step);
    }

    /** Where run {@code r} starts: splitmix64, the scattering the old sampler used. */
    public static long runStart(long r) {
        long z = r * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (z ^ (z >>> 31)) & MASK;
    }

    /**
     * The seed at global sample index {@code i}, so a build can resume from a count of
     * seeds rather than having to remember where in a run it stopped.
     *
     * <p>Walks from the run start, so it costs up to {@link #RUN} jumps. Callers that
     * iterate should use {@link #runStart} and {@link #shift} directly instead.
     */
    public static long sampleAt(long i, int featureIndex, int step) {
        long seed = runStart(Math.floorDiv(i, RUN));
        for (long k = Math.floorMod(i, RUN); k > 0; k--) {
            seed = shift(seed, featureIndex, step);
        }
        return seed;
    }
}
