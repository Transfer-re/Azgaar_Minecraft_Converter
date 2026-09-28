# Conquest Reforged content — plan and status

Last updated 2026-09-20.

This is the working document for the Conquest Reforged variant of the FMG
generator. The terrain module (heightmap, rivers, roads, towns) is shared with
the vanilla/WWOO version and is not touched by any of this — everything here is
content: which biomes exist, what grows in them, and what they are made of.


## 0. Where things stand

| | |
|---|---|
| Tree conversion (QF pack) | **done** — 874 trees, lossless, verified |
| Per-biome design files | **done** — 29 biomes, both packs, all ids validated |
| Conquest block catalog | **done** — 18,249 ids from the real 1.20.1 jar |
| Axiom pack remap | **done and reviewed in game** — 428 blueprints, see §5 |
| Axiom → NBT | **done** — 428 trees, combined index with QF |
| African pack remap | **done, not yet reviewed in game** — 165 trees, 42 species, see §5 |
| African → NBT + `.bp` | **done** — 165 of each |
| Regional packs (ena/asn/ind/sam/fl) | **done, not yet reviewed in game** — 1107 trees, 174 genera, see §5 |
| Combined tree index | **2574 trees / 355 groups** across 8 packs |
| Bush/shrub pack extraction | not started — it is a world save, see §5 |
| Ground palettes (layer blocks) | **designed, not wired** — 29 biomes, 115 sub-biomes, slope/shore/altitude ramps; see `palettes.md` |
| Placement feature (Java) | not started — see §6 |
| Biome/feature datapack JSON | not started — generated from §4 files |

**Open blocker: Minecraft version.** Conquest Reforged in your ArdaCraft profile
is `ConquestReforged-fabric-1.20.1-1.7.0.jar`. This mod targets **1.21.4**. They
cannot run in the same instance. Everything in this document can be built and
checked without resolving that, but a playable Conquest world needs either a
1.21.x Conquest build or an FMG backport to 1.20.1. Worth deciding before the
Java placement work (§6), not before then.


## 1. Two questions, answered

### Mixed species per biome — yes, and it is already how these files work

The worry was right: one species per region would look planted. The existing
region-coherent mapper picks one *biome* per contiguous Azgaar region, but
species are chosen **per tree**, from a weighted list inside the biome. So
`oak_woodland` is 55% English Oak, 20% Sugar Maple, 15% Horse Chestnut, 10%
Aspen — every stand comes out mixed, and two oak woodlands do not look identical.

Three biomes exist specifically for this:

- `mixed_broadleaf_forest` — an even five-way mix, for large deciduous regions
  where any dominant species would read as flat.
- `mixed_conifer_broadleaf` — the transition band. Drop it wherever a deciduous
  region borders a taiga one; it is the single thing that stops that edge
  looking like a cut line.
- `autumn_broadleaf_forest` — seven entries, including all four beech colours.

**Still worth adding later:** a low-frequency dominance noise that biases the
species roll spatially, so you get a beech-heavy patch inside an oak wood rather
than an even statistical smear. Cheap to add once the placement feature exists.

### Branch directionality — not needed for the QF pack, needed for Axiom

This turned out much better than expected. **The QF trees are already built out
of Conquest blocks.** A single XL oak uses 95 palette entries including
`diagonal_oak_branch_22[facing=north,toggle=1,type=bottom]`,
`thick_diagonal_oak_branch_45_connector`, `oak_branch_tip_2[facing=west,toggle=2]`,
`oak_log_vertical_quarter`, `oak_log_pillar` and mossy variants of all of them.

Every one of those blockstate strings — facings, toggles, connector types —
is stored in the schematic and copied through verbatim. **No geometry is
inferred anywhere in the converter.** Conversion is a pure container change,
`.schem` → structure NBT, and it round-trips exactly (§2).

Directionality only becomes a problem for the **Axiom** pack, which is
vanilla-blocked. See §5 for how far that is worth taking.


## 2. Tree conversion — done

```
python -m fmgtrees.convert_qf "<QF zip>" <out-dir> [--registry <ids.txt>]
```

