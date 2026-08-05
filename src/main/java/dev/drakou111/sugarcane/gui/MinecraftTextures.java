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
 * Block textures read out of a Minecraft client jar already on this machine.
 *
 * <p>They are not shipped with this project. The jar is public and Mojang's texture files
 * are theirs, so the alternative — copying the PNGs into {@code src/main/resources} — would
 * be redistributing their assets to everyone who downloads a release. Reading them at
 * runtime from an install the user already has does not.
 *
 * <p>Nothing depends on finding one. {@link #block} returns null when there is no install,
 * and the caller draws its own approximation instead.
 *
 * <p>1.16.1 is preferred, since that is the version this whole project is about, but any
 * client jar carrying the textures will do.
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
            if (img != null && img.getHeight() > img.getWidth()) {
                img = img.getSubimage(0, 0, img.getWidth(), img.getWidth());
            }
        }
        CACHE.put(name, img);
        return img;
    }

    private static void find() {
        if (searched) {
            return;
        }
        searched = true;
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
        }
        if (home != null) {
            roots.add(Path.of(home, ".minecraft", "versions"));                       // Linux
            roots.add(Path.of(home, "Library", "Application Support", "minecraft",    // macOS
                    "versions"));
            roots.add(Path.of(home, "curseforge", "minecraft", "Install", "versions"));
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
