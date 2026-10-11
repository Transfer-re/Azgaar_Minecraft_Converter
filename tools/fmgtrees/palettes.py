"""
Ground palettes for the Conquest biomes: what the terrain surface is made of.

The terrain is built from layer blocks, not full blocks -- every surface column
is full blocks up to the floor of its height and one `layers=1..8` block on top
-- so a palette names **materials**, and every material has a layer form (the
skin that is actually seen) and a full form (what fills underneath when the
material continues down). Conquest ships layer forms in two shapes:

    conquest:<x>_layer   layers=1..8                  (sands, gravels, floors)
    conquest:<x>_slab    layers=1..8, type=bottom     (soils, muds, every rock)

Both are fine as a skin; the generator only has to set `type=bottom` on slabs.

Each biome palette has

    tints      grass / foliage / water colours for the biome JSON. Most CR
               grass, moss and overgrown blocks are greyscale with a tintindex,
               so this is half of what a surface looks like.
    soil       full block under skin materials (grass, floors, moss)
    stone      the biome's rock -- column fill below soil and bare cliffs
    soilDepth  soil blocks under the skin on flat ground; thins on slopes
    clump      noise scale, in blocks, at which materials in a mix form
               patches (small = speckled, large = broad drifts)
    patch      noise scale, in blocks, of sub-biome patches
    sub        sub-biomes: low-frequency patches inside one biome region, each
               with its own weighted material mix
    ramps      gradients that override the sub-biome mix by terrain variable:
               slope, shore distance (coast / bank) and altitude
    bed        what the floor under water is made of, by water depth

Semantics every consumer (preview page, Java surface rule) must share are in
docs/conquest/palettes.md. In short: ramps apply in the fixed order altitude,
coast, bank, slope, and a later match replaces an earlier one; `feather` adds
noise of that amplitude to the variable before comparing, so band edges dither
instead of cutting straight lines.

Hand edits belong here, not in docs/conquest/biomes/*.json -- make_biomes.py
writes those from this module.
"""

C = "conquest:"
V = "minecraft:"


# ---- materials ---------------------------------------------------------------

MATERIALS = {}


def _mat(name, layer, full, group, note=None):
    MATERIALS[name] = {"layer": layer, "full": full, "group": group}
    if note:
        MATERIALS[name]["note"] = note


def L(name, group, full=None, note=None):
    """conquest:<name>_layer over conquest:<name>."""
    _mat(name, C + name + "_layer", full or C + name, group, note)


def S(name, group, full=None, note=None):
    """conquest:<name>_slab (layers, type=bottom) over conquest:<name>."""
    _mat(name, C + name + "_slab", full or C + name, group, note)


def LV(name, group, note=None):
    """conquest:<name>_layer over the vanilla full block."""
    _mat(name, C + name + "_layer", V + name, group, note)


def SV(name, group, note=None):
    """conquest:<name>_slab over the vanilla full block."""
    _mat(name, C + name + "_slab", V + name, group, note)


# Grass. All three are grass-tinted and use a 4x4 repeat texture in CR.
LV("grass_block", "grass", "plain turf; the default green")
L("taiga_grass", "grass", note="coarser, darker tussock turf")
L("clover_covered_grass", "grass", note="lush clover lawn, reads brighter")
_mat("grassy_dirt", C + "grassy_dirt_layer", V + "dirt", "grass",
     "worn turf, dirt showing through; layer-only, vanilla dirt underneath")

# Forest floors and litter.
L("taiga_forest_floor", "floor", note="dark needle duff")
L("fir_forest_floor", "floor", note="brown needles, 3x3 repeat")
L("mossy_fir_forest_floor", "floor", note="needles with tinted moss")
L("overgrown_fir_forest_floor", "floor", note="needles mostly mossed over")
L("mossy_forest_floor_with_roots", "floor")
L("autumnal_forest_floor", "floor", note="yellow-orange leaf fall")
L("autumnal_forest_floor_with_roots", "floor")
L("muddy_autumnal_forest_floor", "floor", note="wet trodden leaves")
_mat("vibrant_autumnal_forest_floor", C + "vibrant_autumnal_forest_floor_layer",
     C + "autumnal_forest_floor", "floor",
     "layer-only; brighter leaf fall over the plain autumnal floor")
L("lorien_forest_floor", "floor", note="golden leaves -- CR's elven floor")
L("shredded_leaves", "floor", note="brown broadleaf litter")

# Mosses, heath and bog.
L("heather_covered_moss", "moss", note="purple heather over moss")
S("brown_moss", "moss")
S("cushion_moss_covered_loamy_dirt", "moss")
S("mossy_soil", "moss")
S("lichen_loamy_dirt", "moss", note="pale lichen crust")
L("green_sphagnum_moss_block", "moss")
L("light_green_sphagnum_moss_block", "moss")
L("yellow_sphagnum_moss_block", "moss")
L("red_sphagnum_moss_block", "moss")
L("brown_sphagnum_moss_block", "moss")

# Soils.
S("brown_earth", "soil")
S("dark_fertile_soil", "soil")
S("rocky_soil", "soil")
S("sandy_soil", "soil")
S("dark_sandy_soil", "soil")
S("rocky_sandy_soil", "soil", note="16 weighted random variants")
S("overgrown_rocky_sandy_soil", "soil")
S("chalky_soil", "soil")
S("mossy_chalky_soil", "soil")
S("overgrown_chalky_soil", "soil")
S("frozen_loamy_dirt", "soil", note="frost-whitened")
S("burnt_soil", "soil")
S("red_earth", "soil", note="orange laterite")
S("ochre_soil", "soil")
S("coarse_red_dirt", "soil")
S("medium_yellow_dirt", "soil")
S("dusty_yellow_dirt", "soil")
S("dusty_white_dirt", "soil")
S("fine_white_dirt", "soil")
S("light_cracked_dirt", "soil", note="dry pan")
S("cracked_dirt", "soil", note="red cracked clay")

# Wet ground.
S("dark_silt", "wet")
S("light_silt", "wet")
S("mossy_light_silt", "wet")
S("overgrown_light_silt", "wet")
S("clay_rich_mud", "wet")
S("mud", "wet")
S("thick_mud", "wet")
S("peat", "wet", note="2x2 repeat")

# Sand. Vanilla-based ones are sand-tinted under ACRP.
LV("sand", "sand")
L("wet_sand", "sand")
L("sand_and_vegetation", "sand")
L("wet_sand_and_vegetation", "sand")
L("sand_and_gravel", "sand")
L("wet_sand_and_gravel", "sand")
L("overgrown_sand", "sand")
L("tan_sand", "sand", note="8x8 repeat")
L("wet_tan_sand", "sand")
L("overgrown_tan_sand", "sand")
L("brown_sand", "sand")
L("wet_brown_sand", "sand")
L("mossy_brown_sand", "sand")
LV("red_sand", "sand")
L("red_sand_and_gravel", "sand")
L("red_sand_and_vegetation", "sand")
L("orange_sand", "sand")
L("gray_sand", "sand")
L("wet_gray_sand", "sand")
L("light_gray_sand", "sand")
L("dry_river_sand", "sand")
L("wet_river_sand", "sand")
L("white_salt", "sand", note="salt crust")

