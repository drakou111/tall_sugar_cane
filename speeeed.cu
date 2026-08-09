#include <stdio.h>
#include <stdlib.h>
#include <stdbool.h>
#include <assert.h>
#include <inttypes.h>
#include <stdint.h>
#include <getopt.h>

#include <iostream>
#include <vector>
#include <thread>
#include <mutex>
#include <fstream>
#include <atomic>
#include <algorithm>
#include <cuda_runtime.h>

#define CUDA_FUNCTION __host__ __device__
#include "rng.cuh"

#define MAX_RESULTS_PER_BATCH 65536

// --- CUDA Helper Macro ---
#define CUDA_CHECK(call) \
    do { \
        cudaError_t err = call; \
        if (err != cudaSuccess) { \
            fprintf(stderr, "CUDA error at %s:%d: %s\n", __FILE__, __LINE__, cudaGetErrorString(err)); \
            exit(EXIT_FAILURE); \
        } \
    } while (0)

// --- Active CUDA / RNG Helpers ---

__host__ __device__ static uint64_t setFeatureSeed(uint64_t *rng, uint64_t baseSeed, int32_t x, int32_t z) {
    uint64_t i = baseSeed + (uint64_t)x + (uint64_t)(10000 * z);
    set_seed(rng, i); 
    return i;
}

__host__ __device__ int get_offset(uint64_t *rng, int bound) {
    int a = next_int(rng, bound);
    int b = next_int(rng, bound);
    return a - b;
}

__device__ __host__ void get_dirt(uint64_t chunk_seed, int *dx, int *dy, int *dz) {
    uint64_t rng;
    setFeatureSeed(&rng, chunk_seed, 0, 6);

    (*dx) = next_int(&rng, 16);
    (*dz) = next_int(&rng, 16);
    (*dy) = next_int(&rng, 256);
}

__device__ __host__ bool run_check(uint64_t *rng, int tx, int ty, int tz, uint64_t chunk_seed) {
    int dirt_dx, dirt_dy, dirt_dz;
    get_dirt(chunk_seed, &dirt_dx, &dirt_dy, &dirt_dz);
    
    int dirt_offset_x = dirt_dx - tx;
    int dirt_offset_y = dirt_dy - ty;
    if (!((dirt_offset_x >= -1 && dirt_offset_x <= 2) && (dirt_offset_y <= 3 && dirt_offset_y >= 0) && dirt_dz == 0)) {
        return false; 
    }

    #pragma unroll
    for (int n = 0; n < 10; n++) {
        int dx = next_int(rng, 16);
        int dz = next_int(rng, 16);
        int dy = next_int(rng, 126);
        for (int a = 0; a < 20; a++) {
            int ox = get_offset(rng, 5);
            int oy = get_offset(rng, 1);
            int oz = get_offset(rng, 5);
            if (ox + dx == tx && oy + dy == ty && oz + dz == tz) {
                int height = (2 + next_int(rng, next_int(rng, 2 + 1) + 1));
                if (height >= 4) {
                    return true;
                }
            }
        } 
    }
    return false;
}

// --- GPU Search Kernel ---

__global__ void search_kernel(uint64_t s, uint64_t *out_seeds, uint32_t *out_count, uint32_t max_results) {
    uint64_t upper60 = threadIdx.x + blockDim.x * (uint64_t)blockIdx.x + s;
    uint64_t chunk_seed = (upper60 << 4) | (154772930895857ull & 0b1111);

    uint64_t rng;
    setFeatureSeed(&rng, chunk_seed, 5, 8);

    if (run_check(&rng, 3, 19, -2, chunk_seed)) {
        uint32_t idx = atomicAdd(out_count, 1);
        if (idx < max_results) {
            out_seeds[idx] = chunk_seed;
        }
    }
}

// --- Multi-GPU Host Worker Thread ---

