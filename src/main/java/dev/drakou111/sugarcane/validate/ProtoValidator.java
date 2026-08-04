package dev.drakou111.sugarcane.validate;

import dev.drakou111.sugarcane.gen.BiomeCaneConfig;
import dev.drakou111.sugarcane.gen.CanyonCarver;
import dev.drakou111.sugarcane.gen.Carver;
import dev.drakou111.sugarcane.gen.CarverConfig;
import dev.drakou111.sugarcane.gen.CaveCarver;
import dev.drakou111.sugarcane.gen.Disk;
import dev.drakou111.sugarcane.gen.OreBlob;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.gen.SurfaceBuilder;
import dev.drakou111.sugarcane.gen.SurfaceConfig;
import dev.drakou111.sugarcane.gen.Terrain;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.version.MCVersion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * Compares the simulated feature-time world against real chunks saved at
 * {@code features} status — the only ground truth for what the sugar cane feature
 * actually saw.
 *
 * <p>Everything else that checks the carvers compares against {@code full} chunks,
 * where the underwater carver's scheduled fluid ticks have already flooded the
 * caves, so carved air legitimately shows up as water and the comparison is
 * confounded. A proto chunk has run noise, surface, both carving steps and its own
 * decoration, and nothing since.
 *
 * <p>This matters because the cane geometry lives exactly on a carve boundary. A
 * simulator that carves 10% too much invents soil-and-air arrangements the game
 * does not have, and every hit built on one is false.
 *
 * <p>Only the interior of each chunk is scored by default: a neighbour's dirt blobs
 * and disks reach about six blocks in, and in a proto chunk the neighbours may not
 * have been decorated at all.
 */
public final class ProtoValidator {

    private static final int SEA = 63;
    private static boolean DECORATE_NEIGHBOURS = true;
    private static int MINESHAFT_RADIUS = 5;
    private static final int HEIGHT = 71;

    // Categories written by export_proto.py.
    private static final int OTHER = 0, AIR = 1, WATER = 2, STONE = 3, DIRT = 4,
            SAND = 5, GRAVEL = 6, CLAY = 7, GRASS = 8, CANE = 9, ICE = 10, LAVA = 11;

    /** The depth band the reverse search draws chain bases from (ChainPrefilter). */
    private static final int LAVA_BAND_MIN = 13, LAVA_BAND_MAX = 35;

    private static final String[] NAMES = {
            "other", "air", "water", "stone", "dirt", "sand", "gravel", "clay",
            "grass", "cane", "ice", "lava"};

