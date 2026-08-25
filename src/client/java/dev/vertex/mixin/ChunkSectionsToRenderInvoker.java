package dev.vertex.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 暴露 protected 抽象 render(...)，让包程序以管线覆盖方式重绘地形层。 */
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
}
