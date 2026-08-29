package dev.vertex.mixin;

import dev.vertex.render.ShadowRenderer;
import dev.vertex.render.TerrainCommandCache;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A completed section upload makes the cached static shadow map stale. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class ShadowInvalidationMixin {
    @Inject(method = "setSectionMesh", at = @At("RETURN"))
    private void vertex$invalidateShadow(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
        ShadowRenderer.invalidate();
        TerrainCommandCache.invalidate();
    }
}
