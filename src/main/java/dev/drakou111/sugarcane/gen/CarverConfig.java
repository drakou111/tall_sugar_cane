package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;

/**
 * Which chunks act as carver start chunks in 1.16.1, and with what seed.
 *
 * <p>From {@code ChunkGenerator.applyCarvers}: for the chunk being generated,
 * every chunk within a radius of 8 is considered a candidate start chunk. For
 * each, and for each carver in the biome's list for that carving step, the RNG
 * is seeded with {@code setLargeFeatureSeed(levelSeed + carverIndex, startX,
 * startZ)} and the carver runs if {@code nextFloat() <= probability}.
 *
 * <p>Two subtleties that are easy to get wrong:
 * <ul>
 *   <li>the carver list comes from the biome at the <em>generating</em> chunk's
 *       corner - {@code getNoiseBiome(chunkX << 2, 0, chunkZ << 2)} - not from
 *       the start chunk's biome;</li>
 *   <li>the salt is the carver's index within that biome's list for the step, so
 *       AIR carvers use indices 0 and 1, and LIQUID carvers restart at 0.</li>
 * </ul>
 *
 * <p>Only ocean biomes register LIQUID carvers, which is exactly why the
 * stackable geometry appears only in oceans (see FINDINGS section 5c).
 */
public final class CarverConfig {

    public static final int CARVE_RADIUS = 8;

    /** Sea level, which is where the carvers switch between air and water. */
    public static final int SEA_LEVEL = 63;

    /** Probabilities from {@code BiomeDefaultFeatures}. */
    public static final float CAVE_LAND = 0.14285715f;
    public static final float CAVE_OCEAN = 0.06666667f;
    public static final float CANYON = 0.02f;
    public static final float UNDERWATER_CANYON = 0.02f;
    public static final float UNDERWATER_CAVE = 0.06666667f;

    private CarverConfig() {
    }

    /** Carvers registered at GenerationStep.Carving.AIR, in list order. */
    public static float[] airCarvers(boolean oceanBiome) {
        return oceanBiome
                ? new float[]{CAVE_OCEAN, CANYON}
                : new float[]{CAVE_LAND, CANYON};
    }

    /** Carvers at GenerationStep.Carving.LIQUID; only oceans have any. */
    public static float[] liquidCarvers(boolean oceanBiome) {
        return oceanBiome
                ? new float[]{UNDERWATER_CANYON, UNDERWATER_CAVE}
                : new float[0];
    }

    /**
     * Whether (startX, startZ) starts the given carver, for a chunk whose biome
     * supplies {@code probability} at list position {@code carverIndex}.
     */
    public static boolean isStartChunk(JavaRandom random, long levelSeed, int carverIndex,
                                       int startX, int startZ, float probability) {
        random.setLargeFeatureSeed(levelSeed + carverIndex, startX, startZ);
        return random.nextFloat() <= probability;
    }

    /**
     * Counts start chunks that would run a carver of the given probability for
     * the chunk at (chunkX, chunkZ). Useful as a cheap sanity signal: with
     * radius 8 there are 289 candidates, so the expected count is 289 * p.
     */
    public static int countStartChunks(long levelSeed, int carverIndex,
                                       int chunkX, int chunkZ, float probability) {
        JavaRandom random = new JavaRandom();
        int n = 0;
        for (int i = chunkX - CARVE_RADIUS; i <= chunkX + CARVE_RADIUS; i++) {
            for (int j = chunkZ - CARVE_RADIUS; j <= chunkZ + CARVE_RADIUS; j++) {
                if (isStartChunk(random, levelSeed, carverIndex, i, j, probability)) {
                    n++;
                }
            }
        }
        return n;
    }
}
