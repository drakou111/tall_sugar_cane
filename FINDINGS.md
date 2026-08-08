# Sugar cane taller than 4: mechanics, version choice, and what to search for

## FOUND: seed 1500050556, 5 tall at 91,16,65 (1.16.1)

Verified on a real 1.16.1 server, and reproducible: a **five-tall** sugar cane
about 112 blocks from spawn, standing on dirt at y=16..20, deep underwater in a
flooded cave in a deep lukewarm ocean (biome 48). A three-tall column sits beside
it at 90,16,65.

It formed exactly the way section 2 predicts — two invocations of the same chunk's
`patch_sugar_cane`, the second landing on top of the first:

```
invocation 1: origin 91,64 y=16
    try 6 PLACED 91,16,65 height 3     <- first column, y=16..18
invocation 4: origin 91,68 y=19
    try 15 PLACED 91,19,65 height 2    <- lands on the first column's top
```

Growth alone caps at 3 and worldgen at 4, so this is only reachable by stacking.

The find came immediately after the underwater carver's cursor bug (section 6r)
was fixed. Every hit reported before that fix was false, and all of them were
checked and failed; the first hit after it verified.


Everything below is read off the **1.16.1 server decompiled with official Mojang
mappings**, not from memory or the wiki. Class names are Mojang mappings.

## 1. Why 4 is the natural ceiling

`SugarCaneBlock.randomTick` counts the canes below and gates growth on `n < 3`,
so growth alone tops out at 3. Worldgen places columns of `2 + nextInt(nextInt(3) + 1)`
(`ColumnPlacer`, min 2, extra 2) → heights 2/3/4 at 11/18, 5/18, 2/18.

Anything above 4 requires **two worldgen columns stacked**.

## 2. The check that actually blocks stacking

`RandomPatchFeature.place` accepts a position when all of:

1. the target block is air (`canReplace` is false for this config);
2. `SugarCaneBlock.canSurvive` — **free for a stacked column**, since it returns
   `true` immediately when the block below is sugar cane;
3. `needWater` — water fluid in one of the four horizontal neighbours of
   `pos.below()`.

For a column stacking onto another, `pos.below()` is the **top block of the lower
column**. So the whole problem reduces to:

> water 2–4 blocks above the soil, horizontally adjacent, at the moment the
> sugar cane feature runs.

`FluidTags.WATER` matches both `water` and `flowing_water`.

Valid soil is an explicit list, not a tag: `grass_block, dirt, coarse_dirt,
podzol, sand, red_sand`. Mycelium is not included.

## 3. Version choice: 1.16.1, not 1.21+

| version | placement | can a chunk stack onto itself? |
|---|---|---|
| 1.16.1 / 1.17.1 | `COUNT_HEIGHTMAP_DOUBLE(n)`: **n independent invocations**, each drawing its own XZ and `nextInt(2 × heightmap)` for Y | **yes** |
| 1.18.2 / 1.21.x / current | `rarity_filter(6) → in_square → heightmap`: **one invocation**, Y pinned to the surface | no |

In 1.18+ all 20 tries share one surface-locked Y, so a second column can never
start on top of a first. It would take a neighbouring chunk's feature bleeding
over, and inter-chunk decoration order depends on how the world is explored, so
such a find would not be reproducible. **1.21+ is not worth searching.**

Two 1.16.1 details make it work:

- `CountHeighmapDoubleDecorator` draws `nextInt(16)` for X, `nextInt(16)` for Z,
  then `nextInt(2 * heightmap)` for Y — Y is *not* tied to the surface, so cane
  is attempted underground and high in the air too;
- the decorator returns a **lazy** `IntStream`, so invocation *n* sees blocks
  placed by invocations `< n`. Stacking happens within one chunk, deterministically.

Invocations per chunk (`BiomeDefaultFeatures`): default **10**, badlands **13**,
swamp **20**, desert **60**. Oceans and rivers are included via
`addDefaultExtraVegetation`. The feature list comes from the biome sampled at the
**chunk centre** (`ChunkGenerator.applyBiomeDecoration`).

## 4. The RNG is not the bottleneck — measured

`Main` runs the exact feature over N decoration seeds against fixed terrain.
Tallest column produced, 200k seeds:

| water column beside the soil | count=10 | count=20 | count=60 (desert) |
|---|---|---|---|
| 1 tall (ordinary shore) | **0** | **0** | **0** |
| 2 tall | **0** | **0** | **0** |
| 3 tall | 0.009% | 0.035% | 0.28% |
| 4 tall | 0.016% | 0.074% | 0.61% |
| below-sea-level pocket (6 tall face) | 0.008% | — | 0.27%, up to **8 tall** |

A 2-tall water column is not enough: the shortest first column is 2, so the face
must reach soil+2, i.e. **3 blocks minimum**.

Those rows line a whole water body with valid spots. Real terrain offers **one**
spot, which is roughly 10× worse (2M seeds each):

| single isolated spot | count=10 | count=60 (desert) |
|---|---|---|
| soil Y=52, water Y=52..56 | 0.0011% | 0.0325% |
| soil Y=62, water Y=62..65 | 0.0005% | 0.0238% |

So, per chunk that contains one stackable spot: **~1e-5** in an ordinary biome,
**~3e-4** in desert. Call this P.

## 5. Where elevated water can and cannot come from

