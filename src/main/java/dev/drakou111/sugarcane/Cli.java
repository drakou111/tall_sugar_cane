package dev.drakou111.sugarcane;

import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Properties;
import java.util.Scanner;

/**
 * Single entry point for everything in this project, so the shaded jar can be run
 * without knowing class names:
 *
 * <pre>
 * java -jar sugarcane.jar search 1 1000000 6 24 5
 * java -jar sugarcane.jar inspect 1500050556 91 16 65 6
 * </pre>
 */
public final class Cli {

    private static final String CONFIG_FILE = "config.properties";
    /**
     * {@code --user=<name>}: the name finds are reported under. Saved to
     * {@link #CONFIG_FILE}, so it is given once rather than every run.
     *
     * <p>It exists because {@code --yes-report} deliberately never touches stdin — a
     * backgrounded run would stop dead on a prompt — which used to mean anyone reporting
     * without an interactive first run was stuck as Anonymous forever. That includes
     * every run started from the GUI, which always passes the flag.
     */
    private static final String USER_FLAG = "--user=";
    private static String reporterUsername;
    /** Set by {@code --user=<name>}: overrides the saved name and replaces it. */
    private static String userOverride;

    public static boolean reportFinds;

    private Cli() {
    }

    private record Command(String name, String args, String description, Runner runner) {
    }

    private interface Runner {
        void run(String[] args) throws Exception;
    }

