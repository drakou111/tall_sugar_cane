package dev.drakou111.sugarcane.rng;

/**
 * Solves for the world seed from two neighbouring chunks' decoration seeds, by Hensel lifting.
 *
 * <p>{@link DecorationLattice} answers "given a world seed, which chunk has this decoration
 * seed?". That is the right question for one chunk, and the wrong one for two: a cross-chunk
 * stack needs a chain ending in chunk A and another beginning in the neighbour B, and asking
 * a random world seed to place <em>both</em> is hopeless. This class turns the question round
 * and solves for the world seed that places both at once.
 *
 * <h2>The position cancels</h2>
 *
 * <p>With {@code a}, {@code b} the odd multipliers of {@code setDecorationSeed}:
 *
 * <pre>
 *   D1 = (16cx*a + 16cz*b) ^ ws
 *   D2 = (16(cx+dx)*a + 16(cz+dz)*b) ^ ws
 * </pre>
 *
 * <p>so {@code (D2 ^ ws) - (D1 ^ ws) == 16*(dx*a + dz*b) (mod 2^48)}. The chunk coordinate is
 * gone. One equation, one unknown, and the unknown is the thing we actually want. The
 * coordinate comes back afterwards from {@link DecorationLattice#solve}, which is also where
 * the world border finally gets to reject most of it.
 *
 * <h2>Why it lifts</h2>
 *
 * <p>The left side is XOR and subtraction, so its low k bits need only {@code ws}'s low k bits.
 * The right side needs {@code a} (and {@code b}) low bits, which live in {@code next(32)}
 * results, which are state bits 16 and up; an LCG is lower-triangular, so state mod 2^n needs
 * seed mod 2^n. Reading {@code a} mod 2^j therefore costs {@code ws} mod 2^(16+j), and the
 * {@code *16} gives back four: <b>checking the equation mod 2^k needs {@code ws} mod
 * 2^(k+12)</b> — a twelve-bit lookahead, verified rather than assumed, and the reason
 * exactly twelve bits have to be guessed before the walk can start.
 *
 * <p>So: take the low four bits free (see {@link #solve}), guess the next twelve blind, then
 * walk up one bit at a time, each new bit of {@code ws} exposing one more bit of the equation
 * twelve places behind it. A bit that fits neither way kills the branch. The frontier stays
 * about as wide as it started rather than doubling, which is the whole trick — a few hundred
 * thousand candidates visited instead of 2^48, and a handful of milliseconds per pair on one
 * thread.
 *
 * <p>It is a branchy tree walk with data-dependent survival, so it is poor GPU work. That is
 * the right way round: the GPU is already saturated building target sets, and this runs once
 * per candidate pair rather than once per seed.
 *
 * <p>The top twelve bits of the equation are never reachable by lifting — bit 47 would want
 * {@code ws} mod 2^60. They are checked directly on the survivors instead, which is cheap
 * because there are only tens of them.
 */
public final class TwoChunkLift {

    private static final long MULT = 0x5DEECE66DL;
    private static final long ADD = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    /** Verified in {@code TwoChunkLiftTest}: 11 fails, 12 holds, so there is no slack here. */
    public static final int LOOKAHEAD = 12;

    /** Where the bit-by-bit walk starts: four free bits plus {@code LOOKAHEAD} guessed. */
    private static final int BLIND_BITS = LOOKAHEAD + 4;

    private TwoChunkLift() {
    }

    /**
     * {@code 16*(dx*a + dz*b)} mod 2^48 — the right-hand side.
     *
     * <p>{@code nextLong} adds a <em>sign-extended</em> low word rather than OR-ing it in. That
     * only touches bits 32 and up, so the lifting never notices, but the final full-width check
     * does: getting it wrong throws away the true seed about half the time while every seed it
     * does return still satisfies the (wrong) equation, which looks exactly like success.
     */
    public static long rightHandSide(long ws, int dx, int dz) {
        long s = (ws ^ MULT) & MASK;
        s = (s * MULT + ADD) & MASK;
        long hi = s >>> 16;
        s = (s * MULT + ADD) & MASK;
        long a = ((hi << 32) + (int) (s >>> 16)) | 1L;
        long total = dx * a;
        if (dz != 0) {
            s = (s * MULT + ADD) & MASK;
            hi = s >>> 16;
            s = (s * MULT + ADD) & MASK;
            long b = ((hi << 32) + (int) (s >>> 16)) | 1L;
            total += dz * b;
        }
        return (16L * total) & MASK;
    }

