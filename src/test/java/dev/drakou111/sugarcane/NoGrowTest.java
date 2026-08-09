package dev.drakou111.sugarcane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --no-grow} skips pass 1 entirely, and skipping it is how the arrays pass 1 fills end up
 * null. That failed twice in a row on the real thing — once on the thread pool, once on the
 * per-thread results — because each downstream loop is its own dereference, and a run that only
 * gets checked as far as its banner line looks fine both times.
 *
 * <p>So this drives a whole run with the flag on and requires it to finish and to have joined
 * against what the table already held. It is a smoke test on purpose: the failure mode was never
 * subtle, it was just never reached.
 */
class NoGrowTest {

    @Test
    void aRunWithNoGrowFinishesAndJoinsAgainstTheStoredTable(@TempDir Path dir) throws Exception {
        Path table = dir.resolve("t.table");

        // A table with something in it, built the ordinary way.
        CrossFind.main(new String[] {"3000000", "4", "16", "--sisters=4", "--floor",
                "--table=" + table, "--sample-from=0", "--no-report"});
        CrossTable.Loaded built = CrossTable.load(table, null);
        assertNotNull(built, "pass 1 should have written a table");
        assertTrue(built.keys().length > 0, "the table needs chains for the next run to use");
        long chains = built.keys().length;
        long covered = built.header().covered();

        // The same command with --no-grow must run to completion rather than dying on a null,
        // and must leave the table exactly as it found it.
        CrossFind.main(new String[] {"3000000", "4", "16", "--sisters=4", "--floor",
                "--no-grow", "--table=" + table, "--sample-from=50000000", "--no-report"});

        CrossTable.Loaded after = CrossTable.load(table, null);
        assertEquals(chains, after.keys().length, "--no-grow must not add chains");
        assertEquals(covered, after.header().covered(),
                "--no-grow must not claim to have covered new ground");
        assertEquals(List.copyOf(built.header().ranges()), List.copyOf(after.header().ranges()),
                "the ranges are what tell a collaborator which ground is taken");
    }
}
