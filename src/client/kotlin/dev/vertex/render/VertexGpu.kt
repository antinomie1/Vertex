package dev.vertex.render

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.shaders.ShaderSource
import java.util.Optional
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * G0 切片 2：经官方 RenderPipeline/RenderPass 通道把自绘内容写进主目标。
 * 同一 VkDevice（协同驻留）、同一命令流、零内部手术——DESIGN.md §1 的合法注入路径实证。
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

    private var pipeline: com.mojang.blaze3d.pipeline.RenderPipeline? = null
    private var failed = false

    /** 每帧调用（END_MAIN）。首次惰性建管线；失败即本会话静默停用。 */
    fun drawOverlay() {
        if (failed) return
        try {
            val device = RenderSystem.getDevice()
            val p = pipeline ?: buildPipeline(device).also { pipeline = it }
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

    private fun buildPipeline(device: com.mojang.blaze3d.systems.GpuDevice): com.mojang.blaze3d.pipeline.RenderPipeline {
        val source = ShaderSource { id, _ ->
            when (id.path) {
                "g0v" -> VSH
                "g0f" -> FSH
                else -> null
            }
        }
        val pipeline = com.mojang.blaze3d.pipeline.RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation("vertex:pipeline/g0")
            .withVertexShader(Identifier.fromNamespaceAndPath("vertex", "g0v"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("vertex", "g0f"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build()
        val compiled = device.precompilePipeline(pipeline, source)
        check(compiled.isValid) { "[Vertex] G0 pipeline precompile invalid" }
        return pipeline
    }
}