Step order (`GenerationStep.Decoration`, and `Biome.generate` places structures
of a step before that step's features):

```
noise → surface → CARVERS(air) → LIQUID_CARVERS
  → 1 LAKES → 3 UNDERGROUND_STRUCTURES(mineshafts, dungeons)
  → 4 SURFACE_STRUCTURES(villages, ocean ruins, shipwrecks) → 5 STRONGHOLDS
  → 6 UNDERGROUND_ORES(dirt blobs) → 8 VEGETAL_DECORATION(… sugar cane … springs)
```

| source | verdict |
|---|---|
| **Noise sea fill** | Below y=63 every non-solid block is water, flat top at y=62. No vertical face on its own — but it is the only water that **never gets a scheduled fluid tick**. |
| **Water springs** | Dead, and it is the closest miss in the problem — see 6y. |
| **Lakes** | Dead. `LakeFeature` has an explicit boundary pass: it aborts if any block bordering the water half is not solid, or if anything bordering the air half is liquid. Fully sealed, flat surface. |
| **Ocean ruins / shipwrecks** | Dead. Both use `BlockIgnoreProcessor.STRUCTURE_AND_AIR`, which strips air from the template, so they stay water-filled. |
| **Underwater carvers** | Produces the geometry but **self-destructs** — see below. |
| **Structures that keep air** | **The viable path.** |

### Underwater carvers flood the cave — but the cane survives

`UnderwaterCaveWorldCarver.carveBlock` places water and, when a horizontal
neighbour is air or outside the chunk, also calls
`chunkAccess.getLiquidTicks().scheduleTick(pos, WATER, 0)`. Those scheduled ticks
are carried into the `LevelChunk` and run when the chunk goes full, i.e. **after**
features, so the cave does flood.

**Correction.** An earlier version of this file said the carver "stops at an
existing air cave" because `replaceableBlocks` excludes air. That is true of the
base `WorldCarver`, but `UnderwaterCaveWorldCarver` overrides
`replaceableBlocks` to *include* `AIR` and `CAVE_AIR` (and `WATER`), and also
overrides `hasWater` to return `false`. So it happily overwrites air with water.
The water face beside air arises from the **carve volume boundary** — blocks
inside the underwater tunnel become water, blocks outside it stay air — not from
the carver refusing to enter air. The observed geometry and every rate in this
document are unaffected; only the stated reason was wrong.

**The cane is not destroyed.** `FlowingFluid.canHoldFluid` refuses to spread into
sugar cane explicitly:

```java
if (block instanceof DoorBlock || block.is(BlockTags.SIGNS) || block == Blocks.LADDER
    || block == Blocks.SUGAR_CANE || block == Blocks.BUBBLE_COLUMN) {
    return false;
}
```

`canSurvive` also still holds after flooding — the soil is untouched and water is
adjacent. So a column placed at generation time stands in the flooded cave and is
fully visible. Underwater carvers are therefore a **live mechanism**, and by far
the most common source of tall water faces.

### Consequence: never measure the geometry on fully generated chunks

Because the flood happens after features, a saved **full** chunk no longer shows
the water/air boundary the cane feature saw. Chunks saved at status `features` /
`light` / `spawn` / `heightmaps` have had all features applied but have **not**
run their fluid ticks, because those only execute on promotion to a
`LevelChunk`. Those proto-chunks are the correct sample.

Measured on seed 1:

| sample | chunks | stackable spots | rate |
|---|---|---|---|
| full (post-flood) | 83,718 | 24 | 2.9e-4 |
| **proto (feature-time)** | 35,490 | **14** | **3.9e-4** |

**R ≈ 4e-4 per chunk**, measured on proto-chunks.

### Only proto-chunks give a trustworthy rate

Measured across two independently generated worlds:

| world | proto chunks | hits | rate | full chunks | hits | rate |
|---|---|---|---|---|---|---|
| srv | 1,643 | 1 | 6.1e-4 | 80,614 | **0** | 0 |
| srv2 | 35,490 | 14 | 3.9e-4 | 83,718 | **24** | 2.9e-4 |

The proto rates agree (6.1e-4 vs 3.9e-4). The full rates do not agree at all —
zero versus 24 on samples of the same size. That is not Poisson noise.

The likely cause is that flooding depends on how the world was pregenerated.
Fluid spreading across a chunk border needs the neighbour loaded, so a world
built from large contiguous forceload batches (srv) floods thoroughly, while one
built from small isolated loads (srv2) floods only partially and leaves
carver-cut water faces intact in chunks that are nonetheless marked `full`.

**Consequence: never measure R on full chunks.** An earlier claim in this file
that full chunks are "a usable proxy within ~1.4x" was wrong — the full-chunk
figure is an artefact of the pregeneration pattern. Earlier claims of a 30x and
then a 2x proto/full gap were also wrong, both computed from single-digit event
counts. Use proto-chunks, and quote no ratio from fewer than ~10 events.

To mass-produce proto-chunks, forceload *isolated* single chunks with a stride of
16+ chunks. At stride 8 with 40 forceloads in flight the halos merge and every
would-be `features` chunk gets promoted to `full` — a 3000-chunk run at stride 8
produced **zero** proto-chunks.

### Structures that keep air are the target

`ProtoChunk.setBlockState` and `WorldGenRegion.setBlock` schedule **no** fluid
ticks and perform no neighbour updates. So an air block written next to
noise-generated water leaves a **permanently static** water face — it survives
chunk load and stays dry.

Structures using `BlockIgnoreProcessor.STRUCTURE_BLOCK` (keeps air):

- `SinglePoolElement` / `LegacySinglePoolElement` → **jigsaw villages**, pillager outposts
- `IglooPieces`
- `WoodlandMansionPieces`

**Search target:** a village (or igloo / mansion) piece writing air below sea
level beside a body of water at least 3 blocks deep, with natural sand or dirt
left as the pocket's floor and water reaching down to that soil level.

Best case is a **desert village on a river or ocean shore** — desert gives 60
invocations per chunk, and a deep face allows columns up to 8.

## 5b. Two mechanisms that survive chunk load

Both need a structure that writes air (`STRUCTURE_BLOCK` processor) to cut into
water that was placed without a fluid tick:

- **structure air ∩ sea fill** — an air pocket below y=63 beside ocean or river
  water. The face is as tall as the water is deep.
- **structure air ∩ lake** — `LakeFeature` fills its bottom **4 layers** with
  water via `setBlock`, so a breached lake gives a 4-tall static face, and the
  lake's own post-pass converts the rim dirt to grass_block, supplying the soil.

Neither is tick-scheduled, so unlike the carver case the pocket stays dry.

Ruled out along the way: village well and fountain templates keep their water
fully sealed in cobblestone (all 10 water-bearing templates in the game show
zero water blocks adjacent to template air). `plains_fountain_01` does have a
3-tall water column whose top two blocks border unwritten positions, but its
pool is ringed by cobblestone at every level, so no soil can ever sit beside it.

## 5c. Feasibility with the measured rate

**Search oceans, not deserts.** Tallying stackable spots by the chunk's centre
biome (38 hits over seed 1):

| biome | chunks | hits | R |
|---|---|---|---|
| frozen_ocean (10) | 447 | 1 | 2.2e-3 |
| cold_ocean (46) | 4,494 | 9 | 2.0e-3 |
| lukewarm_ocean (45) | 4,971 | 7 | 1.4e-3 |
| deep_ocean (24) | 7,174 | 8 | 1.1e-3 |
| deep_cold_ocean (49) | 4,019 | 4 | 1.0e-3 |
| ocean (0) | 8,516 | 7 | 8.2e-4 |
| warm_ocean (44) | 3,349 | 2 | 6.0e-4 |
| **every land biome combined** | ~25,000 | **0** | **< 1.2e-4** |

Every hit is in an ocean. This is the underwater carver mechanism confirmed from
the other direction: `addOceanCarvers` registers `UNDERWATER_CAVE` and
`UNDERWATER_CANYON` only for ocean biomes, and they are what cuts a tall water
face against an air cave. Land biomes have no such carver, so the geometry
essentially never forms there.

Consequently **desert targeting is worthless**: desert's 60 invocations buy a 30x
better P, but R there is ~0, so the product is ~0. The right target is ocean,
where R ~ 1.1e-3 is about 3x the global average and P is the ordinary 1.1e-5.

| target | R | P | rate per chunk | ocean chunks needed |
|---|---|---|---|---|
| ocean (count=10), projected here | ~1.1e-3 | ~1.1e-5 | ~1.2e-8 | ~8e7 |
| ocean (count=10), **measured in 6i** | 1.32e-3 | 5.40e-6 | 7.1e-9 | 1.4e8 |

Oceans are roughly a third of the overworld, so ~2.5e8 chunks generated overall —
except that a biome-only prefilter (no terrain needed) can skip non-ocean chunks
cheaply, which is a large constant-factor win for the searcher.

### Constraining the find to near spawn is nearly free (measured)

Total chunks examined is what costs; *where* they come from does not. Requiring
the result near (0,0) just restructures the loop from "one seed, 1e8 chunks" to
"1e5 seeds x 1024 chunks each" (a 32x32 chunk box around spawn). Same total work,
and seeds are embarrassingly parallel, so it is if anything easier to distribute.

This only hurts with the vanilla-server prototype, where each seed needs a fresh
world and a ~10s restart. With a custom generator, per-seed cost is just noise
initialisation, amortised over the 1024 chunks.

Measured with `RegionSearcher` on 24 threads, all on an otherwise idle machine:

| chunk radius | distance from 0,0 | searched chunks/s | expected time to a find |
|---|---|---|---|
| 32 | +/-512 blocks | 10,650 | 3.6 h |
| 6 | +/-96 blocks | 10,979 | 3.6 h |
| 4 | +/-64 blocks | 9,805 | 4.0 h |
| 2 | +/-32 blocks | 7,986 | 4.9 h |
| 1 | +/-16 blocks | 5,714 | 6.8 h |
| 0 | chunk 0,0 only | 1,684 | 23 h |

So it is free down to about +/-96 blocks and cheap down to +/-32, then falls off a
cliff. The cliff is the eight-neighbour requirement: one searched chunk needs its
3x3 neighbourhood generated, so when the box is only a few chunks across almost
everything generated is neighbourhood rather than result. At radius 0 that is nine
generated chunks for one searched.

(An earlier reading of 78% was taken while another 24-thread search was still
running and is wrong; measure throughput on an idle machine.)

Two small costs do exist and are evidently absorbed:

- **the neighbourhood overhead is worse in a small box.** A searched chunk needs
  its eight neighbours generated; in a 32-chunk box that costs 1.43 generated
  chunks per searched chunk, in a 6-chunk box 1.58;
- **per-seed setup stops being amortised.** Each seed builds a biome source and
  four octave noise samplers, spread over ~38 searchable chunks instead of ~1400.

The region side adapts to the box (`regionFor`): it has to be at least
`2 * radius + 3` so the whole box lands in the region interior, since border
chunks are never searched. Chunks outside the radius are skipped even when the
region covers them, so the reported find is genuinely bounded.

## 6. The open question, now answered

The remaining unknown was purely empirical: **how often does that pocket geometry
occur?** Call it R per chunk.

Both R and P are now measured on generated terrain rather than estimated — see
section 6i. R = 1.32e-3 per ocean chunk, P = 5.40e-6, so a >4 column costs about
**1.4e8 ocean chunks**, or four hours at 10,700 chunks/s.

The geometry test used throughout is: soil at y-1, air at y and y+1 and y+2,
water beside (x, y-1, z) **and** beside (x, y+1, z).

## 6b. The simulation is validated against the real game

`RealWorldValidator` replays `patch_sugar_cane` over terrain exported from
chunks a real 1.16.1 server generated, and asks whether it places exactly the
cane the game placed. The biome's invocation count and the feature's index are
**not** hard-coded — they are recovered by trying every plausible pair.

Result on seed 1, 105 testable chunks: **54 reproduced exactly (51.4%)**,
including 4 chunks with 9 interior cane blocks and one with 11. Reproducing the
exact x/y/z of 11 blocks by luck is impossible; a wrong RNG order scores zero.

The recovered parameters match the decompiled source independently:

| biome | recovered | source |
|---|---|---|
| 2, 17 (desert) | count 60, index 5 | `addDesertExtraVegetation` count 60 |
| 6 (swamp) | count 20, index 9 | `addSwampExtraVegetation` count 20 |

**Why the other ~49% cannot match.** Placements use a ±4 offset, so ~44% of the
placement window lies outside the chunk, in neighbours that had not been
decorated yet when this chunk's cane ran. A saved world shows those neighbours
*fully* decorated. One differing success/failure anywhere in the chunk desyncs
the RNG (`ColumnPlacer` only draws its 2 values on success) and every later
placement diverges. This is inherent to validating against saved worlds, not a
model defect — and it does not affect the searcher, which generates the world in
the correct order itself.

Two hypotheses tested and rejected along the way: spring contamination (clean
chunks scored 54.9% vs 51.4% overall — no real difference) and the heightmap
plant bug (49.5% -> 51.4%). The plant fix was still correct and is kept:
`grass`, `tall_grass` and flowers are not motion-blocking, so they must not
raise the MOTION_BLOCKING heightmap that `nextInt(2 * height)` samples. See
`Blocks.PLANT`.

## 6c. Biome source: reused and verified (the ocean prefilter)

Since only ocean chunks can produce the geometry, the searcher can reject ~2/3 of
chunks using biomes alone, before touching any terrain code. That prefilter uses
KaptainWutax's `BiomeUtils` rather than a fresh implementation of the ~20-layer
1.16.1 stack.

`BiomeSourceValidator` checks it against the `Biomes` arrays of real generated
chunks: **320,000 / 320,000 cells agree over 20,000 chunks on seed 1**.

Two traps worth recording:

- Query `getBiomeForNoiseGen(quartX, 0, quartZ)`, **not** `getBiome(blockX, ...)`.
  A chunk's stored `Biomes[]` holds noise biomes at quart resolution; `getBiome`
  additionally applies the Voronoi fuzzing the game only uses for per-block
  runtime queries. Using it scores 93.5%, failing exactly at biome boundaries
  (deep_ocean<->ocean, beach<->river, frozen_river<->snowy_tundra).
- The JitPack dependency versions are fragile. `BiomeUtils:1.0.0` declares
  `MCUtils:11e3c708...`, which does not build on JitPack; the working
  combination is `MCUtils:1e5785a6...` with `NoiseUtils:288e1b60...` (the
  NoiseUtils that 1.0.0 was compiled against — pairing it with the version from
  the master branch throws `NoSuchMethodError` at runtime). `BiomeSourceTest`
  pins known-good values so a version change cannot break this silently.

## 6d. Terrain generator: reused, NOT yet fully verified

`TerrainUtils` (`OverworldTerrainGenerator`) supplies the noise terrain and
surface builder. `TerrainValidator` compares `getHeightOnGround` against the
`OCEAN_FLOOR` heightmap of real chunks, over 3,000 chunks on seed 1:

| ocean chunks | share |
|---|---|
| exact match | 96.02% |
| real lower than generated (carved - expected) | 2.78% |
| real higher than generated (**unexplained**) | 1.21% |

Carving explains the "lower" side: underwater carvers cut the sea floor after
the noise stage. The "higher" side does not yet have a confirmed cause. Coral
and icebergs were the obvious candidates, but restricting to plain oceans
(no warm, no frozen) leaves the number completely unchanged, so that is ruled
out. Ocean ruins, shipwrecks and monuments add blocks above the floor and remain
plausible, but 1.2% of all columns feels high for structures alone.

**Do not treat the terrain layer as verified until this is closed.** A 1%
systematic height error would shift the `nextInt(2 * heightmap)` draw and
desynchronise the cane RNG exactly as the grass/heightmap bug did.

The clean way to settle it: the `_WG` heightmaps are computed at the `noise`
status, before carvers and features, and would isolate the noise terrain
perfectly - but they are **not saved for chunks written as `full`**. Only
proto-chunks from `noise` onward carry them, and the pregen used so far forces
almost everything to `full`. A pregen tuned to leave many chunks at `noise` or
`surface` status would give a contamination-free comparison.

Note also `getHeightInGround` is not the counterpart of `WORLD_SURFACE`
(0.9% agreement); over ocean `WORLD_SURFACE` is just sea level, since it counts
fluids.

## 6e. Carvers: seeding and start-chunk selection (implemented)

No community library covers carvers, so this one is written here.
`ChunkGenerator.applyCarvers`, for the chunk being generated:

```
for startX in chunkX-8 .. chunkX+8:
  for startZ in chunkZ-8 .. chunkZ+8:
    for carverIndex, carver in biome.getCarvers(step):
      random.setLargeFeatureSeed(levelSeed + carverIndex, startX, startZ)
      if random.nextFloat() <= carver.probability:
          carver.carve(...)
```

Three things to get right:

- the carver list comes from the biome at the **generating** chunk's corner,
  `getNoiseBiome(chunkX << 2, 0, chunkZ << 2)` — not the start chunk's biome;
- the salt is the carver's index in that biome's list **for that step**, so AIR
  carvers use 0 and 1 and LIQUID carvers restart at 0;
- 289 candidate start chunks per chunk, so expected starts = 289 * probability.

Probabilities (`BiomeDefaultFeatures`): cave 0.14285715 on land but 0.06666667
in ocean, canyon 0.02, underwater canyon 0.02, underwater cave 0.06666667.
`CarverConfig` holds these; `CarverConfigTest` checks the seeding against
`java.util.Random` and that observed start-chunk counts track 289 * p.

### Cave carver geometry (implemented, not yet validated)

`CaveCarver` transcribes `CaveWorldCarver`: cave count
`nextInt(nextInt(nextInt(15)+1)+1)`, a 1-in-4 chance of a room plus
`nextInt(4)` extra branches, then per branch a tunnel of
`112 - nextInt(112/4)` steps of `carveSphere`.

Traps found while transcribing:

- `getRange()` is **4**, not 8. The driver's start-chunk radius is 8, but the
  tunnel length comes from the range: `(4 * 2 - 1) * 16 = 112`. The two look
  interchangeable and are not.
- `carveSphere` seeds its RNG with `tunnelSeed + chunkX + chunkZ`, i.e. per
  chunk, not per tunnel step.
- `skip()` is `dy <= -0.7 || dx^2 + dy^2 + dz^2 >= 1.0` — the -0.7 gives caves
  their flat floors.
- `hasWater` is a **shell** test, not a volume test: interior columns only check
  floor and ceiling (the `y = y1` jump at the end of the loop).
- The y loop runs downward, `for (y = y1; y > y0; y--)`, and the block sampled is
  `y - 0.5`, not `y + 0.5` as for x and z.

`CaveCarverTest` covers determinism, the water guard aborting every sphere, and
that no air is cut below y=11 (lava) or above genHeight-8.

**These are self-consistency tests only.** Nothing yet checks that the carved
*shape* matches the real game. Float-vs-double and `Mth.sin/cos` table lookups
(float-precision in vanilla, `Math` here) can move a block boundary. Validate by
comparing carved air below sea level in ocean chunks against real chunks before
the search depends on it.

**Since done:** the underwater cave carver, both canyon carvers and the dirt
blobs. Lakes remain unimplemented, and section 5 argues they are dead for this
purpose anyway — a lake aborts if anything bordering its water half is not solid,
so it can never form beside a cave.

## 6f. Where the build stands

| subsystem | state |
|---|---|
| biome source / ocean prefilter | **verified exact** — 320,000/320,000 cells |
| terrain noise (`TerrainUtils`) | 96.0% on ocean floors; 1.2% unexplained, accepted |
| terrain noise, truncated fast path (`TruncatedNoise`) | **verified exact** below y=104 against TerrainUtils itself |
| surface builder (`SurfaceBuilder`, `SurfaceConfig`) | implemented; confirmed end-to-end (cane standing on generated sand and grass_block at the exact predicted blocks) |
| carver driver + start chunks (`CarverConfig`) | implemented, seeding tested |
| cave carver (`CaveCarver`) | **validated** - 90.4% precision vs real ocean chunks |
| underwater cave carver | implemented; the water face it leaves is confirmed against a real chunk (6k) |
| canyon + underwater canyon (`CanyonCarver`) | implemented; raises the spot rate 4.4x; the void it cuts is confirmed against a real chunk (6k) |
| dirt blobs (`OreBlob`) | implemented; feature index confirmed to be 0, and a predicted blob found at the exact block in a real world (6k) |
| sugar cane feature | **verified** against the real game |
| lakes, sand/clay/gravel disks, structures | not implemented |
| **end-to-end search** (`RegionSearcher`) | 11,400 searched chunks/s on 24 threads |

### Carver validation result

`CarverValidator` runs the cave carver over real ocean chunks and asks how much
of what it carves is genuinely air in the saved world:

```
ocean chunks scored : 74
blocks carved       : 15384
also air in reality : 13912
PRECISION           : 90.43%
```

Sub-sea air is a small fraction of an ocean chunk's volume, so a wrong tunnel
walk would score near zero. 90.4% says the walk, the RNG order and the sphere
geometry are substantially right.

The missing ~10% has three known causes, none of which imply a bug:

- the **underwater carver runs after** the air carvers and its
  `replaceableBlocks` includes `AIR`, so some genuinely-carved air is water in
  the final chunk;
- **flooding**: fluid ticks from the underwater carver flood adjoining caves
  once the chunk goes full, turning carved air into water in the saved data;
- lakes, disks and ocean structures fill or replace blocks later.

Measured as precision, not recall, because the canyon carver is not implemented
and so the carved set is necessarily a subset of the real air.

### FIXED: TerrainUtils supplies no surface blocks

`OverworldTerrainGenerator.getColumnAt` returns **raw noise terrain only** -
every solid block comes back as `stone`. There is no grass_block, dirt, sand or
gravel anywhere. Despite the class extending `SurfaceGenerator`, that name refers
to the noise *surface* (the density field), not Minecraft's block-palette surface
builder, and the library has no equivalent of it — checked by decompiling the
jar, there is no `buildSurface` pass to call.

That is why the first integrated search produced almost nothing: over 72,000
ocean chunks it found 401 legal cane positions and one column. Cane needs
`grass_block/dirt/coarse_dirt/podzol/sand/red_sand` beneath it, and none of those
existed.

`SurfaceBuilder` now implements `ChunkGenerator.buildSurfaceAndBedrock` plus
`DefaultSurfaceBuilder`. Legal positions went from 0.0055 to **0.19 per ocean
chunk** (35x), and stackable spots appeared at all.

Three things had to be right:

- the RNG is seeded once per chunk with `setBaseChunkSeed(chunkX, chunkZ)` and
  then **shared by all 256 columns**, visited x-outer, z-inner. One column
  drawing the wrong number of values corrupts every column after it. The only
  draws are one `nextDouble` per column for `depth`, plus a `nextInt(4)` when a
  sand band turns to sandstone;
- the surface noise is
  `surfaceDepthNoise.getSurfaceNoiseValue(x/16, z/16, ...) * 15`. It is an
  *octave simplex* sampler for the overworld (`simplex_surface_noise = true` in
  the overworld preset) built between the main noise and the depth noise from the
  same RNG, so it cannot be reconstructed independently — `Terrain` reads it out
  of the generator by reflection;
- water neither resets the descent nor gets written over: only blocks equal to
  the default block (stone) are touched, and `blockState.isAir()` is what resets
  the run.

The result on an ocean floor: **one gravel block** over stone where
`y < 63 - 7 - depth`, and a **dirt band** of `depth+1` blocks where the floor is
shallower than that. In warm and lukewarm oceans the deep floor is sand instead
(`CONFIG_FULL_SAND` / `CONFIG_OCEAN_SAND`), which *is* cane soil.

### Surface builders that are deliberately not implemented

The chunk RNG is shared across columns, so a biome whose builder consumes a
different number of draws cannot be approximated — it would desynchronise the
whole chunk. `SurfaceConfig.supported()` marks those, and the searcher skips any
chunk whose 3x3 neighbourhood contains one:

| biome | why |
|---|---|
| frozen_ocean, deep_frozen_ocean | `FrozenOceanSurfaceBuilder`: iceberg noise plus `nextInt(4)`, `nextInt(10)` and a `nextDouble` per iterated block |
| badlands, wooded/eroded/modified variants | clay bands |
| swamp, swamp_hills | consumes the RNG *identically*, but writes a water block at y=62 from `Biome.BIOME_INFO_NOISE`, which is not implemented. Approximating it would leave soil where the game has water, i.e. invent placements |

Everything else funnels into `DefaultSurfaceBuilder`: `MOUNTAIN`,
`GRAVELLY_MOUNTAIN`, `GIANT_TREE_TAIGA` and `SHATTERED_SAVANNA` only pick a
different configuration from the noise value and draw exactly the same values.

Temperature matters only for the ice-instead-of-water branch, which fires below
sea level, and `getHeightAdjustedTemperature` only perturbs the value above y=64
— so the flat base temperature is the whole story. No 1.16.1 biome uses a
`TemperatureModifier`.

## 6g. Carver corrections found while wiring the surface in

Once the terrain had real blocks instead of stone, three things in the carvers
turned out to matter:

- **`canReplaceBlock` takes two blocks, not one.** The AIR-step carvers use
  `canReplaceBlock(state, above)`, and sand and gravel are only replaceable when
  the block above holds no water. So the single gravel block on an ocean floor is
  **never carved** by the cave or canyon carver — it is protected by the water
  sitting on it. Treating it as ordinary stone opened caves the game does not
  have. The underwater carvers use the one-argument form and their own much wider
  set, so they cut straight through it;
- **grass follows the cave.** `carveBlock` remembers whether it has passed a
  grass_block or mycelium while descending a column, and if so converts dirt
  under the carved block to the biome's top material. That is a source of cane
  soil, so leaving it out only loses finds — but it is cheap and now implemented;
- **the underwater carver draws a `nextFloat` at y=10** to choose between magma
  and obsidian. Skipping it desynchronises the rest of that sphere.

Two driver details were also wrong:

- both carvers of a generation step **share one carving mask**
  (`getOrCreateCarvingMask(step)`), so whichever reaches a block first owns it;
- and the iteration order is **start chunks outer, the biome's carver list
  inner** — not one carver at a time over all start chunks. With a shared mask
  the order changes the result.

## 6h. Canyons are worth 4.4x

`CanyonWorldCarver` has only a 2% start probability against the cave carver's
6.7%, but a canyon is a long high-walled cut rather than a tube, so it exposes
far more vertical face. Adding it and `UnderwaterCanyonWorldCarver`:

| | stackable spots per ocean chunk |
|---|---|
| caves only | 4.4e-4 |
| caves + canyons | **2.1e-3** |

Differences from the cave carver that are easy to miss: one canyon per start
chunk with no count loop; vertical scale 3; two extra `nextFloat` draws per step
scaling both radii by `0.75..1.0`; and `skip` uses a per-canyon width table
indexed by **absolute block y minus one**, filled by 256 iterations of RNG before
the walk starts.

## 6i. The cost of a find, measured rather than estimated

`RegionSearcher probe:<n>` replays `patch_sugar_cane` over many synthetic
decoration seeds on every chunk that offers a stackable spot. That measures P on
terrain the generator actually produces, instead of on a hand-built pocket.

Over 563,626 searched ocean chunks (seeds 700001..700400):

| quantity | value |
|---|---|
| R, chunks with at least one stackable spot | **1.32e-3** |
| stackable spots per chunk | 2.10e-3 |
| P, per decoration seed, given a spot (743 chunks x 100,000 seeds, 401 hits) | **5.40e-6** |
| product | **7.1e-9 per chunk** |
| chunks per expected find | **1.4e8** |

Two things to note. R now agrees with the 1.1e-3 measured directly on real ocean
chunks (section 5c), which is the strongest evidence yet that the pipeline
produces the same geometry the game does — before the canyons it was 3.5x low.
And the earlier P estimate of 1.1e-5, taken from a hand-built isolated spot, was
**2x optimistic**; the real number is 5.4e-6.

The spots are deep: mean soil y **23.3**, and 98% of them are inside the terrain
rather than on the sea floor surface. That matches the one confirmed real-world
spot at 499163/24/518311, which sits on ore-blob dirt at y=23 — and it means the
unimplemented sand/clay/gravel disks barely matter, since a disk only ever
touches the top solid block of a column.

## 6j. Making it fast enough

Two changes took the search from 5,500 to **10,700 searched chunks/s** on 24
threads, i.e. an expected find in under four hours:

- **regions instead of per-chunk windows.** A chunk can only be judged once its
  eight neighbours are surfaced and carved, so the old code rebuilt a 24x24
  window per chunk — 2.25 columns of work per column of result, and the margin
  was never carved at all. `RegionSearcher` generates a 32x32-chunk region, runs
  the carvers per chunk inside it (each writes only into its own chunk, so this
  is exact), then decorates the interior chunks in raster order;
- **biome depth and scale are memoised per noise cell.** `getDepthAndScale`
  queries the 5x5 cell neighbourhood, so neighbouring cells re-ask the biome
  layer stack for the same values; that alone was 15% of runtime after the noise
  cut;
- **the density noise stops at y=104.** Profiling put 78% of all time in
  `sampleNoiseColumn`: 33 cell values per column, 40 octaves of Perlin each.
  Nothing the search looks at is above y=104, so `TruncatedNoise` evaluates 13 of
  33 cells — a 2.2x end-to-end speedup. It is a bounded transcription of
  TerrainUtils' own code using the generator's own samplers, `TruncatedNoiseTest`
  asserts every block below the cut is identical, and a column whose terrain
  reaches the cut falls back to the full generator.

A third change bought 33% more searched chunks out of the same generated
regions: **every ocean biome except frozen and deep frozen is searched**, not
just the four plain ones.

The worry about warm and lukewarm oceans was coral — it is motion-blocking, so it
would perturb the MOTION_BLOCKING heightmap that `nextInt(2 * height)` samples.
Reading the biome constructors settles it: the coral and seagrass features are
registered *after* `addDefaultExtraVegetation`, so they run after the sugar cane
and cannot touch its RNG. Nothing registered before the cane in any ocean biome
places a motion-blocking block above sea level, so the heightmap is exactly 63
and the draws are reproducible. Measured spot rate in the wider set is 2.15e-3
against 2.10e-3 for the plain four — the same geometry, just more of it.

Frozen oceans stay out because their surface builder is not implemented.

Warm and lukewarm ocean floors are also *safer* than plain ones: their deep floor
is sand rather than gravel, and the unimplemented disks only replace dirt and
clay, so they cannot degrade a sand floor.

## 6k. End-to-end confirmation against the real game

`verify_hit.py` builds a throwaway 1.16.1 server on a reported seed, forceloads a
5x5 chunk block around the target so the chunk actually runs its features, then
reads the saved region file. Run against ordinary height-4 predictions:

| seed | predicted | found in the real world |
|---|---|---|
| 900009 | 4-tall at 196,63,-360 | 4-tall at 196,63,-360, on sand |
| 900009 | 4-tall at 200,63,-357 | 4-tall at 200,63,-357, on sand |
| 900017 | 4-tall at 484,63,425 | 4-tall at 484,63,425, on grass_block |

Exact x/y/z agreement, and the columns stand on blocks that only exist because of
the new surface builder. Chains together the biome source, the noise terrain, the
surface builder, the carvers and the placement RNG in one test.

All three were then **confirmed in a real client** as well, which rules out the
one thing the server-side check could not: a mistake in this project's own
region-file reading.

All three were then **confirmed in a real client** as well, which rules out the
one thing the server-side check could not: a mistake in this project's own
region-file reading.

Note the cane survives the post-generation flood, so a candidate can be checked
in a fully generated world even though the air pocket around it has become water.

### The deep geometry checks out too

`verify_geom.py` does the same for a predicted stackable spot rather than a cane
column, which is the only check there has ever been on the canyon carvers and on
the dirt blobs at depth. On seed 900002 the simulator predicted dirt soil at
894,29,-87 with a 4-tall pocket above it. The real world has:

```
real block there             : dirt
real block above (the pocket): water
neighbours of the block above: -x=water +x=water -z=water +z=cave_air
```

and the slice shows a tall void spanning roughly y=21..39 around it, flooded
exactly as section 5 predicts, with one cave_air block that the flood did not
reach. Dirt at the exact predicted x/y/z is not something a wrong blob decorator
or a wrong carver walk would produce.

Four such spots (seeds 900002, 900004, 900009) were also **checked in a client**
and all four were as predicted. Between this and 6k, every stage of the pipeline
has now been confirmed against the real game at block precision.

### A disagreement that turned out not to be one

The simulator said one of the verified height-4 columns stood on `grass_block`
where the real world had `sand`. That is `DISK_SAND`, whose configuration is
`DiskConfiguration(SAND, radius 7, halfHeight 2, targets {DIRT, GRASS_BLOCK})`
placed by `COUNT_TOP_SOLID(3)` at UNDERGROUND_ORES — it converts the surface
builder's grass to sand at the top solid block, after the surface stage and before
the cane. Soil to soil, so the placement is unaffected.

The other two disks are the ones that could matter, and both are small:
`DISK_CLAY` (clay, radius 4, halfHeight 1, targets dirt and clay) and
`DISK_GRAVEL` (gravel, radius 6, halfHeight 2, targets dirt and grass_block) turn
soil into something cane cannot stand on. All three only ever touch the top solid
block of a column, which is why it matters that 98% of the stackable spots are
inside the terrain rather than on the sea floor surface.

### What could still make a reported hit fail to reproduce

- **margin placements.** A column whose base is outside the chunk interior
  (local x,z in 4..11) can depend on whether a neighbour was decorated first,
  which depends on how the world is explored. `RegionSearcher` decorates in
  raster order and labels every hit `interior` or `margin` for exactly this
  reason;
- **ocean structures.** Ocean ruins and shipwrecks are placed before
  VEGETAL_DECORATION and can raise the heightmap the placement samples. Roughly
  1 chunk in 200-400 is affected and none of it is simulated;
- **lakes and disks**, neither implemented.

## 6l. Where the reference material lives

Not in this repository, and worth knowing before starting again from scratch:

- 1.16.1 server decompiled with official mappings, plus the two pregenerated
  worlds and the python region-file readers, are in the earlier session's
  scratchpad: `%TEMP%\claude\D--code-java-sugarcane\6ee42b05-.../scratchpad`
  (`mc/DecompilerMC/src/1.16.1/server`, `srv`, `srv2`). It is a temp directory —
  copy it somewhere durable before relying on it;
- `verify_hit.py` lives in this session's scratchpad `verify/` and takes
  `SRV_SRC` to point at that server.jar.

## 6m. How to run it

Build the classpath once, then run the searcher directly — `mvn exec:java` adds
startup cost and swallows output:

```
mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
mvn -o -q compile
CP="target/classes;$(cat target/cp.txt)"
java -Xmx12g -cp "$CP" dev.drakou111.sugarcane.RegionSearcher      <firstSeed> <seeds> <chunkRadius> <threads> <reportHeight> [diag|probe:N]
```

So the actual search is:

```
java -Xmx12g -cp "$CP" dev.drakou111.sugarcane.RegionSearcher 1 1000000 32 24 5
```

Each seed covers a 128x128-chunk box (the region grid rounds the radius up), of
which roughly 1,400 chunks are searchable ocean. Progress prints once a minute; a
find prints a line starting `HIT` with the seed, the base block of the column, its
height, and whether it is `interior` or `margin (order-dependent)`.

- `diag` adds the geometry counters — legal positions, stackable spots, how deep
  they are. Costs about 25% throughput.
- `probe:N` additionally replays the cane feature over N synthetic decoration
  seeds on every chunk that has a spot, which is how R and P were measured. Use
  a few hundred seeds only; it prints the implied chunks-per-find.

Memory is about 70 MB per thread (a 32x32-chunk region at full height), so 24
threads want roughly 2 GB of heap plus headroom.

### What to do when a HIT appears

1. **Look at what the simulator thinks it found:**

   ```
   java -cp "$CP" dev.drakou111.sugarcane.Inspect <seed> <x> <y> <z>
   ```

   It regenerates that one region and prints the cane columns nearby, the water
   beside the soil and beside the block above, and a vertical slice. If the column
   is not there, the hit came from a different region alignment — `Inspect`
   assumes the searcher's radius of 32.

2. **Confirm it in the real game** with `verify_hit.py` below. A `margin` hit that
   fails to reproduce is expected occasionally (decoration order); an `interior`
   one that fails means a real gap — check the slice against `verify_geom.py`
   output for lakes, disks or a structure the simulator does not know about.

3. The find is a stack of two columns, so in-game it looks like a single cane
   5 to 8 blocks tall, standing in what is now water (the pocket floods on chunk
   load; the cane itself survives).

To check a hit against the real game:

```
cd <scratchpad>/verify
python verify_hit.py <seed> <x> <y> <z> [radiusChunks]
```

It builds a throwaway server on that seed, forceloads a 5x5 chunk block so the
target chunk actually runs its features, then prints every cane column in the 3x3
chunks around the target. `SRV_SRC` points it at a 1.16.1 `server.jar` (see 6l).

`verify_geom.py` takes the same arguments and dumps the blocks around a predicted
stackable spot instead, which is the check to run when a hit does not reproduce.

## 6n. The first hit, and why it was not real

Seed 119658 produced a 5-tall column at -267,26,-190 (deep ocean, chunk -17,-12,
labelled `margin`). It does not exist in the real world: the server has a 2-tall
column at -268,26,-190 and nothing at -267. Confirmed both server-side and in a
client.

`Inspect` prints the placement trace, which localises the divergence exactly:

```
invocation 0: origin -268,-190 y=26 (heightmap 63)
    try 5 PLACED -268,26,-190 height 2      <- the real world has this one
    try 9 PLACED -267,26,-190 height 3      <- the real world does not
invocation 8: origin -266,-191 y=29
    try 18 PLACED -267,29,-190 height 2     <- this is what made it 5 tall
```

So the stream agreed through try 5 and parted company at try 9, one block over.
The soil (dirt at -267,25) and the water beside it are in the real world too, and
the real cane at -268,26 proves the pocket was air at feature time. The only
remaining explanation is that (-267,26) itself was **water** at feature time —
the underwater carver reached one block further in +z than the simulation has it —
so the try failed there.

Three candidate causes were checked and eliminated:

- **the heightmap convention.** `ChunkAccess.getHeight` is
  `getFirstAvailable() - 1`, which would make the decorator draw `nextInt(124)`
  over ocean rather than `nextInt(126)`. But `WorldGenRegion.getHeight` adds the
  one back, so the feature really does see 63. Tested against the 105 real chunks:
  the current convention reproduces 54, the off-by-one reproduces 6;
- **`Mth.sin`/`Mth.cos`.** The game uses a 65536-entry float lookup table, not
  `Math.sin`; this project used `Math`. Now fixed (`rng/Mth`), and it is the right
  thing to do, but it moved the cave carver's precision only from 90.43% to
  90.48% — about one block per 74 chunks — so it is not the explanation;
- **the disks.** Now implemented (`gen/Disk`), because they land on the sea floor
  exactly where the cane feature does its tries and can flip a try's outcome. They
  did not change this chunk.

Also verified against the source and found already correct: the carver
probabilities and registration order (`addOceanCarvers`: AIR cave 0.0667 then
canyon 0.02; LIQUID underwater canyon then underwater cave), the shared carving
mask per step, the start-chunk-outer iteration order, the ore-blob feature index,
and the `nextInt(16), nextInt(16), nextInt(256)` draw order of `COUNT_RANGE`.

**What this means for the search.** A stack needs two placements to land exactly
right, so any single block that differs kills it — a hit is inherently fragile.
The land-chunk validator says about half of all chunks reproduce exactly
(54/105), so **expect roughly half of the hits to be false** and verify every one.
It is not a reason to distrust the geometry: the spot rate still agrees with real
chunks, and this failure was one block at a carver boundary, not a systematic
error.

### A note on why the pocket height is not the problem

It is tempting to explain a missing tall column by the pocket being too short.
It is not that: `ColumnPlacer.place` loops `setBlock` upward with no checks at
all, so a 4-tall pocket still yields a 5-tall cane, with the top block replacing
the ceiling. Only the *base* position is tested.

### Getting the pre-flood state out of a real world

`verify_proto.py` tries to read a chunk at `features` status, which is the only
way to see the geometry the cane feature saw. It does not work near spawn: the
spawn area is always loaded to `full`, and a forceload ticket drags a radius of
chunks up with it. The prior session got its 35,490 proto-chunks by pregenerating
at an origin of 900,000. Since the search box is 32 chunks around the origin,
every hit lands inside the spawn area, so this diagnostic is unavailable for
hits — which is why the trace and the block dumps had to carry the argument.

## 6o. A terrain-free prefilter on the cane RNG: measured, not adopted

Worth knowing about, because it looks like an obvious win and mostly is not.

The cane stream is almost entirely terrain-independent. Every {@code nextInt} is
one LCG step, the offsets are fixed (3 draws per invocation for the origin, then 6
per try), and the only thing the terrain changes is that a *successful* placement
consumes 2 extra draws for the column height. So the whole sequence can be laid
out flat and indexed, and two structural facts make a stack cheap to test for:

- all 20 tries of an invocation share its y, because the y-spread is 0 — so the
  two columns of a stack must come from **different invocations**;
- the upper column must start at exactly `y1 + height1`, and `height1` is two
  known draws — so the later invocation's y is pinned to one value out of 126.

`StackPrefilter` does this in about 4 microseconds a chunk, against roughly 85 for
terrain. Measured over 200,000 chunks:

| variant | accepts |
|---|---|
| assuming no earlier placement | 8.0% |
| union over 0, 1 and 2 earlier placements | 21.6% |

So the RNG really is a 5x filter. It still does not pay off here:

- **in the region design it is worth about 1.1x.** A searched chunk needs its eight
  neighbours surfaced and carved, so accepting 21.6% of chunks scattered at random
  still needs `1 - (1 - 0.216)^9` = 89% of the region's terrain;
- **it would be worth about 2.9x with per-chunk windows**, the design this replaced:
  0.216 x 2.25 columns of work per searched chunk against 1.43 now. That is the
  version to build if the search ever needs to be faster.

One trap found while measuring it. The stack on seed 119658 had an *extra*
placement before its lower column — invocation 0 placed at try 5 and again at try
9 — so the stream was already shifted by 2 draws part-way through that invocation.
A filter that shifts whole invocations uniformly, as this one does, accepts that
chunk only by coincidence. A sound version has to track the shift per draw, which
multiplies the variants and pushes acceptance higher again.

The useful conclusion is what it says about where the rarity lives: the RNG
supplies a stackable pair in one chunk in five, so essentially all of the 7.1e-9
is the terrain having to cooperate at both positions.

## 6p. The second hit, and the validation it forced

Seed 113305583 produced a 5-tall column at 23,35,-19 — 23 blocks from spawn, in a
deep cold ocean. The real world has **no cane at all** in the 3x3 chunks around it,
confirmed server-side and in a client. Worse than the first failure, which at
least reproduced the lower column.

The trace showed it was a **cross-chunk stack**: chunk 1,-2 placed a 2-tall column
at 23,35,-19 (invocation 8, tries 0 and 13), and then chunk 1,-1's own run placed a
column on top of it, reaching 3 blocks into its neighbour. The block dumps then
showed why it cannot happen: the real world keeps **stone at x=19..22, y=33..36**,
so its dirt blob turned that into a dirt shelf, whereas the simulation had already
carved the same volume to water and so had dirt only at x=23. Different soil,
different tries succeed, different stream.

`ProbeColumns` ruled out the noise as the cause — the raw density field is solid
stone through that whole slice in both — which left the carvers, and led to the
validation below.

### Feature index ruled out first

Worth recording as a dead end: the obvious explanation for "my cane exists and the
game's does not" is a wrong feature index, since it salts `setFeatureSeed` and
would make every placement fiction. It is not that. Expanding every
`BiomeDefaultFeatures` helper in call order for all eight searched ocean biomes
gives an identical VEGETAL_DECORATION list — water trees, flowers, grass, two
mushrooms, then sugar cane — so the index is **5 for every one of them**, as
`BiomeCaneConfig` has it.

## 6q. Validating against pre-flood chunks: `ProtoValidator`

Every earlier check of the carvers compared against `full` chunks, where the
underwater carver's scheduled fluid ticks have already flooded the caves. Carved
air legitimately reads as water there, which is why `CarverValidator` tops out
around 90% and why that number cannot distinguish a good carver from a bad one.

`export_proto.py` + `ProtoValidator` fix that by comparing against chunks saved at
`features` status: noise, surface, both carving steps and the chunk's own
decoration have run, and nothing since. That is exactly the world the cane feature
saw. The pregenerated `srv2` world has 774 such chunks in only 25 region files.

Result over 281 ocean proto chunks, scoring each chunk's interior only (a
neighbour's blobs and disks reach about six blocks in, and in a proto chunk the
neighbours may be undecorated):

| quantity | value |
|---|---|
| exact block-category match | **98.68%** of 314,720 cells |
| simulated air that is really solid | 14 / 38,993 = **0.036%** |
| simulated water that is really solid | 63 / 89,650 = **0.070%** |
| simulated soil that is really not | 202 / 4,938 = **4.09%** |
| real air simulated as solid (loses finds) | 250 |
| real soil simulated as something else | 18 |

**So the carvers are right to about 0.05%, not 10%.** The earlier inference that
they over-carve by 10% was the flooding confound all over again — the same trap
section 5 warns about, walked into from a different direction.

The real weak spot is **soil at 4%**, which is the dirt blobs, and that is the one
thing both false hits turned on.

### The bug it found: OreFeature endpoints are float arithmetic

`OreFeature.place` computes the blob's endpoints as
`(float)blockPos.getX() + Mth.sin(f) * f2` — the whole expression in **float**,
widened to double only on assignment. This project did the addition in double.

Near the origin that is exact, which is why nothing caught it. At the proto
chunks' x of about 3 million a float step is **0.25 blocks**, so the entire blob
shifts. Fixing it dropped missed soil from 51 cells to 18 and false soil from
4.74% to 4.09%.

The lesson generalises: worldgen mixes float and double deliberately, and a
transcription that "simplifies" to double is exact near spawn and wrong far from
it. Anything validated only near the origin has not been validated for this.

### What is left, and what it means for the search

- **4% of simulated soil is not soil in the real world.** Since the deep spots sit
  on blob dirt (mean soil y 23), this is the dominant reason a hit can be false.
- **The air/water boundary is off by a block sometimes** (0.036% and 0.070% above,
  plus 131 cells of simulated air that is really water). That is enough to break a
  spot, and it also costs recall: at the one real stackable spot in the sample —
  2999957,14,3011194 on seed 1 — the simulation has the air and the dirt right but
  puts the water one block further away, so it would never have reported it.
- `ORE_GRAVEL` (count 8, size 33, index 1) is **not** implemented, which explains
  9,324 cells of simulated stone that is really gravel. It does not affect the
  search: gravel blobs run after the carvers and replace stone, which is not cane
  soil either way.

Expect roughly half of hits to be false, matching the 54/105 of section 6b, and
verify every one.

## 6r. THE BUG: the underwater carver writes its water through a moved cursor

Eight consecutive hits were reported, verified against a real server, and every
one of them was false. The cause was one line of vanilla that this project
transcribed as if it did the obvious thing.

`UnderwaterCaveWorldCarver.carveBlock`:

```java
for (Direction direction : Direction.Plane.HORIZONTAL) {
    int n10 = n4 + direction.getStepX();
    int n11 = n5 + direction.getStepZ();
    if (n10 >> 4 == n2 && n11 >> 4 == n3
        && !chunkAccess.getBlockState(mutableBlockPos.set(n10, n7, n11)).isAir()) continue;
    chunkAccess.setBlockState(mutableBlockPos, WATER.createLegacyBlock(), false);
    ...
    bl = true;
    break;
}
mutableBlockPos.set(n4, n7, n5);
if (!bl) { chunkAccess.setBlockState(mutableBlockPos, WATER.createLegacyBlock(), false); }
```

The air test is written as `getBlockState(cursor.set(nx, y, nz))`, so testing an
in-chunk neighbour **moves the cursor onto that neighbour** — and the very next
line writes the water through the same cursor. So:

| neighbour | where the water actually goes |
|---|---|
| outside the chunk | the carved block (the `set` was short-circuited away) |
| in-chunk and air | **the neighbouring air block**, and the carved block is left alone |
| all in-chunk and non-air | the carved block |

The middle case is the one that matters: an underwater tunnel touching an air cave
floods **the cave**, one block in, rather than itself. This project wrote the water
at the carved block in every case, which left dry air exactly where the game has
water — and the air/water boundary is where every piece of sugar cane geometry
lives.

Measured against 4,741 pre-flood ocean chunks:

| | before | after |
|---|---|---|
| simulated water that is really solid | 1,086 | **397** |
| **stackable spot precision** | **25%** | **67%** |
| stackable spots per chunk (the search rate) | 2.1e-3 | 1.1e-3 |

So three quarters of the spots this project used to report did not exist, the
search rate was correspondingly inflated, and every hit built on one was
guaranteed to fail. The rate is now half what it was and the hits are real.

