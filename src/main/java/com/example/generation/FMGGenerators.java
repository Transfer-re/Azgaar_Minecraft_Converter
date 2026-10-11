package com.example.generation;

import com.example.FantasyMapGenerator;
import com.example.worldgen.ColorMapBiomeSource;
import com.example.worldgen.BurgVillageStructure;
import com.example.worldgen.ImageMapChunkGenerator;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.neoforged.neoforge.registries.RegisterEvent;

public class FMGGenerators {

        public static final Identifier IMAGE_MAP_GENERATOR_ID =
            Identifier.of(FantasyMapGenerator.MOD_ID, "image_map");
        public static final Identifier COLOR_MAP_BIOME_SOURCE_ID =
            Identifier.of(FantasyMapGenerator.MOD_ID, "color_map");

    /** Called for every registry's {@link RegisterEvent}; each call only fills its own. */
    public static void register(RegisterEvent event) {
        event.register(RegistryKeys.BIOME_SOURCE, helper -> helper.register(
            COLOR_MAP_BIOME_SOURCE_ID,
            ColorMapBiomeSource.CODEC
        ));
        event.register(RegistryKeys.CHUNK_GENERATOR, helper -> {
            FantasyMapGenerator.LOGGER.info("Registering FMG worldgen codecs...");
            helper.register(
                IMAGE_MAP_GENERATOR_ID,
                ImageMapChunkGenerator.CODEC
            );
            FantasyMapGenerator.LOGGER.info(
                "Registered ColorMapBiomeSource ({}) and image-map ChunkGenerator ({})",
                COLOR_MAP_BIOME_SOURCE_ID,
                IMAGE_MAP_GENERATOR_ID
            );
        });

        // Register custom structures.
        event.register(RegistryKeys.STRUCTURE_TYPE, helper -> {
            BurgVillageStructure.TYPE = () -> BurgVillageStructure.CODEC;
            helper.register(BurgVillageStructure.ID, BurgVillageStructure.TYPE);
        });
    }
}
