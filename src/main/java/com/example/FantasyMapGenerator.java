package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.command.FMGCommands;
import com.example.generation.FMGGenerators;
import com.example.worldmap.runtime.PlayerRegionTracker;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
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
		FMGGenerators.register();
		FMGCommands.register();

		// Grab the density-function registry before world load so the FMG chunk
		// generator can carve vanilla-style noise caves into its heightmap, and
		// record the save directory so per-world FMG data resolves against it.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			com.example.worldgen.FmgNoiseCaves.capture(server.getRegistryManager());
			com.example.worldgen.FmgWorldSettings.setSaveDir(
				server.getSavePath(net.minecraft.util.WorldSavePath.ROOT));
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			com.example.worldgen.FmgWorldSettings.clear();
			com.example.worldmap.runtime.FMGMapDataCache.invalidate();
		});

		// One-time "Preparing Fantasy Map..." bake (heightfield / roads / rivers /
		// climate -> <world>/fmg/bake), before spawn-chunk generation.
		ServerWorldEvents.LOAD.register((server, world) -> {
			if (world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) {
				return;
			}
			if (world.getChunkManager().getChunkGenerator()
					instanceof com.example.worldgen.ImageMapChunkGenerator fmg) {
				// The only point guaranteed to be the real, running world's own
				// generator -- not a throwaway decode from registry loading (see
				// the note in ImageMapChunkGenerator's constructor).
				com.example.worldgen.FmgWorldSettings.setCurrent(fmg.mapInfo());
				com.example.worldgen.FmgBake.ensureBaked(server, fmg.mapInfo());
			}
		});
		PlayerRegionTracker.register();
		com.example.worldmap.runtime.PlayerBorderViewer.register();

	   	LOGGER.info("Registered FMG image map pipeline");
		
		FabricLoader.getInstance().getModContainer(FantasyMapGenerator.MOD_ID).ifPresent(container -> {
			ResourceManagerHelper.registerBuiltinResourcePack(
				new Identifier(FantasyMapGenerator.MOD_ID, "builtin"),
				container, Text.literal("Fantasy Map Generator"),
				ResourcePackActivationType.DEFAULT_ENABLED
			);
		});
	}
}
