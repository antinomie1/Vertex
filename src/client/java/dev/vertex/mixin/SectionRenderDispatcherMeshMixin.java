package dev.vertex.mixin;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.vertex.render.TerrainMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the vanilla uber-buffer allocator aligned with the extended mesh. */
@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherMeshMixin {
    @Inject(method = "lambda$new$0", at = @At("HEAD"))
    private void vertex$prepareTerrain(ChunkSectionLayer layer, CallbackInfoReturnable<?> cir) {
        TerrainMesh.prepare();
    }
    @Redirect(
        method = "lambda$new$0",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/vertex/VertexFormat;getVertexSize()I"
        )
    )
    private int vertex$meshStride(VertexFormat format) {
        return TerrainMesh.strideFor(format);
    }
}
