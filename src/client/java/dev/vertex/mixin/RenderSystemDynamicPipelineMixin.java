package dev.vertex.mixin;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vertex.render.DynamicRenderer;
import dev.vertex.render.PackChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the game's PipelineCache intact while serving compiled pack pipelines. */
@Mixin(com.mojang.blaze3d.systems.RenderSystem.class)
public abstract class RenderSystemDynamicPipelineMixin {
    @Inject(method = "bindDefaultUniforms", at = @At("TAIL"))
    private static void vertex$bindPackUniforms(
        com.mojang.renderpearl.api.commands.RenderPass pass,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci
    ) {
        PackChain.bindUniforms(pass);
        PackChain.bindDynamicSamplers(pass);
    }

    @Inject(method = "getCompiledPipelineNullable", at = @At("HEAD"), cancellable = true)
    private static void vertex$compiledDynamic(
        RenderPipeline pipeline,
        CallbackInfoReturnable<CompiledRenderPipeline> cir
    ) {
        CompiledRenderPipeline compiled = DynamicRenderer.compiledFor(pipeline);
        if (compiled != null) cir.setReturnValue(compiled);
    }
}
