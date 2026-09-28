"""
Remap the paleozoey regional tree packs (ena, asn, ind, sam, fl) onto Conquest.

The African pack got a species table by hand because it is only 42 species.
These five are 174 genera over 1107 files, and hand-writing that is neither
practical nor useful, because **the vanilla blocks carry no meaning here**. The
survey is blunt about it: `Acer` is built from oak leaves on birch wood,
`Salix` from birch leaves on spruce wood, `Taxodium` from oak leaves on oak
wood. Mapping the source block would produce nonsense.

What *is* reliable is the filename. Every file is `Genus_species<n>.schem`, so
the genus is the signal and the table below is keyed on it. A genus gives:

    (foliage, trunk family, accent family)

The accent family is for the trees that use two woods -- the baobab pattern, a
pale trunk with darker branches. Wood families in a model are ranked by voxel
count; the biggest becomes the trunk, anything else becomes the accent. Where a
genus has no meaningful second wood the accent is just the trunk again.

`SPECIES` overrides the genus where the species really does matter, which in
practice is the willows: `Salix_babylonica` is a weeping willow and Conquest has
`weeping_willow_leaves`, while `Salix_discolor` is a pussy willow and Conquest
has `goat_willow_leaves` over `goat_sallow_log`. Throwing both at one block
would waste two of the best matches in the whole set.

Conquest block facts this leans on, all measured rather than assumed:

  * Conquest has **no green birch, oak or maple leaf**. Birch and oak reuse the
    vanilla block (and so need `persistent=true`, or the canopy rots on the
    first random tick); for maple the nearest is `horse_chestnut_leaves`, which
    is at least palmate.
  * `caribbean_royal_palm_leaves` carries a `facing`, so it cannot be dropped in
    blind. `RADIAL_FACING` fills it from the frond's own position relative to
    the trunk, which is a direction-from-centre, not the branch-skeleton
    detection that was ruled out.
  * `conquest:cactus` is *not* the block for the columnar cacti (`Cereus`,
    `Leucostele`, built from bamboo_block): its model is a ground clump that
    overflows its own cube. Vanilla cactus is the stackable column, though
    nothing retextures it, so those nine files will read as vanilla.
"""

import collections

from .remap_afr import FAMILIES as AFR_FAMILIES, NO_AXIS, AGED_LEAVES, SIDES, OPPOSITE

# Extra families the regional packs need on top of the African ones.
FAMILIES = dict(AFR_FAMILIES)
FAMILIES.update({
    "larch":         ("conquest:larch_log",         "conquest:larch_log_bark",         "conquest:larch_log"),
    "norway_spruce": ("conquest:norway_spruce_log", "conquest:norway_spruce_log_bark", "conquest:norway_spruce_log"),
    "orange_pine":   ("conquest:orange_pine_log",   "conquest:orange_pine_log_bark",   "conquest:orange_pine_log"),
    "horse_chestnut": ("conquest:horse_chestnut_log", "conquest:horse_chestnut_log_bark", "conquest:horse_chestnut_log"),
    "apple":         ("conquest:apple_log",         "conquest:apple_log_bark",         "conquest:apple_log"),
    "transition_pine": ("conquest:transition_pine_log", "conquest:transition_pine_log_bark", "conquest:transition_pine_log"),
    # Columnar cacti are the whole plant, trunk and all. `conquest:cactus` is
    # NOT the block for it: its model is a decorative clump that overflows its
    # own cube (elements run to y=20.5), so stacking it into a 15-block Cereus
    # gives overlapping spikes. Vanilla cactus is the proper stackable column.
    # ACRP does not retexture it, so these will read as vanilla until Conquest
    # ships a columnar cactus -- 9 files, worth a look but not worth blocking.
    "cactus":        ("minecraft:cactus",           "minecraft:cactus",                "conquest:olive_log"),
})
# Families whose full block has no axis property.
NO_AXIS = set(NO_AXIS) | {"cactus"}

# Vanilla leaf blocks Conquest has no green equivalent for. They keep working,
# but they must be pinned or they decay: Conquest tags its logs (and even its
# *_log_branch blocks) into minecraft:logs, but vanilla decay only reaches
# distance 7 and these canopies are wider than that.
VANILLA_FOLIAGE = ("minecraft:oak_leaves", "minecraft:birch_leaves")

# Conquest leaves whose `facing` has to be filled from the model rather than
# copied, because the source block has no direction to copy.
RADIAL_FACING = frozenset((
    "conquest:caribbean_royal_palm_leaves",
    "conquest:old_caribbean_royal_palm_leaves",
))


