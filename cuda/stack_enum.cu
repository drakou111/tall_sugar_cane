/*
 * Tall cane chains by enumerating RNG states, rather than sampling decoration seeds.
 *
 * ChainPrefilter walks a seed's 1,230 draws forward and pays that whatever the seed holds. This
 * inverts it: a chain's y is nextInt(126), so the states yielding a wanted y can be CONSTRUCTED
 *
 *     upper31 = k*126 + wantedY;   state = (upper31 << 17) | low17
 *
 * making the y free instead of a 1-in-126 test, and most states then die two draws later on the
 * height check.
 *
 * It is NOT exhaustive, whatever the construction suggests. k and wantedY are swept in full but
 * the 17 low bits are sampled -- `lows` of 131072, 8 by default -- and low17 drives everything
 * downstream, so a default sweep sees 6.1e-5 of the states in its y band. Raising `lows` to
 * 131072 does cover the band exhaustively, at 16,384x the time.
 *
 * Measured at height 10 over the full k range, edge filter on: 871 confirmed chains/s. Driven
 * through crossfind's pass 1, which adds the CPU geometry step, that is 621 chains stored per
 * second against the seed scan's 94 -- 6.6x, on the same card and the same command.
 *
 * Every hit here is a real chain where 32.7% of the scan's accepts are, its shift levels
 * granting chains that need an unrelated placement elsewhere in the chunk. Since joins go as the
 * square of the candidate count, that precision compounds downstream.
 *
 * The scan also caps a chain at its shift-level count -- four levels stop at height 16, and a
 * fifth halves its throughput -- where this tracks placements exactly and has no cap. The gap
 * widens as the target rises.
 *
 * The idea is a collaborator's. What is different here:
 *
 *   - nextInt branches on power-of-two bounds. Java returns (bound*next(31))>>31 there and
 *     next(31)%bound otherwise -- different bits entirely. Bounds 16, 2 and 1 all occur, and
 *     bound 2 turns up inside the height draw one time in three, so using % throughout gets
 *     ~17% of column heights wrong. It cost the Java port an afternoon of returning zero.
 *   - the sliding window is sized 20, not 19: the loop advances `front` before reading it, so
 *     19 entries is a one-past-the-end read on the last pass.
 *
 * Skip constants are generated from the Java LcgSkip, which is itself pinned to JavaRandom by
 * LcgSkipTest -- forwards, backwards, and round-tripping every stride used below.
 */

#include <cstdio>
#include <cstdlib>
#include <cuda_runtime.h>

#define MASK48 ((1ULL << 48) - 1ULL)
#define MULT 0x5DEECE66DULL
#define FEATURE_SALT 80005ULL
#define Y_BOUND 126
#define TRIES 20
#define INVOCATIONS 10
/* 9 invocations either side of the one we landed on, plus one slot the window walks past. */
#define TABLE 20

#define MUL_P1 0x5DEECE66DULL
#define ADD_P1 0xBULL
#define MUL_P2 0xBB20B4600A69ULL
#define ADD_P2 0x40942DE6BAULL
#define MUL_P3 0xD498BD0AC4B5ULL
#define ADD_P3 0xAA8544E593DULL
#define MUL_P6 0x45D73749A7F9ULL
#define ADD_P6 0x17617168255EULL
#define MUL_P120 0x6EE5EEC36E1ULL
#define ADD_P120 0x9C4720814738ULL
#define MUL_P122 0xDC4F1674C49ULL
#define ADD_P122 0xC6400AFC4CB2ULL
#define MUL_P123 0x6A8C38D11115ULL
#define ADD_P123 0x5DB66A6C93D5ULL
#define MUL_P125 0xF5C4A44AD39DULL
#define ADD_P125 0xE283AC5CDB17ULL
#define MUL_M1 0xDFE05BCB1365ULL
#define ADD_M1 0x615C0E462AA9ULL
#define MUL_M2 0xE7A191A625D9ULL
#define ADD_M2 0x6D6ACC228A56ULL
#define MUL_M3 0x13A1F16F099DULL
#define ADD_M3 0x95756C5D2097ULL
#define MUL_M123 0x3472A262B63DULL
#define ADD_M123 0x6FE8117D583FULL
#define MUL_M125 0xE33BA2914AB5ULL
#define ADD_M125 0x828AA4FD72BDULL

