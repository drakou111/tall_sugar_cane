package dev.drakou111.sugarcane.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.UIResource;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A window over the same commands {@code Cli} exposes: one tab each, a shared console,
 * and a stop button.
 *
 * <p><b>Every run is a fresh process, and that is not just for the stop button.</b>
 * {@code SisterScan} and {@code Inspect} set {@code RegionSearcher.relaxFilters},
 * {@code allBiomes} and {@code centreOverrideX/Z}, which are static and which nothing
 * resets. Running {@code sisters} and then {@code search} inside one JVM would give a
 * search with the filters relaxed and its box centred somewhere else — wrong, and quietly
 * so. A subprocess per run makes that unreachable, lets the console show exactly the
 * command line to reproduce it in a terminal, and makes stopping a search actually
 * possible: the workers loop until their seed count runs out and have no cancel.
 *
 * <p>Reporting to the shared spreadsheet is off unless the box is ticked, and the flag is
 * always passed explicitly — the CLI otherwise asks on stdin, and a subprocess has none.
 */
public final class SugarcaneGui {

    private static final int MAX_LINES = 20_000;

    private final JTextArea console = new JTextArea();
    private final JButton run = new JButton("Run");
    private final JButton stop = new JButton("Stop");
    private final JCheckBox report = new JCheckBox("report finds as");
    private final JTextField user = new JTextField(14);
    private final JTabbedPane tabs = new JTabbedPane();
    private final List<Supplier<List<String>>> argsPerTab = new ArrayList<>();
    private volatile Process running;

    // A dark palette, applied through Nimbus. The system look and feel on Windows draws
    // with native theming and ignores most colour keys, so asking it to go dark leaves
    // white text on white panels; Nimbus honours them.
    private static final Color BG = new Color(0x2B2D30);
    private static final Color PANEL = new Color(0x33363B);
    private static final Color FIELD = new Color(0x1E1F22);
    private static final Color TEXT = new Color(0xD8D8D8);
    private static final Color MUTED = new Color(0x9AA0A6);
    private static final Color ACCENT = new Color(0x4A6E9C);

    public static void main(String[] args) {
        dark();
        SwingUtilities.invokeLater(() -> new SugarcaneGui().show());
    }

    private static void dark() {
        dark(true);
    }

    /**
     * Install a dark palette, whatever look and feel this JVM actually has.
     *
     * <p>Ordering is the whole trick, and getting it wrong is what produced a white window
     * on a machine without Nimbus. Nimbus reads its {@code nimbus*} keys <em>as it
     * initialises</em>, so those must be set before {@code setLookAndFeel}. But
     * {@code setLookAndFeel} then installs that look and feel's own defaults and wipes
     * anything else put before it — so the generic keys have to be set afterwards. Both
     * sides, or one of the two look and feels comes out stock.
     *
     * @param preferNimbus false forces the cross-platform look and feel, which is how the
     *                     no-Nimbus path gets tested on a machine that has it
     */
    static void dark(boolean preferNimbus) {
        UIManager.put("control", PANEL);
        UIManager.put("info", PANEL);
        UIManager.put("nimbusBase", new Color(0x23252A));
        UIManager.put("nimbusBlueGrey", PANEL);
        UIManager.put("nimbusLightBackground", FIELD);
        UIManager.put("nimbusSelectionBackground", ACCENT);
        UIManager.put("nimbusSelectedText", Color.WHITE);
        UIManager.put("nimbusFocus", ACCENT);
        UIManager.put("nimbusBorder", new Color(0x4A4D52));
        UIManager.put("nimbusDisabledText", new Color(0x6B6F76));
        UIManager.put("text", TEXT);
        UIManager.put("menuText", TEXT);
        UIManager.put("infoText", TEXT);
        UIManager.put("controlText", TEXT);

        try {
            String nimbus = null;
            for (UIManager.LookAndFeelInfo laf : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(laf.getName())) {
                    nimbus = laf.getClassName();
                    break;
                }
            }
            // Metal takes its bevels from a MetalTheme, not from UIManager defaults, so
            // putting controlHighlight and friends does nothing and every button keeps a
            // white outline. The theme has to be installed, and before the look and feel.
            javax.swing.plaf.metal.MetalLookAndFeel.setCurrentTheme(new DarkMetal());
            // Never fall through to the system look and feel: on Windows it paints
            // natively and ignores colour keys, which is exactly how this ended up white.
            UIManager.setLookAndFeel(preferNimbus && nimbus != null ? nimbus
                    : UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // Cosmetic only: a look and feel that will not load is not worth failing over.
        }

        // After, so they survive whatever setLookAndFeel just installed.
        for (String k : new String[]{"Panel", "TabbedPane", "ScrollPane", "Viewport",
                "SplitPane", "OptionPane", "CheckBox", "Label", "Button", "ComboBox",
                "TextField", "TextArea"}) {
            UIManager.put(k + ".background", k.startsWith("Text") ? FIELD : PANEL);
            UIManager.put(k + ".foreground", TEXT);
        }
        UIManager.put("Button.select", ACCENT);
        UIManager.put("TextField.caretForeground", TEXT);
        // Metal and Basic draw their bevels from these. Left stock they are near-white,
        // which outlines every control in bright lines on a dark window.
        UIManager.put("controlHighlight", new Color(0x4A4D52));
        UIManager.put("controlLtHighlight", new Color(0x53575D));
        UIManager.put("controlShadow", new Color(0x26282C));
        UIManager.put("controlDkShadow", new Color(0x1A1B1E));
        UIManager.put("Separator.foreground", new Color(0x4A4D52));
        UIManager.put("Separator.background", PANEL);
    }

