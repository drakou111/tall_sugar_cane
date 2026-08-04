// Chain prefilter on the GPU: which decoration seeds could chain a tall enough sugar
// cane column, judged from the RNG alone with no terrain.
//
// This is a deliberate transliteration of gen/ChainPrefilter.java, not a reimagining of
// it. Everything here is integer arithmetic on Java's 48-bit LCG, so it is bit-exact by
// construction rather than by hope -- and `targets --verify` checks that against the Java
// filter on millions of seeds before any of it is trusted.
//
// The soil filter deliberately stays on the CPU. It is 0.6% of the build cost (measured:
// 5.927 us a seed against 0.036) and it is the only part using doubles and a sine table,
// so moving it would buy nothing and risk everything. The GPU takes the 99.4%, hands
// back the ~1.6% of seeds that survive, and Java finishes the job with code that is
// already validated.
//
// Build:
//   nvcc -O3 -fmad=false -o find_targets.exe find_targets.cu
// (-fmad=false is not needed for this kernel, which touches no floats, but it is set so
// that the flag is already right if anything float-valued is ever added.)

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>

#define MULTIPLIER 0x5DEECE66DULL
#define ADDEND 0xBULL
#define MASK48 ((1ULL << 48) - 1ULL)

#define TRIES 20
#define DRAWS_PER_TRY 6
#define DRAWS_PER_INVOCATION (3 + TRIES * DRAWS_PER_TRY)   /* 123 */
#define MAX_COUNT 10
#define SHIFT_COUNT 4
#define GROUPS (MAX_COUNT * SHIFT_COUNT)
#define DOUBLED_HEIGHTMAP (63 * 2)
#define Y_FLOOR 11
#define Y_CEIL 64
#define Y_SLOTS (Y_CEIL - Y_FLOOR + 1)
#define CAPACITY (MAX_COUNT * DRAWS_PER_INVOCATION + 6 + 16)
#define MAX_CANDIDATES (GROUPS * TRIES)

__constant__ int SHIFTS[SHIFT_COUNT] = {0, 2, 4, 6};

/** java.util.Random.next(32), as an unsigned value. */
__device__ __forceinline__ unsigned int nextInt(unsigned long long &seed) {
    seed = (seed * MULTIPLIER + ADDEND) & MASK48;
    return (unsigned int) (seed >> 16);
}

/**
 * ChainPrefilter.bounded: nextInt(bound) recovered from a stored next(32). next(31) is
 * the same step shifted one further, and the rejection retry is ignored, which can only
 * cause a false accept.
 */
__device__ __forceinline__ int bounded(unsigned int raw, int bound) {
    int bits = (int) (raw >> 1);
    if ((bound & -bound) == bound) {
        return (int) (((long long) bound * (long long) bits) >> 31);
    }
    return bits % bound;
}

struct Chains {
    unsigned int draws[CAPACITY];
    signed char cx[MAX_CANDIDATES];
    signed char cz[MAX_CANDIDATES];
    signed char cy[MAX_CANDIDATES];
    signed char ch[MAX_CANDIDATES];
    signed char cn[MAX_CANDIDATES];
    short groupStart[GROUPS];
    short groupEnd[GROUPS];
    signed char groupN[GROUPS];
    short groupNext[GROUPS];
    short yHead[Y_SLOTS];
    short yTail[Y_SLOTS];
    int candidates;
    int groupCount;
};

/** ChainPrefilter.chainFrom, iterative in depth is not worth it -- four levels at most. */
__device__ int chainFrom(Chains &c, int i, int depth) {
    int height = c.ch[i];
    if (depth >= 4) {
        return height;
    }
    int wantedY = c.cy[i] + height;
    if (wantedY < Y_FLOOR || wantedY > Y_CEIL) {
        return height;
    }
    int extra = 0;
    for (int g = c.yHead[wantedY - Y_FLOOR]; g != -1; g = c.groupNext[g]) {
        if (c.groupN[g] <= c.cn[i]) {
            continue;
        }
        for (int j = c.groupStart[g]; j < c.groupEnd[g]; j++) {
            if (c.cx[j] != c.cx[i] || c.cz[j] != c.cz[i]) {
                continue;
            }
            int sub = chainFrom(c, j, depth + 1);
            if (sub > extra) {
                extra = sub;
            }
        }
    }
    return height + extra;
}

