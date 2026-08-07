/*
 * TwoChunkLift on the GPU.
 *
 * FINDINGS 6bc ruled this out: "a branchy tree with data-dependent survival ... it runs once
 * per candidate pair, not once per seed, while the GPU stays on target sets." That was right
 * when pairs were rare. Once crossfind's scan moved to the GPU (6bl) the lift became the entire
 * cost of a run -- 170M pairs at 1.45 ms is hours of CPU with the card idle -- so the premise
 * changed, not the reasoning.
 *
 * The shape is friendlier than "branchy" suggests. A pair's blind prefix is 2^LOOKAHEAD
 * independent starting values, each walking its own subtree, so there is natural parallelism
 * one level above the branching: one thread per (pair, prefix), no cross-talk, and a thread
 * whose subtree dies just idles.
 *
 * Everything here must agree with TwoChunkLift.solve exactly. The two places that is easy to
 * get wrong, both of which 6bc flagged after they had already cost a debugging session:
 *
 *   - nextLong adds a SIGN-EXTENDED low word rather than OR-ing it in. That only moves bits 32
 *     and up, so the lifting never notices and every seed returned still satisfies the wrong
 *     equation -- it fails only in the full-width check, throwing away the true seed about half
 *     the time while looking perfect. Hence the (int) cast below, deliberately.
 *   - a and b are OR-ed with 1 after assembly, not before.
 */

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cuda_runtime.h>

#define MULT 0x5DEECE66DULL
#define ADD 0xBULL
#define MASK ((1ULL << 48) - 1ULL)
#define LOOKAHEAD 12
#define BLIND_BITS (LOOKAHEAD + 4)
/* Each level pops one node and pushes at most two, so the stack grows by at most one per
 * level over 48 - BLIND_BITS levels. A little headroom on top. */
/* 48 - BLIND_BITS levels, net one entry per level, plus the root. 34 is the bound. */
#define STACK 34

/* 16*(dx*a + dz*b) mod 2^48 -- TwoChunkLift.rightHandSide, transcribed. */
__device__ __forceinline__ unsigned long long rightHandSide(
        unsigned long long ws, int dx, int dz) {
    unsigned long long s = (ws ^ MULT) & MASK;
    s = (s * MULT + ADD) & MASK;
    unsigned long long hi = s >> 16;
    s = (s * MULT + ADD) & MASK;
    /* (int) of the low word, sign-extended into the long, exactly as nextLong does. */
    long long lo = (long long) (int) (unsigned int) (s >> 16);
    unsigned long long a = (unsigned long long) (((long long) (hi << 32)) + lo) | 1ULL;
    unsigned long long total = (unsigned long long) ((long long) dx * (long long) a);
    if (dz != 0) {
        s = (s * MULT + ADD) & MASK;
        hi = s >> 16;
        s = (s * MULT + ADD) & MASK;
        lo = (long long) (int) (unsigned int) (s >> 16);
        unsigned long long b = (unsigned long long) (((long long) (hi << 32)) + lo) | 1ULL;
        total += (unsigned long long) ((long long) dz * (long long) b);
    }
    return (16ULL * total) & MASK;
}

/* Zero exactly when ws explains both decoration seeds. */
__device__ __forceinline__ unsigned long long residual(
        unsigned long long ws, unsigned long long d1, unsigned long long d2, int dx, int dz) {
    return ((((d2 ^ ws) & MASK) - ((d1 ^ ws) & MASK)) - rightHandSide(ws, dx, dz)) & MASK;
}

struct Pair {
    unsigned long long d1;
    unsigned long long d2;
    int dx;
    int dz;
};

struct Hit {
    unsigned int pair;
    unsigned long long ws;
};

/*
 * One thread per (pair, blind prefix), walking that prefix's subtree depth first.
 *
 * The CPU walks the whole candidate set level by level; this walks one prefix all the way down.
 * Same tree, same survivors, different traversal order -- which is why the host sorts before
 * comparing, and why the set is what the test pins rather than the order.
 */
