package com.example.worldgen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.fmg.FMGBurg;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.fmg.FMGRoute;
import com.example.fmg.FMGRouteSampler;
import com.example.fmg.FMRiverSampler;
import com.example.mixin.NoiseConfigAccessor;
import com.example.worldmap.MapInfo;
import com.example.worldmap.runtime.MapImageCache;
import com.example.worldmap.runtime.MapImageData;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryElementCodec;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;

/**
 * Chunk generator that projects block heights from FMG exports instead of vanilla noise.
 */
public final class ImageMapChunkGenerator extends ChunkGenerator {

    public static final MapCodec<ImageMapChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
                        RegistryElementCodec.of(
                            RegistryKeys.CHUNK_GENERATOR_SETTINGS,
                            ChunkGeneratorSettings.CODEC
                        )
                        .fieldOf("settings")
                        .forGetter(generator -> generator.delegateSettings),
                        MapInfo.CODEC
                        .fieldOf("map")
                        .forGetter(ImageMapChunkGenerator::mapInfo)
            ).apply(instance, ImageMapChunkGenerator::new)
    );

    private final NoiseChunkGenerator delegate;
    private final RegistryEntry<ChunkGeneratorSettings> delegateSettings;
    private final RegistryEntry<ChunkGeneratorSettings> runtimeSettings;
    private final MapInfo mapInfo;

    private final ThreadLocal<ChunkColumnCache> columnCache;

    private final DensityFunction heightCap;
    private final AtomicBoolean noiseRouterPatched = new AtomicBoolean(false);

    public ImageMapChunkGenerator(
            BiomeSource biomeSource,
            RegistryEntry<ChunkGeneratorSettings> settings,
            MapInfo mapInfo
    ) {
        super(biomeSource);
        this.delegateSettings = settings;
        this.mapInfo = mapInfo;

        this.columnCache = ThreadLocal.withInitial(() -> new ChunkColumnCache(mapInfo));

        // Build an in-memory copy of the provided settings, but replace the density
        // functions with a height-cap derived from the FMG map.
        ChunkGeneratorSettings base = settings.value();
        NoiseRouter baseRouter = base.noiseRouter();

        this.heightCap = new FmgHeightCapDensityFunction(mapInfo, this.columnCache);
        NoiseRouter fixedRouter = new NoiseRouter(
            baseRouter.barrierNoise(),
            baseRouter.fluidLevelFloodednessNoise(),
            baseRouter.fluidLevelSpreadNoise(),
            baseRouter.lavaNoise(),
            baseRouter.temperature(),
            baseRouter.vegetation(),
            baseRouter.continents(),
            baseRouter.erosion(),
            baseRouter.depth(),
            baseRouter.ridges(),
            this.heightCap,
            this.heightCap,
            baseRouter.veinToggle(),
            baseRouter.veinRidged(),
            baseRouter.veinGap()
        );

        @SuppressWarnings("deprecation")
        ChunkGeneratorSettings fixedSettings = new ChunkGeneratorSettings(
            base.generationShapeConfig(),
            base.defaultBlock(),
            base.defaultFluid(),
            fixedRouter,
            base.surfaceRule(),
            base.spawnTarget(),
            mapInfo.seaLevel(),
            base.mobGenerationDisabled(),
            false,
            false,
            base.usesLegacyRandom()
        );
        this.runtimeSettings = RegistryEntry.of(fixedSettings);
        this.delegate = new NoiseChunkGenerator(biomeSource, this.runtimeSettings);
    }

    public MapInfo mapInfo() {
        return mapInfo;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess,
                      StructureAccessor structureAccessor, Chunk chunk) {
        // Disable vanilla carving so our projected heightmap stays intact.
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
        patchNoiseRouterOnce(noiseConfig);

        // Let vanilla surface rules decide the top blocks per-biome.
        delegate.buildSurface(region, structures, noiseConfig, chunk);

        // Then stamp route blocks on top of the finished surface.
        applyRoutes(chunk);
    }

    @Override
    public void populateEntities(ChunkRegion region) {
        delegate.populateEntities(region);
    }

    @Override
    public int getWorldHeight() {
        return delegate.getWorldHeight();
    }

    @Override
    public int getSeaLevel() {
        return mapInfo.seaLevel();
    }

    @Override
    public int getMinimumY() {
        return delegate.getMinimumY();
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        return columnCache.get().getHeight(x, z);
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        return delegate.getColumnSample(x, z, world, noiseConfig);
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig,
            StructureAccessor structureAccessor, Chunk chunk) {
        patchNoiseRouterOnce(noiseConfig);
        return delegate.populateNoise(blender, noiseConfig, structureAccessor, chunk);
    }

    private void patchNoiseRouterOnce(NoiseConfig noiseConfig) {
        if (!noiseRouterPatched.compareAndSet(false, true)) {
            return;
        }

        // Vanilla NoiseChunkGenerator uses noiseConfig.getNoiseRouter() during fill.
        // NoiseConfig is created/cached by the ChunkGeneratorSettings registry key.
        // We point our FMG dimension at a distinct settings key (aliasing the vanilla overworld settings)
        // so this patch only affects the FMG dimension and not the real minecraft:overworld.
        NoiseRouter base = noiseConfig.getNoiseRouter();
        NoiseRouter patched = new NoiseRouter(
                base.barrierNoise(),
                base.fluidLevelFloodednessNoise(),
                base.fluidLevelSpreadNoise(),
                base.lavaNoise(),
                base.temperature(),
                base.vegetation(),
                base.continents(),
                base.erosion(),
                base.depth(),
                base.ridges(),
                this.heightCap,
                this.heightCap,
                base.veinToggle(),
                base.veinRidged(),
                base.veinGap()
        );

        ((NoiseConfigAccessor) (Object) noiseConfig).fantasymapgenerator$setNoiseRouter(patched);
    }

    @Override
    public void appendDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        text.add("FMG map: " + mapInfo.fmgExport());

        // Debug info to validate that our height cap is being used.
        int height = columnCache.get().getHeight(pos.getX(), pos.getZ());
        double cap = heightCap.sample(new DensityFunction.UnblendedNoisePos(pos.getX(), pos.getY(), pos.getZ()));
        text.add("FMG height@XZ: " + height + " cap@pos: " + String.format(java.util.Locale.ROOT, "%.2f", cap));
    }

    private void applyRoutes(Chunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int minY = getMinimumY();
        int maxY = getWorldHeight();
        int seaLevel = mapInfo.seaLevel();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        ChunkColumnCache cache = columnCache.get();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkPos.getStartX() + localX;
                int worldZ = chunkPos.getStartZ() + localZ;
                int target = cache.getHeight(worldX, worldZ);

                // Our height cap density function produces air at exactly y==target.
                // The actual top solid surface is therefore usually at y==target-1.
                int surfaceY = target - 1;

                if (surfaceY <= minY + 1 || surfaceY >= maxY - 1) {
                    continue;
                }
                if (surfaceY < seaLevel) {
                    continue;
                }

                FMGRouteSampler.RouteKind routeKind = cache.getRouteKind(worldX, worldZ);
                if (routeKind == FMGRouteSampler.RouteKind.NONE) {
                    continue;
                }

                int biomeX = worldX >> 2;
                int biomeZ = worldZ >> 2;
                int biomeY = surfaceY >> 2;
                RegistryEntry<Biome> biome = chunk.getBiomeForNoiseGen(biomeX, biomeY, biomeZ);

                // Prefer tags for modded biome compatibility where possible.
                boolean isDesert = isBiome(biome, BiomeKeys.DESERT) || biome.isIn(BiomeTags.IS_BADLANDS);
                boolean isSnowy = isBiome(biome, BiomeKeys.SNOWY_PLAINS) || isBiome(biome, BiomeKeys.SNOWY_TAIGA)
                        || isBiome(biome, BiomeKeys.SNOWY_SLOPES) || isBiome(biome, BiomeKeys.FROZEN_PEAKS);

                BlockState pathBlock = choosePathBlock(routeKind, isDesert, isSnowy, worldX, worldZ);
                if (pathBlock == null) {
                    continue;
                }

                mutable.set(worldX, surfaceY, worldZ);
                chunk.setBlockState(mutable, pathBlock, false);

                // Ensure dirt paths don't immediately revert by having snow layers above them.
                mutable.set(worldX, surfaceY + 1, worldZ);
                if (chunk.getBlockState(mutable).isOf(Blocks.SNOW)) {
                    chunk.setBlockState(mutable, Blocks.AIR.getDefaultState(), false);
                }
            }
        }
    }

    /**
     * Caches a fully-smoothed 16x16 height + route kind grid for the most recently
     * accessed chunk on the current thread.
     */
    private static final class ChunkColumnCache {
        private final MapInfo mapInfo;

        // Cache relaxed (1D-smoothed) spline heights per route so results are
        // consistent across chunk grids and don't depend on local windowing.
        private final java.util.HashMap<Integer, RelaxedRoute> relaxedRouteCache = new java.util.HashMap<>();

        // LRU of a few recently used chunk grids for this thread.
        // Noise sampling during fill can touch neighbor chunks; caching prevents
        // recomputing 16x16 grids over and over.
        private final LinkedHashMap<Long, ChunkGrid> grids = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, ChunkGrid> eldest) {
                return size() > 9; // 3x3 neighborhood is usually enough
            }
        };

        ChunkColumnCache(MapInfo mapInfo) {
            this.mapInfo = mapInfo;
        }

        int getHeight(int worldX, int worldZ) {
            ChunkGrid grid = getGrid(worldX, worldZ);
            return grid.heights[idx(worldX, worldZ)];
        }

        FMGRouteSampler.RouteKind getRouteKind(int worldX, int worldZ) {
            ChunkGrid grid = getGrid(worldX, worldZ);
            return grid.routes[idx(worldX, worldZ)];
        }

        private ChunkGrid getGrid(int worldX, int worldZ) {
            int chunkX = worldX >> 4;
            int chunkZ = worldZ >> 4;
            long key = ChunkPos.toLong(chunkX, chunkZ);
            ChunkGrid existing = grids.get(key);
            if (existing != null) {
                return existing;
            }
            ChunkGrid computed = computeGrid(chunkX, chunkZ);
            grids.put(key, computed);
            return computed;
        }

        private ChunkGrid computeGrid(int chunkX, int chunkZ) {
            ChunkGrid out = new ChunkGrid();

            MapImageData data = MapImageCache.get(mapInfo);
            int seaLevel = mapInfo.seaLevel();
            final FMGMapData map = data.fmgData();

            final boolean hasBurgs = map != null && map.getInfo() != null && map.getBurgs() != null && !map.getBurgs().isEmpty();

            // Route shaping is done on a padded grid so we can smoothly blend across
            // chunk edges (noise fill samples neighbor columns frequently).
            // Burg shaping needs a much larger neighborhood to avoid chunk seams.
            // Keep this >= burg blend radius.
            final int pad = hasBurgs ? 64 : 8; // must be >= max(roadRadius, burgBlendRadius)
            final int size = 16 + pad * 2;
            final int startX = (chunkX << 4) - pad;
            final int startZ = (chunkZ << 4) - pad;

            int[] baseHeights = new int[size * size];
            int[] shapedHeights = new int[size * size];

            // These arrays store the nearest spline sample for each cell (if any),
            // letting us treat the road network as a 1D chain (along the spline)
            // plus a radial (2D) gaussian cross-section.
            int[] nearestRouteId = new int[size * size];
            int[] nearestSampleIndex = new int[size * size];
            float[] nearestSampleT = new float[size * size];
            double[] nearestDistSq = new double[size * size];

            // 1) Sample FMG height (padded area)
            for (int lx = 0; lx < size; lx++) {
                for (int lz = 0; lz < size; lz++) {
                    int x = startX + lx;
                    int z = startZ + lz;
                    int index = (lx * size) + lz;

                    int target = data.sampleHeightY(x, z);

                    // River carving from your current generator.
                    FMRiverSampler.RiverQuery rq = FMRiverSampler.query(x, z);
                    if (rq.hit) {
                        double carved = FMRiverSampler.carvedHeight(rq, seaLevel);
                        int riverBed = (int) Math.floor(carved);
                        target = Math.min(target, riverBed);
                    }

                    baseHeights[index] = target;
                }
            }

            // 1b) Smooth/flatten around burg sites BEFORE route shaping.
            // Roads/paths are stamped later on top of this baseline.
            if (hasBurgs) {
                applyBurgPreSmoothing(baseHeights, startX, startZ, size, seaLevel, map);
            }

            // 2) Project FMG route splines into this padded grid and compute nearest
            // route segment + interpolation parameter per cell.
            java.util.Arrays.fill(nearestRouteId, -1);
            java.util.Arrays.fill(nearestSampleIndex, -1);
            java.util.Arrays.fill(nearestSampleT, 0.0f);
            java.util.Arrays.fill(nearestDistSq, Double.POSITIVE_INFINITY);

            final java.util.HashMap<Integer, FMGRouteSampler.RouteKind> routeKindById = new java.util.HashMap<>();
            final java.util.HashMap<Integer, RouteProfile> profileById = new java.util.HashMap<>();

            if (map != null && map.getInfo() != null && map.getRoutes() != null && !map.getRoutes().isEmpty()) {
                final double mapW = map.getInfo().getWidth();
                final double mapH = map.getInfo().getHeight();
                final double scale = FMGHeightSampler.SAMPLE_SCALE;
                final double offsetX = -(mapW * scale) / 2.0;
                final double offsetZ = -(mapH * scale) / 2.0;

                // Use a single max radius for stamping to keep the work bounded.
                final RouteProfile roadProfile = RouteProfile.forKind(FMGRouteSampler.RouteKind.ROAD);
                final RouteProfile trailProfile = RouteProfile.forKind(FMGRouteSampler.RouteKind.TRAIL);
                final int stampRadius = (int) Math.ceil(Math.max(roadProfile.maxRadius, trailProfile.maxRadius) + 1.0);

                for (FMGRoute route : map.getRoutes()) {
                    if (route == null || route.getGroup() == null) {
                        continue;
                    }

                    FMGRouteSampler.RouteKind kind;
                    if ("roads".equals(route.getGroup())) {
                        kind = FMGRouteSampler.RouteKind.ROAD;
                    } else if ("trails".equals(route.getGroup())) {
                        kind = FMGRouteSampler.RouteKind.TRAIL;
                    } else {
                        continue;
                    }

                    List<FMGRouteSampler.RouteSample> samples = FMGRouteSampler.getSampledRoute(route.getI());
                    if (samples == null || samples.size() < 2) {
                        continue;
                    }

                    RouteProfile profile = RouteProfile.forKind(kind);
                    routeKindById.put(route.getI(), kind);
                    profileById.put(route.getI(), profile);

                    double maxR2 = profile.maxRadius * profile.maxRadius;

                    // Stamp each spline segment into the local padded grid.
                    // Using distance-to-segment (not distance-to-sample-point) avoids
                    // occasional "holes" on steep/curvy sections that can create a
                    // single large step when the path briefly stops being shaped.
                    for (int si = 0; si < samples.size() - 1; si++) {
                        FMGRouteSampler.RouteSample a = samples.get(si);
                        FMGRouteSampler.RouteSample b = samples.get(si + 1);

                        double ax = (a.x() * scale) + offsetX;
                        double az = (a.y() * scale) + offsetZ;
                        double bx = (b.x() * scale) + offsetX;
                        double bz = (b.y() * scale) + offsetZ;

                        double segMinX = Math.min(ax, bx) - stampRadius;
                        double segMaxX = Math.max(ax, bx) + stampRadius;
                        double segMinZ = Math.min(az, bz) - stampRadius;
                        double segMaxZ = Math.max(az, bz) + stampRadius;

                        int minLX = (int) Math.floor(segMinX - startX);
                        int maxLX = (int) Math.ceil(segMaxX - startX);
                        int minLZ = (int) Math.floor(segMinZ - startZ);
                        int maxLZ = (int) Math.ceil(segMaxZ - startZ);

                        if (maxLX < 0 || maxLZ < 0 || minLX >= size || minLZ >= size) {
                            continue;
                        }

                        minLX = Math.max(0, minLX);
                        minLZ = Math.max(0, minLZ);
                        maxLX = Math.min(size - 1, maxLX);
                        maxLZ = Math.min(size - 1, maxLZ);

                        double vx = bx - ax;
                        double vz = bz - az;
                        double segLenSq = (vx * vx) + (vz * vz);

                        for (int lx = minLX; lx <= maxLX; lx++) {
                            double px = startX + lx;
                            for (int lz = minLZ; lz <= maxLZ; lz++) {
                                double pz = startZ + lz;

                                double t;
                                if (segLenSq <= 0.0) {
                                    t = 0.0;
                                } else {
                                    double wx = px - ax;
                                    double wz = pz - az;
                                    t = (wx * vx + wz * vz) / segLenSq;
                                    if (t < 0.0) t = 0.0;
                                    else if (t > 1.0) t = 1.0;
                                }

                                double projX = ax + t * vx;
                                double projZ = az + t * vz;
                                double dx = px - projX;
                                double dz = pz - projZ;
                                double distSq = (dx * dx) + (dz * dz);
                                if (distSq > maxR2) {
                                    continue;
                                }

                                int index = (lx * size) + lz;
                                if (distSq < nearestDistSq[index]) {
                                    nearestDistSq[index] = distSq;
                                    nearestRouteId[index] = route.getI();
                                    nearestSampleIndex[index] = si;
                                    nearestSampleT[index] = (float) t;
                                }
                            }
                        }
                    }
                }
            }

            // 3) For each route that touches this grid, build a relaxed + gaussian-smoothed
            // 1D height chain along the spline (using the sampled points as the chain).
            final java.util.HashMap<Integer, int[]> sampleRangeByRoute = new java.util.HashMap<>();
            for (int i = 0; i < nearestRouteId.length; i++) {
                int routeId = nearestRouteId[i];
                if (routeId < 0) {
                    continue;
                }
                int si = nearestSampleIndex[i];
                int[] range = sampleRangeByRoute.get(routeId);
                if (range == null) {
                    sampleRangeByRoute.put(routeId, new int[] { si, si + 1 });
                } else {
                    if (si < range[0]) range[0] = si;
                    if (si + 1 > range[1]) range[1] = si + 1;
                }
            }

            final java.util.HashMap<Integer, RelaxedRoute> relaxedByRoute = new java.util.HashMap<>();
            if (!sampleRangeByRoute.isEmpty() && map != null && map.getInfo() != null) {
                final double mapW = map.getInfo().getWidth();
                final double mapH = map.getInfo().getHeight();
                final double scale = FMGHeightSampler.SAMPLE_SCALE;
                final double offsetX = -(mapW * scale) / 2.0;
                final double offsetZ = -(mapH * scale) / 2.0;

                for (Map.Entry<Integer, int[]> entry : sampleRangeByRoute.entrySet()) {
                    int routeId = entry.getKey();

                    FMGRouteSampler.RouteKind kind = routeKindById.get(routeId);
                    if (kind == null) {
                        continue;
                    }
                    RouteProfile profile = profileById.get(routeId);
                    if (profile == null) {
                        profile = RouteProfile.forKind(kind);
                    }

                    List<FMGRouteSampler.RouteSample> samples = FMGRouteSampler.getSampledRoute(routeId);
                    if (samples == null || samples.size() < 2) {
                        continue;
                    }

                    RelaxedRoute cached = relaxedRouteCache.get(routeId);
                    if (cached == null || cached.heights.length != samples.size()) {
                        cached = computeRelaxedRouteFull(data, seaLevel, offsetX, offsetZ, scale, profile, samples);
                        relaxedRouteCache.put(routeId, cached);
                    }

                    relaxedByRoute.put(routeId, cached);
                }
            }

            // 4) Apply a gaussian cross-section around the spline, using the
            // relaxed 1D chain height as the center height.
            System.arraycopy(baseHeights, 0, shapedHeights, 0, baseHeights.length);
            for (int lx = 0; lx < size; lx++) {
                for (int lz = 0; lz < size; lz++) {
                    int index = (lx * size) + lz;
                    int original = baseHeights[index];
                    if (original < seaLevel) {
                        continue;
                    }

                    int routeId = nearestRouteId[index];
                    if (routeId < 0) {
                        continue;
                    }

                    RelaxedRoute chain = relaxedByRoute.get(routeId);
                    if (chain == null) {
                        continue;
                    }

                    FMGRouteSampler.RouteKind kind = routeKindById.get(routeId);
                    if (kind == null) {
                        continue;
                    }

                    RouteProfile profile = profileById.get(routeId);
                    if (profile == null) {
                        profile = RouteProfile.forKind(kind);
                    }

                    double dist = Math.sqrt(nearestDistSq[index]);
                    if (dist > profile.maxRadius) {
                        continue;
                    }

                    int si = nearestSampleIndex[index];
                    int localSi = si - chain.offset;
                    if (localSi < 0 || localSi + 1 >= chain.heights.length) {
                        continue;
                    }

                    double t = nearestSampleT[index];
                    double center = lerp(chain.heights[localSi], chain.heights[localSi + 1], t);
                    double target;

                    if (dist <= profile.flatHalfWidth) {
                        target = center;
                    } else {
                        double d = dist - profile.flatHalfWidth;
                        double w = gaussian(d, profile.shoulderSigmaBlocks);
                        // Fade to 0 at the edge so terrain doesn't get a visible ring.
                        w *= 1.0 - smoothstep(profile.maxRadius - 1.0, profile.maxRadius, dist);
                        target = lerp(original, center, w);
                    }

                    // Prefer cut. Allow limited fill to avoid big embankments.
                    if (target > original) {
                        target = Math.min(target, original + profile.maxFill);
                    }

                    shapedHeights[index] = (int) Math.round(target);
                }
            }

            // 5) Clamp cross-slope in the core band so left/right don't diverge.
            // This is a cut-only relaxation on the band.
            int[] constrained = shapedHeights.clone();
            for (int pass = 0; pass < 3; pass++) {
                for (int lx = 1; lx < size - 1; lx++) {
                    for (int lz = 1; lz < size - 1; lz++) {
                        int index = (lx * size) + lz;
                        int routeId = nearestRouteId[index];
                        if (routeId < 0) {
                            continue;
                        }

                        FMGRouteSampler.RouteKind kind = routeKindById.get(routeId);
                        if (kind == null) {
                            continue;
                        }
                        RouteProfile profile = profileById.get(routeId);
                        if (profile == null) {
                            profile = RouteProfile.forKind(kind);
                        }

                        double dist = Math.sqrt(nearestDistSq[index]);
                        if (dist > (profile.flatHalfWidth + 1.5)) {
                            continue;
                        }

                        int h0 = constrained[index];
                        if (h0 < seaLevel) {
                            continue;
                        }

                        int limit = profile.maxCrossStep;
                        int hN = constrained[index - size];
                        int hS = constrained[index + size];
                        int hW = constrained[index - 1];
                        int hE = constrained[index + 1];

                        int maxAllowed = h0;
                        if (hN >= seaLevel) maxAllowed = Math.min(maxAllowed, hN + limit);
                        if (hS >= seaLevel) maxAllowed = Math.min(maxAllowed, hS + limit);
                        if (hW >= seaLevel) maxAllowed = Math.min(maxAllowed, hW + limit);
                        if (hE >= seaLevel) maxAllowed = Math.min(maxAllowed, hE + limit);

                        constrained[index] = Math.min(h0, maxAllowed);
                    }
                }
            }
            shapedHeights = constrained;

            // 5b) Enforce walkability on the actual path surface: adjacent path cells
            // must differ by at most 1 block. This fixes "2 blocks up at once" cases
            // (including around junctions) even when spline samples are slightly sparse
            // or when the nearest-sample projection picks different samples per cell.
            boolean[] pathMask = new boolean[size * size];
            for (int i = 0; i < pathMask.length; i++) {
                int routeId = nearestRouteId[i];
                if (routeId < 0) {
                    continue;
                }
                FMGRouteSampler.RouteKind kind = routeKindById.get(routeId);
                if (kind == null) {
                    continue;
                }
                RouteProfile profile = profileById.get(routeId);
                if (profile == null) {
                    profile = RouteProfile.forKind(kind);
                }
                pathMask[i] = nearestDistSq[i] <= (profile.routeBlockRadius * profile.routeBlockRadius);
            }
            enforceWalkablePathSurface(shapedHeights, pathMask, size, seaLevel, 1, 2);

            // 6) Copy central 16x16 into the chunk grid, and build a route mask
            // based on the (flat) core distance to the spline.
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int srcX = localX + pad;
                    int srcZ = localZ + pad;
                    int srcIndex = (srcX * size) + srcZ;
                    int dstIndex = (localX << 4) | localZ;

                    out.heights[dstIndex] = shapedHeights[srcIndex];

                    int routeId = nearestRouteId[srcIndex];
                    if (routeId < 0) {
                        out.routes[dstIndex] = FMGRouteSampler.RouteKind.NONE;
                        continue;
                    }

                    FMGRouteSampler.RouteKind kind = routeKindById.get(routeId);
                    if (kind == null) {
                        out.routes[dstIndex] = FMGRouteSampler.RouteKind.NONE;
                        continue;
                    }

                    RouteProfile profile = profileById.get(routeId);
                    if (profile == null) {
                        profile = RouteProfile.forKind(kind);
                    }

                    double dist = Math.sqrt(nearestDistSq[srcIndex]);
                    out.routes[dstIndex] = dist <= profile.routeBlockRadius ? kind : FMGRouteSampler.RouteKind.NONE;
                }
            }

            return out;
        }

        private void applyBurgPreSmoothing(
                int[] baseHeights,
                int startX,
                int startZ,
                int size,
                int seaLevel,
                FMGMapData map
        ) {
            // Your requested behavior:
            // - pick a representative height near the burg center (average of a small neighborhood)
            // - bias it downward a few blocks (3-4)
            // - smoothly push terrain in a radius towards that target with a blend ring
            // - keep slopes gentle so the transition looks natural

            // 2x larger footprint than before.
            final int coreRadius = 40;
            final int blendRadius = 64;
            final int sampleRadius = 3; // neighborhood for average height
            final int downBias = 4;

            final int maxFill = 10;
            final int maxCut = 18;

            // Keep grades gentle: adjacent <=1, and over 2 blocks <=1.
            final int slopePasses = 6;

            final double mapW = map.getInfo().getWidth();
            final double mapH = map.getInfo().getHeight();
            final double scale = FMGHeightSampler.SAMPLE_SCALE;
            final double offsetX = -(mapW * scale) / 2.0;
            final double offsetZ = -(mapH * scale) / 2.0;

            // Snapshot of the baseline before we touch burgs (used for clamping).
            final int[] baseline = baseHeights.clone();

            for (FMGBurg burg : map.getBurgs()) {
                if (burg == null || burg.isRemoved()) {
                    continue;
                }

                int centerX = (int) Math.round((burg.getX() * scale) + offsetX);
                int centerZ = (int) Math.round((burg.getY() * scale) + offsetZ);

                if (centerX < startX - blendRadius || centerX > startX + size - 1 + blendRadius
                        || centerZ < startZ - blendRadius || centerZ > startZ + size - 1 + blendRadius) {
                    continue;
                }

                Integer avg = computeLocalMeanLandHeight(baseHeights, startX, startZ, size, seaLevel, centerX, centerZ, sampleRadius);
                if (avg == null) {
                    continue;
                }

                int targetY = Math.max(seaLevel + 2, avg - downBias);

                // First: direct pull towards targetY with smooth falloff.
                for (int dx = -blendRadius; dx <= blendRadius; dx++) {
                    int worldX = centerX + dx;
                    int lx = worldX - startX;
                    if (lx <= 0 || lx >= size - 1) continue;

                    for (int dz = -blendRadius; dz <= blendRadius; dz++) {
                        int worldZ = centerZ + dz;
                        int lz = worldZ - startZ;
                        if (lz <= 0 || lz >= size - 1) continue;

                        double dist = Math.sqrt((double) dx * (double) dx + (double) dz * (double) dz);
                        if (dist > blendRadius) {
                            continue;
                        }

                        int i = (lx * size) + lz;
                        int original = baseHeights[i];
                        if (original < seaLevel) {
                            continue;
                        }

                        double pull = dist <= coreRadius
                                ? 0.85
                                : 0.85 * (1.0 - smoothstep(coreRadius, blendRadius, dist));

                        int shaped = (int) Math.round(lerp(original, targetY, pull));

                        int base0 = baseline[i];
                        if (shaped > base0 + maxFill) {
                            shaped = base0 + maxFill;
                        } else if (shaped < base0 - maxCut) {
                            shaped = base0 - maxCut;
                        }

                        baseHeights[i] = shaped;
                    }
                }

                // Second: a few slope-tightening passes in the region so it blends naturally.
                for (int pass = 0; pass < slopePasses; pass++) {
                    boolean any = false;

                    for (int dx = -blendRadius; dx <= blendRadius; dx++) {
                        int worldX = centerX + dx;
                        int lx = worldX - startX;
                        if (lx <= 1 || lx >= size - 2) continue;
                        for (int dz = -blendRadius; dz <= blendRadius; dz++) {
                            int worldZ = centerZ + dz;
                            int lz = worldZ - startZ;
                            if (lz <= 1 || lz >= size - 2) continue;

                            double dist = Math.sqrt((double) dx * (double) dx + (double) dz * (double) dz);
                            if (dist > blendRadius) continue;

                            int i = (lx * size) + lz;
                            int h = baseHeights[i];
                            if (h < seaLevel) continue;

                            // Adjacent neighbors
                            any |= clampStepCutPreferred(baseHeights, baseline, i, i - size, 1, maxFill);
                            any |= clampStepCutPreferred(baseHeights, baseline, i, i + size, 1, maxFill);
                            any |= clampStepCutPreferred(baseHeights, baseline, i, i - 1, 1, maxFill);
                            any |= clampStepCutPreferred(baseHeights, baseline, i, i + 1, 1, maxFill);

                            // Two-step neighbors (approx 1 up per 2 blocks)
                            any |= clampStepCutPreferred(baseHeights, baseline, i, i - size * 2, 1, maxFill);
                            any |= clampStepCutPreferred(baseHeights, baseline, i, i + size * 2, 1, maxFill);
                            any |= clampStepCutPreferred(baseHeights, baseline, i, i - 2, 1, maxFill);
                            any |= clampStepCutPreferred(baseHeights, baseline, i, i + 2, 1, maxFill);

                            // Softly bias back towards target in the core.
                            if (dist <= coreRadius) {
                                int hh = baseHeights[i];
                                int biased = (int) Math.round(lerp(hh, targetY, 0.25));
                                int base0 = baseline[i];
                                if (biased > base0 + maxFill) biased = base0 + maxFill;
                                else if (biased < base0 - maxCut) biased = base0 - maxCut;
                                if (biased != hh) {
                                    baseHeights[i] = biased;
                                    any = true;
                                }
                            }
                        }
                    }

                    if (!any) {
                        break;
                    }
                }
            }
        }

        private static Integer computeLocalMeanLandHeight(
                int[] heights,
                int startX,
                int startZ,
                int size,
                int seaLevel,
                int centerX,
                int centerZ,
                int radius
        ) {
            long sum = 0;
            int count = 0;

            for (int dx = -radius; dx <= radius; dx++) {
                int worldX = centerX + dx;
                int lx = worldX - startX;
                if (lx < 0 || lx >= size) continue;
                for (int dz = -radius; dz <= radius; dz++) {
                    int worldZ = centerZ + dz;
                    int lz = worldZ - startZ;
                    if (lz < 0 || lz >= size) continue;
                    int h = heights[(lx * size) + lz];
                    if (h >= seaLevel) {
                        sum += h;
                        count++;
                    }
                }
            }

            if (count == 0) {
                return null;
            }
            return (int) Math.round(sum / (double) count);
        }

        private static boolean clampStepCutPreferred(
                int[] heights,
                int[] baseline,
                int i,
                int ni,
                int maxStep,
                int maxFill
        ) {
            if (ni < 0 || ni >= heights.length) {
                return false;
            }

            int h = heights[i];
            int hn = heights[ni];
            int diff = h - hn;
            if (diff > maxStep) {
                heights[i] = hn + maxStep;
                return true;
            }
            if (diff < -maxStep) {
                // Allow limited fill (relative to baseline), but prefer cut overall.
                int desired = hn - maxStep;
                int maxAllowed = baseline[i] + maxFill;
                if (desired > maxAllowed) {
                    desired = maxAllowed;
                }
                if (desired > heights[i]) {
                    heights[i] = desired;
                    return true;
                }
            }
            return false;
        }



        private static double smoothstep(double edge0, double edge1, double x) {
            if (x <= edge0) return 0.0;
            if (x >= edge1) return 1.0;
            double t = (x - edge0) / (edge1 - edge0);
            return t * t * (3.0 - 2.0 * t);
        }

        private static double lerp(double a, double b, double t) {
            return a + (b - a) * t;
        }

        private static double gaussian(double x, double sigma) {
            if (sigma <= 0.0) {
                return 0.0;
            }
            double t = x / sigma;
            return Math.exp(-0.5 * t * t);
        }

        private static void enforceWalkablePathSurface(
                int[] heights,
                boolean[] pathMask,
                int size,
                int seaLevel,
                int maxStep,
                int maxRaise
        ) {
            int[] baseline = heights.clone();
            int[] working = heights;

            // A few passes is enough because the path band is narrow.
            for (int pass = 0; pass < 8; pass++) {
                boolean any = false;
                for (int x = 1; x < size - 1; x++) {
                    for (int z = 1; z < size - 1; z++) {
                        int i = (x * size) + z;
                        if (!pathMask[i]) {
                            continue;
                        }
                        int h = working[i];
                        if (h < seaLevel) {
                            continue;
                        }

                        // For each neighbor that is also path, clamp both upward and downward.
                        int[] nIdx = new int[] {
                                i - size, i + size, i - 1, i + 1,
                                i - size - 1, i - size + 1, i + size - 1, i + size + 1
                        };
                        for (int ni : nIdx) {
                            if (!pathMask[ni]) {
                                continue;
                            }
                            int hn = working[ni];
                            if (hn < seaLevel) {
                                continue;
                            }
                            int diff = h - hn;
                            if (diff > maxStep) {
                                h = hn + maxStep;
                                any = true;
                            } else if (diff < -maxStep) {
                                int desired = hn - maxStep;
                                int maxAllowed = baseline[i] + maxRaise;
                                if (desired > maxAllowed) {
                                    desired = maxAllowed;
                                }
                                if (desired > h) {
                                    h = desired;
                                    any = true;
                                }
                            }
                        }
                        working[i] = h;
                    }
                }
                if (!any) {
                    break;
                }
            }
        }

        private static double sampleHeightBilinear(MapImageData data, double worldX, double worldZ) {
            int x0 = (int) Math.floor(worldX);
            int z0 = (int) Math.floor(worldZ);
            int x1 = x0 + 1;
            int z1 = z0 + 1;

            double fx = worldX - x0;
            double fz = worldZ - z0;

            double h00 = data.sampleHeightY(x0, z0);
            double h10 = data.sampleHeightY(x1, z0);
            double h01 = data.sampleHeightY(x0, z1);
            double h11 = data.sampleHeightY(x1, z1);

            double hx0 = lerp(h00, h10, fx);
            double hx1 = lerp(h01, h11, fx);
            return lerp(hx0, hx1, fz);
        }

        private static RelaxedRoute computeRelaxedRouteFull(
                MapImageData data,
                int seaLevel,
                double offsetX,
                double offsetZ,
                double scale,
                RouteProfile profile,
                List<FMGRouteSampler.RouteSample> samples
        ) {
            int len = samples.size();
            double[] wx = new double[len];
            double[] wz = new double[len];
            double[] h0 = new double[len];
            double[] h = new double[len];

            for (int si = 0; si < len; si++) {
                FMGRouteSampler.RouteSample s = samples.get(si);
                double px = (s.x() * scale) + offsetX;
                double pz = (s.y() * scale) + offsetZ;
                wx[si] = px;
                wz[si] = pz;

                double target = sampleHeightBilinear(data, px, pz);

                int ix = (int) Math.round(px);
                int iz = (int) Math.round(pz);
                FMRiverSampler.RiverQuery rq = FMRiverSampler.query(ix, iz);
                if (rq.hit) {
                    double carved = FMRiverSampler.carvedHeight(rq, seaLevel);
                    target = Math.min(target, carved);
                }

                h0[si] = target;
                h[si] = target;
            }

            // Enforce max slope (iterative forward/backward constraints).
            for (int it = 0; it < profile.slopeIters; it++) {
                for (int j = 1; j < len; j++) {
                    double segLen = Math.hypot(wx[j] - wx[j - 1], wz[j] - wz[j - 1]);
                    double maxDiff = segLen * profile.maxSlope;
                    double diff = h[j] - h[j - 1];
                    if (diff > maxDiff) {
                        h[j] = h[j - 1] + maxDiff;
                    }
                }
                for (int j = len - 2; j >= 0; j--) {
                    double segLen = Math.hypot(wx[j] - wx[j + 1], wz[j] - wz[j + 1]);
                    double maxDiff = segLen * profile.maxSlope;
                    double diff = h[j] - h[j + 1];
                    if (diff > maxDiff) {
                        h[j] = h[j + 1] + maxDiff;
                    }
                }
            }

            // True gaussian blur along the entire chain, using clamped boundaries
            // so the result is independent of where a chunk "slices" the route.
            if (profile.chainRadiusSamples > 0 && profile.chainPasses > 0) {
                int r = profile.chainRadiusSamples;
                double sigma = Math.max(0.25, profile.chainSigmaSamples);
                double[] weights = new double[r + 1];
                for (int o = 0; o <= r; o++) {
                    weights[o] = gaussian(o, sigma);
                }

                double denom = weights[0];
                for (int o = 1; o <= r; o++) {
                    denom += 2.0 * weights[o];
                }

                double[] tmp = new double[len];
                for (int pass = 0; pass < profile.chainPasses; pass++) {
                    for (int j = 0; j < len; j++) {
                        double sum = weights[0] * h[j];
                        for (int o = 1; o <= r; o++) {
                            double w = weights[o];
                            int a = j - o;
                            int b = j + o;
                            if (a < 0) a = 0;
                            if (b >= len) b = len - 1;
                            sum += w * h[a];
                            sum += w * h[b];
                        }
                        tmp[j] = sum / denom;
                    }
                    double[] swap = h;
                    h = tmp;
                    tmp = swap;
                }
            }

            // Re-apply slope constraints and cap upward movement to be cut-preferred.
            for (int it = 0; it < profile.slopeIters; it++) {
                for (int j = 1; j < len; j++) {
                    double segLen = Math.hypot(wx[j] - wx[j - 1], wz[j] - wz[j - 1]);
                    double maxDiff = segLen * profile.maxSlope;
                    double diff = h[j] - h[j - 1];
                    if (diff > maxDiff) {
                        h[j] = h[j - 1] + maxDiff;
                    }
                }
                for (int j = len - 2; j >= 0; j--) {
                    double segLen = Math.hypot(wx[j] - wx[j + 1], wz[j] - wz[j + 1]);
                    double maxDiff = segLen * profile.maxSlope;
                    double diff = h[j] - h[j + 1];
                    if (diff > maxDiff) {
                        h[j] = h[j + 1] + maxDiff;
                    }
                }
            }

            if (profile.maxCenterRaise >= 0.0) {
                for (int j = 0; j < len; j++) {
                    h[j] = Math.min(h[j], h0[j] + profile.maxCenterRaise);
                }
            }

            return new RelaxedRoute(0, h);
        }

        private static final class RelaxedRoute {
            final int offset;
            final double[] heights;

            RelaxedRoute(int offset, double[] heights) {
                this.offset = offset;
                this.heights = heights;
            }
        }

        private static final class RouteProfile {
            final double flatHalfWidth;
            final double maxRadius;
            final double maxFill;
            final double shoulderSigmaBlocks;

            // 1D chain smoothing (along the spline)
            final double maxSlope;
            final int slopeIters;
            final int chainRadiusSamples;
            final double chainSigmaSamples;
            final int chainPasses;

            // Cap how much the centerline is allowed to rise above its sampled height.
            // This prevents isolated spikes where one sample hits a ridge cell.
            final double maxCenterRaise;

            // Cross-slope clamp in the core band
            final int maxCrossStep;

            // Radius around the spline that becomes the visible path blocks.
            final double routeBlockRadius;

            private RouteProfile(
                    double flatHalfWidth,
                    double maxRadius,
                    double maxFill,
                    double shoulderSigmaBlocks,
                    double maxSlope,
                    int slopeIters,
                    int chainRadiusSamples,
                    double chainSigmaSamples,
                    int chainPasses,
                        double maxCenterRaise,
                    int maxCrossStep,
                    double routeBlockRadius
            ) {
                this.flatHalfWidth = flatHalfWidth;
                this.maxRadius = maxRadius;
                this.maxFill = maxFill;
                this.shoulderSigmaBlocks = shoulderSigmaBlocks;

                this.maxSlope = maxSlope;
                this.slopeIters = slopeIters;
                this.chainRadiusSamples = chainRadiusSamples;
                this.chainSigmaSamples = chainSigmaSamples;
                this.chainPasses = chainPasses;
                this.maxCenterRaise = maxCenterRaise;

                this.maxCrossStep = maxCrossStep;
                this.routeBlockRadius = routeBlockRadius;
            }

            static RouteProfile forKind(FMGRouteSampler.RouteKind kind) {
                if (kind == FMGRouteSampler.RouteKind.ROAD) {
                    // Roads: flat core + moderate shoulders, big 1D gaussian.
                    // maxSlope ~= 1 block up per 1.5 blocks forward.
                    return new RouteProfile(
                            3.0,   // flatHalfWidth (blocks) (wider core)
                            8.0,   // maxRadius (blocks)
                            2.0,   // maxFill (blocks)
                            2.0,   // shoulderSigmaBlocks
                            1.0 / 1.5, // maxSlope (dy per block length)
                            10,    // slopeIters
                            18,    // chainRadiusSamples
                            6.0,   // chainSigmaSamples
                            2,     // chainPasses
                            0.5,   // maxCenterRaise (blocks)
                            2,     // maxCrossStep
                            4.4    // routeBlockRadius (wider painted path)
                    );
                }
                if (kind == FMGRouteSampler.RouteKind.TRAIL) {
                    return new RouteProfile(
                            1.6,
                            6.0,
                            1.0,
                            1.6,
                            1.0 / 1.5,
                            10,
                            14,
                            5.0,
                            2,
                            0.5,
                            2,
                            2.4
                    );
                }
                return new RouteProfile(
                        1.6,
                        6.0,
                        1.0,
                        1.6,
                        1.0 / 1.5,
                        10,
                        14,
                        5.0,
                        2,
                        0.5,
                        2,
                        2.4
                );
            }
        }

        private static int idx(int worldX, int worldZ) {
            int localX = worldX & 15;
            int localZ = worldZ & 15;
            return (localX << 4) | localZ;
        }

        private static final class ChunkGrid {
            final int[] heights = new int[16 * 16];
            final FMGRouteSampler.RouteKind[] routes = new FMGRouteSampler.RouteKind[16 * 16];
        }
    }

    /**
     * Density function that makes everything solid below the FMG height (and air above).
     *
     * This is intentionally large-magnitude so it dominates other density functions,
     * keeping the resulting terrain tightly locked to the height map.
     */
    private static final class FmgHeightCapDensityFunction implements DensityFunction {
        private static final double SCALE = 64.0;

        private static final MapCodec<FmgHeightCapDensityFunction> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                MapInfo.CODEC.fieldOf("map").forGetter(df -> df.mapInfo)
            ).apply(instance, info -> new FmgHeightCapDensityFunction(
                info,
                ThreadLocal.withInitial(() -> new ChunkColumnCache(info))
            ))
        );

        private final MapInfo mapInfo;
        private final ThreadLocal<ChunkColumnCache> cache;

        private FmgHeightCapDensityFunction(MapInfo mapInfo, ThreadLocal<ChunkColumnCache> cache) {
            this.mapInfo = mapInfo;
            this.cache = cache;
        }

        @Override
        public double sample(DensityFunction.NoisePos pos) {
            int x = pos.blockX();
            int y = pos.blockY();
            int z = pos.blockZ();

            int height = cache.get().getHeight(x, z);
            return (height - y) * SCALE;
        }

        @Override
        public void fill(double[] densities, DensityFunction.EachApplier applier) {
            applier.fill(densities, this);
        }

        @Override
        public DensityFunction apply(DensityFunction.DensityFunctionVisitor visitor) {
            return visitor.apply(this);
        }

        @Override
        public double minValue() {
            return -(mapInfo.maxY() - mapInfo.minY() + 16) * SCALE;
        }

        @Override
        public double maxValue() {
            return (mapInfo.maxY() - mapInfo.minY() + 16) * SCALE;
        }

        @Override
        public net.minecraft.util.dynamic.CodecHolder<? extends DensityFunction> getCodecHolder() {
            // Not expected to be serialized (settings are stored by registry key), but required by the interface.
            return net.minecraft.util.dynamic.CodecHolder.of(CODEC);
        }
    }

    private static BlockState choosePathBlock(
            FMGRouteSampler.RouteKind kind,
            boolean isDesert,
            boolean isSnowy,
            int worldX,
            int worldZ
    ) {
        // Deterministic pseudo-random based on world coordinates so
        // routes look varied but stable between runs.
        long seed = (long) worldX * 341873128712L ^ (long) worldZ * 132897987541L;
        seed ^= (seed >>> 13);
        seed *= 0x5DEECE66DL;
        int r = (int) (seed & 0x7FFFFFFF);

        if (kind == FMGRouteSampler.RouteKind.TRAIL) {
            if (isDesert) {
                // Desert trails: mostly sand with occasional birch planks.
                int v = r % 4; // 0-3
                if (v == 2) {
                    return Blocks.BIRCH_PLANKS.getDefaultState();
                }
                return Blocks.SAND.getDefaultState();
            }

            if (isSnowy) {
                // Snowy trails: cobblestone and gravel, no snow on top.
                int v = r % 4;
                if (v < 2) {
                    return Blocks.COBBLESTONE.getDefaultState();
                }
                return Blocks.GRAVEL.getDefaultState();
            }
            return Blocks.DIRT_PATH.getDefaultState();
        }

        if (kind == FMGRouteSampler.RouteKind.ROAD) {
            if (isDesert) {
                // Desert roads: terracotta core with granite variations.
                int v = r % 8;
                if (v < 3) {
                    return Blocks.TERRACOTTA.getDefaultState();
                } else if (v < 5) {
                    return Blocks.GRANITE.getDefaultState();
                } else if (v < 7) {
                    return Blocks.POLISHED_GRANITE.getDefaultState();
                }
                // Occasional sand to help it fade into surroundings.
                return Blocks.SAND.getDefaultState();
            }

            if (isSnowy) {
                // Snowy roads: cobblestone/gravel base with andesite/stone bricks.
                int v = r % 8;
                if (v < 2) {
                    return Blocks.COBBLESTONE.getDefaultState();
                } else if (v < 4) {
                    return Blocks.GRAVEL.getDefaultState();
                } else if (v < 6) {
                    return Blocks.ANDESITE.getDefaultState();
                }
                return Blocks.STONE_BRICKS.getDefaultState();
            }

            return Blocks.DIRT_PATH.getDefaultState();
        }

        return null;
    }

    private static boolean isBiome(RegistryEntry<Biome> biome, net.minecraft.registry.RegistryKey<Biome> key) {
        return biome.getKey().map(k -> k.equals(key)).orElse(false);
    }
}
