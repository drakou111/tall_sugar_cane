"""Export pre-flood chunks so the simulated carve can be compared against them.

A chunk saved at status 'features' has had noise, surface, both carving steps and
its own decoration applied, but has never been promoted to a LevelChunk, so its
scheduled fluid ticks never ran. That is the exact state the sugar cane feature
saw, and the only ground truth that can settle whether this project's carvers cut
the right blocks — a 'full' chunk has been flooded and cannot.

Writes a binary: magic "PROT", seed, count, then per chunk cx, cz and 71 * 256
category bytes (y=0..70, indexed y * 256 + localX * 16 + localZ).

  python export_proto.py <world> <out.bin> [maxChunks]
"""
import glob
import gzip
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcnbt import read_nbt, Reader  # noqa: E402

WORLD = sys.argv[1]
OUT = sys.argv[2]
MAX = int(sys.argv[3]) if len(sys.argv) > 3 else 100000
HEIGHT = 71


def world_seed(world):
    """The seed out of level.dat.

    It used to be written as a hardcoded 1 for the caller to patch, and a caller that
    forgot produced a file the validator happily read with the wrong seed: it
    regenerated a different world, found no ocean chunks, and reported 0/0 cells and
    0.0000% accuracy. Reading it here removes the step that can be skipped.
    """
    raw = gzip.open(os.path.join(world, 'level.dat'), 'rb').read()
    # 1.16 keeps it at Data.WorldGenSettings.seed; older worlds at Data.RandomSeed.
    tag = read_nbt(Reader(raw))

    def walk(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key in ('RandomSeed', 'seed') and isinstance(value, int):
                    return value
                found = walk(value)
                if found is not None:
                    return found
        elif isinstance(node, list):
            for value in node:
                found = walk(value)
                if found is not None:
                    return found
        return None

    seed = walk(tag)
    if seed is None:
        raise SystemExit('no seed in %s/level.dat' % world)
    return seed

# Categories must match the Java side.
OTHER, AIR, WATER, STONE, DIRT, SAND, GRAVEL, CLAY, GRASS, CANE, ICE, LAVA = range(12)

AIR_NAMES = {'air', 'cave_air', 'void_air'}
WATER_NAMES = {'water', 'kelp', 'kelp_plant', 'seagrass', 'tall_seagrass',
               'bubble_column'}
STONE_NAMES = {'stone', 'granite', 'diorite', 'andesite', 'sandstone',
               'red_sandstone', 'terracotta', 'white_terracotta', 'coal_ore',
               'iron_ore', 'gold_ore', 'redstone_ore', 'lapis_ore',
               'diamond_ore', 'emerald_ore', 'infested_stone', 'bedrock',
               'obsidian', 'magma_block', 'packed_ice', 'blue_ice', 'snow_block',
               'mossy_cobblestone', 'cobblestone', 'chest', 'spawner', 'rail',
               'oak_planks', 'oak_fence', 'torch', 'wall_torch', 'oak_log',
               'dark_prismarine', 'prismarine', 'prismarine_bricks',
               'sea_lantern', 'wet_sponge', 'gold_block', 'sandstone_stairs',
               'sandstone_slab', 'chiseled_sandstone', 'cut_sandstone'}


def category(name):
    # Lava is its own category rather than OTHER. The simulator assumes lava exists only
    # below y=11 (Carver), and the real world has it up to y=30 -- inside the cane depth
    # band -- so lumping it with coral and leaves hid a class of invented find.
    if name == 'lava':
        return LAVA
    if name in AIR_NAMES:
        return AIR
    if name in WATER_NAMES:
        return WATER
    if name == 'dirt' or name == 'coarse_dirt' or name == 'podzol':
        return DIRT
    if name == 'sand' or name == 'red_sand':
        return SAND
    if name == 'gravel':
        return GRAVEL
    if name == 'clay':
        return CLAY
    if name == 'grass_block' or name == 'mycelium':
        return GRASS
    if name == 'sugar_cane':
        return CANE
    if name == 'ice' or name == 'frosted_ice':
        return ICE
    if name in STONE_NAMES:
        return STONE
    return OTHER


out = open(OUT, 'wb')
out.write(b'PROT')
SEED = world_seed(WORLD)
print('world seed %d' % SEED)
out.write(struct.pack('<q', SEED))
count_pos = out.tell()
out.write(struct.pack('<i', 0))

written = 0
files = sorted(glob.glob(os.path.join(WORLD, 'region', '*.mca')))
for path in files:
    if written >= MAX:
        break
    try:
        with open(path, 'rb') as f:
            header = f.read(4096)
            f.read(4096)
            blob = f.read()
        if len(header) < 4096:
            continue
    except OSError:
        continue
    for ci in range(1024):
        if written >= MAX:
            break
        off = struct.unpack('>I', b'\x00' + header[ci * 4:ci * 4 + 3])[0]
        if off == 0:
            continue
        start = off * 4096 - 8192
        if start + 5 > len(blob):
            continue
        length = struct.unpack('>i', blob[start:start + 4])[0]
        comp = blob[start + 4]
        raw = blob[start + 5:start + 4 + length]
        if not raw:
            continue
        try:
            data = zlib.decompress(raw) if comp == 2 else gzip.decompress(raw)
            level = read_nbt(Reader(data))['Level']
        except Exception:
            continue
        status = level.get('Status', '')
        # Only pre-flood chunks that have nonetheless been decorated.
        if status not in ('features', 'light', 'spawn', 'heightmaps'):
            continue
        cx, cz = level['xPos'], level['zPos']
        grid = bytearray(HEIGHT * 256)
        for sec in level.get('Sections', []):
            pal, states, y0 = sec.get('Palette'), sec.get('BlockStates'), sec.get('Y')
            if pal is None or states is None or y0 is None or y0 < 0 or y0 * 16 > 70:
                continue
            cats = [category(p['Name'].replace('minecraft:', '')) for p in pal]
            bits = max(4, (len(pal) - 1).bit_length())
            per_long = 64 // bits
            mask = (1 << bits) - 1
            for i in range(4096):
                y = y0 * 16 + (i >> 8)
                if y > 70:
                    break
                li = i // per_long
                if li >= len(states):
                    break
                idx = (states[li] >> ((i % per_long) * bits)) & mask
                if idx < len(cats):
                    grid[y * 256 + (i & 15) * 16 + ((i >> 4) & 15)] = cats[idx]
        out.write(struct.pack('<ii', cx, cz))
        out.write(bytes(grid))
        written += 1
        if written % 200 == 0:
            print('  %d chunks' % written, flush=True)

out.seek(count_pos)
out.write(struct.pack('<i', written))
out.close()
print('wrote %d proto chunks to %s' % (written, OUT))
