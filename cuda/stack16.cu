// Dedicated greedy CUDA scanner for exact 4+4+4+4 ocean stacks.
//
// Design:
// - one GPU thread owns one decoration seed stream at a time
// - the thread scans invocations in vanilla order for ocean count=10/index=5
// - it greedily picks a root 4-high placement, then requires the next three
//   chosen successes to stack exactly on top
// - when an invocation's origin y cannot possibly match the current target y,
//   the thread jumps over all 20 tries in O(1) with a precomputed LCG jump
// - if the greedy walk reaches four columns, a second exact rescan validates
//   the witness with O(1) local state before printing
//
// The kernel prints finds directly via device printf. They are expected to be
// extremely rare, so this avoids host-side result buffers entirely.

#include <cuda_runtime.h>

#include <errno.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define MASK48 ((1ULL << 48) - 1ULL)
#define TOTAL_DECORATION_SEEDS (1ULL << 48)
#define MULTIPLIER 0x5DEECE66DULL
#define ADDEND 0xBULL

#define OCEAN_COUNT 10
#define OCEAN_INDEX 5
#define VEGETAL_DECORATION_STEP 8
#define FEATURE_OFFSET ((uint64_t) OCEAN_INDEX + 10000ULL * (uint64_t) VEGETAL_DECORATION_STEP)

#define TRIES 20
#define DRAWS_PER_TRY 6
#define DRAWS_PER_INVOCATION (3 + TRIES * DRAWS_PER_TRY)
#define SHIFT_LEVELS 4
#define COLUMN_HEIGHT 4
#define TARGET_HEIGHT 16
#define MAX_ROOT_INVOCATION (OCEAN_COUNT - SHIFT_LEVELS)

#define DOUBLED_HEIGHTMAP (63 * 2)
#define BASE_MIN_Y 13
#define BASE_MAX_Y 35

#define DEFAULT_BLOCKS 4096
#define DEFAULT_THREADS_PER_BLOCK 256
#define DEFAULT_SEEDS_PER_THREAD 64
#define DEFAULT_PRINTF_FIFO_BYTES (32U * 1024U * 1024U)

typedef struct {
    uint64_t mul;
    uint64_t add;
} LcgJump;

typedef struct {
    uint64_t state;
} JavaRandom;

typedef struct {
    int invocation;
    int try_index;
    int shift_draws;
    int x;
    int z;
    int y;
} StackEntry;

__constant__ LcgJump d_skip_all_tries_jump;
__constant__ LcgJump d_skip_remaining_try_jumps[TRIES];

static void usage(const char *prog) {
    fprintf(stderr,
            "Usage: %s [--start-decoration-seed N] [--limit N]\n"
            "          [--blocks N] [--threads N] [--seeds-per-thread N]\n"
            "  scans ocean decoration seeds for exact 4+4+4+4 stacks with one GPU thread\n"
            "  greedily walking one seed stream at a time and validating only rare hits\n",
            prog);
}

static bool parse_u64(const char *text, uint64_t *out) {
    char *end = NULL;
    unsigned long long value;

    errno = 0;
    value = strtoull(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0') {
        return false;
    }
    *out = (uint64_t) value;
    return true;
}

static bool parse_int(const char *text, int *out) {
    char *end = NULL;
    long value;

    errno = 0;
    value = strtol(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0') {
        return false;
    }
    if (value < INT32_MIN || value > INT32_MAX) {
        return false;
    }
    *out = (int) value;
    return true;
}

static void cuda_fail(cudaError_t code, const char *what, const char *file, int line) {
    if (code == cudaSuccess) {
        return;
    }
    fprintf(stderr, "CUDA error at %s:%d during %s: %s\n",
            file, line, what, cudaGetErrorString(code));
    exit(1);
}

#define CUDA_CHECK(expr) cuda_fail((expr), #expr, __FILE__, __LINE__)

static double now_seconds(void) {
    struct timespec ts;

    timespec_get(&ts, TIME_UTC);
    return (double) ts.tv_sec + (double) ts.tv_nsec / 1000000000.0;
}

