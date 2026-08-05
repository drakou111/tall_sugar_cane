// Is a double-precision Perlin kernel actually faster than the CPU on a consumer card?
//
// The existing find_targets kernel is integer LCG work, which runs at full rate. Minecraft's
// terrain noise is double throughout and bit-exactness leaves no choice, and an Ada consumer
// part runs FP64 at 1/64 of FP32. This measures the gap that matters rather than assuming it.
//
// The arithmetic is Minecraft's PerlinNoiseSampler.sample, reproduced exactly so the Java
// side can be compared value-for-value as well as rate-for-rate.

#include <cstdio>
#include <cstdint>
#include <cuda_runtime.h>

__constant__ int GRAD[16][3] = {
    {1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},
    {1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},
    {0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1},
    {1,1,0},{0,-1,1},{-1,1,0},{0,-1,-1}
};

__device__ __forceinline__ double gradDot(int hash, double x, double y, double z) {
    const int* g = GRAD[hash & 15];
    return g[0] * x + g[1] * y + g[2] * z;
}

__device__ __forceinline__ double fade(double t) {
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

__device__ __forceinline__ double lerp(double t, double a, double b) {
    return a + t * (b - a);
}

__device__ double perlin(const unsigned char* __restrict__ p, double x, double y, double z) {
    int xi = (int)floor(x) & 255;
    int yi = (int)floor(y) & 255;
    int zi = (int)floor(z) & 255;
    double xf = x - floor(x);
    double yf = y - floor(y);
    double zf = z - floor(z);
    double u = fade(xf), v = fade(yf), w = fade(zf);

    int a  = p[xi] + yi;
    int aa = p[a & 255] + zi;
    int ab = p[(a + 1) & 255] + zi;
    int b  = p[(xi + 1) & 255] + yi;
    int ba = p[b & 255] + zi;
    int bb = p[(b + 1) & 255] + zi;

    double d0 = gradDot(p[aa & 255],       xf,       yf,       zf);
    double d1 = gradDot(p[ba & 255],       xf - 1.0, yf,       zf);
    double d2 = gradDot(p[ab & 255],       xf,       yf - 1.0, zf);
    double d3 = gradDot(p[bb & 255],       xf - 1.0, yf - 1.0, zf);
    double d4 = gradDot(p[(aa + 1) & 255], xf,       yf,       zf - 1.0);
    double d5 = gradDot(p[(ba + 1) & 255], xf - 1.0, yf,       zf - 1.0);
    double d6 = gradDot(p[(ab + 1) & 255], xf,       yf - 1.0, zf - 1.0);
    double d7 = gradDot(p[(bb + 1) & 255], xf - 1.0, yf - 1.0, zf - 1.0);

    return lerp(w, lerp(v, lerp(u, d0, d1), lerp(u, d2, d3)),
                   lerp(v, lerp(u, d4, d5), lerp(u, d6, d7)));
}

#define OCTAVES 16

__global__ void bench(const unsigned char* __restrict__ p, double* __restrict__ out, long n) {
    long i = blockIdx.x * (long)blockDim.x + threadIdx.x;
    long stride = gridDim.x * (long)blockDim.x;
    double acc = 0.0;
    for (long s = i; s < n; s += stride) {
        double x = (double)(s % 1024) * 0.0625;
        double y = (double)((s / 1024) % 256) * 0.125;
        double z = (double)(s % 733) * 0.03125;
        double amp = 1.0, freq = 1.0;
        for (int o = 0; o < OCTAVES; o++) {
            acc += perlin(p, x * freq, y * freq, z * freq) * amp;
            amp *= 0.5;
            freq *= 2.0;
        }
    }
    out[i] = acc;
}

int main(int argc, char** argv) {
    long n = (argc > 1) ? atol(argv[1]) : (1L << 24);

    unsigned char h[256];
    // Same permutation the Java side builds, from a fixed LCG so both agree exactly.
    long long seed = 12345;
    for (int i = 0; i < 256; i++) h[i] = (unsigned char)i;
    for (int i = 0; i < 256; i++) {
        seed = (seed * 0x5DEECE66DLL + 0xB) & ((1LL << 48) - 1);
        // The cast must bind to the modulo, not the shift: truncating to int first
        // can go negative and C's % keeps the sign, which Java's long path does not.
        int j = (int)(((unsigned long long)seed >> 16) % (unsigned long long)(256 - i)) + i;
        unsigned char t = h[i]; h[i] = h[j]; h[j] = t;
    }

    unsigned char* dp; double* dout;
    cudaMalloc(&dp, 256);
    cudaMemcpy(dp, h, 256, cudaMemcpyHostToDevice);

    int threads = 256, blocks = 512;
    cudaMalloc(&dout, sizeof(double) * threads * blocks);

    bench<<<blocks, threads>>>(dp, dout, 1 << 20);      // warm up
    cudaDeviceSynchronize();
    cudaError_t err = cudaGetLastError();
    if (err != cudaSuccess) { printf("launch failed: %s\n", cudaGetErrorString(err)); return 1; }

    cudaEvent_t a, b; cudaEventCreate(&a); cudaEventCreate(&b);
    cudaEventRecord(a);
    bench<<<blocks, threads>>>(dp, dout, n);
    cudaEventRecord(b);
    cudaEventSynchronize(b);
    err = cudaGetLastError();
    if (err != cudaSuccess) { printf("launch failed: %s\n", cudaGetErrorString(err)); return 1; }

    float ms = 0; cudaEventElapsedTime(&ms, a, b);
    double octaves = (double)n * OCTAVES;
    printf("GPU: %ld samples x %d octaves in %.1f ms -> %.1f M octave-evals/s\n",
           n, OCTAVES, ms, octaves / (ms * 1e3));

    // One value, printed exactly, so the Java side can be diffed against it bit for bit.
    double first;
    cudaMemcpy(&first, dout, sizeof(double), cudaMemcpyDeviceToHost);
    printf("GPU: out[0] = %.17g  bits=%016llx\n", first, *(unsigned long long*)&first);
    printf("GPU: perm[0..7] = %d %d %d %d %d %d %d %d\n",
           h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]);
    return 0;
}