Run from `tools/`. Reads straight out of the downloaded zip; no extraction step.

Output lives in **`run/config/fantasymapgenerator/trees/conquest/`**, *not* in
`src/main/resources` — the QF readme says "do not redistribute", so these must
not be baked into the mod jar. If you never share the jar, moving them back is
a one-line change to the converter invocation.

```
  <species>/<species>_<colour>_<size>_<id>.nbt
  index.json    species, colour, size, footprint, height, trunk anchor
```

Result: **874 trees, 0 failures, 344 distinct block ids, every id present in the
real Conquest 1.20.1 registry.** 12 randomly sampled trees were read back out of
the written NBT and compared block-for-block against the source schematic — all
12 identical, including every branch facing.

| species | colours | trees | height range |
|---|---|---|---|
| english_oak | green, autumnal | 92 | 3–24 |
| european_beech | green, autumnal, bright_autumnal, faded_autumnal | 184 | 3–27 |
| european_larch | green, orange, yellow | 138 | 3–29 |
| horse_chestnut | green, autumnal | 92 | 3–24 |
| norway_spruce | dark, light, dead | 138 | 3–26 |
| quaking_aspen | green, autumnal | 92 | 5–27 |
| scottish_pine | green | 46 | 3–27 |
| sugar_maple | green, autumnal | 92 | 3–24 |

Air is dropped rather than written, so placing a tree never punches holes in the
terrain around it. `index.json` records a trunk anchor per tree — the centroid
of its lowest occupied layer — which is the column the placer seats on the
ground.


## 3. Conquest block catalog

`docs/conquest/conquest_blocks_1.18.2.txt` — 14,112 ids, scraped from the
bush-pack world's `level.dat`.

The authoritative list is the **18,249 ids in the 1.20.1 jar**, regenerated with:

```
unzip -l ConquestReforged-fabric-1.20.1-1.7.0.jar \
  | grep -oE 'assets/conquest/blockstates/[a-z0-9_]+\.json' \
  | sed 's|.*/||;s|\.json||' | sort -u > cr_blocks.txt
```

Both converters take `--registry` and report unknown ids rather than failing, so
a version mismatch shows up at conversion time instead of as missing blocks in a
world.

Useful things found in it that the 1.18.2 dump did not have: `cypress_leaves`,
`black_poplar_leaves`, `mangrove_ground_leaves`, `eucalyptus_ground_leaves`, and
a full set of **`*_ground_leaves`** litter carpets matched to each tree species —
which is why `oak_woodland` floors with `oak_ground_leaves` and
`autumn_broadleaf_forest` with `autumnal_beech_tree_ground_leaves`.


## 4. The biomes — `docs/conquest/biomes/*.json`

One file per biome. These are the editable source of truth; the datapack JSON is
generated from them later, not hand-written. Regenerate with:

```
python -m fmgtrees.make_biomes --registry <ids.txt>
```

Every block id and every tree group in all 29 files validates against the real
registry and against the combined tree index.

Each file carries: `azgaar` (which of the 13 categories it serves), `surface`
(top/under block), `trees` (density, size mix, weighted `species`, plus `stumps`
with its own density), `groundCover` (weighted), `props`, and a `notes` line
saying what the biome is *for*.

Tree groups are `<pack>/<species>/<colour>` from the combined index. QF stays
dominant wherever it covers the role — it is the better-looking half — and Axiom
carries the biomes where QF has nothing. Typical forest now draws on 150–350
trees instead of 46–140.

**24 ready, 5 blocked.**

| Azgaar category | biomes |
|---|---|
| Temperate deciduous forest | `oak_woodland`, `beech_forest`, `mixed_broadleaf_forest`, `autumn_broadleaf_forest`, `aspen_grove`, **`birch_wood`** |
| …/Taiga transition | `mixed_conifer_broadleaf` |
| Taiga | `spruce_taiga`, `old_growth_spruce`, `pine_taiga`, `larch_taiga`, `autumn_larch_taiga`, `dead_taiga` |
| Temperate rainforest | `mossy_conifer_forest` |
| Grassland | `meadow`, `moorland`, `downland` |
| Tundra | `shrub_tundra`, `lichen_tundra`, `alpine_scree` |
| Glacier | `glacier` |
| Wetland | `sphagnum_bog`, `fen_reedbed` |
| Cold desert | `cold_steppe` |
| **Blocked** | `willow_carr`, `hot_desert`, `savanna`, `tropical_seasonal_forest`, `tropical_rainforest` |

