package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.GpuStackEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code --enum} drives pass 1 from the state enumerator instead of the seed scan.
 *
 * <p>A smoke test in the spirit of {@code NoGrowTest}: the interesting failures here are not
 * subtle, they are simply never reached unless a whole run is driven. The enumerator hands back
 * decoration seeds that {@code ChainPrefilter} then has to re-derive geometry for, and those two
 * do not agree by construction — 85% of enumerated seeds survive the ending filter — so "pass 1
 * stored nothing" is a live outcome that a banner line would not reveal.
 *
 * <p>The k cursor is the other thing worth pinning. It lives in its own list, keyed by sweep
 * shape, because {@code lows} and the y band decide what one k covers; a second run must take
 * fresh k rather than redo the first run's.
 *
 * <p>Skips without a CUDA device, like {@code GpuStackEnumTest}.
 */
class CrossFindEnumTest {

    @Test
    void enumFillsPassOneAndAdvancesItsOwnCursor(@TempDir Path dir) throws Exception {
        assumeTrue(GpuStackEnum.detect() != null,
                "no CUDA device: " + GpuStackEnum.lastFailure());
        Path table = dir.resolve("e.table");

        String[] run = {"2000000", "4", "17", "10", "7", "--enum", "--enum-k=400000",
                "--sisters=4", "--table=" + table, "--sample-from=0", "--no-report"};
        CrossFind.main(run);

        CrossTable.Loaded first = CrossTable.load(table, null);
        assertNotNull(first, "pass 1 should have written a table");
        assertTrue(first.keys().length > 0,
                "the enumerator produced chains but none survived into the table, so --enum "
                        + "feeds nothing downstream");
        assertEquals(1, first.header().enumSweeps().size());
        assertEquals(0, first.header().enumSweeps().get(0).kFrom());
        assertEquals(400_000, first.header().enumSweeps().get(0).kCount());

        // Second run: same settings, so it must continue past the first sweep rather than
        // re-enumerate it. Sharing the sample cursor would have restarted it at k=0.
        CrossFind.main(run);
        CrossTable.Loaded second = CrossTable.load(table, null);
        assertEquals(2, second.header().enumSweeps().size());
        assertEquals(400_000, second.header().enumSweeps().get(1).kFrom(),
                "the second sweep must take fresh k, not redo the first");
        assertTrue(second.keys().length > first.keys().length,
                "a fresh k slice should add chains");
    }
}
