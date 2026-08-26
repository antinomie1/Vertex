package dev.vertex.mixin;

import dev.vertex.render.TerrainMesh;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks current BlockState for mc_Entity material payload emission. */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMeshMixin {
    @Inject(method = "tesselateBlock", at = @At("HEAD"))
    private void vertex$trackBlockStateHead(
        BlockQuadOutput output,
        float x,
        float y,
        float z,
        BlockAndTintGetter level,
        BlockPos pos,
        BlockState blockState,
        BlockStateModel model,
        long seed,
        CallbackInfo ci
    ) {
        TerrainMesh.setCurrentBlock(blockState);
    }

    @Inject(method = "tesselateBlock", at = @At("RETURN"))
    private void vertex$trackBlockStateReturn(
        BlockQuadOutput output,
        float x,
        float y,
        float z,
        BlockAndTintGetter level,
        BlockPos pos,
        BlockState blockState,
        BlockStateModel model,
        long seed,
        CallbackInfo ci
    ) {
        TerrainMesh.clearCurrentBlock();
    }
}
