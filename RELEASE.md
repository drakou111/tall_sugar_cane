# v2.1.3 — a filter that was accepting the impossible, and a window to drive it from

## The reverse search got faster, for free

A collaborator asked whether `ChainPrefilter` checks that a continuation reads the RNG at
a later shift than the column beneath it. It did not, and that turned out to be most of
the target set.

The shift index counts how many successful placements a chunk is assumed to have made
before an invocation. Placements only accumulate, and a chain's own column is one of them,
so consecutive columns must have strictly increasing shifts. Without that check a chain
could be built with a later column reading the stream at the *same* offset as the column
it stands on — which would require placing the cane out of order.

```
height    q before     q after    tighter
7         1.571e-1     6.163e-2     2.5x
8         3.379e-2     1.109e-2     3.0x
9         2.210e-3     1.200e-4      18x
10        7.433e-4     5.000e-5      15x
```

Find rate goes as `rate / q`, so that is the speedup, and it **costs no coverage**:
everything removed is a chain no chunk could produce. Both confirmed finds pass unchanged.

Fixed in the CUDA kernel too, and a CPU-built and GPU-built target set are byte-identical
afterwards, which is how you know they have not drifted. **Target files are now version 3**
— a version 2 file is mostly impossible chains and will be rejected, so rebuild it.

## sugarcaneGUI.jar

```
java -jar sugarcaneGUI.jar
```

A tab per command — `search`, `reverse`, `targets`, `sisters`, `inspect` — over a shared
console, dark, with Run/Stop. Every run is a fresh process, which is not only so Stop works:
`sisters` and `inspect` set static filters that nothing resets, so running one and then a
search in the same JVM would silently give you a search with its filters relaxed.

Closing the window stops the search. A child process outlives its parent on Windows, so
this used to leave a search running on every core with no window to stop it from.

Block textures ship in the jar now rather than being hunted for on your machine.

## Also

- `seedCount` **0, or left off, runs until you stop it**. `search 1 0 ...` previously
  searched nothing at all.
- `--user=<name>` sets the name finds are reported under and remembers it. `--yes-report`
  never prompts, so anyone who had not done an interactive first run was Anonymous forever.
- Threads are clamped to the machine's count, out loud.
- `--sisters` and the water probe from 2.1.0 are unchanged; `--water-probe` is still opt-in
  until its retention is measured on real finds.

# v2.1.0 — the upper 16 bits, and what to do with a find the game refuses

The reverse search is about **4.7x faster**, and there is a new command for the case
that used to be a dead end: a find the simulator calls 12 and the game calls 8.

Both come from the same fact. Seeds that share their low 48 bits have the *same*
decoration seed at the same chunk, the *same* lattice solution and the *same* carver
walks — verified directly, not argued. Only the biome map differs. So the expensive
half of a candidate can be computed once and reused across 65,536 seeds, and a chain
the terrain refuses can be retried against 65,536 different terrains without touching
the RNG at all.

## The search got faster

Nothing to pass; `--sisters=64` is the default.

```
              candidates/s   biome gate   air probe   chunk    total
before              52,639        93 us       26 us   31 us   151 us
after              220,995         3 us        2 us   29 us    34 us
```

The lattice solve and the carver walk now run once per low-48 seed instead of once per
candidate, and the biome gate — which was 54% of the cost — only runs on what the air
probe kept. Flat past 64 sisters, so there is no point sweeping all 65,536: chunk
generation is 29 µs of the remaining 34 and amortises over nothing.

`--sisters=1` restores the old loop. **One thing to know:** `firstSeed` and `seedCount`
now count *low-48* seeds, and each is searched at 64 upper-16 values, so divide your
usual `seedCount` by about 64 for the same wall time.

A second, smaller win rides along: a chain names its column bases, and below sea level
the noise fills non-solid blocks with water. A carver turns stone into air but never
turns water into air, so one noise column can reject a whole chain before the ~4 ms
nine-chunk build it stands in front of. Rejects 9.9% of ocean candidates, worth 1.12x.

Together, **4.7x**.

## `sisters` — retry a find against 65,536 terrains

```
java -jar sugarcane.jar sisters <seed> <x> <y> <z> [count] [threads] [minHeight]
```

