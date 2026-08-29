package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import dev.vertex.Vertex
import dev.vertex.core.SharedVulkanContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft

/**
 * 世界渲染后的唯一生产帧缝；HUD 由游戏在同一队列上随后合成。
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
                PackChain.draw()
                frames++
                val stopAfter = System.getProperty("vertex.autostop")?.toLongOrNull()
                if (stopAfter != null && frames >= stopAfter * 60L) {
                    Vertex.log.info("[Vertex] autotest complete -> clean shutdown")
                    Minecraft.getInstance().stop()
                }
                if (frames % 600L == 0L) {
                    Vertex.log.info("[Vertex] alive(level): frame {} on '{}'", frames, SharedVulkanContext.attach().gpuName)
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
        val context = SharedVulkanContext.attach()
        Vertex.log.info(
            "[Vertex] shared Vulkan device: gpu='{}' graphicsFamily={} tiers={}",
            context.gpuName, context.graphicsFamily, context.decisions.mapValues { it.value.tier },
        )
        context.decisions.filterValues { it.tier != dev.vertex.runtime.RenderTier.TIER_2 }
            .forEach { (family, decision) -> Vertex.log.warn("[Vertex] {} -> {}: {}", family, decision.tier, decision.reason) }
    }
}