Size mixes are weighted separately from species, so a forest is mostly m/l with
a few xl and an xs/s understory, while a moor is almost all xs/s. Tundra and bog
use a dwarf mix (80% xs) — krummholz, not forest.

`alpine_scree`, `lichen_tundra` and `shrub_tundra` are designed as the rungs of
an elevation ladder under `glacier`.

### What the Axiom pack changed here

- **`birch_wood` is new.** QF has no birch at all, so this biome only became
  possible once the Axiom pack converted. 84% Axiom, 158 trees. It is the other
  good answer (with `mixed_conifer_broadleaf`) to a hard deciduous/taiga edge.
- **`mossy_conifer_forest` is no longer standing in for anything.** Western
  hemlock and douglas fir now carry the canopy with red spruce and balsam fir
  under them — 80% Axiom. That note about the pack having no hemlock is gone.
- **`dead_taiga` went from 3 species to 9** — `dead_pine/1,2,3`,
  `survival_dead_pine/1,2` and `survival_pine_dead` are a far deeper bench of
  dead conifers than QF's single dead spruce, and it carries the highest stump
  density (0.06).
- **Stumps are a new field.** `trees.stumps` places the 25 `survival_stump`
  blueprints (2–3 blocks tall) on the forest floor at their own low density,
  separate from the canopy roll. Highest in `mossy_conifer_forest` (0.07) and
  `dead_taiga` (0.06).
- **Species-matched litter.** Now that the ground-needle blocks are confirmed,
  conifer biomes floor with their own species: `dark_spruce_ground_needles` +
  `fir_forest_floor` in spruce taiga, `pine_ground_needles` + `pine_cones` in
  pine taiga, `larch_ground_needles` in larch, the two
  `*_autumnal_larch_ground_needles` in autumn larch, `dead_pine_ground_needles`
  in dead taiga, `mossy_fir_forest_floor` in the rainforest.
- **`beech_forest` is deliberately unchanged** at 100% QF. Beech is QF-only and
  the biome is meant to read as a pure stand.


## 5. What is missing

Ranked by how much it costs the look of a typical Azgaar map.

1. **Willow and alder.** `willow_carr` is blocked. Conquest has `willow_log`,
   `weeping_willow_leaves`, `downy_willow_leaves`, `goat_sallow_log` and an
   alder sapling — the blocks are all there, no schematics are. Azgaar uses
   Wetland heavily and `fen_reedbed` currently has to cover for it alone.
2. ~~**Every warm-climate tree.**~~ **Solved by the African pack** — see below.
   All four warm categories now have trees: 42 African species covering savanna
   thorn trees, baobabs, palms, cycads, miombo woodland, arid scrub and closed
   rainforest. The four blocked biomes can be written whenever you want them.
3. **Birch in Conquest blocks.** The Axiom pack has three birches but
   vanilla-blocked. Conquest has `autumnal_birch_leaves` + `mossy_birch_log`.
4. **Mediterranean.** `olive_log`/`olive_tree_leaves` and now `cypress_leaves`
   exist as blocks. Relevant only if your maps have a southern coast.
5. **North-American conifers** for `mossy_conifer_forest` — hemlock and douglas
   fir are in the Axiom pack.

### The Axiom pack — remapped, needs your eyes

Format was easier than feared. `.bp` is a plain container: magic `0x0AE5BB36`,
an uncompressed header NBT, a PNG thumbnail, then gzipped NBT holding
`BlockRegion` sections in the ordinary vanilla chunk-section palette format. Full
read/write in `tools/fmgtrees/axiom.py`. No parser heroics needed.