class Genus:
    __slots__ = ("foliage", "trunk", "accent", "flower", "note")

    def __init__(self, foliage, trunk, accent=None, flower=None, note=""):
        self.foliage = foliage
        self.trunk = trunk
        self.accent = accent or trunk
        # What this genus's blossom becomes. Conquest ships six blossom blocks
        # and until now not one of them had a tree.
        self.flower = flower or foliage
        self.note = note


def _g(foliage, trunk, accent=None, flower=None, note=""):
    return Genus(foliage, trunk, accent, flower, note)


# Source leaves that mean "this tree is in flower" rather than "these are its
# leaves". The packs use both for blossom, so both route to Genus.flower.
FLOWER_SOURCE = frozenset((
    "minecraft:flowering_azalea_leaves", "minecraft:cherry_leaves"))
PINK = "conquest:pink_cherry_blossoms"
WHITE = "conquest:white_cherry_blossoms"
PURPLE = "conquest:purple_cherry_blossoms"


# Shorthand for the blocks used most.
DARK = "conquest:dark_deciduous_leaves"
DBEECH = "conquest:dark_beech_tree_leaves"
BEECH = "conquest:beech_tree_leaves"
ASH = "conquest:ash_tree_leaves"
OLIVE = "conquest:olive_tree_leaves"
POPLAR = "conquest:black_poplar_leaves"
HOLLY = "conquest:holly_leaves"
CHESTNUT = "conquest:horse_chestnut_leaves"
CHERRY = "conquest:cherry_tree_leaves"
ASPEN = "conquest:aspen_leaves"
DOWNY = "conquest:downy_willow_leaves"
CYPRESS = "conquest:cypress_leaves"
LCYPRESS = "conquest:light_cypress_leaves"
SPRUCE = "conquest:dark_spruce_needles"
PINE = "conquest:pine_needles"
LARCH = "conquest:larch_needles"
DATE = "conquest:date_palm_leaves"
ROYAL = "conquest:caribbean_royal_palm_leaves"
BANANA = "conquest:banana_tree_leaves"
CYCAD = "conquest:bushy_joshua_tree_leaves"
GORSE = "conquest:gorse_leaves"
CACTUS = "minecraft:cactus"
OAK_V = "minecraft:oak_leaves"
BIRCH_V = "minecraft:birch_leaves"