static LcgJump compute_jump(uint64_t steps) {
    LcgJump acc = {1ULL, 0ULL};
    LcgJump base = {MULTIPLIER, ADDEND};

    while (steps != 0) {
        if ((steps & 1ULL) != 0) {
            acc.add = (acc.add * base.mul + base.add) & MASK48;
            acc.mul = (acc.mul * base.mul) & MASK48;
        }
        base.add = (base.add * (base.mul + 1ULL)) & MASK48;
        base.mul = (base.mul * base.mul) & MASK48;
        steps >>= 1;
    }
    return acc;
}

__device__ __forceinline__ uint64_t next_state(uint64_t state) {
    return (state * MULTIPLIER + ADDEND) & MASK48;
}

__device__ __forceinline__ void jr_set_feature_seed(JavaRandom *rng, uint64_t decoration_seed) {
    uint64_t feature_seed = (decoration_seed + FEATURE_OFFSET) & MASK48;
    rng->state = (feature_seed ^ MULTIPLIER) & MASK48;
}

__device__ __forceinline__ uint32_t jr_next31(JavaRandom *rng) {
    rng->state = next_state(rng->state);
    return (uint32_t) (rng->state >> 17);
}

__device__ __forceinline__ void jr_apply_jump(JavaRandom *rng, LcgJump jump) {
    rng->state = (rng->state * jump.mul + jump.add) & MASK48;
}

__device__ __forceinline__ int jr_next_int_16(JavaRandom *rng) {
    return (int) (jr_next31(rng) >> 27);
}

__device__ __forceinline__ int jr_next_int_1(JavaRandom *rng) {
    (void) jr_next31(rng);
    return 0;
}

__device__ __forceinline__ int jr_next_int_3(JavaRandom *rng) {
    uint32_t bits;
    int val;

    do {
        bits = jr_next31(rng);
        val = (int) (bits % 3U);
    } while ((int32_t) (bits - (uint32_t) val + 2U) < 0);
    return val;
}

__device__ __forceinline__ int jr_next_int_5(JavaRandom *rng) {
    uint32_t bits;
    int val;

    do {
        bits = jr_next31(rng);
        val = (int) (bits % 5U);
    } while ((int32_t) (bits - (uint32_t) val + 4U) < 0);
    return val;
}

__device__ __forceinline__ int jr_next_int_126(JavaRandom *rng) {
    uint32_t bits;
    int val;

    do {
        bits = jr_next31(rng);
        val = (int) (bits % 126U);
    } while ((int32_t) (bits - (uint32_t) val + 125U) < 0);
    return val;
}

__device__ __forceinline__ bool advance_height_and_check_four(JavaRandom *rng) {
    int outer = jr_next_int_3(rng);

    if (outer == 0) {
        (void) jr_next31(rng);
        return false;
    }
    if (outer == 1) {
        (void) jr_next31(rng);
        return false;
    }
    return jr_next_int_3(rng) == 2;
}

__device__ __forceinline__ void print_hit(uint64_t decoration_seed,
                                          const StackEntry entries[SHIFT_LEVELS]) {
    uint64_t feature_seed = (decoration_seed + FEATURE_OFFSET) & MASK48;
    int i;

    printf("decorationSeed=%llu featureSeed=%llu total=%d stack=4+4+4+4 base=(%d,%d,%d)\n",
            (unsigned long long) decoration_seed,
            (unsigned long long) feature_seed,
            TARGET_HEIGHT,
            entries[0].x, entries[0].z, entries[0].y);
    for (i = 0; i < SHIFT_LEVELS; i++) {
        printf("  col=%d invocation=%d try=%d shift=%d at=(%d,%d,%d) height=%d\n",
                i,
                entries[i].invocation,
                entries[i].try_index,
                entries[i].shift_draws,
                entries[i].x,
                entries[i].z,
                entries[i].y,
                COLUMN_HEIGHT);
    }
}

