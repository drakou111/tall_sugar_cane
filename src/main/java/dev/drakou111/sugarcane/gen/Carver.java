package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.rng.Mth;

import java.util.BitSet;

/**
 * The machinery 1.16.1's {@code WorldCarver} shares between the cave and canyon
 * carvers: the sphere fill, the water guard, the reach test and the block write.
 * Subclasses supply the walk ({@link #carve}) and the sphere shape
 * ({@link #skip}).
 *
 * <p>Both carvers of a generation step share one carving mask, so a block already
 * visited by the cave carver is skipped by the canyon carver — which is why the
 * mask is passed in rather than owned here.
 *
 * <p>Float vs double matters throughout: the game uses {@code float} for angles
 * and thickness and {@code double} for positions and radii, and {@code Mth.sin}
 * and {@code Mth.cos} are float-precision table lookups in the real code but
 * plain {@code Math} here. Sub-ulp differences can move a block boundary.
 */
public abstract class Carver {

    /** Receives carved blocks; lets the caller decide how to store terrain. */
    public interface Target {
        /**
         * {@code WorldCarver.canReplaceBlock(state, above)} — note it depends on
         * the block <em>above</em> as well: sand and gravel are only replaceable
         * when no water sits on top of them, which is why the top gravel of an
         * ocean floor survives the AIR carvers.
         */
        boolean canReplace(int x, int y, int z);

        /**
         * Grass block or mycelium. Vanilla remembers having seen one while
         * descending a column and then re-grows the surface on the cave floor.
         */
        default boolean isGrassLike(int x, int y, int z) {
            return false;
        }

        /**
         * If this block is dirt, replace it with the biome's top material — the
         * "grass follows the cave" rule at the end of {@code carveBlock}. It is a
         * source of cane soil, so leaving it out only loses finds.
         */
        default void convertDirtToTopMaterial(int x, int y, int z) {
        }

        /** True if a water fluid sits here; carving aborts near water. */
        boolean isWater(int x, int y, int z);

        /**
         * Whether this sphere's water guard is worth asking about, in chunk-local bounds.
         *
         * <p>For a caller that only wants to know about one column, the answer for every other
         * sphere is irrelevant and skipping it is exact rather than approximate: a sphere carves
         * only inside its own box, so a sphere that does not contain the column can neither
         * carve it nor set its mask, and whether that sphere aborted is invisible there. Saying
         * "no water" for it lets it carve blocks the caller is not asking about, which changes
         * nothing it will read.
         *
         * <p>Default true, which is every caller that wants the whole chunk.
         */
        default boolean guardApplies(int x0, int x1, int z0, int z1) {
            return true;
        }

        boolean isAir(int x, int y, int z);

        void setCaveAir(int x, int y, int z);

        /**
         * @param scheduleTick true when a horizontal neighbour was air or lay
         *                     outside the chunk, which is when vanilla queues a
         *                     fluid tick that later floods the neighbouring cave
         */
        void setWater(int x, int y, int z, boolean scheduleTick);
    }

    protected static final int GEN_HEIGHT = 256;
    /**
     * {@code getRange()} is 4, not the 8 of the driver's start-chunk radius. The
     * tunnel length comes from this one: {@code (4 * 2 - 1) * 16 = 112}.
     */
    protected static final int RANGE = 4;

    protected final Target target;
    protected final int chunkX;
    protected final int chunkZ;
    protected final boolean underwater;
    protected final int seaLevel;
    private final BitSet mask;

    protected Carver(Target target, int chunkX, int chunkZ, boolean underwater,
                     int seaLevel, BitSet mask) {
        this.target = target;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.underwater = underwater;
        this.seaLevel = seaLevel;
        this.mask = mask;
    }

    /** Runs one start chunk's worth of carving into the target chunk. */
    public abstract void carve(JavaRandom random, int startX, int startZ);

    /** {@code WorldCarver.skip}: which offsets inside the bounding box are inside the shape. */
    protected abstract boolean skip(double dx, double dy, double dz, int y);

    /** {@code WorldCarver.hasWater}: the underwater carvers override it to false. */
    protected boolean waterGuard() {
        return !underwater;
    }

    /**
     * Set to collect the sphere envelope instead of carving. The walk is pure RNG,
     * so this runs with no terrain at all and no per-block work — it reports where
     * a tunnel <em>would</em> cut, which is a strict over-approximation of what it
     * actually cuts once hasWater and canReplaceBlock have their say.
     */
    public interface SphereSink {
        void sphere(double x, double y, double z, double radius, double vertical);
    }

    private SphereSink sink;

    public void collectInto(SphereSink sink) {
        this.sink = sink;
    }

