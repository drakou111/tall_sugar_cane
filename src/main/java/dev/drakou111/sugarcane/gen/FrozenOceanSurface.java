package dev.drakou111.sugarcane.gen;

import kaptainwutax.mcutils.rand.ChunkRand;
import kaptainwutax.noiseutils.simplex.OctaveSimplexNoiseSampler;

import java.util.List;
import java.util.stream.IntStream;

/**
 * The two noise samplers {@code FrozenOceanSurfaceBuilder.initNoise} builds, which is
 * everything about that builder that depends on the world seed rather than the column:
 *
 * <pre>
 * WorldgenRandom random = new WorldgenRandom(seed);
 * icebergNoise     = new PerlinSimplexNoise(random, IntStream.rangeClosed(-3, 0));
 * icebergRoofNoise = new PerlinSimplexNoise(random, ImmutableList.of(0));
 * </pre>
 *
 * <p>Both draw from the <em>same</em> random in that order, so the roof sampler's values
 * depend on the first having consumed its four octaves already.
 *
 * <p>{@code OctaveSimplexNoiseSampler} is vanilla's {@code PerlinSimplexNoise} — the same
 * class TerrainUtils uses for {@code surfaceDepthNoise}, which vanilla builds with the
 * identical {@code rangeClosed(-3, 0)} octave set. That path is already confirmed against
 * the real game end to end (FINDINGS 6k), so the four-octave sampler here needs no
 * separate argument. The single-octave roof sampler is the one configuration this project
 * had not exercised before.
 *
 * <p>{@code new ChunkRand(seed)} is bit-identical to {@code new java.util.Random(seed)},
 * checked directly, which is what {@code new WorldgenRandom(seed)} reduces to.
 */
public final class FrozenOceanSurface {

    private final OctaveSimplexNoiseSampler icebergNoise;
    private final OctaveSimplexNoiseSampler icebergRoofNoise;

    public FrozenOceanSurface(long worldSeed) {
        ChunkRand random = new ChunkRand(worldSeed);
        this.icebergNoise = new OctaveSimplexNoiseSampler(random, IntStream.rangeClosed(-3, 0));
        this.icebergRoofNoise = new OctaveSimplexNoiseSampler(random, List.of(0));
    }

    /** {@code icebergNoise.getValue(x * 0.1, z * 0.1, false)} */
    public double iceberg(int x, int z) {
        return icebergNoise.sample(x * 0.1, z * 0.1, false);
    }

    /** {@code icebergRoofNoise.getValue(x * 0.09765625, z * 0.09765625, false)} */
    public double roof(int x, int z) {
        return icebergRoofNoise.sample(x * 0.09765625, z * 0.09765625, false);
    }
}
