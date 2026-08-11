package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;

import java.util.BitSet;

/**
 * Whether a single block could have been carved to air, with no terrain generated at
 * all — the terrain question the reverse search needs, at 1/7 of the cost of asking
 * it by generating nine chunks.
 *
 * <p><b>Why it is sound.</b> Below sea level the only source of air is an AIR-step
 * carver:
 * <ul>
 *   <li>the noise fills every non-solid block under y=63 with water, not air
 *       ({@code Terrain.column} passes WATER as the fluid);</li>
 *   <li>the LIQUID-step carvers never call {@code setCaveAir} — {@code Carver}'s
 *       underwater branch writes water, or returns early at y&lt;=10;</li>
 *   <li>lakes are sealed and structures keep their water (FINDINGS 5), and chunks
 *       near a mineshaft start are skipped by the search anyway, which is the one
 *       other thing that writes air down there.</li>
 * </ul>
 * So a cane column's base being air <em>implies</em> an air carver reached it, and a
 * position no air carver reaches cannot hold cane. Rejecting on that loses nothing.
 *
 * <p><b>Why it needs no terrain.</b> The tunnel walks are pure RNG — where a carver
 * goes depends only on the seed. Only its <em>decisions</em> read the world, so
 * running it against a permissive stub (everything replaceable, nothing water) can
 * only ever carve a superset of what the real carvers carve. Same argument, and the
 * same {@code Stub}, as {@code CarverWalkFilter}; the difference is asking about one
 * position instead of the whole chunk, which is enormously more selective.
 *
 * <p>This is a filter, not a substitute: anything it accepts still goes through the
 * real pipeline. It is allowed to be wrong in one direction only.
 */
public final class AirCarveProbe {

    /** Carves everything it is asked to and records where. */
    private final class Stub implements Carver.Target {
        @Override
        public boolean canReplace(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            // With no oracle this is the permissive stub: the guard never fires and the
            // walk carves a superset, which is what makes the probe terrain-free.
            //
            // The guard is not a detail. waterGuard() is !underwater, and the AIR-step
            // carvers are the ONLY source of air below sea level -- so the carvers that
            // matter are exactly the ones that run it, and below sea level every non-solid
            // block is water. Stubbed out, a ravine approaching the ocean floor carves
            // straight through where the real one aborts sphere after sphere. Backing this
            // with the noise is the difference between a probe that says "a walk reached
            // here" and one that says "a carver cut here".
            return water != null && water.isWater(x, y, z);
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return false;
        }

        @Override
        public void setCaveAir(int x, int y, int z) {
            if (y >= 0 && y < 256) {
                carved.set((x & 15) | (z & 15) << 4 | y << 8);
            }
        }

        @Override
        public boolean guardApplies(int x0, int x1, int z0, int z1) {
            if (guardLocalX < 0) {
                return true;            // asking about the whole chunk
            }
            return x0 <= guardLocalX && guardLocalX < x1
                    && z0 <= guardLocalZ && guardLocalZ < z1;
        }

        @Override
        public void setWater(int x, int y, int z, boolean scheduleTick) {
            // An air carver never reaches this; a liquid one is not run here.
        }
    }

    /**
     * Where the water is, so the AIR carvers' guard can fire.
     *
     * <p>Optional, and null for the reverse search, which has no world seed when it builds
     * its target set and so cannot answer this at all. {@code crossfind} has one by the time
     * it asks, which is the whole reason this hook exists.
     *
     * <p><b>Supplying one makes the walk depend on the terrain</b>, and therefore on the
     * sister: the noise moves with the upper 16 bits. A caller that hoists the walk out of a
     * sister loop must not set this.
     */
    public interface WaterOracle {
        boolean isWater(int x, int y, int z);
    }

    private WaterOracle water;
    private int guardLocalX = -1;
    private int guardLocalZ = -1;

