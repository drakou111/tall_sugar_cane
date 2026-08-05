package dev.drakou111.sugarcane.gui;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The slots tab has to survive being built and drawn.
 *
 * <p>It once shipped crashing: a text substitution rewrote {@code reloadSprites} into a
 * call to itself, so opening the tab threw {@code StackOverflowError} before anything
 * appeared. Nothing failed the build, because the only thing exercising the panel was a
 * person clicking on it. Constructing and painting it costs milliseconds and would have
 * caught it, so now the build does that.
 */
class SlotMachineTest {

    @Test
    void buildsAndPaintsWithoutBlowingUp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display toolkit");

        SlotMachine slots = assertDoesNotThrow(() -> new SlotMachine(px -> { }),
                "constructing the slots tab threw");

        Dimension size = slots.getPreferredSize();
        assertNotNull(size);
        slots.setSize(Math.max(size.width, 600), Math.max(size.height, 300));
        slots.doLayout();

        BufferedImage img = new BufferedImage(slots.getWidth(), slots.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        assertDoesNotThrow(() -> slots.paint(g), "painting the slots tab threw");
        g.dispose();
    }

    /** The textures are meant to be in the jar; if they are not, everyone gets fallbacks. */
    @Test
    void theBlockTexturesAreBundled() {
        for (String name : new String[]{"sugar_cane", "dirt", "sand", "gravel",
                "water_still", "packed_ice"}) {
            assertNotNull(getClass().getResourceAsStream("/textures/block/" + name + ".png"),
                    "/textures/block/" + name + ".png is missing from the jar");
        }
    }
}