**The straight `axis`→`axis` name map does not work on this pack**, and that is
worth being blunt about. The trees do not use blocks literally. A "douglas fir"
is `acacia_wood` + `acacia_leaves` + `spruce_slab`; walls, fences and fence gates
stand in for branches; several species use `wheat`, `horn_coral` and
`horn_coral_fan` as foliage. `spruce_log → norway_spruce_log` would have touched
a small minority of the voxels and left the rest nonsense.

What runs instead is **per Axiom species × per block role**:

```
python -m fmgtrees.remap_axiom <blueprints-dir> <out-dir> --registry <ids.txt> --all
```

| role | from | to | properties |
|---|---|---|---|
| trunk | `*_log`, `*_wood`, `stripped_*` | `<trunk>` | `axis` kept |
| foliage | `*_leaves` | `<foliage>` | dropped |
| branch | `*_fence`, `*_fence_gate` | `<family>_log_branch` | n/s/e/w kept, `up=false` |
| branch | `*_wall` | `<family>_log_branch` | `none/low/tall` → bool |
| branch | `*_pane`, `*_bars` | `<family>_log_small_branch` | n/s/e/w kept |
| limb | `*_slab` | `<family>_log_slab` | `type` kept, `layer=4` |
| limb | `*_stairs` | `<family>_log_stairs` | `facing`/`half`/`shape` kept |

The wall/fence→branch mapping is the good news: Conquest's `*_log_branch` has
exactly `north/south/east/west/up` booleans, so a vanilla fence maps onto it
one-for-one and a wall only needs `none/low/tall` collapsing to a bool. No
geometry is inferred anywhere.

**Output: 440 blueprints across all 30 species**, written to
`.minecraft/config/axiom/blueprints/conquest/`. Geometry is provably untouched —
the header, thumbnail and every packed `data` array are copied byte for byte and
only palette *entries* are rewritten, so the worst a bad mapping can do is name a
block wrong. Verified on samples: voxel positions identical before and after.

The registry check caught one real bug: `conquest:oak_log` and
`conquest:birch_log` **do not exist** — Conquest reuses vanilla for those full
blocks while still providing `conquest:oak_log_slab`, `_stairs`, `_branch`,
`_small_branch`, `_stump`. Hence the separate "family prefix" column in the
`SPECIES` table. That is also why QF's English Oak schematics contain
`minecraft:oak_log` next to `conquest:oak_log_vertical_quarter`.

Compromises in the species table, all editable at the top of `remap_axiom.py`:
Conquest has no hemlock, douglas fir, cedar, poplar or maple **log**, so those
map to the nearest match (dark spruce, pine, aspen, beech). Oak and birch are
near no-ops.

#### Reviewed in game — signed off

Walked the remapped trees in Axiom. Outcome: **the axis-level remap was enough.
No directional branch work, no skeleton pass.** That option is closed.

Three stand-in blocks were resolved by eye and are now in `OVERRIDES` at the top
of `remap_axiom.py`, applied before the role rules and across every species:

| stand-in | becomes | voxels |
|---|---|---|
| `wheat` | `conquest:bright_autumnal_beech_tree_ground_leaves` | 4041 |
| `horn_coral`, `horn_coral_fan` | `conquest:faded_autumnal_beech_tree_ground_leaves` | 2827 |
| `crimson_roots`, `fire_coral`, `fire_coral_fan` | `conquest:autumnal_maple_leaves` | 1303 |

Note this **overrules the earlier ACRP reasoning about `wheat`**. ACRP does
retexture it, which is why the tool left it alone, but seen in place it reads
better as beech litter. Looking beats inferring from a resource pack manifest.

`fire_coral` was not named in the review but was folded into the red-maple group
anyway: it is 27% of that tree and the same red-foliage role as the two that
were named, so leaving it out would have left a quarter of the tree as coral.
`crimson_roots` and both fire corals only ever occur in `red_maple`, so these
are safe as global rules.

**12 blueprints dropped** on review: `dead_pine_1_m_01/02`, `dead_pine_1_s_01–04`,
`dead_pine_2_m_01/02`, `dead_pine_2_s_01–04`. The `dead_pine_3_*` group and both
`_l_01` are kept, so dead_pine goes 33 → 21.

