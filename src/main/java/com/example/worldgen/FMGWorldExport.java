package com.example.worldgen;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.dimension.DimensionType;

/**
 * Writes the FMG-generated terrain out as a self-contained, mod-free vanilla
 * world save: pre-generated chunks under {@code region/}, a {@code level.dat}
 * with an ordinary (void {@code minecraft:flat}) generator and only the three
 * vanilla dimensions, plus a tiny world datapack that restores the FMG build
 * height. The result opens in vanilla Minecraft, WorldPainter, Amulet, MCEdit.
 */
public final class FMGWorldExport {

    private FMGWorldExport() {
    }

    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final String[] REGION_DIRS = {"region", "entities", "poi"};

    public record Result(Path savePath, int regionFiles, int generatedChunks, boolean heightPack, long millis) {
    }

    /**
     * @param radius  half-width of the export box in blocks around {@code centerX/centerZ}
     * @param generate  force-generate every chunk in the box first (blocks the server thread)
     * @throws IOException on any file error; throws {@link IllegalStateException} for a
     *         user-facing precondition failure (save exists, nothing generated, ...)
     */
    public static Result export(MinecraftServer server, ServerWorld world, String name,
                                int centerX, int centerZ, int radius, boolean generate) throws IOException {
        long t0 = System.currentTimeMillis();

        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) throw new IllegalStateException("bad export name");
        Path savesDir = FMLPaths.GAMEDIR.get().resolve("saves");
        Path dest = savesDir.resolve(safe);
        if (Files.exists(dest)) {
            throw new IllegalStateException("saves/" + safe + " already exists");
        }

        int minChunkX = (centerX - radius) >> 4;
        int maxChunkX = (centerX + radius) >> 4;
        int minChunkZ = (centerZ - radius) >> 4;
        int maxChunkZ = (centerZ + radius) >> 4;

