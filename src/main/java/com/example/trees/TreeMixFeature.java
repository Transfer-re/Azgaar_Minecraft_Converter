package com.example.trees;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

/**
 * One layer of a biome's tree mix: picks a preset by weight, a size by the
 * biome's size weights, one of that size's objects at random and a quarter-turn,
 * and places it. Placed by an ordinary vanilla placed feature -- count,
 * in_square, surface_water_depth_filter, heightmap, biome -- once per count.
 *
 * <p>A refused spot (too steep, wrong ground, too close to another tree) is
 * retried elsewhere in the same chunk, up to {@code attempts} times, and only in
 * the same biome as the spot vanilla's biome filter approved. Without retries
 * rough or wet ground would quietly cut every number in the mix.
 */
public final class TreeMixFeature extends Feature<TreeMixConfig> {

    public TreeMixFeature() {
        super(TreeMixConfig.CODEC);
    }

    @Override
    public boolean generate(FeatureContext<TreeMixConfig> context) {
        long start = System.nanoTime();
        try {
            return placeMix(context);
        } finally {
            TreePlacer.time(TreeFeatures.statsKey(context), System.nanoTime() - start);
        }
    }

    private boolean placeMix(FeatureContext<TreeMixConfig> context) {
        TreeMixConfig cfg = context.getConfig();
        if (cfg.entries().isEmpty()) return false;
        TreePlacer.Ctx c = TreeFeatures.ctx(context);
        Random random = context.getRandom();
        BlockPos origin = context.getOrigin();
        String key = TreeFeatures.statsKey(context);
        byte layer = TreeFeatures.layer(cfg.layer());

        Optional<RegistryKey<Biome>> firstBiome = context.getWorld().getBiome(origin).getKey();
        int chunkX = origin.getX() & ~15, chunkZ = origin.getZ() & ~15;

        for (int attempt = 0; attempt < Math.max(1, cfg.attempts()); attempt++) {
            int x = attempt == 0 ? origin.getX() : chunkX + random.nextInt(16);
            int z = attempt == 0 ? origin.getZ() : chunkZ + random.nextInt(16);
            TreePlacer.count(key + " tries");

            RegistryEntry<Biome> biome = context.getWorld().getBiome(new BlockPos(x, origin.getY(), z));
            if (cfg.hosts().isPresent() ? !hostOk(biome, cfg.hosts().get())
                    : attempt > 0 && !biome.getKey().equals(firstBiome)) {
                TreePlacer.count(key + " refused BIOME");
                continue;
            }

            TreeMixConfig.Entry entry = pick(cfg.entries(), random);
            if (!(entry.preset().value().config() instanceof TreeObjectConfig preset)) continue;
            TreeObjectConfig.ObjectSpec spec = pickObject(preset.objects(), entry.sizes(), random);
            if (spec == null) continue;

            List<String> ground = entry.ground().isEmpty() ? preset.ground() : entry.ground();
            TreePlacer.Rules rules = new TreePlacer.Rules(layer, ground, preset.water(), cfg.spacing(),
                    entry.nearWater(), entry.nearSea(), entry.openSky(), cfg.slope(), cfg.gradient());
            TreePlacer.Fail fail = TreePlacer.place(c, spec, rules, x, z, random.nextInt(4));
            // Too steep for this tree: a smaller one of the same kind, on the
            // same spot. Mountainsides then carry stunted trees and the gentle
            // ground the big ones -- and a small footprint is not bedded deep
            // into the hill the way a wide one would be. Not for fallen logs:
            // they need level ground at any size, and the long footprint scan
            // made stepping them down the costliest thing the layer did.
            while (fail == TreePlacer.Fail.SLOPE && layer != PlacedObjects.LOG) {
                TreeObjectConfig.ObjectSpec smaller = smaller(preset.objects(), spec.size(), random);
                if (smaller == null) break;
                TreePlacer.count(key + " stepped down");
                spec = smaller;
                fail = TreePlacer.place(c, spec, rules, x, z, random.nextInt(4));
            }
            if (fail == null) {
                TreePlacer.count(key + " placed");
                return true;
            }
            TreePlacer.count(key + " refused " + fail + (c.detail == null ? "" : "(" + c.detail + ")"));
            if (fail == TreePlacer.Fail.MISSING) break;
        }
        return false;
    }

