package dev.vertex.render

import dev.vertex.Vertex
import dev.vertex.core.VkCore
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents

/**
 * G0 帧缝：LevelRenderEvents.END_MAIN = 世界渲染结束、无打开 pass 的挂接点（DESIGN.md §1）。
 * 本切片只做：一次性 Vulkan 引导 + 存活心跳。绘制注入在下一刀。
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
                frames++
                if (frames % 300L == 0L) {
                    Vertex.log.info("[Vertex] alive: frame {} on '{}'", frames, VkCore.gpuName)
                }
            } catch (t: Throwable) {
                // 遏制原则（DESIGN.md §12.2）：任何引导失败=本会话降档，绝不杀死游戏
                failed = true
                Vertex.log.error("[Vertex] bootstrap failed -> degraded to post-only Tier 0", t)
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
