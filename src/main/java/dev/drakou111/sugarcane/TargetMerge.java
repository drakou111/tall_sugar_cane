package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.TargetCache;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Pool target sets built on different machines into one.
 *
 * <p>The set never depends on the world seed, so it is the one artefact worth sharing, and
 * since a build now starts at a random sample index two people running the same command
 * cover different ground. This is the other half of that: their files add up.
 *
 * <p>Refuses to merge sets that do not mean the same thing. Every header field except the
 * two counters decides what membership <em>is</em> -- height, invocation count, feature
 * index, depth band, soil filter, shift and column caps, slack budget -- so a mismatch is
 * not a wider set, it is two different questions. Merging them would produce a file whose
 * header describes neither.
 */
public final class TargetMerge {

    private TargetMerge() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: merge <out> <in...>");
            System.err.println("  pools target sets that were built with the same parameters");
            System.exit(2);
            return;
        }
        Path out = Path.of(args[0]);

        TargetCache.Header first = null;
        Path firstPath = null;
        java.util.LinkedHashSet<Long> pooled = new java.util.LinkedHashSet<>();
        long tested = 0;
        long sampledThrough = 0;
        int duplicates = 0;

        for (int i = 1; i < args.length; i++) {
            Path in = Path.of(args[i]);
            TargetCache.Loaded loaded = TargetCache.loadAny(in);
            if (loaded == null) {
                System.err.println(in + ": no such file");
                System.exit(2);
                return;
            }
            TargetCache.Header h = loaded.header();
            if (first == null) {
                first = h;
                firstPath = in;
            } else {
                String differs = sameQuestion(first, h);
                if (differs != null) {
                    System.err.printf("%s was built with %s, %s with a different one -- "
                                    + "these are not the same set%n", firstPath, differs, in);
                    System.exit(2);
                    return;
                }
            }
            int fresh = 0;
            for (long t : loaded.targets()) {
                if (pooled.add(t)) {
                    fresh++;
                } else {
                    duplicates++;
                }
            }
            // tested is summed because q is targets over seeds looked at, and two random
            // slices look at different seeds. Feeding the same slice twice would inflate it,
            // so a file that adds nothing is called out rather than quietly counted -- the
            // one shape of mistake this command makes easy.
            tested += h.tested();
            // A single cursor cannot describe a union of disjoint ranges, so take the
            // furthest. Resuming the merged file continues past every slice in it, which
            // re-tests nothing; it just does not go back and fill the gaps between them.
            sampledThrough = Math.max(sampledThrough, h.sampledThrough());
            System.out.printf("  %-40s %d targets, %d new, %d tested%s%n",
                    in, loaded.targets().length, fresh, h.tested(),
                    fresh == 0 && loaded.targets().length > 0
                            ? "   <- adds nothing; already pooled, and its tested count is "
                                    + "still being summed, which understates q"
                            : "");
        }

        long[] merged = new long[pooled.size()];
        int k = 0;
        for (long t : pooled) {
            merged[k++] = t;
        }
        Arrays.sort(merged);
        int unique = merged.length;

        TargetCache.Header header = new TargetCache.Header(first.minHeight(), first.count(),
                first.featureIndex(), first.baseMinY(), first.baseMaxY(), first.soilFilter(),
                first.maxBaseShift(), first.maxColumns(), first.maxSlack(),
                tested, sampledThrough);
        TargetCache.save(out, header, merged, null);

        System.out.printf("%nmerged %d files -> %s%n", args.length - 1, out);
        System.out.printf("  %d targets (%d duplicates dropped), %d seeds tested, q = %.4e%n",
                unique, duplicates, tested,
                tested == 0 ? 0.0 : unique / (double) tested);
        int[] buckets = new int[16];
        for (long t : merged) {
            buckets[(int) (t & 15L)]++;
        }
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int b : buckets) {
            min = Math.min(min, b);
            max = Math.max(max, b);
        }
        // A world seed reads one bucket in sixteen, so an uneven merge helps some seeds
        // and not others. Worth seeing rather than discovering as a slow search.
        System.out.printf("  low-4-bit buckets: %d..%d per bucket%n", min, max);
    }

    /** @return the field that differs, or null when both files answer the same question */
    private static String sameQuestion(TargetCache.Header a, TargetCache.Header b) {
        if (a.minHeight() != b.minHeight()) {
            return "height " + a.minHeight();
        }
        if (a.count() != b.count()) {
            return "invocation count " + a.count();
        }
        if (a.featureIndex() != b.featureIndex()) {
            return "feature index " + a.featureIndex();
        }
        if (a.baseMinY() != b.baseMinY() || a.baseMaxY() != b.baseMaxY()) {
            return "depth band " + a.baseMinY() + ".." + a.baseMaxY();
        }
        if (a.soilFilter() != b.soilFilter()) {
            return "the soil filter " + (a.soilFilter() ? "on" : "off");
        }
        if (a.maxBaseShift() != b.maxBaseShift()) {
            return "maximum base shift " + a.maxBaseShift();
        }
        if (a.maxColumns() != b.maxColumns()) {
            return "maximum columns " + a.maxColumns();
        }
        if (a.maxSlack() != b.maxSlack()) {
            return "slack budget " + a.maxSlack();
        }
        return null;
    }
}
