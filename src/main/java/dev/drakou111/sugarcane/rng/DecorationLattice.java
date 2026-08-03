package dev.drakou111.sugarcane.rng;

/**
 * Given a world seed and a decoration seed you want, finds the chunk inside the
 * world border that has it — by lattice reduction, not by search.
 *
 * <p>This is the half of a reversal search that FINDINGS 6w never considered.
 * Reversing the cane RNG produces decoration seeds; on its own that is useless,
 * because a chunk's decoration seed is whatever it is. But
 * {@code setDecorationSeed} is affine in the chunk origin:
 *
 * <pre>
 * populationSeed = (16*cx * a + 16*cz * b) ^ worldSeed        a, b = nextLong()|1 after setSeed(worldSeed)
 * </pre>
 *
 * so for a fixed world seed the reachable decoration seeds form a 2D lattice mod
 * 2^48, and the border allows |cx|, |cz| <= 1,874,999 — about 2^43.7 chunks to
 * choose from. Two consequences:
 *
 * <ul>
 *   <li>Only 1 target in 16 is reachable at all. Every achievable
 *       {@code 16*(cx*a + cz*b)} is a multiple of 16, so the low four bits of the
 *       target must equal the low four bits of the world seed. Nothing to do with
 *       the lattice — it is the {@code *16} in the block coordinate.</li>
 *   <li>Of the reachable ones the box holds 0.8 on average, but {@link #solve}
 *       returns one and P(at least one) is below that for a skewed basis: measured
 *       0.604 per reachable target.</li>
 * </ul>
 *
 * <p>The lattice depends only on the world seed, and the target set does not depend
 * on it at all, so the cost of building the set amortises over every world seed
 * tried afterwards. That is what makes the 1/q of FINDINGS 6ac reachable.
 *
 * <p>Reduction happens once per world seed; each target is then a Babai rounding plus
 * a small window, and every hit is re-checked against {@link #decorationSeedOf}
 * before being returned — so a rounding slip or a window miss can only ever lose a
 * chunk, never invent one.
 *
 * <p>The 128-bit arithmetic is hand-rolled rather than {@link java.math.BigInteger}
 * so that constructing one of these allocates nothing: 8.17 us down to 1.15 us. That
 * is 7.1x on a step which runs once per world seed and is therefore invisible beside
 * the ~2,000 candidates that follow it — kept because it is strictly better code, not
 * because it showed up in a measurement.
 */
public final class DecorationLattice {

    /** Chunks: the vanilla border is 29,999,984 blocks, so 16*cx must fit inside it. */
    public static final int BORDER_CHUNKS = 1_874_999;

    private static final long LOW48_MASK = (1L << 48) - 1;
    private static final int BITS = 44;
    private static final long N = 1L << BITS;
    private static final long MASK = N - 1;

    private final long worldSeed;
    /** The odd multipliers from setDecorationSeed(worldSeed, x, z). */
    private final long a;
    private final long b;
    private final long inverseA;
    /** Reduced basis of {(x, z) : x + m*z = 0 mod 2^44}, m = b * a^-1. */
    private final long[] v1;
    private final long[] v2;

    public DecorationLattice(long worldSeed) {
        this.worldSeed = worldSeed;
        JavaRandom random = new JavaRandom();
        random.setSeed(worldSeed);
        this.a = random.nextLong() | 1L;
        this.b = random.nextLong() | 1L;

        this.inverseA = inverseOddMod2Pow(a & MASK);
        long m = mul(b, inverseA);

        long[][] reduced = gauss(new long[]{N, 0}, new long[]{-m, 1});
        this.v1 = reduced[0];
        this.v2 = reduced[1];
    }

    /** Multiplication mod 2^44. Overflow is harmless: long arithmetic is mod 2^64. */
    private static long mul(long x, long y) {
        return (x * y) & MASK;
    }

    /**
     * Inverse of an odd value mod 2^44, by Newton iteration. Starting from
     * {@code x = odd} is valid because {@code odd * odd == 1 (mod 2)}, and each step
     * doubles the count of correct bits, so six of them reach 64 — more than the 44
     * needed.
     */
    private static long inverseOddMod2Pow(long odd) {
        long x = odd & MASK;
        for (int i = 0; i < 6; i++) {
            x = (x * (2L - odd * x)) & MASK;
        }
        return x;
    }

    /** Lagrange-Gauss reduction, exact. Components fit in a long, dot products do not. */
    private static long[][] gauss(long[] p, long[] q) {
        Int128 normP = new Int128();
        Int128 normQ = new Int128();
        Int128 dot = new Int128();
        Int128 scratchA = new Int128();
        Int128 scratchB = new Int128();
        Int128 scratchC = new Int128();
        Int128 scratchD = new Int128();
        Int128 scratchE = new Int128();
        while (true) {
            norm(normQ, q);
            norm(normP, p);
            if (normQ.comparePositive(normP) < 0) {
                long[] t = p;
                p = q;
                q = t;
                Int128 tn = normP;
                normP = normQ;
                normQ = tn;
            }
            dot(dot, p, q);
            long k = roundQuotient(dot, normP, scratchA, scratchB, scratchC,
                    scratchD, scratchE);
            if (k == 0) {
                return new long[][]{p, q};
            }
            q = new long[]{q[0] - k * p[0], q[1] - k * p[1]};
        }
    }

