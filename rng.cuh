#ifndef CUDA_FUNCTION
    #ifdef USE_CUDA
        #define CUDA_FUNCTION inline __device__ 
    #else
        #define CUDA_FUNCTION
    #endif
#endif

#define LOOT_LIB_FUNCTION CUDA_FUNCTION

#define XRSR_MIX1          0xbf58476d1ce4e5b9
#define XRSR_MIX2          0x94d049bb133111eb
#define XRSR_MIX1_INVERSE  0x96de1b173f119089
#define XRSR_MIX2_INVERSE  0x319642b2d24d8ec3
#define XRSR_SILVER_RATIO  0x6a09e667f3bcc909
#define XRSR_GOLDEN_RATIO  0x9e3779b97f4a7c15

CUDA_FUNCTION uint64_t mix64(uint64_t a) {
	a = (a ^ a >> 30) * XRSR_MIX1;
	a = (a ^ a >> 27) * XRSR_MIX2;
	return a ^ a >> 31;
}

LOOT_LIB_FUNCTION static inline void set_seed(uint64_t *seed, uint64_t value)
{
    *seed = (value ^ 0x5deece66d) & ((1ULL << 48) - 1);
}

LOOT_LIB_FUNCTION static inline int _next(uint64_t *seed, const int bits)
{
    *seed = (*seed * 0x5deece66d + 0xb) & ((1ULL << 48) - 1);
    return (int) ((int64_t)*seed >> (48 - bits));
}

LOOT_LIB_FUNCTION static inline int next_int(uint64_t *seed, const int n)
{
    int bits, val;
    const int m = n - 1;

    if ((m & n) == 0) {
        uint64_t x = n * (uint64_t)_next(seed, 31);
        return (int) ((int64_t) x >> 31);
    }

    do {
        bits = _next(seed, 31);
        val = bits % n;
    }
    while (bits - val + m < 0);
    return val;
}

LOOT_LIB_FUNCTION static inline uint64_t next_long(uint64_t *seed)
{
    return ((uint64_t) _next(seed, 32) << 32) + _next(seed, 32);
}

LOOT_LIB_FUNCTION static inline float next_float(uint64_t *seed)
{
    return _next(seed, 24) / (float) (1 << 24);
}

LOOT_LIB_FUNCTION static inline double next_double(uint64_t *seed)
{
    uint64_t x = (uint64_t)_next(seed, 26);
    x <<= 27;
    x += _next(seed, 27);
    return (int64_t) x / (double) (1ULL << 53);
}

LOOT_LIB_FUNCTION static inline int next_int_bounded(uint64_t *seed, int min, int max) {
    if (min >= max) {
        return min;
    }
    return next_int(seed, max - min + 1) + min;
}
// CUDA_FUNCTION uint64_t rotl64(uint64_t x, uint8_t b)
// {
    // return (x << b) | (x >> (64-b));
// }:w

// typedef struct {
//     uint64_t lo, hi;
// } Xoroshiro;

// CUDA_FUNCTION static void xSetSeed(Xoroshiro *xr, uint64_t value)
// {
//     const uint64_t XL = 0x9e3779b97f4a7c15ULL;
//     const uint64_t XH = 0x6a09e667f3bcc909ULL;
//     const uint64_t A = 0xbf58476d1ce4e5b9ULL;
//     const uint64_t B = 0x94d049bb133111ebULL;
//     uint64_t l = value ^ XH;
//     uint64_t h = l + XL;
//     l = (l ^ (l >> 30)) * A;
//     h = (h ^ (h >> 30)) * A;
//     l = (l ^ (l >> 27)) * B;
//     h = (h ^ (h >> 27)) * B;
//     l = l ^ (l >> 31);
//     h = h ^ (h >> 31);
//     xr->lo = l;
//     xr->hi = h;
// }

// CUDA_FUNCTION static void xSetFeatureSeed(Xoroshiro *xr, uint64_t p_190065_, int p_190066_, int p_190067_) {
//     uint64_t i = p_190065_ + (long)p_190066_ + (long)(10000 * p_190067_);
//     xSetSeed(xr, i);
// }

// CUDA_FUNCTION static uint64_t xNextLong(Xoroshiro *xr) {
//     uint64_t l = xr->lo;
//     uint64_t h = xr->hi;
//     uint64_t n = rotl64(l + h, 17) + l;
//     h ^= l;
//     xr->lo = rotl64(l, 49) ^ h ^ (h << 21);
//     xr->hi = rotl64(h, 28);
//     return n;
// }