__device__ bool validate_witness(uint64_t decoration_seed,
                                 const int abs_tries[SHIFT_LEVELS],
                                 int root_x, int root_y, int root_z,
                                 StackEntry entries[SHIFT_LEVELS]) {
    JavaRandom rng;
    int next_chosen = 0;
    int target_x = root_x;
    int target_y = root_y;
    int target_z = root_z;

    jr_set_feature_seed(&rng, decoration_seed);
    for (int invocation = 0; invocation < OCEAN_COUNT; invocation++) {
        int chosen_try = -1;
        int origin_x;
        int origin_z;
        int origin_y;

        if (next_chosen < SHIFT_LEVELS && abs_tries[next_chosen] / TRIES == invocation) {
            chosen_try = abs_tries[next_chosen] % TRIES;
        }

        origin_x = jr_next_int_16(&rng);
        origin_z = jr_next_int_16(&rng);
        origin_y = jr_next_int_126(&rng);

        if (chosen_try < 0) {
            if (origin_y != target_y) {
                jr_apply_jump(&rng, d_skip_all_tries_jump);
                continue;
            }
            for (int t = 0; t < TRIES; t++) {
                int px = origin_x + jr_next_int_5(&rng) - jr_next_int_5(&rng);
                int py = origin_y + jr_next_int_1(&rng) - jr_next_int_1(&rng);
                int pz = origin_z + jr_next_int_5(&rng) - jr_next_int_5(&rng);

                if (px == target_x && py == target_y && pz == target_z) {
                    return false;
                }
            }
            continue;
        }

        for (int t = 0; t < TRIES; t++) {
            int px = origin_x + jr_next_int_5(&rng) - jr_next_int_5(&rng);
            int py = origin_y + jr_next_int_1(&rng) - jr_next_int_1(&rng);
            int pz = origin_z + jr_next_int_5(&rng) - jr_next_int_5(&rng);

            if (t < chosen_try) {
                if (px == target_x && py == target_y && pz == target_z) {
                    return false;
                }
                continue;
            }

            if (px != target_x || py != target_y || pz != target_z) {
                return false;
            }

            JavaRandom after_height = rng;
            if (!advance_height_and_check_four(&after_height)) {
                return false;
            }

            entries[next_chosen].invocation = invocation;
            entries[next_chosen].try_index = t;
            entries[next_chosen].shift_draws = 2 * next_chosen;
            entries[next_chosen].x = px;
            entries[next_chosen].z = pz;
            entries[next_chosen].y = py;

            rng = after_height;
            next_chosen++;
            if (next_chosen == SHIFT_LEVELS) {
                return true;
            }

            target_y += COLUMN_HEIGHT;
            jr_apply_jump(&rng, d_skip_remaining_try_jumps[t]);
            break;
        }
    }

    return false;
}

__device__ bool greedy_extensions(JavaRandom rng,
                                  int start_invocation,
                                  int root_x, int root_z, int target_y,
                                  int abs_tries[SHIFT_LEVELS]) {
    int matched = 1;

    for (int invocation = start_invocation; invocation < OCEAN_COUNT; invocation++) {
        int origin_x;
        int origin_z;
        int origin_y;
        bool chosen_here = false;

        if (OCEAN_COUNT - invocation < SHIFT_LEVELS - matched) {
            return false;
        }

        origin_x = jr_next_int_16(&rng);
        origin_z = jr_next_int_16(&rng);
        origin_y = jr_next_int_126(&rng);

        if (origin_y != target_y) {
            jr_apply_jump(&rng, d_skip_all_tries_jump);
            continue;
        }

        for (int t = 0; t < TRIES; t++) {
            int px = origin_x + jr_next_int_5(&rng) - jr_next_int_5(&rng);
            int py = origin_y + jr_next_int_1(&rng) - jr_next_int_1(&rng);
            int pz = origin_z + jr_next_int_5(&rng) - jr_next_int_5(&rng);

            if (chosen_here) {
                continue;
            }
            if (px != root_x || py != target_y || pz != root_z) {
                continue;
            }

            JavaRandom after_height = rng;
            if (!advance_height_and_check_four(&after_height)) {
                return false;
            }

            abs_tries[matched] = invocation * TRIES + t;
            matched++;
            if (matched == SHIFT_LEVELS) {
                return true;
            }

            rng = after_height;
            target_y += COLUMN_HEIGHT;
            chosen_here = true;
            jr_apply_jump(&rng, d_skip_remaining_try_jumps[t]);
            break;
        }
    }

    return false;
}

