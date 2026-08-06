package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures <b>q</b>: the fraction of decoration seeds whose cane RNG could build a
 * run of a given height <em>somewhere</em> in the chunk, with no terrain at all.
 *
 * <p>Why this number and not {@code Main}'s: q is exactly the speedup available to
 * a reversal search. Reversal enumerates the seeds that pass this test and only
 * then generates terrain, so it generates 1/q times fewer chunks than a brute force
 * that pays for terrain first. {@code StackPrefilter} measures the same quantity for
 * a mere stack (19%, FINDINGS 6u) which is why 6w concludes reversal is worthless —
 * that figure is for height 5.
 *
 * <p>The test is a necessary condition, deliberately loose, so q is an upper bound
 * and 1/q a lower bound on the speedup:
 * <ul>
 *   <li>every {@code nextInt} is one LCG step and the offsets are fixed (3 per
 *       invocation, then 6 per try), so the stream can be laid out flat. The only
 *       terrain-dependent part is that a <em>successful</em> placement draws two
 *       more for the height, shifting everything after it — enumerated here as an
 *       independent per-column shift rather than tracked;</li>
 *   <li>the y-spread is 0, so all 20 tries of one invocation share a y and the
 *       columns of a chain must come from different invocations;</li>
 *   <li>a column stands on the previous one, so its y is pinned to
 *       {@code y + height} exactly, height being the two draws after the try that
 *       succeeded.</li>
 * </ul>
 *
 * <p>Only upward chains are counted. {@code ColumnPlacer} overwrites upward
 * unconditionally, so a column placed lower and later can merge with one above it
 * and make a longer run — but it needs its own soil and its own water, which is a
 * second terrain coincidence, and the confirmed 8-tall at -24848077,21,18720986 is
 * a plain 4+4 upward chain.
 */
public final class ChainPrefilter {

    private static final int TRIES = 20;
    private static final int DRAWS_PER_TRY = 6;
    private static final int DRAWS_PER_INVOCATION = 3 + TRIES * DRAWS_PER_TRY;
    private static final int SUCCESS_DRAWS = 2;

    /** The heightmap over open ocean: y is drawn from nextInt(2 * 63). */
    private static final int DOUBLED_HEIGHTMAP = 63 * 2;

    /** Cane stands between the lava layer and just above the water surface. */
    private static final int Y_FLOOR = 11;
    private static final int Y_CEIL = 64;

    /**
     * Default band for the <em>base</em> of a chain, from the measured depth of real
     * stackable spots: 2,847 of them over 3.18M ocean chunks sit at soil y 8..51 with
     * a mean of 23.3, and 87.8% of them between 12 and 34 (FINDINGS 6ac).
     *
     * <p>Narrowing the band is a straight trade of coverage for selectivity, and it
     * pays because q scales with the band's width while the finds lost scale with the
     * spot mass outside it. Cost per find goes as width/mass, which is 2.06x better
     * here than accepting the whole column, and is flat across bands from 15 to 23
     * wide — so this is the wide, forgiving end of the plateau rather than the peak.
     * Upper columns of a chain are not restricted; only where it starts.
     */
    public static final int DEFAULT_BASE_MIN_Y = 13;
    public static final int DEFAULT_BASE_MAX_Y = 35;

    /**
     * Shifts enumerated per column, in draws. Each earlier successful placement costs two.
     *
     * <p>Five values are defined but {@link #DEFAULT_SHIFT_LEVELS} are used, because how
     * many levels exist is exactly how many columns a chain can have: shifts must strictly
     * increase up a chain, so C columns need C distinct levels. Four levels cap a chain at
     * four columns and therefore at height 16, and asking for 17 with four levels does not
     * fail -- it silently accepts nothing, and a target build runs forever finding none.
     *
     * <p>Levels are not free. Every one adds a whole shift-view of every invocation to the
     * candidate set, so q rises and the search slows, for chains longer than almost any
     * real find needs. 6ah measured four-column chains at 0 of 256 real finds. So the count
     * is raised only when the height demands it, by {@link #shiftLevelsFor}.
     */
    private static final int[] SHIFTS = {0, 2, 4, 6, 8};

