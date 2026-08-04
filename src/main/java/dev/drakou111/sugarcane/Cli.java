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
                    "Search for sugar cane taller than 4. This is the main program. "
                            + "--spawn centres each seed's box on that world's spawn chunk "
                            + "rather than 0,0, so a find is one you can walk to; it costs "
                            + "about 38% of the chunks per second. --update sets how often "
                            + "the progress line prints, in minutes (default 1).",
                    RegionSearcher::main),
            new Command("reverse",
                    "<minHeight> [threads] [targets] [firstSeed] [seedCount] [--targets=<file>]",
                    "Reverse search: choose the cane RNG first, then solve for the chunk "
                            + "that has it. Builds a set of decoration seeds whose draws "
                            + "could chain a tall enough column, then uses the decoration "
                            + "seed lattice to turn each one into real coordinates inside "
                            + "the world border. Worth it from height 7 up; below that use "
                            + "`search`. Prints the same HIT lines. --targets=<file> "
                            + "caches the target set, which does not depend on the "
                            + "world seed, so its build cost is paid once ever and a "
                            + "later run can extend it rather than rebuild it.",
                    ReverseSearcher::main),
            new Command("inspect",
                    "<seed> <x> <y> <z> [searchRadius]",
                    "Regenerate one region and dump what the simulator sees at a position, "
                            + "including the placement trace that shows which invocations stacked.",
                    Inspect::main),
            new Command("spawn",
                    "<seed> [count]",
                    "Where a fresh world puts the player. With a count, times it over "
                            + "that many seeds.",
                    SpawnBench::main),
            new Command("columns",
                    "<seed> <x0> <x1> <z> <y0> <y1>",
                    "Print the raw noise terrain for a slice, before surface, carvers or features.",
                    ProbeColumns::main),
            new Command("seed-bits",
                    "[low48]",
                    "Show that the low 48 bits of the seed fix carvers and decoration while "
                            + "the upper 16 move only the biomes.",
                    SeedBitsProbe::main),
            new Command("rng-only",
                    "[trials]",
                    "Measure P: replay the cane feature over many decoration seeds on fixed terrain.",
                    Main::main),
            new Command("prefilter-bench",
                    "[seeds] [radius]",
                    "Benchmark the seed-only prefilters and check they keep the confirmed find.",
                    PrefilterBench::main),
            new Command("carver-walk",
                    "[chunks] [firstSeed]",
                    "How often the carver walks alone put air against water, with no terrain.",
                    CarverWalkFilter::main),
            new Command("validate-proto",
                    "<proto.bin> [margin]",
                    "Compare the simulated feature-time world against real pre-flood chunks. "
                            + "Needs an export from tools/export_proto.py.",
                    args -> dev.drakou111.sugarcane.validate.ProtoValidator.main(args)),
            new Command("validate-cane",
                    "<chunks.bin>",
                    "Replay the cane feature over real chunks and count exact reproductions.",
                    args -> dev.drakou111.sugarcane.validate.RealWorldValidator.main(args)),
            new Command("validate-carver",
                    "<air.bin>",
                    "Score the cave and canyon carvers against real chunks.",
                    args -> dev.drakou111.sugarcane.validate.CarverValidator.main(args)),
            new Command("validate-biomes",
                    "<biomes.bin>",
                    "Check the biome source against the stored biome array of real chunks.",
                    args -> dev.drakou111.sugarcane.validate.BiomeSourceValidator.main(args)),
            new Command("validate-terrain",
                    "<heightmaps.bin>",
                    "Check the noise terrain against the stored heightmaps of real chunks.",
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

    private static void usage() {
        System.out.println("Sugar cane taller than 4: a Minecraft 1.16.1 worldgen search.");
        System.out.println();
        System.out.println("  java -jar sugarcane.jar <command> [args]");
        System.out.println();
        for (Command command : COMMANDS) {
            System.out.printf("  %-16s %s%n", command.name(), command.args());
            System.out.printf("  %-16s   %s%n", "", command.description());
            System.out.println();
        }
        System.out.println("Start here:");
        System.out.println("  java -jar sugarcane.jar search 10 1000000 6 24 5");
        System.out.println("     searches seeds 10..1000010 within 96 blocks (6 chunks) of spawn on 24 threads,");
        System.out.println("     printing a HIT line for any column 5 or taller.");
        System.out.println();
        System.out.println("  java -jar sugarcane.jar inspect 1500050556 91 16 65 6");
        System.out.println("     shows the confirmed 5-tall find and how it was built.");
        System.out.println();
        System.out.println("Flags accepted anywhere, on any command:");
        System.out.println("  --no-report      do not report finds; never reads stdin");
        System.out.println("  --yes-report     report finds; never reads stdin");
        System.out.println("     Without either, the spreadsheet question is asked on stdin, which");
        System.out.println("     stalls a backgrounded run and kills one with no stdin at all.");
    }
}
