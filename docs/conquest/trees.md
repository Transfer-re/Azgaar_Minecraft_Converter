# Trees: the TreeGenerator objects in the world

The trees, shrubs, dead wood, fallen logs and stumps from `TreeGenerator`
(`C:\Users\ferre\projectjes\TreeGenerator`, see its `WORLDGEN.md`) are converted to
structure NBT and placed by **Minecraft's own feature system**: configured and
placed features in each biome's feature list, with vanilla's placement modifiers.

## Rebuilding

After regenerating trees, changing `biome_mix.toml` or marking keepers:

```
cd tools
python -m fmgtrees.treegen          # schematics -> NBT, mix -> feature JSON
python -m fmgtrees.make_datapack    # puts the features into the biome JSONs
```

`treegen --no-nbt` rewrites only the JSON (seconds instead of a minute). A changed
feature JSON needs the world reopened: worldgen registries load with the world, not
on `/reload`. Changed NBT files do reload with `/reload`.

Marking keepers works as WORLDGEN.md describes: put an `x` in a batch's
`keep.txt`, rebuild the index (`python -m treegen index`), rerun `treegen`. A
batch with any keepers places only those.

## What gets written

| Where (under `src/main/resources/data/fantasymapgenerator/`) | What | In git |
|---|---|---|
| `structures/treegen/<preset>/<name>.nbt` | 4,560 objects, air left out (21 MB) | **no** -- generated, gitignored |
| `worldgen/configured_feature/treegen/preset/<preset>.json` | one per preset: its objects (template, size, paste point, radii) and where it may stand | yes |
| `worldgen/configured_feature/treegen/<biome>/<layer>.json` | one per biome and layer: presets, weights, sizes, spacing | yes |
| `worldgen/placed_feature/treegen/<biome>/<layer>.json` | `count`, `in_square`, `surface_water_depth_filter`, `heightmap OCEAN_FLOOR`, `biome`, as vanilla's `trees_plains` | yes |

A checkout without the NBT files still loads; the feature then places nothing and
logs which templates it could not find (once). The NBT files do go into the jar
you build locally.

Layer order within a chunk is WORLDGEN.md's: trees, dead wood, logs and stumps,
then shrubs, all in `vegetal_decoration`. Each biome's tree features are its own;
the shoreline overlay (`tropical_coast`) is shared by its two host biomes and comes
last in both, so the vanilla feature-order check stays happy.

## Reviewing objects in game

```
/place feature fantasymapgenerator:treegen/preset/english_oak       a random English oak, any size
/place template fantasymapgenerator:treegen/english_oak/english_oak_l3   one exact object (from its corner)
/fmg trees stats                                                     tries / placed / refused per biome layer
/fmg trees reset
```

`/place feature` keeps the preset's ground rules but not the spacing. The stats
are also written to the log when the server stops.

## The rules (Java: `com.example.trees`)

Everything below comes from WORLDGEN.md unless it says otherwise. Every number
here is a knob at the top of `treegen.py` (density, spacing, attempts, slope,
gradient, rock) and needs only `treegen --no-nbt` plus `make_datapack` to change.

**Density.** The mix's `<layer>_per_chunk` counts are multiplied on our side --
trees x1.5, shrubs x1.4, dead wood and logs x1 (`DENSITY`) -- so the TreeGenerator
file stays the source of the proportions. Dense stands are capped by spacing
anyway; the multiplier mostly fills out woodland and open land.

**Ground.** The paste point goes one block above the top ground block. Ground is
classified into the surface palettes' own groups (grass, floor, soil, moss, wet,
sand, gravel, rock, ice), read from the palette file's material list, so a tree
sees exactly the ground `palettes.py` laid down. Defaults: grass, floor, soil,
moss, wet; plus sand for palms and acacias and for everything in hot desert,
savanna and cold steppe; plus gravel for conifers and for every layer in the
conifer biomes; plus **gravel and rock for trees, dead trees and shrubs in every
biome** except alpine scree and glacier -- the palettes turn steep ground to rock,
and refusing it left mountainsides bare. Ice and snow never, which keeps a
treeline.

**Slope.** Ground under the trunk footprint (`base_radius + 1`) may vary by the
object's sunk depth plus 3 blocks (`SLOPE`; shrubs 2) -- or, whichever is more, by a
steepness of 60 degrees across the footprint's width (`GRADIENT` 1.7; shrubs 54).
Never by more than the sunk depth plus 6, so a big tree is never bedded deep into a
hill. The object is seated so the bottom of its sunk base meets the *lowest* ground
under it (never floating downhill) and is bedded into the uphill side no more than
it has to be; past the trunk's bottom two layers the hillside simply covers it.
**When a spot is too steep for the chosen tree, a smaller one of the same kind is
tried there** -- stunted trees on the mountainsides, the big ones on the gentle
ground. Fallen logs need ground level within one block along their whole length,
rest on the lowest of it, and do not step down.
*All of this goes well past WORLDGEN.md's "at most 1 block". Encia's blended
mountains are steep -- in one taiga sample the median slope was 48 degrees and 41%
of the ground steeper than 55 -- and the strict rule left them bare.*

**Water.** Never on water, except mangroves: those stand on the bed in sea water
1-2 (red_mangrove) or up to 3 (stiltbark) deep, and their blocks are waterlogged
where they go into water, so the water stays between the roots.