**The general lesson.** Decompiled code that reuses a mutable cursor is a trap:
`pos.set(...)` inside a condition is an argument *and* a side effect. Anywhere a
`MutableBlockPos` appears in a test, check what it points at by the time the next
write happens.

## 6s2. Three more accuracy fixes, and what the error budget looks like now

With the cursor bug fixed, `ProtoValidator` was pointed at the remaining error and
found three things. Restricting the comparison to y >= 8 matters throughout: below
that is the bedrock layer, which this project does not simulate, so its blobs fill
in where the game has bedrock. That artefact alone accounted for four fifths of
the apparent soil error and none of it can carry sugar cane.

| error class (y >= 8, 4,741 ocean proto chunks) | rate |
|---|---|
| simulated air that is really solid | 0.026% |
| simulated water that is really solid | 0.024% |
| simulated soil that is really not | **0.59%** (was 4.59% including bedrock) |
| real air simulated as solid | 10,342 cells |

### Sandstone is not natural stone

`OreConfiguration.Predicates.NATURAL_STONE` is only stone, granite, diorite and
andesite. The reduced palette folded sandstone into SOLID, so dirt blobs replaced
it — which the game never does. It matters on warm and lukewarm ocean floors,
where the sand band turns to sandstone underneath. Fixed with a distinct
`Blocks.SANDSTONE`; worth only a handful of cells here, but it is the kind of
error that is invisible until it is not.

### The missing air is mineshafts

Dumping the cells where the game has air and this project has solid shows the
shape immediately: three-tall corridors with regular vertical supports and
unmapped blocks (planks, fences, rails) in them. Mineshafts generate at
UNDERGROUND_STRUCTURES, step 3 — before the ore blobs and long before the cane —
and their air is written with `setBlock`, so it never floods and never appears in
any carver.

They cut both ways. The spots they create are invisible to this project (recall),
and worse, a mineshaft inside a searched chunk flips cane tries and desynchronises
the chunk's whole RNG stream (precision), because a successful placement consumes
two extra draws.

Implementing them is a large job. Skipping them is not: spacing is 1 and
separation 0, so a chunk starts a mineshaft iff
`setLargeFeatureSeed(seed, cx, cz); nextDouble() < 0.004`, which is 49 cheap draws
for a radius of 3. Measured:

| exclusion radius | chunks skipped | missing air removed |
|---|---|---|
| 2 | 10.4% | 64.2% |
| **3** | **19.0%** | **81.0%** |
| 5 | 38.1% | 91.7% |

The searcher now skips any chunk within 3 of a mineshaft start. It costs 17% of
the spot rate and removes four fifths of the terrain error that was silently
invalidating hits.

## 6s. What is still missing, in order of value

Recall is 50-75% — the simulation finds two thirds of the real spots — and there
are 10,599 cells in the sample where the game has air and this project has solid.
Candidates, best first:

- **Lakes.** Section 5 dismissed them as a source of a vertical water face, which
  is right, but overlooked the plain case: `LakeFeature` runs at step 1, *before*
  the ore blobs, and places air above water sealed inside rock, with no fluid
  ticks, so it never floods. A dirt blob at step 6 replacing stone at the lake rim
  then gives soil, with lake water beside it and lake air above — a spot. Roughly
  one chunk in four attempts a water lake.
- **Mineshafts** are now known to be the dominant missing air (section 6s2) and
  are skipped rather than simulated. Implementing them would recover both the
  skipped 19% of chunks and the spots they create, which are permanently dry and
  therefore the most robust kind.
- **Dungeons** (8 attempts per chunk, same step) are still unsimulated, though
  their cobblestone shell means the air rarely borders soil.
- The 1.2% of ocean columns where the real terrain is higher than the noise
  generator says, still unexplained from section 6d.

## 6t. Seed reversal and the upper 16 bits: verified, and why it does not help

A suggestion worth recording, along with the measurements that settle it: reverse
the carvers for the water ravine and the air cave, then roll the upper 16 bits of
the seed for the biomes and the cane.

### The premise is exactly right

Every worldgen RNG goes through `Random.setSeed`, which masks to 48 bits.
`setDecorationSeed` does XOR in the full 64-bit seed —
`(x * a + z * b) ^ levelSeed` — but feeds the result straight back through
`setSeed`, so the top 16 bits are masked off again. Biomes take another route
entirely: layer salts mix the full seed and the Voronoi uses
`WorldSeed.toHash(worldSeed)`.

`SeedBitsProbe` shows it directly. Holding the low 48 bits and varying the upper
16:

```
upper  decoration seed(0,0)   first cane draw   cave@0,0  biome@0,0
0      1500050556             (12, 2,119)       false     45
1      281476476761212        (12, 2,119)       false     13
2      562951453471868        (12, 2,119)       false      3
```

Identical draws, different biome. So **carvers, terrain noise and all decoration
are properties of the low 48 bits alone**, and the upper 16 are a free 65,536-way
re-roll of the biome map. Chunk (0,0) is the easy case to see it in: both
`setDecorationSeed` and `setLargeFeatureSeed` collapse to the level seed there,
because the chunk coordinates they multiply in are zero.

### Why it still does not beat brute force here

- Biomes feed `getDepthAndScale`, so the noise terrain changes with the roll. The
  only reusable work across variants is the noise samplers, and those are already
  amortised — radius 6 and radius 32 measure the same throughput, so per-seed
  setup is not a cost worth attacking.
- The expensive part cannot be localised to the pair's position. Whether each of
  the ~200 cane tries succeeds shifts the stream for every later one, so the whole
  chunk's geometry is needed, which means the whole 3x3 neighbourhood.
- Any single-chunk-per-seed scheme therefore pays **9 chunk-generations per
  trial**, where the region design amortises to **1.58**. That is a 6x handicap
  against a 4.6x gain from the cane prefilter.

### The idea it did suggest, and its measurement

Ocean biomes that share depth and scale produce *identical* terrain and register
identical carvers — ocean and lukewarm_ocean are both (-1.0, 0.1), deep_ocean and
deep_lukewarm both (-1.8, 0.1). They differ only in the surface configuration, and
that decides the deep floor: `CONFIG_GRASS` gives gravel, which cane cannot stand
on, while `OCEAN_SAND` and `FULL_SAND` give sand, which it can. So the same cave
should be worth more in a lukewarm ocean.

Measured over 320,000 ocean chunks:

| biome | floor | stackable spots/chunk |
|---|---|---|
| lukewarm_ocean | sand | 1.25e-3 |
| cold_ocean | gravel | 9.16e-4 |
| ocean | gravel | 8.81e-4 |
| deep_ocean | gravel | 8.30e-4 |
| warm_ocean | sand | 7.56e-4 |
| deep_lukewarm | sand | 6.39e-4 |
| deep_cold | gravel | 5.09e-4 |

It does not hold up, and the same run says why: **97% of stackable spots are
inside the terrain rather than on the sea floor surface** (267 of 274). Deep spots
stand on ore-blob dirt, which is biome-independent, so the floor material only
decides the other 3%. Lukewarm's 1.4x is barely outside its own error bar and the
other two sand biomes go the other way.

## 6u. Seed-only prefilters, built and measured: 0.70x

Section 6t argued the reversal idea down on cost estimates. Those estimates were
wrong, so both filters were built and benchmarked (`PrefilterBench`,
`GeometryPrefilter`). The answer is worse than the estimate, not better.

```
1013 ocean chunks
  cane RNG pair   : 19.05%   23.2 us/chunk
  carver envelope : 44.92%   68.5 us/chunk
  both            :  9.38%   91.7 us/chunk total

accepts 9.4%, 3x3 neighbourhoods cover 58.8%
projected: 91.7 filter + 64.7 terrain = 156 us against 110 us now  ->  0.70x
```

Three findings, two of them structural:

**The cane filter is unsound as written, and the confirmed find proves it.**
Testing a filter against the one verified result is what caught this; the
aggregate acceptance rate of 19% looked perfectly healthy. Enumerating the shift
pairs that accept seed 1500050556 chunk 5,4:

```
base shift 0, top shift 4  -> ACCEPTED
base shift 0, top shift 8  -> ACCEPTED
```

which matches its trace exactly: the lower column was the chunk's first success
(shift 0), try 8 then placed an unrelated column at 90,16,65, and the upper column
came at invocation 4 with **two** placements already absorbed (shift 4).
`StackPrefilter` only tests *tied* pairs — (0,2), (2,4), (4,6) — so (0,4) is not in
its search space at all. That is a modelling error, not a tuning one.

Making it sound means enumerating (baseShift, topShift) independently, since any
number of unrelated placements can fall between the two columns: about ten
combinations instead of three, so roughly ten times the cost **and** a union over
ten variants, pushing acceptance well above 19%. Both terms move the wrong way
from an already-losing 0.70x.

**A terrain-free carver walk costs more than the real one.** The carvers are about
6 us/chunk in the search, because `hasWater` aborts spheres and
`canReplaceBlock` fails fast. Strip the terrain out and nothing aborts, so the
walk does more work, not less: 68.5 us. The guard that makes the geometry rare is
the same guard that makes the real carve cheap, so there is no cheap preview of it.

**The neighbourhood dilation caps the idea regardless.** A searched chunk needs its
eight neighbours generated. At 9.4% acceptance the union of 3x3 neighbourhoods is
58.8% of chunks, so even a **free** filter would only reach 1.7x.

The envelope test also turned out far looser than the block-level version of the
same question: 44.9% against the 12.4% measured in section 6t by testing actual
carved blocks rather than sphere bounding boxes.

**Conclusion.** The rarity is not in the seed. Two seed-determined layers — the
cane RNG pair and the carver envelope — together admit ~9% of chunks, while 0.09%
actually hold the geometry. The missing factor of a hundred is the noise density
field deciding whether a sphere carves anything, and that is a different generator
which the carver seeds say nothing about.

## 6v. Cross-chunk columns are real, and still worthless near spawn

Seed 4505722117 reported a 5-tall at 20,15,64 and came back 3 tall in game. The
trace showed chunk 1,4 placing only the top two blocks, at y=18 and 19; the three
below came from chunk **1,3** reaching over the border, since a placement lands
within ±4 of an origin drawn inside its own chunk and z=64 is the first block of
chunk 4. `canSurvive` returns true the moment the block below is cane, so 1,4's
placement at y=18 succeeds if and only if 1,3 has already run.

Forced the question with a real server. Move `SpawnX/SpawnZ` far away, delete the
six chunks around the target so they regenerate, then forceload in stages:

| stages | column at 20,15,64 |
|---|---|
| chunk 1,3 alone, then 1,4 | **5 tall, y=15..19** |
| chunk 1,4 alone, then 1,3 | 3 tall |
| both as one forceload box | 5 tall |

So the column is genuine and the simulator's raster order was right. But in an
untouched world it is unreachable: spawn for this seed is x=-4 z=236, chunk
-1,14, and the server pregenerates a radius of 11 chunks around it. The target at
chunk 1,4 is exactly 10 away, so **both chunks are decorated during "Preparing
spawn area"**, before a player exists. The spiral radiates outward from spawn and
reaches 1,4 (distance 10) before 1,3 (distance 11) — it approaches from the south,
which is the losing direction. Four different forceload strategies afterwards all
returned 3, because by then the chunks were already `full`.

This is why the searcher now scores only the run one chunk built by itself rather
than labelling border columns and letting them through. We search at chunk radius
6, so hits sit near 0,0 and usually inside the spawn pregeneration, where the
decoration order is fixed by the spawn spiral and no load pattern can change it.
A cross-chunk column there is not a risky hit — it is an unavailable one.

## 6w. Where the time actually goes, and why reversal cannot take it

JFR profile of a live search, 14,538 samples, aggregated by top frame:

| | share | what |
|---|---|---|
| `Noise.lookup` | 46.6% | Perlin gradient lookups, 16 octaves for the limits, 8 for the selector |
| `IntLayerCache.get` + `HashMap.*` | 20.4% | biome lookups, **all of them inside terrain generation** |
| carvers | 10.8% | `carveSphere`, `genTunnel`, `hasWater`, `carveBlock`, `genCanyon` |
| `ArrayWorld.setBlock` / `setNoiseColumn` | 7.7% | writing the block array |
| everything else | ~14% | surface builder, disks, ore blobs, the cane feature |

Attributing the HashMap traffic by caller puts 522 samples under
`TruncatedNoise.cellBiome` and 517 under `TruncatedNoise.noiseColumn`, against 9
in the biome source proper. 1.16 blends `getDepthAndScale` over a 9x9 biome
neighbourhood per noise cell, so each cell costs tens of layer-cache lookups
through a `HashMap` with boxed keys. That is the cheapest large win on the table:
a flat per-region biome array feeding the noise directly should recover most of
20%, for about **1.25x**, with no change to results.

### Why no amount of reversal helps

The chain is `worldSeed -> low 48 bits -> {noise permutation tables, carver
seeds, decoration seed}`. Reversal in the seedfinding sense inverts **LCG output
constraints** — that is how structure seeds, slime chunks and decorator seeds all
fall. Our two conditions sit on opposite sides of that line:

- The **RNG condition** (two cane invocations able to stack) is on the LCG layer
  and genuinely reversible — but it is not rare. Section 6u measures 19% for the
  cane pair and 44.9% for the carver envelope, and making the cane filter sound
  pushes it higher, not lower. Reversing a condition that 1 seed in 5 already
  satisfies buys nothing.
- The **terrain condition** is rare — 1.3e-3 of ocean chunks — but it is decided
  by the density field: 16 octaves of interpolated Perlin gradients over
  permutation tables built by a Fisher-Yates shuffle. There is no linear structure
  to invert and no published technique for it.

**The rare part is not reversible and the reversible part is not rare.** That is
the whole answer, and it is why 6t and 6u both came out negative from different
directions.

The one framing that escapes this is "fix the spot, reverse the RNG onto it,
forward-check terrain at that single position". It fails on the check: knowing
whether one block is air beside water needs the carvers that reach it, and 6u
measured a terrain-free carver walk at 68 us against 110 us for generating the
entire real chunk. The thing you would jump to costs 60% of the thing you are
avoiding, before any lattice work.

### What is left, ranked

1. **Flat biome array for the noise** — ~1.25x, low risk, bit-exact.
2. **Vectorise the octave loop** across noise cells with the Vector API. Lanes are
   independent cells, so the per-cell summation order is untouched and the result
   stays bit-exact. Guess 1.2-1.35x overall.
3. **Cheaper block writes** — 7.7% in `setBlock`, much of it heightmap maintenance
   that the search reads only at cane time.

Realistic combined ceiling is somewhere near **1.7x**. Nothing here changes the
shape of the problem: the search is bounded by evaluating a noise field that
cannot be predicted, only computed.

## 6x. The water does not have to be a face — and it changes nothing

A suggestion worth testing: a water lake at the base and a single water source
block four higher inside a ravine. The premise is right, and sharper than how this
file had been describing the geometry.

`RandomPatchFeature` takes `vec3i = mutableBlockPos.below()` and applies
`needWater` to *its* four horizontal neighbours. `canSurvive` short-circuits to
true on cane-below, but `needWater` is a separate clause that still runs. So a
stack needs water beside **y0-1** and again beside **y0+h1-1** — two heights
exactly h1 apart, h1 being 2, 3 or 4. They need not be connected, and the blocks
between need not be air, because `ColumnPlacer` overwrites upward
unconditionally. "Lake at the base, one source block four higher" is the h1=4
case exactly.

`stackableSpots` only ever tested the connected h1=2 case, so it undercounts.
`stackableRelaxed` tests what the game tests. Measured with `diag-all`, which
drops the ocean restriction:

| | chunks | legal spots/chunk | stackable | relaxed |
|---|---|---|---|---|
| oceans only | 14,000 | 0.235 | 1.57e-3 | 1.64e-3 |
| every biome | 51,000 | 2.54 | 4.30e-4 | 4.49e-4 |

The relaxation buys 4% in the ocean, where the carver geometry is a real face and
h1=2 already works. On land it buys nothing at all: the 22 strict and 23 relaxed
spots in the all-biome run are the same ocean spots, so **37,000 land chunks
produced zero stackable spots under the rule the game actually uses**. With
section 5c's earlier 25,000 that is 62,000 land chunks and no spot.

Land has **ten times more legal spots per chunk** than the ocean — every shoreline
offers somewhere a first column could start. What it never has is the second
water block. Nothing in 1.16 puts an isolated source above the flat water line
near a legal spot: springs are registered after the cane in the same step, lakes
are sealed by their boundary pass and flat-topped regardless, the noise sea fill
is flat at y=62 by construction, and the only carvers that *write* water are the
underwater pair, registered for ocean biomes only.

So the ocean restriction in `isSearchableOcean` survives the stricter test, and
for the reason 5c gave: the underwater carvers are the only generator in the game
that leaves water at two separated heights beside a spot cane can start from.

## 6y. Ravine springs: the right block, two list entries too late

Single water sources really do generate in ravine and cave walls — that is
`SPRING_WATER`, and it is exactly the isolated elevated source section 6x said
land has no producer for. It would be a good one. From `BiomeDefaultFeatures`:

```
537: addDefaultExtraVegetation(biome)
538:   VEGETAL_DECORATION, RANDOM_PATCH(SUGAR_CANE_CONFIG), COUNT_HEIGHTMAP_DOUBLE(10)
592: addDefaultSprings(biome)
593:   VEGETAL_DECORATION, SPRING(WATER_SPRING_CONFIG), COUNT_BIASED_RANGE(50, 8, 8, 256)
```

Fifty attempts per chunk over y 8..256, against ten for the cane. Both sit in
`VEGETAL_DECORATION`, and a step's features run in the order they were added, so
the only thing that matters is which call comes first. Checked in
`PlainsBiome`, `OceanBiome`, `DesertBiome` and `RiverBiome`: every one calls
`addDefaultExtraVegetation` and then `addDefaultSprings` on adjacent lines. The
only features registered after the springs are seagrass and kelp.

So when the cane feature runs, every spring block in the world is still stone. The
water appears afterwards, which is why ravines look full of promise and contain
none.

Worth stating plainly because the intuition is sound and the geometry is right:
swap those two calls and land ravines would beat ocean caves outright — 50
sources per chunk against a carver intersection that needs 1.4e8 chunks. The
whole search lives or dies on the order of two lines in a biome constructor.

## 6z. The 1.7x that was really 1.18x

> **Superseded in part by 6aa.** The conclusion below — that the noise had no
> cheap structural win left — was wrong, and wrong for an instructive reason. It
> reasons about the Perlin *lattice*, where reuse really is limited, and never
> looks at what `grad` compiles to. A coefficient table in place of its switch
> later measured 1.42x on its own.

Section 6w projected ~1.7x from the profile. Built, measured against the same
20,000-seed run on an otherwise idle machine, results identical throughout (666
cane columns, tallest 4): **12,633 -> 14,934 chunks/s, 1.18x**.

| change | chunks/s |
|---|---|
| before | 12,633 |
| raw biome ids + flat noise caches | 13,219 |
| layer cache 1024 -> 4096 | 14,171 |
| hoisted sampling constants and octave samplers | 14,934 |

What the projection got wrong, in order of size:

**Biome lookups, projected 1.25x, delivered ~1.12x.** The 20% was real but mostly
not recoverable. The memo was already working; what was left was the boxing
(`Biomes.REGISTRY.get(Integer.valueOf(id))` on a call that only wanted the id
back) and cache sizing. `IntLayerCache` turned out not to be a HashMap at all but
a direct-mapped `long[]`/`int[]` table — the HashMap frames in the profile were
our own two memo maps.

**Cache size is not monotonic.** 1024 stock, 4096 best, and 65536 at 6,571
chunks/s — half the speed of stock. Forty layers times 24 workers puts the tables
in competition with the noise data for L2, so past a point locality costs more
than recomputation saves. Worth remembering before "just make the cache bigger".

**Block writes, projected ~7%, delivered a regression.** The 7.7% is
`setBlock` called per block by carvers and features, not the bulk column copy.
Trimming the copy to the cut and skipping the always-air remainder measured
13,339 against 14,171 — the extra indirection cost more than the memory traffic
saved. Reverted. Clearing less of the world per region is worth 0.6%: measured by
skipping the fill entirely, which is 13,659 against 13,579.

**The noise has no cheap structural win left.** It is 46.6% and the
selector-first trick already cuts it from 40 octave evaluations to 26. The
tempting idea — hoist the x/z permutation lookups across the 14 y values of a
column — pays almost nothing: in Perlin only `p[X]` and `p[X+1]` are
y-independent, everything past `p[X] + Y` is not. Neighbouring columns do not
help either, because one quart step is `xzScale * persistence` in noise space,
far more than a lattice cell. What is left is genuine SIMD across octaves with
gathers into sixteen permutation tables, which is a real project with an
uncertain payoff, not a tidy-up.

Run-to-run variance on a 13-second benchmark is about 4%, which is larger than
two of the steps above. Every number here is from a 40-second run.

## 6aa. The bottleneck was a jump table, not arithmetic

Setting out to vectorise the eight gradient dot products per sample, the first
step was reading what `MathHelper.grad` actually does. It is not a dot product.
It is a 16-way `tableswitch`, each case a single add:

```
0: x+y   1: -x+y   2: x-y   3: -x-y     8: y+z    9: -y+z  10: y-z  11: -y-z
4: x+z   5: -x+z   6: x-z   7: -x-z    12: y+x   13: -y+z  14: y-x  15: -y-z
```

That compiles to a jump table indexed by four bits of a permutation value, and it
is taken **eight times per sample**. The index is effectively random, so the
indirect branch mispredicts nearly every time, and a mispredict costs an order of
magnitude more than the addition it is guarding. This was the single largest cost
in the search, sitting inside what the profiler attributed to `Noise.lookup`.

The replacement is a coefficient table:

```java
return GX[h] * x + GY[h] * y + GZ[h] * z;
```

**Exact, not merely equivalent.** Every case is a sum of two of the three
coordinates with unit signs, so the table holds 0 and ±1. Multiplying by `1.0` or
`-1.0` reproduces `dload` and `dneg` bit for bit; `a - b` is *defined* as
`a + (-b)`; and the two cases the library writes the other way round (`y + x`) are
commutative in IEEE754. The only divergence is the unused third coordinate
contributing a signed zero, which can change the result only when the other two
terms are both exactly zero at once, and `TruncatedNoiseTest` still reports 764
columns exact against TerrainUtils.

Measured interleaved, three pairs: **6,626 → 9,435 chunks/s, 1.42x**, identical
output.

The other change in the same round: `ColumnPerlin` hoists each octave's x/z
lattice work — the sections, fractions, fades and the two permutation lookups
taken before the section y is added in — out of the 14 cell values that share a
column. Interleaved, 12,170 → 13,017, **1.07x**. Inverting the loops so each
octave sweeps the column instead hoists the same work but moves the per-y
accumulator from a register into an array, and the 224 extra read-modify-writes
per column cost more than the hoisting saves: 13,441 against 14,934. Rejected.

Total since v1.0, every step bit-exact: about **12,600 → 19,200 chunks/s, 1.5x**.

Two lessons, both of which cost time here:

**Read the callee before declaring a hot path irreducible.** Section 6z reasoned
carefully about the Perlin lattice and concluded there was nothing left. The
reasoning was sound and the conclusion was wrong, because the cost was not in the
lattice arithmetic at all. A profiler naming a method tells you where the time
goes, never why.

**Interleave A/B on a machine you do not control.** The column hoisting above was
measured three times as ~10% slower, deleted, and only recovered because the
reverted baseline then measured 11,367 where it had measured 14,934 twenty minutes
earlier. Minecraft had been launched mid-session. Runs minutes apart are not
comparable; alternating base/variant/base/variant is, and it is what every number
in this section and 6aa comes from. Absolute figures under load are meaningless,
ratios survive.

SIMD remains untried and now looks less attractive: what is left per sample is
three dependent permutation gathers and a lerp tree, and gathers are the thing
SIMD does worst.

## 6ab. The error that manufactures false hits, measured at last

Seed 4531414558 reported 5 tall at -87,23,96 and came back **2 tall** in game. Not
the optimisation — the pre-noise-work build produces byte-identical output on that
seed. The blocks say why:

| position | simulated | real |
|---|---|---|
| -87, 25, 96 | air | **water** |
| -88, 23, 95 | air | **water** |

Every placement is gated on `isEmptyBlock`, so simulated air where the game has
water invents a legal spot from nothing. Invocation 4 placed three blocks on top of
the 2-tall base in simulation and nothing in the game, and invocation 2's second
column at -88,23,95 vanished for the same reason — which is the tell, because it
means the stream diverged *within* an invocation, between try 8 and try 15.

**The accuracy table never measured this.** It reported simulated air that is
really solid, simulated water that is really solid, and simulated soil that is
really not. Air against water was missing, and it is the only one that can turn a
non-spot into a spot. Added to `ProtoValidator`; against 281 real pre-flood ocean
chunks:

```
simulated AIR that is really WATER   : 8 / 38953 air  (0.0205%)
simulated WATER that is really AIR   : 7 / 89677 water (0.0078%)
```

0.02% sounds harmless and is not. The scored window is 16 columns per chunk, so a
whole chunk holds about 2,200 simulated-air cells, giving **~0.46 wrongly-air cells
per chunk** and a ~37% chance that any chunk contains at least one. One is enough:
a try that wrongly succeeds consumes two extra draws from `ColumnPlacer` and
desynchronises everything after it. That is the arithmetic behind the ~67% spot
precision measured after the cursor bug, and it means **about one reported hit in
three is expected to fail in game**.

Which is a rate to design around rather than be surprised by: verify before
travelling, and treat a HIT line as a candidate.

## 7. Watch out for

- `nextInt(1)` is called twice per try (yspread is 0). It always returns 0 but
  still advances the LCG.
- `ColumnPlacer` writes upward **unconditionally**, overwriting whatever is there.
- The feature index passed to `setFeatureSeed` counts structures placed in the
  same step before the features.
- 1.16.1 predates the 1.16.2 worldgen datapack refactor: the decorator classes
  differ, so 1.16.2 JSON is only a guide, not ground truth.
- `Mth.sin` and `Mth.cos` are float lookup tables, not `Math.sin`/`Math.cos`, and
  the carvers use them in float arithmetic before widening to double. See
  `rng/Mth`.
- `ChunkAccess.getHeight` returns `getFirstAvailable() - 1`, but
  `WorldGenRegion.getHeight` adds one back. During worldgen a feature therefore
  sees the raw heightmap value.
- Heightmaps: `OCEAN_FLOOR_WG` counts only blocks that block motion, so water does
  not count; `MOTION_BLOCKING` does count fluids, which is why it is 63 over open
  ocean. The cane decorator uses MOTION_BLOCKING, the disks use OCEAN_FLOOR_WG.
- `DiskReplaceFeature` tests for water at the position *before* drawing its
  radius, so on land it consumes nothing from the stream.
- `OreFeature.place` computes its endpoints in float and widens afterwards. Exact
  near spawn, wrong by up to a quarter of a block at x of a few million. Assume
  every other transcription has the same class of bug until checked far from the
  origin.
- `OreFeature`'s reachability gate is
  `if (minY > getHeight(OCEAN_FLOOR_WG, x, z)) continue`, and that getHeight is the
  WorldGenRegion one, so it is the firstAvailable value. Getting the gate wrong
  desynchronises every later blob in the chunk, because doPlace draws `size`
  doubles.