#define SKIP(s, N) (((s) * MUL_##N + ADD_##N) & MASK48)

/* The contribution table, three bits per invocation slot, packed into one 64-bit register. */
#define SLOT(p, i) ((int) (((p) >> (3 * (i))) & 7ULL))
#define SET_SLOT(p, i, h)     ((p) = ((p) & ~(7ULL << (3 * (i)))) | ((uint64_t) (h) << (3 * (i))))

__device__ __forceinline__ int next31(uint64_t state) {
    return (int) ((((state * MULT + 0xBULL) & MASK48)) >> 17);
}

/* nextInt(bound). The power-of-two branch is a different answer, not a faster one. */
__device__ __forceinline__ int nextIntOf(uint64_t state, int bound) {
    int bits = next31(state);
    if ((bound & -bound) == bound) {
        return (int) (((long long) bound * (long long) bits) >> 31);
    }
    return bits % bound;
}

struct Hit {
    unsigned long long decorationSeed;
    int x, y, z, height;
};

/* The height a placement would take: 2 + nextInt(nextInt(3)+1). Advances `state` by 2. */
__device__ __forceinline__ int caneHeight(uint64_t *state) {
    int bound = nextIntOf(*state, 3) + 1;
    *state = SKIP(*state, P1);
    int h = 2 + nextIntOf(*state, bound);      /* bound may be 2 -- power of two */
    *state = SKIP(*state, P1);
    return h;
}

/*
 * Everything this seed's draws would stack at one column, assuming a jitter landing on it always
 * places. Terrain-free on purpose: the same question ChainPrefilter answers, and the final word
 * on a candidate before it leaves the kernel.
 */
__device__ int runAt(uint64_t decorationSeed, int rootX, int rootY, int rootZ) {
    uint64_t state = ((decorationSeed + FEATURE_SALT) ^ MULT) & MASK48;
    int total = 0;
    int y = rootY;
    for (int n = 0; n < INVOCATIONS; n++) {
        int baseX = nextIntOf(state, 16);
        state = SKIP(state, P1);
        int baseZ = nextIntOf(state, 16);
        state = SKIP(state, P1);
        int drawnY = nextIntOf(state, Y_BOUND);
        state = SKIP(state, P1);
        for (int t = 0; t < TRIES; t++) {
            int px = baseX + nextIntOf(state, 5);
            state = SKIP(state, P1);
            px -= nextIntOf(state, 5);
            state = SKIP(state, P3);           /* that draw, plus the two zero y-jitters */
            int pz = baseZ + nextIntOf(state, 5);
            state = SKIP(state, P1);
            pz -= nextIntOf(state, 5);
            state = SKIP(state, P1);
            if (px == rootX && drawnY == y && pz == rootZ) {
                int h = caneHeight(&state);
                total += h;
                y += h;
            }
        }
    }
    return total;
}

/*
 * Could ANY neighbouring invocation extend a 4-tall column at this y, in either direction?
 *
 * Cheap for the same reason the walks are: the cursor sits on each invocation's post-y state, so
 * a test is one shift, one modulo and one multiply-add. It depends only on the state and the y,
 * not on which try landed where, so it is paid once per state instead of once per surviving try.
 *
 * LOSSLESS, which is the whole point of testing both directions. A chain's first contribution in
 * a given direction happens before any placement in that direction, so the no-placement offsets
 * used here are exactly the offsets the walk would use to find it. The forward-only version --
 * which is what the original kernel had -- is 4.3x rather than 2.1x, but it cannot see a chain
 * whose single 4-tall column is its topmost, and that was measured at a flat 40.6% of chains at
 * both height 10 and 12. Trading 40% of the population for 1.24x more throughput is a bad deal
 * when the search covers 6.1e-5 of the space and the loss is systematic rather than sampled.
 */
