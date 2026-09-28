"""
Convert the paleozoey regional tree packs to Conquest blueprints (and NBT).

    python -m fmgtrees.convert_packs <tree-packs-root> --packs ena,asn,ind,sam,fl
                                     --bp-out <blueprints-dir>
                                     [--registry <ids.txt>] [--jar <CR.jar>]
                                     [--report <md>] [--dry-run]

**This step writes blueprints only.** NBT comes from `convert_bp`, run against
the blueprint folder afterwards, so the trees the placement feature gets are the
ones that went through Axiom -- hand edits and all -- rather than a parallel
copy made from the schematics that would silently go stale the moment anything
was fixed.

That split is also where the empty cells are handled. A blueprint has to fill
every cell of every 16-cube section with something, and on a big tree that is
~97% of the bounding box; filling it with `structure_void` makes Axiom's tools
walk all of it. These are filled with plain air instead, which every voxel
editor skips, and `convert_bp` drops all four empty states when it writes the
NBT, so nothing reaches placement either way.

Blueprints land in `<blueprints-dir>/conquest_<pack>/<genus>/`, two levels deep
like the folders Axiom already has.

Grouping is by genus, not species: `Quercus_alba` and `Quercus_rubra` land in
one `ena/quercus/default` group. 174 genera is already a lot of knobs for a
biome file, and the genus is what reads as one kind of tree.
"""

import argparse
import collections
import json
import os
import re
import sys

from . import bpwrite, remap_packs, schem, structure, thumbnail
from .convert_afr import size_for

PACKS = {
    "ena": "eastern North America",
    "asn": "temperate and montane Asia",
    "ind": "South and Southeast Asia",
    "sam": "South America",
    "fl":  "Florida, Caribbean and coastal wetland",
    "aus": "Australia and New Zealand",
    "afr": "Africa and Madagascar",
}


def split_name(stem):
    """`Khaya_ivoriensis (3)` / `Quercus_alba7` -> ("Quercus_alba", "07")."""
    m = re.match(r"^(.*?)[ _]*\(?(\d+)\)?$", stem)
    if m and m.group(1):
        return m.group(1).rstrip("_ "), "%02d" % int(m.group(2))
    return stem, "01"


def trunk_centre(voxels):
    """
    (x, z) of the trunk, as the mean of the lowest occupied layer.

    Only the radial-facing palms need it, to work out which way a frond points.
    """
    if not voxels:
        return (0, 0)
    floor = min(p[1] for p in voxels)
    base = [p for p in voxels if p[1] <= floor + 1]
    return (sum(p[0] for p in base) / len(base),
            sum(p[2] for p in base) / len(base))


