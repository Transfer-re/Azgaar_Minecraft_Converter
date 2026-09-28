package com.example.surface;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;

/**
 * A set of ground palettes (e.g. {@code conquest}): which layer blocks make the
 * surface of each biome, in which sub-biome patches, and how that changes with
 * slope, shore distance, altitude and water depth.
 *
 * <p>Loaded from {@code fantasymapgenerator/surface/<id>.json} in the mod jar,
 * which {@code tools/fmgtrees/make_datapack.py} writes from
 * {@code tools/fmgtrees/palettes.py}. A file of the same name in
 * {@code config/fantasymapgenerator/surface/} overrides it, for tuning without a
 * rebuild. The evaluation rules are in {@code docs/conquest/palettes.md}; the
 * review page's JavaScript is the reference implementation.
 *
 * <p>Minecraft-free: block ids stay strings here, and {@link #decide} works on
 * plain numbers, so the harness ({@link SurfaceCli}) runs it without the game.
 */
public final class SurfaceSet {

    public static final int VAR_SUB = -1;
    public static final int VAR_SLOPE = 0, VAR_COAST = 1, VAR_BANK = 2, VAR_ALT = 3, VAR_DEPTH = 4;
    private static final String[] VAR_NAMES = {"slope", "coast", "bank", "alt", "depth"};

    private static final Gson GSON = new Gson();
    private static final Map<String, SurfaceSet> CACHE = new ConcurrentHashMap<>();

    // ---- JSON model ------------------------------------------------------------

    static final class RawMaterial { String layer, full, group; }
    static final class RawTints { String grass, foliage, water; }
    static final class RawSub { String id, name; double weight; LinkedHashMap<String, Double> mix; }
    static final class RawBand { Double from, to; LinkedHashMap<String, Double> mix; }
    static final class RawRamp { String var; double feather; List<RawBand> bands; }
    static final class RawPalette {
        RawTints tints; String soil, stone; int soilDepth; double clump, patch;
        List<RawSub> sub; List<RawRamp> ramps; RawRamp bed;
    }
    static final class RawFile {
        String id, fallback;
        LinkedHashMap<String, RawMaterial> materials;
        LinkedHashMap<String, RawPalette> palettes;
        LinkedHashMap<String, String> biomes;
    }

    // ---- compiled ------------------------------------------------------------

    /** Material names, their layer / full block ids and group, by index. */
    public final String[] materialNames;
    public final String[] layerIds;
    public final String[] fullIds;
    public final String[] groups;
    /** True for grass, floor and moss: skins that sit on {@link Palette#soil}, not on themselves. */
    public final boolean[] skin;

    public final String id;
    private final Map<String, Palette> palettes = new LinkedHashMap<>();
    private final Map<String, Palette> byBiome = new HashMap<>();
    private final Palette fallback;

    public static final class Mix {
        final int[] materials;
        final double[] weights;
        final double total;

        Mix(int[] materials, double[] weights) {
            this.materials = materials;
            this.weights = weights;
            double t = 0;
            for (double w : weights) t += w;
            this.total = t;
        }
    }

    static final class Ramp {
        final int var;
        final double feather;
        final boolean rising;
        final double[] edges;
        final Mix[] mixes;

        Ramp(int var, double feather, boolean rising, double[] edges, Mix[] mixes) {
            this.var = var;
            this.feather = feather;
            this.rising = rising;
            this.edges = edges;
            this.mixes = mixes;
        }

        /** Band index for a value, or -1. Rising: last edge <= v. Distance: first edge > v. */
        int band(double v) {
            if (rising) {
                int hit = -1;
                for (int i = 0; i < edges.length; i++) if (v >= edges[i]) hit = i;
                return hit;
            }
            for (int i = 0; i < edges.length; i++) if (v < edges[i]) return i;
            return -1;
        }
    }

    public static final class Palette {
        public final String id;
        public final String soil;
        public final int stone;
        public final int soilDepth;
        public final int[] tints;          // grass, foliage, water as 0xRRGGBB
        final double clump, patch;
        final int seed;
        final String[] subNames;
        final double[] subWeights;
        final double subTotal;
        final Mix[] subMixes;
        final Ramp[] ramps;
        final Ramp bed;

        Palette(String id, String soil, int stone, int soilDepth, int[] tints, double clump, double patch,
                String[] subNames, double[] subWeights, Mix[] subMixes, Ramp[] ramps, Ramp bed) {
            this.id = id;
            this.soil = soil;
            this.stone = stone;
            this.soilDepth = soilDepth;
            this.tints = tints;
            this.clump = clump;
            this.patch = patch;
            this.seed = SurfaceNoise.seedOf(id);
            this.subNames = subNames;
            this.subWeights = subWeights;
            double t = 0;
            for (double w : subWeights) t += w;
            this.subTotal = t;
            this.subMixes = subMixes;
            this.ramps = ramps;
            this.bed = bed;
        }