GENERA = {
    # --- conifers -----------------------------------------------------------
    "Abies":          _g(SPRUCE, "norway_spruce"),
    "Araucaria":      _g(CYPRESS, "pine", note="monkey puzzle; CR has nothing spiky enough"),
    "Cryptomeria":    _g(CYPRESS, "norway_spruce"),
    "Cunninghamia":   _g(CYPRESS, "pine"),
    "Cupressus":      _g(CYPRESS, "pine", note="Mediterranean cypress, an exact CR match"),
    "Fitzroya":       _g(CYPRESS, "orange_pine"),
    "Glyptostrobus":  _g(LCYPRESS, "willow", note="Chinese swamp cypress"),
    "Juniperus":      _g(LCYPRESS, "rowan"),
    "Larix":          _g(LARCH, "larch", note="exact CR match"),
    "Metasequoia":    _g(LCYPRESS, "orange_pine", note="dawn redwood, reddish bark"),
    "Picea":          _g(SPRUCE, "norway_spruce", note="exact CR match"),
    "Pilgerodendron": _g(CYPRESS, "pine"),
    "Pinus":          _g(PINE, "pine", note="exact CR match"),
    "Podocarpus":     _g(CYPRESS, "rowan"),
    "Saxegothea":     _g(CYPRESS, "pine"),
    "Sciadopitys":    _g(PINE, "pine"),
    "Taxodium":       _g(LCYPRESS, "willow", note="bald cypress; CR ships dead and "
                                                  "half-dead cypress for exactly this"),
    "Taxus":          _g(SPRUCE, "rowan", note="yew; reddish flaking bark"),
    "Torreya":        _g(SPRUCE, "pine"),
    "Tsuga":          _g(SPRUCE, "norway_spruce", note="hemlock"),
    "Agathis":        _g(CYPRESS, "mallorn", note="kauri, huge pale trunk"),

    # --- temperate broadleaf ------------------------------------------------
    "Acer":           _g(CHESTNUT, "ash", note="CR has no green maple leaf; "
                                               "horse chestnut is at least palmate"),
    "Aesculus":       _g(CHESTNUT, "horse_chestnut", note="exact CR match"),
    "Betula":         _g(BIRCH_V, "birch", note="CR has only autumnal birch; green is vanilla"),
    "Camphora":       _g(DARK, "beech"),
    "Carpinus":       _g(BEECH, "beech", note="hornbeam"),
    "Carya":          _g(ASH, "ash", note="hickory, pinnate"),
    "Castanea":       _g(DARK, "oak"),
    "Castanopsis":    _g(DARK, "beech"),
    "Celtis":         _g(BEECH, "ash"),
    "Cercidophyllum": _g(ASPEN, "aspen", note="katsura, round leaves"),
    "Cornus":            _g(CHERRY, "rowan", flower=WHITE, note="flowering dogwood"),
    "Fagus":          _g(BEECH, "beech", note="exact CR match"),
    "Ginkgo":         _g(POPLAR, "ash"),
    "Hamamelis":      _g(ASPEN, "goat_sallow"),
    "Juglans":        _g(ASH, "ash", note="walnut, pinnate"),
    "Liquidambar":    _g(CHESTNUT, "ash", note="sweetgum, star-shaped leaf"),
    "Liriodendron":   _g(POPLAR, "aspen", note="tulip tree"),
    "Magnolia":          _g(DARK, "beech", flower=WHITE),
    "Nothofagus":     _g(BEECH, "beech", note="southern beech"),
    "Nyssa":          _g(DARK, "willow", note="tupelo, a swamp tree"),
    "Platanus":       _g(CHESTNUT, "aspen", note="plane; aspen_log is the palest CR log, "
                                                 "for the mottled bark"),
    "Populus":        _g(POPLAR, "aspen", note="exact CR match"),
    "Prunus":            _g(CHERRY, "rowan", flower=PINK, note="exact CR match, blossom and all"),
    "Quercus":        _g(OAK_V, "oak", note="CR reuses vanilla oak for the full block"),
    "Sassafras":      _g(DARK, "ash"),
    "Tilia":          _g(BEECH, "beech", note="lime"),
    "Ulmus":          _g(ASH, "ash", note="elm"),
    "Ilex":           _g(HOLLY, "rowan", note="exact CR match"),
    "Berberis":       _g(GORSE, "goat_sallow", note="thorny shrub; gorse_leaves had no tree"),
    "Elaeagnus":      _g(DOWNY, "goat_sallow", note="oleaster, silvery"),
    "Pistacia":       _g(OLIVE, "olive"),
    "Wisteria":          _g("conquest:wisteria_leaves", "ash", flower="conquest:wisteria_blossoms", note="exact CR match, leaf and blossom"),
    "Drimys":         _g(DARK, "beech", note="winter's bark"),
    "Aextoxicon":     _g(DARK, "beech"),
    "Laurelia":       _g(DARK, "beech"),
    "Luma":           _g(BEECH, "orange_pine", note="arrayan, vivid orange bark"),
    "Quillaja":       _g(OLIVE, "olive"),
    "Xylosma":        _g(HOLLY, "goat_sallow"),
    "Feijoa":         _g(DOWNY, "goat_sallow"),
    "Ocotea":         _g(DARK, "beech"),
    "Phytolacca":     _g(POPLAR, "aspen"),
    "Hibiscus":       _g(DARK, "goat_sallow"),
    "Duranta":           _g(OLIVE, "goat_sallow", flower=PURPLE),
    "Eriobotrya":     _g(DARK, "rowan", note="loquat"),
    "Lagerstroemia":     _g(CHERRY, "aspen", flower=PINK, note="crape myrtle, smooth pale bark"),
    "Drypetes":       _g(DARK, "beech"),
    "Sorbus":         _g("conquest:rowan_leaves", "rowan", note="exact CR match"),

    # --- willows (species-level overrides below) ----------------------------
    "Salix":          _g(DOWNY, "willow"),

    # --- Mediterranean, arid and scrub --------------------------------------
    "Haloxylon":      _g(GORSE, "goat_sallow", note="saxaul, nearly leafless"),
    "Tamarix":        _g(DOWNY, "goat_sallow", note="tamarisk, feathery grey"),
    "Ziziphus":       _g(OLIVE, "olive", note="jujube"),
    "Prosopis":       _g(ASH, "acacia", note="mesquite"),
    "Vachellia":      _g(ASH, "acacia"),
    "Senegalia":      _g(OLIVE, "acacia"),
    "Acacia":         _g(ASH, "acacia"),
    "Anadenanthera":  _g(ASH, "acacia"),
    "Parapiptadenia": _g(ASH, "acacia"),
    "Enterolobium":   _g(ASH, "acacia"),
    "Lysiloma":       _g(ASH, "acacia"),
    "Albizia":        _g(ASH, "acacia"),
    "Pentaclethra":   _g(ASH, "acacia"),
    "Gleditsia":      _g(ASH, "acacia", note="honey locust"),
    "Schinopsis":     _g(OLIVE, "olive", note="quebracho"),
    "Schinus":        _g(ASH, "olive", note="pepper tree"),
    "Lithraea":       _g(OLIVE, "olive"),
    "Myracrodruon":   _g(OLIVE, "olive"),
    "Astronium":      _g(DARK, "ash"),
    "Bursera":        _g(ASH, "orange_pine", note="gumbo limbo, peeling red bark"),
    "Guaiacum":       _g(DARK, "olive", note="lignum vitae"),
    "Sideroxylon":    _g(OLIVE, "olive"),
    "Metopium":       _g(DARK, "orange_pine", note="poisonwood"),
    "Capparis":       _g(OLIVE, "goat_sallow", note="caper; almost all root in the model"),
    "Cereus":         _g(CACTUS, "cactus", note="columnar cactus, built from bamboo_block"),
    "Leucostele":     _g(CACTUS, "cactus", note="columnar cactus, built from bamboo_block"),

    # --- palms --------------------------------------------------------------
    "Roystonea":      _g(ROYAL, "palm", note="royal palm; CR has the exact block, and its "
                                             "facing is filled from the frond position"),
    "Cocos":          _g(DATE, "palm", note="coconut"),
    "Sabal":          _g(DATE, "palm", note="palmetto"),
    "Serenoa":        _g(DATE, "palm", note="saw palmetto, a ground shrub"),
    "Phoenix":        _g(DATE, "palm", note="exact CR match"),
    "Borassus":       _g(DATE, "palm"),
    "Areca":          _g(DATE, "palm"),
    "Cyrtostachys":   _g(DATE, "palm"),
    "Livistona":      _g(DATE, "palm"),
    "Nypa":           _g(DATE, "palm", note="nipa, a mangrove palm"),
    "Euterpe":        _g(DATE, "palm"),
    "Attalea":        _g(DATE, "palm"),
    "Astrocarym":     _g(DATE, "palm"),
    "Acrocomia":      _g(DATE, "palm"),
    "Mauritia":       _g(DATE, "palm"),
    "Jubaea":         _g(DATE, "palm"),
    "Ceroxylon":      _g(DATE, "palm", note="wax palm, the tallest palm there is"),
    "Socratea":       _g(DATE, "palm", note="walking palm, stilt roots"),
    "Calyptronoma":   _g(DATE, "palm"),
    "Musa":           _g(BANANA, "palm", note="banana, exact CR match"),
    "Cycas":          _g(CYCAD, "palm"),
    "bamboo":         _g("conquest:golden_bamboo", "palm", note="the plant, not a tree"),

    # --- mangrove and coastal ----------------------------------------------
    "Rhizopora":      _g(DARK, "willow", note="red mangrove; the prop roots become branches"),
    "Avicennia":      _g(DOWNY, "willow", note="grey mangrove"),
    "Bruguiera":      _g(DARK, "willow"),
    "Laguncularia":   _g(OLIVE, "willow", note="white mangrove"),
    "Conocarpus":     _g(DOWNY, "willow", note="buttonwood"),
    "Coccoloba":      _g(DARK, "beech", note="sea grape"),
    "Chrysobalanus":  _g(HOLLY, "olive", note="coco plum"),

    # --- tropical rainforest -------------------------------------------------
    "Ficus":          _g(DARK, "mallorn", note="banyan, pale trunk"),
    "Ceiba":          _g(DARK, "mallorn", note="kapok"),
    "Swietenia":      _g(DBEECH, "mallorn", note="mahogany"),
    "Cedrela":        _g(DBEECH, "ash"),
    "Shorea":         _g(DARK, "mallorn"),
    "Dipterocarpus":  _g(DARK, "mallorn"),
    "Hopea":          _g(DARK, "mallorn"),
    "Tectona":        _g(DARK, "ash", note="teak, very large leaves"),
    "Terminalia":     _g(DARK, "ash"),
    "Toona":          _g(DBEECH, "ash"),
    "Diospyros":      _g(HOLLY, "dark_oak", note="ebony"),
    "Dalbergia":      _g(DARK, "willow", note="rosewood"),
    "Pterocarpus":    _g(DBEECH, "willow", note="padauk"),
    "Erythrina":      _g(ASH, "ash", note="coral tree"),
    "Theobroma":      _g(DARK, "rowan", note="cacao"),
    "Hevea":          _g(DARK, "beech", note="rubber"),
    "Artocarpus":     _g(DARK, "ash", note="jackfruit"),
    "Mangifera":      _g(DARK, "ash", note="mango"),
    "Durio":          _g(DARK, "ash", note="durian"),
    "Garcinia":       _g(HOLLY, "ash"),
    "Litchi":         _g(DARK, "ash"),
    "Manilkara":      _g(HOLLY, "ash"),
    "Madhuca":        _g(DARK, "ash"),
    "Melia":          _g(ASH, "ash", note="neem relative, pinnate"),
    "Epicharis":      _g(DARK, "ash"),
    "Vitex":          _g(CHESTNUT, "ash", note="palmate leaflets"),
    "Xylia":          _g(ASH, "acacia"),
    "Chloroxylon":    _g(ASH, "ash"),
    "Hardwickia":     _g(ASH, "acacia"),
    "Lannea":         _g(ASH, "ash"),
    "Intsia":         _g(DARK, "ash"),
    "Alstonia":       _g(DARK, "beech"),
    "Aleurites":      _g(CHESTNUT, "ash", note="candlenut, palmate"),
    "Cinnamomum":     _g(DARK, "beech"),
    "Santalum":       _g(OLIVE, "olive", note="sandalwood"),
    "Bombax":         _g(DARK, "mallorn", note="silk cotton, a kapok relative"),
    "Anacardium":     _g(DARK, "ash", note="cashew"),
    "Annona":         _g(DARK, "ash"),
    "Caryocar":       _g(DARK, "mallorn"),
    "Caropa":         _g(DARK, "ash"),
    "Pachira":        _g(CHESTNUT, "mallorn", note="money tree, palmate"),
    "Sterculia":      _g(DARK, "mallorn"),
    "Paubrasilia":    _g(ASH, "orange_pine", note="brazilwood, red heartwood"),
    "Handroanthus":      _g(ASH, "ash", flower=PINK, note="ipe"),
    "Jacaranda":         _g(ASH, "ash", flower=PURPLE, note="CR has a purple blossom after all"),
    "Asimina":        _g(DARK, "beech", note="pawpaw"),
    "Calophyllum":    _g(DARK, "beech"),
    "Ochroma":        _g(CHESTNUT, "mallorn", note="balsa"),
    "Gnetum":         _g(DARK, "willow"),
    "Eucalyptus":     _g(DOWNY, "aspen", note="CR has no eucalypt foliage at all"),
    "Rafflesia":      _g(DARK, "ash", note="a parasitic flower, not a tree; 1 file"),
}