- The low 48 bits of the world seed decide carvers, terrain noise and all
  decoration; the upper 16 decide only biomes. See section 6t.
- A `MutableBlockPos` used inside a condition is a side effect as well as an
  argument. See section 6r, which cost eight false hits.
- Never measure carver fidelity against `full` chunks. Use `features`-status
  chunks; `CarverValidator` cannot exceed about 90% for reasons that have nothing
  to do with correctness, while `ProtoValidator` reads 99.95%.

## 6ac. Eight tall exists, someone else found it, and it inverts 6w

Reported find, and the first independent one: seed **-7585781829663227268** at
**-24848077, 21, 18720986**, 1.16. Verified by forceloading the chunk in a real
1.16.1 server and reading the region file:

```
y= 20  dirt        N=stone     S=dirt      W=dirt    E=water[0]
y= 21  sugar_cane                                    E=water[0]
 ...   sugar_cane  x 8
y= 28  sugar_cane  N=water[0]  S=water[1]  W=gravel  E=water[0]
y= 29  stone
```

Eight tall on ore-blob dirt at y=20, with a water **source** column to the east
continuous from y=20 to y=36. Nothing exotic: it is the flooded-underwater-carver
water face of section 5, at the mean spot depth this document already predicts
(soil y=20 against a measured mean of 23.2).

### Two reasons this search could never have produced it

**The area is invisible to us.** The 3x3 neighbourhood contains biomes 10 and 50,
frozen_ocean and deep_frozen_ocean. `SurfaceConfig.supported()` excludes both, so
`searchRegion` skips the chunk outright. This is not a rate — it is a region of the
world the search does not look at.

**`inspect` lied about it rather than saying so.** It clipped to `radius` chunks
around 0,0, so nothing was generated and every read returned the out-of-window
`SOLID`; the tool printed a confident wall of stone for a position that really
holds an 8-tall column. It now centres the box on the target, drops the ocean and
mineshaft filters, and warns when a chunk was not generated. Any find reported by
someone else lands in this case, because the world border allows chunk coordinates
out to ±1,874,999.

### What 8 tall actually costs, measured

An 8-tall run is two 4-high columns. `ColumnPlacer` draws
`2 + nextInt(nextInt(3) + 1)`, so P(h=4) = 1/9, and the second column's y is pinned
to `y1 + h1` exactly.

The terrain requirement is **much weaker than it looks**, and this is the useful
part. The upper column needs `needWater` at the top of the lower one — water beside
`base+3` — and air at `base+4`. It does *not* need water beside `base+1` or
`base+2`, and it does not need the run's own top to be beside water. So a **5-tall
water face is already enough for 8 tall**. Over 120M decoration seeds per row, on
one isolated spot with soil at y=20:

| water column | P(>=5) | P(>=8) |
|---|---|---|
| y=20..22 (3 tall) | 3.92e-6 | **0** in 120M |
| y=20..24 (5 tall) | 9.52e-6 | 2.75e-7 |
| y=20..26 (7 tall) | 9.73e-6 | 2.42e-7 |
| y=20..36 (17 tall, the real find) | 9.15e-6 | 1.82e-7 (600M trials) |

It saturates at 5. Height beyond `base+3` buys nothing, which is why the real find
sitting against a 17-block face is not the improbable part of it.

Combining with the spot geometry over 3.18M searched ocean chunks (60,000 seeds,
radius 8), where `air pocket` is the contiguous air-and-water-beside run above the
base:

| quantity | value |
|---|---|
| stackable spots | 8.94e-4 /chunk (agrees with 6i) |
| of those, face >= 5 — sufficient for 8 tall | 13.6% |
| 8-capable spots | ~1.22e-4 /chunk |
| P per decoration seed, given such a spot | ~2e-7 |
| **chunks per expected 8-tall find** | **~5e10** |

Against 1.4e8 for 5 tall (6i), an 8-tall is about **370x** more expensive: 70 days
at the 8,636 chunks/s this machine manages on 12 threads. Note where the 370x comes
from — 7.4x from the terrain and 50x from the RNG.

### Why that inverts 6w

Section 6w rejects reversal with "the rare part is not reversible and the
reversible part is not rare", citing 19% for the stackable pair. That figure is for
height 5. `gen/ChainPrefilter` measures the same terrain-free question at
each height — q, the fraction of decoration seeds whose draws could chain a run of
that height *somewhere*, enumerating the success-shift per column rather than
tracking it:

```
200,000 decoration seeds, count=10
   q(>= 5) = 6.07e-1
   q(>= 7) = 1.57e-1
   q(>= 8) = 3.38e-2
   q(>=10) = 6.65e-4
```

q is exactly the speedup available to reversal: a search that enumerates seeds
passing this test and only then generates terrain generates 1/q times fewer chunks
than one that pays for terrain first. At height 5 that is 1.6x and not worth the
machinery, which is what 6u measured from the other direction. **At height 8 it is
30x**, and the filter here is deliberately loose — a sound one that tracks shifts
instead of enumerating them accepts less, so 30x is a lower bound.

### The lattice is the other half, and the coordinate is the evidence

Reversal needs somewhere to *put* an RNG-good decoration seed. `setDecorationSeed`
is affine in the chunk origin:

```
decorationSeed = (16*cx*a + 16*cz*b) ^ worldSeed        a, b = nextLong()|1 from setSeed(worldSeed)
```

For a fixed world seed that is a 2D lattice mod 2^48 with ~2^43.7 legal chunk
coordinates, so a target seed is reachable with probability ~1/20, found by Gauss
reduction rather than search. And the target set is **world-seed-independent** —
a, b change, the set does not — so the cost of building it amortises across every
world seed tried afterwards. That is what makes 1/q achievable instead of the
`C_chunk / C_filter` ceiling that 6u ran into, and 6w's "9 chunk-generations per
trial" objection does not apply, because a candidate is one position whose spot
test needs that chunk's own noise, surface and carver walk.

**-24848077, 18720986 is 1.55M chunks from the origin.** Nobody searches there.
That is where a lattice solve lands, and it is the strongest evidence for how the
find was made.

### The lattice, built and verified

`rng/DecorationLattice` does the solve. With `a, b` from the world seed,
`low48(16*cx*a + 16*cz*b) == low48(target ^ worldSeed)`; every achievable left side
is a multiple of 16, so divide through and the congruence becomes
`cx + m*cz = v (mod 2^44)` with `m = b*a^-1`. That is a 2D lattice, reduced once per
world seed by Lagrange-Gauss, then one Babai rounding per target.

Two predictions, both confirmed over 200,000 random 48-bit targets on the find's own
world seed:

```
reduced basis (3712981, 651373) and (-503742, 4649650)
  low-4-bit reachable : 12521 (0.0626, predicted 0.0625)
  solved in the border: 9938  (0.794 of reachable, predicted ~0.8)
  verified against setDecorationSeed: 9938/9938, mismatches 0
```

The 1-in-16 is not a lattice property and cannot be optimised away: the low four
bits of any chunk's decoration seed are the world seed's own, because the block
coordinate is `16*cx`. Net yield is **|T|/20 candidate chunks per world seed**, and
since the target set does not depend on the world seed, the cost of building it
amortises over as many seeds as you care to try.

End to end, the lattice given nothing but the world seed and the decoration seed
returns the real find:

```
confirmed find is chunk -1553005,1170061, decoration seed 72846194777308
  lattice asked for that seed returns chunk -1553005,1170061 -> same cane RNG
```

### The filter is sound on the one case that can test it

6u's lesson was that an aggregate acceptance rate hides an unsound filter, and only
the confirmed find catches it. `ChainPrefilter` on decoration seed 72846194777308
with count 10 and feature index 5 reports **exactly 8**, from the raw LCG stream with
no terrain of any kind:

```
confirmed 8-tall find (decoration seed 72846194777308, index 5): filter says 8 -> ACCEPTED
```

That is three independent things at once: the filter keeps the only real 8-tall
known, the find is a plain 4+4 upward chain as section 5's mechanism predicts, and
the flattened-stream model of the feature is right about a chunk 1.55M chunks from
the origin.

### Built: `reverse`, measured at 6.6x

`ReverseSearcher` is the whole thing end to end — build the target set, lattice each
member into a chunk, generate that chunk and its eight neighbours, run the real
feature. Both searches on 12 threads on the same machine:

```
reverse 8:  453,158 candidates from 750,008 targets walked (0.604 each)
            8,294 candidates/s, 1,938 searched chunks/s
            952,839 chunks generated for 105,871 searched  (9.0x, as 6t predicted)
search:     8,636 searched chunks/s
```

A reverse-searched chunk is conditioned on the RNG side already being satisfied, so
it is worth 1/q = 29.5 of a box-scan chunk. Effective rate 1,938 x 29.5 = 57,200
against 8,636: **6.6x**. On the earlier estimate of ~5e10 chunks per 8-tall find,
that is 10 days here instead of 67.

Two things cap it at 6.6x rather than 30x, both understood and neither a bug:

- **1 target in 16.** Structural, and it costs nothing at runtime — targets are
  bucketed by their low four bits and only the matching bucket is walked.
- **9 chunks generated per chunk searched.** Candidates arrive scattered across the
  world instead of in a box, so nothing amortises. This is exactly 6t's objection,
  and it is now a measurement rather than an argument. The cheap biome gate already
  absorbs most of it — only 23% of candidates are searchable ocean, and the rest are
  rejected for 36 biome lookups — so what is left to win is the terrain test at a
  single position, worth up to another ~5x.

### Both known finds pass the pipeline, with their exact heights

`ReversePipelineTest` runs both ground truths through filter and lattice, since a
target set that misses a real find fails silently:

```
5-tall at 91,16,65:                  decoration seed 112095894509740, filter 5, lattice -> chunk 5,4
8-tall at -24848077,21,18720986:     decoration seed  72846194777308, filter 8, lattice -> chunk -1553005,1170061
```

The filter reports each one's height **exactly**, from the LCG stream with no terrain
— which is a much stronger statement than acceptance, and would be a surprising
coincidence if the flattened-stream model were wrong anywhere.

`DecorationLatticeTest` solves the congruence the slow way, one cz at a time across
the whole border, and requires agreement: 32 of 32 reachable targets found over five
world seeds, none invented. That also settles the candidate rate — 0.604 per
reachable target, not the 0.794 the first probe showed, because `solve` returns one
chunk and P(at least one lattice point in the box) is below E[count] = 0.8 for a
skewed basis. The first measurement was one lucky world seed.

### What is left

1. `FrozenOceanSurfaceBuilder`, without which this class of find is unreachable
   whatever the search strategy. The confirmed find is in one.
2. The single-position terrain test, worth the remaining ~5x: the chain names one
   (x, z, y), so the question is whether that column has soil, air, and water beside
   at the base and at base+3 — not whether the chunk is worth searching. 6u measured
   a terrain-free carver walk at 68 us against 110 for a whole chunk, but that walked
   a whole chunk; five columns should be far cheaper.
3. Whether any of this pays at height 7, where q = 0.157 gives only 6.4x before the
   9x handicap, i.e. probably not.

## 6ad. Two speedups on the reverse search, and where the rest of it is

`reverse` shipped at 6.6x (6ac). Two changes took it to **13.9x**, and the profile
that came out of them says the remaining gain is all in one place.

### Restricting the chain to the depth where spots actually are: 2.15x

The chain filter accepted a run starting anywhere in y 11..64. Real spots do not live
there — of 2,847 stackable spots over 3.18M ocean chunks, soil y runs 8..51 with 88%
between 12 and 34:

```
  y  8-11:   4.0%      y 24-27:  17.4%      y 40-43:   2.1%
  y 12-15:  14.8%      y 28-31:  13.9%      y 44-47:   0.7%
  y 16-19:  17.0%      y 32-35:   8.3%      y 48-51:   0.1%
  y 20-23:  17.6%      y 36-39:   4.2%
```

q scales with the band's width, and the finds given up scale with the spot mass
outside it, so cost per find goes as width/mass. That is worth 2.06x at its best and
the curve is flat for bands 15 to 23 wide, so the default is the forgiving end:
cane base y 13..35, 88% of the mass, q(>=8) **3.39e-2 -> 1.57e-2**, measured 2.15x.
Only the *start* of a chain is banded — an 8-tall run beginning at y=35 has its upper
column at y=39, and banding the candidates instead of the chain starts would break
chains rather than narrow the search.

### The cold biome lookup, and a fix worth only 10%

A single `noiseGen` lookup at a scattered location costs **116 us**; the same lookup
with the layer caches warm costs **1.67 us**. The box scan never sees this because its
chunks are neighbours; the reverse search jumps millions of blocks between candidates,
so it pays the pyramid build every time. `searchRegion` did 36 of them per candidate
before it could decide the region held nothing searchable, so `reverse` now does the
one lookup that decides it — the candidate's own chunk — and skips the rest.

Predicted 4x. **Measured 10%.** The biome bill was never the problem:

```
per candidate, cold scattered location:
   36 noiseGen (candidate scan) :  135 us   <- one cold pyramid, then 35 warm ones
 2304 voronoi (3x3 columns)     :  332 us   <- 0.14 us each once quart is warm
  144 noiseGen (same area)      :    3 us
```

### Where the time is, measured rather than guessed

```
per candidate: lattice 0 us, biome gate 157 us, chunk 1189 us
               -> 1346 us accounted of 1394 thread-us actual
per searchable-ocean candidate: chunk 4005 us for 7.1 generated chunks
```

The accounting closes at 97%, and the answer is dull: it is the neighbourhood, exactly
as 6t predicted. Per *generated* chunk the reverse search is already **cheaper** than
the box scan — 563 us against 885 us, presumably from the smaller region's locality —
so there is nothing left to win inside the generation code. The whole penalty is the
ratio: **7.1 chunks generated per chunk searched, against 1.57 for the box scan.**

### What that means for what is worth doing next

- The single-position terrain test is not one optimisation among several, it is the
  only one left that matters, and it is worth the whole 4.5x: 13.9x -> ~60x. The chain
  names one (x, z, y), so the question is whether that column has soil at base-1, air
  at base and base+4, and water beside base-1 and base+3 — five columns, not nine
  chunks.
- Shrinking the region, trimming the reset memset, cheaper voronoi: all worth a few
  percent each. Not worth the risk to a validated pipeline.
- Reversing the LCG directly instead of sampling forward for the target set: worth
  nothing. The set costs 4.4 ms a member to build and is reused across every world
  seed, so it has already amortised to zero.

## 6ae. The position filter: 6.9x, and the reverse search lands near 95x

6ad ended with one thing left worth doing, and it was worth what it promised.

### The question, and why it needs no terrain

A chain names an (x, z) and the base y of each of its columns, and **every one of
those bases has to be air**. Below sea level, air has exactly one source:

- the noise fills every non-solid block under y=63 with *water*, not air —
  `Terrain.column` passes WATER as the fluid;
- the LIQUID-step carvers never call `setCaveAir`. `Carver`'s underwater branch
  writes water, or returns at y<=10;
- lakes are sealed and structures keep their water (section 5), and chunks near a
  mineshaft start — the one other thing that writes air down there — are skipped by
  the search already.

So cane at y in 11..62 **implies** an AIR-step carver reached its base, and a position
no air carver reaches cannot hold cane. Rejecting on that loses nothing.

And the walks need no terrain at all. Where a tunnel goes is pure RNG; only its
*decisions* read the world. Run against a permissive stub — everything replaceable,
nothing water — a carver carves a superset of what it really carves. That is the same
argument and the same `Stub` as `CarverWalkFilter`, and crucially **it reuses the
validated carvers unchanged**: `AirCarveProbe` supplies a different `Carver.Target`,
not a second implementation of anything. None of the bit-exactness risk that 6ab is
about applies here.

The three places it could be wrong, all resolved in the permissive direction: chains
that overflow the enumeration cap, chain positions outside the walked chunk, and
`isWater`/`canReplace` on the stub. Each accepts rather than rejects.

### Measured

```
                    before      after
per candidate       1388 us     202 us
  biome gate         154         110
  air probe            -          49
  chunk             1189          33
candidates/s        8,643      59,356
generated chunks per ocean candidate   7.1       0.15
```

**The probe rejects 97.88% of searchable-ocean candidates for 49 us.** Because it is
sound, finds per second scale exactly with candidates per second, so that is a
straight **6.9x** — and it collapses the 7.1-chunks-per-search handicap that 6ad
identified as the only remaining cost, to 0.15.

On top of 6ad's 13.9x, the reverse search now stands at roughly **95x** the box scan.
Turning that into a time per find needs R_8 (1.22e-4 8-capable spots per chunk) and
P_8 (~2e-7 per decoration seed), both measured but both estimates, and it comes out at
**order hours rather than the 67 days** brute force wanted. That is the first number
in this file that makes an 8-tall a thing you can go and get rather than a thing you
wait for.

### The test that guards it

`ReversePipelineTest.theAirProbeAcceptsTheConfirmedEightTall`. The probe is silent
when it is wrong — a search that runs forever with healthy-looking rates — so the
only real 8-tall is the guard:

```
air probe: chain at -24848077,18720986 bases y=21 y=25 all carved -> accepted
```

Note what that line also settles. The chain filter, from the LCG stream alone, put the
chain at exactly the block the real server has cane in, with bases 4 apart — so the
find is a 4+4 upward chain, which until now was inferred from the block dump rather
than known.

### What is left

1. `FrozenOceanSurfaceBuilder`. Now the largest known loss by a wide margin, and the
   confirmed find is in one.
2. The water half of the position test. Water beside base-1 and base+3 is needed too,
   and requiring it to come from a LIQUID-step carver would cut further — but ~2% of
   spots sit on the sea floor where sea fill supplies it, so unlike the air test this
   one trades coverage. Measure before adopting.
3. The biome gate is now the largest single cost at 110 us of 202, and it is one
   cache-cold pyramid build per candidate. Nothing obvious is left in it.

## 6af. The soil condition is also RNG: another 5.9x

Same move as the depth band in 6ad, applied to the other terrain requirement. A chain's
first column needs soil under its base, and 98% of real spots stand on `ORE_DIRT` blob
dirt (6i). The blobs run from the **same decoration seed as the cane** —
`setFeatureSeed(ds, 0, 6)` against the cane's `(ds, 5, 8)` — so "could a blob put dirt
here" is answerable when the target set is built, for free, instead of by generating the
chunk and looking.

### The draw count is terrain-dependent, but boundedly

`OreBlob.place` bails before drawing its 33 radii when the blob's box sits entirely
above `OCEAN_FLOOR_WG`, so the stream is not a pure function of the seed. It is close
to one: every blob spends 6 `next()` calls, plus 66 more if it placed. The state after
k blobs depends only on **how many** placed, not which, so blob k begins at
`6k + 66m` for some `m <= k` — 55 combinations, enumerated exactly as `ChainPrefilter`
enumerates its success shifts. Enumerating m over-approximates, which is the accepting
direction.

`nextInt(3)` is the one draw here that can take Java's rejection retry and shift
everything after it. About 9.3e-10 per call, so it never happens — but it is detected
explicitly and the seed accepted, because a desynchronised read would be a *false
reject*, which is the direction that silently loses finds.

### Measured

```
q(>=8) with the depth band          1.5834e-02
q(>=8) with the band and the soil   2.2211e-03      7.13x tighter
per candidate                       205 us  (was 202)
```

The search-time cost is unchanged — the filter runs at target-set build time, which
rises from 5.9 ms to ~42 ms a member and amortises over every world seed afterwards.

**It costs coverage, and this is the honest part.** A blob reaches 6.7 blocks, so a
*neighbouring* chunk's blob can supply the dirt — and its decoration seed needs `a` and
`b`, hence a world seed, which is not chosen yet when the set is built. Measured over
4.5M placed blocks: **17.9% of dirt lands outside its own chunk**, and by symmetry the
same share of dirt inside a chunk came from a neighbour. So those finds are lost.

Net: `7.13 x 0.821 = ` **5.9x**, taking the reverse search to roughly **560x** the box
scan, and an 8-tall from ~3 hours to ~30 minutes.

### Validation

`DirtBlobFilterTest` drives the real `OreBlob` and requires the filter to accept every
block it actually placed, at three ocean-floor heights so that different `m` paths
through the stream are exercised:

```
accepted 487215 real dirt blocks, 0 rejected
one fixed block accepted for 9.55% of seeds
17.9% of placed dirt lands outside its own chunk (802608 of 4485367)
```

and the confirmed 8-tall survives — it stands on dirt at chunk-relative (3,20,10), which
the filter keeps.

The three figures are mutually consistent, which is the strongest check available:
chain acceptance 13.94%, fixed-block acceptance 9.55%, and ~5% of chains falling outside
the chunk and being accepted untested. `0.05 + 0.95*0.0955 = 0.14`. Chain positions sit
further from the chunk edge than a single placement would, because a chain needs two
invocations to agree on one (x, z).

### What is left

1. `FrozenOceanSurfaceBuilder`, still the largest known loss and now by a long way.
2. The neighbour-blob 17.9%. Recoverable only by moving the soil test to search time,
   where the world seed is known and all nine decoration seeds are computable — but
   that trades the 7.13x for about 1.4x, so it is the wrong side of the deal.
3. The biome gate, now 105 us of 205 per candidate and the single largest cost.

## 6ag. Measuring R and P exactly, and three corrections

The reverse search found one 7-tall in 77 minutes where I had predicted ~13. That gap
turned out to be mostly my arithmetic, not the search, and chasing it produced better
numbers than the ones it replaced.

### The exact terrain condition

`countGeometry` now computes `maxRun`: the tallest contiguous run the terrain permits
from a base, maximised over every column composition. That is the real condition, and
it is looser than the contiguous "face" the earlier measurements used, for two reasons
the file had already recorded separately without joining up:

- `ColumnPlacer` writes upward **unconditionally**, so only each column's *base* needs
  air. The blocks above may be solid and simply get overwritten.
- `needWater` is checked below each base, so water is needed at `base-1`,
  `base+h1-1`, ... and nowhere in between. A 3+4 chain needs water beside `base+2`,
  which a face test insisting on water beside `base+1` never sees.

Two free checks on it, over 844,600 searched chunks: spots permitting >=4 comes to
149,794, exactly the count of legal spots, since one column can be 4 unaided. And
>=5 and >=6 are both 840, exactly `stackableRelaxed` — structurally, once any
continuation exists the last column can be 4, so >=5 implies >=6. Both hold.

### The numbers, direct rather than decomposed

`probe:N` gated on `maxRun >= report` instead of on `stackable`, which is not a
superset of it:

| | height 7 | height 8 |
|---|---|---|
| R, chunks permitting it | 3.528e-04 | 1.752e-04 |
| P, per decoration seed | 5.369e-07 | 8.446e-08 |
| per chunk | 1.894e-10 | 1.480e-11 |
| chunks per find | 5.28e9 | 6.76e10 |

### Correction 1: the scenario is a flat 2x optimistic, at every height

I had said the hand-built isolated spot would be increasingly optimistic with height,
because real permitting spots cluster at the threshold while a 17-block face accepts
any composition. **The data refutes that.** Scenario against measured:

```
h>=5:  1.1e-5 / 5.4e-6  = 2.04x     (6i)
h>=7:  1.08e-6 / 5.369e-7 = 2.01x
h>=8:  1.82e-7 / 8.446e-8 = 2.15x
```

Constant. Which is more useful than my story would have been: the scenario **is** a
valid predictor of P, as long as it is divided by two.

### Correction 2: the estimate was 1.6x out, not 5x

Mid-investigation I claimed the prediction was ~5x wrong and P7 was near 1.1e-7. Both
were wrong, and wrong because I extrapolated R7 from a 12-chunk sample. With the real
numbers the predicted rate is 20.5 min at height 7, against the ~13 min I first quoted
— 1.6x optimistic. Observing 1 find in 77 minutes against an expected 3.75 is a Poisson
p of 0.11: on the unlucky side, not evidence of a defect.

### Correction 3: the speedup was understated by 4.6x

Computed from measured quantities only, with no chained ratios:

```
box scan   : 8,636 chunks/s    x 1.480e-11 per chunk     = 1.28e-7 finds/s  -> 90 days
reverse    : 68,000 candidates/s x 4.80e-9 per candidate = 3.27e-4 finds/s  -> 51 min
```

where `P(find | candidate) = p * mass * (1 - leak) / q`, mass 0.878 for the depth band
and 0.821 for the neighbour-blob leak. That is **~2,500x**, not the 560x recorded in
6af. The 560 came from chaining a 13.9x that was expressed in searched-chunks/s with a
6.9x expressed in candidates/s — two different units, so the product meant nothing.
The individual measurements (q, candidates/s, filter selectivities) were all sound; the
composition was not.

**The lesson for this file: measure the end quantity, do not multiply ratios.** Both
errors here came from composing correct factors that were not commensurable, and they
pointed in opposite directions so the narrative looked plausible throughout.

### Where this leaves it

An 8-tall is ~51 minutes on twelve threads against ~90 days for the box scan. The
binding constraint is no longer speed: at 68,000 candidates/s we produce candidates far
faster than we can trust them, and 6ab's ~1-in-3 in-game failure rate is now the thing
worth attacking. The 7-tall found at -7996270,18,-6279960 on seed 5180 is exactly that
case — simulator air where the game has water at y=18..21.

## 6ah. Ranking targets, and a correction to 6af's coverage figure

`ProbabilityProbe` now records the chain properties of every real find it produces, and
of the accepted population, so target quality is read off finds instead of argued about.
256 finds at height 7, against a 400,000-seed population sample.

### The soil filter costs 56.3% of finds, not 17.9%

```
finds the soil filter would reject: 144 of 256 (56.3%)
```

6af priced that loss at 17.9% and claimed a net 5.9x. Both were wrong. 17.9% was **the
wrong quantity**: it measured how much placed dirt lands outside its own chunk, not how
often a real find's soil is something an own-chunk blob could have supplied. Those
differ whenever the soil is not ore-blob dirt at all — a possibility waved away on the
strength of 6i's "98% of spots are inside the terrain", which does not imply blob dirt.

Corrected: selectivity 7.13x, retention 0.437, **net 3.12x**. Still worth keeping, and
still the second largest win in the reverse search, but half what was recorded.

That also closes the gap 6ag left open. With the measured retention:

```
P(find | candidate) = 1.516e-10 * 0.878 * 0.437 / 1.1515e-2 = 5.05e-9
                    -> 50 min per find at 66,000 candidates/s
```

Observed: 1 find in 155 minutes, against 3.1 expected. Poisson p = 0.19, which is
ordinary. The model and the search now agree, and no defect is left to look for. Every
step of the way the error was a factor composed from something measured on the wrong
population — three times in one day, in both directions.

### The chain model is nearly complete

```
finds whose chain the filter cannot see at all: 1 of 256 (0.4%)
```

So the flattened stream, the `{0,2,4,6}` shift enumeration and the upward-chain-only
assumption between them miss 0.4% of real finds. The hypothesis in 6ag that chain
invisibility explained the shortfall was wrong, and wrong in the reassuring direction.

### What predicts a find, measured

