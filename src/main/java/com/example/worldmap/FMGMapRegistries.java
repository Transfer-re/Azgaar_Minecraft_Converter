package com.example.worldmap;

import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;

import com.example.FantasyMapGenerator;

/**
 * Registry accessors for FMG image-based map definitions.
 */
public final class FMGMapRegistries {

        public static final RegistryKey<Registry<MapInfo>> MAP_INFO =
            RegistryKey.ofRegistry(Identifier.of(FantasyMapGenerator.MOD_ID, "map_info"));

    private FMGMapRegistries() {
    }
}
