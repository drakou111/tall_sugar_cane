package dev.drakou111.sugarcane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world seed has to survive the round trip to the spreadsheet exactly, and JSON is where it
 * stops doing so on its own: one numeric type, parsed by JavaScript as a double, exact to 2^53
 * against the seed's 64 bits. The corrupted value still looks like a seed, so nothing downstream
 * notices -- the find just stops reproducing.
 */
class SeedReporterTest {

    /** The confirmed 8-tall's world seed, which needs 63 bits. */
    private static final long SEED = -7585781829663227268L;

    @Test
    void theSeedIsQuotedSoItSurvivesADoubleParse() {
        String json = SeedReporter.payload("someone", SEED, -24848077, 21, 18720986, 0,
                -1553005, 1170061, false, 8, 0, 0, 30000000L);
        assertTrue(json.contains("\"seed\":\"" + SEED + "\""),
                "the seed must be a JSON string, got: " + json);

        // What the receiving end would have done with it unquoted. Asserted as a difference
        // rather than a literal: the exact landing point is binary rounding, and pinning it
        // tests the JVM's rounding rather than the thing that matters.
        long throughADouble = (long) (double) SEED;
        assertNotEquals(SEED, throughADouble,
                "if a seed ever survives a double the quoting is no longer load-bearing");
        assertTrue(Math.abs(SEED - throughADouble) > 100,
                "off by " + Math.abs(SEED - throughADouble) + ", which is enough to name a "
                        + "different world");
    }

    @Test
    void everyNumericFieldIsQuoted() {
        String json = SeedReporter.payload("u", SEED, 1, 2, 3, 4, 5, 6, true, 7, 8, 9, 10L);
        for (String field : new String[]{"seed", "x", "base", "z", "biome", "chunkX", "chunkZ",
                "height", "spawnX", "spawnZ", "distance"}) {
            assertTrue(json.contains("\"" + field + "\":\""),
                    field + " is not quoted in " + json);
        }
        // isCrossChunk stays a real boolean: it has no precision to lose and the sheet reads
        // it as a flag.
        assertTrue(json.contains("\"isCrossChunk\":true"), json);
    }

    @Test
    void quotesInAUsernameCannotBreakThePayload() {
        String json = SeedReporter.payload("a\"b\\c", SEED, 0, 0, 0, 0, 0, 0, false, 0, 0, 0,
                0L);
        assertTrue(json.contains("\"username\":\"a\\\"b\\\\c\""), json);
    }
}
