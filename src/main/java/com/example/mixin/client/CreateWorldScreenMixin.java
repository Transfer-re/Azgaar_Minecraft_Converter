package com.example.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.client.screen.FmgCreateState;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

/**
 * Disables "Create New World" while the Fantasy Map world type is selected and
 * no map file has been chosen in its Customize screen. The button state is
 * re-evaluated every frame (the world type can change on the World tab without
 * the screen re-initialising), and {@code createLevel} itself is guarded so the
 * Enter-key shortcut can't bypass the disabled button.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin extends Screen {

    @Unique
    private static final Text FMG_NEEDS_FILE = Text.literal(
            "Choose a map file first: World tab > Customize");

    @Unique
    private ButtonWidget fantasymapgenerator$createButton;

    protected CreateWorldScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void fantasymapgenerator$gateCreateButton(DrawContext context, int mouseX, int mouseY,
                                                       float delta, CallbackInfo ci) {
        if (fantasymapgenerator$createButton == null || !this.children().contains(fantasymapgenerator$createButton)) {
            fantasymapgenerator$createButton = null;
            for (Element child : this.children()) {
                if (child instanceof ButtonWidget button
                        && button.getMessage().getContent() instanceof TranslatableTextContent content
                        && "selectWorld.create".equals(content.getKey())) {
                    fantasymapgenerator$createButton = button;
                    break;
                }
            }
        }
        if (fantasymapgenerator$createButton == null) {
            return;
        }
        boolean blocked = FmgCreateState.needsMapFile(((CreateWorldScreen) (Object) this).getWorldCreator());
        if (fantasymapgenerator$createButton.active == blocked) {
            fantasymapgenerator$createButton.active = !blocked;
            fantasymapgenerator$createButton.setTooltip(blocked ? Tooltip.of(FMG_NEEDS_FILE) : null);
        }
    }

    @Inject(method = "createLevel", at = @At("HEAD"), cancellable = true)
    private void fantasymapgenerator$blockCreate(CallbackInfo ci) {
        if (FmgCreateState.needsMapFile(((CreateWorldScreen) (Object) this).getWorldCreator())) {
            ci.cancel();
        }
    }
}
