"""
Emit the per-biome design files under docs/conquest/biomes/.

These are the editable source of truth for the Conquest content: one JSON per
biome saying which Azgaar category it comes from, what its surface is made of,
which tree groups grow in it and at what mix, and what covers the ground. A
later step turns them into worldgen datapack JSON; for now they are the thing
to read, argue with and hand-edit.

Tree groups are `<pack>/<species>/<colour>` keys straight out of the combined
`trees/index.json`, so every weight here is backed by real trees -- `qf/...` from
the hand-built Conquest pack (46 per group), `axiom/...` from the remapped Axiom
pack (3-20 per group). Sizes are weighted separately from species: a forest is
mostly m/l with a few xl and an xs/s understory, a moor is almost all xs/s.

QF trees are the better-looking half, so they stay dominant wherever they cover
the role. Axiom earns its place where QF has nothing: hemlock and douglas fir
for temperate rainforest, birch at all, and a much deeper bench of dead conifers
and stumps.

    python -m fmgtrees.make_biomes [--registry <ids.txt>] [--out <dir>]
"""

import argparse
import json
import os
import sys

NS = "conquest:"

# Size mixes, reused across biomes.
CANOPY = {"xs": 5, "s": 15, "m": 35, "l": 30, "xl": 15}
YOUNG = {"xs": 20, "s": 35, "m": 30, "l": 15, "xl": 0}
SCRUB = {"xs": 55, "s": 35, "m": 10, "l": 0, "xl": 0}
DWARF = {"xs": 80, "s": 20, "m": 0, "l": 0, "xl": 0}


def biome(bid, name, azgaar, notes, surface, trees=None, density=0.0,
          sizes=None, ground=None, props=None, status="ready",
          stumps=None, stump_density=0.0):
    return {
        "id": bid,
        "name": name,
        "azgaar": azgaar,
        "status": status,
        "notes": notes,
        "surface": surface,
        "trees": {
            "density": density,
            "sizes": sizes or CANOPY,
            # keys are `<pack>/<species>/<colour>` from trees/index.json
            "species": trees or {},
            # fallen/cut stumps scattered on the forest floor, placed like
            # trees but on their own much lower density
            "stumps": {"density": stump_density, "groups": stumps or {}},
        },
        "groundCover": ground or {},
        "props": props or {},
    }


def s(top, under):
    return {"top": top, "under": under}


