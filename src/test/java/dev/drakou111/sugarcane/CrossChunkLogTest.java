package dev.drakou111.sugarcane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-chunk log fires about twice in a nine-hour night (FINDINGS 6bs), which is far too
 * rare to find out it is broken by running the search. It was already lost once: those two
 * stacks were counted and discarded because {@code --out} only recorded CONFIRMED finds, so the
 * only genuine two-chunk stacks the project has ever produced left no seed and no coordinate.
 *
 * <p>So the writer is exercised directly rather than waited for.
 */
class CrossChunkLogTest {

    @Test
    void aCrossChunkStackIsWrittenWithEnoughToFindItAgain(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("h17.out");
        Class<?> cf = CrossFind.class;
        Field file = cf.getDeclaredField("FINDS_FILE");
        file.setAccessible(true);
        file.set(null, out);

        Class<?> cand = Class.forName("dev.drakou111.sugarcane.CrossFind$Candidate");
        Constructor<?> ctor = cand.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        // ws, cxa, cza, cxb, czb, px, pz, baseY, joinY, runA, runB, predicted, chainA, chainB
        Object c = ctor.newInstance(1500050556L, 5, 4, 6, 4, 91, 65, 16, 24, 8, 9, 17, 0L, 0L);

        Method record = cf.getDeclaredMethod("recordCrossChunk", cand, int.class, int.class);
        record.setAccessible(true);
        record.invoke(null, c, 12, 8);
        record.invoke(null, c, 9, 4);

        String text = Files.readString(out);
        assertEquals(2, text.lines().count(), "one line per stack, appended not overwritten");
        String first = text.lines().findFirst().orElseThrow();
        // Everything needed to go and look at it, which is the whole point of the file.
        for (String must : new String[] {"height=12", "aloneWouldBe=8", "seed=1500050556",
                "x=91", "y=16", "z=65", "chunkA=5,4", "chunkB=6,4", "predicted=17"}) {
            assertTrue(first.contains(must), "missing " + must + " in: " + first);
        }
        assertTrue(text.lines().skip(1).findFirst().orElseThrow().contains("height=9"),
                "the second stack must be appended, not lost to the first");

        file.set(null, null);
    }
}
