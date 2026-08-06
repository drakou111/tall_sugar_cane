package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.ChainPrefilter;
import dev.drakou111.sugarcane.gen.OrbitSampler;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How many joins the keyed lookup loses, and how many it invents.
 *
 * <p>{@code crossfind} does not compare pairs — it hashes each chain to a block and pairs
 * whatever collides, which is what makes N seeds cost N rather than N squared. That is only
 * sound if the key is exactly the join condition. Off by one in the 16dx, or a nibble in the
 * key that does not belong there, and the search quietly stops finding things; the funnel still
 * looks healthy, because a lost join is a join that never existed as far as the counters know.
 *
 * <p>So this does the comparison the real thing refuses to do: every ending against every
 * beginning, by hand, and requires the same set of pairs.
 */
class CrossFindJoinTest {

    private static final int OCEAN_INDEX = 5;
    private static final int MIN = 8;
    private static final int DX = 1, DZ = 0;

    private record Hit(long ds, int x, int z, int y) {
    }

    /** Endings keep the depth band; beginnings do not, since they stand on the neighbour. */
    private static List<Hit> collect(long seeds, boolean endings) {
        ChainPrefilter filter = endings
                ? new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT)
                : new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT, 11, 64, 3, 4);
        List<Hit> out = new ArrayList<>();
        long runs = seeds / OrbitSampler.RUN;
        for (long run = 0; run < runs; run++) {
            long ds = OrbitSampler.runStart(run);
            for (int k = 0; k < OrbitSampler.RUN; k++) {
                int n = filter.collectChains(ds, OCEAN_INDEX, MIN);
                if (!filter.chainsOverflowed()) {
                    for (int i = 0; i < n; i++) {
                        long chain = filter.chain(i);
                        out.add(new Hit(ds, ChainPrefilter.chainX(chain),
                                ChainPrefilter.chainZ(chain),
                                endings ? ChainPrefilter.chainTop(chain)
                                        : ChainPrefilter.chainBaseY(chain, 0)));
                    }
                }
                ds = OrbitSampler.shift(ds, OCEAN_INDEX, SugarCaneFeature.VEGETAL_DECORATION);
            }
        }
        return out;
    }

    @Test
    void theKeyedJoinFindsExactlyThePairsBruteForceDoes() {
        // Enough that joins actually happen: at min 8 they run about 2e-7 per pair, so a
        // few hundred million pairs are needed before the comparison means anything. The
        // first version used 400k seeds, found zero joins either way, and passed.
        long seeds = 4_000_000L;
        List<Hit> endings = collect(seeds, true);
        List<Hit> beginnings = collect(seeds, false);
        assertTrue(endings.size() > 100 && beginnings.size() > 100,
                "too few chains to measure anything: " + endings.size() + " and "
                        + beginnings.size());

        // Brute force: the join condition itself, stated once, with no key anywhere near it.
        // Two chains meet when they name the same world block, which for chunks dx,dz apart
        // means the ending's relative x is the beginning's plus 16dx. Flattened to primitive
        // arrays because this really is every pair -- hundreds of millions of them.
        int na = endings.size(), nb = beginnings.size();
        long[] ads = new long[na];
        int[] ax = new int[na], az = new int[na], ay = new int[na];
        for (int i = 0; i < na; i++) {
            Hit h = endings.get(i);
            ads[i] = h.ds;
            ax[i] = h.x;
            az[i] = h.z;
            ay[i] = h.y;
        }
        long[] bds = new long[nb];
        int[] bx = new int[nb], bz = new int[nb], by = new int[nb];
        for (int i = 0; i < nb; i++) {
            Hit h = beginnings.get(i);
            bds[i] = h.ds;
            bx[i] = h.x + 16 * DX;
            bz[i] = h.z + 16 * DZ;
            by[i] = h.y;
        }
        Set<String> byHand = new HashSet<>();
        for (int i = 0; i < na; i++) {
            for (int j = 0; j < nb; j++) {
                if (ay[i] == by[j] && ax[i] == bx[j] && az[i] == bz[j]
                        && ((ads[i] ^ bds[j]) & 15L) == 0) {
                    byHand.add(ads[i] + ":" + bds[j] + ":" + ax[i] + ":" + az[i] + ":" + ay[i]);
                }
            }
        }

        // Keyed, the way crossfind does it, through the real key function.
        Set<String> byKey = new HashSet<>();
        java.util.Map<Integer, List<Hit>> table = new java.util.HashMap<>();
        for (Hit a : endings) {
            if (CrossFind.inFrame(a.x, a.z, a.y)) {
                table.computeIfAbsent(CrossFind.key(a.x, a.z, a.y, a.ds),
                        k -> new ArrayList<>()).add(a);
            }
        }
        for (Hit b : beginnings) {
            int x = b.x + 16 * DX, z = b.z + 16 * DZ;
            if (!CrossFind.inFrame(x, z, b.y)) {
                continue;
            }
            for (Hit a : table.getOrDefault(CrossFind.key(x, z, b.y, b.ds), List.of())) {
                byKey.add(a.ds + ":" + b.ds + ":" + a.x + ":" + a.z + ":" + a.y);
            }
        }

        Set<String> lost = new HashSet<>(byHand);
        lost.removeAll(byKey);
        Set<String> invented = new HashSet<>(byKey);
        invented.removeAll(byHand);

        System.out.printf("CrossFind join: %d endings x %d beginnings, %d joins by hand, "
                        + "%d by key -- lost %d, invented %d%n",
                endings.size(), beginnings.size(), byHand.size(), byKey.size(),
                lost.size(), invented.size());
        assertEquals(0, lost.size(), "the keyed join LOST pairs, e.g. " + first(lost));
        assertEquals(0, invented.size(), "the keyed join INVENTED pairs, e.g. " + first(invented));
        assertEquals(byHand.size(), byKey.size());
        assertTrue(byHand.size() > 0, "no joins occurred at all, so this measured nothing -- "
                + "raise the seed count");
    }

    /**
     * The frame check is a filter, so it is also a place to lose finds. A chain reaching from
     * chunk B into A's territory has relative x in -4..3 for dx=1, and anything outside that
     * genuinely cannot be reached by both chunks — but the bound has to be the real one.
     */
    @Test
    void theFrameRejectsOnlyBlocksNeitherChunkCouldReach() {
        for (int xb = -4; xb <= 19; xb++) {
            boolean reachable = xb + 16 >= -4 && xb + 16 <= 19;
            assertEquals(reachable, CrossFind.inFrame(xb + 16, 0, 30),
                    "frame disagrees at relative x " + xb);
        }
        // Which leaves an eight-wide strip, exactly the overlap the two chunks share.
        int strip = 0;
        for (int xb = -4; xb <= 19; xb++) {
            if (CrossFind.inFrame(xb + 16, 0, 30)) {
                strip++;
            }
        }
        assertEquals(8, strip, "the shared strip should be eight blocks wide");
    }

    private static String first(Set<String> set) {
        return set.isEmpty() ? "(none)" : set.iterator().next();
    }
}