    /** {@code round(dot / norm)}, half up, matching the BigInteger version it replaced. */
    private static long roundQuotient(Int128 dot, Int128 norm, Int128 absDot,
            Int128 numerator, Int128 twiceNorm, Int128 remainder, Int128 shifted) {
        if (dot.isZero()) {
            return 0L;
        }
        twiceNorm.setShiftLeft(norm, 1);
        if (!dot.isNegative()) {
            numerator.setShiftLeft(dot, 1);
            numerator.addInPlace(norm);
            return divideFloorPositive(numerator, twiceNorm, remainder, shifted);
        }
        // floor((norm - 2d) / 2norm) == -floor((2d + norm - 1) / 2norm), the usual
        // -ceil identity, so the division below only ever sees positive operands.
        absDot.setNegated(dot);
        numerator.setShiftLeft(absDot, 1);
        numerator.addInPlace(norm);
        numerator.subtractOne();
        return -divideFloorPositive(numerator, twiceNorm, remainder, shifted);
    }

    private static long divideFloorPositive(Int128 numerator, Int128 denominator,
            Int128 remainder, Int128 shifted) {
        if (denominator.isZero()) {
            throw new ArithmeticException("division by zero");
        }
        if (numerator.comparePositive(denominator) < 0) {
            return 0L;
        }
        int shift = numerator.bitLengthPositive() - denominator.bitLengthPositive();
        // Components are bounded by 2^44, and the shortest basis vector has norm >= 2
        // because m is odd, so neither (1,0) nor (0,1) is ever in the lattice. That
        // bounds the quotient and keeps this shift under about 45. Past 63 both the
        // sub-shift and the 1L << s below would be wrong, and being wrong here means a
        // bad reduction and silently missed chunks — so say so rather than continue.
        if (shift < 0 || shift > 62) {
            throw new IllegalStateException("unexpected quotient width " + shift);
        }
        long quotient = 0L;
        remainder.set(numerator);
        for (int s = shift; s >= 0; s--) {
            shifted.setShiftLeft(denominator, s);
            if (shifted.comparePositive(remainder) <= 0) {
                remainder.subtractInPlace(shifted);
                quotient |= 1L << s;
            }
        }
        return quotient;
    }

    private static void dot(Int128 out, long[] p, long[] q) {
        out.setProduct(p[0], q[0]);
        out.addProduct(p[1], q[1]);
    }

    private static void norm(Int128 out, long[] p) {
        dot(out, p, p);
    }

    /**
     * A chunk whose decoration seed matches {@code target} in its low 48 bits.
     *
     * @return {@code {cx, cz}}, or null if the border holds no such chunk
     */
    public int[] solve(long target) {
        long targetLow48 = target & LOW48_MASK;
        long u = (targetLow48 ^ worldSeed) & LOW48_MASK;
        if ((u & 15L) != 0) {
            // Unreachable for this world seed whatever the coordinates: the low four
            // bits of a decoration seed are the world seed's own.
            return null;
        }
        long v = mul(u >>> 4, inverseA);

        // Want (cx, cz) = (v, 0) + i*v1 + j*v2 inside the box, so the lattice point
        // has to sit near -(v, 0). Babai rounding, then a window for the rounding
        // error and for the second-shortest direction.
        double det = (double) v1[0] * v2[1] - (double) v1[1] * v2[0];
        long i0 = Math.round((-(double) v * v2[1]) / det);
        long j0 = Math.round(((double) v * v1[1]) / det);

        for (long di = -3; di <= 3; di++) {
            for (long dj = -3; dj <= 3; dj++) {
                long i = i0 + di, j = j0 + dj;
                long cx = v + i * v1[0] + j * v2[0];
                long cz = i * v1[1] + j * v2[1];
                if (cx < -BORDER_CHUNKS || cx > BORDER_CHUNKS
                        || cz < -BORDER_CHUNKS || cz > BORDER_CHUNKS) {
                    continue;
                }
                if (decorationSeedOf((int) cx, (int) cz) == targetLow48) {
                    return new int[]{(int) cx, (int) cz};
                }
            }
        }
        return null;
    }

    /**
     * The low 48 bits of the population seed — what {@code setSeed} would actually
     * use. Inlined rather than routed through {@link JavaRandom#setDecorationSeed},
     * which would re-run the LCG for {@code a} and {@code b} on every call. The two
     * are held to agreement by {@code DecorationLatticeTest}, since this being wrong
     * would make {@link #solve}'s own re-check agree with itself.
     */
    public long decorationSeedOf(int chunkX, int chunkZ) {
        return ((chunkX * 16L) * a + (chunkZ * 16L) * b ^ worldSeed) & LOW48_MASK;
    }

    /**
     * Just enough signed 128-bit arithmetic for the reduction, mutable so the loop
     * allocates nothing. Everything here is a norm or dot product of vectors whose
     * components fit in 45 bits, so values stay well inside 2^91 and the sign bit is
     * only ever set by a genuinely negative dot product.
     */
    private static final class Int128 {

