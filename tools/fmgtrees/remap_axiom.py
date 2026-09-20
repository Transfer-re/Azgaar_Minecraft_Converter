"""
Remap the Axiom tree blueprints from vanilla blocks onto Conquest blocks.

    python -m fmgtrees.remap_axiom <blueprints-dir> <out-dir> [--registry <ids.txt>]
                                   [--only species,species] [--all]

Writes `.bp` files again, not NBT -- the point is to open them in Axiom, fix
what looks wrong by hand, and resave. Geometry is never touched: the header,
the thumbnail and every packed `data` array are copied byte for byte and only
palette entries are rewritten (see axiom.py).

This is NOT a plain `spruce_log -> norway_spruce_log` name map, because the pack
does not use blocks literally. A "douglas fir" is acacia_wood + acacia_leaves +
spruce_slab; walls and fences stand in for branches; several species use wheat
and horn_coral as foliage. So the mapping is per Axiom species (which Conquest
tree it becomes) crossed with per-block role (trunk / branch / foliage / limb).

Roles map onto Conquest like this, with properties translated rather than copied:

    trunk    *_log, *_wood, stripped_*   -> <trunk>                 axis kept
    foliage  *_leaves                    -> <foliage>               CR: props dropped
                                                                    vanilla: persistent=true
    branch   *_fence, *_fence_gate       -> <trunk>_branch          n/s/e/w kept, up=false
             *_wall                      -> <trunk>_branch          none/low/tall -> bool
             *_pane, *_bars              -> <trunk>_small_branch
    limb     *_slab                      -> <trunk>_slab            type kept, layer=4
             *_stairs                    -> <trunk>_stairs          facing/half/shape kept

`OVERRIDES` runs before all of that and maps the stand-in blocks that were
resolved by eye in game (coral, crimson roots, wheat). Anything still unmatched
-- candles, trapdoors, carpets, base rocks -- is left exactly as it was and
listed in the report.
"""

import argparse
import collections
import json
import os
import shutil
import sys

from .axiom import Blueprint

