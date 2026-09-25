package com.example.command;

import java.io.IOException;

import com.example.border.BorderMode;
import com.example.fmg.FMGBiomeReport;
import com.example.heightmap.HeightmapExport;
import com.example.towns.SettlementDebugPainter;
import com.example.worldgen.FMGWorldExport;
import com.example.worldgen.ImageMapChunkGenerator;
import com.example.worldmap.FMGMapRegistries;
import com.example.worldmap.MapInfo;
import com.example.worldmap.runtime.MapImageCache;
import com.example.worldmap.runtime.MapImageData;
import com.example.worldmap.runtime.PlayerBorderViewer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

/**
 * Command to help test the FMG chunk generator
 */
public class FMGCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("fmg")
            .then(literal("info")
                .executes(FMGCommands::showInfo))
            .then(literal("town")
                .requires(src -> src.hasPermissionLevel(2))
                .then(literal("legend")
                    .executes(FMGCommands::townLegend))
                .then(literal("clear")
                    .executes(FMGCommands::townClear))
                .then(literal("paint")
                    .then(argument("townRadius", DoubleArgumentType.doubleArg(4.0, 512.0))
                        .executes(ctx -> townPaint(ctx, ""))
                        .then(argument("options", StringArgumentType.greedyString())
                            .executes(ctx -> townPaint(ctx, StringArgumentType.getString(ctx, "options")))))))
            .then(literal("heightmap")
                .requires(src -> src.hasPermissionLevel(2))
                .then(argument("radius", IntegerArgumentType.integer(16, 4096))
                    .executes(ctx -> heightmapDump(ctx, IntegerArgumentType.getInteger(ctx, "radius"), null))
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> heightmapDump(ctx, IntegerArgumentType.getInteger(ctx, "radius"),
                                StringArgumentType.getString(ctx, "name"))))))
            .then(literal("export")
                .requires(src -> src.hasPermissionLevel(2))
                .then(argument("name", StringArgumentType.word())
                    .executes(ctx -> exportWorld(ctx, StringArgumentType.getString(ctx, "name"), 1024, false))
                    .then(argument("radius", IntegerArgumentType.integer(64, 16384))
                        .executes(ctx -> exportWorld(ctx, StringArgumentType.getString(ctx, "name"),
                                IntegerArgumentType.getInteger(ctx, "radius"), false))
                        .then(literal("generate")
                            .executes(ctx -> exportWorld(ctx, StringArgumentType.getString(ctx, "name"),
                                    IntegerArgumentType.getInteger(ctx, "radius"), true))))))
            .then(literal("biome")
                .requires(src -> src.hasPermissionLevel(2))
                .then(literal("report")
                    .executes(ctx -> biomeReport(ctx, 1))
                    .then(argument("step", IntegerArgumentType.integer(1, 1000))
                        .executes(ctx -> biomeReport(ctx, IntegerArgumentType.getInteger(ctx, "step"))))))
            .then(literal("borders")
                .executes(ctx -> toggleBorders(ctx, BorderMode.PROVINCE))
                .then(literal("province").executes(ctx -> toggleBorders(ctx, BorderMode.PROVINCE)))
                .then(literal("state").executes(ctx -> toggleBorders(ctx, BorderMode.STATE)))
                .then(literal("off").executes(ctx -> setBorders(ctx, BorderMode.OFF)))
            )
        );
    }

    // ===================================================================
    // /fmg town -- sample the world around the player, run the settlement
    // generator over it, and paint the layout in wool
    // ===================================================================

    private static int townPaint(CommandContext<ServerCommandSource> context, String rawOptions) {
        ServerCommandSource source = context.getSource();
        BlockPos center;
        try {
            center = source.getPlayerOrThrow().getBlockPos();
        } catch (Exception e) {
            source.sendError(Text.literal("This command can only be used by a player."));
            return 0;
        }

        SettlementDebugPainter.Options options = new SettlementDebugPainter.Options();
        options.townRadius = DoubleArgumentType.getDouble(context, "townRadius");
        try {
            SettlementDebugPainter.parseOptions(options, rawOptions);
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal("Bad options: " + e.getMessage()));
            return 0;
        }

        int r = SettlementDebugPainter.effectiveSampleRadius(options);
        boolean autoSized = options.sampleRadius == null;
        int span = 2 * r + 1;
        int chunks = (span / 16 + 2) * (span / 16 + 2);
        source.sendFeedback(() -> Text.literal(
            "Sampling " + span + "x" + span + " blocks (~" + chunks + " chunks, "
                + (autoSized ? "auto radius " + r + " from townRadius " + (int) options.townRadius
                        + (options.wall && options.farms ? " + farmDepth " + options.farmDepth : "")
                   : "radius " + r + " forced") + ")"
                + (r > SettlementDebugPainter.LARGE_SAMPLE_RADIUS
                    ? " - this will freeze the server for a while..." : " ...")
        ), false);

        SettlementDebugPainter.Report report;
        try {
            report = SettlementDebugPainter.run(source.getWorld(), center, options);
        } catch (Exception e) {
            source.sendError(Text.literal("Settlement generation failed: " + e));
            return 0;
        }

        source.sendFeedback(() -> Text.literal(
            "FMG town painted at " + report.centerX() + "," + report.centerZ()
                + " | seed=" + report.seed()
                + " | grid " + report.gridSize() + "x" + report.gridSize()
                + " (path=" + report.pathCells() + ", ocean=" + report.oceanCells()
                + ", river=" + report.riverCells() + ")"
                + " | buildings=" + report.buildings()
                + " (" + report.housesPlaced() + " real structures)"
                + ", towers=" + report.towers()
                + ", gates=" + report.gates()
                + ", docks=" + report.docks()
                + " | " + report.painted() + " marker blocks"
                + (report.cleared() > 0 ? " (" + report.cleared() + " old ones removed)" : "")
                + " in " + report.millis() + "ms"
        ), true);

        if (!report.placedStructures().isEmpty()) {
            source.sendFeedback(() -> Text.literal(
                "Prebuilt placed: " + String.join(", ", report.placedStructures())), false);
        }
        if (!report.unplacedStructures().isEmpty()) {
            source.sendFeedback(() -> Text.literal(
                "Prebuilt that did not fit: " + String.join(", ", report.unplacedStructures())), false);
        }
        return 1;
    }

    // ===================================================================
    // /fmg heightmap <radius> [name] -- dump the raw FMG heightfield around
    // the player to <gameDir>/fmg_heightmaps/ for offline post-processing
    // ===================================================================

    private static int heightmapDump(CommandContext<ServerCommandSource> context, int radius, String name) {
        ServerCommandSource source = context.getSource();
        BlockPos center;
        try {
            center = source.getPlayerOrThrow().getBlockPos();
        } catch (Exception e) {
            source.sendError(Text.literal("This command can only be used by a player."));
            return 0;
        }

        if (!(source.getWorld().getChunkManager().getChunkGenerator() instanceof ImageMapChunkGenerator fmg)) {
            source.sendError(Text.literal("This dimension is not an FMG world (no ImageMapChunkGenerator)."));
            return 0;
        }

        MapInfo info = fmg.mapInfo();
        MapImageData image = MapImageCache.get(info);
        try {
            HeightmapExport.Result r = HeightmapExport.export(image, info,
                    center.getX(), center.getZ(), radius, name);
            source.sendFeedback(() -> Text.literal(
                "Heightmap " + r.width() + "x" + r.height()
                    + " (y " + (int) r.minY() + ".." + (int) r.maxY()
                    + ", " + r.riverCount() + " rivers) in " + r.millis() + "ms -> "
                    + HeightmapExport.outputDir().toAbsolutePath()), true);
            source.sendFeedback(() -> Text.literal("  " + r.fmgh().getFileName()
                    + "  +.height.png  +.hillshade.png  +.rivers"), false);
            return 1;
        } catch (IOException e) {
            source.sendError(Text.literal("Heightmap export failed: " + e));
            return 0;
        }
    }

    // ===================================================================
    // /fmg export <name> [radius] [generate] -- write the FMG terrain out
    // as a self-contained, mod-free vanilla save for WorldPainter etc.
    // ===================================================================

    private static int exportWorld(CommandContext<ServerCommandSource> context, String name,
            int radius, boolean generate) {
        ServerCommandSource source = context.getSource();
        BlockPos center;
        try {
            center = source.getPlayerOrThrow().getBlockPos();
        } catch (Exception e) {
            source.sendError(Text.literal("This command can only be used by a player."));
            return 0;
        }

        long chunks = FMGWorldExport.chunkCount(center.getX(), center.getZ(), radius);
        source.sendFeedback(() -> Text.literal(
            "Exporting \"" + name + "\" -- " + (2 * radius) + "x" + (2 * radius) + " blocks (~"
                + chunks + " chunks)"
                + (generate ? " -- force-generating missing chunks, the server will freeze..."
                            : " -- already-generated chunks only")), false);

        FMGWorldExport.Result r;
        try {
            r = FMGWorldExport.export(source.getServer(), source.getWorld(), name,
                    center.getX(), center.getZ(), radius, generate);
        } catch (IllegalStateException e) {
            source.sendError(Text.literal("Export failed: " + e.getMessage()));
            return 0;
        } catch (IOException e) {
            source.sendError(Text.literal("Export failed: " + e));
            return 0;
        }

        source.sendFeedback(() -> Text.literal(
            "Exported to " + r.savePath().toAbsolutePath()
                + " | " + r.regionFiles() + " region files"
                + (r.generatedChunks() > 0 ? ", " + r.generatedChunks() + " chunks generated" : "")
                + (r.heightPack() ? ", + fmg_terrain height datapack" : "")
                + " in " + r.millis() + "ms"), true);
        source.sendFeedback(() -> Text.literal(
            "  Open in WorldPainter via \"Import existing Minecraft map\", or load directly in vanilla."), false);
        return 1;
    }

    // ===================================================================
    // /fmg biome report [step] -- tally which vanilla biomes the name+variant
    // mapper actually produced, confirm the guaranteed rares (pale garden,
    // cherry grove, mangrove swamp, bamboo jungle) show up, and count direct
    // snowy/hot adjacencies (should be ~0).
    // ===================================================================

    private static int biomeReport(CommandContext<ServerCommandSource> context, int step) {
        ServerCommandSource source = context.getSource();
        if (!(source.getWorld().getChunkManager().getChunkGenerator() instanceof ImageMapChunkGenerator fmg)) {
            source.sendError(Text.literal("This dimension is not an FMG world (no ImageMapChunkGenerator)."));
            return 0;
        }

        MapInfo info = fmg.mapInfo();
        MapImageData image = MapImageCache.get(info);
        var biomeLookup = source.getRegistryManager().getOrThrow(RegistryKeys.BIOME);

        FMGBiomeReport.Report r;
        try {
            r = FMGBiomeReport.run(image.fmgData(), biomeLookup, info.fmgExport(),
                    com.example.profile.FmgProfiles.get(info.profile()), step);
        } catch (Exception e) {
            source.sendError(Text.literal("Biome report failed: " + e));
            return 0;
        }

        source.sendFeedback(() -> Text.literal(
                "Biome report: " + r.cellsChecked() + " cells checked"
                        + " | snowy-touching-hot adjacencies: " + r.snowyHotAdjacencies()
        ), true);

        for (var g : r.guaranteedFound().entrySet()) {
            String line = "  guaranteed " + g.getKey().getValue().getPath() + ": " + g.getValue()
                    + (g.getValue() > 0 ? " (OK)" : " (MISSING)");
            source.sendFeedback(() -> Text.literal(line), false);
        }

        r.counts().entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> {
                    String line = "  " + e.getKey() + ": " + e.getValue();
                    source.sendFeedback(() -> Text.literal(line), false);
                });
        return 1;
    }

    private static int townClear(CommandContext<ServerCommandSource> context) {
        int removed = SettlementDebugPainter.clear(context.getSource().getWorld());
        if (removed < 0) {
            context.getSource().sendError(Text.literal(
                "Nothing painted in this dimension since the last server start."));
            return 0;
        }
        int finalRemoved = removed;
        context.getSource().sendFeedback(() -> Text.literal("Removed " + finalRemoved + " marker blocks."), true);
        return 1;
    }

    private static int townLegend(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        source.sendFeedback(() -> Text.literal("FMG town paint legend:"), false);
        for (String line : SettlementDebugPainter.legend()) {
            source.sendFeedback(() -> Text.literal(" - " + line), false);
        }
        source.sendFeedback(() -> Text.literal("Options: " + SettlementDebugPainter.OPTION_HELP), false);
        return 1;
    }

    private static int toggleBorders(CommandContext<ServerCommandSource> context, BorderMode mode) {
        try {
            var player = context.getSource().getPlayer();
            BorderMode current = PlayerBorderViewer.getMode(player);
            BorderMode next = (current == mode) ? BorderMode.OFF : mode;
            PlayerBorderViewer.setMode(player, next);
            context.getSource().sendFeedback(() -> Text.literal("Border viewer: " + next.name().toLowerCase()), false);
            return 1;
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("This command can only be used by a player."));
            return 0;
        }
    }

    private static int setBorders(CommandContext<ServerCommandSource> context, BorderMode mode) {
        try {
            var player = context.getSource().getPlayer();
            PlayerBorderViewer.setMode(player, mode);
            context.getSource().sendFeedback(() -> Text.literal("Border viewer: " + mode.name().toLowerCase()), false);
            return 1;
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("This command can only be used by a player."));
            return 0;
        }
    }

    private static int showInfo(CommandContext<ServerCommandSource> context) {
        var registryManager = context.getSource().getRegistryManager();
        var registryOpt = registryManager.getOptional(FMGMapRegistries.MAP_INFO);
        if (registryOpt.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§cNo map info registry present."), false);
            return 0;
        }

        var registry = registryOpt.get();
        var entries = registry.streamEntries().toList();
        context.getSource().sendFeedback(() -> Text.literal("§aFMG Map definitions: " + entries.size()), false);
        for (var holder : entries) {
            var key = holder.getKey().map(k -> k.getValue().toString()).orElse("unknown");
            MapInfo definition = holder.value();
            String line = "§7- " + key + " (§f" + definition.fmgExport() + "§7)";
            context.getSource().sendFeedback(() -> Text.literal(line), false);
        }

        return 1;
    }
}
