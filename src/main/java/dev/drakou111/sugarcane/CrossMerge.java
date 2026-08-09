package dev.drakou111.sugarcane;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Combines {@code crossfind} join tables built by different people.
 *
 * <p>The table does not depend on the world seed, so it is the one artefact in this project
 * several machines can build in parallel and pool — the same reason {@code merge} exists for
 * target sets. Each contributor scans their own slice of sample space (a random start, printed,
 * or one handed out), and their tables add up here.
 *
 * <p>Merging is worth more than the sum of its parts, because joins go as
 * {@code |table| x streamed}: doubling the table doubles the yield of every later run against it,
 * not just the runs that built it.
 *
 * <h2>Duplicates are dropped, and overlap is reported</h2>
 *
 * <p>Two people who scanned the same ground would otherwise contribute the same {@code (key,
 * seed)} twice, and a duplicated entry does not find anything new — it forms the same join twice,
 * inflating every count downstream while adding nothing. So entries are deduplicated exactly, and
 * the overlap between contributors' ranges is printed, because it is wasted effort somebody
 * should know about before the next round.
 */
public final class CrossMerge {

    private CrossMerge() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: crossmerge <out> <in...>");
            System.out.println("  pools crossfind join tables built on different machines.");
            System.out.println("  All inputs must be the same search: same stored side, same");
            System.out.println("  minimum height, same count and feature index.");
            return;
        }
        Path out = Path.of(args[0]);

        CrossTable.Header shape = null;
        List<CrossTable.Range> ranges = new ArrayList<>();
        List<CrossTable.EnumSweep> sweeps = new ArrayList<>();
        List<long[]> pairs = new ArrayList<>();
        long rawEntries = 0;

        for (int i = 1; i < args.length; i++) {
            Path in = Path.of(args[i]);
            CrossTable.Loaded loaded = CrossTable.loadAny(in);
            if (loaded == null) {
                System.out.printf("  %s does not exist, skipping%n", in);
                continue;
            }
            if (shape == null) {
                shape = loaded.header();
            } else if (!shape.sameShape(loaded.header())) {
                System.err.printf("%s is a different search (%s) from the first input (%s); "
                                + "pooling them would join chains that never meet%n",
                        in, loaded.header(), shape);
                return;
            }
            long dup = CrossTable.overlap(loaded.header().ranges(), ranges);
            System.out.printf("  %s: %d chains over %d range(s) covering %d samples%s%n",
                    in, loaded.keys().length, loaded.header().ranges().size(),
                    loaded.header().covered(),
                    dup > 0 ? String.format("  (%d samples overlap what is already merged)", dup)
                            : "");
            ranges.addAll(loaded.header().ranges());
            // Carried through rather than dropped: a sweep is how somebody covered their k,
            // and losing it would hand the next contributor ground already worked.
            sweeps.addAll(loaded.header().enumSweeps());
            if (!loaded.header().enumSweeps().isEmpty()) {
                System.out.printf("    plus %d enum sweep(s) over %d states%n",
                        loaded.header().enumSweeps().size(), loaded.header().enumStates());
            }
            for (int k = 0; k < loaded.keys().length; k++) {
                pairs.add(new long[] {loaded.keys()[k], loaded.seeds()[k]});
            }
            rawEntries += loaded.keys().length;
        }
        if (shape == null) {
            System.err.println("nothing to merge");
            return;
        }

        // Exact dedupe. A repeated (key, seed) forms the same join twice: no new ground, and
        // every count after it reads high.
        pairs.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
        int[] keys = new int[pairs.size()];
        long[] seeds = new long[pairs.size()];
        int n = 0;
        for (long[] pair : pairs) {
            if (n > 0 && keys[n - 1] == (int) pair[0] && seeds[n - 1] == pair[1]) {
                continue;
            }
            keys[n] = (int) pair[0];
            seeds[n] = pair[1];
            n++;
        }

        CrossTable.Header merged = new CrossTable.Header(shape.storeEndings(), shape.storedMin(),
                shape.count(), shape.featureIndex(), ranges, sweeps);
        CrossTable.save(out, merged, keys, seeds, n);
        System.out.printf("%nwrote %s: %d chains (%d before dedupe), %d range(s) covering "
                        + "%d samples%n",
                out, n, rawEntries, ranges.size(), merged.covered());
        if (rawEntries > n) {
            // Two causes, and they mean different things. Overlapping ranges are wasted effort
            // somebody should stop repeating; a seed carrying two chains that meet at the same
            // block is ordinary and happens inside a single scan too -- a 4M-sample table has
            // about 17 of them. The overlap figure above is what distinguishes them, so do not
            // read this line as evidence of duplicated work on its own.
            System.out.printf("  %d duplicate entries dropped. Overlapping ranges cause these, "
                    + "and so does one decoration seed carrying two chains that meet at the "
                    + "same block -- see the per-file overlap above to tell which%n",
                    rawEntries - n);
        }
        System.out.printf("  next unclaimed start is --sample-from=%d%n", merged.nextFrom());
        // Per shape, because lows and the y band decide what a k covers -- one number across
        // sweeps that swept differently would send somebody over ground already done.
        java.util.LinkedHashSet<String> shown = new java.util.LinkedHashSet<>();
        for (CrossTable.EnumSweep e : sweeps) {
            String line = String.format(
                    "  next unclaimed k for --enum-lows=%d --enum-y=%d:%d is --enum-from=%d",
                    e.lows(), e.minY(), e.maxY(), merged.nextEnumFrom(e));
            if (shown.add(line)) {
                System.out.println(line);
            }
        }
    }
}