#define UP_BIT 1
#define DOWN_BIT 2

__device__ __forceinline__ int continuationMask(uint64_t afterY, int y) {
    uint64_t up = SKIP(afterY, P125);
    uint64_t down = SKIP(afterY, M125);
    int wantUp = y + 4;                    /* the anchor is 4 tall, so the next starts here */
    int mask = 0;
    /* No early exit: both bits are wanted, and a straight-line 9 unrolls without divergence. */
    #pragma unroll
    for (int i = 0; i < 9; i++) {
        if ((int) ((up >> 17) % Y_BOUND) == wantUp) {
            mask |= UP_BIT;
        }
        /* below: some column of height 2..4 whose top is exactly this base */
        int d = (int) ((down >> 17) % Y_BOUND);
        if (d + 4 >= y && d + 2 <= y) {
            mask |= DOWN_BIT;
        }
        up = SKIP(up, P123);
        down = SKIP(down, M123);
    }
    return mask;
}

/*
 * One state, assumed to be an invocation's post-y draw with a placement in this invocation.
 * Walks the neighbouring invocations both ways, filling the height each would contribute at the
 * same column, then slides a 10-wide window -- the chunk's real invocation count -- over the
 * table to find any alignment whose total reaches the target.
 */
__device__ void expand(uint64_t afterY, int finalX, int finalY, int finalZ,
                       int placedHeight, int target, int mask, Hit *out, unsigned int *count,
                       unsigned int cap) {
    /*
     * The contribution table lives in one register, three bits per slot.
     *
     * As an array it was 20 bytes of LOCAL memory, and the window loop below read it 100 times
     * on every call whether or not anything had stacked -- 120 local accesses against about 60
     * arithmetic ops for the scan that led here. Same lesson two_chunk_lift learned the hard
     * way: these kernels are bound by local memory traffic, not by the arithmetic. A column is
     * at most 4 tall, so 3 bits is exact and 20 slots fit in 60.
     */
    uint64_t added = (uint64_t) placedHeight << (3 * 9);

    /* forwards: invocations after this one, stacking upward */
    if (mask & UP_BIT) {
        int targetY = finalY + placedHeight;
        /*
         * The cursor sits on the state AFTER each invocation's y draw, not on its origin.
         *
         * That is what makes the reject path cheap: y is then just (cursor >> 17) % 126, one
         * shift and one modulo, where holding the origin costs SKIP(P2) + next31 + SKIP(P123)
         * -- three 64-bit multiplies to answer the same question. This walk is 94% of the
         * kernel and 125 invocations in 126 answer "no", so it is the whole cost.
         *
         * It also fixes an off-by-three. The next origin is 123 + 2 draws from THIS origin, but
         * only 122 from afterY, which is already 3 draws in. The old code reasoned the distance
         * from the origin and applied it to afterY, so the forward walk read the wrong draws
         * and almost never found a continuation. Nothing looked wrong: every candidate is
         * confirmed by runAt before it is emitted, so a broken walk loses recall in silence --
         * measured at 2.27x the chains once corrected. In this form the arithmetic is the
         * identity afterY + 125 = the next invocation's post-y state, with nothing to get
         * backwards, which is also how the backward walk below was right all along.
         */
        uint64_t yState = SKIP(afterY, P125);
        for (int idx = 10; idx < 19; idx++) {
            int y = (int) ((yState >> 17) % Y_BOUND);
            if (y != targetY) {
                yState = SKIP(yState, P123);
                continue;
            }
            /* postDraw >> 44 again, so the origin itself is never reconstructed */
            int baseX = (int) (SKIP(yState, M2) >> 44);
            int baseZ = (int) (SKIP(yState, M1) >> 44);
            uint64_t s = yState;              /* the tries begin here, 3 draws past the origin */
            bool placed = false;
            for (int t = 0; t < TRIES; t++) {
                int px = baseX + nextIntOf(s, 5);
                s = SKIP(s, P1);
                px -= nextIntOf(s, 5);
                s = SKIP(s, P3);
                int pz = baseZ + nextIntOf(s, 5);
                s = SKIP(s, P1);
                pz -= nextIntOf(s, 5);
                s = SKIP(s, P1);
                if (px == finalX && pz == finalZ) {
                    int h = caneHeight(&s);
                    targetY += h;
                    SET_SLOT(added, idx, h);
                    placed = true;
                }
            }
            /* SKIP pastes its argument as a token, so the branch cannot live inside it. */
            yState = placed ? SKIP(yState, P125) : SKIP(yState, P123);
        }
    }

    /* backwards: invocations before it, which built what we landed on */
    if (mask & DOWN_BIT) {
        int targetY = finalY;
        /* Already on the post-y state -- this walk was written in terms of the previous
         * invocation's afterY, which is why it never had the forward walk's off-by-three. */
        uint64_t yState = SKIP(afterY, M125);
        for (int idx = 8; idx >= 0; idx--) {
            int y = (int) ((yState >> 17) % Y_BOUND);
            if (y + 4 < targetY || y + 2 > targetY) {
                yState = SKIP(yState, M123);
                continue;
            }
            int baseX = (int) (SKIP(yState, M2) >> 44);
            int baseZ = (int) (SKIP(yState, M1) >> 44);
            uint64_t q = yState;              /* origin + 3 draws: exactly where the tries begin */
            bool placed = false;
            for (int t = 0; t < TRIES; t++) {
                int px = baseX + nextIntOf(q, 5);
                q = SKIP(q, P1);
                px -= nextIntOf(q, 5);
                q = SKIP(q, P3);
                int pz = baseZ + nextIntOf(q, 5);
                q = SKIP(q, P1);
                pz -= nextIntOf(q, 5);
                q = SKIP(q, P1);
                if (px == finalX && pz == finalZ) {
                    int h = caneHeight(&q);
                    if (y + h == targetY) {
                        targetY -= h;
                        SET_SLOT(added, idx, h);
                        placed = true;
                    }
                }
            }
            yState = placed ? SKIP(yState, M125) : SKIP(yState, M123);
        }
    }

    /*
     * Nothing stacked on the anchor, so no window can beat the anchor's own height. Almost every
     * call ends here -- a neighbouring invocation contributes about 1.75e-3 of the time -- and
     * it is the reason the table had to leave local memory: this test is one register compare,
     * where the loop below was 100 loads.
     */
    if (added == (uint64_t) placedHeight << (3 * 9) && placedHeight < target) {
        return;
    }

    /* the window: 10 consecutive invocations, any alignment containing slot 9 */
    for (int back = 0; back <= 9; back++) {
        int sum = 0;
        for (int i = back; i <= back + 9; i++) {
            sum += SLOT(added, i);
        }
        if (sum < target) {
            continue;
        }
        /* rebuild the seed at this alignment: step back to the first invocation of the window */
        uint64_t state = afterY;
        for (int i = 9; i > back; i--) {
            state = SKIP(state, M123);
            if (SLOT(added, i - 1) > 0) {
                state = SKIP(state, M2);
            }
        }
        state = SKIP(state, M3);              /* back over the origin's x, z, y draws */
        unsigned long long ds = (unsigned long long) (((state ^ MULT) - FEATURE_SALT) & MASK48);

        int rootY = finalY;
        for (int i = back; i < 9; i++) {
            rootY -= SLOT(added, i);
        }
        int got = runAt(ds, finalX, rootY, finalZ);
        if (got >= target) {
            unsigned int at = atomicAdd(count, 1u);
            if (at < cap) {
                out[at].decorationSeed = ds;
                out[at].x = finalX;
                out[at].y = rootY;
                out[at].z = finalZ;
                out[at].height = got;
            }
            return;
        }
    }
}

