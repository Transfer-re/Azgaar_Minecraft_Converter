package com.example.generation;

import com.example.FantasyMapGenerator;
import com.example.worldgen.ColorMapBiomeSource;
import com.example.worldgen.ImageMapChunkGenerator;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class FMGGenerators {

        public static final Identifier IMAGE_MAP_GENERATOR_ID =
            Identifier.of(FantasyMapGenerator.MOD_ID, "image_map");
        public static final Identifier COLOR_MAP_BIOME_SOURCE_ID =
            Identifier.of(FantasyMapGenerator.MOD_ID, "color_map");

    public static void register() {
        FantasyMapGenerator.LOGGER.info("Registering FMG worldgen codecs...");
        Registry.register(
            Registries.BIOME_SOURCE,
            COLOR_MAP_BIOME_SOURCE_ID,
            ColorMapBiomeSource.CODEC
        );
        Registry.register(
                Registries.CHUNK_GENERATOR,
                IMAGE_MAP_GENERATOR_ID,
                ImageMapChunkGenerator.CODEC
        );
        FantasyMapGenerator.LOGGER.info(
            "Registered ColorMapBiomeSource ({}) and image-map ChunkGenerator ({})",
            COLOR_MAP_BIOME_SOURCE_ID,
            IMAGE_MAP_GENERATOR_ID
        );
    }
}