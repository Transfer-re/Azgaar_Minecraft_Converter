"""
Convert the paleozoey African tree schematics to Conquest, as blueprints and NBT.

    python -m fmgtrees.convert_afr <schem-dir> --bp-out <dir> --nbt-out <dir>
                                   [--registry <ids.txt>] [--only Species,Species]
                                   [--jar <ConquestReforged.jar>] [--report <md>]
                                   [--data-version N] [--dry-run]

Two outputs from one pass, because the review loop needs both:

  * `.bp` blueprints, so the trees can be opened in Axiom next to the rest of
    the Conquest set, fixed by hand and resaved. This is the half that matters
    first -- none of these have been looked at in game yet.
  * structure `.nbt` plus an `index.json` in the same shape as the QF and Axiom
    packs, so the placement feature can use them without a special case.

Sizes are derived from height, because unlike the QF pack the filenames carry
no size: the thresholds are set to line up with the height bands the existing
1302 trees already occupy.

The schematics are paleozoey's work (see the pack's ATTRIBUTION.md) and are not
redistributed here -- both output directories are outside `src/main/resources`
so nothing lands in the jar.
"""

import argparse
import collections
import json
import os
import re
import sys

from . import bpwrite, remap_afr, schem, structure, thumbnail

# Height bands, chosen to sit inside the ranges the QF and Axiom trees already
# use (xs medians 4, s 11, m 16, l 17, xl 23).
SIZE_BANDS = ((6, "xs"), (12, "s"), (19, "m"), (26, "l"))
SIZE_XL = "xl"


def size_for(height):
    for limit, name in SIZE_BANDS:
        if height <= limit:
            return name
    return SIZE_XL


def split_name(stem):
    """`Khaya_ivoriensis (3)` / `Vachellia_tortilis7` -> ("Khaya_ivoriensis", "03")."""
    m = re.match(r"^(.*?)[ _]*\(?(\d+)\)?$", stem)
    if m and m.group(1):
        return m.group(1).rstrip("_ "), "%02d" % int(m.group(2))
    return stem, "01"


def slug(name):
    return name.lower()


def convert_one(path, spec, unmapped, oddities):
    """Read one schematic and return its remapped voxels."""
    source = schem.read(path)
    mapper = remap_afr.make_mapper(spec, unmapped)

    out = {}
    cache = {}
    for pos, state in source.voxels.items():
        new = cache.get(state)
        if new is None:
            name, props = schem.parse_state(state)
            result = mapper(name, props)
            if result is None:
                # Unmappable: keep it, so nothing silently disappears from the
                # model. It shows up in the report to be fixed by hand.
                new = state
            else:
                block, newprops = result
                new = block
                if newprops:
                    new += "[" + ",".join("%s=%s" % (k, newprops[k])
                                          for k in sorted(newprops)) + "]"
                if name in remap_afr.ODDITIES or remap_afr.classify(name) in (
                        "fence", "button", "fruit"):
                    oddities[(name, new)] += 1
            cache[state] = new
        out[pos] = new
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", help="directory of .schem files")
    ap.add_argument("--bp-out", help="where to write Axiom .bp blueprints")
    ap.add_argument("--nbt-out", help="where to write structure .nbt + index.json "
                    "(normally left off: run convert_bp on the blueprints instead, "
                    "so the NBT carries the hand edits)")
    ap.add_argument("--fill", choices=("air", "void"), default="air",
                    help="what fills the empty cells of a blueprint "
                         "(default air: the state Axiom's tools skip)")
    ap.add_argument("--registry", help="file of known Conquest block ids")
    ap.add_argument("--jar", help="Conquest jar, for thumbnail colours")
    ap.add_argument("--only", help="comma-separated species to convert")
    ap.add_argument("--report", help="write a markdown report here")
    ap.add_argument("--data-version", type=int, default=bpwrite.DATA_VERSION_1_20_1,
                    help="DataVersion for the .bp files (default 3465 = 1.20.1, "
                         "the version Conquest runs on)")
    ap.add_argument("--no-thumbnails", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)

    registry = None
    if args.registry and os.path.exists(args.registry):
        with open(args.registry, encoding="utf-8") as fh:
            registry = set(fh.read().split())

    only = {s.strip() for s in args.only.split(",")} if args.only else None

    files = sorted(f for f in os.listdir(args.src) if f.endswith(".schem"))
    if not files:
        print("no .schem files in %s" % args.src, file=sys.stderr)
        return 1

    pal = None
    if not args.no_thumbnails:
        pal = thumbnail.Palette(args.jar)

    unmapped = collections.Counter()
    oddities = collections.Counter()
    missing_species = collections.Counter()
    unknown_ids = collections.Counter()
    entries = []
    # Kept separately from `entries`: the report describes what was converted,
    # which is every tree, while `entries` is only the ones that also got NBT.
    converted = []
    groups = collections.Counter()
    written_bp = written_nbt = 0

    for fname in files:
        stem = fname[:-len(".schem")]
        species, variant = split_name(stem)
        if only and species not in only:
            continue
        spec = remap_afr.SPECIES.get(species)
        if spec is None:
            missing_species[species] += 1
            continue

        voxels = convert_one(os.path.join(args.src, fname), spec, unmapped, oddities)
        if registry is not None:
            for state in set(voxels.values()):
                block = state.split("[", 1)[0]
                if block.startswith("conquest:") and block not in registry:
                    unknown_ids[block] += 1

        trimmed, dims = structure.trim(voxels)
        height = dims[1]
        size = size_for(height)
        ident = "%s_%s_%s" % (slug(species), size, variant)
        groups["afr/%s/default" % slug(species)] += 1
        converted.append({"species": slug(species), "height": height, "size": size})

        if args.dry_run:
            continue

        if args.bp_out:
            out_dir = os.path.join(args.bp_out, slug(species))
            os.makedirs(out_dir, exist_ok=True)
            thumb = None
            if pal is not None:
                thumb = thumbnail.render(voxels, pal)
            bpwrite.write(os.path.join(out_dir, ident + ".bp"), voxels,
                          name=stem, data_version=args.data_version,
                          thumbnail=thumb,
                          fill=bpwrite.FILL_AIR if args.fill == "air"
                          else bpwrite.FILL_VOID)
            written_bp += 1

        if args.nbt_out:
            out_dir = os.path.join(args.nbt_out, slug(species))
            os.makedirs(out_dir, exist_ok=True)
            structure.write(os.path.join(out_dir, ident + ".nbt"), trimmed, dims)
            anchor = structure.trunk_anchor(trimmed)
            entries.append({
                "id": ident,
                "species": slug(species),
                "colour": "default",
                "size": size,
                "variant": variant,
                "file": "%s/%s.nbt" % (slug(species), ident),
                "footprint": [dims[0], dims[2]],
                "height": height,
                "anchor": list(anchor),
                "blocks": len(trimmed),
                "palette": len(set(trimmed.values())),
                "common": spec.common,
            })
            written_nbt += 1

    if entries and args.nbt_out:
        index = {
            "pack": "afr",
            "count": len(entries),
            "groups": {g: c for g, c in sorted(groups.items())},
            "trees": sorted(entries, key=lambda e: e["id"]),
        }
        with open(os.path.join(args.nbt_out, "index.json"), "w", encoding="utf-8") as fh:
            json.dump(index, fh, indent=1)

    print("schematics:     %d" % len(files))
    print("blueprints:     %d" % written_bp)
    print("structure nbt:  %d" % written_nbt)
    print("groups:         %d" % len(groups))
    if missing_species:
        print("species with no mapping: %s" % ", ".join(sorted(missing_species)))
    if unmapped:
        print("blocks left as they were:")
        for name, count in unmapped.most_common():
            print("   %-44s %d" % (name, count))
    if unknown_ids:
        print("NOT IN REGISTRY:")
        for name, count in unknown_ids.most_common():
            print("   %-44s %d" % (name, count))
    if oddities:
        print("hand-mapped oddities (worth a look in game):")
        for (src, dst), count in oddities.most_common():
            print("   %-34s -> %-42s %d" % (src, dst, count))

    if args.report:
        write_report(args.report, converted, groups, unmapped, oddities,
                     unknown_ids, len(files))
        print("report:         %s" % args.report)
    return 0


