// One Perlin octave down a column, on the GPU, bit-for-bit identical to ColumnPerlin.
//
// Stage one of moving the search onto the device. Noise is 54% of the chunk build and the
// chunk build is 83% of the per-candidate cost, and on an idle RTX 4080 the FP64 form runs
// 2.3x the 24-thread CPU (FINDINGS 6am). Nothing downstream is worth writing until the
// octave underneath it is exact, so this exists to be verified rather than to be fast.
//
// Transcribed from gen/ColumnPerlin, which is itself transcribed from the bytecode of
// PerlinNoiseSampler.sample and held to it by TruncatedNoiseTest. Same lookups in the same
// order, same arguments to grad, same lerp3 nesting. Build with -fmad=false: contraction
// would be more accurate than Java's separate multiply and add, and therefore different.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <cuda_runtime.h>

__constant__ double GX[16], GY[16], GZ[16];

__device__ __forceinline__ int floorD(double d) {
    int i = (int) d;
    return d < (double) i ? i - 1 : i;
}

__device__ __forceinline__ double smoothStep(double t) {
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

__device__ __forceinline__ double lerp(double t, double a, double b) {
    return a + t * (b - a);
}

__device__ __forceinline__ double grad(int hash, double x, double y, double z) {
    int h = hash & 15;
    return GX[h] * x + GY[h] * y + GZ[h] * z;
}

struct Column {
    int sectionZ, permX, permX1;
    double localX, localZ, localX1, localZ1, fadeX, fadeZ;
};

__device__ void beginColumn(const unsigned char* p, double x, double z,
                            double originX, double originZ, Column& c) {
    double dx = x + originX;
    double dz = z + originZ;
    int sectionX = floorD(dx);
    c.sectionZ = floorD(dz);
    c.localX = dx - sectionX;
    c.localZ = dz - c.sectionZ;
    c.localX1 = c.localX - 1.0;
    c.localZ1 = c.localZ - 1.0;
    c.fadeX = smoothStep(c.localX);
    c.fadeZ = smoothStep(c.localZ);
    c.permX = p[sectionX & 255];
    c.permX1 = p[(sectionX + 1) & 255];
}

__device__ double sampleY(const unsigned char* p, const Column& c, double originY,
                          double y, double yScale, double yMax) {
    double dy = y + originY;
    int sectionY = floorD(dy);
    double local = dy - sectionY;
    double step = 0.0;
    if (yScale != 0.0) {
        double capped = yMax < local ? yMax : local;      // Math.min
        step = (double) floorD(capped / yScale) * yScale;
    }
    double ly = local - step;
    double ly1 = ly - 1.0;

    int i = c.permX + sectionY;
    int j = c.permX1 + sectionY;
    int k = p[i & 255] + c.sectionZ;
    int l = p[j & 255] + c.sectionZ;
    int m = p[(i + 1) & 255] + c.sectionZ;
    int n = p[(j + 1) & 255] + c.sectionZ;

    double d0 = grad(p[k & 255],       c.localX,  ly,  c.localZ);
    double d1 = grad(p[l & 255],       c.localX1, ly,  c.localZ);
    double d2 = grad(p[m & 255],       c.localX,  ly1, c.localZ);
    double d3 = grad(p[n & 255],       c.localX1, ly1, c.localZ);
    double d4 = grad(p[(k + 1) & 255], c.localX,  ly,  c.localZ1);
    double d5 = grad(p[(l + 1) & 255], c.localX1, ly,  c.localZ1);
    double d6 = grad(p[(m + 1) & 255], c.localX,  ly1, c.localZ1);
    double d7 = grad(p[(n + 1) & 255], c.localX1, ly1, c.localZ1);

    double fy = smoothStep(local);
    return lerp(c.fadeZ,
                lerp(fy, lerp(c.fadeX, d0, d1), lerp(c.fadeX, d2, d3)),
                lerp(fy, lerp(c.fadeX, d4, d5), lerp(c.fadeX, d6, d7)));
}

// Inputs are derived from the index with exactly representable arithmetic, so both sides
// agree on them without having to ship them across.
__device__ __host__ __forceinline__ void inputsFor(long i, double& x, double& z, double& y,
                                                   double& yScale, double& yMax) {
    x = (double) (i % 4096) * 0.25 - 512.0;
    z = (double) ((i / 4096) % 4096) * 0.25 - 512.0;
    y = (double) (i % 384) * 0.5;
    yScale = (i % 3 == 0) ? 0.0 : 4.0;
    yMax = 32.0;
}

__global__ void run(const unsigned char* __restrict__ p, double originX, double originY,
                    double originZ, long n, unsigned long long* __restrict__ checksum,
                    double* __restrict__ head) {
    long idx = blockIdx.x * (long) blockDim.x + threadIdx.x;
    long stride = gridDim.x * (long) blockDim.x;
    unsigned long long acc = 0;
    for (long i = idx; i < n; i += stride) {
        double x, z, y, ys, ym;
        inputsFor(i, x, z, y, ys, ym);
        Column c;
        beginColumn(p, x, z, originX, originZ, c);
        double v = sampleY(p, c, originY, y, ys, ym);
        unsigned long long bits;
        memcpy(&bits, &v, sizeof(bits));
        // Order-independent so thread scheduling cannot change the answer.
        acc ^= bits * 0x9E3779B97F4A7C15ULL + (unsigned long long) i;
        if (i < 4) {
            head[i] = v;
        }
    }
    atomicXor(checksum, acc);
}

int main(int argc, char** argv) {
    if (argc < 6) {
        fprintf(stderr, "usage: noise_column <perm512hex> <originX> <originY> <originZ> <count>\n");
        return 2;
    }
    unsigned char perm[256];
    const char* hex = argv[1];
    if (strlen(hex) != 512) {
        fprintf(stderr, "permutation must be 512 hex chars, got %zu\n", strlen(hex));
        return 2;
    }
    for (int i = 0; i < 256; i++) {
        char b[3] = {hex[2 * i], hex[2 * i + 1], 0};
        perm[i] = (unsigned char) strtol(b, nullptr, 16);
    }
    double ox = atof(argv[2]), oy = atof(argv[3]), oz = atof(argv[4]);
    long n = atol(argv[5]);

    const double gx[16] = {1,-1,1,-1,1,-1,1,-1,0,0,0,0,1,0,-1,0};
    const double gy[16] = {1,1,-1,-1,0,0,0,0,1,-1,1,-1,1,-1,1,-1};
    const double gz[16] = {0,0,0,0,1,1,-1,-1,1,1,-1,-1,0,1,0,-1};
    cudaMemcpyToSymbol(GX, gx, sizeof(gx));
    cudaMemcpyToSymbol(GY, gy, sizeof(gy));
    cudaMemcpyToSymbol(GZ, gz, sizeof(gz));

    unsigned char* dp;
    unsigned long long* dsum;
    double* dhead;
    cudaMalloc(&dp, 256);
    cudaMemcpy(dp, perm, 256, cudaMemcpyHostToDevice);
    cudaMalloc(&dsum, sizeof(unsigned long long));
    cudaMemset(dsum, 0, sizeof(unsigned long long));
    cudaMalloc(&dhead, sizeof(double) * 4);

    cudaEvent_t a, b;
    cudaEventCreate(&a);
    cudaEventCreate(&b);
    cudaEventRecord(a);
    run<<<512, 256>>>(dp, ox, oy, oz, n, dsum, dhead);
    cudaEventRecord(b);
    cudaEventSynchronize(b);
    // A rejected launch leaves nothing queued, so the synchronise succeeds and only
    // cudaGetLastError knows. FINDINGS 6ai: this exact check is why a card with no
    // matching cubin reports itself instead of silently accepting nothing.
    cudaError_t err = cudaGetLastError();
    if (err != cudaSuccess) {
        fprintf(stderr, "kernel launch failed: %s\n", cudaGetErrorString(err));
        return 1;
    }

    unsigned long long sum = 0;
    double head[4] = {0, 0, 0, 0};
    cudaMemcpy(&sum, dsum, sizeof(sum), cudaMemcpyDeviceToHost);
    cudaMemcpy(head, dhead, sizeof(head), cudaMemcpyDeviceToHost);
    float ms = 0;
    cudaEventElapsedTime(&ms, a, b);

    printf("checksum %016llx\n", sum);
    for (int i = 0; i < 4; i++) {
        unsigned long long bits;
        memcpy(&bits, &head[i], sizeof(bits));
        printf("head%d %016llx\n", i, bits);
    }
    printf("rate %.1f M samples/s\n", n / (ms * 1e3));
    return 0;
}