    /**
     * Ask the guard about one column only, which is what makes it affordable.
     *
     * <p>The guard is the expensive half: the walk is 74 us and filling a chunk's 256 noise
     * columns behind it is another 618. A sphere can only carve inside its own box, so for a
     * caller that reads one column the other spheres' guards cannot change the answer — see
     * {@link Carver.Target#guardApplies}. Restricting to them reads the columns around one
     * sphere instead of the chunk.
     *
     * <p>Pass -1, -1 to go back to answering for the whole chunk.
     */
    public AirCarveProbe guardColumn(int x, int z) {
        int lx = x < 0 ? -1 : (x & 15);
        int lz = x < 0 ? -1 : (z & 15);
        if (lx != guardLocalX || lz != guardLocalZ) {
            guardLocalX = lx;
            guardLocalZ = lz;
            this.walked = false;
        }
        return this;
    }

    /** @param oracle null to keep the terrain-free stub */
    public AirCarveProbe water(WaterOracle oracle) {
        this.water = oracle;
        this.walked = false;
        return this;
    }

    private final BitSet carved = new BitSet(65536);
    private final BitSet stepMask = new BitSet(65536);
    private final JavaRandom random = new JavaRandom();
    private final Stub stub = new Stub();

    private long walkedSeed;
    private int walkedChunkX;
    private int walkedChunkZ;
    private boolean walked;

    /**
     * Runs both AIR-step carvers for one chunk, from every start chunk within the
     * carve radius. Memoised on (seed, chunk) because the chains of one candidate
     * usually share a chunk.
     *
     * @param ocean the carve probability differs, so this must be the biome at the
     *              generating chunk's corner, exactly as {@code runCarvers} uses it
     */
    /**
     * Only ravines carve the air a tall stack needs, so only run those.
     *
     * <p>A cave is tubular and a few blocks across; a stack of four columns needs sixteen
     * blocks of air at one x,z, which is a ravine's shape and not a cave's. Measured on
     * every find we have: the 8-tall, the reported 11 and the simulated 10 are all reached
     * by a canyon and by no cave at all. The one cave-carved find is the 5-tall, which is
     * a single column on top of another and does not need the height.
     *
     * <p>Ravines start in 1 chunk in 50 against a cave's 1 in 15, so dropping caves is most
     * of what the probe could reject. It is a coverage trade rather than a free tightening,
     * which is why it is a flag and why the confirmed finds are pinned against it.
     */
    private boolean ravinesOnly;

    public AirCarveProbe ravinesOnly(boolean on) {
        this.ravinesOnly = on;
        this.walked = false;
        return this;
    }

    public void walk(long worldSeed, int chunkX, int chunkZ, boolean ocean) {
        if (walked && walkedSeed == worldSeed
                && walkedChunkX == chunkX && walkedChunkZ == chunkZ) {
            return;
        }
        carved.clear();
        stepMask.clear();
        // One mask per generation step, shared by both carvers of that step, so
        // whichever reaches a block first owns it — as in runCarvers.
        Carver cave = new CaveCarver(stub, chunkX, chunkZ, false,
                CarverConfig.SEA_LEVEL, stepMask);
        Carver canyon = new CanyonCarver(stub, chunkX, chunkZ, false,
                CarverConfig.SEA_LEVEL, stepMask);
        float caveProbability = ocean ? CarverConfig.CAVE_OCEAN : CarverConfig.CAVE_LAND;
        int r = CarverConfig.CARVE_RADIUS;
        for (int sx = chunkX - r; sx <= chunkX + r; sx++) {
            for (int sz = chunkZ - r; sz <= chunkZ + r; sz++) {
                if (!ravinesOnly
                        && CarverConfig.isStartChunk(random, worldSeed, 0, sx, sz,
                                caveProbability)) {
                    cave.carve(random, sx, sz);
                }
                if (CarverConfig.isStartChunk(random, worldSeed, 1, sx, sz, CarverConfig.CANYON)) {
                    canyon.carve(random, sx, sz);
                }
            }
        }
        walkedSeed = worldSeed;
        walkedChunkX = chunkX;
        walkedChunkZ = chunkZ;
        walked = true;
    }

    /** Whether the last {@link #walk} carved this block. World coordinates. */
    public boolean isCarved(int x, int y, int z) {
        if (y < 0 || y >= 256 || (x >> 4) != walkedChunkX || (z >> 4) != walkedChunkZ) {
            // Outside the chunk that was walked, so unknown: say yes rather than
            // reject something that might be real.
            return true;
        }
        return carved.get((x & 15) | (z & 15) << 4 | y << 8);
    }
}