**Spacing.** Trees and dead trees keep `distance >= k * (crown_a + crown_b)`, with
k 0.42 in dense stands, 0.6 in woodland, 0.9 in open land, picked from how many
trees and dead trees a chunk is meant to hold. (WORLDGEN.md suggests 0.5-0.6 / 0.8 /
1.2; the crown radius is the furthest leaf from the trunk, so those kept forests at
25-40% canopy cover.) Shrubs keep off trunks
(`base_radius + 1`) and `0.7 * (r_a + r_b)` from each other. Logs keep 2 blocks
from trunks and from each other. Vanilla features only see blocks, so placements
are recorded with their radii for neighbouring chunks to check (not saved; after a
restart the block check below still stops wood going into wood).

**Pasting.** Air is never written. Above the paste point an object does not cut
into terrain: only its sunk base and the trunk's bottom two layers replace ground.
No wood goes into existing wood, and leaves never replace wood. Roads keep three
blocks of headroom and the road marker is never overwritten. A ground plant's
`layers` is set to the layer block under it, as Conquest's own placement does, and
a ground plant with nothing under it is left out. A tall plant cut in half by a
trunk loses its other half. Where the sunk base replaces a ground block, the
surface's ground cover on that block goes too: it would otherwise stand on, or
sink into, a root or a bush's leaves.

**Rotation.** Random quarter-turns, by property name the way WorldEdit does it
(`facing`, `axis`; side booleans and `rotation` too, in case they appear). The
survey of all 6,221 states found only `facing` and `axis` carrying direction.
Never mirrored.

**The 3x3 chunk window.** A vanilla feature may only write to the chunks around the
one it decorates. Every object fits from some paste point in the centre chunk; the
49 that reach more than 16 blocks out have their paste point moved in from the
chunk edge just enough. Nothing is clipped, and nothing writes outside.

**Retries.** A refused spot is retried up to four times elsewhere in the same chunk
and biome (`ATTEMPTS` 5), so rough ground does not quietly cut the mix's numbers.

## Notes in the mix, made into rules

`NOTE_RULES` in `treegen.py`:

| Biome | Preset | Rule |
|---|---|---|
| oak woodland | blackthorn, hawthorn, bramble | 20% chance under canopy: "mostly at the edge and in clearings" |
| aspen grove | willow scrub, goat willow | water within 10: "willows at wet edges" |
| pine taiga | bog pine | wet or moss ground: "wet hollows" |
| moorland | bog myrtle | wet or moss ground |
| fen and reedbed | all trees and shrubs | dry ground only: "not in the reeds" |
| hot desert | date palm, dead palm | water within 12: oases |
| hot desert | dragon tree | rock, gravel, soil, sand |
| savanna | fever tree | water within 14 |
| tropical seasonal | African mahogany | water within 24: "the wetter parts" |
| tropical rainforest | emergents | large and forest sizes only; midcanopy small to large |
| tropical coast | coconut palm, dead palm | sand within 8 / 10 of the sea |

`DROP` in `treegen.py` leaves a preset out of one biome's mix, with its share of
the count, so the other presets keep their numbers. Only `tree_fern` in the mossy
conifer forest so far: a mossy palm trunk under royal palm fronds reads as a palm
among conifers (user, 2026-10-05). It stays in the tropical rainforest.

`tropical_coast` is not a biome but a shoreline overlay on the tropical rainforest
and tropical seasonal forest: mangroves in the shallow sea off them, coconut palms
on their beaches. Its placements skip vanilla's biome filter (shallow sea carries an
ocean biome) and accept the host biomes plus ocean, beach and river.

## Test (Encia, dev server with Conquest)

Eight 96x96 areas, generated fresh for every change: oak woodland, spruce taiga,
mossy conifer forest, meadow, willow carr, birch wood, dead taiga, pine taiga.

Canopy cover (share of columns topped by leaves or wood):

| Area | WORLDGEN.md rules (2026-10-03) | Tuned (2026-10-04) |
|---|---|---|
| oak woodland | 25% | 69% |
| willow carr | 42% | 74% |
| birch wood | 31% | 66% |
| mossy conifer forest | 17% | 57% |
| spruce taiga (steep mountain) | 4% | 43% |
| pine taiga | 10% | 24% (snow on crowns reads as bare) |
| meadow (open by design) | 2% | 26% |
| dead taiga (snowy mountain; snags have no canopy) | 0% | 5% |

- No writes outside the feature window, no exceptions, no leaves standing in
  water, no road blocked (409 road columns through the mossy forest, all clear).
- What stays bare on the mountains is steeper than 60 degrees: in the spruce
  sample the median slope is 48 degrees and 41% of the ground is steeper than 55.
- Cost: 12 s of tree-placement CPU over 784 generated chunks, ~15 ms per chunk,
  spread over the worldgen threads -- about a second of the run's wall time.
  `/fmg trees stats` shows the time per biome layer (`ms=`).
- Tropical biomes and the coast overlay are not on the Encia map, so mangroves,
  palms and the shoreline overlay have **not** been seen in a world yet.

## Open points

- **Snow on crowns at altitude.** Vanilla cools a biome with height, so on high
  ground rain falls as snow, and it settles fully on leaves but only patchily on
  layered ground. High conifers turn white while the ground under them stays
  mostly bare. A climate-side fix (biome temperatures or a no-snow rule), not a
  placement one.
- Notes in the mix that are not rules yet: desert acacia "toward the dry edge";
  "apple_tree and cherry_blossom near farms" in the meadow. Alpine trees "only at
  the lower edge" follows from the ground rules in practice.
- The companion stump WORLDGEN.md suggests (a stump 3-8 blocks from a log's base
  end) needs to know which end of a log is its base.
- Forest-grown variants are not generated yet (WORLDGEN.md "More variety"); when
  they are, `treegen.py` currently converts only `setting == "open"`.
