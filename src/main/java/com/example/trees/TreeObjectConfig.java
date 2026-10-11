package com.example.trees;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.FeatureConfig;

/**
 * One TreeGenerator preset: every object it has (all sizes), and where it may
 * stand. Written by {@code tools/fmgtrees/treegen.py} as
 * {@code configured_feature/treegen/preset/<preset>.json}.
 *
 * @param layer   trees, dead, logs or shrubs -- which spacing rules apply
 * @param ground  ground groups it may stand on (the surface palettes' groups)
 * @param water   present for mangroves: they stand in water this deep
 * @param objects every schematic of the preset
 */
public record TreeObjectConfig(String preset, String layer, List<String> ground,
                               Optional<Water> water, List<ObjectSpec> objects) implements FeatureConfig {

    public static final Codec<TreeObjectConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("preset").forGetter(TreeObjectConfig::preset),
            Codec.STRING.fieldOf("layer").forGetter(TreeObjectConfig::layer),
            Codec.STRING.listOf().optionalFieldOf("ground", List.of()).forGetter(TreeObjectConfig::ground),
            Water.CODEC.optionalFieldOf("water").forGetter(TreeObjectConfig::water),
            ObjectSpec.CODEC.listOf().fieldOf("objects").forGetter(TreeObjectConfig::objects)
    ).apply(i, TreeObjectConfig::new));

    /**
     * One schematic.
     *
     * @param anchor the paste point inside the template (trunk base; middle of a
     *               fallen log); y = anchor y is the first layer above the ground
     * @param crown  furthest leaf (or wood) from the paste column, blocks
     * @param base   trunk footprint radius at ground level; half the length of a log
     * @param lying  fallen logs: the axis the log lies along, unrotated
     */
    public record ObjectSpec(Identifier template, String size, List<Integer> anchor, float crown,
                             float base, int height, int depth, Optional<String> lying) {

        public static final Codec<ObjectSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("template").forGetter(ObjectSpec::template),
                Codec.STRING.fieldOf("size").forGetter(ObjectSpec::size),
                Codec.INT.listOf().fieldOf("anchor").forGetter(ObjectSpec::anchor),
                Codec.FLOAT.fieldOf("crown").forGetter(ObjectSpec::crown),
                Codec.FLOAT.fieldOf("base").forGetter(ObjectSpec::base),
                Codec.INT.fieldOf("height").forGetter(ObjectSpec::height),
                Codec.INT.optionalFieldOf("depth", 0).forGetter(ObjectSpec::depth),
                Codec.STRING.optionalFieldOf("lying").forGetter(ObjectSpec::lying)
        ).apply(i, ObjectSpec::new));
    }

    /**
     * Mangroves: water from {@code min} to {@code max} blocks deep over the bed,
     * and with {@code sea} only sea water (its surface at sea level), not rivers.
     */
    public record Water(int minDepth, int maxDepth, boolean sea) {

        public static final Codec<Water> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("min_depth").forGetter(Water::minDepth),
                Codec.INT.fieldOf("max_depth").forGetter(Water::maxDepth),
                Codec.BOOL.optionalFieldOf("sea", false).forGetter(Water::sea)
        ).apply(i, Water::new));
    }
}
