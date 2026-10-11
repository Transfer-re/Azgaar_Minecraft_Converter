package com.example;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

import com.example.border.ClientBorderOverlay;

@Mod(value = FantasyMapGenerator.MOD_ID, dist = Dist.CLIENT)
public class FantasyMapGeneratorClient {
	public FantasyMapGeneratorClient() {
		ClientBorderOverlay.registerClient();
	}
}