    /** Metal's palette, which it reads from here rather than from UIManager. */
    private static final class DarkMetal extends javax.swing.plaf.metal.DefaultMetalTheme {
        private static javax.swing.plaf.ColorUIResource c(Color col) {
            return new javax.swing.plaf.ColorUIResource(col);
        }

        @Override
        protected javax.swing.plaf.ColorUIResource getPrimary1() {
            return c(new Color(0x23252A));
        }

        @Override
        protected javax.swing.plaf.ColorUIResource getPrimary2() {
            return c(ACCENT);
        }

        @Override
        protected javax.swing.plaf.ColorUIResource getPrimary3() {
            return c(new Color(0x3A5070));
        }

        @Override
        protected javax.swing.plaf.ColorUIResource getSecondary1() {
            return c(new Color(0x17181B));
        }

        @Override
        protected javax.swing.plaf.ColorUIResource getSecondary2() {
            return c(new Color(0x3A3D42));
        }

        @Override
        protected javax.swing.plaf.ColorUIResource getSecondary3() {
            return c(PANEL);
        }

        /** The one that matters: Metal paints its highlights with "white". */
        @Override
        protected javax.swing.plaf.ColorUIResource getWhite() {
            return c(new Color(0x53575D));
        }

        @Override
        protected javax.swing.plaf.ColorUIResource getBlack() {
            return c(new Color(0x101114));
        }

        @Override
        public javax.swing.plaf.ColorUIResource getControlTextColor() {
            return c(TEXT);
        }

        @Override
        public javax.swing.plaf.ColorUIResource getSystemTextColor() {
            return c(TEXT);
        }

        @Override
        public javax.swing.plaf.ColorUIResource getUserTextColor() {
            return c(TEXT);
        }

        @Override
        public javax.swing.plaf.ColorUIResource getInactiveControlTextColor() {
            return c(new Color(0x6B6F76));
        }
    }

    /**
     * Force the palette onto anything the look and feel coloured itself.
     *
     * <p>Swing tags colours it supplied as {@link UIResource}, so this repaints those and
     * leaves alone anything set deliberately — the muted hints, the console, the gold in
     * the slot machine. It is the belt to the UIManager braces: whatever look and feel
     * ends up loading, the window comes out dark.
     */
    private static void forceDark(Component c) {
        if (c.getBackground() instanceof UIResource) {
            c.setBackground(c instanceof JTextComponent ? FIELD : PANEL);
        }
        if (c.getForeground() instanceof UIResource) {
            c.setForeground(TEXT);
        }
        if (c instanceof Container parent) {
            for (Component child : parent.getComponents()) {
                forceDark(child);
            }
        }
    }

