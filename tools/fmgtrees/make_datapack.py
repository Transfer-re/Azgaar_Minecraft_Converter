"""
Emit the Conquest variant's runtime files from the design sources.

    python -m fmgtrees.make_datapack [--registry <ids.txt>] [--mc-jar <1.21.4 jar>]

Writes, under src/main/resources:

    data/fantasymapgenerator/worldgen/biome/conquest/<id>.json
        One biome per ground palette, tinted from the palette. The feature
        lists are vanilla plains' underground content -- ores, geodes,
        dungeons, springs, glow lichen, freezing -- in plains' own order, so
        the feature-order check across biomes stays happy, plus the biome's
        trees: the placed features `treegen.py` wrote under
        placed_feature/treegen/<biome>/, appended to vegetal_decoration in
        layer order (trees, dead wood, logs, shrubs), then any shoreline
        overlay that names the biome as a host. Each biome's tree features
        are its own, and the shared overlays come last in every host, so
        they add no ordering conflicts. Ground cover is placed by the
        generator, not by features.

    fantasymapgenerator/surface/conquest.json
        The palettes the chunk generator reads to build the layered surface:
        materials (layer + full block), every palette with its ground cover
        (plants, props and patches from make_biomes' groundCover / props),
        and which biome id uses which palette.

and under src/client/resources:

    resourcepacks/conquest_sky/
        A built-in resource pack: a Polytone biome id mapper and dimension
        effects that give FMG's dimension types Conquest's sky and fog
        colormaps (see SKY_ANALOG).

Biome JSON references only vanilla ids, so the biomes load (unused) in any
world, with or without Conquest installed. Conquest block ids live only in the
surface file, which is read by the generator when a Conquest world runs.
"""

import argparse
import colorsys
import json
import os
import sys
import zipfile

from .make_biomes import BIOMES
from .palettes import MATERIALS, PALETTES, SEA_BIOMES, materials_used

NS = "fantasymapgenerator"
BIOME_PREFIX = NS + ":conquest/"
HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main", "resources"))
# This branch targets Minecraft 1.20.1 (Conquest Reforged's version): feature
# lists come from its jar and the biome JSON uses its format.
MC_JAR = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "fabric-loom",
                      "1.20.1", "minecraft-common.jar")

# temperature, downfall, precipitation, spawn set. Temperatures stay >= 0.2
# outside the glacier on purpose: below 0.15 snowfall settles only on columns
# with a full top face, which on layered ground is a random 1 in 8 of them.
# Cold biomes carry their snow in the palette instead.
CLIMATE = {
    "oak_woodland": (0.7, 0.8, True, "woodland"),
    "beech_forest": (0.7, 0.8, True, "woodland"),
    "mixed_broadleaf_forest": (0.7, 0.8, True, "woodland"),
    "autumn_broadleaf_forest": (0.6, 0.8, True, "woodland"),
    "aspen_grove": (0.6, 0.7, True, "woodland"),
    "birch_wood": (0.6, 0.6, True, "woodland"),
    "mixed_conifer_broadleaf": (0.45, 0.8, True, "taiga"),
    "spruce_taiga": (0.3, 0.8, True, "taiga"),
    "old_growth_spruce": (0.3, 0.8, True, "taiga"),
    "pine_taiga": (0.35, 0.6, True, "taiga"),
    "larch_taiga": (0.25, 0.7, True, "taiga"),
    "autumn_larch_taiga": (0.25, 0.7, True, "taiga"),
    "dead_taiga": (0.35, 0.4, True, "sparse"),
    "mossy_conifer_forest": (0.5, 0.95, True, "taiga"),
    "meadow": (0.75, 0.6, True, "pastoral"),
    "moorland": (0.45, 0.8, True, "pastoral"),
    "downland": (0.7, 0.5, True, "pastoral"),
    "shrub_tundra": (0.2, 0.5, True, "cold"),
    "lichen_tundra": (0.2, 0.4, True, "cold"),
    "alpine_scree": (0.2, 0.4, True, "cold"),
    "glacier": (-0.7, 0.5, True, "none"),
    "sphagnum_bog": (0.45, 0.9, True, "wet"),
    "fen_reedbed": (0.6, 0.9, True, "wet"),
    "willow_carr": (0.65, 0.9, True, "wet"),
    "cold_steppe": (0.4, 0.2, True, "arid"),
    "hot_desert": (2.0, 0.0, False, "arid"),
    "savanna": (1.6, 0.1, False, "savanna"),
    "tropical_seasonal_forest": (1.1, 0.6, True, "jungle"),
    "tropical_rainforest": (0.95, 0.95, True, "jungle"),
}