# Which Conquest tree each Axiom species becomes, as
# (full trunk block, foliage block, Conquest family prefix, note).
#
# The family prefix is separate from the trunk because Conquest reuses vanilla
# oak and birch for the full block -- `conquest:oak_log` does not exist -- while
# still providing `conquest:oak_log_slab`, `_stairs`, `_branch`, `_small_branch`
# and `_stump`. That is also why the QF English Oak schematics contain
# minecraft:oak_log next to conquest:oak_log_vertical_quarter.
#
# Conquest has no hemlock, douglas fir, cedar or maple log, so those fall back
# to the nearest match; `note` says which ones are a compromise.
SPECIES = {
    # axiom species        trunk block            foliage block                      conquest family              note
    "atlas_cedar":        ("conquest:pine_log",          "conquest:pine_needles",         "conquest:pine_log",          "no cedar in CR; pine"),
    "balsam_fir":         ("conquest:norway_spruce_log", "conquest:dark_spruce_needles",  "conquest:norway_spruce_log", "no fir in CR; dark spruce"),
    "bigtooth_aspen":     ("conquest:aspen_log",         "conquest:aspen_leaves",         "conquest:aspen_log",         ""),
    "common_ash":         ("conquest:ash_log",           "conquest:ash_tree_leaves",      "conquest:ash_log",           ""),
    "common_maple":       ("conquest:beech_log",         "conquest:dark_deciduous_leaves","conquest:beech_log",         "no maple log in CR; beech + generic deciduous"),
    "dead_pine":          ("conquest:pine_log",          "conquest:dead_pine_needles",    "conquest:pine_log",          ""),
    "deodar_cedar":       ("conquest:pine_log",          "conquest:pine_needles",         "conquest:pine_log",          "no cedar in CR; pine"),
    "douglas_fir":        ("conquest:norway_spruce_log", "conquest:dark_spruce_needles",  "conquest:norway_spruce_log", "no douglas fir in CR; dark spruce"),
    "english_oak":        ("minecraft:oak_log",          "minecraft:oak_leaves",          "conquest:oak_log",           "CR reuses vanilla oak for the full block"),
    "european_larch":     ("conquest:larch_log",         "conquest:larch_needles",        "conquest:larch_log",         ""),
    "forest_oak":         ("minecraft:oak_log",          "minecraft:oak_leaves",          "conquest:oak_log",           "CR reuses vanilla oak"),
    "jack_pine":          ("conquest:pine_log",          "conquest:pine_needles",         "conquest:pine_log",          ""),
    "paper_birch":        ("minecraft:birch_log",        "minecraft:birch_leaves",        "conquest:birch_log",         "CR reuses vanilla birch"),
    "quaking_aspen":      ("conquest:aspen_log",         "conquest:aspen_leaves",         "conquest:aspen_log",         ""),
    "red_maple":          ("conquest:beech_log",         "conquest:autumnal_maple_leaves","conquest:beech_log",         "no maple log in CR; beech + autumnal maple"),
    "red_spruce":         ("conquest:norway_spruce_log", "conquest:dark_spruce_needles",  "conquest:norway_spruce_log", ""),
    "silver_birch":       ("minecraft:birch_log",        "minecraft:birch_leaves",        "conquest:birch_log",         "CR reuses vanilla birch"),
    "silver_maple":       ("conquest:beech_log",         "conquest:dark_deciduous_leaves","conquest:beech_log",         "no maple log in CR"),
    "simple_pine":        ("conquest:pine_log",          "conquest:pine_needles",         "conquest:pine_log",          ""),
    "survival_birch":     ("minecraft:birch_log",        "minecraft:birch_leaves",        "conquest:birch_log",         "CR reuses vanilla birch"),
    "survival_dead_pine": ("conquest:pine_log",          "conquest:dead_pine_needles",    "conquest:pine_log",          ""),
    "survival_oak":       ("minecraft:oak_log",          "minecraft:oak_leaves",          "conquest:oak_log",           "CR reuses vanilla oak"),
    "survival_pine":      ("conquest:pine_log",          "conquest:pine_needles",         "conquest:pine_log",          ""),
    "survival_pine_dead": ("conquest:pine_log",          "conquest:dead_pine_needles",    "conquest:pine_log",          ""),
    "survival_stump":     ("minecraft:oak_log",          "minecraft:oak_leaves",          "conquest:oak_log",           "stumps; trunk-only"),
    "tamarack":           ("conquest:larch_log",         "conquest:larch_needles",        "conquest:larch_log",         ""),
    "western_hemlock":    ("conquest:norway_spruce_log", "conquest:dark_spruce_needles",  "conquest:norway_spruce_log", "no hemlock in CR; dark spruce"),
    "western_larch":      ("conquest:larch_log",         "conquest:larch_needles",        "conquest:larch_log",         ""),
    "white_poplar":       ("conquest:aspen_log",         "conquest:black_poplar_leaves",  "conquest:aspen_log",         "no poplar log in CR; aspen (same genus)"),
    "yellow_maple":       ("conquest:beech_log",         "conquest:autumnal_maple_leaves","conquest:beech_log",         "no maple log in CR"),
}

# Species whose blueprints are mostly stand-in blocks -- see docs/conquest/axiom_roles.md.
# Remapped anyway, but flagged: the result needs real work in game, not a glance.
FILLER_HEAVY = {"red_maple", "yellow_maple", "paper_birch", "european_larch",
                "quaking_aspen", "bigtooth_aspen", "tamarack", "silver_maple",
                "balsam_fir"}

# Stand-in blocks resolved by eye in game, after walking the remapped trees.
# These win over the role rules below, and apply to every species -- crimson
# roots and fire coral only ever occur in red_maple, so there is no collision.
# All three targets are plain blocks with no properties, so properties are
# dropped.
OVERRIDES = {
    # red_maple's red foliage: 89% of that tree is these three.
    "minecraft:crimson_roots":  "conquest:autumnal_maple_leaves",
    "minecraft:fire_coral":     "conquest:autumnal_maple_leaves",
    "minecraft:fire_coral_fan": "conquest:autumnal_maple_leaves",
    # the broadleaf/larch foliage stand-ins
    "minecraft:horn_coral":     "conquest:faded_autumnal_beech_tree_ground_leaves",
    "minecraft:horn_coral_fan": "conquest:faded_autumnal_beech_tree_ground_leaves",
    "minecraft:wheat":          "conquest:bright_autumnal_beech_tree_ground_leaves",
}