    /** What every search up to height 16 uses, and what every existing target set was
     *  built with. Changing this would silently redefine those sets. */
    public static final int DEFAULT_SHIFT_LEVELS = 4;

    /**
     * How many shift levels a height needs: enough for the columns it takes to reach it.
     *
     * <p>A pure function of {@code minHeight}, deliberately. It could have been a
     * {@code TargetCache} header field, but the header already records the height, and
     * deriving it means every set built before five levels existed stays valid rather than
     * being invalidated by a format bump nobody needed.
     */
    public static int shiftLevelsFor(int minHeight) {
        return Math.max(DEFAULT_SHIFT_LEVELS, minimumColumns(minHeight));
    }

    private final int count;
    private final int baseMinY;
    private final int baseMaxY;
    /**
     * Ranking, measured off real finds rather than guessed (FINDINGS 6ah).
     *
     * <p>{@code maxBaseShift} caps how many earlier placements the chain's first column
     * may assume. Shift 0 holds 94.1% of finds but only 60.7% of the accepted set,
     * because a prior success in the same chunk is rare and three of them rarer still.
     *
     * <p>{@code maxColumns} caps the chain length. Two-column chains hold 89.8% of finds
     * against 77.7% of the set, and four-column chains produced none at all in 256.
     */
    private final int maxBaseShift;
    private final int maxAnyShift;
    private final int maxColumns;
    /**
     * How many <em>foreign</em> placements the chain may assume between its own columns,
     * summed over the chain. A step from shift {@code a} to shift {@code b} spends
     * {@code b - a - 1} of the budget, since one of those levels is the chain's own
     * column placing.
     *
     * <p>{@link Integer#MAX_VALUE} — the default — is the plain ascending rule. Zero is
     * the contiguous-window rule: consecutive placements, nothing else interleaved.
     *
     * <p>Zero is <b>not sound</b>. The confirmed 5-tall on seed 1500050556 runs shift
     * 0 -> 2, because its chunk grew an unrelated column between the two that make the
     * stack, and a budget of zero throws it away. The confirmed 8-tall runs 0 -> 1 and
     * survives either way. A budget of one keeps both. FINDINGS 6ao has what each buys.
     */
    private int maxSlack = Integer.MAX_VALUE;
    /** How many of {@link #SHIFTS} this filter reads. See the field's note on cost. */
    private final int shiftLevels;
    private final int capacity;
    private final int[] draws;

    /** Flattened candidate columns for one seed: x, z, y, height, invocation. */
    private final int[] cx;
    private final int[] cz;
    private final int[] cy;
    private final int[] ch;
    private final int[] cn;
    /** Which SHIFTS index each candidate was read at, i.e. how many earlier
     *  placements it assumes. 0 assumes none, 3 assumes three. */
    private final int[] cs;
    private int candidates;

    /**
     * Candidates are emitted in groups: one per (invocation, shift), all twenty tries
     * of which share a y, because the y-spread is 0. A chain's next column has to sit
     * at exactly {@code y + height}, so the search only ever wants groups at one y --
     * and indexing them by it turns the inner scan from every candidate into the
     * handful that could possibly match.
     *
     * <p>Worth roughly 50x on that loop: 40 groups spread over the 54 legal y values
     * means about 0.74 groups per y, so ~15 candidates examined instead of ~800. The
     * buckets are appended to rather than pushed onto, which keeps groups in ascending
     * order and makes the iteration order identical to the flat scan it replaces --
     * so the chains come out in the same order, not merely the same set.
     */
    private final int[] groupStart;
    private final int[] groupEnd;
    private final int[] groupN;
    private final int[] groupNext;
    private final int[] yHead;
    private final int[] yTail;
    private int groupCount;

    /**
     * Chains found by {@link #collectChains}, packed by {@link #pack}. More than this
     * many and the caller is told to accept without testing, which keeps the filter
     * sound at the cost of a little selectivity.
     */
    public static final int MAX_CHAINS = 32;

    private final long[] chains = new long[MAX_CHAINS];
    private int chainCount;
    private boolean chainOverflow;
    private final int[] path = new int[8];
    private final int[] packY = new int[8];
    /** 576 bits: which (x, z) an invocation's earlier tries already claimed. */
    private final long[] seenXZ = new long[9];
    private int wantedHeight;

