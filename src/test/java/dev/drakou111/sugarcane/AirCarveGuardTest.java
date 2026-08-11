package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.AirCarveProbe;
import dev.drakou111.sugarcane.gen.BiomeCaneConfig;
import dev.drakou111.sugarcane.gen.BiomeIds;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backing the AIR carvers' water guard with the noise, against real generated terrain.
 *
 * <p>6cf found the probe's dominant over-approximation. {@code waterGuard()} is
 * {@code !underwater}, and the AIR-step carvers are the <em>only</em> source of air below sea
 * level — so the carvers that matter are exactly the ones that run the guard, and below sea
 * level every non-solid block is water. With {@code isWater} stubbed to false a ravine
 * approaching the ocean floor carves straight through where the real one aborts sphere after
 * sphere, which is why a height-20 run saw 58.6% of its candidates rejected at chunk A's first
 * column for BLOCKED.
 *
 * <p><b>Soundness is the whole test.</b> The probe is allowed to be wrong in one direction only:
 * it may accept a block no carver cut, never reject one a carver did. A tightening that loses
 * finds looks exactly like one that works, so this measures against generated terrain rather
 * than against the probe's own opinion, and asserts <em>zero</em> regressions — not "few".
 */
class AirCarveGuardTest {

    private record Counts(long air, long stubOnAir, long noiseOnAir, long regressed,
            long stubOnSolid, long noiseOnSolid) {
    }

    private Counts measure(int chunks, boolean ravinesOnly) {
        SplittableRandom r = new SplittableRandom(20260810L);
        RegionSearcher.Worker w = new RegionSearcher.Worker(20, false, 0,
                new RegionSearcher.Stats(), 0);
        long air = 0, stubAir = 0, noiseAir = 0, regressed = 0, stubSolid = 0, noiseSolid = 0;

        for (int c = 0; c < chunks; c++) {
            long seed = r.nextLong();
            int cx = r.nextInt(20000) - 10000, cz = r.nextInt(20000) - 10000;
            int biome = BiomeIds.noiseGen(new kaptainwutax.biomeutils.source.OverworldBiomeSource(
                    kaptainwutax.mcutils.version.MCVersion.v1_16_1, seed), cx * 4 + 2, cz * 4 + 2);
            if (!RegionSearcher.isSearchableOcean(biome)
                    || !BiomeCaneConfig.hasSugarCane(biome)) {
                continue;
            }
            w.prepare(seed);
            w.searchOneChunk(cx, cz);                 // ground truth
            ArrayWorld world = w.world;

            AirCarveProbe stub = new AirCarveProbe().ravinesOnly(ravinesOnly);
            stub.walk(seed, cx, cz, true);
            AirCarveProbe noise = new AirCarveProbe().ravinesOnly(ravinesOnly)
                    .water((x, y, z) -> y >= 0 && y < ArrayWorld.HEIGHT
                            && Blocks.isWaterFluid(w.noiseAt(x, y, z)));
            noise.walk(seed, cx, cz, true);
            // The same probe restricted to one column, which is how crossfind runs it. Only
            // spheres containing a column can carve it, so this must agree with the full-chunk
            // answer exactly -- it is an optimisation, not a second approximation.
            AirCarveProbe targeted = new AirCarveProbe().ravinesOnly(ravinesOnly)
                    .water((x, y, z) -> y >= 0 && y < ArrayWorld.HEIGHT
                            && Blocks.isWaterFluid(w.noiseAt(x, y, z)));

            for (int x = cx * 16; x < cx * 16 + 16; x++) {
                for (int z = cz * 16; z < cz * 16 + 16; z++) {
                    // Below sea level and above the lava layer: where cane geometry lives,
                    // and the only band where "air implies an AIR carver" holds.
                    for (int y = 11; y < 63; y++) {
                        boolean s = stub.isCarved(x, y, z);
                        boolean n = noise.isCarved(x, y, z);
                        if (y == 30) {          // one column-targeted spot check per column
                            targeted.guardColumn(x, z);
                            targeted.walk(seed, cx, cz, true);
                            for (int ty = 11; ty < 63; ty++) {
                                if (noise.isCarved(x, ty, z) != targeted.isCarved(x, ty, z)) {
                                    throw new AssertionError("targeted guard disagrees with the "
                                            + "full one at " + x + "," + ty + "," + z);
                                }
                            }
                        }
                        if (world.getBlock(x, y, z) == Blocks.AIR) {
                            air++;
                            if (s) stubAir++;
                            if (n) noiseAir++;
                            if (s && !n) regressed++;
                        } else {
                            if (s) stubSolid++;
                            if (n) noiseSolid++;
                        }
                    }
                }
            }
        }
        return new Counts(air, stubAir, noiseAir, regressed, stubSolid, noiseSolid);
    }

    /**
     * With every carver on, the stub is sound — it accepts every block that really is air —
     * and the guard must not cost a single one of them.
     */
    @Test
    void theGuardLosesNoRealAirWithEveryCarver() {
        Counts c = measure(60, false);
        assertTrue(c.air() > 1000, "not enough real air sampled: " + c.air());
        assertEquals(c.air(), c.stubOnAir(),
                "the stub is supposed to accept every genuinely air block");
        assertEquals(0, c.regressed(),
                "the noise-backed guard threw away " + c.regressed() + " blocks that really "
                        + "are air; it is only allowed to be wrong in the accepting direction");
        assertTrue(c.noiseOnSolid() < c.stubOnSolid(),
                "no selectivity gained: " + c.stubOnSolid() + " -> " + c.noiseOnSolid());
    }

    /**
     * And in the mode crossfind actually runs. Ravines-only is a deliberate coverage trade, so
     * the stub already declines most real air here; what must not change is the part it keeps.
     */
    @Test
    void theGuardLosesNoRealAirWithRavinesOnly() {
        Counts c = measure(60, true);
        assertEquals(0, c.regressed(),
                "the noise-backed guard threw away " + c.regressed() + " blocks the stub kept");
        // Ravines start in 1 chunk in 50, so the false positives arrive in bursts and a short
        // sample can hold none at all -- 40 chunks caught zero where 60 caught 4,914. Asserting
        // a ratio against an empty sample would be a test that fails for the wrong reason, so
        // the selectivity claim is only made once there is something to claim it about.
        if (c.stubOnSolid() > 500) {
            assertTrue(c.noiseOnSolid() * 20 < c.stubOnSolid(),
                    "expected the guard to cut false positives by more than 20x, got "
                            + c.stubOnSolid() + " -> " + c.noiseOnSolid());
        }
    }
}
