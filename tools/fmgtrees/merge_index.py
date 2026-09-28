"""
Rebuild the combined tree index from every per-pack index under a tree root.

    python -m fmgtrees.merge_index <trees-dir>

`convert_axiom --merge-with` could only ever join two packs, which was fine
while there were two. With the African pack there are three, and the next one
should not need another flag, so this just scans for `*/index.json` and folds
them all together.

Each entry keeps its own fields and gains `pack` plus a `file` rewritten
relative to the tree root, so a group key is `<pack>/<species>/<colour>` and
the biome files can name a tree without knowing which folder it lives in.
"""

import argparse
import collections
import json
import os
import sys

# Folder name -> the short pack name used in group keys. A pack index that
# names itself wins; this is only for the QF one, written before packs existed.
FOLDER_PACKS = {"conquest": "qf", "conquest_axiom": "axiom", "conquest_afr": "afr"}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", help="the trees directory holding the per-pack folders")
    args = ap.parse_args(argv)

    merged = []
    packs = collections.OrderedDict()
    for name in sorted(os.listdir(args.root)):
        path = os.path.join(args.root, name, "index.json")
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as fh:
            index = json.load(fh)
        pack = index.get("pack") or FOLDER_PACKS.get(name, name)
        for entry in index["trees"]:
            entry = dict(entry)
            entry["pack"] = pack
            entry["file"] = "%s/%s" % (name, entry["file"])
            merged.append(entry)
        packs[pack] = len(index["trees"])

    groups = collections.Counter(
        "%s/%s/%s" % (e["pack"], e["species"], e.get("colour", "default"))
        for e in merged)

    out = os.path.join(args.root, "index.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump({"count": len(merged),
                   "packs": dict(packs),
                   "groups": {k: groups[k] for k in sorted(groups)},
                   "trees": sorted(merged, key=lambda e: (e["pack"], e["id"]))},
                  fh, indent=2)

    print("%s: %d trees in %d groups" % (out, len(merged), len(groups)))
    for pack, count in packs.items():
        print("   %-8s %4d" % (pack, count))
    return 0


if __name__ == "__main__":
    sys.exit(main())