WATER_FOG = {"#4a5f4c": 0x1c2a1f}       # bog water is murky

# Conquest Reforged's sky and fog (its rp_crrp pack, Polytone textures
# polytone:overworld_sky / _fog) are colormaps with one column per vanilla
# biome, in the order of its biome_mapper.json. They target the vanilla
# overworld dimension type only, so FMG ships a pack that points its own
# dimension types at the same textures, each Conquest biome borrowing the
# column of the vanilla biome below.
CONQUEST_SKY_COLUMNS = [
    "the_void", "plains", "sunflower_plains", "snowy_plains", "ice_spikes", "desert", "swamp",
    "mangrove_swamp", "forest", "flower_forest", "birch_forest", "dark_forest",
    "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga", "taiga",
    "snowy_taiga", "savanna", "savanna_plateau", "windswept_hills", "windswept_gravelly_hills",
    "windswept_forest", "windswept_savanna", "jungle", "sparse_jungle", "bamboo_jungle",
    "badlands", "eroded_badlands", "wooded_badlands", "meadow", "cherry_grove", "grove",
    "snowy_slopes", "frozen_peaks", "jagged_peaks", "stony_peaks", "river", "frozen_river",
    "beach", "snowy_beach", "stony_shore", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean",
    "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean",
    "mushroom_fields", "dripstone_caves", "lush_caves", "deep_dark", "nether_wastes",
    "warped_forest", "crimson_forest", "soul_sand_valley", "basalt_deltas", "the_end",
    "end_highlands", "end_midlands", "small_end_islands", "end_barrens",
]
SKY_ANALOG = {
    "oak_woodland": "forest",
    "beech_forest": "forest",
    "mixed_broadleaf_forest": "forest",
    "autumn_broadleaf_forest": "forest",
    "aspen_grove": "birch_forest",
    "birch_wood": "birch_forest",
    "mixed_conifer_broadleaf": "forest",
    "spruce_taiga": "taiga",
    "old_growth_spruce": "old_growth_spruce_taiga",
    "pine_taiga": "old_growth_pine_taiga",
    "larch_taiga": "taiga",
    "autumn_larch_taiga": "taiga",
    "dead_taiga": "taiga",
    "mossy_conifer_forest": "old_growth_spruce_taiga",
    "meadow": "meadow",
    "moorland": "windswept_hills",
    "downland": "plains",
    "shrub_tundra": "snowy_plains",
    "lichen_tundra": "snowy_plains",
    "alpine_scree": "stony_peaks",
    "glacier": "frozen_peaks",
    "sphagnum_bog": "swamp",
    "fen_reedbed": "swamp",
    "willow_carr": "swamp",
    "cold_steppe": "plains",
    "hot_desert": "desert",
    "savanna": "savanna",
    "tropical_seasonal_forest": "sparse_jungle",
    "tropical_rainforest": "jungle",
}
CLIENT_RES = os.path.normpath(os.path.join(HERE, "..", "..", "src", "client", "resources"))
SKY_PACK = "conquest_sky"   # registered by FantasyMapGeneratorClient when Conquest is loaded


def mob(t, weight, lo, hi):
    return {"type": "minecraft:" + t, "weight": weight, "minCount": lo, "maxCount": hi}


MONSTERS = [mob("spider", 100, 4, 4), mob("zombie", 95, 4, 4), mob("zombie_villager", 5, 1, 1),
            mob("skeleton", 100, 4, 4), mob("creeper", 100, 4, 4), mob("slime", 100, 4, 4),
            mob("enderman", 10, 1, 4), mob("witch", 5, 1, 1)]