    public ChainPrefilter(int count) {
        this(count, DEFAULT_BASE_MIN_Y, DEFAULT_BASE_MAX_Y, 3, 4);
    }

    /** The ranked filter: shift 0 only, and no more columns than the height needs. */
    public static ChainPrefilter ranked(int count, int minHeight) {
        // maxAnyShift deliberately left unrestricted. See its javadoc: gating it at 0
        // rejects both confirmed finds.
        return new ChainPrefilter(count, DEFAULT_BASE_MIN_Y, DEFAULT_BASE_MAX_Y, 0,
                minimumColumns(minHeight), shiftLevelsFor(minHeight) - 1,
                shiftLevelsFor(minHeight));
    }

    /** A column is at most 4 tall, so this is the fewest that can reach the height. */
    public static int minimumColumns(int minHeight) {
        return Math.max(1, (minHeight + 3) / 4);
    }

    /**
     * @param baseMinY lowest y a chain may start at, inclusive
     * @param baseMaxY highest y a chain may start at, inclusive. Pass the full
     *                 {@code 11..64} to measure q without the depth band.
     */
    public ChainPrefilter(int count, int baseMinY, int baseMaxY) {
        this(count, baseMinY, baseMaxY, 3, 4);
    }

    public ChainPrefilter(int count, int baseMinY, int baseMaxY, int maxBaseShift,
            int maxColumns) {
        this(count, baseMinY, baseMaxY, maxBaseShift, maxColumns,
                DEFAULT_SHIFT_LEVELS - 1);
    }

    /** With the shift levels a height needs, which is what a tall search wants. */
    public static ChainPrefilter forHeight(int count, int baseMinY, int baseMaxY,
            int maxBaseShift, int maxColumns, int minHeight) {
        int levels = shiftLevelsFor(minHeight);
        return new ChainPrefilter(count, baseMinY, baseMaxY, maxBaseShift, maxColumns,
                levels - 1, levels);
    }

    /**
     * @param maxAnyShift cap on the shift of <em>every</em> column, not just the first.
     *                    {@code maxBaseShift} only ever gated the base, so a chain could
     *                    assume no placement before it and still assume one interleaved
     *                    between its own columns — which is the same implausible thing in
     *                    a less visible place. A chain needing a success elsewhere in the
     *                    chunk between two of its columns is as unlikely as one needing it
     *                    beforehand: cane columns run about 1.1e-3 per chunk, and 6ah
     *                    measured base shift 1 at 0.11x the population rate against shift
     *                    0's 1.55x.
     *
     *                    <p><b>Read this with the monotonic-shift rule in mind.</b> A
     *                    chain's own placements consume the shift levels: column two of
     *                    any chain sits at shift >= 1 because column one placing is itself
     *                    a placement. So a cap of 0 forbids stacking outright rather than
     *                    forbidding interleaved foreign placements, which is why it
     *                    rejects every real find. That is not evidence that foreign
     *                    placements are needed.
     *
     *                    <p>Of the two confirmed finds, the 8-tall's shift 1 is entirely
     *                    its own first column and assumes nothing foreign; only the
     *                    5-tall's shift 2 implies one unrelated placement, which is the
     *                    neighbouring column its chunk also grew. An earlier note here
     *                    claimed both needed a foreign placement, and that was a misreading
     *                    of what the shift counts.
     *
     */
    public ChainPrefilter(int count, int baseMinY, int baseMaxY, int maxBaseShift,
            int maxColumns, int maxAnyShift) {
        this(count, baseMinY, baseMaxY, maxBaseShift, maxColumns, maxAnyShift,
                DEFAULT_SHIFT_LEVELS);
    }

