package com.example.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.client.screen.FmgLevelScreenProvider;
import com.example.client.screen.FmgWorldCreateScreen;

import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import net.minecraft.client.gui.screen.world.WorldCreator;

/**
 * Makes the create-world screen's <b>Customize</b> button open the FMG settings
 * screen when the "Fantasy Map" world type is selected.
 *
 * <p>{@code LevelScreenProvider.WORLD_PRESET_TO_SCREEN_PROVIDER} is an immutable
 * {@code Map.of(...)} of flat/single-biome only, so it can't be extended at
 * runtime -- instead we supply our provider here, where {@code WorldCreator}
 * looks one up by preset key. A non-null result also flips the Customize button
 * to {@code active}.</p>
 */
@Mixin(WorldCreator.class)
public class WorldCreatorMixin {

    @Inject(method = "getLevelScreenProvider", at = @At("RETURN"), cancellable = true)
    private void fantasymapgenerator$fmgProvider(CallbackInfoReturnable<LevelScreenProvider> cir) {
        if (cir.getReturnValue() != null) {
            return;
        }
        WorldCreator self = (WorldCreator) (Object) this;
        var preset = self.getWorldType().preset();
        if (preset != null && preset.matchesKey(FmgWorldCreateScreen.FMG_PRESET_KEY)) {
            cir.setReturnValue(FmgLevelScreenProvider.INSTANCE);
        }
    }
}
