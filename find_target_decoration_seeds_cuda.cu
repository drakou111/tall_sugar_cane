// compile with nvcc -O3 find_target_decoration_seeds_cuda.cu
#include <cuda_runtime.h>

#include <errno.h>
#include <inttypes.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#define MASK48 ((1ULL << 48) - 1ULL)
#define TOTAL_DECORATION_SEEDS (1ULL << 48)
#define MULTIPLIER 0x5DEECE66DULL
#define ADDEND 0xBULL

#define OCEAN_COUNT 10
#define OCEAN_INDEX 5
#define VEGETAL_DECORATION_STEP 8
#define ORE_INDEX 0
#define ORE_STEP 6

#define TRIES 20
#define DRAWS_PER_TRY 6
#define DRAWS_PER_INVOCATION (3 + TRIES * DRAWS_PER_TRY)
#define DOUBLED_HEIGHTMAP (63 * 2)
#define Y_FLOOR 11
#define Y_CEIL 64
#define BASE_MIN_Y 13
#define BASE_MAX_Y 35
#define MAX_CHAINS 32
#define DIRT_BLOBS 10
#define DIRT_SIZE 33
#define DIRT_REACH 8

#define SHIFTS_LEN 4
#define MAX_SHIFT 6
#define DRAWS_CAPACITY (OCEAN_COUNT * DRAWS_PER_INVOCATION + MAX_SHIFT + 16)
#define CANDIDATE_CAPACITY (OCEAN_COUNT * SHIFTS_LEN * TRIES)
#define GROUP_CAPACITY (OCEAN_COUNT * SHIFTS_LEN)
#define Y_SLOTS (Y_CEIL - Y_FLOOR + 1)

#define SIN_TABLE_SIZE 65536
#define PI_F 3.14159265358979323846f
#define PI_D 3.14159265358979323846

#define DEFAULT_BATCH_SIZE (1U << 20)
#define DEFAULT_THREADS_PER_BLOCK 256

typedef struct {
    uint64_t state;
} JavaRandom;

typedef struct {
    uint32_t draws[DRAWS_CAPACITY];
    /*
     * signed char, not int. Every one of these fits: cx and cz span -4..19 (an origin
     * in 0..15 offset by up to 4), cy is 11..64, ch is 2..4, cn is 0..9, cs is 0..3.
     * As ints these six arrays were 19.2 KB a thread and the struct was zero-filled on
     * every seed; as bytes they are 4.8 KB. That is the difference between a kernel
     * bound by local-memory traffic and one bound by arithmetic.
     */
    signed char cx[CANDIDATE_CAPACITY];
    signed char cz[CANDIDATE_CAPACITY];
    signed char cy[CANDIDATE_CAPACITY];
    signed char ch[CANDIDATE_CAPACITY];
    signed char cn[CANDIDATE_CAPACITY];
    signed char cs[CANDIDATE_CAPACITY];
    int candidates;

    /*
     * All twenty tries of an invocation share a y, because the y-spread is 0, and a
     * chain's next column must sit at exactly y + height. So candidates arrive in
     * groups of one y, and only groups at that exact y can ever continue a chain.
     * Indexing groups by y turns each inner scan from every candidate (~800) into the
     * handful that could match (~15).
     *
     * Buckets are appended to, not pushed onto, so groups stay in ascending order and
     * the iteration order is identical to the flat scan this replaces -- which matters
     * because record_chain caps at MAX_CHAINS, so a different order would keep a
     * different 32.
     */
    short group_start[GROUP_CAPACITY];
    short group_end[GROUP_CAPACITY];
    signed char group_n[GROUP_CAPACITY];
    short group_next[GROUP_CAPACITY];
    short y_head[Y_SLOTS];
    short y_tail[Y_SLOTS];
    int group_count;

    uint64_t chains[MAX_CHAINS];
    int chain_count;
    bool chain_overflow;
    int wanted_height;
} ChainPrefilter;

typedef struct {
    uint32_t draws[DIRT_BLOBS * 6 + DIRT_BLOBS * 2 * DIRT_SIZE + 16];
} DirtBlobFilter;