__global__ void enumerate(unsigned long long kOffset, unsigned long long kCount,
                          int minY, int target, int edgeOnly, unsigned long long lows,
                          Hit *out, unsigned int *count, unsigned int cap) {
    unsigned long long tid = blockIdx.x * (unsigned long long) blockDim.x + threadIdx.x;
    if (tid >= kCount) {
        return;
    }
    int wantY = minY + blockIdx.y;
    unsigned long long k = kOffset + tid;
    for (unsigned long long low = 0; low < lows; low++) {
        unsigned long long upper31 = k * Y_BOUND + wantY;
        if (upper31 >= (1ULL << 31)) {
            return;
        }
        /*
         * Scatter the sampled low bits with an odd multiplier rather than an even stride.
         *
         * `low * (131072 / lows)` looked like even spacing and was in fact a trap: the stride is
         * a power of two, so every sampled state had its bottom 14 bits ZERO at the default
         * lows=8 -- and the bottom bits of a java.util.Random state are its weakest, bit i having
         * period 2^i. The slice was measurably poor, not merely odd-looking: yield rose 5.59x for
         * a 4x denser sample, meaning the forced-zero states were below average. An odd
         * multiplier is a bijection mod 2^17, so lows=131072 is still exactly exhaustive, and
         * 81001 is the nearest odd number to 2^17/phi, which spreads any prefix evenly.
         */
        uint64_t afterY = ((upper31 << 17) | ((low * 81001ULL) & 0x1FFFFULL)) & MASK48;

        // Nothing can stack on this invocation's y, so none of its twenty tries can start a
        // chain and the whole state is dead. Independent of the try, hence hoisted here -- and
        // ahead of the origin below, which three quarters of states now never pay for.
        int mask = continuationMask(afterY, wantY);
        if (mask == 0) {
            continue;
        }
        /*
         * The origin's x and z, one and two draws back, with no multiply beyond the step.
         *
         * nextInt(16) takes the power-of-two path, (16 * next31) >> 31, which is next31 >> 27;
         * and next31 is itself the post-draw state >> 17. The two shifts collapse into one, and
         * walking backwards hands us exactly those post-draw states. Lifted from the original
         * kernel, which spells it `rand4 >> 44` -- five multiply-adds down to two.
         */
        int baseZ = (int) (SKIP(afterY, M1) >> 44);
        int baseX = (int) (SKIP(afterY, M2) >> 44);
        uint64_t state = afterY;
        for (int t = 0; t < TRIES; t++) {
            uint64_t jitter = state;
            state = SKIP(state, P6);
            /* would this try place a 4-tall column? 1 in 9, and it kills most states here */
            uint64_t h = state;
            if (nextIntOf(h, 3) != 2) {
                continue;
            }
            h = SKIP(h, P1);
            if (nextIntOf(h, 3) != 2) {
                continue;
            }

            int px = baseX + nextIntOf(jitter, 5);
            jitter = SKIP(jitter, P1);
            px -= nextIntOf(jitter, 5);
            jitter = SKIP(jitter, P3);
            int pz = baseZ + nextIntOf(jitter, 5);
            jitter = SKIP(jitter, P1);
            pz -= nextIntOf(jitter, 5);

            /* only columns a neighbouring chunk's +-4 jitter can reach are cross-chunk usable */
            if (edgeOnly && px >= 4 && px <= 11 && pz >= 4 && pz <= 11) {
                continue;
            }
            expand(afterY, px, wantY, pz, 4, target, mask, out, count, cap);
        }
    }
}

