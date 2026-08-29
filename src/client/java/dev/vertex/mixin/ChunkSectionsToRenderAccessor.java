package dev.vertex.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkSectionsToRender.class)
public interface ChunkSectionsToRenderAccessor {
    @Accessor("terrainTransformUBO") GpuBufferSlice vertex$terrainTransformUBO();
}