Result: **428 blueprints across all 30 species**, none of the six stand-ins
remaining anywhere. `red_maple` is now just `autumnal_maple_leaves` over
`beech_log`.

Still untouched, and judged fine as they are: `dead_bush` (148 entries),
`dark_oak_trapdoor` (131), `dandelion` (102), `orange_candle` (62),
`polished_blackstone_button` (55), `moss_carpet` (54), `orange_tulip` (41), and
a tail of base rocks (`polished_basalt`, `tuff`, `andesite`, `packed_mud`,
`deepslate_bricks`, `mossy_cobblestone`).

#### Leaf decay

Conquest's own leaves and needles are plain blocks with no `distance` or
`persistent` properties, so they cannot decay — nothing to do for any species
mapping onto CR foliage.

Conquest also tags its logs into `minecraft:logs` (1419 entries), **including
`conquest:oak_log_branch` and `norway_spruce_log_branch`**, so vanilla decay does
recognise CR branches as logs. That is not sufficient on its own though: vanilla
decay only reaches distance 7 and these canopies are wider than that.

So vanilla leaf targets (`minecraft:oak_leaves`, `birch_leaves`, used by the oak
and birch species) get `persistent=true, distance=1, waterlogged=false`. The QF
pack already does exactly this — every vanilla leaf in all 874 converted trees
carries `persistent=true`, and the lossless conversion preserved it.

### The African pack — converted, needs your eyes

`C:\Users\ferre\projects\arnis\assets\tree-packs\afr`, 165 `.schem` by
paleozoey, bundled with Arnis. Unlike the Axiom pack this one **is literal**:
the survey found 42 distinct blocks across the whole pack and every species is
one leaf block over one or two wood families, with no stand-ins to decode. So
the remap is a straight species × role table and there was nothing to resolve
by eye first.

Three measured facts shaped the block choices, and they are worth keeping:

- **Conquest leaves are biome-tinted.** Every leaf used here except
  `cypress_leaves` has a `tintindex`, so the texture is luminance and the biome
  supplies the hue. `ash_tree_leaves` is near-greyscale (166,158,158), which is
  why it carries the acacias: gold in a savanna, green in miombo, one block.
- **No Conquest acacia, palm, oak, birch, dark oak or jungle *log*.** Same
  pattern as oak and birch in the QF work — the vanilla full block is reused
  and only the accessories are Conquest (`conquest:acacia_log_branch`,
  `_slab`, `_stump`). Palm is the exception: `conquest:palm_tree_trunk` is a
  real block, and it has **no `axis`**, so that property has to be dropped.
- **No Conquest stripped logs at all** (6 ids, all nether stems). So
  `stripped_jungle_log` and `jungle_log` collapse onto one family, and the
  pale-trunk / dark-branch split the baobabs depend on has to be made by
  picking two different Conquest families rather than by stripping.

Result: **55,145 Conquest voxels against 1,308 vanilla**, and every one of
those 1,308 is in `acacia`, `oak` or `dark_oak` — exactly the families where
Conquest deliberately reuses the vanilla block. Nothing was left unmapped and
nothing fell outside the registry.

Per-species table, judgement calls and the shape hacks are in
`docs/conquest/afr_pack.md`.

#### Writing `.bp` from scratch

`axiom.py` could only rewrite an existing blueprint. Making one out of a
schematic needed a real writer, which is `bpwrite.py`. The parts that had to be
measured against the pack's own files rather than guessed:

- **X and Z are centred** on `-(size // 2)`; **Y is arbitrary**. Checked across
  40 blueprints: X agrees in 38, Z in 39, Y is scattered over a 30-block range
  (it is wherever the player was standing). So the writer centres X/Z and puts
  the base of the model on y=0.
- **Empty space is `structure_void`, not air**, and `ContainsAir` is 0 in every
  blueprint in the folder. A tree pasted over terrain must not punch a box of
  air around itself.
- **`Version` is the blueprint format, not the game** — 1 and 2 both occur.

Verified by round-tripping 13 existing blueprints through read → write → read:
all 13 came back with identical voxel positions and blocks. The 165 new files
were then re-read and checked against their source schematics — positions
identical in every spot check.

