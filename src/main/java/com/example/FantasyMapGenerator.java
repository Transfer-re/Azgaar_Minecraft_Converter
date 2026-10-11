package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.command.FMGCommands;
import com.example.generation.FMGGenerators;
import com.example.border.BorderDataPayload;
import com.example.worldmap.runtime.PlayerRegionTracker;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.RegisterEvent;

@Mod(FantasyMapGenerator.MOD_ID)
public class FantasyMapGenerator {
	public static final String MOD_ID = "fantasymapgenerator";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public FantasyMapGenerator() {
		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		LOGGER.info("Bootstrapping Fantasy Map Generator");
		BorderDataPayload.register();
		modBus.addListener((RegisterEvent event) -> {
			FMGGenerators.register(event);
			// TreeGenerator objects as worldgen features (placed from the biome JSONs)
			com.example.trees.TreeFeatures.registerFeatures(event);
		});
		FMGCommands.register();
		com.example.trees.TreeFeatures.register();

		// Grab the density-function registry before world load so the FMG chunk
		// generator can carve vanilla-style noise caves into its heightmap, and
		// record the save directory so per-world FMG data resolves against it.
		// Before the worlds load, like Fabric's SERVER_STARTING (Forge's
		// ServerStartingEvent comes after them).
		MinecraftForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> {
			MinecraftServer server = event.getServer();
			com.example.worldgen.FmgNoiseCaves.capture(server.getRegistryManager());
			com.example.worldgen.FmgWorldSettings.setSaveDir(
				server.getSavePath(net.minecraft.util.WorldSavePath.ROOT));
			// before the worlds exist: their structure placement reads it
			com.example.worldgen.FmgStructureBiomes.capture(server.getRegistryManager());
		});
		// no player = the sync after /reload (Fabric's END_DATA_PACK_RELOAD)
		MinecraftForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
			if (event.getPlayer() == null) {
				com.example.worldgen.FmgStructureBiomes.capture(
					event.getPlayerList().getServer().getRegistryManager());
			}
		});
		MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
			com.example.worldgen.FmgStructureBiomes.clear();
			com.example.worldgen.FmgWorldSettings.clear();
			com.example.worldmap.runtime.FMGMapDataCache.invalidate();
		});

		// One-time "Preparing Fantasy Map..." bake (heightfield / roads / rivers /
		// climate -> <world>/fmg/bake), before spawn-chunk generation.
		MinecraftForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
			if (!(event.getLevel() instanceof ServerWorld world)
					|| world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) {
				return;
			}
			if (world.getChunkManager().getChunkGenerator()
					instanceof com.example.worldgen.ImageMapChunkGenerator fmg) {
				// The only point guaranteed to be the real, running world's own
				// generator -- not a throwaway decode from registry loading (see
				// the note in ImageMapChunkGenerator's constructor).
				com.example.worldgen.FmgWorldSettings.setCurrent(fmg.mapInfo());
				com.example.worldgen.FmgBake.ensureBaked(world.getServer(), fmg.mapInfo());
			}
		});
		PlayerRegionTracker.register();
		com.example.worldmap.runtime.PlayerBorderViewer.register();

		// only loaded on the client, so a dedicated server never touches client classes
		if (FMLEnvironment.dist.isClient()) {
			FantasyMapGeneratorClient.init(modBus);
		}

	   	LOGGER.info("Registered FMG image map pipeline");
	}
}