// CUDA_FUNCTION static uint64_t xSetDecorationSeed(Xoroshiro *xr, uint64_t p_64691_, int p_64692_, int p_64693_) {
//     // this.setSeed(p_64691_);
//     xSetSeed(xr, p_64691_);
//     uint64_t i = xNextLong(xr) | 1L;
//     uint64_t j = xNextLong(xr) | 1L;
//     uint64_t k = (uint64_t)p_64692_ * i + (uint64_t)p_64693_ * j ^ p_64691_;
//     // this.setSeed(k);
//     xSetSeed(xr, k);
//     return k;
// }
//
// // CUDA_FUNCTION static int xNextInt(Xoroshiro *xr, uint32_t n)
// // {
// //     uint64_t r = (xNextLong(xr) & 0xFFFFFFFF) * n;
// //     if ((uint32_t)r < n)
// //     {
// //         while ((uint32_t)r < (~n + 1) % n)
// //         {
// //             r = (xNextLong(xr) & 0xFFFFFFFF) * n;
// //         }
// //     }
// //     return r >> 32;
// // }
//
// // CUDA_FUNCTION static double xNextDouble(Xoroshiro *xr)
// // {
// //     return (xNextLong(xr) >> (64-53)) * 1.1102230246251565E-16;
// // }
//
// // CUDA_FUNCTION static float xNextFloat(Xoroshiro *xr)
// // {
// //     return (xNextLong(xr) >> (64-24)) * 5.9604645E-8F;
// // }
//
// // CUDA_FUNCTION static void xSkipN(Xoroshiro *xr, int count)
// // {
// //     while (count --> 0)
// //         xNextLong(xr);
// // }
//
// // CUDA_FUNCTION static uint64_t xNextLongJ(Xoroshiro *xr)
// // {
// //     int32_t a = xNextLong(xr) >> 32;
// //     int32_t b = xNextLong(xr) >> 32;
// //     return ((uint64_t)a << 32) + b;
// // }
//
// typedef struct {
//     Xoroshiro internal;
//     int num_calls;
// } RNG; // Bruh I really didn't want to have to do this.
//
// CUDA_FUNCTION RNG rng_new() {
//     RNG rng;
//     // Xoroshiro xr;
//     rng.internal = {0};
//     return rng;
// }
//
// CUDA_FUNCTION static void rng_set_seed(RNG *rng, uint64_t seed) {
//     seed ^= XRSR_SILVER_RATIO;
//     rng->internal.lo = mix64(seed);
//     rng->internal.hi = mix64(seed + XRSR_GOLDEN_RATIO);
// }
//
// CUDA_FUNCTION static void rng_set_internal(RNG *rng, uint64_t lo, uint64_t hi) {
//     rng->internal.lo = lo;
//     rng->internal.hi = hi;
// }
//
// CUDA_FUNCTION static uint64_t rng_next(RNG *rng, int32_t bits) {
//     rng->num_calls++;
//     return xNextLong(&rng->internal) >> (64 - bits);
// }
//
// CUDA_FUNCTION static int32_t rng_next_int(RNG *rng, uint32_t bound) {
//     uint32_t r = rng_next(rng, 31);
//     uint32_t m = bound - 1;
//     if ((bound & m) == 0) {
//         // (int)((long)p_188504_ * (long)this.next(31) >> 31);
//         r = (uint32_t)((uint64_t)bound * (uint64_t)r >> 31);
//     }
//     else {
//         for (uint32_t u = r; (int32_t)(u - (r = u % bound) + m) < 0; u = rng_next(rng, 31));
//     }
//     return r;
// }
//
// CUDA_FUNCTION static int32_t rng_next_int_bounded(RNG *rng, int32_t min, int32_t max) {
//     if (min >= max) {
//         return min;
//     } 
//     return rng_next_int(rng, max - min + 1) + min;
// }
//
// CUDA_FUNCTION static float rng_next_float(RNG *rng) {
//     return xNextFloat(&rng->internal);
// }
//
// CUDA_FUNCTION static double rng_next_double(RNG *rng) { // whoops!
//     int32_t i = rng_next(rng, 26);
//     int32_t j = rng_next(rng, 27);
//     uint64_t k = ((uint64_t)i << 27) + (uint64_t)j;
//     return (double)k * (double)1.110223E-16F;
// }
//
// CUDA_FUNCTION static int rng_next_between_inclusive(RNG *rng, int i, int j) {
//     return rng_next_int(rng, j - i + 1) + i;
// }
//
// CUDA_FUNCTION static uint64_t rng_next_long(RNG *rng) {
//     int32_t i = rng_next(rng, 32);
//     int32_t j = rng_next(rng, 32);
//     uint64_t k = (uint64_t)i << 32;
//     return k + (uint64_t)j;
// }
//
// CUDA_FUNCTION static uint64_t rng_set_feature_seed(RNG *rng, uint64_t p_190065_, int32_t p_190066_, int32_t p_190067_) {
//     uint64_t i = p_190065_ + (uint64_t)p_190066_ + (uint64_t)(10000 * p_190067_);
//     //printf("Salt = %" PRIu64 "\n", (uint64_t)p_190066_ + (uint64_t)(10000 * p_190067_));
//     rng_set_seed(rng, i);
//     return i;
// }
//
// CUDA_FUNCTION uint64_t reverse_decoration_seed(uint64_t decorator_seed, int index, int step) {
//     return decorator_seed - (uint64_t)index - 10000L * (uint64_t)step;
// }
//
// CUDA_FUNCTION static uint64_t rng_set_decoration_seed(RNG *rng, uint64_t world_seed, int32_t x, int32_t z) {
//     rng_set_seed(rng, world_seed);
//
//     uint64_t a = rng_next_long(rng) | 1L;
//     uint64_t b = rng_next_long(rng) | 1L;
//
//     // printf("the k to recover = %" PRIu64 "\n", (a * (uint64_t)x + b * (uint64_t)z));
//     uint64_t k = (a * (uint64_t)x + b * (uint64_t)z) ^ world_seed;
//     // printf("real k = %" PRIu64 "\n", k);
//     // printf("invert k = %" PRIu64 "\n", k ^ world_seed);
//     rng_set_seed(rng, k);
//     return k;
// }
