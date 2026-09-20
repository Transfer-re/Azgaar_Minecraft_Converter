package com.example.fmg;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.biome.Biome;

/**
 * Offline sanity check for the name+variant biome mapper ({@link FMGBiomeVariantMap}):
 * tallies which vanilla biomes actually got used, confirms the guaranteed rare
 * biomes (pale garden, cherry grove, mangrove swamp, bamboo jungle) show up
 * somewhere, and counts direct snowy/hot adjacencies (should be ~0, since
 * name-based regions inherit Azgaar's own spatial coherence). Run via
 * {@code /fmg biome report}.
 */
public final class FMGBiomeReport {

    private FMGBiomeReport() {
    }

    private static final Set<String> SNOWY = Set.of(
            "snowy_plains", "snowy_taiga", "ice_spikes", "snowy_slopes", "frozen_peaks",
            "jagged_peaks", "grove", "frozen_ocean", "deep_frozen_ocean", "cold_ocean",
            "deep_cold_ocean", "snowy_beach");
    private static final Set<String> HOT = Set.of(
            "desert", "badlands", "eroded_badlands", "wooded_badlands", "savanna",
            "savanna_plateau", "windswept_savanna", "jungle", "sparse_jungle",
            "bamboo_jungle", "warm_ocean");

    public record Report(
            int cellsChecked,
            Map<String, Integer> counts,
            Map<RegistryKey<Biome>, Integer> guaranteedFound,
            int snowyHotAdjacencies
    ) {
    }

    public static Report run(FMGMapData map, RegistryEntryLookup<Biome> biomeLookup, String fmgExport,
                             com.example.profile.FmgContentProfile profile, int step) {
        FMGBiomeVariantMap variants = FMGBiomeVariantMap.get(fmgExport, map, profile);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<RegistryKey<Biome>, Integer> guaranteed = new LinkedHashMap<>();
        for (String rare : profile.biomes.guaranteedRares.values()) {
            guaranteed.putIfAbsent(profile.biomeKey(rare), 0);
        }

        List<FMGCell> cells = map.getCells();
        if (cells == null) {
            return new Report(0, counts, guaranteed, 0);
        }

        int checked = 0;
        int adjacencies = 0;
        int i = 0;
        for (FMGCell cell : cells) {
            if ((i++ % Math.max(1, step)) != 0) continue;
            RegistryKey<Biome> key = variants.keyFor(cell);
            if (key == null) continue;
            checked++;
            String path = key.getValue().getPath();
            counts.merge(path, 1, Integer::sum);
            if (guaranteed.containsKey(key)) {
                guaranteed.merge(key, 1, Integer::sum);
            }
            if (SNOWY.contains(path) && cell.getC() != null) {
                for (int nid : cell.getC()) {
                    FMGCell nc = map.getCell(nid);
                    if (nc == null) continue;
                    RegistryKey<Biome> nk = variants.keyFor(nc);
                    if (nk != null && HOT.contains(nk.getValue().getPath())) {
                        adjacencies++;
                    }
                }
            }
        }
        return new Report(checked, counts, guaranteed, adjacencies);
    }
}
