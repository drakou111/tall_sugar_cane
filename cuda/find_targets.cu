// Chain prefilter on the GPU: which decoration seeds could chain a tall enough sugar
// cane column, judged from the RNG alone with no terrain.
//
// A transliteration of gen/ChainPrefilter.java, not a reimagining of it. Everything here
// is integer arithmetic on Java's 48-bit LCG, so it is exact by construction rather than
// by hope, and it is checked against the Java filter over millions of seeds before any
// number from it is believed.
//
// The soil filter deliberately stays on the CPU. Measured at 0.6% of the build cost
// against the chain filter's 99.4%, and it is the only part touching doubles and a sine
// table, so moving it would buy nothing and risk everything. The GPU takes the 99.4%,
// hands back the ~1.6% of seeds that survive, and Java finishes with code already
// validated against a real 1.16.1 server.
//
// Three structural choices, each of which was measured rather than assumed:
//
//   * A SLIDING WINDOW of 131 draws, not the whole 1,252-draw stream. An invocation
//     spans 123 draws and a group reads at most 124 past its base, so 131 covers every
//     shift and the window advances by 123 per invocation. 524 bytes instead of 5,008.
//
//   * A DP over candidates in REVERSE order instead of a recursive descent. best2[i] and
//     best3[i] hold the tallest run of at most two and three columns starting at i, so
//     one backward pass replaces four nested loops. Valid because a chain requires
//     n[j] > n[i] and candidates are emitted in invocation order, so j > i always and
//     the values are ready when needed.
//
//   * A Y-INDEX rather than a hash table. All twenty tries of an invocation share a y and
//     the next column must sit at exactly y + height, so grouping by y gives the same
//     selectivity from 54 slots as a hash gives from 2,048 -- and 54 shorts is 108 bytes
//     to clear per seed against 4,096. That clearing was the difference between 2,247k
//     and 5,564k seeds/s in two earlier versions of this kernel.
//
// The struct is ~6.6 KB and there is no recursion, so it lives in local memory rather
// than on the 1 KB thread stack. An earlier version passed a struct by reference into a
// recursive function, which put it on the stack and overflowed it -- compute-sanitizer
// named that in one line where reading the code had not.
//
// Build (see build.bat):
//   nvcc -O3 -fmad=false ^
//     -gencode arch=compute_75,code=sm_75 ^
//     -gencode arch=compute_86,code=sm_86 ^
//     -gencode arch=compute_89,code=sm_89 ^
//     -gencode arch=compute_89,code=compute_89 ^
//     -o find_targets.exe find_targets.cu
//
// Several -gencode rather than one -arch, because a single-architecture binary will not
// load on any other card and the Java side then reports "no usable GPU" -- identical to
// having no card at all. A build for sm_89 alone silently cost a 3060 owner the GPU path.
// The last line embeds PTX so a card newer than any listed here JITs instead of failing.
//
// Naming an architecture still matters for measurement: with none of them the driver JITs
// on first use, which cost about five seconds and made a short benchmark meaningless.

#include <cstdio>
#include <cstdlib>
#include <cstdint>

#define MULTIPLIER 0x5DEECE66DULL
#define ADDEND 0xBULL
#define MASK48 ((1ULL << 48) - 1ULL)

#define TRIES 20
#define DRAWS_PER_TRY 6
#define DRAWS_PER_INVOCATION (3 + TRIES * DRAWS_PER_TRY)   /* 123 */
#define MAX_COUNT 10
/* The most levels any variant compiles. Which one RUNS is a template parameter, because
 * the struct is sized off it and a five-level struct costs everyone occupancy: measured at
 * 6.25e6 seeds/s against 14.2e6 for a height that only ever needs four. */
#define MAX_SHIFT_COUNT 5
#define MAX_SHIFT 8
#define WINDOW_DRAWS (DRAWS_PER_INVOCATION + MAX_SHIFT + 2)  /* 133 */


#define DOUBLED_HEIGHTMAP (63 * 2)
#define Y_FLOOR 11
#define Y_CEIL 64
#define Y_SLOTS (Y_CEIL - Y_FLOOR + 1)

/** x and z both span -4..19, so the pair packs into 0..575. */
__device__ __forceinline__ unsigned short packXZ(int x, int z) {
    return (unsigned short) ((x + 4) + (z + 4) * 24);
}

/** One bit of 64 for a key, by multiplicative hash. */
__device__ __forceinline__ unsigned long long xzBit(unsigned short key) {
    return 1ULL << ((((unsigned int) key * 2654435761u) >> 26) & 63u);
}

/* Must match OrbitSampler.RUN and its LCG^123 jump constants. */
#define ORBIT_RUN 64
#define JUMP_A 0x6A8C38D11115ULL
#define JUMP_C 0x5DB66A6C93D5ULL

