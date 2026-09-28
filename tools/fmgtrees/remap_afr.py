"""
Remap the paleozoey African tree schematics from vanilla blocks onto Conquest.

Unlike the Axiom pack, this one *is* literal: every one of the 42 species is
built from a single leaf block over one or two wood families, with no stand-ins
to decode. The survey is what makes that safe to rely on -- 42 distinct blocks
across 165 files, and `Vachellia_erioloba` really is acacia_leaves on acacia
wood. So the mapping is per species (which Conquest tree it becomes) crossed
with per-block role, exactly like remap_axiom, but the species table carries a
*wood family* map rather than one trunk block, because the baobabs use a pale
trunk wood and a darker branch wood and that distinction is the whole silhouette.

Three things drove the block choices, all measured rather than guessed:

  * **Conquest leaves are biome-tinted.** Every leaf block used here except
    `cypress_leaves` has a tintindex, so the texture is luminance and the biome
    supplies the hue. `ash_tree_leaves` is near-greyscale, which is why it does
    the acacia work: in a savanna it comes out gold, in miombo it comes out
    green, from one block.
  * **Conquest has no acacia, palm, oak, birch, dark oak or jungle *log*.** It
    reuses the vanilla block and ships only the accessories
    (`conquest:acacia_log_branch`, `_slab`, `_stump`...), which is why FAMILIES
    keeps the full block and the family prefix apart. Palm is the exception:
    `conquest:palm_tree_trunk` is a real block -- and it has no `axis`, so the
    property has to be dropped rather than carried.
  * **No Conquest stripped logs exist at all** (6 ids, all nether stems), so
    `stripped_jungle_log` and `jungle_log` collapse onto the same family and the
    pale/dark distinction has to be made by picking different families.

The pack also contains a handful of decorative oddities -- fence, button, cocoa,
decorated_pot, mangrove_roots -- that are shape hacks rather than blocks. They
are mapped by hand and listed separately in the report, because they are the
most likely thing to want a second look in game.
"""

# ---------------------------------------------------------------------------
# Wood families: (full log block, all-bark block, Conquest accessory prefix)
#
# The full block is vanilla wherever Conquest reuses vanilla, and the all-bark
# block is `conquest:*_log_bark` only for the 17 families that actually have one
# (checked against the registry) -- otherwise the vanilla `*_wood` stays.
# ---------------------------------------------------------------------------
FAMILIES = {
    "ash":         ("conquest:ash_log",         "conquest:ash_log_bark",         "conquest:ash_log"),
    "aspen":       ("conquest:aspen_log",       "conquest:aspen_log_bark",       "conquest:aspen_log"),
    "beech":       ("conquest:beech_log",       "conquest:beech_log_bark",       "conquest:beech_log"),
    "goat_sallow": ("conquest:goat_sallow_log", "conquest:goat_sallow_log_bark", "conquest:goat_sallow_log"),
    "mallorn":     ("conquest:mallorn_log",     "conquest:mallorn_log_bark",     "conquest:mallorn_log"),
    "olive":       ("conquest:olive_log",       "conquest:olive_log_bark",       "conquest:olive_log"),
    "rowan":       ("conquest:rowan_log",       "conquest:rowan_log_bark",       "conquest:rowan_log"),
    "willow":      ("conquest:willow_log",      "conquest:willow_log_bark",      "conquest:willow_log"),
    "pine":        ("conquest:pine_log",        "conquest:pine_log_bark",        "conquest:pine_log"),
    # Conquest reuses the vanilla full block for these and ships the accessories.
    "acacia":      ("minecraft:acacia_log",     "minecraft:acacia_wood",         "conquest:acacia_log"),
    "oak":         ("minecraft:oak_log",        "minecraft:oak_wood",            "conquest:oak_log"),
    "dark_oak":    ("minecraft:dark_oak_log",   "minecraft:dark_oak_wood",       "conquest:dark_oak_log"),
    "birch":       ("minecraft:birch_log",      "minecraft:birch_wood",          "conquest:birch_log"),
    # palm_tree_trunk is a plain block with no properties -- axis is dropped.
    "palm":        ("conquest:palm_tree_trunk", "conquest:palm_tree_trunk",      "conquest:palm_log"),
}

# Conquest families whose full block has no axis property, so the source axis
# cannot be carried over.
NO_AXIS = {"palm"}


