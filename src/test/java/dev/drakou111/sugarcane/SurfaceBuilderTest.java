package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.FrozenOceanSurface;
import dev.drakou111.sugarcane.gen.SurfaceBuilder;
import dev.drakou111.sugarcane.gen.SurfaceConfig;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The surface builder is what puts cane soil into the world, and it is the reason
 * the search finds anything at all. These check its shape on hand-built columns,
 * where the expected answer can be worked out by hand from
 * {@code DefaultSurfaceBuilder}.
 */
class SurfaceBuilderTest {

    private static final int SEA = 63;

    /** Stone up to {@code floor} inclusive, water above it to y=62. */
    private static ArrayWorld oceanColumn(int floor) {
        ArrayWorld world = new ArrayWorld(0, 0, 3, 3);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                for (int y = 0; y <= floor; y++) {
                    world.setBlock(x, y, z, Blocks.SOLID);
                }
                for (int y = floor + 1; y < SEA; y++) {
                    world.setBlock(x, y, z, Blocks.WATER);
                }
            }
        }
        return world;
    }

    /** Runs the default builder on one column with a fixed noise value. */
    private static void build(ArrayWorld world, int biome, int x, int z, int start, double noise,
                             long chunkSeed) {
        JavaRandom random = new JavaRandom();
        random.setSeed(chunkSeed);
        SurfaceBuilder.apply(world, random, biome, x, z, start, noise);
    }

    /**
     * A deep ocean floor gets exactly one block of the underwater material and
     * keeps stone below: the {@code y < seaLevel - 7 - depth} branch fires, writes
     * the underwater material and then resets under-material to the default block.
     */
    @Test
    void deepOceanFloorGetsOneGravelOverStone() {
        int floor = 40;
        ArrayWorld world = oceanColumn(floor);
        build(world, 0, 1, 1, floor + 1, 0.0, 12345L);

        assertEquals(Blocks.GRAVEL, world.getBlock(1, floor, 1), "top of a deep floor");
        assertEquals(Blocks.SOLID, world.getBlock(1, floor - 1, 1), "below stays stone");
        assertEquals(Blocks.SOLID, world.getBlock(1, floor - 2, 1), "below stays stone");
        assertEquals(Blocks.WATER, world.getBlock(1, floor + 1, 1), "water is not overwritten");
    }

    /**
     * Warm oceans use CONFIG_FULL_SAND, so the same floor is sand — which cane can
     * stand on, unlike gravel. That is why warm and lukewarm oceans are searched.
     */
    @Test
    void warmOceanFloorIsSandAndThereforeCaneSoil() {
        int floor = 40;
        ArrayWorld world = oceanColumn(floor);
        build(world, 44, 1, 1, floor + 1, 0.0, 12345L);

        assertEquals(Blocks.SAND, world.getBlock(1, floor, 1));
        assertTrue(Blocks.isCaneSoil(world.getBlock(1, floor, 1)));
    }

    /**
     * A shallow floor is inside the band, so it gets the under material (dirt) —
     * which is cane soil, and is where the shallow spots come from.
     */
    @Test
    void shallowOceanFloorGetsADirtBand() {
        int floor = 58;    // 58 >= 63 - 7 - depth for any plausible depth
        ArrayWorld world = oceanColumn(floor);
        build(world, 0, 1, 1, floor + 1, 0.0, 12345L);

        assertEquals(Blocks.DIRT, world.getBlock(1, floor, 1), "top of a shallow floor");
        assertTrue(Blocks.isCaneSoil(world.getBlock(1, floor, 1)));
        assertEquals(Blocks.DIRT, world.getBlock(1, floor - 1, 1), "band continues down");
    }

    /** Above sea level the top material is used, so an island surface is grass. */
    @Test
    void landSurfaceGetsTopMaterial() {
        ArrayWorld world = oceanColumn(70);
        build(world, 1, 1, 1, 71, 0.0, 999L);

        assertEquals(Blocks.GRASS_BLOCK, world.getBlock(1, 70, 1));
        assertEquals(Blocks.DIRT, world.getBlock(1, 69, 1));
    }

    /**
     * The whole chunk shares one RNG stream, seeded from the chunk coordinates, so
     * the same chunk must always come out the same and different chunks must not.
     */
    @Test
    void chunkSeedDrivesTheResultDeterministically() {
        ArrayWorld a = new ArrayWorld(0, 0, 16, 16);
        ArrayWorld b = new ArrayWorld(0, 0, 16, 16);
        for (ArrayWorld world : new ArrayWorld[]{a, b}) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y <= 55; y++) {
                        world.setBlock(x, y, z, Blocks.SOLID);
                    }
                    for (int y = 56; y < SEA; y++) {
                        world.setBlock(x, y, z, Blocks.WATER);
                    }
                }
            }
        }
        SurfaceBuilder.Context context = new SurfaceBuilder.Context() {
            @Override
            public int surfaceStart(int x, int z) {
                return 56;
            }

            @Override
            public double noise(int x, int z, int localX) {
                // Vary across the chunk so the band bottom moves, as the real
                // noise does.
                return ((x * 7 + z * 13) % 11) - 5;
            }

            @Override
            public int biome(int x, int z) {
                return 0;
            }
        };
        SurfaceBuilder.buildChunk(a, 0, 0, context);
        SurfaceBuilder.buildChunk(b, 0, 0, context);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 40; y <= 56; y++) {
                    assertEquals(a.getBlock(x, y, z), b.getBlock(x, y, z),
                            "same chunk twice must agree at " + x + "," + y + "," + z);
                }
            }
        }

        // Rebuild with a different chunk seed; at least one column should differ,
        // since depth comes from a nextDouble on the chunk stream.
        ArrayWorld c = new ArrayWorld(0, 0, 16, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y <= 55; y++) {
                    c.setBlock(x, y, z, Blocks.SOLID);
                }
                for (int y = 56; y < SEA; y++) {
                    c.setBlock(x, y, z, Blocks.WATER);
                }
            }
        }
        SurfaceBuilder.buildChunk(c, 5, 9, context);
        boolean differs = false;
        for (int x = 0; x < 16 && !differs; x++) {
            for (int z = 0; z < 16 && !differs; z++) {
                for (int y = 40; y <= 56; y++) {
                    if (a.getBlock(x, y, z) != c.getBlock(x, y, z)) {
                        differs = true;
                        break;
                    }
                }
            }
        }
        assertTrue(differs, "a different chunk seed should change some column");
    }

    /** Water must never be written over, and must not reset the descent. */
    @Test
    void waterIsLeftAlone() {
        int floor = 45;
        ArrayWorld world = oceanColumn(floor);
        build(world, 0, 1, 1, floor + 1, 0.0, 4242L);
        for (int y = floor + 1; y < SEA; y++) {
            assertEquals(Blocks.WATER, world.getBlock(1, y, 1), "water at y=" + y);
        }
        assertNotEquals(Blocks.SOLID, world.getBlock(1, floor, 1),
                "the floor below the water should still have been surfaced");
    }

    /** Unsupported biomes must fail loudly rather than be approximated. */
    @Test
    void unsupportedBiomeThrows() {
        ArrayWorld world = oceanColumn(40);
        assertFalse(SurfaceConfig.supported(6), "swamp is not implemented");
        assertFalse(SurfaceConfig.supported(37), "badlands is not implemented");
        assertThrows(IllegalStateException.class,
                () -> build(world, 6, 1, 1, 41, 0.0, 1L));
    }

    /**
     * Frozen ocean is implemented now, and the failure mode that matters is silent: a
     * Context with no samplers must not quietly build the default surface, because the
     * two consume different numbers of draws from the chunk-wide stream.
     */
    @Test
    void frozenOceanIsSupportedAndDemandsItsSamplers() {
        assertTrue(SurfaceConfig.supported(10), "frozen_ocean is implemented");
        assertTrue(SurfaceConfig.supported(50), "deep_frozen_ocean is implemented");
        assertEquals(SurfaceConfig.Kind.FROZEN_OCEAN, SurfaceConfig.kind(10));
        assertThrows(IllegalStateException.class,
                () -> SurfaceBuilder.apply(oceanColumn(40), new JavaRandom(), 10,
                        1, 41, 1, 0.0, null),
                "no samplers supplied must throw, not fall back to the default builder");
    }

    /**
     * The three draws before the column walk are the whole reason this biome needs its
     * own builder: the default takes one, so a frozen ocean column that took one would
     * leave every later column of the chunk reading the wrong stream.
     */
    @Test
    void frozenOceanConsumesThreeDrawsBeforeTheWalk() {
        FrozenOceanSurface ice = new FrozenOceanSurface(1234L);
        JavaRandom frozen = new JavaRandom();
        frozen.setBaseChunkSeed(0, 0);
        SurfaceBuilder.apply(oceanColumn(40), frozen, 10, 1, 1, 41, 0.0, ice);

        JavaRandom reference = new JavaRandom();
        reference.setBaseChunkSeed(0, 0);
        reference.nextDouble();
        reference.nextInt(4);
        reference.nextInt(10);
        assertEquals(reference.nextLong(), frozen.nextLong(),
                "an all-water column should consume exactly the three pre-walk draws");
    }
}