__constant__ int SHIFTS[MAX_SHIFT_COUNT] = {0, 2, 4, 6, 8};

template<int SC>
struct Chains {
    enum { GROUPS = MAX_COUNT * SC, MAX_CANDIDATES = GROUPS * TRIES,
           /* best4 only exists where a fifth column can: four levels cap a chain at four
            * columns, so carrying it there is 800 bytes per thread of local memory for a
            * value nothing reads. That alone cost 6.67e6 seeds/s against 14.2e6. */
           BEST4_N = (SC >= 5) ? MAX_CANDIDATES : 1 };
    unsigned int window[WINDOW_DRAWS];
    /** x and z packed into one key. They are only ever compared as a pair -- a chain's
     *  next column must sit at exactly the same spot -- so one load and one compare does
     *  the work of two, in the loop that runs most. */
    unsigned short xz[MAX_CANDIDATES];
    unsigned char y[MAX_CANDIDATES];
    unsigned char h[MAX_CANDIDATES];
    unsigned char n[MAX_CANDIDATES];
    /** Which SHIFTS index the candidate was read at: how many earlier placements it
     *  assumes. Only a chain's first column is capped on this. */
    unsigned char s[MAX_CANDIDATES];
    /** Tallest run of at most two, three, and four columns starting here. best4 exists
     *  only so a fifth column can be reached: heights above 16 need five. */
    unsigned char best2[MAX_CANDIDATES];
    unsigned char best3[MAX_CANDIDATES];
    unsigned char best4[BEST4_N];
    /** The group's y, so the y-buckets rebuild each step without re-reading candidates. */
    unsigned char groupY[GROUPS];
    /** Which xz keys the group holds, as a 64-bit Bloom filter. The inner scan looks for
     *  one key among twenty and almost never finds it, so testing one bit first skips the
     *  scan outright most of the time. False positives cost a scan that would have
     *  happened anyway; there are no false negatives, so the answer cannot change. */
    unsigned long long groupMask[GROUPS];
    short groupStart[GROUPS];
    short groupEnd[GROUPS];
    unsigned char groupN[GROUPS];
    short groupNext[GROUPS];
    short yHead[Y_SLOTS];
    short yTail[Y_SLOTS];
    short candidates;
    short groupCount;
};

/** java.util.Random.next(32). */
__device__ __forceinline__ unsigned int nextInt(unsigned long long &seed) {
    seed = (seed * MULTIPLIER + ADDEND) & MASK48;
    return (unsigned int) (seed >> 16);
}

/**
 * ChainPrefilter.bounded: nextInt(bound) from a stored next(32). next(31) is the same
 * step shifted one further, and the rejection retry is ignored, which can only cause a
 * false accept.
 */
__device__ __forceinline__ int bounded(unsigned int raw, int bound) {
    int bits = (int) (raw >> 1);
    if ((bound & -bound) == bound) {
        return (int) (((long long) bound * (long long) bits) >> 31);
    }
    return bits % bound;
}

__device__ __forceinline__ void refillWindow(unsigned long long &rng, unsigned int *w) {
    for (int i = 0; i < WINDOW_DRAWS; i++) {
        w[i] = nextInt(rng);
    }
}

/** Slide by one invocation's worth, keeping the overlap the next group's shifts need. */
__device__ __forceinline__ void advanceWindow(unsigned long long &rng, unsigned int *w) {
    int i = 0;
    for (; i < WINDOW_DRAWS - DRAWS_PER_INVOCATION; i++) {
        w[i] = w[i + DRAWS_PER_INVOCATION];
    }
    for (; i < WINDOW_DRAWS; i++) {
        w[i] = nextInt(rng);
    }
}

/**
 * Derive one invocation's four shift-views into a fixed ring slot.
 *
 * <p>Slots are fixed rather than appended: group {@code slot*SHIFT_COUNT+shiftIndex} always
 * owns candidates {@code group*TRIES ..+TRIES}, so one invocation can be overwritten in
 * place while the other {@code count-1} stay put. A group whose y falls outside the legal
 * column is marked empty by {@code groupStart == groupEnd} instead of being skipped, since
 * skipping would move everything after it.
 *
 * <p>{@code absN} is an absolute invocation counter, not a position in the ring. The DP
 * only ever compares invocation numbers to each other, so absolute values order correctly
 * and -- unlike ring-relative ones -- do not all have to be rewritten on every slide.
 */
