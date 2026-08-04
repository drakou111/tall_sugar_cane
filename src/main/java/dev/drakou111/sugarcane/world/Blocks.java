package dev.drakou111.sugarcane.world;

/**
 * Minimal block palette: only the distinctions the sugar cane feature checks.
 *
 * <p>Everything the feature cares about is (a) is it air, (b) does it hold water
 * fluid, (c) is it one of the six blocks sugar cane may stand on, (d) is it
 * sugar cane, (e) is it frosted ice. Anything else collapses to SOLID.
 */
public final class Blocks {
    public static final byte AIR = 0;
    public static final byte SOLID = 1;          // stone, sandstone, logs, ... anything opaque
    public static final byte WATER = 2;          // source
    public static final byte FLOWING_WATER = 3;  // FluidTags.WATER matches this too
    public static final byte SAND = 4;
    public static final byte RED_SAND = 5;
    public static final byte DIRT = 6;
    public static final byte COARSE_DIRT = 7;
    public static final byte PODZOL = 8;
    public static final byte GRASS_BLOCK = 9;
    public static final byte SUGAR_CANE = 10;
    public static final byte FROSTED_ICE = 11;
    /**
     * Grass, ferns, flowers, dead bushes and the like: not air, so they stop the
     * feature (which requires isEmptyBlock), but not motion-blocking, so they do
     * not raise the MOTION_BLOCKING heightmap the placement samples.
     */
    public static final byte PLANT = 12;
    /**
     * Gravel and sand need to be distinguishable from stone: the land carver only
     * replaces them when the block above holds no water, so the top gravel of an
     * ocean floor survives carving where plain stone would not
     * ({@code WorldCarver.canReplaceBlock(state, above)}).
     */
    public static final byte GRAVEL = 13;
    /** Ice, which the surface builder writes instead of water in cold biomes. Not a water fluid, and not carver-replaceable. */
    public static final byte ICE = 14;
    /**
     * Clay, which {@code DISK_CLAY} puts on the sea floor in place of dirt. Not
     * cane soil, so it has to be distinguishable from dirt — otherwise the
     * simulation offers positions the game does not.
     */
    public static final byte CLAY = 15;
    /**
     * Sandstone, which the surface builder writes under a sand band. It has to be
     * distinguishable from stone because {@code OreConfiguration.Predicates
     * .NATURAL_STONE} is only stone, granite, diorite and andesite — so the game
     * never puts a dirt blob into sandstone, while a palette that folds it into
     * SOLID happily would, inventing soil on warm and lukewarm ocean floors.
     */
    public static final byte SANDSTONE = 16;
    /**
     * Packed ice, which {@code FrozenOceanSurfaceBuilder} writes for icebergs. It
     * cannot fold into SOLID: the builder re-reads the block it just wrote and
     * branches on whether it is the biome's default block, so folding packed ice
     * into stone would send the column down the surfacing branch vanilla skips —
     * and that branch can draw from the shared chunk RNG, desynchronising every
     * later column.
     */
    public static final byte PACKED_ICE = 17;
    /**
     * The snow <em>block</em> an iceberg is capped with. Distinct from SOLID only
     * because vanilla's carvers do not replace it, unlike packed ice.
     */
    public static final byte SNOW_BLOCK = 18;

    private Blocks() {
    }

    /**
     * {@code WorldCarver.replaceableBlocks} plus the sand/gravel rule from
     * {@code canReplaceBlock(state, above)}: the AIR-step carvers replace stone
     * and soil freely, but sand and gravel only when no water sits on top.
     *
     * <p>Blocks the reduced palette folds into SOLID are all in the vanilla set
     * (stone, sandstone, terracotta, mycelium, snow layer, packed ice); the one
     * exception is the snow <em>block</em> of ice spikes, which vanilla does not
     * replace.
     */
    public static boolean isCarverReplaceable(byte b, byte above) {
        if (b == SAND || b == RED_SAND || b == GRAVEL) {
            return !isWaterFluid(above);
        }
        return b == SOLID || b == SANDSTONE || b == DIRT || b == COARSE_DIRT
                || b == PODZOL || b == GRASS_BLOCK || b == PACKED_ICE;
    }

    public static boolean isAir(byte b) {
        return b == AIR;
    }

    /** {@code getFluidState(pos).is(FluidTags.WATER)} */
    public static boolean isWaterFluid(byte b) {
        return b == WATER || b == FLOWING_WATER;
    }

    /**
     * The soil whitelist in {@code SugarCaneBlock.canSurvive} — an explicit block
     * list in 1.16.1, not a tag. Note mycelium is <em>not</em> in it.
     */
    public static boolean isCaneSoil(byte b) {
        return b == GRASS_BLOCK || b == DIRT || b == COARSE_DIRT
                || b == PODZOL || b == SAND || b == RED_SAND;
    }

    /** MOTION_BLOCKING = blocksMotion() || !getFluidState().isEmpty(). Sugar cane does neither. */
    public static boolean isMotionBlocking(byte b) {
        return b != AIR && b != SUGAR_CANE && b != PLANT;
    }

    /** {@code Material.blocksMotion()}, the OCEAN_FLOOR_WG predicate — water excluded. */
    public static boolean blocksMotion(byte b) {
        return isMotionBlocking(b) && !isWaterFluid(b);
    }
}
