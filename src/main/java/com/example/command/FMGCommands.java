package com.example.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import com.example.worldmap.FMGMapRegistries;
import com.example.worldmap.MapInfo;

import static net.minecraft.server.command.CommandManager.literal;

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
        );
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