template<int SC>
__device__ void deriveSlot(Chains<SC> &c, int slot, int absN) {
    for (int shiftIndex = 0; shiftIndex < SC; shiftIndex++) {
        int group = slot * SC + shiftIndex;
        int base = group * TRIES;
        int shift = SHIFTS[shiftIndex];
        c.groupN[group] = (unsigned char) absN;
        c.groupStart[group] = (short) base;

        int y = bounded(c.window[shift + 2], DOUBLED_HEIGHTMAP);
        if (y < Y_FLOOR || y > Y_CEIL) {
            c.groupEnd[group] = (short) base;
            continue;
        }
        c.groupEnd[group] = (short) (base + TRIES);
        c.groupY[group] = (unsigned char) y;

        int originX = bounded(c.window[shift], 16);
        int originZ = bounded(c.window[shift + 1], 16);
        unsigned long long mask = 0ULL;
        // Only the FIRST try landing on a position can be the column there. All twenty
        // tries share a y, so a repeat is the same block; a chain needs terrain to permit
        // placement at that block, so the earlier try placed and the later one finds cane
        // instead of air. Later duplicates are made inert rather than skipped, because the
        // slots are fixed -- an unmatched key and a zero height keep them out of every
        // chain without moving anything after them.
        unsigned long long seen[9];
        for (int w = 0; w < 9; w++) {
            seen[w] = 0ULL;
        }
        for (int t = 0; t < TRIES; t++) {
            int off = shift + 3 + t * DRAWS_PER_TRY;
            int after = off + DRAWS_PER_TRY;
            int k = base + t;
            unsigned short key = packXZ(originX + bounded(c.window[off], 5)
                            - bounded(c.window[off + 1], 5),
                    originZ + bounded(c.window[off + 4], 5)
                            - bounded(c.window[off + 5], 5));
            bool repeat = (seen[key >> 6] & (1ULL << (key & 63))) != 0ULL;
            seen[key >> 6] |= 1ULL << (key & 63);
            c.xz[k] = repeat ? (unsigned short) 0xFFFFu : key;
            if (!repeat) {
                mask |= xzBit(key);
            }
            c.y[k] = (unsigned char) y;
            c.h[k] = repeat ? (unsigned char) 0
                    : (unsigned char) (2 + bounded(c.window[after + 1],
                            bounded(c.window[after], 3) + 1));
            c.n[k] = (unsigned char) absN;
            c.s[k] = (unsigned char) shiftIndex;
        }
        c.groupMask[group] = mask;
    }
}

/**
 * Rebuild the y-index over the invocations currently in the ring.
 *
 * <p>Per window, not per run: which groups are in the window changes on every slide, and
 * both searches walk this index. It used to live inside the DP, which was fine while the
 * DP ran every window -- once one window could be answered without it, the index silently
 * described an older window and the two paths stopped agreeing. The byte-identical set
 * check caught that; nothing else would have.
 *
 * <p>Ascending order, because the DP wants a continuation's values ready before its
 * predecessor's and the backward walk wants predecessors in invocation order.
 */
template<int SC>
__device__ void rebuildYIndex(Chains<SC> &c, int head, int count) {
    for (int i = 0; i < Y_SLOTS; i++) {
        c.yHead[i] = -1;
    }
    for (int L = 0; L < count; L++) {
        int slot = head + L;
        if (slot >= count) {
            slot -= count;
        }
        for (int sh = 0; sh < SC; sh++) {
            int g = slot * SC + sh;
            if (c.groupStart[g] == c.groupEnd[g]) {
                continue;
            }
            int ys = (int) c.groupY[g] - Y_FLOOR;
            c.groupNext[g] = -1;
            if (c.yHead[ys] == -1) {
                c.yHead[ys] = (short) g;
            } else {
                c.groupNext[c.yTail[ys]] = (short) g;
            }
            c.yTail[ys] = (short) g;
        }
    }
}

/**
 * Take a slot's groups out of the y-index, and put a slot's groups in.
 *
 * <p>Two halves rather than one call, because the slot is reused: the outgoing invocation
 * has to leave the index while its groups still describe it, and the incoming one can only
 * join after {@code deriveSlot} has overwritten them. Doing both after the derive would
 * remove entries using the new invocation's y values and quietly corrupt the lists.
 *
 * <p>Both are O(SC) against a rebuild's 54 slot clears and 40 relinks. The lists are in
 * ascending invocation order, so the departing groups are at the head and the arriving
 * ones belong at the tail. Departures go in shift order, the order they were appended, so
 * each is at the head when its turn comes.
 */
template<int SC>
__device__ void removeSlotFromIndex(Chains<SC> &c, int slot) {
    for (int sh = 0; sh < SC; sh++) {
        int g = slot * SC + sh;
        if (c.groupStart[g] == c.groupEnd[g]) {
            continue;
        }
        int ys = (int) c.groupY[g] - Y_FLOOR;
        if (c.yHead[ys] == (short) g) {
            c.yHead[ys] = c.groupNext[g];
        }
    }
}