        private long hi;
        private long lo;

        boolean isZero() {
            return hi == 0 && lo == 0;
        }

        boolean isNegative() {
            return hi < 0;
        }

        void set(Int128 other) {
            hi = other.hi;
            lo = other.lo;
        }

        void setProduct(long x, long y) {
            hi = Math.multiplyHigh(x, y);
            lo = x * y;
        }

        void addProduct(long x, long y) {
            long productHi = Math.multiplyHigh(x, y);
            long productLo = x * y;
            long sumLo = lo + productLo;
            long carry = Long.compareUnsigned(sumLo, lo) < 0 ? 1L : 0L;
            hi += productHi + carry;
            lo = sumLo;
        }

        void addInPlace(Int128 other) {
            long sumLo = lo + other.lo;
            long carry = Long.compareUnsigned(sumLo, lo) < 0 ? 1L : 0L;
            hi += other.hi + carry;
            lo = sumLo;
        }

        void subtractInPlace(Int128 other) {
            long borrow = Long.compareUnsigned(lo, other.lo) < 0 ? 1L : 0L;
            hi -= other.hi + borrow;
            lo -= other.lo;
        }

        void setNegated(Int128 other) {
            hi = ~other.hi + (other.lo == 0 ? 1L : 0L);
            lo = -other.lo;
        }

        void subtractOne() {
            if (lo == 0) {
                hi--;
            }
            lo--;
        }

        /** {@code bits == 0} has to be special-cased: {@code x >>> 64} is a no-op. */
        void setShiftLeft(Int128 other, int bits) {
            if (bits == 0) {
                set(other);
            } else if (bits < 64) {
                hi = (other.hi << bits) | (other.lo >>> (64 - bits));
                lo = other.lo << bits;
            } else {
                hi = other.lo << (bits - 64);
                lo = 0L;
            }
        }

        /** Only valid for non-negative values, which is all this class compares. */
        int comparePositive(Int128 other) {
            int hiCmp = Long.compare(hi, other.hi);
            return hiCmp != 0 ? hiCmp : Long.compareUnsigned(lo, other.lo);
        }

        int bitLengthPositive() {
            if (hi != 0) {
                return 128 - Long.numberOfLeadingZeros(hi);
            }
            return lo == 0 ? 0 : 64 - Long.numberOfLeadingZeros(lo);
        }
    }

    public static void main(String[] args) {
        long worldSeed = args.length > 0 ? Long.parseLong(args[0]) : -7585781829663227268L;
        int trials = args.length > 1 ? Integer.parseInt(args[1]) : 200_000;

        DecorationLattice lattice = new DecorationLattice(worldSeed);
        System.out.printf("world seed %d%nreduced basis (%d, %d) and (%d, %d)%n",
                worldSeed, lattice.v1[0], lattice.v1[1], lattice.v2[0], lattice.v2[1]);

        // Targets drawn at random over the whole 48-bit space: the honest question,
        // since a reversal produces the seeds it produces.
        int reachable = 0, solved = 0, mismatched = 0;
        JavaRandom rng = new JavaRandom(12345L);
        for (int i = 0; i < trials; i++) {
            long target = ((long) rng.nextInt() << 16 ^ rng.nextInt()) & LOW48_MASK;
            if (((target ^ worldSeed) & 15L) == 0) {
                reachable++;
            }
            int[] chunk = lattice.solve(target);
            if (chunk == null) {
                continue;
            }
            solved++;
            // Against the real function, not against decorationSeedOf, so that this
            // line is an independent check rather than a tautology.
            if ((new JavaRandom().setDecorationSeed(worldSeed, chunk[0] * 16, chunk[1] * 16)
                    & LOW48_MASK) != target) {
                mismatched++;
            }
        }
        System.out.printf("%d random 48-bit targets%n", trials);
        System.out.printf("  low-4-bit reachable : %d (%.4f, predicted 0.0625)%n",
                reachable, (double) reachable / trials);
        System.out.printf("  solved in the border: %d (%.4f of all, %.3f of reachable"
                        + " - measured ~0.60)%n",
                solved, (double) solved / trials, (double) solved / Math.max(1, reachable));
        System.out.printf("  verified against setDecorationSeed: %d/%d, mismatches %d%n",
                solved - mismatched, solved, mismatched);

        // The end-to-end check that matters: take the decoration seed of the chunk
        // holding the confirmed 8-tall find and ask the lattice for it back.
        int findCx = -24848077 >> 4, findCz = 18720986 >> 4;
        long findSeed = lattice.decorationSeedOf(findCx, findCz);
        int[] again = lattice.solve(findSeed);
        System.out.printf("%nconfirmed find is chunk %d,%d, decoration seed %d%n",
                findCx, findCz, findSeed);
        System.out.printf("  lattice asked for that seed returns %s -> %s%n",
                again == null ? "nothing" : "chunk " + again[0] + "," + again[1],
                again != null && lattice.decorationSeedOf(again[0], again[1]) == findSeed
                        ? "same decoration seed, so the same cane RNG" : "WRONG");
    }
}
