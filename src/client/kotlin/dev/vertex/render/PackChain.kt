package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
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
 * G1 切片3a：包链双通道——colortex0（场景色）+ depthtex0（真实场景深度的拷贝）。
 */
object PackChain {
    private var composite: CompiledRenderPipeline? = null
    private var blit: CompiledRenderPipeline? = null
    private var tempTex: GpuTexture? = null
    private var tempView: GpuTextureView? = null
    private var depthTex: GpuTexture? = null
    private var depthView: GpuTextureView? = null
    private var w = 0
    private var h = 0
    private var failed = false
    private var dbgFrame = 0L

    fun draw() {
        if (failed) return
        try {
            val device = RenderSystem.getDevice()
            ensurePipelines(device)
            val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            val sceneView = main.colorTextureView ?: return
            val mainDepth = main.depthTexture ?: return
            ensureSize(device, main.width, main.height)

            val encoder = device.createCommandEncoder()
            // 场景深度 → 我们的 D32 拷贝（END_MAIN 时深度尚未被清除）
            encoder.copyTextureToTexture(mainDepth, depthTex!!, 0, 0, 0, 0, 0, w, h)

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

            val dbg = System.getProperty("vertex.debugReadback") == "true"
            if (dbg && dbgFrame % 120L == 0L) {
                debugReadback(device, main.colorTexture!!, "a-scene-in")
            }

            // P1：包 composite —— 同时采样 colortex0 与 depthtex0
            val packPass: RenderPass = encoder.createRenderPass({ "vertex-pack-composite" }, tv(), Optional.empty())
            packPass.use {
                RenderSystem.bindDefaultUniforms(it)
                it.setPipeline(composite!!)
                it.setUniform("colortex0", sceneView, sampler)
                it.setUniform("depthtex0", depthView!!, sampler)
                it.draw(3, 1, 0, 0)
            }

            // P2：回屏
            pass("vertex-pack-blit", sceneView, tv(), "InSampler", blit!!)

            if (dbg && dbgFrame % 120L == 0L) {
                debugDepthReadback(device, depthTex!!, "depth-copy")
            }
            if (dbg && dbgFrame % 120L == 0L) {
                debugReadback(device, tempTex!!, "b-composite-out")
                debugReadback(device, main.colorTexture!!, "c-screen-final")
                dev.vertex.Vertex.log.info("[Vertex] dbg frame={} paused={}", dbgFrame, Minecraft.getInstance().isPaused)
            }
            dbgFrame++
        } catch (t: Throwable) {
            failed = true
            dev.vertex.Vertex.log.error("[Vertex] pack chain disabled for this session", t)
        }
    }

    private fun tv(): GpuTextureView = tempView!!

    private fun ensureSize(device: com.mojang.renderpearl.api.device.GpuDevice, width: Int, height: Int) {
        if (tempView != null && depthView != null && w == width && h == height) return
        tempView?.close(); tempTex?.close()
        depthView?.close(); depthTex?.close()

        tempTex = device.createTexture(
            { "vertex-pack-temp" },
            GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
            GpuFormat.RGBA8_UNORM, width, height, 1, 1
        )
        tempView = device.createTextureView(tempTex!!)

        depthTex = device.createTexture(
            { "vertex-depth-copy" },
            GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
            GpuFormat.D32_FLOAT, width, height, 1, 1
        )
        depthView = device.createTextureView(depthTex!!)
        w = width; h = height
    }