template<int SC>
__device__ void addSlotToIndex(Chains<SC> &c, int slot) {
    for (int sh = 0; sh < SC; sh++) {
        int g = slot * SC + sh;
        if (c.groupStart[g] == c.groupEnd[g]) {
            continue;
        }
        int ys = (int) c.groupY[g] - Y_FLOOR;
        c.groupNext[g] = -1;
        if (c.yHead[ys] == -1) {
            c.yHead[ys] = (short) g;
        } else {
            c.groupNext[c.yTail[ys]] = (short) g;
        }
        c.yTail[ys] = (short) g;
    }
}

/**
 * Whether a candidate is the first placement at its spot, and so can start a chain.
 *
 * <p>A chain's base needs terrain to permit building at its block. If an earlier invocation
 * read at the same shift also lands there, that one places first at whatever height it
 * drew, and the base finds cane rather than air. Same argument for continuations, one step
 * earlier.
 *
 * <p>Only with no slack budget, where the shift a chain reads at is pinned.
 */
template<int SC>
__device__ bool baseIsFirst(Chains<SC> &c, int i, int maxSlack) {
    if (maxSlack != 0) {
        return true;
    }
    for (int g = c.yHead[(int) c.y[i] - Y_FLOOR]; g != -1; g = c.groupNext[g]) {
        if (c.groupN[g] >= c.n[i]) {
            break;      // ascending invocation order
        }
        if (c.groupStart[g] >= c.groupEnd[g] || c.s[c.groupStart[g]] != c.s[i]) {
            continue;
        }
        if ((c.groupMask[g] & xzBit(c.xz[i])) == 0ULL) {
            continue;
        }
        for (int j = (int) c.groupStart[g]; j < (int) c.groupEnd[g]; j++) {
            if (c.xz[j] == c.xz[i]) {
                return false;
            }
        }
    }
    return true;
}

/**
 * Whether the {@code count} invocations currently in the ring could chain a run of
 * {@code minHeight} starting inside the depth band.
 *
 * <p>Logical invocation L lives in slot {@code (head + L) % count}. The y-buckets are
 * rebuilt ascending and the DP runs descending, which is the same relative order the flat
 * per-seed scan had -- the DP needs a continuation's best2/best3 ready before its
 * predecessor, and a continuation always has the higher invocation number.
 */
template<int SC>
__device__ bool chainExists(Chains<SC> &c, int head, int count, int minHeight,
                            int baseMinY, int baseMaxY, int maxBaseShift, int maxColumns,
                            int maxSlack, int *baseNOut) {
    for (int L = count - 1; L >= 0; L--) {
        int slot = head + L;
        if (slot >= count) {
            slot -= count;
        }
        for (int sh = SC - 1; sh >= 0; sh--) {
            int gi = slot * SC + sh;
            for (int i = (int) c.groupEnd[gi] - 1; i >= (int) c.groupStart[gi]; i--) {
                int h1 = c.h[i];
                int h2 = h1, h3 = h1, h4 = h1, h5 = h1;
                int wantedY = (int) c.y[i] + h1;

                if (wantedY >= Y_FLOOR && wantedY <= Y_CEIL) {
                    unsigned short key = c.xz[i];
                    unsigned long long bit = xzBit(key);
                    bool strictOrder = (maxSlack == 0);
                    for (int g = c.yHead[wantedY - Y_FLOOR]; g != -1; g = c.groupNext[g]) {
                        if (c.groupN[g] <= c.n[i]) {
                            continue;
                        }
                        if (strictOrder && c.groupStart[g] < c.groupEnd[g]
                                && c.s[c.groupStart[g]] != c.s[i] + 1) {
                            continue;   // not the shift this invocation is read at
                        }
                        if ((c.groupMask[g] & bit) == 0ULL) {
                            continue;   // nothing in this group can be at that spot
                        }
                        bool owned = false;
                        for (int j = c.groupStart[g]; j < c.groupEnd[g]; j++) {
                            if (c.xz[j] != key) {
                                continue;
                            }
                            owned = true;
                            // Placements only accumulate, so a continuation cannot read
                            // the stream at the same offset as the column it sits on --
                            // that column is itself a placement. Only the i -> j step
                            // needs checking; best2[j] and best3[j] enforce it further up.
                            if (c.s[j] <= c.s[i]) {
                                continue;
                            }
                            // The slack budget. Only 0 and unbounded are local rules and
                            // therefore expressible in a DP that memoises per candidate;
                            // the host refuses anything in between.
                            if (maxSlack == 0 && c.s[j] != c.s[i] + 1) {
                                continue;
                            }
                            int two = h1 + c.h[j];
                            int three = h1 + c.best2[j];
                            int four = h1 + c.best3[j];
                            int five = (SC >= 5) ? h1 + c.best4[j] : 0;
                            if (two > h2) h2 = two;
                            if (three > h3) h3 = three;
                            if (four > h4) h4 = four;
                            if (five > h5) h5 = five;
                        }
                        if (strictOrder && owned) {
                            break;      // this invocation owns the spot; no later one can
                        }
                    }
                }
                c.best2[i] = (unsigned char) h2;
                c.best3[i] = (unsigned char) h3;
                if (SC >= 5) {
                    c.best4[i] = (unsigned char) h4;
                }

                if (c.y[i] < baseMinY || c.y[i] > baseMaxY || c.s[i] > maxBaseShift) {
                    continue;
                }
                if (!baseIsFirst(c, i, maxSlack)) {
                    continue;
                }
                if (h1 >= minHeight
                        || (maxColumns >= 2 && h2 >= minHeight)
                        || (maxColumns >= 3 && h3 >= minHeight)
                        || (maxColumns >= 4 && h4 >= minHeight)
                        || (SC >= 5 && maxColumns >= 5 && h5 >= minHeight)) {
                    *baseNOut = (int) c.n[i];
                    return true;
                }
            }
        }
    }
    return false;
}

