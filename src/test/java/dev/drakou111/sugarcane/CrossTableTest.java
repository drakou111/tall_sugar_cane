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

    /**
     * Enum coverage is a separate list from the sample ranges, and has to stay separate.
     *
     * <p>The two index different things — a sample range names decoration seeds visited, a sweep
     * names k values whose states were constructed — so adding a sweep's count into
     * {@code covered()} would produce a number that means nothing, and {@code covered()} is what
     * collaborators split ground by.
     */
    @Test
    void enumSweepsRoundTripAndStayOutOfTheSampleCount(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("e.bin");
        List<CrossTable.EnumSweep> sweeps = List.of(
                new CrossTable.EnumSweep(0, 400_000, 8, 16, 36),
                new CrossTable.EnumSweep(400_000, 400_000, 8, 16, 36),
                new CrossTable.EnumSweep(0, 100_000, 32, 16, 36));
        CrossTable.Header h = new CrossTable.Header(true, 10, 10, 5,
                List.of(new CrossTable.Range(0, 2048)), sweeps);
        CrossTable.save(file, h, new int[] {7}, new long[] {9L}, 1);

        CrossTable.Loaded back = CrossTable.load(file, null);
        assertEquals(List.copyOf(sweeps), List.copyOf(back.header().enumSweeps()));
        assertEquals(2048, back.header().covered(),
                "a sweep is not a sample range and must not be counted as one");
        assertEquals(800_000L * 8 * 21 + 100_000L * 32 * 21, back.header().enumStates());
    }

    /**
     * The next free k is per sweep shape. {@code lows} and the y band decide what one k covers,
     * so handing a lows=32 run the cursor a lows=8 run left would send it over ground that
     * settings has not touched — and, because sweeps nest, quietly skip states nobody has done.
     */
    @Test
    void theNextEnumStartIsPerShape() {
        CrossTable.Header h = new CrossTable.Header(true, 10, 10, 5, List.of(), List.of(
                new CrossTable.EnumSweep(0, 400_000, 8, 16, 36),
                new CrossTable.EnumSweep(400_000, 400_000, 8, 16, 36),
                new CrossTable.EnumSweep(0, 100_000, 32, 16, 36)));
        assertEquals(800_000, h.nextEnumFrom(new CrossTable.EnumSweep(0, 0, 8, 16, 36)));
        assertEquals(100_000, h.nextEnumFrom(new CrossTable.EnumSweep(0, 0, 32, 16, 36)));
        assertEquals(0, h.nextEnumFrom(new CrossTable.EnumSweep(0, 0, 8, 13, 40)),
                "a different y band is different ground and starts fresh");
    }

    /**
     * Version 2 files must still load. Version 3 only appended the sweeps; nothing that decides
     * what a key means changed, so refusing them would throw away real work — including the
     * 1.8M-chain table this project has already accumulated.
     */
    @Test
    void aVersionTwoTableStillLoads(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("v2.bin");
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                Files.newOutputStream(file))) {
            out.writeInt(0x58434846);       // "XCHF"
            out.writeInt(2);
            out.writeBoolean(false);
            out.writeInt(10);               // storedMin
            out.writeInt(10);               // count
            out.writeInt(5);                // featureIndex
            out.writeInt(1);                // one range
            out.writeLong(64);
            out.writeLong(2048);
            out.writeInt(2);                // two entries, and NO sweep block
            out.writeInt(11);
            out.writeLong(22L);
            out.writeInt(33);
            out.writeLong(44L);
        }
        CrossTable.Loaded back = CrossTable.load(file, null);
        assertArrayEquals(new int[] {11, 33}, back.keys());
        assertArrayEquals(new long[] {22L, 44L}, back.seeds());
        assertTrue(back.header().enumSweeps().isEmpty());
        assertEquals(2048, back.header().covered());
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