def convert_one(path, genus, unmapped):
    """Read one schematic and return its remapped voxels."""
    source = schem.read(path)

    woods = collections.Counter()
    for state in source.voxels.values():
        name = schem.parse_state(state)[0]
        family, _form = remap_packs.wood_family(name)
        if family and family != "roots":
            woods[family] += 1
    roles = remap_packs.rank_woods(woods)

    mapper = remap_packs.make_mapper(genus, roles, trunk_centre(source.voxels))

    out = {}
    cache = {}
    for pos, state in source.voxels.items():
        name, props = schem.parse_state(state)
        # Radial facing depends on position, so those cannot be cached.
        key = None if name in ("minecraft:cherry_leaves",) or genus.foliage in \
            remap_packs.RADIAL_FACING else state
        if key is not None and key in cache:
            out[pos] = cache[key]
            continue
        result = mapper(name, props, pos)
        if result is None:
            unmapped[name] += 1
            new = state
        else:
            block, newprops = result
            new = block
            if newprops:
                new += "[" + ",".join("%s=%s" % (k, newprops[k])
                                      for k in sorted(newprops)) + "]"
        if key is not None:
            cache[key] = new
        out[pos] = new
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", help="the tree-packs directory")
    ap.add_argument("--packs", required=True, help="comma-separated pack folders")
    ap.add_argument("--bp-out", help="the Axiom blueprints directory")
    ap.add_argument("--registry", help="file of known Conquest block ids")
    ap.add_argument("--jar", help="Conquest jar, for thumbnail colours")
    ap.add_argument("--report", help="write a markdown report here")
    ap.add_argument("--data-version", type=int, default=bpwrite.DATA_VERSION_1_20_1)
    ap.add_argument("--fill", choices=("air", "void"), default="air",
                    help="what fills the empty cells of a blueprint "
                         "(default air: the state Axiom's tools skip)")
    ap.add_argument("--no-thumbnails", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)

    registry = None
    if args.registry and os.path.exists(args.registry):
        with open(args.registry, encoding="utf-8") as fh:
            registry = set(fh.read().split())

    pal = None if args.no_thumbnails else thumbnail.Palette(args.jar)
    fill = bpwrite.FILL_AIR if args.fill == "air" else bpwrite.FILL_VOID

    unmapped = collections.Counter()
    unknown = collections.Counter()
    no_genus = collections.Counter()
    per_pack = collections.OrderedDict()
    used_blocks = collections.Counter()

    for pack in [p.strip() for p in args.packs.split(",")]:
        src = os.path.join(args.root, pack)
        files = sorted(f for f in os.listdir(src) if f.endswith(".schem"))
        groups = collections.Counter()

        for fname in files:
            stem = fname[:-len(".schem")]
            species, variant = split_name(stem)
            genus = remap_packs.lookup(species)
            if genus is None:
                no_genus[species.split("_")[0]] += 1
                continue

            voxels = convert_one(os.path.join(src, fname), genus, unmapped)
            for state in voxels.values():
                used_blocks[state.split("[", 1)[0]] += 1
            if registry is not None:
                for state in set(voxels.values()):
                    block = state.split("[", 1)[0]
                    if block.startswith("conquest:") and block not in registry:
                        unknown[block] += 1

            trimmed, dims = structure.trim(voxels)
            height = dims[1]
            size = size_for(height)
            key = species.split("_")[0].lower()
            ident = "%s_%s_%s" % (species.lower(), size, variant)
            groups["%s/%s/default" % (pack, key)] += 1

            if args.dry_run:
                continue

            if args.bp_out:
                out_dir = os.path.join(args.bp_out, "conquest_" + pack, key)
                os.makedirs(out_dir, exist_ok=True)
                thumb = thumbnail.render(voxels, pal) if pal is not None else None
                bpwrite.write(os.path.join(out_dir, ident + ".bp"), voxels,
                              name=stem, data_version=args.data_version,
                              thumbnail=thumb, fill=fill)

        per_pack[pack] = (len(files), len(groups))
        print("%-5s %4d schematics -> %3d genera" % (pack, len(files), len(groups)))

    total = sum(v[0] for v in per_pack.values())
    print("total: %d schematics" % total)
    cq = sum(c for b, c in used_blocks.items() if b.startswith("conquest:"))
    vn = sum(c for b, c in used_blocks.items() if b.startswith("minecraft:"))
    print("blocks: %d conquest / %d vanilla (%.1f%% conquest)"
          % (cq, vn, 100.0 * cq / max(1, cq + vn)))
    if no_genus:
        print("no genus entry: %s" % ", ".join(sorted(no_genus)))
    if unmapped:
        print("left as they were:")
        for name, count in unmapped.most_common(20):
            print("   %-44s %d" % (name, count))
    if unknown:
        print("NOT IN REGISTRY:")
        for name, count in unknown.most_common():
            print("   %-44s %d" % (name, count))

    if args.report:
        write_report(args.report, per_pack, used_blocks, unmapped, unknown)
        print("report: %s" % args.report)
    return 0


def write_report(path, per_pack, used_blocks, unmapped, unknown):
    lines = ["# Regional tree packs -> Conquest", ""]
    lines.append("Source: paleozoey (https://www.planetminecraft.com/member/paleozoey/), "
                 "bundled with Arnis. Not redistributed here.")
    lines.append("")
    lines.append("| pack | region | schematics | genera |")
    lines.append("|---|---|---|---|")
    for pack, (nfiles, ngroups) in per_pack.items():
        lines.append("| `%s` | %s | %d | %d |" % (pack, PACKS.get(pack, pack),
                                                  nfiles, ngroups))
    lines.append("")

    lines.append("## Genus map")
    lines.append("")
    lines.append("Keyed on genus, because the source blocks carry no meaning: "
                 "`Acer` is oak leaves on birch wood, `Salix` is birch leaves on "
                 "spruce wood. The filename is the only reliable signal.")
    lines.append("")
    lines.append("| genus | foliage | blossom | trunk | accent | note |")
    lines.append("|---|---|---|---|---|---|")
    for name in sorted(remap_packs.GENERA):
        g = remap_packs.GENERA[name]
        lines.append("| `%s` | `%s` | %s | %s | %s | %s |" % (
            name, g.foliage.split(":")[-1],
            "`%s`" % g.flower.split(":")[-1] if g.flower != g.foliage else "",
            g.trunk, g.accent if g.accent != g.trunk else "", g.note))
    lines.append("")

    if remap_packs.SPECIES:
        lines.append("## Species overrides")
        lines.append("")
        lines.append("| species | foliage | trunk | why |")
        lines.append("|---|---|---|---|")
        for name in sorted(remap_packs.SPECIES):
            g = remap_packs.SPECIES[name]
            lines.append("| `%s` | `%s` | %s | %s |"
                         % (name, g.foliage.split(":")[-1], g.trunk, g.note))
        lines.append("")

    lines.append("## Blocks used")
    lines.append("")
    for block, count in used_blocks.most_common():
        lines.append("- `%s` x%d" % (block, count))
    lines.append("")

    if unmapped:
        lines.append("## Left unmapped")
        lines.append("")
        for name, count in unmapped.most_common():
            lines.append("- `%s` x%d" % (name, count))
        lines.append("")
    if unknown:
        lines.append("## Not in the Conquest registry")
        lines.append("")
        for name, count in unknown.most_common():
            lines.append("- `%s` x%d" % (name, count))
        lines.append("")

    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
