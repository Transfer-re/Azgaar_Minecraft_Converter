package com.example.towns;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.worldgen.RoadMarker;
import com.example.towns.CustomSettlementGenerator.BoolGrid;
import com.example.towns.CustomSettlementGenerator.IntGrid;
import com.example.towns.CustomSettlementGenerator.PlacedStructure;
import com.example.towns.CustomSettlementGenerator.PrebuiltStructure;
import com.example.towns.CustomSettlementGenerator.Result;
import com.example.towns.CustomSettlementGenerator.Town;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Debug bridge between the world and {@link CustomSettlementGenerator}: samples a
 * square of the live world around a point, turns it into the generator's terrain
 * grid (road-marker columns -> MAIN_PATH, water -> OCEAN/RIVER), runs the settlement
 * generator over it, and paints the resulting layout as a one-block-thick sheet of
 * coloured wool/concrete draped over the terrain surface.
 *
 * Nothing existing is overwritten: every marker goes at the first free y of its
 * column, so the paint sits ON TOP of the ground and {@link #clear} can lift it
 * back off again.
 */
public final class SettlementDebugPainter {

    private SettlementDebugPainter() {
    }

    public static final int MIN_SAMPLE_RADIUS = 16;
    public static final int MAX_SAMPLE_RADIUS = 512;
    /** Above this, the command warns that it is going to stall the server for a while. */
    public static final int LARGE_SAMPLE_RADIUS = 224;

    // --- auto sample-radius model -------------------------------------
    // The sampled square only has to be big enough to contain the finished
    // settlement plus its farm ring. The town's cost-distance growth reaches
    // ~townRadius in open terrain and further along roads; the farm ring adds
    // ~farmDepth beyond the wall (again further along roads). These factors
    // cover the common "roughly round town, roads leaving it" case -- a town
    // sitting on a dead-straight road running through its centre can still
    // spill past the edge, so `pad=N` / `sample=N` overrides exist.
    private static final double TOWN_STRETCH = 1.4;
    private static final double FARM_STRETCH = 1.4;
    private static final int WALL_PAD = 8;   // organic footprint expand + smoothing slack
    private static final int EDGE_MARGIN = 6;

    /** Final sampled half-width for these options: explicit override if set, else derived, always clamped. */
    public static int effectiveSampleRadius(Options o) {
        int raw = (o.sampleRadius != null) ? o.sampleRadius : autoSampleRadius(o);
        return Math.max(MIN_SAMPLE_RADIUS, Math.min(MAX_SAMPLE_RADIUS, raw));
    }

    /** Half-width needed to hold the settlement + farm ring implied by the options (before clamping). */
    public static int autoSampleRadius(Options o) {
        double reach = Math.hypot(o.centerOffsetX, o.centerOffsetZ) + o.townRadius * TOWN_STRETCH;
        for (double[] t : o.extraTowns) {
            reach = Math.max(reach, Math.hypot(t[0], t[1]) + t[2] * TOWN_STRETCH);
        }
        double farm = (o.wall && o.farms) ? o.farmDepth * FARM_STRETCH : 0.0;
        return (int) Math.ceil(reach + WALL_PAD + farm + EDGE_MARGIN) + o.pad;
    }

    private static final int[][] DIRS4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static final int SET_BLOCK_FLAGS =
            Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;

    // ===================================================================
    // palette: one distinct block per generator output type
    // ===================================================================
    private static final int PALETTE_SIZE = 27;
    private static final Block[] PALETTE = new Block[PALETTE_SIZE];
    private static final String[] LABELS = new String[PALETTE_SIZE];
    private static final Set<Block> PALETTE_BLOCKS = new LinkedHashSet<>();

    private static void paint(int outType, Block block, String label) {
        PALETTE[outType] = block;
        LABELS[outType] = label;
        if (block != null) PALETTE_BLOCKS.add(block);
    }

    static {
        paint(CustomSettlementGenerator.OUT_TERRAIN, null, "untouched terrain");
        paint(CustomSettlementGenerator.OUT_OCEAN, Blocks.BLUE_WOOL, "ocean");
        paint(CustomSettlementGenerator.OUT_RIVER, Blocks.LIGHT_BLUE_WOOL, "river");
        paint(CustomSettlementGenerator.OUT_MAIN_PATH, Blocks.BROWN_WOOL, "main path (detected road)");
        paint(CustomSettlementGenerator.OUT_CONNECTOR_PATH, Blocks.LIGHT_GRAY_WOOL, "connector path");
        paint(CustomSettlementGenerator.OUT_BUILDING_WALL, Blocks.WHITE_WOOL, "house wall");
        paint(CustomSettlementGenerator.OUT_BUILDING_FLOOR, Blocks.GRAY_WOOL, "house floor");
        paint(CustomSettlementGenerator.OUT_DOOR, Blocks.YELLOW_WOOL, "door");
        paint(CustomSettlementGenerator.OUT_CITY_WALL, Blocks.BLACK_WOOL, "city wall");
        paint(CustomSettlementGenerator.OUT_GATE, Blocks.ORANGE_WOOL, "gate");
        paint(CustomSettlementGenerator.OUT_CITADEL_WALL, Blocks.PURPLE_WOOL, "citadel wall");
        paint(CustomSettlementGenerator.OUT_CITADEL_FLOOR, Blocks.MAGENTA_WOOL, "citadel floor");
        paint(CustomSettlementGenerator.OUT_FORT_WALL, Blocks.RED_WOOL, "fort wall");
        paint(CustomSettlementGenerator.OUT_FORT_FLOOR, Blocks.PINK_WOOL, "fort floor");
        paint(CustomSettlementGenerator.OUT_FARM_FENCE, Blocks.LIME_CONCRETE, "farm fence");
        paint(CustomSettlementGenerator.OUT_FARM_FIELD, Blocks.GREEN_WOOL, "farm field");
        paint(CustomSettlementGenerator.OUT_TOWER, Blocks.BLACK_CONCRETE, "tower wall");
        paint(CustomSettlementGenerator.OUT_TOWER_FLOOR, Blocks.GRAY_CONCRETE, "tower floor");
        paint(CustomSettlementGenerator.OUT_MARKET_WALL, Blocks.ORANGE_CONCRETE, "market wall");
        paint(CustomSettlementGenerator.OUT_MARKET_FLOOR, Blocks.YELLOW_CONCRETE, "market floor");
        paint(CustomSettlementGenerator.OUT_CHURCH_WALL, Blocks.WHITE_CONCRETE, "church wall");
        paint(CustomSettlementGenerator.OUT_CHURCH_FLOOR, Blocks.LIGHT_BLUE_CONCRETE, "church floor");
        paint(CustomSettlementGenerator.OUT_BRIDGE, Blocks.BROWN_CONCRETE, "bridge");
        paint(CustomSettlementGenerator.OUT_DOCK, Blocks.CYAN_WOOL, "dock");
        paint(CustomSettlementGenerator.OUT_PREBUILT_WALL, Blocks.PURPLE_CONCRETE, "prebuilt structure wall");
        paint(CustomSettlementGenerator.OUT_PREBUILT_FLOOR, Blocks.MAGENTA_CONCRETE, "prebuilt structure floor");
        paint(CustomSettlementGenerator.OUT_FARM_PATH, Blocks.LIME_WOOL, "farm path");
    }

    public static List<String> legend() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < PALETTE_SIZE; i++) {
            if (PALETTE[i] == null) continue;
            lines.add(blockName(PALETTE[i]) + " = " + LABELS[i]);
        }
        return lines;
    }

    private static String blockName(Block block) {
        return Registries.BLOCK.getId(block).getPath();
    }

    // ===================================================================
    // options
    // ===================================================================

    /**
     * Everything the command can pass through. Anything not named in the
     * command's option string keeps the default below.
     */
    public static final class Options {
        /**
         * Half-width of the world square that gets sampled, in blocks (= grid
         * cells). null = derive it from townRadius + farm ring (the usual case);
         * set via {@code sample=N} to force a size.
         */
        public Integer sampleRadius = null;
        /** Blocks of slack added to the derived sample radius ({@code pad=N}). */
        public int pad = 0;
        /** Cost-distance growth radius of the settlement itself. */
        public double townRadius = 45.0;
        /** Town centre, as a block offset from the sample centre (grid centre = 0,0). */
        public int centerOffsetX = 0;
        public int centerOffsetZ = 0;

        public boolean wall = true;
        public boolean citadel = true;
        public boolean farms = true;
        public boolean docks = true;
        public boolean externalForts = true;

        public int farmDepth = 30;
        public int farmFieldSpacing = 22;
        public int farmPathThickness = 4;
        public int predrawnRoadWidth = 3;
        public int maxLandmarks = 3;

        /** null = fresh random layout every run. */
        public Long seed = null;

        /** Water is loud and huge; off by default, but the mask is always built. */
        public boolean paintWater = false;
        /** Wipe the previous paint job in this dimension before painting a new one. */
        public boolean clearPrevious = true;
        /** Water bodies narrower than this (in cells) are classified RIVER, wider ones OCEAN. */
        public int riverWidth = 8;

        /** Extra settlement seeds: {offsetX, offsetZ, radius}. */
        public final List<double[]> extraTowns = new ArrayList<>();
        public List<PrebuiltStructure> prebuilt = defaultPrebuilt();

        /**
         * Place real {@link TownStructureCatalog} NBT buildings (matched by
         * footprint + door side, biome-specific with a "plains" fallback) over
         * ordinary houses wherever a matching one exists. Off = every building
         * stays the plain wool outline.
         */
        public boolean houses = true;
    }

    /** Placeholder buildings until real structure templates are wired in. */
    public static List<PrebuiltStructure> defaultPrebuilt() {
        List<PrebuiltStructure> list = new ArrayList<>();
        list.add(new PrebuiltStructure("inn", 7, 9));
        list.add(new PrebuiltStructure("barracks", 11, 13));
        list.add(new PrebuiltStructure("granary", 9, 9));
        list.add(new PrebuiltStructure("manor", 13, 11));
        return list;
    }

    public static final String OPTION_HELP =
            "cx cz wall citadel farms docks forts farmDepth farmSpacing farmPathWidth roadWidth "
            + "landmarks seed water clear riverWidth sample=N pad=N town=dx,dz,r prebuilt=name:WxH,... "
            + "houses=true|false";

    /**
     * Parses "key=value key=value ..." (also accepts bare "key" as "key=true").
     *
     * @throws IllegalArgumentException with a player-readable message on anything unparseable
     */
    public static void parseOptions(Options o, String raw) {
        if (raw == null || raw.isBlank()) return;
        boolean prebuiltReplaced = false;
        for (String token : raw.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            int eq = token.indexOf('=');
            String key = (eq < 0 ? token : token.substring(0, eq)).toLowerCase(Locale.ROOT);
            String value = (eq < 0 ? "true" : token.substring(eq + 1));
            switch (key) {
                case "cx" -> o.centerOffsetX = parseInt(key, value);
                case "cz", "cy" -> o.centerOffsetZ = parseInt(key, value);
                case "radius", "townradius" -> o.townRadius = parseDouble(key, value);
                case "wall" -> o.wall = parseBool(key, value);
                case "citadel" -> o.citadel = parseBool(key, value);
                case "farms" -> o.farms = parseBool(key, value);
                case "docks" -> o.docks = parseBool(key, value);
                case "forts", "externalforts" -> o.externalForts = parseBool(key, value);
                case "farmdepth" -> o.farmDepth = parseInt(key, value);
                case "farmspacing", "farmfieldspacing" -> o.farmFieldSpacing = parseInt(key, value);
                case "farmpathwidth", "farmpaththickness" -> o.farmPathThickness = parseInt(key, value);
                case "roadwidth", "predrawnroadwidth" -> o.predrawnRoadWidth = parseInt(key, value);
                case "landmarks", "maxlandmarks" -> o.maxLandmarks = parseInt(key, value);
                case "riverwidth" -> o.riverWidth = Math.max(1, parseInt(key, value));
                case "sample", "sampleradius" -> o.sampleRadius =
                        "auto".equalsIgnoreCase(value) ? null : parseInt(key, value);
                case "pad" -> o.pad = parseInt(key, value);
                case "water", "paintwater" -> o.paintWater = parseBool(key, value);
                case "clear", "clearprevious" -> o.clearPrevious = parseBool(key, value);
                case "seed" -> o.seed = "random".equalsIgnoreCase(value) ? null : parseLong(key, value);
                case "town" -> o.extraTowns.add(parseTown(value));
                case "prebuilt", "structures" -> {
                    if (!prebuiltReplaced) {
                        o.prebuilt = new ArrayList<>();
                        prebuiltReplaced = true;
                    }
                    o.prebuilt.addAll(parsePrebuilt(value));
                }
                case "houses" -> o.houses = parseBool(key, value);
                default -> throw new IllegalArgumentException(
                        "unknown option '" + key + "'. known options: " + OPTION_HELP);
            }
        }
    }

    private static int parseInt(String key, String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " expects a whole number, got '" + v + "'");
        }
    }

    private static long parseLong(String key, String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " expects a whole number, got '" + v + "'");
        }
    }

    private static double parseDouble(String key, String v) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " expects a number, got '" + v + "'");
        }
    }

    private static boolean parseBool(String key, String v) {
        if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes")) return true;
        if (v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no")) return false;
        throw new IllegalArgumentException(key + " expects true/false, got '" + v + "'");
    }

    /** "dx,dz,radius" -> extra settlement seed. */
    private static double[] parseTown(String v) {
        String[] parts = v.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("town expects dx,dz,radius (e.g. town=40,-20,25), got '" + v + "'");
        }
        return new double[]{parseDouble("town.dx", parts[0]), parseDouble("town.dz", parts[1]),
                parseDouble("town.radius", parts[2])};
    }

    /** "none" or "inn:7x9,barracks:11x13". */
    private static List<PrebuiltStructure> parsePrebuilt(String v) {
        List<PrebuiltStructure> list = new ArrayList<>();
        if (v.equalsIgnoreCase("none") || v.isBlank()) return list;
        if (v.equalsIgnoreCase("default")) return defaultPrebuilt();
        for (String entry : v.split(",")) {
            if (entry.isBlank()) continue;
            String name = entry;
            String size = entry;
            int colon = entry.indexOf(':');
            if (colon >= 0) {
                name = entry.substring(0, colon);
                size = entry.substring(colon + 1);
            }
            String[] wh = size.toLowerCase(Locale.ROOT).split("x");
            if (wh.length != 2) {
                throw new IllegalArgumentException(
                        "prebuilt expects name:WxH entries (e.g. prebuilt=inn:7x9,keep:15x15), got '" + entry + "'");
            }
            list.add(new PrebuiltStructure(name, parseInt("prebuilt.w", wh[0]), parseInt("prebuilt.h", wh[1])));
        }
        return list;
    }

    // ===================================================================
    // painted-region bookkeeping (so a run can be undone)
    // ===================================================================

    public record PaintedRegion(int minX, int minZ, int maxX, int maxZ, long[] blocks) {
    }

    private static final Map<RegistryKey<World>, PaintedRegion> LAST_PAINTED = new HashMap<>();

    public static PaintedRegion lastPainted(ServerWorld world) {
        return LAST_PAINTED.get(world.getRegistryKey());
    }

    /** Removes every marker block from the last paint job in this dimension. Returns blocks removed, or -1 if none. */
    public static int clear(ServerWorld world) {
        PaintedRegion region = LAST_PAINTED.remove(world.getRegistryKey());
        if (region == null) return -1;
        int removed = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (long packed : region.blocks()) {
            pos.set(BlockPos.unpackLongX(packed), BlockPos.unpackLongY(packed), BlockPos.unpackLongZ(packed));
            BlockState state = world.getBlockState(pos);
            if (!PALETTE_BLOCKS.contains(state.getBlock())) continue;
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), SET_BLOCK_FLAGS);
            removed++;
        }
        return removed;
    }

    // ===================================================================
    // sample -> generate -> paint
    // ===================================================================

    public record Report(
            int sampleRadius,
            int gridSize,
            int centerX,
            int centerZ,
            int pathCells,
            int oceanCells,
            int riverCells,
            int buildings,
            int towers,
            int gates,
            int docks,
            int painted,
            int cleared,
            long seed,
            long millis,
            List<String> placedStructures,
            List<String> unplacedStructures,
            int housesPlaced
    ) {
    }

    public static Report run(ServerWorld world, BlockPos center, Options o) {
        long startedAt = System.currentTimeMillis();
        int r = effectiveSampleRadius(o);
        int size = 2 * r + 1;
        int originX = center.getX() - r;
        int originZ = center.getZ() - r;

        // lift the previous paint job off first -- marker blocks sit on the
        // surface, so leaving them there would make the next sample read them
        // as terrain and stack a second sheet on top of the first
        int cleared = 0;
        if (o.clearPrevious && LAST_PAINTED.containsKey(world.getRegistryKey())) {
            cleared = Math.max(0, clear(world));
        }

        IntGrid terrain = new IntGrid(size, size);
        BoolGrid waterMask = new BoolGrid(size, size);
        int[] paintY = new int[size * size];

        int pathCells = sampleTerrain(world, originX, originZ, size, o, terrain, waterMask, paintY);
        int[] waterCounts = classifyWater(terrain, waterMask, o.riverWidth);

        List<Town> towns = new ArrayList<>();
        towns.add(new Town(r + o.centerOffsetX, r + o.centerOffsetZ, o.townRadius));
        for (double[] extra : o.extraTowns) {
            towns.add(new Town(r + (int) extra[0], r + (int) extra[1], extra[2]));
        }

        long seed = (o.seed != null) ? o.seed : world.getRandom().nextLong();
        Result result = CustomSettlementGenerator.generateSettlement(
                terrain, towns,
                o.wall, o.citadel, o.farms, o.docks, o.externalForts,
                o.farmDepth, o.farmFieldSpacing, o.farmPathThickness, o.predrawnRoadWidth,
                seed, o.maxLandmarks,
                (o.prebuilt == null || o.prebuilt.isEmpty()) ? null : o.prebuilt);

        long[] painted = paintResult(world, originX, originZ, size, o, result, paintY);
        LAST_PAINTED.put(world.getRegistryKey(),
                new PaintedRegion(originX, originZ, originX + size - 1, originZ + size - 1, painted));

        int housesPlaced = o.houses ? placeHouses(world, center, originX, originZ, size, result, paintY) : 0;

        List<String> placed = new ArrayList<>();
        for (PlacedStructure ps : result.placedStructures) {
            placed.add(ps.name + " @ " + (originX + ps.rect.x()) + "," + (originZ + ps.rect.y()));
        }

        return new Report(
                r, size, center.getX(), center.getZ(),
                pathCells, waterCounts[0], waterCounts[1],
                result.buildings != null ? result.buildings.size() : 0,
                result.towers != null ? result.towers.size() : 0,
                result.gates != null ? result.gates.size() : 0,
                result.docks != null ? result.docks.size() : 0,
                painted.length, cleared, seed,
                System.currentTimeMillis() - startedAt,
                placed, new ArrayList<>(result.unplacedStructures), housesPlaced);
    }

    /**
     * Replaces the plain wool outline with a real {@link TownStructureCatalog}
     * NBT building for every house (and prebuilt-slot) that got a road
     * connection (a door) and has a matching structure available. Buildings
     * without a match keep the wool painted by {@link #paintResult}.
     */
    private static int placeHouses(ServerWorld world, BlockPos center, int originX, int originZ, int size,
                                    Result result, int[] paintY) {
        if (result.buildings == null || result.doors == null || result.doors.isEmpty()) {
            return 0;
        }

        String biomeKey = world.getBiome(center).getKey()
                .map(k -> k.getValue().getPath())
                .orElse(TownStructureCatalog.FALLBACK_BIOME);
        // Town buildings come from the world's content profile, so a Conquest
        // world draws from its own structure set rather than the vanilla one.
        String structureRoot = world.getChunkManager().getChunkGenerator()
                instanceof com.example.worldgen.ImageMapChunkGenerator fmg
                ? com.example.profile.FmgProfiles.get(fmg.mapInfo().profile()).townStructureRoot
                : null;
        List<TownStructureCatalog.Entry> catalog =
                TownStructureCatalog.forBiome(world.getServer(), biomeKey, structureRoot);
        if (catalog.isEmpty()) {
            return 0;
        }

        List<CustomSettlementGenerator.Rect> combined = new ArrayList<>(result.buildings);
        for (PlacedStructure p : result.placedStructures) {
            combined.add(p.rect);
        }

        int placedCount = 0;
        for (Map.Entry<Integer, CustomSettlementGenerator.DoorInfo> e : result.doors.entrySet()) {
            int idx = e.getKey();
            if (idx < 0 || idx >= combined.size()) continue;
            CustomSettlementGenerator.Rect rect = combined.get(idx);
            CustomSettlementGenerator.DoorInfo door = e.getValue();

            Direction required = directionFromDoor(rect, door);
            if (required == null) continue;

            TownStructureCatalog.Pick pick =
                    TownStructureCatalog.pickFor(catalog, rect.w(), rect.h(), required, world.getRandom());
            if (pick == null) continue;

            int gridIdx = door.wall.y() * size + door.wall.x();
            if (gridIdx < 0 || gridIdx >= paintY.length) continue;
            int doorGroundY = paintY[gridIdx];

            boolean ok = TownStructurePlacer.place(world, pick.id(), pick.rotation(), pick.mirror(),
                    originX + rect.x(), originZ + rect.y(), doorGroundY, world.getRandom());
            if (ok) placedCount++;
        }
        return placedCount;
    }

    /** Which compass side of {@code rect} the door's wall cell sits on, or {@code null}. */
    private static Direction directionFromDoor(CustomSettlementGenerator.Rect rect,
                                                CustomSettlementGenerator.DoorInfo door) {
        int wx = door.wall.x(), wy = door.wall.y();
        if (wy == rect.y()) return Direction.NORTH;
        if (wy == rect.y() + rect.h() - 1) return Direction.SOUTH;
        if (wx == rect.x()) return Direction.WEST;
        if (wx == rect.x() + rect.w() - 1) return Direction.EAST;
        return null;
    }

    /**
     * Walks the sample square chunk by chunk (loading/generating each one) and fills
     * the generator's terrain grid plus the per-column paint height.
     *
     * @return number of cells detected as road
     */
    private static int sampleTerrain(ServerWorld world, int originX, int originZ, int size, Options o,
                                      IntGrid terrain, BoolGrid waterMask, int[] paintY) {
        int pathCells = 0;
        int minChunkX = originX >> 4;
        int maxChunkX = (originX + size - 1) >> 4;
        int minChunkZ = originZ >> 4;
        int maxChunkZ = (originZ + size - 1) >> 4;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                Chunk chunk = world.getChunk(cx, cz);
                int x0 = Math.max(originX, cx << 4);
                int x1 = Math.min(originX + size - 1, (cx << 4) + 15);
                int z0 = Math.max(originZ, cz << 4);
                int z1 = Math.min(originZ + size - 1, (cz << 4) + 15);

                for (int wz = z0; wz <= z1; wz++) {
                    for (int wx = x0; wx <= x1; wx++) {
                        int gx = wx - originX;
                        int gy = wz - originZ;

                        // OCEAN_FLOOR skips fluids and non-motion-blocking cover
                        // (grass, flowers, snow layers), so this is the real ground
                        // block even under a river or a field of tall grass.
                        // NB: Chunk#sampleHeightmap returns the y OF the topmost
                        // block, so the free column starts one above it.
                        int groundTop = chunk.sampleHeightmap(Heightmap.Type.OCEAN_FLOOR, wx, wz);
                        pos.set(wx, groundTop, wz);
                        BlockState ground = chunk.getBlockState(pos);
                        pos.set(wx, groundTop + 1, wz);
                        BlockState above = chunk.getBlockState(pos);

                        int y = groundTop + 1;
                        int type;
                        if (isWater(above) || isWater(ground)) {
                            type = CustomSettlementGenerator.OCEAN; // refined into OCEAN/RIVER below
                            waterMask.set(gx, gy, true);
                            // float the marker on the water surface rather than drowning it
                            y = Math.max(y, chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, wx, wz) + 1);
                        } else if (RoadMarker.is(above)) {
                            type = CustomSettlementGenerator.MAIN_PATH;
                            pathCells++;
                            // the marker occupies groundTop+1; paint one higher so a
                            // paint/clear cycle never overwrites (and erases) it
                            y = groundTop + 2;
                        } else {
                            type = CustomSettlementGenerator.BUILDABLE;
                        }

                        terrain.set(gx, gy, type);
                        paintY[gy * size + gx] = y;
                    }
                }
            }
        }
        return pathCells;
    }

    private static boolean isWater(BlockState state) {
        if (!state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.WATER)) return true;
        return state.isOf(Blocks.ICE) || state.isOf(Blocks.FROSTED_ICE) || state.isOf(Blocks.BLUE_ICE)
                || state.isOf(Blocks.PACKED_ICE);
    }

    /**
     * The generator treats OCEAN as impassable and RIVER as crossable-but-unbuildable,
     * so a lake or sea has to be told apart from a stream. A single erosion pass does
     * it by width: any water body that still has cells left after being eroded by half
     * the river-width threshold is wide open water, everything else is a river.
     *
     * @return {oceanCells, riverCells}
     */
    private static int[] classifyWater(IntGrid terrain, BoolGrid waterMask, int riverWidth) {
        if (!waterMask.any()) return new int[]{0, 0};
        BoolGrid wide = CustomSettlementGenerator.erodeMask(waterMask, Math.max(1, riverWidth / 2));

        int h = waterMask.h;
        int w = waterMask.w;
        boolean[][] seen = new boolean[h][w];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        List<int[]> body = new ArrayList<>();
        int oceanCells = 0;
        int riverCells = 0;

        for (int sy = 0; sy < h; sy++) {
            for (int sx = 0; sx < w; sx++) {
                if (!waterMask.d[sy][sx] || seen[sy][sx]) continue;
                body.clear();
                queue.clear();
                queue.add(new int[]{sx, sy});
                seen[sy][sx] = true;
                boolean isOcean = false;
                while (!queue.isEmpty()) {
                    int[] cur = queue.poll();
                    body.add(cur);
                    if (wide.d[cur[1]][cur[0]]) isOcean = true;
                    for (int[] dir : DIRS4) {
                        int nx = cur[0] + dir[0];
                        int ny = cur[1] + dir[1];
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        if (!waterMask.d[ny][nx] || seen[ny][nx]) continue;
                        seen[ny][nx] = true;
                        queue.add(new int[]{nx, ny});
                    }
                }
                int value = isOcean ? CustomSettlementGenerator.OCEAN : CustomSettlementGenerator.RIVER;
                for (int[] cell : body) terrain.set(cell[0], cell[1], value);
                if (isOcean) oceanCells += body.size();
                else riverCells += body.size();
            }
        }
        return new int[]{oceanCells, riverCells};
    }

    /** Drapes the generator's output grid over the terrain, one marker block per column. */
    private static long[] paintResult(ServerWorld world, int originX, int originZ, int size, Options o,
                                       Result result, int[] paintY) {
        long[] placed = new long[1024];
        int count = 0;
        int minY = world.getBottomY();
        int maxY = world.getBottomY() + world.getHeight();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int gy = 0; gy < size; gy++) {
            for (int gx = 0; gx < size; gx++) {
                int type = result.out.get(gx, gy);
                if (type < 0 || type >= PALETTE_SIZE) continue;
                Block block = PALETTE[type];
                if (block == null) continue;
                boolean isWaterCell = type == CustomSettlementGenerator.OUT_OCEAN
                        || type == CustomSettlementGenerator.OUT_RIVER;
                if (isWaterCell && !o.paintWater) continue;

                int y = paintY[gy * size + gx];
                if (y <= minY || y >= maxY) continue;
                pos.set(originX + gx, y, originZ + gy);
                world.setBlockState(pos, block.getDefaultState(), SET_BLOCK_FLAGS);

                if (count == placed.length) placed = Arrays.copyOf(placed, placed.length * 2);
                placed[count++] = pos.asLong();
            }
        }
        return Arrays.copyOf(placed, count);
    }
}
