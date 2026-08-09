package dev.drakou111.sugarcane.rng;

/**
 * Stepping {@code java.util.Random}'s LCG any number of places, forwards or backwards.
 *
 * <p>The whole point of the state-enumeration search is that you can start in the middle of a
 * chunk's draw stream and walk outwards: forwards to the invocations that stack on top, backwards
 * to the ones that built the column you landed on. Walking backwards is what the sampled approach
 * cannot do at all — from a decoration seed you only ever go forwards.
 *
 * <p>{@code s' = s*a + c} composes: applying it twice gives {@code s*a^2 + c*(a+1)}, so any skip
 * is one multiply-add once its {@code (a, c)} pair is known, and the pairs come from binary
 * exponentiation. Backwards is the same with {@code a^-1} — the multiplier is odd, so it is
 * invertible mod 2^48, and the inverse of the whole step is {@code s = (s' - c) * a^-1}.
 */
public final class LcgSkip {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    /** {@code MULTIPLIER^-1} mod 2^48, by Newton iteration on an odd number. */
    private static final long INVERSE = inverse(MULTIPLIER);

    private LcgSkip() {
    }

    private static long inverse(long a) {
        long x = 1;
        for (int i = 0; i < 6; i++) {          // doubles correct bits each round: 1,2,4,...,64
            x *= 2 - a * x;
        }
        return x & MASK;
    }

    /**
     * The raw seed {@code n} steps on. Negative {@code n} steps back.
     *
     * <p>Note this is the <em>raw</em> state, the thing {@code nextInt} advances and reads from —
     * not a seed as handed to {@code setSeed}, which XORs by the multiplier first.
     */
    public static long skip(long seed, long n) {
        long mul = 1;
        long add = 0;
        long baseMul = n >= 0 ? MULTIPLIER : INVERSE;
        long baseAdd = n >= 0 ? ADDEND : (-ADDEND * INVERSE);
        long steps = Math.abs(n);
        while (steps != 0) {
            if ((steps & 1) != 0) {
                mul *= baseMul;
                add = add * baseMul + baseAdd;
            }
            baseAdd = baseAdd * baseMul + baseAdd;
            baseMul *= baseMul;
            steps >>>= 1;
        }
        return (seed * mul + add) & MASK;
    }
}
