package dev.drakou111.sugarcane.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * A slot machine, because a search that takes nine days needs something to do meanwhile.
 *
 * <p>The reels hold the blocks this project actually cares about, and the payouts are the
 * search's own vocabulary: three canes is a twelve, one cane on its own is
 * {@code cross-chunk, not verifiable}.
 *
 * <p>The sprites are drawn here rather than taken from the game. They are meant to read as
 * Minecraft blocks and the resemblance is the point, but this repository is public and
 * shipping Mojang's texture files inside the jar would be redistributing their assets.
 * Sixteen-by-sixteen, a fixed palette per block and a seeded noise pass gets close enough
 * for a joke.
 */
final class SlotMachine extends JPanel {

    private enum Symbol {
        SUGAR_CANE("sugar cane", 0x5EA83C, 0x8CC85A, 0x3F7A28),
        DIRT("dirt", 0x866043, 0x9A7053, 0x6F4F36),
        SAND("sand", 0xDBD3A0, 0xEFE8BC, 0xC4BC88),
        GRAVEL("gravel", 0x83807D, 0x9C9995, 0x6A6764),
        WATER("water", 0x3A5FCD, 0x4E77E0, 0x2C49A8),
        PACKED_ICE("packed ice", 0x8FB6E8, 0xB3D2F5, 0x6E93C8);

        final String name;
        final int base;
        final int light;
        final int dark;

        Symbol(String name, int base, int light, int dark) {
            this.name = name;
            this.base = base;
            this.light = light;
            this.dark = dark;
        }
    }

    private static final Symbol[] REELS = Symbol.values();
    private static final int CELL = 96;

    private final Image[] sprites = new Image[REELS.length];
    private final Symbol[] shown = {Symbol.DIRT, Symbol.SAND, Symbol.WATER};
    private final long[] stopAt = new long[3];
    private final Random random = new Random();

    private final JLabel result = new JLabel("pull the lever", SwingConstants.CENTER);
    private final JLabel purse = new JLabel("", SwingConstants.CENTER);
    private final JButton spin = new JButton("SPIN");
    private final Reels reels = new Reels();
    private final Timer timer;

    private int credits = 20;
    private int best;
    private boolean spinning;

    SlotMachine() {
        setLayout(new BorderLayout(0, 10));
        setOpaque(false);
        for (int i = 0; i < REELS.length; i++) {
            sprites[i] = sprite(REELS[i]).getScaledInstance(CELL - 16, CELL - 16,
                    Image.SCALE_REPLICATE);   // nearest neighbour: pixel art must stay crisp
        }

        JLabel title = new JLabel("SUGAR CANE SLOTS", SwingConstants.CENTER);
        title.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
        title.setForeground(new Color(0xD8D8D8));

        result.setFont(new Font(Font.MONOSPACED, Font.BOLD, 15));
        purse.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        result.setForeground(new Color(0xD8D8D8));
        purse.setForeground(new Color(0x9AA0A6));

        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.setOpaque(false);
        buttons.add(spin);
        JButton refill = new JButton("beg for more");
        refill.addActionListener(e -> {
            credits += 20;
            purse();
            result.setText("fine. 20 more.");
        });
        buttons.add(refill);
        south.add(result, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.CENTER);
        south.add(purse, BorderLayout.SOUTH);

        add(title, BorderLayout.NORTH);
        add(reels, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        spin.addActionListener(e -> pull());
        timer = new Timer(45, e -> tick());
        purse();
    }

    private void purse() {
        purse.setText("credits " + credits + "   best " + (best == 0 ? "-" : best + " tall")
                + "   |   3 cane = 12 tall, 3 alike = 8, 2 cane = 5");
    }

    private void pull() {
        if (spinning) {
            return;
        }
        if (credits <= 0) {
            result.setText("out of credits. the search is free, though.");
            return;
        }
        credits--;
        spinning = true;
        spin.setEnabled(false);
        result.setText("...");
        long now = System.currentTimeMillis();
        // Reels land left to right, each a little after the last.
        for (int i = 0; i < 3; i++) {
            stopAt[i] = now + 700 + i * 450L;
        }
        purse();
        timer.start();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            if (now < stopAt[i]) {
                shown[i] = REELS[random.nextInt(REELS.length)];
                any = true;
            }
        }
        reels.repaint();
        if (!any) {
            timer.stop();
            spinning = false;
            spin.setEnabled(true);
            score();
        }
    }

    private void score() {
        int canes = 0;
        for (Symbol s : shown) {
            if (s == Symbol.SUGAR_CANE) {
                canes++;
            }
        }
        boolean allAlike = shown[0] == shown[1] && shown[1] == shown[2];

        int height;
        String text;
        int payout;
        if (canes == 3) {
            height = 12;
            payout = 60;
            text = "TWELVE TALL — now go and verify it in game";
        } else if (allAlike) {
            height = 8;
            payout = 18;
            text = "eight tall — " + shown[0].name + " all the way down";
        } else if (canes == 2) {
            height = 5;
            payout = 6;
            text = "five tall — the one that was actually confirmed";
        } else if (canes == 1) {
            height = 0;
            payout = 1;
            text = "cross-chunk, not verifiable";
        } else {
            height = 0;
            payout = 0;
            text = "no usable spot. 1.1e-3 per chunk, you know.";
        }
        credits += payout;
        best = Math.max(best, height);
        result.setText(text + (payout > 0 ? "   +" + payout : ""));
        purse();
    }

    /** One 16x16 block face: flat colour, then a seeded speckle so it reads as a texture. */
    private static BufferedImage sprite(Symbol s) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(s.name.hashCode());
        if (s == Symbol.SUGAR_CANE) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    boolean stalk = x >= 5 && x <= 10;
                    if (!stalk) {
                        img.setRGB(x, y, 0);
                        continue;
                    }
                    int c = x == 5 || x == 10 ? s.dark : (x == 6 ? s.light : s.base);
                    if (r.nextInt(6) == 0) {
                        c = s.dark;
                    }
                    img.setRGB(x, y, 0xFF000000 | c);
                }
            }
            return img;
        }
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int roll = r.nextInt(10);
                int c = roll == 0 ? s.light : roll == 1 ? s.dark : s.base;
                img.setRGB(x, y, 0xFF000000 | c);
            }
        }
        return img;
    }

    /** The three windows, drawn as recessed slots so it looks like a cabinet. */
    private final class Reels extends JPanel {
        Reels() {
            setOpaque(false);
            setPreferredSize(new Dimension(3 * CELL + 40, CELL + 24));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            int total = 3 * CELL + 20;
            int x0 = (getWidth() - total) / 2;
            int y0 = (getHeight() - CELL) / 2;
            for (int i = 0; i < 3; i++) {
                int x = x0 + i * (CELL + 10);
                g2.setColor(new Color(0x1E1F22));
                g2.fillRoundRect(x, y0, CELL, CELL, 10, 10);
                g2.setColor(new Color(0x4A4D52));
                g2.drawRoundRect(x, y0, CELL, CELL, 10, 10);
                Image sprite = sprites[shown[i].ordinal()];
                g2.drawImage(sprite, x + 8, y0 + 8, null);
            }
            g2.dispose();
        }
    }
}
