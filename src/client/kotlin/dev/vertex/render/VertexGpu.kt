package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * G0 切片2（26.3-snapshot-9 / RenderPearl）：
 * 自定义 GLSL 经 compilePipeline(pipeline, ShaderSource) 编译，createRenderPass 写入主目标。
 */
object VertexGpu {
    private const val VSH = """#version 330
void main() {
    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    gl_Position = vec4(p * vec2(2, 2) - vec2(1, 1), 0.0, 1.0);
}
"""

    // 底部 48px 绿色条带（x 向渐变）——可见性证明，且不糊脸
    private const val FSH = """#version 330
layout(location = 0) out vec4 fragColor;
void main() {
    if (gl_FragCoord.y > 48.0) discard;
    float t = clamp(gl_FragCoord.x / 1600.0, 0.0, 1.0);
    fragColor = vec4(0.05 + 0.35 * t, 0.9, 0.4, 1.0);
}
"""

    private var compiledPipeline: CompiledRenderPipeline? = null
    private var failed = false

    /** 菜单期即可调用：只编译不绘制。返回是否就绪。 */
    fun ensureCompiled(): Boolean {
        if (compiledPipeline != null) return true
        if (failed) return false
        return try {
            compiledPipeline = buildPipeline(RenderSystem.getDevice())
            dev.vertex.Vertex.log.info("[Vertex] G0 pipeline compiled OK")
            true
        } catch (t: Throwable) {
            failed = true
            dev.vertex.Vertex.log.error("[Vertex] G0 pipeline compile failed", t)
            false
        }
    }

    /** 世界内每帧调用（END_MAIN）。 */
    fun drawOverlay() {
        if (!ensureCompiled()) return
        try {
            val device = RenderSystem.getDevice()
            val p = compiledPipeline!!
            val view = Minecraft.getInstance().gameRenderer.mainRenderTarget().colorTextureView
                ?: return
            val encoder = device.createCommandEncoder()
            val pass: RenderPass = encoder.createRenderPass({ "vertex-g0-overlay" }, view, Optional.empty())
            pass.use {
                it.setPipeline(p)
                it.draw(3, 1, 0, 0)
            }
        } catch (t: Throwable) {
            failed = true
            dev.vertex.Vertex.log.error("[Vertex] G0 overlay disabled for this session", t)
        }
    }

    private fun buildPipeline(device: com.mojang.renderpearl.api.device.GpuDevice): CompiledRenderPipeline {
        val source = ShaderSource { id, type ->
            dev.vertex.Vertex.log.info("[Vertex] shader request: id={} type={}", id, type)
            when (id.path) {
                "g0v" -> VSH
                "g0f" -> FSH
                else -> null
            }
        }
        val declarative = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("vertex", "pipeline/g0"))
            .withVertexShader(Identifier.fromNamespaceAndPath("vertex", "g0v"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("vertex", "g0f"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build()
        val compiled = device.compilePipeline(declarative, source)
        if (compiled == null || compiled.isClosed()) {
            dev.vertex.Vertex.log.error("[Vertex] compilePipeline returned null/closed")
        }
        return compiled ?: throw IllegalStateException("compilePipeline null")
    }
}
