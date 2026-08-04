package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.world.Blocks;

/**
 * Which surface builder and which {@code SurfaceBuilderBaseConfiguration} each
 * 1.16.1 biome uses, read off the per-biome constructors in
 * {@code net.minecraft.world.level.biome}.
 *
 * <p>The configuration is three materials — top, under and underwater — and the
 * builder decides which of them lands where; see {@link SurfaceBuilder}.
 *
 * <p>Builders whose RNG draws depend on geometry we do not simulate are marked
 * {@link Kind#UNSUPPORTED}. They are not approximated, because the surface RNG
 * is shared by all 256 columns of a chunk: one column consuming the wrong number
 * of draws corrupts every column after it. {@link #supported(int)} lets the
 * searcher skip any chunk whose window touches one instead.
 */
public final class SurfaceConfig {

    /**
     * The surface builder implementations that exist in 1.16.1. All of the
     * SUPPORTED ones end up in {@code DefaultSurfaceBuilder}; they differ only in
     * how they choose the configuration from the surface noise, so they consume
     * the RNG identically.
     */
    public enum Kind {
        /** {@code SurfaceBuilder.DEFAULT} — uses the biome's own configuration. */
        DEFAULT,
        /** {@code MOUNTAIN}: stone above noise 1.0, else grass. */
        MOUNTAIN,
        /** {@code GRAVELLY_MOUNTAIN}: gravel outside [-1, 2], stone above 1, else grass. */
        GRAVELLY_MOUNTAIN,
        /** {@code GIANT_TREE_TAIGA}: coarse dirt above 1.75, podzol above -0.95, else grass. */
        GIANT_TREE_TAIGA,
        /** {@code SHATTERED_SAVANNA}: stone above 1.75, coarse dirt above -0.5, else grass. */
        SHATTERED_SAVANNA,
        /**
         * {@code FROZEN_OCEAN}: icebergs. Unlike the others it does <em>not</em> funnel
         * into the default builder — it draws three values before the column walk where
         * the default draws one, and more inside it, so it needs its own implementation
         * rather than a configuration choice.
         */
        FROZEN_OCEAN,
        /**
         * {@code BADLANDS} / {@code WOODED_BADLANDS} / {@code ERODED_BADLANDS}
         * (clay bands), {@code NOPE}, and everything outside the overworld.
         *
         * <p>{@code SWAMP} is here too, for a different reason: it consumes the
         * RNG exactly like the default builder, but it writes a water block at
         * y=62 from a noise this project does not implement. Approximating it
         * would put soil where the game has water — i.e. invent placements — so
         * swamp columns are skipped instead.
         */
        UNSUPPORTED
    }

    /** A {@code SurfaceBuilderBaseConfiguration} in the reduced block palette. */
    public record Config(byte top, byte under, byte underwater) {
    }

    // SurfaceBuilder.CONFIG_* — the constants in SurfaceBuilder itself.
    public static final Config GRASS = new Config(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.GRAVEL);
    public static final Config OCEAN_SAND = new Config(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.SAND);
    public static final Config FULL_SAND = new Config(Blocks.SAND, Blocks.SAND, Blocks.SAND);
    public static final Config DESERT = new Config(Blocks.SAND, Blocks.SAND, Blocks.GRAVEL);
    public static final Config STONE = new Config(Blocks.SOLID, Blocks.SOLID, Blocks.GRAVEL);
    public static final Config GRAVEL_CFG = new Config(Blocks.GRAVEL, Blocks.GRAVEL, Blocks.GRAVEL);
    public static final Config PODZOL = new Config(Blocks.PODZOL, Blocks.DIRT, Blocks.GRAVEL);
    public static final Config COARSE_DIRT = new Config(Blocks.COARSE_DIRT, Blocks.DIRT, Blocks.GRAVEL);
    public static final Config MYCELIUM = new Config(Blocks.SOLID, Blocks.DIRT, Blocks.GRAVEL);
    /** ice_spikes: {@code (SNOW_BLOCK, DIRT, GRAVEL)}, declared inline in the biome. */
    public static final Config SNOW = new Config(Blocks.SOLID, Blocks.DIRT, Blocks.GRAVEL);

