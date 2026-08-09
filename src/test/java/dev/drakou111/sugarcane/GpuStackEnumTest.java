package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.GpuStackEnum;
import dev.drakou111.sugarcane.gen.StackEnumerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Holds the enumeration kernel to {@link StackEnumerator}, hit for hit.
 *
 * <p>The kernel's neighbour walk is a proposer, not an oracle: it guesses which invocations
 * stacked and can guess wrong. What it may never do is emit a chain that is not there, because
 * every candidate goes through the kernel's own {@code runAt} before it leaves. So the test is
 * that the CPU {@code runAt} returns <em>exactly</em> the height reported, not merely something
 * tall — a kernel whose LCG had drifted would still report plausible numbers.
 *
 * <p>Skips without a CUDA device, like {@code GpuLiftTest}.
 */
class GpuStackEnumTest {

    @Test
    void everyHitReproducesOnTheCpu() throws Exception {
        GpuStackEnum gpu = GpuStackEnum.detect();
        assumeTrue(gpu != null, "no CUDA device: " + GpuStackEnum.lastFailure());

        // Height 8 so a short sweep still returns thousands: the point is breadth of agreement,
        // and a target that yields three hits would pass while badly broken.
        int target = 8;
        List<GpuStackEnum.Hit> hits = gpu.sweep(0, 250_000, 16, 36, target, false, 8);
        assertTrue(hits.size() > 1000, "expected a few thousand hits, got " + hits.size());

        for (GpuStackEnum.Hit h : hits) {
            assertEquals(h.height(),
                    StackEnumerator.runAt(h.decorationSeed(), h.x(), h.y(), h.z()),
                    "kernel and CPU disagree at " + h);
            assertTrue(h.height() >= target, "below the target it was asked for: " + h);
        }
    }

    @Test
    void theLowBitKnobBuysCoverageInProportion() throws Exception {
        GpuStackEnum gpu = GpuStackEnum.detect();
        assumeTrue(gpu != null, "no CUDA device: " + GpuStackEnum.lastFailure());

        // lows is the only coverage knob there is, so it has to actually widen the search rather
        // than re-walk the same states. Four times the low-bit samples, about four times the
        // chains; the bound is loose because this is a sample, not a count.
        int few = gpu.sweep(0, 200_000, 16, 36, 8, false, 8).size();
        int many = gpu.sweep(0, 200_000, 16, 36, 8, false, 32).size();
        assertTrue(many > few * 3.2 && many < few * 4.6,
                "lows=32 should find roughly 4x lows=8, got " + few + " then " + many);
    }
}
