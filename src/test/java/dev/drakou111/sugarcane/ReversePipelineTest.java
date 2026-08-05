package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.AirCarveProbe;
import dev.drakou111.sugarcane.gen.BiomeIds;
import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.LayerCaches;
import dev.drakou111.sugarcane.gen.LiquidCarveProbe;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.DecorationLattice;
import dev.drakou111.sugarcane.validate.BiomeSourceValidator;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.version.MCVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both halves of the reverse search, against both columns that are known to exist.
 *
 * <p>A reverse search can only ever find what its target set contains, so the filter
 * rejecting a real find is silent and fatal — it shows up as a search that runs
 * forever with a healthy-looking acceptance rate. These are the only two ground
 * truths available:
 *
 * <ul>
 *   <li>5 tall at 91,16,65 on seed 1500050556, this project's own confirmed find
 *       (FINDINGS, top of file), biome 48, chunk 5,4;</li>
 *   <li>8 tall at -24848077,21,18720986 on seed -7585781829663227268, found
 *       independently and verified here against a real 1.16.1 server
 *       (FINDINGS 6ac), biome 46, chunk -1553005,1170061.</li>
 * </ul>
 *
 * Every ocean biome is count 10, index 5, which is what both of these are.
 */
class ReversePipelineTest {

    private static final int OCEAN_INDEX = 5;

    private static void assertPipelineFinds(long worldSeed, int chunkX, int chunkZ,
            int height, String what) {
        DecorationLattice lattice = new DecorationLattice(worldSeed);
        long decorationSeed = lattice.decorationSeedOf(chunkX, chunkZ);

        // 1. the filter has to keep this seed, or the target set never contains it
        int tallest = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT)
                .tallestPossible(decorationSeed, OCEAN_INDEX);
        assertTrue(tallest >= height,
                what + ": ChainPrefilter says " + tallest + ", needs >= " + height
                        + " or the reverse search can never reach it");

        // 2. the lattice has to turn that seed back into a real chunk
        int[] solved = lattice.solve(decorationSeed);
        assertNotNull(solved, what + ": lattice found no chunk for its own decoration seed");
        assertEquals(decorationSeed, lattice.decorationSeedOf(solved[0], solved[1]),
                what + ": lattice returned a chunk with a different decoration seed");

