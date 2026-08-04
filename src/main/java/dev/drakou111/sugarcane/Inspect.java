package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;

/**
 * Regenerates one region and prints what the simulator thinks is at a position:
 * a vertical slice, every cane column nearby, and the water face the placement
 * depended on.
 *
 * <pre>
 * java ... Inspect &lt;seed&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [searchRadiusChunks]
 * </pre>
 *
 * <p>Two uses. Before spending two minutes on a real server, it confirms the hit
 * is what it looks like. Afterwards, if the server disagrees, the slice says
 * which block the simulator got wrong — the usual suspects being the
 * unimplemented lakes and disks, or a neighbour chunk that the real world
 * decorated in a different order.
 */
public final class Inspect {

    private static final char[] GLYPH = new char[16];

    static {
        GLYPH[Blocks.AIR] = '.';
        GLYPH[Blocks.SOLID] = '#';
        GLYPH[Blocks.WATER] = '~';
        GLYPH[Blocks.FLOWING_WATER] = '~';
        GLYPH[Blocks.SAND] = 's';
        GLYPH[Blocks.RED_SAND] = 'S';
        GLYPH[Blocks.DIRT] = 'd';
        GLYPH[Blocks.COARSE_DIRT] = 'c';
        GLYPH[Blocks.PODZOL] = 'p';
        GLYPH[Blocks.GRASS_BLOCK] = 'g';
        GLYPH[Blocks.SUGAR_CANE] = 'C';
        GLYPH[Blocks.FROSTED_ICE] = 'i';
        GLYPH[Blocks.PLANT] = ',';
        GLYPH[Blocks.GRAVEL] = 'v';
        GLYPH[Blocks.ICE] = 'I';
    }

    private Inspect() {
    }

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        int tx = Integer.parseInt(args[1]);
        int ty = Integer.parseInt(args[2]);
        int tz = Integer.parseInt(args[3]);
        int chunkX = tx >> 4, chunkZ = tz >> 4;

        int radius = args.length > 4 ? Integer.parseInt(args[4]) : 32;
        RegionSearcher.Stats stats = new RegionSearcher.Stats();
        RegionSearcher.Worker worker = new RegionSearcher.Worker(5, false, 0, stats, radius);
        RegionSearcher.traceChunkX = chunkX;
        RegionSearcher.traceChunkZ = chunkZ;
        // Centre the box on the target and drop the search-only filters, so a
        // position anywhere in the world can be looked at - not just one within
        // `radius` of the origin.
        //
        // These have to be set BEFORE prepare(), which is what reads them. Setting
        // them after left the centre at 0,0, so the box check rejected every chunk
        // and nothing was generated - which looks identical to an unsupported biome
        // and is invisible near spawn, where 0,0 is the right centre anyway.
        RegionSearcher.centreOverrideX = chunkX;
        RegionSearcher.centreOverrideZ = chunkZ;
        RegionSearcher.relaxFilters = true;
        RegionSearcher.allBiomes = true;
        worker.prepare(seed);
        // Regions are aligned to multiples of REGION starting from -radius, and the
        // searcher uses radius 32, so the grid is offset by -32.
        int originX = alignRegion(chunkX, radius);
        int originZ = alignRegion(chunkZ, radius);
        System.out.printf("seed %d, target %d,%d,%d (chunk %d,%d) in region %d,%d%n",
                seed, tx, ty, tz, chunkX, chunkZ, originX, originZ);
        worker.searchRegion(originX, originZ);

        ArrayWorld world = worker.world;
        // y=200 is air in every generated chunk, so SOLID there means this chunk was
        // never built: either it fell outside the region window, or a filter skipped
        // it (an unimplemented surface builder, or a biome with no cane feature).
        if (world.getBlock(tx, 200, tz) == Blocks.SOLID) {
            System.out.println("\nWARNING: nothing was generated at this position - "
                    + "the slice below is the out-of-window SOLID, not terrain. "
                    + "Biome " + dev.drakou111.sugarcane.gen.BiomeIds.noiseGen(
                            worker.biomeSource(), chunkX * 4 + 2, chunkZ * 4 + 2)
                    + " at the chunk centre.");
        }
        System.out.printf("%ncane columns within 6 blocks:%n");
        boolean any = false;
        for (int x = tx - 6; x <= tx + 6; x++) {
            for (int z = tz - 6; z <= tz + 6; z++) {
                for (int y = Math.max(1, ty - 8); y <= ty + 8; y++) {
                    int height = world.caneHeightAt(x, y, z);
                    if (height == 0) {
                        continue;
                    }
                    any = true;
                    System.out.printf("  height %d at %d,%d,%d standing on %s%s%n",
                            height, x, y, z, name(world.getBlock(x, y - 1, z)),
                            x == tx && y == ty && z == tz ? "   <== target" : "");
                }
            }
        }
        if (!any) {
            System.out.println("  none - the simulator does not reproduce this by itself");
        }

        System.out.printf("%nwater beside the soil at %d,%d,%d: %s%n", tx, ty - 1, tz,
                describeNeighbours(world, tx, ty - 1, tz));
        System.out.printf("water beside the block above:      %s%n",
                describeNeighbours(world, tx, ty + 1, tz));

        System.out.printf("%nslice at z=%d, x from %d to %d (y downwards)%n",
                tz, tx - 8, tx + 8);
        System.out.print("      ");
        for (int x = tx - 8; x <= tx + 8; x++) {
            System.out.print(Math.abs(x) % 10);
        }
        System.out.println();
        for (int y = Math.min(ArrayWorld.HEIGHT - 1, ty + 10); y >= Math.max(0, ty - 10); y--) {
            System.out.printf("y=%3d ", y);
            for (int x = tx - 8; x <= tx + 8; x++) {
                System.out.print(GLYPH[world.getBlock(x, y, tz)]);
            }
            System.out.println(y == ty ? "  <== target y" : "");
        }
        System.out.println("      . air  # stone  ~ water  v gravel  d dirt  s sand  "
                + "g grass  C cane");
    }

    /** The region origin the searcher would have used for this chunk. */
    private static int alignRegion(int chunk, int radius) {
        int region = RegionSearcher.regionFor(radius);
        int step = region - 2;
        int first = -radius - 1;
        return first + Math.floorDiv(chunk - first, step) * step;
    }

    private static String describeNeighbours(ArrayWorld world, int x, int y, int z) {
        StringBuilder sb = new StringBuilder();
        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        String[] labels = {"-x", "+x", "-z", "+z"};
        for (int i = 0; i < 4; i++) {
            byte b = world.getBlock(x + dirs[i][0], y, z + dirs[i][1]);
            sb.append(labels[i]).append('=').append(name(b)).append(' ');
        }
        return sb.toString();
    }

    private static String name(byte b) {
        return switch (b) {
            case Blocks.AIR -> "air";
            case Blocks.SOLID -> "stone";
            case Blocks.WATER -> "water";
            case Blocks.FLOWING_WATER -> "flowing_water";
            case Blocks.SAND -> "sand";
            case Blocks.RED_SAND -> "red_sand";
            case Blocks.DIRT -> "dirt";
            case Blocks.COARSE_DIRT -> "coarse_dirt";
            case Blocks.PODZOL -> "podzol";
            case Blocks.GRASS_BLOCK -> "grass_block";
            case Blocks.SUGAR_CANE -> "sugar_cane";
            case Blocks.GRAVEL -> "gravel";
            case Blocks.ICE -> "ice";
            case Blocks.PLANT -> "plant";
            default -> "?" + b;
        };
    }
}
