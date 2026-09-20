"""
Sponge schematic (.schem) reader -> a plain voxel dict.

WorldEdit writes Sponge v2/v3: a gzipped NBT root with a `Palette` mapping
blockstate strings to indices and a `BlockData` byte array of varint indices in
Y -> Z -> X order. v3 nests those under `Blocks`.

Blockstate strings keep their properties verbatim
(`conquest:diagonal_oak_branch_45[facing=north,toggle=1,type=bottom]`), so
anything already built out of Conquest blocks survives the round trip exactly --
including every branch facing. No geometry is interpreted here.
"""

from . import nbtio


def parse_state(s):
    """`ns:name[a=b,c=d]` -> ("ns:name", {"a": "b", "c": "d"})."""
    if "[" not in s:
        return s, {}
    name, _, rest = s.partition("[")
    rest = rest.rstrip("]")
    props = {}
    for part in rest.split(","):
        if not part:
            continue
        k, _, v = part.partition("=")
        props[k.strip()] = v.strip()
    return name, props


def _varints(data):
    """Decode a byte array of unsigned LEB128 varints."""
    out = []
    value = 0
    shift = 0
    for b in data:
        b &= 0xFF
        value |= (b & 0x7F) << shift
        if b & 0x80:
            shift += 7
        else:
            out.append(value)
            value = 0
            shift = 0
    return out


class Schematic:
    def __init__(self, width, height, length, voxels, data_version, offset):
        self.width = width
        self.height = height
        self.length = length
        self.voxels = voxels           # (x, y, z) -> blockstate string
        self.data_version = data_version
        self.offset = offset

    def __repr__(self):
        return "Schematic(%dx%dx%d, %d blocks)" % (
            self.width, self.height, self.length, len(self.voxels))


def read(path, drop=("minecraft:air", "minecraft:cave_air", "minecraft:void_air")):
    _, root = nbtio.load(path)

    # v3 moves the block container into a `Blocks` compound.
    container = root.get("Blocks") if "Blocks" in root else root
    palette = container.get("Palette") or root.get("Palette")
    block_data = container.get("Data") if "Data" in container else root.get("BlockData")
    if palette is None or block_data is None:
        raise ValueError("%s: no palette/block data (not a Sponge schematic?)" % path)

    width = int(root["Width"])
    height = int(root["Height"])
    length = int(root["Length"])

    by_index = {}
    for state, index in palette.items():
        by_index[int(index)] = state

    indices = _varints(block_data)
    expected = width * height * length
    if len(indices) != expected:
        raise ValueError("%s: expected %d blocks, decoded %d" % (path, expected, len(indices)))

    dropped = set(drop)
    voxels = {}
    i = 0
    for y in range(height):
        for z in range(length):
            for x in range(width):
                state = by_index.get(indices[i])
                i += 1
                if state is None:
                    continue
                if parse_state(state)[0] in dropped:
                    continue
                voxels[(x, y, z)] = state

    return Schematic(width, height, length, voxels,
                     int(root.get("DataVersion", 0)),
                     list(root.get("Offset", (0, 0, 0))))
