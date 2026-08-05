package dev.drakou111.sugarcane.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Random;

/**
 * Stack a column, the way the game does, and see how tall you get before it stops.
 *
 * <p>It is the real mechanic rather than a slot machine with cane on it. Every spin that
 * lands a cane adds one {@code ColumnPlacer} draw — {@code 2 + nextInt(nextInt(3) + 1)},
 * so 2, 3 or 4 at 11/18, 5/18 and 2/18, exactly the distribution
 * {@code SugarCaneFeature} uses. A spin with no cane is the terrain refusing to continue,
 * which is what really ends a run. Cash out before that and the height is banked.
 *
 * <p>Continuation sits at about one spin in two, so the height distribution has the same
 * shape as the search's: 4 and 5 constantly, 8 occasionally, 12 rarely, and 20 about once
 * in a few hundred runs. Every height from 2 to 20 is reachable, and the payouts and the
 * captions are the project's own numbers.
 *
 * <p>Textures come from a Minecraft install if there is one — see {@link MinecraftTextures}
 * for why they are not shipped — and are drawn from a palette otherwise.
 */
final class SlotMachine extends JPanel {

    private enum Symbol {
        SUGAR_CANE("sugar_cane", "sugar cane", 13, 0, 0x5EA83C, 0x8CC85A, 0x3F7A28),
        DIRT("dirt", "dirt", 10, 0, 0x866043, 0x9A7053, 0x6F4F36),
        SAND("sand", "sand", 10, 0, 0xDBD3A0, 0xEFE8BC, 0xC4BC88),
        GRAVEL("gravel", "gravel", 10, 0, 0x83807D, 0x9C9995, 0x6A6764),
        // water_still.png ships greyscale -- measured, max channel spread 0 -- because the
        // game multiplies it by the biome's water colour at render time. Untinted it is a
        // grey square. 0x3F76E4 is the default, which is what an ocean uses.
        WATER("water_still", "water", 10, 0x3F76E4, 0x3A5FCD, 0x4E77E0, 0x2C49A8),
        PACKED_ICE("packed_ice", "packed ice", 10, 0, 0x8FB6E8, 0xB3D2F5, 0x6E93C8);

        final String texture;
        final String label;
        final int weight;
        /** Biome tint to multiply the texture by, or 0 to leave it as it ships. */
        final int tint;
        final int base;
        final int light;
        final int dark;

        Symbol(String texture, String label, int weight, int tint, int base, int light,
                int dark) {
            this.texture = texture;
            this.label = label;
            this.weight = weight;
            this.tint = tint;
            this.base = base;
            this.light = light;
            this.dark = dark;
        }
    }

    private static final Symbol[] SYMBOLS = Symbol.values();
    private static final int TOTAL_WEIGHT;

    static {
        int w = 0;
        for (Symbol s : SYMBOLS) {
            w += s.weight;
        }
        TOTAL_WEIGHT = w;
    }

    private static final int CELL = 84;
    private static final Color BG = new Color(0x1E1F22);
    private static final Color EDGE = new Color(0x4A4D52);
    private static final Color TEXT = new Color(0xD8D8D8);
    private static final Color MUTED = new Color(0x9AA0A6);
    private static final Color GOLD = new Color(0xE8C15A);

    private final Image[] sprites = new Image[SYMBOLS.length];
    private final BufferedImage caneRaw;
    private final Symbol[] shown = {Symbol.DIRT, Symbol.SAND, Symbol.WATER};
    private final long[] stopAt = new long[3];
    private final Random random = new Random();

    private final JLabel caption = new JLabel("spin to place the first column",
            SwingConstants.CENTER);
    private final JLabel status = new JLabel("", SwingConstants.CENTER);
    private final JLabel origin = new JLabel("", SwingConstants.CENTER);
    private final JButton spin = new JButton("SPIN");
    private final JButton cash = new JButton("cash out");
    private final Board board = new Board();
    private final Timer timer;

    private int credits = 50;
    private int height;
    private int columns;
    private int best;
    private boolean spinning;
    private boolean running;

