package dev.vertex.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkSectionsToRender.DrawIndirect.class)
public interface DrawIndirectAccessor {
    @Accessor("drawGroupsPerLayer")
    EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> vertex$drawGroups();
    @Accessor("chunkSectionInfos") GpuBufferSlice vertex$chunkSectionInfos();
}
