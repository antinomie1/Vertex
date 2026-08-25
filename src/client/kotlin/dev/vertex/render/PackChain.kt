package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.vertex.frontend.PackFrontend
import dev.vertex.frontend.SamplePack
import dev.vertex.translate.LegacyTranslator
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * G1 切片2：真包加载——composite 程序经翻译器进链，场景色作 colortex0。
 */
object PackChain {
    private var composite: CompiledRenderPipeline? = null
    private var blit: CompiledRenderPipeline? = null
    private var tempTex: com.mojang.renderpearl.api.textures.GpuTexture? = null
    private var tempView: GpuTextureView? = null
    private var w = 0
    private var h = 0
    private var failed = false

    fun draw() {
        if (failed) return
        try {
            val device = RenderSystem.getDevice()
            ensurePipelines(device)
            val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            val sceneView = main.colorTextureView ?: return
            ensureTemp(device, main.width, main.height)
            val tv = tempView ?: return

            val encoder = device.createCommandEncoder()
            val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

            fun pass(label: String, color: GpuTextureView, input: GpuTextureView, samplerName: String, pipe: CompiledRenderPipeline) {
                val pass: RenderPass = encoder.createRenderPass({ label }, color, Optional.empty())
                pass.use {
                    RenderSystem.bindDefaultUniforms(it)
                    it.setPipeline(pipe)
                    it.setUniform(samplerName, input, sampler)
                    it.draw(3, 1, 0, 0)
                }
            }
            pass("vertex-pack-composite", tv, sceneView, "colortex0", composite!!)
            pass("vertex-pack-blit", sceneView, tv, "InSampler", blit!!)
        } catch (t: Throwable) {
            failed = true
            dev.vertex.Vertex.log.error("[Vertex] pack chain disabled for this session", t)
        }
    }

    private fun ensurePipelines(device: com.mojang.renderpearl.api.device.GpuDevice) {
        if (composite != null && blit != null) return
        val runDir = Minecraft.getInstance().gameDirectory.toPath()
        val packRoot = SamplePack.ensure(runDir.resolve("shaderpacks"))
        val prog = PackFrontend.loadComposite(packRoot)
        dev.vertex.Vertex.log.info("[Vertex] pack loaded: samplers={} varying='{}'", prog.samplers, prog.varyingName)

        val source = ShaderSource { id, _ ->
            when (id.path) {
                "pack/composite.v" -> LegacyTranslator.vertex(prog)
                "pack/composite.f" -> LegacyTranslator.fragment(prog)
                "pack/blit.f" -> """#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() { fragColor = vec4(texture(InSampler, texCoord).rgb, 1.0); }
"""
                else -> null
            }
        }

        composite = compile(
            device, source,
            id("pack/composite.v"), id("pack/composite.f"),
            layout = BindGroupLayout.builder()
                .withUniform("colortex0", UniformType.COMBINED_IMAGE_SAMPLER)
                .build(),
        )
        blit = compile(
            device, source,
            id("pack/blit.v"), id("pack/blit.f"),
            layout = BindGroupLayouts.IN_SAMPLER,
        )
    }

    private fun compile(
        device: com.mojang.renderpearl.api.device.GpuDevice,
        source: ShaderSource,
        vs: Identifier,
        fs: Identifier,
        layout: BindGroupLayout,
    ): CompiledRenderPipeline {
        val declarative = com.mojang.renderpearl.api.pipeline.RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("vertex", fs.path.substringAfterLast('/')))
            .withVertexShader(vs)
            .withFragmentShader(fs)
            .withBindGroupLayout(layout)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build()
        return device.compilePipeline(declarative, source)
            ?: throw IllegalStateException("compile failed: ${fs.path}")
    }

    private fun id(path: String) = Identifier.fromNamespaceAndPath("vertex", path)

    private fun ensureTemp(device: com.mojang.renderpearl.api.device.GpuDevice, width: Int, height: Int) {
        if (tempView != null && w == width && h == height) return
        tempView?.close(); tempTex?.close()
        tempTex = device.createTexture(
            { "vertex-pack-temp" },
            com.mojang.renderpearl.api.textures.GpuTexture.USAGE_TEXTURE_BINDING or
                com.mojang.renderpearl.api.textures.GpuTexture.USAGE_RENDER_ATTACHMENT,
            GpuFormat.RGBA8_UNORM, width, height, 1, 1
        )
        tempView = device.createTextureView(tempTex!!)
        w = width; h = height
    }
}
