package dev.vertex.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.vertex.render.VertexRuntime;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererCaptureMixin {
    @Inject(method = "addMainPass", at = @At("TAIL"))
    private void vertex$captureSections(
        FrameGraphBuilder frame,
        FeatureRenderDispatcher.PreparedFrame featureFrame,
        GpuBufferSlice terrainFog,
        ChunkSectionsToRender sections,
        boolean consistentDepthRequired,
        CallbackInfo ci
    ) {
        VertexRuntime.INSTANCE.setSections(sections);
    }
}
