package dev.vertex.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 暴露地形重绘所需：protected 抽象 render(...)、变换 UBO、索引规模。 */
@Mixin(ChunkSectionsToRender.class)
public interface ChunkSectionsToRenderInvoker {
    @Invoker("render")
    void invokeRender(
        ChunkSectionLayer layer,
        RenderPass renderPass,
        GpuBuffer defaultIndexBuffer,
        IndexType defaultIndexType,
        RenderPipeline renderPipelineOverride,
        RenderPipeline renderPipelineOverrideMultidraw
    );

    @Accessor("terrainTransformUBO")
    GpuBufferSlice vertexTerrainTransformUbo();

    @Accessor("maxIndicesRequired")
    int vertexMaxIndicesRequired();
}
