"""
Convert remapped Axiom blueprints into structure NBT, and merge the result with
the QF trees into one index.

    python -m fmgtrees.convert_axiom <blueprints/conquest> <out-dir> [--registry <ids.txt>]
                                     [--merge-with <qf-tree-dir>]

Run this on the *remapped* blueprints (the `conquest` folder written by
remap_axiom), not the vanilla originals. Like the QF converter this is a pure
container change -- blockstates are copied through verbatim -- so whatever the
blueprints look like in Axiom is what gets placed.

Both `structure_void` and `void_air` count as empty and are dropped, so a tree
never punches a hole in the terrain around it.

Layout written:

    <out>/<species>/<name>.nbt
    <out>/index.json        this pack only
    <merge>/../index.json   both packs, each entry tagged with `pack`
"""

import argparse
import collections
import glob
import json
import os
import sys

from . import structure
from .axiom import Blueprint
from .convert_qf import SIZES, split_name
from .schem import parse_state


def main(argv=None):
    ap = argparse.ArgumentParser(prog="fmgtrees.convert_axiom")
    ap.add_argument("source", help="the remapped Axiom blueprint directory")
    ap.add_argument("out", help="output directory for the .nbt tree files")
    ap.add_argument("--registry", help="a file of known Conquest block ids")
    ap.add_argument("--merge-with", help="the QF tree directory, to write a combined index")
    args = ap.parse_args(argv)

    known = None
    if args.registry:
        with open(args.registry, encoding="utf-8") as fh:
            bare = {line.strip() for line in fh if line.strip()}
        known = {"conquest:" + b for b in bare}

    os.makedirs(args.out, exist_ok=True)

    index = []
    block_use = collections.Counter()
    unknown = collections.Counter()
    failures = []

    species_dirs = sorted(d for d in os.listdir(args.source)
                          if os.path.isdir(os.path.join(args.source, d)))

    for species in species_dirs:
        for path in sorted(glob.glob(os.path.join(args.source, species, "*.bp"))):
            stem = os.path.basename(path)[:-len(".bp")]
            colour, size, ident = split_name(stem, species)
            try:
                bp = Blueprint(path)
                voxels, dims = structure.trim(bp.voxels())
            except Exception as ex:
                failures.append((stem, str(ex)))
                continue
            if not voxels:
                failures.append((stem, "empty after dropping void"))
                continue

            anchor = structure.trunk_anchor(voxels)
            for state in voxels.values():
                name = parse_state(state)[0]
                block_use[name] += 1
                if known is not None and name.startswith("conquest:") and name not in known:
                    unknown[name] += 1

            species_dir = os.path.join(args.out, species)
            os.makedirs(species_dir, exist_ok=True)
            palette_size, block_count = structure.write(
                os.path.join(species_dir, stem + ".nbt"), voxels, dims)

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

    index.sort(key=lambda e: (e["species"], e["colour"],
                              SIZES.index(e["size"]) if e["size"] in SIZES else 9,
                              e["variant"]))
    groups = collections.OrderedDict()
    for entry in index:
        groups.setdefault("%s/%s" % (entry["species"], entry["colour"]), []).append(entry["id"])

    with open(os.path.join(args.out, "index.json"), "w", encoding="utf-8") as fh:
        json.dump({"pack": "axiom", "count": len(index),
                   "groups": {k: len(v) for k, v in groups.items()},
                   "trees": index}, fh, indent=2)

    print("converted %d trees into %s" % (len(index), args.out))
    for key, ids in groups.items():
        hs = [e["height"] for e in index
              if "%s/%s" % (e["species"], e["colour"]) == key]
        fp = max(max(e["footprint"]) for e in index
                 if "%s/%s" % (e["species"], e["colour"]) == key)
        print("  %-34s %3d trees, height %2d-%2d, widest %d"
              % (key, len(ids), min(hs), max(hs), fp))
    print("distinct block ids used: %d" % len(block_use))

    if unknown:
        print("\nNOT in the supplied registry (%d ids):" % len(unknown))
        for name, n in unknown.most_common():
            print("  %-56s %d" % (name, n))

    oversized = [e for e in index if max(e["footprint"]) > 24 or e["height"] > 40]
    if oversized:
        print("\nunusually large (check these are single trees, not a scene):")
        for e in sorted(oversized, key=lambda e: -e["height"])[:12]:
            print("  %-36s %dx%d footprint, %d tall"
                  % (e["id"], e["footprint"][0], e["footprint"][1], e["height"]))

    if args.merge_with:
        qf_index = os.path.join(args.merge_with, "index.json")
        with open(qf_index, encoding="utf-8") as fh:
            qf = json.load(fh)
        qf_dir = os.path.basename(os.path.normpath(args.merge_with))
        ax_dir = os.path.basename(os.path.normpath(args.out))
        merged = []
        for e in qf["trees"]:
            e = dict(e, pack="qf", file="%s/%s" % (qf_dir, e["file"]))
            merged.append(e)
        for e in index:
            e = dict(e, pack="axiom", file="%s/%s" % (ax_dir, e["file"]))
            merged.append(e)
        merged_groups = collections.OrderedDict()
        for e in merged:
            merged_groups.setdefault(
                "%s/%s/%s" % (e["pack"], e["species"], e["colour"]), []).append(e["id"])
        root = os.path.dirname(os.path.normpath(args.out))
        with open(os.path.join(root, "index.json"), "w", encoding="utf-8") as fh:
            json.dump({"count": len(merged),
                       "packs": {"qf": len(qf["trees"]), "axiom": len(index)},
                       "groups": {k: len(v) for k, v in merged_groups.items()},
                       "trees": merged}, fh, indent=2)
        print("\ncombined index: %d trees (%d qf + %d axiom), %d groups -> %s"
              % (len(merged), len(qf["trees"]), len(index), len(merged_groups),
                 os.path.join(root, "index.json")))

    if failures:
        print("\n%d file(s) failed:" % len(failures))
        for name, why in failures[:20]:
            print("  %s: %s" % (name, why))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
