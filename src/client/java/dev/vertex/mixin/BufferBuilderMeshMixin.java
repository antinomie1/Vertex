package dev.vertex.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vertex.render.TerrainMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Completes optional terrain attributes after vanilla has emitted a vertex. */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMeshMixin {
    @Shadow private long vertexPointer;

    @Inject(method = "endLastVertex", at = @At("HEAD"))
    private void vertex$finishTerrainVertex(CallbackInfo ci) {
        if (!TerrainMesh.isPrepared() || vertexPointer == -1L) return;
        VertexConsumer consumer = (VertexConsumer)(Object)this;
        consumer.setNormal(0.0f, 1.0f, 0.0f);
    }

    @Inject(method = "addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At("RETURN"))
    private void vertex$fillTerrainDefaults(float x, float y, float z, CallbackInfoReturnable<VertexConsumer> cir) {
        if (!TerrainMesh.isPrepared()) return;
        TerrainMesh.fillExtraVertex((VertexConsumer)(Object)this);
        // Fluid tessellation does not emit a normal or a second color. Keep the
        // widened terrain contract complete without changing the vanilla writer.
        ((VertexConsumer)(Object)this).setNormal(0.0f, 1.0f, 0.0f);
    }

    @Inject(method = "addVertex(FFFIFFIIFFF)V", at = @At("RETURN"))
    private void vertex$fillTerrainExtras(
        float x, float y, float z, int color, float u, float v, int overlay, int light,
        float nx, float ny, float nz, CallbackInfo ci
    ) {
        TerrainMesh.fillExtraVertex((VertexConsumer)(Object)this);
    }
}
