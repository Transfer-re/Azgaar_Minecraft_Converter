package com.example.worldgen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.fmg.FMGCell;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.fmg.FMGProvince;
import com.example.fmg.FMGRoute;
import com.example.fmg.FMGRouteSampler;
import com.example.fmg.FMGState;
import com.example.mixin.NoiseConfigAccessor;
import com.example.worldmap.MapInfo;
import com.example.worldmap.runtime.MapImageCache;
import com.example.worldmap.runtime.MapImageData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryElementCodec;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.Identifier;
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
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;

/**
 * Chunk generator that projects block heights from FMG exports instead of vanilla noise.
 */
public final class ImageMapChunkGenerator extends ChunkGenerator {

    public static final Codec<ImageMapChunkGenerator> CODEC = RecordCodecBuilder.create(instance ->
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
    /** Vertical generation range: the settings' own, or the world's build range for blended terrain. */
    private final net.minecraft.world.gen.chunk.GenerationShapeConfig shape;
    private final AtomicBoolean noiseRouterPatched = new AtomicBoolean(false);
    private volatile boolean cavesApplied = false;
    private volatile boolean worldSeedKnown = false;
    private volatile long worldSeed;

    /** Working-grid padding around each chunk for route shaping (see computeGrid). */
    private static final int ROUTE_PAD = 20;

    public ImageMapChunkGenerator(
            BiomeSource biomeSource,
            RegistryEntry<ChunkGeneratorSettings> settings,
            MapInfo mapInfo
    ) {
        super(biomeSource);
        this.delegateSettings = settings;
        this.mapInfo = mapInfo;
        // NOTE: FmgWorldSettings.setCurrent is deliberately NOT called here.
        // This constructor runs for every decode of an image_map generator --
        // including throwaway ones from registry loading (e.g. the datapack's
        // default fmg_test.json world preset), not just the actually-running
        // world -- so setting the process-wide "current" settings here let a
        // stray decode silently clobber the real world's scale/height for any
        // chunk generated afterward. It's set instead from ServerWorldEvents.LOAD
        // in FantasyMapGenerator, gated to the real overworld's generator.

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

        this.shape = shapeFor(mapInfo, base.generationShapeConfig());

        @SuppressWarnings("deprecation")
        ChunkGeneratorSettings fixedSettings = new ChunkGeneratorSettings(
            this.shape,
            base.defaultBlock(),
            base.defaultFluid(),
            fixedRouter,
            base.surfaceRule(),
            base.spawnTarget(),
            mapInfo.seaLevel(),
            base.mobGenerationDisabled(),
            // Aquifers off: the constant sea-level aquifer fills every sub-sea-level
            // air cell with water, so FMG oceans stay full right up to the shore.
            // (The real aquifer's flooded/dry noise leaves a ragged band of exposed
            // seafloor at the coast because it keys off vanilla continentalness,
            // which has nothing to do with where the FMG landmass is.)
            false,
            false,
            base.usesLegacyRandom()
        );
        this.runtimeSettings = RegistryEntry.of(fixedSettings);
        this.delegate = new NoiseChunkGenerator(biomeSource, this.runtimeSettings);

        // The expensive per-map builds (heightfield / routes / rivers / climate)
        // now happen in FmgBake.ensureBaked on ServerWorldEvents.LOAD -- on the
        // server thread, before any chunk generates, and persisted to the world
        // folder. If that doesn't run (non-server tooling), the lazy builders
        // still fire on first use.
    }

    public MapInfo mapInfo() {
        return mapInfo;
    }

    /**
     * Blended-terrain worlds pick a dimension type tall enough for their
     * mountains, so noise has to be generated over that whole range too --
     * the bundled settings stop at y 400.
     */
    private static net.minecraft.world.gen.chunk.GenerationShapeConfig shapeFor(
            MapInfo mapInfo, net.minecraft.world.gen.chunk.GenerationShapeConfig base) {
        if (!mapInfo.blendTerrain()) {
            return base;
        }
        int minY = Math.floorDiv(mapInfo.minY(), 16) * 16;
        int height = Math.max(16, ((mapInfo.maxY() - minY + 15) / 16) * 16);
        return net.minecraft.world.gen.chunk.GenerationShapeConfig.create(
                minY, height, base.horizontalSize(), base.verticalSize());
    }

    // ---- fractal detail noise (analytic, per column) ----

    private static com.example.heightmap.HeightPostProcess.FractalDetail detailPass(MapInfo mapInfo) {
        // Per-world switch + amplitude come from MapInfo (the create-world screen);
        // frequency / octaves / fade stay in the global heightmap.json.
        if (!mapInfo.surfaceDetail() || mapInfo.detailAmplitude() == 0.0) {
            return null;
        }
        com.example.heightmap.HeightmapPostConfig cfg = com.example.heightmap.HeightmapPostConfig.get();
        if (!cfg.enabled) {
            return null;
        }
        com.example.heightmap.HeightPostProcess.FractalDetail d = cfg.detail.toPass();
        d.amplitude = mapInfo.detailAmplitude();
        return d;
    }

    /**
     * Terrain height before route/river shaping: FMG height plus detail noise.
     * Both the terrain grid and the road centre-line chain must sample this --
     * a chain that skips the detail noise sits below every detail bump and cuts
     * a trench the length of the noise wavelength through it.
     */
    static double terrainY(MapInfo mapInfo, MapImageData data, int worldX, int worldZ) {
        double h = data.sampleHeightY(worldX, worldZ);
        if (data.fmgData() != null && data.fmgData().getTerrain() != null) {
            return h;   // the baked terrain carries its own per-block detail
        }
        return h + detailOffset(mapInfo, worldX, worldZ, h);
    }

    /** Per-column height offset from the configured detail-noise pass, or 0 if disabled. */
    static double detailOffset(MapInfo mapInfo, int worldX, int worldZ, double currentHeight) {
        com.example.heightmap.HeightPostProcess.FractalDetail d = detailPass(mapInfo);
        if (d == null) return 0.0;
        com.example.heightmap.HeightmapPostConfig cfg = com.example.heightmap.HeightmapPostConfig.get();
        long base = cfg.seed != 0 ? cfg.seed
                : (mapInfo.fmgExport() == null ? 0 : mapInfo.fmgExport().hashCode());
        long ns = com.example.heightmap.HeightPostProcess.FractalDetail.noiseSeed(base);
        return d.deltaAt(worldX, worldZ, currentHeight, mapInfo.seaLevel(), ns);
    }

    @Override
    protected Codec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess,
                      StructureAccessor structureAccessor, Chunk chunk,
                      net.minecraft.world.gen.GenerationStep.Carver carverStep) {
        patchNoiseRouterOnce(noiseConfig);
        // Delegate to vanilla carving to enable caves.
        // Our height cap density function keeps the surface locked to the FMG heightmap,
        // but carving can still cut into that solid volume to form caves.
        delegate.carve(chunkRegion, seed, noiseConfig, biomeAccess, structureAccessor, chunk, carverStep);
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
        patchNoiseRouterOnce(noiseConfig);

        // Let vanilla surface rules decide the top blocks per-biome.
        delegate.buildSurface(region, structures, noiseConfig, chunk);

        // A layered-surface profile (Conquest) then rebuilds the top of every
        // land column from its ground palettes.
        LayeredSurface.Context layered = layeredContext(chunk.getPos());
        if (layered != null) {
            LayeredSurface.apply(chunk, layered);
        }

        // Carve water / waterfalls / arch bridges first, then routes (which
        // now skip river columns), all before feature & vegetation placement.
        applyRivers(chunk);
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
    public CompletableFuture<Chunk> populateNoise(java.util.concurrent.Executor executor, Blender blender,
            NoiseConfig noiseConfig, StructureAccessor structureAccessor, Chunk chunk) {
        patchNoiseRouterOnce(noiseConfig);
        return delegate.populateNoise(executor, blender, noiseConfig, structureAccessor, chunk);
    }

    @Override
    public StructurePlacementCalculator createStructurePlacementCalculator(
            net.minecraft.registry.RegistryWrapper<StructureSet> structureSetRegistry,
            NoiseConfig noiseConfig,
            long seed
    ) {
        // Disable random village generation for the FMG overworld by removing the
        // villages structure set from the placement calculator.
        //
        // Burg-driven village placement is handled via a custom structure.
        var villagesKey = net.minecraft.registry.RegistryKey.of(
                RegistryKeys.STRUCTURE_SET,
                new Identifier("minecraft", "villages")
        );
        var filtered = FilteringRegistryWrapper.excludingKey(structureSetRegistry, villagesKey);
        this.worldSeed = seed;
        this.worldSeedKnown = true;
        return super.createStructurePlacementCalculator(filtered, noiseConfig, seed);
    }

    private synchronized void patchNoiseRouterOnce(NoiseConfig noiseConfig) {
        if (cavesApplied) {
            return;
        }
        NoiseConfigAccessor accessor = (NoiseConfigAccessor) (Object) noiseConfig;

        // Preferred path: a fully resolved router built from the FMG noise
        // settings -- initialDensity = the height step (surface stays locked to
        // the map), finalDensity = the step with vanilla 1.18+ noise caves
        // carved in. NoiseConfig.create binds all the noise samplers the way the
        // game does.
        if (worldSeedKnown && FmgNoiseCaves.ready()) {
            NoiseRouter resolved = FmgNoiseCaves.buildResolvedRouter(
                    this.delegateSettings.value(), this.shape, this.heightCap, mapInfo.seaLevel(), this.worldSeed);
            if (resolved != null) {
                accessor.fantasymapgenerator$setNoiseRouter(resolved);
                noiseRouterPatched.set(true);
                cavesApplied = true;
                return;
            }
        }

        // Fallback (registries/seed not available yet -- at worst the first few
        // spawn chunks): bare height step for both densities, no noise caves.
        // Not latched, so a later call installs the real router.
        if (noiseRouterPatched.get()) {
            return;
        }
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
        accessor.fantasymapgenerator$setNoiseRouter(patched);
        noiseRouterPatched.set(true);
    }

    @Override
    public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        text.add("FMG map: " + mapInfo.fmgExport());

        // Debug info to validate that our height cap is being used.
        int height = columnCache.get().getHeight(pos.getX(), pos.getZ());
        double cap = heightCap.sample(new DensityFunction.UnblendedNoisePos(pos.getX(), pos.getY(), pos.getZ()));
        text.add("FMG height@XZ: " + height + " cap@pos: " + String.format(java.util.Locale.ROOT, "%.2f", cap));

        MapImageData cached = MapImageCache.get(mapInfo);
        FMGMapData map = cached.fmgData();
        FMGCell cell = FMGHeightSampler.findCellAtWorldPos(map, pos.getX(), pos.getZ());
        if (cell == null) {
            text.add("FMG region: (unknown)");
            text.add("FMG province: (unknown)");
            return;
        }

        int stateId = cell.getState();
        Integer provinceId = map.getProvinceIdForCell(cell.getI());

        FMGState state = map.getState(stateId);
        FMGProvince province = provinceId != null ? map.getProvince(provinceId) : null;

        String stateName = state != null && state.getName() != null && !state.getName().isBlank()
                ? state.getName()
                : "Unknown State";
        String provinceName = province != null && province.getName() != null && !province.getName().isBlank()
                ? province.getName()
                : "Unclaimed Province";

        text.add("FMG region: " + stateName);
        text.add("FMG province: " + provinceName);
    }

