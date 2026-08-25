package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import dev.vertex.Vertex
import dev.vertex.core.VkCore
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents

/**
 * G0+G1 帧缝：LevelRenderEvents.END_MAIN（DESIGN.md §1）。
 * 顺序：G0 叠加条带 → G1 复合链（vignette）。失败即本会话降档。
 */
object VertexRenderer {
    private var booted = false
    private var failed = false
    private var frames = 0L

    fun register() {
        Vertex.log.info("[Vertex] register(): wiring seams")
        LevelRenderEvents.END_MAIN.register { _ ->
            if (failed) return@register
            try {
                ensureBoot()
                VertexGpu.drawOverlay()
                VertexPost.drawChain()
                PackChain.draw()
                frames++
                if (frames % 600L == 0L) {
                    Vertex.log.info("[Vertex] alive(level): frame {} on '{}'", frames, VkCore.gpuName)
                }
            } catch (t: Throwable) {
                failed = true
                Vertex.log.error("[Vertex] degraded to Tier 0 for this session", t)
            }
        }
    }

    private fun ensureBoot() {
        if (booted) return
        booted = true
        VkCore.bootstrap()
        Vertex.log.info("[Vertex] VkCore online: gpu='{}' graphicsFamily={}", VkCore.gpuName, VkCore.graphicsFamily)
    }
}