        System.out.printf("%s: decoration seed %d, filter %d, lattice -> chunk %d,%d%n",
                what, decorationSeed, tallest, solved[0], solved[1]);
    }

    @Test
    void reachesTheConfirmedFiveTall() {
        assertPipelineFinds(1500050556L, 5, 4, 5, "5-tall at 91,16,65");
    }

    @Test
    void reachesTheConfirmedEightTall() {
        assertPipelineFinds(-7585781829663227268L, -1553005, 1170061, 8,
                "8-tall at -24848077,21,18720986");
    }

    /**
     * The position filter, against the only real 8-tall there is. It rejects a
     * candidate unless an AIR-step carver reached every column base of some chain, and
     * if that reasoning is wrong anywhere the reverse search silently stops finding
     * anything — so this is the test that guards it.
     *
     * <p>The find is at -24848077,21,18720986 in chunk -1553005,1170061, whose origin
     * is -24848080,18720976: chunk-relative x=3, z=10. It is a 4+4 chain, so the bases
     * are y=21 and y=25 and both must come back carved.
     */
    /**
     * Both confirmed finds need a placement interleaved between their own columns, which
     * is the thing it is most tempting to forbid: chains that assume an unrelated success
     * look like noise, and forbidding them is a 4.3x at height 8 and 22x at height 9.
     *
     * <p>It would also have thrown away every find this project has ever verified in game.
     * The 8-tall's only chain is baseShift 0 / maxShift 1, the 5-tall's are baseShift 0 /
     * maxShift 2. This test exists so that a future attempt at the same optimisation fails
     * here rather than silently in a search that then finds nothing.
     */
    @Test
    void theRankedFilterKeepsChainsWithInterleavedPlacements() {
        assertTrue(rankedKeeps(72846194777308L, 8), "confirmed 8-tall");
        assertTrue(rankedKeeps(112095894509740L, 5), "confirmed 5-tall");

        ChainPrefilter gated = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT,
                11, 64, 3, 4, 0);
        assertEquals(0, gated.collectChains(72846194777308L, OCEAN_INDEX, 8),
                "if this ever passes, the 8-tall no longer needs an interleaved placement "
                        + "and the maxAnyShift trade is worth measuring again");
    }

    /**
     * The shipped default is a contiguous window, and it is a coverage trade rather than
     * a free tightening: it keeps 87.9% of real finds against a 2.5x smaller target set
     * (FINDINGS 6ao). The 5-tall is in the 12% it drops, because its chunk really did grow
     * an unrelated column between the two that make the stack.
     *
     * <p>Asserted in both directions on purpose. Every other guard here says a real find
     * survives; this one also pins the find that deliberately does not, so the cost stays
     * visible and nobody has to rediscover it from a search that quietly stops finding
     * things. If the 5-tall ever starts passing, the rule changed and the 2.5x with it.
     */
    @Test
    void theContiguousDefaultKeepsTheEightTallAndDropsTheFiveTall() {
        assertTrue(contiguousKeeps(72846194777308L, 8),
                "confirmed 8-tall: shifts 0 -> 1 are consecutive, so it must survive");
        assertEquals(false, contiguousKeeps(112095894509740L, 5),
                "confirmed 5-tall: shifts 0 -> 2 with a foreign placement between, so the "
                        + "contiguous default is expected to drop it -- if it now passes, "
                        + "re-measure what contiguity actually costs");
    }

    private static boolean contiguousKeeps(long decorationSeed, int height) {
        ChainPrefilter contiguous = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT)
                .maxSlack(0);
        return contiguous.collectChains(decorationSeed, OCEAN_INDEX, height) > 0
                || contiguous.chainsOverflowed();
    }

    private static boolean rankedKeeps(long decorationSeed, int height) {
        ChainPrefilter ranked = ChainPrefilter.ranked(SugarCaneFeature.COUNT_DEFAULT, height);
        return ranked.collectChains(decorationSeed, OCEAN_INDEX, height) > 0
                || ranked.chainsOverflowed();
    }

    /**
     * The water half of the position test is the one filter here that can lose real
     * finds -- a spot on the sea floor gets its water from the noise fill, not a carver
     * -- so the two confirmed finds are the only guard against it silently discarding
     * the thing it is meant to keep. Exactly the role 6ac gives the 8-tall for the air
     * probe, and the failure it caught in FINDINGS 6u.
     */
    @Test
    void theWaterProbeAcceptsBothConfirmedFinds() {
        assertTrue(waterProbeAccepts(-7585781829663227268L, -1553005, 1170061, 8),
                "the confirmed 8-tall must survive the water filter");
        assertTrue(waterProbeAccepts(1500050556L, 5, 4, 5),
                "the confirmed 5-tall must survive the water filter");
    }

    private static boolean waterProbeAccepts(long worldSeed, int chunkX, int chunkZ,
            int height) {
        long decorationSeed = new DecorationLattice(worldSeed).decorationSeedOf(chunkX, chunkZ);
        ChainPrefilter filter = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT);
        int chains = filter.collectChains(decorationSeed, OCEAN_INDEX, height);
        if (filter.chainsOverflowed()) {
            return true;
        }
        LiquidCarveProbe liquid = new LiquidCarveProbe();
        for (int i = 0; i < chains; i++) {
            long chain = filter.chain(i);
            int px = chunkX * 16 + ChainPrefilter.chainX(chain);
            int pz = chunkZ * 16 + ChainPrefilter.chainZ(chain);
            liquid.walk(worldSeed, px >> 4, pz >> 4);
            boolean all = true;
            for (int c = 0; c < ChainPrefilter.chainColumns(chain) && all; c++) {
                all = liquid.waterBeside(px, ChainPrefilter.chainBaseY(chain, c) - 1, pz);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theAirProbeAcceptsTheConfirmedEightTall() {
        long worldSeed = -7585781829663227268L;
        int chunkX = -1553005, chunkZ = 1170061;
        int targetX = -24848077, targetZ = 18720986;

        DecorationLattice lattice = new DecorationLattice(worldSeed);
        long decorationSeed = lattice.decorationSeedOf(chunkX, chunkZ);

        ChainPrefilter filter = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT);
        int chains = filter.collectChains(decorationSeed, OCEAN_INDEX, 8);
        assertTrue(chains > 0 || filter.chainsOverflowed(),
                "collectChains found nothing for a seed whose chain filter says 8");

        OverworldBiomeSource biomes = new OverworldBiomeSource(MCVersion.v1_16_1, worldSeed);
        LayerCaches.enlarge(biomes);
        boolean ocean = BiomeSourceValidator.isOcean(
                BiomeIds.noiseGen(biomes, chunkX * 4, chunkZ * 4));

        AirCarveProbe probe = new AirCarveProbe();
        probe.walk(worldSeed, chunkX, chunkZ, ocean);

        // The blocks the real world holds cane in must read as carved.
        assertTrue(probe.isCarved(targetX, 21, targetZ),
                "the air probe says y=21 was never carved, but the real world has cane there");
        assertTrue(probe.isCarved(targetX, 25, targetZ),
                "the air probe says y=25 was never carved, but the real world has cane there");

        // And the chain the filter found has to be the one at that position.
        boolean found = false;
        for (int i = 0; i < chains; i++) {
            long chain = filter.chain(i);
            int x = chunkX * 16 + ChainPrefilter.chainX(chain);
            int z = chunkZ * 16 + ChainPrefilter.chainZ(chain);
            if (x != targetX || z != targetZ) {
                continue;
            }
            boolean allCarved = true;
            for (int c = 0; c < ChainPrefilter.chainColumns(chain); c++) {
                allCarved &= probe.isCarved(x, ChainPrefilter.chainBaseY(chain, c), z);
            }
            if (allCarved) {
                found = true;
                System.out.printf("air probe: chain at %d,%d bases", x, z);
                for (int c = 0; c < ChainPrefilter.chainColumns(chain); c++) {
                    System.out.print(" y=" + ChainPrefilter.chainBaseY(chain, c));
                }
                System.out.println(" all carved -> accepted");
            }
        }
        assertTrue(found, "no chain at the find's own position survived the air probe; "
                + "the filter would have rejected the only real 8-tall");
    }

    /**
     * The chain filter must not be so loose that it accepts everything, or the reverse
     * search degenerates into the box scan with extra steps. q at height 8 is 3.4%
     * over the whole column and 1.57% with the default depth band, whose whole purpose
     * is to halve it — the search's gain is 1/q, so a drift here is a silent slowdown.
     */
    @Test
    void heightEightIsSelective() {
        ChainPrefilter filter = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT);
        int accepted = 0;
        int trials = 20_000;
        for (int i = 0; i < trials; i++) {
            long z = i * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = (z ^ (z >>> 31)) & ((1L << 48) - 1);
            if (filter.tallestPossible(z, OCEAN_INDEX) >= 8) {
                accepted++;
            }
        }
        double q = (double) accepted / trials;
        System.out.printf("ChainPrefilter q(>=8) = %.4f over %d seeds%n", q, trials);
        assertTrue(q > 0.005 && q < 0.05,
                "q(>=8) drifted to " + q + "; the measured value is 0.0157 with the "
                        + "default depth band and the reverse search's whole gain is 1/q");
    }
}