/** Must match OrbitSampler.runStart exactly, or a cache built on one device cannot
 *  be extended on the other. */
__device__ __forceinline__ unsigned long long runStart(unsigned long long r) {
    unsigned long long z = r * 0x9E3779B97F4A7C15ULL + 0x632BE59BD9B4E019ULL;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return (z ^ (z >> 31)) & MASK48;
}

/**
 * OrbitSampler.shift: the seed whose stream is this one's, one invocation in.
 *
 * <p>JUMP_A and JUMP_C are LCG^123 collapsed to one step. Held as literals rather than
 * computed, because deriving them per thread would cost 123 iterations to reproduce a
 * compile-time constant. OrbitSamplerTest pins the Java side against real streams and
 * the byte-identical CPU/GPU set check pins this against that.
 */
__device__ __forceinline__ unsigned long long orbitShift(unsigned long long ds,
                                                         int featureIndex) {
    unsigned long long k = (unsigned long long) featureIndex + 10000ULL * 8ULL;
    unsigned long long state = ((ds + k) ^ MULTIPLIER) & MASK48;
    state = (JUMP_A * state + JUMP_C) & MASK48;
    return ((state ^ MULTIPLIER) - k) & MASK48;
}

/** OrbitSampler.sampleAt: the seed at a global sample index. */
__device__ __forceinline__ unsigned long long sampleAt(unsigned long long i,
                                                       int featureIndex) {
    unsigned long long ds = runStart(i / ORBIT_RUN);
    for (unsigned long long j = i % ORBIT_RUN; j > 0; j--) {
        ds = orbitShift(ds, featureIndex);
    }
    return ds;
}

/**
 * One thread per run of {@link ORBIT_RUN} seeds.
 *
 * <p>The whole point of the run: consecutive seeds differ by one invocation, so the ring
 * fills once at {@code count} invocations and every seed after costs one. A run of 64
 * averages (10 + 63) / 64 = 1.14 invocations per seed against 10 unrolled.
 *
 * <p>Emission is a plain atomicAdd per accepted seed rather than the warp ballot this had
 * when a thread could accept at most once. A thread now accepts a variable number of times,
 * which the ballot cannot express, and at the acceptance rates a real build runs at -- q is
 * around 1e-8 at height 12 -- the contention it was avoiding does not exist.
 */
/**
 * Whether a chain ending in the newest invocation reaches the height.
 *
 * <p>Only sound to ask when the PREVIOUS window rejected, and that is the whole trick. A
 * window covers invocations [k, k+9]; the next covers [k+1, k+10]. Any chain in the next
 * one that does not use invocation k+10 lies inside [k+1, k+9], which is a subset of the
 * previous window -- so the previous window would have found it. If it did not, the new
 * invocation must be the chain's last column.
 *
 * <p>That turns a search over every candidate in the window into a backward walk from the
 * eighty in one invocation, which matters because the DP is 92% of this kernel: derivation
 * is 0.4s of 5.5s over 100M seeds, so the ring that made derivation cheap left almost all
 * of the cost untouched.
 *
 * <p>Predecessors need no new index. A column sits on one whose top is at its base, and a
 * column at y is 2, 3 or 4 tall, so the candidates that could carry a column at y are in
 * the groups at y-2, y-3 and y-4 -- three lookups in the y-index that already exists.
 *
 * <p>An explicit stack rather than recursion: the struct is ~6.6 KB and an earlier version
 * of this kernel overflowed the 1 KB thread stack by recursing with it in scope. On
 * overflow this returns false and the caller falls back to the full DP, so the bound is a
 * performance limit and never a correctness one.
 */
