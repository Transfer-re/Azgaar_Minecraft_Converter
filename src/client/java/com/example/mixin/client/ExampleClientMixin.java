package com.example.mixin.client;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class ExampleClientMixin {

    @Inject(method = "run", at = @At("HEAD"))
    private void init(CallbackInfo info) {
        // Code injected at start of MinecraftClient.run()
    }
}