class Spec:
    """One African species: what its leaves become and what its woods become."""

    def __init__(self, common, foliage, wood, note=""):
        self.common = common
        self.foliage = foliage
        self.wood = wood          # vanilla wood family -> Conquest family
        self.note = note


def _s(common, foliage, wood, note=""):
    return Spec(common, foliage, wood, note)


# ---------------------------------------------------------------------------
# The species table.
#
# Leaf choices lean on Conquest tinting: `ash_tree_leaves` for anything with
# fine pinnate foliage (the acacias, the miombo legumes, the baobabs),
# `olive_tree_leaves` for the small grey-leaved arid trees, `dark_deciduous`
# and `dark_beech` for closed rainforest canopy, `holly_leaves` for the glossy
# understorey, `date_palm`/`banana` for the palms and `joshua` for the cycads.
#
# Trunks are picked off the measured bark colours: aspen_log is the palest
# Conquest log (166,168,155) so it carries the fever tree and the pale baobabs,
# beech_log and mallorn_log are the mid greys for the big smooth trunks, and
# olive and goat_sallow are the dry scrub browns.
# ---------------------------------------------------------------------------
SPECIES = {
    # --- baobabs: huge smooth pale trunks, sparse crowns --------------------
    "Adansonia_digitata":       _s("African baobab", "conquest:ash_tree_leaves",
                                   {"spruce": "beech", "oak": "ash"},
                                   "pale trunk wood and darker branch wood kept apart"),
    "Adansonia_grandidieri":    _s("Grandidier baobab", "conquest:olive_tree_leaves",
                                   {"jungle": "aspen", "acacia": "beech"},
                                   "pale trunk wood and darker branch wood kept apart"),
    "Adansonia_suarezensis":    _s("Suarez baobab", "conquest:ash_tree_leaves",
                                   {"cherry": "beech"}),
    "Adansonia_za":             _s("za baobab", "conquest:olive_tree_leaves",
                                   {"jungle": "beech"}),

    # --- savanna thorn trees ------------------------------------------------
    "Vachellia_erioloba":       _s("camelthorn", "conquest:ash_tree_leaves",
                                   {"acacia": "acacia"}),
    "Vachellia_tortilis":       _s("umbrella thorn", "conquest:ash_tree_leaves",
                                   {"acacia": "acacia"}),
    "Vachellia_xanthophloea":   _s("fever tree", "conquest:ash_tree_leaves",
                                   {"birch": "aspen"},
                                   "aspen_log is the palest CR log, for the yellow-green bark"),
    "Senegalia_senegal":        _s("gum arabic", "conquest:olive_tree_leaves",
                                   {"acacia": "acacia"}),
    "Faidherbia_albida":        _s("apple-ring acacia", "conquest:ash_tree_leaves",
                                   {"acacia": "acacia"}),

    # --- miombo and Sudanian woodland --------------------------------------
    "Brachystegia_spiciformis": _s("msasa", "conquest:ash_tree_leaves",
                                   {"jungle": "ash"}),
    "Julbernardia_globiflora":  _s("munondo", "conquest:ash_tree_leaves",
                                   {"oak": "oak"}),
    "Afzelia_africana":         _s("African mahogany bean", "conquest:black_poplar_leaves",
                                   {"jungle": "ash"}),
    "Colophospermum_mopane":    _s("mopane", "conquest:wisteria_leaves",
                                   {"acacia": "acacia"},
                                   "wisteria_leaves for the butterfly leaf"),
    "Sclerocarya_birrea":       _s("marula", "conquest:black_poplar_leaves",
                                   {"acacia": "goat_sallow"}),
    "Vitellaria_paradoxa":      _s("shea", "conquest:black_poplar_leaves",
                                   {"jungle": "ash"}),
    "Kigelia_africana":         _s("sausage tree", "conquest:black_poplar_leaves",
                                   {"spruce": "ash"}),
    "Delonix_regia":            _s("flamboyant", "conquest:ash_tree_leaves",
                                   {"birch": "aspen"},
                                   "bipinnate; CR has no red flowering leaf"),
    "Stereospermum_kunthianum": _s("pink jacaranda", "conquest:ash_tree_leaves",
                                   {"acacia": "goat_sallow"}),

    # --- arid scrub and semi-desert ----------------------------------------
    "Balanites_aegyptiaca":     _s("desert date", "conquest:olive_tree_leaves",
                                   {"spruce": "goat_sallow"}),
    "Salvadora_persica":        _s("toothbrush tree", "conquest:olive_tree_leaves",
                                   {"oak": "olive"}),
    "Combretum_imberbe":        _s("leadwood", "conquest:olive_tree_leaves",
                                   {"acacia": "goat_sallow"}),
    "Sideroxylon_spinosum":     _s("argan", "conquest:olive_tree_leaves",
                                   {"oak": "olive"},
                                   "argan really is olive-like, so CR olive is the right block"),
    "Uapaca_bojeri":            _s("tapia", "conquest:olive_tree_leaves",
                                   {"pale_oak": "goat_sallow"},
                                   "source uses pale_oak, which does not exist before 1.21.4"),

    # --- palms --------------------------------------------------------------
    "Phoenix_canariensis":      _s("Canary date palm", "conquest:date_palm_leaves",
                                   {"jungle": "palm"}),
    "Borassus_aethiopum":       _s("African fan palm", "conquest:date_palm_leaves",
                                   {"jungle": "palm"}),
    "Hyphaene_compressa":       _s("doum palm", "conquest:date_palm_leaves",
                                   {"jungle": "palm"}),
    "Raphia_farinifera":        _s("raffia palm", "conquest:date_palm_leaves",
                                   {"jungle": "palm"}),
    "Beccariophoenix_alfredii": _s("Madagascar coconut palm", "conquest:date_palm_leaves",
                                   {"jungle": "palm"}),
    "Bismarckia_nobilis":       _s("silver fan palm", "conquest:downy_willow_leaves",
                                   {"jungle": "palm"},
                                   "downy willow is the palest CR leaf, for the silver fronds"),
    "Ravenala_madagascariensis": _s("traveller palm", "conquest:banana_tree_leaves",
                                    {"bamboo": "palm"}),

    # --- cycads -------------------------------------------------------------
    "Cycas_thouarsii":          _s("Thouars cycad", "conquest:bushy_joshua_tree_leaves",
                                   {"jungle": "palm"}),
    "Encephalartos_laurentianus": _s("giant cycad", "conquest:joshua_tree_leaves",
                                     {"jungle": "palm"}),

    # --- closed rainforest --------------------------------------------------
    "Ceiba_pentandra":          _s("kapok", "conquest:dark_deciduous_leaves",
                                   {"jungle": "mallorn"},
                                   "mallorn_log for the pale buttressed giant"),
    "Khaya_ivoriensis":         _s("African mahogany", "conquest:dark_beech_tree_leaves",
                                   {"jungle": "mallorn"}),
    "Milicia_excelsa":          _s("iroko", "conquest:dark_deciduous_leaves",
                                   {"jungle": "beech"}),
    "Diospyros_crassiflora":    _s("African ebony", "conquest:holly_leaves",
                                   {"dark_oak": "dark_oak"}),
    "Pterocarpus_soyauxii":     _s("padauk", "conquest:dark_beech_tree_leaves",
                                   {"mangrove": "willow"}),
    "Pericopsis_elata":         _s("afrormosia", "conquest:dark_deciduous_leaves",
                                   {"acacia": "ash"}),
    "Prunus_africana":          _s("red stinkwood", "conquest:dark_beech_tree_leaves",
                                   {"cherry": "ash"}),
    "Cola_nitida":              _s("kola", "conquest:dark_deciduous_leaves",
                                   {"jungle": "ash"}),
    "Dalbergia_baronii":        _s("Madagascar rosewood", "conquest:dark_deciduous_leaves",
                                   {"mangrove": "willow"}),
    "Gilbertiodendron_dewevrei": _s("limbali", "conquest:dark_deciduous_leaves",
                                    {"jungle": "beech"}),

    # --- understorey --------------------------------------------------------
    "Chrysobalanus_icaco":      _s("coco plum", "conquest:holly_leaves",
                                   {"acacia": "olive"}),
    "Coffea_liberica":          _s("Liberian coffee", "conquest:holly_leaves",
                                   {"spruce": "rowan"}),

    # --- montane conifer ----------------------------------------------------
    "Podocarpus_milanjianus":   _s("yellowwood", "conquest:cypress_leaves",
                                   {"oak": "rowan"},
                                   "a true conifer; cypress_leaves is the only untinted "
                                   "CR leaf used here, so it stays dark in a savanna"),
}

