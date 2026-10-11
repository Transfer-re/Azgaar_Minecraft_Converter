package com.example.client.screen;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import net.minecraft.client.world.GeneratorOptionsHolder;

/** Opens {@link FmgWorldCreateScreen} for the "Fantasy Map" world type. */
public final class FmgLevelScreenProvider implements LevelScreenProvider {

    public static final FmgLevelScreenProvider INSTANCE = new FmgLevelScreenProvider();

    private FmgLevelScreenProvider() {
    }

    @Override
    public Screen createEditScreen(CreateWorldScreen parent, GeneratorOptionsHolder holder) {
        return new FmgWorldCreateScreen(parent, holder);
    }
}
