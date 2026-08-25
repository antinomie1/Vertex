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
 * G0 切片2（26.3-snapshot-9 / RenderPearl 版）：
 * 自定义 GLSL 经 compilePipeline(pipeline, ShaderSource) 编译，
 * createRenderPass 写入主目标——官方通道，同设备零手术。
 */
object VertexGpu {
    private const val VSH = """#version 330
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * vec2(2, 2) - vec2(1, 1), 0.0, 1.0);
}
"""

    // 底部 48px 绿色条带（x 向渐变）——可见性证明，且不糊脸
    private const val FSH = """#version 330
out vec4 fragColor;
void main() {
    if (gl_FragCoord.y > 48.0) discard;
    float t = clamp(gl_FragCoord.x / 1600.0, 0.0, 1.0);
    fragColor = vec4(0.05 + 0.35 * t, 0.9, 0.4, 1.0);
}
"""

    private var compiledPipeline: CompiledRenderPipeline? = null
    private var failed = false

    /** 每帧调用（END_MAIN）。首次惰性编译；失败即本会话静默停用。 */
    fun drawOverlay() {
        if (failed) return
        try {
            val device = RenderSystem.getDevice()
            val p = compiledPipeline ?: buildPipeline(device).also { compiledPipeline = it }
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
        val source = ShaderSource { id, _ ->
            when (id.path) {
                "g0v" -> VSH
                "g0f" -> FSH
                else -> null
            }
        }
        val declarative = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation("vertex:pipeline/g0")
            .withVertexShader(Identifier.fromNamespaceAndPath("vertex", "g0v"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("vertex", "g0f"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build()
        val compiled = device.compilePipeline(declarative, source)
        check(compiled != null && !compiled.isClosed()) { "[Vertex] G0 pipeline failed to compile" }
        return compiled
    }
}