    private static final Command[] COMMANDS = {
            new Command("search",
                    "<firstSeed> <seedCount> <chunkRadius> <threads> <minHeight> "
                            + "[diag|probe:N|spots] [--spawn] [--update=<minutes>]",
                    "Scan every chunk in a box around each seed's origin. seedCount 0, "
                            + "or leaving it off, runs until you stop it. The plain search, "
                            + "and the right one up to height 6; from 7 up use `reverse`, which "
                            + "is ~2,500x faster at height 8. chunkRadius bounds how far from "
                            + "the centre a find may be and is nearly free (6 runs as fast as "
                            + "32). minHeight is the shortest run worth printing. Modes: `diag` "
                            + "counts the terrain geometry, `probe:N` also replays the cane "
                            + "feature over N synthetic decoration seeds per promising chunk to "
                            + "measure the per-chunk find rate, `spots` prints the rare terrain "
                            + "itself. --spawn centres the box on that world's spawn chunk "
                            + "rather than 0,0, so a find is one you can walk to, at ~38% of "
                            + "the chunks per second. --update sets the progress interval in "
                            + "minutes, default 1.",
                    RegionSearcher::main),
            new Command("reverse",
                    "<minHeight> [threads] [targets] [firstSeed] [seedCount] "
                            + "[--targets=<file>] [--cpu] [--report=<h>] "
                            + "[--update=<minutes>] [--max-shift=<n>] [--max-columns=<n>] "
                            + "[--max-slack=<n>] [--sample-from=<n>] [--sisters=<n>] "
                            + "[--all-carvers]",
                    "Pick the cane RNG first, then solve for a chunk that has it. seedCount "
                            + "0, or leaving it off, runs until you stop it. Collects "
                            + "decoration seeds whose draws could chain a tall enough column "
                            + "with no terrain involved, then inverts setDecorationSeed by "
                            + "lattice reduction to turn each one into real coordinates inside "
                            + "the world border - so finds land anywhere, not near spawn. "
                            + "Prints the same HIT lines as `search`. targets sizes the "
                            + "collected set: bigger costs more up front and amortises better, "
                            + "20k for a quick run, 200k for a long one. --targets=<file> saves "
                            + "that set and reloads it, which is worth doing because it does "
                            + "not depend on the world seed at all; a later run wanting more "
                            + "extends the file instead of rebuilding it. Advance firstSeed "
                            + "between runs, or you repeat the same work exactly. "
                            + "--report=<h> reports runs of h or more while still building "
                            + "targets for minHeight: a 9-chain of 4+3+2 whose last column "
                            + "finds no terrain still leaves a 7, and that is worth keeping. "
                            + "--max-shift and --max-columns override the target ranking, "
                            + "which is what makes an older set reproducible. "
                            + "--max-slack=<n> is how many OTHER cane placements may land "
                            + "between a chain's own columns. The default 0 is the "
                            + "contiguous window: the stack's columns must be consecutive "
                            + "successful placements. That keeps 40% of the target set and "
                            + "87.9% of real finds, a net 2.2x, and it is the one filter "
                            + "here that trades coverage rather than being free -- the "
                            + "confirmed 5-tall is in the 12% it drops, because its chunk "
                            + "grew an unrelated column mid-stack. --max-slack=99 restores "
                            + "the old behaviour. Values between 0 and maxColumns are "
                            + "CPU-only; the kernel cannot express them. "
                            + "From height 8 up the carve probe uses RAVINES ONLY, because "
                            + "every find at that height is ravine-carved and a cave cannot "
                            + "open the vertical wall a stack needs -- canyon runs of 5, 10 "
                            + "and 28 blocks at the three finds we have. That is also why a "
                            + "16 is barely harder than an 8 in terrain terms: the ravine "
                            + "satisfies every column at once, so search tall. --all-carvers "
                            + "puts caves back, --ravines-only forces them out below height 8. "
                            + "--sample-from=<n> starts decoration-seed sampling at that "
                            + "sample index. Left off, the start is RANDOM and printed, so "
                            + "two machines building the same set cover different ground "
                            + "instead of duplicating each other -- pass the printed index "
                            + "back to repeat a run exactly. Rounded down to a run boundary. "
                            + "A resumed cache still wins, since its own cursor is what "
                            + "knows what was tested. "
                            + "--update=<minutes> sets the progress interval, default 1. "
                            + "--sisters=<n> sweeps n values of the seed's upper 16 bits "
                            + "per low-48 seed. Those bits change only the biome map: the "
                            + "lattice, the decoration seed and the carver walk all depend "
                            + "on the low 48 alone, so the target sweep and the air probe "
                            + "run once and amortise over all n, and the biome gate then "
                            + "runs only on what the probe kept. Measured 4.2x at n=64, "
                            + "which is the default and is where the curve flattens; "
                            + "--sisters=1 restores the old loop. Note firstSeed and "
                            + "seedCount count low-48 seeds, and each is searched at n "
                            + "different upper-16 values rather than consecutively.",
                    ReverseSearcher::main),
            new Command("merge",
                    "<out> <in...>",
                    "Pool target sets built on different machines into one. A set never "
                            + "depends on the world seed, so it is the one artefact worth "
                            + "sharing, and since a build now starts at a random sample "
                            + "index two people running the same command cover different "
                            + "ground -- this is what makes their files add up. Duplicates "
                            + "are dropped and the result is sorted, so merging the same "
                            + "file twice is harmless. Refuses sets that do not mean the "
                            + "same thing: every header field except the counters decides "
                            + "what membership is, so a mismatch is two different questions "
                            + "rather than a wider set. Reports the per-bucket spread, "
                            + "because a world seed reads only one bucket in sixteen and an "
                            + "uneven pool helps some seeds and not others.",
                    TargetMerge::main),
            new Command("targets",
                    "<minHeight> <count> <file> [threads] [--cpu] [--update=<minutes>] "
                            + "[--max-shift=<n>] [--max-columns=<n>] [--max-slack=<n>] "
                            + "[--sample-from=<n>]",
                    "Build or extend a reverse-search target set and stop, without "
                            + "searching. The set is the expensive half and the reusable "
                            + "one: it depends on the height, the depth band and the soil "
                            + "filter, never on the world seed, so build it once and hand it "
                            + "to every `reverse` run afterwards with --targets=<file>. "
                            + "Re-running with a larger count extends the file rather than "
                            + "starting over. Cost per member climbs steeply with height, "
                            + "because the acceptance rate falls faster than the per-test cost "
                            + "does - about 0.4 ms at height 5, 5.9 ms at height 8. Uses a CUDA "
                            + "chain filter automatically when cuda/find_targets.exe is "
                            + "present and a device answers a test batch, which is ~4.7x "
                            + "faster; --cpu forces the CPU path. Either device produces a "
                            + "byte-identical file. The kernel ships inside this jar and "
                            + "needs no CUDA toolkit, only an NVIDIA driver, so there is "
                            + "nothing to build or install; if the GPU is skipped anyway "
                            + "it says why, because the fallback is 4.5x slower and used "
                            + "to be silent.",
                    ReverseSearcher::targetsMain),
            new Command("sisters",
                    "<seed> <x> <y> <z> [count] [threads] [minHeight]",
                    "Re-roll the terrain under a known chain by sweeping the seed's upper "
                            + "16 bits. Seeds sharing their low 48 bits have the same "
                            + "decoration seed at the same chunk, the same lattice solution "
                            + "and the same carver walks, so the chain sits at the same "
                            + "block with the same column bases in all 65,536 of them - only "
                            + "the biome map changes, and with it the sea floor. That is what "
                            + "to run on a find the game truncates: a simulated 12 standing 8 "
                            + "in game lost its upper columns to terrain, which is exactly "
                            + "what a sister re-rolls while leaving the RNG alone.",
                    SisterScan::main),
            new Command("inspect",
                    "<seed> <x> <y> <z> [searchRadius]",
                    "Regenerate the region around one position and dump what the simulator "
                            + "sees there: a vertical slice, every nearby cane column, the "
                            + "water the placement depended on, and the placement trace naming "
                            + "which invocations stacked. Use it on a HIT before travelling, "
                            + "and afterwards to see which block the simulator got wrong if "
                            + "the real game disagrees. Works anywhere in the world, including "
                            + "millions of blocks out.",
                    Inspect::main),
    };

