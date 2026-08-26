package dev.vertex.mixin;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.vertex.render.TerrainMesh;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps vanilla base-vertex assembly consistent with the extended mesh stride. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererTerrainMeshMixin {
    @Redirect(
        method = "extractSectionDrawGroups",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/vertex/VertexFormat;getVertexSize()I"
        )
    )
    private int vertex$meshBaseVertexStride(VertexFormat format) {
        return TerrainMesh.strideFor(format);
    }
}