__global__ void liftKernel(const Pair *pairs, long long pairCount,
                           Hit *out, unsigned int *outCount, unsigned int outCap) {
    long long total = pairCount << LOOKAHEAD;
    for (long long idx = blockIdx.x * (long long) blockDim.x + threadIdx.x;
            idx < total; idx += gridDim.x * (long long) blockDim.x) {
        long long p = idx >> LOOKAHEAD;
        unsigned long long prefix = (unsigned long long) (idx & ((1 << LOOKAHEAD) - 1));
        unsigned long long d1 = pairs[p].d1;
        unsigned long long d2 = pairs[p].d2;
        int dx = pairs[p].dx;
        int dz = pairs[p].dz;
        if (((d1 ^ d2) & 15ULL) != 0ULL) {
            continue;   // the low nibble must agree; the host prunes too, this is belt and braces
        }

        /*
         * The stack is ONE array, with the level packed into the spare top bits of the 48-bit
         * ws. That is not tidiness: this stack spills to local memory, and local memory traffic
         * is what this kernel is bound by.
         *
         * Measured, because the obvious optimisation is the wrong one. A node's residual does
         * not depend on its level -- only the mask widens -- so carrying it down the 0-branch
         * halves the 48-bit multiplies. Doing that made the kernel SLOWER, 1,938 ms against
         * 1,554 on 50k pairs, because the second array cost more traffic than the arithmetic it
         * saved. So the residual is recomputed and the stack stays narrow.
         */
        unsigned long long stack[STACK];
        int sp = 0;
        stack[sp++] = ((prefix << 4) | (d1 & 15ULL))
                | ((unsigned long long) BLIND_BITS << 56);

        while (sp > 0) {
            unsigned long long packed = stack[--sp];
            unsigned long long ws = packed & MASK;
            int bit = (int) (packed >> 56);
            if (bit == 48) {
                /* The top twelve bits are never reachable by lifting, so the survivors get the
                 * full-width check here -- this is where a mis-transcribed nextLong shows up. */
                if (residual(ws, d1, d2, dx, dz) == 0ULL) {
                    unsigned int at = atomicAdd(outCount, 1u);
                    if (at < outCap) {
                        out[at].pair = (unsigned int) p;
                        out[at].ws = ws;
                    }
                }
                continue;
            }
            unsigned long long mask = (1ULL << (bit - LOOKAHEAD + 1)) - 1ULL;
            if (sp + 2 <= STACK) {
                unsigned long long hi = ws | (1ULL << bit);
                unsigned long long next = (unsigned long long) (bit + 1) << 56;
                if ((residual(hi, d1, d2, dx, dz) & mask) == 0ULL) {
                    stack[sp++] = hi | next;
                }
                if ((residual(ws, d1, d2, dx, dz) & mask) == 0ULL) {
                    stack[sp++] = ws | next;
                }
            }
        }
    }
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: two_chunk_lift <in.bin> <out.bin>\n"
                        "  in : int32 count, then count x { u64 d1, u64 d2, i32 dx, i32 dz }\n"
                        "  out: int32 n, then n x { u32 pairIndex, u64 worldSeed }\n");
        return 2;
    }
    FILE *in = fopen(argv[1], "rb");
    if (!in) {
        fprintf(stderr, "two_chunk_lift: cannot open %s\n", argv[1]);
        return 2;
    }
    int count = 0;
    if (fread(&count, sizeof(int), 1, in) != 1 || count < 0) {
        fprintf(stderr, "two_chunk_lift: bad header\n");
        fclose(in);
        return 2;
    }
    Pair *host = (Pair *) malloc(sizeof(Pair) * (size_t) (count > 0 ? count : 1));
    for (int i = 0; i < count; i++) {
        unsigned long long d1, d2;
        int dx, dz;
        if (fread(&d1, 8, 1, in) != 1 || fread(&d2, 8, 1, in) != 1
                || fread(&dx, 4, 1, in) != 1 || fread(&dz, 4, 1, in) != 1) {
            fprintf(stderr, "two_chunk_lift: truncated input at %d\n", i);
            fclose(in);
            return 2;
        }
        host[i].d1 = d1;
        host[i].d2 = d2;
        host[i].dx = dx;
        host[i].dz = dz;
    }
    fclose(in);

    int device = 0;
    if (cudaSetDevice(device) != cudaSuccess) {
        fprintf(stderr, "two_chunk_lift: no usable CUDA device\n");
        return 3;
    }

    /* A pair yields about one seed. Room for far more, because a batch that overflows silently
     * would drop solutions and look like a search that found nothing. */
    unsigned int outCap = (unsigned int) (count > 0 ? count : 1) * 8u + 1024u;
    Pair *dPairs = nullptr;
    Hit *dOut = nullptr;
    unsigned int *dCount = nullptr;
    if (cudaMalloc(&dPairs, sizeof(Pair) * (size_t) (count > 0 ? count : 1)) != cudaSuccess
            || cudaMalloc(&dOut, sizeof(Hit) * (size_t) outCap) != cudaSuccess
            || cudaMalloc(&dCount, sizeof(unsigned int)) != cudaSuccess) {
        fprintf(stderr, "two_chunk_lift: out of device memory\n");
        return 3;
    }
    cudaMemcpy(dPairs, host, sizeof(Pair) * (size_t) (count > 0 ? count : 1),
               cudaMemcpyHostToDevice);
    cudaMemset(dCount, 0, sizeof(unsigned int));

    int threads = 128;
    long long want = ((long long) count << LOOKAHEAD) / threads + 1;
    int blocks = (int) (want > 65535 ? 65535 : (want < 1 ? 1 : want));
    liftKernel<<<blocks, threads>>>(dPairs, count, dOut, dCount, outCap);
    cudaError_t err = cudaDeviceSynchronize();
    if (err != cudaSuccess) {
        fprintf(stderr, "two_chunk_lift: %s\n", cudaGetErrorString(err));
        return 3;
    }

    unsigned int n = 0;
    cudaMemcpy(&n, dCount, sizeof(unsigned int), cudaMemcpyDeviceToHost);
    if (n > outCap) {
        fprintf(stderr, "two_chunk_lift: %u hits overflowed a %u buffer; solutions were "
                        "dropped, so this batch is unusable\n", n, outCap);
        return 4;
    }
    Hit *hits = (Hit *) malloc(sizeof(Hit) * (size_t) (n > 0 ? n : 1));
    cudaMemcpy(hits, dOut, sizeof(Hit) * (size_t) n, cudaMemcpyDeviceToHost);

    FILE *outf = fopen(argv[2], "wb");
    if (!outf) {
        fprintf(stderr, "two_chunk_lift: cannot write %s\n", argv[2]);
        return 2;
    }
    int written = (int) n;
    fwrite(&written, sizeof(int), 1, outf);
    for (unsigned int i = 0; i < n; i++) {
        fwrite(&hits[i].pair, 4, 1, outf);
        fwrite(&hits[i].ws, 8, 1, outf);
    }
    fclose(outf);
    fprintf(stderr, "lifted=%d hits=%u\n", count, n);
    return 0;
}