# Where the species really does change the answer. Keyed on the full
# `Genus_species` stem.
SPECIES = {
    "Salix_babylonica":   _g("conquest:weeping_willow_leaves", "willow",
                             note="weeping willow, exact CR match"),
    "Salix_discolor":     _g("conquest:goat_willow_leaves", "goat_sallow",
                             note="pussy willow; CR has both the leaf and goat_sallow_log"),
    "Salix_nigra":        _g(DOWNY, "willow", note="black willow"),
    "Salix_humboldtiana": _g("conquest:weeping_willow_leaves", "willow"),
    "Salix_mucronata":    _g(DOWNY, "willow"),
    "Taxodium_ascendens": _g(LCYPRESS, "willow", note="pond cypress"),
    "Pinus_longaeva":     _g("conquest:dead_pine_needles", "transition_pine",
                             note="bristlecone; mostly dead wood"),
}


# Blocks that mean the same thing whatever genus they turn up in.
GLOBAL = {
    "minecraft:mangrove_propagule": ("conquest:hanging_roots", {}),
    "minecraft:decorated_pot":      ("conquest:banana_bundle", {}),
    "minecraft:melon":              ("conquest:banana_bundle", {}),
    "minecraft:red_mushroom_block": ("minecraft:red_mushroom_block", {}),
    "minecraft:moss_block":         ("conquest:moss_on_the_ground", {}),
    "minecraft:moss_carpet":        ("conquest:moss_on_the_ground", {}),
    # Erythrina is the only user, and it is the coral tree's red flowers.
    "minecraft:red_carpet":         ("conquest:red_cherry_blossoms", {}),
    "minecraft:crimson_roots":      ("conquest:hanging_roots", {}),
    "minecraft:bamboo":             ("conquest:golden_bamboo", {}),
}

