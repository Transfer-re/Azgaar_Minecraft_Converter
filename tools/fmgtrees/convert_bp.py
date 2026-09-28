"""
Convert Axiom `.bp` blueprints into structure NBT, plus an index.

    python -m fmgtrees.convert_bp <blueprint-dir> <out-dir> --pack <name>
                                  [--registry <ids.txt>] [--only sp,sp]

This is the second half of the loop. `convert_afr` / `convert_packs` write
blueprints straight out of the schematics so there is something to open; once
those have been opened in Axiom, fixed by hand and resaved, *this* is what turns
the finished article into the NBT the placement feature reads. Run it against
the blueprint folder rather than the schematics and the hand edits come along.

**This is where the empty blocks are dealt with.** A blueprint has to fill every
cell of every 16-cube section with something -- there is no way to leave a cell
out -- so `bpwrite` fills with plain air, which is the state Axiom's tools skip.
Structure NBT has no such constraint: a position that is not listed is simply
not written, so the tree cannot punch a hole in the terrain it is placed on.
All four empty states (`air`, `cave_air`, `void_air`, `structure_void`) are
dropped here, so it does not matter which one the blueprint used or which one
Axiom wrote when it resaved.

Sizes come from height, using the same bands as `convert_afr`, so a tree keeps
its size class whether it was indexed from the schematic or from the blueprint.
"""

import argparse
import collections
import json
import os
import re
import sys

from . import structure
from .axiom import Blueprint, EMPTY

SIZE_BANDS = ((6, "xs"), (12, "s"), (19, "m"), (26, "l"))
SIZE_XL = "xl"


def size_for(height):
    for limit, name in SIZE_BANDS:
        if height <= limit:
            return name
    return SIZE_XL


def split_id(stem):
    """`vachellia_tortilis_m_03` -> ("vachellia_tortilis", "m", "03")."""
    m = re.match(r"^(.*?)_(xs|s|m|l|xl)_(\d+)$", stem)
    if m:
        return m.group(1), m.group(2), m.group(3)
    m = re.match(r"^(.*?)[ _]*\(?(\d+)\)?$", stem)
    if m and m.group(1):
        return m.group(1), None, "%02d" % int(m.group(2))
    return stem, None, "01"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", help="directory of .bp files (species subfolders)")
    ap.add_argument("out", help="where to write the .nbt tree files")
    ap.add_argument("--pack", required=True, help="pack name for the index")
    ap.add_argument("--registry", help="file of known Conquest block ids")
    ap.add_argument("--only", help="comma-separated species folders to convert")
    args = ap.parse_args(argv)

    registry = None
    if args.registry and os.path.exists(args.registry):
        with open(args.registry, encoding="utf-8") as fh:
            registry = set(fh.read().split())

    only = {s.strip() for s in args.only.split(",")} if args.only else None

    files = []
    for dirpath, _dirs, names in os.walk(args.src):
        for name in sorted(names):
            if name.endswith(".bp"):
                files.append(os.path.join(dirpath, name))
    if not files:
        print("no .bp files under %s" % args.src, file=sys.stderr)
        return 1

    entries = []
    groups = collections.Counter()
    unknown = collections.Counter()
    dropped_empty = 0

    for path in sorted(files):
        rel = os.path.relpath(path, args.src)
        folder = os.path.dirname(rel).replace("\\", "/").split("/")[0] or ""
        if only and folder not in only:
            continue

        stem = os.path.basename(path)[:-len(".bp")]
        binomial, size, variant = split_id(stem)
        # The folder is the group (a genus), but the id has to stay the file
        # stem: the regional packs put several species in one genus folder, and
        # rebuilding the id from the folder collapses `quercus_alba_m_01` and
        # `quercus_rubra_m_01` onto the same name, so one silently overwrites
        # the other. That cost 53 trees before it was caught.
        species = folder or binomial
        ident = stem

        voxels = Blueprint(path).voxels()
        # The blueprint reader already skips the four empty states; count what
        # a full section would have held so the report says what was saved.
        before = len(voxels)
        voxels = {p: s for p, s in voxels.items()
                  if s.split("[", 1)[0] not in EMPTY}
        dropped_empty += before - len(voxels)
        if not voxels:
            continue

        if registry is not None:
            for state in set(voxels.values()):
                block = state.split("[", 1)[0]
                if block.startswith("conquest:") and block not in registry:
                    unknown[block] += 1

        trimmed, dims = structure.trim(voxels)
        height = dims[1]
        size = size or size_for(height)

        out_dir = os.path.join(args.out, species)
        os.makedirs(out_dir, exist_ok=True)
        structure.write(os.path.join(out_dir, ident + ".nbt"), trimmed, dims)

        groups["%s/%s/default" % (args.pack, species)] += 1
        entries.append({
            "id": ident,
            "species": species,
            "colour": "default",
            "size": size,
            "variant": variant,
            "file": "%s/%s.nbt" % (species, ident),
            "footprint": [dims[0], dims[2]],
            "height": height,
            "anchor": list(structure.trunk_anchor(trimmed)),
            "blocks": len(trimmed),
            "palette": len(set(trimmed.values())),
            "binomial": binomial,
        })

    index = {
        "pack": args.pack,
        "source": "axiom blueprints: %s" % args.src,
        "count": len(entries),
        "groups": {g: c for g, c in sorted(groups.items())},
        "trees": sorted(entries, key=lambda e: e["id"]),
    }
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "index.json"), "w", encoding="utf-8") as fh:
        json.dump(index, fh, indent=1)

    print("blueprints read: %d" % len(files))
    print("trees written:   %d in %d groups" % (len(entries), len(groups)))
    if unknown:
        print("NOT IN REGISTRY:")
        for name, count in unknown.most_common():
            print("   %-44s %d" % (name, count))
    return 0


if __name__ == "__main__":
    sys.exit(main())