# Shape hacks that are the same block whatever species they turn up in.
ODDITIES = {
    # Tangled root flare at the base of an umbrella thorn.
    "minecraft:mangrove_roots": ("conquest:hanging_roots", {}),
    # The sausage tree fruit, hung off the branches.
    "minecraft:decorated_pot":  ("conquest:banana_bundle", {}),
}

# Conquest leaf blocks that carry an `age` (0-6 identical, 7 fruiting).
AGED_LEAVES = frozenset((
    "conquest:date_palm_leaves", "conquest:banana_tree_leaves",
    "conquest:olive_tree_leaves", "conquest:holly_leaves",
    "conquest:apple_tree_leaves", "conquest:cherry_tree_leaves",
    "conquest:rowan_leaves", "conquest:grape_vine_leaves",
    "conquest:lemon_tree_leaves", "conquest:orange_tree_leaves",
    "conquest:pear_tree_leaves", "conquest:plum_tree_leaves",
))

SIDES = ("north", "south", "east", "west")
# A wall button faces away from what it is stuck to, so the branch that
# replaces it has to connect on the opposite side.
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}


def short(name):
    return name.split(":", 1)[-1]


def wood_family(name):
    """`minecraft:stripped_jungle_wood` -> ("jungle", "bark")."""
    n = short(name)
    if n.startswith("stripped_"):
        n = n[len("stripped_"):]
    if n == "bamboo_block":
        return "bamboo", "log"
    if n.endswith("_wood"):
        return n[:-len("_wood")], "bark"
    if n.endswith("_log"):
        return n[:-len("_log")], "log"
    return None, None