    public ChainPrefilter(int count, int baseMinY, int baseMaxY, int maxBaseShift,
            int maxColumns, int maxAnyShift, int shiftLevels) {
        if (shiftLevels < 1 || shiftLevels > SHIFTS.length) {
            throw new IllegalArgumentException("shiftLevels must be 1.." + SHIFTS.length
                    + ", got " + shiftLevels);
        }
        this.shiftLevels = shiftLevels;
        this.maxAnyShift = maxAnyShift;
        this.count = count;
        this.baseMinY = baseMinY;
        this.baseMaxY = baseMaxY;
        this.maxBaseShift = maxBaseShift;
        this.maxColumns = maxColumns;
        this.capacity = count * DRAWS_PER_INVOCATION + SHIFTS[shiftLevels - 1] + 16;
        this.draws = new int[capacity];
        int max = count * shiftLevels * TRIES;
        this.cx = new int[max];
        this.cz = new int[max];
        this.cy = new int[max];
        this.ch = new int[max];
        this.cn = new int[max];
        this.cs = new int[max];
        int groups = count * shiftLevels;
        this.groupStart = new int[groups];
        this.groupEnd = new int[groups];
        this.groupN = new int[groups];
        this.groupNext = new int[groups];
        this.yHead = new int[Y_CEIL - Y_FLOOR + 1];
        this.yTail = new int[Y_CEIL - Y_FLOOR + 1];
    }

    /**
     * Budget of foreign placements between the chain's own columns. Lossy below 1;
     * see {@link #maxSlack}.
     *
     * @return this, so it can be set at the construction site
     */
    public ChainPrefilter maxSlack(int slack) {
        this.maxSlack = slack;
        return this;
    }

    /** @return the tallest run this seed's draws could chain together, ignoring terrain */
    public int tallestPossible(long decorationSeed, int featureIndex) {
        buildCandidates(decorationSeed, featureIndex);
        int best = 0;
        for (int i = 0; i < candidates; i++) {
            // Only the start of a chain is restricted: to the band where real spots are,
            // and to chains that assume few enough earlier placements to be plausible.
            if (cy[i] < baseMinY || cy[i] > baseMaxY || cs[i] > maxBaseShift) {
                continue;
            }
            best = Math.max(best, chainFrom(i, 0, maxSlack));
        }
        return best;
    }

    /** Every column any try could place, over all four shift assumptions. */
    private void buildCandidates(long decorationSeed, int featureIndex) {
        JavaRandom random = new JavaRandom();
        random.setFeatureSeed(decorationSeed, featureIndex, SugarCaneFeature.VEGETAL_DECORATION);
        for (int i = 0; i < capacity; i++) {
            draws[i] = random.nextInt();
        }

        candidates = 0;
        groupCount = 0;
        java.util.Arrays.fill(yHead, -1);
        for (int n = 0; n < count; n++) {
            for (int shiftIndex = 0; shiftIndex < shiftLevels; shiftIndex++) {
                int shift = SHIFTS[shiftIndex];
                int base = n * DRAWS_PER_INVOCATION + shift;
                if (base + 2 >= capacity) {
                    continue;
                }
                // Candidates are generated over the whole legal column, not just the
                // base band: an 8-tall run starting at y=35 has its upper column at
                // y=39, and dropping those would break the chain rather than narrow it.
                int y = bounded(draws[base + 2], DOUBLED_HEIGHTMAP);
                if (y < Y_FLOOR || y > Y_CEIL) {
                    continue;
                }
                int originX = bounded(draws[base], 16);
                int originZ = bounded(draws[base + 1], 16);
                int group = groupCount++;
                groupStart[group] = candidates;
                groupN[group] = n;
                groupNext[group] = -1;
                int slot = y - Y_FLOOR;
                if (yHead[slot] == -1) {
                    yHead[slot] = group;
                } else {
                    groupNext[yTail[slot]] = group;
                }
                yTail[slot] = group;
                // Only the FIRST try landing on a position can be the column there.
                //
                // All twenty tries of an invocation share a y, so a repeat means the same
                // block. A chain needs terrain to permit placement at that block -- that is
                // what makes it a chain -- so the earlier try placed, and the later one
                // finds cane rather than air and places nothing. Treating a later duplicate
                // as the column assumes a placement the game would already have made at a
                // height of its own choosing.
                //
                // Measured against a dedicated 4+4+4+4 scanner over 4M seeds: this is 26%
                // of what the filter used to accept at height 8, and none of it was real.
                java.util.Arrays.fill(seenXZ, 0L);
                for (int i = 0; i < TRIES; i++) {
                    int off = base + 3 + i * DRAWS_PER_TRY;
                    if (off + DRAWS_PER_TRY + 1 >= capacity) {
                        break;
                    }
                    // The height is drawn immediately after the try that succeeded,
                    // so it sits where the next try's draws would have been.
                    int after = off + DRAWS_PER_TRY;
                    int px = originX + bounded(draws[off], 5) - bounded(draws[off + 1], 5);
                    int pz = originZ + bounded(draws[off + 4], 5) - bounded(draws[off + 5], 5);
                    int key = (px + 4) + (pz + 4) * 24;
                    if ((seenXZ[key >>> 6] & (1L << (key & 63))) != 0L) {
                        continue;   // an earlier try already owns this block
                    }
                    seenXZ[key >>> 6] |= 1L << (key & 63);
                    cx[candidates] = px;
                    cz[candidates] = pz;
                    cy[candidates] = y;
                    ch[candidates] = 2 + bounded(draws[after + 1], bounded(draws[after], 3) + 1);
                    cn[candidates] = n;
                    cs[candidates] = shiftIndex;
                    candidates++;
                }
                groupEnd[group] = candidates;
            }
        }
    }