    public static void main(String[] args) throws IOException {
        if (args[0].equals("spot")) {
            // Check one known-real spot: does the pipeline reproduce it? If the
            // simulated spots were a different population from the real ones, the
            // matching overall rates would be a coincidence.
            long s = Long.parseLong(args[1]);
            int x = Integer.parseInt(args[2]);
            int y = Integer.parseInt(args[3]);
            int z = Integer.parseInt(args[4]);
            OverworldBiomeSource bs = new OverworldBiomeSource(MCVersion.v1_16_1, s);
            Terrain t = new Terrain(bs);
            ArrayWorld w = simulate(t, bs, s, x >> 4, z >> 4);
            System.out.printf("seed %d, real spot at %d,%d,%d (chunk %d,%d, biome %d)%n",
                    s, x, y, z, x >> 4, z >> 4,
                    bs.getBiomeForNoiseGen((x >> 4) * 4 + 2, 0, (z >> 4) * 4 + 2).getId());
            System.out.printf("simulated spot here? %s%n", simSpot(w, x, y, z) ? "YES" : "no");
            System.out.printf("  air at y  : %s%n", w.isAir(x, y, z));
            System.out.printf("  block below: %d%n", w.getBlock(x, y - 1, z));
            System.out.printf("  canPlace  : %s%n", SugarCaneFeature.canPlace(w, x, y, z));
            for (int dy = 4; dy >= -3; dy--) {
                System.out.printf("  y=%3d  ", y + dy);
                for (int dx = -6; dx <= 6; dx++) {
                    byte b = w.getBlock(x + dx, y + dy, z);
                    System.out.print(b == Blocks.AIR ? '.' : b == Blocks.WATER ? '~'
                            : b == Blocks.DIRT ? 'd' : b == Blocks.SAND ? 's'
                            : b == Blocks.GRAVEL ? 'v' : b == Blocks.GRASS_BLOCK ? 'g'
                            : b == Blocks.CLAY ? 'l' : '#');
                }
                System.out.println(dy == 0 ? "   <== spot y" : "");
            }
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0])))
                .order(ByteOrder.LITTLE_ENDIAN);
        int margin = args.length > 1 ? Integer.parseInt(args[1]) : 6;
        // A proto chunk has run its OWN decoration; its neighbours mostly have not.
        // An ore blob's box spans x-8..x+8, so a neighbour's blob reaches 7 blocks
        // in and there is no column of the chunk safe from it. Decorating the
        // neighbours therefore adds dirt the ground truth cannot have.
        if (args.length > 3) {
            MINESHAFT_RADIUS = Integer.parseInt(args[3]);
        }
        boolean decorateNeighbours = args.length > 2 && args[2].equals("neighbours");
        DECORATE_NEIGHBOURS = decorateNeighbours;

        byte[] magic = new byte[4];
        bb.get(magic);
        if (!new String(magic).equals("PROT")) {
            throw new IOException("bad magic");
        }
        long seed = bb.getLong();
        int n = bb.getInt();
        System.out.printf("seed %d, %d proto chunks, scoring local %d..%d%n",
                seed, n, margin, 15 - margin);

        OverworldBiomeSource biomes = new OverworldBiomeSource(MCVersion.v1_16_1, seed);
        Terrain terrain = new Terrain(biomes);

        long cells = 0, agree = 0;
        long simAir = 0, simAirRealSolid = 0;
        // The error class that actually manufactures false hits. Every cane
        // placement is gated on isEmptyBlock, so simulated air where the game has
        // water invents a legal spot out of nothing, and the whole chunk's RNG
        // stream desynchronises from the first try that wrongly succeeds. Seed
        // 4531414558 was reported 5 tall and came back 2 for exactly this: air at
        // -87,25,96 that the game has as water.
        long simAirRealWater = 0, simWaterRealAir = 0;
        long simAirRealLava = 0, simWaterRealLava = 0, simWaterRealLavaInBand = 0;
        long realLava = 0, realLavaInBand = 0;
        long realAirSimSolid = 0;
        long simWater = 0, simWaterRealSolid = 0;
        long simSoil = 0, simSoilRealNot = 0, realSoilSimNot = 0;
        long oceanChunks = 0;
        long[][] confusion = new long[NAMES.length][NAMES.length];
        // What actually decides the search: a spot is a conjunction of about six
        // blocks at a carve boundary, so its error rate can be far worse than the
        // per-block rate.
        long simSpots = 0, realSpots = 0, spotsBoth = 0;
        // The measurement that matters most: does the pipeline put cane where the
        // game put cane? Spots are an intermediate; this is the end product.
        long simCane = 0, realCane = 0, caneBoth = 0;
        int falseShown = 0;
        int soilShown = 0;
        int missShown = 0;
        long mineshaftChunks = 0, missingAirNearMineshaft = 0;
        long chunksWithRealCane = 0, chunksReproduced = 0;

        for (int i = 0; i < n; i++) {
            int cx = bb.getInt(), cz = bb.getInt();
            byte[] real = new byte[HEIGHT * 256];
            bb.get(real);

            int centre = biomes.getBiomeForNoiseGen(cx * 4 + 2, 0, cz * 4 + 2).getId();
            if (!BiomeSourceValidator.isOcean(centre) || !BiomeCaneConfig.hasSugarCane(centre)) {
                continue;
            }
            // Skip anything whose neighbourhood uses a surface builder we do not
            // implement, exactly as the search does.
            boolean supported = true;
            for (int dx = -1; dx <= 1 && supported; dx++) {
                for (int dz = -1; dz <= 1 && supported; dz++) {
                    for (int lx = 0; lx < 16 && supported; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            int b = biomes.getBiome((cx + dx) * 16 + lx, 0,
                                    (cz + dz) * 16 + lz).getId();
                            if (!SurfaceConfig.supported(b)) {
                                supported = false;
                                break;
                            }
                        }
                    }
                }
            }
            if (!supported) {
                continue;
            }
            oceanChunks++;
            boolean nearMineshaft = mineshaftNear(seed, cx, cz, MINESHAFT_RADIUS);
            if (nearMineshaft) {
                mineshaftChunks++;
            }

            ArrayWorld world = simulate(terrain, biomes, seed, cx, cz);

            boolean anyRealCane = false, allMatched = true;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int y = 1; y < HEIGHT; y++) {
                        boolean sc = world.getBlock(cx * 16 + lx, y, cz * 16 + lz)
                                == Blocks.SUGAR_CANE;
                        boolean rc = cat(real, lx, y, lz) == CANE;
                        if (sc) {
                            simCane++;
                        }
                        if (rc) {
                            realCane++;
                            anyRealCane = true;
                        }
                        if (sc && rc) {
                            caneBoth++;
                        }
                        if (sc != rc) {
                            allMatched = false;
                        }
                    }
                }
            }
            if (anyRealCane) {
                chunksWithRealCane++;
                if (allMatched) {
                    chunksReproduced++;
                }
            }

            for (int lx = 1; lx < 15; lx++) {
                for (int lz = 1; lz < 15; lz++) {
                    for (int y = 12; y < HEIGHT - 4; y++) {
                        boolean sim = simSpot(world, cx * 16 + lx, y, cz * 16 + lz);
                        boolean rl = realSpot(real, lx, y, lz);
                        if (sim) {
                            simSpots++;
                            if (!rl && falseShown < 6) {
                                falseShown++;
                                dumpFalseSpot(world, real, cx, cz, lx, y, lz);
                            }
                        }
                        if (rl) {
                            realSpots++;
                        }
                        if (sim && rl) {
                            spotsBoth++;
                        }
                    }
                }
            }

            for (int lx = margin; lx <= 15 - margin; lx++) {
                for (int lz = margin; lz <= 15 - margin; lz++) {
                    // Below y=8 is the bedrock layer, which this project does not
                    // simulate: blobs there fill in where the game has bedrock, and
                    // none of it can carry sugar cane anyway.
                    for (int y = 8; y < HEIGHT; y++) {
                        int realCat = real[y * 256 + lx * 16 + lz];
                        int simCat = categorise(world.getBlock(cx * 16 + lx, y, cz * 16 + lz));
                        cells++;
                        confusion[simCat][realCat]++;
                        if (simCat == realCat) {
                            agree++;
                        }
                        boolean realSolidish = realCat == STONE || realCat == DIRT
                                || realCat == SAND || realCat == GRAVEL
                                || realCat == CLAY || realCat == GRASS;
                        if (simCat == AIR) {
                            simAir++;
                            if (realSolidish) {
                                simAirRealSolid++;
                            }
                            if (realCat == WATER) {
                                simAirRealWater++;
                            }
                            if (realCat == LAVA) {
                                simAirRealLava++;
                            }
                        }
                        if (simCat == WATER) {
                            simWater++;
                            if (realSolidish) {
                                simWaterRealSolid++;
                            }
                            if (realCat == AIR) {
                                simWaterRealAir++;
                            }
                            // Cane needs water beside its soil. Simulated water that is
                            // really lava satisfies a condition the game does not, so it
                            // invents a spot outright.
                            if (realCat == LAVA) {
                                simWaterRealLava++;
                                if (y >= LAVA_BAND_MIN && y <= LAVA_BAND_MAX) {
                                    simWaterRealLavaInBand++;
                                }
                            }
                        }
                        if (realCat == LAVA) {
                            realLava++;
                            if (y >= LAVA_BAND_MIN && y <= LAVA_BAND_MAX) {
                                realLavaInBand++;
                            }
                        }
                        if (realCat == AIR && (simCat == STONE || simCat == DIRT
                                || simCat == SAND || simCat == GRAVEL || simCat == GRASS)) {
                            realAirSimSolid++;
                            if (nearMineshaft) {
                                missingAirNearMineshaft++;
                            }
                            if (missShown < 5 && lx == 7) {
                                missShown++;
                                dumpMissingAir(world, real, cx, cz, lx, y, lz);
                            }
                        }
                        boolean simSoilBlock = simCat == DIRT || simCat == SAND || simCat == GRASS;
                        boolean realSoilBlock = realCat == DIRT || realCat == SAND
                                || realCat == GRASS;
                        if (simSoilBlock) {
                            simSoil++;
                            if (!realSoilBlock) {
                                simSoilRealNot++;
                                if (soilShown < 5 && lx >= 7 && lx <= 8) {
                                    soilShown++;
                                    dumpFalseSoil(world, real, cx, cz, lx, y, lz);
                                }
                            }
                        }
                        if (realSoilBlock && !simSoilBlock) {
                            realSoilSimNot++;
                        }
                    }
                }
            }
        }

        System.out.printf("%nocean chunks scored : %d%n", oceanChunks);
        System.out.printf("cells compared      : %d%n", cells);
        System.out.printf("exact category match: %.4f%%%n", 100.0 * agree / Math.max(1, cells));
        System.out.printf("%nthe errors that invent geometry:%n");
        System.out.printf("  simulated AIR that is really solid   : %d / %d air  (%.4f%%)%n",
                simAirRealSolid, simAir, 100.0 * simAirRealSolid / Math.max(1, simAir));
        System.out.printf("  simulated WATER that is really LAVA  : %d / %d water (%.4f%%)"
                        + ", %d of them in the y %d..%d band%n",
                simWaterRealLava, simWater, 100.0 * simWaterRealLava / Math.max(1, simWater),
                simWaterRealLavaInBand, LAVA_BAND_MIN, LAVA_BAND_MAX);
        System.out.printf("  simulated AIR that is really LAVA    : %d / %d air  (%.4f%%)%n",
                simAirRealLava, simAir, 100.0 * simAirRealLava / Math.max(1, simAir));
        System.out.printf("  (real lava cells seen: %d, of which %d in the band; the "
                        + "simulator models none above y=11)%n", realLava, realLavaInBand);
        System.out.printf("  simulated WATER that is really solid : %d / %d water (%.4f%%)%n",
                simWaterRealSolid, simWater, 100.0 * simWaterRealSolid / Math.max(1, simWater));
        System.out.printf("  simulated SOIL that is really not    : %d / %d soil  (%.4f%%)%n",
                simSoilRealNot, simSoil, 100.0 * simSoilRealNot / Math.max(1, simSoil));
        System.out.printf("%nthe errors that only lose finds:%n");
        System.out.printf("  simulated AIR that is really WATER   : %d / %d air  (%.4f%%)%n",
                simAirRealWater, simAir, 100.0 * simAirRealWater / Math.max(1, simAir));
        System.out.printf("  simulated WATER that is really AIR   : %d / %d water (%.4f%%)%n",
                simWaterRealAir, simWater, 100.0 * simWaterRealAir / Math.max(1, simWater));
        System.out.printf("  real AIR simulated as solid          : %d%n", realAirSimSolid);
        System.out.printf("  real SOIL simulated as something else: %d%n", realSoilSimNot);

        System.out.printf("%nmineshafts (not simulated; their air is written with setBlock so it never floods):%n");
        System.out.printf("  chunks within " + MINESHAFT_RADIUS + " of a mineshaft start: %d / %d  (%.1f%%)%n",
                mineshaftChunks, oceanChunks, 100.0 * mineshaftChunks / Math.max(1, oceanChunks));
        System.out.printf("  of the missing air, %d of %d cells (%.1f%%) is in those chunks%n",
                missingAirNearMineshaft, realAirSimSolid,
                100.0 * missingAirNearMineshaft / Math.max(1, realAirSimSolid));

        System.out.printf("%ncane blocks placed (the end product):%n");
        System.out.printf("  simulated %d, real %d, at the same block %d%n",
                simCane, realCane, caneBoth);
        System.out.printf("  chunks with real cane: %d, of which reproduced exactly: %d%n",
                chunksWithRealCane, chunksReproduced);

        System.out.printf("%nstackable spots (the quantity the search rate is built on):%n");
        System.out.printf("  simulated : %d%n", simSpots);
        System.out.printf("  real      : %d%n", realSpots);
        System.out.printf("  both      : %d%n", spotsBoth);
        System.out.printf("  precision : %.1f%%   recall: %.1f%%%n",
                100.0 * spotsBoth / Math.max(1, simSpots),
                100.0 * spotsBoth / Math.max(1, realSpots));

        System.out.printf("%nconfusion (rows simulated, columns real):%n");
        for (int s = 0; s < 11; s++) {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 11; r++) {
                if (confusion[s][r] > 0) {
                    sb.append(String.format(" %s=%d", NAMES[r], confusion[s][r]));
                }
            }
            if (sb.length() > 0) {
                System.out.printf("  %-6s ->%s%n", NAMES[s], sb);
            }
        }
    }

    /**
     * Prints air the real world has and the simulation does not. The shape says
     * which unimplemented feature made it: a wide blob over water is a lake, a
     * narrow horizontal run is a mineshaft corridor, a boxy room is a dungeon.
     */
    private static void dumpMissingAir(ArrayWorld world, byte[] real, int cx, int cz,
                                       int lx, int y, int lz) {
        int x = cx * 16 + lx, z = cz * 16 + lz;
        System.out.printf("%nMISSING AIR at %d,%d,%d (chunk %d,%d local %d,%d)%n",
                x, y, z, cx, cz, lx, lz);
        System.out.println("         sim              real        (x from -6 to +6)");
        for (int dy = 4; dy >= -4; dy--) {
            StringBuilder sim = new StringBuilder();
            StringBuilder rl = new StringBuilder();
            for (int dx = -6; dx <= 6; dx++) {
                sim.append(glyph(categorise(world.getBlock(x + dx, y + dy, z))));
                rl.append(glyph(cat(real, lx + dx, y + dy, lz)));
            }
            System.out.printf("  y=%3d %s   %s%s%n", y + dy, sim, rl, dy == 0 ? "  <==" : "");
        }
    }

    /** Prints simulated soil the real world does not have, with both worlds side by side. */
    private static void dumpFalseSoil(ArrayWorld world, byte[] real, int cx, int cz,
                                      int lx, int y, int lz) {
        int x = cx * 16 + lx, z = cz * 16 + lz;
        System.out.printf("%nFALSE SOIL at %d,%d,%d (chunk %d,%d local %d,%d)%n",
                x, y, z, cx, cz, lx, lz);
        System.out.println("        sim         real      (x from -4 to +4)");
        for (int dy = 2; dy >= -2; dy--) {
            StringBuilder sim = new StringBuilder();
            StringBuilder rl = new StringBuilder();
            for (int dx = -4; dx <= 4; dx++) {
                sim.append(glyph(categorise(world.getBlock(x + dx, y + dy, z))));
                rl.append(glyph(cat(real, lx + dx, y + dy, lz)));
            }
            System.out.printf("  y=%3d %s   %s%s%n", y + dy, sim, rl, dy == 0 ? "  <==" : "");
        }
    }

    /**
     * Prints a spot the simulation believes in and the real world does not, with
     * both columns side by side, so the lying block class is visible.
     */
    private static void dumpFalseSpot(ArrayWorld world, byte[] real, int cx, int cz,
                                      int lx, int y, int lz) {
        int x = cx * 16 + lx, z = cz * 16 + lz;
        System.out.printf("%nFALSE SPOT at %d,%d,%d (chunk %d,%d local %d,%d)%n",
                x, y, z, cx, cz, lx, lz);
        System.out.println("        sim              real");
        for (int dy = 3; dy >= -2; dy--) {
            StringBuilder sim = new StringBuilder();
            StringBuilder rl = new StringBuilder();
            for (int dx = -2; dx <= 2; dx++) {
                sim.append(glyph(categorise(world.getBlock(x + dx, y + dy, z))));
                rl.append(glyph(cat(real, lx + dx, y + dy, lz)));
            }
            System.out.printf("  y=%3d %s   %s   %s%n", y + dy, sim, rl,
                    dy == 0 ? "<== spot" : dy == -1 ? "<== soil" : "");
        }
        StringBuilder simZ = new StringBuilder();
        StringBuilder realZ = new StringBuilder();
        for (int dz = -2; dz <= 2; dz++) {
            simZ.append(glyph(categorise(world.getBlock(x, y - 1, z + dz))));
            realZ.append(glyph(cat(real, lx, y - 1, lz + dz)));
        }
        System.out.printf("  soil row along z: sim %s   real %s%n", simZ, realZ);
    }

    private static char glyph(int c) {
        return switch (c) {
            case AIR -> '.';
            case WATER -> '~';
            case STONE -> '#';
            case DIRT -> 'd';
            case SAND -> 's';
            case GRAVEL -> 'v';
            case CLAY -> 'l';
            case GRASS -> 'g';
            case CANE -> 'C';
            case ICE -> 'I';
            case LAVA -> 'L';
            default -> '?';
        };
    }

    /** The geometry test from FINDINGS section 6, on the simulated world. */
    private static boolean simSpot(ArrayWorld world, int x, int y, int z) {
        return SugarCaneFeature.canPlace(world, x, y, z)
                && world.isAir(x, y + 1, z) && world.isAir(x, y + 2, z)
                && SugarCaneFeature.hasWaterBeside(world, x, y + 1, z);
    }

    /** The same test on the exported categories of a real proto chunk. */
    private static boolean realSpot(byte[] real, int lx, int y, int lz) {
        if (cat(real, lx, y, lz) != AIR || cat(real, lx, y + 1, lz) != AIR
                || cat(real, lx, y + 2, lz) != AIR) {
            return false;
        }
        int below = cat(real, lx, y - 1, lz);
        if (below != DIRT && below != SAND && below != GRASS) {
            return false;
        }
        return waterBeside(real, lx, y - 1, lz) && waterBeside(real, lx, y + 1, lz);
    }

    private static boolean waterBeside(byte[] real, int lx, int y, int lz) {
        return cat(real, lx - 1, y, lz) == WATER || cat(real, lx + 1, y, lz) == WATER
                || cat(real, lx, y, lz - 1) == WATER || cat(real, lx, y, lz + 1) == WATER;
    }

    private static int cat(byte[] real, int lx, int y, int lz) {
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || y < 0 || y >= HEIGHT) {
            return OTHER;
        }
        return real[y * 256 + lx * 16 + lz];
    }

    /**
     * Whether any chunk within {@code radius} starts a mineshaft. Spacing is 1 and
     * separation 0, so every chunk is a candidate and the only gate is
     * {@code setLargeFeatureSeed(seed, cx, cz); nextDouble() < 0.004}.
     */
    private static boolean mineshaftNear(long seed, int cx, int cz, int radius) {
        JavaRandom random = new JavaRandom();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                random.setLargeFeatureSeed(seed, x, z);
                if (random.nextDouble() < 0.004) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int categorise(byte b) {
        return switch (b) {
            case Blocks.AIR -> AIR;
            case Blocks.WATER, Blocks.FLOWING_WATER -> WATER;
            case Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL -> DIRT;
            case Blocks.SAND, Blocks.RED_SAND -> SAND;
            case Blocks.GRAVEL -> GRAVEL;
            case Blocks.CLAY -> CLAY;
            case Blocks.GRASS_BLOCK -> GRASS;
            case Blocks.SUGAR_CANE -> CANE;
            case Blocks.ICE, Blocks.FROSTED_ICE -> ICE;
            case Blocks.SANDSTONE -> STONE;   // the exporter counts it as stone
            default -> STONE;
        };
    }

    /**
     * Generates the 3x3 chunk neighbourhood the same way the search does: noise and
     * surface everywhere, then the carvers per chunk, then per chunk in raster order
     * the ore blobs, the disks and the cane.
     */
    private static ArrayWorld simulate(Terrain terrain, OverworldBiomeSource biomes,
                                       long seed, int cx, int cz) {
        int minX = (cx - 1) * 16, minZ = (cz - 1) * 16;
        ArrayWorld world = new ArrayWorld(minX, minZ, 48, 48);
        byte[] column = new byte[256];
        int[] biomeMap = new int[48 * 48];
        for (int x = 0; x < 48; x++) {
            for (int z = 0; z < 48; z++) {
                biomeMap[x * 48 + z] = biomes.getBiome(minX + x, 0, minZ + z).getId();
            }
        }

        short[] surfaceStart = new short[256];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int chunkX = cx + dx, chunkZ = cz + dz;
                int originX = chunkX * 16, originZ = chunkZ * 16;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int h = terrain.column(originX + lx, originZ + lz, column);
                        world.setNoiseColumn(originX + lx, originZ + lz, column, h);
                        surfaceStart[lx * 16 + lz] = (short) (h + 1);
                    }
                }
                short[] starts = surfaceStart.clone();
                SurfaceBuilder.buildChunk(world, chunkX, chunkZ, new SurfaceBuilder.Context() {
                    @Override
                    public int surfaceStart(int x, int z) {
                        return starts[(x - originX) * 16 + (z - originZ)];
                    }

                    @Override
                    public double noise(int x, int z, int localX) {
                        return terrain.surfaceNoise(x, z, localX);
                    }

                    @Override
                    public int biome(int x, int z) {
                        return biomeMap[(x - minX) * 48 + (z - minZ)];
                    }
                });
            }
        }

        JavaRandom random = new JavaRandom();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                carve(world, biomes, random, seed, cx + dx, cz + dz, biomeMap, minX, minZ);
            }
        }
        if (DECORATE_NEIGHBOURS) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    decorate(world, biomes, terrain, random, seed, cx + dx, cz + dz);
                }
            }
        } else {
            decorate(world, biomes, terrain, random, seed, cx, cz);
        }
        return world;
    }

    private static void carve(ArrayWorld world, OverworldBiomeSource biomes, JavaRandom random,
                              long seed, int chunkX, int chunkZ, int[] biomeMap,
                              int minX, int minZ) {
        boolean ocean = BiomeSourceValidator.isOcean(
                biomes.getBiomeForNoiseGen(chunkX * 4, 0, chunkZ * 4).getId());
        Carver.Target air = new Carver.Target() {
            @Override
            public boolean canReplace(int x, int y, int z) {
                return Blocks.isCarverReplaceable(world.getBlock(x, y, z),
                        world.getBlock(x, y + 1, z));
            }

            @Override
            public boolean isGrassLike(int x, int y, int z) {
                return world.getBlock(x, y, z) == Blocks.GRASS_BLOCK;
            }

            @Override
            public void convertDirtToTopMaterial(int x, int y, int z) {
                if (world.getBlock(x, y, z) == Blocks.DIRT) {
                    int b = biomeMap[(x - minX) * 48 + (z - minZ)];
                    world.setBlock(x, y, z, SurfaceConfig.config(b).top());
                }
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return world.isWaterFluid(x, y, z);
            }

            @Override
            public boolean isAir(int x, int y, int z) {
                return world.isAir(x, y, z);
            }

            @Override
            public void setCaveAir(int x, int y, int z) {
                world.setBlock(x, y, z, Blocks.AIR);
            }

            @Override
            public void setWater(int x, int y, int z, boolean scheduleTick) {
                world.setBlock(x, y, z, Blocks.WATER);
            }
        };
        Carver.Target liquid = new Carver.Target() {
            @Override
            public boolean canReplace(int x, int y, int z) {
                byte b = world.getBlock(x, y, z);
                return b != Blocks.SUGAR_CANE && b != Blocks.ICE;
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return world.isWaterFluid(x, y, z);
            }

            @Override
            public boolean isAir(int x, int y, int z) {
                return world.isAir(x, y, z);
            }

            @Override
            public void setCaveAir(int x, int y, int z) {
                world.setBlock(x, y, z, Blocks.AIR);
            }

            @Override
            public void setWater(int x, int y, int z, boolean scheduleTick) {
                world.setBlock(x, y, z, Blocks.WATER);
            }
        };

        BitSet airMask = new BitSet(65536);
        BitSet liquidMask = new BitSet(65536);
        Carver cave = new CaveCarver(air, chunkX, chunkZ, false, SEA, airMask);
        Carver canyon = new CanyonCarver(air, chunkX, chunkZ, false, SEA, airMask);
        Carver underwaterCanyon = new CanyonCarver(liquid, chunkX, chunkZ, true, SEA, liquidMask);
        Carver underwaterCave = new CaveCarver(liquid, chunkX, chunkZ, true, SEA, liquidMask);
        float caveProbability = ocean ? CarverConfig.CAVE_OCEAN : CarverConfig.CAVE_LAND;
        int r = CarverConfig.CARVE_RADIUS;
        for (int sx = chunkX - r; sx <= chunkX + r; sx++) {
            for (int sz = chunkZ - r; sz <= chunkZ + r; sz++) {
                if (CarverConfig.isStartChunk(random, seed, 0, sx, sz, caveProbability)) {
                    cave.carve(random, sx, sz);
                }
                if (CarverConfig.isStartChunk(random, seed, 1, sx, sz, CarverConfig.CANYON)) {
                    canyon.carve(random, sx, sz);
                }
            }
        }
        if (!ocean) {
            return;
        }
        for (int sx = chunkX - r; sx <= chunkX + r; sx++) {
            for (int sz = chunkZ - r; sz <= chunkZ + r; sz++) {
                if (CarverConfig.isStartChunk(random, seed, 0, sx, sz,
                        CarverConfig.UNDERWATER_CANYON)) {
                    underwaterCanyon.carve(random, sx, sz);
                }
                if (CarverConfig.isStartChunk(random, seed, 1, sx, sz,
                        CarverConfig.UNDERWATER_CAVE)) {
                    underwaterCave.carve(random, sx, sz);
                }
            }
        }
    }

    private static void decorate(ArrayWorld world, OverworldBiomeSource biomes, Terrain terrain,
                                 JavaRandom random, long seed, int chunkX, int chunkZ) {
        long decorationSeed = random.setDecorationSeed(seed, chunkX * 16, chunkZ * 16);
        OreBlob blob = new OreBlob(new OreBlob.Target() {
            @Override
            public boolean isNaturalStone(int x, int y, int z) {
                return world.getBlock(x, y, z) == Blocks.SOLID;
            }

            @Override
            public void setDirt(int x, int y, int z) {
                world.setBlock(x, y, z, Blocks.DIRT);
            }

            @Override
            public int oceanFloorHeight(int x, int z) {
                return world.getHeightOceanFloor(x, z);
            }
        }, OreBlob.DIRT_SIZE);
        random.setFeatureSeed(decorationSeed, 0, 6);
        for (int i = 0; i < OreBlob.DIRT_COUNT; i++) {
            int x = chunkX * 16 + random.nextInt(16);
            int z = chunkZ * 16 + random.nextInt(16);
            int y = random.nextInt(256);
            blob.place(random, x, y, z);
        }

        Disk.OceanFloor floor = world::getHeightOceanFloor;
        Disk.place(world, random, decorationSeed, Disk.INDEX_SAND, Disk.SAND, chunkX, chunkZ, floor);
        Disk.place(world, random, decorationSeed, Disk.INDEX_CLAY, Disk.CLAY, chunkX, chunkZ, floor);
        Disk.place(world, random, decorationSeed, Disk.INDEX_GRAVEL, Disk.GRAVEL,
                chunkX, chunkZ, floor);

        int biome = biomes.getBiomeForNoiseGen(chunkX * 4 + 2, 0, chunkZ * 4 + 2).getId();
        if (BiomeCaneConfig.hasSugarCane(biome)) {
            SugarCaneFeature.place(world, decorationSeed, BiomeCaneConfig.index(biome),
                    BiomeCaneConfig.count(biome), chunkX, chunkZ);
        }
    }
}
