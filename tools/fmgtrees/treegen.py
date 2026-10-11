"""
Bring the TreeGenerator trees into the mod: schematics -> structure NBT, and the
biome mix -> vanilla configured + placed features.

    python -m fmgtrees.treegen [--treegen <TreeGenerator repo>] [--out <resources>]
    python -m fmgtrees.make_datapack          # then: puts the features in the biomes

Reads `<treegen>/out/index.json` (every schematic, with its paste point, radii and
flags) and `<treegen>/biome_mix.toml` (per biome: which presets, how many, what
sizes), and writes under src/main/resources/data/fantasymapgenerator/:

    structures/treegen/<preset>/<name>.nbt
        One structure file per schematic, air left out, so a placement only ever
        writes the object's own blocks -- the same thing `//paste -a` does.
        GENERATED and gitignored: thousands of files, rebuilt whenever the
        generator runs. A checkout without them still loads, it just places
        nothing (the feature logs which templates it could not find, once).

    worldgen/configured_feature/treegen/preset/<preset>.json
        One `fantasymapgenerator:treegen_object` per preset: its objects (template,
        size, paste point, radii) and where it may stand. `/place feature` on one
        of these drops a random object of that preset, which is the review tool.

    worldgen/configured_feature/treegen/<biome>/<layer>.json
    worldgen/placed_feature/treegen/<biome>/<layer>.json
        One `fantasymapgenerator:treegen_mix` per biome and layer, placed with
        vanilla's own modifier chain -- the same one `trees_plains` uses: count,
        in_square, surface_water_depth_filter, heightmap OCEAN_FLOOR, biome.

`make_datapack` then appends each biome's placed features to its
vegetal_decoration step, in layer order: trees, dead wood, fallen logs and
stumps, shrubs -- WORLDGEN.md's order, so shrubs fill the gaps the others left.

The biome mix is matched to our biome ids by its `name` field, which is the
biome list's display name. `tropical_coast` is not a biome: it is a shoreline
overlay, hosted by the two tropical forests, and its features skip the biome
filter (shallow sea columns carry a vanilla ocean biome) and check it themselves.

Everything WORLDGEN.md and the mix's `notes` say about *where* a preset goes is
turned into data here, so it can be tuned without touching Java:
NOTE_RULES (near water, wet hollows, not in the reeds, woodland edge, size
classes for the rainforest emergents) and the ground groups per biome.
"""

import argparse
import json
import math
import os
import sys
import tomllib

from . import nbtio, schem
from .make_biomes import BIOMES

NS = "fantasymapgenerator"
HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main", "resources"))
TREEGEN = r"C:\Users\ferre\projectjes\TreeGenerator"
DATA_VERSION_1_20_1 = 3465

# placement order within a chunk (WORLDGEN.md "Placement rules")
LAYERS = ("trees", "dead", "logs", "shrubs")
SIZES = ("tiny", "small", "medium", "large", "forest")
SIZE_KEY = {"trees": "tree_sizes", "dead": "dead_sizes", "logs": "log_sizes", "shrubs": "shrub_sizes"}
CATEGORY_LAYER = {"trees": "trees", "bushes_and_shrubs": "shrubs",
                  "dead_and_old_wood": "dead", "fallen_logs_and_stumps": "logs"}

# ---- ground ------------------------------------------------------------------
# Ground groups are palettes.py's material groups; the Java side reads the
# same groups out of the surface palettes, so "soil" here and "soil" there are
# literally the same list of blocks. WORLDGEN.md: grass, dirt, podzol, mud and
# Conquest soil blocks, sand too for palms, acacias and desert presets.
SOIL = ["grass", "floor", "soil", "moss", "wet"]
DRY = ["grass", "floor", "soil", "moss"]
# In these biomes sand is ordinary ground for everything that grows there.
SANDY_BIOMES = {"hot_desert", "savanna", "cold_steppe"}
# Scree and tundra shrubs and deadwood also root in gravel. Trees do not:
# "Alpine scree: trees only at the lower edge" is exactly "not on gravel".
GRAVEL_BIOMES = {"alpine_scree", "lichen_tundra", "shrub_tundra", "cold_steppe"}
# The palettes ramp steep ground to gravel and rock (overgrown stones, pebbles,
# scree), and on hilly maps conifer forest meets that constantly. A first test
# on Encia refused half of all spruce spots for it, leaving the taiga at 4%
# canopy. Conifers root in gravel; in the conifer biomes so does everything.
# Bare rock stays for the hardy few in ROCK_OK.
CONIFER_BIOMES = {"spruce_taiga", "old_growth_spruce", "pine_taiga", "larch_taiga",
                  "autumn_larch_taiga", "dead_taiga", "mossy_conifer_forest",
                  "mixed_conifer_broadleaf"}
