package dev.drakou111.sugarcane.gen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Heights above 16 need a fifth column, and a fifth column needs two things that were not
 * there: a fifth shift level, and somewhere in the packed chain to put its y.
 *
 * <p>Neither failed loudly. Four shift levels cap a chain at four columns, because shifts
 * must strictly increase, so asking for 17 accepted nothing at all and a target build would
 * have run forever finding none. And the fifth y landed on bits 41..44, which is where the
 * shift fields were -- a five-column chain would have overwritten its own shifts.
 *
 * <p>Five-column chains are far too rare to reach by sampling, so the layout is checked
 * directly rather than by generating one and hoping.
 */
class TallChainTest {

    @Test
    void aHeightGetsTheShiftLevelsItsColumnsNeed() {
        assertEquals(4, ChainPrefilter.shiftLevelsFor(5));
        assertEquals(4, ChainPrefilter.shiftLevelsFor(12));
        assertEquals(4, ChainPrefilter.shiftLevelsFor(16), "four columns reach 16");
        assertEquals(5, ChainPrefilter.shiftLevelsFor(17), "17 needs a fifth column");
        assertEquals(5, ChainPrefilter.shiftLevelsFor(20));
    }

    /**
     * The whole point of the change. With four levels a five-column chain cannot exist, so
     * anything above 16 is unreachable however long the search runs.
     */
    @Test
    void fourLevelsCannotReachSeventeen() {
        ChainPrefilter four = new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT, 11, 64,
                3, 8, 3, 4);
        int tallest = 0;
        java.util.Random r = new java.util.Random(1234);
        for (int i = 0; i < 200_000; i++) {
            tallest = Math.max(tallest, four.tallestPossible(r.nextLong() & ((1L << 48) - 1), 5));
        }
        assertTrue(tallest <= 16,
                "four shift levels cap a chain at four columns and so at height 16, saw " + tallest);
    }

    /** Five columns of seven-bit y, and the shift fields have to survive them. */
    @Test
    void theFifthColumnDoesNotLandOnTheShiftFields() {
        int[] ys = {13, 17, 21, 25, 29};
        long packed = ChainPrefilter.pack(7, -3, 5, ys, 0, 4);

        assertEquals(7, ChainPrefilter.chainX(packed));
        assertEquals(-3, ChainPrefilter.chainZ(packed));
        assertEquals(5, ChainPrefilter.chainColumns(packed));
        for (int i = 0; i < ys.length; i++) {
            assertEquals(ys[i], ChainPrefilter.chainBaseY(packed, i), "y of column " + i);
        }
        assertEquals(0, ChainPrefilter.chainBaseShift(packed));
        assertEquals(4, ChainPrefilter.chainMaxShift(packed),
                "a fifth column used to overwrite this");
    }

    /**
     * The extremes, since a y of 64 is seven bits of ones and would bleed into whatever
     * sits above it if the stride were wrong.
     */
    @Test
    void theLayoutSurvivesItsWidestValues() {
        int[] ys = {64, 64, 64, 64, 64};
        long packed = ChainPrefilter.pack(19, 19, 5, ys, 4, 4);
        for (int i = 0; i < 5; i++) {
            assertEquals(64, ChainPrefilter.chainBaseY(packed, i), "y of column " + i);
        }
        assertEquals(19, ChainPrefilter.chainX(packed));
        assertEquals(19, ChainPrefilter.chainZ(packed));
        assertEquals(5, ChainPrefilter.chainColumns(packed));
        assertEquals(4, ChainPrefilter.chainBaseShift(packed));
        assertEquals(4, ChainPrefilter.chainMaxShift(packed));

        // Independence: moving one field must not disturb another.
        long baseOnly = ChainPrefilter.pack(0, 0, 0, new int[8], 5, 0);
        assertEquals(5, ChainPrefilter.chainBaseShift(baseOnly));
        assertEquals(0, ChainPrefilter.chainMaxShift(baseOnly));
        long maxOnly = ChainPrefilter.pack(0, 0, 0, new int[8], 0, 5);
        assertEquals(0, ChainPrefilter.chainBaseShift(maxOnly));
        assertEquals(5, ChainPrefilter.chainMaxShift(maxOnly));
    }

    /** Asking for more levels than exist should say so rather than read past the array. */
    @Test
    void anImpossibleLevelCountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT, 11, 64, 0, 4, 0, 6));
        assertThrows(IllegalArgumentException.class,
                () -> new ChainPrefilter(SugarCaneFeature.COUNT_DEFAULT, 11, 64, 0, 4, 0, 0));
    }
}