`DataVersion` is written as **3465 (1.20.1)**, matching the Conquest instance,
not the 1.21.4 the mod targets.

#### Thumbnails

Axiom renders a thumbnail when *it* saves a blueprint; one written from outside
has never been rendered, and 165 blank entries in the picker is unpleasant. So
`thumbnail.py` draws a rough isometric silhouette, with colours taken from the
Conquest jar's own textures and a nominal foliage tint applied to the greyscale
(biome-tinted) leaves so they do not come out white. It is a silhouette, not a
render — enough to tell a baobab from a palm in the picker.

#### Where they are

Written to **`.minecraft/config/axiom/blueprints/conquest_<pack>/<genus>/`** —
`conquest_african`, `conquest_ena`, `conquest_asn`, `conquest_ind`,
`conquest_sam`, `conquest_fl`. All siblings of your `conquest/` folder,
deliberately: `conquest/african/` already holds your hand-shaped
`adansonia_digitata1` (the one with the rounded trunk in
`oak_log_vertical_corner` and `spruce_wood_beam_*`) and nothing here should
overwrite that. Move them across as you touch them up.

#### The empty cells, and why the NBT comes from the blueprints

A blueprint has to fill **every** cell of every 16-cube section — the format
gives no way to leave one out — and on a big tree that is ~97% of the bounding
box. Filling it with `structure_void` (what Axiom itself writes) makes an editor
walk all of it, which is what made detailing crawl. These are filled with plain
**air** instead, the state every voxel editor special-cases as nothing.

Placement never sees that either way, because **the NBT is built from the
blueprints, not from the schematics**: `convert_bp` drops all four empty states
(`air`, `cave_air`, `void_air`, `structure_void`) when it writes a structure
file, and a position that is not listed is simply not written, so a tree cannot
punch a hole in the terrain it lands on.

That is also why the pipeline runs `.schem → .bp → .nbt` rather than writing
both from the schematic. Re-run `convert_bp` after a detailing session and the
hand edits come along; a parallel copy made from the schematics would go stale
the moment anything was fixed.

    python -m fmgtrees.convert_packs <tree-packs> --packs ena,asn,ind,sam,fl --bp-out <blueprints>
    python -m fmgtrees.convert_bp <blueprints>/conquest_ena <trees>/conquest_ena --pack ena
    python -m fmgtrees.merge_index <trees>

`--fill void` on the converters puts `structure_void` back if a blueprint ever
needs to paste over terrain without clearing around itself.

### The five regional packs — converted, need your eyes

`projects/arnis/assets/tree-packs/{ena,asn,ind,sam,fl}`, **1107 schematics,
174 genera**, also paleozoey. Ranked by what they unblock rather than by size:
`ena` clears willow and the cypress/royal-palm gaps, `ind` is the deepest source
for tropical seasonal forest, `sam` and `asn` deepen rainforest and taiga, `fl`
is palms and swamp. `aus` was skipped: 14 Eucalyptus species and Conquest has no
eucalypt foliage at all, so every one would be a stand-in.

#### Why this one is keyed on genus, not species

The African pack got a hand-written species table because it is 42 species and
**it used its blocks literally**. These five do not. The survey is blunt:

- `Acer` is oak leaves on birch wood
- `Salix` is birch leaves on spruce wood
- `Taxodium` is oak leaves on oak wood

Mapping the source block would produce nonsense. The filename is the only
reliable signal, so `remap_packs.GENERA` is keyed on genus and gives
`(foliage, trunk family, accent family, blossom)`. 174 entries, one line each.

`SPECIES` overrides where the species genuinely changes the answer, which in
practice is the willows: `Salix_babylonica` is a weeping willow and Conquest has
`weeping_willow_leaves`; `Salix_discolor` is a pussy willow and Conquest has
`goat_willow_leaves` over `goat_sallow_log`. Collapsing both onto one block would
waste two of the best matches in the set.

**Two-wood trees survive.** Wood families in a model are ranked by voxel count:
the biggest becomes the trunk, anything else becomes the accent. That is what
keeps a pale trunk with darker branches instead of flattening it to one block.

#### What this filled in