ROCK_OK = {"krummholz_spruce", "juniper_shrub", "common_juniper", "crag_pine", "swiss_pine",
           "dragon_tree"}

# ---- spacing -----------------------------------------------------------------
# WORLDGEN.md: keep distance >= k * (crown_a + crown_b); k 0.5-0.6 in dense
# forest (crowns touch), 0.8 in woodland, >= 1.2 in open land.
#
# Tuned denser than WORLDGEN.md's 0.5-0.6 / 0.8 / 1.2 after the first test on
# Encia: the full crown radius is the furthest leaf from the trunk, so k = 0.55
# kept two forest-size oaks ~19 blocks apart, spacing refused most spots in every
# forest, and canopy cover came out at 25-40% where a forest should be closed.
# The user's verdict: "most areas in general should have denser trees".
def tree_spacing(per_chunk):
    if per_chunk >= 4:
        return 0.42
    if per_chunk >= 1:
        return 0.6
    return 0.9


SHRUB_SPACING = 0.7      # "between shrubs, keep about 0.7 x the sum of their radii"

# Per-layer multiplier on biome_mix.toml's `<layer>_per_chunk`, so the
# TreeGenerator mix stays the source of the proportions while this side sets the
# overall density. Spacing still caps the dense stands; these mostly fill out
# the woodland and open biomes.
DENSITY = {"trees": 1.5, "dead": 1.0, "logs": 1.0, "shrubs": 1.4}

# Spots tried per placement before giving up (rough ground fails often).
ATTEMPTS = 5

# How much the ground under a trunk footprint may vary beyond the object's sunk
# depth. WORLDGEN.md's rule is 1 ("the sunk base hides that much"); at 1 the
# mountain taiga was refused for slope at almost every spot, so trees take 3 --
# seated on the lowest ground, bedded into the uphill side -- and shrubs 2.
SLOPE = {"trees": 3, "dead": 3, "logs": 1, "shrubs": 2}
# ...or as steep as this anyway, rise over run across the footprint (1.7 = 60
# degrees). The block allowance alone is stricter for wide trunks, whose wider
# footprint spans more rise on the same hillside. Measured on Encia's spruce
# mountains: median slope 48 degrees, 58% of the ground steeper than 45, so
# anything stricter leaves most mountainsides bare. Steeper than this is cliff
# and stays bare. The Java side caps the burial (a trunk is never bedded more
# than 6 blocks into the uphill side) and, when a spot is too steep for a tree,
# tries a smaller one of the same kind there -- stunted trees on the slopes.
# Logs keep their flat-ground rule.
GRADIENT = {"trees": 1.7, "dead": 1.7, "logs": 0.0, "shrubs": 1.4}

# Trees, dead trees and shrubs stand on rock too: the palettes turn steep ground
# to rock, and refusing it left mountainsides bare. Ice and snow stay refused, which is
# what keeps a treeline. Scree keeps its own rule ("trees only at the lower
# edge"), and nothing grows on the glacier.
ROCK_FOR_TREES = True
NO_ROCK_BIOMES = {"alpine_scree", "glacier"}

