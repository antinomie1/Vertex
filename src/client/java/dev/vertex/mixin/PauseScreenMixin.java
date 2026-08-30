package dev.vertex.mixin;

import dev.vertex.ui.VertexUi;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void vertex$addShadersButton(CallbackInfo ci) {
        PauseScreen screen = (PauseScreen) (Object) this;
        ScreenWidgetsAccessor accessor = (ScreenWidgetsAccessor) (Object) screen;
        accessor.vertex$addRenderableWidget(Button.builder(Component.literal("Vertex Shaders"), button -> VertexUi.open(screen))
            .bounds(screen.width / 2 - 100, Math.max(20, screen.height - 64), 200, 20).build());
    }
}