```
earlier placements assumed     of finds     of pop    weight
  0                            94.118%    60.745%     1.55x
  1                             2.745%    24.257%     0.11x
  2                             1.961%    10.348%     0.19x
  3                             1.176%     4.650%     0.25x

columns in the chain           of finds     of pop    weight
  2                            89.804%    77.737%     1.16x
  3                            10.196%    22.114%     0.46x
  4                             0.000%     0.150%     0.00x

base y                         of finds     of pop    weight
  8..13                         5.490%     4.495%     1.22x
  14..19                       24.314%    26.133%     0.93x
  20..25                       26.275%    26.006%     1.01x
  26..31                       30.980%    25.843%     1.20x
  32..37                       12.941%    17.523%     0.74x
```

**Shift dominates.** A chain assuming no earlier placement in its chunk carries 94% of
the finds but only 61% of the set, because a prior success is rare (~1.1e-3 cane columns
per chunk) and three of them rarer still. Keeping shift 0 alone costs 6% of finds to
drop 39% of the set: **1.55x**.

**Fewer columns is better**, as predicted from the water requirement at each junction:
two columns 1.16x, three 0.46x, and four produced no finds at all in 256.

**Base y is spent.** 0.74x to 1.22x across the band, so the depth band of 6ad already
banked that signal and there is little left in it.

### Not multiplying these

1.55x and 1.16x are not independent — a two-column chain is likelier to sit at shift 0 —
and this file has now been wrong three times from composing factors that looked
composable. The combined filter has to be built and its q and retention measured
together, on finds, before any number goes here.

## 6ai. A GPU build for one architecture is a silent 4.5x slowdown

A 3060 owner ran the searcher and it said `no usable GPU`. The card was fine, the driver
was fine, the toolkit was fine. The binary was built with `-arch=sm_89`, which is Ada
only, so it had no code for an Ampere card.

Three separate faults had to line up to make that unreadable, and each is worth naming.

**The kernel did not notice its own launch failing.** The code checked
`cudaDeviceSynchronize()`, which is the usual advice, and that check is useless here: when
a launch is rejected there is nothing queued, so the synchronise returns `cudaSuccess`.
The launch error sits in `cudaGetLastError()` instead. So the process exited 0 having
tested every seed and accepted none — indistinguishable from a filter that legitimately
rejected everything.

Reproduced deliberately, by building for `sm_50` and running on the 4080:

```
progress 4096 4096 0
tested=4096 accepted=0 dropped=0        <- exit 0
```

With the launch checked:

```
kernel launch failed: no kernel image is available for execution on the device
this binary has no code for NVIDIA GeForce RTX 4080 (compute 8.9); rebuild it
with -gencode arch=compute_89,code=sm_89
```

**The stderr carrying that message was discarded.** `GpuChainFilter` only attached a
reader when it had a progress consumer, and the detection probe has none — so the one call
whose failure needed explaining was the one call that threw the explanation away.

**And the fallback was silent.** `detect()` returned null, the CPU path took over, and
nothing said why. The CPU path is about 4.5x slower, which is slow enough to matter and
fast enough not to look broken.

### The fix

Several `-gencode` rather than one `-arch` (`cuda/build.bat`): Turing, Ampere and Ada
compiled in, plus `compute_89` PTX so a newer card JITs instead of failing.

**And then no build script at all.** The kernel now ships inside the jar as a resource,
unpacked to a temp file on first use. That works because nvcc links the CUDA runtime
statically: the binary imports only `kernel32.dll` and `nvcuda.dll`, and the latter arrives
with every NVIDIA driver. No toolkit, no compiler, no script on the user's machine. A
binary beside the jar still wins, so a local rebuild overrides the shipped one.

This matters more than convenience. Any fast path reached only by running a build script is
a fast path most people will not have, and the failure is invisible: they get correct
results, 4.5x slower, and no reason to suspect anything. Shipping the binary makes the fast
path the default and the slow path the exception that has to explain itself.

The cost is a binary in the tree, which can drift from its source — and had already done so
once, when a failed compile left a stale exe running and the measurements looked fine.
`BundledKernelTest` fails the build if `find_targets.cu` is newer than the shipped
executable, and `build.bat` refreshes both copies. `.gitignore` needed a negation for it:
`*.exe` had silently excluded it, which would have produced a clone whose jar built fine
and shipped no kernel.

Multi-architecture costs nothing at runtime. Measured on 200M seeds, single-arch `sm_89`
26.71s against multi-arch 25.55s, with identical accept counts — the unused cubins are
dead weight in the fatbin, not in the instruction stream. Only the file grows.

`detect()` now records why it failed and the caller prints it, so the friend's case
diagnoses itself and prescribes its own fix.

### The general shape

An error path that only executes on hardware the author does not own gets no testing from
ordinary use, and this one degraded into a plausible-looking success. Building a
deliberately broken binary took one command and turned a report of "no usable GPU" into
the exact line of the fix. Worth doing whenever a failure is reported from a machine that
is not to hand.

## 6aj. The simulator is worse far from the origin, and that is where every reverse find is

The reverse search only ever produces far-out coordinates — that is the point of inverting
`setDecorationSeed`. So its accuracy at 8 million blocks matters more than its accuracy near
spawn, and the two are not the same.

Against 59 ground-truth `features`-status chunks around x=-8.0M, z=-6.3M on seed 5180
(`ProtoValidator`, exported from a real 1.16.1 server):

```
exact category match: 97.9217%   (59,472 cells)
simulated AIR that is really WATER : 11 / 9484 air  (0.1160%)
simulated WATER that is really AIR :  3 / 14351 water (0.0209%)
```

The first line is the class implicated in false hits (6ab). Near spawn it measured
**0.0205%** over 281 chunks. Out here it is **0.1160% — 5.7x worse.**

### Lava was the wrong suspect

The simulator assumes lava exists only below y=11 (`Carver` returns early there), and the
real world has lava up to y=30 — inside the y 13..35 depth band the reverse search draws
chain bases from. Simulated water where the game has lava would satisfy the cane's water
condition when the game does not, inventing a spot outright.

It does not happen. `LAVA` is now its own export category rather than being lumped into
`OTHER` with coral and leaves, and in the scored window there are 189 lava cells, **none in
the band**, and zero disagreements of either kind. Hypothesis dead; the instrumentation
stays, because "we checked and it is not this" is worth keeping.

Worth noting how close that came to never being asked. Lava was invisible while it sat in
`OTHER` alongside 9,085 cells of coral, leaves and mineshaft cobweb. Categories that mean
"something else" hide exactly the questions nobody has thought to ask yet.

### The open suspect: float precision

`float` has 24 bits of mantissa, so its ulp reaches 1.0 at x = 8.4M and 2.0 at 16.8M. The
world border the reverse search reaches is ±29.9M. Any worldgen arithmetic done in `float`
therefore cannot resolve individual blocks out there — and section 7 already records
`OreFeature.place` computing its endpoints in float and being wrong by up to a quarter of a
block at a few million, with the warning to assume every other transcription has the same
class of bug until checked far from the origin.

That warning was never acted on. The next step is to find every `float` in the carvers,
surface builder and terrain, and check each against the decompiled source at 8M — not to
replace them with `double`, because the game's own floats are the ground truth. A simulator
using `double` where the game uses `float` diverges exactly where our finds live.

### The ground truth for this

`tools/export_proto.py <world> <out.bin>` reads the seed from `level.dat` itself now. It
used to write a hardcoded `1` for the caller to patch, and a caller who forgot got a report
of `0/0 cells, 0.0000% accuracy` — a wrong-seed world has no ocean chunks where the real one
does, and nothing in the output said so.

## 6ak. What every height from 7 to 25 costs, and where the game runs out

The question was "how long do we wait for an N-tall, for N = 7..25". It needed R and P at
every height, and the useful part turned out to be that the two decompose cleanly: the
terrain barely resists at all and the RNG resists enormously.

### The RNG side, modelled and then checked against 2e10 trials

`yspread` is 0, so all 20 tries of an invocation share one y. A chain therefore needs one
*distinct invocation per column*, each independently drawing the same (x, z) and the exact
stacking y. That gives a per-column factor

```
A = (1/126) * (1/256) * sum over the 9x9 try offsets of [1 - (1 - t(dx)t(dz))^20]
  = 5.2479e-04
```

and `P(>=N) = sum over k of C(10,k) * A^k * P(sum of k column heights >= N)`, with heights
2, 3, 4 at 11/18, 5/18, 2/18. Measured on one ideal isolated spot, 2e10 decoration seeds:

| N | columns | model | measured | ratio | events |
|---|---|---|---|---|---|
| 4 | 1 | 5.955e-4 | 5.924e-4 | 1.005 | 11,847,374 |
| 5 | 2 | 7.782e-6 | 7.758e-6 | 1.003 | 155,150 |
| 7 | 2 | 9.314e-7 | 9.322e-7 | 0.999 | 18,644 |
| 8 | 2 | 1.610e-7 | 1.586e-7 | 1.015 | 3,171 |
| 9 | 3 | 3.389e-9 | 3.250e-9 | 1.043 | 65 |
| 10 | 3 | 1.051e-9 | 9.500e-10 | 1.106 | 19 |

The three-column term was the one being extrapolated on faith and it holds to 4-11%. One
run of exactly 13 appeared where 0.015 were expected (Poisson p = 1.4%); across twelve bins
that is ordinary, but it sits exactly at the four-column boundary and is the first place the
model could be wrong.

Real terrain divides this by the haircut 6ag measured -- 0.69, 0.58, 0.53 at N = 5, 7, 8.
Held flat at 0.55 below, which is mildly optimistic since it is still deepening.

### The terrain side barely resists

`diag` over 3,129,022 searched ocean chunks. The tail of `maxRun` decays only **0.668 per
block**: 3.95e-4 chunks permit a 7, and ~2.4e-7 permit a 25. Across N = 7..25 the terrain
term moves three orders of magnitude while the RNG term moves eighteen. **R is not the
problem at any height and never becomes it.**

### The answer

| N | cols | chunks/find | box scan | reverse | target build |
|---|---|---|---|---|---|
| 7 | 2 | 4.9e9 | 3.6 d | 4.5 h | secs |
| 8 | 2 | 5.3e10 | 38 d | 10.3 h | secs |
| 9 | 3 | 4.5e12 | 9 y | 2.4 d | secs |
| 10 | 3 | 2.1e13 | 42 y | 3.6 d | secs |
| 11 | 3 | 1.6e14 | 309 y | 5.8 d | 2 min |
| 12 | 3 | 1.7e15 | 3,290 y | 9.2 d | 14 min |
| 13 | 4 | 8.3e16 | 1.6e5 y | 38 d | 2.7 h |
| 15 | 4 | 4.3e18 | 8.4e6 y | 85 d | 2.6 d |
| 17 | 5 | 2.6e21 | 5.1e9 y | 3 y | 124 d |
| 20 | 5 | 3.7e24 | 7.4e12 y | 16 y | 90 y |
| 25 | 7 | 2.1e31 | 4.1e19 y | 8,369 y | 9.8e5 y |

24 threads. The staircase is entirely the column count -- 8->9 is x85 and 12->13 is x50,
both crossing a column boundary.

**The reverse search is far flatter than the box scan, and that is the result.** 7 -> 12
costs the box scan 340,000x and costs reversal 49x, because `q(N)` falls almost as fast as
the find rate and reversal divides by it. `q` measured directly to N = 15 over 60M
decoration seeds: 1.575e-1, 3.393e-2, 2.176e-3, 7.167e-4, 1.548e-4, 2.303e-5, 1.933e-6,
4.833e-7, 8.333e-8 for N = 7..15, reproducing 6ac's figures at 5, 7, 8 and 10.

Caveat on the reverse column, in the spirit of 6ag's lesson: it is anchored on the only
end-to-end observation there is -- two finds in 232 minutes on 12 threads (6ag + 6ah) -- so
it carries +/-70% from the anchor alone. Everything else in the table is measured.

Above N ~ 16 the binding cost stops being the search and becomes the target build, which
scales as 1/q.

### Where the game runs out

Two bounds, agreeing. `2^48 * q(N) < 1` -- no decoration seed can chain the height at all --
extrapolates to N ~ 24. Counting trials gives 22 to 25 depending on how much the upper 16
seed bits buy, and that is the open part:

- decoration seed, noise field and **carver walks are all low-48 properties**
  (`setLargeFeatureSeed`, `CarverConfig:63`);
- the upper 16 change only the biome map, which reaches terrain solely through
  `getDepthAndScale` (`TruncatedNoise:378`);
- every searchable ocean has **scale 0.10** and depth either -1.0 or -1.8.

So a sister seed re-rolls the sea floor by the ocean/deep-ocean difference on a *fixed*
carver walk. **Settled in 6al, and the answer is ~24-25**: the 3.96e27 alternative was an
error, because it conflated independence with expectation and correlation cannot change an
expectation. Read 6al rather than the bracket above.

### Per-candidate cost, and two things that do not work

Measured under load (an unrelated 24-thread run sharing the box):

```
per candidate: biome gate 215 us, air probe 48 us, chunk 57 us  -> 320 of 348 thread-us
6ae, uncontended:           110              49            33   -> 202
```

The air probe is unchanged at 48 vs 49; the gate nearly doubled. The probe is compute bound,
the gate is a cache-cold pyramid build and is memory bound -- so **the dominant cost is also
the one that degrades when workers share a machine**, which matters for any distributed plan.

**Reordering does not help.** `CAVE_LAND = 0.1429 > CAVE_OCEAN = 0.0667` on the same
`nextFloat()`, so `walk(..., ocean=false)` carves a superset and the probe *could* legally
run before the gate. But 48 us is amortised over all candidates while the probe only runs on
the 29.7% that are ocean -- its true per-run cost is 165 us, above the gate's 110. Moving it
first: 165 + 110*0.025 + 33 = 201 us against 192. Slightly worse.

**Carver reversal attacks the wrong term.** The probe is 49 us of 202, so making the carver
test free caps at 1.32x. It only pays if it changes the pipeline shape rather than the
per-candidate cost -- and note the probe is a *sound* filter, so generating only carver-good
candidates adds no finds, it only stops paying for rejects. That ceiling is the 31,600 us
currently burned per probe-passing candidate against ~200 us, i.e. ~150x, and it is available
only if a joint solve is as cheap as the current sub-us Babai step.

Which it may not be. `setDecorationSeed` combines its terms by **addition mod 2^48** -- hence
a lattice, hence `DecorationLattice`. `setLargeFeatureSeed` combines by **XOR**. Constraining
both at once is a mixed additive/bitwise system with no lattice to reduce, which is
presumably why population+carver two-chunk resisted. And the counting is against it: a second
congruence mod 2^48 divides the 0.8 border solutions per target by ~2^48, so for a fixed
world seed there is essentially never a joint solution inside the border. Any such solver has
to carry the world seed as a third unknown.

### Three reported 11-talls are one target

Reported: `-3944851142443026541` at 2361850,28,15559736; `3908902645157422835` at
-9682102,28,22080008; `216080956173563027` at -6193110,28,-22740088. All three reproduce in
the simulator as 4+3+4 from invocations 0/1/2.

All three carry **decoration seed 109089712118451**, with byte-identical traces. Same-y and
same relative offsets are consequences of that, not independent evidence. And all three world
seeds are congruent to 3 mod 16 -- 1-in-256 by chance -- which is the bucket constraint: a
world seed only reads `buckets[seed & 15]`, so **the effective target set per seed is T/16**.
If bucket 3 holds one member, every find from a seed congruent to 3 mod 16 is forced to be
that decoration seed at that y.

The alternative explanation, that some targets are far more findable than others, was tested
and is false. A target's chain geometry and its `DirtBlobFilter` verdict are functions of the
decoration seed alone, so they precompute; one `AirCarveProbe` walk then serves the whole set
at once. Over 419 in-chunk targets at height 11 and 60,000 walked chunks:

```
best 1245 passes, median 998, worst 533      2.3x spread, CV 17.6%
top  1% of targets ->  1.2% of passes
top 50% of targets -> 57.1% of passes
overall pass rate 1.62%  (the live search measures 2.15%)
```

Near-uniform. **Ranking is not the lever; set size is**, and it is 16x smaller per seed than
it looks.

Two measurement traps worth recording, because both produced dramatic wrong answers first.
Counting soil-rejected seeds as zero-yield targets is wrong -- the real set applies the soil
filter at build time, so they are not members; that alone reported 88% of targets as dead.
And `isCarved` returns **true** outside the walked chunk, so a chain sitting in a neighbour
passes every trial. 25.8% of the set is in that state -- those are not high quality, they are
untested, and they cost a full chunk generation every time. Together the two artefacts gave a
dispersion of 18,422 where the truth is 30.

### Still unverified

None of the three 11-talls has been checked in game. They sit 15.7M, 24.1M and 23.6M blocks
out -- past where 6aj measured the simulator 5.7x worse, with float precision still the open
suspect and float ulp already 2 blocks by 16.8M. The terrain rolls independently in each, so
at 6ab's ~1-in-3 failure rate P(all three false) is ~4%; the chain logic does not, so one
in-game check settles the shape for all three at once.

## 6al. Sister seeds: the ceiling settled, and 4.2x from the upper 16 bits

6ak left the existence ceiling as a 22-to-25 bracket, turning on how independent seeds
sharing their low 48 bits are. That is now measured, and it also turned out to price an
optimisation.

### What a sister actually shares

Verified directly rather than argued. Six seeds sharing low-48 `...890115`:

```
upper   world seed          decorationSeed      lattice(cx,cz)     carved
0       1234567890115       109089712118451     1509158,-240279    00000
1       282709544600771     109089712118451     1509158,-240279    00000
...
5       1408609451443395    109089712118451     1509158,-240279    00000
```

The lattice solution, the decoration seed at it, and the air probe are **identical**.
`setDecorationSeed` XORs the full 64-bit seed but feeds the result through `setSeed`,
which masks to 48; `setLargeFeatureSeed` does the same. Only the biome map varies.

### How independent they are

Two arms, same statistic: SISTER varies the upper 16 at a fixed low-48 seed and chunk,
CONTROL varies the low-48 seed at the same chunk and is independent by construction.

```
                        within/total   all-identical   P(another stackable | one is)
SISTER                      0.793          86.3%              0.5597   (11 families)
CONTROL                     0.807          85.4%              0.0000   (28 families)
base rate of stackable                                        1.1e-03
```

The two statistics disagree, and both are true. Legal spots — essentially "is there a
floor with soil beside water" — re-roll almost freely, because depth/scale moves the sea
floor by the ocean/deep-ocean difference. The **rare** geometry does not: it is cut by the
carver walk, which is fixed across sisters, so given one sister has a stackable spot, 56%
of the others do too against a base rate of 0.1%.

### The ceiling: ~24-25, and 6ak's 22 was wrong

The correlation is real but it does not do what 6ak used it for. Expected count is
`sum over (world, chunk) of P(N-tall)` and **correlation cannot change an expectation**.
6ak replaced 4.80e31 trials with 3.96e27 on independence grounds; that conflated
independence with expectation and was simply an error. The original figure stands:

```
E[count] = 4.80e31 x rate(N)        ->  E = 2.3 at N = 25, 0.22 at N = 26
```

Clustering does lower P(at least one) relative to E[count], so the honest ceiling is
**~24-25** — not 22, and not the 22-25 bracket.

### The same fact is worth 4.2x

If the lattice, the decoration seed and the carver walk are all low-48, they can be
computed **once** and reused across every sister. Then the biome gate — 54% of the
per-candidate cost, and the term that degrades worst under load — runs only on what the
probe kept, instead of on everything. `--sisters=<n>` does this.

The probe has to be biome-blind to be shareable, which is sound in the accepting
direction: `CAVE_LAND = 0.1429` against `CAVE_OCEAN = 0.0667` on the same `nextFloat()`,
so the land walk fires on a strict superset of ocean start chunks and carves a superset.
No find can be lost to it; it costs ~17% more generated chunks.

Measured, 8 threads, matched candidate counts:

```
              candidates/s   gate   probe   chunk   total    searched chunks/s
n = 1               52,639   93 us   26 us   31 us   151 us        288
n = 64             220,995    3 us    2 us   29 us    34 us      1,415     4.2x
n = 256            211,640    4 us    0 us   31 us    35 us      1,287
n = 1024           204,552    3 us    0 us   29 us    33 us      1,134
```

**Flat past 64**, so there is no reason to sweep all 65,536: chunk generation is 29 us of
33 and amortises over nothing. Beyond that, sisters are 0.56-correlated on the rare
geometry, so exhausting one family concentrates effort on near-identical worlds — the same
expectation with more variance than spreading over more low-48 families.

### The 44x I first claimed was arithmetic, not measurement

I projected 44x from "per sister, 1.4 ms setup + 19 survivors x 110 us over ~750
candidates". That omitted chunk generation entirely, which is per candidate, does not
amortise, and is now 85% of what is left. The correct accounting was available before
building anything: 151 us drops to gate 3 + probe 2 + chunk 29 = 34, which is 4.4x, and
4.2x is what the run gives.

Two benchmarking traps found on the way, both of which produced confident wrong numbers.
Four low-48 seeds across eight threads leaves half the threads idle while the reported
`thread-us` figure assumes all are busy, inflating per-candidate cost 2x — the give-away is
"accounted" and "actual" diverging, and they agree once the seeds outnumber the threads.
And `LayerCaches.enlarge` costs 301 us against 38 us for the biome source it enlarges,
which is worth knowing but was not the bottleneck it looked like.

## 6am. A double-precision terrain kernel: measured twice, because the first time was wrong

Terrain noise is the biggest single cost left, so it is the obvious thing to put on the
GPU. The first measurement said it was pointless. That measurement was taken on a card a
Steam game and Wallpaper Engine were holding at 99% and 300 W, and it was wrong by 3.4x.

### Where the time is

Splitting the chunk build, per searched chunk:

```
noise             2653 us   54%
carvers           1389 us   28%
surface builder    709 us   14%
world writes       109 us    2%
decorate+feature    42 us    1%
```

Chunk generation is 83% of the per-candidate cost, so noise alone is ~45% of the search.

### Measured, on an idle card

Matching implementations of `PerlinNoiseSampler.sample`, 16 octaves, `tools/perlin_bench`:

```
                                    contended        idle
GPU  RTX 4080, -fmad=false          750 M/s      2553 M/s     (median of 8, 1.13x spread)
CPU  24 threads                     865 M/s      1106 M/s     (3 runs within 0.6%)
                                    0.87x          2.31x
```

**The GPU is 2.3x the CPU**, not the tie first reported. Both figures moved: the card was
being time-shared and power-limited, and the CPU baseline was also taken while a game ran.

**And the port is bit-exact.** Identical bit patterns, `bfe03707aa000000`, from both
devices — the 17th-digit disagreement in the printed decimals is C's `%.17g` against
Java's, nothing more. Building with and without `-fmad=false` also gives identical bits
here, so this kernel has no contraction the default build would change. That does not
retire the hazard in general and `build.bat` should keep the flag, but it is not the
obstacle it looked like.

### What 2.3x is worth

Amdahl, against the 45% share:

```
noise offloaded at 2.3x         45% -> 19.6%     total 74.6%   ->  1.34x
whole chunk build at 2.3x       83% -> 36.1%     total 53.1%   ->  1.88x
```

So a noise-only kernel is ~1.34x end to end, before any PCIe cost for moving terrain back
— which is real, at ~590 KB per candidate. The number that justifies the work is the whole
per-candidate pipeline on the device returning only hits, as `find_targets` already does
for target building: no transfer, no Amdahl ceiling from the parts left behind, ~1.9x from
the noise alone and more once the biome gate and the probes follow it over.

The cost is the one this project has already paid once: a second implementation of the
validated generation core, which can drift from the first. `BundledKernelTest` exists
because a stale exe once shipped. Anything built here needs the same guard, plus a
chunk-for-chunk diff against the CPU path.

### The lesson, which is the reason this section exists twice

A benchmark on a contended machine does not return a noisy version of the right answer, it
returns a confident wrong one. Three consecutive identical runs spanned 84 to 344 M
octave-evals/s — a 4x spread that best-of-N could not correct, because contention is a
systematic steal and not noise. The tell was there and I did not act on it: the first
reading was 932 M/s and the second 157 M/s from a *host-side* change that could not
possibly have mattered. When a number moves for a reason that cannot explain it, stop and
check the environment before drawing anything from it.

The same run also produced an apparent proof that FMA contraction changes the terrain,
-2.0620 against -2.0464. That was a negative array index in the host shuffle writing out of
bounds, and two separately linked binaries corrupting different stack bytes. With the bug
fixed both builds agree exactly. Neither the FMA claim nor the "GPU is not faster" claim
survived contact with a clean measurement.

## 6an. Most of the target set was chains that could not be placed

A collaborator asked whether `ChainPrefilter` ever checks that a continuation reads the
RNG at a later shift than the column beneath it, and guessed that if it does not, most of
the set is physically impossible. It does not, and it was.

### The rule that was missing

`cs[i]` is the shift index of a candidate: how many successful placements the chunk is
assumed to have made *before* that invocation, at two draws each. Placements only ever
accumulate, and a chain's own column is a placement. So for consecutive columns of a
chain at invocations n1 < n2:

```
cs(column 2)  >=  cs(column 1) + 1
```

strictly greater, always, because column one placing is itself the thing that shifts the
stream for everything after it.

`collect` and `chainFrom` checked three things — later invocation, same (x, z), shift
within the cap — and never that one. A chain could therefore be built where a later
column read the stream at the *same or an earlier* offset than the column it stands on,
which would require the chunk to have placed the cane out of order.

The kernel had it too, in the same shape: `find_targets.cu` checked `groupN[g] <= n[i]`
and the position, and nothing about `s`.

### What it was worth

Both are one line. Measured over 200,000 decoration seeds, band 11..64:

| height | q before | q after | tighter |
|---|---|---|---|
| 7 | 1.571e-1 | 6.163e-2 | 2.5x |
| 8 | 3.379e-2 | 1.109e-2 | 3.0x |
| 9 | 2.210e-3 | 1.200e-4 | **18x** |
| 10 | 7.433e-4 | 5.000e-5 | **15x** |
| 11 | 1.500e-4 | 0 in 200,000 | — |

The find rate goes as `rate_chunk / q`, so that is the speedup: **3x at height 8, ~18x at
9 and 10**, and it costs nothing, because everything it removes is a chain no chunk could
have produced. Both confirmed finds pass unchanged — their shifts already increase, 0 -> 1
for the 8-tall and 0 -> 2 for the 5-tall.

Target set build gets proportionally more expensive per member, since q is what it divides
by. The set is seed-independent and cached, so that amortises; the search does not pay it.

`TargetCache` goes to version 3. A version 2 file was built when the rule did not exist
and is mostly impossible chains — harmless to search, but no longer what its header says.

### It also corrects 6am's reading of maxAnyShift

That section recorded both confirmed finds as *needing an interleaved foreign placement*,
on the strength of the 8-tall having base shift 0 and max shift 1. That was a misreading:
with the monotonic rule stated plainly, shift 1 on a two-column chain is **its own first
column** and assumes nothing foreign at all. Only the 5-tall's shift 2 implies one
unrelated placement, and that one is visible — its chunk grew a second column one block
over, which is the other chain the filter reports at x=12.