# ---- what the notes say, as rules ---------------------------------------------
# (mix key, preset) -> extra fields on that mix entry. near_water / near_sea:
# radius in blocks within which water must exist. open_sky: chance to accept
# a spot that already has canopy overhead (1.0 = no preference). ground:
# replaces the entry's ground groups. sizes: replaces the size weights.
NOTE_RULES = {
    # "Blackthorn, hawthorn and bramble mostly at the woodland edge and in clearings."
    ("oak_woodland", "blackthorn"): {"open_sky": 0.2},
    ("oak_woodland", "hawthorn"): {"open_sky": 0.2},
    ("oak_woodland", "bramble_thicket"): {"open_sky": 0.2},
    # "Willows at wet edges."
    ("aspen_grove", "willow_scrub"): {"near_water": 10},
    ("aspen_grove", "goat_willow"): {"near_water": 10},
    # "bog_pine only in wet hollows."
    ("pine_taiga", "bog_pine"): {"ground": ["wet", "moss"]},
    # "bog_myrtle in wet patches."
    ("moorland", "bog_myrtle"): {"ground": ["wet", "moss"]},
    # "Trees and shrubs at the edges and on dry islands, not in the reeds."
    ("fen_and_reedbed", "alder"): {"ground": DRY},
    ("fen_and_reedbed", "black_poplar"): {"ground": DRY},
    ("fen_and_reedbed", "willow_scrub"): {"ground": DRY},
    ("fen_and_reedbed", "bog_myrtle"): {"ground": DRY},
    ("fen_and_reedbed", "elder"): {"ground": DRY},
    # "date_palm and dead_palm only at oases (near water); dragon_tree on rocky ground."
    ("hot_desert", "date_palm"): {"near_water": 12},
    ("hot_desert", "dead_palm"): {"near_water": 12},
    ("hot_desert", "dragon_tree"): {"ground": ["rock", "gravel", "soil", "sand"]},
    # "fever_tree near water"
    ("savanna", "fever_tree"): {"near_water": 14},
    # "african_mahogany in the wetter parts."
    ("tropical_seasonal_forest", "african_mahogany"): {"near_water": 24},
    # "Emergents (tropical_canopy, vaultwood, african_mahogany) in large/forest
    # size stand above the midcanopy."
    ("tropical_rainforest", "tropical_canopy"): {"sizes": {"large": 1, "forest": 2}},
    ("tropical_rainforest", "vaultwood"): {"sizes": {"large": 1, "forest": 2}},
    ("tropical_rainforest", "african_mahogany"): {"sizes": {"large": 1, "forest": 2}},
    ("tropical_rainforest", "tropical_midcanopy"): {"sizes": {"small": 1, "medium": 2, "large": 2}},
    # "Mangroves stand in shallow water; coconut_palm on beach sand."
    ("tropical_coast", "coconut_palm"): {"ground": ["sand"], "near_sea": 8},
    ("tropical_coast", "dead_palm"): {"ground": ["sand"], "near_sea": 10},
}

# (mix key, preset) left out of a biome's mix, and its share of the count with
# it: the other presets keep their numbers instead of filling the gap. The mix
# itself stays TreeGenerator's.
DROP = {
    # tree_fern is a mossy palm trunk under royal palm fronds, so in a conifer
    # forest it reads as a palm. The user, 2026-10-05: "forests like taiga have a
    # type of palm tree that i'd like removed". It stays in the rainforest.
    ("mossy_conifer_rainforest", "tree_fern"),
}

# Shoreline overlays: mix key -> the biomes it belongs to.
COAST_MIXES = {"tropical_coast": ("tropical_rainforest", "tropical_seasonal_forest")}

# WORLDGEN.md: red_mangrove in 1-2 blocks of water, stiltbark up to 3.
WATER = {"red_mangrove": {"min_depth": 1, "max_depth": 2, "sea": True},
         "stiltbark": {"min_depth": 1, "max_depth": 3, "sea": True}}


# ---- conversion ----------------------------------------------------------------

def template_id(e):
    return "%s:treegen/%s/%s" % (NS, e["preset"], e["name"])