def write_report(path, entries, groups, unmapped, oddities, unknown_ids, nfiles):
    by_species = collections.defaultdict(list)
    for e in entries:
        by_species[e["species"]].append(e)

    lines = ["# African tree pack -> Conquest", ""]
    lines.append("%d schematics, %d species, %d converted." %
                 (nfiles, len(by_species), len(entries)))
    lines.append("")
    lines.append("Source: paleozoey, bundled with Arnis. Not redistributed here.")
    lines.append("")
    lines.append("| species | common name | foliage | wood | n | heights |")
    lines.append("|---|---|---|---|---|---|")
    for species in sorted(by_species):
        spec = None
        for key, value in remap_afr.SPECIES.items():
            if key.lower() == species:
                spec = value
                break
        rows = by_species[species]
        heights = sorted(e["height"] for e in rows)
        wood = ", ".join("%s -> %s" % (k, v) for k, v in spec.wood.items())
        lines.append("| `%s` | %s | `%s` | %s | %d | %d-%d |" % (
            species, spec.common, spec.foliage.split(":")[-1], wood,
            len(rows), heights[0], heights[-1]))
    lines.append("")

    notes = [(k, v.note) for k, v in remap_afr.SPECIES.items() if v.note]
    if notes:
        lines.append("## Judgement calls")
        lines.append("")
        for name, note in sorted(notes):
            lines.append("- **%s** -- %s" % (name, note))
        lines.append("")

    if oddities:
        lines.append("## Shape hacks, mapped by hand")
        lines.append("")
        lines.append("These are blocks the pack uses for their shape, not their "
                     "material. Most likely to want a second look in game.")
        lines.append("")
        lines.append("| source | becomes | n |")
        lines.append("|---|---|---|")
        for (src, dst), count in oddities.most_common():
            lines.append("| `%s` | `%s` | %d |" % (src, dst, count))
        lines.append("")

    if unmapped:
        lines.append("## Left unmapped")
        lines.append("")
        for name, count in unmapped.most_common():
            lines.append("- `%s` x%d" % (name, count))
        lines.append("")

    if unknown_ids:
        lines.append("## Not in the Conquest registry")
        lines.append("")
        for name, count in unknown_ids.most_common():
            lines.append("- `%s` x%d" % (name, count))
        lines.append("")

    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