The conclusion there still holds, for a better reason. Capping the chain-wide shift at 0
does not forbid foreign placements, it forbids *stacking*: column two of anything sits at
shift >= 1 by construction. That is why it rejected 47 of 47 reported finds and both
confirmed ones, and why 1.09% of three-column chains survived it rather than none — those
are the ones whose base sits high enough that the cap is not binding.

### Why this was worth a collaborator noticing

The filter is meant to over-accept, and every previous audit of it asked whether it might
be rejecting something real. Nobody asked the cheaper question: whether the things it
accepts are possible. A soundness argument in one direction had been checked repeatedly;
the arithmetic in the other direction had not been checked at all.

## 6ao. The contiguous-window rule: 2.5x, and it costs a confirmed find

A collaborator's follow-up to 6an: ascending shifts are not the whole rule, because a
chain can still ascend *past* a foreign placement. Concretely, the filter accepts an
8-tall built as 4, then some unrelated cane placed elsewhere in the chunk, then the
second 4 — shifts 0 -> 2. Contiguity would forbid it: consecutive placements only.

The observation is correct. `ChainPrefilter.maxSlack` now expresses the whole family —
a budget of foreign placements between the chain's own columns, where a step from shift
`a` to `b` spends `b - a - 1`. Unrestricted is the ascending rule of 6an, 0 is the
contiguous rule.

### What each costs and buys

Measured on `target/targets12_3.bin`, the live set: height 12, `maxBaseShift` 0,
`maxColumns` 3, q = 4.5e-8.

| budget | targets surviving | search |
|---|---|---|
| unrestricted | 100 of 100 | 1.00x |
| slack <= 1 | 100 of 100 | 1.00x |
| slack = 0 | **40 of 100** | **2.50x** |

Slack 1 is free here because it is already implied: reaching 12 in 3 columns from base
shift 0 uses shifts {0,1,2}, {0,1,3} or {0,2,3}, none of which spends more than one.
Over random decoration seeds in the default band the same rules give 1.2x / 2.0x at
height 7 and 1.0x / 1.6x at height 9.

### f, measured: contiguity keeps 87.9% of real finds

`ProbabilityProbe` already replays the cane feature over synthetic decoration seeds on
real ocean terrain and inspects the chain of every find, which is what 6ah's weights came
from. It now also asks whether a contiguous window would have kept each find. That is f,
and it is the only thing that decides this.

Over 76 real finds on real terrain (height >= 5, `probe:12000`):

```
finds an ASCENDING window keeps (count 10): 66
finds a CONTIGUOUS window would keep:   58 (87.9%)
finds a slack<=1 window would keep:     65 (98.5%)
```

**First measurement of this was wrong and read 98.9%.** The probe's own filters are built
at `COUNT_DESERT`, 60 invocations, because it is shared with biomes that have them. Ocean
runs the feature 10 times, so a filter told to expect 60 reads 50 invocations of stream
the chunk never ran, and those phantom candidates can supply a consecutive chain the real
chunk did not have. That inflates f in exactly the direction that flatters contiguity.
The slack filters are built at `COUNT_DEFAULT` for this reason, and f is taken against
what an ascending filter at the same count keeps, not against all finds -- a find neither
rule can see is not evidence about either.

So contiguity is a **net 2.20x** at these chains: 2.50x fewer targets, 87.9% of finds kept.

Every find in the sample is a 2-column chain, which has one gap for a foreign placement
to land in. Reaching 12 takes three columns and therefore two gaps, so f there is about
0.879^2 = 77%, and the net is about **1.9x**. Still clearly worth having.

### The confirmed 5-tall is the minority case, not half the population

Which is what made the earlier reading of this so misleading. Two confirmed finds split
1-1 on contiguity, and taking that at face value put f at 50% -- close enough to the 40%
break-even to look like a coin flip. It is not: the 5-tall is in the 12% whose chunk
grew something else between the stack's columns, and one draw from a 12% class is
unremarkable in a sample of two.

### What adopting it costs



It still **rejects the confirmed 5-tall** on seed 1500050556. That chain runs shift
0 -> 2 because the chunk really did grow an unrelated column between the two that make
the stack, and it is visible in game. The confirmed 8-tall runs 0 -> 1 and survives
either way.

```
confirmed 8-tall  ascending ACCEPTED   slack<=1 ACCEPTED   slack=0 ACCEPTED
confirmed 5-tall  ascending ACCEPTED   slack<=1 ACCEPTED   slack=0 REJECTED
```

So this is not 6an. That fix was free: everything it removed was physically impossible,
and q fell 3x at height 8 with no coverage lost at all. Contiguity removes chains that
are perfectly possible, and is the same kind of trade as narrowing the base band —
judged on cost per find, not on coverage.

Break-even is f = 40%, since it keeps 40% of the set. Measured f is 87.9%, so it clears
that by more than a factor of two and the decision is not close.

Shipping it is still not free work. A set built under a slack budget is not
interchangeable with one built without it, so it has to go in the `TargetCache` header,
which means version 4 and every existing target file invalidated. `find_targets.cu` needs
the same rule or a GPU-built set stops matching a CPU-built one, and that equality is the
only check that the two paths have not drifted.

Both are worth doing, and the moment to do them is a rebuild that is happening anyway --
see 6ap, where the live set turns out to be 10x smaller than it should be.

## 6ap. Adopting the contiguous window, and the set size that was hiding behind it

6ao measured the trade and left it off. It is on now, by default, and the same rebuild
fixes a second problem that turned out to be costing more than the filter was.

### The filter

`--max-slack=<n>`, default 0. Zero is the contiguous window: a chain's columns must be
consecutive successful placements. `--max-slack=99` restores the ascending rule of 6an.
Values between 0 and `maxColumns` are CPU-only — the GPU DP memoises `best2`/`best3` per
candidate, which is sound only while the rule is local, and contiguity is (`s[j] ==
s[i] + 1`) while a budget shared along a path is not. The kernel refuses those values
rather than quietly disagreeing with the CPU.

`TargetCache` goes to **version 4**: the budget changes what membership means, so a
version 3 file is a different set and cannot be extended into this one.

Verified the way this project verifies target-set changes — a GPU-built and a CPU-built
set of the same 300 members at height 8, byte for byte:

```
gpu 7D96F281D2DBD39EA5BF7ED08CAD44B2CF9DF964CDCB5ABFE86565FBAF733117
cpu 7D96F281D2DBD39EA5BF7ED08CAD44B2CF9DF964CDCB5ABFE86565FBAF733117
```

`ReversePipelineTest` now pins **both directions**: the 8-tall survives the default, and
the 5-tall does not. Every other guard there asserts that a real find is kept; this is
the first one that asserts a real find is dropped, which is the point — the cost stays
visible instead of being rediscovered later from a search that quietly finds nothing.

### The set was 10x too small, which cost more than the filter gained

The live height-12 set had **100 targets**. The version-2 files it replaced had 1,000.
Nothing was wrong with the rebuild except its size, and the size is not a detail:

```
per candidate: lattice 0 us, biome gate 3 us, air probe 3 us, chunk 19 us
               -> 24 us accounted of 92 thread-us actual
64 sisters per low-48 seed, per-sister setup total 4.5 s   (of 6.4 thread-s)
```

**70% of the CPU was building biome sources.** Each sister needs its own biome map no
matter how many targets there are, so that cost is fixed per seed and the only thing that
dilutes it is more targets per seed. At 8 per bucket it was being diluted by nothing.

`--sisters=64` is not the culprit and was measured again to be sure: 21,262 candidates/s
at 64, 12,133 at 8, 2,564 at 1. The sweep's saving on lattice and probe still beats the
extra prepares. The set size is the lever.

Growing to 1,000 recovers about **2.7x**; the ceiling with setup fully amortised is 3.4x,
and past roughly 5,000 the curve is flat.

### What the build actually costs, which is not what was first claimed

An earlier version of this section said 1,000 height-12 targets was about eight minutes.
That was wrong twice over, and both errors point the same way.

It came from a 4-thread run that produced 31 targets in 98 s, scaled by six for 24
threads. **The build does not scale with threads.** Measured on 24 threads it samples
7.5e6 seeds/s; the 4-thread run sampled 7.1e6. The chain filter runs on the GPU and the
CPU only sifts soil on the ~1.6% handed back, so seed throughput is the card's and the
thread count barely enters it. Scaling a thread count linearly was an assumption, not a
measurement, and nothing checked it.

The second error is that the figure was taken under the *ascending* rule. Contiguity cuts
q, and q is exactly what the build divides by, so every member costs proportionally more:

| rule | q at height 12 | seconds per target, 24 threads |
|---|---|---|
| ascending | 4.55e-8 | 2.9 |
| contiguous | **1.63e-8** | **8.5** |

So 1,000 contiguous height-12 targets is about **2.4 hours**, not eight minutes. That 2.8x
is also an independent confirmation of the filter itself, arriving from a different
direction than the 2.5x measured by re-testing an existing set.

Still obviously worth doing: the build checkpoints and resumes, the set never depends on
the world seed, and one person building it covers everyone -- which matters more here
than the wall clock, because the collaborators are on other cards.

### The two together

About **5x** on the reverse search: ~2.7x from amortising the per-sister setup, ~1.9x from
contiguity at the three-column chains a height-12 set uses. They want the same rebuild,
which is why they landed together.

The general lesson is the one from 6an's ending, pointed the other way. That section noted
every audit had asked whether the filter rejected real things and none had asked whether
it accepted impossible ones. This one is worse: nobody asked whether the set was the size
it was supposed to be. A parameter that is merely too small produces a search that works
perfectly and slowly, and nothing in the output says so.

## 6aq. The dedicated 4+4+4+4 scanner, and what validating it found

A contributed kernel (`cuda/stack16.cu`) scans decoration seeds for exactly one stack shape:
four columns of height 4, base in the usual band, nothing else placed in the chunk. It holds
no per-thread candidate arrays at all -- it walks the RNG stream once, keeping only the
current target position -- and it skips an invocation's twenty tries in O(1) with a
precomputed LCG jump when the origin y cannot match. **215M seeds/s against the chain
filter's 30M.**

### It was missing 19 roots in every 20

`search_seed` picks a root at try `t` of an invocation, consumes the two height draws, and
calls `greedy_extensions` for the next invocation -- but the invocation still has tries
`t+1..19` to go, and `greedy_extensions` starts by reading an origin. So the stream was
positioned mid-invocation and the walk almost always failed. `greedy_extensions` applies
`d_skip_remaining_try_jumps[t]` after its own matches; the root path did not.

Only `t == 19` worked, where that jump is zero. The signature was unmistakable once looked
for -- every hit in a 4M-seed run had `col=0 try=19`:

```
Name Count
---- -----
19     106
```

With the jump applied, the same range gives 2,616 instead of 107.

### Validated against our own filter, and it wins

Compared over 4M sequential decoration seeds, against `ChainPrefilter` asked the same
question (minHeight 8 as 4+4, band 13..35, maxBaseShift 0, maxSlack 0):

```
scanner 2616, ours 3512
only ours    : 896
only scanner : 0
```

A strict subset. **Our filter accepts 896 of 3,512 chains -- 26% -- that cannot happen**, and
the scanner is right to drop them.

The reason is a rule the chain filter never had. Within one invocation, only the *first* try
landing on the target position can be the column. A chain needs terrain to permit placement
at that position, so an earlier try landing there would also have placed -- and if its height
is not 4, the position is consumed at the wrong height. `ChainPrefilter` lets any of the
twenty tries be the column and so accepts chains that a real chunk would have preempted.

That is the same category as 6an: not a coverage trade, a soundness gap. And unlike the
scanner it applies at every height, so fixing `ChainPrefilter` is worth more than adopting
the scanner -- 26% off the target set at heights divisible by 4 is what the scanner buys,
while the same rule in our filter buys it everywhere.

## 6ar. Two implementations that now agree exactly

6aq measured our chain filter accepting 26% chains no chunk could produce, found by
disagreeing with the contributed 4+4+4+4 scanner. Chasing that disagreement to zero took
three rules, each the same idea at a different scope.

**Within an invocation.** All twenty tries share a y, so two tries at the same (x, z) are
the same block. The first places; the second finds cane. 896 of 3,512 at height 8.

**Between invocations, for a continuation.** Once a chain's column places, every later
invocation reads the stream at one more shift. The earliest of those landing on the spot
places there, at whatever height it drew -- a later one cannot supply the column instead.
5 of 2,621.

**Between invocations, for the base.** The same, one step earlier: a base whose spot an
earlier invocation at the same shift already claimed never places. 2 of 2,618.

```
ours 2616, scanner 2616
only ours    : 0
only scanner : 0
```

Over 4M decoration seeds, a DP over enumerated candidates and a greedy single-pass stream
walk now return exactly the same set. They share no code and were written from opposite
directions, which makes the agreement worth more than either alone -- and every step of
the way the disagreement was ours over-accepting, never theirs.

All three rules need the shift a chain reads at to be pinned, so they apply only with no
slack budget, which is the default. With foreign placements allowed the shift is not
determined and the earliest owner is not knowable.

## 6as. The greedy path, folded in: 11.3x at height 12

6ar got the contributed 4+4+4+4 scanner and our chain filter to agree exactly. With the
semantics identical, its remaining value is speed, so it is now a second kernel inside
`find_targets.cu` rather than a second binary -- one output format, one host scaffold, one
argument list, and the byte-identical CPU check covers it for free.

It is chosen when the parameters are exactly what it models: height divisible by four, one
column of 4 each, no prior placement and none interleaved. Anything else falls through to
the general filter.

```
target build, 24 threads
  height 8   general 3.0 s    greedy 0.9 s     3.3x
  height 12  general 175.4 s  greedy 15.5 s   11.3x
```

Byte-identical at both, checked against the CPU at height 8 and against the general kernel
at height 12 -- the CPU comparison there needs 2.2e9 seeds and does not finish in a
sensible time, so the chain is greedy == general == CPU rather than one comparison.

The gain grows with height because the walk gets to give up sooner. Every column must be
exactly 4, so the target y of the next one is pinned, and an invocation whose origin y
misses it costs one jump instead of 120 draws. At three columns almost every seed dies on
the first or second invocation.

Two passes, because greedy alone over-accepts: the walk finds a witness, then a validator
replays the chunk from invocation 0 and rejects it if any earlier or intervening invocation
lands on a target position first. That validator is the ownership rule of 6ar, which is why
the two paths agree rather than merely nearly agreeing.

## 6at. Tall stacks are ravine-carved, and what that filter is actually worth

A collaborator's claim: 99% of candidate seed-and-coordinate pairs can be thrown out with a
couple of RNG calls from the ravine sphere placements. They found a 15-tall in ten minutes,
so it works for them.

### The observation is right, and it is not close

Which carver reaches the base of every find we have:

| find | cave reaches it | canyon reaches it | canyon's vertical run |
|---|---|---|---|
| confirmed 8-tall | no | **yes** | 5 |
| confirmed 5-tall | yes | no | 0 |
| reported 11 | no | **yes** | 10 |
| simulated 10 | no | **yes** | 28 |

Every find of height 8 or more is reached by a canyon and by **no cave at all**. The one
cave-carved find is the 5-tall, which is one column on another and does not need the height.

It makes sense from the shape. A cave is tubular and a few blocks across; four stacked
columns need sixteen blocks of air at one x, z. That is a ravine.

### But the filter is worth 31%, not 99%

`--ravines-only` drops caves from the carve probe. Over identical work:

```
both carvers : chunks generated 103806, searched 11534
ravines only : chunks generated  71829, searched  7981
```

31% fewer chunks, and chunk generation is ~84% of the search, so about 26% overall. Real,
and nowhere near what the claim suggests.

The reason is population, not disagreement. Their 99% is measured on raw candidates. Ours
are already through a biome gate that rejects 99.2% and a depth band of y 13..35 -- and what
survives that is heavily enriched for being near a ravine, because that is what put it in
the band. Ravines are also long, so 5.8 of them over a 17x17 chunk region cover a great deal
of ground. Filtering an already-filtered population gives back much less.

Which means the technique is their **primary** filter and can only ever be our **secondary**
one. Worth having, not worth expecting an order of magnitude from.

### Kept as a flag

It is a coverage trade: it would drop a cave-carved find like the 5-tall. Every find at the
heights actually searched is ravine-carved, so the trade looks good above height 8, and
`ReversePipelineTest` pins the confirmed 8-tall against it -- if that ever fails the
assumption is wrong and the flag is discarding real finds silently.

## 6au. Why terrain checking is 84% of the search, and what stops it being cheap

Nine chunks are generated per chunk searched -- full noise, carvers, surface builders -- to
answer a question about roughly four blocks. Chunk generation is ~84% of the reverse search,
and the probe meant to keep candidates out of it rejects 12%. The expensive stage is the
crude one.

### It should be five columns, not nine chunks

A chain is a vertical stack at one (x, z). So everything the cane feature asks is answerable
from five noise columns -- the column itself, for air at each base and soil under it, and its
four horizontal neighbours, for `needWater`. That is 5 columns against the 2,304 a
nine-chunk generation computes.

`terrain.column` already exists and `noiseCouldHoldChain` already uses it, so the machinery
is there. The blocker is not the column oracle, it is that we cannot yet answer *water*.

### The water source that nothing models

`--water-probe` (`LiquidCarveProbe`) rejects the reported 11-tall. Retention against every
find:

| find | --water-probe | --ravines-only |
|---|---|---|
| confirmed 8-tall | keeps | keeps |
| confirmed 5-tall | keeps | drops (cave-carved, height 5) |
| reported 11 | **drops** | keeps |
| simulated 10 | keeps | keeps |

The obvious explanation was that the 11's water is the noise fill rather than a carver, since
the probe only knows carver water. It is not. Reading the four neighbours directly at the
three levels `needWater` checks:

```
y=27  +x noise=1 water=false carver=false   -x ...   +z noise=1 water=false carver=false   -z ...
y=31  (same)
y=34  (same)
```

Every neighbour reads solid in the noise and unflooded in the carver probe, at every level --
yet `inspect` shows the real world with `+z=water` at y=27, and the cane grows. So the water
at that block comes from neither the noise fill nor a LIQUID carver, and until that is
identified any water prefilter will silently discard real finds.

That is the one thing standing between the current pipeline and a terrain check that never
generates a chunk until a candidate has passed everything. Worth more than any other
optimisation open: 84% of the search sits behind it.

`--water-probe` stays off, and now there is a named counterexample rather than a vague
worry about the sea floor.


## 6av. Search tall, not short -- a ravine satisfies every column at once

The terrain event a stack needs is not "four columns each got lucky". It is "a ravine opened
a wall of air against water, with soil at the bottom". Measured at the three tall finds, the
canyon's vertical run at the stack's own column is **5, 10 and 28 blocks**.

So a 16 is barely harder than an 8 in terrain terms. Once the wall is there it satisfies
every column of the chain at the same time; the extra difficulty of a taller stack lives
almost entirely on the RNG side, where q falls and the GPU does the work.

Which makes a height-8 search a strategic error rather than a slow one: it pays the same
terrain cost per candidate and returns an 8. A collaborator searching directly at 14-16 found
a 14 in about ten minutes on a 5090, and a 15 not long after.

It also settles why our height-8 run saw 57.5M candidates and no hit. Terrain cooperation is
the binding constraint and it barely cares about the height being asked for, so the run was
paying full price for the least valuable possible answer.

### Ravines only, by default, from height 8

`--ravines-only` is now the default at height 8 and above, since every find at that height is
ravine-carved and no cave can open the vertical wall. Below 8 caves stay in -- a single column
on another needs no vertical extent, and the confirmed 5-tall is cave-carved.

`--all-carvers` forces caves back at any height and `--ravines-only` forces them out below 8,
so both directions are reachable. `ReversePipelineTest` pins the confirmed 8-tall against the
ravine-only path.

## 6aw. Cross-chunk stacking: WRONG, superseded by 6ax

**This section is wrong. 6ax has the corrected measurement.** Two flaws, both mine, and both
suppressed exactly the case worth asking about. Left here because the reasoning about why
alignment costs what it does is still right, and because a retracted measurement is worth
more visible than deleted.

A placement lands at chunk-relative x -4..19, so a chunk can put cane four blocks over its
own border. If one chunk stacks into its neighbour's territory and the neighbour then stacks
on top, the run is the sum -- two ordinary chains instead of one extraordinary one. Worth
measuring, because that is a much weaker RNG demand than a single chunk doing all of it.

`crosschunk` measures it. Over 20M chunk pairs:

| height | one chunk | two chunks |
|---|---|---|
| >= 14 | 5.000e-08 | 5.000e-08 |
| >= 13 | 1.000e-07 | 1.000e-07 |
| >= 12 | 8.000e-07 | 8.000e-07 |
| >= 11 | 5.450e-06 | 7.000e-06 |
| >= 10 | 2.855e-05 | 3.660e-05 |
| >= 9 | 9.530e-05 | 1.182e-04 |
| >= 8 | 7.882e-03 | 9.505e-03 |

About **1.25x at heights 8 to 11, and exactly nothing at 12 and above**. The tallest
combination seen in 20M pairs was 11.

### Why it stops helping exactly where it would matter

Combining costs an alignment. The two chains must share x, share z, and the second must start
at precisely the block the first stops on -- roughly 1 in 24 x 24 x 54, about 3e-5. So joining
two 6s into a 12 costs P(6)^2 x 3e-5 = 6.5e-7, against P(12) = 8e-7 for one chunk doing it
alone. A wash at 12, and worse above, because a single chunk can already use four or five
columns and pays no alignment penalty at all.

The gain at 8-11 is real but it is the region where single-chunk chains are already common.

### Caveats, both of which make it look better than it is

Only one neighbour is sampled (cx+1); a real chunk has four, so the rate could be a few times
higher -- but the 12+ column stays empty either way, since the alignment penalty is structural
rather than a constant. And placement order is assumed: the first chunk has to have decorated
before the second, which depends on how the world was explored. Only one of the two orders
works, so halve whatever the four-neighbour figure turns out to be.

That is also why `SisterScan` calls these "not verifiable" -- a cross-chunk result is a lead,
not a find.


## 6ax. Cross-chunk (superseded by 6ba, which measures it properly)

**The conclusion here is also unreliable.** It rests on single events -- one 14 in 100M pairs
-- and 6ba, which counts hundreds of thousands instead, reverses it. The two flaws it
identifies in 6aw are real and worth reading; the number it replaces them with is not.

6aw concluded "nothing at 12 and above". That was an artefact of two mistakes.

**The neighbour was enumerated with a depth band.** The band exists because a chain's base
needs soil the terrain actually put there. The second chunk's chain stands on the first
chunk's *cane*, so it needs nothing of the sort and the whole legal column is available.
Restricting it to y 13..35 made any join above 35 invisible -- and for a tall combination the
join is necessarily high. An 8-tall starting at y=25 tops out at 33; a 12-tall at 37.

**And `minPart` capped the answer.** `collectChains` records the *shortest* chain reaching the
height asked for, so `minPart=4` makes every chain a single column and no combination can
exceed 8. The measurement could not have found a 12 whatever the data said.

### Corrected

Both fixed, all four neighbours walked, 100M pairs with each side contributing >= 6:

| height | one chunk | two chunks |
|---|---|---|
| >= 14 | 0 | **1.0e-08** |
| >= 13 | 0 | **1.0e-08** |
| >= 12 | 3.30e-07 | 3.70e-07 |
| >= 11 | 3.14e-06 | 3.18e-06 |

**Cross-chunk reached 14 in a sample where one chunk reached neither 13 nor 14.** Against a
separately measured single-chunk rate of 2e-9 at height 14, that is roughly 5x -- on one event
each side, so the error bars are enormous and the direction is the only trustworthy part.

At 16 there is still no positive evidence: 0 in 500M pairs with each side at 8. Single-chunk 16
is under 3e-10 (0 targets in 5.02 billion seeds on the GPU), so the comparison is unresolved
rather than settled -- both are below what 500M pairs can see.

### The part that does not need statistics

A single chunk is **capped**. Shifts must strictly increase, so four shift levels allow four
columns and four columns of 4 is 16. Five levels allow five, so 20. There is no arrangement of
one chunk's RNG that reaches 21.

Two chunks are capped at twice that. So above 20 cross-chunk is not merely better, it is the
only route, and the question stops being whether it pays and becomes whether the placement
order can be relied on.

That last part is still unresolved and still the reason these are leads rather than finds: the
first chunk must have decorated before the second, which depends on how the world was explored.


## 6ay. The split matters more than the total: 12+8 beats 10+10 by ~55x

A collaborator's point, and it follows from a shape in the data that is easy to miss. The
per-chain rate does not decay smoothly with height -- it falls off cliffs at column boundaries,
because a chain of C columns tops out at exactly 4C:

| height | rate | columns needed |
|---|---|---|
| >= 8 | 3.77e-02 | 2 |
| >= 9 | 4.80e-05 | 3 |
| >= 12 | 3.30e-07 | 3 |
| >= 13 | 2.00e-09 | 4 |

**785x between 8 and 9, and 165x between 12 and 13.** So 8 and 12 are not ordinary heights,
they are the maxima of their column counts, and asking for one more than either costs a whole
extra column.

Reaching 20 across two chunks, by split:

| split | P(A) x P(B) |
|---|---|
| **12 + 8** | 3.3e-7 x 3.77e-2 = **1.24e-8** |
| 16 + 4 | 3e-10 x ~1 = 3.0e-10 |
| 10 + 10 | 1.5e-5 x 1.5e-5 = 2.3e-10 |
| 9 + 11 | 4.8e-5 x 3.1e-6 = 1.5e-10 |

**12+8 is about 55x better than 10+10**, and the even-looking split is close to the worst of
the sensible ones. The rule: put every column boundary to work -- ask each side for a multiple
of 4, and never for one more than a multiple of 4.

### Not confirmed by brute force, and why that is expected

100M pairs at 12+8 and at 10+10 both produced nothing. That is what the numbers predict: with
the strip constraint, 12+8 expects about 0.06 events at that scale and 10+10 about 0.001.
Separating them needs roughly 1.6e10 pairs, about five hours on the CPU, which is a GPU port
rather than a longer run.

`crosschunk` now takes the two sides separately -- `crosschunk <seeds> <threads> <minThisChunk>
<minNeighbour>` -- and reports which split carried each combination it finds, so the question
is answerable once there is something fast enough to answer it with.

## 6az. The position constraint on the GPU, and a disagreement that was not one

`spot` asks whether a seed builds at one named block. That is a single-seed predicate, which
means it fits the existing kernel rather than needing a new one: three lines in the accept
test, comparing the base candidate's packed xz and y against a requested pair, with -1 meaning
unconstrained.

```
spot at chunk-relative 3,10 base 21, height 8, 200M seeds
  CPU   70.8 s
  GPU    4.6 s      15x, same 153 seeds
```

The kernel alone runs 41.7M seeds/s against the CPU filter's 1.6M.

The greedy N x 4 walk is skipped for spot queries: it picks its own root and would have to be
rewritten to be told one. The general filter handles them, and the ordinary unconstrained build
is byte-identical to before, so nothing that already worked moved.

### The counts differed and neither was wrong