def convert(e, root, out_dir):
    """One schematic -> one structure file. Returns the anchor and block count."""
    path = os.path.join(root, "out", *e["path"].split("/"))
    _, raw = nbtio.load(path)
    if "Schematic" in raw and "Width" not in raw:
        raw = raw["Schematic"]
    md = raw.get("Metadata", {})
    we = (int(md.get("WEOffsetX", 0)), int(md.get("WEOffsetY", 0)), int(md.get("WEOffsetZ", 0)))
    s = schem.read(path)                  # air is already dropped
    rel = {(x + we[0], y + we[1], z + we[2]): st for (x, y, z), st in s.voxels.items()}
    if not rel:
        return None
    lo = [min(p[i] for p in rel) for i in range(3)]
    hi = [max(p[i] for p in rel) for i in range(3)]
    # The index's bounds are the same numbers; a mismatch means the schematic
    # changed under the index, and the radii below would be stale.
    if lo != e["bounds_min"] or hi != e["bounds_max"]:
        raise ValueError("%s: bounds %s..%s disagree with index %s..%s -- rebuild the "
                         "index (python -m treegen index)" % (e["name"], lo, hi,
                                                                e["bounds_min"], e["bounds_max"]))
    voxels = {(x - lo[0], y - lo[1], z - lo[2]): st for (x, y, z), st in rel.items()}
    size = [hi[i] - lo[i] + 1 for i in range(3)]
    target = os.path.join(out_dir, e["preset"], e["name"] + ".nbt")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    write_structure(target, voxels, size)
    names = {st.split("[", 1)[0] for st in voxels.values()}
    return [-lo[0], -lo[1], -lo[2]], len(voxels), names


