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
