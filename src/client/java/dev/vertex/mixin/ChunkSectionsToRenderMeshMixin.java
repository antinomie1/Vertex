package dev.vertex.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vertex.render.TerrainMesh;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Replaces only opaque terrain pipelines; vanilla draw grouping stays untouched. */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMeshMixin {
    @ModifyArgs(
        method = "renderLayers",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;render(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V"
        )
    )
    private void vertex$meshPipeline(Args args) {
        ChunkSectionLayer layer = args.get(0);
        RenderPipeline base = TerrainMesh.pipelineFor(layer, false);
        RenderPipeline multidraw = TerrainMesh.pipelineFor(layer, true);
        if (base != null && multidraw != null) {
            args.set(4, base);
            args.set(5, multidraw);
        }
    }
}
