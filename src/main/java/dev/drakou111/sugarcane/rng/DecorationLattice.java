package dev.drakou111.sugarcane.rng;

import java.math.BigInteger;

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
 *   <li>Of the reachable ones, the box holds {@code 2^43.7 / 2^44 ~= 0.8} solutions
 *       on average, so a target set of size |T| yields about |T|/20 candidate
 *       chunks per world seed.</li>
 * </ul>
 *
 * <p>The lattice depends only on the world seed, and the target set does not depend
 * on it at all, so the cost of building the set amortises over every world seed
 * tried afterwards. That is what makes the 1/q of FINDINGS 6ac reachable.
 *
 * <p>Reduction is done once per world seed; each target is then a Babai rounding
 * plus a small window, and every hit is checked against
 * {@link JavaRandom#setDecorationSeed} rather than trusted.
 */
public final class DecorationLattice {

    /** Chunks: the vanilla border is 29,999,984 blocks, so 16*cx must fit inside it. */
    public static final int BORDER_CHUNKS = 1_874_999;

    private static final int BITS = 44;
    private static final long N = 1L << BITS;
    private static final long MASK = N - 1;

    private final long worldSeed;
    /** {@code b * a^-1} and the reduced basis of {x + m*z = 0 mod 2^44}. */
    private final long m;
    private final long inverseA;
    private final long[] v1;
    private final long[] v2;

    public DecorationLattice(long worldSeed) {
        this.worldSeed = worldSeed;
        JavaRandom random = new JavaRandom();
        random.setSeed(worldSeed);
        long a = random.nextLong() | 1L;
        long b = random.nextLong() | 1L;

        BigInteger modulus = BigInteger.ONE.shiftLeft(BITS);
        this.inverseA = BigInteger.valueOf(a).mod(modulus).modInverse(modulus).longValue();
        this.m = mul(b, inverseA);

        // Lattice of offsets that keep the congruence: (N, 0) and (-m, 1).
        long[] p = {N, 0};
        long[] q = {-m, 1};
        long[][] reduced = gauss(p, q);
        this.v1 = reduced[0];
        this.v2 = reduced[1];
    }

    /** Multiplication mod 2^44. Overflow is harmless: long arithmetic is mod 2^64. */
    private static long mul(long x, long y) {
        return (x * y) & MASK;
    }

    /** Lagrange-Gauss reduction, exact. Components fit in a long, dot products do not. */
    private static long[][] gauss(long[] p, long[] q) {
        while (true) {
            if (norm(q).compareTo(norm(p)) < 0) {
                long[] t = p;
                p = q;
                q = t;
            }
            BigInteger dot = dot(p, q);
            BigInteger np = norm(p);
            // round(dot / np), for either sign
            BigInteger[] dr = dot.multiply(BigInteger.TWO).add(np).divideAndRemainder(np.shiftLeft(1));
            BigInteger k = dr[0];
            if (dr[1].signum() < 0) {
                k = k.subtract(BigInteger.ONE);
            }
            if (k.signum() == 0) {
                return new long[][]{p, q};
            }
            long kl = k.longValueExact();
            q = new long[]{q[0] - kl * p[0], q[1] - kl * p[1]};
        }
    }

    private static BigInteger dot(long[] p, long[] q) {
        return BigInteger.valueOf(p[0]).multiply(BigInteger.valueOf(q[0]))
                .add(BigInteger.valueOf(p[1]).multiply(BigInteger.valueOf(q[1])));
    }

    private static BigInteger norm(long[] p) {
        return dot(p, p);
    }

    /**
     * A chunk whose decoration seed matches {@code target} in its low 48 bits.
     *
     * @return {@code {cx, cz}}, or null if the border holds no such chunk
     */
    public int[] solve(long target) {
        long u = (target ^ worldSeed) & ((1L << 48) - 1);
        if ((u & 15L) != 0) {
            // Unreachable for this world seed whatever the coordinates: the low four
            // bits of a decoration seed are the world seed's own.
            return null;
        }
        long w = u >>> 4;
        long v = mul(w, inverseA);

        // Want (cx, cz) = (v, 0) + i*v1 + j*v2 inside the box, so the lattice point
        // has to sit near -(v, 0). Babai rounding, then a window for the rounding
        // error and for the second-shortest direction.
        double det = (double) v1[0] * v2[1] - (double) v1[1] * v2[0];
        double alpha = (-(double) v * v2[1]) / det;
        double beta = ((double) v * v1[1]) / det;
        long i0 = Math.round(alpha);
        long j0 = Math.round(beta);

        for (long di = -3; di <= 3; di++) {
            for (long dj = -3; dj <= 3; dj++) {
                long i = i0 + di, j = j0 + dj;
                long cx = v + i * v1[0] + j * v2[0];
                long cz = i * v1[1] + j * v2[1];
                if (cx < -BORDER_CHUNKS || cx > BORDER_CHUNKS
                        || cz < -BORDER_CHUNKS || cz > BORDER_CHUNKS) {
                    continue;
                }
                if (decorationSeedOf((int) cx, (int) cz) == (target & ((1L << 48) - 1))) {
                    return new int[]{(int) cx, (int) cz};
                }
            }
        }
        return null;
    }

    /** The low 48 bits of the real thing — what {@code setSeed} would actually use. */
    public long decorationSeedOf(int chunkX, int chunkZ) {
        return new JavaRandom().setDecorationSeed(worldSeed, chunkX * 16, chunkZ * 16)
                & ((1L << 48) - 1);
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
            long target = ((long) rng.nextInt() << 16 ^ rng.nextInt()) & ((1L << 48) - 1);
            if (((target ^ worldSeed) & 15L) == 0) {
                reachable++;
            }
            int[] chunk = lattice.solve(target);
            if (chunk == null) {
                continue;
            }
            solved++;
            if (lattice.decorationSeedOf(chunk[0], chunk[1]) != target) {
                mismatched++;
            }
        }
        System.out.printf("%d random 48-bit targets%n", trials);
        System.out.printf("  low-4-bit reachable : %d (%.4f, predicted 0.0625)%n",
                reachable, (double) reachable / trials);
        System.out.printf("  solved in the border: %d (%.4f of all, %.3f of reachable"
                        + " - predicted ~0.8)%n",
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