First comparison gave 147 from the kernel and 153 from Java over the same 200M seeds. Six
seeds, only ever in Java's favour, which is the shape of a kernel missing things.

It was not. Disabling the window optimisation left it at 147, so the cheap path was innocent,
and every one of the six turned out to be an orbit relative of a kernel hit -- at -2, -2 and -4.
`spot` expands each hit into its orbit family, and a family reaches *backwards* out of the
scanned index range. Java was reporting valid seeds the scan never visited.

Worth recording because the instinct on a 147-vs-153 is that the faster implementation is
cutting a corner. Here the slower one was doing extra work on purpose, and the filters agreed
on every seed either had actually looked at.

### What this buys for cross-chunk

A cross-chunk combination needs the second chunk to have a chain at the exact block the first
one stops on -- which is a spot query. So the two factors of P(cross) are both single-seed
predicates the kernel can now measure at 40M/s, and neither needs the pair loop that made a
brute force hopeless. 6ay's 55x rests on multiplying measured per-height rates; this is what
would let it be measured directly instead.


## 6ba. Cross-chunk, counted rather than sampled: it loses wherever one chunk can compete

Both previous attempts sampled pairs, and pair sampling cannot answer this. The combined rate
is around 1e-10, so seeing one costs 1e10 pairs, and every conclusion so far rested on zero,
one or four events.

It never needed pairs. A pair matters only through the block the two chunks share:

```
P(cross) = SUM over blocks p of  P(a chain ENDS at p) x P(a chain BEGINS at p)
```

Both factors belong to a single seed. One pass over N seeds, histogramming where chains end and
where they begin, evaluates all N^2 pairings at once -- **O(N) instead of O(N^2)**, with the
answer sharpening as the histograms fill rather than as pairs coincide. 20M seeds in 12 seconds
now gives what 1e14 pairs could not.

### And the answer is no

| height | split | cross-chunk | one chunk | |
|---|---|---|---|---|
| 12 | 6+6 | 1.061e-07 | 2.940e-07 | **0.4x** |
| 16 | 8+8 | 2.021e-10 | 3.000e-10 | **0.7x** |
| 18 | 7+11 | 2.224e-13 | four shift levels cannot | -- |

**Cross-chunk is worse at every height a single chunk can reach.** Splitting the RNG demand
across two streams does buy something, and the alignment costs more: the second chain has to
begin on the exact block the first ends on, and paying 1-in-24 x 24 x 54 for that exceeds what
the easier chains save. 81,446 endings and 174,954 beginnings went into the height-16 row, so
this is not another one-event conclusion.

Its only exclusive ground is above 20, where one chunk cannot go at all -- five shift levels
allow five columns and five columns of 4 is 20. That is real but remote: 2.2e-13 at height 18
is already beyond reach, and 21+ is further still.

### What was wrong before

6aw sampled pairs with both sides banded and with `minPart` capping combinations at twice
itself, and concluded "nothing at 12+". 6ax fixed both and saw a single 14, and read that as
cross-chunk reaching what one chunk could not. Neither number survives counting properly.

The lesson is about the method rather than the mechanism: three measurements of the same thing,
and the two that sampled pairs were both wrong, in opposite directions. The rate was never
within reach of sampling, and no amount of care about the filters would have fixed that.

## 6bb. Two-chunk reversal: the world seed cancels, and there are no free parameters

The question was how to set free parameters so the lattice reduction works over two chunks at
once -- fix x and z for the first chunk, take two decoration seeds, and solve. It turns out
there is nothing to fix, because the position drops out.

### The world seed cancels

`D(x,z) = (x*l + z*m) ^ ws`, with l and m odd and fixed by ws. The neighbour at x+16 gains 16l
inside the sum, so with `S = x*l + z*m`:

```
D1 ^ D2 = (S ^ ws) ^ ((S + 16l) ^ ws) = S ^ (S + 16l)
```

Verified on 2M adjacent pairs. And l is odd, so 16l has exactly four trailing zeros, which
forces the low four bits of `D1 ^ D2` to zero for every S and every world seed:

```
2000000 adjacent chunk pairs
  pairs where (D1 ^ D2) low four bits are NOT zero: 0
```

**A cross-chunk target pair must agree in its low nibble** -- a free 16x prune before any
lattice work, and conveniently the same nibble the search already buckets by, so a valid pair
always comes from one bucket.

### And the position cancels too

Since `S = D1 ^ ws`, substituting gives

```
(D2 ^ ws) - (D1 ^ ws)  ==  16 * l(ws)     (mod 2^48)
```

One equation, one unknown, and x and z are nowhere in it. So there are no free parameters to
set: solve for ws first, and the position follows from the ordinary single-chunk lattice
`x*l + z*m = D1 ^ ws`. Expect about one world seed per target pair, since it is one 48-bit
equation over a 48-bit space.

Worth ruling out: `X = D1 ^ D2` does not usefully constrain l. For any S, `K = (S ^ X) - S`, so
almost every l admits some S. The low-nibble condition is the only cheap structural prune.

### It splits 2^24 + 2^24

Brute force over ws is 2^48. It separates, which is what makes a meet in the middle possible:

```
500000 world seeds
  state2 != H2(hi) + L2(lo) mod 2^48 : 0
  H2 with any of its low 24 bits set : 0
  low 8 bits of next(32) depending on hi: 0
```

Writing `seed0 = (ws ^ M) & mask` as `hi<<24 | lo`, the hi half contributes `M*(hi<<24)`, whose
low 24 bits are zero. The second LCG step stays separable because the wrap in state1 vanishes
under multiplication: `(X - 2^48)*M == X*M mod 2^48`. So `state2 = H2(hi) + L2(lo)` exactly, no
carry correction, and H2 keeps its low 24 bits clear -- which puts the low 8 bits of the second
`next(32)` under `lo` alone.

### What is left

The solver itself. Both sides of the equation are additive in the two halves up to small carry
corrections, which is the shape a meet in the middle wants, and 2^24 + 2^24 is minutes rather
than the 2^48 of a scan.

Not built here, deliberately. A wrong MITM returns most of the solutions and looks perfect,
and the failure is invisible against a search that is supposed to find almost nothing. The
oracle for it is cheap though -- pick a world seed, derive D1 and D2 from it, and require the
solver to return that seed -- so it is testable, just not testable by inspection.

## 6bc: the two-chunk solver, by lifting rather than meeting in the middle

The MITM of 6bb was never built. It is superseded: the equation lifts, which is simpler, exact,
and needs no table. The idea is a collaborator's -- "its just lifting on crack / you know the
lower 4 bits for free / you guess the next 12 bits / and then just do bit by bit / if you run
into a contradiction you stop". All three constants are right, and the twelve is not an estimate.

### Why twelve

Checking `(D2 ^ ws) - (D1 ^ ws) == 16*(dx*a + dz*b)` mod 2^k:

- the left side is XOR and subtraction, so its low k bits need only `ws` mod 2^k;
- `a` mod 2^j lives in `next(32)` results, i.e. state bits 16 and up, and an LCG is
  lower-triangular, so it needs `ws` mod 2^(16+j);
- the `*16` gives four bits back.

So the equation mod 2^k needs `ws` mod 2^(k+12). Measured, not argued:

```
  lookahead 10 : FAILS first at k = 5
  lookahead 11 : FAILS first at k = 5
  lookahead 12 : holds for every k
```

The low four bits are not guessed either: `D1 = 16*(...) ^ ws`, so `ws` agrees with `D1` below
bit four. That is the same condition `DecorationLattice` needs to reach `D1` at all, so pinning
it does not merely cut the blind prefix 16x -- it stops fifteen in sixteen of the answers from
being seeds that no chunk in the border can use. Before: 32 seeds, 3 usable. After: 3, all 3.

### It works

`TwoChunkLift`, 4 to 25 ms per pair on one thread, a few hundred thousand candidates against
2^48 for a scan. Round trip over six chunk offsets including diagonals:

```
TwoChunkLift: 24 round trips, the true world seed recovered every time
TwoChunkLift: lookahead 12 holds, 11 does not
```

### The bug this was always going to have

The first working version missed 16 of 40 world seeds and reported zero bad answers. `nextLong`
is `(next(32) << 32) + next(32)` with the low word **sign-extended**, not OR-ed. That only moves
bits 32 and up, so the lifting never saw it -- every pruning decision was correct -- and it
surfaced only in the full-width check at the end, which threw away the true seed whenever that
bit was set. Every seed returned still satisfied the equation as coded. Exactly the failure 6bb
predicted for the MITM, and only the round-trip oracle catches it.

### Not for the GPU, and that is fine

A branchy tree with data-dependent survival. It runs once per candidate pair, not once per seed,
while the GPU stays on target sets.

## 6bd: block rotation cracks coordinates, but there is no solve step

Separate problem, opposite structure. `Mth.getSeed(x,y,z)` feeds one LCG step and the rotation
is the **top** two bits. Two gotchas in the first line, one of them asymmetric: `x * 3129871` is
an **int** multiply that overflows before widening, so a negative product fills the entire upper
half with sign bits (49.9% of x); `z` is widened *first* and multiplied as a long; `y` is XOR-ed.

The proposal was to guess two of x, y, z and solve for the third. There is no solve. Lifting
worked in 6bc because the observable's low bits depended on the input's low bits; here the
observable is the high bits, which depend on everything:

```
  flipping z bit 20 changes the rotation 75.1% of the time
  flipping z bit 24 changes the rotation 75.1% of the time
  flipping z bit 30 changes the rotation 75.0% of the time
```

75% is the ceiling for a uniform 2-bit output -- full mixing, no handle. So the third variable
can only be scanned, and guess-two-scan-one is the same work as scanning the plane.

What that costs: 6.4e8 rotations/s single-threaded, so the whole x,z plane at a known y is 2^52,
about 52 CPU-days or ~12 GPU-hours. y must be known or nearly so; 256 values multiply it out of
reach. Twenty observed blocks leave 4.1e3 false positives over the plane, twenty-six leave 1.
The consolation is that this one is embarrassingly parallel, unlike 6bc.

## 6be: cross-chunk finds 16s in seconds, and it is not close

`crossfind` (FINDINGS 6bc gave it the solver) over 1e8 decoration seeds, 12 CPU threads, no GPU:

```
  pass 1: 407030 chains stored in 41.1 s
  joins tried             : 32359
  world seeds solved      : 32127
  pairs inside the border : 19465
  both chunks cane ocean  : 5723
  every base carved       : 41
  FINDS                   : 41          (40 x 16, 1 x 17)
done in 93.9 s
```

Two numbers are consistency checks rather than results. **32127 world seeds from 32359 joins**
is one per pair, which is what the algebra says: the low nibble is pinned, leaving 44 free bits
against 44 bits of equation. **19465/32127 = 60.6% inside the border** against `DecorationLattice`'s
independently measured 0.604. Both would have moved if the join rule or the lift were wrong.

### The speedup

`rate(16) = 3.0e-10` per decoration seed, so those same 1e8 seeds contain **0.030** single-chunk
16-chains -- and each of those would still need a world seed that places it in the border, a
cane-bearing ocean, and a ravine. `crossfind` returned 41 that had already passed all three.

So about **1,400x more 16s per seed scanned**, and the comparison flatters the single-chunk route
badly, because its 0.030 chains are raw and the 41 are finished. End to end the gap is far wider:
the single-chunk route needs a target set at height 16 before it can start, and a target set is
980x more expensive per member at 16 than at 12, where 100k members already cost 6.4 GPU-hours.
`crossfind` needs no target set, no GPU, and no world-seed search at all -- the pair produces the
world seed.

The 17 is the sharper point. `rate(17) = 0` in the table: a single chunk needs a fifth column and
a fifth shift level to reach it. Cross-chunk got one as 9+8 for the same price as a 16.

### The standing caveat

Decoration order is assumed, not checked. Chunk A must have decorated before chunk B, which is a
property of how the world was explored rather than of the seed. Everything else on a hit is
verified: both chains exist, they meet at one block, the world seed places both chunks, both are
cane-bearing ocean, every column base of both is inside an air carve, and chunk A has soil under
its bottom column. These are leads, and they want confirming in game before they are finds.

## 6bf: the cross-chunk "finds" were prefilter survivors, and the terrain never supported them

6be reported 41 sixteens and a seventeen from 1e8 seeds. **None of them were real.** Generating
the terrain for all 41: 8 chunks were never built, and of the 33 that were, the tallest cane
actually grown at the join column was **0**. Same at height 10 over 4M seeds: 184 candidates,
154 generated, 0 cane.

### The mistake

`ReverseSearcher` uses `AirCarveProbe` as a cheap gate and then calls `searchOneChunk` -- full
generation -- and only that is a hit. `crossfind` printed FIND for anything that passed the
probe and never generated anything. The probe was answering its own question correctly; it was
being asked to be the last word instead of the first.

The control matters here, because a verifier that has only ever said "no" is indistinguishable
from a broken one: run against the in-game-confirmed 8-tall, the same calls return 8.

### Why it fails systematically rather than occasionally

Probe against generated world, same column, y=10..69:

```
failing candidate : probe says carved 31, actually air  7, overlap 0
confirmed 8-tall  : probe says carved 10, actually air 10, overlap 3 (the rest is cane)
```

Zero overlap. The probe's carver stub replaces anything, but the real `canReplaceBlock` will not
replace water, so above the ocean floor the genuine carve does nothing at all while the probe
happily carves the whole water column. `ReverseSearcher` never sees this because its chains are
depth-banded to the floor. **A cross-chunk join is high by construction** -- chunk A's cane has
to reach up before chunk B can stand on it -- so cross-chunk candidates land precisely in the
region where the probe is wrong.

`crossfind` also skipped the soil check on exactly the wrong chains. Soil is only tested for
relative x in 0..15, and the join geometry forces one of the two chains to sit at relative x
12..19 or -4..3, i.e. outside its own chunk. So the chain that most needed checking was the one
waved through.

### What this does to 6be's claim

The 1,400x stands for the **RNG** and nothing else. Cross-chunk buys two ordinary chains instead
of one extraordinary one, and that part is real and verified. It buys nothing on terrain: the
base of every column still has to be AIR (`canReplace` is false for this feature, so water will
not do), which means the whole run -- both chunks' worth -- must sit inside one tall carved air
pocket under the ocean. That is the same air pocket a single-chunk 16 would need.

So the binding constraint was never the RNG. Making the RNG 1,400x cheaper moved the bottleneck
entirely onto terrain, and left it exactly where it was.

### Now

`crossfind` generates the terrain for every candidate and reports only what actually grew.
Candidates are counted separately from confirmations, and chunks that were never built are
counted separately again -- "grew no cane" and "was never generated" look identical if you only
count cane, and the difference is whether the candidate is wrong or the check is.

## 6bg: where crossfind's time goes, and what 3,500 candidates say

### The scan is the chain filter, and nothing else

```
1 thread, filter + shift        : 2.23e5 seeds/s
1 thread, orbit shift alone     : 4.12e8 seeds/s   (free)
12 threads, per-seed atomic     : 2.15e6 seeds/s
12 threads, local counter       : 2.19e6 seeds/s   -> 1.0x
```

Batching the shared counters buys nothing -- the contention theory was wrong, and 2.23e5 x 12
accounts for the whole observed rate. `StackPrefilter` was already built and rejected as a cheap
early-out, so there is no CPU trick left.

At **low** target heights the filter stops being the cost. Height 10 over 20M seeds produced
3.17M joins, and at 1.45 ms a lift that is 383 s across 12 threads -- the scan fell to 56k
seeds/s. So the bottleneck swaps: the lift dominates when joins are common, the filter when they
are rare. Height 16 is filter-bound, height 10 is lift-bound.

### Verification was never the cap

3,339 regions generated in 2.0 s, 1,666/s, parallel. It was assumed to be expensive and is not;
the parallelism is kept because it is free, not because it was needed.

### 3,500 candidates, zero cane

Across three runs -- 41 at height 16, 154 and 3,339 at height 10 -- **not one candidate grew a
single cane block**. Two structural explanations were checked and both are wrong:

- the simulator *can* place outside the decorating chunk (`SugarCaneFeature` writes through
  `world.setBlock` with no bounds test), and
- both chunks *do* decorate: `regionFor(0)` is 6, not 3, so chunk A at local (1,1) and chunk B at
  (2,1) are both interior and both pass the all-eight-neighbours test.

So the zero is real. The carve probe accepts something like one candidate per 3,500 that terrain
could ever support, which is the 6bf failure measured rather than argued.

### The GPU is 12x faster and does not agree with the CPU

On an idle card, 2.02e7 seeds/s at min 8 and 4.15e7 at min 12, against 2.7e6 for 12 CPU threads.
But against the `ranked` filter the target builder pairs it with, at min 8 over 4M samples:

```
gpu 2937, cpu 7669, gpu-only 358, cpu-only 5090
```

Disagreement in **both** directions, so not a subset. Two candidate causes: the kernel takes no
`maxAnyShift` argument while `ranked` sets it to 3 (explains gpu-only), and min 8 with
maxColumns 2 takes the greedy path, whose comment claims it "writes the same seeds" as the
general filter but which no test checks. `BundledKernelTest` only asserts the binary is bundled
and not stale. **Nothing pins the kernel to the filter**, and the target builder runs on that
pairing. Not yet resolved -- it may equally be that the parameter mapping reconstructed here is
wrong.

## 6bh: crossfind's candidates were never checked against the terrain that rejects them

6bg left 3,500 candidates and zero cane, and read that as the carve probe accepting roughly one
candidate in 3,500 that terrain could support. That was the right diagnosis of the symptom and
the wrong one of the cause. Four separate things were wrong, three of them fixable, and the
fourth is where cross-chunk actually stands.

### The noise gate was simply missing

`ReverseSearcher` gates every chain on `Worker.noiseCouldHoldChain`: below sea level a carver
turns stone into air and never turns water into air, so a column base the **noise** left as water
can never be carved, whatever the walk did. `crossfind` never called it. It is one noise column
against the probe's 81 carver walks, and rejecting on it cannot lose a find.

Worth 20%, not the systematic kill 6bf's "probe says carved 31, actually air 7, overlap 0"
suggested -- the overlap failure is real but it is the *walk* over-carving, not the noise:

```
4M seeds, height 10:  ocean 22916 -> solid noise 18326 -> past carve 149 -> generated 124
```

### The soil check was waved through on exactly the chains that needed it

6bf spotted that soil was only tested for relative x in 0..15 while the join geometry puts the
column at 12..19 or -4..3 about a third of the time. What it did not spot is that the fix is
free here. The single-chunk pipeline asks only the decorating chunk because when a target set is
built there is no world seed yet, so no neighbour's decoration seed can be named. `crossfind` has
**already solved the world seed** by the time it asks, so every chunk within blob reach is one
`decorationSeedOf` away. A blob reaches 8 blocks, so at most two chunk columns in x and two in z
can supply any block, and asking all four is both tighter than the wave-through and more
permissive than the single-chunk filter -- the 18% coverage that filter is known to lose to
neighbour blobs is exactly what this sees.

Candidates got 6x tighter, and every one is now actually checked.

### The bottom column has to stand ON the ravine floor, and nothing said so

This is the one that was hiding. Reading the base back out of generated terrain instead of only
counting cane splits the failures cleanly, and the split is not what the funnel suggested:

```
2M seeds, 64 sisters, no floor rule:  301 generated
  not air                      110
  air but no soil under it     191   -> of those: block under the base was carved away  119
                                                  was water                               0
                                                  was stone the blobs missed              72
```

**62% of the no-soil failures had the floor carved out from under them.** A ravine tall enough to
hold a cross-chunk stack usually takes the block below the base as well, and dirt cannot be
blobbed into air. Every filter in the pipeline asked whether the base was carved; none asked that
the block under it was not.

`--floor` adds that condition, and it is worth more than anything else here:

```
2M seeds, 64 sisters, --floor:         79 generated
  not air                       10   (13%, against 37%)
  air but no soil under it      69   -> carved away 0, water 0, stone the blobs missed 69
```

Kept as a flag rather than made the default, on the same reasoning as `--ravines-only` in 6at:
the probe over-approximates carving, so it can report "carved" where the real carver was stopped
and throw away a find that was standing on that floor. The over-approximation is mostly above the
noise floor and this block is below it, but "mostly" is not "measured".

### One lift was buying one roll of the only thing that ever says no

`TwoChunkLift` recovers the low 48 bits, which is all a decoration seed ever sees. 6al established
what the upper 16 do and do not move: the lattice solution, both decoration seeds, the carver walk
and the dirt blobs are **identical** across sisters, and only the biome map and, through depth and
scale, the sea floor change. `crossfind` solved a 1.45 ms lift and then tested exactly one of the
65,536 terrains it had just paid for.

Since terrain is the only thing that has ever rejected a cross-chunk candidate, those 16 bits
re-roll precisely the binding constraint. Reordering so the sister-invariant work (carve probe,
dirt blobs) happens once and the biome and noise gates sweep sisters:

```
2M seeds, 12 threads:  27 sister-invariant survivors -> 535 ocean pairs -> 403 candidates
                       (without sisters those 27 would have yielded ~8)
```

The reverse search caps its own sweep at 64 because chunk generation dominates there and
amortises over nothing. Here the thing being amortised is the lift, so 64 is a floor rather than
a ceiling; it is the default and `--sisters` raises it.

`allCarved` had to become biome-blind to be shareable, which is sound in the accepting direction
for the same reason 6al gives: `CAVE_LAND` fires on a superset of `CAVE_OCEAN` start chunks off
the same `nextFloat`. With `--ravines-only`, which is this command's default, the cave carver
does not run at all and the question does not arise.

### Where the candidates die now, which is one gate further than before

20M seeds, 11 threads, 64 sisters, `--floor`, 400 s:

```
  chains stored           : 4007919
  joins tried             : 3172600
  world seeds solved      : 3175764
  pairs inside the border : 1922430
  past carve + soil       : 200        (sister-invariant)
  both chunks cane ocean  : 3656       (x64 sisters)
  every base solid noise  : 3041
  generated               : 2368       (673 were never built)
  CONFIRMED               : 0

  not air                     1321
  air but no soil              936     (carved away 16, stone the blobs missed 920)
  soil but no water beside     111
  placeable, RNG did not         0
```

**111 candidates reached the last gate.** Across every cross-chunk run before this one, over
3,500 candidates, not a single one had air and soil together at the base; the run above has 4.7%
of them there, and what stops all 111 is water beside the base. That is the condition
`LiquidCarveProbe` answers and which `--water-probe` has always been able to apply, and it has
never been the gate that mattered because nothing used to get far enough to meet it.

"not air" rising to 56% is expected and is the price of sisters: the carve probe is
sister-invariant, so a sister whose sea floor sits elsewhere gets a walk that no longer describes
its terrain. The candidates are cheap and generation runs at 1,155/s, so this costs nothing worth
recovering.

### Sisters are 66x for 1.27x, because the lift is the entire cost

The funnel above accounts for its own runtime: 3.17M joins at 1.45 ms is 4,600 thread-seconds
against 400 s on 11 threads, so the lift **is** the run. The sister sweep is 200 survivors times
64 biome-source builds, which is nothing beside it. Raising it should therefore be close to free,
and it is -- same 20M seeds, same everything else:

```
  sisters    candidates    wall
       64         3,041     400 s
    4,096       200,379     506 s        66x the candidates for 1.27x the time
```

So 64 was leaving the whole family on the table. 4,096 is now the default, and the last 16x is
available with `--sisters`; it is not the default only because candidates are held in memory
until verification, which is what `--max-candidates` bounds.

### But 66x the candidates is not 66x the search, and the reason is 6al's own list

6al says what sisters share: the lattice, both decoration seeds, **the carver walk**, and the
dirt blobs. `LiquidCarveProbe` carries the same note -- "being a low-48 property it is shareable
across sister seeds exactly as the air walk". Put those together and all three conditions that
actually reject cross-chunk candidates are sister-invariant:

```
  air carve at every base   walk is low-48   -> identical across all 65,536 sisters
  water beside the base     walk is low-48   -> identical
  soil under the base       blobs are low-48 -> identical
```

What sisters do re-roll is the **noise**: which blocks are solid, and therefore which of the
walk's reach the real carver converts, plus the biome gate. That is a real re-roll and it is why
candidate counts move at all. It is not an independent redraw of the geometry.

So the effective sample for the binding constraint is the number of **sister-invariant
survivors** -- 200 in the height-10 run, not 156,765 -- and the truth sits somewhere between the
two, at 6al's measured 0.56 correlation for rare geometry. The run cannot separate them: fully
independent sisters predict 0.8 confirmations and fully correlated ones predict 0.001, and zero
is consistent with both.

The claim that survives is narrow and worth keeping anyway: sisters cost 1.27x and can only help,
so there is no reason not to sweep them. The claim that does not survive is that they multiply the
search 66x. **The lever that multiplies the search is distinct lifts**, because a different low-48
seed is the only thing that moves the carver walks.

### Where it stands: 156,765 candidates, and the wall is water

```
20M seeds, 22 threads, 4,096 sisters, --floor:  156,765 generated, 0 confirmed
  not air                     88,476   (56%)
  air but no soil             60,418   (39%)   carved away 1,211, stone the blobs missed 59,207
  soil but no water beside     7,871   (5.0%)
  placeable, RNG did not           0
```

The `--floor` rule is doing its job -- the failure it was built for is down from 62% of the
no-soil bucket to 2%. And 7,871 candidates now reach the last gate, against zero in every
cross-chunk run before this one.

**None of them have water.** That is the finding. The bottom column needs water beside the block
it stands on, `--floor` requires that block to be uncarved, and water beside a *solid* ravine
floor at depth means a LIQUID-step carver flooding the column next to it at exactly that y. It
happens -- the confirmed 8-tall is that geometry -- but 0 in 7,871 puts it below 1.3e-4 even
given air and soil, and `--water-probe` barely helps because the liquid probe over-approximates
about as freely as the air one (it trims candidates 43% and moved the water pass rate from 4.7%
to 3.4%, both of them still zero).

Cumulatively, counting 6bg's 3,500: about **168,000 cross-chunk candidates and no confirmation**.
Per candidate that bounds the rate under 6e-6; per *position*, which is the number that governs,
it is 168,000 candidates drawn from only a few hundred distinct low-48 seeds, and bounds nothing
much at all.

Height 17, the cheapest target above 16 (7+10, since 6ba puts 18 about 1000x below 16 and 20
further still), makes the position problem impossible to miss:

```
1e9 seeds, 22 threads, 4,096 sisters, --floor, 667 s
  chains stored           : 5563
  joins tried             : 40709
  past carve + soil       : 2          <- two positions
  both chunks cane ocean  : 2356       (x4096 sisters, off those two)
  every base solid noise  : 1899
  generated               : 541        (1358 never built -- one bad neighbourhood, twice)
  CONFIRMED               : 0
```

1,899 candidates that are really two places seen under many biome maps. The 71% never generated
is the same artifact: `searchRegion` needs all eight neighbours of the chunk to be cane-bearing
ocean, and with two positions in the sample, one bad neighbourhood is 71% of the run.

### What this does and does not settle

It does not overturn 6bf. Terrain is still the binding constraint and the RNG is still free; what
changed is that the pipeline now spends its terrain budget on the constraint instead of around
it, and can afford 66x more of it per lift.

