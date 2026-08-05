package dev.drakou111.sugarcane.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
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
        try {
            for (UIManager.LookAndFeelInfo laf : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(laf.getName())) {
                    UIManager.setLookAndFeel(laf.getClassName());
                    break;
                }
            }
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
            UIManager.put("Panel.background", PANEL);
            UIManager.put("TextArea.background", FIELD);
            UIManager.put("TextArea.foreground", TEXT);
            UIManager.put("ScrollPane.background", PANEL);
        } catch (Exception ignored) {
            // Cosmetic only: a look and feel that will not load is not worth failing over.
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

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top,
                new JScrollPane(console));
        split.setResizeWeight(0.0);
        frame.add(split);
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
        if (frame == null || split == null) {
            return;
        }
        int max = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds().height;
        int want = Math.min(baseHeight + Math.max(0, stackPixels - 40), max);
        if (frame.getHeight() != want) {
            frame.setSize(frame.getWidth(), want);
        }
        split.setDividerLocation(baseDivider + Math.max(0, stackPixels - 40));
    }

    private void addTab(String name, Tab tab) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(tab.panel, BorderLayout.NORTH);
        wrapper.setBorder(new EmptyBorder(8, 8, 8, 8));
        tabs.addTab(name, new JScrollPane(wrapper));
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
        JTextField update = f.text("--update (minutes)", "", null);
        JCheckBox cpu = f.check("--cpu", "force the CPU chain filter instead of CUDA");
        JCheckBox water = f.check("--water-probe", "require a chain's water to come from a "
                + "LIQUID carver: 1.6x, but it can lose spots that sit on the sea floor");
        return new Tab(f.panel, () -> {
            List<String> a = new ArrayList<>(List.of("reverse", req(height, "minHeight"),
                    req(threads, "threads"), req(targets, "targets"),
                    req(first, "firstSeed"), orZero(count)));
            addIf(a, "--targets=", file);
            addIf(a, "--report=", reportH);
            addIf(a, "--sisters=", sisters);
            addIf(a, "--max-shift=", maxShift);
            addIf(a, "--max-columns=", maxCols);
            addIf(a, "--update=", update);
            if (cpu.isSelected()) {
                a.add("--cpu");
            }
            if (water.isSelected()) {
                a.add("--water-probe");
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
        JCheckBox cpu = f.check("--cpu", "force the CPU filter; the GPU one is ~4.7x");
        JTextField update = f.text("--update (minutes)", "", null);
        return new Tab(f.panel, () -> {
            List<String> a = new ArrayList<>(List.of("targets", req(height, "minHeight"),
                    req(count, "count"), req(file, "file"), req(threads, "threads")));
            if (cpu.isSelected()) {
                a.add("--cpu");
            }
            addIf(a, "--update=", update);
            return a;
        });
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