# Blueprints dropped outright -- reviewed in game and not worth keeping.
DROP = {
    "dead_pine_1_m_01", "dead_pine_1_m_02",
    "dead_pine_1_s_01", "dead_pine_1_s_02", "dead_pine_1_s_03", "dead_pine_1_s_04",
    "dead_pine_2_m_01", "dead_pine_2_m_02",
    "dead_pine_2_s_01", "dead_pine_2_s_02", "dead_pine_2_s_03", "dead_pine_2_s_04",
}

TRUNK_SUFFIX = ("_log", "_wood", "_stem", "_hyphae")
WALL_LEVELS = {"none": "false", "low": "true", "tall": "true"}


def short(name):
    return name.split(":", 1)[-1]


def classify(name):
    n = short(name)
    if n.endswith("_leaves"):
        return "foliage"
    if n.endswith("_fence") or n.endswith("_fence_gate"):
        return "fence"
    if n.endswith("_wall"):
        return "wall"
    if n.endswith("_pane") or n.endswith("_bars"):
        return "pane"
    if n.endswith("_slab"):
        return "slab"
    if n.endswith("_stairs"):
        return "stairs"
    if any(n.endswith(s) for s in TRUNK_SUFFIX):
        return "trunk"
    return None


def make_mapper(trunk, foliage, family, skipped):
    """Build the per-palette-entry rewrite function for one species."""
    branch = family + "_branch"
    small = family + "_small_branch"

    def mapper(name, props):
        override = OVERRIDES.get(name)
        if override is not None:
            return override, {}

        kind = classify(name)

        if kind == "trunk":
            keep = {"axis": props["axis"]} if "axis" in props else {}
            return trunk, keep

        if kind == "foliage":
            # Conquest's own leaves and needles are plain blocks with no
            # properties, so they cannot decay. Vanilla leaves can: dropped
            # properties default to persistent=false, distance=7, and the whole
            # canopy rots on the first random tick. Conquest does tag its logs
            # and even its *_log_branch blocks into minecraft:logs, so decay
            # does see them -- but vanilla decay only reaches distance 7, and
            # these canopies are wider than that. persistent=true is the fix,
            # and it is what the QF pack does too.
            if foliage.startswith("minecraft:") and foliage.endswith("_leaves"):
                return foliage, {"persistent": "true", "distance": "1",
                                 "waterlogged": "false"}
            return foliage, {}

        if kind == "fence":
            if short(name).endswith("_fence_gate"):
                # A gate spans one axis; turn it into a branch running that way.
                facing = props.get("facing", "north")
                ns = facing in ("north", "south")
                sides = {"north": str(not ns).lower(), "south": str(not ns).lower(),
                         "east": str(ns).lower(), "west": str(ns).lower()}
            else:
                sides = {d: props.get(d, "false") for d in ("north", "south", "east", "west")}
            sides["up"] = "false"
            return branch, sides

        if kind == "wall":
            sides = {d: WALL_LEVELS.get(props.get(d, "none"), "false")
                     for d in ("north", "south", "east", "west")}
            sides["up"] = props.get("up", "false")
            return branch, sides

        if kind == "pane":
            sides = {d: props.get(d, "false") for d in ("north", "south", "east", "west")}
            sides["up"] = "false"
            return small, sides

        if kind == "slab":
            t = props.get("type", "bottom")
            if t == "double":
                return trunk, {}
            return family + "_slab", {"type": t, "layer": "4"}

        if kind == "stairs":
            return family + "_stairs", {
                "facing": props.get("facing", "north"),
                "half": props.get("half", "bottom"),
                "shape": props.get("shape", "straight"),
            }

        skipped[name] += 1
        return None

    return mapper


