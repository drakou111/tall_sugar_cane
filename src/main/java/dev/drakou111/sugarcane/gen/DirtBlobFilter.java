package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.rng.Mth;

/**
 * Whether this chunk's own {@code ORE_DIRT} pass could put dirt at one block, from
 * the decoration seed alone with no terrain.
 *
 * <p>A chain's first column needs soil under its base, and 98% of real stackable spots
 * stand on ore-blob dirt (FINDINGS 6i). The blobs run from the same decoration seed as
 * the cane — {@code setFeatureSeed(ds, 0, 6)} against the cane's {@code (ds, 5, 8)} —
 * so this is answerable when the target set is built, which is free and amortises,
 * rather than by generating the chunk.
 *
 * <h2>The draw count is terrain-dependent, but boundedly</h2>
 *
 * {@code OreBlob.place} bails before drawing the 33 radii when the blob's box sits
 * entirely above {@code OCEAN_FLOOR_WG}. So the stream is not a pure function of the
 * seed. It is close to one: every blob spends 6 {@code next()} calls (three for the
 * decorator's x, z, y and three inside {@code place}), plus 66 more (33
 * {@code nextDouble}) if it placed. The state after k blobs therefore depends only on
 * <em>how many</em> of them placed, not which, so blob k begins at
 * {@code 6k + 66m} for some {@code m <= k} — 55 combinations, enumerated here the way
 * {@link ChainPrefilter} enumerates its success shifts.
 *
 * <h2>What it is allowed to get wrong</h2>
 *
 * Only in the accepting direction:
 * <ul>
 *   <li>enumerating m admits blobs that could not really be at that offset;</li>
 *   <li>{@code isNaturalStone} is ignored, so a blob that covers the block but could
 *       not convert it still counts;</li>
 *   <li>a {@code nextInt(3)} that would have hit its rejection retry desynchronises
 *       the flat indexing, so that is detected and the seed accepted outright.</li>
 * </ul>
 *
 * <p><b>The one thing it does get wrong in the rejecting direction</b>, and the reason
 * it costs coverage: a blob from a <em>neighbouring</em> chunk reaches up to 6.7 blocks
 * and can supply the dirt. Its decoration seed needs {@code a} and {@code b}, hence the
 * world seed, which is not chosen yet when the target set is built. So a find whose
 * soil came from a neighbour's blob is discarded. See {@code DirtBlobFilterTest} for
 * the measured rate.
 */
public final class DirtBlobFilter {

    private static final int BLOBS = OreBlob.DIRT_COUNT;
    private static final int SIZE = OreBlob.DIRT_SIZE;
    /** Three decorator draws plus nextFloat and two nextInt(3) inside place(). */
    private static final int CALLS_PER_BLOB = 6;
    /** 33 nextDouble, two next() calls each. */
    private static final int CALLS_PER_PLACE = 2 * SIZE;

    /**
     * How far a blob can reach from its centre draw. The line spans +-33/8 = 4.125 in
     * x and z, and the largest sphere radius is {@code ((1+1)*2.0625 + 1)/2 = 2.5625},
     * so 6.69 horizontally and 4.57 vertically. 8 is the same bound with margin.
     */
    public static final int REACH = 8;

    /** ORE_DIRT is the first feature of UNDERGROUND_ORES, which is step 6. */
    public static final int ORE_INDEX = 0;
    public static final int ORE_STEP = 6;

    private final int[] draws;
    private final JavaRandom random = new JavaRandom();

    public DirtBlobFilter() {
        this.draws = new int[CALLS_PER_BLOB * BLOBS + CALLS_PER_PLACE * BLOBS + 16];
    }