int main(int argc, char **argv) {
    unsigned long long kFrom = argc > 1 ? strtoull(argv[1], nullptr, 10) : 0;
    unsigned long long kCount = argc > 2 ? strtoull(argv[2], nullptr, 10) : (1ULL << 20);
    int minY = argc > 3 ? atoi(argv[3]) : 16;
    int maxY = argc > 4 ? atoi(argv[4]) : 36;
    int target = argc > 5 ? atoi(argv[5]) : 16;
    int edgeOnly = argc > 6 ? atoi(argv[6]) : 1;
    const char *outPath = argc > 7 && argv[7][0] ? argv[7] : nullptr;
    unsigned long long lows = argc > 8 ? strtoull(argv[8], nullptr, 10) : 8;
    if (lows == 0 || lows > (1ULL << 17) || ((1ULL << 17) % lows) != 0) {
        fprintf(stderr, "stack_enum: lows must be a power of two up to 131072\n");
        return 2;
    }

    if (cudaSetDevice(0) != cudaSuccess) {
        fprintf(stderr, "stack_enum: no usable CUDA device\n");
        return 3;
    }
    /* 4M hits is 96 MB on the device and covers a lows=16384 sweep at height 12 with room to
     * spare. The host halves its k range and retries on overflow anyway, so this only decides
     * how often that costs an extra launch. */
    unsigned int cap = argc > 9 ? (unsigned int) strtoul(argv[9], nullptr, 10) : (4u << 20);
    Hit *dOut = nullptr;
    unsigned int *dCount = nullptr;
    cudaMalloc(&dOut, sizeof(Hit) * (size_t) cap);
    cudaMalloc(&dCount, sizeof(unsigned int));
    cudaMemset(dCount, 0, sizeof(unsigned int));

    int threads = 256;
    unsigned long long blocks = (kCount + threads - 1) / threads;
    if (blocks > 2147483647ULL) {
        fprintf(stderr, "stack_enum: kCount too large for one launch\n");
        return 2;
    }
    dim3 grid((unsigned int) blocks, (unsigned int) (maxY - minY + 1), 1);
    enumerate<<<grid, threads>>>(kFrom, kCount, minY, target, edgeOnly, lows,
                                dOut, dCount, cap);
    cudaError_t err = cudaDeviceSynchronize();
    if (err != cudaSuccess) {
        fprintf(stderr, "stack_enum: %s\n", cudaGetErrorString(err));
        return 3;
    }

    unsigned int n = 0;
    cudaMemcpy(&n, dCount, sizeof(unsigned int), cudaMemcpyDeviceToHost);
    /* The atomic counted every hit, including the ones there was no room to store. Report that
     * number rather than the clamped one: the host sizes its retry from it, and "hits = the cap"
     * tells it nothing except that the cap was reached. */
    unsigned int wanted = n;
    bool overflowed = n > cap;
    if (overflowed) {
        n = cap;
    }
    Hit *hits = (Hit *) malloc(sizeof(Hit) * (n > 0 ? n : 1));
    cudaMemcpy(hits, dOut, sizeof(Hit) * (size_t) n, cudaMemcpyDeviceToHost);

    if (outPath) {
        FILE *f = fopen(outPath, "wb");
        int written = (int) n;
        fwrite(&written, sizeof(int), 1, f);
        for (unsigned int i = 0; i < n; i++) {
            fwrite(&hits[i].decorationSeed, 8, 1, f);
            fwrite(&hits[i].x, 4, 1, f);
            fwrite(&hits[i].y, 4, 1, f);
            fwrite(&hits[i].z, 4, 1, f);
            fwrite(&hits[i].height, 4, 1, f);
        }
        fclose(f);
    } else {
        for (unsigned int i = 0; i < n; i++) {
            printf("%llu %d %d %d %d\n", hits[i].decorationSeed, hits[i].x, hits[i].y,
                   hits[i].z, hits[i].height);
        }
    }
    fprintf(stderr, "enumerated=%llu states=%llu hits=%u%s\n", kCount,
            kCount * lows * (unsigned long long) (maxY - minY + 1), wanted,
            overflowed ? " (OVERFLOWED, results dropped)" : "");
    // Overflow drops hits silently, which looks exactly like a barren sweep. Same reasoning as
    // two_chunk_lift's exit 4: a short list must not be mistaken for a complete one.
    return overflowed ? 4 : 0;
}
