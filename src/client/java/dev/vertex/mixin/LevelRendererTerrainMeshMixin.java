package dev.vertex.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vertex.render.TerrainMesh;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Supplies the custom pipeline (and its bound vertex format/stride) to draw group extraction. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererTerrainMeshMixin {
    @Shadow
    private boolean usingMultiDrawIndirectForTerrain;

    @Inject(
        method = "render",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;usingMultiDrawIndirectForTerrain:Z",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD,
            shift = At.Shift.AFTER
        )
    )
    private void vertex$overrideDrawMode(CallbackInfo ci) {
        String mode = System.getProperty("vertex.drawMode");
        if ("separate".equalsIgnoreCase(mode)) {
            this.usingMultiDrawIndirectForTerrain = false;
        } else if ("indirect".equalsIgnoreCase(mode)) {
            this.usingMultiDrawIndirectForTerrain = true;
        }
    }

    @Redirect(
        method = "extractSectionDrawGroups",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;pipeline(Z)Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
        )
    )
    private RenderPipeline vertex$drawGroupPipeline(net.minecraft.client.renderer.chunk.ChunkSectionLayer layer, boolean multidraw) {
        RenderPipeline custom = TerrainMesh.pipelineFor(layer, multidraw);
        return custom != null ? custom : layer.pipeline(multidraw);
    }
}