    private fun ensurePipelines(device: com.mojang.renderpearl.api.device.GpuDevice) {
        if (composite != null && blit != null) return
        val runDir = Minecraft.getInstance().gameDirectory.toPath()
        val packRoot = SamplePack.ensure(runDir.resolve("shaderpacks"))
        val prog = PackFrontend.loadComposite(packRoot)
        dev.vertex.Vertex.log.info("[Vertex] pack loaded: samplers={} varying='{}'", prog.samplers, prog.varyingName)

        val source = ShaderSource { id, _ ->
            when (id.path) {
                "pack/post.v" -> POST_VSH
                "pack/composite.f" -> LegacyTranslator.fragment(prog)
                "pack/blit.f" -> BLIT_FSH
                else -> null
            }
        }
        val packLayout = BindGroupLayout.builder()
            .withUniform("colortex0", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("depthtex0", UniformType.COMBINED_IMAGE_SAMPLER)
            .build()

        composite = compile(
            device, source,
            vs = id("pack/post.v"), fs = id("pack/composite.f"),
            layout = packLayout,
        )
        blit = compile(
            device, source,
            vs = id("pack/post.v"), fs = id("pack/blit.f"),
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

    private fun debugReadback(device: com.mojang.renderpearl.api.device.GpuDevice, tex: GpuTexture, tag: String) {
        val bw = w / 4 * 4
        val bh = h / 4 * 4
        if (bw <= 0 || bh <= 0) return
        val buf = device.createBuffer({ "vertex-dbg" }, GpuBuffer.USAGE_MAP_READ or GpuBuffer.USAGE_COPY_DST, bw.toLong() * bh * 4L)
        device.createCommandEncoder().copyTextureToBuffer(tex, buf, 0L, {
            try {
                buf.map(true, false).use { mv ->
                    val d = mv.data()
                    val bpr = d.limit() / bh
                    var r = 0L; var g = 0L; var b = 0L; var n = 0L
                    for (yy in 0 until bh step bh / 16 + 1) for (xx in 0 until bw step bw / 16 + 1) {
                        val o = yy * bpr + xx * 4
                        r += d.get(o).toLong() and 0xFF; g += d.get(o + 1).toLong() and 0xFF; b += d.get(o + 2).toLong() and 0xFF; n++
                    }
                    if (n > 0) dev.vertex.Vertex.log.info("[Vertex] readback {}: R={} G={} B={} n={}", tag, r / n, g / n, b / n, n)
                }
            } catch (t: Throwable) {
                dev.vertex.Vertex.log.error("[Vertex] readback $tag failed", t)
            } finally { buf.close() }
        }, 0)
    }


    private fun debugDepthReadback(device: com.mojang.renderpearl.api.device.GpuDevice, tex: GpuTexture, tag: String) {
        val bw = w / 4 * 4
        val bh = h / 4 * 4
        if (bw <= 0 || bh <= 0) return
        val buf = device.createBuffer({ "vertex-dbg-d" }, GpuBuffer.USAGE_MAP_READ or GpuBuffer.USAGE_COPY_DST, bw.toLong() * bh * 4L)
        device.createCommandEncoder().copyTextureToBuffer(tex, buf, 0L, {
            try {
                buf.map(true, false).use { mv ->
                    val fb = mv.data().asFloatBuffer()
                    val bpr = fb.capacity() / bh
                    var mn = 1f; var mx = 0f; var sum = 0f; var n = 0
                    for (yy in 0 until bh step bh / 16 + 1) for (xx in 0 until bw step bw / 16 + 1) {
                        val v = fb.get(yy * bpr + xx); mn = minOf(mn, v); mx = maxOf(mx, v); sum += v; n++
                    }
                    if (n > 0) dev.vertex.Vertex.log.info("[Vertex] depth {}: min={} max={} avg={}", tag, mn, mx, sum / n)
                }
            } catch (t: Throwable) {
                dev.vertex.Vertex.log.error("[Vertex] depth readback failed", t)
            } finally { buf.close() }
        }, 0)
    }

    private fun id(path: String) = Identifier.fromNamespaceAndPath("vertex", path)

    private const val POST_VSH = """#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) out vec2 texCoord;

void main() {
    vec2 uv = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    gl_Position = vec4(uv * vec2(2, 2) - vec2(1, 1), 0.0, 1.0);
    texCoord = uv;
}
"""

    private const val BLIT_FSH = """#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() { fragColor = vec4(texture(InSampler, texCoord).rgb, 1.0); }
"""
}
