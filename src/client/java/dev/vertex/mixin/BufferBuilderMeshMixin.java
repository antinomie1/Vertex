package dev.vertex.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vertex.render.TerrainMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Completes optional terrain attributes after vanilla has emitted a vertex. */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMeshMixin {
    @Inject(method = "addVertex(FFFIFFIIFFF)V", at = @At("RETURN"))
    private void vertex$fillTerrainExtras(
        float x, float y, float z, int color, float u, float v, int overlay, int light,
        float nx, float ny, float nz, CallbackInfo ci
    ) {
        TerrainMesh.fillExtraVertex((VertexConsumer)(Object)this);
    }
}
