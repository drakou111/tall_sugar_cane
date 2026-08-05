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
    /**
     * Block size in the showcase, deliberately fixed. Shrinking the blocks to fit would be
     * the sensible thing; instead the panel grows and the window grows with it, so a tall
     * run is tall on the screen too.
     */
    private static final int TILE = 22;
    /** Everything in the board that is not stack: the reels and their margins. */
    private static final int BOARD_CHROME = 130;
    private static final Color BG = new Color(0x1E1F22);
    private static final Color EDGE = new Color(0x4A4D52);
    private static final Color TEXT = new Color(0xD8D8D8);
    private static final Color MUTED = new Color(0x9AA0A6);
    private static final Color GOLD = new Color(0xE8C15A);

    private final Image[] sprites = new Image[SYMBOLS.length];
    private BufferedImage caneRaw;
    private BufferedImage dirtRaw;
    private BufferedImage waterRaw;
    private final Symbol[] shown = {Symbol.DIRT, Symbol.SAND, Symbol.WATER};
    private final long[] stopAt = new long[3];
    private final Random random = new Random();

    private final JLabel caption = new JLabel("spin to place a column",
            SwingConstants.CENTER);
    private final JLabel status = new JLabel("", SwingConstants.CENTER);
    private final JLabel origin = new JLabel("", SwingConstants.CENTER);
    private final JButton spin = new JButton("SPIN");
    private final JButton cash = new JButton("cash out");
    private final JButton refill = new JButton("+25 credits");
    private JButton pick;
    private final Board board = new Board();
    private final Timer timer;
    /** Told how many pixels of stack there are, so the window can make room. */
    private final java.util.function.IntConsumer grow;

    private final java.util.List<Integer> columnHeights = new java.util.ArrayList<>();
    private int credits = 50;
    private int height;
    private int columns;
    private int best;
    private boolean spinning;
    private boolean running;

    SlotMachine(java.util.function.IntConsumer grow) {
        this.grow = grow;
        setLayout(new BorderLayout(0, 8));
        setOpaque(false);
        // Straight from the jar: no search, no wait, same art for everyone.
        reloadSprites();

        JLabel title = new JLabel("stack a column", SwingConstants.CENTER);
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
        refill.addActionListener(e -> {
            credits += 25;
            status();
        });
        buttons.add(refill);
        // Only offered when the search came up empty, which is the only time it helps.
        pick = new JButton("find Minecraft jar...");
        pick.setVisible(!MinecraftTextures.bundledPresent());
        pick.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("pick a Minecraft client jar");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Minecraft client jar", "jar"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            if (MinecraftTextures.use(chooser.getSelectedFile().toPath())) {
                reloadSprites();
                pick.setVisible(false);
                showTextureSource();
                board.repaint();
            } else {
                origin.setText("that jar has no block textures in it");
            }
        });
        buttons.add(pick);

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

    private void showTextureSource() {
        origin.setText(MinecraftTextures.bundledPresent()
                ? "textures: Minecraft 1.16.1, bundled"
                : "no textures bundled - drawn from a palette");
    }

    /** Rebuilds every sprite from whatever {@link MinecraftTextures} currently answers. */
    private void reloadSprites() {
        for (int i = 0; i < SYMBOLS.length; i++) {
            sprites[i] = scaled(image(SYMBOLS[i]), CELL - 16);
        }
        caneRaw = raw(Symbol.SUGAR_CANE);
        dirtRaw = raw(Symbol.DIRT);
        waterRaw = raw(Symbol.WATER);
    }

    // ------------------------------------------------------------------ play

    private void pull() {
        if (spinning) {
            return;
        }
        if (!running && credits <= 0) {
            caption.setText("out of credits");
            return;
        }
        if (!running) {
            credits--;
            reset();
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

    /** The number of canes is the column: 1 -> 2 tall, 2 -> 3, 3 -> 4. None ends the run. */
    private void resolve() {
        int canes = 0;
        for (Symbol sym : shown) {
            if (sym == Symbol.SUGAR_CANE) {
                canes++;
            }
        }
        if (canes > 0) {
            int drawn = canes + 1;      // 1 -> 2, 2 -> 3, 3 -> 4, the same range a column has
            height += drawn;
            columnHeights.add(drawn);
            columns++;
            running = true;
            resize();
            spin.setEnabled(true);
            cash.setEnabled(true);
            caption.setText("column " + columns + ": " + drawn + " tall. "
                    + height + " so far.");
        } else {
            int banked = height;
            running = false;
            spin.setEnabled(true);
            cash.setEnabled(false);
            if (banked == 0) {
                caption.setText("No cane...");
            } else {
                caption.setText("the terrain refused... " + banked + " tall, nothing paid. "
                        + "cash out next time.");
            }
            reset();
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
                + "   +" + payout);
        running = false;
        reset();
        cash.setEnabled(false);
        board.repaint();
        status();
    }

    private void reset() {
        height = 0;
        columns = 0;
        columnHeights.clear();
        resize();
    }

    /** Ask the board for more room, and the window for more room than that. */
    private void resize() {
        int stack = (height + 1) * TILE;
        board.setPreferredSize(new Dimension(3 * CELL + 330, BOARD_CHROME + stack));
        board.revalidate();
        grow.accept(stack);
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
        status.setText("credits " + credits + "    best " + (best == 0 ? "-" : best + "")
                + "    spin costs 1");
        // Only offered when actually stuck, so it is a way out rather than a shortcut.
        refill.setEnabled(credits <= 0 && !running);
    }

    /** A run pays what it grew: one credit per block. */
    private static int payout(int h) {
        return h;
    }

    private static String flavour(int h) {
        return switch (h) {
            case 0, 1 -> "";
            case 2, 3 -> "growth caps here";
            case 4 -> "worldgen caps here";
            case 5 -> "1 in 1.4e8 chunks";
            case 6 -> "1 in 1e9 chunks";
            case 7 -> "1 in 4.9e9 chunks";
            case 8 -> "1 in 5.3e10 chunks";
            case 9 -> "1 in 4.5e12 chunks";
            case 10 -> "1 in 2.1e13 chunks";
            case 11 -> "1 in 1.6e14 chunks";
            case 12 -> "1 in 1.7e15 chunks";
            case 13 -> "1 in 8.3e16 chunks";
            case 14 -> "1 in 4.4e17 chunks";
            case 15 -> "1 in 4.3e18 chunks";
            case 16 -> "1 in 6.3e19 chunks";
            case 17 -> "1 in 2.6e21 chunks";
            case 18 -> "1 in 1.6e22 chunks";
            case 19 -> "1 in 1.7e23 chunks";
            default -> "1 in 3.7e24 chunks";
        };
    }

    // ------------------------------------------------------------------ art

    private static BufferedImage raw(Symbol s) {
        Image img = image(s);
        return img instanceof BufferedImage b ? b : drawn(s);
    }

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
            setPreferredSize(new Dimension(3 * CELL + 330, BOARD_CHROME + TILE));
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

            // The find as it would actually look: soil at the bottom, a water column
            // beside it -- needWater is checked under every base -- and the cane above.
            // Column boundaries are drawn, so a 9 reads as 4+3+2 rather than as nine
            // identical blocks.
            int sx = x0 + reelsWidth + 34;
            int floor = getHeight() - 16;
            int tile = TILE;                               // fixed on purpose; see TILE
            int fits = height;

            int caneX = sx + tile;
            int waterX = sx;
            g2.setColor(BG);
            int boxTop = Math.max(y0, floor - (height + 2) * tile);
            g2.fillRoundRect(sx - 6, boxTop, tile * 2 + 12, floor - boxTop + 6, 8, 8);
            g2.setColor(EDGE);
            g2.drawRoundRect(sx - 6, boxTop, tile * 2 + 12, floor - boxTop + 6, 8, 8);

            Image soil = scaled(dirtRaw, tile);
            Image water = scaled(waterRaw, tile);
            Image cane = scaled(caneRaw, tile);

            g2.drawImage(soil, caneX, floor - tile, null);
            // Water runs from the soil up past the top of the stack, which is what a
            // chain needs at every junction.
            for (int i = 0; i <= fits; i++) {
                g2.drawImage(water, waterX, floor - (i + 1) * tile, null);
            }
            for (int i = 0; i < fits; i++) {
                g2.drawImage(cane, caneX, floor - (i + 2) * tile, null);
            }

            // A rule between columns.
            g2.setColor(GOLD);
            int upto = 0;
            for (int i = 0; i < columnHeights.size() - 1; i++) {
                upto += columnHeights.get(i);
                if (upto <= fits) {
                    int y = floor - (upto + 1) * tile;
                    g2.drawLine(caneX, y, caneX + tile, y);
                }
            }

            int tx = sx + tile * 2 + 16;
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
            g2.setColor(height >= 8 ? GOLD : TEXT);
            g2.drawString(height + " tall", tx, y0 + 26);
            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            g2.setColor(MUTED);
            if (height > 0) {
                StringBuilder mix = new StringBuilder();
                for (int i = 0; i < columnHeights.size(); i++) {
                    mix.append(i == 0 ? "" : "+").append(columnHeights.get(i));
                }
                g2.drawString(mix + "  (" + columns
                        + (columns == 1 ? " column)" : " columns)"), tx, y0 + 44);
                g2.setColor(height >= 8 ? GOLD : MUTED);
                g2.drawString(flavour(height), tx, y0 + 60);
            } else {
                g2.drawString("no column yet", tx, y0 + 44);
            }
            g2.dispose();
        }
    }
}
