package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.CommandEncoder
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
 * 包运行时核心：colortex0 + depthtex0 + normalsTex 三通道复合链。
 * 不透明地形在主渲染 pass 内由 TerrainMesh 接管，避免二次重绘。
 */
object PackChain {
    private var composite: CompiledRenderPipeline? = null
    private var blit: CompiledRenderPipeline? = null
    private var normals: CompiledRenderPipeline? = null
    private var tempTex: GpuTexture? = null
    private var tempView: GpuTextureView? = null
    private var depthTex: GpuTexture? = null
    private var depthView: GpuTextureView? = null
    private var normalTex: GpuTexture? = null
    private var normalView: GpuTextureView? = null
    private var w = 0
    private var h = 0
    private var builtForW = 0
    private var builtForH = 0
    private var failed = false
    private var dbgFrame = 0L

    fun draw() {
        if (failed) return
        try {
            val device = RenderSystem.getDevice()
            val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            val sceneView = main.colorTextureView ?: return
            val mainDepth = main.depthTexture ?: return
            ensureSize(device, main.width, main.height)
            ensurePipelines(device)

            val encoder = device.createCommandEncoder()
            // 场景深度拷贝（END_MAIN 时深度尚未清除）
            encoder.copyTextureToTexture(mainDepth, depthTex!!, 0, 0, 0, 0, 0, w, h)

            val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
            val dbg = System.getProperty("vertex.debugReadback") == "true"
            if (dbg && dbgFrame % 120L == 0L) {
                debugColorReadback(device, main.colorTexture!!, "a-scene-in")
            }

            // N：深度→法线
            pass(encoder, "vertex-pack-normals", normalView!!, { rp ->
                rp.setPipeline(normals!!)
                rp.setUniform("depthtex0", depthView!!, sampler)
            })

            // P1：包 composite —— albedo + 深度雾 + 法线光照
            pass(encoder, "vertex-pack-composite", tempView!!, { rp ->
                RenderSystem.bindDefaultUniforms(rp)
                rp.setPipeline(composite!!)
                rp.setUniform("colortex0", sceneView, sampler)
                rp.setUniform("depthtex0", depthView!!, sampler)
                rp.setUniform("normalsTex", normalView!!, sampler)
            })

            // P2：回屏
            pass(encoder, "vertex-pack-blit", sceneView, { rp ->
                RenderSystem.bindDefaultUniforms(rp)
                rp.setPipeline(blit!!)
                rp.setUniform("InSampler", tempView!!, sampler)
            })


            if (dbg && dbgFrame % 120L == 0L) {
                debugColorReadback(device, tempTex!!, "b-composite-out")
                debugColorReadback(device, main.colorTexture!!, "c-screen-final")
                dev.vertex.Vertex.log.info("[Vertex] dbg frame={} paused={}", dbgFrame, Minecraft.getInstance().isPaused)
            }
            dbgFrame++
        } catch (t: Throwable) {
            failed = true
            dev.vertex.Vertex.log.error("[Vertex] pack chain disabled for this session", t)
        }
    }


    private fun debugColorReadback(device: com.mojang.renderpearl.api.device.GpuDevice, tex: GpuTexture, tag: String) {
        val bw = w / 4 * 4
        val bh = h / 4 * 4
        if (bw <= 0 || bh <= 0) return
        val buf = device.createBuffer({ "vertex-dbg" }, GpuBuffer.USAGE_MAP_READ or GpuBuffer.USAGE_COPY_DST, bw.toLong() * bh * 4L)
        val callback = java.lang.Runnable {
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
        }
        device.createCommandEncoder().copyTextureToBuffer(tex, buf, 0L, callback, 0)
    }


    private fun pass(
        encoder: CommandEncoder,
        label: String,
        color: GpuTextureView,
        setup: (RenderPass) -> Unit,
    ) {
        val renderPass: RenderPass = encoder.createRenderPass({ label }, color, Optional.empty())
        renderPass.use {
            setup(it)
            it.draw(3, 1, 0, 0)
        }
    }

    private fun ensureSize(device: com.mojang.renderpearl.api.device.GpuDevice, width: Int, height: Int) {
        if (normalView != null && w == width && h == height) return
        listOf(tempView to tempTex, normalView to normalTex, depthView to depthTex).forEach { (v, t) -> v?.close(); t?.close() }
        tempTex = device.createTexture({ "vertex-temp" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
        tempView = device.createTextureView(tempTex!!)
        normalTex = device.createTexture({ "vertex-normals" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
        normalView = device.createTextureView(normalTex!!)
        depthTex = device.createTexture({ "vertex-depth" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, width, height, 1, 1)
        depthView = device.createTextureView(depthTex!!)
        w = width; h = height
    }

    private fun ensurePipelines(device: com.mojang.renderpearl.api.device.GpuDevice) {
        if (composite != null && blit != null && normals != null && builtForW == w && builtForH == h) return
        val runDir = Minecraft.getInstance().gameDirectory.toPath()
        val packRoot = SamplePack.ensure(runDir.resolve("shaderpacks"))
        val prog = PackFrontend.loadComposite(packRoot)
        dev.vertex.Vertex.log.info("[Vertex] pack loaded ({}/{}): samplers={} varying='{}'", w, h, prog.samplers, prog.varyingName)

        val source = ShaderSource { id, type ->
            dev.vertex.Vertex.log.info("[Vertex] src-query: {} ({}) type={}", id, id.namespace, type)
            val out = when (id.path) {
                "pack/post.v" -> POST_VSH
                "pack/normals.f" -> NORMAL_FSH.replace("__TEXEL__", "vec2(${1.0 / w}, ${1.0 / h})")
                "pack/composite.f" -> LegacyTranslator.fragment(prog)
                "pack/blit.f" -> BLIT_FSH
                else -> null
            }
            dev.vertex.Vertex.log.info("[Vertex] src-answer: {} -> {}", id, if (out != null) "HIT" else "MISS")
            out
        }

        normals = compile(device, source, id("pack/post.v"), id("pack/normals.f"),
            BindGroupLayout.builder().withUniform("depthtex0", UniformType.COMBINED_IMAGE_SAMPLER).build())
        composite = compile(device, source, id("pack/post.v"), id("pack/composite.f"),
            BindGroupLayout.builder()
                .withUniform("colortex0", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("depthtex0", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("normalsTex", UniformType.COMBINED_IMAGE_SAMPLER)
                .build())
        blit = compile(device, source, id("pack/post.v"), id("pack/blit.f"),
            BindGroupLayouts.IN_SAMPLER)

        builtForW = w; builtForH = h
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

    private const val POST_VSH = """#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) out vec2 texCoord;

void main() {
    vec2 uv = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    gl_Position = vec4(uv * vec2(2, 2) - vec2(1, 1), 0.0, 1.0);
    texCoord = uv;
}
"""

    // 深度梯度→法线；__TEXEL__ 编译期注入像素尺寸
    private const val NORMAL_FSH = """#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D depthtex0;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
const vec2 TEXEL = __TEXEL__;
void main() {
    float dC = texture(depthtex0, texCoord).r;
    float dX = texture(depthtex0, texCoord + vec2(TEXEL.x, 0.0)).r;
    float dY = texture(depthtex0, texCoord + vec2(0.0, TEXEL.y)).r;
    float k = 24.0;
    vec3 n = normalize(vec3((dC - dX) * k, (dC - dY) * k, 1.0));
    n = normalize(n * 2.0 - 1.0);
    fragColor = vec4(n * 0.5 + 0.5, 1.0);
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