    /** True when {@code wantedY} can be a chain continuation at all. */
    private boolean indexable(int wantedY) {
        return wantedY >= Y_FLOOR && wantedY <= Y_CEIL;
    }

    /**
     * The chains that reach {@code minHeight}, as positions rather than a height, so
     * a caller can ask the terrain about them. Each is one (x, z) relative to the
     * chunk origin plus the base y of every column in it — and every one of those
     * bases has to be air, which is what makes a cheap terrain test possible.
     *
     * <p>Records the <em>shortest</em> chain that reaches the height rather than the
     * tallest, because fewer columns means fewer positions required to be air, which
     * is the permissive direction. Duplicates are common — the four shift
     * assumptions usually rediscover the same geometry — and are dropped.
     *
     * @return number of chains, or {@link #MAX_CHAINS} with {@link #chainsOverflowed}
     *         set, in which case the caller must accept without testing
     */
    public int collectChains(long decorationSeed, int featureIndex, int minHeight) {
        buildCandidates(decorationSeed, featureIndex);
        chainCount = 0;
        chainOverflow = false;
        wantedHeight = minHeight;
        for (int i = 0; i < candidates && !chainOverflow; i++) {
            if (cy[i] < baseMinY || cy[i] > baseMaxY || cs[i] > maxBaseShift) {
                continue;
            }
            collect(i, 0, 0, maxSlack);
        }
        return chainCount;
    }

    /** True when there were too many chains to enumerate; accept without testing. */
    public boolean chainsOverflowed() {
        return chainOverflow;
    }

    public long chain(int index) {
        return chains[index];
    }

    private void collect(int i, int depth, int total, int slackLeft) {
        path[depth] = i;
        int newTotal = total + ch[i];
        if (newTotal >= wantedHeight) {
            record(depth + 1);
            return;     // shortest sufficient chain; a longer one only adds requirements
        }
        if (depth + 1 >= maxColumns) {
            return;
        }
        int wantedY = cy[i] + ch[i];
        if (wantedY > Y_CEIL) {
            return;
        }
        if (!indexable(wantedY)) {
            return;
        }
        for (int g = yHead[wantedY - Y_FLOOR]; g != -1; g = groupNext[g]) {
            if (groupN[g] <= cn[i]) {
                continue;
            }
            for (int j = groupStart[g]; j < groupEnd[g]; j++) {
                if (cx[j] != cx[i] || cz[j] != cz[i]) {
                    continue;
                }
                if (cs[j] > maxAnyShift) {
                    continue;   // an interleaved placement, same implausibility as a prior one
                }
                if (cs[j] <= cs[i]) {
                    continue;   // see chainFrom: shifts must strictly increase up a chain
                }
                int spent = cs[j] - cs[i] - 1;
                if (spent > slackLeft) {
                    continue;   // more foreign placements than the budget allows
                }
                collect(j, depth + 1, newTotal, slackLeft - spent);
                if (chainOverflow) {
                    return;
                }
            }
        }
    }

    private void record(int columns) {
        int first = path[0];
        long packed = pack(cx[first], cz[first], columns);
        for (int i = 0; i < chainCount; i++) {
            if (chains[i] == packed) {
                return;
            }
        }
        if (chainCount == MAX_CHAINS) {
            chainOverflow = true;
            return;
        }
        chains[chainCount++] = packed;
    }

