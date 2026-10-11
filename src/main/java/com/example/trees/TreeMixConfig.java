package com.example.trees;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.FeatureConfig;

/**
 * One layer of one biome's mix: which presets, how they are weighted and sized,
 * and the spacing for the layer. Written by {@code tools/fmgtrees/treegen.py} as
 * {@code configured_feature/treegen/<biome>/<layer>.json} from the TreeGenerator's
 * {@code biome_mix.toml}. How many attempts per chunk is the placed feature's
 * {@code count}, as for any vanilla vegetation.
 *
 * @param spacing  k in {@code distance >= k * (crown_a + crown_b)} for trees and
 *                 dead trees, {@code 0.7} between shrubs
 * @param attempts spots tried per placement before giving up: on rough or wet
 *                 ground the first spot often fails, and without retries the
 *                 realised density would fall well below the mix's numbers
 * @param slope    how many blocks the ground under the trunk footprint may vary
 *                 beyond the object's sunk depth (WORLDGEN.md's rule is 1)
 * @param gradient steepness the ground may have anyway, as rise over run across
 *                 the footprint (1.0 = 45 degrees); 0 to rely on {@code slope} alone.
 *                 A block count alone is stricter for wide trunks, whose wider
 *                 footprint spans more rise on the same hillside.
 * @param hosts    shoreline overlays only: the biomes this overlay belongs to.
 *                 Their placements skip vanilla's biome filter, because the
 *                 shallow sea a mangrove stands in carries an ocean biome.
 */
public record TreeMixConfig(String layer, float spacing, int attempts, int slope, float gradient,
                            List<Entry> entries, Optional<List<Identifier>> hosts) implements FeatureConfig {

    public static final Codec<TreeMixConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("layer").forGetter(TreeMixConfig::layer),
            Codec.FLOAT.fieldOf("spacing").forGetter(TreeMixConfig::spacing),
            Codec.INT.optionalFieldOf("attempts", 3).forGetter(TreeMixConfig::attempts),
            Codec.INT.optionalFieldOf("slope", 1).forGetter(TreeMixConfig::slope),
            Codec.FLOAT.optionalFieldOf("gradient", 0f).forGetter(TreeMixConfig::gradient),
            Entry.CODEC.listOf().fieldOf("entries").forGetter(TreeMixConfig::entries),
            Identifier.CODEC.listOf().optionalFieldOf("hosts").forGetter(TreeMixConfig::hosts)
    ).apply(i, TreeMixConfig::new));

    /**
     * A preset in the mix.
     *
     * @param preset    a {@code treegen_object} configured feature
     * @param sizes     size -> relative weight, for this biome
     * @param ground    ground groups for this biome (replaces the preset's own)
     * @param nearWater fresh or sea water must lie within this many blocks (0: off)
     * @param nearSea   sea water must lie within this many blocks (0: off)
     * @param openSky   chance to keep a spot that already has canopy overhead
     *                  (1: no preference; low values keep it to edges and clearings)
     */
    public record Entry(RegistryEntry<ConfiguredFeature<?, ?>> preset, float weight,
                        Map<String, Float> sizes, List<String> ground, int nearWater, int nearSea,
                        float openSky) {

        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                ConfiguredFeature.REGISTRY_CODEC.fieldOf("preset").forGetter(Entry::preset),
                Codec.FLOAT.fieldOf("weight").forGetter(Entry::weight),
                Codec.unboundedMap(Codec.STRING, Codec.FLOAT).optionalFieldOf("sizes", Map.of())
                        .forGetter(Entry::sizes),
                Codec.STRING.listOf().optionalFieldOf("ground", List.of()).forGetter(Entry::ground),
                Codec.INT.optionalFieldOf("near_water", 0).forGetter(Entry::nearWater),
                Codec.INT.optionalFieldOf("near_sea", 0).forGetter(Entry::nearSea),
                Codec.FLOAT.optionalFieldOf("open_sky", 1.0f).forGetter(Entry::openSky)
        ).apply(i, Entry::new));
    }
}
