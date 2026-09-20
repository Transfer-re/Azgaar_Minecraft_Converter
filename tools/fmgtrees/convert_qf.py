"""
Convert the QF Tree Repository (WorldEdit .schem) into structure NBT the mod
can place, straight out of the downloaded zip.

    python -m fmgtrees.convert_qf "<zip or folder>" <out-dir> [--registry <ids.txt>]

The QF trees are already built from Conquest blocks, so this is a pure format
change: every blockstate string -- including each `diagonal_*_branch_45[facing=...]`
-- is copied through verbatim. Nothing about branch geometry is inferred.

Layout written:

    <out>/<species>/<species>_<colour>_<size>_<id>.nbt
    <out>/index.json      every tree with species/colour/size/footprint/anchor

`--registry` checks every block id against a dump of the Conquest registry and
lists anything unknown instead of failing, so a version mismatch shows up here
rather than as missing blocks in a world.
"""

import argparse
import collections
import json
import os
import re
import sys
import zipfile

from . import structure
from .schem import parse_state, read as read_schem

SIZES = ("xs", "s", "m", "l", "xl")


def slug(s):
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")


def split_name(stem, species):
    """
    `<species>[_<colour>][_<size>]_<id>` -> (colour, size, id).

    Both packs are parsed with this. QF always has all four parts
    (`english_oak_bright_autumnal_xl_01`); the Axiom names often drop the colour
    (`western_larch_l_01`) or the size (`survival_stump_oak_01`), so each piece
    is taken only when it is actually there rather than by position.
    """
    rest = stem[len(species):].strip("_") if stem.startswith(species) else stem
    parts = [p for p in rest.split("_") if p]

    ident = "01"
    if parts and parts[-1].isdigit():
        ident = parts[-1]
        parts = parts[:-1]

    size = "m"
    if parts and parts[-1] in SIZES:
        size = parts[-1]
        parts = parts[:-1]

    return "_".join(parts) or "default", size, ident


def iter_schems(source):
    """Yield (species_folder, filename, open_path_or_bytes) from a zip or a folder."""
    if os.path.isdir(source):
        for root, _dirs, files in os.walk(source):
            for f in sorted(files):
                if f.endswith(".schem"):
                    yield os.path.basename(root), f, os.path.join(root, f)
        return
    with zipfile.ZipFile(source) as zf:
        tmp = None
        for info in sorted(zf.infolist(), key=lambda i: i.filename):
            if not info.filename.endswith(".schem"):
                continue
            parts = info.filename.split("/")
            folder = parts[-2] if len(parts) >= 2 else ""
            yield folder, parts[-1], ("zip", zf, info)
        del tmp


def _load(entry):
    if isinstance(entry, tuple) and entry[0] == "zip":
        _, zf, info = entry
        import io
        import tempfile
        with tempfile.NamedTemporaryFile(suffix=".schem", delete=False) as fh:
            fh.write(zf.read(info))
            path = fh.name
        try:
            return read_schem(path)
        finally:
            os.unlink(path)
    return read_schem(entry)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="fmgtrees.convert_qf")
    ap.add_argument("source", help="the QF zip, or a folder of .schem files")
    ap.add_argument("out", help="output directory for the .nbt tree files")
    ap.add_argument("--registry", help="a file of known block ids, one per line")
    args = ap.parse_args(argv)

    known = None
    if args.registry:
        with open(args.registry, encoding="utf-8") as fh:
            known = {line.strip() for line in fh if line.strip()}
            known |= {"conquest:" + i for i in list(known) if ":" not in i}

    os.makedirs(args.out, exist_ok=True)

    index = []
    block_use = collections.Counter()
    unknown = collections.Counter()
    failures = []

    for folder, filename, entry in iter_schems(args.source):
        species = slug(folder) or "unsorted"
        stem = filename[:-len(".schem")]
        colour, size, ident = split_name(stem, species)

        try:
            schem = _load(entry)
        except Exception as ex:                      # keep going; report at the end
            failures.append((filename, str(ex)))
            continue

        voxels, dims = structure.trim(schem.voxels)
        if not voxels:
            failures.append((filename, "empty after dropping air"))
            continue
        anchor = structure.trunk_anchor(voxels)

        for state in voxels.values():
            name = parse_state(state)[0]
            block_use[name] += 1
            if known is not None and name not in known and not name.startswith("minecraft:"):
                unknown[name] += 1

        species_dir = os.path.join(args.out, species)
        os.makedirs(species_dir, exist_ok=True)
        out_path = os.path.join(species_dir, stem + ".nbt")
        palette_size, block_count = structure.write(out_path, voxels, dims)

        index.append({
            "id": stem,
            "species": species,
            "colour": colour,
            "size": size,
            "variant": ident,
            "file": "%s/%s.nbt" % (species, stem),
            "footprint": [dims[0], dims[2]],
            "height": dims[1],
            "anchor": list(anchor),
            "blocks": block_count,
            "palette": palette_size,
        })

    index.sort(key=lambda e: (e["species"], e["colour"], SIZES.index(e["size"]) if e["size"] in SIZES else 9, e["variant"]))
    groups = collections.OrderedDict()
    for entry in index:
        groups.setdefault("%s/%s" % (entry["species"], entry["colour"]), []).append(entry["id"])

    with open(os.path.join(args.out, "index.json"), "w", encoding="utf-8") as fh:
        json.dump({
            "source": os.path.basename(args.source),
            "count": len(index),
            "groups": {k: len(v) for k, v in groups.items()},
            "trees": index,
        }, fh, indent=2)

    print("converted %d trees into %s" % (len(index), args.out))
    for key, ids in groups.items():
        heights = [e["height"] for e in index if "%s/%s" % (e["species"], e["colour"]) == key]
        print("  %-38s %3d trees, height %d-%d" % (key, len(ids), min(heights), max(heights)))
    print("distinct block ids used: %d" % len(block_use))
    if unknown:
        print("\nNOT in the supplied registry (%d ids):" % len(unknown))
        for name, n in unknown.most_common():
            print("  %-60s %d" % (name, n))
    if failures:
        print("\n%d file(s) failed:" % len(failures))
        for name, why in failures[:20]:
            print("  %s: %s" % (name, why))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