# Gravel, pebbles, debris.
LV("gravel", "gravel", "4 weighted random variants")
L("small_stones", "gravel")
L("mossy_small_stones", "gravel")
L("overgrown_small_stones", "gravel")
L("smooth_pebbles", "gravel")
L("brown_gravel", "gravel")
L("mossy_brown_gravel", "gravel")
L("light_gravel", "gravel")
L("gray_gravel", "gravel")
L("dark_gravel", "gravel")
L("schist_debris", "gravel")
L("mossy_schist_debris", "gravel")
L("sandstone_debris", "gravel")
L("brown_sandstone_debris", "gravel")
L("pale_limestone_debris", "gravel")
L("overgrown_pale_limestone_debris", "gravel")
L("gray_limestone_gravel", "gravel")
L("mossy_gray_limestone_gravel", "gravel")
L("ash", "gravel", note="volcanic / burn ash")

# Rock. Every one is a slab with layers, so bare rock can be layered too.
SV("granite", "rock")
S("weathered_granite", "rock")
S("dark_gray_granite", "rock")
S("light_brown_granite", "rock")
S("brown_granite", "rock")
S("red_granite", "rock")
S("gray_limestone", "rock")
S("pale_limestone", "rock")
S("dark_limestone", "rock")
S("mossy_limestone", "rock")
S("moss_covered_limestone", "rock")
S("weathered_blue_schist", "rock")
S("mossy_blue_schist", "rock")
S("lichen_blue_schist", "rock")
S("slate", "rock")
S("gneiss", "rock")
S("eroded_gneiss", "rock")
S("chalk", "rock")
S("weathered_basalt", "rock")
S("tan_sandstone", "rock")
S("faded_tan_sandstone", "rock")
S("brown_sandstone", "rock")
S("red_sandstone", "rock")
# Boulder fields: not in any palette's mix, only placed as ground-cover patches.
S("light_limestone_boulders", "rock")
S("mossy_light_limestone_boulders", "rock")

# Snow and ice. (bright_glacier_ice_slab is a plain slab with no `layers`,
# so it cannot be a skin.)
_mat("snow", V + "snow", V + "snow_block", "ice", "vanilla snow layers")
S("snowy_granite", "ice")
S("icy_limestone", "ice")
S("glacier_ice", "ice")
S("dirty_glacier_ice", "ice")
S("snow_covered_icy_limestone", "ice")


# ---- shared ramps ------------------------------------------------------------

def slope(soft, loose, rock, bare=None, feather=0.15):
    """Flat ground keeps the sub-biome mix; steeper ground sheds soil.

    Slope is rise per block (1.0 = 45 degrees). `soft` is worn ground on
    banks, `loose` the scree band, `rock` broken outcrop, `bare` cliff face.
    """
    bands = [{"from": 0.65, "mix": soft}, {"from": 1.0, "mix": loose},
             {"from": 1.45, "mix": rock}]
    if bare:
        bands.append({"from": 2.1, "mix": bare})
    return {"var": "slope", "feather": feather, "bands": bands}


def shore(kind, bands, feather=1.2):
    """kind 'coast' (sea) or 'bank' (river/lake): bands by distance in blocks."""
    return {"var": kind, "feather": feather,
            "bands": [{"to": d, "mix": m} for d, m in bands]}


def altitude(bands, feather=3.0):
    """Azgaar height units: 20 is sea level, 100 the highest the map allows."""
    return {"var": "alt", "feather": feather,
            "bands": [{"from": h, "mix": m} for h, m in bands]}


def bed(bands, feather=0.8):
    """Floor under water by depth in blocks (0 = the waterline)."""
    return {"var": "depth", "feather": feather,
            "bands": [{"to": d, "mix": m} for d, m in bands]}


COAST_TEMPERATE = shore("coast", [
    (1.5, {"wet_sand": 70, "wet_sand_and_gravel": 30}),
    (4, {"sand": 75, "sand_and_gravel": 25}),
    (6.5, {"sand_and_vegetation": 60, "overgrown_sand": 40}),
])
COAST_COLD = shore("coast", [
    (1.5, {"wet_gray_sand": 50, "smooth_pebbles": 50}),
    (4, {"gray_sand": 45, "smooth_pebbles": 30, "gray_gravel": 25}),
    (6, {"light_gray_sand": 40, "mossy_small_stones": 60}),
])
COAST_WARM = shore("coast", [
    (1.5, {"wet_tan_sand": 100}),
    (5, {"tan_sand": 85, "sand": 15}),
    (7.5, {"overgrown_tan_sand": 55, "sand_and_vegetation": 45}),
])
COAST_MUDDY = shore("coast", [
    (2, {"mud": 50, "dark_silt": 50}),
    (4.5, {"wet_sand_and_vegetation": 55, "overgrown_light_silt": 45}),
])

BANK_TEMPERATE = shore("bank", [
    (1, {"wet_river_sand": 55, "smooth_pebbles": 45}),
    (2.5, {"dark_silt": 45, "dry_river_sand": 35, "mossy_light_silt": 20}),
    (4, {"overgrown_light_silt": 60, "grassy_dirt": 40}),
], feather=0.9)
BANK_COLD = shore("bank", [
    (1, {"smooth_pebbles": 60, "wet_gray_sand": 40}),
    (2.5, {"gray_gravel": 40, "mossy_small_stones": 60}),
], feather=0.9)
BANK_WARM = shore("bank", [
    (1, {"wet_river_sand": 70, "smooth_pebbles": 30}),
    (3, {"dry_river_sand": 60, "sand_and_vegetation": 40}),
], feather=0.9)
BANK_MUD = shore("bank", [
    (1.5, {"mud": 45, "thick_mud": 25, "dark_silt": 30}),
    (3.5, {"mossy_light_silt": 50, "overgrown_light_silt": 50}),
], feather=0.9)

BED_TEMPERATE = bed([
    (1.5, {"wet_river_sand": 50, "smooth_pebbles": 50}),
    (4, {"dark_silt": 55, "wet_sand_and_gravel": 45}),
    (99, {"mud": 60, "dark_silt": 40}),
])
BED_COLD = bed([
    (2, {"smooth_pebbles": 60, "wet_gray_sand": 40}),
    (99, {"gray_gravel": 50, "dark_silt": 50}),
])
BED_WARM = bed([
    (2, {"wet_tan_sand": 70, "wet_river_sand": 30}),
    (99, {"wet_sand": 50, "dark_silt": 50}),
])
BED_BOG = bed([
    (99, {"peat": 50, "thick_mud": 30, "dark_silt": 20}),
])


# Ramps always apply in this order and a later match replaces an earlier one:
# cliffs stay rock at any altitude and next to any water.
RAMP_ORDER = {"alt": 0, "coast": 1, "bank": 2, "slope": 3}


def palette(tints, soil, stone, sub, ramps, bed_ramp, soil_depth=3, notes=None,
            clump=4.0, patch=28.0):
    grass, foliage, water = tints
    out = {"tints": {"grass": grass, "foliage": foliage, "water": water},
           "soil": soil, "stone": stone, "soilDepth": soil_depth,
           "clump": clump, "patch": patch,
           "sub": [{"id": i, "name": n, "weight": w, "mix": mix}
                   for i, n, w, mix in sub],
           "ramps": sorted(ramps, key=lambda r: RAMP_ORDER[r["var"]]),
           "bed": bed_ramp}
    if notes:
        out["notes"] = notes
    return out


