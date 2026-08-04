package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;

import java.util.BitSet;

/**
 * Whether a block could have been flooded by a LIQUID-step carver, with no terrain
 * generated — the water half of the position test {@link AirCarveProbe} answers for air,
 * and the filter FINDINGS 6ae listed as the next one worth having.
 *
 * <p>A cane column needs {@code needWater} to pass beside the block under every one of
 * its bases, so a chain names water positions just as it names air positions. Where that
 * water can come from below sea level is short:
 *
 * <ul>
 *   <li>the {@code UNDERWATER_CAVE} and {@code UNDERWATER_CANYON} carvers, which is what
 *       this walks;</li>
 *   <li>the noise sea fill, wherever the terrain was not solid to begin with.</li>
 * </ul>
 *
 * <p><b>So unlike the air probe this one is not free.</b> The air argument is airtight —
 * nothing but an AIR carver makes air down there — but a spot sitting on the sea floor
 * has sea fill beside it and needs no carver at all. Those are lost. FINDINGS 5c put them
 * at ~2% of stackable spots and a 3.13M-chunk diag measured 3 of 216 on the floor, so the
 * loss is ~1.4-2%. That is the trade, and it is why 6ae said measure before adopting.
 *
 * <p>Everything else carries over from the air probe: the walks are pure RNG, the stub is
 * permissive so it floods a superset of what the real carvers flood, and the answer is
 * memoised on (seed, chunk). Both carvers run against a shared step mask, as
 * {@code runCarvers} does, because they are the same generation step.
 *
 * <p>Being a low-48 property it is shareable across sister seeds exactly as the air walk
 * is, so under {@code --sisters} it costs nothing per sister.
 */
public final class LiquidCarveProbe {

    /** Floods everything it is asked to and records where. */
    private final class Stub implements Carver.Target {
        @Override
        public boolean canReplace(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return false;
        }

        @Override
        public void setCaveAir(int x, int y, int z) {
            // The underwater carvers fill with water; this is the land branch.
        }

        @Override
        public void setWater(int x, int y, int z, boolean scheduleTick) {
            if (y >= 0 && y < 256) {
                flooded.set((x & 15) | (z & 15) << 4 | y << 8);
            }
        }
    }

    private final BitSet flooded = new BitSet(65536);
    private final BitSet stepMask = new BitSet(65536);
    private final JavaRandom random = new JavaRandom();
    private final Stub stub = new Stub();

    private long walkedSeed;
    private int walkedChunkX;
    private int walkedChunkZ;
    private boolean walked;

    /**
     * Runs both LIQUID-step carvers for one chunk, from every start chunk within the
     * carve radius. The salt restarts at 0 for the step, so the canyon is index 0 and
     * the cave index 1 — the opposite order to the AIR step.
     */
    public void walk(long worldSeed, int chunkX, int chunkZ) {
        if (walked && walkedSeed == worldSeed
                && walkedChunkX == chunkX && walkedChunkZ == chunkZ) {
            return;
        }
        flooded.clear();
        stepMask.clear();
        Carver canyon = new CanyonCarver(stub, chunkX, chunkZ, true,
                CarverConfig.SEA_LEVEL, stepMask);
        Carver cave = new CaveCarver(stub, chunkX, chunkZ, true,
                CarverConfig.SEA_LEVEL, stepMask);
        int r = CarverConfig.CARVE_RADIUS;
        for (int sx = chunkX - r; sx <= chunkX + r; sx++) {
            for (int sz = chunkZ - r; sz <= chunkZ + r; sz++) {
                if (CarverConfig.isStartChunk(random, worldSeed, 0, sx, sz,
                        CarverConfig.UNDERWATER_CANYON)) {
                    canyon.carve(random, sx, sz);
                }
                if (CarverConfig.isStartChunk(random, worldSeed, 1, sx, sz,
                        CarverConfig.UNDERWATER_CAVE)) {
                    cave.carve(random, sx, sz);
                }
            }
        }
        walkedSeed = worldSeed;
        walkedChunkX = chunkX;
        walkedChunkZ = chunkZ;
        walked = true;
    }

    /** Whether the last {@link #walk} flooded this block. World coordinates. */
    public boolean isFlooded(int x, int y, int z) {
        if (y < 0 || y >= 256 || (x >> 4) != walkedChunkX || (z >> 4) != walkedChunkZ) {
            // Outside what was walked, so unknown: say yes rather than reject something
            // that might be real, exactly as the air probe does.
            return true;
        }
        return flooded.get((x & 15) | (z & 15) << 4 | y << 8);
    }

    /**
     * {@code needWater}: water in any of the four horizontal neighbours. A neighbour in
     * another chunk is unknown to this walk and accepted, which keeps the test on the
     * permissive side of the chunk border.
     */
    public boolean waterBeside(int x, int y, int z) {
        return isFlooded(x - 1, y, z) || isFlooded(x + 1, y, z)
                || isFlooded(x, y, z - 1) || isFlooded(x, y, z + 1);
    }
}
