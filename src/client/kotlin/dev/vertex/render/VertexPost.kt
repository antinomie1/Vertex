package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * G1 切片1：双 pass 复合链（场景色→中间纹理→回屏）。
 * 这就是包运行时 composite 链的机制骨架：纹理间接、多管线、瞬态目标。
 * 效果：vignette + 边缘去饱和（肉眼可辨、不碍事）。
 */
object VertexPost {
    private const val VSH = """#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) out vec2 texCoord;
void main() {
    vec2 uv = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    gl_Position = vec4(uv * vec2(2, 2) - vec2(1, 1), 0.0, 1.0);
    texCoord = uv;
}
"""

    private const val F1 = """#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    float r = length(texCoord - 0.5);
    col = mix(col, vec3(0.1, 0.9, 0.25), 0.35);
    col *= 1.0 - smoothstep(0.35, 1.0, r) * 0.6;
    fragColor = vec4(col, 1.0);
}
"""

    private const val F2 = """#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    fragColor = vec4(texture(InSampler, texCoord).rgb, 1.0);
}
"""

    private var p1: CompiledRenderPipeline? = null
    private var p2: CompiledRenderPipeline? = null
    private var tempTex: GpuTexture? = null
    private var tempView: GpuTextureView? = null
    private var w = 0
    private var h = 0
    private var failed = false
    private var announced = false

    fun drawChain() {
        if (failed) return
        try {
            val device = RenderSystem.getDevice()
            ensurePipelines(device)
            val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            val sceneView = main.colorTextureView ?: return
            ensureTemp(main.width, main.height)
            val tv = tempView ?: return

            val encoder = device.createCommandEncoder()
            val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

            fun pass(label: String, color: GpuTextureView, input: GpuTextureView, pipe: CompiledRenderPipeline) {
                val pass: RenderPass = encoder.createRenderPass({ label }, color, Optional.empty())
                pass.use {
                    RenderSystem.bindDefaultUniforms(it)
                    it.setPipeline(pipe)
                    it.setUniform("InSampler", input, sampler)
                    it.draw(3, 1, 0, 0)
                }
            }
            pass("vertex-post1", tv, sceneView, p1!!)
            pass("vertex-post2", sceneView, tv, p2!!)
            if (!announced) { announced = true; dev.vertex.Vertex.log.info("[Vertex] post chain ACTIVE ({}x{})", w, h) }
        } catch (t: Throwable) {
            failed = true
            dev.vertex.Vertex.log.error("[Vertex] post chain disabled for this session", t)
        }
    }

    private fun ensurePipelines(device: com.mojang.renderpearl.api.device.GpuDevice) {
        if (p1 != null && p2 != null) return
        val source = ShaderSource { id, _ ->
            when (id.path) {
                "post/v" -> VSH
                "post/f1" -> F1
                "post/f2" -> F2
                else -> null
            }
        }
        fun make(frag: String): CompiledRenderPipeline {
            val declarative = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("vertex", "pipeline/$frag"))
                .withVertexShader(Identifier.fromNamespaceAndPath("vertex", "post/v"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("vertex", frag))
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .build()
            return device.compilePipeline(declarative, source)
                ?: throw IllegalStateException("compile failed: $frag")
        }
        p1 = make("post/f1")
        p2 = make("post/f2")
    }

    private fun ensureTemp(width: Int, height: Int) {
        if (tempView != null && w == width && h == height) return
        tempView?.close(); tempTex?.close()
        val device = RenderSystem.getDevice()
        tempTex = device.createTexture(
            { "vertex-post-temp" },
            GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
            GpuFormat.RGBA8_UNORM, width, height, 1, 1
        )
        tempView = device.createTextureView(tempTex!!)
        w = width; h = height
    }
}