    /**
     * A shoreline overlay belongs to its host biomes and the water off them: the
     * shallow sea a mangrove stands in carries an ocean or beach biome, not the
     * forest's. It only runs at all where a host biome is within the chunk's
     * neighbourhood, because vanilla only runs a biome's features there.
     */
    private static boolean hostOk(RegistryEntry<Biome> biome, List<Identifier> hosts) {
        if (biome.isIn(BiomeTags.IS_OCEAN) || biome.isIn(BiomeTags.IS_BEACH) || biome.isIn(BiomeTags.IS_RIVER)) {
            return true;
        }
        return biome.getKey().map(k -> hosts.contains(k.getValue())).orElse(false);
    }

    private static TreeMixConfig.Entry pick(List<TreeMixConfig.Entry> entries, Random random) {
        double total = 0;
        for (TreeMixConfig.Entry e : entries) total += Math.max(0, e.weight());
        double roll = random.nextDouble() * total;
        for (TreeMixConfig.Entry e : entries) {
            roll -= Math.max(0, e.weight());
            if (roll < 0) return e;
        }
        return entries.get(entries.size() - 1);
    }

    /** A size by weight among the sizes the preset has, then one of its objects. */
    static TreeObjectConfig.ObjectSpec pickObject(List<TreeObjectConfig.ObjectSpec> objects,
                                                  Map<String, Float> sizes, Random random) {
        if (objects.isEmpty()) return null;
        String size = null;
        if (!sizes.isEmpty()) {
            double total = 0;
            for (Map.Entry<String, Float> s : sizes.entrySet()) {
                if (has(objects, s.getKey())) total += Math.max(0, s.getValue());
            }
            double roll = random.nextDouble() * total;
            for (Map.Entry<String, Float> s : sizes.entrySet()) {
                if (!has(objects, s.getKey())) continue;
                roll -= Math.max(0, s.getValue());
                if (roll < 0) {
                    size = s.getKey();
                    break;
                }
            }
        }
        int n = 0;
        for (TreeObjectConfig.ObjectSpec o : objects) {
            if (size == null || o.size().equals(size)) n++;
        }
        if (n == 0) return objects.get(random.nextInt(objects.size()));
        int pick = random.nextInt(n);
        for (TreeObjectConfig.ObjectSpec o : objects) {
            if (size == null || o.size().equals(size)) {
                if (pick-- == 0) return o;
            }
        }
        return null;
    }

    private static final List<String> SIZES = List.of("tiny", "small", "medium", "large", "forest");

    /** A random object of the next smaller size the preset has, or null if this is the smallest. */
    static TreeObjectConfig.ObjectSpec smaller(List<TreeObjectConfig.ObjectSpec> objects, String size,
                                               Random random) {
        for (int s = SIZES.indexOf(size) - 1; s >= 0; s--) {
            String want = SIZES.get(s);
            if (!has(objects, want)) continue;
            int n = 0;
            for (TreeObjectConfig.ObjectSpec o : objects) {
                if (o.size().equals(want)) n++;
            }
            int pick = random.nextInt(n);
            for (TreeObjectConfig.ObjectSpec o : objects) {
                if (o.size().equals(want) && pick-- == 0) return o;
            }
        }
        return null;
    }

    private static boolean has(List<TreeObjectConfig.ObjectSpec> objects, String size) {
        for (TreeObjectConfig.ObjectSpec o : objects) {
            if (o.size().equals(size)) return true;
        }
        return false;
    }
}
