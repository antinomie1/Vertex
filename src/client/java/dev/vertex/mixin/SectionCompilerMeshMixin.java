package dev.vertex.mixin;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.vertex.render.TerrainMesh;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Substitutes the opaque terrain mesh format before BufferBuilder starts. */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMeshMixin {
    @Redirect(
        method = "getOrBeginLayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;vertexFormat()Lcom/mojang/renderpearl/api/vertex/VertexFormat;"
        )
    )
    private VertexFormat vertex$meshFormat(ChunkSectionLayer layer) {
        return TerrainMesh.formatFor(layer);
    }
}