W_TEMPERATE = "#3f6ea8"
W_COLD = "#3a5c86"
W_BOG = "#4a5f4c"
W_WARM = "#3a8fa6"
W_GLACIAL = "#6f9ec4"


# ---- the palettes ------------------------------------------------------------

PALETTES = {

    # ---- Temperate deciduous forest ---------------------------------------

    "oak_woodland": palette(
        ("#7d9f46", "#5f8f35", W_TEMPERATE), C + "unfertile_loamy_dirt",
        "gray_limestone",
        [("leaf_floor", "Leafy floor", 40,
          {"grass_block": 35, "shredded_leaves": 25,
           "mossy_forest_floor_with_roots": 22, "grassy_dirt": 18}),
         ("glade", "Glade", 25,
          {"grass_block": 50, "clover_covered_grass": 40, "grassy_dirt": 10}),
         ("bramble_edge", "Bramble edge", 20,
          {"grass_block": 50, "grassy_dirt": 30, "brown_earth": 20}),
         ("damp_hollow", "Damp hollow", 15,
          {"mossy_soil": 35, "cushion_moss_covered_loamy_dirt": 25,
           "mossy_forest_floor_with_roots": 25, "clay_rich_mud": 15})],
        [BANK_TEMPERATE, COAST_TEMPERATE,
         slope({"grassy_dirt": 60, "rocky_soil": 40},
               {"overgrown_small_stones": 55, "rocky_soil": 45},
               {"mossy_limestone": 45, "gray_limestone": 35,
                "mossy_gray_limestone_gravel": 20},
               {"gray_limestone": 80, "mossy_limestone": 20})],
        BED_TEMPERATE,
        notes="Green-summer oak wood. Litter is brown shredded leaves under "
              "the canopy, turf in the gaps; limestone breaks out on slopes."),

    "beech_forest": palette(
        ("#82a44a", "#6a9a38", W_TEMPERATE), C + "brown_earth", "pale_limestone",
        [("litter", "Deep litter", 55,
          {"shredded_leaves": 45, "autumnal_forest_floor": 20,
           "mossy_forest_floor_with_roots": 15, "grass_block": 12,
           "grassy_dirt": 8}),
         ("mossy_roots", "Mossy roots", 20,
          {"mossy_forest_floor_with_roots": 50,
           "cushion_moss_covered_loamy_dirt": 30, "shredded_leaves": 20}),
         ("bluebell_glade", "Bluebell glade", 15,
          {"grass_block": 60, "clover_covered_grass": 25, "grassy_dirt": 15}),
         ("chalk_hanger", "Chalk hanger", 10,
          {"overgrown_chalky_soil": 40, "mossy_chalky_soil": 30,
           "shredded_leaves": 30})],
        [BANK_TEMPERATE, COAST_TEMPERATE,
         slope({"shredded_leaves": 50, "mossy_chalky_soil": 50},
               {"overgrown_pale_limestone_debris": 60, "chalky_soil": 40},
               {"pale_limestone": 60, "pale_limestone_debris": 40},
               {"pale_limestone": 100})],
        BED_TEMPERATE,
        notes="Beech holds its dead leaves brown all year and shades out the "
              "ground layer, so the floor is litter first and turf only in "
              "gaps. Beech hangers on chalk give the pale slope band."),

    "mixed_broadleaf_forest": palette(
        ("#7d9f46", "#62933a", W_TEMPERATE), C + "unfertile_loamy_dirt",
        "gray_limestone",
        [("mixed_litter", "Mixed litter", 40,
          {"grass_block": 30, "shredded_leaves": 25,
           "mossy_forest_floor_with_roots": 20, "grassy_dirt": 15,
           "autumnal_forest_floor": 10}),
         ("glade", "Glade", 25,
          {"grass_block": 50, "clover_covered_grass": 35, "grassy_dirt": 15}),
         ("fern_hollow", "Fern hollow", 20,
          {"mossy_soil": 30, "mossy_forest_floor_with_roots": 30,
           "cushion_moss_covered_loamy_dirt": 20, "grass_block": 20}),
         ("stony_rise", "Stony rise", 15,
          {"overgrown_small_stones": 40, "rocky_soil": 30, "grass_block": 30})],
        [BANK_TEMPERATE, COAST_TEMPERATE,
         slope({"grassy_dirt": 60, "rocky_soil": 40},
               {"overgrown_small_stones": 55, "rocky_soil": 45},
               {"mossy_limestone": 45, "gray_limestone": 35,
                "mossy_gray_limestone_gravel": 20},
               {"gray_limestone": 80, "mossy_limestone": 20})],
        BED_TEMPERATE),

    "autumn_broadleaf_forest": palette(
        ("#a29d4f", "#b8923a", W_TEMPERATE), C + "unfertile_loamy_dirt",
        "gray_limestone",
        [("leaf_fall", "Leaf fall", 45,
          {"autumnal_forest_floor": 40, "vibrant_autumnal_forest_floor": 25,
           "shredded_leaves": 20, "grass_block": 15}),
         ("rooted_floor", "Rooted floor", 25,
          {"autumnal_forest_floor_with_roots": 45,
           "muddy_autumnal_forest_floor": 25, "shredded_leaves": 30}),
         ("golden_glade", "Golden glade", 15,
          {"lorien_forest_floor": 40, "grass_block": 40, "grassy_dirt": 20}),
         ("wet_hollow", "Wet hollow", 15,
          {"muddy_autumnal_forest_floor": 50, "mossy_soil": 30,
           "clay_rich_mud": 20})],
        [BANK_TEMPERATE, COAST_TEMPERATE,
         slope({"autumnal_forest_floor_with_roots": 50, "rocky_soil": 50},
               {"overgrown_small_stones": 50, "rocky_soil": 50},
               {"mossy_limestone": 45, "gray_limestone": 35,
                "mossy_gray_limestone_gravel": 20},
               {"gray_limestone": 80, "mossy_limestone": 20})],
        BED_TEMPERATE,
        notes="Leaf fall does the colour work, so turf is kept to ~15% and "
              "tinted dry. The golden glade is the brightest patch in any "
              "temperate biome -- keep it rare."),

    "aspen_grove": palette(
        ("#8aab4e", "#7fa843", W_TEMPERATE), C + "brown_earth", "weathered_granite",
        [("grassy_understory", "Grassy understory", 50,
          {"grass_block": 60, "clover_covered_grass": 20, "grassy_dirt": 20}),
         ("leaf_floor", "Leaf floor", 30,
          {"grass_block": 40, "shredded_leaves": 25, "autumnal_forest_floor": 15,
           "grassy_dirt": 20}),
         ("terrace", "River terrace", 20,
          {"overgrown_small_stones": 35, "grass_block": 40, "rocky_soil": 25})],
        [BANK_TEMPERATE, COAST_TEMPERATE,
         slope({"grassy_dirt": 55, "rocky_soil": 45},
               {"overgrown_small_stones": 50, "small_stones": 50},
               {"weathered_granite": 55, "granite": 25, "gray_gravel": 20},
               {"weathered_granite": 70, "granite": 30})],
        BED_TEMPERATE),

    "birch_wood": palette(
        ("#88aa52", "#86ad4f", W_TEMPERATE), C + "unfertile_loamy_dirt",
        "weathered_granite",
        [("light_floor", "Light floor", 45,
          {"grass_block": 45, "grassy_dirt": 20, "shredded_leaves": 15,
           "mossy_forest_floor_with_roots": 20}),
         ("heath_floor", "Heath floor", 25,
          {"taiga_grass": 35, "heather_covered_moss": 25, "grass_block": 25,
           "cushion_moss_covered_loamy_dirt": 15}),
         ("birch_litter", "Yellow litter", 15,
          {"autumnal_forest_floor": 35, "grass_block": 45, "grassy_dirt": 20}),
         ("mossy_hollow", "Mossy hollow", 15,
          {"mossy_soil": 40, "cushion_moss_covered_loamy_dirt": 30,
           "green_sphagnum_moss_block": 30})],
        [BANK_TEMPERATE, COAST_TEMPERATE,
         slope({"grassy_dirt": 50, "rocky_soil": 50},
               {"mossy_small_stones": 50, "small_stones": 50},
               {"weathered_granite": 55, "granite": 25, "gray_gravel": 20},
               {"weathered_granite": 70, "granite": 30})],
        BED_TEMPERATE),

    "mixed_conifer_broadleaf": palette(
        ("#759a48", "#5a8a3c", W_TEMPERATE), C + "unfertile_loamy_dirt",
        "weathered_granite",
        [("needle_leaf_mix", "Needle and leaf", 40,
          {"grass_block": 30, "shredded_leaves": 25, "taiga_forest_floor": 25,
           "fir_forest_floor": 20}),
         ("mossy_floor", "Mossy floor", 25,
          {"mossy_fir_forest_floor": 45, "mossy_forest_floor_with_roots": 35,
           "cushion_moss_covered_loamy_dirt": 20}),
         ("clearing", "Clearing", 20,
          {"grass_block": 45, "taiga_grass": 35, "grassy_dirt": 20}),
         ("boulder_ground", "Boulder ground", 15,
          {"overgrown_small_stones": 30, "mossy_small_stones": 30,
           "taiga_grass": 40})],
        [BANK_TEMPERATE, COAST_COLD,
         slope({"grassy_dirt": 50, "rocky_soil": 50},
               {"mossy_small_stones": 55, "small_stones": 45},
               {"weathered_granite": 55, "granite": 25, "gray_gravel": 20},
               {"weathered_granite": 70, "granite": 30})],
        BED_TEMPERATE,
        notes="Half the floor is needle duff, half broadleaf litter and turf: "
              "the ground makes the same transition the canopy does."),

    # ---- Taiga ---------------------------------------------------------------

    "spruce_taiga": palette(
        ("#6f8f4a", "#4f7a3c", W_COLD), C + "unfertile_loamy_dirt",
        "weathered_granite",
        [("needle_carpet", "Needle carpet", 45,
          {"taiga_forest_floor": 45, "fir_forest_floor": 30,
           "mossy_fir_forest_floor": 25}),
         ("moss_floor", "Moss floor", 25,
          {"mossy_fir_forest_floor": 45, "overgrown_fir_forest_floor": 30,
           "cushion_moss_covered_loamy_dirt": 25}),
         ("berry_clearing", "Berry clearing", 20,
          {"taiga_grass": 60, "heather_covered_moss": 25, "grassy_dirt": 15}),
         ("rocky_knoll", "Rocky knoll", 10,
          {"mossy_small_stones": 35, "lichen_loamy_dirt": 25, "taiga_grass": 40})],
        [BANK_COLD, COAST_COLD,
         slope({"taiga_grass": 40, "rocky_soil": 60},
               {"mossy_small_stones": 50, "gray_gravel": 50},
               {"weathered_granite": 55, "granite": 25, "lichen_loamy_dirt": 20},
               {"weathered_granite": 60, "dark_gray_granite": 40}),
         altitude([(72, {"lichen_loamy_dirt": 40, "taiga_forest_floor": 30,
                         "frozen_loamy_dirt": 30})])],
        BED_COLD,
        notes="Dark duff with moss running through it. Above ~72 Azgaar "
              "height the floor frosts and lichens toward shrub tundra."),

    "old_growth_spruce": palette(
        ("#648a44", "#47703a", W_COLD), C + "dark_fertile_soil",
        "weathered_granite",
        [("deep_moss", "Deep moss", 45,
          {"mossy_fir_forest_floor": 45, "overgrown_fir_forest_floor": 30,
           "cushion_moss_covered_loamy_dirt": 25}),
         ("needle_duff", "Needle duff", 35,
          {"taiga_forest_floor": 50, "fir_forest_floor": 30,
           "mossy_forest_floor_with_roots": 20}),
         ("windthrow_gap", "Windthrow gap", 20,
          {"taiga_grass": 45, "overgrown_fir_forest_floor": 30,
           "grassy_dirt": 25})],
        [BANK_COLD, COAST_COLD,
         slope({"mossy_fir_forest_floor": 50, "rocky_soil": 50},
               {"mossy_small_stones": 60, "gray_gravel": 40},
               {"mossy_blue_schist": 40, "weathered_granite": 60},
               {"weathered_granite": 70, "dark_gray_granite": 30})],
        BED_COLD),

    "pine_taiga": palette(
        ("#86975a", "#5c8040", W_COLD), C + "dark_sandy_soil", "granite",
        [("pine_needles", "Pine needles", 40,
          {"fir_forest_floor": 45, "taiga_forest_floor": 25, "taiga_grass": 20,
           "dark_sandy_soil": 10}),
         ("lichen_heath", "Lichen heath", 30,
          {"lichen_loamy_dirt": 30, "heather_covered_moss": 25,
           "taiga_grass": 25, "fir_forest_floor": 20}),
         ("sandy_ridge", "Sandy ridge", 20,
          {"dark_sandy_soil": 30, "rocky_sandy_soil": 30, "fir_forest_floor": 25,
           "overgrown_rocky_sandy_soil": 15}),
         ("moss_hollow", "Moss hollow", 10,
          {"mossy_fir_forest_floor": 50, "cushion_moss_covered_loamy_dirt": 30,
           "green_sphagnum_moss_block": 20})],
        [BANK_COLD, COAST_COLD,
         slope({"rocky_sandy_soil": 60, "dark_sandy_soil": 40},
               {"gray_gravel": 50, "small_stones": 50},
               {"granite": 50, "weathered_granite": 30, "lichen_loamy_dirt": 20},
               {"granite": 70, "weathered_granite": 30})],
        BED_COLD,
        notes="Dry, sandy and open. Lichen heath is what separates this from "
              "spruce taiga at a glance."),

    "larch_taiga": palette(
        ("#7f9a55", "#7ca048", W_COLD), C + "unfertile_loamy_dirt",
        "weathered_blue_schist",
        [("larch_duff", "Larch duff", 45,
          {"taiga_grass": 35, "fir_forest_floor": 30,
           "overgrown_fir_forest_floor": 20, "taiga_forest_floor": 15}),
         ("cotton_meadow", "Cotton-grass meadow", 25,
          {"taiga_grass": 50, "brown_moss": 30,
           "cushion_moss_covered_loamy_dirt": 20}),
         ("stony_slope", "Stony slope", 20,
          {"mossy_schist_debris": 35, "lichen_loamy_dirt": 25, "taiga_grass": 40}),
         ("larch_bog", "Larch bog", 10,
          {"green_sphagnum_moss_block": 35, "brown_sphagnum_moss_block": 35,
           "peat": 30})],
        [BANK_COLD, COAST_COLD,
         slope({"lichen_loamy_dirt": 45, "rocky_soil": 55},
               {"schist_debris": 55, "mossy_schist_debris": 45},
               {"weathered_blue_schist": 55, "lichen_blue_schist": 25,
                "schist_debris": 20},
               {"weathered_blue_schist": 70, "slate": 30}),
         altitude([(74, {"lichen_loamy_dirt": 40, "brown_moss": 30,
                         "frozen_loamy_dirt": 30})])],
        BED_COLD),

    "autumn_larch_taiga": palette(
        ("#a59a55", "#c9a13c", W_COLD), C + "unfertile_loamy_dirt",
        "weathered_blue_schist",
        [("gold_duff", "Golden duff", 45,
          {"fir_forest_floor": 30, "lorien_forest_floor": 25, "taiga_grass": 25,
           "autumnal_forest_floor": 20}),
         ("russet_meadow", "Russet meadow", 25,
          {"taiga_grass": 45, "brown_moss": 30, "grassy_dirt": 25}),
         ("stony_slope", "Stony slope", 20,
          {"mossy_schist_debris": 30, "lichen_loamy_dirt": 30, "taiga_grass": 40}),
         ("red_bog", "Red bog", 10,
          {"red_sphagnum_moss_block": 40, "brown_sphagnum_moss_block": 35,
           "peat": 25})],
        [BANK_COLD, COAST_COLD,
         slope({"lichen_loamy_dirt": 45, "rocky_soil": 55},
               {"schist_debris": 55, "mossy_schist_debris": 45},
               {"weathered_blue_schist": 55, "lichen_blue_schist": 25,
                "schist_debris": 20},
               {"weathered_blue_schist": 70, "slate": 30})],
        BED_COLD,
        notes="Needle drop is golden, so lorien_forest_floor (CR's gold leaf "
              "floor) earns a quarter of the ground here. Red sphagnum in "
              "the hollows echoes the orange larches."),

    "dead_taiga": palette(
        ("#9b9160", "#7d7a4f", W_COLD), C + "dark_sandy_soil", "dark_gray_granite",
        [("burn_scar", "Burn scar", 40,
          {"burnt_soil": 45, "fir_forest_floor": 25, "ash": 20,
           "dark_gravel": 10}),
         ("grey_needles", "Grey needles", 30,
          {"taiga_forest_floor": 40, "fir_forest_floor": 30, "burnt_soil": 30}),
         ("regrowth", "Fireweed regrowth", 20,
          {"taiga_grass": 45, "grassy_dirt": 25, "burnt_soil": 30}),
         ("bare_rock", "Bare rock", 10,
          {"weathered_granite": 40, "lichen_loamy_dirt": 30, "dark_gravel": 30})],
        [BANK_COLD, COAST_COLD,
         slope({"burnt_soil": 50, "rocky_soil": 50},
               {"dark_gravel": 50, "gray_gravel": 50},
               {"dark_gray_granite": 60, "weathered_granite": 40},
               {"dark_gray_granite": 100})],
        BED_COLD),

    # ---- Temperate rainforest ----------------------------------------------

    "mossy_conifer_forest": palette(
        ("#5f9340", "#4a7f36", W_TEMPERATE), C + "dark_fertile_soil",
        "mossy_blue_schist",
        [("moss_carpet", "Moss carpet", 45,
          {"mossy_fir_forest_floor": 40, "overgrown_fir_forest_floor": 30,
           "cushion_moss_covered_loamy_dirt": 15,
           "mossy_forest_floor_with_roots": 15}),
         ("fern_floor", "Fern floor", 25,
          {"mossy_forest_floor_with_roots": 40, "mossy_soil": 30,
           "taiga_forest_floor": 30}),
         ("nurse_log_duff", "Nurse-log duff", 20,
          {"fir_forest_floor": 35, "mossy_fir_forest_floor": 35,
           "shredded_leaves": 30}),
         ("seep", "Seep", 10,
          {"green_sphagnum_moss_block": 30, "mossy_soil": 40,
           "clay_rich_mud": 30})],
        [BANK_TEMPERATE, COAST_COLD,
         slope({"mossy_soil": 50, "mossy_forest_floor_with_roots": 50},
               {"mossy_schist_debris": 60, "mossy_small_stones": 40},
               {"mossy_blue_schist": 60, "lichen_blue_schist": 40},
               {"mossy_blue_schist": 70, "weathered_blue_schist": 30})],
        BED_TEMPERATE,
        notes="Everything green, including the rock: mossy schist on every "
              "slope band so cliffs do not break the mood."),

    # ---- Grassland -----------------------------------------------------------

    "meadow": palette(
        ("#8db052", "#74a043", W_TEMPERATE), C + "dark_fertile_soil",
        "gray_limestone",
        [("hay_meadow", "Hay meadow", 45,
          {"grass_block": 70, "clover_covered_grass": 30}),
         ("clover_lea", "Clover lea", 25,
          {"clover_covered_grass": 60, "grass_block": 40}),
         ("worn_turf", "Worn turf", 15,
          {"grass_block": 50, "grassy_dirt": 35, "brown_earth": 15}),
         ("wet_meadow", "Wet meadow", 15,
          {"grass_block": 45, "mossy_soil": 25, "clover_covered_grass": 20,
           "clay_rich_mud": 10})],
        [BANK_TEMPERATE, COAST_TEMPERATE,
         slope({"grassy_dirt": 60, "brown_earth": 40},
               {"overgrown_small_stones": 55, "rocky_soil": 45},
               {"gray_limestone": 50, "mossy_limestone": 30,
                "gray_limestone_gravel": 20},
               {"gray_limestone": 100})],
        BED_TEMPERATE, clump=5.0, patch=36.0,
        notes="Deliberately quiet: two turf blocks carry 70% of the area, so "
              "the flowers and grasses in groundCover are what you notice."),

    "moorland": palette(
        ("#8d8f55", "#6f7f45", W_BOG), C + "peat", "dark_gray_granite",
        [("heather_moor", "Heather moor", 45,
          {"heather_covered_moss": 60, "brown_moss": 20, "taiga_grass": 20}),
         ("grass_moor", "Grass moor", 25,
          {"taiga_grass": 55, "brown_moss": 25, "grassy_dirt": 20}),
         ("blanket_bog", "Blanket bog", 20,
          {"peat": 35, "brown_sphagnum_moss_block": 30,
           "green_sphagnum_moss_block": 20, "heather_covered_moss": 15}),
         ("tor_ground", "Tor ground", 10,
          {"mossy_small_stones": 35, "dark_gray_granite": 35,
           "lichen_loamy_dirt": 30})],
        [BANK_COLD, COAST_COLD,
         slope({"peat": 40, "rocky_soil": 60},
               {"mossy_small_stones": 50, "gray_gravel": 50},
               {"dark_gray_granite": 55, "weathered_granite": 25,
                "lichen_loamy_dirt": 20},
               {"dark_gray_granite": 80, "weathered_granite": 20})],
        BED_BOG,
        notes="Purple from the heather block itself, olive-brown from the "
              "tint. Tors are dark granite so they read as outcrops, not "
              "scree."),

    "downland": palette(
        ("#93ad5a", "#6e9a40", W_TEMPERATE), C + "chalky_soil", "chalk",
        [("chalk_turf", "Chalk turf", 50,
          {"grass_block": 65, "clover_covered_grass": 20,
           "overgrown_chalky_soil": 15}),
         ("thin_turf", "Thin turf", 25,
          {"overgrown_chalky_soil": 45, "mossy_chalky_soil": 30,
           "grass_block": 25}),
         ("combe_bottom", "Combe bottom", 15,
          {"clover_covered_grass": 40, "grass_block": 40, "brown_earth": 20}),
         ("chalk_scar", "Chalk scar", 10,
          {"chalky_soil": 40, "chalk": 30, "pale_limestone_debris": 30})],
        [BANK_TEMPERATE, COAST_TEMPERATE,
         slope({"overgrown_chalky_soil": 60, "mossy_chalky_soil": 40},
               {"chalky_soil": 50, "pale_limestone_debris": 50},
               {"chalk": 70, "pale_limestone_debris": 30},
               {"chalk": 100}, feather=0.12)],
        BED_TEMPERATE, soil_depth=1,
        notes="Soil is one block deep; chalk shows on any real slope. The "
              "white scarps are the signature -- do not soften the slope "
              "ramp."),

    # ---- Tundra --------------------------------------------------------------

    "shrub_tundra": palette(
        ("#8e9a5e", "#6f8a4c", W_COLD), C + "frozen_loamy_dirt", "eroded_gneiss",
        [("berry_heath", "Berry heath", 40,
          {"brown_moss": 30, "taiga_grass": 30, "heather_covered_moss": 25,
           "lichen_loamy_dirt": 15}),
         ("lichen_patch", "Lichen patch", 25,
          {"lichen_loamy_dirt": 50, "brown_moss": 30, "frozen_loamy_dirt": 20}),
         ("tussock", "Tussock", 20,
          {"taiga_grass": 45, "cushion_moss_covered_loamy_dirt": 30,
           "brown_moss": 25}),
         ("frost_boil", "Frost boil", 15,
          {"frozen_loamy_dirt": 40, "mossy_small_stones": 30,
           "lichen_loamy_dirt": 30})],
        [BANK_COLD, COAST_COLD,
         slope({"lichen_loamy_dirt": 50, "rocky_soil": 50},
               {"light_gravel": 50, "mossy_small_stones": 50},
               {"eroded_gneiss": 55, "gneiss": 25, "lichen_loamy_dirt": 20},
               {"eroded_gneiss": 60, "gneiss": 40}),
         altitude([(80, {"snow": 40, "frozen_loamy_dirt": 35,
                         "lichen_loamy_dirt": 25})])],
        BED_COLD, soil_depth=2),

    "lichen_tundra": palette(
        ("#9aa06a", "#7d8a5a", W_COLD), C + "frozen_loamy_dirt", "eroded_gneiss",
        [("lichen_mat", "Lichen mat", 45,
          {"lichen_loamy_dirt": 60, "brown_moss": 25, "frozen_loamy_dirt": 15}),
         ("stone_polygons", "Stone polygons", 25,
          {"lichen_loamy_dirt": 40, "small_stones": 30, "mossy_small_stones": 30}),
         ("frost_heave", "Frost heave", 20,
          {"frozen_loamy_dirt": 50, "lichen_loamy_dirt": 30, "light_gravel": 20}),
         ("snowbed", "Late snowbed", 10,
          {"snow": 60, "frozen_loamy_dirt": 40})],
        [BANK_COLD, COAST_COLD,
         slope({"frozen_loamy_dirt": 50, "light_gravel": 50},
               {"light_gravel": 50, "gray_gravel": 50},
               {"eroded_gneiss": 60, "gneiss": 40},
               {"gneiss": 60, "eroded_gneiss": 40})],
        BED_COLD, soil_depth=2),

    "alpine_scree": palette(
        ("#8f9c62", "#6e8450", W_GLACIAL), C + "rocky_soil", "gneiss",
        [("scree", "Scree", 45,
          {"light_gravel": 30, "gray_gravel": 25, "schist_debris": 25,
           "small_stones": 20}),
         ("rock_garden", "Rock garden", 25,
          {"weathered_blue_schist": 40, "mossy_schist_debris": 30,
           "lichen_loamy_dirt": 30}),
         ("slab_field", "Slab field", 20,
          {"slate": 40, "gneiss": 30, "gray_gravel": 30}),
         ("snow_patch", "Snow patch", 10,
          {"snow": 70, "snowy_granite": 30})],
        [BANK_COLD,
         slope({"gray_gravel": 60, "schist_debris": 40},
               {"schist_debris": 50, "light_gravel": 50},
               {"gneiss": 50, "slate": 30, "weathered_blue_schist": 20},
               {"gneiss": 70, "slate": 30}),
         altitude([(85, {"snow": 60, "snowy_granite": 40})])],
        BED_COLD, soil_depth=1),

    # ---- Glacier -------------------------------------------------------------

    "glacier": palette(
        ("#90a07a", "#70866a", W_GLACIAL), C + "glacier_ice", "snowy_granite",
        [("firn", "Firn", 50, {"snow": 85, "glacier_ice": 15}),
         ("blue_ice", "Blue ice", 25,
          {"glacier_ice": 75, "snow": 15, "dirty_glacier_ice": 10}),
         ("moraine_ice", "Moraine ice", 15,
          {"dirty_glacier_ice": 60, "gray_gravel": 20, "glacier_ice": 20}),
         ("crevasse_field", "Crevasse field", 10,
          {"glacier_ice": 50, "snow": 30, "dirty_glacier_ice": 20})],
        [slope({"glacier_ice": 60, "snow": 40},
               {"glacier_ice": 80, "dirty_glacier_ice": 20},
               {"snowy_granite": 60, "glacier_ice": 40},
               {"snowy_granite": 100})],
        bed([(99, {"glacier_ice": 60, "gray_gravel": 40})]), soil_depth=6,
        notes="Soil is ice, six deep, so a glacier edge cut by a slope shows "
              "a real ice wall before the granite."),

    # ---- Wetland -------------------------------------------------------------

    "sphagnum_bog": palette(
        ("#7f8f4a", "#66803e", W_BOG), C + "peat", "dark_gray_granite",
        [("hummock", "Hummocks", 35,
          {"green_sphagnum_moss_block": 35, "light_green_sphagnum_moss_block": 25,
           "red_sphagnum_moss_block": 20, "yellow_sphagnum_moss_block": 20}),
         ("lawn", "Moss lawn", 30,
          {"light_green_sphagnum_moss_block": 40,
           "yellow_sphagnum_moss_block": 30, "brown_sphagnum_moss_block": 30}),
         ("hollow", "Wet hollow", 20,
          {"peat": 40, "brown_sphagnum_moss_block": 35,
           "green_sphagnum_moss_block": 25}),
         ("bog_rim", "Bog rim", 15,
          {"heather_covered_moss": 40, "brown_moss": 30, "taiga_grass": 30})],
        [BANK_MUD, COAST_MUDDY,
         slope({"peat": 50, "brown_moss": 50},
               {"mossy_small_stones": 60, "peat": 40},
               {"dark_gray_granite": 60, "weathered_granite": 40},
               {"dark_gray_granite": 100})],
        BED_BOG, clump=2.5, patch=18.0,
        notes="Four sphagnum colours in clumps are the whole look. Clump size "
              "matters more than weights here, so it is set small."),

    "fen_reedbed": palette(
        ("#8a9a50", "#6f8d42", W_BOG), C + "dark_silt", "dark_limestone",
        [("reed_mud", "Reed mud", 40,
          {"dark_silt": 35, "clay_rich_mud": 35, "mud": 30}),
         ("sedge_lawn", "Sedge lawn", 30,
          {"overgrown_light_silt": 40, "mossy_light_silt": 30,
           "grass_block": 30}),
         ("silt_flat", "Silt flat", 20,
          {"dark_silt": 40, "light_silt": 30, "wet_river_sand": 30}),
         ("carr_edge", "Carr edge", 10,
          {"mossy_soil": 50, "grass_block": 30,
           "cushion_moss_covered_loamy_dirt": 20})],
        [BANK_MUD, COAST_MUDDY,
         slope({"mossy_light_silt": 50, "brown_earth": 50},
               {"gray_limestone_gravel": 60, "mossy_gray_limestone_gravel": 40},
               {"dark_limestone": 60, "gray_limestone": 40},
               {"dark_limestone": 100})],
        BED_BOG),

    "willow_carr": palette(
        ("#6f9443", "#5e8a3c", W_BOG), C + "dark_silt", "dark_limestone",
        [("wet_woodland", "Wet woodland", 40,
          {"mossy_soil": 30, "mossy_forest_floor_with_roots": 25,
           "clay_rich_mud": 25, "shredded_leaves": 20}),
         ("sedge_swamp", "Sedge swamp", 30,
          {"overgrown_light_silt": 40, "mud": 30, "mossy_light_silt": 30}),
         ("leaf_pools", "Leaf pools", 20,
          {"muddy_autumnal_forest_floor": 50, "thick_mud": 25, "dark_silt": 25}),
         ("hummock", "Root hummock", 10,
          {"grass_block": 40, "cushion_moss_covered_loamy_dirt": 30,
           "mossy_forest_floor_with_roots": 30})],
        [BANK_MUD, COAST_MUDDY,
         slope({"mossy_soil": 50, "brown_earth": 50},
               {"mossy_gray_limestone_gravel": 60, "rocky_soil": 40},
               {"dark_limestone": 60, "mossy_limestone": 40},
               {"dark_limestone": 100})],
        BED_BOG),

    # ---- Cold desert ---------------------------------------------------------

    "cold_steppe": palette(
        ("#a8a26a", "#8a8d58", W_COLD), C + "dark_sandy_soil", "brown_granite",
        [("feathergrass_steppe", "Feathergrass steppe", 45,
          {"grass_block": 35, "grassy_dirt": 30, "dark_sandy_soil": 20,
           "rocky_sandy_soil": 15}),
         ("gravel_pan", "Gravel pan", 25,
          {"rocky_sandy_soil": 40, "dark_sandy_soil": 30, "gray_gravel": 30}),
         ("loess_bank", "Loess bank", 15,
          {"medium_yellow_dirt": 35, "dusty_yellow_dirt": 35, "grassy_dirt": 30}),
         ("saline_flat", "Saline flat", 15,
          {"light_cracked_dirt": 40, "dusty_white_dirt": 30,
           "fine_white_dirt": 15, "white_salt": 15})],
        [BANK_COLD, COAST_COLD,
         slope({"rocky_sandy_soil": 60, "dark_sandy_soil": 40},
               {"brown_gravel": 50, "gray_gravel": 50},
               {"brown_granite": 60, "light_brown_granite": 40},
               {"brown_granite": 100})],
        BED_COLD, soil_depth=2,
        notes="Turf only survives as the dry-tinted minority. Most of the "
              "ground should read as bare soil between clumps."),

    # ---- Hot desert, savanna, tropics -----------------------------------------

    "hot_desert": palette(
        ("#c4b064", "#a2a150", W_WARM), C + "tan_sand", "faded_tan_sandstone",
        [("erg", "Dune sea", 40,
          {"tan_sand": 55, "sand": 30, "orange_sand": 15}),
         ("reg", "Gravel plain", 25,
          {"sand_and_gravel": 30, "red_sand_and_gravel": 30,
           "brown_sandstone_debris": 20, "dusty_yellow_dirt": 20}),
         ("hamada", "Rock plateau", 15,
          {"faded_tan_sandstone": 40, "sandstone_debris": 30,
           "brown_sandstone_debris": 30}),
         ("playa", "Playa", 10,
          {"light_cracked_dirt": 50, "cracked_dirt": 25, "fine_white_dirt": 25}),
         ("wadi", "Wadi", 10,
          {"dry_river_sand": 50, "sand_and_vegetation": 30, "smooth_pebbles": 20})],
        [BANK_WARM, COAST_WARM,
         slope({"sandstone_debris": 50, "tan_sand": 50},
               {"sandstone_debris": 50, "brown_sandstone_debris": 50},
               {"faded_tan_sandstone": 55, "tan_sandstone": 25,
                "brown_sandstone": 20},
               {"faded_tan_sandstone": 60, "tan_sandstone": 40})],
        BED_WARM, soil_depth=4, clump=7.0, patch=40.0,
        notes="Five sub-biomes because a desert of one sand reads as a "
              "texture test. Sand is the soil, four deep, so dunes stay sand "
              "all the way through a cut."),

    "savanna": palette(
        ("#c2b458", "#9aa24a", W_WARM), C + "red_earth", "red_granite",
        [("grass_savanna", "Grass savanna", 45,
          {"grass_block": 45, "grassy_dirt": 25, "ochre_soil": 15,
           "coarse_red_dirt": 15}),
         ("tree_savanna", "Tree savanna", 25,
          {"grass_block": 40, "grassy_dirt": 20, "red_earth": 25,
           "shredded_leaves": 15}),
         ("bare_laterite", "Bare laterite", 15,
          {"red_earth": 50, "coarse_red_dirt": 30, "ochre_soil": 20}),
         ("kopje", "Kopje", 15,
          {"red_granite": 40, "light_brown_granite": 30, "brown_gravel": 30})],
        [BANK_WARM, COAST_WARM,
         slope({"coarse_red_dirt": 50, "red_earth": 50},
               {"brown_gravel": 50, "red_sand_and_gravel": 50},
               {"red_granite": 55, "light_brown_granite": 45},
               {"red_granite": 70, "light_brown_granite": 30})],
        BED_WARM, soil_depth=3,
        notes="Golden tint on grass_block does most of the work; laterite "
              "patches and granite kopjes stop it reading as a yellow plain."),

    "tropical_seasonal_forest": palette(
        ("#8fa747", "#6f9a38", W_WARM), C + "red_earth", "light_brown_granite",
        [("dry_leaf_floor", "Dry-season leaf fall", 40,
          {"shredded_leaves": 30, "autumnal_forest_floor": 25, "grass_block": 25,
           "red_earth": 20}),
         ("grass_understory", "Grass understory", 30,
          {"grass_block": 55, "grassy_dirt": 25, "red_earth": 20}),
         ("laterite", "Laterite", 15,
          {"red_earth": 45, "ochre_soil": 30, "coarse_red_dirt": 25}),
         ("rocky_gully", "Rocky gully", 15,
          {"sandy_soil": 40, "brown_gravel": 30, "mossy_brown_gravel": 30})],
        [BANK_WARM, COAST_WARM,
         slope({"red_earth": 50, "sandy_soil": 50},
               {"brown_gravel": 50, "mossy_brown_gravel": 50},
               {"light_brown_granite": 60, "brown_granite": 40},
               {"light_brown_granite": 100})],
        BED_WARM),

    "tropical_rainforest": palette(
        ("#4f9a34", "#3f8a2c", W_WARM), C + "clay_rich_mud", "mossy_limestone",
        [("leaf_litter", "Leaf litter", 40,
          {"mossy_forest_floor_with_roots": 35, "shredded_leaves": 30,
           "mossy_soil": 20, "muddy_autumnal_forest_floor": 15}),
         ("moss_floor", "Moss floor", 25,
          {"mossy_soil": 40, "mossy_forest_floor_with_roots": 30,
           "cushion_moss_covered_loamy_dirt": 30}),
         ("red_clay_floor", "Red clay floor", 20,
          {"red_earth": 30, "clay_rich_mud": 30, "mossy_soil": 20,
           "sandy_soil": 20}),
         ("swampy_floor", "Swampy floor", 15,
          {"mud": 35, "mossy_light_silt": 35, "thick_mud": 30})],
        [BANK_MUD, COAST_WARM,
         slope({"mossy_soil": 50, "red_earth": 50},
               {"mossy_brown_gravel": 60, "mossy_small_stones": 40},
               {"mossy_limestone": 50, "moss_covered_limestone": 50},
               {"moss_covered_limestone": 60, "mossy_limestone": 40})],
        BED_WARM,
        notes="Karst under rainforest: every rock band is moss-covered "
              "limestone, so the ground never goes grey."),

    # ---- Sea: ocean floors and the beach strips of ocean cells ------------------
    # Not biomes of their own -- the ocean/beach biomes stay vanilla. These are
    # what the surface is made of wherever the map's cell is marine: the sea
    # floor, and land that pokes above water inside an ocean cell (the beach).

    "sea_temperate": palette(
        ("#86a44f", "#6a9a3a", W_TEMPERATE), V + "sand", "gray_limestone",
        [("beach", "Sand beach", 70,
          {"sand": 60, "sand_and_gravel": 20, "sand_and_vegetation": 20}),
         ("shingle", "Shingle", 30,
          {"smooth_pebbles": 45, "sand_and_gravel": 35, "gravel": 20})],
        [shore("coast", [(1.5, {"wet_sand": 70, "wet_sand_and_gravel": 30})]),
         slope({"sand_and_gravel": 60, "gravel": 40},
               {"gravel": 50, "gray_limestone_gravel": 50},
               {"gray_limestone": 70, "gray_limestone_gravel": 30},
               {"gray_limestone": 100})],
        bed([(2, {"sand": 60, "wet_sand": 40}),
             (7, {"sand_and_gravel": 50, "sand": 30, "gravel": 20}),
             (99, {"dark_silt": 45, "gravel": 35, "mud": 20})]),
        soil_depth=4, clump=6.0, patch=40.0,
        notes="Ocean floor and beach strips of temperate seas."),

    "sea_cold": palette(
        ("#86a44f", "#6a9a3a", W_COLD), C + "gray_sand", "granite",
        [("grey_beach", "Grey sand", 55,
          {"gray_sand": 55, "light_gray_sand": 25, "smooth_pebbles": 20}),
         ("pebble_beach", "Pebble beach", 45,
          {"smooth_pebbles": 55, "gray_gravel": 30, "gray_sand": 15})],
        [shore("coast", [(1.5, {"wet_gray_sand": 60, "smooth_pebbles": 40})]),
         slope({"gray_gravel": 60, "smooth_pebbles": 40},
               {"gray_gravel": 60, "small_stones": 40},
               {"granite": 60, "weathered_granite": 40},
               {"granite": 100})],
        bed([(2, {"smooth_pebbles": 50, "wet_gray_sand": 50}),
             (7, {"gray_gravel": 50, "gray_sand": 50}),
             (99, {"dark_silt": 50, "gray_gravel": 50})]),
        soil_depth=4, clump=6.0, patch=40.0,
        notes="Cold and frozen seas: grey sand and pebbles, never yellow."),

    "sea_warm": palette(
        ("#86a44f", "#6a9a3a", W_WARM), C + "tan_sand", "faded_tan_sandstone",
        [("tan_beach", "Tan beach", 80, {"tan_sand": 75, "sand": 25}),
         ("shell_grit", "Shell grit", 20,
          {"sand_and_gravel": 45, "tan_sand": 40, "smooth_pebbles": 15})],
        [shore("coast", [(1.5, {"wet_tan_sand": 100})]),
         slope({"tan_sand": 50, "sandstone_debris": 50},
               {"sandstone_debris": 60, "brown_sandstone_debris": 40},
               {"faded_tan_sandstone": 70, "tan_sandstone": 30},
               {"faded_tan_sandstone": 100})],
        bed([(3, {"wet_tan_sand": 60, "tan_sand": 40}),
             (99, {"sand": 50, "tan_sand": 30, "dark_silt": 20})]),
        soil_depth=4, clump=7.0, patch=40.0,
        notes="Warm and lukewarm seas: tan sand to the drop-off."),
}

# Vanilla biome ids that keep their vanilla biome but take a sea palette.
SEA_BIOMES = {
    "minecraft:frozen_ocean": "sea_cold", "minecraft:deep_frozen_ocean": "sea_cold",
    "minecraft:cold_ocean": "sea_cold", "minecraft:deep_cold_ocean": "sea_cold",
    "minecraft:snowy_beach": "sea_cold", "minecraft:stony_shore": "sea_cold",
    "minecraft:ocean": "sea_temperate", "minecraft:deep_ocean": "sea_temperate",
    "minecraft:beach": "sea_temperate",
    "minecraft:lukewarm_ocean": "sea_warm", "minecraft:deep_lukewarm_ocean": "sea_warm",
    "minecraft:warm_ocean": "sea_warm",
}


def materials_used(p):
    """Every material a palette can place, in first-seen order."""
    seen = []

    def add(mix):
        for m in mix:
            if m not in seen:
                seen.append(m)

    for s in p["sub"]:
        add(s["mix"])
    for r in p["ramps"]:
        for b in r["bands"]:
            add(b["mix"])
    for b in p["bed"]["bands"]:
        add(b["mix"])
    add([p["stone"]])
    return seen


def resolve(material):
    return MATERIALS[material]