The structural reading is that `crossfind` lets the **RNG choose the column** and then hopes the
terrain at that one column obliges. The base rate for the geometry is ~1.3e-3 per ocean chunk, or
~5e-6 for a named column, which is the order the measured bound sits at -- the search is not
missing something, it is paying the column-selection price in full. A terrain-first search picks
the column instead and pays for RNG, which is the trade `spot` already makes; and 6ba's result
that cross-chunk loses per-seed to one chunk is consistent with that, because the world seed it
solves for is an arbitrary one.

What that means for cost. Distinct positions arrive at 200 per 400 s on 11 threads at height 10,
and at **2 per 667 s on 22 threads at height 17**, since joins go as the square of the seeds and
a height-17 pass stores 5,563 chains where height 10 stores 4M. Getting height 17 to a few hundred
positions is a 1e10-seed run, about 1.8 hours on 22 threads, and a few hundred positions against a
~5e-6 per-position rate is not a find -- it is a lottery ticket. Cross-chunk reaches 17 for the
price of 16 in **RNG**, which was 6be's claim and is still true; it does not reach it for the price
of 16 in terrain, and terrain is the whole bill.

The lever this analysis points at is not throughput. It is to stop letting the RNG name the
column: find the geometry first, as `spot` does, and let cross-chunk supply the much easier RNG
that a found column then needs.

## 6bi: the GPU/CPU disagreement was mostly the harness, and the real bug is 0.11%

6bg found the kernel and the ranked filter disagreeing in both directions at min 8 --
`gpu 2937, cpu 7669, gpu-only 358, cpu-only 5090` -- and left it open, noting it might be the
parameter mapping. It was. `KernelAgreement` now takes every parameter from one place and hands
the same numbers to both sides.

### cpu-only 5090 was two different filters

`ChainPrefilter.ranked` leaves `maxSlack` at `Integer.MAX_VALUE`. `ReverseSearcher` runs the whole
pipeline at `maxSlack = 0` -- the contiguous window of 6ap -- and passes that 0 to the kernel. So
the old comparison ran an unrestricted CPU filter against a contiguous kernel, and the 5,090
"cpu-only" seeds are the ones contiguity is *supposed* to reject.

Matched, at min 8 over 4M samples:

```
  gpu 2937, cpu 2574
  agreed 2574, gpu-only 363, cpu-only 0
```

**The CPU is a strict subset of the GPU.** Nothing the builder wants is being dropped at min 8.

### gpu-only 363 is the greedy path, and it is harmless

Those seeds have `tallestPossible = 4` against a min height of 8 -- they cannot make the height at
all. The greedy walk says so itself ("may over-accept"), and the second pass does not catch all of
it. It costs nothing but soil-filter work, because `ReverseSearcher` re-tests every seed the kernel
returns on the CPU. The line that does so carried the comment "cannot happen; the GPU already
agreed"; it happens 12% of the time and that line is the only thing keeping those seeds out of the
target set. Comment corrected.

Turning the greedy path off confirms the attribution -- min 7 and min 9 are not divisible by four,
and neither has a single gpu-only seed.

### What is left is real, and it runs the other way

```
  min 7 : gpu 14802, cpu 14818, gpu-only 0, cpu-only 16     (0.11%)
  min 8 : gpu  2937, cpu  2574, gpu-only 363, cpu-only 0
  min 9 : gpu    46, cpu    46, exact agreement
```

At min 7 the kernel **drops 16 seeds the filter keeps**, and this is the direction that costs
finds: a dropped seed is never re-tested, so a GPU-built target set is missing it and nothing
downstream can recover it. Four of the sixteen have `tallestPossible = 8`.

They share a shape:

```
  x=8  z=13  cols=2  baseShift=0  maxShift=1  y32+3 y35+4   run 7
  x=5  z=10  cols=2  baseShift=0  maxShift=1  y22+3 y25+4   run 7
  x=3  z=4   cols=2  baseShift=0  maxShift=1  y22+4 y26+3   run 7
  x=2  z=7   cols=2  baseShift=0  maxShift=1  y26+4 y30+4   run 8
  x=10 z=3   cols=2  baseShift=0  maxShift=1  y30+3 y33+4   run 7
```

Two columns, base shift 0, max shift 1, bases high in the 13..35 band. The suspect is the
incremental path: when the previous window rejected, the kernel only looks for a chain **ending in
the newest invocation**, which is sound under `strictOrder` but is the one place a two-column chain
spanning a slide could fall through. Not fixed here -- 0.11% did not justify a CUDA debugging
session against the other things this run was for -- but it is now pinned by
`KernelAgreementTest` at a 1% bound, and min 9 is pinned to exact equality.

### Why this was worth an hour

The reason to chase it was the possibility that GPU target sets were missing two thirds of their
targets, which would have made every reverse search since the kernel landed roughly 3x weaker than
it looked. **They are not.** Sets are missing about 0.1%, and the 66% was an artifact of comparing
two different filters. That closes the thread rather than opening a rewrite.

## 6bj: why there is no cane/carver meet in the middle, and the hook that makes it look like there is

The idea, proposed as a hunch: enumerate constraints from the cane side and from the carver side
and meet in the middle on the world seed. There is a real structural hook under it, and it still
does not pay. Both halves are worth writing down, because the hook will keep suggesting the idea.

### The hook is real

`setDecorationSeed` and `setLargeFeatureSeed` both begin `setSeed(worldSeed)` and draw **the same
two `nextLong`s**. They then diverge:

```
  decoration : a = nextLong()|1, b = nextLong()|1,  D = (16cx*a + 16cz*b) ^ W
  carver     : l = nextLong(),   m = nextLong(),    C = (sx*l ^ sz*m ^ W)
```

Same draws, one pair odd-ified, one combining with `+` and the other with `^`. Cane and carver are
not independent, which is exactly the shape a meet in the middle feeds on.

### It does not pay, for two independent reasons

**The cane side already pins the seed.** A cross-chunk pair is 44 bits of constraint on 44 free
bits of `W` -- 6be measured one solution per pair, 32,127 world seeds from 32,359 joins. There is
no residual freedom for a carver constraint to cut into, so there is no product space to split.
A meet in the middle wants an under-determined system; this one is exactly determined.

**And the second condition is cheap, which is the more general objection.** A MITM pays when
checking condition B against a solution of condition A is expensive. Here, given `W`, the carver
seeds at the relevant chunks are one LCG step each and the whole walk is ~49 us, against the
1.45 ms lift that produced `W`. **Checking is already 30x cheaper than solving.** There is nothing
to meet in the middle about.

### The version that is a MITM dies on position

Enumerate `W` from the cane side (that is `crossfind`), enumerate `W` from the carver side (that is
`CarverReverser`, ~2^16 starts plus lifting), and intersect. Both sets are enumerable and both live
in 2^48, so a collision needs `|A|*|B| = 2.8e14` -- 1.7e7 each, which is hours rather than years.

It fails on the coordinate. A collision in the `W` **value** puts the ravine at whatever `(sx, sz)`
the carver side happened to reverse at, which has nothing to do with where the cane column is. Add
back "the ravine must be within carve radius of the column" and the carver side stops being a free
enumeration: for a given `W` the carver seeds near the column are determined, and testing them is
the `AirCarveProbe` walk the search already runs. The MITM collapses into the existing probe.

### What this says about where the cost is

The bottleneck was never *checking* terrain. It is that terrain rejects 99.99% of positions and the
search cannot mint world seeds fast enough to absorb that. Anything that makes checking cheaper --
a MITM, a better probe, carver reversal -- attacks a term that is already small. The term that is
large is positions per second, which is why the ranked lever is throughput: `crossfind`'s pass 2
runs at 3.26e6 seeds/s on 22 CPU threads against the kernel's 4.47e7, and joins go as the **square**
of seeds scanned, so 14x the rate is ~196x the positions.

## 6bk: one table, eight neighbours -- 3.5x positions for 3% of the time, and a discard nobody noticed

6bj put the cost in the right place: positions per second, not the price of checking one. The
cheapest position multiplier available was sitting in pass 1.

### The table never needed to know the direction

Pass 1 keyed a stored **beginning** after shifting it into chunk A's frame (`x += 16*dx`), which
baked the neighbour offset into the table and meant a table could serve exactly one direction.
Keying each side in its **own** chunk's frame instead makes the table direction-free, and the
streamed side does the shifting -- once per direction, as a frame test and a lookup. So all eight
neighbours share one pass 1 rather than needing eight.

### The shift was also silently discarding most of the table

`inFrame` was applied *after* the shift. A cane column occupies relative -4..19, so requiring
`x + 16 <= 19` kept only `x <= 3` -- a third of the range thrown away before anything looked at
it, and thrown away for a reason that was an artifact of the keying rather than a property of the
geometry. Unshifted, the whole range stores.

Height 17, 1e9 seeds, 22 threads, 4,096 sisters, `--floor`, identical otherwise:

```
                        one direction    eight directions
  chains stored                5,563              31,374     5.6x
  joins tried                 40,709             160,627     3.9x
  pairs inside the border     24,788              97,014     3.9x
  past carve + soil                2                   7     3.5x
  candidates                   1,899               6,258     3.3x
  wall clock                    667 s               685 s    1.03x
```

**3.5x the surviving positions for 3% more time.** Less than the 8x the direction count suggests,
because a chain near one edge of the frame cannot reach a neighbour on that side -- the frame test
prunes most directions for most chains, which is exactly the constraint that was previously being
applied once and destructively.

Still zero confirmed, and at this height nothing even reaches the water gate: 4,900 generated,
3,167 with no air at the base, 1,733 with air and no soil, 0 with soil. Seven positions cannot
populate the tail that 6bh's 200 could.

### What is left

The remaining lever is the one 6bj named. Pass 2 streams at 3.15e6 seeds/s on 22 CPU threads
against the kernel's 4.47e7 on one 4080, and joins go as the **square** of seeds scanned, so
moving the chain scan to the GPU is worth ~200x positions rather than 14x. The kernel already
emits accepted decoration seeds; it would additionally have to emit each chain's join coordinate
`(x, z, y)` so the key can be formed on the host. That is the next thing worth building, and it is
a bigger change than anything in 6bh through 6bk.

## 6bl: the kernel as a pre-filter for crossfind -- 9.9x, and it needed no kernel change

6bj ranked this first and estimated it as a kernel rewrite. It is not one. `crossfind` does not
need the GPU to report chain geometry; it needs to know **which seeds are worth looking at**, and
the kernel already answers exactly that. Used as a pre-filter, with the CPU re-deriving geometry
for the survivors, the CPU work falls by the acceptance rate:

```
  height 17, pass 1 (>= 10, the stored side) : kernel keeps 0.0031% of seeds
  height 17, pass 2 (>=  7, the streamed one): kernel keeps 2.2259%
```

### It had to be pinned to the two filters first

`crossfind` does not use the ranked filter, so 6bi's agreement result does not transfer.
`KernelAgreement` grew `--config=crossfind-ending` and `--config=crossfind-beginning`, which build
both sides from one set of numbers. Over 4M samples:

```
  h= 7 ending    agreed  89602, gpu-only 0, cpu-only 0
  h= 7 beginning agreed 192013, gpu-only 0, cpu-only 0
  h= 8 ending    agreed  16073, gpu-only 0, cpu-only 0
  h= 8 beginning agreed  34089, gpu-only 0, cpu-only 0
  h=10 ending    agreed     32, gpu-only 0, cpu-only 0
  h=10 beginning agreed     97, gpu-only 0, cpu-only 0
```

**Exact agreement everywhere**, including height 7, where 6bi caught the kernel dropping seeds.
It does not drop them here because `crossfind` runs at `maxBaseShift 3`, which takes neither the
greedy path (it needs 0) nor the incremental path's failure case. The 6bi defect is real and is
confined to the ranked configuration.

### End to end, and the two regimes

Both paths were run over the same seeds, `--cpu` against the GPU:

```
  height 10, 4M seeds, 12 threads   CPU 49.1 s   GPU 48.4 s   1.0x
  height 17, 1e9 seeds, 22 threads  CPU  685 s   GPU 69.2 s   9.9x
```

Every funnel number is identical in both cases -- 803,929 / 520,473 / 37 at height 10 and
31,374 / 160,627 / 7 at height 17 -- so this is a speed change and nothing else.

Height 10 gains nothing because it is **lift-bound**, exactly as 6bg measured: 520,473 joins at
1.45 ms over 12 threads is 63 s, which is the whole run. Height 17 is **filter-bound**, and there
the kernel is the whole point. The regimes are the ones 6bg named; what is new is that the tool
now matches them.

9.9x rather than 14x because pass 1's 23.8 s includes fixed setup, and because the CPU still runs
the geometry pass over 2.2% of a billion seeds.

### What it is worth, which is more than 9.9x

Joins go as the **square** of seeds scanned, so 9.9x the rate is ~98x the positions in a fixed
wall-clock. Positions are the currency 6bh established: the terrain filters reject 99.99% of them
and nothing else in the pipeline moves that rate. Combined with 6bk's 3.5x from the shared table,
height 17 now produces roughly **340x the positions per hour** it did at the start of this run.

Still zero confirmed. The bottleneck moves to the lift as positions multiply, which is where 6bg's
two regimes come back: at 5e9 seeds and up, height 17 becomes lift-bound too, and the next thing
to price is the 1.45 ms.

## 6bm: 6,344 positions at height 17, and the first cross-chunk cane that ever grew

3e10 seeds, 22 threads, 256 sisters, `--floor`, 4.1 hours. Sisters were traded down from 4,096
because 6bh established they inflate candidates without adding independent tries; the budget went
to positions instead.

```
  chains stored           :     919,173
  joins tried             : 145,207,288
  past carve + soil       :       6,344      positions
  both chunks cane ocean  :     475,533      (x256 sisters)
  candidates              :     335,093
  generated               :     270,636
  CONFIRMED               :           0
```

**6,344 positions**, against 456 in the 8e9 run and 2 in the first height-17 attempt this morning.

### Two firsts, and they are the point

```
  tallest cane actually grown : 4        (every previous run: 0)

  not air                     : 153,852   57%
  air but no soil             : 109,475   of the air, 94%
  soil but no water beside    :   7,163
  placeable, RNG did not      :     146   <- every previous run: 0
```

Cane grew. Not a stack — a single column, four tall — but across every cross-chunk run before this
one the tallest thing ever produced at a join column was **nothing at all**, and the reason was
always that the base failed. Now 146 bases were **fully placeable**: air in the block, soil under
it, water beside it, everything the feature asks for.

So the funnel finally has a floor under it, and the gates can be priced:

```
  P(air at the base)                  43%     of generated candidates
  P(soil | air)                      6.3%
  P(water | soil)                    2.0%     <- was 0 of 7,871
  P(fully placeable)                5.4e-4    per candidate
  P(stack completes | placeable)   < 2%       0 of 146
```

Water was 6bh's wall and it is not an absolute one. **But read the 2.0% carefully, and 6bn shows
why**: it is a rate per *candidate*, and candidates cluster on positions. Water is sister-invariant,
so the 146 came from some small number of positions, not 146 of them. The per-position rate is what
governs and this run cannot report it — the same effective-sample trap 6bh fell into, one level up.

### The bound, which is the run's real product

Cumulatively about **7,000 positions and no confirmation**, so the per-position rate is under
**4.3e-4** at 95% — ten times tighter than the 4.6e-3 the morning's data supported. The structural
estimate for a named column, ~5e-6, is still comfortably inside that and is still the number to
plan against.

### The new frontier is the last gate, not the first

0 of 146 placeable bases completed their stack, and the histogram cannot say why because it only
reads chunk A's **bottom** base. The candidates are 17-tall predictions; something above the first
column is failing. Three things it could be, none measured:

- the upper columns' own bases are not air, or have no water beside them;
- the chain assumed a shift level the real chunk did not produce, so the draws never line up;
- decoration order — chunk B decorating before chunk A, which no run has ever verified.

That is the question worth instrumenting next. It is also the first time the question has been
about the *stack* rather than about the ground it stands on.

## 6bn: height 20 at 2e11 seeds — the predictions checked, and one of them was wrong

2e11 seeds, 24 threads, 1,024 sisters, `--floor`. Sized from 6bm's numbers, which makes it a test
of those numbers as much as a search.

```
  chains stored           :     139,477
  joins tried             :  26,449,477
  past carve + soil       :       1,004      positions
  candidates              :     181,716
  generated               :     151,483
  CONFIRMED               :           0
  tallest cane grown      :           0
```

### What was predicted, and what happened

```
  positions      predicted 1,000-2,000     got 1,004      right
  expected finds predicted ~0.01           got 0          consistent
  wall clock     predicted 3.5-4 h         got 7.3 h      wrong by 2x
```

The timing miss is the flagged weakness turning out to matter. The estimate used a GPU scan rate of
4.2e7 samples/s measured at **height 17**; backing it out of this run gives **1.63e7**. Accounting
for the rest confirms it is the scan and nothing else:

```
  lifts      26.4M joins x 1.45 ms / 24 threads =  1,598 s
  geometry   0.3966% of 2e11 at ~4.5 us / 24    =    149 s
  scan       the remaining                       = 24,488 s   (93% of the run)
```

Height 20's stored side runs the **beginning** filter at min 12, whose band is 11..64 rather than
13..35. A band three times wider leaves far more candidate groups alive in the kernel's DP, so it
scans 2.5x slower. The lesson is narrow and worth keeping: **the kernel's rate is a property of the
filter, not of the card**, and quoting one height's rate at another is what produced a 2x error.

### The water rate, corrected

6bm reported P(water | soil) = 2.0% from 146 placeable candidates. This run got **0 of 3,519**.
At 2% that is 70 expected, which chance does not explain — so one of the two is not measuring what
it says.

It is 6bm's, and the reason is the trap this project keeps re-entering. Water comes from the liquid
carver walk, which is **low-48 and therefore sister-invariant**: every sister of a position has the
same answer. 6bm ran 256 sisters and produced 53 candidates per position; this run ran 1,024 and
produced 181. So a candidate-level water rate mostly measures how many sisters were configured,
and comparing two runs at different sister counts compares nothing.

Per **position** the two agree completely: 6bm's 146 placeable candidates came from some small
number of its 6,344 positions, and 0 of this run's 1,004 is exactly what a rate of order 1e-3 per
position predicts. There is no discrepancy, and there was never a 2% water rate.

### The bound

About **8,000 positions across the two runs, no confirmation**: the per-position rate is under
**3.75e-4** at 95%. The structural estimate for a named column, ~5e-6, remains well inside it.

### And the cost comparison, now measured rather than projected

Height 20 took 6.7x the seeds and 1.8x the wall clock of 6bm's height-17 run to produce **6.3x
fewer positions** — 1,004 against 6,344. That is the ~146x per-seed penalty quoted when the split
was chosen, arriving as predicted. Anything aimed at a find belongs at 17.

## 6bo: the lift on the GPU, 4.3x — and the obvious optimisation made it slower

6bl moved the chain scan to the card and 6bn measured what that left: at 2e11 seeds the scan was
93% of the run, but at height 10 the scan finishes in minutes and the run spends **hours** in
`TwoChunkLift` with the GPU idle. 6bc had ruled the lift out for the card — "a branchy tree with
data-dependent survival ... it runs once per candidate pair, not once per seed" — which was right
when pairs were rare and stopped being right when a run does 288 million of them.

### There was nothing left on the CPU

Measured before writing any CUDA, which is the step worth keeping:

```
  residual()      3.62 ns each      (~14 cycles: four LCG steps and some masks)
  one lift      ~400,000 residual calls
```

The lookahead is 12 and 11 provably fails, the four low bits are already free, and pruning is one
new bit of constraint per level, which is all there is. ~400k nodes to find ~1 seed is the price
of the blind prefix, not slack. So the only lever was parallelism.

### The shape

A pair's blind prefix is 2^12 independent starting values, each walking its own subtree: one
thread per (pair, prefix), no cross-talk, and a thread whose subtree dies just idles. The CPU
walks levels breadth first and the kernel walks each prefix depth first — same tree, same
survivors, different order, which is why the test compares sets.

### Three versions, and the middle one is the lesson

```
  separate ws[] and bit[] arrays, STACK 40        1,554 ms / 50k pairs
  + carry the residual down the 0-branch          1,938 ms      SLOWER
  level packed into ws's spare bits, STACK 34     1,110 ms      best
```

A node's residual does not depend on its level — only the mask widens — so carrying it down the
0-branch **halves the 48-bit multiplies**. It made the kernel 25% slower. The stack spills to
local memory and this kernel is bound by that traffic, not by arithmetic; a second 8-byte array
cost more than the multiplies it saved. Packing the level into the top bits of the 48-bit `ws`
went the other way and won 1.4x.

Worth stating plainly because the arithmetic argument was compelling and wrong: **on this kernel,
count the loads, not the multiplies.**

### What it is worth

```
  GPU marginal    ~14.2 us/pair   70,400 pairs/s   plus ~400 ms fixed CUDA start-up
  24 CPU threads   1.45 ms/pair   16,550 pairs/s
                                                    4.3x
```

The fixed cost means batches want to be large; a few thousand pairs is mostly start-up.

### Held to the CPU, both directions

`GpuLiftTest` builds pairs from a known world seed the way a join does, and requires the kernel to
return **exactly** `TwoChunkLift.solve`'s set for each, and to recover the planted seed every time.
Both matter, because 6bb warned that a wrong solver returns most of the answers and looks perfect,
and 6bc's sign-extension bug did exactly that — every seed it returned satisfied the equation
while the true one was discarded about half the time. 1,500 pairs over all eight neighbours, zero
disagreements, zero misses.

### Not yet wired in

`GpuLift` exists and is tested; `crossfind` still lifts on the CPU. Pass 2 calls `examine` per join
inline, and using the kernel means batching joins, lifting a batch, then continuing — a real
restructure of the hot loop rather than a flag. That is the next piece of work, and until it lands
the 4.3x is available only to whatever calls `GpuLift` directly.

## 6bp: the cross-chunk join has never once been exercised

Height 10, 1e8 seeds, 7 hours, **18,420 positions** — the largest sample of cross-chunk positions
yet, at the height where terrain is easiest. 236,729 candidates generated, zero confirmed. The
column walk of 6bm is what makes this run different from the ten before it:

```
  where the predicted stack stops, first column with no cane:
    A col 0: BLOCKED               137,713
    A col 0: NO_SUPPORT             91,919
    A col 0: NO_WATER                7,019
    A col 1: NO_WATER                   39
    A col 0: PLACEABLE_BUT_EMPTY        30
    A col 1: PLACEABLE_BUT_EMPTY         9

  48 grew some cane, of which 0 ran taller than any one chunk built
```

**Every line is chunk A.** Not one candidate in 236,729 reached chain B — the neighbour, the
entire point of the command. 236,681 of them die on chunk A's *first* column; 48 reach its second;
none go further. And of the 48 that grew cane, **zero** exceeded `caneRunFromOneChunk`, so no two
chunks have ever cooperated here, not once, in the ~500,000 candidates generated today.

### What that means, and it is not a tuning problem

The split at height 10 is 5+5, so chain A must itself build a **5-tall stack** before the join is
even reachable. That is the project's confirmed find — the one that took a search of a few billion
chunks to turn up. Cross-chunk does not divide the terrain problem between two chunks: chunk A
still has to complete an entire multi-column stack on its own, and only then does the neighbour
get to add to it.

So `crossfind` at target H with split (a, b) is **strictly harder than a single-chunk a-tall**. Its
only real advantage is that a + b can exceed what four shift levels allow one chunk — 16 — and that
advantage is bought on top of, not instead of, the a-tall. 6ba said exactly this from the rates
("cross-chunk is worse at every height a single chunk can reach"); this is the same conclusion
arriving from the terrain side, and it explains every zero in 6bh, 6bm and 6bn at a stroke.

`PLACEABLE_BUT_EMPTY` — everything the feature asks for present and no placement — is 39 of
236,729. The shift-level and decoration-order suspicions of 6bm are real but negligible. They were
never the reason.

### Which points somewhere specific

The search has been building chain A from scratch and hoping, at a column the RNG chose. The
pipeline that reliably produces tall single-chunk stacks already exists: `reverse` found the 8, and
the record 16. **Start from a stack that pipeline found, and ask whether the neighbour extends it.**

That inverts the expensive half. Chain B needs no soil — it stands on chunk A's cane — and the air
it needs is in the ravine chunk A is already standing in, so the terrain is largely paid for. What
remains is one question about the neighbour's decoration seed, which is pure RNG and cheap.

It also explains why 17 as 9+8 looked so attractive in 6be and has never materialised: it still
needs a 9-tall to exist first.

## 6bq: the split does not matter, because everything dies on the first column

6bp showed every cross-chunk candidate failing inside chunk A, and suggested the split was
mis-chosen: `bestSplit` maximises `rate(a) * rate(b)`, which is symmetric in a and b, while the
two sides are not remotely symmetric in terrain — chunk B needs no soil and stands in the ravine
chunk A already occupies. So a lighter chunk A ought to convert better. It does not.

Height 10, 24 threads, 64 sisters, `--floor`, the automatic split against the lightest possible
chunk A:

```
              positions  generated   BLOCKED  NO_SUPPORT  NO_WATER  past col 0
  auto 5+5          757      9,742     58.4%       39.5%      2.1%           1
  light 4+6       1,198     15,195     58.8%       39.0%      2.2%           0
```

**The distributions are the same to a few tenths of a percent.** Lowering `minA` bought 1.2x the
positions per second and changed the conversion not at all.

### Which is obvious once seen

Chain A's *first* column asks for exactly the same three things whatever the chain's length: air
in the base, soil under it, water beside. `minA` governs how many columns come **after** that, and
essentially nothing survives to find out. So the split cannot matter while ~100% of failures are
at column 0, and no re-weighting of `bestSplit` will change anything.

### What actually binds

```
  P(a cane column places at an RNG-chosen position)  ~2e-4     (48 of 236,729, 6bp)
```

That is `crossfind`'s ceiling, and it is a property of letting the RNG name the column rather than
of any parameter. The filters are the reason it is not higher — 58.8% of positions have a base the
carve probe called carved and terrain calls solid, 39.0% have soil the blob filter allowed and
terrain does not have. But sharpening them would not raise throughput, only reduce wasted
generation, and generation runs at ~2,900/s against a search that is lift-bound. **The filters are
not the bottleneck; the architecture is.**

### So, for 20 and above

- **Exactly 20** is reachable by one chunk — five shift levels, five columns of four — and
  `reverse` is the right tool because it keeps free choice of world seed, which is the thing
  `crossfind` gives up and 6bp shows it cannot afford to. It needs a five-level target set, which
  nothing has built; every existing set is four-level and 6al's note that "heights up to 16 need
  four and get four" is why.
- **21 and above** is cross-chunk only: `minimumColumns(21)` is 6 against a five-entry `SHIFTS`
  table, and `reverse 21` stops with `find_targets: shiftLevels must be 1..5, got 6`. `crossfind`
  is unaffected because each side runs its own four-level filter, capping a chain at 16 and a pair
  at 32.
- And 21+ therefore inherits the 2e-4 ceiling above, on top of needing chunk A to complete a stack
  that has never once been observed to complete. That is the honest position: **not a tuning
  problem, and not one more run away.**
