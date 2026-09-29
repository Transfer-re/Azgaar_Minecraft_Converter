package com.example.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;

/**
 * Everything the generator needs that names a concrete block or biome, in one
 * swappable bundle. The terrain side of the mod (heightfield, routes, rivers,
 * bake, towns) is content-agnostic and shared by every profile; a profile only
 * decides <em>what the world is made of</em>:
 *
 * <ul>
 *   <li>which vanilla/modded biome each Azgaar biome category maps to, including
 *       the weighted variant pools, guaranteed rares, elevation ladders, wetland
 *       and ocean bands;</li>
 *   <li>the block palettes for roads, trails, river beds and bridges;</li>
 *   <li>which {@code noise_settings} (and therefore which surface rules) the
 *       world generates with;</li>
 *   <li>where its town structures live.</li>
 * </ul>
 *
 * <p>Profiles are plain JSON in {@code config/fantasymapgenerator/profiles/}.
 * Biomes and features themselves are authored as an ordinary worldgen datapack
 * (that is the only way Minecraft accepts custom biomes); a profile just points
 * at the ids that datapack registers, so adding a Conquest Reforged variant
 * needs no code and no compile-time dependency on Conquest Reforged.
 *
 * <p>{@code requiresMod} hides a profile unless that mod is installed, so the
 * same jar works with or without the mod it targets.
 */
