package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.command.FMGCommands;
import com.example.generation.FMGGenerators;
import com.example.border.BorderDataPayload;
import com.example.worldmap.runtime.PlayerRegionTracker;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class FantasyMapGenerator implements ModInitializer {
	public static final String MOD_ID = "fantasymapgenerator";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Bootstrapping Fantasy Map Generator");
		PayloadTypeRegistry.playS2C().register(BorderDataPayload.ID, BorderDataPayload.CODEC);
		FMGGenerators.register();
		FMGCommands.register();
		PlayerRegionTracker.register();
		com.example.worldmap.runtime.PlayerBorderViewer.register();

	   	LOGGER.info("Registered FMG image map pipeline");
		
		FabricLoader.getInstance().getModContainer(FantasyMapGenerator.MOD_ID).ifPresent(container -> {
			ResourceManagerHelper.registerBuiltinResourcePack(
				Identifier.of(FantasyMapGenerator.MOD_ID, "builtin"),
				container, Text.literal("Fantasy Map Generator"),
				ResourcePackActivationType.DEFAULT_ENABLED
			);
		});
	}
}