def write_structure(path, voxels, size):
    """Vanilla structure NBT, palette in first-seen order, blocks bottom-up."""
    palette, index, blocks = [], {}, []
    for (x, y, z), state in sorted(voxels.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        i = index.get(state)
        if i is None:
            name, props = schem.parse_state(state)
            entry = {"Name": name}
            if props:
                entry["Properties"] = dict(props)
            i = index[state] = len(palette)
            palette.append(entry)
        blocks.append({"pos": nbtio.List([nbtio.Int(x), nbtio.Int(y), nbtio.Int(z)], nbtio.TAG_INT),
                       "state": nbtio.Int(i)})
    nbtio.dump(path, {
        "DataVersion": nbtio.Int(DATA_VERSION_1_20_1),
        "size": nbtio.List([nbtio.Int(v) for v in size], nbtio.TAG_INT),
        "palette": nbtio.List(palette, nbtio.TAG_COMPOUND),
        "blocks": nbtio.List(blocks, nbtio.TAG_COMPOUND),
        "entities": nbtio.List([], nbtio.TAG_COMPOUND),
    }, name="")


def kept(entries):
    """WORLDGEN.md: if any schematic in a batch is marked keep, use only those."""
    batches = {}
    for e in entries:
        batches.setdefault((e["preset"], e["size"], e["setting"]), []).append(e)
    out = []
    for batch in batches.values():
        keepers = [e for e in batch if e.get("keep") is True]
        out.extend(keepers if keepers else [e for e in batch if e.get("keep") is not False])
    return out


# ---- feature JSON --------------------------------------------------------------

def default_ground(category, archetype, preset):
    """Ground for a preset placed on its own (`/place feature`) or by a mix that adds nothing."""
    if preset in WATER:
        return []
    ground = list(SOIL)
    if archetype in ("palm", "umbrella", "mangrove"):
        ground.append("sand")
    if archetype == "conifer" or preset in ROCK_OK:
        ground.append("gravel")
    if preset in ROCK_OK:
        ground.append("rock")
    return ground


def preset_feature(preset, objs):
    e0 = objs[0]
    cfg = {
        "preset": preset,
        "layer": CATEGORY_LAYER[e0["category"]],
        "ground": default_ground(e0["category"], e0["archetype"], preset),
        "objects": [],
    }
    if preset in WATER:
        cfg["water"] = WATER[preset]
    for e in sorted(objs, key=lambda e: (SIZES.index(e["size"]), e["name"])):
        o = {"template": template_id(e), "size": e["size"], "anchor": e["_anchor"],
             "crown": round(e["crown_radius"], 2), "base": round(e["base_radius"], 2),
             "height": e["height"], "depth": e["depth"]}
        if e.get("lying_axis"):
            o["lying"] = e["lying_axis"]
        cfg["objects"].append(o)
    return {"type": NS + ":treegen_object", "config": cfg}


def count_provider(per_chunk):
    """An average of `per_chunk` as vanilla's weighted_list of floor and ceil."""
    lo = int(math.floor(per_chunk))
    frac = per_chunk - lo
    if frac < 1e-6:
        return lo
    hi_w = int(round(frac * 1000))
    return {"type": "minecraft:weighted_list",
            "distribution": [{"data": lo, "weight": 1000 - hi_w}, {"data": lo + 1, "weight": hi_w}]}


def size_weights(sizes, available):
    out = {s: float(w) for s, w in sizes.items() if s in available and w > 0}
    return out or {s: 1.0 for s in available}


def mix_feature(mix_key, biome_id, layer, spec, defaults, presets, coast_hosts):
    per_chunk = float(spec.get(layer + "_per_chunk", 0) or 0)
    weights = spec.get(layer, {})
    dropped = sum(w for p, w in weights.items() if (mix_key, p) in DROP)
    if dropped:
        per_chunk *= 1 - dropped / sum(weights.values())
        weights = {p: w for p, w in weights.items() if (mix_key, p) not in DROP}
    if per_chunk <= 0 or not weights:
        return None, None
    layer_sizes = spec.get(SIZE_KEY[layer], defaults[SIZE_KEY[layer]]) if layer == "trees" \
        else defaults[SIZE_KEY[layer]]
    entries = []
    for preset, w in weights.items():
        objs = presets[preset]
        available = {o["size"] for o in objs}
        rule = NOTE_RULES.get((mix_key, preset), {})
        ground = rule.get("ground")
        if ground is None:
            ground = default_ground(objs[0]["category"], objs[0]["archetype"], preset)
            if biome_id in SANDY_BIOMES and "sand" not in ground and ground:
                ground = ground + ["sand"]
            if ground and "gravel" not in ground and (
                    biome_id in CONIFER_BIOMES
                    or (biome_id in GRAVEL_BIOMES and layer in ("shrubs", "dead"))):
                ground = ground + ["gravel"]
            if (ROCK_FOR_TREES and ground and layer in ("trees", "dead", "shrubs")
                    and biome_id not in NO_ROCK_BIOMES):
                for g in ("gravel", "rock"):
                    if g not in ground:
                        ground = ground + [g]
        entry = {"preset": "%s:treegen/preset/%s" % (NS, preset), "weight": float(w),
                 "sizes": size_weights(rule.get("sizes", layer_sizes), available),
                 "ground": ground}
        for k in ("near_water", "near_sea", "open_sky"):
            if k in rule:
                entry[k] = rule[k]
        entries.append(entry)

    if layer in ("trees", "dead"):
        # how closed the stand is counts everything standing: dead taiga is a
        # dense forest of snags with a few live trees, not open land
        standing = float(spec.get("trees_per_chunk", 0) or 0) + float(spec.get("dead_per_chunk", 0) or 0)
        spacing = tree_spacing(standing)
    elif layer == "shrubs":
        spacing = SHRUB_SPACING
    else:
        spacing = 1.0
    cfg = {"layer": layer, "spacing": spacing, "attempts": ATTEMPTS, "slope": SLOPE[layer],
           "gradient": GRADIENT[layer], "entries": entries}
    if coast_hosts:
        cfg["hosts"] = ["%s:conquest/%s" % (NS, h) for h in coast_hosts]
    configured = {"type": NS + ":treegen_mix", "config": cfg}

    max_depth = 0
    for preset in weights:
        if preset in WATER:
            max_depth = max(max_depth, WATER[preset]["max_depth"])
    placement = [{"type": "minecraft:count", "count": count_provider(per_chunk * DENSITY[layer])},
                 {"type": "minecraft:in_square"},
                 {"type": "minecraft:surface_water_depth_filter", "max_water_depth": max_depth},
                 {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"}]
    if not coast_hosts:
        placement.append({"type": "minecraft:biome"})
    placed = {"feature": "%s:treegen/%s/%s" % (NS, biome_id, layer), "placement": placement}
    return configured, placed


def dump(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(obj, fh, indent=1)
        fh.write("\n")


def clear(directory, suffix):
    if not os.path.isdir(directory):
        return
    for dirpath, _dirs, names in os.walk(directory, topdown=False):
        for n in names:
            if n.endswith(suffix):
                os.remove(os.path.join(dirpath, n))
        if dirpath != directory and not os.listdir(dirpath):
            os.rmdir(dirpath)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="fmgtrees.treegen", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--treegen", default=TREEGEN, help="the TreeGenerator repo")
    ap.add_argument("--out", default=RES, help="src/main/resources")
    ap.add_argument("--registry", default=os.path.join(HERE, "..", "..", "docs", "conquest",
                                                       "conquest_blocks_1.20.1.txt"))
    ap.add_argument("--no-nbt", action="store_true", help="only rewrite the feature JSON")
    args = ap.parse_args(argv)

    with open(os.path.join(args.treegen, "out", "index.json"), encoding="utf-8") as fh:
        index = json.load(fh)["schematics"]
    with open(os.path.join(args.treegen, "biome_mix.toml"), "rb") as fh:
        mix = tomllib.load(fh)
    defaults = mix.pop("defaults")

    # mix keys -> our biome ids, by display name
    by_name = {b["name"]: b["id"] for b in BIOMES}
    biome_of = {}
    for key, spec in mix.items():
        if key in COAST_MIXES:
            biome_of[key] = key
        elif spec.get("name") in by_name:
            biome_of[key] = by_name[spec["name"]]
        else:
            print("  problem: mix [%s] '%s' matches no biome" % (key, spec.get("name")))
            return 1

    entries = kept([e for e in index if e["setting"] == "open"])
    presets = {}
    for e in entries:
        presets.setdefault(e["preset"], []).append(e)
    missing = sorted({p for spec in mix.values() for layer in LAYERS
                      for p in spec.get(layer, {}) if p not in presets})
    if missing:
        print("  problem: the mix names presets with no schematics:", ", ".join(missing))
        return 1

    data = os.path.join(args.out, "data", NS)
    nbt_dir = os.path.join(data, "structures", "treegen")
    cf_dir = os.path.join(data, "worldgen", "configured_feature", "treegen")
    pf_dir = os.path.join(data, "worldgen", "placed_feature", "treegen")

    # 1. schematics -> structure NBT
    states = set()
    if not args.no_nbt:
        clear(nbt_dir, ".nbt")
    written = blocks = 0
    for e in entries:
        if args.no_nbt:
            lo = e["bounds_min"]
            e["_anchor"] = [-lo[0], -lo[1], -lo[2]]
            continue
        got = convert(e, args.treegen, nbt_dir)
        if got is None:
            continue
        e["_anchor"], n, names = got
        states.update(names)
        written += 1
        blocks += n
    if not args.no_nbt:
        # every block id the trees use must exist in Conquest 1.20.1 or vanilla
        known = set()
        if os.path.exists(args.registry):
            with open(args.registry, encoding="utf-8") as fh:
                known = {line.strip() for line in fh if line.strip()}
        unknown = sorted(b for b in states if b.startswith("conquest:") and known and b not in known)
        if unknown:
            print("  problem: block ids not in Conquest 1.20.1:", ", ".join(unknown))
            return 1

    # 2. one feature per preset
    clear(cf_dir, ".json")
    clear(pf_dir, ".json")
    for preset, objs in sorted(presets.items()):
        objs = [o for o in objs if "_anchor" in o]
        if objs:
            dump(os.path.join(cf_dir, "preset", preset + ".json"), preset_feature(preset, objs))

    # 3. one configured + placed feature per biome and layer
    report = []
    for key, spec in mix.items():
        bid = biome_of[key]
        hosts = COAST_MIXES.get(key)
        for layer in LAYERS:
            configured, placed = mix_feature(key, bid, layer, spec, defaults, presets, hosts)
            if configured is None:
                continue
            dump(os.path.join(cf_dir, bid, layer + ".json"), configured)
            dump(os.path.join(pf_dir, bid, layer + ".json"), placed)
            report.append((bid, layer, spec.get(layer + "_per_chunk"),
                           len(configured["config"]["entries"]), configured["config"]["spacing"]))

    if not args.no_nbt:
        print("converted %d schematics (%d blocks) into %s" % (written, blocks, nbt_dir))
    print("wrote %d preset features and %d biome-layer features" % (len(presets), len(report)))
    for bid, layer, n, k, sp in report:
        print("   %-26s %-7s %5s per chunk, %2d presets, spacing %.2f" % (bid, layer, n, k, sp))
    print("next: python -m fmgtrees.make_datapack  (puts them into the biomes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