    /**
     * x and z are chunk-relative and span -4..19, since an origin in 0..15 is offset
     * by up to 4 either way. y is 11..64. Five, five, three, then five sevens.
     *
     * <pre>
     *   0..4   x + 4
     *   5..9   z + 4
     *   10..12 columns
     *   13..47 base y of each column, seven bits each, up to five of them
     *   48..50 the base column's shift
     *   51..53 the chain's largest shift
     * </pre>
     *
     * <p>The shift fields used to sit at 41..44, immediately after four y slots. A fifth
     * column's y lands exactly there, so a five-column chain would have overwritten its own
     * shift fields -- silently, since nothing reads a chain back to check it. Moving them
     * to 48 and widening them to three bits (five levels no longer fit in two) leaves this
     * at 54 bits of a long.
     *
     * <p>Only ever held in memory, so widening it is not a format change and no target file
     * is affected.
     */
    private long pack(int x, int z, int columns) {
        int maxShift = 0;
        for (int i = 0; i < columns; i++) {
            packY[i] = cy[path[i]];
            if (cs[path[i]] > maxShift) {
                maxShift = cs[path[i]];
            }
        }
        return pack(x, z, columns, packY, cs[path[0]], maxShift);
    }

    /**
     * The layout itself, taking its inputs explicitly so it can be tested.
     *
     * <p>Five-column chains are far too rare to reach by sampling -- four-column ones were
     * 0 of 256 real finds -- so the fifth y slot cannot be exercised by generating chains
     * and hoping. It is checked directly instead.
     */
    static long pack(int x, int z, int columns, int[] ys, int baseShift, int maxShift) {
        long packed = (long) (x + 4) | (long) (z + 4) << 5 | (long) columns << 10;
        for (int i = 0; i < columns; i++) {
            packed |= (long) ys[i] << (13 + 7 * i);
        }
        packed |= (long) baseShift << 48;
        packed |= (long) maxShift << 51;
        return packed;
    }

    public static int chainX(long chain) {
        return (int) (chain & 31) - 4;
    }

    public static int chainZ(long chain) {
        return (int) (chain >>> 5 & 31) - 4;
    }

    public static int chainColumns(long chain) {
        return (int) (chain >>> 10 & 7);
    }

    /** Base y of column {@code i}, every one of which has to be air. */
    public static int chainBaseY(long chain, int i) {
        return (int) (chain >>> (13 + 7 * i) & 127);
    }

    /**
     * Earlier placements the chain's first column assumes, 0..3. Each one is a
     * successful placement before it in the chunk's stream, and those are rare
     * (~1.1e-3 cane columns per chunk), so a high value marks a target that is
     * unlikely ever to cash in.
     */
    public static int chainBaseShift(long chain) {
        return (int) (chain >>> 48 & 7);
    }

    /** The largest such assumption across the chain's columns. */
    public static int chainMaxShift(long chain) {
        return (int) (chain >>> 51 & 7);
    }

    /**
     * Tallest run starting at candidate {@code i}. Depth-limited: four columns of
     * the minimum height 2 already reach 8, and nothing here needs more.
     */
    private int chainFrom(int i, int depth, int slackLeft) {
        int height = ch[i];
        if (depth + 1 >= maxColumns) {
            return height;
        }
        int wantedY = cy[i] + height;
        if (wantedY > Y_CEIL) {
            return height;
        }
        if (!indexable(wantedY)) {
            return height;
        }
        int extra = 0;
        for (int g = yHead[wantedY - Y_FLOOR]; g != -1; g = groupNext[g]) {
            if (groupN[g] <= cn[i]) {
                continue;
            }
            for (int j = groupStart[g]; j < groupEnd[g]; j++) {
                if (cx[j] != cx[i] || cz[j] != cz[i]) {
                    continue;
                }
                if (cs[j] > maxAnyShift) {
                    continue;   // interleaved placement; see maxAnyShift
                }
                if (cs[j] <= cs[i]) {
                    // The shift index counts successful placements before an invocation,
                    // and placements only accumulate -- so a later column cannot read the
                    // stream at the same offset as an earlier one, and the chain's own
                    // previous column is itself a placement. Without this the filter
                    // accepts chains that would have to be built out of order.
                    continue;
                }
                int spent = cs[j] - cs[i] - 1;
                if (spent > slackLeft) {
                    continue;   // more foreign placements than the budget allows
                }
                extra = Math.max(extra, chainFrom(j, depth + 1, slackLeft - spent));
            }
        }
        return height + extra;
    }

