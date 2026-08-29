package dev.vertex.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vertex.render.DynamicRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Redirects the three RenderType state reads used before draw extraction. */
@Mixin(RenderType.class)
public abstract class RenderTypeDynamicPipelineMixin {
    @Redirect(
        method = {"prepare", "format", "primitiveTopology"},
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/rendertype/RenderSetup;pipeline:Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;")
    )
    private RenderPipeline vertex$dynamicPipeline(RenderSetup state) {
        RenderPipeline original = ((RenderSetupPipelineAccessor) (Object) state).vertex$getPipeline();
        return DynamicRenderer.pipelineFor(original);
    }
}
