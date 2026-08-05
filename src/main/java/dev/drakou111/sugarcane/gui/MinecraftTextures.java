package dev.drakou111.sugarcane.gui;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Block textures, from the jar first and a local Minecraft install second.
 *
 * <p>The six 16x16 PNGs the slot machine needs ship in {@code /textures/block}, taken from
 * 1.16.1 — the version this whole project simulates. That is Mojang's artwork rather than
 * this project's; {@code src/main/resources/textures/block/README.txt} says so and says
 * what to delete if it should not be there. Bundling them is what makes the art the same
 * for everyone, which searching a machine for an install demonstrably was not.
 *
 * <p>The search is kept as a fallback, so a copy deleted from the jar still finds an
 * install, and {@code -Dsugarcane.mcjar=<path>} still overrides both. If everything comes
 * up empty {@link #block} returns null and the caller draws its own approximation, so the
 * game works with no textures at all.
 */
final class MinecraftTextures {

    private static final String PREFIX = "assets/minecraft/textures/block/";
    private static final String MARKER = PREFIX + "sugar_cane.png";

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();
    private static boolean searched;
    private static Path jar;

    private MinecraftTextures() {
    }

    /** The client jar in use, or null. Shown in the UI so it is obvious where art came from. */
    static synchronized Path source() {
        find();
        return jar;
    }

    /**
     * One block texture, or null when no install was found.
     *
     * <p>Animated textures are stored as a vertical strip of frames — {@code water_still}
     * is 16x512 — so anything taller than it is wide is cropped to its first frame.
     */
    static synchronized BufferedImage block(String name) {
        if (CACHE.containsKey(name)) {
            return CACHE.get(name);
        }
        BufferedImage img = bundled(name);
        if (img == null) {
            img = fromInstall(name);
        }
        if (img != null && img.getHeight() > img.getWidth()) {
            img = img.getSubimage(0, 0, img.getWidth(), img.getWidth());
        }
        CACHE.put(name, img);
        return img;
    }

    /** Whether the jar carries its own textures, which decides what the UI should say. */
    static boolean bundledPresent() {
        return MinecraftTextures.class.getResource("/textures/block/sugar_cane.png") != null;
    }

    /** The copies inside the jar, so no install is needed and everyone sees the same art. */
    private static BufferedImage bundled(String name) {
        try (InputStream in = MinecraftTextures.class
                .getResourceAsStream("/textures/block/" + name + ".png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage fromInstall(String name) {
        BufferedImage img = null;
        find();
        if (jar != null) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(PREFIX + name + ".png");
                if (entry != null) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        img = ImageIO.read(in);
                    }
                }
            } catch (Exception e) {
                img = null;
            }
        }
        return img;
    }

    /**
     * Point at a jar explicitly. Returns false and changes nothing if it has no textures,
     * so a wrong pick cannot silently leave the game with no art.
     */
    static synchronized boolean use(Path candidate) {
        try (ZipFile zip = new ZipFile(candidate.toFile())) {
            if (zip.getEntry(MARKER) == null) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        jar = candidate;
        searched = true;
        CACHE.clear();
        return true;
    }

    private static void find() {
        if (searched) {
            return;
        }
        searched = true;
        // An explicit answer beats any amount of guessing.
        String override = System.getProperty("sugarcane.mcjar");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isRegularFile(p) && use(p)) {
                return;
            }
        }
        for (Path candidate : candidates()) {
            try (ZipFile zip = new ZipFile(candidate.toFile())) {
                if (zip.getEntry(MARKER) != null) {
                    jar = candidate;
                    return;
                }
            } catch (Exception ignored) {
                // Not a readable jar, or not a client one. Try the next.
            }
        }
    }

    /**
     * Plausible client jars, 1.16.1 first.
     *
     * <p>Bounded on purpose: a launcher directory holds hundreds of mod jars, and opening
     * all of them to find a texture for a slot machine would be a visible pause. Depth and
     * count are capped, mods are skipped by size, and the first jar that has the marker
     * wins.
     */
    private static List<Path> candidates() {
        List<Path> roots = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        String home = System.getProperty("user.home");
        if (appData != null) {
            roots.add(Path.of(appData, ".minecraft", "versions"));
            roots.add(Path.of(appData, "PrismLauncher", "instances"));
            roots.add(Path.of(appData, "curseforge", "minecraft", "Install", "versions"));
            roots.add(Path.of(appData, ".minecraft", "libraries", "com", "mojang", "minecraft"));
            // The other launchers people actually use.
            roots.add(Path.of(appData, "MultiMC", "instances"));
            roots.add(Path.of(appData, "PolyMC", "instances"));
            roots.add(Path.of(appData, "ATLauncher", "versions"));
            roots.add(Path.of(appData, "gdlauncher_next", "instances"));
            roots.add(Path.of(appData, "ModrinthApp", "meta", "versions"));
            roots.add(Path.of(appData, "com.modrinth.theseus", "meta", "versions"));
            roots.add(Path.of(appData, ".technic", "modpacks"));
        }
        // Deliberately NOT %LOCALAPPDATA%\Packages: that is the Microsoft Store app,
        // which is Bedrock and has no Java assets at all. Walking it cost 1.8 seconds on
        // the event thread and could never have found anything.
        if (home != null) {
            roots.add(Path.of(home, ".minecraft", "versions"));                       // Linux
            roots.add(Path.of(home, "Library", "Application Support", "minecraft",    // macOS
                    "versions"));
            roots.add(Path.of(home, "curseforge", "minecraft", "Install", "versions"));
            roots.add(Path.of(home, ".local", "share", "PrismLauncher", "instances"));
            roots.add(Path.of(home, ".local", "share", "multimc", "instances"));
            roots.add(Path.of(home, "Library", "Application Support", "PrismLauncher",
                    "instances"));
        }

        List<Path> found = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var walk = Files.walk(root, 6)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(MinecraftTextures::plausible)
                        .limit(200)
                        .forEach(found::add);
            } catch (Exception ignored) {
                // An unreadable launcher directory is not worth a stack trace.
            }
            if (found.size() > 200) {
                break;
            }
        }
        // 1.16.1 first: it is the version everything else here simulates.
        found.sort(Comparator
                .comparingInt((Path p) -> p.toString().contains("1.16.1") ? 0 : 1)
                .thenComparingInt(p -> p.toString().contains("client") ? 0 : 1));
        return found.size() > 60 ? found.subList(0, 60) : found;
    }

    private static boolean plausible(Path p) {
        try {
            // Client jars are megabytes; mods and language packs mostly are not.
            long size = Files.size(p);
            return size > 3_000_000L && size < 120_000_000L;
        } catch (Exception e) {
            return false;
        }
    }
}