CREATURES = {
    "pastoral": [mob("sheep", 12, 4, 4), mob("pig", 10, 4, 4), mob("chicken", 10, 4, 4),
                 mob("cow", 8, 4, 4), mob("horse", 5, 2, 6), mob("donkey", 1, 1, 3)],
    "woodland": [mob("sheep", 12, 4, 4), mob("pig", 10, 4, 4), mob("chicken", 10, 4, 4),
                 mob("cow", 8, 4, 4), mob("wolf", 5, 4, 4), mob("rabbit", 4, 2, 3)],
    "taiga": [mob("sheep", 12, 4, 4), mob("pig", 10, 4, 4), mob("chicken", 10, 4, 4),
              mob("cow", 8, 4, 4), mob("wolf", 8, 4, 4), mob("rabbit", 4, 2, 3),
              mob("fox", 8, 2, 4)],
    "sparse": [mob("rabbit", 6, 2, 3), mob("fox", 6, 2, 4), mob("wolf", 3, 2, 4)],
    "cold": [mob("rabbit", 10, 2, 3), mob("fox", 4, 2, 4), mob("goat", 3, 1, 3)],
    "wet": [mob("sheep", 8, 4, 4), mob("chicken", 8, 4, 4), mob("cow", 6, 4, 4),
            mob("frog", 10, 2, 5)],
    "arid": [mob("rabbit", 4, 2, 3), mob("camel", 1, 1, 1)],
    "savanna": [mob("sheep", 12, 4, 4), mob("chicken", 10, 4, 4), mob("cow", 8, 4, 4),
                mob("horse", 1, 2, 6), mob("donkey", 1, 1, 1)],
    "jungle": [mob("chicken", 10, 4, 4), mob("parrot", 40, 1, 2), mob("ocelot", 2, 1, 3),
               mob("pig", 10, 4, 4)],
    "none": [],
}

# Plains' features, minus everything that would draw on top of the palette:
# vegetation, surface lava lakes, and the underwater sand/clay/gravel disks and
# magma that would overwrite the palette's bed.
DROP = {"minecraft:lake_lava_surface", "minecraft:disk_sand", "minecraft:disk_clay",
        "minecraft:disk_gravel", "minecraft:underwater_magma",
        "minecraft:patch_tall_grass_2", "minecraft:trees_plains", "minecraft:flower_plains",
        "minecraft:patch_grass_plain", "minecraft:brown_mushroom_normal",
        "minecraft:red_mushroom_normal", "minecraft:patch_sugar_cane",
        "minecraft:patch_pumpkin"}


# ---- ground cover ------------------------------------------------------------

# How each groundCover / props block goes into the world. Read off Conquest's
# block classes (Conquest Reforged 1.20.1):
#   plant  one block on top of the layer. Bush-family plants, berry bushes,
#          ground leaves and rocks carry their own `layers`, set to the layer
#          under them so the model sinks onto it; lichens and ground moss get
#          Conquest's offset from the block below at render time.
#   tall   two blocks, half=lower/upper (large_fern)
#   sunk   replaces the layer block. For clumps with no offset at all
#          (hawthorn_bush is a leaves block): sunk into the ground reads fine,
#          floating over a part layer does not.
#   loose  no offset (cones, tumbleweed): only on a full layer, or in place of
#          a nearly empty one
#   patch  a full block: it swaps the ground material in clumps instead,
#          through its _layer / _slab form (a material in palettes.py)
#   skip   hangs from trees or floats on water -- a later step places those
KIND = {
    "large_fern": "tall",
    "hawthorn_bush": "sunk",
    "pine_cones": "loose", "spruce_cones": "loose", "tumbleweed": "loose",
    "hanging_moss": "skip", "moss_vines": "skip", "duckweed": "skip",
}
# patch blocks -> material name (the same name everywhere, as it happens)
PATCH = {n: n for n in (
    "taiga_grass", "fir_forest_floor", "mossy_fir_forest_floor", "overgrown_fir_forest_floor",
    "smooth_pebbles", "brown_sphagnum_moss_block", "light_green_sphagnum_moss_block",
    "burnt_soil", "dirty_glacier_ice", "snow_covered_icy_limestone",
    "light_limestone_boulders", "mossy_light_limestone_boulders")}
# boulder patches stand this many eighths proud of the ground around them
RAISE = {"light_limestone_boulders": 3, "mossy_light_limestone_boulders": 3}

