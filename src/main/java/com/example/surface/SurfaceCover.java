package com.example.surface;

import java.util.List;
import java.util.Map;

/**
 * What stands on a palette's ground: plants (grass, flowers, ferns, bushes,
 * leaf litter), props (rocks, cones, mushrooms) and patches, where a clump of
 * the ground itself turns to another material (pebbles, burnt soil, boulders).
 *
 * <p>Written by {@code tools/fmgtrees/make_datapack.py} from the biome design
 * files' {@code groundCover} and {@code props}; how each block kind is placed is
 * documented there. This class only decides <em>what</em> goes on a column --
 * placing it is the chunk generator's job -- so the game and the harness share
 * it. Minecraft-free.
 *
 * <p>Plants grow thicker and thinner over a dozen blocks or so (glades and
 * thickets), in species clumps a few blocks across, and only sparsely on sand,
 * gravel and rock, never on ice or snow, and less on steep ground. Rocks do the
 * opposite: they gather in fields and favour scree and slopes.
 */
public final class SurfaceCover {

    /** On top of the layer; the block's own {@code layers} (or Conquest's offset) sinks it onto it. */
    public static final int PLANT = 0;
    /** Two blocks tall, {@code half=lower/upper}. */
    public static final int TALL = 1;
    /** Replaces the layer block: a clump with no offset that would float over a part layer. */
    public static final int SUNK = 2;
    /** No offset: only on a full layer, or in place of a nearly empty one. */
    public static final int LOOSE = 3;

    /** A weighted list of blocks with a density. */
    public static final class Scatter {
        public final String[] blocks;
        public final int[] kinds;
        final double[] weights;
        final double total;
        final double density;

        Scatter(String[] blocks, int[] kinds, double[] weights, double density) {
            this.blocks = blocks;
            this.kinds = kinds;
            this.weights = weights;
            double t = 0;
            for (double w : weights) t += w;
            this.total = t;
            this.density = t > 0 ? density : 0;
        }
    }

    public final Scatter plants;
    public final Scatter props;
    /** Patch material indices into the set's materials, and how many eighths each stands proud. */
    public final int[] patchMaterials;
    public final int[] patchRaise;
    private final double[] patchWeights;   // [0] = no patch
    private final double patchTotal;
    private final int seed;

    private SurfaceCover(Scatter plants, Scatter props, int[] patchMaterials, int[] patchRaise,
                         double[] patchWeights, int seed) {
        this.plants = plants;
        this.props = props;
        this.patchMaterials = patchMaterials;
        this.patchRaise = patchRaise;
        this.patchWeights = patchWeights;
        double t = 0;
        for (double w : patchWeights) t += w;
        this.patchTotal = t;
        this.seed = seed;
    }

    // ---- JSON model --------------------------------------------------------------

    static final class RawEntry { String block, kind; double weight; }
    static final class RawPatch { String material; double weight; int raise; }
    static final class Raw {
        double plantDensity, propDensity, patchDensity;
        List<RawEntry> plants, props;
        List<RawPatch> patches;
    }

    static SurfaceCover compile(Raw raw, Map<String, Integer> materials, String paletteId) {
        if (raw == null) return null;
        int n = raw.patches == null ? 0 : raw.patches.size();
        int[] mats = new int[n];
        int[] raise = new int[n];
        double[] w = new double[n + 1];
        double sum = 0;
        for (int i = 0; i < n; i++) sum += raw.patches.get(i).weight;
        double d = sum > 0 ? Math.max(0, Math.min(1, raw.patchDensity)) : 0;
        w[0] = 1 - d;
        for (int i = 0; i < n; i++) {
            RawPatch p = raw.patches.get(i);
            Integer m = materials.get(p.material);
            if (m == null) throw new IllegalArgumentException(paletteId + ": patch material '" + p.material + "' is not a material");
            mats[i] = m;
            raise[i] = p.raise;
            w[i + 1] = d * p.weight / sum;
        }
        return new SurfaceCover(scatter(raw.plants, raw.plantDensity, paletteId),
                scatter(raw.props, raw.propDensity, paletteId), mats, raise, w,
                SurfaceNoise.seedOf(paletteId) ^ 0x3C0FE7);
    }