/** The tallest run this seed's draws could chain, starting inside the depth band. */
__device__ int tallestPossible(Chains &c, unsigned long long decorationSeed,
                               int featureIndex, int count, int baseMinY, int baseMaxY) {
    // setFeatureSeed(decorationSeed, index, step=8) then setSeed.
    unsigned long long s = decorationSeed + (unsigned long long) featureIndex + 10000ULL * 8ULL;
    unsigned long long seed = (s ^ MULTIPLIER) & MASK48;
    for (int i = 0; i < CAPACITY; i++) {
        c.draws[i] = nextInt(seed);
    }

    c.candidates = 0;
    c.groupCount = 0;
    for (int i = 0; i < Y_SLOTS; i++) {
        c.yHead[i] = -1;
    }

    for (int n = 0; n < count; n++) {
        for (int shiftIndex = 0; shiftIndex < SHIFT_COUNT; shiftIndex++) {
            int base = n * DRAWS_PER_INVOCATION + SHIFTS[shiftIndex];
            if (base + 2 >= CAPACITY) {
                continue;
            }
            int y = bounded(c.draws[base + 2], DOUBLED_HEIGHTMAP);
            if (y < Y_FLOOR || y > Y_CEIL) {
                continue;
            }
            int originX = bounded(c.draws[base], 16);
            int originZ = bounded(c.draws[base + 1], 16);
            int group = c.groupCount++;
            c.groupStart[group] = (short) c.candidates;
            c.groupN[group] = (signed char) n;
            c.groupNext[group] = -1;
            int slot = y - Y_FLOOR;
            if (c.yHead[slot] == -1) {
                c.yHead[slot] = (short) group;
            } else {
                c.groupNext[c.yTail[slot]] = (short) group;
            }
            c.yTail[slot] = (short) group;
            for (int i = 0; i < TRIES; i++) {
                int off = base + 3 + i * DRAWS_PER_TRY;
                if (off + DRAWS_PER_TRY + 1 >= CAPACITY) {
                    break;
                }
                int after = off + DRAWS_PER_TRY;
                int k = c.candidates;
                c.cx[k] = (signed char) (originX + bounded(c.draws[off], 5)
                        - bounded(c.draws[off + 1], 5));
                c.cz[k] = (signed char) (originZ + bounded(c.draws[off + 4], 5)
                        - bounded(c.draws[off + 5], 5));
                c.cy[k] = (signed char) y;
                c.ch[k] = (signed char) (2 + bounded(c.draws[after + 1],
                        bounded(c.draws[after], 3) + 1));
                c.cn[k] = (signed char) n;
                c.candidates = k + 1;
            }
            c.groupEnd[group] = (short) c.candidates;
        }
    }

    int best = 0;
    for (int i = 0; i < c.candidates; i++) {
        if (c.cy[i] < baseMinY || c.cy[i] > baseMaxY) {
            continue;
        }
        int r = chainFrom(c, i, 0);
        if (r > best) {
            best = r;
        }
    }
    return best;
}

/** Must match ReverseSearcher's sampling exactly, or the two disagree about which
 *  seeds were ever tested and a cached set cannot be extended on the other device. */
__device__ __forceinline__ unsigned long long spread(unsigned long long i) {
    unsigned long long z = i * 0x9E3779B97F4A7C15ULL + 0x632BE59BD9B4E019ULL;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return (z ^ (z >> 31)) & MASK48;
}

