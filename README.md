# Fantasy Map Generator for Minecraft

Turns a map from [Azgaar's Fantasy Map Generator](https://azgaar.github.io/Fantasy-Map-Generator/) (FMG) into a Minecraft overworld. The map's heights become the terrain, its biomes become Minecraft biomes, its rivers are carved into valleys, its roads are laid out with bridges, and its towns (burgs) become villages. The world can also be built from [Conquest Reforged](https://www.conquestreforged.com/) blocks, biomes and trees instead of vanilla ones.

**This branch: `fabric-1.20.1`**: Fabric, Minecraft 1.20.1, vanilla or Conquest Reforged content.

| Branch | Minecraft | Loader | Content |
|---|---|---|---|
| `fabric-1.20.1` | 1.20.1 | Fabric + Fabric API | vanilla, Conquest Reforged |
| `forge-1.20.1` | 1.20.1 | Forge 47 | vanilla, Conquest Reforged |
| `fabric-1.21.1` | 1.21.1 | Fabric + Fabric API | vanilla |
| `neoforge-1.21.1` | 1.21.1 | NeoForge 21.1 | vanilla |
| `fabric-1.21.4` | 1.21.4 | Fabric + Fabric API | vanilla (older version of the mod) |

Conquest Reforged only exists for Minecraft 1.20.1, so the 1.21 versions are vanilla only.

## Gallery

### Granesia, generated with Conquest Reforged

The full Granesia map as a 27,000 x 14,000 block world (about 1.5 million chunks, pregenerated with Chunky), rendered with [uNmINeD](https://unmined.net/). uNmINeD does not know Conquest blocks; `tools/fmgtrees/unmined_config.py` writes the uNmINeD config that gives every Conquest block its in-game colour.

![Granesia, the whole world (1 pixel = 8 blocks)](images/granesia_world_map.jpg)

[Full resolution, 1 pixel = 4 blocks (6824 x 3588, 14 MB)](images/granesia_world_map_full.jpg)

![Glaciated mountains meeting forest and a river (1 pixel = 2 blocks)](images/granesia_mountains.jpg)
![Northern coast with roads, forest and frozen ocean (1 pixel = 2 blocks)](images/granesia_coast.jpg)

### In game, with Conquest Reforged

Conquest Reforged blocks and textures; the last six with a shader pack.

| | |
|---|---|
| ![A river in its carved valley; roads cross it on stone bridges](images/conquest_river_bridge.jpg) | ![Roads from the map follow the terrain over the hills](images/conquest_road.jpg) |
| A river in its carved valley; roads cross it on stone bridges | Roads from the map follow the terrain over the hills |
| ![Conifer forest up to the snow line](images/conquest_treeline.jpg) | ![Savanna with TreeGenerator trees](images/conquest_savanna.jpg) |
| Conifer forest up to the snow line | Savanna with TreeGenerator trees |
| ![Tropical forest on a coastal hill](images/conquest_tropical_hill.jpg) | ![A lake in the hills at sunset](images/conquest_lake.jpg) |
| Tropical forest on a coastal hill | A lake in the hills at sunset |
| ![Palms on a tropical coast](images/conquest_palms.jpg) | ![A cliff in tropical seasonal forest](images/conquest_cliff.jpg) |
| Palms on a tropical coast | A cliff in tropical seasonal forest |

### In game, vanilla

![In-game screenshot](images/2026-01-15_13.45.00.png)
![In-game screenshot](images/2026-03-18_13.36.08.png)
![In-game screenshot](images/2026-03-18_13.43.20.png)
![In-game screenshot](images/2026-03-19_14.16.48.png)
![In-game screenshot](images/2026-03-20_12.07.41.png)

### The source map

An FMG map (zoomable), and two more map examples:

![FMG map overview (zoomable)](images/fmg__map.png)

![Map example](images/Screenshot%202026-03-19%20125819.png)
![Map example](images/Screenshot%202026-03-19%20141940.png)

## Features

- **Terrain from the map.** Heights come from the FMG export. The *Blended* terrain mode erodes mountains and shapes lowlands per biome; *Legacy* follows the map heights directly. The build limit is raised automatically when the mountains need it (up to Y 2032).
- **Rivers** are carved as valleys along the map's rivers, with water and river beds.
- **Roads and trails** follow the map's routes, with bridges over rivers and materials that suit the biome (in deserts, for example, terracotta roads and sand trails).
- **Biomes from the map.** Each FMG biome maps to a mix of Minecraft biome variants, chosen per region so neighbouring areas don't all look the same.
- **Villages at the map's towns.** Each burg gets a village in the style of its biome (from `villages.json`).
- **Region titles.** Entering another state or province shows its name on screen; `/fmg borders` shows the borders as walls.
- **Caves**: vanilla 1.18-style noise caves under the map terrain.
- **Conquest Reforged content** (when Conquest Reforged is installed): 29 Conquest biomes, ground built from Conquest layer blocks that blends between biomes, the project's own TreeGenerator trees, Conquest sky and fog colours in FMG worlds, and vanilla and modded structures that also spawn in the Conquest biomes.
- **Self-contained worlds.** The map JSON is copied into the world folder, and the terrain, rivers, roads and biomes are prepared ("baked") once, on the first load.
- **For WorldPainter and other editors**: `/fmg export` turns an area into a plain vanilla save that opens without the mod, and `/fmg heightmap` writes the terrain as a heightmap image.

## Setting up a client (singleplayer)

### 1. Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) (0.18.4 or newer) for Minecraft 1.20.1, and put [Fabric API](https://modrinth.com/mod/fabric-api) in `mods/`.
2. Put the Fantasy Map Generator jar in `mods/`. There are no release downloads yet; see [Building from source](#building-from-source).
3. For the Conquest content, add [Conquest Reforged](https://modrinth.com/mod/conquest-reforged) (Fabric, Minecraft 1.20.1; tested with 1.7.0). Without it the mod generates vanilla worlds and only offers the vanilla content.
4. For vanilla worlds, [William Wythers' Overhauled Overworld](https://modrinth.com/mod/wwoo/) (WWOO) is recommended: it adds biome blending and sub-biomes that make the vanilla biomes look much better. It adds no blocks or items.

### 2. Export your map from FMG

In Fantasy Map Generator, export the map as a **Full JSON** (*Export* > *Export to JSON* > *Full*). It contains everything the mod reads: heights, biomes, states, provinces, burgs, routes and rivers. Save the file anywhere.

![FMG JSON export](images/Screenshot%202026-03-20%20152618.png)

### 3. Create the world

1. *Singleplayer* > *Create New World*.
2. On the *World* tab, set *World Type* to **Fantasy Map**.
3. Click **Customize**, click **Map file** and pick your JSON export.
4. Choose the options below, then *Done*.
5. Click *Create New World*. The first load shows **Preparing Fantasy Map** with a progress bar while it shapes the terrain, rivers, roads and biomes. That takes a few minutes for a large map and only happens once per world.

*Create New World* stays greyed out until a map file is chosen.

| Option | What it does |
|---|---|
| Map file | The FMG JSON export to build the world from. |
| Terrain | **Blended** (default): eroded mountains and biome-shaped lowlands. **Legacy**: the map heights as they are. |
| Map scale | World blocks per map pixel, 4 to 48 (default 16). A 1700 x 900 pixel map at 16 becomes about 27,000 x 14,000 blocks. |
| Surface detail noise | Adds small hills and dips on top of the map heights (default on). |
| Detail amount | How strong that noise is, in blocks (0 to 14, default 6). |
| Biome variants | On (default): each FMG biome becomes a mix of Minecraft biomes. Off: one Minecraft biome per FMG biome, from `biome_mappings.json`. |
| Content | **Vanilla / WWOO**, or **Conquest Reforged** (only listed when Conquest Reforged is installed). |

The centre of the map is at X 0, Z 0. Nether and End stay vanilla. Sea level is Y 63.

## Setting up a server

A dedicated server has no Create World screen, so create the world in singleplayer first and move it over.

1. Install the Fabric server for Minecraft 1.20.1 (the Fabric installer has a Server tab) with Fabric API, and put this mod and (for a Conquest world) Conquest Reforged in the server's `mods/`. Leave client-only mods off the server.
2. In singleplayer, with the same mod versions, create the world as described above and wait until *Preparing Fantasy Map* has finished and you are in the world. Quit to the title screen.
3. Copy these from `.minecraft/saves/<your world>/` into the server's world folder (`world/`, or whatever `level-name` in `server.properties` says):
   - `level.dat`: the world settings, including the map and its options
   - `fmg/`: the map JSON and the prepared terrain
   - `region/`, `entities/` and `poi/` as well if you want to keep what you already explored; without them the server generates everything fresh.
4. Copy `.minecraft/config/fantasymapgenerator/` to the server's `config/`. If the settings there differ, the server prepares the terrain again on its first start, with its own settings.
5. Start the server. With matching files the log says `Loaded FMG bake from ...` and it skips preparing the terrain.

**Players** need the same mods as the server, including Conquest Reforged for a Conquest world. The mod on the client also adds `/fmg borders`.

**Pregenerating** a large world is worth it: with [Chunky](https://modrinth.com/plugin/chunky), generate a rectangle of half the map size around the centre. For a 1707 x 898 pixel map at scale 16:

```
chunky center 0 0
chunky shape rectangle
chunky radius 13656 7184
chunky start
```

(radius = map width in pixels x scale / 2, and map height in pixels x scale / 2)

## Commands

| Command | Who | What it does |
|---|---|---|
| `/fmg borders [province\|state\|off]` | everyone | Shows the borders of the province (default) or state you are in as walls, or hides them. Needs the mod on the client. |
| `/fmg biome report [step]` | op | Counts the biomes the map produces and checks the guaranteed rare biomes. |
| `/fmg export <name> [radius] [generate]` | op | **For WorldPainter, Amulet and other world editors**, which can't open an FMG world: copies the area within `radius` blocks of you (default 1024) into a standalone vanilla save, `saves/<name>/`, that opens without the mod. Only chunks that already exist are copied; add `generate` to generate the missing ones first (this freezes the server while it runs). |
| `/fmg heightmap <radius> [name]` | op | **For WorldPainter and other terrain tools**: writes the terrain within `radius` blocks of you to `fmg_heightmaps/` in the game folder: `<name>.height.png`, a 16-bit grayscale heightmap to import (black is the lowest and white the highest Y, both printed by the command), a hillshade preview and an `.fmgh` heightfield. |
| `/fmg trees stats` / `reset` | op | Tree placement statistics per biome layer (placed, refused and why), and resetting them. |

## Configuration

The settings chosen on the Customize screen are saved with each world. The files in `config/fantasymapgenerator/` are shared defaults and tuning, and are written on first launch:

| File | What it holds |
|---|---|
| `heightmap.json` | Terrain shaping (`terrain`; `terrain.peak` is the mountain height and sets the build limit), river carving (`rivers`) and the detail noise defaults (`detail`). |
| `profiles/*.json` | Content profiles (`vanilla.json`, `conquest.json`): which Minecraft biomes each FMG biome becomes, road, river bed and bridge blocks, and which vanilla biomes the Conquest biomes stand in for when structures pick a spot. Edit in place; a newer built-in version replaces the file and keeps the old one as `.bak`. |
| `biome_mappings.json` | The one-to-one FMG to Minecraft biome table used when *Biome variants* is off. |
| `villages.json` | Which village style each biome's burgs get. |
| `config.json` | `sampleScale`, the scale for worlds made before the Customize screen existed, and `mapJsonPath`, used by the command-line tools only. |
| `surface/<id>.json` | Optional override for a ground palette set, for example `surface/conquest.json` (the built-in one is in the jar; see `docs/conquest/palettes.md`). |

Changing `heightmap.json` or a profile makes worlds that use them prepare their terrain again on the next load. Chunks that were already generated stay as they are.

## Troubleshooting

- **Create New World is greyed out**: choose a map file first (*World* tab > *Customize* > *Map file*).
- **The first load takes long**: that is the one-time *Preparing Fantasy Map* step. Later loads are quick.
- **No Conquest Reforged option under Content**: Conquest Reforged is not installed (mod id `conquest`).
- **The server prepares the terrain again instead of loading it**: its `config/fantasymapgenerator/` differs from the one the world was made with. Copy it over from the client (or let it finish; it only does this once).
- **No borders or region titles**: they only work in a Fantasy Map world, and `/fmg borders` needs the mod on the client as well.
- **Map file not found when opening an old world**: newer worlds keep their own copy in `<world>/fmg/map.json`. Older ones read the original path; put the JSON back there or copy it into `<world>/fmg/map.json`.

## Building from source

- Needs JDK 17. `gradlew build` writes the mod to `build/libs/fantasymapgenerator-1.0.0.jar`.
- `gradlew runClient` and `gradlew runServer` start a development game. Their run folders are `../runs/fabric-1.20.1` and `../runs/fabric-1.20.1-server`, next to the repository rather than inside it.
- The Conquest trees are structure files in `src/main/resources/data/fantasymapgenerator/structures/treegen/`, generated from TreeGenerator by `tools/fmgtrees/treegen.py` (see `docs/conquest/trees.md`) and committed. The build stops if that folder is empty, so a jar can't end up without trees.

## Credits

- **[Azgaar's Fantasy Map Generator](https://azgaar.github.io/Fantasy-Map-Generator/)** by Azgaar -- the map format this mod reads, and the reason it exists.
- **[Conquest Reforged](https://www.conquestreforged.com/)** -- the block set the Conquest content is built from (mod id `conquest`). It is not included in this mod; install it separately.
- **The trees** are this project's own, built from Conquest Reforged blocks with TreeGenerator, the author's tree generator.

## License

See `LICENSE`. Conquest Reforged is under its own terms.
