"""
Build an Axiom `.bp` blueprint from a voxel dict.

`axiom.Blueprint` can only rewrite a file that already exists -- it copies the
header, the thumbnail and every packed data array through untouched. That is the
right thing for remapping, but it cannot make a blueprint out of a schematic.
This module does that half: voxels in, a file Axiom will open out.

Container layout is documented in `axiom`. The parts that had to be measured
against real files rather than guessed:

  * X and Z are centred on the origin at `-(size // 2)`; Y is wherever the
    player happened to be standing, so we put the base of the model on y=0.
    (Checked across 40 of the pack's own blueprints: X and Z agree in 38 and
    39 of them, Y is scattered over a 30-block range.)
  * `ContainsAir` is 0 in every blueprint in the folder.
  * `Version` is the blueprint format, not the game: 1 and 2 both occur, so 2.
  * The thumbnail is a PNG Axiom renders itself when `LockedThumbnail` is 0.
    We still have to put *something* there, hence `PLACEHOLDER_PNG`.

The format gives no way to leave a cell out: every 16-cube section carries a
full 4096-entry array, so empty space has to be *some* block. The pack's own
blueprints use `minecraft:structure_void` (299 palette entries) or
`minecraft:void_air` (56). Both are real blockstates as far as an editor is
concerned, and on a big tree they are ~97% of the cells, which is what makes
detailing crawl. `FILL_AIR` is the default here instead: plain air is the state
every voxel editor special-cases as nothing, so the tools skip it. Placement
never sees this either way -- the mod reads the structure NBT, and `convert_bp`
drops all four empty states when it writes one.
"""

import gzip
import struct
import zlib

from . import nbtio
from .axiom import MAGIC, VOID

SECTION = 16
DATA_VERSION_1_20_1 = 3465

# What to put in the cells the model does not occupy. See the module docstring:
# air keeps Axiom's tools fast, structure_void is what Axiom itself writes.
FILL_AIR = "minecraft:air"
FILL_VOID = VOID


def _png(width=64, height=64):
    """A fully transparent PNG, built from stdlib zlib -- no image library."""
    def chunk(kind, payload):
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)   # 8-bit RGBA
    raw = b"".join(b"\x00" + b"\x00" * (width * 4) for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


PLACEHOLDER_PNG = _png()


def _bits_per_entry(palette_size):
    bits = max(1, (palette_size - 1).bit_length()) if palette_size > 1 else 1
    return max(4, bits)


def _pack(indices, palette_size):
    """Indices -> a 1.16+ chunk-section long array (entries never span a long)."""
    bits = _bits_per_entry(palette_size)
    per_long = 64 // bits
    out = []
    for start in range(0, len(indices), per_long):
        word = 0
        for i, idx in enumerate(indices[start:start + per_long]):
            word |= (idx & ((1 << bits) - 1)) << (i * bits)
        # NBT longs are signed; the packing is unsigned.
        out.append(word - (1 << 64) if word >= (1 << 63) else word)
    return out


def _split_state(state):
    """`ns:name[a=b,c=d]` -> a palette compound."""
    if "[" not in state:
        return {"Name": state}
    name, _, rest = state.partition("[")
    props = {}
    for part in rest.rstrip("]").split(","):
        if not part:
            continue
        k, _, v = part.partition("=")
        props[k.strip()] = v.strip()
    return {"Name": name, "Properties": props} if props else {"Name": name}


def centre(voxels):
    """
    Rebase voxels the way Axiom does: X and Z centred, base of the model on y=0.
    Returns the new dict plus the (width, height, length) it occupies.
    """
    if not voxels:
        return {}, (0, 0, 0)
    xs = [p[0] for p in voxels]
    ys = [p[1] for p in voxels]
    zs = [p[2] for p in voxels]
    w = max(xs) - min(xs) + 1
    h = max(ys) - min(ys) + 1
    ln = max(zs) - min(zs) + 1
    dx = -(w // 2) - min(xs)
    dy = -min(ys)
    dz = -(ln // 2) - min(zs)
    moved = {(x + dx, y + dy, z + dz): s for (x, y, z), s in voxels.items()}
    return moved, (w, h, ln)


def write(path, voxels, name, author="fmgtrees",
          data_version=DATA_VERSION_1_20_1, thumbnail=None, recentre=True,
          fill=FILL_AIR):
    """
    Write `voxels` ((x, y, z) -> blockstate string) as an Axiom blueprint.

    Anything not in `voxels` becomes `fill`. Pass `FILL_VOID` for
    structure_void if a blueprint needs to paste over terrain without
    clearing around itself; `FILL_AIR` (the default) is the fast one to edit.
    """
    if recentre:
        voxels, _ = centre(voxels)

    sections = {}
    for (x, y, z), state in voxels.items():
        key = (x >> 4, y >> 4, z >> 4)
        sections.setdefault(key, {})[(x & 15, y & 15, z & 15)] = state

    regions = nbtio.List(element_tag=nbtio.TAG_COMPOUND)
    for (sx, sy, sz) in sorted(sections):
        cells = sections[(sx, sy, sz)]
        # Index 0 is the filler so an untouched cell needs no special case.
        order = [fill]
        seen = {fill: 0}
        indices = [0] * (SECTION ** 3)
        for (x, y, z), state in cells.items():
            idx = seen.get(state)
            if idx is None:
                idx = seen[state] = len(order)
                order.append(state)
            indices[y * 256 + z * 16 + x] = idx

        palette = nbtio.List([_split_state(s) for s in order],
                             element_tag=nbtio.TAG_COMPOUND)
        regions.append({
            "X": nbtio.Int(sx), "Y": nbtio.Int(sy), "Z": nbtio.Int(sz),
            "BlockStates": {
                "palette": palette,
                "data": nbtio.Array(_pack(indices, len(order)),
                                    nbtio.TAG_LONG_ARRAY),
            },
        })

    blocks = {
        "DataVersion": nbtio.Int(data_version),
        "BlockRegion": regions,
        "BlockEntities": nbtio.List(element_tag=nbtio.TAG_END),
        "Entities": nbtio.List(element_tag=nbtio.TAG_END),
    }
    header = {
        "Name": name,
        "Author": author,
        "BlockCount": nbtio.Int(len(voxels)),
        "Tags": nbtio.List(element_tag=nbtio.TAG_END),
        "Version": nbtio.Int(2),
        "ContainsAir": nbtio.Int(0, nbtio.TAG_BYTE),
        "LockedThumbnail": nbtio.Int(0, nbtio.TAG_BYTE),
        "ThumbnailPitch": nbtio.Float(30.0, nbtio.TAG_FLOAT),
        "ThumbnailYaw": nbtio.Float(135.0, nbtio.TAG_FLOAT),
    }

    header_bytes = nbtio.dumps(header, "")
    blob = gzip.compress(nbtio.dumps(blocks, ""), mtime=0)
    thumb = PLACEHOLDER_PNG if thumbnail is None else thumbnail

    with open(path, "wb") as fh:
        fh.write(struct.pack(">I", MAGIC))
        fh.write(struct.pack(">I", len(header_bytes)))
        fh.write(header_bytes)
        fh.write(struct.pack(">I", len(thumb)))
        fh.write(thumb)
        fh.write(struct.pack(">I", len(blob)))
        fh.write(blob)