# Vanilla ground blocks: left alone. The resource pack retextures these and a
# tree that carries a lump of its own soil should keep it.
GROUND = frozenset(("minecraft:grass_block", "minecraft:podzol", "minecraft:dirt"))


def short(name):
    return name.split(":", 1)[-1]


def wood_family(name):
    """`minecraft:stripped_jungle_wood` -> ("jungle", "bark")."""
    n = short(name)
    if n.startswith("stripped_"):
        n = n[len("stripped_"):]
    if n in ("bamboo_block", "warped_stem", "crimson_stem"):
        return "bamboo", "log"
    if n in ("warped_hyphae", "crimson_hyphae"):
        return "bamboo", "bark"
    if n in ("mangrove_roots", "muddy_mangrove_roots"):
        return "roots", "roots"
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


def lookup(stem):
    """`Salix_babylonica3` -> the Genus for it, species override first."""
    base = stem
    spec = SPECIES.get(base)
    if spec is not None:
        return spec
    return GENERA.get(base.split("_")[0])


def rank_woods(counts):
    """
    Vanilla wood family -> "trunk" or "accent", by how much of the model it is.

    The two-wood trees (the baobab pattern) are the reason this exists: the
    biggest wood family is the trunk and anything else is the accent, so a pale
    trunk with darker branches survives instead of collapsing to one block.
    """
    ranked = [f for f, _ in collections.Counter(counts).most_common()]
    return {f: ("trunk" if i == 0 else "accent") for i, f in enumerate(ranked)}