def main(argv=None):
    ap = argparse.ArgumentParser(prog="fmgtrees.remap_axiom")
    ap.add_argument("source", help="the Axiom blueprints directory")
    ap.add_argument("out", help="output directory (a new Axiom blueprint folder)")
    ap.add_argument("--registry", help="file of known Conquest block ids")
    ap.add_argument("--only", help="comma-separated species to convert")
    ap.add_argument("--all", action="store_true",
                    help="include the filler-heavy species (default: skip them)")
    args = ap.parse_args(argv)

    known = None
    if args.registry and os.path.exists(args.registry):
        with open(args.registry, encoding="utf-8") as fh:
            bare = {line.strip() for line in fh if line.strip()}
        known = {"conquest:" + b for b in bare}

    wanted = set(args.only.split(",")) if args.only else set(SPECIES)
    if not args.all and not args.only:
        wanted -= FILLER_HEAVY

    os.makedirs(args.out, exist_ok=True)

    unknown = set()
    if known is not None:
        for sp, (trunk, foliage, family, _n) in SPECIES.items():
            for target in (trunk, foliage, family + "_branch", family + "_small_branch",
                           family + "_slab", family + "_stairs"):
                if target.startswith("conquest:") and target not in known:
                    unknown.add(target)

    done = collections.Counter()
    skipped = collections.Counter()
    dropped = []
    failures = []

    for sp in sorted(wanted):
        if sp not in SPECIES:
            print("unknown species %r, skipping" % sp)
            continue
        src_dir = os.path.join(args.source, sp)
        if not os.path.isdir(src_dir):
            continue
        trunk, foliage, family, _note = SPECIES[sp]
        dst_dir = os.path.join(args.out, sp)
        os.makedirs(dst_dir, exist_ok=True)

        mapper = make_mapper(trunk, foliage, family, skipped)
        for fn in sorted(os.listdir(src_dir)):
            if not fn.endswith(".bp"):
                continue
            if fn[:-len(".bp")] in DROP:
                dropped.append(sp + "/" + fn)
                continue
            try:
                bp = Blueprint(os.path.join(src_dir, fn))
                bp.remap(mapper)
                bp.write(os.path.join(dst_dir, fn))
                done[sp] += 1
            except Exception as ex:
                failures.append((sp + "/" + fn, str(ex)))

        ordering = os.path.join(src_dir, ".axiom_blueprint_ordering")
        if os.path.exists(ordering):
            shutil.copy2(ordering, os.path.join(dst_dir, ".axiom_blueprint_ordering"))

    print("remapped %d blueprints across %d species into %s"
          % (sum(done.values()), len(done), args.out))
    for sp in sorted(done):
        trunk, foliage, _family, note = SPECIES[sp]
        flag = "  <-- filler-heavy" if sp in FILLER_HEAVY else ""
        print("  %-22s %3d  %s + %s%s%s"
              % (sp, done[sp], short(trunk), short(foliage),
                 ("  (" + note + ")") if note else "", flag))

    if unknown:
        print("\nTARGET BLOCKS NOT IN THE REGISTRY (%d) -- fix SPECIES before trusting these:"
              % len(unknown))
        for t in sorted(unknown):
            print("  " + t)

    if dropped:
        print("\ndropped %d blueprint(s) on request:" % len(dropped))
        for name in sorted(dropped):
            print("  " + name)

    if skipped:
        print("\nleft untouched (stand-in blocks -- these are the hand-fix list):")
        for name, n in skipped.most_common(25):
            print("  %-44s %d palette entries" % (short(name), n))

    if not args.all and not args.only:
        print("\nskipped %d filler-heavy species (pass --all to include): %s"
              % (len(FILLER_HEAVY), ", ".join(sorted(FILLER_HEAVY))))

    if failures:
        print("\n%d file(s) failed:" % len(failures))
        for name, why in failures[:10]:
            print("  %s: %s" % (name, why))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
