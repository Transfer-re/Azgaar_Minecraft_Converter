package com.example.fmg;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.profile.FmgContentProfile;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

/**
 * Precomputed, per-cell vanilla biome assignment driven primarily by Azgaar's
 * own biome name -- not a MultiNoise climate remap. Azgaar's biome map is
 * already spatially coherent (drawn from a smooth temperature/precipitation
 * field), so trusting the name directly buys natural transitions for free;
 * the job here is only to add variety and richness on top without
 * reintroducing per-cell noise scramble:
 *
 * <ul>
 *   <li>Contiguous same-name regions (flood-filled over Azgaar's own cell
 *       graph) each pick ONE variant from the active {@link FmgContentProfile},
 *       hashed from the map seed + region -- so a forest patch reads as one
 *       coherent biome, not a per-cell scramble.</li>
 *   <li>Exclusive-material biomes (pale garden, cherry grove, mangrove swamp,
 *       bamboo jungle) are force-placed on the biggest eligible region when no
 *       region already rolled one, so they're guaranteed present rather than
 *       relying on a weighted die that could roll zero on a small map.</li>
 *   <li>Mountain/highlands/glacier are elevation-ordered per region instead of
 *       weighted -- the name alone doesn't carry the elevation detail that
 *       separates snowy_slopes from jagged_peaks.</li>
 *   <li>Marine cells get temperature-banded ocean variants (frozen -> cold ->
 *       ocean -> lukewarm -> warm, deep variants by depth) picked by each
 *       cell's temperature *rank* among all marine cells on this map (not an
 *       absolute degree threshold -- Azgaar's raw range varies wildly by map),
 *       smoothed over marine neighbours so a single outlier can't speckle, and
 *       biased warm so most maps still get warm_ocean pockets for coral reefs
 *       even when the whole map reads fairly mild.</li>
 * </ul>
 *
 * Built once per FMG export and cached (see {@link #get}); {@link #writeTo}/
 * {@link #readFrom} let {@code FmgBake} persist it so a saved world skips the
 * rebuild.
 */
public final class FMGBiomeVariantMap {

    private static final Logger LOGGER = LoggerFactory.getLogger("FMGBiomeVariantMap");
    private static final Map<String, FMGBiomeVariantMap> CACHE = new ConcurrentHashMap<>();

    /** FMG height is roughly 0..100; land starts here, "deep" ocean is below the second threshold. */
    private static final double SEA_H = 19.0;
    private static final double DEEP_H = 12.0;

    private final RegistryKey<Biome>[] byCellId;

    private FMGBiomeVariantMap(RegistryKey<Biome>[] byCellId) {
        this.byCellId = byCellId;
    }

    /** Resolved vanilla biome for this cell, or null if the cell fell outside the built array (safety net only). */
    public RegistryKey<Biome> keyFor(FMGCell cell) {
        if (cell == null) return null;
        int i = cell.getI();
        return i >= 0 && i < byCellId.length ? byCellId[i] : null;
    }

    /** Cache key: the same map under a different content profile is a different assignment. */
    private static String cacheKey(String fmgExport, FmgContentProfile profile) {
        return fmgExport + "|" + (profile == null ? FmgContentProfile.VANILLA_ID : profile.id);
    }

    public static FMGBiomeVariantMap get(String fmgExport, FMGMapData map, FmgContentProfile profile) {
        String key = cacheKey(fmgExport, profile);
        FMGBiomeVariantMap m = CACHE.get(key);
        if (m != null) return m;
        synchronized (CACHE) {
            m = CACHE.get(key);
            if (m != null) return m;
            m = new FMGBiomeVariantMap(build(map, deriveSeed(map, fmgExport), profile));
            CACHE.put(key, m);
            return m;
        }
    }