Conquest ships 63 leaf and needle blocks and before this only 32 had a tree.
The ones that just gained one:

- **`weeping_willow_leaves`, `goat_willow_leaves`** — 19 willows across asn,
  ena and sam. This is what unblocks `willow_carr`, the last blocked biome.
  There is still no alder anywhere, but Conquest only ever shipped an alder
  *sapling*, so alder was never actually available.
- **`cypress_leaves`, `light_cypress_leaves`** — 12 `Taxodium` (bald cypress)
  plus `Cupressus_sempervirens`. Conquest also ships `dead_`, `half_dead_` and
  `quarter_dead_` cypress, which are obviously meant for a swamp.
- **`caribbean_royal_palm_leaves`** — 8 `Roystonea`. This block carries a
  `facing`, so it cannot be dropped in blind; the facing is filled from the
  frond's own position relative to the trunk. That is a direction-from-centre,
  not the branch-skeleton detection that was ruled out. Verified: facings come
  out an even 8/8/8/8 across the four compass directions.
- **Six blossom blocks** — `pink`/`white`/`purple_cherry_blossoms` and
  `wisteria_blossoms` had no tree at all. The packs mark a tree in flower with
  `flowering_azalea_leaves` or `cherry_leaves`, so those route to the genus's
  blossom: `Prunus` pink, `Cornus` and `Magnolia` white, `Jacaranda` and
  `Duranta` purple, `Wisteria` its own. `Erythrina`'s red flowers come in as
  `red_carpet` and become `red_cherry_blossoms`.
- **`autumnal_birch_leaves`, `holly_leaves`, `gorse_leaves`, `rowan_leaves`** —
  `Betula`, `Ilex`, `Berberis`, `Sorbus`.

#### Judgement calls worth knowing

- **Conquest has no green birch, oak or maple leaf.** Birch and oak reuse the
  vanilla block, which means they need `persistent=true` or the canopy rots on
  the first random tick — verified, 145 vanilla leaf palette entries, all
  pinned, none missed. For maple the nearest is `horse_chestnut_leaves`, which
  is at least palmate.
- **`conquest:cactus` is not the block for a columnar cactus.** Its model is a
  ground clump whose elements run to y=20.5, so stacking it into a 15-block
  `Cereus` gives overlapping spikes. Those nine files use vanilla cactus, which
  nothing retextures, so they will read as vanilla until Conquest ships a
  columnar one.
- **Mangrove prop roots become `*_log_small_branch`**, not `hanging_roots`.
  They are structural on a `Rhizopora`, not a ground plant.
- **Grouping is by genus**, so `Quercus_alba` and `Quercus_rubra` share
  `ena/quercus/default`. 174 knobs is already a lot for a biome file, and the
  genus is what reads as one kind of tree. The binomial is kept on each entry.

Full genus table: `docs/conquest/regional_packs.md`.

### The bush/shrub pack

`respriatory-copy.zip` is **not a schematic pack** — it is a 1.18.2 Minecraft
world save (region files, no index, no `.schem`). Getting the bushes out means
either WorldEdit-copying each one in game or writing a region scraper. Its one
immediate payoff was the block catalog in §3.

Note that Conquest's single-block plants (`heather`, `bracken`, `bilberry_bush`,
`reindeer_lichen`, `*_rocks`, `light_limestone_boulders`, …) need no schematics
at all, and the §4 ground-cover lists are already built from them — so this pack
is a nice-to-have, not a dependency.


## 6. What is left to build

1. **Placement feature (Java).** The one real code addition: a feature that
   picks a weighted species + size from the biome's lists, loads the NBT, seats
   it on the terrain by its trunk anchor, and rejects slopes/water. Driven by
   the content profile, so the vanilla profile is unaffected. Everything else in
   this plan is data.
2. **Datapack generator.** A script turning `docs/conquest/biomes/*.json` into
   `data/fantasymapgenerator/worldgen/biome/*.json` plus configured/placed
   features. Custom biomes can only reach Minecraft as a worldgen datapack.
3. **Surface rules.** Written into the already-shipped
   `fmg_overworld_conquest.json`, from each biome's `surface` block.