        public String subName(int i) {
            return i >= 0 && i < subNames.length ? subNames[i] : "?";
        }
    }

    /** What {@link #decide} chose for one column, reused to avoid allocation. */
    public static final class Choice {
        public int material;
        public int sub;
        /** Which variable's ramp decided ({@code VAR_*}), or {@link #VAR_SUB}. */
        public int var;
        public int band;

        public String reason(Palette p) {
            return var == VAR_SUB ? "sub-biome " + p.subName(sub) : VAR_NAMES[var] + " band " + (band + 1);
        }
    }

    /** Terrain variables for one column; see docs/conquest/palettes.md for units. */
    public static final class Vars {
        public double slope;
        public double coast = Double.POSITIVE_INFINITY;
        public double bank = Double.POSITIVE_INFINITY;
        public double alt;
        public double depth;
        public boolean underwater;

        double get(int var) {
            return switch (var) {
                case VAR_SLOPE -> slope;
                case VAR_COAST -> coast;
                case VAR_BANK -> bank;
                case VAR_ALT -> alt;
                default -> depth;
            };
        }
    }

    // ---- loading ---------------------------------------------------------------

    /** The set with this id, from the config override or the mod jar; null if neither exists. */
    public static SurfaceSet get(String id, Path configDir) {
        if (id == null || id.isBlank()) return null;
        return CACHE.computeIfAbsent(id, k -> load(k, configDir));
    }

    public static void invalidate() {
        CACHE.clear();
    }

    private static SurfaceSet load(String id, Path configDir) {
        try {
            if (configDir != null) {
                Path override = configDir.resolve("fantasymapgenerator/surface/" + id + ".json");
                if (Files.exists(override)) {
                    try (Reader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                        return new SurfaceSet(GSON.fromJson(r, RawFile.class));
                    }
                }
            }
            try (InputStream in = SurfaceSet.class.getResourceAsStream("/fantasymapgenerator/surface/" + id + ".json")) {
                if (in == null) return null;
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    return new SurfaceSet(GSON.fromJson(r, RawFile.class));
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("could not load surface palettes '" + id + "'", ex);
        }
    }

    /** Parses a palette file directly (the harness uses this). */
    public static SurfaceSet read(Reader reader) {
        return new SurfaceSet(GSON.fromJson(reader, RawFile.class));
    }

    private SurfaceSet(RawFile raw) {
        this.id = raw.id;
        int n = raw.materials.size();
        materialNames = new String[n];
        layerIds = new String[n];
        fullIds = new String[n];
        groups = new String[n];
        skin = new boolean[n];
        Map<String, Integer> index = new HashMap<>();
        int i = 0;
        for (Map.Entry<String, RawMaterial> e : raw.materials.entrySet()) {
            materialNames[i] = e.getKey();
            layerIds[i] = e.getValue().layer;
            fullIds[i] = e.getValue().full;
            groups[i] = e.getValue().group;
            skin[i] = "grass".equals(groups[i]) || "floor".equals(groups[i]) || "moss".equals(groups[i]);
            index.put(e.getKey(), i++);
        }
        for (Map.Entry<String, RawPalette> e : raw.palettes.entrySet()) {
            palettes.put(e.getKey(), compile(e.getKey(), e.getValue(), index));
        }
        for (Map.Entry<String, String> e : raw.biomes.entrySet()) {
            Palette p = palettes.get(e.getValue());
            if (p != null) byBiome.put(e.getKey(), p);
        }
        Palette fb = palettes.get(raw.fallback);
        this.fallback = fb != null ? fb : palettes.values().iterator().next();
    }

    private static Palette compile(String id, RawPalette raw, Map<String, Integer> index) {
        int subs = raw.sub.size();
        String[] names = new String[subs];
        double[] weights = new double[subs];
        Mix[] mixes = new Mix[subs];
        for (int i = 0; i < subs; i++) {
            names[i] = raw.sub.get(i).name;
            weights[i] = raw.sub.get(i).weight;
            mixes[i] = mix(raw.sub.get(i).mix, index, id);
        }
        List<Ramp> ramps = new ArrayList<>();
        for (RawRamp r : raw.ramps) ramps.add(ramp(r, index, id));
        int[] tints = {rgb(raw.tints.grass), rgb(raw.tints.foliage), rgb(raw.tints.water)};
        Integer stone = index.get(raw.stone);
        if (stone == null) throw new IllegalArgumentException(id + ": stone '" + raw.stone + "' is not a material");
        return new Palette(id, raw.soil, stone, raw.soilDepth, tints, raw.clump, raw.patch,
                names, weights, mixes, ramps.toArray(new Ramp[0]), ramp(raw.bed, index, id));
    }

    private static Ramp ramp(RawRamp r, Map<String, Integer> index, String id) {
        int var = switch (r.var) {
            case "slope" -> VAR_SLOPE;
            case "coast" -> VAR_COAST;
            case "bank" -> VAR_BANK;
            case "alt" -> VAR_ALT;
            case "depth" -> VAR_DEPTH;
            default -> throw new IllegalArgumentException(id + ": unknown ramp variable " + r.var);
        };
        boolean rising = r.bands.get(0).from != null;
        double[] edges = new double[r.bands.size()];
        Mix[] mixes = new Mix[r.bands.size()];
        for (int i = 0; i < edges.length; i++) {
            RawBand b = r.bands.get(i);
            edges[i] = rising ? b.from : b.to;
            mixes[i] = mix(b.mix, index, id);
        }
        return new Ramp(var, r.feather, rising, edges, mixes);
    }

    private static Mix mix(LinkedHashMap<String, Double> raw, Map<String, Integer> index, String id) {
        int[] mats = new int[raw.size()];
        double[] w = new double[raw.size()];
        int i = 0;
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            Integer m = index.get(e.getKey());
            if (m == null) throw new IllegalArgumentException(id + ": unknown material " + e.getKey());
            mats[i] = m;
            w[i++] = e.getValue();
        }
        return new Mix(mats, w);
    }

