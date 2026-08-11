package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.BiomeCaneConfig;
import dev.drakou111.sugarcane.gen.BiomeIds;
import dev.drakou111.sugarcane.gen.DirtBlobFilter;
import dev.drakou111.sugarcane.rng.DecorationLattice;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DirtBlobFilter#couldSupplyNarrowed} against real generated terrain.
 *
 * <p>{@code couldSupply} enumerates all 55 offsets a blob could sit at, because at target-set time
 * there is no world seed and so no way to know how many earlier blobs drew their 33 radii. 6cf
 * measured that at ~37x looser than the truth and could not fix it exactly: the bail reads
 * {@code OCEAN_FLOOR_WG}, which {@code ArrayWorld} tracks as the world changes, so by
 * UNDERGROUND_ORES it reflects carving and needs terrain.
 *
 * <p>The narrowing is sound for one reason: <b>carving only removes blocks</b>, so the noise floor
 * is an upper bound on the real one, and a blob whose box sits above <em>that</em> certainly drew
 * no radii. Those stop branching; everything else still branches both ways.
 *
 * <p>So the test is that it accepts every block the loose filter accepted and really is dirt —
 * <em>zero</em> regressions, measured against generated chunks rather than against the filter's
 * own opinion. A tightened filter that loses finds looks exactly like one that works.
 */
class NarrowedSoilTest {

    @Test
    void narrowingLosesNoRealDirtAndIsTighter() {
        SplittableRandom r = new SplittableRandom(31337L);
        RegionSearcher.Worker w = new RegionSearcher.Worker(20, false, 0,
                new RegionSearcher.Stats(), 0);
        DirtBlobFilter f = new DirtBlobFilter();

        long dirt = 0, looseOnDirt = 0, regressed = 0, looseOnOther = 0, narrowOnOther = 0;

        for (int c = 0; c < 25; c++) {
            long seed = r.nextLong();
            int cx = r.nextInt(20000) - 10000, cz = r.nextInt(20000) - 10000;
            int biome = BiomeIds.noiseGen(new kaptainwutax.biomeutils.source.OverworldBiomeSource(
                    kaptainwutax.mcutils.version.MCVersion.v1_16_1, seed), cx * 4 + 2, cz * 4 + 2);
            if (!RegionSearcher.isSearchableOcean(biome)
                    || !BiomeCaneConfig.hasSugarCane(biome)) {
                continue;
            }
            w.prepare(seed);
            w.searchOneChunk(cx, cz);
            ArrayWorld world = w.world;
            long ds = new DecorationLattice(seed).decorationSeedOf(cx, cz);

            DirtBlobFilter.FloorOracle floor = (x, z) -> {
                for (int y = ArrayWorld.HEIGHT - 1; y >= 0; y--) {
                    if (Blocks.blocksMotion(w.noiseAt(x, y, z))) {
                        return y + 1;
                    }
                }
                return 0;
            };

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int y = 12; y < 60; y++) {
                        boolean loose = f.couldSupply(ds, lx, y, lz);
                        boolean narrow = f.couldSupplyNarrowed(ds, cx, cz, lx, y, lz, floor);
                        if (world.getBlock(cx * 16 + lx, y, cz * 16 + lz) == Blocks.DIRT) {
                            dirt++;
                            if (loose) {
                                looseOnDirt++;
                                if (!narrow) {
                                    regressed++;
                                }
                            }
                        } else {
                            if (loose) looseOnOther++;
                            if (narrow) narrowOnOther++;
                        }
                    }
                }
            }
        }

        assertTrue(dirt > 500, "not enough real dirt sampled: " + dirt);
        assertTrue(looseOnDirt > 500, "not enough accepted dirt sampled: " + looseOnDirt);
        assertEquals(0, regressed,
                "the narrowing threw away " + regressed + " blocks that really are dirt and the "
                        + "loose filter kept; it may only ever accept a subset of what is real");
        // Measured 7,651 -> 2,118 over 25 chunks. Held loosely: what matters is that removing
        // the provably-bailed blobs from the enumeration actually costs the filter something.
        assertTrue(narrowOnOther * 2 < looseOnOther,
                "expected the narrowing to cut false positives at least twofold, got "
                        + looseOnOther + " -> " + narrowOnOther);
    }
}