# Per biome: plants (share of columns with a plant, on turf or forest floor --
# other ground takes fewer, ice none), props (share with a rock, cone or
# mushroom; rock and scree take more) and patches (share of the ground under
# patch clumps, when the biome lists patch blocks). Meadow and fen are the
# thickest; forests leave room for the trees; tundra and scree thin out.
GROUND = {
    "oak_woodland": (0.50, 0.010, 0.0),
    "beech_forest": (0.45, 0.008, 0.0),
    "mixed_broadleaf_forest": (0.52, 0.008, 0.0),
    "autumn_broadleaf_forest": (0.55, 0.008, 0.0),
    "birch_wood": (0.55, 0.010, 0.0),
    "aspen_grove": (0.60, 0.006, 0.0),
    "mixed_conifer_broadleaf": (0.50, 0.010, 0.14),
    "spruce_taiga": (0.45, 0.018, 0.12),
    "old_growth_spruce": (0.50, 0.010, 0.05),
    "pine_taiga": (0.45, 0.020, 0.06),
    "larch_taiga": (0.45, 0.020, 0.0),
    "autumn_larch_taiga": (0.48, 0.015, 0.0),
    "dead_taiga": (0.35, 0.0, 0.14),
    "mossy_conifer_forest": (0.60, 0.0, 0.22),
    "meadow": (0.78, 0.003, 0.0),
    "moorland": (0.65, 0.015, 0.0),
    "downland": (0.55, 0.020, 0.05),
    "shrub_tundra": (0.45, 0.030, 0.0),
    "lichen_tundra": (0.38, 0.040, 0.06),
    "alpine_scree": (0.14, 0.080, 0.08),
    "glacier": (0.0, 0.0, 0.14),
    "sphagnum_bog": (0.50, 0.0, 0.20),
    "fen_reedbed": (0.65, 0.0, 0.0),
    "cold_steppe": (0.28, 0.030, 0.06),
    "willow_carr": (0.60, 0.0, 0.0),
    "hot_desert": (0.06, 0.0, 0.0),
    "savanna": (0.35, 0.0, 0.0),
    "tropical_seasonal_forest": (0.55, 0.0, 0.0),
    "tropical_rainforest": (0.60, 0.0, 0.0),
}


# Scales every biome's plant and prop density above. The user, 2026-10-05:
# ground foliage and decoration "overall is a little much maybe turn that down
# by 30%". Patches are ground material, not decoration, and keep theirs.
COVER_SCALE = 0.7


def cover_json(b):
    """The palette's `cover` section from a biome's groundCover and props."""
    plants, props, patches = GROUND[b["id"]]
    plants, props = round(plants * COVER_SCALE, 4), round(props * COVER_SCALE, 4)
    out = {"plantDensity": plants, "plants": [], "propDensity": props, "props": [],
           "patchDensity": patches, "patches": []}
    for src, dst in (("groundCover", "plants"), ("props", "props")):
        for block, weight in b[src].items():
            name = block.split(":", 1)[1]
            if name in PATCH:
                out["patches"].append({"material": PATCH[name], "weight": weight,
                                       "raise": RAISE.get(name, 0)})
                continue
            kind = KIND.get(name, "plant")
            if kind != "skip":
                out[dst].append({"block": block, "weight": weight, "kind": kind})
    return out


def hexint(h):
    return int(h.lstrip("#"), 16)


def sky_color(temperature):
    """Vanilla's OverworldBiomeCreator.getSkyColor."""
    f = max(-1.0, min(1.0, temperature / 3.0))
    r, g, b = colorsys.hsv_to_rgb((0.62222224 - f * 0.05) % 1.0, 0.5 + f * 0.1, 1.0)
    return (int(r * 255) << 16) | (int(g * 255) << 8) | int(b * 255)


def plains_features(jar):
    with zipfile.ZipFile(jar) as z:
        plains = json.loads(z.read("data/minecraft/worldgen/biome/plains.json"))
    return [[f for f in step if f not in DROP] for step in plains["features"]]


# ---- trees -------------------------------------------------------------------

TREE_LAYERS = ("trees", "dead", "logs", "shrubs")
VEGETAL_DECORATION = 9


