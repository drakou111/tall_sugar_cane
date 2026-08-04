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
#define SHIFT_COUNT 4
#define MAX_SHIFT 6
#define WINDOW_DRAWS (DRAWS_PER_INVOCATION + MAX_SHIFT + 2)  /* 131 */
#define GROUPS (MAX_COUNT * SHIFT_COUNT)
#define MAX_CANDIDATES (GROUPS * TRIES)
#define DOUBLED_HEIGHTMAP (63 * 2)
#define Y_FLOOR 11
#define Y_CEIL 64
#define Y_SLOTS (Y_CEIL - Y_FLOOR + 1)

__constant__ int SHIFTS[SHIFT_COUNT] = {0, 2, 4, 6};

struct Chains {
    unsigned int window[WINDOW_DRAWS];
    signed char x[MAX_CANDIDATES];
    signed char z[MAX_CANDIDATES];
    unsigned char y[MAX_CANDIDATES];
    unsigned char h[MAX_CANDIDATES];
    unsigned char n[MAX_CANDIDATES];
    /** Which SHIFTS index the candidate was read at: how many earlier placements it
     *  assumes. Only a chain's first column is capped on this. */
    unsigned char s[MAX_CANDIDATES];
    /** Tallest run of at most two, and at most three, columns starting here. */
    unsigned char best2[MAX_CANDIDATES];
    unsigned char best3[MAX_CANDIDATES];
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
 * Whether this seed's draws could chain a run of {@code minHeight} starting inside the
 * depth band. Four columns at most, which is what {@code ChainPrefilter.collect} allows
 * and therefore what the target build actually uses.
 */
__device__ bool accepts(Chains &c, unsigned long long decorationSeed, int featureIndex,
                        int count, int minHeight, int baseMinY, int baseMaxY,
                        int maxBaseShift, int maxColumns) {
    unsigned long long s = decorationSeed + (unsigned long long) featureIndex + 10000ULL * 8ULL;
    unsigned long long rng = (s ^ MULTIPLIER) & MASK48;

    c.candidates = 0;
    c.groupCount = 0;
    for (int i = 0; i < Y_SLOTS; i++) {
        c.yHead[i] = -1;
    }
    refillWindow(rng, c.window);

    for (int invocation = 0; invocation < count; invocation++) {
        for (int shiftIndex = 0; shiftIndex < SHIFT_COUNT; shiftIndex++) {
            int shift = SHIFTS[shiftIndex];
            int y = bounded(c.window[shift + 2], DOUBLED_HEIGHTMAP);
            if (y < Y_FLOOR || y > Y_CEIL) {
                continue;
            }
            int originX = bounded(c.window[shift], 16);
            int originZ = bounded(c.window[shift + 1], 16);

            int group = c.groupCount++;
            c.groupStart[group] = c.candidates;
            c.groupN[group] = (unsigned char) invocation;
            c.groupNext[group] = -1;
            int slot = y - Y_FLOOR;
            // Appended, not pushed, so groups stay in ascending order and the iteration
            // order matches the flat scan this replaces.
            if (c.yHead[slot] == -1) {
                c.yHead[slot] = (short) group;
            } else {
                c.groupNext[c.yTail[slot]] = (short) group;
            }
            c.yTail[slot] = (short) group;

            for (int t = 0; t < TRIES; t++) {
                int off = shift + 3 + t * DRAWS_PER_TRY;
                int after = off + DRAWS_PER_TRY;
                int k = c.candidates++;
                c.x[k] = (signed char) (originX + bounded(c.window[off], 5)
                        - bounded(c.window[off + 1], 5));
                c.z[k] = (signed char) (originZ + bounded(c.window[off + 4], 5)
                        - bounded(c.window[off + 5], 5));
                c.y[k] = (unsigned char) y;
                unsigned char height = (unsigned char) (2 + bounded(c.window[after + 1],
                        bounded(c.window[after], 3) + 1));
                c.h[k] = height;
                c.n[k] = (unsigned char) invocation;
                c.s[k] = (unsigned char) shiftIndex;
                c.best2[k] = height;
                c.best3[k] = height;
            }
            c.groupEnd[group] = c.candidates;
        }
        if (invocation + 1 < count) {
            advanceWindow(rng, c.window);
        }
    }

    // Backwards, so best2 and best3 of any continuation are already known.
    for (int i = (int) c.candidates - 1; i >= 0; i--) {
        int h1 = c.h[i];
        int h2 = h1, h3 = h1, h4 = h1;
        int wantedY = (int) c.y[i] + h1;

        if (wantedY >= Y_FLOOR && wantedY <= Y_CEIL) {
            for (int g = c.yHead[wantedY - Y_FLOOR]; g != -1; g = c.groupNext[g]) {
                if (c.groupN[g] <= c.n[i]) {
                    continue;
                }
                // Everything in this bucket already has y == wantedY by construction,
                // which a hash bucket would have had to re-check.
                for (int j = c.groupStart[g]; j < c.groupEnd[g]; j++) {
                    if (c.x[j] != c.x[i] || c.z[j] != c.z[i]) {
                        continue;
                    }
                    int two = h1 + c.h[j];
                    int three = h1 + c.best2[j];
                    int four = h1 + c.best3[j];
                    if (two > h2) h2 = two;
                    if (three > h3) h3 = three;
                    if (four > h4) h4 = four;
                }
            }
        }
        c.best2[i] = (unsigned char) h2;
        c.best3[i] = (unsigned char) h3;

        if (c.y[i] < baseMinY || c.y[i] > baseMaxY || c.s[i] > maxBaseShift) {
            continue;
        }
        // Only as many columns as the height needs: a longer chain needs water at another
        // junction and is measurably less likely to be real.
        if (h1 >= minHeight
                || (maxColumns >= 2 && h2 >= minHeight)
                || (maxColumns >= 3 && h3 >= minHeight)
                || (maxColumns >= 4 && h4 >= minHeight)) {
            return true;
        }
    }
    return false;
}

/** Must match ReverseSearcher's sampling exactly, or a cache built on one device cannot
 *  be extended on the other. */
__device__ __forceinline__ unsigned long long spread(unsigned long long i) {
    unsigned long long z = i * 0x9E3779B97F4A7C15ULL + 0x632BE59BD9B4E019ULL;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return (z ^ (z >> 31)) & MASK48;
}

__global__ void filterKernel(unsigned long long sampleFrom, long long total,
                             int minHeight, int count, int featureIndex,
                             int baseMinY, int baseMaxY, int maxBaseShift, int maxColumns,
                             unsigned long long *out, unsigned int *outCount,
                             unsigned int outCapacity) {
    Chains c;
    long long stride = (long long) blockDim.x * gridDim.x;
    for (long long idx = (long long) blockIdx.x * blockDim.x + threadIdx.x;
            idx < total; idx += stride) {
        unsigned long long ds = spread(sampleFrom + (unsigned long long) idx);
        bool accept = accepts(c, ds, featureIndex, count, minHeight, baseMinY, baseMaxY,
                              maxBaseShift, maxColumns);

        // One atomicAdd per warp rather than per accepting thread: the whole warp agrees
        // a base with a ballot, then each accepting lane takes its rank within the mask.
        unsigned int active = __activemask();
        unsigned int mask = __ballot_sync(active, accept);
        if (mask == 0u) {
            continue;
        }
        int lane = (int) (threadIdx.x & 31);
        unsigned int leader = (unsigned int) (__ffs((int) mask) - 1);
        unsigned int base = 0;
        if ((unsigned int) lane == leader) {
            base = atomicAdd(outCount, __popc(mask));
        }
        base = __shfl_sync(active, base, (int) leader);
        if (accept) {
            unsigned int prior = mask & (lane == 0 ? 0u : ((1u << lane) - 1u));
            unsigned int slot = base + __popc(prior);
            if (slot < outCapacity) {
                out[slot] = ds;
            }
        }
    }
}

int main(int argc, char **argv) {
    if (argc < 8) {
        fprintf(stderr,
                "usage: %s <minHeight> <count> <featureIndex> <baseMinY> <baseMaxY> "
                "<sampleFrom> <samples> [outFile]\n"
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
    unsigned long long sampleFrom = strtoull(argv[8], NULL, 10);
    long long samples = atoll(argv[9]);
    const char *outFile = argc > 10 ? argv[10] : NULL;

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
        filterKernel<<<blocks, threads>>>(sampleFrom + (unsigned long long) done,
                thisBatch, minHeight, count, featureIndex, baseMinY, baseMaxY,
                maxBaseShift, maxColumns, dOut, dCount, outCapacity);
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
