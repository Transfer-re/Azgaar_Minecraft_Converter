package com.example;

import net.fabricmc.api.ClientModInitializer;

import com.example.border.ClientBorderOverlay;

public class FantasyMapGeneratorClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientBorderOverlay.registerClient();
	}
}