package dev.vertex.render

import dev.vertex.Vertex
import dev.vertex.core.VkCore
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents

/**
 * G0 帧缝：LevelRenderEvents.END_MAIN（DESIGN.md §1）。
 * 切片1：自有 Vulkan 栈引导 + 心跳。切片2：官方通道主目标叠加注入。
 */
object VertexRenderer {
    private var booted = false
    private var failed = false
    private var frames = 0L

    fun register() {
        LevelRenderEvents.END_MAIN.register { _ ->
            if (failed) return@register
            try {
                ensureBoot()
                VertexGpu.drawOverlay()
                frames++
                if (frames % 300L == 0L) {
                    Vertex.log.info("[Vertex] alive: frame {} on '{}'", frames, VkCore.gpuName)
                }
            } catch (t: Throwable) {
                // 遏制原则（DESIGN.md §12.2）：失败=本会话降档，绝不杀死游戏
                failed = true
                Vertex.log.error("[Vertex] degraded to Tier 0 for this session", t)
            }
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register { VkCore.shutdown() }
    }

    private fun ensureBoot() {
        if (booted) return
        booted = true
        VkCore.bootstrap()
        Vertex.log.info("[Vertex] VkCore online: gpu='{}' graphicsFamily={}", VkCore.gpuName, VkCore.graphicsFamily)
    }
}