template<int SC>
__device__ bool chainEndingInNewest(Chains<SC> &c, int newestSlot, int minHeight,
                                    int baseMinY, int baseMaxY, int maxBaseShift,
                                    int maxColumns, int maxSlack, int *baseNOut,
                                    bool *overflowed) {
    struct Step { short cand; unsigned char acc; unsigned char col; };
    Step stack[64];
    int top = 0;
    *overflowed = false;

    for (int sh = 0; sh < SC; sh++) {
        int g = newestSlot * SC + sh;
        for (int j = (int) c.groupStart[g]; j < (int) c.groupEnd[g]; j++) {
            if (top == 64) { *overflowed = true; return false; }
            stack[top].cand = (short) j;
            stack[top].acc = c.h[j];
            stack[top].col = 1;
            top++;
        }
    }

    while (top > 0) {
        top--;
        int cur = stack[top].cand;
        int acc = stack[top].acc;
        int col = stack[top].col;

        // The chain's base is its earliest column, and the band and shift cap apply there.
        if (acc >= minHeight
                && c.y[cur] >= baseMinY && c.y[cur] <= baseMaxY
                && c.s[cur] <= maxBaseShift
                && baseIsFirst(c, cur, maxSlack)) {
            *baseNOut = (int) c.n[cur];
            return true;
        }
        if (col >= maxColumns) {
            continue;
        }

        int wantTop = (int) c.y[cur];
        for (int dh = 2; dh <= 4; dh++) {
            int py = wantTop - dh;
            if (py < Y_FLOOR || py > Y_CEIL) {
                continue;
            }
            unsigned short key = c.xz[cur];
            unsigned long long bit = xzBit(key);
            for (int g = c.yHead[py - Y_FLOOR]; g != -1; g = c.groupNext[g]) {
                if (c.groupN[g] >= c.n[cur]) {
                    continue;       // a predecessor is strictly earlier
                }
                if ((c.groupMask[g] & bit) == 0ULL) {
                    continue;
                }
                for (int i = (int) c.groupStart[g]; i < (int) c.groupEnd[g]; i++) {
                    if (c.h[i] != dh) {
                        continue;   // its top has to land exactly on this column's base
                    }
                    if (c.xz[i] != key) {
                        continue;
                    }
                    if (c.s[i] >= c.s[cur]) {
                        continue;   // shifts strictly increase up a chain
                    }
                    if (maxSlack == 0 && c.s[cur] != c.s[i] + 1) {
                        continue;
                    }
                    // cur has to be the FIRST invocation after i to land on this spot, or
                    // that earlier one placed here instead. Walking backwards this cannot
                    // be read off the DP, so it is checked directly.
                    if (maxSlack == 0) {
                        bool preempted = false;
                        for (int gg = c.yHead[(int) c.y[cur] - Y_FLOOR];
                                gg != -1 && !preempted; gg = c.groupNext[gg]) {
                            if (c.groupN[gg] <= c.n[i] || c.groupN[gg] >= c.n[cur]) {
                                continue;
                            }
                            if (c.groupStart[gg] >= c.groupEnd[gg]
                                    || c.s[c.groupStart[gg]] != c.s[cur]) {
                                continue;
                            }
                            if ((c.groupMask[gg] & bit) == 0ULL) {
                                continue;
                            }
                            for (int jj = (int) c.groupStart[gg];
                                    jj < (int) c.groupEnd[gg]; jj++) {
                                if (c.xz[jj] == key) {
                                    preempted = true;
                                    break;
                                }
                            }
                        }
                        if (preempted) {
                            continue;
                        }
                    }
                    if (top == 64) { *overflowed = true; return false; }
                    stack[top].cand = (short) i;
                    stack[top].acc = (unsigned char) (acc + dh);
                    stack[top].col = (unsigned char) (col + 1);
                    top++;
                }
            }
        }
    }
    return false;
}