BIOMES = [

    # ---- Temperate deciduous forest ---------------------------------------

    biome("oak_woodland", "Oak Woodland", ["Temperate deciduous forest"],
          "The default lowland wood. Oak-dominant but never pure -- maple and "
          "chestnut break up the canopy colour, aspen fills gaps and edges.",
          s(NS + "grass", NS + "unfertile_loamy_dirt"),
          {"qf/english_oak/green": 34, "qf/sugar_maple/green": 14,
           "qf/horse_chestnut/green": 11, "qf/quaking_aspen/green": 7,
           "axiom/english_oak/default": 12, "axiom/forest_oak/default": 10,
           "axiom/common_ash/default": 8, "axiom/common_maple/default": 4},
          density=0.55,
          stumps={"axiom/survival_stump/oak": 60, "axiom/survival_stump/1": 25,
                  "axiom/survival_stump/2": 15},
          stump_density=0.035,
          ground={NS + "oak_ground_leaves": 30, NS + "lush_grass": 25,
                  NS + "bracken": 15, NS + "hawthorn_bush": 8,
                  NS + "blackberry_bush": 7, NS + "thick_fern": 8,
                  NS + "wild_daisy": 4, NS + "lily_of_the_valley": 3},
          props={NS + "morel_mushrooms": 2, NS + "mudstone_rocks": 2}),

    biome("beech_forest", "Beech Forest", ["Temperate deciduous forest"],
          "Beech shades out its own understory, so this one is deliberately "
          "bare underfoot: deep leaf litter, little else. Oak and chestnut "
          "hold the edges.",
          s(NS + "grass", NS + "unfertile_loamy_dirt"),
          {"qf/european_beech/green": 60, "qf/english_oak/green": 25,
           "qf/horse_chestnut/green": 15},
          density=0.6,
          ground={NS + "beech_tree_ground_leaves": 55, NS + "lush_grass": 15,
                  NS + "harts_tongue_fern": 10, NS + "thick_fern": 8,
                  NS + "bracken": 7, NS + "morel_mushrooms": 5},
          props={NS + "moss_on_the_ground": 4}),

    biome("mixed_broadleaf_forest", "Mixed Broadleaf Forest",
          ["Temperate deciduous forest"],
          "An even five-way mix -- the one to use where a deciduous region is "
          "large enough that a single dominant species would read as flat.",
          s(NS + "grass", NS + "unfertile_loamy_dirt"),
          {"qf/english_oak/green": 17, "qf/european_beech/green": 18,
           "qf/horse_chestnut/green": 14, "qf/sugar_maple/green": 13,
           "qf/quaking_aspen/green": 7, "axiom/common_ash/default": 9,
           "axiom/silver_birch/default": 8, "axiom/english_oak/default": 6,
           "axiom/common_maple/2": 4, "axiom/white_poplar/default": 4},
          density=0.58,
          stumps={"axiom/survival_stump/oak": 40, "axiom/survival_stump/birch": 30,
                  "axiom/survival_stump/3": 30},
          stump_density=0.03,
          ground={NS + "lush_grass": 25, NS + "oak_ground_leaves": 15,
                  NS + "beech_tree_ground_leaves": 15, NS + "bracken": 12,
                  NS + "thick_fern": 10, NS + "hawthorn_bush": 7,
                  NS + "raspberry_bush": 6, NS + "red_clover_flowers": 6,
                  NS + "cornflower": 4}),

    biome("autumn_broadleaf_forest", "Autumnal Broadleaf Forest",
          ["Temperate deciduous forest"],
          "The same species in autumn colour. Worth keeping as its own biome "
          "rather than a variant: the four beech colours plus autumnal oak and "
          "maple mix into a genuinely varied canopy.",
          s(NS + "grass", NS + "unfertile_loamy_dirt"),
          {"qf/english_oak/autumnal": 22, "qf/european_beech/autumnal": 18,
           "qf/european_beech/bright_autumnal": 14,
           "qf/european_beech/faded_autumnal": 7, "qf/sugar_maple/autumnal": 15,
           "qf/horse_chestnut/autumnal": 6, "qf/quaking_aspen/autumnal": 4,
           "axiom/red_maple/default": 9, "axiom/yellow_maple/default": 7,
           "axiom/silver_maple/orange": 5, "axiom/paper_birch/yellow": 5,
           "axiom/quaking_aspen/yellow": 4, "axiom/bigtooth_aspen/yellow": 3},
          density=0.58,
          stumps={"axiom/survival_stump/oak": 50, "axiom/survival_stump/2": 50},
          stump_density=0.03,
          ground={NS + "autumnal_oak_ground_leaves": 22,
                  NS + "autumnal_beech_tree_ground_leaves": 20,
                  NS + "autumnal_maple_ground_leaves": 18,
                  NS + "dead_grass": 12, NS + "autumnal_bracken": 12,
                  NS + "dark_autumnal_bracken": 8,
                  NS + "brittlegill_mushrooms": 5, NS + "morel_mushrooms": 3}),

    biome("birch_wood", "Birch Wood", ["Temperate deciduous forest", "Taiga"],
          "New -- the QF pack has no birch at all, so this biome only became "
          "possible once the Axiom pack converted. Light, open and pale-trunked; "
          "sits naturally between broadleaf forest and taiga, and is the other "
          "good answer (with mixed_conifer_broadleaf) to a hard deciduous/taiga "
          "edge. A little aspen and pine keep it from reading as a plantation.",
          s(NS + "grass", NS + "unfertile_loamy_dirt"),
          {"axiom/silver_birch/default": 22, "axiom/silver_birch/light": 16,
           "axiom/survival_birch/1": 16, "axiom/survival_birch/2": 13,
           "axiom/paper_birch/default": 11, "axiom/paper_birch/yellow": 6,
           "qf/quaking_aspen/green": 10, "qf/scottish_pine/green": 6},
          density=0.52,
          ground={NS + "birch_ground_leaves": 26, NS + "lush_grass": 22,
                  NS + "wavy_hair_grass": 14, NS + "bracken": 12,
                  NS + "thick_fern": 9, NS + "bilberry_bush": 7,
                  NS + "rosebay_willowherb": 6, NS + "wild_daisy": 4},
          props={NS + "brittlegill_mushrooms": 4, NS + "granite_rocks": 3},
          stumps={"axiom/survival_stump/birch": 80, "axiom/survival_stump/1": 20},
          stump_density=0.05),

    biome("aspen_grove", "Aspen Grove", ["Temperate deciduous forest"],
          "Aspen is a pioneer -- good on slopes, river terraces and old "
          "clearings. A few autumnal aspen mixed in read as individual clones "
          "turning early, which is how real aspen stands look.",
          s(NS + "grass", NS + "unfertile_loamy_dirt"),
          {"qf/quaking_aspen/green": 44, "qf/quaking_aspen/autumnal": 8,
           "qf/english_oak/green": 10, "axiom/quaking_aspen/default": 14,
           "axiom/bigtooth_aspen/default": 8, "axiom/white_poplar/default": 12,
           "axiom/quaking_aspen/yellow": 4},
          density=0.5,
          ground={NS + "lush_grass": 35, NS + "aspen_ground_leaves": 20,
                  NS + "bracken": 15, NS + "wavy_hair_grass": 10,
                  NS + "raspberry_bush": 8, NS + "rosebay_willowherb": 7,
                  NS + "wild_daisy": 5}),

    # ---- Transition --------------------------------------------------------

    biome("mixed_conifer_broadleaf", "Mixed Conifer-Broadleaf Forest",
          ["Temperate deciduous forest", "Taiga"],
          "The transition band. Put this wherever a deciduous region borders a "
          "taiga one -- it is the single biome that stops that edge looking "
          "like a cut line.",
          s(NS + "grass", NS + "unfertile_loamy_dirt"),
          {"qf/norway_spruce/dark": 18, "qf/scottish_pine/green": 13,
           "qf/quaking_aspen/green": 13, "qf/european_beech/green": 12,
           "qf/english_oak/green": 10, "axiom/silver_birch/default": 11,
           "axiom/silver_birch/light": 7, "axiom/red_spruce/default": 8,
           "axiom/jack_pine/default": 8},
          density=0.56,
          stumps={"axiom/survival_stump/spruce": 40, "axiom/survival_stump/birch": 35,
                  "axiom/survival_stump/oak": 25},
          stump_density=0.035,
          ground={NS + "lush_grass": 22, NS + "taiga_grass": 20,
                  NS + "bracken": 16, NS + "bilberry_bush": 12,
                  NS + "thick_fern": 10, NS + "oak_ground_leaves": 10,
                  NS + "wavy_hair_grass": 10}),

    # ---- Taiga -------------------------------------------------------------

    biome("spruce_taiga", "Spruce Taiga", ["Taiga"],
          "Dark and light spruce together give the canopy depth; a few dead "
          "spruce and some pine keep it from looking planted.",
          s(NS + "taiga_grass", NS + "unfertile_loamy_dirt"),
          {"qf/norway_spruce/dark": 34, "qf/norway_spruce/light": 20,
           "qf/scottish_pine/green": 8, "qf/norway_spruce/dead": 4,
           "axiom/red_spruce/default": 13, "axiom/balsam_fir/default": 11,
           "axiom/balsam_fir/light": 10},
          density=0.68,
          stumps={"axiom/survival_stump/spruce": 70, "axiom/survival_stump/3": 30},
          stump_density=0.04,
          ground={NS + "dark_spruce_ground_needles": 24, NS + "taiga_grass": 22,
                  NS + "bilberry_bush": 14, NS + "fir_forest_floor": 10,
                  NS + "lingonberry_bush": 9, NS + "reindeer_lichen": 8,
                  NS + "thick_fern": 7, NS + "brittlegill_mushrooms": 3,
                  NS + "melancholy_thistle": 3},
          props={NS + "granite_rocks": 4, NS + "slate_rocks": 2,
                 NS + "spruce_cones": 3}),

    biome("old_growth_spruce", "Old-Growth Spruce Forest", ["Taiga"],
          "Same species, biased hard to xl, with moss and hanging moss on "
          "everything. Use sparingly -- it reads best as a small region inside "
          "ordinary spruce taiga.",
          s(NS + "taiga_grass", NS + "unfertile_loamy_dirt"),
          {"qf/norway_spruce/dark": 44, "qf/norway_spruce/light": 17,
           "qf/norway_spruce/dead": 7, "axiom/red_spruce/default": 18,
           "axiom/balsam_fir/default": 14},
          density=0.75,
          sizes={"xs": 5, "s": 10, "m": 20, "l": 30, "xl": 35},
          ground={NS + "moss_on_the_ground": 30, NS + "taiga_grass": 20,
                  NS + "bilberry_bush": 15, NS + "thick_fern": 12,
                  NS + "harts_tongue_fern": 10, NS + "morel_mushrooms": 7,
                  NS + "brittlegill_mushrooms": 6},
          props={NS + "hanging_moss": 8, NS + "moss_vines": 5,
                 NS + "mossy_light_limestone_boulders": 3}),

    biome("pine_taiga", "Pine Taiga", ["Taiga"],
          "Scottish pine is the only single-colour species in the pack, so "
          "this biome leans on size spread and a spruce/larch minority instead "
          "of colour for its variety.",
          s(NS + "taiga_grass", NS + "dark_sandy_soil"),
          {"qf/scottish_pine/green": 34, "qf/norway_spruce/light": 12,
           "qf/european_larch/green": 9, "axiom/simple_pine/1": 8,
           "axiom/simple_pine/2": 8, "axiom/jack_pine/default": 9,
           "axiom/survival_pine/1": 7, "axiom/atlas_cedar/default": 7,
           "axiom/deodar_cedar/default": 6},
          density=0.5,
          stumps={"axiom/survival_stump/3": 55, "axiom/survival_stump/spruce": 45},
          stump_density=0.03,
          ground={NS + "pine_ground_needles": 26, NS + "taiga_grass": 20,
                  NS + "heather": 15, NS + "lingonberry_bush": 12,
                  NS + "reindeer_lichen": 11, NS + "wavy_hair_grass": 9,
                  NS + "bracken": 7},
          props={NS + "granite_rocks": 5, NS + "smooth_pebbles": 3,
                 NS + "pine_cones": 4}),

    biome("larch_taiga", "Larch Taiga", ["Taiga"],
          "Larch is a deciduous conifer -- lighter and more open than spruce. "
          "Good as the upper band of an elevation ladder below the treeline.",
          s(NS + "taiga_grass", NS + "unfertile_loamy_dirt"),
          {"qf/european_larch/green": 40, "qf/norway_spruce/dark": 12,
           "qf/scottish_pine/green": 7, "axiom/western_larch/default": 16,
           "axiom/tamarack/default": 14, "axiom/european_larch/default": 11},
          density=0.45,
          ground={NS + "larch_ground_needles": 24, NS + "taiga_grass": 22,
                  NS + "bilberry_bush": 15, NS + "reindeer_lichen": 13,
                  NS + "heather": 11, NS + "cotton_grass": 8,
                  NS + "alpine_sow_thistle": 4, NS + "melancholy_thistle": 3},
          props={NS + "slate_rocks": 6, NS + "granite_rocks": 4}),

    biome("autumn_larch_taiga", "Autumnal Larch Taiga", ["Taiga"],
          "Larch turning. The orange and yellow variants against dark spruce "
          "is the strongest single image in the whole pack -- give it real "
          "regions, not just edges.",
          s(NS + "taiga_grass", NS + "unfertile_loamy_dirt"),
          {"qf/european_larch/orange": 32, "qf/european_larch/yellow": 25,
           "qf/norway_spruce/dark": 14, "axiom/tamarack/yellow": 16,
           "axiom/european_larch/yellow": 13},
          density=0.45,
          ground={NS + "orange_autumnal_larch_ground_needles": 22,
                  NS + "yellow_autumnal_larch_ground_needles": 18,
                  NS + "dead_grass": 16, NS + "taiga_grass": 13,
                  NS + "bilberry_bush": 12, NS + "reindeer_lichen": 10,
                  NS + "autumnal_bracken": 9},
          props={NS + "slate_rocks": 6}),

    biome("dead_taiga", "Dead Taiga", ["Taiga"],
          "Burn scar or beetle kill. Mostly dead spruce with survivors; the "
          "burnt soil underneath does as much work as the trees.",
          s(NS + "dead_grass", NS + "burnt_soil"),
          {"qf/norway_spruce/dead": 30, "qf/norway_spruce/dark": 8,
           "qf/scottish_pine/green": 5, "axiom/dead_pine/3": 16,
           "axiom/dead_pine/1": 8, "axiom/dead_pine/2": 8,
           "axiom/survival_dead_pine/1": 10, "axiom/survival_dead_pine/2": 8,
           "axiom/survival_pine_dead/default": 7},
          density=0.35,
          stumps={"axiom/survival_stump/spruce": 50, "axiom/survival_stump/1": 25,
                  "axiom/survival_stump/2": 25},
          stump_density=0.06,
          ground={NS + "dead_grass": 30, NS + "dead_pine_ground_needles": 18,
                  NS + "dead_shrubs": 18, NS + "dead_bracken": 14,
                  NS + "rosebay_willowherb": 12, NS + "reindeer_lichen": 8},
          props={NS + "burnt_soil": 6}),

    # ---- Temperate rainforest ---------------------------------------------

    biome("mossy_conifer_forest", "Mossy Conifer Rainforest",
          ["Temperate rainforest"],
          "Wet, dark and mossy, and now built on the species it should be: "
          "western hemlock and douglas fir carry the canopy, with red spruce "
          "and balsam fir under them. This was the biome most obviously "
          "standing in for missing assets, and it no longer is.",
          s(NS + "lush_grass", NS + "unfertile_loamy_dirt"),
          {"axiom/western_hemlock/dark": 24, "axiom/western_hemlock/light": 16,
           "axiom/douglas_fir/default": 20, "axiom/red_spruce/default": 12,
           "axiom/balsam_fir/default": 8, "qf/norway_spruce/dark": 12,
           "qf/norway_spruce/dead": 4, "qf/european_larch/green": 4},
          density=0.8,
          sizes={"xs": 5, "s": 10, "m": 25, "l": 32, "xl": 28},
          ground={NS + "moss_on_the_ground": 26,
                  NS + "mossy_fir_forest_floor": 14,
                  NS + "overgrown_fir_forest_floor": 10, NS + "thick_fern": 14,
                  NS + "harts_tongue_fern": 11, NS + "large_fern": 9,
                  NS + "lush_grass": 6, NS + "brittlegill_mushrooms": 5,
                  NS + "morel_mushrooms": 5},
          props={NS + "hanging_moss": 12, NS + "moss_vines": 8,
                 NS + "mossy_light_limestone_boulders": 5},
          stumps={"axiom/survival_stump/spruce": 60, "axiom/survival_stump/3": 40},
          stump_density=0.07),

    # ---- Grassland ---------------------------------------------------------

    biome("meadow", "Meadow", ["Grassland"],
          "Open grass with isolated trees, never groups. Density is the whole "
          "point here -- push it above ~0.04 and it stops being grassland.",
          s(NS + "grass", NS + "unfertile_loamy_dirt"),
          {"qf/english_oak/green": 30, "qf/sugar_maple/green": 16,
           "qf/horse_chestnut/green": 13, "qf/quaking_aspen/green": 7,
           "axiom/survival_oak/default": 14, "axiom/common_ash/default": 10,
           "axiom/white_poplar/default": 10},
          density=0.03,
          sizes=YOUNG,
          ground={NS + "common_meadow_grass": 28, NS + "timothy_grass": 20,
                  NS + "kentucky_bluegrass": 14, NS + "red_clover_flowers": 10,
                  NS + "oxeye_daisy": 8, NS + "cornflower": 7,
                  NS + "wild_daisy": 7, NS + "poppy": 6}),

    biome("moorland", "Moorland", ["Grassland"],
          "Heather and moor grass with wind-bitten pine. Treeless over most of "
          "its area; the few trees should look like survivors.",
          s(NS + "purple_moor_grass", NS + "peat"),
          {"qf/scottish_pine/green": 42, "qf/quaking_aspen/green": 13,
           "qf/norway_spruce/dead": 10, "axiom/survival_pine/2": 15,
           "axiom/silver_birch/light": 12, "axiom/dead_pine/3": 8},
          density=0.04,
          sizes=SCRUB,
          ground={NS + "heather": 34, NS + "purple_moor_grass": 24,
                  NS + "cross_leaved_heath": 12, NS + "broom_bush": 8,
                  NS + "bilberry_bush": 8, NS + "cotton_grass": 7,
                  NS + "moorland_spotted_orchid": 4,
                  NS + "melancholy_thistle": 3},
          props={NS + "granite_rocks": 6, NS + "slate_rocks": 4}),

    biome("downland", "Chalk Downland", ["Grassland"],
          "Thin turf over chalk. Herb-rich, almost treeless, with limestone "
          "showing through -- a good contrast biome next to oak woodland.",
          s(NS + "grass_covered_limestone", NS + "chalky_soil"),
          {"qf/english_oak/green": 45, "qf/european_beech/green": 30,
           "axiom/survival_oak/default": 15, "axiom/common_ash/default": 10},
          density=0.02,
          sizes=SCRUB,
          ground={NS + "sweet_grass": 30, NS + "thyme": 18,
                  NS + "rock_rose": 14, NS + "wild_daisy": 12,
                  NS + "oxeye_daisy": 10, NS + "hawthorn_bush": 8,
                  NS + "black_knapweed": 8},
          props={NS + "limestone_rocks": 10, NS + "light_limestone_boulders": 4}),

    # ---- Tundra ------------------------------------------------------------

    biome("shrub_tundra", "Shrub Tundra", ["Tundra"],
          "Above the treeline but not bare. Trees only in xs/s and only "
          "occasionally -- these are krummholz, not forest.",
          s(NS + "taiga_grass", NS + "frozen_loamy_dirt"),
          {"qf/norway_spruce/dark": 36, "qf/quaking_aspen/green": 16,
           "qf/norway_spruce/dead": 13, "axiom/balsam_fir/light": 15,
           "axiom/survival_pine_dead/default": 10,
           "axiom/silver_birch/light": 10},
          density=0.03,
          sizes=DWARF,
          ground={NS + "bilberry_bush": 24, NS + "lingonberry_bush": 20,
                  NS + "bog_blueberry_bush": 16, NS + "reindeer_lichen": 16,
                  NS + "cotton_grass": 12, NS + "dead_grass": 12},
          props={NS + "granite_rocks": 8, NS + "slate_rocks": 5}),

    biome("lichen_tundra", "Lichen Tundra", ["Tundra"],
          "Treeless by design. Lichen and frozen ground with rock breaking "
          "through; the rung between shrub tundra and scree.",
          s(NS + "lichen_loamy_dirt", NS + "frozen_loamy_dirt"),
          density=0.0,
          ground={NS + "reindeer_lichen": 38, NS + "wolf_lichen": 18,
                  NS + "elegant_sunburst_lichen": 12, NS + "cotton_grass": 14,
                  NS + "dead_grass": 18},
          props={NS + "granite_rocks": 12, NS + "slate_rocks": 8,
                 NS + "smooth_pebbles": 5}),

    biome("alpine_scree", "Alpine Scree", ["Tundra", "Glacier"],
          "Bare rock and loose stone. The top rung of every elevation ladder, "
          "sitting under glacier.",
          s(NS + "snowy_granite", NS + "snowy_granite"),
          density=0.0,
          ground={NS + "alpine_sow_thistle": 20, NS + "reindeer_lichen": 25,
                  NS + "dead_grass": 20, NS + "cotton_grass": 15,
                  NS + "wolf_lichen": 20},
          props={NS + "granite_rocks": 20, NS + "slate_rocks": 14,
                 NS + "andesite_rocks": 10, NS + "smooth_pebbles": 8}),

    # ---- Glacier -----------------------------------------------------------

    biome("glacier", "Glacier", ["Glacier"],
          "Fully covered with no trees needed. Dirty ice at the margins does "
          "most of the visual work.",
          s(NS + "glacier_ice", NS + "dirty_glacier_ice"),
          density=0.0,
          ground={},
          props={NS + "dirty_glacier_ice": 20,
                 NS + "snow_covered_icy_limestone": 10}),

    # ---- Wetland -----------------------------------------------------------

    biome("sphagnum_bog", "Sphagnum Bog", ["Wetland"],
          "Peat and three colours of sphagnum. Stunted dead spruce and small "
          "larch only -- a bog that grows full-size trees looks wrong.",
          s(NS + "green_sphagnum_moss_block", NS + "peat"),
          {"qf/norway_spruce/dead": 34, "qf/european_larch/green": 18,
           "qf/norway_spruce/dark": 9, "axiom/survival_dead_pine/1": 14,
           "axiom/tamarack/default": 15, "axiom/survival_pine_dead/default": 10},
          density=0.05,
          sizes=DWARF,
          ground={NS + "green_sphagnum_moss": 24,
                  NS + "dark_green_sphagnum_moss": 16,
                  NS + "cotton_grass": 20, NS + "bog_blueberry_bush": 14,
                  NS + "heather": 10, NS + "dead_reeds": 10,
                  NS + "sedge": 6},
          props={NS + "brown_sphagnum_moss_block": 10,
                 NS + "light_green_sphagnum_moss_block": 10}),

    biome("fen_reedbed", "Fen and Reedbed", ["Wetland", "Marine"],
          "The wet edge: sedge, reed and cattail at the waterline. Treeless "
          "until willow and alder exist -- this is the biome that most wants "
          "them.",
          s(NS + "clay_rich_mud", NS + "dark_silt"),
          density=0.0,
          ground={NS + "greater_fen_sedge": 24, NS + "bottle_sedge": 18,
                  NS + "rushes": 16, NS + "cattails": 14,
                  NS + "sedge": 12, NS + "dry_reeds": 10,
                  NS + "sea_arrowgrass": 6},
          props={NS + "duckweed": 12}),

    # ---- Cold desert -------------------------------------------------------

    biome("cold_steppe", "Cold Steppe", ["Cold desert"],
          "Dry, cold and treeless. Deliberately sparse -- most of the ground "
          "should be bare soil showing between clumps.",
          s(NS + "dead_grass", NS + "dark_sandy_soil"),
          density=0.0,
          ground={NS + "dead_grass": 40, NS + "dry_reeds": 16,
                  NS + "dead_shrubs": 16, NS + "tumbleweed": 12,
                  NS + "desert_shrub": 10, NS + "wavy_hair_grass": 6},
          props={NS + "mudstone_rocks": 10, NS + "smooth_pebbles": 6}),

    # ---- Blocked on assets -------------------------------------------------

    biome("willow_carr", "Willow Carr", ["Wetland"],
          "BLOCKED: Conquest has willow_log, weeping_willow_leaves, "
          "downy_willow_leaves, goat_sallow_log and an alder sapling, but "
          "neither pack ships willow or alder schematics. Highest-value gap -- "
          "Azgaar uses Wetland heavily and fen_reedbed has to cover for it.",
          s(NS + "clay_rich_mud", NS + "dark_silt"),
          density=0.0, status="blocked",
          ground={NS + "rushes": 30, NS + "sedge": 25, NS + "cattails": 20,
                  NS + "duckweed": 25}),

    biome("hot_desert", "Hot Desert", ["Hot desert"],
          "BLOCKED: blocks exist (date_palm_leaves, joshua_tree_leaves, "
          "cactus, brown_sand) but there are no palm or joshua schematics in "
          "either pack.",
          s(NS + "brown_sand", NS + "brown_sandstone"),
          density=0.0, status="blocked",
          ground={NS + "desert_shrub": 40, NS + "dead_shrubs": 30,
                  NS + "tumbleweed": 30}),

    biome("savanna", "Savanna", ["Savanna"],
          "BLOCKED: acacia_log and the acacia branch blocks exist, but there "
          "are no acacia or baobab schematics.",
          s(NS + "dead_grass", NS + "rocky_sandy_soil"),
          density=0.0, status="blocked",
          ground={NS + "dead_grass": 60, NS + "desert_shrub": 40}),

    biome("tropical_seasonal_forest", "Tropical Seasonal Forest",
          ["Tropical seasonal forest"],
          "BLOCKED: no warm-climate tree schematics at all.",
          s(NS + "lush_grass", NS + "red_clay"),
          density=0.0, status="blocked", ground={NS + "lush_grass": 100}),

    biome("tropical_rainforest", "Tropical Rainforest", ["Tropical rainforest"],
          "BLOCKED: no warm-climate tree schematics at all. banana_tree_leaves "
          "and the palm set exist as blocks only.",
          s(NS + "lush_grass", NS + "red_clay"),
          density=0.0, status="blocked",
          ground={NS + "lush_grass": 60, NS + "large_fern": 40}),
]


