# Sugar cane taller than 4

Minecraft caps sugar cane at 3 by growth and 4 by worldgen. This project searches
for **naturally generated columns of 5 or more** in Minecraft 1.16.1, by
reimplementing the relevant slice of worldgen — biomes, noise terrain, the surface
builder, cave and canyon carvers, ore blobs, disks and `patch_sugar_cane` — and
running it a few billion chunks at a time.

## It found one

**Seed `1500050556`, five tall at `91 16 65`, Minecraft 1.16.1.**

Verified on a real server and in-game. It stands on dirt at y=16..20, deep
underwater in a flooded cave in a deep lukewarm ocean about 112 blocks from spawn,
so swim or dig down to it. A three-tall column sits beside it at `90 16 65`.

It formed the only way anything over 4 can:

```
invocation 1: origin 91,64  y=16    try 6  PLACED 91,16,65 height 3   -> y=16,17,18
invocation 4: origin 91,68  y=19    try 15 PLACED 91,19,65 height 2   -> y=19,20
```

Two independent invocations of the same chunk's cane feature. The first built a
3-tall column; a later one drew y=19 — exactly its top — and stacked 2 more on it.

## Quick start

Needs Java 21+. Either grab a release jar or build it:

```
mvn package
java -jar target/sugarcane.jar
```

That prints the commands. The two worth knowing:

```
# search seeds 1.. within 96 blocks of spawn, 24 threads, report height >= 5
java -jar target/sugarcane.jar search 1 1000000 6 24 5

# look at the confirmed find and how it was built
java -jar target/sugarcane.jar inspect 1500050556 91 16 65 6

# reverse search: for 7 tall and up, this is the faster one
java -jar target/sugarcane.jar reverse 8 24
```

A find prints as:

```
HIT seed 1500050556  x=91 y=16 z=65  height 5  biome 48  chunk 5,4  spawn ~-40,8 (~144 blocks away)
```

The spawn position is the centre of that world's **spawn chunk**, so both it and
the distance are good to about ±8 blocks — enough to know whether a find is a
short swim or an expedition. It is computed only when there is something to
print, so it costs the search nothing.

The height reported is the run a **single chunk built by itself**. A column can
also be built by two chunks cooperating across a border, but only if they decorate
in one particular order, and near spawn that order is fixed by the server's spawn
pregeneration rather than by anything a player can do. Those print as
`cross-chunk` and are not hits — see FINDINGS 6v for the experiment.

### Search arguments

`search <firstSeed> <seeds> <chunkRadius> <threads> <minHeight> [mode] [flags]`

- **chunkRadius** bounds how far from spawn a find may be, in chunks. This is
  nearly free: 6 (±96 blocks) runs as fast as 32 (±512). It gets expensive below
  about 2 — see FINDINGS 5c for the measured table.
- **mode** — `diag` counts geometry, `probe:N` measures the hit probability,
  `spots` prints the coordinates of the rare terrain the search hunts for.
- **`--spawn`** centres each seed's box on that world's **spawn chunk** instead of
  0,0. A new world drops you at the spawn point, which over 300 seeds averages 196
  blocks from the origin, so this is the difference between a find near 0,0 and a
  find near where you actually arrive. It costs about 38% of the chunks per second:
  reproducing `setInitialSpawn` means sweeping a 129x129 square of quart cells, and
  spawn always sits in a land biome, so fewer of the surrounding chunks are the
  ocean this search needs (30.8 per seed against 45.0). Verified against level.dat
  from five real generated worlds.
- **`--update=<minutes>`** how often the progress line prints. Defaults to 1.
  Fractions work (`--update=0.25`), and a long run that scrolls past is usually
  better served by something like `--update=15`.

Expect very roughly **one hit per 2 hours** on 24 cores at ~19,000 chunks/s, of
which about half are 6 or taller. That rate is a projection from the measured
geometry and RNG rates, not yet a long-run observation.

### The reverse search

`reverse <minHeight> [threads] [targets] [firstSeed] [seedCount]`

`search` generates a chunk's terrain and only then discovers whether its RNG could
ever have stacked anything. At height 5 that is fine, because 61% of chunks could.
At height 8 only 3.4% could, so it throws away 29 chunks in 30. `reverse` picks the
RNG first:

1. run the cane draws with no terrain at all and keep the decoration seeds that
   could chain a tall enough column;
2. `setDecorationSeed` is affine in the chunk origin, so for any world seed a wanted
   decoration seed can be *solved* for a chunk inside the world border by lattice
   reduction — no searching;
3. generate only those chunks.

Step 1 does not depend on the world seed, so it is paid once and amortises. Only 3.4%
of decoration seeds could build 8 tall anywhere, and only 1.57% within the depth band
where real spots live, so each chunk this generates is worth 64 of the box scan's.
Measured on the same machine, both on 12 threads: 2,010 chunks/s against 8,636/s,
which after the 1/q weighting and the 88% of spots the band keeps is **13.9x**.