    /** What to do about the spreadsheet question, before anything reads stdin. */
    private enum Reporting { ASK, YES, NO }

    public static void main(String[] args) throws Exception {
        // Pulled out and stripped before dispatch, because every command below parses
        // its arguments positionally and a stray flag would be read as a seed.
        Reporting reporting = Reporting.ASK;
        java.util.List<String> rest = new java.util.ArrayList<>(args.length);
        for (String arg : args) {
            if (arg.equals("--no-report")) {
                reporting = Reporting.NO;
            } else if (arg.equals("--yes-report")) {
                reporting = Reporting.YES;
            } else if (arg.startsWith(USER_FLAG)) {
                userOverride = arg.substring(USER_FLAG.length()).trim();
            } else {
                rest.add(arg);
            }
        }
        args = rest.toArray(new String[0]);

        Desktop desktop = Desktop.getDesktop();
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")
                || args[0].equals("help")) {
            usage();
            return;
        }

        if (args[0].equals("-s") || args[0].equals("--sheet")) {
            try {
                URI oURL = new URI("https://docs.google.com/spreadsheets/d/1dhSnz-PFo3yl5uOFxqGmzDXg2O7JHACnqSlmLw_1Ang/edit?usp=sharing");
                desktop.browse(oURL);
                return;
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
            return;
        }

        setupUser(reporting);

        for (Command command : COMMANDS) {
            if (command.name().equals(args[0])) {
                command.runner().run(Arrays.copyOfRange(args, 1, args.length));
                return;
            }
        }
        System.err.println("unknown command: " + args[0]);
        usage();
        System.exit(2);
    }

    /**
     * Decides whether finds go to the spreadsheet. Left to itself this asks on stdin,
     * which is fine interactively and a trap otherwise: a backgrounded run stops dead
     * on the prompt, and one with no stdin at all dies on
     * {@code NoSuchElementException} before it starts. {@code --no-report} and
     * {@code --yes-report} answer it up front so nothing touches stdin.
     */
    private static void setupUser(Reporting reporting) {
        File configFile = new File(CONFIG_FILE);
        Properties props = new Properties();

        if (configFile.exists()) {
            try (FileInputStream in = new FileInputStream(configFile)) {
                props.load(in);
                reporterUsername = props.getProperty("username");
            } catch (IOException e) {
                System.err.println("Failed to read " + CONFIG_FILE + ", proceeding without username.");
            }
        }

        // An explicit name wins over the saved one, and replaces it, so --user= is given
        // once rather than every run.
        if (userOverride != null && !userOverride.isEmpty()) {
            boolean changed = !userOverride.equals(reporterUsername);
            reporterUsername = userOverride;
            if (changed) {
                saveUsername(props, configFile);
            }
        }

        if (reporting == Reporting.NO) {
            reportFinds = false;
            return;
        }
        if (reporting == Reporting.YES) {
            reportFinds = true;
            // No prompting here either: whatever the config holds, or Anonymous. The
            // whole point of the flag is that stdin is not available.
            if (reporterUsername == null || reporterUsername.trim().isEmpty()) {
                reporterUsername = "Anonymous";
                System.out.println("--yes-report: no username in " + CONFIG_FILE
                        + ", reporting as Anonymous. Pass --user=<name> once to set it.");
            } else {
                System.out.println("reporting finds as " + reporterUsername);
            }
            return;
        }

        Scanner scanner1 = new Scanner(System.in);
        System.out.print("Do you want to report your finds to the spreadsheet, which you can open by doing java -jar sugarcane.jar -s? (y/n): ");
        String report = scanner1.nextLine().trim();
        if (report.equals("y") || report.equals("yes") || report.equals("Yes") || report.equals("Y")) {
            reportFinds = true;
        } else {
            reportFinds = false;
            return;
        }

        if (reporterUsername == null || reporterUsername.trim().isEmpty()) {
            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter your username for reporting finds: ");
            reporterUsername = scanner.nextLine().trim();

            if (reporterUsername.isEmpty()) {
                reporterUsername = "Anonymous";
            }

            saveUsername(props, configFile);
        }
    }

