package com.example.worldgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.fmg.FMGBurg;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.worldmap.runtime.FMGMapDataCache;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.StructureLiquidSettings;
import net.minecraft.structure.pool.StructurePoolBasedGenerator;
import net.minecraft.structure.pool.alias.StructurePoolAliasLookup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.DimensionPadding;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;

/**
 * Worldgen structure that places a jigsaw village exactly at FMG burg positions.
 *
 * The structure is evaluated for many chunks (see structure_set placement); it returns
 * a structure position only for chunks containing a burg.
 */
public final class BurgVillageStructure extends Structure {

    public static final Identifier ID = Identifier.of("fantasymapgenerator", "burg_village");

    /** Registered in {@link com.example.generation.FMGGenerators#register()}. */
    public static StructureType<BurgVillageStructure> TYPE;

    public static final MapCodec<BurgVillageStructure> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Structure.configCodecBuilder(instance)
            ).apply(instance, BurgVillageStructure::new)
    );

    private static final Map<String, Map<Long, List<BurgPos>>> BURG_POS_CACHE = new HashMap<>();

    // Flatness gate for villages. This prevents villages from generating on steep terrain
    // now that we no longer pre-flatten around burg sites.
    private static final int FLATNESS_RADIUS_BLOCKS = 32;
    private static final int FLATNESS_SAMPLE_STEP_BLOCKS = 8;
    private static final int FLATNESS_MAX_RELIEF_BLOCKS = 14;
    private static final double FLATNESS_MAX_MEAN_SLOPE = 0.70; // blocks up per block traveled

    private static final class BurgPos {
        final int burgId;
        final int x;
        final int z;

        BurgPos(int burgId, int x, int z) {
            this.burgId = burgId;
            this.x = x;
            this.z = z;
        }
    }

    public BurgVillageStructure(Structure.Config config) {
        super(config);
    }

    @Override
    protected Optional<StructurePosition> getStructurePosition(Context context) {
        // Only generate in the FMG overworld (or any dimension using ImageMapChunkGenerator).
        if (!(context.chunkGenerator() instanceof ImageMapChunkGenerator fmgGenerator)) {
            return Optional.empty();
        }

        String exportKey = fmgGenerator.mapInfo().fmgExport();
        FMGMapData map = FMGMapDataCache.load(exportKey);
        if (map == null || map.getInfo() == null || map.getBurgs() == null || map.getBurgs().isEmpty()) {
            return Optional.empty();
        }

        Map<Long, List<BurgPos>> byChunk = BURG_POS_CACHE.computeIfAbsent(exportKey, k -> computeBurgChunkMap(map));
        ChunkPos cp = context.chunkPos();
        long key = ChunkPos.toLong(cp.x, cp.z);
        List<BurgPos> burgs = byChunk.get(key);
        if (burgs == null || burgs.isEmpty()) {
            return Optional.empty();
        }

        // If multiple burgs are in the same chunk, pick deterministically.
        BurgPos burg = pickDeterministicBurg(burgs, context.random());

        if (!isAreaFlatEnoughForVillage(context, burg.x, burg.z)) {
            return Optional.empty();
        }

        int y = context.chunkGenerator().getHeight(
                burg.x,
                burg.z,
                Heightmap.Type.WORLD_SURFACE_WG,
                context.world(),
                context.noiseConfig()
        );

        // ChunkGenerator#getHeight returns the heightmap value (typically the first air block
        // above the surface). Our terrain cap produces air at y==target, so the top solid block
        // is usually at y-1.
        int surfaceY = Math.max(context.world().getBottomY(), y - 1);

        BlockPos start = new BlockPos(burg.x, surfaceY, burg.z);

        BurgVillageConfig.Config cfg = BurgVillageConfig.loadOrCreate();
        BurgVillageConfig.VillageType typeCfg = pickVillageType(context, start, cfg, burg.burgId);

        Identifier startPoolId = Identifier.tryParse(typeCfg.startPool());
        if (startPoolId == null) {
            return Optional.empty();
        }

        var pools = context.dynamicRegistryManager().getOrThrow(RegistryKeys.TEMPLATE_POOL);
        var startPoolEntry = pools.getOptional(RegistryKey.of(RegistryKeys.TEMPLATE_POOL, startPoolId));
        if (startPoolEntry.isEmpty()) {
            return Optional.empty();
        }

        Optional<Identifier> startJigsawName = Optional.empty();
        if (typeCfg.startJigsawName() != null && !typeCfg.startJigsawName().isBlank()) {
            Identifier name = Identifier.tryParse(typeCfg.startJigsawName());
            if (name != null) {
                startJigsawName = Optional.of(name);
            }
        }

        int size = Math.max(1, typeCfg.size());
        int maxDistanceFromCenter = 128;

        return StructurePoolBasedGenerator.generate(
                context,
                startPoolEntry.get(),
                startJigsawName,
                size,
                start,
                true,
            // We already computed an explicit Y for the start position. Passing a heightmap here
            // can cause the jigsaw generator to re-snap or offset the Y again.
            Optional.empty(),
                maxDistanceFromCenter,
                StructurePoolAliasLookup.EMPTY,
                DimensionPadding.NONE,
                StructureLiquidSettings.APPLY_WATERLOGGING
        );
    }

    private static boolean isAreaFlatEnoughForVillage(Context context, int centerX, int centerZ) {
        // Sample the surface heightmap on a coarse grid in a square around the center.
        // Metrics:
        // - relief = maxHeight - minHeight
        // - meanSlope = mean(|dh| / step) over cardinal neighbor pairs

        final int radius = FLATNESS_RADIUS_BLOCKS;
        final int step = FLATNESS_SAMPLE_STEP_BLOCKS;

        final int size = (radius * 2 / step) + 1;
        int[] heights = new int[size * size];

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (int ix = 0; ix < size; ix++) {
            int x = centerX - radius + (ix * step);
            for (int iz = 0; iz < size; iz++) {
                int z = centerZ - radius + (iz * step);

                int h = context.chunkGenerator().getHeight(
                        x,
                        z,
                        Heightmap.Type.WORLD_SURFACE_WG,
                        context.world(),
                        context.noiseConfig()
                );

                heights[(ix * size) + iz] = h;
                if (h < min) min = h;
                if (h > max) max = h;
            }
        }

        int relief = max - min;
        if (relief > FLATNESS_MAX_RELIEF_BLOCKS) {
            return false;
        }

        double slopeSum = 0.0;
        int slopeCount = 0;

        for (int ix = 0; ix < size; ix++) {
            for (int iz = 0; iz < size; iz++) {
                int h = heights[(ix * size) + iz];

                if (ix + 1 < size) {
                    int hx = heights[((ix + 1) * size) + iz];
                    slopeSum += Math.abs(hx - h) / (double) step;
                    slopeCount++;
                }
                if (iz + 1 < size) {
                    int hz = heights[(ix * size) + (iz + 1)];
                    slopeSum += Math.abs(hz - h) / (double) step;
                    slopeCount++;
                }
            }
        }

        double meanSlope = slopeCount == 0 ? 0.0 : (slopeSum / slopeCount);
        return meanSlope <= FLATNESS_MAX_MEAN_SLOPE;
    }

    private static BurgPos pickDeterministicBurg(List<BurgPos> burgs, Random random) {
        if (burgs.size() == 1) {
            return burgs.getFirst();
        }
        // deterministic, but still uses the chunk's seeded random
        return burgs.get(Math.floorMod(random.nextInt(), burgs.size()));
    }

    private static BurgVillageConfig.VillageType pickVillageType(
            Context context,
            BlockPos pos,
            BurgVillageConfig.Config config,
            int burgId
    ) {
        Registry<Biome> biomeRegistry = context.dynamicRegistryManager().getOrThrow(RegistryKeys.BIOME);
        RegistryEntry<Biome> biomeEntry = context.biomeSource().getBiome(
                pos.getX() >> 2,
                pos.getY() >> 2,
                pos.getZ() >> 2,
                context.noiseConfig().getMultiNoiseSampler()
        );
        Identifier biomeId = biomeRegistry.getId(biomeEntry.value());

        String biomeKey = biomeId != null ? biomeId.toString() : "";
        List<String> types = config.biomeVillageTypes().get(biomeKey);
        if (types == null || types.isEmpty()) {
            types = config.defaultVillageTypes();
        }
        if (types == null || types.isEmpty()) {
            types = List.of("plains");
        }

        String type = types.get(Math.floorMod(burgId, types.size()));
        BurgVillageConfig.VillageType typeCfg = config.villageTypes().get(type);
        if (typeCfg == null || typeCfg.startPool() == null || typeCfg.startPool().isBlank()) {
            typeCfg = config.villageTypes().get("plains");
        }
        if (typeCfg == null) {
            // Final fallback.
            return new BurgVillageConfig.VillageType("minecraft:village/plains/town_centers", "", 6);
        }
        return typeCfg;
    }

    private static Map<Long, List<BurgPos>> computeBurgChunkMap(FMGMapData map) {
        Map<Long, List<BurgPos>> byChunk = new HashMap<>();

        final double mapW = map.getInfo().getWidth();
        final double mapH = map.getInfo().getHeight();
        final double scale = FMGHeightSampler.SAMPLE_SCALE;
        final double offsetX = -(mapW * scale) / 2.0;
        final double offsetZ = -(mapH * scale) / 2.0;

        for (FMGBurg burg : map.getBurgs()) {
            if (burg == null || burg.isRemoved()) {
                continue;
            }

            int centerX = (int) Math.round((burg.getX() * scale) + offsetX);
            int centerZ = (int) Math.round((burg.getY() * scale) + offsetZ);

            int chunkX = centerX >> 4;
            int chunkZ = centerZ >> 4;
            long key = ChunkPos.toLong(chunkX, chunkZ);

            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(new BurgPos(burg.getI(), centerX, centerZ));
        }

        return byChunk;
    }

    @Override
    public StructureType<?> getType() {
        return TYPE;
    }
}