def tree_features(res):
    """biome id -> placed feature ids, from what `treegen.py` wrote."""
    pf = os.path.join(res, "data", NS, "worldgen", "placed_feature", "treegen")
    cf = os.path.join(res, "data", NS, "worldgen", "configured_feature", "treegen")
    out, overlays = {}, []
    if not os.path.isdir(pf):
        return out
    for d in sorted(os.listdir(pf)):
        ids = ["%s:treegen/%s/%s" % (NS, d, layer) for layer in TREE_LAYERS
               if os.path.exists(os.path.join(pf, d, layer + ".json"))]
        hosts = None
        for layer in TREE_LAYERS:
            path = os.path.join(cf, d, layer + ".json")
            if os.path.exists(path):
                with open(path, encoding="utf-8") as fh:
                    hosts = json.load(fh)["config"].get("hosts")
                break
        if hosts:
            overlays.append((hosts, ids))
        else:
            out.setdefault(d, []).extend(ids)
    for hosts, ids in overlays:
        for h in hosts:
            out.setdefault(h.split("/")[-1], []).extend(ids)
    return out


# freeze_top_layer also snows on mountains in warm biomes (vanilla temperature
# falls with height), with the same 1-in-8 speckle, so only the glacier keeps it.
FREEZE = "minecraft:freeze_top_layer"
KEEPS_FREEZE = {"glacier"}


def biome_json(bid, features, trees=()):
    p = PALETTES[bid]
    if bid not in KEEPS_FREEZE:
        features = [[f for f in step if f != FREEZE] for step in features]
    if trees:
        features = [list(step) for step in features]
        features[VEGETAL_DECORATION].extend(trees)
    temp, down, precip, spawns = CLIMATE[bid]
    water = p["tints"]["water"]
    return {
        "has_precipitation": precip,
        "temperature": temp,
        "downfall": down,
        "effects": {
            "fog_color": 12638463,
            "sky_color": sky_color(temp),
            "water_color": hexint(water),
            "water_fog_color": WATER_FOG.get(water, 329011),
            "grass_color": hexint(p["tints"]["grass"]),
            "foliage_color": hexint(p["tints"]["foliage"]),
            "mood_sound": {"block_search_extent": 8, "offset": 2.0,
                           "sound": "minecraft:ambient.cave", "tick_delay": 6000},
        },
        "spawners": {
            "ambient": [mob("bat", 10, 8, 8)], "axolotls": [],
            "creature": CREATURES[spawns], "misc": [], "monster": MONSTERS,
            "underground_water_creature": [mob("glow_squid", 10, 4, 6)],
            "water_ambient": [], "water_creature": [],
        },
        "spawn_costs": {},
        "carvers": {"air": ["minecraft:cave", "minecraft:cave_extra_underground", "minecraft:canyon"]},
        "features": features,
    }


def surface_json():
    used = []
    for p in PALETTES.values():
        for m in materials_used(p):
            if m not in used:
                used.append(m)
    covers = {b["id"]: cover_json(b) for b in BIOMES if b["id"] in PALETTES}
    for c in covers.values():
        for patch in c["patches"]:
            if patch["material"] not in used:
                used.append(patch["material"])
    palettes = {}
    for pid, p in PALETTES.items():
        q = {k: v for k, v in p.items() if k != "notes"}
        if pid in covers:
            q["cover"] = covers[pid]
        palettes[pid] = q
    biomes = {BIOME_PREFIX + b["id"]: b["id"] for b in BIOMES if b["id"] in PALETTES}
    biomes.update(SEA_BIOMES)
    return {
        "_comment": "Generated by tools/fmgtrees/make_datapack.py from palettes.py -- edit that, not this.",
        "id": "conquest",
        "fallback": "meadow",
        "materials": {m: {k: v for k, v in MATERIALS[m].items() if k != "note"} for m in used},
        "palettes": palettes,
        "biomes": biomes,
    }


def sky_pack_files():
    """Published path -> JSON for the Conquest sky pack."""
    mapper = {"minecraft:" + b: i for i, b in enumerate(CONQUEST_SKY_COLUMNS)}
    for b in BIOMES:
        mapper[BIOME_PREFIX + b["id"]] = CONQUEST_SKY_COLUMNS.index(SKY_ANALOG[b["id"]])
    mapper["texture_size"] = 256

    def colormap(texture):
        return {"texture_path": "polytone:" + texture, "x_axis": "biome_id", "y_axis": "y_level",
                "biome_id_mapper": NS + ":conquest"}

    polytone = "assets/%s/polytone/" % NS
    return {
        "pack.mcmeta": {"pack": {"pack_format": 15,
                                 "description": "Conquest Reforged sky and fog in Fantasy Map worlds"}},
        polytone + "biome_id_mappers/conquest.json": mapper,
        # every FMG height tier: fmg_overworld_tall and fmg_overworld_h<top>
        polytone + "dimension_effects/fmg_overworld.json": {
            "targets": [NS + ":fmg_overworld_.*"],
            "sky_colormap": colormap("overworld_sky"),
            "fog_colormap": colormap("overworld_fog"),
        },
    }


