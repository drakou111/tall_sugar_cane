# How Minecraft 1.16 grows impossible sugar cane

*Written for video — trim to taste. A 150-word cut for the description box with the full
version as a pinned comment works well.*

## The setup

Sugar cane doesn't grow tall in Minecraft. It grows **2, 3, or 4 blocks** — that's it. The
game picks from `2 + nextInt(nextInt(3)+1)`, which lands on 2 about 61% of the time and 4
only 11%.

So a 12-block column shouldn't exist. Except in 1.16 it can, and finding one is a computing
problem.

## Why 1.16 is special

Every chunk runs a feature called `patch_sugar_cane`. In ocean biomes it runs **10 separate
times**, and each run does this:

1. Pick a random x and z inside the chunk
2. Pick **y = nextInt(2 × 63)** — a random height from 0 to 125

That second step is the whole story. **The y is not the surface.** It's a random height in
the world, which means most attempts land inside solid rock or floating in mid-air and do
nothing at all.

Then it makes 20 attempts, each jittered a few blocks from that origin — but critically,
**all 20 share the same y**, because the vertical spread is zero.

For cane to place, three things must hold: air at the target, sand/dirt/gravel below it, and
**water beside the block underneath**.

## The stacking trick

Here's the part that makes tall cane possible. Those 10 runs happen **in sequence, on the
same chunk** — so run number 7 can see blocks that run number 3 already placed.

If a later run happens to pick the exact spot on top of an earlier column, it stacks. Cane on
cane is legal. **4 + 4 = 8. Three of them = 12. Four = 16.**

But that water requirement still applies at every level. A column stacking at y=31 needs
water at y=30 — which above sea level simply doesn't exist.

**So this only happens underwater.** Ocean biomes register underwater cave and canyon carvers
that cut air pockets against a solid wall of water. That's the one place in the game where
you can have air, soil, and water at the right heights, forty blocks below the surface.

We tallied it: **38 stackable spots across every ocean biome, and exactly 0 across ~25,000
land chunks.**

## How we search for it

The obvious approach — generate chunks and look — is hopeless. Chunk generation is the
expensive part, and the odds are roughly one in a billion.

So we do it backwards.

**1. Pick the dice first.** Everything the feature does comes from one 48-bit random number
generator, seeded from a per-chunk value. Know that value, and you can replay every single
draw with pure integer math — no terrain, no world, no chunk generation.

**2. Ask the cheap question.** "Could these draws build a tall stack, *if* the terrain
cooperated?" At height 12 about **one in 100 million** seeds pass.

**3. Invert it.** The function turning a world seed and chunk position into that per-chunk
value is affine — so a lattice reduction runs it *backwards*, turning a winning random seed
into real world coordinates. This is why finds turn up millions of blocks from spawn.

**4. Only now generate terrain**, and only for the handful that survived.

## Making the filter honest

The interesting engineering isn't speed — it's **removing things that can't actually
happen**, which costs nothing and makes everything faster:

- **Each successful placement consumes 2 extra random draws**, so a column physically cannot
  read the stream at the same offset as the one below it. Enforcing that removed most of the
  search space.
- **Only the first attempt landing on a spot can be the column.** A later one finds cane
  there, not air. That alone was 26% of what we were accepting — chains no chunk could ever
  produce.

Two completely independent implementations — one a dynamic-programming search over
enumerated candidates, one a single-pass greedy stream walk — now agree on **exactly the same
2,616 seeds out of 4 million**. Sharing no code and written from opposite directions, that
agreement is worth more than either alone.

Current throughput: **over 400 million decoration seeds per second** on a single GPU.

## Where it stands

- An **8-tall** has been verified in the real game
- A **5-tall**, likewise
- The simulator has produced **10s and 11s** awaiting in-game confirmation
- **16 is the ceiling** of what the current filter can even represent — four stacked 4s

Nothing taller has been seen. Whether it exists at all is still open.
