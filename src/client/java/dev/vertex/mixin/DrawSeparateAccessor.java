package dev.vertex.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkSectionsToRender.DrawSeparate.class)
public interface DrawSeparateAccessor {
    @Accessor("drawsPerLayer")
    Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> vertex$drawsPerLayer();
}