    private static int rgb(String hex) {
        return hex == null ? 0x7fa34a : Integer.parseInt(hex.substring(hex.startsWith("#") ? 1 : 0), 16);
    }

    // ---- queries ---------------------------------------------------------------

    public Palette palette(String id) {
        return palettes.get(id);
    }

    /** Palette for a biome id, or the fallback palette. */
    public Palette forBiome(String biomeId) {
        Palette p = biomeId == null ? null : byBiome.get(biomeId);
        return p != null ? p : fallback;
    }

    public boolean hasBiome(String biomeId) {
        return byBiome.containsKey(biomeId);
    }

    public int materialCount() {
        return materialNames.length;
    }

    /**
     * Choose the material for one column (spec steps 2-5 in palettes.md).
     *
     * @param seed per-world seed, so two worlds from the same map can differ
     */
    public void decide(Palette p, int x, int z, Vars v, int seed, Choice out) {
        int s = p.seed + seed * 101;
        out.sub = SurfaceNoise.cellPick(x, z, p.patch, s, p.subWeights, p.subTotal);
        out.var = VAR_SUB;
        out.band = -1;
        Mix mix;
        if (v.underwater) {
            double f = (SurfaceNoise.vnoise(x / 3.1, z / 3.1, p.seed + 77) * 2 - 1) * p.bed.feather;
            int b = p.bed.band(v.depth + f);
            if (b < 0) b = p.bed.edges.length - 1;
            mix = p.bed.mixes[b];
            out.var = VAR_DEPTH;
            out.band = b;
        } else {
            mix = p.subMixes[out.sub];
            for (int ri = 0; ri < p.ramps.length; ri++) {
                Ramp r = p.ramps[ri];
                double f = (SurfaceNoise.vnoise(x / 3.1, z / 3.1, p.seed + 40 + ri) * 2 - 1) * r.feather;
                int b = r.band(v.get(r.var) + f);
                if (b >= 0) {
                    mix = r.mixes[b];
                    out.var = r.var;
                    out.band = b;
                }
            }
        }
        int k = SurfaceNoise.cellPick(x, z, p.clump, p.seed + seed * 131 + 9, mix.weights, mix.total);
        if (SurfaceNoise.u01(x, z, p.seed + 5) < 0.15) {
            k = SurfaceNoise.weighted(SurfaceNoise.u01(x, z, p.seed + 6), mix.weights, mix.total);
        }
        out.material = mix.materials[k];
    }

    /**
     * Blocks of soil (or of the material itself) under the skin: the palette's
     * depth on flat ground, thinning to none at slope 1.5.
     */
    public static int fillDepth(Palette p, double slope) {
        double f = Math.max(0.0, Math.min(1.0, 1.0 - slope / 1.5));
        return (int) Math.round(p.soilDepth * f);
    }

    /** Height in eighths -> {top block y, layers 1..8}. */
    public static int[] layerOf(double surfaceY) {
        int h8 = Math.max(1, (int) Math.round(surfaceY * 8.0));
        int yTop = Math.floorDiv(h8 - 1, 8);
        return new int[] {yTop, h8 - 8 * yTop};
    }
}