    private static void saveUsername(Properties props, File configFile) {
        props.setProperty("username", reporterUsername);
        try (FileOutputStream out = new FileOutputStream(configFile)) {
            props.store(out, "Sugarcane Finder User Configuration");
            System.out.println("Saved username \"" + reporterUsername + "\" to " + CONFIG_FILE);
        } catch (IOException e) {
            System.err.println("Failed to save config file: " + e.getMessage());
        }
    }

    /** The saved name, or null. Read by the GUI so its field starts filled in. */
    public static String savedUsername() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            return null;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(configFile)) {
            props.load(in);
            return props.getProperty("username");
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Threads, never more than the machine has.
     *
     * <p>Oversubscribing does not just fail to help: every worker holds its own region
     * buffer and biome caches, so past the core count the extra threads add memory and
     * cache pressure and the search gets slower. Asking for 64 on a 24-thread box is
     * always a mistake, so it is corrected rather than obeyed.
     */
    public static int clampThreads(int requested) {
        int cores = Runtime.getRuntime().availableProcessors();
        if (requested < 1) {
            System.out.println("threads " + requested + " is not usable, using 1");
            return 1;
        }
        if (requested > cores) {
            System.out.println("threads " + requested + " exceeds this machine's " + cores
                    + ", using " + cores);
            return cores;
        }
        return requested;
    }

    public static String getReporterUsername() {
        return reporterUsername != null ? reporterUsername : "Anonymous";
    }

    /** Word-wraps a description so a long one does not depend on terminal width. */
    private static void wrap(String text, String indent, int width) {
        StringBuilder line = new StringBuilder(indent);
        for (String word : text.split(" ")) {
            if (line.length() > indent.length() && line.length() + 1 + word.length() > width) {
                System.out.println(line);
                line.setLength(0);
                line.append(indent);
            } else if (line.length() > indent.length()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > indent.length()) {
            System.out.println(line);
        }
    }

    private static void usage() {
        System.out.println("Sugar cane taller than 4: a Minecraft 1.16.1 worldgen search.");
        System.out.println();
        System.out.println("Growth stops at 3 and worldgen stops at 4, so 5 or more needs two");
        System.out.println("placements landing on the same block in one chunk. This finds them.");
        System.out.println();
        System.out.println("  java -jar sugarcane.jar [--no-report|--yes-report] <command> [args]");
        System.out.println();
        for (Command command : COMMANDS) {
            System.out.printf("  %-16s %s%n", command.name(), command.args());
            wrap(command.description(), "                     ", 78);
            System.out.println();
        }
        System.out.println("Start here:");
        System.out.println();
        System.out.println("  java -jar sugarcane.jar search 10 1000000 6 24 5");
        System.out.println("     scans seeds 10.. within 96 blocks of the origin on 24 threads,");
        System.out.println("     printing a HIT for any column 5 or taller.");
        System.out.println();
        System.out.println("  java -jar sugarcane.jar reverse 8 24 200000 1 --targets=targets8.bin");
        System.out.println("     the fast path for 7 and above. Spends a few minutes collecting");
        System.out.println("     targets, saves them for next time, then searches. Bump firstSeed");
        System.out.println("     on the next run so it covers new ground.");
        System.out.println();
        System.out.println("  java -jar sugarcane.jar inspect 1500050556 91 16 65 6");
        System.out.println("     shows the confirmed 5-tall find and how it was built.");
        System.out.println();
        System.out.println("A HIT is a candidate, not a result: the searcher is a reimplementation");
        System.out.println("and about one hit in three does not survive the real game. Check it with");
        System.out.println("`inspect`, then tools/verify.py, before travelling.");
        System.out.println();
        System.out.println("Flags accepted anywhere, on any command:");
        System.out.println("  --no-report      do not report finds; never reads stdin");
        System.out.println("  --yes-report     report finds; never reads stdin");
        System.out.println("     Without either, the spreadsheet question is asked on stdin, which");
        System.out.println("     stalls a backgrounded run and kills one with no stdin at all.");
        System.out.println();
        System.out.println("The diagnostics and validators are no longer listed here, but the");
        System.out.println("classes remain and the shaded jar can still run them directly, e.g.");
        System.out.println("  java -cp target/sugarcane.jar dev.drakou111.sugarcane.validate.ProtoValidator <args>");
        System.out.println("  java -cp target/sugarcane.jar dev.drakou111.sugarcane.rng.Mth <file>");
        System.out.println();
        System.out.println("FINDINGS.md is the real documentation: mechanics read off the decompiled");
        System.out.println("1.16.1 server, every measurement, and everything that went wrong.");
    }
}
