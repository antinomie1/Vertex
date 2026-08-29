package dev.vertex.mixin;

import dev.vertex.render.PackChain;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Supplies shader-pack-only samplers alongside RenderType's texture bindings. */
@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeSamplerMixin {
    @Inject(
        method = "draw",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setIndexBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;)V",
            shift = At.Shift.BEFORE
        )
    )
    private void vertex$bindPackSamplers(
        StagedVertexBuffer.ExecuteInfo info,
        RenderPass pass,
        RenderPipeline pipeline,
        CallbackInfo ci
    ) {
        PackChain.bindDynamicSamplers(pass);
    }
}
