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
    private static String reporterUsername;

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
                    "Scan every chunk in a box around each seed's origin. The plain search, "
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
                            + "[--targets=<file>]",
                    "Pick the cane RNG first, then solve for a chunk that has it. Collects "
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
                            + "between runs, or you repeat the same work exactly.",
                    ReverseSearcher::main),
            new Command("targets",
                    "<minHeight> <count> <file> [threads]",
                    "Build or extend a reverse-search target set and stop, without "
                            + "searching. The set is the expensive half and the reusable "
                            + "one: it depends on the height, the depth band and the soil "
                            + "filter, never on the world seed, so build it once and hand it "
                            + "to every `reverse` run afterwards with --targets=<file>. "
                            + "Re-running with a larger count extends the file rather than "
                            + "starting over. Cost per member climbs steeply with height, "
                            + "because the acceptance rate falls faster than the per-test cost "
                            + "does - about 0.4 ms at height 5, 5.9 ms at height 8.",
                    ReverseSearcher::targetsMain),
            new Command("sin-table",
                    "<file>",
                    "Write Mth.SIN as big-endian float bits, for the CUDA scanner to load "
                            + "rather than recompute. Recomputing it with C's sin() "
                            + "disagrees with Java at entry 32768, so the table is handed "
                            + "over rather than trusted to two libms agreeing.",
                    args -> dev.drakou111.sugarcane.rng.Mth.main(args)),
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
            new Command("spawn",
                    "<seed> [count]",
                    "Where a fresh world drops the player, which is not the origin. With a "
                            + "count, times the calculation over that many seeds.",
                    SpawnBench::main),
            new Command("columns",
                    "<seed> <x0> <x1> <z> <y0> <y1>",
                    "Diagnostic: the raw noise terrain for a slice, before the surface "
                            + "builder, carvers or any feature has touched it.",
                    ProbeColumns::main),
            new Command("seed-bits",
                    "[low48]",
                    "Diagnostic: shows that carvers, terrain and decoration depend only on "
                            + "the seed's low 48 bits, while the upper 16 move the biome map "
                            + "and nothing else.",
                    SeedBitsProbe::main),
            new Command("rng-only",
                    "[trials]",
                    "Diagnostic: replays the cane feature over many decoration seeds on "
                            + "hand-built terrain, to price the RNG separately from the "
                            + "terrain. Reads ~2x optimistic against generated chunks at every "
                            + "height, so halve it (FINDINGS 6ag).",
                    Main::main),
            new Command("prefilter-bench",
                    "[seeds] [radius]",
                    "Diagnostic: benchmarks the seed-only prefilters and checks they still "
                            + "keep the confirmed find.",
                    PrefilterBench::main),
            new Command("carver-walk",
                    "[chunks] [firstSeed]",
                    "Diagnostic: how often the carver walks alone put air against water, "
                            + "with no terrain generated. The walks are pure RNG, which is "
                            + "what makes the reverse search's position filter possible.",
                    CarverWalkFilter::main),
            new Command("validate-proto",
                    "<proto.bin> [margin]",
                    "Validation: compares the simulated feature-time world block by block "
                            + "against real pre-flood chunks. The broadest accuracy check "
                            + "there is. Needs an export from tools/export_proto.py.",
                    args -> dev.drakou111.sugarcane.validate.ProtoValidator.main(args)),
            new Command("validate-cane",
                    "<chunks.bin>",
                    "Validation: replays the cane feature over real chunks and counts how "
                            + "many reproduce exactly.",
                    args -> dev.drakou111.sugarcane.validate.RealWorldValidator.main(args)),
            new Command("validate-carver",
                    "<air.bin>",
                    "Validation: scores the cave and canyon carvers against real chunks. Use "
                            + "features-status chunks, not full ones - see FINDINGS 7.",
                    args -> dev.drakou111.sugarcane.validate.CarverValidator.main(args)),
            new Command("validate-biomes",
                    "<biomes.bin>",
                    "Validation: checks the biome source against the stored biome array of "
                            + "real chunks.",
                    args -> dev.drakou111.sugarcane.validate.BiomeSourceValidator.main(args)),
            new Command("validate-terrain",
                    "<heightmaps.bin>",
                    "Validation: checks the noise terrain against the stored heightmaps of "
                            + "real chunks.",
                    args -> dev.drakou111.sugarcane.validate.TerrainValidator.main(args)),
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
                        + ", reporting as Anonymous.");
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

            props.setProperty("username", reporterUsername);
            try (FileOutputStream out = new FileOutputStream(configFile)) {
                props.store(out, "Sugarcane Finder User Configuration");
                System.out.println("Saved username to " + CONFIG_FILE);
            } catch (IOException e) {
                System.err.println("Failed to save config file: " + e.getMessage());
            }
        }
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
        System.out.println("FINDINGS.md is the real documentation: mechanics read off the decompiled");
        System.out.println("1.16.1 server, every measurement, and everything that went wrong.");
    }
}
