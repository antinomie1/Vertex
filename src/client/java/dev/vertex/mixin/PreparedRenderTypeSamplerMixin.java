package dev.vertex.mixin;

import dev.vertex.render.PackChain;
import dev.vertex.render.TerrainMesh;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Supplies shader-pack-only samplers alongside RenderType's texture bindings. */
@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeSamplerMixin {
    @Shadow
    public abstract java.util.List<PreparedRenderType.Texture> textures();

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
        if (TerrainMesh.isMultidrawPipeline(pipeline) != null) {
            textures().stream()
                .filter(texture -> "Sampler0".equals(texture.name()))
                .findFirst()
                .ifPresent(texture -> PackChain.bindTerrainAtlas(pass, texture.textureView()));
        }
    }
}
