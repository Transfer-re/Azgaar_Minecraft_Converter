package com.example.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.worldgen.FmgBake;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * While {@link FmgBake} is running (the "Preparing Fantasy Map…" step on world
 * load) overlay the current phase + a progress bar on whatever loading screen is
 * showing.
 */
@Mixin(Screen.class)
public class ScreenBakeOverlayMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void fantasymapgenerator$bakeOverlay(DrawContext context, int mouseX, int mouseY,
            float delta, CallbackInfo ci) {
        if (!FmgBake.isActive()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) {
            return;
        }
        Screen self = (Screen) (Object) this;
        int cx = self.width / 2;
        int y = self.height - 40;

        context.fill(0, y - 6, self.width, self.height, 0xC0000000);
        context.drawCenteredTextWithShadow(mc.textRenderer,
                Text.literal("Preparing Fantasy Map — " + FmgBake.phase()), cx, y, 0xFFFFFF);

        int w = 182;
        int x = cx - w / 2;
        int by = y + 14;
        float f = Math.max(0f, Math.min(1f, FmgBake.fraction()));
        context.fill(x - 1, by - 1, x + w + 1, by + 5, 0xFF000000);
        context.fill(x, by, x + Math.round(w * f), by + 4, 0xFF54C154);
    }
}