A simulated 12 that stands 8 in game lost its upper columns to terrain, and terrain is
exactly what a sister re-rolls. Run against the reported 12-tall on seed
`1000128284072` at `18846535 21 -2417559`, over the full 65,536:

```
15,031 sisters put a 12-tall at that block
16 DISTINCT local terrains among them
```

**That second line is the point.** The carvers are a low-48 property, so the carved
pocket is identical in every sister and only the noise-built terrain around it moves.
Identical terrain will disagree with the game identically, so those 15,031 hits are 16
chances, not 15,031. The command groups them and names one seed per group, and marks
the group belonging to the seed you asked about — if that one has already been checked
in game, its whole group is spent.

It is worth running on anything the game has refused. One find in a 47-seed batch went
from 8 to 10 across its sisters — but all 37,326 of those hits shared a single terrain,
which is a jackpot that is really one roll of the dice. Without the grouping you would
burn real trips discovering that.

## Frozen oceans are searched now

`FrozenOceanSurfaceBuilder` is implemented, so biomes 10 and 50 are no longer skipped.
It could not funnel into the default builder like every other supported biome: it takes
three draws before the column walk where the default takes one, and the surface RNG is
one stream shared by all 256 columns of a chunk, so a column consuming the wrong count
corrupts every column after it.

Worth about 10% more searchable chunks — 6.6% the frozen chunks themselves, and 3.4%
that were blocked by *having a frozen neighbour*, since a chunk is skipped when anything
in its 3x3 is unsupported.

Less than previously advertised, and that is a correction: FINDINGS 5c put frozen_ocean
at twice the stackable-spot rate of any other biome, on the strength of one hit in 447
chunks. Measured over a larger sample it is ordinary — 8.68e-4 and 6.13e-4 per chunk.

## `--water-probe`, opt-in, another 1.6x

A chain names the water positions `needWater` checks, as well as the air ones, and those
were never filtered. Requiring that water to come from a LIQUID-step carver rejects 62.6%
of what the air probe keeps:

```
                   candidates/s   searched chunks/s
air probe only          269,319              1,657
+ water probe           431,086              1,024
```

**Off by default, deliberately.** Unlike the air test it can lose real finds: a spot
sitting on the sea floor has fill water beside it that no carver ever touched. The
sea-floor share suggests 1.4–2%, but that number has not been measured on real finds,
and the last filter judged that way was argued at 82% retention and measured at 43.7%.
Both confirmed finds survive it, and that is now a test.

## The progress line says where you are

```
[ 5.0 min] seed 1234571893 (4003 done), candidates 812443 (2708/s), ...
```

The seed frontier, which is the resume point. Previously the only way to know how far a
run had got was to multiply elapsed time by the observed rate.

## A CUDA noise kernel, bundled but not yet wired in

On an idle card, bit-exact double-precision Perlin runs 2.3x a 24-thread CPU, and noise
is 54% of the chunk build. `cuda/noise_column.cu` is the octave, transcribed from
`ColumnPerlin` and verified against it bit for bit over 4M samples.

It ships inside the jar for **Maxwell, Pascal, Turing, Ampere and Ada**, plus PTX so
anything newer JITs — GTX 750 Ti through RTX 50xx. No toolkit, no compiler, no build
script; the jar is still the only thing you need. The CPU remains the path that must
always work: the test skips rather than fails when there is no usable GPU, and only a
device that ran and *disagreed* is a defect.

**It is not on the search path yet.** Wiring it into the terrain generator is the next
step; this release is the foundation, verified first.

## Also

- **A filter that would have discarded every confirmed find, rejected.** `--max-shift`
  gates only the chain's first column; forbidding placements interleaved between its own
  columns looks like 4.3x at height 8 and 22x at height 9. It keeps 1.09% of
  three-column chains, kills 47 of 47 reported finds and both confirmed ones. Available
  as `maxAnyShift`, defaulted off, with a test that fails if anyone turns it on.
- **Raise your target count.** A world seed reads only `buckets[seed & 15]`, so a
  1,000-target set gives ~62 usable members per seed and amortises per-seed setup badly.
  4,000 is a measured +15%, and the set is seed-independent and cached.
- `sisters` no longer counts a neighbour's find as its own. `searchRegion` returns before
  `world.reset` when nothing is searchable, so the previous sister's cane was still in the
  buffer; the count was 15% high and depended on thread scheduling. `search` and `reverse`
  were never affected — neither reads the world after `searchRegion` returns.
