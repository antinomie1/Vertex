package dev.vertex.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vertex.render.TerrainMesh;
import dev.vertex.render.ShadowRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets prepared mesh pipelines bypass the global vanilla shader cache. */
@Mixin({ChunkSectionsToRender.DrawIndirect.class, ChunkSectionsToRender.DrawSeparate.class})
public abstract class ChunkSectionsToRenderDrawMixin {
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"
        )
    )
    private CompiledRenderPipeline vertex$compiledMeshPipeline(RenderPipeline pipeline) {
        CompiledRenderPipeline compiled = ShadowRenderer.compiledFor(pipeline);
        if (compiled == null) compiled = TerrainMesh.compiledFor(pipeline);
        if (compiled != null) {
            TerrainMesh.noteDrawPath((Object) this instanceof ChunkSectionsToRender.DrawIndirect);
            return compiled;
        }
        return RenderSystem.getCompiledPipeline(pipeline);
    }
}