    /**
     * {@code Random.nextInt(bound)} from a raw {@code next(32)}: {@code next(31)} is
     * the same step shifted one bit further. The rejection retry is ignored, which
     * can only cause a false accept.
     */
    private static int bounded(int raw, int bound) {
        int bits = raw >>> 1;
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) bits) >> 31);
        }
        return bits % bound;
    }

    /**
     * The only soundness test available: the confirmed 8-tall at
     * -24848077,21,18720986 on seed -7585781829663227268. Its chunk is -1553005,
     * 1170061, decoration seed 72846194777308, biome 46 (cold_ocean) so count 10 and
     * feature index 5. A filter that rejects it would silently discard real finds —
     * which is exactly the bug FINDINGS 6u caught in the first prefilter, and the
     * aggregate acceptance rate gave no hint of it.
     */
    private static void checkConfirmedFind() {
        long decorationSeed = 72846194777308L;
        int tallest = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT)
                .tallestPossible(decorationSeed, 5);
        System.out.printf("confirmed 8-tall find (decoration seed %d, index 5): "
                        + "filter says %d -> %s%n%n",
                decorationSeed, tallest,
                tallest >= 8 ? "ACCEPTED" : "REJECTED, the filter is unsound");
    }

    public static void main(String[] args) throws InterruptedException {
        checkConfirmedFind();
        long trials = args.length > 0 ? Long.parseLong(args[0]) : 1_000_000L;
        int threads = args.length > 1 ? Integer.parseInt(args[1])
                : Runtime.getRuntime().availableProcessors();
        int count = args.length > 2 ? Integer.parseInt(args[2])
                : SugarCaneFeature.COUNT_DEFAULT;
        // "11 64" reproduces q without the depth band, which is what the 6ac table
        // quotes; the defaults are the band the reverse search actually uses.
        final int baseMinY = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_BASE_MIN_Y;
        final int baseMaxY = args.length > 4 ? Integer.parseInt(args[4]) : DEFAULT_BASE_MAX_Y;

        AtomicLong[] hist = new AtomicLong[32];
        for (int i = 0; i < hist.length; i++) {
            hist[i] = new AtomicLong();
        }
        long start = System.nanoTime();
        Thread[] pool = new Thread[threads];
        long per = trials / threads;
        for (int t = 0; t < threads; t++) {
            final long from = t * per;
            pool[t] = new Thread(() -> {
                ChainPrefilter filter = new ChainPrefilter(count, baseMinY, baseMaxY);
                long[] local = new long[hist.length];
                for (long i = 0; i < per; i++) {
                    long z = (from + i) * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
                    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
                    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
                    z = (z ^ (z >>> 31)) & ((1L << 48) - 1);
                    local[Math.min(filter.tallestPossible(z, 0), local.length - 1)]++;
                }
                for (int h = 0; h < local.length; h++) {
                    if (local[h] != 0) {
                        hist[h].addAndGet(local[h]);
                    }
                }
            }, "chain-" + t);
            pool[t].start();
        }
        for (Thread thread : pool) {
            thread.join();
        }

        long total = 0;
        for (AtomicLong a : hist) {
            total += a.get();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("count=%d, base band y %d..%d, %d decoration seeds, "
                        + "%.1f s (%.1f us/seed/thread)%n",
                count, baseMinY, baseMaxY, total, seconds, seconds * threads * 1e6 / total);
        System.out.println("tallest run the draws alone could chain, with no terrain:");
        long tail = 0;
        for (int h = hist.length - 1; h >= 2; h--) {
            tail += hist[h].get();
            if (tail > 0) {
                System.out.printf("   %2d: %-12d   q(>=%2d) = %.4e%n",
                        h, hist[h].get(), h, (double) tail / total);
            }
        }
    }
}
