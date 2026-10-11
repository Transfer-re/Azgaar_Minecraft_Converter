package com.example.trees;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

/**
 * One TreeGenerator preset, placed on its own: a random object of any size.
 * The biome mixes call into the same placer; this one exists so a preset can be
 * dropped with {@code /place feature fantasymapgenerator:treegen/preset/<preset>}
 * to look at it in the world. It keeps the preset's ground and water rules but
 * not the spacing, since a hand placement is meant to land where it is put.
 */
public final class TreeObjectFeature extends Feature<TreeObjectConfig> {

    public TreeObjectFeature() {
        super(TreeObjectConfig.CODEC);
    }

    @Override
    public boolean generate(FeatureContext<TreeObjectConfig> context) {
        TreeObjectConfig cfg = context.getConfig();
        if (cfg.objects().isEmpty()) return false;
        TreePlacer.Ctx c = TreeFeatures.ctx(context);
        TreeObjectConfig.ObjectSpec spec = cfg.objects().get(context.getRandom().nextInt(cfg.objects().size()));
        TreePlacer.Rules rules = new TreePlacer.Rules(TreeFeatures.layer(cfg.layer()), cfg.ground(), cfg.water(),
                0f, 0, 0, 1f, 3, 1f);
        BlockPos o = context.getOrigin();
        TreePlacer.Fail fail = TreePlacer.place(c, spec, rules, o.getX(), o.getZ(), context.getRandom().nextInt(4));
        if (fail != null) {
            TreePlacer.count("place " + cfg.preset() + " refused " + fail);
        }
        return fail == null;
    }
}