    private static long deriveSeed(FMGMapData map, String fmgExport) {
        String s = map != null && map.getInfo() != null ? map.getInfo().getSeed() : null;
        if (s != null && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return s.hashCode() * 0x9E3779B97F4A7C15L;
            }
        }
        return fmgExport == null ? 1L : fmgExport.hashCode() * 0x9E3779B97F4A7C15L;
    }

    public static void invalidate() {
        CACHE.clear();
    }

    // ---- bake persistence ----

    /** Install a pre-built (baked) assignment so {@link #get} skips the rebuild. */
    public static void seed(String fmgExport, FmgContentProfile profile, FMGBiomeVariantMap variants) {
        CACHE.putIfAbsent(cacheKey(fmgExport, profile), variants);
    }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(byCellId.length);
        for (RegistryKey<Biome> key : byCellId) {
            out.writeUTF(key == null ? "" : key.getValue().toString());
        }
    }

    public static FMGBiomeVariantMap readFrom(DataInputStream in) throws IOException {
        int n = in.readInt();
        @SuppressWarnings("unchecked")
        RegistryKey<Biome>[] arr = new RegistryKey[n];
        for (int i = 0; i < n; i++) {
            String s = in.readUTF();
            arr[i] = s.isEmpty() ? null : RegistryKey.of(RegistryKeys.BIOME, Identifier.of(s));
        }
        return new FMGBiomeVariantMap(arr);
    }

    // ---- build ----

    private static final class RegionAssign {
        final String name;
        final List<Integer> cells;
        RegistryKey<Biome> variant;

        RegionAssign(String name, List<Integer> cells, RegistryKey<Biome> variant) {
            this.name = name;
            this.cells = cells;
            this.variant = variant;
        }
    }

    @SuppressWarnings("unchecked")
    private static RegistryKey<Biome>[] build(FMGMapData map, long seed, FmgContentProfile profile) {
        List<FMGCell> cells = map.getCells();
        if (cells == null || cells.isEmpty()) {
            return new RegistryKey[0];
        }

        int maxId = 0;
        for (FMGCell c : cells) maxId = Math.max(maxId, c.getI());
        RegistryKey<Biome>[] out = new RegistryKey[maxId + 1];
        boolean[] visited = new boolean[maxId + 1];

        List<RegionAssign> regions = new ArrayList<>();
        for (FMGCell c : cells) {
            int id = c.getI();
            if (id < 0 || id > maxId || visited[id]) continue;
            String name = normalizedNameOf(map, c);
            if (isMarine(name, c)) continue;   // handled in the ocean pass below
            List<Integer> region = floodFill(map, c, name, visited, maxId);
            RegistryKey<Biome> variant = pickVariantForRegion(map, name, region, seed, profile);
            regions.add(new RegionAssign(name, region, variant));
        }

        applyGuaranteedRares(regions, profile);

        for (RegionAssign r : regions) {
            for (int id : r.cells) out[id] = r.variant;
        }

        assignOceanBands(map, cells, maxId, out, profile);

        LOGGER.info("FMGBiomeVariantMap built: {} cells, {} regions, seed {}", cells.size(), regions.size(), seed);
        return out;
    }

    /**
     * Ocean variant is picked by the cell's temperature *rank* among all marine
     * cells on this map, not an absolute degree threshold -- Azgaar's raw
     * per-cell temperature range varies wildly by map (the same lesson the old
     * climate system learned the hard way: see the superseded
     * biome-climate-mapping notes), so a fixed -6/0/10/18 split can land every
     * single ocean cell in one band on a map whose whole range happens to sit
     * inside it. Ranking stretches whatever range this map actually has across
     * the full vanilla ocean palette, and is deliberately biased warm (only the
     * coldest quarter of ocean cells go frozen/cold) since most Azgaar maps
     * read fairly mild at sea level and coral reefs need warm_ocean somewhere.
     */
    private static void assignOceanBands(FMGMapData map, List<FMGCell> cells, int maxId,
                                         RegistryKey<Biome>[] out, FmgContentProfile profile) {
        Map<Integer, Double> marineTempById = new HashMap<>();
        List<Double> marineTemps = new ArrayList<>();
        for (FMGCell c : cells) {
            int id = c.getI();
            if (id < 0 || id > maxId) continue;
            if (isMarine(normalizedNameOf(map, c), c)) {
                double t = smoothedMarineTemp(map, c);
                marineTempById.put(id, t);
                marineTemps.add(t);
            }
        }
        double[] sortedMarineT = toSortedArray(marineTemps);

        for (Map.Entry<Integer, Double> e : marineTempById.entrySet()) {
            int id = e.getKey();
            double pct = percentile(e.getValue(), sortedMarineT);
            boolean deep = map.getCell(id).getH() < DEEP_H;
            out[id] = oceanBandFor(pct, deep, profile);
        }
    }

    private static RegistryKey<Biome> oceanBandFor(double pct, boolean deep, FmgContentProfile profile) {
        FmgContentProfile.OceanBands o = profile.biomes.ocean;
        if (pct < o.frozenBelow) return profile.biomeKey(deep ? o.deepFrozen : o.frozen);
        if (pct < o.coldBelow) return profile.biomeKey(deep ? o.deepCold : o.cold);
        if (pct < o.temperateBelow) return profile.biomeKey(deep ? o.deepTemperate : o.temperate);
        if (pct < o.lukewarmBelow) return profile.biomeKey(deep ? o.deepLukewarm : o.lukewarm);
        return profile.biomeKey(o.warm);   // vanilla has no deep-warm-ocean variant
    }

    private static boolean isMarine(String name, FMGCell c) {
        return "marine".equals(name) || "ocean".equals(name) || "coast".equals(name) || c.getH() < SEA_H;
    }

    private static List<Integer> floodFill(FMGMapData map, FMGCell start, String name, boolean[] visited, int maxId) {
        List<Integer> region = new ArrayList<>();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        visited[start.getI()] = true;
        stack.push(start.getI());
        while (!stack.isEmpty()) {
            int id = stack.pop();
            region.add(id);
            FMGCell c = map.getCell(id);
            if (c == null || c.getC() == null) continue;
            for (int nid : c.getC()) {
                if (nid < 0 || nid > maxId || visited[nid]) continue;
                FMGCell nc = map.getCell(nid);
                if (nc == null) continue;
                String nName = normalizedNameOf(map, nc);
                if (isMarine(nName, nc) || !nName.equals(name)) continue;
                visited[nid] = true;
                stack.push(nid);
            }
        }
        return region;
    }

    private static RegistryKey<Biome> pickVariantForRegion(FMGMapData map, String name, List<Integer> region,
                                                           long seed, FmgContentProfile profile) {
        if (profile.isWetland(name)) {
            double avgT = region.stream().mapToDouble(id -> map.getCell(id).getT()).average().orElse(8.0);
            FmgContentProfile.WetlandRule rule = profile.biomes.wetland;
            double span = Math.max(1e-6, rule.warmTo - rule.warmFrom);
            double warmBias = clamp((avgT - rule.warmFrom) / span, 0.0, 1.0);
            double roll = unitRoll(regionHash(region, seed));
            return profile.biomeKey(roll < warmBias ? rule.warm : rule.cool);
        }
        List<FmgContentProfile.Rung> ladder = profile.elevationLadder(name);
        if (ladder != null && !ladder.isEmpty()) {
            double avgHeight = region.stream().mapToInt(id -> map.getCell(id).getH()).average().orElse(50.0);
            for (FmgContentProfile.Rung rung : ladder) {
                if (avgHeight < rung.below) return profile.biomeKey(rung.biome);
            }
            return profile.biomeKey(ladder.get(ladder.size() - 1).biome);
        }
        List<FmgContentProfile.Weighted> pool = profile.poolFor(name);
        if (pool == null || pool.isEmpty()) {
            RegistryKey<Biome> legacy = FMGBiomeMapper.legacyKeyFor(name);
            return legacy != null ? legacy : profile.biomeKey(profile.biomes.fallback);
        }
        FmgContentProfile.Weighted picked = FmgContentProfile.pick(pool, regionHash(region, seed));
        return profile.biomeKey(picked == null ? profile.biomes.fallback : picked.biome);
    }

    /** Force-assign a guaranteed rare onto the biggest eligible region if no region of that name already rolled it. */
    private static void applyGuaranteedRares(List<RegionAssign> regions, FmgContentProfile profile) {
        for (Map.Entry<String, String> entry : profile.biomes.guaranteedRares.entrySet()) {
            String name = entry.getKey();
            RegistryKey<Biome> rare = profile.biomeKey(entry.getValue());
            boolean present = false;
            RegionAssign biggest = null;
            for (RegionAssign r : regions) {
                if (!r.name.equals(name)) continue;
                if (r.variant.equals(rare)) {
                    present = true;
                    break;
                }
                if (biggest == null || r.cells.size() > biggest.cells.size()) biggest = r;
            }
            if (!present && biggest != null) {
                biggest.variant = rare;
            }
        }
    }

    private static double smoothedMarineTemp(FMGMapData map, FMGCell c) {
        double sum = c.getT();
        int n = 1;
        if (c.getC() != null) {
            for (int nid : c.getC()) {
                FMGCell nc = map.getCell(nid);
                if (nc != null && isMarine(normalizedNameOf(map, nc), nc)) {
                    sum += nc.getT();
                    n++;
                }
            }
        }
        return sum / n;
    }

    /** Rank of {@code x} within the sorted sample, 0 (coldest) .. 1 (warmest). */
    private static double percentile(double x, double[] sorted) {
        if (sorted == null || sorted.length <= 1) {
            return 0.5;
        }
        int idx = Arrays.binarySearch(sorted, x);
        if (idx < 0) idx = -idx - 1;
        return clamp(idx / (double) (sorted.length - 1), 0.0, 1.0);
    }

    private static double[] toSortedArray(List<Double> values) {
        double[] a = new double[values.size()];
        for (int i = 0; i < a.length; i++) a[i] = values.get(i);
        Arrays.sort(a);
        return a;
    }

    private static String normalizedNameOf(FMGMapData map, FMGCell c) {
        FMGBiome b = map.getBiome(c.getBiome());
        return b != null ? FMGBiomeMapper.normalize(b.getName()) : "";
    }

    // ---- deterministic hashing ----

    private static double unitRoll(long hash) {
        return (hash >>> 11) / (double) (1L << 53);
    }

    private static long regionHash(List<Integer> region, long seed) {
        int anchor = Integer.MAX_VALUE;
        for (int id : region) anchor = Math.min(anchor, id);
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h = (h ^ anchor) * 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