Candidates arrive scattered rather than in a box, so each would cost its own 3x3
neighbourhood — 7.1 chunks generated per chunk searched against the box scan's 1.57.
A fourth step removes that: a chain names an (x, z) and a base y per column, every one
of those has to be air, and below sea level air can only come from an air-step carver.
The carver walks are pure RNG, so that question needs no terrain, and it rejects
**97.9% of candidates for 49 us** — generated chunks per candidate fall to 0.15.
A fifth step does the same for soil: the chain's base needs dirt under it, and deep dirt
comes from ore blobs seeded off the same decoration seed as the cane — so that is also
answerable with no terrain, and it tightens the set another 7.1x.

Together that is roughly **560x** the box scan: an 8-tall goes from a two-month run to
about half an hour. Both terrain filters cost some coverage (12% for the depth band, 18%
for soil, from blobs that reach in from a neighbouring chunk), which is priced into that
figure.

Also structural, though it costs nothing at runtime: only 1 target in 16 is reachable
for a given world seed, because the low four bits of a decoration seed are the world
seed's own (the block coordinate is `16*cx`). Targets are bucketed by those bits and
only the matching bucket is walked.

Finds land anywhere in the world rather than near spawn — the first 8-tall ever
found is at -24848077, 21, 18720986 — so `--spawn` has no analogue here. Use
`inspect` on a hit before travelling.

## Verifying a hit

The searcher is a reimplementation, so a hit is a candidate until the real game
agrees. `tools/verify.py` builds a throwaway world on the seed, generates the
chunk with enough neighbours for it to be decorated, and reads the region file
back:

```
python tools/verify.py path/to/minecraft_server.1.16.1.jar 1500050556 91 16 65
python tools/verify.py path/to/server.jar 1500050556 91 16 65 --blocks   # when it fails
```

You supply the server jar — it is not redistributable. Any vanilla 1.16.1 server
works, and Java must be on PATH.

The cane survives the flooding that fills its cave when the chunk loads
(`FlowingFluid.canHoldFluid` refuses to spread into sugar cane), which is why a
column generated in a dry pocket is still there underwater.

## How it works, briefly

Growth stops at 3 and `ColumnPlacer` stops at 4, so 5+ needs **two placements on
the same block**. In 1.16.1 that is possible because `COUNT_HEIGHTMAP_DOUBLE` runs
the feature 10 independent times per chunk, each drawing
`y = nextInt(2 × heightmap)` — so placements land anywhere in the column, not on
the surface — and the decorator's stream is lazy, so a later invocation sees what
an earlier one built. `canSurvive` returns true immediately when the block below
is cane, so the second column needs no soil.

What it needs from the terrain is a **water face beside an air pocket, with soil
under it**, and water beside the column at the height where the second placement
starts too. In practice that means an air cave cut against an underwater carver's
water, with dirt from an ore blob beneath — which is why the search only looks at
ocean biomes, and why finds sit deep (mean soil y ≈ 23).

Measured rates: about **1.3e-3** of ocean chunks hold usable geometry, and about
**5e-6** of decoration seeds exploit a given one, so roughly **1 in 200 million
ocean chunks**.

**This does not work in 1.18+.** The placement became
`rarity_filter(6) → in_square → heightmap` with `y_spread: 0`: one placement per
chunk, pinned to the surface, so nothing can stack on anything. Read off the
shipped 1.20.1 data files. See FINDINGS 6t.

## Accuracy

The simulation is checked against chunks a real 1.16.1 server generated, saved at
`features` status — decorated but not yet flooded, which is the state the cane
feature actually saw. Full chunks are useless for this: the underwater carver's
scheduled fluid ticks turn carved air into water on load, which masks exactly the
errors that matter.

Over 4,741 real pre-flood ocean chunks, above the bedrock layer:

| | |
|---|---|
| block categories matching | 98.9% |
| simulated air that is really solid | 0.026% |
| simulated water that is really solid | 0.024% |
| simulated soil that is really not | 0.59% |

Known gaps, all documented in FINDINGS: mineshafts are not simulated (chunks
within 3 of a mineshaft start are skipped rather than searched wrongly), and
lakes, dungeons and structures are missing entirely.

## Layout

```
src/main/java/dev/drakou111/sugarcane/
  Cli.java              every entry point
  RegionSearcher.java   the box search
  ReverseSearcher.java  the reverse search: pick the RNG, solve for the chunk
  Inspect.java          dump one position, with the placement trace
  gen/                  worldgen: surface builder, carvers, ore blobs, disks, the cane feature
  rng/                  java.util.Random and Mth, bit-exact
  world/                block palette and the chunk array
  validate/             comparisons against real generated chunks
tools/                  python: verification and pre-flood chunk export
FINDINGS.md             how all of it was worked out, and everything that went wrong
```

`FINDINGS.md` is the real documentation — mechanics read off the decompiled 1.16.1
server, every measurement, and the bugs that produced eight false hits before the
first real one.

## Credits

Biome and terrain generation reuse [KaptainWutax](https://github.com/KaptainWutax)'s
BiomeUtils and TerrainUtils rather than reimplementing the layer stack. Everything
downstream — surface builder, carvers, ore blobs, disks, the cane feature — is
transcribed here from the 1.16.1 server decompiled with official Mojang mappings.