    private static final int[][] DIRS4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Water, waterfalls and arch bridges for river columns. Runs after the
     * vanilla surface builder and before {@link #applyRoutes} / feature gen.
     */
    private void applyRivers(Chunk chunk) {
        ChunkColumnCache cache = columnCache.get();
        ChunkPos cp = chunk.getPos();
        int minY = getMinimumY();
        int maxY = getWorldHeight();
        BlockState water = Blocks.WATER.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        com.example.profile.FmgContentProfile profile = MapImageCache.get(mapInfo).profile();
        BlockState brick = profile.block(profile.blocks.bridge);
        LayeredSurface.Context layered = layeredContext(cp);
        var rcfg = com.example.heightmap.HeightmapPostConfig.get().rivers;
        BlockPos.Mutable m = new BlockPos.Mutable();

        // Effective per-column water surface: from the carve, then a relaxation
        // pass that fills 1-wide dry pockets a scalloped channel edge leaves
        // between the 4-connected river columns (grass/sugar-cane bait otherwise).
        int[] effWater = new int[256];
        int[] effBed = new int[256];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int i = (lx << 4) | lz;
                effWater[i] = cache.getRiverWaterY(cp.getStartX() + lx, cp.getStartZ() + lz);
                effBed[i] = cache.getRiverBedY(cp.getStartX() + lx, cp.getStartZ() + lz);
            }
        }
        for (int pass = 0; pass < 3; pass++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int i = (lx << 4) | lz;
                    if (effWater[i] >= 0) continue;
                    int wx = cp.getStartX() + lx;
                    int wz = cp.getStartZ() + lz;
                    int nBest = -1, wet = 0;
                    for (int[] dir : DIRS4) {
                        int nlx = lx + dir[0], nlz = lz + dir[1];
                        int nWater = (nlx >= 0 && nlx < 16 && nlz >= 0 && nlz < 16)
                                ? effWater[(nlx << 4) | nlz]
                                : cache.getRiverWaterY(wx + dir[0], wz + dir[1]);
                        if (nWater >= 0) {
                            wet++;
                            nBest = (nBest < 0) ? nWater : Math.min(nBest, nWater);
                        }
                    }
                    int top = cache.getHeight(wx, wz) - 1;
                    if (wet >= 2 && nBest >= 0 && top < nBest) {
                        effWater[i] = nBest;
                        effBed[i] = Math.min(top, nBest - 1);
                    }
                }
            }
        }

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cp.getStartX() + lx;
                int wz = cp.getStartZ() + lz;

                int deckY = cache.getBridgeDeckY(wx, wz);
                int waterY = effWater[(lx << 4) | lz];

                if (waterY >= 0) {
                    int bedY = effBed[(lx << 4) | lz];
                    if (bedY < minY + 1) bedY = minY + 1;
                    if (bedY >= waterY) bedY = waterY - 1;

                    // solid bed: replace whatever the surface builder left, down
                    // a couple blocks, so there's never dirt/grass under the water
                    for (int y = bedY; y >= bedY - 1 && y > minY; y--) {
                        m.set(wx, y, wz);
                        BlockState bed = layered != null
                                ? LayeredSurface.bedBlock(layered, wx, wz, waterY + 1.0 - (bedY + 1.0))
                                : riverBedBlock(profile, chunk, wx, y, wz);
                        chunk.setBlockState(m, bed, false);
                    }

                    // water column
                    for (int y = bedY + 1; y <= waterY && y < maxY - 1; y++) {
                        m.set(wx, y, wz);
                        chunk.setBlockState(m, water, false);
                    }
                    // let the surface water settle/flow on chunk load so it
                    // seals any remaining scallop it borders
                    if (waterY > minY && waterY < maxY - 1) {
                        m.set(wx, waterY, wz);
                        chunk.markBlockForPostProcessing(m.toImmutable());
                    }
                    // clear anything left just above the surface
                    for (int y = waterY + 1; y <= waterY + 4 && y < maxY - 1; y++) {
                        m.set(wx, y, wz);
                        BlockState s = chunk.getBlockState(m);
                        if (!s.isAir() && !s.isOf(Blocks.WATER)) chunk.setBlockState(m, air, false);
                    }
                    // waterfall: a higher neighbour pool spills into this column
                    int cap = waterY + rcfg.maxWaterfallDrop + 2;
                    for (int[] dir : DIRS4) {
                        int nWater = cache.getRiverWaterY(wx + dir[0], wz + dir[1]);
                        if (nWater >= waterY + 2) {
                            for (int y = waterY + 1; y <= Math.min(nWater, cap) && y < maxY - 1; y++) {
                                m.set(wx, y, wz);
                                chunk.setBlockState(m, water, false);
                            }
                        }
                    }
                }

                if (deckY >= 0) {
                    int archY = cache.getBridgeArchY(wx, wz);
                    if (archY < 0 || archY >= deckY) archY = deckY - 1;
                    boolean sideCol = cache.getBridgeSide(wx, wz) == 1;

                    // structural underside (the "bottom", cobblestone)
                    if (waterY >= 0) {
                        for (int y = waterY + 1; y < archY && y < maxY - 1; y++) {
                            m.set(wx, y, wz);
                            if (!chunk.getBlockState(m).isOf(Blocks.WATER)) chunk.setBlockState(m, air, false);
                        }
                        for (int y = Math.max(minY + 1, archY); y < deckY && y < maxY - 1; y++) {
                            m.set(wx, y, wz);
                            chunk.setBlockState(m, brick, false);
                        }
                    } else {
                        int ground = Math.max(minY + 1, cache.getHeight(wx, wz) - 1);
                        if (cache.getBridgeSpan(wx, wz) == 1) {
                            ground = Math.max(ground, archY);                   // open span: beam only
                        }
                        for (int y = ground; y < deckY && y < maxY - 1; y++) {
                            m.set(wx, y, wz);
                            chunk.setBlockState(m, brick, false);
                        }
                    }

                    // walkway floor (the "top", same cobblestone)
                    if (deckY > minY && deckY < maxY - 1) {
                        m.set(wx, deckY, wz);
                        chunk.setBlockState(m, brick, false);
                    }

                    // sides get a parapet one above the walkway; the middle is
                    // kept open (>= 1 block headroom) so you can walk through
                    for (int y = deckY + 1; y <= deckY + 4 && y < maxY - 1; y++) {
                        m.set(wx, y, wz);
                        if (sideCol && y == deckY + 1) {
                            chunk.setBlockState(m, brick, false);
                        } else if (!chunk.getBlockState(m).isAir()) {
                            chunk.setBlockState(m, air, false);
                        }
                    }

                    // road marker on the walkway (above the parapet on side
                    // columns) so bridges stay recognisable as road
                    int markerY = deckY + (sideCol ? 2 : 1);
                    if (markerY < maxY - 1) {
                        m.set(wx, markerY, wz);
                        chunk.setBlockState(m, RoadMarker.state(), false);
                    }
                }
            }
        }
    }

    /** Y at which the altitude ramps read 100 (the map's top); computed once. */
    private volatile double altTop = Double.NaN;

    /**
     * Everything the layered surface needs for this chunk, or null when the
     * world's profile has no surface palettes (vanilla surface rules only).
     */
    private LayeredSurface.Context layeredContext(ChunkPos cp) {
        MapImageData data = MapImageCache.get(mapInfo);
        com.example.profile.FmgContentProfile profile = data.profile();
        if (profile.surfacePalettes == null) {
            return null;
        }
        com.example.surface.SurfaceSet set = com.example.surface.SurfaceSet.get(profile.surfacePalettes,
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
        if (set == null) {
            return null;
        }
        FMGMapData map = data.fmgData();
        com.example.terrain.TerrainRaster terrain = map == null ? null : map.getTerrain();
        double top = altTop;
        if (Double.isNaN(top)) {
            top = terrain != null ? terrain.maxHeight() : mapInfo.maxY() - 1;
            altTop = top;
        }
        com.example.fmg.FMGRiverNetwork rivers = com.example.fmg.FMGRiverNetwork.get(
                mapInfo.fmgExport(), map, mapInfo.minY(), mapInfo.maxY(), mapInfo.seaLevel());
        ChunkColumnCache cache = columnCache.get();
        LayeredSurface.Columns cols = new LayeredSurface.Columns() {
            @Override public int height(int x, int z) { return cache.getHeight(x, z); }
            @Override public double exact(int x, int z) { return cache.getExact(x, z); }
            @Override public boolean skip(int x, int z) { return cache.isBuiltColumn(x, z); }
            @Override public int freshWaterY(int x, int z) { return cache.getRiverWaterY(x, z); }
        };
        LayeredSurface.BiomeIds biomes = getBiomeSource() instanceof ColorMapBiomeSource cms
                ? cms::landBiomeId
                : (x, z) -> null;
        int seed = mapInfo.fmgExport() == null ? 0 : mapInfo.fmgExport().hashCode();
        return new LayeredSurface.Context(set, cols, biomes, data::sampleHeightExactY,
                rivers == null ? List.of() : rivers.collectNear(cp.x, cp.z), terrain,
                mapInfo.seaLevel(), getMinimumY(), getMinimumY() + getWorldHeight(), top, seed);
    }

    private BlockState riverBedBlock(com.example.profile.FmgContentProfile profile,
                                     Chunk chunk, int x, int y, int z) {
        int biomeY = y >> 2;
        RegistryEntry<Biome> biome = chunk.getBiomeForNoiseGen(x >> 2, biomeY, z >> 2);
        boolean desert = isBiome(biome, BiomeKeys.DESERT) || biome.isIn(BiomeTags.IS_BADLANDS);
        List<com.example.profile.FmgContentProfile.Weighted> palette =
                profile.blocks.riverBed.get(desert ? "desert" : "default");
        if (palette == null) {
            palette = profile.blocks.riverBed.get("default");
        }
        // coherent noise rather than a per-block hash, so the bed reads as shoals
        // and gravel runs instead of speckle
        double scale = 1.0 / Math.max(1.0, profile.blocks.riverBedPatchScale);
        double roll = com.example.heightmap.HeightPostProcess.valueNoise(x * scale, z * scale, 0x5A4DL);
        return profile.pickBlockByRoll(palette, roll);
    }

    private void applyRoutes(Chunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int minY = getMinimumY();
        int maxY = getWorldHeight();
        int seaLevel = mapInfo.seaLevel();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        ChunkColumnCache cache = columnCache.get();
        com.example.profile.FmgContentProfile profile = MapImageCache.get(mapInfo).profile();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkPos.getStartX() + localX;
                int worldZ = chunkPos.getStartZ() + localZ;
                if (cache.getRiverWaterY(worldX, worldZ) >= 0 || cache.getBridgeDeckY(worldX, worldZ) >= 0) {
                    continue;
                }
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

                BlockState pathBlock = choosePathBlock(profile, routeKind, isDesert, isSnowy, worldX, worldZ);
                if (pathBlock == null) {
                    continue;
                }

                mutable.set(worldX, surfaceY, worldZ);
                chunk.setBlockState(mutable, pathBlock, false);

                // Mark every generated road column with the invisible road
                // marker (see RoadMarker): it keeps snow off the path and is the
                // signal the town sampler uses to recognise generated roads.
                mutable.set(worldX, surfaceY + 1, worldZ);
                BlockState above = chunk.getBlockState(mutable);
                if (above.isAir() || above.isOf(Blocks.SNOW)) {
                    chunk.setBlockState(mutable, RoadMarker.state(), false);
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

        int getRiverWaterY(int worldX, int worldZ) {
            return getGrid(worldX, worldZ).riverWaterY[idx(worldX, worldZ)];
        }

        /** Continuous surface height (the integer height where no layered surface is built). */
        double getExact(int worldX, int worldZ) {
            ChunkGrid grid = getGrid(worldX, worldZ);
            int i = idx(worldX, worldZ);
            return grid.exact != null ? grid.exact[i] : grid.heights[i];
        }

        /** Road, bridge or river column: the layered surface leaves it to the generator. */
        boolean isBuiltColumn(int worldX, int worldZ) {
            ChunkGrid grid = getGrid(worldX, worldZ);
            int i = idx(worldX, worldZ);
            return grid.routes[i] != FMGRouteSampler.RouteKind.NONE
                    || grid.bridgeDeckY[i] >= 0 || grid.riverWaterY[i] >= 0;
        }

        int getRiverBedY(int worldX, int worldZ) {
            return getGrid(worldX, worldZ).riverBedY[idx(worldX, worldZ)];
        }

        int getBridgeDeckY(int worldX, int worldZ) {
            return getGrid(worldX, worldZ).bridgeDeckY[idx(worldX, worldZ)];
        }

        int getBridgeArchY(int worldX, int worldZ) {
            return getGrid(worldX, worldZ).bridgeArchY[idx(worldX, worldZ)];
        }

        int getBridgeSpan(int worldX, int worldZ) {
            return getGrid(worldX, worldZ).bridgeSpan[idx(worldX, worldZ)];
        }

        int getBridgeSide(int worldX, int worldZ) {
            return getGrid(worldX, worldZ).bridgeSide[idx(worldX, worldZ)];
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

            // Route shaping runs on a grid padded past the chunk so the
            // cross-section + walkability relaxation is fully resolved before we
            // copy the centre 16x16. It only needs to cover the widest route
            // radius (~8) plus the walkability propagation reach (8 passes); 20
            // is 2x that. (An old burg pre-flatten pass wanted 64 -- it's gone.)
            final int pad = ROUTE_PAD;
            final int size = 16 + pad * 2;
            final int startX = (chunkX << 4) - pad;
            final int startZ = (chunkZ << 4) - pad;

            int[] baseHeights = new int[size * size];
            int[] shapedHeights = new int[size * size];
            // continuous twin of the heights, for the layered surface only
            boolean layered = data.profile().surfacePalettes != null;
            double[] exactPad = layered ? new double[size * size] : null;

            // These arrays store the nearest spline sample for each cell (if any),
            // letting us treat the road network as a 1D chain (along the spline)
            // plus a radial (2D) gaussian cross-section.
            int[] nearestRouteId = new int[size * size];
            int[] nearestSampleIndex = new int[size * size];
            float[] nearestSampleT = new float[size * size];
            double[] nearestDistSq = new double[size * size];

            // 1) Sample FMG height (padded area), plus the analytic fractal-detail
            // offset (rivers are carved later, in step 5c).
            for (int lx = 0; lx < size; lx++) {
                for (int lz = 0; lz < size; lz++) {
                    int x = startX + lx;
                    int z = startZ + lz;
                    int index = (lx * size) + lz;

                    if (exactPad == null) {
                        baseHeights[index] = (int) Math.round(terrainY(mapInfo, data, x, z));
                    } else {
                        // same integer as terrainY, from one sample
                        double hx = data.sampleHeightExactY(x, z);
                        int hi = (int) Math.round(hx);
                        double det = (map != null && map.getTerrain() != null)
                                ? 0.0 : detailOffset(mapInfo, x, z, hi);
                        baseHeights[index] = (int) Math.round(hi + det);
                        exactPad[index] = hx + det;
                    }
                }
            }

            // 1b) NOTE: We intentionally do NOT pre-flatten around burg/village sites.
            // Villages are instead gated by a flatness check at placement time.

            // 2) Project FMG route splines into this padded grid and compute nearest
            // route segment + interpolation parameter per cell.
            java.util.Arrays.fill(nearestRouteId, -1);
            java.util.Arrays.fill(nearestSampleIndex, -1);
            java.util.Arrays.fill(nearestSampleT, 0.0f);
            java.util.Arrays.fill(nearestDistSq, Double.POSITIVE_INFINITY);

            final java.util.HashMap<Integer, FMGRouteSampler.RouteKind> routeKindById = new java.util.HashMap<>();
            final java.util.HashMap<Integer, RouteProfile> profileById = new java.util.HashMap<>();

            if (map != null && map.getInfo() != null && map.getRoutes() != null && !map.getRoutes().isEmpty()) {
                final int stampRadius = (int) Math.ceil(
                        Math.max(RouteProfile.forKind(FMGRouteSampler.RouteKind.ROAD).maxRadius,
                                RouteProfile.forKind(FMGRouteSampler.RouteKind.TRAIL).maxRadius) + 1.0);

                // Only the route segments whose bucket overlaps this padded grid,
                // not all ~200k in the map.
                java.util.List<com.example.fmg.FMGRouteIndex.Seg> segs = new java.util.ArrayList<>();
                com.example.fmg.FMGRouteIndex.get(mapInfo.fmgExport(), map)
                        .collectSegments(startX, startZ, startX + size - 1, startZ + size - 1, segs);

                for (com.example.fmg.FMGRouteIndex.Seg seg : segs) {
                    RouteProfile profile = profileById.get(seg.routeId);
                    if (profile == null) {
                        profile = RouteProfile.forKind(seg.kind);
                        profileById.put(seg.routeId, profile);
                        routeKindById.put(seg.routeId, seg.kind);
                    }
                    double maxR2 = profile.maxRadius * profile.maxRadius;

                    double ax = seg.ax, az = seg.az, bx = seg.bx, bz = seg.bz;
                    int minLX = (int) Math.floor(Math.min(ax, bx) - stampRadius - startX);
                    int maxLX = (int) Math.ceil(Math.max(ax, bx) + stampRadius - startX);
                    int minLZ = (int) Math.floor(Math.min(az, bz) - stampRadius - startZ);
                    int maxLZ = (int) Math.ceil(Math.max(az, bz) + stampRadius - startZ);
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
                                t = ((px - ax) * vx + (pz - az) * vz) / segLenSq;
                                if (t < 0.0) t = 0.0;
                                else if (t > 1.0) t = 1.0;
                            }
                            double dx = px - (ax + t * vx);
                            double dz = pz - (az + t * vz);
                            double distSq = (dx * dx) + (dz * dz);
                            if (distSq > maxR2) {
                                continue;
                            }
                            int index = (lx * size) + lz;
                            if (distSq < nearestDistSq[index]) {
                                nearestDistSq[index] = distSq;
                                nearestRouteId[index] = seg.routeId;
                                nearestSampleIndex[index] = seg.sampleIndex;
                                nearestSampleT[index] = (float) t;
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

            com.example.fmg.FMGRiverNetwork rivers = com.example.fmg.FMGRiverNetwork.get(
                    mapInfo.fmgExport(), map, mapInfo.minY(), mapInfo.maxY(), seaLevel);
            com.example.heightmap.HeightmapPostConfig.Rivers rcfg =
                    com.example.heightmap.HeightmapPostConfig.get().rivers;

            final java.util.HashMap<Integer, RelaxedRoute> relaxedByRoute = new java.util.HashMap<>();
            if (!sampleRangeByRoute.isEmpty() && map != null && map.getInfo() != null) {
                final double mapW = map.getInfo().getWidth();
                final double mapH = map.getInfo().getHeight();
                final double scale = FMGHeightSampler.sampleScale();
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
                        cached = computeRelaxedRouteFull(mapInfo, data, seaLevel, offsetX, offsetZ, scale, profile,
                                samples, rivers, rcfg);
                        relaxedRouteCache.put(routeId, cached);
                    }

                    relaxedByRoute.put(routeId, cached);
                }
            }

            // Road cells within reach of their route's river crossing: the only place
            // a road may stand above (or span) the river's carved dent -- see step 6.
            // Taken from the route's own chain, so every chunk agrees on it.
            boolean[] approach = new boolean[size * size];
            double dentReach = rivers == null ? 0.0
                    : maxBridgeLift(rcfg) / Math.max(0.05, rcfg.bridgeApproachGrade) + 2.0
                            + rivers.maxValleyRadius() + rivers.valleySmoothK() + 24.0;

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
                    double lift = lerp(chain.lift[localSi], chain.lift[localSi + 1], t);
                    if (dist <= profile.routeBlockRadius
                            && Math.min(chain.crossDist[localSi], chain.crossDist[localSi + 1]) <= dentReach) {
                        approach[index] = true;
                    }
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

                    // Prefer cut. Allow limited fill to avoid big embankments --
                    // except where the chain was lifted to meet a bridge deck.
                    if (target > original) {
                        target = Math.min(target, original + profile.maxFill + lift);
                    }

                    shapedHeights[index] = (int) Math.round(target);
                    if (exactPad != null) {
                        exactPad[index] = target;
                    }
                }
            }

            // 4b) Bridge approaches: every road cell near a bridge is lifted to at
            // least the deck height minus bridgeApproachGrade per block of distance,
            // so whichever road meets a bridge -- the crossing route itself or a
            // village street joining it at the bank -- climbs onto the deck rather
            // than stepping up to it. (The crossing route's own chain is already
            // lifted in computeRelaxedRouteFull; this also covers the others.)
            List<com.example.fmg.FMGRiverNetwork.RiverSample> riverCand =
                    rivers == null ? List.of() : rivers.collectNear(chunkX, chunkZ);
            if (!riverCand.isEmpty() && !relaxedByRoute.isEmpty()) {
                int clearance = Math.max(1, rcfg.bridgeClearance);
                double grade = Math.max(0.05, rcfg.bridgeApproachGrade);
                double maxLift = maxBridgeLift(rcfg);
                double rampReach = maxLift / grade + 2.0;
                java.util.ArrayList<int[]> deckCells = new java.util.ArrayList<>();   // lx, lz, deck height
                for (int lx = 0; lx < size; lx++) {
                    for (int lz = 0; lz < size; lz++) {
                        int index = (lx * size) + lz;
                        RouteProfile profile = profileById.get(nearestRouteId[index]);
                        if (profile == null || nearestDistSq[index] > profile.routeBlockRadius * profile.routeBlockRadius) {
                            continue;
                        }
                        double px = startX + lx, pz = startZ + lz;
                        com.example.fmg.FMGRiverNetwork.RiverSample best = null;
                        double bestD2 = Double.MAX_VALUE;
                        for (com.example.fmg.FMGRiverNetwork.RiverSample rs : riverCand) {
                            double d2 = (px - rs.x) * (px - rs.x) + (pz - rs.z) * (pz - rs.z);
                            if (d2 < bestD2) {
                                bestD2 = d2;
                                best = rs;
                            }
                        }
                        double reach = best == null ? 0 : best.halfWidth + Math.max(1, rcfg.bankWidth) + 1.0;
                        if (best != null && bestD2 <= reach * reach) {
                            deckCells.add(new int[]{lx, lz, best.waterSurfaceY + clearance + 2});
                        }
                    }
                }
                if (!deckCells.isEmpty()) {
                    for (int lx = 0; lx < size; lx++) {
                        for (int lz = 0; lz < size; lz++) {
                            int index = (lx * size) + lz;
                            RouteProfile profile = profileById.get(nearestRouteId[index]);
                            if (profile == null) {
                                continue;
                            }
                            double dist = Math.sqrt(nearestDistSq[index]);
                            if (dist > profile.maxRadius) {
                                continue;
                            }
                            double req = Double.NEGATIVE_INFINITY;
                            for (int[] b : deckCells) {
                                double dd = Math.hypot(lx - b[0], lz - b[1]);
                                if (dd <= rampReach) {
                                    req = Math.max(req, b[2] - grade * dd);
                                }
                            }
                            req = Math.min(req, shapedHeights[index] + maxLift);
                            if (req <= shapedHeights[index]) {
                                continue;
                            }
                            if (dist <= profile.routeBlockRadius) {
                                approach[index] = true;          // e.g. a village street joining at the bridge
                            }
                            double w = dist <= profile.flatHalfWidth ? 1.0
                                    : gaussian(dist - profile.flatHalfWidth, profile.shoulderSigmaBlocks)
                                            * (1.0 - smoothstep(profile.maxRadius - 1.0, profile.maxRadius, dist));
                            int raised = (int) Math.round(shapedHeights[index] + (req - shapedHeights[index]) * w);
                            if (raised > shapedHeights[index]) {
                                shapedHeights[index] = raised;
                            }
                        }
                    }
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
            java.util.Arrays.fill(out.riverWaterY, -1);
            java.util.Arrays.fill(out.riverBedY, -1);
            java.util.Arrays.fill(out.bridgeDeckY, -1);
            java.util.Arrays.fill(out.bridgeArchY, -1);
            java.util.Arrays.fill(out.bridgeSide, -1);
            if (exactPad != null) {
                out.exact = new double[16 * 16];
            }
            // The valley reaches much further than the channel, so every sample
            // that can touch this chunk (riverCand, gathered in 4b) is scanned per column.
            double valleyK = rivers == null ? 0.0 : rivers.valleySmoothK();
            com.example.terrain.TerrainRaster terrain = map == null ? null : map.getTerrain();
            double valleyReach = rivers == null ? 0.0 : rivers.maxValleyRadius();

            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int srcX = localX + pad;
                    int srcZ = localZ + pad;
                    int srcIndex = (srcX * size) + srcZ;
                    int dstIndex = (localX << 4) | localZ;
                    int worldX = (chunkX << 4) + localX;
                    int worldZ = (chunkZ << 4) + localZ;

                    int terrainHeight = shapedHeights[srcIndex];
                    out.heights[dstIndex] = terrainHeight;
                    if (out.exact != null) {
                        // integer-only edits (walkability, bridge ramps) move a
                        // column by a whole block; those columns stay whole blocks
                        double e = exactPad[srcIndex];
                        out.exact[dstIndex] = Math.abs(e - terrainHeight) < 1.0 ? e : terrainHeight;
                    }

                    // route mask
                    FMGRouteSampler.RouteKind routeKind = FMGRouteSampler.RouteKind.NONE;
                    double routeDist = -1, routeRadius = 0;
                    int routeId = nearestRouteId[srcIndex];
                    if (routeId >= 0) {
                        FMGRouteSampler.RouteKind kind = routeKindById.get(routeId);
                        if (kind != null) {
                            RouteProfile profile = profileById.get(routeId);
                            if (profile == null) profile = RouteProfile.forKind(kind);
                            routeDist = Math.sqrt(nearestDistSq[srcIndex]);
                            routeRadius = profile.routeBlockRadius;
                            if (routeDist <= routeRadius) routeKind = kind;
                        }
                    }
                    out.routes[dstIndex] = routeKind;

                    // river carve -- curved channel bed inside the banks, then a
                    // constant-slope V-valley outside it, smooth-blended into the
                    // terrain so the rim is not a crease. Only ever lowers ground.
                    com.example.fmg.FMGRiverNetwork.RiverSample near = null;
                    double nearDistSq = Double.MAX_VALUE;
                    for (com.example.fmg.FMGRiverNetwork.RiverSample s : riverCand) {
                        double ddx = worldX - s.x;
                        double ddz = worldZ - s.z;
                        double d2 = (ddx * ddx) + (ddz * ddz);
                        if (d2 < nearDistSq) {
                            nearDistSq = d2;
                            near = s;
                        }
                    }
                    double nearDist = near == null ? 0 : Math.sqrt(nearDistSq);
                    if (near != null && nearDist <= near.halfWidth + valleyReach + valleyK) {
                        double d = nearDist;
                        int surface = near.waterSurfaceY;

                        double floorTop = com.example.fmg.FMGRiverNetwork.valleyFloorY(near, d);
                        int carvedTop = (int) Math.floor(com.example.fmg.FMGRiverNetwork.smoothMin(
                                terrainHeight - 1.0, floorTop, valleyK));
                        boolean onRoad = routeKind != FMGRouteSampler.RouteKind.NONE;
                        double bridgeHalf = near.halfWidth + Math.max(1, rcfg.bankWidth) + 1.0;
                        boolean dentSpan = false;
                        if (onRoad && d > bridgeHalf && approach[srcIndex]) {
                            // On a bridge approach the road keeps its (lifted) height through
                            // the river's dent instead of dipping into it before the deck.
                            // A shallow dent is filled under the road; a deep one (a gorge)
                            // is spanned by the bridge itself, so the road never stands on
                            // a tall earth wall. Anywhere else the valley is carved as usual.
                            if (carvedTop < terrainHeight - 1 - 2) {
                                dentSpan = true;
                            } else {
                                carvedTop = Math.max(carvedTop, terrainHeight - 1);
                            }
                        }
                        if (carvedTop + 1 < out.heights[dstIndex]) {
                            out.heights[dstIndex] = carvedTop + 1;
                        }
                        if (out.exact != null) {
                            // the same smooth valley, without the floor to a whole block
                            double ec = com.example.fmg.FMGRiverNetwork.smoothMin(
                                    out.exact[dstIndex] - 1.0, floorTop, valleyK) + 1.0;
                            if (ec < out.exact[dstIndex]) out.exact[dstIndex] = ec;
                            if (Math.abs(out.exact[dstIndex] - out.heights[dstIndex]) >= 1.0) {
                                out.exact[dstIndex] = out.heights[dstIndex];
                            }
                        }
                        int bedTop = out.heights[dstIndex] - 1;
                        if (d <= near.halfWidth + rcfg.bankWidth && bedTop < surface) {
                            out.riverWaterY[dstIndex] = surface;
                            out.riverBedY[dstIndex] = bedTop;
                        }

                        // a road that meets the river becomes a cobblestone bridge, not a ford.
                        // The road chain was already lifted to deck height over the water
                        // (computeRelaxedRouteFull), so the deck is simply the road's own
                        // height: the path runs on continuously instead of dropping into
                        // the river's dent, onto the bridge and back up. The bridge itself
                        // only spans the channel and its banks.
                        int clearance = Math.max(1, rcfg.bridgeClearance);
                        if (dentSpan) {
                            int deckY = terrainHeight - 1;
                            out.bridgeDeckY[dstIndex] = deckY;
                            out.bridgeArchY[dstIndex] = deckY - 1;               // beam under the deck
                            // piers every 7 blocks out from the river, solid to the ground
                            out.bridgeSpan[dstIndex] = (byte) (Math.floorMod((int) Math.floor(d), 7) == 0 ? 0 : 1);
                            out.bridgeSide[dstIndex] = (routeDist >= routeRadius - 1.2) ? 1 : 0;
                            out.routes[dstIndex] = FMGRouteSampler.RouteKind.NONE;
                        }
                        if (onRoad && d <= bridgeHalf) {
                            double bu = Math.min(1.0, d / bridgeHalf);
                            int flatDeck = surface + clearance + 1;
                            int deckY = Math.max(flatDeck, terrainHeight - 1);
                            int archY = deckY - 1
                                    - (int) Math.round(rcfg.bridgeArchRise * Math.sqrt(Math.max(0.0, 1.0 - bu * bu)));
                            archY = Math.max(archY, surface + clearance);   // never dam the river
                            out.bridgeDeckY[dstIndex] = deckY;
                            out.bridgeArchY[dstIndex] = Math.min(archY, deckY - 1);
                            // outer ~1.2 block ring of the road width -> the sides/parapets
                            out.bridgeSide[dstIndex] = (routeDist >= routeRadius - 1.2) ? 1 : 0;
                            out.routes[dstIndex] = FMGRouteSampler.RouteKind.NONE;
                        }
                    }

                    // lakes above sea level (blended terrain): fill to the baked lake surface
                    if (terrain != null && out.riverWaterY[dstIndex] < 0 && out.bridgeDeckY[dstIndex] < 0) {
                        int lakeY = terrain.lakeWaterAt(worldX, worldZ);
                        if (lakeY != com.example.terrain.TerrainRaster.NO_WATER && lakeY > seaLevel - 1
                                && out.heights[dstIndex] - 1 < lakeY) {
                            out.riverWaterY[dstIndex] = lakeY;
                            out.riverBedY[dstIndex] = out.heights[dstIndex] - 1;
                            out.routes[dstIndex] = FMGRouteSampler.RouteKind.NONE;
                        }
                    }
                }
            }

            return out;
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

        private static double sampleHeightBilinear(MapInfo mapInfo, MapImageData data, double worldX, double worldZ) {
            int x0 = (int) Math.floor(worldX);
            int z0 = (int) Math.floor(worldZ);
            int x1 = x0 + 1;
            int z1 = z0 + 1;

            double fx = worldX - x0;
            double fz = worldZ - z0;

            double h00 = terrainY(mapInfo, data, x0, z0);
            double h10 = terrainY(mapInfo, data, x1, z0);
            double h01 = terrainY(mapInfo, data, x0, z1);
            double h11 = terrainY(mapInfo, data, x1, z1);

            double hx0 = lerp(h00, h10, fx);
            double hx1 = lerp(h01, h11, fx);
            return lerp(hx0, hx1, fz);
        }

        /** Most a road is ever raised to meet a bridge deck: the deck's own height above the bank. */
        private static double maxBridgeLift(com.example.heightmap.HeightmapPostConfig.Rivers rcfg) {
            return Math.max(1, rcfg.bridgeClearance) + 3.0;
        }

        private static RelaxedRoute computeRelaxedRouteFull(
                MapInfo mapInfo,
                MapImageData data,
                int seaLevel,
                double offsetX,
                double offsetZ,
                double scale,
                RouteProfile profile,
                List<FMGRouteSampler.RouteSample> samples,
                com.example.fmg.FMGRiverNetwork rivers,
                com.example.heightmap.HeightmapPostConfig.Rivers rcfg
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

                double target = sampleHeightBilinear(mapInfo, data, px, pz);

                // int ix = (int) Math.round(px);
                // int iz = (int) Math.round(pz);
                // FMRiverSampler.RiverQuery rq = FMRiverSampler.query(ix, iz);
                // if (rq.hit) {
                //     double carved = FMRiverSampler.carvedHeight(rq, seaLevel);
                //     target = Math.min(target, carved);
                // }

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

            // Bridges: over a river's channel and banks the road must sit at deck
            // height (top solid = surface + clearance + 1). Lift it there and ramp
            // the approaches at bridgeApproachGrade, so the road climbs gently onto
            // the bridge from its own level rather than dipping into the dent first.
            double[] lift = new double[len];
            double[] crossDist = new double[len];
            java.util.Arrays.fill(crossDist, Double.POSITIVE_INFINITY);
            if (rivers != null && !rivers.isEmpty()) {
                int clearance = Math.max(1, rcfg.bridgeClearance);
                double[] need = new double[len];
                boolean any = false;
                for (int j = 0; j < len; j++) {
                    need[j] = Double.NEGATIVE_INFINITY;
                    com.example.fmg.FMGRiverNetwork.Hit hit =
                            rivers.query((int) Math.round(wx[j]), (int) Math.round(wz[j]));
                    if (hit != null) {
                        need[j] = hit.sample.waterSurfaceY + clearance + 2;
                        any = true;
                    }
                }
                if (any) {
                    // The lift is a short approach ramp, never a causeway: at most
                    // maxBridgeLift high and only within its own ramp length of the
                    // crossing. (Unbounded, a ramp next to a mountain stream kept
                    // going down the slope as an ever taller embankment.)
                    double grade = Math.max(0.05, rcfg.bridgeApproachGrade);
                    double maxLift = maxBridgeLift(rcfg);
                    double reach = maxLift / grade + 2.0;
                    double[] lifted = new double[len];
                    double[] toCrossing = new double[len];
                    for (int j = 0; j < len; j++) {
                        lifted[j] = Math.max(h[j], need[j]);
                        toCrossing[j] = need[j] > Double.NEGATIVE_INFINITY ? 0 : Double.POSITIVE_INFINITY;
                    }
                    for (int j = 1; j < len; j++) {
                        double seg = Math.hypot(wx[j] - wx[j - 1], wz[j] - wz[j - 1]);
                        lifted[j] = Math.max(lifted[j], lifted[j - 1] - seg * grade);
                        toCrossing[j] = Math.min(toCrossing[j], toCrossing[j - 1] + seg);
                    }
                    for (int j = len - 2; j >= 0; j--) {
                        double seg = Math.hypot(wx[j] - wx[j + 1], wz[j] - wz[j + 1]);
                        lifted[j] = Math.max(lifted[j], lifted[j + 1] - seg * grade);
                        toCrossing[j] = Math.min(toCrossing[j], toCrossing[j + 1] + seg);
                    }
                    for (int j = 0; j < len; j++) {
                        lift[j] = toCrossing[j] <= reach ? Math.min(lifted[j] - h[j], maxLift) : 0.0;
                        h[j] += lift[j];
                    }
                    crossDist = toCrossing;
                }
            }

            return new RelaxedRoute(0, h, lift, crossDist);
        }

        private static final class RelaxedRoute {
            final int offset;
            final double[] heights;
            /** How far each sample was raised to meet a bridge deck (extra fill allowed there). */
            final double[] lift;
            /** Distance along the route (blocks) to its nearest river crossing, +inf if none. */
            final double[] crossDist;

            RelaxedRoute(int offset, double[] heights, double[] lift, double[] crossDist) {
                this.offset = offset;
                this.heights = heights;
                this.lift = lift;
                this.crossDist = crossDist;
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
            /** Water surface Y per column, or -1 where there is no river. */
            final int[] riverWaterY = new int[16 * 16];
            /** Carved bed top-solid Y per column (shelves toward the banks), or -1. */
            final int[] riverBedY = new int[16 * 16];
            /** Deck (walkway floor) Y for a bridge where a road meets a river, or -1. */
            final int[] bridgeDeckY = new int[16 * 16];
            /** Bottom Y of the arch ring / structural underside at this column, or -1. */
            final int[] bridgeArchY = new int[16 * 16];
            /** 1 = bridge side/parapet column, 0 = open walkway column, -1 = not a bridge. */
            final int[] bridgeSide = new int[16 * 16];
            /** 1 = dry span over the river's dent (deck + beam only, open below); 0 = solid to the ground. */
            final byte[] bridgeSpan = new byte[16 * 16];
            /**
             * Continuous surface height per column, within one block of
             * {@link #heights}; only built for a layered-surface profile, else null.
             */
            double[] exact;
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

    /**
     * Road/trail surface from the world's content profile. The palette is keyed
     * by terrain flavour ({@code desert} / {@code snowy} / {@code default}), so a
     * Conquest profile can swap in its own materials without touching this code.
     */
    private static BlockState choosePathBlock(
            com.example.profile.FmgContentProfile profile,
            FMGRouteSampler.RouteKind kind,
            boolean isDesert,
            boolean isSnowy,
            int worldX,
            int worldZ
    ) {
        String flavour = isDesert ? "desert" : (isSnowy ? "snowy" : "default");
        com.example.profile.FmgContentProfile.RoadPalette palette = profile.blocks.roads.get(flavour);
        if (palette == null) {
            palette = profile.blocks.roads.get("default");
        }
        if (palette == null) {
            return null;
        }

        List<com.example.profile.FmgContentProfile.Weighted> entries;
        if (kind == FMGRouteSampler.RouteKind.TRAIL) {
            entries = palette.trail;
        } else if (kind == FMGRouteSampler.RouteKind.ROAD) {
            entries = palette.road;
        } else {
            return null;
        }
        if (entries == null || entries.isEmpty()) {
            return null;
        }

        // Deterministic pseudo-random based on world coordinates so
        // routes look varied but stable between runs.
        long seed = (long) worldX * 341873128712L ^ (long) worldZ * 132897987541L;
        seed ^= (seed >>> 13);
        seed *= 0x5DEECE66DL;
        return profile.pickBlock(entries, seed);
    }

    private static boolean isBiome(RegistryEntry<Biome> biome, net.minecraft.registry.RegistryKey<Biome> key) {
        return biome.getKey().map(k -> k.equals(key)).orElse(false);
    }
}
