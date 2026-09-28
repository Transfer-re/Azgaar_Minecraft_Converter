"""
Emit the Conquest variant's runtime files from the design sources.

    python -m fmgtrees.make_datapack [--registry <ids.txt>] [--mc-jar <1.21.4 jar>]

Writes, under src/main/resources:

    data/fantasymapgenerator/worldgen/biome/conquest/<id>.json
        One biome per ground palette, tinted from the palette. No vegetation
        yet (trees and ground cover come later), so the feature lists are
        vanilla plains' underground content only -- ores, geodes, dungeons,
        springs, glow lichen, freezing -- in plains' own order, so the
        feature-order check across biomes stays happy.

    fantasymapgenerator/surface/conquest.json
        The palettes the chunk generator reads to build the layered surface:
        materials (layer + full block), every palette, and which biome id
        uses which palette.

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
MC_JAR = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "fabric-loom",
                      "1.21.4", "minecraft-common.jar")

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
                mob("horse", 1, 2, 6), mob("donkey", 1, 1, 1), mob("armadillo", 10, 2, 3)],
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


# freeze_top_layer also snows on mountains in warm biomes (vanilla temperature
# falls with height), with the same 1-in-8 speckle, so only the glacier keeps it.
FREEZE = "minecraft:freeze_top_layer"
KEEPS_FREEZE = {"glacier"}


def biome_json(bid, features):
    p = PALETTES[bid]
    if bid not in KEEPS_FREEZE:
        features = [[f for f in step if f != FREEZE] for step in features]
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
            "music_volume": 1.0,
        },
        "spawners": {
            "ambient": [mob("bat", 10, 8, 8)], "axolotls": [],
            "creature": CREATURES[spawns], "misc": [], "monster": MONSTERS,
            "underground_water_creature": [mob("glow_squid", 10, 4, 6)],
            "water_ambient": [], "water_creature": [],
        },
        "spawn_costs": {},
        "carvers": ["minecraft:cave", "minecraft:cave_extra_underground", "minecraft:canyon"],
        "features": features,
    }


def surface_json():
    used = []
    for p in PALETTES.values():
        for m in materials_used(p):
            if m not in used:
                used.append(m)
    palettes = {}
    for pid, p in PALETTES.items():
        q = {k: v for k, v in p.items() if k != "notes"}
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
    for b in BIOMES:
        if b["id"] not in PALETTES:
            bad.append("biome %s has no palette" % b["id"])
        if b["id"] not in CLIMATE:
            bad.append("biome %s has no climate" % b["id"])
    return bad


def main(argv=None):
    ap = argparse.ArgumentParser(prog="fmgtrees.make_datapack")
    ap.add_argument("--registry", default=os.path.join(HERE, "..", "..", "docs", "conquest",
                                                       "conquest_blocks_1.20.1.txt"))
    ap.add_argument("--mc-jar", default=MC_JAR)
    ap.add_argument("--out", default=RES)
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
    bdir = os.path.join(args.out, "data", NS, "worldgen", "biome", "conquest")
    os.makedirs(bdir, exist_ok=True)
    for b in BIOMES:
        with open(os.path.join(bdir, b["id"] + ".json"), "w", encoding="utf-8", newline="\n") as fh:
            json.dump(biome_json(b["id"], features), fh, indent=2)
            fh.write("\n")

    sdir = os.path.join(args.out, NS, "surface")
    os.makedirs(sdir, exist_ok=True)
    with open(os.path.join(sdir, "conquest.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(surface, fh, indent=1)
        fh.write("\n")

    print("wrote %d biomes to %s" % (len(BIOMES), bdir))
    print("wrote surface palettes (%d palettes, %d materials, %d biome ids) to %s"
          % (len(surface["palettes"]), len(surface["materials"]), len(surface["biomes"]), sdir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
