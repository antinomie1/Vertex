package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import dev.vertex.Vertex
import dev.vertex.core.VkCore
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents

/**
 * G0 帧缝：LevelRenderEvents.END_MAIN（DESIGN.md §1）。
 * 切片1：自有 Vulkan 栈引导 + 心跳。切片2：官方通道主目标叠加注入。
 * 诊断：tick@200 在菜单期完成管线编译验证，无需进世界。
 */
object VertexRenderer {
    private var booted = false
    private var failed = false
    private var frames = 0L
    private var ticks = 0L

    fun register() {
        Vertex.log.info("[Vertex] register(): wiring seams")
        LevelRenderEvents.END_MAIN.register { _ ->
            if (failed) return@register
            try {
                ensureBoot()
                if (VertexGpu.ensureCompiled()) {
                    VertexGpu.drawOverlay()
                    frames++
                    if (frames % 300L == 0L) {
                        Vertex.log.info("[Vertex] alive(level): frame {} on '{}'", frames, VkCore.gpuName)
                    }
                }
            } catch (t: Throwable) {
                failed = true
                Vertex.log.error("[Vertex] degraded to Tier 0 for this session", t)
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            ticks++
            if (!failed && ticks % 600L == 0L && booted) {
                Vertex.log.info("[Vertex] alive(tick): {} ticks", ticks)
            }
            if (ticks == 200L) {
                ensureBoot()
                val backend = try {
                    RenderSystem.getDevice().deviceInfo.backendName()
                } catch (t: Throwable) {
                    "<n/a: ${t.message}>"
                }
                Vertex.log.info(
                    "[Vertex] diag @200t: backend='{}' compiled={}",
                    backend,
                    try { VertexGpu.ensureCompiled() } catch (t: Throwable) { "ERR $t" }
                )
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
