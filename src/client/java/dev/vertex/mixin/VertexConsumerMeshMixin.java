package dev.vertex.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vertex.render.TerrainMesh;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Injects mc_Entity material payload into QuadInstance before vertex emission. */
@Mixin(VertexConsumer.class)
public interface VertexConsumerMeshMixin {
    @Inject(method = "putBlockBakedQuad", at = @At("HEAD"))
    private void vertex$applyPayload(
        float x,
        float y,
        float z,
        BakedQuad quad,
        QuadInstance instance,
        CallbackInfo ci
    ) {
        TerrainMesh.applyQuadPayload(instance, quad);
    }
}