def classify(name):
    n = short(name)
    if n.endswith("_leaves"):
        return "foliage"
    if n == "vine":
        return "vine"
    if n.endswith("_fence"):
        return "fence"
    if n.endswith("_button"):
        return "button"
    if n == "cocoa":
        return "fruit"
    if wood_family(name)[0]:
        return "trunk"
    return None


def make_mapper(spec, unmapped):
    """Build the per-blockstate rewrite function for one species."""
    fams = {src: FAMILIES[dst] for src, dst in spec.wood.items()}
    # The first wood family listed is the trunk; it also owns the accessories.
    prefix = FAMILIES[next(iter(spec.wood.values()))][2]
    aged = spec.foliage in AGED_LEAVES

    def mapper(name, props):
        odd = ODDITIES.get(name)
        if odd is not None:
            return odd

        kind = classify(name)

        if kind == "trunk":
            fam, form = wood_family(name)
            target = fams.get(fam)
            if target is None:
                unmapped[name] += 1
                return None
            block = target[1] if form == "bark" else target[0]
            if spec.wood[fam] in NO_AXIS:
                return block, {}
            return block, ({"axis": props["axis"]} if "axis" in props else {})

        if kind == "foliage":
            # Conquest leaves are plain blocks with no distance/persistent, so
            # they cannot decay at all -- nothing further to do here. The
            # vanilla-leaf persistent=true case the Axiom pack needs does not
            # arise, because every species in this table lands on a CR leaf.
            return spec.foliage, ({"age": "0"} if aged else {})

        if kind == "vine":
            # conquest:lianas has exactly vanilla vine's five booleans.
            return "conquest:lianas", {d: props.get(d, "false")
                                       for d in SIDES + ("up",)}

        if kind == "fence":
            # Every fence in the pack is a free-standing post: a thin stalk,
            # not a fence. The small branch is the closest Conquest shape.
            sides = {d: props.get(d, "false") for d in SIDES}
            sides["up"] = "false"
            return prefix + "_small_branch", sides

        if kind == "button":
            facing = props.get("facing", "north")
            sides = {d: "false" for d in SIDES}
            sides[OPPOSITE.get(facing, "north")] = "true"
            sides["up"] = "false"
            return prefix + "_small_branch", sides

        if kind == "fruit":
            # age=7 is the fruiting model on every Conquest fruit leaf, so a
            # date palm can keep its dates. Where the species landed on a leaf
            # with no age the fruit would just become more canopy and vanish,
            # so it falls back to a hanging bundle instead.
            if aged:
                return spec.foliage, {"age": "7"}
            return "conquest:banana_bundle", {}

        unmapped[name] += 1
        return None

    return mapper
