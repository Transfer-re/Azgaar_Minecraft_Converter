"""
Axiom `.bp` blueprint reader/writer.

Container layout (big-endian):

    u32   magic 0x0AE5BB36
    u32   header length
    ...   header NBT, uncompressed  (Name, Author, BlockCount, Tags, thumbnail angles)
    u32   thumbnail length
    ...   PNG thumbnail
    u32   block-data length
    ...   gzipped NBT: { DataVersion, BlockRegion: [...], BlockEntities, Entities }

Each `BlockRegion` entry is a 16x16x16 section at section coords X/Y/Z holding a
vanilla-style `BlockStates { palette, data }`: indices packed into a long array,
`max(4, bits(len(palette)-1))` bits each, never spanning a long. Empty space is
`minecraft:structure_void`, not air.

Writing takes the deliberately conservative route: the header, the thumbnail and
every packed `data` array are copied through byte for byte, and only the palette
*entries* are rewritten. Remapping can therefore never corrupt the geometry --
the worst it can do is name a block wrong. Two palette entries mapping onto the
same block is fine; duplicate entries are legal.
"""

import gzip
import struct

from . import nbtio

MAGIC = 0x0AE5BB36
# Both show up as "nothing here": structure_void means leave the world alone,
# void_air means write air. Some of the Axiom trees use one, some the other.
VOID = "minecraft:structure_void"
EMPTY = frozenset((VOID, "minecraft:void_air", "minecraft:air", "minecraft:cave_air"))


def _bits_per_entry(palette_size):
    bits = max(1, (palette_size - 1).bit_length()) if palette_size > 1 else 1
    return max(4, bits)


def _unpack(data, palette_size, count=4096):
    """Unpack a 1.16+ chunk-section long array into palette indices."""
    if not data:
        return [0] * count
    bits = _bits_per_entry(palette_size)
    per_long = 64 // bits
    mask = (1 << bits) - 1
    out = []
    for word in data:
        w = word & 0xFFFFFFFFFFFFFFFF
        for i in range(per_long):
            if len(out) >= count:
                return out
            out.append((w >> (i * bits)) & mask)
    while len(out) < count:
        out.append(0)
    return out


def state_string(entry):
    """A palette compound -> `ns:name[a=b,...]` with properties in sorted order."""
    name = entry["Name"]
    props = entry.get("Properties") or {}
    if not props:
        return name
    return name + "[" + ",".join("%s=%s" % (k, props[k]) for k in sorted(props)) + "]"


class Blueprint:
    def __init__(self, path):
        self.path = path
        raw = open(path, "rb").read()

        magic, = struct.unpack_from(">I", raw, 0)
        if magic != MAGIC:
            raise ValueError("%s: not an Axiom blueprint (magic %08x)" % (path, magic))

        off = 4
        hlen, = struct.unpack_from(">I", raw, off)
        off += 4
        self.header_bytes = raw[off:off + hlen]
        _, self.header = nbtio.loads(self.header_bytes)
        off += hlen

        tlen, = struct.unpack_from(">I", raw, off)
        off += 4
        self.thumbnail = raw[off:off + tlen]
        off += tlen

        blen, = struct.unpack_from(">I", raw, off)
        off += 4
        self.block_blob = raw[off:off + blen]
        off += blen
        self.trailer = raw[off:]

        _, self.blocks = nbtio.loads(gzip.decompress(self.block_blob))

    # ---- inspection ------------------------------------------------------

    @property
    def name(self):
        return self.header.get("Name", "")

    def regions(self):
        return self.blocks.get("BlockRegion", [])

    def palette_entries(self):
        """Yield (region, index, entry) for every palette entry in the file."""
        for region in self.regions():
            palette = region["BlockStates"]["palette"]
            for i, entry in enumerate(palette):
                yield region, i, entry

    def block_counts(self):
        """blockstate string -> how many voxels use it (structure_void excluded)."""
        counts = {}
        for region in self.regions():
            bs = region["BlockStates"]
            palette = bs["palette"]
            states = [state_string(e) for e in palette]
            for idx in _unpack(bs.get("data", []), len(palette)):
                if idx >= len(states):
                    continue
                s = states[idx]
                if s in EMPTY:
                    continue
                counts[s] = counts.get(s, 0) + 1
        return counts

    def voxels(self):
        """(x, y, z) -> blockstate string, in blueprint-local coordinates."""
        out = {}
        for region in self.regions():
            bs = region["BlockStates"]
            palette = bs["palette"]
            states = [state_string(e) for e in palette]
            bx = int(region.get("X", 0)) * 16
            by = int(region.get("Y", 0)) * 16
            bz = int(region.get("Z", 0)) * 16
            indices = _unpack(bs.get("data", []), len(palette))
            for i, idx in enumerate(indices):
                if idx >= len(states):
                    continue
                s = states[idx]
                if s in EMPTY:
                    continue
                y, rem = divmod(i, 256)
                z, x = divmod(rem, 16)
                out[(bx + x, by + y, bz + z)] = s
        return out

    # ---- rewriting -------------------------------------------------------

    def remap(self, fn):
        """
        Rewrite palette entries in place. `fn(name, props)` returns a new
        `(name, props)` pair, or None to leave the entry alone. Packed data is
        never touched. Returns the number of entries changed.
        """
        changed = 0
        for _region, _i, entry in self.palette_entries():
            name = entry["Name"]
            props = dict(entry.get("Properties") or {})
            result = fn(name, props)
            if result is None:
                continue
            new_name, new_props = result
            if new_name == name and new_props == props:
                continue
            entry["Name"] = new_name
            if new_props:
                entry["Properties"] = {k: str(v) for k, v in new_props.items()}
            else:
                entry.pop("Properties", None)
            changed += 1
        return changed

    def write(self, path, name=None):
        header = dict(self.header)
        if name is not None:
            header["Name"] = name
        header_bytes = nbtio.dumps(header, "")

        blob = gzip.compress(nbtio.dumps(self.blocks, ""), mtime=0)

        with open(path, "wb") as fh:
            fh.write(struct.pack(">I", MAGIC))
            fh.write(struct.pack(">I", len(header_bytes)))
            fh.write(header_bytes)
            fh.write(struct.pack(">I", len(self.thumbnail)))
            fh.write(self.thumbnail)
            fh.write(struct.pack(">I", len(blob)))
            fh.write(blob)
            fh.write(self.trailer)
