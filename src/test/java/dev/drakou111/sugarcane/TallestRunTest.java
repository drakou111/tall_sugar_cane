package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cross-chunk test compares a run's height against what one chunk built, and those two
 * numbers are only comparable at the same block.
 *
 * <p>They were not. {@code grown} came from the tallest run anywhere in the column while
 * {@code alone} was read at the <em>predicted</em> base, so a column whose cane grew anywhere
 * else returned 0 for {@code alone} — which beats any height and flags the candidate. Three
 * cross-chunk stacks were reported that way and none of them were real (FINDINGS 6bu).
 */
class TallestRunTest {

    private static long tallestRun(ArrayWorld world, int x, int z) throws Exception {
        Method m = CrossFind.class.getDeclaredMethod("tallestRun", ArrayWorld.class,
                int.class, int.class);
        m.setAccessible(true);
        return (long) m.invoke(null, world, x, z);
    }

    @Test
    void theTallestRunReportsWhereItStands() throws Exception {
        ArrayWorld world = new ArrayWorld(0, 0, 16, 16);
        // A three-tall run at y=40, nowhere near where a prediction might have pointed.
        for (int y = 40; y < 43; y++) {
            world.setBlock(4, y, 5, Blocks.SUGAR_CANE);
        }
        long run = tallestRun(world, 4, 5);
        assertEquals(3, (int) run, "height");
        assertEquals(40, (int) (run >> 32), "base — the number the old code never had");
    }

    @Test
    void theTallerOfTwoRunsWins() throws Exception {
        ArrayWorld world = new ArrayWorld(0, 0, 16, 16);
        for (int y = 20; y < 22; y++) {
            world.setBlock(1, y, 1, Blocks.SUGAR_CANE);      // 2 tall
        }
        for (int y = 60; y < 65; y++) {
            world.setBlock(1, y, 1, Blocks.SUGAR_CANE);      // 5 tall
        }
        long run = tallestRun(world, 1, 1);
        assertEquals(5, (int) run);
        assertEquals(60, (int) (run >> 32));
    }

    @Test
    void anEmptyColumnHasNoRun() throws Exception {
        ArrayWorld world = new ArrayWorld(0, 0, 16, 16);
        assertEquals(0, (int) tallestRun(world, 2, 2));
    }
}
