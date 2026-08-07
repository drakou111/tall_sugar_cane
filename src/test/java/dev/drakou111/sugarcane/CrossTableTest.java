package dev.drakou111.sugarcane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The join table is the artefact several people build together, so the ways it can silently
 * go wrong are the ways a campaign silently searches less than it thinks.
 */
class CrossTableTest {

    private static CrossTable.Header header(boolean endings, int min, List<CrossTable.Range> r) {
        return new CrossTable.Header(endings, min, 10, 5, r);
    }

    @Test
    void aTableSurvivesTheRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.bin");
        int[] keys = {5, 900, 12345};
        long[] seeds = {1L, 2L, 1L << 47};
        CrossTable.save(file, header(true, 8, List.of(new CrossTable.Range(64, 2048))),
                keys, seeds, 3);

        CrossTable.Loaded back = CrossTable.load(file, header(true, 8, List.of()));
        assertArrayEquals(keys, back.keys());
        assertArrayEquals(seeds, back.seeds());
        assertEquals(1, back.header().ranges().size());
        assertEquals(2048, back.header().covered());
        assertEquals(64 + 2048, back.header().nextFrom());
    }

    /** A missing file is a first run, not an error, so nothing needs a special case. */
    @Test
    void anAbsentTableIsNull(@TempDir Path dir) throws IOException {
        assertNull(CrossTable.load(dir.resolve("nope.bin"), header(true, 8, List.of())));
    }

    /**
     * The dangerous failure: a table from another search loads fine and joins chains that
     * never meet, which looks like a working run finding nothing.
     */
    @Test
    void aDifferentSearchIsRefused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.bin");
        CrossTable.save(file, header(true, 8, List.of(new CrossTable.Range(0, 16))),
                new int[] {1}, new long[] {1}, 1);

        assertThrows(IOException.class,
                () -> CrossTable.load(file, header(true, 12, List.of())), "different height");
        assertThrows(IOException.class,
                () -> CrossTable.load(file, header(false, 8, List.of())), "different side");
    }

    @Test
    void garbageIsRefusedRatherThanParsed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.bin");
        Files.writeString(file, "not a table at all");
        assertThrows(IOException.class, () -> CrossTable.loadAny(file));
    }

    /** Disjoint contributors overlap in nothing; a repeat of the same slice overlaps fully. */
    @Test
    void overlapCountsDuplicatedEffort() {
        List<CrossTable.Range> a = List.of(new CrossTable.Range(0, 100));
        List<CrossTable.Range> b = List.of(new CrossTable.Range(100, 100));
        assertEquals(0, CrossTable.overlap(a, b));
        assertEquals(100, CrossTable.overlap(a, a));
        assertEquals(40, CrossTable.overlap(a, List.of(new CrossTable.Range(60, 80))));
    }

    /**
     * nextFrom must clear every range, not just the last one added. Handing out a start that
     * somebody already scanned is the one thing this whole mechanism exists to prevent.
     */
    @Test
    void nextFromClearsEveryRangeHoweverTheyWereAdded() {
        CrossTable.Header h = header(true, 8, List.of(
                new CrossTable.Range(5000, 100),
                new CrossTable.Range(0, 100),
                new CrossTable.Range(900, 100)));
        assertEquals(5100, h.nextFrom());
        assertEquals(300, h.covered());
        assertTrue(CrossTable.overlap(List.of(new CrossTable.Range(h.nextFrom(), 1_000_000)),
                h.ranges()) == 0, "the next start must not land on covered ground");
    }
}