        int generated = 0;
        if (generate) {
            var mgr = world.getChunkManager();
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                    mgr.getChunk(cx, cz, ChunkStatus.FULL, true);
                    generated++;
                }
            }
        }

        // flush everything touched to disk
        world.save(null, true, false);

        Path srcRoot = server.getSavePath(WorldSavePath.ROOT).normalize();
        RegistryKey<World> dim = world.getRegistryKey();
        Path srcDimDir = DimensionType.getSaveDirectory(dim, srcRoot);

        int rxLo = minChunkX >> 5, rxHi = maxChunkX >> 5;
        int rzLo = minChunkZ >> 5, rzHi = maxChunkZ >> 5;

        Files.createDirectories(dest);
        int regionFiles = 0;
        for (String sub : REGION_DIRS) {
            Path srcSub = srcDimDir.resolve(sub);
            if (!Files.isDirectory(srcSub)) continue;
            Path dstSub = dest.resolve(sub);
            Files.createDirectories(dstSub);
            List<Path> files;
            try (var stream = Files.list(srcSub)) {
                files = stream.toList();
            }
            for (Path f : files) {
                Matcher m = REGION_FILE.matcher(f.getFileName().toString());
                if (!m.matches()) continue;
                int rx = Integer.parseInt(m.group(1));
                int rz = Integer.parseInt(m.group(2));
                if (rx < rxLo || rx > rxHi || rz < rzLo || rz > rzHi) continue;
                Files.copy(f, dstSub.resolve(f.getFileName()));
                if (sub.equals("region")) regionFiles++;
            }
        }
        if (regionFiles == 0 && !generate) {
            deleteRecursively(dest);
            throw new IllegalStateException(
                    "no generated chunks in that area -- fly/pregenerate it first, or add: generate");
        }

        // ---- level.dat: strip the mod, void the generator ----
        Path srcLevel = server.getSavePath(WorldSavePath.LEVEL_DAT);
        NbtCompound root = NbtIo.readCompressed(srcLevel, NbtSizeTracker.ofUnlimitedBytes());
        NbtCompound data = root.getCompound("Data");

        boolean tall = false;
        NbtCompound wgs = data.getCompound("WorldGenSettings");
        NbtCompound dims = wgs.getCompound("dimensions");
        // drop every non-vanilla dimension
        List<String> keys = new ArrayList<>(dims.getKeys());
        for (String k : keys) {
            if (!k.startsWith("minecraft:")) dims.remove(k);
        }
        NbtCompound overworld = dims.getCompound("minecraft:overworld");
        overworld.putString("type", "minecraft:overworld");
        overworld.put("generator", voidFlatGenerator());
        dims.put("minecraft:overworld", overworld);

        NbtCompound dataPacks = data.contains("DataPacks", NbtElement.COMPOUND_TYPE)
                ? data.getCompound("DataPacks") : new NbtCompound();
        NbtList enabled = dataPacks.getList("Enabled", NbtElement.STRING_TYPE);
        enabled.removeIf(e -> e.asString().contains("fantasymapgenerator"));

        // ---- height datapack (FMG range differs from vanilla) ----
        DimensionType dimType = world.getDimension();
        int minY = dimType.minY();
        int height = dimType.height();
        if (minY != -64 || height != 384) {
            tall = true;
            writeHeightDatapack(dest, minY, height);
            enabled.add(NbtString.of("file/fmg_terrain"));
        }
        dataPacks.put("Enabled", enabled);
        data.put("DataPacks", dataPacks);

        data.putString("LevelName", safe);
        NbtIo.writeCompressed(root, dest.resolve("level.dat"));

        return new Result(dest, regionFiles, generated, tall, System.currentTimeMillis() - t0);
    }

    private static NbtCompound voidFlatGenerator() {
        NbtCompound gen = new NbtCompound();
        gen.putString("type", "minecraft:flat");
        NbtCompound settings = new NbtCompound();
        settings.putString("biome", "minecraft:the_void");
        settings.putByte("lakes", (byte) 0);
        settings.putByte("features", (byte) 0);
        NbtList layers = new NbtList();
        NbtCompound air = new NbtCompound();
        air.putString("block", "minecraft:air");
        air.putInt("height", 1);
        layers.add(air);
        settings.put("layers", layers);
        settings.put("structure_overrides", new NbtList());
        gen.put("settings", settings);
        return gen;
    }

    private static void writeHeightDatapack(Path dest, int minY, int height) throws IOException {
        Path pack = dest.resolve("datapacks/fmg_terrain");
        Files.createDirectories(pack.resolve("data/minecraft/dimension_type"));
        write(pack.resolve("pack.mcmeta"),
                "{\n  \"pack\": {\n    \"description\": \"FMG terrain build height\",\n"
                        + "    \"pack_format\": 48\n  }\n}\n");
        write(pack.resolve("data/minecraft/dimension_type/overworld.json"),
                "{\n"
                        + "  \"ultrawarm\": false,\n  \"natural\": true,\n  \"coordinate_scale\": 1.0,\n"
                        + "  \"has_skylight\": true,\n  \"has_ceiling\": false,\n  \"ambient_light\": 0.0,\n"
                        + "  \"piglin_safe\": false,\n  \"bed_works\": true,\n  \"respawn_anchor_works\": false,\n"
                        + "  \"has_raids\": true,\n"
                        + "  \"min_y\": " + minY + ",\n  \"height\": " + height + ",\n"
                        + "  \"logical_height\": " + height + ",\n"
                        + "  \"infiniburn\": \"#minecraft:infiniburn_overworld\",\n"
                        + "  \"effects\": \"minecraft:overworld\",\n"
                        + "  \"monster_spawn_light_level\": { \"type\": \"minecraft:uniform\","
                        + " \"min_inclusive\": 0, \"max_inclusive\": 7 },\n"
                        + "  \"monster_spawn_block_light_limit\": 0\n"
                        + "}\n");
    }

    private static void write(Path p, String s) throws IOException {
        try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            w.write(s);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** Just for the command's pre-flight chunk-count estimate. */
    public static long chunkCount(int centerX, int centerZ, int radius) {
        long w = (((centerX + radius) >> 4) - ((centerX - radius) >> 4) + 1);
        long h = (((centerZ + radius) >> 4) - ((centerZ - radius) >> 4) + 1);
        return w * h;
    }
}
