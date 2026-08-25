package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import dev.vertex.Vertex
import dev.vertex.core.VkCore
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents

/**
 * G0 帧缝：LevelRenderEvents.END_MAIN（DESIGN.md §1）。
 * 诊断双通道：tick 心跳 × 渲染事件互为对照；后端名打印定位 GL/VK。
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
                VertexGpu.drawOverlay()
                frames++
                if (frames % 300L == 0L) {
                    Vertex.log.info("[Vertex] alive(level): frame {} on '{}'", frames, VkCore.gpuName)
                }
            } catch (t: Throwable) {
                failed = true
                Vertex.log.error("[Vertex] degraded to Tier 0 for this session", t)
            }
        }
        // 对照探针：渲染状态失效回调（旧族幸存者），验证事件总线本身是否接通
        InvalidateRenderStateCallback.EVENT.register {
            if (!failed && booted && frames == 0L) {
                Vertex.log.info("[Vertex] diag: render-state invalidated, but END_MAIN never fired")
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            ticks++
            if (!failed && ticks % 600L == 0L) {
                Vertex.log.info("[Vertex] alive(tick): {} ticks, level-frames={}", ticks, frames)
            }
            if (ticks == 200L && !booted) {
                val backend = try {
                    RenderSystem.getDevice().deviceInfo.backendName()
                } catch (t: Throwable) {
                    "<n/a: ${t.message}"
                }
                Vertex.log.info("[Vertex] diag @200t: backend='{}' levelFrames={}", backend, frames)
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