__global__ void filterKernel(unsigned long long sampleFrom, long long total,
                             int minHeight, int count, int featureIndex,
                             int baseMinY, int baseMaxY,
                             unsigned long long *out, unsigned int *outCount,
                             unsigned int outCapacity, Chains *scratch) {
    // Chains is ~9.5 KB. A thread stack is 1 KB by default, so it cannot live there --
    // that was a stack overflow, which compute-sanitizer named in one line where
    // reading the code had not. One slot per resident thread in global memory instead.
    int gid = blockIdx.x * blockDim.x + threadIdx.x;
    Chains &c = scratch[gid];
    long long stride = (long long) blockDim.x * gridDim.x;
    for (long long idx = (long long) blockIdx.x * blockDim.x + threadIdx.x;
            idx < total; idx += stride) {
        unsigned long long ds = spread(sampleFrom + (unsigned long long) idx);
        if (tallestPossible(c, ds, featureIndex, count, baseMinY, baseMaxY) >= minHeight) {
            unsigned int slot = atomicAdd(outCount, 1u);
            if (slot < outCapacity) {
                out[slot] = ds;
            }
        }
    }
}

int main(int argc, char **argv) {
    if (argc < 7) {
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
    unsigned long long sampleFrom = strtoull(argv[6], NULL, 10);
    long long samples = atoll(argv[7]);
    const char *outFile = argc > 8 ? argv[8] : NULL;

    if (count > MAX_COUNT) {
        fprintf(stderr, "count %d exceeds the compiled maximum %d\n", count, MAX_COUNT);
        return 2;
    }

    int device = 0;
    cudaDeviceProp prop;
    if (cudaGetDeviceProperties(&prop, device) != cudaSuccess) {
        fprintf(stderr, "no usable CUDA device\n");
        return 3;
    }

    // Generous: acceptance is ~1.6% at height 8 and far less above it, but a low
    // minHeight can accept nearly everything, so cap the batch instead of the buffer.
    const long long batch = 1LL << 22;
    unsigned int outCapacity = (unsigned int) (batch / 4);
    unsigned long long *dOut = NULL;
    unsigned int *dCount = NULL;
    if (cudaMalloc(&dOut, (size_t) outCapacity * sizeof(unsigned long long)) != cudaSuccess
            || cudaMalloc(&dCount, sizeof(unsigned int)) != cudaSuccess) {
        fprintf(stderr, "cudaMalloc failed\n");
        return 3;
    }
    unsigned long long *hOut =
            (unsigned long long *) malloc((size_t) outCapacity * sizeof(unsigned long long));

    int threads = 64;
    int blocks = prop.multiProcessorCount * 8;
    Chains *dScratch = NULL;
    size_t scratchBytes = (size_t) blocks * threads * sizeof(Chains);
    if (cudaMalloc(&dScratch, scratchBytes) != cudaSuccess) {
        fprintf(stderr, "cudaMalloc of %zu MB scratch failed\n", scratchBytes >> 20);
        return 3;
    }

    FILE *f = outFile ? fopen(outFile, "wb") : stdout;
    if (!f) {
        fprintf(stderr, "cannot open %s\n", outFile);
        return 3;
    }

    long long done = 0;
    long long accepted = 0, dropped = 0;
    while (done < samples) {
        long long thisBatch = samples - done < batch ? samples - done : batch;
        unsigned int zero = 0;
        cudaMemcpy(dCount, &zero, sizeof(unsigned int), cudaMemcpyHostToDevice);
        filterKernel<<<blocks, threads>>>(sampleFrom + (unsigned long long) done,
                thisBatch, minHeight, count, featureIndex, baseMinY, baseMaxY,
                dOut, dCount, outCapacity, dScratch);
        cudaError_t err = cudaDeviceSynchronize();
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
    }
    if (f != stdout) {
        fclose(f);
    }
    // Machine-readable, on stderr so it cannot corrupt a stdout stream of seeds.
    fprintf(stderr, "tested=%lld accepted=%lld dropped=%lld\n", samples, accepted, dropped);
    return dropped > 0 ? 4 : 0;
}