public final class FmgContentProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger("FmgContentProfile");

    public static final String VANILLA_ID = "vanilla";

    public String id = VANILLA_ID;
    public String displayName = "Vanilla";
    /**
     * Revision of a built-in profile. When the file on disk is older than the
     * built-in one, {@link FmgProfiles} replaces it (keeping a backup), so an
     * updated mod's defaults reach existing installs.
     */
    public int version = 0;
    /** Mod id that must be loaded for this profile to be offered, or null/blank for always. */
    public String requiresMod;

    /**
     * Ground palette set ({@code fantasymapgenerator/surface/<id>.json}) that
     * builds a layered surface over the terrain, or null for the vanilla
     * surface rules alone.
     */
    public String surfacePalettes;
    /**
     * Blocks by which biome lookups are displaced with noise, so region borders
     * wander instead of following the map's straight cell edges. 0 = off, which
     * keeps existing worlds identical.
     */
    public double biomeEdgeWarp = 0.0;

    /** Chunk generator settings (and therefore surface rules) this profile generates with. */
    public String noiseSettings = "fantasymapgenerator:fmg_overworld_base";
    /** Resource path prefix under {@code data/<ns>/structure/} holding this profile's town buildings. */
    public String townStructureRoot = "towns/";

    public Biomes biomes = new Biomes();
    public BlockPalettes blocks = new BlockPalettes();

    // ---- biome content -------------------------------------------

    public static final class Weighted {
        public String biome;
        public double weight = 1.0;

        public Weighted() {
        }

        Weighted(String biome, double weight) {
            this.biome = biome;
            this.weight = weight;
        }
    }

    public static final class Rung {
        public String biome;
        /** Applied when the region's average FMG height is below this (last rung ignores it). */
        public double below;

        public Rung() {
        }

        Rung(String biome, double below) {
            this.biome = biome;
            this.below = below;
        }
    }

    public static final class WetlandRule {
        public String cool = "minecraft:swamp";
        public String warm = "minecraft:mangrove_swamp";
        /** Region average temperature (deg C) at which "warm" becomes possible / certain. */
        public double warmFrom = 6.0;
        public double warmTo = 16.0;
    }

    public static final class OceanBands {
        public String frozen = "minecraft:frozen_ocean";
        public String deepFrozen = "minecraft:deep_frozen_ocean";
        public String cold = "minecraft:cold_ocean";
        public String deepCold = "minecraft:deep_cold_ocean";
        public String temperate = "minecraft:ocean";
        public String deepTemperate = "minecraft:deep_ocean";
        public String lukewarm = "minecraft:lukewarm_ocean";
        public String deepLukewarm = "minecraft:deep_lukewarm_ocean";
        /** Vanilla has no deep warm ocean, so this is used at any depth. */
        public String warm = "minecraft:warm_ocean";

        /** Rank thresholds, coldest-first, among all of the map's marine cells. */
        public double frozenBelow = 0.10;
        public double coldBelow = 0.25;
        public double temperateBelow = 0.55;
        public double lukewarmBelow = 0.80;
    }

    public static final class Biomes {
        /** Normalised Azgaar biome name -> weighted variant pool. */
        public Map<String, List<Weighted>> pools = new LinkedHashMap<>();
        /** Alternative Azgaar spellings -> the pool key they share. */
        public Map<String, String> aliases = new LinkedHashMap<>();
        /** Azgaar name -> biome guaranteed at least one region, if that name occurs at all. */
        public Map<String, String> guaranteedRares = new LinkedHashMap<>();
        /** Azgaar name -> elevation ladder, picked by the region's average height instead of weights. */
        public Map<String, List<Rung>> elevationLadders = new LinkedHashMap<>();
        /** Azgaar names handled by {@link #wetland} rather than a pool. */
        public List<String> wetlandNames = new ArrayList<>(List.of("wetland", "swamp", "marsh"));
        public WetlandRule wetland = new WetlandRule();
        public OceanBands ocean = new OceanBands();

        public String beach = "minecraft:beach";
        public String snowyBeach = "minecraft:snowy_beach";
        public String river = "minecraft:river";
        public String lushCaves = "minecraft:lush_caves";
        public String dripstoneCaves = "minecraft:dripstone_caves";
        public String deepDark = "minecraft:deep_dark";
        /** Used when a mapping names a biome this profile's datapack does not register. */
        public String fallback = "minecraft:plains";
    }

    // ---- block content -------------------------------------------

    public static final class RoadPalette {
        public List<Weighted> road = new ArrayList<>();
        public List<Weighted> trail = new ArrayList<>();
    }

    public static final class BlockPalettes {
        public String bridge = "minecraft:cobblestone";

        /** Road/trail surfaces, keyed by terrain flavour: {@code default}, {@code desert}, {@code snowy}. */
        public Map<String, RoadPalette> roads = new LinkedHashMap<>();

        /**
         * River bed, keyed the same way; {@code default} is required. Picked with
         * coherent noise, so weights come out as patches rather than speckle.
         */
        public Map<String, List<Weighted>> riverBed = new LinkedHashMap<>();
        /** Lattice size of those bed patches, in blocks. */
        public double riverBedPatchScale = 14.0;
    }

    // ---- resolution ----------------------------------------------

    private final transient Map<String, RegistryKey<Biome>> biomeKeyCache = new ConcurrentHashMap<>();
    private final transient Map<String, BlockState> blockCache = new ConcurrentHashMap<>();

    /** Pool key for an already-normalised Azgaar name, following aliases. */
    public String poolKey(String normalizedName) {
        String alias = biomes.aliases.get(normalizedName);
        return alias == null ? normalizedName : alias;
    }

    public List<Weighted> poolFor(String normalizedName) {
        return biomes.pools.get(poolKey(normalizedName));
    }

    public boolean isWetland(String normalizedName) {
        return biomes.wetlandNames.contains(poolKey(normalizedName));
    }

    public List<Rung> elevationLadder(String normalizedName) {
        return biomes.elevationLadders.get(poolKey(normalizedName));
    }

    public String guaranteedRare(String normalizedName) {
        return biomes.guaranteedRares.get(normalizedName);
    }

    /** Resolves a biome id, falling back to {@link Biomes#fallback} (warning once) when unparseable. */
    public RegistryKey<Biome> biomeKey(String id) {
        if (id == null || id.isBlank()) {
            return biomeKey(biomes.fallback);
        }
        return biomeKeyCache.computeIfAbsent(id, raw -> {
            Identifier parsed = Identifier.tryParse(raw.trim());
            if (parsed == null) {
                LOGGER.warn("Profile '{}': unparseable biome id '{}' (using {})", this.id, raw, biomes.fallback);
                Identifier fallback = Identifier.tryParse(biomes.fallback);
                return RegistryKey.of(RegistryKeys.BIOME,
                        fallback != null ? fallback : new Identifier("plains"));
            }
            return RegistryKey.of(RegistryKeys.BIOME, parsed);
        });
    }

    /** Resolves a block id to its default state, falling back to stone (warning once). */
    public BlockState block(String id) {
        if (id == null || id.isBlank()) {
            return Blocks.STONE.getDefaultState();
        }
        return blockCache.computeIfAbsent(id, raw -> {
            Identifier parsed = Identifier.tryParse(raw.trim());
            if (parsed == null || !Registries.BLOCK.containsId(parsed)) {
                LOGGER.warn("Profile '{}': unknown block id '{}' (using stone)", this.id, raw);
                return Blocks.STONE.getDefaultState();
            }
            Block block = Registries.BLOCK.get(parsed);
            return block.getDefaultState();
        });
    }

    /** Deterministic weighted pick from a palette, driven by a caller-supplied hash. */
    public BlockState pickBlock(List<Weighted> palette, long hash) {
        Weighted picked = pick(palette, hash);
        return picked == null ? Blocks.STONE.getDefaultState() : block(picked.biome);
    }

    /**
     * Weighted pick driven by a 0..1 roll. Feeding this coherent noise instead of
     * a per-block hash makes the palette come out as patches (sand bars, gravel
     * runs) rather than speckle, while keeping the same proportions.
     */
    public BlockState pickBlockByRoll(List<Weighted> palette, double roll01) {
        Weighted picked = pickByRoll(palette, roll01);
        return picked == null ? Blocks.STONE.getDefaultState() : block(picked.biome);
    }

    public static Weighted pick(List<Weighted> weighted, long hash) {
        return pickByRoll(weighted, (hash >>> 11) / (double) (1L << 53));
    }

    public static Weighted pickByRoll(List<Weighted> weighted, double roll01) {
        if (weighted == null || weighted.isEmpty()) return null;
        double total = 0;
        for (Weighted w : weighted) total += Math.max(0, w.weight);
        if (total <= 0) return weighted.get(0);
        double roll = Math.max(0.0, Math.min(1.0, roll01)) * total;
        double acc = 0;
        for (Weighted w : weighted) {
            acc += Math.max(0, w.weight);
            if (roll <= acc) return w;
        }
        return weighted.get(weighted.size() - 1);
    }

    /** Every biome this profile can produce -- used to declare the biome source's palette. */
    public List<RegistryKey<Biome>> allBiomeKeys() {
        List<String> ids = new ArrayList<>();
        for (List<Weighted> pool : biomes.pools.values()) {
            for (Weighted w : pool) ids.add(w.biome);
        }
        ids.addAll(biomes.guaranteedRares.values());
        for (List<Rung> ladder : biomes.elevationLadders.values()) {
            for (Rung r : ladder) ids.add(r.biome);
        }
        ids.add(biomes.wetland.cool);
        ids.add(biomes.wetland.warm);
        ids.add(biomes.ocean.frozen);
        ids.add(biomes.ocean.deepFrozen);
        ids.add(biomes.ocean.cold);
        ids.add(biomes.ocean.deepCold);
        ids.add(biomes.ocean.temperate);
        ids.add(biomes.ocean.deepTemperate);
        ids.add(biomes.ocean.lukewarm);
        ids.add(biomes.ocean.deepLukewarm);
        ids.add(biomes.ocean.warm);
        ids.add(biomes.beach);
        ids.add(biomes.snowyBeach);
        ids.add(biomes.river);
        ids.add(biomes.lushCaves);
        ids.add(biomes.dripstoneCaves);
        ids.add(biomes.deepDark);
        ids.add(biomes.fallback);

        List<RegistryKey<Biome>> out = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            RegistryKey<Biome> key = biomeKey(id);
            if (!out.contains(key)) out.add(key);
        }
        return out;
    }

    /** Fills in anything a hand-edited profile left out, so a partial file still loads. */
    public void sanitize() {
        FmgContentProfile d = vanilla();
        if (id == null || id.isBlank()) id = d.id;
        if (displayName == null || displayName.isBlank()) displayName = id;
        if (noiseSettings == null || noiseSettings.isBlank()) noiseSettings = d.noiseSettings;
        if (townStructureRoot == null || townStructureRoot.isBlank()) townStructureRoot = d.townStructureRoot;
        if (biomes == null) biomes = d.biomes;
        if (biomes.pools == null || biomes.pools.isEmpty()) biomes.pools = d.biomes.pools;
        if (biomes.aliases == null) biomes.aliases = d.biomes.aliases;
        if (biomes.guaranteedRares == null) biomes.guaranteedRares = d.biomes.guaranteedRares;
        if (biomes.elevationLadders == null) biomes.elevationLadders = d.biomes.elevationLadders;
        if (biomes.wetlandNames == null || biomes.wetlandNames.isEmpty()) {
            biomes.wetlandNames = d.biomes.wetlandNames;
        }
        if (biomes.wetland == null) biomes.wetland = d.biomes.wetland;
        if (biomes.ocean == null) biomes.ocean = d.biomes.ocean;
        if (biomes.fallback == null || biomes.fallback.isBlank()) biomes.fallback = d.biomes.fallback;
        if (blocks == null) blocks = d.blocks;
        if (blocks.roads == null || blocks.roads.isEmpty()) blocks.roads = d.blocks.roads;
        if (blocks.riverBed == null || blocks.riverBed.isEmpty()) blocks.riverBed = d.blocks.riverBed;
        if (blocks.bridge == null || blocks.bridge.isBlank()) blocks.bridge = d.blocks.bridge;
    }

    public static String normalizeName(String rawName) {
        if (rawName == null || rawName.isBlank()) return "";
        return rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ---- the built-in vanilla profile ------------------------------

    private static Weighted w(String biome, double weight) {
        return new Weighted(biome, weight);
    }

    private static List<Weighted> pool(Weighted... entries) {
        return new ArrayList<>(List.of(entries));
    }

    /**
     * The vanilla / WWOO profile: exactly the mapping the generator used before
     * profiles existed, so an existing world keeps generating identically.
     */
    public static FmgContentProfile vanilla() {
        FmgContentProfile p = new FmgContentProfile();
        p.id = VANILLA_ID;
        p.displayName = "Vanilla / WWOO";
        // 1: Minecraft 1.20.1 has no pale garden, so it left the forest pool
        p.version = 1;

        Map<String, List<Weighted>> pools = p.biomes.pools;
        pools.put("grassland", pool(
                w("minecraft:plains", 0.55), w("minecraft:sunflower_plains", 0.20),
                w("minecraft:meadow", 0.20), w("minecraft:cherry_grove", 0.05)));
        pools.put("temperatedeciduousforest", pool(
                w("minecraft:forest", 0.35), w("minecraft:flower_forest", 0.15),
                w("minecraft:birch_forest", 0.20), w("minecraft:old_growth_birch_forest", 0.15),
                w("minecraft:dark_forest", 0.15)));
        pools.put("taiga", pool(
                w("minecraft:taiga", 0.55), w("minecraft:old_growth_pine_taiga", 0.25),
                w("minecraft:old_growth_spruce_taiga", 0.20)));
        pools.put("temperaterainforest", pool(
                w("minecraft:old_growth_spruce_taiga", 0.35), w("minecraft:dark_forest", 0.30),
                w("minecraft:old_growth_pine_taiga", 0.20), w("minecraft:birch_forest", 0.15)));
        pools.put("tundra", pool(
                w("minecraft:snowy_plains", 0.55), w("minecraft:snowy_taiga", 0.35),
                w("minecraft:ice_spikes", 0.10)));
        pools.put("savanna", pool(
                w("minecraft:savanna", 0.60), w("minecraft:savanna_plateau", 0.20),
                w("minecraft:windswept_savanna", 0.20)));
        pools.put("hotdesert", pool(
                w("minecraft:desert", 0.85), w("minecraft:badlands", 0.15)));
        pools.put("mesa", pool(
                w("minecraft:badlands", 0.5), w("minecraft:eroded_badlands", 0.25),
                w("minecraft:wooded_badlands", 0.25)));
        pools.put("colddesert", pool(
                w("minecraft:windswept_gravelly_hills", 0.7), w("minecraft:windswept_hills", 0.3)));
        pools.put("steppe", pool(
                w("minecraft:windswept_savanna", 0.7), w("minecraft:savanna", 0.3)));
        pools.put("tropicalseasonalforest", pool(
                w("minecraft:jungle", 0.35), w("minecraft:sparse_jungle", 0.45),
                w("minecraft:savanna", 0.20)));
        pools.put("tropicalrainforest", pool(
                w("minecraft:jungle", 0.55), w("minecraft:sparse_jungle", 0.20),
                w("minecraft:bamboo_jungle", 0.25)));

        Map<String, String> aliases = p.biomes.aliases;
        aliases.put("plains", "grassland");
        aliases.put("forest", "temperatedeciduousforest");
        aliases.put("desert", "hotdesert");
        aliases.put("jungle", "tropicalrainforest");
        aliases.put("rainforest", "tropicalrainforest");

        Map<String, String> rares = p.biomes.guaranteedRares;
        rares.put("grassland", "minecraft:cherry_grove");
        rares.put("plains", "minecraft:cherry_grove");
        rares.put("tropicalrainforest", "minecraft:bamboo_jungle");
        rares.put("jungle", "minecraft:bamboo_jungle");
        rares.put("wetland", "minecraft:mangrove_swamp");
        rares.put("swamp", "minecraft:mangrove_swamp");

        Map<String, List<Rung>> ladders = p.biomes.elevationLadders;
        ladders.put("glacier", new ArrayList<>(List.of(
                new Rung("minecraft:grove", 40), new Rung("minecraft:snowy_slopes", 55),
                new Rung("minecraft:frozen_peaks", 75), new Rung("minecraft:jagged_peaks", Double.MAX_VALUE))));
        List<Rung> mountain = new ArrayList<>(List.of(
                new Rung("minecraft:windswept_forest", 35), new Rung("minecraft:windswept_hills", 50),
                new Rung("minecraft:windswept_gravelly_hills", 70),
                new Rung("minecraft:stony_peaks", Double.MAX_VALUE)));
        ladders.put("mountain", mountain);
        ladders.put("highlands", new ArrayList<>(mountain));

        RoadPalette def = new RoadPalette();
        def.road = pool(w("minecraft:dirt_path", 1));
        def.trail = pool(w("minecraft:dirt_path", 1));
        RoadPalette desert = new RoadPalette();
        desert.road = pool(w("minecraft:terracotta", 3), w("minecraft:granite", 2),
                w("minecraft:polished_granite", 2), w("minecraft:sand", 1));
        desert.trail = pool(w("minecraft:sand", 3), w("minecraft:birch_planks", 1));
        RoadPalette snowy = new RoadPalette();
        snowy.road = pool(w("minecraft:cobblestone", 2), w("minecraft:gravel", 2),
                w("minecraft:andesite", 2), w("minecraft:stone_bricks", 2));
        snowy.trail = pool(w("minecraft:cobblestone", 2), w("minecraft:gravel", 2));
        p.blocks.roads.put("default", def);
        p.blocks.roads.put("desert", desert);
        p.blocks.roads.put("snowy", snowy);

        p.blocks.riverBed.put("default", pool(
                w("minecraft:gravel", 6), w("minecraft:sand", 2), w("minecraft:clay", 1)));
        p.blocks.riverBed.put("desert", pool(w("minecraft:sand", 1)));

        return p;
    }

    /** Revision of {@link #conquest()}; bump when its defaults change. */
    public static final int CONQUEST_VERSION = 2;

    private static final String CQ = "fantasymapgenerator:conquest/";

    /**
     * The Conquest Reforged profile: the 29 Conquest biomes
     * ({@code data/fantasymapgenerator/worldgen/biome/conquest/}) over the Azgaar
     * categories, and a layered ground built from Conquest layer blocks
     * ({@code surface/conquest.json}). Oceans, beaches and rivers keep their
     * vanilla biomes; their ground comes from the sea palettes.
     */
    public static FmgContentProfile conquest() {
        FmgContentProfile p = vanilla();
        p.id = "conquest";
        p.displayName = "Conquest Reforged";
        p.version = CONQUEST_VERSION;
        p.requiresMod = "conquest";
        p.noiseSettings = "fantasymapgenerator:fmg_overworld_conquest";
        p.townStructureRoot = "towns_conquest/";
        p.surfacePalettes = "conquest";
        p.biomeEdgeWarp = 18.0;

        Map<String, List<Weighted>> pools = p.biomes.pools;
        pools.clear();
        pools.put("grassland", pool(
                w(CQ + "meadow", 0.45), w(CQ + "moorland", 0.30), w(CQ + "downland", 0.25)));
        pools.put("temperatedeciduousforest", pool(
                w(CQ + "oak_woodland", 0.26), w(CQ + "mixed_broadleaf_forest", 0.20),
                w(CQ + "beech_forest", 0.14), w(CQ + "autumn_broadleaf_forest", 0.12),
                w(CQ + "birch_wood", 0.10), w(CQ + "mixed_conifer_broadleaf", 0.10),
                w(CQ + "aspen_grove", 0.08)));
        pools.put("taiga", pool(
                w(CQ + "spruce_taiga", 0.30), w(CQ + "pine_taiga", 0.20), w(CQ + "larch_taiga", 0.15),
                w(CQ + "autumn_larch_taiga", 0.10), w(CQ + "old_growth_spruce", 0.10),
                w(CQ + "mixed_conifer_broadleaf", 0.05), w(CQ + "birch_wood", 0.05),
                w(CQ + "dead_taiga", 0.05)));
        pools.put("temperaterainforest", pool(
                w(CQ + "mossy_conifer_forest", 0.80), w(CQ + "old_growth_spruce", 0.20)));
        pools.put("savanna", pool(w(CQ + "savanna", 1.0)));
        pools.put("hotdesert", pool(w(CQ + "hot_desert", 1.0)));
        pools.put("mesa", pool(w(CQ + "hot_desert", 1.0)));
        pools.put("colddesert", pool(w(CQ + "cold_steppe", 1.0)));
        pools.put("steppe", pool(w(CQ + "cold_steppe", 0.7), w(CQ + "savanna", 0.3)));
        pools.put("tropicalseasonalforest", pool(
                w(CQ + "tropical_seasonal_forest", 0.8), w(CQ + "savanna", 0.2)));
        pools.put("tropicalrainforest", pool(w(CQ + "tropical_rainforest", 1.0)));
        // "wetland" is a weighted pool here; the temperature rule below only
        // catches the other spellings
        pools.put("wetland", pool(
                w(CQ + "sphagnum_bog", 0.40), w(CQ + "fen_reedbed", 0.35), w(CQ + "willow_carr", 0.25)));
        p.biomes.wetlandNames = new ArrayList<>(List.of("swamp", "marsh"));
        p.biomes.wetland.cool = CQ + "sphagnum_bog";
        p.biomes.wetland.warm = CQ + "willow_carr";

        p.biomes.guaranteedRares.clear();
        Map<String, List<Rung>> ladders = p.biomes.elevationLadders;
        ladders.clear();
        ladders.put("tundra", new ArrayList<>(List.of(
                new Rung(CQ + "shrub_tundra", 40), new Rung(CQ + "lichen_tundra", 60),
                new Rung(CQ + "alpine_scree", Double.MAX_VALUE))));
        ladders.put("glacier", new ArrayList<>(List.of(new Rung(CQ + "glacier", Double.MAX_VALUE))));
        List<Rung> mountain = new ArrayList<>(List.of(
                new Rung(CQ + "spruce_taiga", 35), new Rung(CQ + "larch_taiga", 50),
                new Rung(CQ + "alpine_scree", 70), new Rung(CQ + "glacier", Double.MAX_VALUE)));
        ladders.put("mountain", mountain);
        ladders.put("highlands", new ArrayList<>(mountain));
        p.biomes.fallback = CQ + "meadow";
        return p;
    }
}