    private static final Kind[] KIND = new Kind[256];
    private static final Config[] CONFIG = new Config[256];
    private static final float[] TEMPERATURE = new float[256];

    private SurfaceConfig() {
    }

    private static void put(int biomeId, Kind kind, Config config, float temperature) {
        KIND[biomeId] = kind;
        CONFIG[biomeId] = config;
        TEMPERATURE[biomeId] = temperature;
    }

    static {
        for (int i = 0; i < 256; i++) {
            put(i, Kind.UNSUPPORTED, GRASS, 0.5f);
        }
        put(0, Kind.DEFAULT, GRASS, 0.5f);            // ocean
        put(1, Kind.DEFAULT, GRASS, 0.8f);            // plains
        put(2, Kind.DEFAULT, DESERT, 2.0f);           // desert
        put(3, Kind.MOUNTAIN, GRASS, 0.2f);           // mountains
        put(4, Kind.DEFAULT, GRASS, 0.7f);            // forest
        put(5, Kind.DEFAULT, GRASS, 0.25f);           // taiga
        put(6, Kind.UNSUPPORTED, GRASS, 0.8f);        // swamp - water at y=62 from an unimplemented noise
        put(7, Kind.DEFAULT, GRASS, 0.5f);            // river
        put(10, Kind.FROZEN_OCEAN, GRASS, 0.0f);      // frozen_ocean - icebergs
        put(11, Kind.DEFAULT, GRASS, 0.0f);           // frozen_river
        put(12, Kind.DEFAULT, GRASS, 0.0f);           // snowy_tundra
        put(13, Kind.DEFAULT, GRASS, 0.0f);           // snowy_mountains
        put(14, Kind.DEFAULT, MYCELIUM, 0.9f);        // mushroom_fields
        put(15, Kind.DEFAULT, MYCELIUM, 0.9f);        // mushroom_field_shore
        put(16, Kind.DEFAULT, DESERT, 0.8f);          // beach
        put(17, Kind.DEFAULT, DESERT, 2.0f);          // desert_hills
        put(18, Kind.DEFAULT, GRASS, 0.7f);           // wooded_hills
        put(19, Kind.DEFAULT, GRASS, 0.25f);          // taiga_hills
        put(20, Kind.DEFAULT, GRASS, 0.2f);           // mountain_edge
        put(21, Kind.DEFAULT, GRASS, 0.95f);          // jungle
        put(22, Kind.DEFAULT, GRASS, 0.95f);          // jungle_hills
        put(23, Kind.DEFAULT, GRASS, 0.95f);          // jungle_edge
        put(24, Kind.DEFAULT, GRASS, 0.5f);           // deep_ocean
        put(25, Kind.DEFAULT, STONE, 0.2f);           // stone_shore
        put(26, Kind.DEFAULT, DESERT, 0.05f);         // snowy_beach
        put(27, Kind.DEFAULT, GRASS, 0.6f);           // birch_forest
        put(28, Kind.DEFAULT, GRASS, 0.6f);           // birch_forest_hills
        put(29, Kind.DEFAULT, GRASS, 0.7f);           // dark_forest
        put(30, Kind.DEFAULT, GRASS, -0.5f);          // snowy_taiga
        put(31, Kind.DEFAULT, GRASS, -0.5f);          // snowy_taiga_hills
        put(32, Kind.GIANT_TREE_TAIGA, GRASS, 0.3f);  // giant_tree_taiga
        put(33, Kind.GIANT_TREE_TAIGA, GRASS, 0.3f);  // giant_tree_taiga_hills
        put(34, Kind.DEFAULT, GRASS, 0.2f);           // wooded_mountains
        put(35, Kind.DEFAULT, GRASS, 1.2f);           // savanna
        put(36, Kind.DEFAULT, GRASS, 1.0f);           // savanna_plateau
        put(37, Kind.UNSUPPORTED, GRASS, 2.0f);       // badlands - clay bands
        put(38, Kind.UNSUPPORTED, GRASS, 2.0f);       // wooded_badlands_plateau
        put(39, Kind.UNSUPPORTED, GRASS, 2.0f);       // badlands_plateau
        put(44, Kind.DEFAULT, FULL_SAND, 0.5f);       // warm_ocean
        put(45, Kind.DEFAULT, OCEAN_SAND, 0.5f);      // lukewarm_ocean
        put(46, Kind.DEFAULT, GRASS, 0.5f);           // cold_ocean
        put(47, Kind.DEFAULT, FULL_SAND, 0.5f);       // deep_warm_ocean
        put(48, Kind.DEFAULT, OCEAN_SAND, 0.5f);      // deep_lukewarm_ocean
        put(49, Kind.DEFAULT, GRASS, 0.5f);           // deep_cold_ocean
        put(50, Kind.FROZEN_OCEAN, GRASS, 0.5f);      // deep_frozen_ocean - icebergs
        put(129, Kind.DEFAULT, GRASS, 0.8f);          // sunflower_plains
        put(130, Kind.DEFAULT, DESERT, 2.0f);         // desert_lakes
        put(131, Kind.GRAVELLY_MOUNTAIN, GRASS, 0.2f);// gravelly_mountains
        put(132, Kind.DEFAULT, GRASS, 0.7f);          // flower_forest
        put(133, Kind.DEFAULT, GRASS, 0.25f);         // taiga_mountains
        put(134, Kind.UNSUPPORTED, GRASS, 0.8f);      // swamp_hills
        put(140, Kind.DEFAULT, SNOW, 0.0f);           // ice_spikes
        put(149, Kind.DEFAULT, GRASS, 0.95f);         // modified_jungle
        put(151, Kind.DEFAULT, GRASS, 0.95f);         // modified_jungle_edge
        put(155, Kind.DEFAULT, GRASS, 0.6f);          // tall_birch_forest
        put(156, Kind.DEFAULT, GRASS, 0.6f);          // tall_birch_hills
        put(157, Kind.DEFAULT, GRASS, 0.7f);          // dark_forest_hills
        put(158, Kind.DEFAULT, GRASS, -0.5f);         // snowy_taiga_mountains
        put(160, Kind.GIANT_TREE_TAIGA, GRASS, 0.25f);// giant_spruce_taiga
        put(161, Kind.GIANT_TREE_TAIGA, GRASS, 0.25f);// giant_spruce_taiga_hills
        put(162, Kind.GRAVELLY_MOUNTAIN, GRASS, 0.2f);// modified_gravelly_mountains
        put(163, Kind.SHATTERED_SAVANNA, GRASS, 1.1f);// shattered_savanna
        put(164, Kind.SHATTERED_SAVANNA, GRASS, 1.0f);// shattered_savanna_plateau
        put(165, Kind.UNSUPPORTED, GRASS, 2.0f);      // eroded_badlands
        put(166, Kind.UNSUPPORTED, GRASS, 2.0f);      // modified_wooded_badlands_plateau
        put(167, Kind.UNSUPPORTED, GRASS, 2.0f);      // modified_badlands_plateau
        put(168, Kind.DEFAULT, GRASS, 0.95f);         // bamboo_jungle
        put(169, Kind.DEFAULT, GRASS, 0.95f);         // bamboo_jungle_hills
    }

    public static Kind kind(int biomeId) {
        return biomeId >= 0 && biomeId < 256 ? KIND[biomeId] : Kind.UNSUPPORTED;
    }

    public static Config config(int biomeId) {
        return biomeId >= 0 && biomeId < 256 ? CONFIG[biomeId] : GRASS;
    }

    /**
     * {@code Biome.getBaseTemperature()}. The surface builder's ice-instead-of-
     * water branch only fires below sea level, and
     * {@code getHeightAdjustedTemperature} only perturbs the value above y=64, so
     * the base temperature is the whole story here. No 1.16.1 biome uses a
     * {@code TemperatureModifier}.
     */
    public static float temperature(int biomeId) {
        return biomeId >= 0 && biomeId < 256 ? TEMPERATURE[biomeId] : 0.5f;
    }

    public static boolean supported(int biomeId) {
        return kind(biomeId) != Kind.UNSUPPORTED;
    }
}