template<int SC>
__global__ void filterKernel(unsigned long long sampleFrom, long long total,
                             int minHeight, int count, int featureIndex,
                             int baseMinY, int baseMaxY, int maxBaseShift, int maxColumns,
                             int maxSlack,
                             unsigned long long *out, unsigned int *outCount,
                             unsigned int outCapacity) {
    Chains<SC> c;
    long long stride = (long long) blockDim.x * gridDim.x;
    long long runs = (total + ORBIT_RUN - 1) / ORBIT_RUN;

    for (long long r = (long long) blockIdx.x * blockDim.x + threadIdx.x;
            r < runs; r += stride) {
        unsigned long long firstIdx = sampleFrom + (unsigned long long) r * ORBIT_RUN;
        unsigned long long ds = sampleAt(firstIdx, featureIndex);

        unsigned long long k = (unsigned long long) featureIndex + 10000ULL * 8ULL;
        unsigned long long rng = ((ds + k) ^ MULTIPLIER) & MASK48;
        refillWindow(rng, c.window);
        for (int L = 0; L < count; L++) {
            deriveSlot(c, L, L);
            if (L + 1 < count) {
                advanceWindow(rng, c.window);
            }
        }

        int head = 0;
        int absNext = count;
        long long steps = total - (long long) (firstIdx - sampleFrom);
        if (steps > ORBIT_RUN) {
            steps = ORBIT_RUN;
        }
        // What the previous window concluded, and where its chain started. A rejection is
        // what licenses the cheap search; an acceptance whose chain survives the slide is
        // still an acceptance, and one whose chain slid off the front needs the full DP
        // again because a different chain may remain. Acceptance is around 1e-8, so the
        // expensive branches are taken essentially never.
        bool prevAccepted = false;
        int prevBaseN = -1;
        // Absolute invocation number of the oldest invocation in the window.
        int windowStart = 0;

        rebuildYIndex(c, head, count);
        for (long long step = 0; step < steps; step++) {
            bool accept;
            int baseN = -1;
            if (step == 0) {
                accept = chainExists(c, head, count, minHeight, baseMinY, baseMaxY,
                                     maxBaseShift, maxColumns, maxSlack, &baseN);
            } else if (prevAccepted && prevBaseN >= windowStart) {
                accept = true;              // the same chain is still inside the window
                baseN = prevBaseN;
            } else {
                int newestSlot = head - 1;
                if (newestSlot < 0) {
                    newestSlot += count;
                }
                bool overflowed = false;
                if (prevAccepted) {
                    // Its chain slid off the front; another may be left, and only the full
                    // DP can say.
                    accept = chainExists(c, head, count, minHeight, baseMinY, baseMaxY,
                                         maxBaseShift, maxColumns, maxSlack, &baseN);
                } else {
                    accept = chainEndingInNewest(c, newestSlot, minHeight, baseMinY,
                                                 baseMaxY, maxBaseShift, maxColumns,
                                                 maxSlack, &baseN, &overflowed);
                    if (overflowed) {
                        accept = chainExists(c, head, count, minHeight, baseMinY, baseMaxY,
                                             maxBaseShift, maxColumns, maxSlack, &baseN);
                    }
                }
            }
            prevAccepted = accept;
            prevBaseN = baseN;

            if (accept) {
                unsigned int slot = atomicAdd(outCount, 1u);
                if (slot < outCapacity) {
                    out[slot] = ds;
                }
            }
            if (step + 1 < steps) {
                // One new invocation replaces the oldest; the other count-1 stay put.
                advanceWindow(rng, c.window);
                removeSlotFromIndex(c, head);
                deriveSlot(c, head, absNext++);
                addSlotToIndex(c, head);
                head++;
                if (head >= count) {
                    head = 0;
                }
                windowStart++;
                ds = orbitShift(ds, featureIndex);
            }
        }
    }
}