    protected void carveSphere(long seed, double x, double y, double z,
                               double radius, double vertical) {
        if (sink != null) {
            double centreX0 = chunkX * 16 + 8;
            double centreZ0 = chunkZ * 16 + 8;
            if (x < centreX0 - 16.0 - radius * 2.0 || z < centreZ0 - 16.0 - radius * 2.0
                    || x > centreX0 + 16.0 + radius * 2.0 || z > centreZ0 + 16.0 + radius * 2.0) {
                return;
            }
            sink.sphere(x, y, z, radius, vertical);
            return;
        }
        // Note the sphere RNG is seeded per chunk, not per tunnel step.
        JavaRandom random = new JavaRandom(seed + chunkX + chunkZ);
        double centreX = chunkX * 16 + 8;
        double centreZ = chunkZ * 16 + 8;
        if (x < centreX - 16.0 - radius * 2.0 || z < centreZ - 16.0 - radius * 2.0
                || x > centreX + 16.0 + radius * 2.0 || z > centreZ + 16.0 + radius * 2.0) {
            return;
        }

        int x0 = Math.max(floor(x - radius) - chunkX * 16 - 1, 0);
        int x1 = Math.min(floor(x + radius) - chunkX * 16 + 1, 16);
        int y0 = Math.max(floor(y - vertical) - 1, 1);
        int y1 = Math.min(floor(y + vertical) + 1, GEN_HEIGHT - 8);
        int z0 = Math.max(floor(z - radius) - chunkZ * 16 - 1, 0);
        int z1 = Math.min(floor(z + radius) - chunkZ * 16 + 1, 16);

        if (hasWater(x0, x1, y0, y1, z0, z1)) {
            return;
        }

        for (int lx = x0; lx < x1; lx++) {
            int wx = lx + chunkX * 16;
            double dx = ((double) wx + 0.5 - x) / radius;
            for (int lz = z0; lz < z1; lz++) {
                int wz = lz + chunkZ * 16;
                double dz = ((double) wz + 0.5 - z) / radius;
                if (dx * dx + dz * dz >= 1.0) {
                    continue;
                }
                // One "have I passed a grass block yet" flag per column, as in
                // vanilla — it is created outside the y loop.
                boolean grassAbove = false;
                for (int wy = y1; wy > y0; wy--) {
                    double dy = ((double) wy - 0.5 - y) / vertical;
                    if (skip(dx, dy, dz, wy)) {
                        continue;
                    }
                    grassAbove = carveBlock(random, lx, wy, lz, wx, wz, grassAbove);
                }
            }
        }
    }

    /** @return the updated "grass seen in this column" flag */
    private boolean carveBlock(JavaRandom random, int localX, int y, int localZ,
                               int worldX, int worldZ, boolean grassAbove) {
        if (underwater && y >= seaLevel) {
            return grassAbove;   // the underwater carvers never touch anything above sea level
        }
        int index = localX | localZ << 4 | y << 8;
        if (mask.get(index)) {
            return grassAbove;
        }
        mask.set(index);
        boolean grass = grassAbove || (!underwater && target.isGrassLike(worldX, y, worldZ));
        if (!target.canReplace(worldX, y, worldZ)) {
            return grass;
        }
        if (underwater) {
            if (y == 10) {
                // Magma or obsidian, and the choice costs a draw from the
                // per-sphere RNG, so it has to be consumed even though neither
                // block matters here.
                random.nextFloat();
                return grass;
            }
            if (y < 10) {
                return grass;
            }
            // Vanilla's cursor quirk, and it matters more than anything else here.
            // The air test is written as
            //     !chunkAccess.getBlockState(cursor.set(nx, y, nz)).isAir()
            // so testing an in-chunk neighbour MOVES the cursor to it, and the very
            // next line writes the water through that same cursor. The result is
            // that an underwater tunnel touching an air cave floods the CAVE block
            // and leaves its own block alone.
            //
            // Reproducing this is what makes the air/water boundary come out right,
            // and that boundary is where all the sugar cane geometry lives. Writing
            // the water at the carved block instead leaves dry air where the game
            // has water, which invented three quarters of the stackable spots this
            // project used to report.
            int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};   // Direction.Plane.HORIZONTAL
            for (int[] d : dirs) {
                int nx = worldX + d[0], nz = worldZ + d[1];
                boolean inChunk = (nx >> 4) == chunkX && (nz >> 4) == chunkZ;
                if (inChunk && !target.isAir(nx, y, nz)) {
                    continue;
                }
                if (inChunk) {
                    // The cursor was moved onto the neighbour by the air test.
                    target.setWater(nx, y, nz, true);
                } else {
                    // Out of chunk short-circuits before the set, so the cursor is
                    // still on the carved block.
                    target.setWater(worldX, y, worldZ, true);
                }
                return grass;
            }
            target.setWater(worldX, y, worldZ, false);
            return grass;
        }
        if (y < 11) {
            return grass;   // lava below y=11; irrelevant to the sugar cane geometry
        }
        target.setCaveAir(worldX, y, worldZ);
        if (grass) {
            target.convertDirtToTopMaterial(worldX, y - 1, worldZ);
        }
        return grass;
    }

    /**
     * The carver refuses to break into water. Note this is a shell test, not a
     * volume test: interior columns only check the floor and ceiling.
     */
    private boolean hasWater(int x0, int x1, int y0, int y1, int z0, int z1) {
        if (!waterGuard() || !target.guardApplies(x0, x1, z0, z1)) {
            return false;
        }
        for (int lx = x0; lx < x1; lx++) {
            for (int lz = z0; lz < z1; lz++) {
                for (int y = y0 - 1; y <= y1 + 1; y++) {
                    if (target.isWater(lx + chunkX * 16, y, lz + chunkZ * 16)) {
                        return true;
                    }
                    if (y == y1 + 1 || isEdge(x0, x1, z0, z1, lx, lz)) {
                        continue;
                    }
                    y = y1;
                }
            }
        }
        return false;
    }

    private static boolean isEdge(int x0, int x1, int z0, int z1, int lx, int lz) {
        return lx == x0 || lx == x1 - 1 || lz == z0 || lz == z1 - 1;
    }

    protected boolean canReach(double x, double z, int step, int length, float thickness) {
        double dx = x - (chunkX * 16 + 8);
        double dz = z - (chunkZ * 16 + 8);
        double remaining = length - step;
        double reach = thickness + 2.0f + 16.0f;
        return dx * dx + dz * dz - remaining * remaining <= reach * reach;
    }

    protected static int floor(double d) {
        return Mth.floor(d);
    }
}