void gpu_worker(int gpu_id, uint64_t end_batch, std::atomic<uint64_t> &next_batch, std::mutex &file_mutex, std::ofstream &outfile) {
    CUDA_CHECK(cudaSetDevice(gpu_id));

    // Allocate memory buffers on host and GPU
    uint64_t *d_out_seeds = nullptr;
    uint32_t *d_out_count = nullptr;
    CUDA_CHECK(cudaMalloc(&d_out_seeds, MAX_RESULTS_PER_BATCH * sizeof(uint64_t)));
    CUDA_CHECK(cudaMalloc(&d_out_count, sizeof(uint32_t)));

    std::vector<uint64_t> h_seeds(MAX_RESULTS_PER_BATCH);

    while (true) {
        // Dynamically claim next batch
        uint64_t batch = next_batch.fetch_add(1);
        if (batch >= end_batch) {
            break;
        }

        // Reset batch count on GPU
        CUDA_CHECK(cudaMemset(d_out_count, 0, sizeof(uint32_t)));

        // Launch kernel
        search_kernel<<<16777216, 256>>>(batch * (1ull << 32), d_out_seeds, d_out_count, MAX_RESULTS_PER_BATCH);
        CUDA_CHECK(cudaDeviceSynchronize());

        // Get match count
        uint32_t count = 0;
        CUDA_CHECK(cudaMemcpy(&count, d_out_count, sizeof(uint32_t), cudaMemcpyDeviceToHost));

        if (count > 0) {
            uint32_t copy_count = std::min(count, (uint32_t)MAX_RESULTS_PER_BATCH);
            CUDA_CHECK(cudaMemcpy(h_seeds.data(), d_out_seeds, copy_count * sizeof(uint64_t), cudaMemcpyDeviceToHost));

            // Safely write results to file
            std::lock_guard<std::mutex> lock(file_mutex);
            for (uint32_t i = 0; i < copy_count; ++i) {
                outfile << h_seeds[i] << "\n";
            }
            outfile.flush();
        }
    }

    CUDA_CHECK(cudaFree(d_out_seeds));
    CUDA_CHECK(cudaFree(d_out_count));
}

// --- CLI Usage Helper ---

void print_usage(const char *prog_name) {
    printf("Usage: %s -g <num_gpus> -s <start_batch> -e <end_batch> [-o <output_file>]\n", prog_name);
    printf("Options:\n");
    printf("  -g, --gpus <int>         Number of GPUs to use\n");
    printf("  -s, --start-batch <int>  Batch index to start from\n");
    printf("  -e, --end-batch <int>    Batch index to end at (exclusive)\n");
    printf("  -o, --output <string>    Output file path (default: results.txt)\n");
}

// --- Main Function ---

int main(int argc, char *argv[]) {
    int num_gpus = 0;
    uint64_t start_batch = 0;
    uint64_t end_batch = 0;
    bool has_gpus = false, has_start = false, has_end = false;
    std::string output_filename = "results.txt";

    static struct option long_options[] = {
        {"gpus",        required_argument, 0, 'g'},
        {"start-batch", required_argument, 0, 's'},
        {"end-batch",   required_argument, 0, 'e'},
        {"output",      required_argument, 0, 'o'},
        {0, 0, 0, 0}
    };

    int opt;
    while ((opt = getopt_long(argc, argv, "g:s:e:o:", long_options, NULL)) != -1) {
        switch (opt) {
            case 'g':
                num_gpus = atoi(optarg);
                has_gpus = true;
                break;
            case 's':
                start_batch = strtoull(optarg, NULL, 10);
                has_start = true;
                break;
            case 'e':
                end_batch = strtoull(optarg, NULL, 10);
                has_end = true;
                break;
            case 'o':
                output_filename = optarg;
                break;
            default:
                print_usage(argv[0]);
                return EXIT_FAILURE;
        }
    }

    if (!has_gpus || !has_start || !has_end || num_gpus <= 0 || end_batch <= start_batch) {
        fprintf(stderr, "Error: Missing or invalid required command line arguments.\n");
        print_usage(argv[0]);
        return EXIT_FAILURE;
    }

    int device_count = 0;
    CUDA_CHECK(cudaGetDeviceCount(&device_count));
    if (num_gpus > device_count) {
        fprintf(stderr, "Warning: Requested %d GPUs, but system only has %d GPUs. Using %d GPUs.\n", num_gpus, device_count, device_count);
        num_gpus = device_count;
    }

    std::ofstream outfile(output_filename, std::ios::out | std::ios::app);
    if (!outfile.is_open()) {
        fprintf(stderr, "Error: Could not open output file %s\n", output_filename.c_str());
        return EXIT_FAILURE;
    }

    std::atomic<uint64_t> next_batch(start_batch);
    std::mutex file_mutex;
    std::vector<std::thread> threads;

    printf("Starting search across %d GPU(s) for batches [%" PRIu64 ", %" PRIu64 ")... Output file: %s\n",
           num_gpus, start_batch, end_batch, output_filename.c_str());

    for (int i = 0; i < num_gpus; ++i) {
        threads.emplace_back(gpu_worker, i, end_batch, std::ref(next_batch), std::ref(file_mutex), std::ref(outfile));
    }

    for (auto &t : threads) {
        t.join();
    }

    outfile.close();
    printf("Completed search. Results saved to %s\n", output_filename.c_str());

    return EXIT_SUCCESS;
}