__device__ bool search_seed(uint64_t decoration_seed) {
    JavaRandom rng;

    jr_set_feature_seed(&rng, decoration_seed);
    for (int invocation = 0; invocation <= MAX_ROOT_INVOCATION; invocation++) {
        int origin_x = jr_next_int_16(&rng);
        int origin_z = jr_next_int_16(&rng);
        int origin_y = jr_next_int_126(&rng);

        if (origin_y < BASE_MIN_Y || origin_y > BASE_MAX_Y) {
            jr_apply_jump(&rng, d_skip_all_tries_jump);
            continue;
        }

        for (int t = 0; t < TRIES; t++) {
            int px = origin_x + jr_next_int_5(&rng) - jr_next_int_5(&rng);
            int py = origin_y + jr_next_int_1(&rng) - jr_next_int_1(&rng);
            int pz = origin_z + jr_next_int_5(&rng) - jr_next_int_5(&rng);
            JavaRandom after_height = rng;
            int abs_tries[SHIFT_LEVELS];
            StackEntry validated[SHIFT_LEVELS];

            if (!advance_height_and_check_four(&after_height)) {
                continue;
            }

            abs_tries[0] = invocation * TRIES + t;
            // The root consumed try t and its two height draws, but the invocation still
            // has tries t+1..19 to go. greedy_extensions starts by reading an origin, so
            // it needs the stream at the START of the next invocation -- without this jump
            // it reads from the middle of this one and the walk almost always fails. Only
            // t == 19 worked, where the jump is zero, which cost 19 roots in every 20.
            jr_apply_jump(&after_height, d_skip_remaining_try_jumps[t]);
            if (!greedy_extensions(after_height, invocation + 1, px, pz, py + COLUMN_HEIGHT, abs_tries)) {
                continue;
            }

            if (!validate_witness(decoration_seed, abs_tries, px, py, pz, validated)) {
                continue;
            }

            print_hit(decoration_seed, validated);
            return true;
        }
    }

    return false;
}

__global__
void search_kernel(uint64_t start_decoration_seed,
                   uint64_t seeds_this_launch,
                   unsigned long long *hit_counter) {
    uint64_t global_tid = (uint64_t) blockIdx.x * (uint64_t) blockDim.x + (uint64_t) threadIdx.x;
    uint64_t stride = (uint64_t) gridDim.x * (uint64_t) blockDim.x;

    for (uint64_t offset = global_tid; offset < seeds_this_launch; offset += stride) {
        uint64_t decoration_seed = (start_decoration_seed + offset) & MASK48;

        if (search_seed(decoration_seed)) {
            atomicAdd(hit_counter, 1ULL);
        }
    }
}

