"""
Vanilla structure-NBT writer, and the trimming/anchoring a tree needs.

Output is what `StructureTemplate` reads:

    { DataVersion, size: [x,y,z], palette: [{Name, Properties}], blocks: [{pos, state}], entities: [] }

Air is dropped rather than written, so placing a tree never punches holes in the
terrain around it -- the structure's bounding box still comes from `size`.
"""

from . import nbtio
from .schem import parse_state

# 1.21.4. Structures carrying an older DataVersion still load, but Minecraft
# runs them through DataFixerUpper first; stamping the current one skips that.
DATA_VERSION_1_21_4 = 4189


def trim(voxels):
    """Drop empty margins. Returns (voxels, (w, h, l)) with coords rebased to 0."""
    if not voxels:
        return {}, (0, 0, 0)
    xs = [p[0] for p in voxels]
    ys = [p[1] for p in voxels]
    zs = [p[2] for p in voxels]
    x0, y0, z0 = min(xs), min(ys), min(zs)
    out = {(x - x0, y - y0, z - z0): v for (x, y, z), v in voxels.items()}
    return out, (max(xs) - x0 + 1, max(ys) - y0 + 1, max(zs) - z0 + 1)


def trunk_anchor(voxels):
    """
    Where the tree meets the ground: the centroid of the lowest occupied layer,
    rounded to a block. That layer is the trunk foot, so this is the column the
    placer should sit on the terrain surface.
    """
    if not voxels:
        return (0, 0, 0)
    y0 = min(p[1] for p in voxels)
    base = [p for p in voxels if p[1] == y0]
    cx = sum(p[0] for p in base) / len(base)
    cz = sum(p[2] for p in base) / len(base)
    return (int(round(cx)), y0, int(round(cz)))


def write(path, voxels, size, data_version=DATA_VERSION_1_21_4):
    """Write `voxels` ((x,y,z) -> blockstate string) as a structure file."""
    palette = []
    index_of = {}
    blocks = []

    for (x, y, z), state in sorted(voxels.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        idx = index_of.get(state)
        if idx is None:
            name, props = parse_state(state)
            entry = {"Name": name}
            if props:
                entry["Properties"] = dict(props)
            idx = len(palette)
            index_of[state] = idx
            palette.append(entry)
        blocks.append({
            "pos": nbtio.List([nbtio.Int(x), nbtio.Int(y), nbtio.Int(z)], nbtio.TAG_INT),
            "state": nbtio.Int(idx),
        })

    root = {
        "DataVersion": nbtio.Int(data_version),
        "size": nbtio.List([nbtio.Int(size[0]), nbtio.Int(size[1]), nbtio.Int(size[2])],
                           nbtio.TAG_INT),
        "palette": nbtio.List(palette, nbtio.TAG_COMPOUND),
        "blocks": nbtio.List(blocks, nbtio.TAG_COMPOUND),
        "entities": nbtio.List([], nbtio.TAG_COMPOUND),
    }
    nbtio.dump(path, root, name="")
    return len(palette), len(blocks)