    private static Scatter scatter(List<RawEntry> raw, double density, String paletteId) {
        int n = raw == null ? 0 : raw.size();
        String[] blocks = new String[n];
        int[] kinds = new int[n];
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            RawEntry e = raw.get(i);
            blocks[i] = e.block;
            w[i] = e.weight;
            kinds[i] = switch (e.kind == null ? "plant" : e.kind) {
                case "plant" -> PLANT;
                case "tall" -> TALL;
                case "sunk" -> SUNK;
                case "loose" -> LOOSE;
                default -> throw new IllegalArgumentException(paletteId + ": unknown cover kind " + e.kind);
            };
        }
        return new Scatter(blocks, kinds, w, density);
    }

    // ---- decisions -----------------------------------------------------------------

    /**
     * Patch at a column: an index into {@link #patchMaterials}, or -1. Patches are
     * warped cells about 4.5 blocks across, so they come as clumps with ragged edges.
     */
    public int patch(int x, int z, int worldSeed) {
        if (patchMaterials.length == 0 || patchWeights[0] >= patchTotal) return -1;
        int s = seed + worldSeed * 163;
        return SurfaceNoise.cellPick(x, z, 4.5, s + 41, patchWeights, patchTotal) - 1;
    }

    /** Eighths a patch column stands above the ground: lumpy, so boulder fields are not flat tables. */
    public int raise(int patch, int x, int z, int worldSeed) {
        int r = patchRaise[patch];
        if (r <= 0) return 0;
        double n = SurfaceNoise.vnoise(x / 2.3, z / 2.3, seed + worldSeed * 163 + 43);
        return Math.max(1, (int) Math.round(r * (0.4 + 1.2 * n)));
    }

    /** Plant on a column of this ground group and slope: an index into {@link #plants}, or -1. */
    public int plant(int x, int z, String group, double slope, int worldSeed) {
        if (plants.density <= 0) return -1;
        double ground = plantGround(group);
        if (ground <= 0) return -1;
        int s = seed + worldSeed * 157;
        double n = 0.65 * SurfaceNoise.vnoise(x / 13.0, z / 13.0, s + 21)
                + 0.35 * SurfaceNoise.vnoise(x / 4.5, z / 4.5, s + 22);
        double thick = 0.25 + 1.5 * n;                       // glades to thickets, about 1 on average
        double steep = Math.max(0.0, 1.0 - slope / 1.4);
        double chance = Math.min(0.95, plants.density * ground * steep * thick);
        if (SurfaceNoise.u01(x, z, s + 23) >= chance) return -1;
        return pick(plants, x, z, 2.6, s + 24, 0.2);
    }

    /** Prop on a column: an index into {@link #props}, or -1. */
    public int prop(int x, int z, String group, double slope, int worldSeed) {
        if (props.density <= 0) return -1;
        double ground = propGround(group);
        if (ground <= 0) return -1;
        int s = seed + worldSeed * 151;
        double n = SurfaceNoise.vnoise(x / 17.0, z / 17.0, s + 31);
        double field = 3.0 * n * n;                          // rock fields, and long stretches with none
        double steep = 1.0 + 0.8 * Math.min(slope, 1.5);
        double chance = Math.min(0.6, props.density * ground * steep * field);
        if (SurfaceNoise.u01(x, z, s + 32) >= chance) return -1;
        return pick(props, x, z, 9.0, s + 34, 0.1);
    }

    /** Species by clump (warped cells of {@code scale}), with a share of loose speckle. */
    private static int pick(Scatter sc, int x, int z, double scale, int s, double speckle) {
        if (SurfaceNoise.u01(x, z, s + 7) < speckle) {
            return SurfaceNoise.weighted(SurfaceNoise.u01(x, z, s + 8), sc.weights, sc.total);
        }
        return SurfaceNoise.cellPick(x, z, scale, s, sc.weights, sc.total);
    }

    /** How readily plants take to a ground group, relative to turf. */
    static double plantGround(String group) {
        if (group == null) return 0.5;
        return switch (group) {
            case "grass", "floor" -> 1.0;
            case "moss", "wet" -> 0.9;
            case "soil" -> 0.6;
            case "gravel" -> 0.35;
            case "sand", "rock" -> 0.3;
            default -> 0.0;                                   // ice and snow
        };
    }

    /** How readily rocks and other props lie on a ground group. */
    static double propGround(String group) {
        if (group == null) return 1.0;
        return switch (group) {
            case "rock" -> 1.6;
            case "gravel" -> 1.4;
            case "grass", "floor", "moss", "soil" -> 1.0;
            case "sand" -> 0.6;
            case "wet" -> 0.4;
            default -> 0.0;
        };
    }
}