def make_mapper(genus, roles, centre_xz=None):
    """
    Build the per-blockstate rewrite function for one model.

    `roles` maps a vanilla wood family onto "trunk"/"accent" (see rank_woods).
    `centre_xz` is the trunk's (x, z), needed only for the radial-facing palms.
    """
    fams = {"trunk": FAMILIES[genus.trunk], "accent": FAMILIES[genus.accent]}
    names = {"trunk": genus.trunk, "accent": genus.accent}
    prefix = fams["trunk"][2]
    aged = genus.foliage in AGED_LEAVES
    radial = genus.foliage in RADIAL_FACING
    vanilla_leaf = genus.foliage in VANILLA_FOLIAGE

    def foliage_props(pos):
        if vanilla_leaf:
            # Dropped properties default to persistent=false, distance=7, and
            # the whole canopy rots on the first random tick.
            return {"persistent": "true", "distance": "1", "waterlogged": "false"}
        if radial and centre_xz is not None and pos is not None:
            dx = pos[0] - centre_xz[0]
            dz = pos[2] - centre_xz[1]
            if abs(dx) >= abs(dz):
                return {"facing": "east" if dx >= 0 else "west"}
            return {"facing": "south" if dz >= 0 else "north"}
        if radial:
            return {"facing": "north"}
        return {"age": "0"} if aged else {}

    def mapper(name, props, pos=None):
        if name in GROUND:
            return name, props
        glob = GLOBAL.get(name)
        if glob is not None:
            return glob

        kind = classify(name)

        if kind == "trunk":
            fam, form = wood_family(name)
            if form == "roots":
                # Mangrove prop roots are structural, not a ground plant: the
                # small branch is the Conquest shape that reads as a root strut.
                return prefix + "_small_branch", {d: "false" for d in SIDES + ("up",)}
            target = fams[roles.get(fam, "trunk")]
            which = names[roles.get(fam, "trunk")]
            block = target[1] if form == "bark" else target[0]
            if which in NO_AXIS:
                return block, {}
            return block, ({"axis": props["axis"]} if "axis" in props else {})

        if kind == "foliage":
            if name in FLOWER_SOURCE and genus.flower != genus.foliage:
                return genus.flower, {}
            return genus.foliage, foliage_props(pos)

        if kind == "vine":
            return "conquest:lianas", {d: props.get(d, "false")
                                       for d in SIDES + ("up",)}

        if kind == "fence":
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
            if aged:
                return genus.foliage, {"age": "7"}
            return "conquest:banana_bundle", {}

        return None

    return mapper