    SlotMachine() {
        setLayout(new BorderLayout(0, 8));
        setOpaque(false);
        for (int i = 0; i < SYMBOLS.length; i++) {
            sprites[i] = scaled(image(SYMBOLS[i]), CELL - 16);
        }
        Image cane = image(Symbol.SUGAR_CANE);
        caneRaw = cane instanceof BufferedImage b ? b : drawn(Symbol.SUGAR_CANE);

        JLabel title = new JLabel("SUGAR CANE — stack it", SwingConstants.CENTER);
        title.setFont(new Font(Font.MONOSPACED, Font.BOLD, 17));
        title.setForeground(TEXT);

        caption.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        caption.setForeground(TEXT);
        status.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        status.setForeground(MUTED);
        origin.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        origin.setForeground(MUTED);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.setOpaque(false);
        buttons.add(spin);
        buttons.add(cash);
        JButton refill = new JButton("+25 credits");
        refill.addActionListener(e -> {
            credits += 25;
            status();
        });
        buttons.add(refill);

        JPanel south = new JPanel(new BorderLayout(0, 5));
        south.setOpaque(false);
        south.add(caption, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.CENTER);
        JPanel foot = new JPanel(new GridLayout(2, 1));
        foot.setOpaque(false);
        foot.add(status);
        foot.add(origin);
        south.add(foot, BorderLayout.SOUTH);

        add(title, BorderLayout.NORTH);
        add(board, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        spin.addActionListener(e -> pull());
        cash.addActionListener(e -> cashOut());
        cash.setEnabled(false);
        timer = new Timer(45, e -> tick());
        status();
    }

    // ------------------------------------------------------------------ play

    private void pull() {
        if (spinning) {
            return;
        }
        if (!running && credits <= 0) {
            caption.setText("out of credits.");
            return;
        }
        if (!running) {
            credits--;
            height = 0;
            columns = 0;
        }
        spinning = true;
        spin.setEnabled(false);
        cash.setEnabled(false);
        caption.setText("...");
        long now = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            stopAt[i] = now + 550 + i * 380L;
        }
        status();
        timer.start();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        boolean moving = false;
        for (int i = 0; i < 3; i++) {
            if (now < stopAt[i]) {
                shown[i] = roll();
                moving = true;
            }
        }
        board.repaint();
        if (!moving) {
            timer.stop();
            spinning = false;
            resolve();
        }
    }

    /** A spin lands a column if any reel shows cane; otherwise the run is over. */
    private void resolve() {
        boolean cane = false;
        for (Symbol s : shown) {
            if (s == Symbol.SUGAR_CANE) {
                cane = true;
            }
        }
        if (cane) {
            // ColumnPlacer.place: 2 + nextInt(nextInt(3) + 1). 2, 3, 4 at 11/18, 5/18, 2/18.
            int drawn = 2 + random.nextInt(random.nextInt(3) + 1);
            height += drawn;
            columns++;
            running = true;
            spin.setEnabled(true);
            cash.setEnabled(true);
            caption.setText("column " + columns + " is " + drawn + " tall — "
                    + height + " so far. press again, or cash out.");
        } else {
            int banked = height;
            running = false;
            spin.setEnabled(true);
            cash.setEnabled(false);
            if (banked == 0) {
                caption.setText("no cane, no column. that is most chunks.");
            } else {
                caption.setText("the terrain refused — " + banked + " tall, nothing paid. "
                        + "cash out next time.");
            }
            height = 0;
            columns = 0;
            board.repaint();
        }
        status();
    }

    private void cashOut() {
        if (!running || height == 0) {
            return;
        }
        int payout = payout(height);
        credits += payout;
        best = Math.max(best, height);
        caption.setText(height + " tall — " + flavour(height)
                + (payout > 0 ? "   +" + payout : "   pays nothing"));
        running = false;
        height = 0;
        columns = 0;
        cash.setEnabled(false);
        board.repaint();
        status();
    }

    private Symbol roll() {
        int r = random.nextInt(TOTAL_WEIGHT);
        for (Symbol s : SYMBOLS) {
            r -= s.weight;
            if (r < 0) {
                return s;
            }
        }
        return Symbol.DIRT;
    }

    private void status() {
        status.setText("credits " + credits + "    tallest banked "
                + (best == 0 ? "-" : best + "")
                + "    |    a spin costs 1, cash out to keep the height");
    }

    /** Steep, because the real rates are: 4 is free, 8 is rare, 12 is a story. */
    private static int payout(int h) {
        return switch (h) {
            case 0, 1, 2, 3 -> 0;
            case 4 -> 1;
            case 5 -> 4;
            case 6 -> 9;
            case 7 -> 20;
            case 8 -> 50;
            case 9 -> 120;
            case 10 -> 280;
            case 11 -> 650;
            case 12 -> 1500;
            default -> h >= 20 ? 50000 : 1500 * (1 << Math.min(h - 12, 10));
        };
    }