- FINDINGS gains 6ak (what every height from 7 to 25 costs), 6al (sister seeds, and the
  existence ceiling at ~24–25) and 6am (why the GPU measurement was wrong the first time).

# v1.2.0 — share your finds, and know how far away they are

Two people running this search separately find two disjoint sets of seeds and
neither knows about the other's. This release adds a shared spreadsheet, so a hit
on your machine shows up on everyone's. It also stops a `HIT` line from being a
bare coordinate: every result now says where that world's spawn is and how far the
cane is from it.

## Reporting finds to a shared sheet

On startup the jar now asks once:

```
Do you want to report your finds to the spreadsheet, which you can open by doing java -jar sugarcane.jar -s? (y/n):
```

Answer `y` and it asks for a username, saved to `config.properties` next to the jar
so it only asks the once. Anything other than yes is a no, and nothing leaves your
machine. Reporting is **off unless you opt in**, every run.

A find then POSTs the seed, coordinates, biome, chunk, height, spawn position and
distance to a Google Apps Script endpoint that appends a row to the sheet. Only
columns of 5 or more are sent.

Open the sheet at any time:

```
java -jar sugarcane.jar -s
```

Thanks to **chunkberries** for the reporting client, the prompt and the `-s` flag
(PRs #1 and #2).

## Results say where spawn is

```
HIT seed 1500050556  x=91 y=16 z=65  height 5  biome 48  chunk 5,4  spawn ~24,120 (~87 blocks away)
```

`cross-chunk` lines carry it too. The point is triage: a hit 87 blocks from spawn is
a swim, a hit 4,000 blocks away is an evening, and until now you had to run the
`spawn` command by hand to tell the difference.

The position is the centre of that world's **spawn chunk**, not the spawn block —
vanilla's `PlayerRespawnLogic` picks the exact block from real terrain, which this
does not generate. So both numbers are good to about ±8 blocks, hence the `~`.

This is free. Reproducing `setInitialSpawn` costs ~14 ms against the ~35 ms a whole
seed takes, which is why `--spawn` is expensive — but a result is rare enough that
computing it only when there is a line to print costs the search nothing measurable.
`--spawn` has already paid for it and the value is reused.

## `--update=<minutes>`

The progress line was hardcoded to once a minute, which is too chatty for a run you
leave overnight and too quiet for a five-minute check.

```
java -jar sugarcane.jar search 1 1000000 6 24 5 --update=15
```

Fractions work (`--update=0.25`). It is a flag, not a position, so it can go
anywhere and does not disturb the mode slot. Default is still 1 minute.

The line itself also now reports progress through the seed range, not just totals:

```
[15.0 min] seeds done ~238/1199, searched 7826 chunks (2593/s), cane 4, stacked 0, tallest 3, currentSeed 239
```

`currentSeed` is what to resume from if you stop a run.

## Known issues in the reporting path

All four are in the new spreadsheet code, and none affect the search itself:

- **"Successfully reported find to spreadsheet" is not proof.** The client checks
  only `statusCode() == 200`, and Apps Script answers a POST with a 302 that Java's
  `HttpClient` follows as a GET to a different host. That second request returns 200
  whether the row was appended, the script threw, or the deployment redirected you
  to a login page. If rows are not appearing, print `response.uri()` and the body —
  a URI on `accounts.google.com` means the deployment's access setting is not
  *Anyone*.
- **Cross-chunk finds are recorded as clean hits.** The `isCrossChunk` argument is
  passed `false` from both call sites.
- **Cross-chunk finds are usually not recorded at all**, because that branch reports
  only when the solo run is 5 or more, which cannot happen while `minHeight` is 5.
- **A find can produce duplicate rows.** One column is printed once per placement
  that lands in it, so the confirmed find on seed 1500050556 reports twice.

## Upgrading

Drop in the new `sugarcane.jar`. Search behaviour, output format aside, is unchanged
from v1.1.0 — same seeds, same hits, same speed.

One thing to watch if you script this: the opt-in prompt reads stdin on every
command, so a run with no terminal attached exits with `NoSuchElementException`.
Pipe it an answer until that is fixed:

```
echo n | java -jar sugarcane.jar search 1 1000000 6 24 5
```