    /** Zero exactly when {@code ws} explains both decoration seeds. */
    public static long residual(long ws, long d1, long d2, int dx, int dz) {
        return ((((d2 ^ ws) & MASK) - ((d1 ^ ws) & MASK)) - rightHandSide(ws, dx, dz)) & MASK;
    }

    /**
     * Every low-48-bit world seed placing {@code d1} at some chunk and {@code d2} at the chunk
     * {@code (dx, dz)} away from it.
     *
     * <p>Only the low 48 bits are recoverable, and only the low 48 bits exist as far as
     * decoration seeds are concerned: {@code new Random(ws)} discards the top sixteen, and the
     * XOR at the end is masked by the {@code setSeed} that follows. A handful of seeds come
     * back; the coordinate has not been checked, so feed each to
     * {@link DecorationLattice#solve} to find whether any chunk in the border has it.
     */
    public static long[] solve(long d1, long d2, int dx, int dz) {
        if (((d1 ^ d2) & 15L) != 0) {
            // 16*(dx*a + dz*b) has at least four trailing zeros whatever the seed, and the
            // world seed cancels out of D1 ^ D2, so the low nibble must agree. Free 16x.
            return new long[0];
        }

        // The low four bits are not guessed at all: D1 = 16*(...) ^ ws and the multiple of
        // sixteen contributes nothing below bit four, so ws agrees with D1 there. That is a
        // 16x cut to the blind prefix, and — more usefully — it is the same condition
        // DecorationLattice needs to reach D1 from a world seed, so every seed returned here
        // is one it can actually place instead of fifteen in sixteen being unusable.
        long[] cur = new long[1 << LOOKAHEAD];
        for (int i = 0; i < cur.length; i++) {
            cur[i] = ((long) i << 4) | (d1 & 15L);
        }
        int n = cur.length;
        long[] next = new long[2 * n];

        for (int bit = BLIND_BITS; bit < 48; bit++) {
            long mask = (1L << (bit - LOOKAHEAD + 1)) - 1;
            int out = 0;
            for (int i = 0; i < n; i++) {
                long low = cur[i];
                if ((residual(low, d1, d2, dx, dz) & mask) == 0) {
                    next[out++] = low;
                }
                long high = low | (1L << bit);
                if ((residual(high, d1, d2, dx, dz) & mask) == 0) {
                    next[out++] = high;
                }
            }
            long[] spent = cur;
            cur = next;
            n = out;
            next = spent.length >= 2 * n ? spent : new long[2 * n];
            if (n == 0) {
                return new long[0];
            }
        }

        int out = 0;
        for (int i = 0; i < n; i++) {
            if (residual(cur[i], d1, d2, dx, dz) == 0) {
                cur[out++] = cur[i];
            }
        }
        return java.util.Arrays.copyOf(cur, out);
    }

    public static void main(String[] args) {
        long worldSeed = args.length > 0 ? Long.parseLong(args[0]) : -7585781829663227268L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -24848077 >> 4;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : 18720986 >> 4;

        DecorationLattice lattice = new DecorationLattice(worldSeed);
        System.out.printf("world seed %d, chunk %d,%d (the confirmed 8-tall)%n",
                worldSeed, cx, cz);

        for (int[] step : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {1, 1}}) {
            long d1 = lattice.decorationSeedOf(cx, cz);
            long d2 = lattice.decorationSeedOf(cx + step[0], cz + step[1]);
            long start = System.nanoTime();
            long[] seeds = solve(d1, d2, step[0], step[1]);
            double ms = (System.nanoTime() - start) / 1e6;

            boolean found = false;
            int inBorder = 0;
            for (long s : seeds) {
                if (s == (worldSeed & MASK)) {
                    found = true;
                }
                if (new DecorationLattice(s).solve(d1) != null) {
                    inBorder++;
                }
            }
            System.out.printf("  neighbour %+d,%+d : %3d seeds in %5.1f ms, true seed %s, "
                            + "%d also place the chunk inside the border%n",
                    step[0], step[1], seeds.length, ms,
                    found ? "recovered" : "MISSED", inBorder);
        }
    }
}