int main(int argc, char **argv) {
    if (argc < 12) {
        fprintf(stderr,
                "usage: %s <minHeight> <count> <featureIndex> <baseMinY> <baseMaxY> "
                "<maxBaseShift> <maxColumns> <maxSlack> <sampleFrom> <samples> [outFile]\n"
                "  writes accepted decoration seeds as little-endian uint64\n", argv[0]);
        return 2;
    }
    int minHeight = atoi(argv[1]);
    int count = atoi(argv[2]);
    int featureIndex = atoi(argv[3]);
    int baseMinY = atoi(argv[4]);
    int baseMaxY = atoi(argv[5]);
    int maxBaseShift = atoi(argv[6]);
    int maxColumns = atoi(argv[7]);
    int maxSlack = atoi(argv[8]);
    // 0 is contiguous, anything >= maxColumns cannot bind and is the ascending rule.
    // Values in between are path-dependent and this DP cannot express them.
    if (maxSlack != 0 && maxSlack < maxColumns) {
        fprintf(stderr, "find_targets: --max-slack=%d needs a slack dimension this "
                        "kernel does not have; rerun with --cpu\n", maxSlack);
        return 3;
    }
    int shiftLevels = atoi(argv[9]);
    if (shiftLevels < 1 || shiftLevels > MAX_SHIFT_COUNT) {
        fprintf(stderr, "find_targets: shiftLevels must be 1..%d, got %d\n",
                MAX_SHIFT_COUNT, shiftLevels);
        return 3;
    }
    unsigned long long sampleFrom = strtoull(argv[10], NULL, 10);
    long long samples = atoll(argv[11]);
    const char *outFile = argc > 12 ? argv[12] : NULL;

    if (count > MAX_COUNT) {
        fprintf(stderr, "count %d exceeds the compiled maximum %d\n", count, MAX_COUNT);
        return 2;
    }

    cudaDeviceProp prop;
    if (cudaGetDeviceProperties(&prop, 0) != cudaSuccess) {
        fprintf(stderr, "no usable CUDA device\n");
        return 3;
    }

    const long long batch = 1LL << 22;
    unsigned int outCapacity = (unsigned int) (batch / 2);
    unsigned long long *dOut = NULL;
    unsigned int *dCount = NULL;
    if (cudaMalloc(&dOut, (size_t) outCapacity * sizeof(unsigned long long)) != cudaSuccess
            || cudaMalloc(&dCount, sizeof(unsigned int)) != cudaSuccess) {
        fprintf(stderr, "cudaMalloc failed\n");
        return 3;
    }
    unsigned long long *hOut =
            (unsigned long long *) malloc((size_t) outCapacity * sizeof(unsigned long long));

    FILE *f = outFile ? fopen(outFile, "wb") : stdout;
    if (!f) {
        fprintf(stderr, "cannot open %s\n", outFile);
        return 3;
    }

    // 32 threads a block measured fastest: the struct is large enough that occupancy is
    // set by local memory, and a narrow block leaves more of it per warp.
    int threads = 32;
    int blocks = prop.multiProcessorCount * 32;

    long long done = 0, accepted = 0, dropped = 0;
    while (done < samples) {
        long long thisBatch = samples - done < batch ? samples - done : batch;
        unsigned int zero = 0;
        cudaMemcpy(dCount, &zero, sizeof(unsigned int), cudaMemcpyHostToDevice);
        // Four levels is its own instantiation, so the common case keeps the smaller
        // struct. Sizing one struct for five cost 6.25e6 seeds/s against 14.2e6 at a
        // height that only ever needs four -- the arrays are per thread and local memory
        // traffic is what this kernel is bound by.
        if (shiftLevels <= 4) {
            filterKernel<4><<<blocks, threads>>>(sampleFrom + (unsigned long long) done,
                    thisBatch, minHeight, count, featureIndex, baseMinY, baseMaxY,
                    maxBaseShift, maxColumns, maxSlack, dOut, dCount,
                    outCapacity);
        } else {
            filterKernel<5><<<blocks, threads>>>(sampleFrom + (unsigned long long) done,
                    thisBatch, minHeight, count, featureIndex, baseMinY, baseMaxY,
                    maxBaseShift, maxColumns, maxSlack, dOut, dCount,
                    outCapacity);
        }
        // The launch is checked separately from the synchronise, because a launch that never
        // happens leaves nothing to synchronise on: cudaDeviceSynchronize then returns
        // success and the run reports zero accepted seeds, exit code 0, as if the seeds had
        // simply all failed the filter. That is how a binary built for the wrong compute
        // capability presents itself -- silently, and only on someone else's card.
        cudaError_t err = cudaGetLastError();
        if (err != cudaSuccess) {
            fprintf(stderr, "kernel launch failed: %s\n", cudaGetErrorString(err));
            if (err == cudaErrorNoKernelImageForDevice) {
                fprintf(stderr, "this binary has no code for %s (compute %d.%d); rebuild it "
                        "with -gencode arch=compute_%d%d,code=sm_%d%d\n",
                        prop.name, prop.major, prop.minor,
                        prop.major, prop.minor, prop.major, prop.minor);
            }
            return 3;
        }
        err = cudaDeviceSynchronize();
        if (err != cudaSuccess) {
            fprintf(stderr, "kernel failed: %s\n", cudaGetErrorString(err));
            return 3;
        }
        unsigned int n = 0;
        cudaMemcpy(&n, dCount, sizeof(unsigned int), cudaMemcpyDeviceToHost);
        if (n > outCapacity) {
            dropped += n - outCapacity;
            n = outCapacity;
        }
        cudaMemcpy(hOut, dOut, (size_t) n * sizeof(unsigned long long),
                cudaMemcpyDeviceToHost);
        fwrite(hOut, sizeof(unsigned long long), n, f);
        accepted += n;
        done += thisBatch;
        // Relayed by GpuChainFilter so a long epoch is not a silent one. stderr, so it
        // cannot corrupt a stdout stream of seeds.
        fprintf(stderr, "progress %lld %lld %lld\n", done, samples, accepted);
        fflush(stderr);
    }
    if (f != stdout) {
        fclose(f);
    }
    fprintf(stderr, "tested=%lld accepted=%lld dropped=%lld\n", samples, accepted, dropped);
    return dropped > 0 ? 4 : 0;
}