def write_sky_pack(client_res=CLIENT_RES):
    root = os.path.join(client_res, "resourcepacks", SKY_PACK)
    files = sky_pack_files()
    for path, obj in files.items():
        out = os.path.join(root, *path.split("/"))
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(obj, fh, indent=2)
            fh.write("\n")
    return root, len(files)


def check(known, surface):
    bad = []
    for m, spec in surface["materials"].items():
        for i in (spec["layer"], spec["full"]):
            if known is not None and i.startswith("conquest:") and i not in known:
                bad.append("%s: unknown %s" % (m, i))
    for pid, p in surface["palettes"].items():
        if known is not None and p["soil"].startswith("conquest:") and p["soil"] not in known:
            bad.append("%s: unknown soil %s" % (pid, p["soil"]))
        if p["stone"] not in surface["materials"]:
            bad.append("%s: stone %s is not a material" % (pid, p["stone"]))
        cover = p.get("cover", {})
        for entry in cover.get("plants", []) + cover.get("props", []):
            if known is not None and entry["block"] not in known:
                bad.append("%s: unknown cover block %s" % (pid, entry["block"]))
        for entry in cover.get("patches", []):
            if entry["material"] not in surface["materials"]:
                bad.append("%s: patch %s is not a material" % (pid, entry["material"]))
    for b in BIOMES:
        if b["id"] not in PALETTES:
            bad.append("biome %s has no palette" % b["id"])
        if b["id"] not in CLIMATE:
            bad.append("biome %s has no climate" % b["id"])
        if b["id"] not in GROUND:
            bad.append("biome %s has no ground-cover densities" % b["id"])
        if SKY_ANALOG.get(b["id"]) not in CONQUEST_SKY_COLUMNS:
            bad.append("biome %s has no sky column" % b["id"])
    return bad


def main(argv=None):
    ap = argparse.ArgumentParser(prog="fmgtrees.make_datapack")
    ap.add_argument("--registry", default=os.path.join(HERE, "..", "..", "docs", "conquest",
                                                       "conquest_blocks_1.20.1.txt"))
    ap.add_argument("--mc-jar", default=MC_JAR)
    ap.add_argument("--out", default=RES)
    ap.add_argument("--client-out", default=CLIENT_RES)
    args = ap.parse_args(argv)

    known = None
    if os.path.exists(args.registry):
        with open(args.registry, encoding="utf-8") as fh:
            known = {line.strip() for line in fh if line.strip()}

    surface = surface_json()
    problems = check(known, surface)
    if problems:
        for p in problems:
            print("  problem:", p)
        return 1

    features = plains_features(args.mc_jar)
    trees = tree_features(args.out)
    bdir = os.path.join(args.out, "data", NS, "worldgen", "biome", "conquest")
    os.makedirs(bdir, exist_ok=True)
    for b in BIOMES:
        with open(os.path.join(bdir, b["id"] + ".json"), "w", encoding="utf-8", newline="\n") as fh:
            json.dump(biome_json(b["id"], features, trees.get(b["id"], [])), fh, indent=2)
            fh.write("\n")

    sdir = os.path.join(args.out, NS, "surface")
    os.makedirs(sdir, exist_ok=True)
    with open(os.path.join(sdir, "conquest.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(surface, fh, indent=1)
        fh.write("\n")

    print("wrote %d biomes to %s (%d with trees, %d tree features)"
          % (len(BIOMES), bdir, sum(1 for b in BIOMES if trees.get(b["id"])),
             sum(len(trees.get(b["id"], [])) for b in BIOMES)))
    print("wrote surface palettes (%d palettes, %d materials, %d biome ids) to %s"
          % (len(surface["palettes"]), len(surface["materials"]), len(surface["biomes"]), sdir))
    root, n = write_sky_pack(args.client_out)
    print("wrote the Conquest sky pack (%d files) to %s" % (n, root))
    return 0


if __name__ == "__main__":
    sys.exit(main())
