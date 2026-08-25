package dev.vertex.render

import net.minecraft.client.renderer.chunk.ChunkSectionsToRender

/** 跨 mixin/Kotlin 的帧级数据持有点。 */
object VertexRuntime {
    @Volatile
    var sections: ChunkSectionsToRender? = null
}