    /**
     * @param relX chunk-relative x of the soil block, 0..15
     * @param relZ chunk-relative z, 0..15
     * @return false only when no blob of this chunk's pass, under any shift, could
     *         have put dirt there
     */
    public boolean couldSupply(long decorationSeed, int relX, int soilY, int relZ) {
        random.setFeatureSeed(decorationSeed, ORE_INDEX, ORE_STEP);
        for (int i = 0; i < draws.length; i++) {
            draws[i] = random.nextInt();
        }

        for (int k = 0; k < BLOBS; k++) {
            for (int m = 0; m <= k; m++) {
                int base = CALLS_PER_BLOB * k + CALLS_PER_PLACE * m;
                if (base + CALLS_PER_BLOB + CALLS_PER_PLACE >= draws.length) {
                    continue;
                }
                int x = bounded(draws[base], 16);
                int z = bounded(draws[base + 1], 16);
                int y = bounded(draws[base + 2], 256);
                if (Math.abs(x - relX) > REACH || Math.abs(z - relZ) > REACH
                        || Math.abs(y - soilY) > REACH) {
                    continue;
                }
                // Either nextInt(3) hitting its retry would shift everything after it.
                if (rejects(draws[base + 4], 3) || rejects(draws[base + 5], 3)) {
                    return true;
                }
                if (covers(base, x, y, z, relX, soilY, relZ)) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * One past the highest motion-blocking block, as the noise leaves it.
     *
     * <p>An <em>upper bound</em> on what {@code OreFeature} really tests against, and that is the
     * whole trick. {@code ArrayWorld} tracks {@code OCEAN_FLOOR_WG} as the world changes, and by
     * UNDERGROUND_ORES the carvers have run — carving only removes blocks, so the true floor is
     * at or below the noise's. Reading it off the noise therefore cannot say "bails" about a blob
     * that really placed.
     */
    public interface FloorOracle {
        int noiseFloorHeight(int x, int z);
    }

    /**
     * The same question as {@link #couldSupply}, with the blobs that provably bailed removed
     * from the enumeration.
     *
     * <p>{@code couldSupply} enumerates all 55 offsets a blob could sit at, because at target-set
     * time there is no world seed and so no way to know how many earlier blobs drew their 33
     * radii. That is measured at ~37x looser than the truth, and 6cf could not fix it exactly:
     * the bail reads a heightmap that reflects carving, which needs terrain.
     *
     * <p>It can be <em>narrowed</em> soundly. {@code place} bails when its whole box sits above
     * the floor, and the noise floor is an upper bound on the real one, so {@code minY} above it
     * means the blob certainly drew no radii. Those blobs stop branching. The rest still branch
     * both ways, so nothing a real blob could have done is excluded.
     *
     * @param floor the noise floor, in world coordinates
     */
    public boolean couldSupplyNarrowed(long decorationSeed, int chunkX, int chunkZ,
            int relX, int soilY, int relZ, FloorOracle floor) {
        random.setFeatureSeed(decorationSeed, ORE_INDEX, ORE_STEP);
        for (int i = 0; i < draws.length; i++) {
            draws[i] = random.nextInt();
        }
        return branch(0, 0, chunkX, chunkZ, relX, soilY, relZ, floor);
    }

    /** Walks blob {@code k} at stream offset {@code base}, branching only where the bail is open. */
    private boolean branch(int k, int base, int chunkX, int chunkZ,
            int relX, int soilY, int relZ, FloorOracle floor) {
        if (k >= BLOBS || base + CALLS_PER_BLOB + CALLS_PER_PLACE >= draws.length) {
            return false;
        }
        int x = bounded(draws[base], 16);
        int z = bounded(draws[base + 1], 16);
        int y = bounded(draws[base + 2], 256);

        boolean canReach = Math.abs(x - relX) <= REACH && Math.abs(z - relZ) <= REACH
                && Math.abs(y - soilY) <= REACH;
        if (canReach) {
            if (rejects(draws[base + 4], 3) || rejects(draws[base + 5], 3)) {
                return true;        // the retry desynchronises the indexing; accept rather than guess
            }
            if (covers(base, x, y, z, relX, soilY, relZ)) {
                return true;
            }
        }
        // Does this blob certainly draw no radii? place() scans its box for any column whose
        // floor reaches minY, so if none does under the upper bound, none does at all.
        int spread = ceilOf((float) SIZE / 8.0f);
        int pad = ceilOf(((float) SIZE / 16.0f * 2.0f + 1.0f) / 2.0f);
        int minY = y - 2 - pad;
        int worldX = chunkX * 16 + x, worldZ = chunkZ * 16 + z;
        int span = 2 * (spread + pad);
        boolean mayPlace = false;
        for (int px = worldX - spread - pad; px <= worldX - spread - pad + span && !mayPlace; px++) {
            for (int pz = worldZ - spread - pad;
                    pz <= worldZ - spread - pad + span && !mayPlace; pz++) {
                if (minY <= floor.noiseFloorHeight(px, pz)) {
                    mayPlace = true;
                }
            }
        }
        if (!mayPlace) {
            return branch(k + 1, base + CALLS_PER_BLOB, chunkX, chunkZ, relX, soilY, relZ, floor);
        }
        return branch(k + 1, base + CALLS_PER_BLOB, chunkX, chunkZ, relX, soilY, relZ, floor)
                || branch(k + 1, base + CALLS_PER_BLOB + CALLS_PER_PLACE, chunkX, chunkZ,
                        relX, soilY, relZ, floor);
    }

    private static int ceilOf(float f) {
        int i = (int) f;
        return f > i ? i + 1 : i;
    }

    /** {@code OreBlob.doPlace}'s spheres, asked whether one contains the block. */
    private boolean covers(int base, int x, int y, int z, int tx, int ty, int tz) {
        float angle = toFloat(draws[base + 3]) * (float) Math.PI;
        float spread = (float) SIZE / 8.0f;
        // Vanilla computes the endpoints in float and only then widens; keep that,
        // because far from the origin a float step is coarser than a block.
        double x0 = (float) x + Mth.sin(angle) * spread;
        double x1 = (float) x - Mth.sin(angle) * spread;
        double z0 = (float) z + Mth.cos(angle) * spread;
        double z1 = (float) z - Mth.cos(angle) * spread;
        double y0 = y + bounded(draws[base + 4], 3) - 2;
        double y1 = y + bounded(draws[base + 5], 3) - 2;

        int radiiAt = base + CALLS_PER_BLOB;
        for (int i = 0; i < SIZE; i++) {
            float t = (float) i / (float) SIZE;
            double cx = x0 + t * (x1 - x0);
            double cy = y0 + t * (y1 - y0);
            double cz = z0 + t * (z1 - z0);
            double scale = toDouble(draws[radiiAt + 2 * i], draws[radiiAt + 2 * i + 1])
                    * (double) SIZE / 16.0;
            double radius = ((double) (Mth.sin((float) Math.PI * t) + 1.0f) * scale + 1.0) / 2.0;
            double dx = ((double) tx + 0.5 - cx) / radius;
            if (dx * dx >= 1.0) {
                continue;
            }
            double dy = ((double) ty + 0.5 - cy) / radius;
            if (dx * dx + dy * dy >= 1.0) {
                continue;
            }
            double dz = ((double) tz + 0.5 - cz) / radius;
            if (dx * dx + dy * dy + dz * dz < 1.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code Random.nextInt(bound)} would have taken its rejection retry on
     * this draw, which consumes an extra {@code next(31)} and shifts the stream.
     * About 9.3e-10 per call at bound 3, so this fires essentially never — but when it
     * does, the flat indexing below it is wrong, so the caller accepts instead.
     */
    private static boolean rejects(int raw, int bound) {
        int bits = raw >>> 1;
        int val = bits % bound;
        return bits - val + (bound - 1) < 0;
    }

    private static int bounded(int raw, int bound) {
        int bits = raw >>> 1;
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) bits) >> 31);
        }
        return bits % bound;
    }

    /** {@code next(24) / (float) (1 << 24)} from a stored {@code next(32)}. */
    private static float toFloat(int raw) {
        return (raw >>> 8) / (float) (1 << 24);
    }

    /** {@code ((long) next(26) << 27) + next(27)) * 0x1.0p-53} from two next(32). */
    private static double toDouble(int hi, int lo) {
        return (((long) (hi >>> 6) << 27) + (lo >>> 5)) * 0x1.0p-53;
    }
}