def main(argv=None):
    ap = argparse.ArgumentParser(prog="fmgtrees.make_biomes")
    ap.add_argument("--registry", help="file of known Conquest block ids")
    ap.add_argument("--out", default=os.path.join("..", "docs", "conquest", "biomes"))
    ap.add_argument("--tree-index", default=os.path.join(
        "..", "run", "config", "fantasymapgenerator", "trees", "index.json"))
    args = ap.parse_args(argv)

    known = None
    if args.registry and os.path.exists(args.registry):
        with open(args.registry, encoding="utf-8") as fh:
            bare = {line.strip() for line in fh if line.strip()}
        known = {NS + b for b in bare} | {b for b in bare if ":" in b}

    groups = None
    if os.path.exists(args.tree_index):
        with open(args.tree_index, encoding="utf-8") as fh:
            groups = set(json.load(fh)["groups"])

    os.makedirs(args.out, exist_ok=True)

    bad_blocks, bad_groups = [], []
    for b in BIOMES:
        ids = list(b["surface"].values()) + list(b["groundCover"]) + list(b["props"])
        for i in ids:
            if known is not None and i.startswith(NS) and i not in known:
                bad_blocks.append((b["id"], i))
        if groups is not None:
            for g in list(b["trees"]["species"]) + list(b["trees"]["stumps"]["groups"]):
                if g not in groups:
                    bad_groups.append((b["id"], g))
        with open(os.path.join(args.out, b["id"] + ".json"), "w", encoding="utf-8") as fh:
            json.dump(b, fh, indent=2)

    ready = [b for b in BIOMES if b["status"] == "ready"]
    with open(os.path.join(args.out, "index.json"), "w", encoding="utf-8") as fh:
        json.dump({
            "count": len(BIOMES),
            "ready": len(ready),
            "blocked": len(BIOMES) - len(ready),
            "biomes": [{"id": b["id"], "name": b["name"], "azgaar": b["azgaar"],
                        "status": b["status"],
                        "species": len(b["trees"]["species"])} for b in BIOMES],
        }, fh, indent=2)

    print("wrote %d biome files (%d ready, %d blocked) to %s"
          % (len(BIOMES), len(ready), len(BIOMES) - len(ready), args.out))
    for b in BIOMES:
        mark = " " if b["status"] == "ready" else "!"
        print("  %s %-28s %-42s %d species, density %.2f"
              % (mark, b["id"], "/".join(b["azgaar"]),
                 len(b["trees"]["species"]), b["trees"]["density"]))
    if bad_blocks:
        print("\nunknown block ids:")
        for bid, i in bad_blocks:
            print("  %-28s %s" % (bid, i))
    if bad_groups:
        print("\nunknown tree groups:")
        for bid, g in bad_groups:
            print("  %-28s %s" % (bid, g))
    return 1 if (bad_blocks or bad_groups) else 0


if __name__ == "__main__":
    sys.exit(main())