static void usage(const char *prog) {
    fprintf(stderr,
            "Usage: %s <min-height> <sin-table> [start-seed] [limit] [out-file] "
            "[batch-size] [threads-per-block]\n"
            "  min-height         required chained cane height, as in ReverseSearcher\n"
            "  sin-table          Mth.SIN as Java wrote it:\n"
            "                       java -jar sugarcane.jar sin-table sin_table.bin\n"
            "  start-seed         first decoration seed to test (default 0)\n"
            "  limit              number of seeds to test (default: through 2^48)\n"
            "  out-file           accepted seeds as little-endian uint64; omit for decimal\n"
            "                       on stdout, which is fine interactively and not at scale\n"
            "  batch-size         seeds per kernel launch (default %u)\n"
            "  threads-per-block  CUDA threads per block (default %u)\n",
            prog, DEFAULT_BATCH_SIZE, DEFAULT_THREADS_PER_BLOCK);
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

static int compare_u64(const void *a, const void *b) {
    uint64_t x = *(const uint64_t *) a;
    uint64_t y = *(const uint64_t *) b;
    return x < y ? -1 : (x > y ? 1 : 0);
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

__device__ __forceinline__ void jr_set_seed(JavaRandom *rng, uint64_t seed) {
    rng->state = (seed ^ MULTIPLIER) & MASK48;
}

__device__ __forceinline__ uint32_t jr_next(JavaRandom *rng, int bits) {
    rng->state = (rng->state * MULTIPLIER + ADDEND) & MASK48;
    return (uint32_t) (rng->state >> (48 - bits));
}

__device__ __forceinline__ uint32_t jr_next_int_raw(JavaRandom *rng) {
    return jr_next(rng, 32);
}

__device__ __forceinline__ void jr_set_feature_seed(JavaRandom *rng,
        uint64_t decoration_seed, int index, int step) {
    jr_set_seed(rng, decoration_seed + (uint64_t) index + 10000ULL * (uint64_t) step);
}

__device__ __forceinline__ int bounded(uint32_t raw, int bound) {
    uint32_t bits = raw >> 1;
    if ((bound & -bound) == bound) {
        return (int) ((bound * (uint64_t) bits) >> 31);
    }
    return (int) (bits % (uint32_t) bound);
}

__device__ __forceinline__ bool rejects(uint32_t raw, int bound) {
    int32_t bits = (int32_t) (raw >> 1);
    int32_t val = bits % bound;
    return bits - val + (bound - 1) < 0;
}

__device__ __forceinline__ float to_float(uint32_t raw) {
    return (raw >> 8) / (float) (1 << 24);
}

__device__ __forceinline__ double to_double(uint32_t hi, uint32_t lo) {
    return (((uint64_t) (hi >> 6) << 27) + (lo >> 5)) * 0x1.0p-53;
}

__device__ __forceinline__ float mth_sin(const float *sin_table, float value) {
    return sin_table[(int) (value * 10430.378f) & 0xFFFF];
}

__device__ __forceinline__ float mth_cos(const float *sin_table, float value) {
    return sin_table[(int) (value * 10430.378f + 16384.0f) & 0xFFFF];
}

__device__ __forceinline__ uint64_t pack_chain(const ChainPrefilter *filter,
        const int path[4], int columns) {
    int first = path[0];
    uint64_t packed = (uint64_t) (filter->cx[first] + 4)
            | (uint64_t) (filter->cz[first] + 4) << 5
            | (uint64_t) columns << 10;
    int max_shift = 0;
    int i;
    for (i = 0; i < columns; i++) {
        packed |= (uint64_t) filter->cy[path[i]] << (13 + 7 * i);
        if (filter->cs[path[i]] > max_shift) {
            max_shift = filter->cs[path[i]];
        }
    }
    packed |= (uint64_t) filter->cs[path[0]] << 41;
    packed |= (uint64_t) max_shift << 43;
    return packed;
}

__device__ __forceinline__ int chain_x(uint64_t chain) {
    return (int) (chain & 31U) - 4;
}

__device__ __forceinline__ int chain_z(uint64_t chain) {
    return (int) ((chain >> 5) & 31U) - 4;
}

__device__ __forceinline__ int chain_base_y(uint64_t chain, int index) {
    return (int) ((chain >> (13 + 7 * index)) & 127U);
}

__device__ __forceinline__ int shift_for_index(int shift_index) {
    switch (shift_index) {
        case 0:
            return 0;
        case 1:
            return 2;
        case 2:
            return 4;
        default:
            return 6;
    }
}

__device__ void build_candidates(ChainPrefilter *filter, uint64_t decoration_seed) {
    JavaRandom rng;
    int n;

    jr_set_feature_seed(&rng, decoration_seed, OCEAN_INDEX, VEGETAL_DECORATION_STEP);
    for (n = 0; n < DRAWS_CAPACITY; n++) {
        filter->draws[n] = jr_next_int_raw(&rng);
    }

    filter->candidates = 0;
    filter->group_count = 0;
    for (n = 0; n < Y_SLOTS; n++) {
        filter->y_head[n] = -1;
    }
    for (n = 0; n < OCEAN_COUNT; n++) {
        int shift_index;
        for (shift_index = 0; shift_index < SHIFTS_LEN; shift_index++) {
            int shift = shift_for_index(shift_index);
            int base = n * DRAWS_PER_INVOCATION + shift;
            int y;
            int origin_x;
            int origin_z;
            int i;

            if (base + 2 >= DRAWS_CAPACITY) {
                continue;
            }
            y = bounded(filter->draws[base + 2], DOUBLED_HEIGHTMAP);
            if (y < Y_FLOOR || y > Y_CEIL) {
                continue;
            }
            origin_x = bounded(filter->draws[base], 16);
            origin_z = bounded(filter->draws[base + 1], 16);

            {
                int group = filter->group_count++;
                int slot = y - Y_FLOOR;
                filter->group_start[group] = (short) filter->candidates;
                filter->group_n[group] = (signed char) n;
                filter->group_next[group] = -1;
                if (filter->y_head[slot] == -1) {
                    filter->y_head[slot] = (short) group;
                } else {
                    filter->group_next[filter->y_tail[slot]] = (short) group;
                }
                filter->y_tail[slot] = (short) group;
            }

            for (i = 0; i < TRIES; i++) {
                int off = base + 3 + i * DRAWS_PER_TRY;
                int after = off + DRAWS_PER_TRY;
                int idx = filter->candidates;
                if (off + DRAWS_PER_TRY + 1 >= DRAWS_CAPACITY) {
                    break;
                }
                filter->cx[idx] = (signed char) (origin_x
                        + bounded(filter->draws[off], 5)
                        - bounded(filter->draws[off + 1], 5));
                filter->cz[idx] = (signed char) (origin_z
                        + bounded(filter->draws[off + 4], 5)
                        - bounded(filter->draws[off + 5], 5));
                filter->cy[idx] = (signed char) y;
                filter->ch[idx] = (signed char) (2 + bounded(filter->draws[after + 1],
                        bounded(filter->draws[after], 3) + 1));
                filter->cn[idx] = (signed char) n;
                filter->cs[idx] = (signed char) shift_index;
                filter->candidates++;
            }
            filter->group_end[filter->group_count - 1] = (short) filter->candidates;
        }
    }
}

__device__ __forceinline__ void record_chain(ChainPrefilter *filter, const int path[4], int columns) {
    uint64_t packed = pack_chain(filter, path, columns);
    int i;
    for (i = 0; i < filter->chain_count; i++) {
        if (filter->chains[i] == packed) {
            return;
        }
    }
    if (filter->chain_count == MAX_CHAINS) {
        filter->chain_overflow = true;
        return;
    }
    filter->chains[filter->chain_count++] = packed;
}

__device__ int collect_chains(ChainPrefilter *filter, uint64_t decoration_seed, int min_height) {
    int i;
    int path[4];

    build_candidates(filter, decoration_seed);
    filter->chain_count = 0;
    filter->chain_overflow = false;
    filter->wanted_height = min_height;

    for (i = 0; i < filter->candidates && !filter->chain_overflow; i++) {
        int total1;
        int wanted_y1;
        int j;

        if (filter->cy[i] < BASE_MIN_Y || filter->cy[i] > BASE_MAX_Y) {
            continue;
        }
        path[0] = i;
        total1 = filter->ch[i];
        if (total1 >= filter->wanted_height) {
            record_chain(filter, path, 1);
            continue;
        }
        wanted_y1 = filter->cy[i] + filter->ch[i];
        if (wanted_y1 > Y_CEIL) {
            continue;
        }

        if (wanted_y1 < Y_FLOOR) {
            continue;
        }
        for (int g1 = filter->y_head[wanted_y1 - Y_FLOOR];
                g1 != -1 && !filter->chain_overflow; g1 = filter->group_next[g1]) {
        if (filter->group_n[g1] <= filter->cn[i]) {
            continue;
        }
        for (j = filter->group_start[g1];
                j < filter->group_end[g1] && !filter->chain_overflow; j++) {
            int total2;
            int wanted_y2;
            int k;

            if (filter->cx[j] != filter->cx[i]
                    || filter->cz[j] != filter->cz[i]) {
                continue;
            }
            path[1] = j;
            total2 = total1 + filter->ch[j];
            if (total2 >= filter->wanted_height) {
                record_chain(filter, path, 2);
                continue;
            }
            wanted_y2 = filter->cy[j] + filter->ch[j];
            if (wanted_y2 > Y_CEIL) {
                continue;
            }

            if (wanted_y2 < Y_FLOOR) {
                continue;
            }
            for (int g2 = filter->y_head[wanted_y2 - Y_FLOOR];
                    g2 != -1 && !filter->chain_overflow; g2 = filter->group_next[g2]) {
            if (filter->group_n[g2] <= filter->cn[j]) {
                continue;
            }
            for (k = filter->group_start[g2];
                    k < filter->group_end[g2] && !filter->chain_overflow; k++) {
                int total3;
                int wanted_y3;
                int m;

                if (filter->cx[k] != filter->cx[j]
                        || filter->cz[k] != filter->cz[j]) {
                    continue;
                }
                path[2] = k;
                total3 = total2 + filter->ch[k];
                if (total3 >= filter->wanted_height) {
                    record_chain(filter, path, 3);
                    continue;
                }
                wanted_y3 = filter->cy[k] + filter->ch[k];
                if (wanted_y3 > Y_CEIL) {
                    continue;
                }

                if (wanted_y3 < Y_FLOOR) {
                    continue;
                }
                for (int g3 = filter->y_head[wanted_y3 - Y_FLOOR];
                        g3 != -1 && !filter->chain_overflow; g3 = filter->group_next[g3]) {
                if (filter->group_n[g3] <= filter->cn[k]) {
                    continue;
                }
                for (m = filter->group_start[g3];
                        m < filter->group_end[g3] && !filter->chain_overflow; m++) {
                    int total4;
                    if (filter->cx[m] != filter->cx[k]
                            || filter->cz[m] != filter->cz[k]) {
                        continue;
                    }
                    path[3] = m;
                    total4 = total3 + filter->ch[m];
                    if (total4 >= filter->wanted_height) {
                        record_chain(filter, path, 4);
                    }
                }
                }
            }
            }
        }
        }
    }
    return filter->chain_count;
}

__device__ bool dirt_covers(const DirtBlobFilter *filter, const float *sin_table,
        int base, int x, int y, int z, int target_x, int target_y, int target_z) {
    float angle = to_float(filter->draws[base + 3]) * PI_F;
    float spread = (float) DIRT_SIZE / 8.0f;
    double x0 = (float) x + mth_sin(sin_table, angle) * spread;
    double x1 = (float) x - mth_sin(sin_table, angle) * spread;
    double z0 = (float) z + mth_cos(sin_table, angle) * spread;
    double z1 = (float) z - mth_cos(sin_table, angle) * spread;
    double y0 = y + bounded(filter->draws[base + 4], 3) - 2;
    double y1 = y + bounded(filter->draws[base + 5], 3) - 2;
    int radii_at = base + 6;
    int i;

    for (i = 0; i < DIRT_SIZE; i++) {
        float t = (float) i / (float) DIRT_SIZE;
        double cx = x0 + t * (x1 - x0);
        double cy = y0 + t * (y1 - y0);
        double cz = z0 + t * (z1 - z0);
        double scale = to_double(filter->draws[radii_at + 2 * i],
                filter->draws[radii_at + 2 * i + 1]) * (double) DIRT_SIZE / 16.0;
        double radius = ((double) (mth_sin(sin_table, PI_F * t) + 1.0f) * scale + 1.0) / 2.0;
        double dx = (((double) target_x + 0.5) - cx) / radius;
        double dy;
        double dz;
        if (dx * dx >= 1.0) {
            continue;
        }
        dy = (((double) target_y + 0.5) - cy) / radius;
        if (dx * dx + dy * dy >= 1.0) {
            continue;
        }
        dz = (((double) target_z + 0.5) - cz) / radius;
        if (dx * dx + dy * dy + dz * dz < 1.0) {
            return true;
        }
    }
    return false;
}

__device__ bool dirt_could_supply(DirtBlobFilter *filter, const float *sin_table,
        uint64_t decoration_seed, int rel_x, int soil_y, int rel_z) {
    JavaRandom rng;
    int k;

    jr_set_feature_seed(&rng, decoration_seed, ORE_INDEX, ORE_STEP);
    for (k = 0; k < (int) (sizeof(filter->draws) / sizeof(filter->draws[0])); k++) {
        filter->draws[k] = jr_next_int_raw(&rng);
    }

    for (k = 0; k < DIRT_BLOBS; k++) {
        int m;
        for (m = 0; m <= k; m++) {
            int base = 6 * k + 2 * DIRT_SIZE * m;
            int x;
            int z;
            int y;
            int dx;
            int dz;
            int dy;

            if (base + 6 + 2 * DIRT_SIZE >= (int) (sizeof(filter->draws) / sizeof(filter->draws[0]))) {
                continue;
            }
            x = bounded(filter->draws[base], 16);
            z = bounded(filter->draws[base + 1], 16);
            y = bounded(filter->draws[base + 2], 256);
            dx = x - rel_x;
            dz = z - rel_z;
            dy = y - soil_y;
            if (dx < 0) {
                dx = -dx;
            }
            if (dz < 0) {
                dz = -dz;
            }
            if (dy < 0) {
                dy = -dy;
            }
            if (dx > DIRT_REACH || dz > DIRT_REACH || dy > DIRT_REACH) {
                continue;
            }
            if (rejects(filter->draws[base + 4], 3)
                    || rejects(filter->draws[base + 5], 3)) {
                return true;
            }
            if (dirt_covers(filter, sin_table, base, x, y, z, rel_x, soil_y, rel_z)) {
                return true;
            }
        }
    }
    return false;
}

__device__ bool soil_possible(ChainPrefilter *filter, DirtBlobFilter *dirt,
        const float *sin_table, uint64_t decoration_seed, int chains) {
    int i;
    if (filter->chain_overflow) {
        return true;
    }
    for (i = 0; i < chains; i++) {
        uint64_t chain = filter->chains[i];
        int x = chain_x(chain);
        int z = chain_z(chain);
        int soil_y = chain_base_y(chain, 0) - 1;
        if (x < 0 || x > 15 || z < 0 || z > 15) {
            return true;
        }
        if (dirt_could_supply(dirt, sin_table, decoration_seed, x, soil_y, z)) {
            return true;
        }
    }
    return false;
}

/*
 * Accepted seeds are compacted on the device through one atomicAdd, rather than the
 * host scanning a byte per tested seed. At 2^48 seeds that scan is 281 TB of host reads
 * to find a few hundred thousand answers, which would be the bottleneck all by itself.
 * Compacted, the host only ever touches what was accepted.
 */
__global__ void scan_batch_kernel(uint64_t start_seed, uint64_t count, int min_height,
        const float *sin_table, uint64_t *out, unsigned int *out_count,
        unsigned int out_capacity) {
    uint64_t idx = (uint64_t) blockIdx.x * (uint64_t) blockDim.x + (uint64_t) threadIdx.x;
    if (idx >= count) {
        return;
    }

    {
        uint64_t seed = start_seed + idx;
        ChainPrefilter filter = {0};
        DirtBlobFilter dirt = {0};
        int chains = collect_chains(&filter, seed, min_height);
        if (chains == 0 && !filter.chain_overflow) {
            return;
        }
        if (soil_possible(&filter, &dirt, sin_table, seed, chains)) {
            unsigned int slot = atomicAdd(out_count, 1u);
            if (slot < out_capacity) {
                out[slot] = seed;
            }
        }
    }
}

/*
 * Mth.SIN comes from a file Java wrote, not from this machine's libm.
 *
 * Not caution -- measured. Computing it here with sin() disagrees with Java's Math.sin
 * at entry 32768 (sin of pi): java 0x250D3132 against c 0x250D3000. That entry is
 * reachable through mth_cos whenever an angle lands near pi/2, about 2.6e-5 of tested
 * seeds. It happens not to matter today, because a value of 1e-16 is absorbed when it is
 * added to an integer coordinate, but nothing guarantees the next use of mth_sin has an
 * absorbing addition in front of it, and two libms agreeing is not a property anyone
 * should be relying on.
 *
 * Generate with:  java -jar sugarcane.jar sin-table sin_table.bin
 * Big-endian float bits, 65536 of them, as DataOutputStream writes them.
 */
static bool load_sin_table(const char *path, float *table) {
    FILE *f = fopen(path, "rb");
    int i;
    if (f == NULL) {
        fprintf(stderr, "cannot open sine table %s\n", path);
        fprintf(stderr, "  generate it with: java -jar sugarcane.jar sin-table %s\n", path);
        return false;
    }
    for (i = 0; i < SIN_TABLE_SIZE; i++) {
        int b0 = fgetc(f), b1 = fgetc(f), b2 = fgetc(f), b3 = fgetc(f);
        uint32_t bits;
        if (b3 == EOF) {
            fprintf(stderr, "sine table %s is short at entry %d\n", path, i);
            fclose(f);
            return false;
        }
        bits = ((uint32_t) b0 << 24) | ((uint32_t) b1 << 16)
                | ((uint32_t) b2 << 8) | (uint32_t) b3;
        memcpy(&table[i], &bits, sizeof(float));
    }
    fclose(f);
    return true;
}

int main(int argc, char **argv) {
    int min_height;
    uint64_t start = 0;
    uint64_t limit = TOTAL_DECORATION_SEEDS;
    uint64_t batch_size_u64 = DEFAULT_BATCH_SIZE;
    int threads_per_block = DEFAULT_THREADS_PER_BLOCK;
    uint64_t end;
    uint64_t tested = 0;
    uint64_t found = 0;
    uint64_t batch_start;
    const char *sin_table_path = NULL;
    const char *out_path = NULL;
    FILE *out = NULL;
    uint64_t *device_out = NULL;
    unsigned int *device_out_count = NULL;
    uint64_t *host_out = NULL;
    unsigned int out_capacity = 0;
    uint64_t dropped = 0;
    float *device_sin_table = NULL;
    float *host_sin_table = NULL;

    if (argc < 3 || argc > 8 || !parse_int(argv[1], &min_height) || min_height < 2) {
        usage(argv[0]);
        return 2;
    }
    sin_table_path = argv[2];
    if (argc >= 4 && (!parse_u64(argv[3], &start) || start >= TOTAL_DECORATION_SEEDS)) {
        usage(argv[0]);
        return 2;
    }
    if (argc >= 5 && (!parse_u64(argv[4], &limit) || limit == 0)) {
        usage(argv[0]);
        return 2;
    }
    if (argc >= 6) {
        out_path = argv[5];
    }
    if (argc >= 7 && (!parse_u64(argv[6], &batch_size_u64) || batch_size_u64 == 0)) {
        usage(argv[0]);
        return 2;
    }
    if (argc >= 8 && (!parse_int(argv[7], &threads_per_block) || threads_per_block <= 0)) {
        usage(argv[0]);
        return 2;
    }
    if (batch_size_u64 > (uint64_t) SIZE_MAX) {
        fprintf(stderr, "batch-size is too large for this host\n");
        return 2;
    }

    if (start > TOTAL_DECORATION_SEEDS - limit) {
        end = TOTAL_DECORATION_SEEDS;
    } else {
        end = start + limit;
        if (end > TOTAL_DECORATION_SEEDS) {
            end = TOTAL_DECORATION_SEEDS;
        }
    }

    fprintf(stderr,
            "scanning decoration seeds [%" PRIu64 ", %" PRIu64 ") for height >= %d "
            "(ocean count=%d index=%d, base y %d..%d, soil filter on)\n",
            start, end, min_height, OCEAN_COUNT, OCEAN_INDEX, BASE_MIN_Y, BASE_MAX_Y);
    fprintf(stderr,
            "cuda batch-size=%" PRIu64 ", threads-per-block=%d\n",
            batch_size_u64, threads_per_block);

    host_sin_table = (float *) malloc(sizeof(float) * SIN_TABLE_SIZE);
    if (host_sin_table == NULL) {
        fprintf(stderr, "failed to allocate host sin table\n");
        return 1;
    }
    if (!load_sin_table(sin_table_path, host_sin_table)) {
        return 1;
    }

    CUDA_CHECK(cudaMalloc((void **) &device_sin_table, sizeof(float) * SIN_TABLE_SIZE));
    CUDA_CHECK(cudaMemcpy(device_sin_table, host_sin_table,
            sizeof(float) * SIN_TABLE_SIZE, cudaMemcpyHostToDevice));

    /* A quarter of the batch: acceptance is ~2e-3 at height 8 and far less above it, so
     * this is generous, and the count is checked so an overflow is reported rather than
     * quietly truncating the answer. */
    out_capacity = (unsigned int) (batch_size_u64 / 4 + 1024);
    CUDA_CHECK(cudaMalloc((void **) &device_out, (size_t) out_capacity * sizeof(uint64_t)));
    CUDA_CHECK(cudaMalloc((void **) &device_out_count, sizeof(unsigned int)));
    host_out = (uint64_t *) malloc((size_t) out_capacity * sizeof(uint64_t));
    if (host_out == NULL) {
        fprintf(stderr, "failed to allocate the host output buffer\n");
        return 1;
    }
    if (out_path != NULL) {
        out = fopen(out_path, "wb");
        if (out == NULL) {
            fprintf(stderr, "cannot open %s for writing\n", out_path);
            return 1;
        }
    }

    for (batch_start = start; batch_start < end; batch_start += batch_size_u64) {
        uint64_t this_batch = end - batch_start;
        size_t i;
        uint64_t blocks;

        if (this_batch > batch_size_u64) {
            this_batch = batch_size_u64;
        }
        blocks = (this_batch + (uint64_t) threads_per_block - 1) / (uint64_t) threads_per_block;

        unsigned int zero = 0;
        unsigned int accepted_here = 0;
        CUDA_CHECK(cudaMemcpy(device_out_count, &zero, sizeof(unsigned int),
                cudaMemcpyHostToDevice));
        scan_batch_kernel<<<(unsigned int) blocks, threads_per_block>>>(
                batch_start, this_batch, min_height, device_sin_table,
                device_out, device_out_count, out_capacity);
        CUDA_CHECK(cudaGetLastError());
        CUDA_CHECK(cudaDeviceSynchronize());
        CUDA_CHECK(cudaMemcpy(&accepted_here, device_out_count, sizeof(unsigned int),
                cudaMemcpyDeviceToHost));
        if (accepted_here > out_capacity) {
            dropped += accepted_here - out_capacity;
            accepted_here = out_capacity;
        }
        CUDA_CHECK(cudaMemcpy(host_out, device_out,
                (size_t) accepted_here * sizeof(uint64_t), cudaMemcpyDeviceToHost));

        if (out != NULL) {
            /* Sorted, so the file is deterministic even though atomicAdd is not. */
            qsort(host_out, accepted_here, sizeof(uint64_t), compare_u64);
            fwrite(host_out, sizeof(uint64_t), accepted_here, out);
        } else {
            qsort(host_out, accepted_here, sizeof(uint64_t), compare_u64);
            for (i = 0; i < (size_t) accepted_here; i++) {
                printf("%" PRIu64 "\n", host_out[i]);
            }
            fflush(stdout);
        }
        found += accepted_here;
        tested += this_batch;
    }

    if (out != NULL) {
        fclose(out);
    }
    fprintf(stderr, "tested=%" PRIu64 " found=%" PRIu64 " dropped=%" PRIu64 "\n",
            tested, found, dropped);
    if (dropped > 0) {
        fprintf(stderr, "WARNING: the output buffer overflowed, %" PRIu64
                " accepted seeds were lost -- lower batch-size\n", dropped);
    }

    CUDA_CHECK(cudaFree(device_out));
    CUDA_CHECK(cudaFree(device_out_count));
    CUDA_CHECK(cudaFree(device_sin_table));
    free(host_out);
    free(host_sin_table);
    return dropped > 0 ? 4 : 0;
}