int main(int argc, char **argv) {
    uint64_t start_decoration_seed = 0;
    uint64_t limit = TOTAL_DECORATION_SEEDS;
    int blocks = DEFAULT_BLOCKS;
    int threads_per_block = DEFAULT_THREADS_PER_BLOCK;
    int seeds_per_thread = DEFAULT_SEEDS_PER_THREAD;
    uint64_t tested = 0;
    unsigned long long total_hits = 0;
    unsigned long long *d_hits = NULL;
    double started_at;
    double next_report_at;
    cudaDeviceProp prop;
    int device = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--start-decoration-seed") == 0) {
            if (i + 1 >= argc || !parse_u64(argv[++i], &start_decoration_seed)) {
                usage(argv[0]);
                return 2;
            }
        } else if (strcmp(argv[i], "--limit") == 0) {
            if (i + 1 >= argc || !parse_u64(argv[++i], &limit) || limit == 0) {
                usage(argv[0]);
                return 2;
            }
        } else if (strcmp(argv[i], "--blocks") == 0) {
            if (i + 1 >= argc || !parse_int(argv[++i], &blocks) || blocks <= 0) {
                usage(argv[0]);
                return 2;
            }
        } else if (strcmp(argv[i], "--threads") == 0) {
            if (i + 1 >= argc || !parse_int(argv[++i], &threads_per_block)
                    || threads_per_block <= 0 || threads_per_block > 1024) {
                usage(argv[0]);
                return 2;
            }
        } else if (strcmp(argv[i], "--seeds-per-thread") == 0) {
            if (i + 1 >= argc || !parse_int(argv[++i], &seeds_per_thread)
                    || seeds_per_thread <= 0) {
                usage(argv[0]);
                return 2;
            }
        } else {
            usage(argv[0]);
            return 2;
        }
    }

    CUDA_CHECK(cudaGetDevice(&device));
    CUDA_CHECK(cudaGetDeviceProperties(&prop, device));
    CUDA_CHECK(cudaDeviceSetLimit(cudaLimitPrintfFifoSize, DEFAULT_PRINTF_FIFO_BYTES));

    {
        LcgJump skip_all = compute_jump((uint64_t) TRIES * (uint64_t) DRAWS_PER_TRY);
        LcgJump skip_remaining[TRIES];

        for (int t = 0; t < TRIES; t++) {
            skip_remaining[t] = compute_jump((uint64_t) (TRIES - 1 - t) * (uint64_t) DRAWS_PER_TRY);
        }

        CUDA_CHECK(cudaMemcpyToSymbol(d_skip_all_tries_jump, &skip_all, sizeof(skip_all)));
        CUDA_CHECK(cudaMemcpyToSymbol(d_skip_remaining_try_jumps, skip_remaining, sizeof(skip_remaining)));
    }

    CUDA_CHECK(cudaMalloc(&d_hits, sizeof(*d_hits)));
    CUDA_CHECK(cudaMemset(d_hits, 0, sizeof(*d_hits)));

    printf("GPU greedy ocean 4+4+4+4 search\n");
    printf("device=%s sm=%d blocks=%d threads=%d seedsPerThread=%d batchSeeds=%llu\n",
            prop.name,
            prop.multiProcessorCount,
            blocks,
            threads_per_block,
            seeds_per_thread,
            (unsigned long long) ((uint64_t) blocks * (uint64_t) threads_per_block
                    * (uint64_t) seeds_per_thread));
    printf("startDecorationSeed=%llu limit=%llu baseY=%d..%d heightmap=%d count=%d index=%d\n",
            (unsigned long long) start_decoration_seed,
            (unsigned long long) limit,
            BASE_MIN_Y,
            BASE_MAX_Y,
            DOUBLED_HEIGHTMAP / 2,
            OCEAN_COUNT,
            OCEAN_INDEX);
    fflush(stdout);

    started_at = now_seconds();
    next_report_at = started_at + 60.0;

    while (tested < limit) {
        uint64_t batch_seeds = (uint64_t) blocks * (uint64_t) threads_per_block
                * (uint64_t) seeds_per_thread;
        uint64_t current = limit - tested < batch_seeds ? limit - tested : batch_seeds;
        double current_time;
        double elapsed;

        search_kernel<<<blocks, threads_per_block>>>(
                (start_decoration_seed + tested) & MASK48,
                current,
                d_hits);
        CUDA_CHECK(cudaGetLastError());
        CUDA_CHECK(cudaDeviceSynchronize());

        tested += current;
        CUDA_CHECK(cudaMemcpy(&total_hits, d_hits, sizeof(total_hits), cudaMemcpyDeviceToHost));

        current_time = now_seconds();
        elapsed = current_time - started_at;
        if (tested == limit || current_time >= next_report_at) {
            double rate = elapsed > 0.0 ? (double) tested / elapsed : 0.0;
            double remaining = rate > 0.0 ? (double) (limit - tested) / rate : 0.0;

            if (tested < limit) {
                fprintf(stderr,
                        "[%.1f min] tested=%llu/%llu hits=%llu rate=%.0f seeds/s eta=%.1fs\n",
                        elapsed / 60.0,
                        (unsigned long long) tested,
                        (unsigned long long) limit,
                        (unsigned long long) total_hits,
                        rate,
                        remaining);
                next_report_at = current_time + 60.0;
            } else {
                fprintf(stderr,
                        "[%.1f min] finished tested=%llu hits=%llu elapsed=%.6fs rate=%.0f seeds/s\n",
                        elapsed / 60.0,
                        (unsigned long long) tested,
                        (unsigned long long) total_hits,
                        elapsed,
                        rate);
            }
        }
    }

    CUDA_CHECK(cudaFree(d_hits));
    return 0;
}
