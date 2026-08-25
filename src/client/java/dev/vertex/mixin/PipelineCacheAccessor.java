package dev.vertex.mixin;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.blaze3d.pipeline.PipelineCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PipelineCache.class)
public interface PipelineCacheAccessor {
    @Accessor("device")
    GpuDevice vertexDevice();

    @Accessor("shaderSource")
    ShaderSource vertexShaderSource();
}
