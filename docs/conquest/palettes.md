# Ground palettes — layer-block surfaces per biome

Last updated 2026-09-28.

What the terrain surface of each Conquest biome is made of. The terrain is built
from **layer blocks**, not full blocks, so a palette names *materials* that each
have a layer form (the skin you see) and a full form (what fills beneath).

| | |
|---|---|
| Source of truth | `tools/fmgtrees/palettes.py` (hand edits go here) |
| Written into | `docs/conquest/biomes/*.json` as `palette`, by `make_biomes.py` |
| Review page + textures | `python -m fmgtrees.palette_preview` → `../tool-output/conquest_palettes/` |
| Texture resolver | `tools/fmgtrees/crtextures.py` (pack stack, models, Continuity, Polytone) |

`tool-output/conquest_palettes/` sits next to the repo, outside git, on purpose:
everything in it is Conquest and ACRP artwork. It holds `index.html` (open it in a browser),
`textures/<biome>/<material>.png` (4×4-block tops in that biome's tints) and
`textures/_catalog/`.

**Status: 29 biome palettes plus 3 sea palettes, all ids validated against the
1.20.1 registry. Implemented in the generator (section 8) on the `1.20.1`
branch and tested on a 1.20.1 dev server with Conquest Reforged 1.7.0 installed:
529 generated chunks, 93.6% of surface columns topped by a Conquest block,
layer counts spread evenly over 1-8.**

To run it in development, put the Conquest Reforged Fabric jar in
`../runs/fabric-1.20.1/mods/` (this branch's dev run folder, next to the repo);
Loom remaps it at launch.


## 1. Layer forms in Conquest

Conquest ships layer-capable forms in two shapes, and both work as a skin:

| form | properties | used for |
|---|---|---|
| `conquest:<x>_layer` | `layers=1..8` | sands, gravels, debris, forest floors, sphagnum, grass |
| `conquest:<x>_slab` | `layers=1..8`, `type=bottom\|top` | soils, muds, silts, peat, **every rock** |

So bare rock can be layered too (`granite_slab[layers=3,type=bottom]`), and a
cliff band does not have to drop to full blocks.

Things found while checking, which shaped the palettes:

- **A layer's sides show its top texture.** `grass_block_layer` has grassy sides,
  not a dirt side with a green fringe. The soil block beneath only shows on a
  step taller than the layer.
- **No layer form:** `unfertile_loamy_dirt`, `coarse_dirt`, `podzol`,
  `grass_covered_limestone`, `stone`, `diorite`, the terracottas and
  `minecraft:snow_block`. These can only be the full block underneath.
  (`minecraft:snow` is the snow skin.)
- **Layer-only, no full block:** `grassy_dirt_layer` (vanilla dirt used
  beneath) and `vibrant_autumnal_forest_floor_layer` (plain autumnal floor
  beneath).
- **`bright_glacier_ice_slab` has no `layers`.** It is a plain slab and cannot be
  a skin.
- **ACRP draws "Do not use!"** on `dry_mud` and `coarse_sandy_soil`.
- **Read as masonry from above:** the `columnar_*` stones, the three tuffs and
  the quartzites. Not used on natural ground.
- **Single-tile "patch" textures:** `grassy_gravel_layer`, `grass_and_sand_layer`
  and `grassy_small_stones_layer` are a square patch inside a grass border. Tiled
  or dithered they form a checkerboard. Not used.


## 2. Palette fields

```
tints      grass / foliage / water hex for the biome JSON
soil       full block under skin materials (grass, floor, moss groups)
stone      material name: fills below soil, and the rock of cliff bands
soilDepth  soil blocks under a skin on flat ground
clump      noise scale (blocks) at which a mix forms material patches
patch      noise scale (blocks) of sub-biome patches
sub[]      {id, name, weight, mix}   low-frequency patches inside one region
ramps[]    {var, feather, bands[]}   gradients that replace the mix
bed        {var: depth, ...}         the floor under water
materials  name -> {layer, full, group, note}  (resolved, per file)
```

A `mix` is `{material: weight}`. A band is `{from: v, mix}` for rising variables
(slope, altitude) or `{to: v, mix}` for distances (coast, bank, depth).


## 3. How a column is chosen — the spec

The Java surface rule and the preview page must agree on this. The page's
JavaScript (`evaluate()` in `palette_preview.html`) is the reference.

1. **Variables** per column:
   - `slope`: gradient magnitude of the height field in blocks per block, from
     central differences (1.0 = 45°).
   - `coast`, `bank`: horizontal distance in blocks to the nearest sea water or
     river/lake water column. Infinite if none nearby.
   - `alt`: Azgaar height units (20 = sea level, 100 = map max). Keeps palettes
     independent of the vertical scale and dimension height.
   - `depth`: water surface minus terrain height, for underwater columns.
2. **Under water**: the `bed` band for `depth` gives the mix (deepest band if
   none match). Skip to step 5.
3. **Sub-biome**: warped cellular noise at `patch` scale. Jittered-grid Voronoi
   whose feature points each draw a sub-biome by weight, sampled at a position
   displaced by value noise of amplitude 0.45·`patch`. This gives patches in the
   right proportions with organic edges; plain thresholded value noise does not,
   because its distribution bunches in the middle.
4. **Ramps**, in the fixed order `alt`, `coast`, `bank`, `slope`. Add
   `(noise·2−1)·feather` to the variable (value noise at ~3-block scale). A
   matching band replaces the mix, so a later ramp beats an earlier one: cliffs
   stay rock at any altitude and beside any water.
5. **Material** from the mix: the same cellular noise at `clump` scale, then a
   15% chance per block to re-draw from a per-block hash (speckle).
6. **Layers**: `H8 = max(1, round(h·8))`, `yTop = floor((H8−1)/8)`, full blocks up
   to `yTop−1`, and at `yTop` the material's layer block with
   `layers = H8 − 8·yTop` (1–8), plus `type=bottom` for `_slab` forms.
7. **Fill beneath**: `d = round(soilDepth · clamp(1 − slope/1.5, 0, 1))`. Under a
   skin (groups grass, floor, moss) put `d` blocks of `soil`. Under anything else
   (soil, wet, sand, gravel, rock, ice) put `max(1, d)` blocks of the material's
   own full form. Then `stone`'s full block.

Seeds are per biome (a hash of the biome id) so neighbouring biomes do not share
patch boundaries.

**Which palette a column uses** comes before all of this. It is the land biome
at the column, looked up with the profile's `biomeEdgeWarp` (so borders wander
instead of following the map's straight cell edges), and then blended across
borders by `SurfaceBlend` with radius `biomeEdgeBlend` (16 in the Conquest
profile). Biomes are sampled on a 4-block grid; a column counts the biomes
within the radius and draws its palette in that proportion, in ~3.5-block
clumps. At a border the mix is about half and half, and the neighbour's share
thins out to nothing over the radius, so glacier snow breaks up into patches
instead of stopping on a line. Biome ids and tints are not blended; the
client's biome-blend setting already smooths grass colour.


## 4. Tints, and the ACRP problem

Most of what makes a CR surface look green is the tint. `grass_block`,
`taiga_grass`, `clover_covered_grass`, every `mossy_*` and `overgrown_*` block,
`small_stones` and the mossy forest floors are greyscale with a `tintindex`, so
each palette sets `tints.grass` / `tints.foliage` for the biome JSON.

**With ACRP loaded, that biome colour is ignored.** ACRP re-tints grass, sand and
most plants through Polytone colormaps indexed by biome id
(`polytone:ardabiomes`, which lists the 64 vanilla ids plus `arda:biome00..99`).
Polytone's lookup is `map.getOrDefault(biome, 0)` (checked in
`BiomeIdMapper$Custom.getIndex`), so **any biome id not in that list samples
column 0, `#b7c26c`**, whatever its biome JSON says. Every custom FMG biome would
get the same pale yellow-green grass. The review page's "ACRP fallback" toggle
shows this.

ACRP's columns also carry their own meanings: the `taiga` column is autumn
orange, `snowy_taiga` yellow, `meadow` gold. Reusing vanilla ids does not simply
give vanilla colours either.

Options, cheapest first:

1. **Ship a small FMG resource pack above ACRP** that overrides
   `assets/polytone/polytone/biome_id_mappers/ardabiomes.json` with FMG's biome
   ids added, each pointing at a column whose colour matches its palette tint.
   One file, no code, and nothing in ArdaCraft proper changes.
2. The same, but also overriding `colormaps/grasses.png` and `sand.png` with
   FMG columns painted from `tints`. Exact colours, but it replaces ACRP's
   colormaps for every world while the pack is active.
3. Play without ACRP. The palettes are designed so that "Conquest only" in the
   page also looks right. ACRP mainly tones down `blue_soil`/`ochre_soil`.

Worth deciding before the biome datapack is generated, because option 1 needs
the final biome ids.


## 5. Continuity

CR and ACRP describe most terrain with OptiFine-format CTM, which Continuity
applies. The review page reproduces:

- `repeat`: one large image over N×N blocks. Grass top 4×4, `tan_sand`,
  `sand`, `granite` and most rocks 8×8, `fir_forest_floor` 3×3, `peat` 2×2.
- `random`: weighted variants (gravel ×4, `rocky_sandy_soil` ×16).
- One level of chaining. CR's grass top is a 4×4 repeat of *transparent
  placeholders*, each matched by its own `random` rule, 62 real variants in
  all. Without chaining the grass renders invisible.

Not reproduced: **`__zgrass_overlay`**, which draws grass edges onto neighbouring
`dirt`, `unfertile_loamy_dirt`, `sand`, `red_sand`, `gravel`, `small_stones` and
vanilla stones wherever they touch grass. It matches by *texture*, so the layer
forms of those blocks get it too. In game, grass edges against those blocks are
softer than the page shows, and against `tan_sand`, `brown_sand` or the soils
they stay hard. That is one more reason the temperate coast ramps use plain
`sand` and `sand_and_gravel`.


## 6. What each biome family does

- **Temperate deciduous**: litter-led floors (`shredded_leaves`, rooted mossy
  floor) with turf in glades, limestone on slopes. Beech is the one biome where
  litter beats turf, with a chalk hanger. Autumn uses three leaf-fall blocks
  and a rare golden `lorien_forest_floor` glade.
- **Taiga**: needle duff (`taiga_forest_floor`, `fir_forest_floor`) threaded
  with moss. Pine reads dry and sandy with lichen heath. Larch sits on blue
  schist scree. Dead taiga has burn scars with ash. Cold coasts use grey sand
  and pebbles, not yellow beach.
- **Temperate rainforest**: everything green including rock (mossy schist on
  every slope band).
- **Grassland**: meadow is deliberately quiet (two turf blocks at 70%) so ground
  cover carries it. Moorland is heather over peat with dark granite tors.
  Downland has one block of soil over chalk, so white scarps show on every
  real slope.
- **Tundra / alpine / glacier**: an elevation ladder of frozen soil, lichen,
  patterned stones, scree and snow. Glacier soil is ice six deep, so a cut shows
  an ice wall before the granite.
- **Wetland**: bog is four sphagnum colours in small clumps (`clump` 2.5). Fen
  and carr are silt and mud with mud banks.
- **Warm**: hot desert has five sub-biomes (erg, reg, hamada, playa, wadi) so it
  is not one sand. Savanna has golden turf over laterite with granite kopjes.
  Tropical forests sit on red earth. Rainforest rock is moss-covered limestone
  (karst).


## 7. Open questions

1. **ACRP tint fallback** (§4). Pick an option before generating biome ids.
2. **Altitude units.** Ramps use Azgaar height (20–100). The surface rule needs
   the world-Y → Azgaar conversion from the heightmap scale. It exists in the
   loader; the rule just has to use it.
3. **Coast vs bank.** The rule needs to know whether nearby water is sea or
   river/lake. `FMGRiverNetwork` knows river cells; sea is the ocean mask.
4. **Snow as a skin vs snow on top.** Glacier and alpine palettes use
   `minecraft:snow` as the surface. Seasonal snow on other biomes would be a
   separate overlay pass, not a palette material.
5. **Species-matched litter.** The biome designs (`make_biomes.py`) put
   `oak_ground_leaves` etc. in `groundCover`. Those are thin carpets placed on
   top, so they combine with these palettes rather than replacing them.


## 8. In the generator

| piece | where |
|---|---|
| Profile `conquest` (rev 2): pools over the 29 biomes, `surfacePalettes`, `biomeEdgeWarp` 18 | `FmgContentProfile.conquest()` |
| Offered only with the `conquest` mod loaded (`-Dfmg.allProfiles=true` to test without) | `FmgProfiles.available()` |
| Old on-disk `conquest.json` replaced, backup kept as `.v<N>.bak` | `FmgProfiles.writeIfOutdated` |
| 29 biomes (tints, climate, no vegetation features) | `data/fantasymapgenerator/worldgen/biome/conquest/` |
| Runtime palettes | `fantasymapgenerator/surface/conquest.json` (config override: `config/fantasymapgenerator/surface/`) |
| Continuous height next to the integer one | `FMGHeightSampler.sampleHeightExact`, `ChunkGrid.exact` |
| The surface pass | `worldgen/LayeredSurface` (runs after vanilla surface rules, before rivers/roads) |
| Minecraft-free core, shared with the harness | `surface/SurfaceSet`, `SurfaceNoise`, `SurfaceVars`, `SurfaceBlend`, `SurfaceCover` |
| Harness on real terrain | `surface.cmd <bake> <out.json> --center X Z` then `tools/fmgtrees/render_surface_dump.py` |

Regenerate the runtime files after editing `palettes.py`:
`python -m fmgtrees.make_datapack` (from `tools/`).

Behaviour worth knowing:

- Roads, bridges and river channels are left to the generator; river and lake
  beds take the palette's `bed` ramp.
- Under water everything stays full blocks. A partial layer would leave a dry gap
  in its own cell.
- Region borders are warped identically in the biome source and the palette
  lookup (profiles with `biomeEdgeWarp` > 0 only; vanilla is unchanged).
- Only the glacier keeps vanilla `freeze_top_layer`. Elsewhere it would snow on
  the 1 in 8 columns whose layer is full-height.
- A world opened without Conquest generates with vanilla stand-ins, full blocks
  only (logged once).


## 9. Ground cover

Grass, flowers, ferns, bushes, leaf litter, rocks, cones and mushrooms. Trees
are a later step. The lists are each biome's `groundCover` and `props` in
`make_biomes.py`; `make_datapack.py` turns them into a `cover` section on the
palette (`plants`, `props`, `patches`), with the densities from its `GROUND`
table and a placement *kind* per block, read off Conquest's block classes:

| kind | blocks | placed |
|---|---|---|
| `plant` | Bush family (shrubs, dense plants, reeds), berry bushes, ground leaves / needles, `*_rocks`, lichens, `moss_on_the_ground` | on top of the layer. The block's own `layers` is set to the layer under it, like Conquest's own placement, so the model sinks onto the ground. Lichens and ground moss get Conquest's offset from the block below instead. |
| `tall` | `large_fern` | two blocks, `half=lower/upper` |
| `sunk` | `hawthorn_bush` (a leaves block, no offset) | replaces the layer block. A bush sunk up to 7/8 into the ground looks fine; one floating over a part layer does not. |
| `loose` | `pine_cones`, `spruce_cones`, `tumbleweed` (no offset) | on a full layer, or in place of a layer of 1-2 eighths; skipped elsewhere |
| `patch` | full blocks: forest floors, `taiga_grass`, `smooth_pebbles`, sphagnum blocks, `burnt_soil`, dirty ice, boulders | swaps the ground material in clumps (warped cells ~4.5 blocks) through the block's `_layer` / `_slab` form. Boulders stand 1-5 eighths proud, so boulder fields are lumpy. Only on sub-biome ground, never over a slope / shore / altitude band. |
| skip | `hanging_moss`, `moss_vines`, `duckweed` | not yet: they hang from trees or float on water |

Per column, props are rolled first (rarer), then plants:

- **Plants**: `plantDensity` x ground factor (turf and forest floor 1, moss and
  wet 0.9, soil 0.6, gravel 0.35, sand and rock 0.3, ice and snow 0) x
  steepness (none from slope 1.4) x a thickness noise over ~13 blocks (glades
  to thickets, 0.25-1.75). Species come in clumps (warped cells ~2.6 blocks)
  with 20% loose speckle.
- **Props**: `propDensity` x ground factor (rock 1.6, gravel 1.4, turf/soil 1,
  sand 0.6, wet 0.4, ice 0) x 1-2.2 with slope x a field noise over ~17 blocks,
  so rocks gather in fields and some stretches have none. Species by cells
  ~9 blocks, so a field is mostly one rock type.
- The rest of the state comes from the column's hash: berry-bush `age`,
  model `random`, rock `density` (mostly 1-2, now and then a heap of 4).
  Everything is placed dry, flat and unstacked; leaves are persistent.
- `COVER_SCALE` (0.7) in `make_datapack.py` scales every biome's plant and
  prop density: the first pass was "a little much" (user, 2026-10-05).
  Patches are ground, not decoration, and keep theirs.

Conquest plants only check their ground during world generation. In a live
world they stay wherever they are, so neighbour updates never pop them.
Without Conquest installed the cover is skipped: the palettes' stand-ins are
ground only.

**Carvers.** Vanilla's cave and canyon carvers run after this pass and cannot
cut Conquest's layer blocks, so where one comes up under the surface the layer,
and the plant on it, was left hanging over the hole. `LayeredSurface.afterCarve`
runs right after the carvers: up to 2 blocks of air over solid ground are filled
back with the palette's soil; over a bigger opening the layer and what stands on
it go, so the hole reads as an ordinary cave mouth. Under water the bed stays.