4. **Profile wiring.** Point `conquest.json`'s biome pools at the new ids. The
   `requiresMod: "conquest"` guess in that file is **confirmed correct** — the
   mod id really is `conquest`.
5. **Species dominance noise** (§1), once 1 exists.
6. **The five remaining biomes.** `hot_desert`, `savanna`,
   `tropical_seasonal_forest`, `tropical_rainforest` and now `willow_carr` were
   all blocked only for lack of trees. **Nothing is blocked any more.** Writing
   them is the same `make_biomes.py` work as the other 24, and there is enough
   material for several new ones besides (cypress swamp, mangrove coast,
   monsoon forest, Mediterranean).
7. **Review the 1272 new blueprints.** None have been looked at in game.


## 7. Review workflow

The intent is a manual pass over every tree, fixing anything that looks wrong.
The converted NBTs are the thing to review — they are exact copies of the
schematics, so anything wrong with them was wrong in the source pack.

Fastest loop is probably: load them in the 1.20.1 ArdaCraft profile (which has
Conquest and Axiom), place a grid of one species/colour at a time, walk it, and
fix in place. Re-exporting a fixed tree back over its `.nbt` keeps the index
valid as long as the filename does not change.


## 8. Files

```
tools/fmgtrees/nbtio.py          minimal NBT read/write, no dependencies
tools/fmgtrees/schem.py          Sponge .schem reader
tools/fmgtrees/structure.py      structure-NBT writer, trim + trunk anchor
tools/fmgtrees/axiom.py          Axiom .bp reader/writer
tools/fmgtrees/convert_qf.py     QF pack -> structure NBT            [CLI]
tools/fmgtrees/survey_axiom.py   per-species block/role report       [CLI]
tools/fmgtrees/remap_axiom.py    Axiom .bp -> Conquest blocks        [CLI]
tools/fmgtrees/bpwrite.py        Axiom .bp writer (voxels -> blueprint)
tools/fmgtrees/thumbnail.py      isometric thumbnail for the Axiom picker
tools/fmgtrees/remap_afr.py      African pack species x role table
tools/fmgtrees/convert_afr.py    African .schem -> .bp + NBT          [CLI]
tools/fmgtrees/remap_packs.py    174-genus table for the 5 regional packs
tools/fmgtrees/convert_packs.py  regional .schem -> .bp                [CLI]
tools/fmgtrees/convert_bp.py     .bp -> structure NBT + index          [CLI]
tools/fmgtrees/merge_index.py    rebuild the combined tree index      [CLI]
tools/fmgtrees/make_biomes.py    emits docs/conquest/biomes/*.json   [CLI]

docs/conquest/PLAN.md                     this file
docs/conquest/conquest_blocks_1.20.1.txt  block catalog, 18,249 ids from the jar
docs/conquest/conquest_blocks_1.18.2.txt  older catalog, scraped from a world save
docs/conquest/afr_pack.md                 African pack species table + report
docs/conquest/regional_packs.md           174-genus map for the 5 regional packs
docs/conquest/biomes/*.json               28 biome design files
docs/conquest/axiom_roles.{md,json}       Axiom per-species block roles

run/config/fantasymapgenerator/trees/conquest/       874 converted QF trees
run/config/fantasymapgenerator/trees/conquest_axiom/ 428 converted Axiom trees
run/config/fantasymapgenerator/trees/conquest_afr/   165 African trees
run/config/fantasymapgenerator/trees/conquest_ena/   262 eastern North America
run/config/fantasymapgenerator/trees/conquest_asn/   225 temperate and montane Asia
run/config/fantasymapgenerator/trees/conquest_ind/   226 South and Southeast Asia
run/config/fantasymapgenerator/trees/conquest_sam/   256 South America
run/config/fantasymapgenerator/trees/conquest_fl/    138 Florida, Caribbean, wetland
run/config/fantasymapgenerator/trees/index.json      combined, 2574 trees / 355 groups
.minecraft/config/axiom/blueprints/conquest/         428 remapped Axiom blueprints
.minecraft/config/axiom/blueprints/conquest_<pack>/  1272 remapped paleozoey blueprints
```