    private static String flavour(int h) {
        if (h >= 20) {
            return "TWENTY. nothing that tall is known to exist";
        }
        return switch (h) {
            case 2, 3 -> "growth stops here";
            case 4 -> "the natural ceiling — worldgen does this every day";
            case 5 -> "the one that was actually confirmed, at 91,16,65";
            case 6 -> "past everything the game does on purpose";
            case 7 -> "about 4 hours of reverse search";
            case 8 -> "someone else found this one first";
            case 9, 10 -> "days of searching";
            case 11 -> "reported three times, never verified in game";
            case 12 -> "the simulator says 12, the game said 8";
            case 13, 14, 15 -> "beyond anything reported";
            default -> "past the point the maths says should exist";
        };
    }

    // ------------------------------------------------------------------ art

    private static Image image(Symbol s) {
        BufferedImage real = MinecraftTextures.block(s.texture);
        if (real == null) {
            return drawn(s);
        }
        return s.tint == 0 ? real : tinted(real, s.tint);
    }

    /** Multiply by a biome colour, which is what the game does to a greyscale texture. */
    private static BufferedImage tinted(BufferedImage src, int tint) {
        int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                int a = (p >>> 24) & 255;
                int r = ((p >> 16) & 255) * tr / 255;
                int g = ((p >> 8) & 255) * tg / 255;
                int b = (p & 255) * tb / 255;
                out.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
            }
        }
        return out;
    }

    private static Image scaled(Image src, int size) {
        // Nearest neighbour: 16x16 pixel art must not be smoothed.
        return src.getScaledInstance(size, size, Image.SCALE_REPLICATE);
    }

    /** Fallback when there is no Minecraft install: flat colour plus a seeded speckle. */
    private static BufferedImage drawn(Symbol s) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(s.label.hashCode());
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (s == Symbol.SUGAR_CANE && (x < 5 || x > 10)) {
                    img.setRGB(x, y, 0);
                    continue;
                }
                int roll = r.nextInt(10);
                int c = roll == 0 ? s.light : roll == 1 ? s.dark : s.base;
                img.setRGB(x, y, 0xFF000000 | c);
            }
        }
        return img;
    }

    private static String shorten(Path p) {
        String s = p.toString();
        return s.length() < 58 ? s : "..." + s.substring(s.length() - 55);
    }

    /** Three reels on the left, the stack being built on the right. */
    private final class Board extends JPanel {
        Board() {
            setOpaque(false);
            // Tall enough that a 20-block stack is a stack and not a hint of one.
            setPreferredSize(new Dimension(3 * CELL + 230, 300));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            int reelsWidth = 3 * CELL + 20;
            int x0 = Math.max(10, (getWidth() - reelsWidth - 190) / 2);
            int y0 = 12;
            for (int i = 0; i < 3; i++) {
                int x = x0 + i * (CELL + 10);
                g2.setColor(BG);
                g2.fillRoundRect(x, y0, CELL, CELL, 10, 10);
                g2.setColor(shown[i] == Symbol.SUGAR_CANE ? GOLD : EDGE);
                g2.drawRoundRect(x, y0, CELL, CELL, 10, 10);
                g2.drawImage(sprites[shown[i].ordinal()], x + 8, y0 + 8, null);
            }

            // The stack, one cane block per unit of height, growing upward from the soil.
            // The tile shrinks as the run gets tall so the whole thing always fits: the
            // point of the panel is watching it grow, which a clipped stack does not show.
            int sx = x0 + reelsWidth + 34;
            int floor = getHeight() - 18;
            int room = floor - y0 - 4;
            // Shrink to fit whatever room the split pane leaves, down to 3px, then clamp
            // the count as well: a 20-stack must never spill out of its box, and the
            // divider is the user's to drag wherever they like.
            int tile = height <= 0 ? 20 : Math.max(3, Math.min(20, room / height));
            int boxW = 46;
            g2.setColor(BG);
            g2.fillRoundRect(sx - 8, y0, boxW, floor - y0, 8, 8);
            g2.setColor(EDGE);
            g2.drawRoundRect(sx - 8, y0, boxW, floor - y0, 8, 8);
            Image scaledCane = scaled(caneRaw, tile);
            int fits = Math.min(height, Math.max(1, room / tile));
            for (int i = 0; i < fits; i++) {
                g2.drawImage(scaledCane, sx - 8 + (boxW - tile) / 2, floor - (i + 1) * tile,
                        null);
            }

            int tx = sx + boxW + 4;
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
            g2.setColor(height >= 8 ? GOLD : TEXT);
            g2.drawString(height + " tall", tx, y0 + 28);
            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            g2.setColor(MUTED);
            g2.drawString(columns + (columns == 1 ? " column" : " columns"), tx, y0 + 48);
            if (height > 0) {
                g2.drawString("cash out: " + payout(height), tx, y0 + 64);
                g2.setColor(height >= 8 ? GOLD : MUTED);
                g2.drawString(flavour(height), tx, y0 + 84);
            }
            g2.dispose();
        }
    }
}
