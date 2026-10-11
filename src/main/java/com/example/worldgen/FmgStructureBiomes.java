package com.example.worldgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.profile.FmgContentProfile;
import com.example.profile.FmgProfiles;

import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;

/**
 * Lets structures start in FMG's own biomes as if they were vanilla ones.
 *
 * <p>A structure names the biomes it may start in: a tag such as
 * {@code #minecraft:has_structure/pillager_outpost}, a mod's own tag, or plain
 * biome ids. FMG's custom biomes (the Conquest set) are in none of those, so no
 * vanilla or modded structure would ever start in them. Each content profile
 * lists, per custom biome, the vanilla biomes it stands in for
 * ({@code biomes.structureStandIns}), and {@code StructureValidBiomesMixin}
 * widens every structure's biome list with the custom biomes whose stand-ins
 * are on it. Structure-set filtering, structure starts, {@code /locate} and
 * explorer maps all read that one list, so a structure mod that targets
 * vanilla biomes needs nothing from us.
 *
 * <p>Not done with biome tags on purpose: putting the custom biomes into
 * {@code #minecraft:is_forest} and friends would miss every mod that lists
 * biome ids or its own tags, and would also hand them to mods that add
 * features or mobs by tag, which is not what this is for.
 */
public final class FmgStructureBiomes {

    private static final Logger LOGGER = LoggerFactory.getLogger("FmgStructureBiomes");

    /** The running server's table; replaced, never mutated, so its cache dies with it. */
    private static volatile Index index = Index.EMPTY;

    private FmgStructureBiomes() {
    }

    /**
     * Builds the stand-in table from every loaded profile against this server's
     * biome registry. Runs on server start, before any world builds its
     * structure placement, and again after a datapack reload (tags may have
     * changed what a structure's own list holds).
     */
    public static void capture(DynamicRegistryManager registries) {
        Registry<Biome> biomes = registries.get(RegistryKeys.BIOME);
        Map<RegistryEntry<Biome>, Set<RegistryEntry<Biome>>> hosts = new LinkedHashMap<>();
        for (FmgContentProfile profile : FmgProfiles.loaded()) {
            Map<String, List<String>> standIns = profile.biomes.structureStandIns;
            if (standIns == null) {
                continue;
            }
            for (Map.Entry<String, List<String>> e : standIns.entrySet()) {
                Optional<RegistryEntry.Reference<Biome>> host = lookup(biomes, e.getKey());
                if (host.isEmpty() || e.getValue() == null) {
                    // the profile's datapack is not installed; nothing can generate it
                    continue;
                }
                for (String id : e.getValue()) {
                    Optional<RegistryEntry.Reference<Biome>> standIn = lookup(biomes, id);
                    if (standIn.isEmpty()) {
                        LOGGER.warn("profile '{}': stand-in biome '{}' for {} does not exist",
                                profile.id, id, e.getKey());
                    } else if (standIn.get() != host.get()) {
                        hosts.computeIfAbsent(standIn.get(), k -> new LinkedHashSet<>()).add(host.get());
                    }
                }
            }
        }
        Map<RegistryEntry<Biome>, List<RegistryEntry<Biome>>> frozen = new LinkedHashMap<>();
        hosts.forEach((standIn, set) -> frozen.put(standIn, List.copyOf(set)));
        index = frozen.isEmpty() ? Index.EMPTY : new Index(frozen);
        if (!frozen.isEmpty()) {
            LOGGER.info("structures can start in FMG biomes through {} vanilla stand-ins", frozen.size());
        }
    }

    public static void clear() {
        index = Index.EMPTY;
    }

    /**
     * {@code biomes} plus every FMG biome standing in for one of them. Returns
     * {@code biomes} itself when nothing is added. Called on every structure
     * start attempt, so the answer is cached per list.
     */
    public static RegistryEntryList<Biome> widen(RegistryEntryList<Biome> biomes) {
        Index idx = index;
        if (idx == Index.EMPTY || biomes == null) {
            return biomes;
        }
        RegistryEntryList<Biome> widened = idx.widened.get(biomes);
        if (widened == null) {
            widened = idx.widened.computeIfAbsent(biomes, idx::widen);
        }
        return widened;
    }

    private static Optional<RegistryEntry.Reference<Biome>> lookup(Registry<Biome> biomes, String id) {
        Identifier parsed = id == null ? null : Identifier.tryParse(id);
        return parsed == null ? Optional.empty() : biomes.getEntry(RegistryKey.of(RegistryKeys.BIOME, parsed));
    }

    private static final class Index {
        static final Index EMPTY = new Index(Map.of());

        /** Vanilla stand-in -> the FMG biomes that host its structures. */
        final Map<RegistryEntry<Biome>, List<RegistryEntry<Biome>>> hostsByStandIn;
        /** Keyed by the structure's own list (identity: tag lists are shared, not copied). */
        final Map<RegistryEntryList<Biome>, RegistryEntryList<Biome>> widened = new ConcurrentHashMap<>();

        Index(Map<RegistryEntry<Biome>, List<RegistryEntry<Biome>>> hostsByStandIn) {
            this.hostsByStandIn = hostsByStandIn;
        }

        RegistryEntryList<Biome> widen(RegistryEntryList<Biome> biomes) {
            List<RegistryEntry<Biome>> extra = new ArrayList<>();
            for (RegistryEntry<Biome> biome : biomes) {
                for (RegistryEntry<Biome> host : hostsByStandIn.getOrDefault(biome, List.of())) {
                    if (!biomes.contains(host) && !extra.contains(host)) {
                        extra.add(host);
                    }
                }
            }
            if (extra.isEmpty()) {
                return biomes;
            }
            List<RegistryEntry<Biome>> all = new ArrayList<>(biomes.size() + extra.size());
            biomes.forEach(all::add);
            all.addAll(extra);
            return RegistryEntryList.of(all);
        }
    }
}