    private void show() {
        frame = new JFrame("sugarcane");
        // Not DISPOSE_ON_CLOSE: a child process outlives the JVM that started it on
        // Windows, so closing the window would leave a search running on every core with
        // no window to stop it from. Kill it first, then go.
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                stopRunning();
                frame.dispose();
            }
        });
        // A backstop for every other way this JVM can end -- killed from an IDE, logged
        // out, Stop pressed in a terminal. The hook runs on all of them.
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopRunning, "sugarcane-cleanup"));
        frame.getContentPane().setBackground(BG);

        addTab("search", searchTab());
        addTab("reverse", reverseTab());
        addTab("targets", targetsTab());
        addTab("merge", mergeTab());
        addTab("spot", spotTab());
        addTab("crosschunk", crossChunkTab());
        addTab("sisters", sistersTab());
        addTab("inspect", inspectTab());
        // Not a command, so it contributes no arguments and Run does not apply to it.
        tabs.addTab("slots", new SlotMachine(this::makeRoomForStack));
        argsPerTab.add(null);
        tabs.addChangeListener(e -> {
            boolean runnable = argsPerTab.get(tabs.getSelectedIndex()) != null;
            run.setEnabled(runnable && running == null);
        });

        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        console.setLineWrap(false);
        // Set explicitly rather than left to the theme: a console is the one component
        // people stare at for hours, and Nimbus renders a read-only text area grey.
        console.setBackground(FIELD);
        console.setForeground(TEXT);
        console.setCaretColor(TEXT);
        console.setBorder(new EmptyBorder(6, 8, 6, 8));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        controls.add(run);
        controls.add(stop);
        controls.add(report);
        controls.add(user);
        // The name only matters when reporting, and a live field that does nothing is a
        // question the user has to answer for themselves.
        user.setEnabled(report.isSelected());
        report.addActionListener(e -> user.setEnabled(report.isSelected()));
        String saved = dev.drakou111.sugarcane.Cli.savedUsername();
        if (saved != null && !saved.isBlank()) {
            user.setText(saved);
        }
        user.setToolTipText("saved to config.properties on the first run that uses it, so "
                + "it only has to be typed once");
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> console.setText(""));
        controls.add(clear);
        stop.setEnabled(false);
        run.addActionListener(e -> start());
        stop.addActionListener(e -> {
            Process p = running;
            if (p != null) {
                p.destroy();
                append("\n[stopped]\n");
            }
        });

        JPanel top = new JPanel(new BorderLayout());
        top.add(tabs, BorderLayout.CENTER);
        top.add(controls, BorderLayout.SOUTH);

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, scroll(console));
        split.setResizeWeight(0.0);
        frame.add(split);
        forceDark(frame.getContentPane());
        frame.setSize(940, 720);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        baseHeight = frame.getHeight();
        baseDivider = 300;
        SwingUtilities.invokeLater(() -> split.setDividerLocation(baseDivider));

        append("Each run starts a fresh process; the command line is echoed so it can be "
                + "pasted into a terminal.\nReporting is off unless the box is ticked.\n\n");
    }

    private JFrame frame;
    private JSplitPane split;
    private int baseHeight;
    private int baseDivider;

    /**
     * Give the slots tab the room its stack wants, by growing the window.
     *
     * <p>The showcase could shrink its blocks to fit and never disturb anything. It does
     * not, so a twelve is twelve blocks tall on the screen and the window has to get out
     * of its own way. Capped at the usable screen height, after which it simply stops.
     */
    private void makeRoomForStack(int stackPixels) {
        if (frame == null || split == null || !frame.isShowing()) {
            return;
        }
        // Leave a maximized or iconified window alone. Resizing a maximized frame on
        // Windows leaves it flagged maximized at the new size, which reads as the window
        // collapsing or minimising itself the moment you press SPIN.
        if (frame.getExtendedState() != Frame.NORMAL) {
            split.setDividerLocation(baseDivider + Math.max(0, stackPixels - 40));
            return;
        }
        if (baseHeight <= 0) {
            baseHeight = Math.max(frame.getHeight(), 720);   // never grow down from zero
        }
        int max = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds().height;
        int extra = Math.max(0, stackPixels - 40);
        // Clamped below by the base as well as above by the screen: this may only ever
        // make the window taller than it started, never shorter.
        int want = Math.max(baseHeight, Math.min(baseHeight + extra, Math.max(baseHeight, max)));
        if (frame.getHeight() != want) {
            frame.setSize(frame.getWidth(), want);
        }
        split.setDividerLocation(baseDivider + extra);
    }

    /**
     * A scroll pane that moves at a usable speed.
     *
     * <p>Swing's default unit increment is one pixel for anything that is not a JList or
     * JTable, so a wheel notch crawls. 16 is roughly a line.
     */
    private static JScrollPane scroll(Component view) {
        JScrollPane pane = new JScrollPane(view);
        pane.getVerticalScrollBar().setUnitIncrement(16);
        pane.getVerticalScrollBar().setBlockIncrement(160);
        pane.getHorizontalScrollBar().setUnitIncrement(16);
        pane.setBorder(BorderFactory.createEmptyBorder());
        return pane;
    }

    private void addTab(String name, Tab tab) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(tab.panel, BorderLayout.NORTH);
        wrapper.setBorder(new EmptyBorder(8, 8, 8, 8));
        tabs.addTab(name, scroll(wrapper));
        argsPerTab.add(tab.args);
    }

    // ---------------------------------------------------------------- tabs

    private record Tab(JPanel panel, Supplier<List<String>> args) {
    }

    private Tab searchTab() {
        Form f = new Form("Scan every chunk in a box around each seed's origin. The right "
                + "search up to height 6; from 7 up use reverse.");
        JTextField first = f.text("firstSeed", "1", "first world seed to try");
        JTextField count = f.text("seedCount", "", "blank or 0 runs until you press Stop");
        JTextField radius = f.text("chunkRadius", "6", "how far from the centre a find may "
                + "be, in chunks. Nearly free down to 6.");
        JTextField threads = f.text("threads", defaultThreads(), null);
        JTextField height = f.text("minHeight", "5", "shortest run worth printing");
        JComboBox<String> mode = f.combo("mode", "(none)", "diag", "spots");
        JTextField probe = f.text("probe:N", "", "measure the per-chunk find rate over N "
                + "synthetic decoration seeds; overrides mode");
        JCheckBox spawn = f.check("--spawn", "centre on the world's spawn chunk, not 0,0");
        JTextField update = f.text("--update (minutes)", "", null);
        return new Tab(f.panel, () -> {
            List<String> a = new ArrayList<>(List.of("search", req(first, "firstSeed"),
                    orZero(count), req(radius, "chunkRadius"),
                    req(threads, "threads"), req(height, "minHeight")));
            if (!probe.getText().isBlank()) {
                a.add("probe:" + probe.getText().trim());
            } else if (!"(none)".equals(mode.getSelectedItem())) {
                a.add((String) mode.getSelectedItem());
            }
            if (spawn.isSelected()) {
                a.add("--spawn");
            }
            addIf(a, "--update=", update);
            return a;
        });
    }

    private Tab reverseTab() {
        Form f = new Form("Pick the cane RNG first, then solve for a chunk that has it. "
                + "Finds land anywhere in the world, not near spawn.");
        JTextField height = f.text("minHeight", "8", "height to build targets for");
        JTextField threads = f.text("threads", defaultThreads(), null);
        JTextField targets = f.text("targets", "20000", "size of the target set. A world "
                + "seed reads only one bucket in 16, so 4000+ amortises setup properly.");
        JTextField first = f.text("firstSeed", "1", "counts LOW-48 seeds when sisters > 1");
        JTextField count = f.text("seedCount", "", "blank or 0 runs until you press Stop");
        JTextField file = f.text("--targets=<file>", "", "save and reload the target set; it "
                + "does not depend on the world seed");
        JTextField reportH = f.text("--report=<h>", "", "report runs this tall even when the "
                + "targets were built for more");
        JTextField sisters = f.text("--sisters=<n>", "", "upper-16 values per low-48 seed; "
                + "default 64, measured 4.2x. 1 restores the old loop.");
        JTextField maxShift = f.text("--max-shift=<n>", "", null);
        JTextField maxCols = f.text("--max-columns=<n>", "", null);
        JTextField maxSlack = f.text("--max-slack=<n>", "", "other cane allowed between the "
                + "stack's own columns. Default 0, the contiguous window: 2.2x, and it "
                + "keeps 87.9% of real finds. 99 restores the old rule.");
        JTextField levels = f.text("--shift-levels=<n>", "", "how many RNG shift levels, "
                + "which is the most columns a chain can have. Derived from the height "
                + "(4 up to height 16, 5 above); set it only to experiment.");
        JTextField sampleFrom = f.text("--sample-from=<n>", "", "start decoration sampling "
                + "at this index. Blank means a random start, printed so you can repeat it");
        JTextField update = f.text("--update (minutes)", "", null);
        JCheckBox cpu = f.check("--cpu", "force the CPU chain filter instead of CUDA");
        JCheckBox water = f.check("--water-probe", "require a chain's water to come from a "
                + "LIQUID carver: 1.6x, but it is known to reject the reported 11-tall, so "
                + "leave it off unless you are experimenting");
        JCheckBox allCarvers = f.check("--all-carvers", "put caves back into the carve probe. "
                + "From height 8 up it uses ravines only, because every find at that height "
                + "is ravine-carved; only tick this below height 8 or to compare");
        return new Tab(f.panel, () -> {
            List<String> a = new ArrayList<>(List.of("reverse", req(height, "minHeight"),
                    req(threads, "threads"), req(targets, "targets"),
                    req(first, "firstSeed"), orZero(count)));
            addIf(a, "--targets=", file);
            addIf(a, "--report=", reportH);
            addIf(a, "--sisters=", sisters);
            addIf(a, "--max-shift=", maxShift);
            addIf(a, "--max-columns=", maxCols);
            addIf(a, "--max-slack=", maxSlack);
            addIf(a, "--shift-levels=", levels);
            addIf(a, "--sample-from=", sampleFrom);
            addIf(a, "--update=", update);
            if (cpu.isSelected()) {
                a.add("--cpu");
            }
            if (water.isSelected()) {
                a.add("--water-probe");
            }
            if (allCarvers.isSelected()) {
                a.add("--all-carvers");
            }
            return a;
        });
    }

    private Tab targetsTab() {
        Form f = new Form("Build or extend a reverse-search target set and stop. The set "
                + "never depends on the world seed, so build it once and reuse it.");
        JTextField height = f.text("minHeight", "8", null);
        JTextField count = f.text("count", "20000", null);
        JTextField file = f.text("file", "targets8.bin", "where to save it");
        JTextField threads = f.text("threads", defaultThreads(), null);
        JTextField maxShift = f.text("--max-shift=<n>", "", null);
        JTextField maxCols = f.text("--max-columns=<n>", "", null);
        JTextField maxSlack = f.text("--max-slack=<n>", "", "default 0, the contiguous "
                + "window. A set built under one budget is not interchangeable with a set "
                + "built under another, so this is part of what the file records.");
        JTextField levels = f.text("--shift-levels=<n>", "", "derived from the height; "
                + "4 up to 16, 5 above. Set it only to experiment.");
        JTextField sampleFrom = f.text("--sample-from=<n>", "", "start sampling at this "
                + "index. Blank picks a random one and prints it, so two machines build "
                + "different halves of a set that does not depend on the world seed.");
        JCheckBox cpu = f.check("--cpu", "force the CPU filter; the GPU one is ~4.7x");
        JTextField update = f.text("--update (minutes)", "", null);
        return new Tab(f.panel, () -> {
            List<String> a = new ArrayList<>(List.of("targets", req(height, "minHeight"),
                    req(count, "count"), req(file, "file"), req(threads, "threads")));
            addIf(a, "--max-shift=", maxShift);
            addIf(a, "--max-columns=", maxCols);
            addIf(a, "--max-slack=", maxSlack);
            addIf(a, "--shift-levels=", levels);
            addIf(a, "--sample-from=", sampleFrom);
            if (cpu.isSelected()) {
                a.add("--cpu");
            }
            addIf(a, "--update=", update);
            return a;
        });
    }

    private Tab mergeTab() {
        Form f = new Form("Pool target sets built on different machines. Builds start at a "
                + "random sample index, so two people running the same command cover "
                + "different ground and their files add up. Duplicates are dropped.");
        JTextField out = f.text("output file", "", "where the pooled set goes");
        JTextField ins = f.text("input files", "", "space separated; all must have been "
                + "built with the same height, band and filter settings");
        return new Tab(f.panel, () -> {
            List<String> a = new ArrayList<>(List.of("merge", req(out, "output file")));
            String raw = req(ins, "input files");
            for (String part : raw.split("\\s+")) {
                if (!part.isBlank()) {
                    a.add(part);
                }
            }
            if (a.size() < 3) {
                throw new IllegalArgumentException("give at least one input file");
            }
            return a;
        });
    }

    private Tab spotTab() {
        Form f = new Form("Decoration seeds that grow a stack at one NAMED block, instead of "
                + "anywhere. For when you already have a block you like -- a ravine wall with "
                + "soil under it and water beside it -- and want the seeds that build there. "
                + "No world seed and no lattice: turning a decoration seed into coordinates is "
                + "what the reverse tab does.");
        JTextField relX = f.text("relX", "", "chunk-relative, -4..19: a placement can land "
                + "four blocks outside its own chunk");
        JTextField relZ = f.text("relZ", "", "chunk-relative, -4..19");
        JTextField baseY = f.text("baseY", "", "where the bottom of the stack sits");
        JTextField height = f.text("height", "8", null);
        JTextField seeds = f.text("seeds", "", "blank runs until you press Stop");
        JTextField threads = f.text("threads", defaultThreads(), null);
        JCheckBox cpu = f.check("--cpu", "force the CPU; the GPU path is about 14x faster "
                + "and gives the same seeds");
        return new Tab(f.panel, () -> {
            List<String> a = new ArrayList<>(List.of("spot",
                    req(relX, "relX"), req(relZ, "relZ"), req(baseY, "baseY"),
                    req(height, "height"), orZero(seeds), req(threads, "threads")));
            if (cpu.isSelected()) {
                a.add("--cpu");
            }
            return a;
        });
    }

    private Tab crossChunkTab() {
        Form f = new Form("Can two neighbouring chunks build one stack between them? A chunk "
                + "places cane up to four blocks over its border, so one could stack into the "
                + "next and the next stack on top. This measures whether that beats a single "
                + "chunk -- measured over 20M pairs it is about 1.25x at heights 8 to 11 and "
                + "nothing at all at 12 and above, because joining costs an exact alignment.");
        JTextField seeds = f.text("seeds", "20000000", "chunk pairs to sample");
        JTextField threads = f.text("threads", defaultThreads(), null);
        JTextField target = f.text("targetHeight", "20", "how tall a combined stack you are "
                + "after. The split between the two chunks is chosen for you: the per-chain "
                + "rate cliffs at multiples of 4, so 20 is best as 12+8 (~55x likelier than "
                + "10+10) and 16 as 8+8.");
        return new Tab(f.panel, () -> new ArrayList<>(List.of("crosschunk",
                req(seeds, "seeds"), req(threads, "threads"), req(target, "targetHeight"))));
    }

    private Tab sistersTab() {
        Form f = new Form("Re-roll the terrain under a known chain by sweeping the seed's "
                + "upper 16 bits. Run this on a find the game refused: the chain is "
                + "identical in all 65,536, only the terrain differs.");
        JTextField seed = f.text("seed", "", null);
        JTextField x = f.text("x", "", null);
        JTextField y = f.text("y", "", "the base y of the run");
        JTextField z = f.text("z", "", null);
        JTextField count = f.text("count", "65536", "how many upper-16 values to try");
        JTextField threads = f.text("threads", defaultThreads(), null);
        JTextField height = f.text("minHeight", "5", "height worth reporting");
        return new Tab(f.panel, () -> new ArrayList<>(List.of("sisters", req(seed, "seed"),
                req(x, "x"), req(y, "y"), req(z, "z"), req(count, "count"),
                req(threads, "threads"), req(height, "minHeight"))));
    }

    private Tab inspectTab() {
        Form f = new Form("Regenerate the region around one position and dump what the "
                + "simulator sees: a vertical slice, the cane, the water it depended on, "
                + "and which invocations stacked. Works millions of blocks out.");
        JTextField seed = f.text("seed", "", null);
        JTextField x = f.text("x", "", null);
        JTextField y = f.text("y", "", null);
        JTextField z = f.text("z", "", null);
        JTextField radius = f.text("searchRadius", "6", null);
        return new Tab(f.panel, () -> new ArrayList<>(List.of("inspect", req(seed, "seed"),
                req(x, "x"), req(y, "y"), req(z, "z"), req(radius, "searchRadius"))));
    }

    // ---------------------------------------------------------------- running

    private void start() {
        Supplier<List<String>> supplier = argsPerTab.get(tabs.getSelectedIndex());
        if (supplier == null) {
            return;     // the slots tab; nothing to run
        }
        List<String> args;
        try {
            args = supplier.get();
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(null, e.getMessage(), "missing value",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(finalFieldFlag());
        cmd.add("-cp");
        cmd.add(classpath());
        cmd.add("dev.drakou111.sugarcane.Cli");
        cmd.addAll(args);
        // Always explicit: the CLI asks on stdin otherwise, and a subprocess has none.
        cmd.add(report.isSelected() ? "--yes-report" : "--no-report");

        append("$ " + String.join(" ", args)
                + (report.isSelected() ? " --yes-report" : " --no-report") + "\n");
        run.setEnabled(false);
        stop.setEnabled(true);

        Thread worker = new Thread(() -> {
            int exit = -1;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                running = p;
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        append(line + "\n");
                    }
                }
                exit = p.waitFor();
            } catch (IOException | InterruptedException e) {
                append("[failed to run: " + e + "]\n");
            } finally {
                running = null;
                int code = exit;
                SwingUtilities.invokeLater(() -> {
                    run.setEnabled(argsPerTab.get(tabs.getSelectedIndex()) != null);
                    stop.setEnabled(false);
                    append("[exit " + code + "]\n\n");
                });
            }
        }, "sugarcane-run");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * {@code --enable-final-field-mutation=ALL-UNNAMED}, when this JVM understands it.
     *
     * <p>{@code LayerCaches} swaps the biome layers' 1024-entry cache for a 4096-entry one
     * by setting a final field reflectively, which is worth a measured 1.07x. Newer JDKs
     * warn about that on every run and will eventually block it. Blocking is survivable —
     * {@code enlarge} catches and leaves the stock caches, costing speed and not
     * correctness — but the warning is three alarming lines in front of every search.
     *
     * <p>Probed rather than version-checked: an unrecognised {@code --enable-*} stops the
     * JVM from starting at all, so guessing which release introduced it would turn a
     * cosmetic problem into a broken Run button. One {@code -version} launch, cached.
     */
    private static synchronized List<String> finalFieldFlag() {
        if (finalFieldFlag == null) {
            finalFieldFlag = List.of();
            String flag = "--enable-final-field-mutation=ALL-UNNAMED";
            try {
                Process p = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        flag, "-version")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (p.waitFor() == 0) {
                    finalFieldFlag = List.of(flag);
                }
            } catch (IOException | InterruptedException e) {
                // Leave it off; the warning is noise, not a failure.
            }
        }
        return finalFieldFlag;
    }

    private static List<String> finalFieldFlag;

    /**
     * Where to find the classes to launch. {@code java.class.path} is just the jar name
     * when started with {@code -jar}, which is fine from a terminal and wrong when the
     * working directory is not the jar's — a double-click, or a shortcut. The code source
     * is the absolute answer; the property is the fallback for running from classes.
     */
    private static String classpath() {
        try {
            java.security.CodeSource src =
                    SugarcaneGui.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                Path p = Path.of(src.getLocation().toURI());
                if (java.nio.file.Files.isRegularFile(p)) {
                    return p.toAbsolutePath().toString();
                }
            }
        } catch (Exception ignored) {
            // Fall through: an unusual classloader is not worth failing over.
        }
        return System.getProperty("java.class.path");
    }

    /**
     * Ends the child if there is one, and waits briefly for it to actually go.
     *
     * <p>{@code destroy} only asks; without the wait a shutdown hook can return and let
     * the JVM exit while the search is still winding down, which is the same orphan by a
     * slower route.
     */
    private boolean stopRunning() {
        Process p = running;
        if (p == null || !p.isAlive()) {
            return false;
        }
        p.destroy();
        try {
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        return true;
    }

    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            console.append(text);
            // A long search prints for hours; keep the document bounded.
            if (console.getLineCount() > MAX_LINES) {
                try {
                    int cut = console.getLineEndOffset(console.getLineCount() - MAX_LINES);
                    console.replaceRange("", 0, cut);
                } catch (Exception ignored) {
                    // Bounding the console is best-effort and never worth an error dialog.
                }
            }
            console.setCaretPosition(console.getDocument().getLength());
        });
    }

    // ---------------------------------------------------------------- form helper

    private static String defaultThreads() {
        return Integer.toString(Runtime.getRuntime().availableProcessors());
    }

    private static String req(JTextField f, String name) {
        String v = f.getText().trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return v;
    }

    /** Positional, so an empty box still has to send something; 0 means no limit. */
    private static String orZero(JTextField f) {
        String v = f.getText().trim();
        return v.isEmpty() ? "0" : v;
    }

    private static void addIf(List<String> args, String flag, JTextField f) {
        if (!f.getText().isBlank()) {
            args.add(flag + f.getText().trim());
        }
    }

    /** Label-and-field rows, so each tab is a list of arguments rather than a layout. */
    private static final class Form {
        private final JPanel panel = new JPanel(new GridBagLayout());
        private final GridBagConstraints c = new GridBagConstraints();
        private final Map<String, JComponent> fields = new LinkedHashMap<>();
        private int row;

        Form(String blurb) {
            c.insets = new Insets(3, 4, 3, 4);
            c.anchor = GridBagConstraints.WEST;
            JLabel label = new JLabel("<html><body style='width:640px'>" + blurb + "</body></html>");
            c.gridx = 0;
            c.gridy = row++;
            c.gridwidth = 3;
            panel.add(label, c);
            c.gridwidth = 1;
        }

        JTextField text(String name, String value, String tip) {
            JTextField f = new JTextField(value, 16);
            place(name, f, tip);
            fields.put(name, f);
            return f;
        }

        JComboBox<String> combo(String name, String... values) {
            JComboBox<String> b = new JComboBox<>(values);
            place(name, b, null);
            return b;
        }

        JCheckBox check(String name, String tip) {
            JCheckBox b = new JCheckBox(name);
            c.gridx = 1;
            c.gridy = row;
            panel.add(b, c);
            if (tip != null) {
                c.gridx = 2;
                panel.add(hint(tip), c);
            }
            row++;
            return b;
        }

        private void place(String name, JComponent field, String tip) {
            c.gridx = 0;
            c.gridy = row;
            panel.add(new JLabel(name), c);
            c.gridx = 1;
            panel.add(field, c);
            if (tip != null) {
                c.gridx = 2;
                panel.add(hint(tip), c);
            }
            row++;
        }

        private static JLabel hint(String tip) {
            JLabel l = new JLabel("<html><body style='width:420px'>" + tip + "</body></html>");
            l.setForeground(MUTED);
            return l;
        }
    }
}
