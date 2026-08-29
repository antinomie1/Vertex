package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
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
import dev.vertex.frontend.PackRuntime
import dev.vertex.translate.LegacyTranslator
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.util.Optional
import org.joml.Vector4f

/**
 * 包运行时核心：colortex0 + depthtex0 + normalsTex 三通道复合链。
 * 不透明地形在主渲染 pass 内由 TerrainMesh 接管，避免二次重绘。
 */
object PackChain {
    private var screenPrograms = emptyList<ScreenProgram>()
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
    private var needsNormals = false
    private var activeColors = emptySet<Int>()
    private val extraTextures = hashMapOf<Int, Array<GpuTexture>>()
    private val extraViews = hashMapOf<Int, Array<GpuTextureView>>()
    private val banks = IntArray(16)

    fun prepare() {
        if (failed) return
        try {
            val device = RenderSystem.getDevice()
            val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            ensureSize(device, main.width, main.height)
            ensurePipelines(device)
            dev.vertex.Vertex.log.info("[Vertex] pack pipelines prewarmed ({}x{})", w, h)
        } catch (t: Throwable) {
            failed = true
            dev.vertex.Vertex.log.error("[Vertex] pack prewarm failed; Tier 0 chain disabled", t)
        }
    }

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
            val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
            val dbg = System.getProperty("vertex.debugReadback") == "true"
            if (dbg && dbgFrame % 120L == 0L) {
                debugColorReadback(device, main.colorTexture!!, "a-scene-in")
            }

            val needsDepth = needsNormals || screenPrograms.any { "depthtex0" in it.samplers }
            if (needsDepth) encoder.copyTextureToTexture(mainDepth, depthTex!!, 0, 0, 0, 0, 0, w, h)
            if (needsNormals) pass(encoder, "vertex-pack-normals", normalView!!) { rp ->
                rp.setPipeline(normals!!)
                rp.setUniform("depthtex0", depthView!!, sampler)
            }

            for (pair in extraTextures.values) for (texture in pair) encoder.clearColorTexture(texture, CLEAR_COLOR)
            banks.fill(0)
            screenPrograms.forEach { program ->
                pass(encoder, "vertex-pack-${program.name}", program.outputs, sceneView) { rp ->
                    RenderSystem.bindDefaultUniforms(rp)
                    rp.setPipeline(program.pipeline)
                    program.samplers.forEach { name -> rp.setUniform(name, samplerView(name, banks, sceneView), sampler) }
                }
                program.outputs.forEach { banks[it] = banks[it] xor 1 }
            }
            val finalColor = colorView(0, banks[0], sceneView)
            if (finalColor !== sceneView) pass(encoder, "vertex-pack-blit", sceneView) { rp ->
                RenderSystem.bindDefaultUniforms(rp)
                rp.setPipeline(blit!!)
                rp.setUniform("InSampler", finalColor, sampler)
            }


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
        encoder.createRenderPass({ label }, color, Optional.empty()).use {
            setup(it)
            it.draw(3, 1, 0, 0)
        }
    }

    private fun pass(
        encoder: CommandEncoder,
        label: String,
        outputs: List<Int>,
        scene: GpuTextureView,
        setup: (RenderPass) -> Unit,
    ) {
        val descriptor = RenderPassDescriptor.builder { label }.also { builder ->
            for (id in outputs) builder.withColorAttachment(colorView(id, banks[id] xor 1, scene))
        }.build()
        encoder.createRenderPass(descriptor).use {
            setup(it)
            it.draw(3, 1, 0, 0)
        }
    }

    private fun ensureSize(device: com.mojang.renderpearl.api.device.GpuDevice, width: Int, height: Int) {
        if (normalView != null && w == width && h == height) return
        listOf(tempView to tempTex, normalView to normalTex, depthView to depthTex).forEach { (v, t) -> v?.close(); t?.close() }
        extraViews.values.forEach { pair -> pair.forEach(GpuTextureView::close) }
        extraTextures.values.forEach { pair -> pair.forEach(GpuTexture::close) }
        extraViews.clear(); extraTextures.clear()
        tempTex = device.createTexture({ "vertex-temp" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
        tempView = device.createTextureView(tempTex!!)
        normalTex = device.createTexture({ "vertex-normals" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
        normalView = device.createTextureView(normalTex!!)
        depthTex = device.createTexture({ "vertex-depth" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, width, height, 1, 1)
        depthView = device.createTextureView(depthTex!!)
        w = width; h = height
        createExtraColors(device)
    }

    private fun ensurePipelines(device: com.mojang.renderpearl.api.device.GpuDevice) {
        if (screenPrograms.isNotEmpty() && blit != null && (!needsNormals || normals != null && builtForW == w && builtForH == h)) return
        val runDir = Minecraft.getInstance().gameDirectory.toPath()
        val packRoot = PackRuntime.root(runDir)
        val programs = PackFrontend.loadScreenChain(packRoot, PackRuntime.options())
        needsNormals = programs.any { "normalsTex" in it.samplers }
        activeColors = (programs.flatMap { it.outputs } + programs.flatMap { it.samplers }.mapNotNull(::colorId)).toSet()
        createExtraColors(device)
        dev.vertex.Vertex.log.info("[Vertex] screen chain loaded ({}/{}): {}", w, h, programs.map { it.name })

        val source = ShaderSource { id, type ->
            when (id.path) {
                "pack/post.v" -> POST_VSH
                "pack/normals.f" -> NORMAL_FSH.replace("__TEXEL__", "vec2(${1.0 / w}, ${1.0 / h})")
                "pack/blit.f" -> BLIT_FSH
                else -> null
            }
        }

        if (needsNormals && (normals == null || builtForW != w || builtForH != h)) {
            normals?.close()
            normals = compile(device, source, id("pack/post.v"), id("pack/normals.f"),
                BindGroupLayout.builder().withUniform("depthtex0", UniformType.COMBINED_IMAGE_SAMPLER).build())
        }
        if (screenPrograms.isEmpty()) screenPrograms = programs.map { program ->
            val samplers = program.samplers.distinct()
            require(samplers.all { colorId(it) != null || it == "depthtex0" || it == "normalsTex" }) {
                "${program.name}: unsupported samplers ${samplers.filterNot { colorId(it) != null || it == "depthtex0" || it == "normalsTex" }}"
            }
            val layout = BindGroupLayout.builder().also { builder ->
                samplers.forEach { builder.withUniform(it, UniformType.COMBINED_IMAGE_SAMPLER) }
            }.build()
            val vs = id("pack/${program.name}.v")
            val fs = id("pack/${program.name}.f")
            val programSource = ShaderSource { _, type -> when (type) {
                com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX -> LegacyTranslator.vertex(program)
                com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT -> LegacyTranslator.fragment(program)
                else -> null
            } }
            ScreenProgram(program.name, compile(device, programSource, vs, fs, layout, program.outputs.size), samplers, program.outputs)
        }
        if (blit == null) blit = compile(device, source, id("pack/post.v"), id("pack/blit.f"), BindGroupLayouts.IN_SAMPLER)

        builtForW = w; builtForH = h
    }

    private fun samplerView(name: String, banks: IntArray, scene: GpuTextureView): GpuTextureView = when (name) {
        "depthtex0" -> depthView!!
        "normalsTex" -> normalView!!
        else -> colorId(name)?.let { colorView(it, banks[it], scene) } ?: error("unsupported sampler '$name'")
    }

    private fun colorView(id: Int, bank: Int, scene: GpuTextureView): GpuTextureView = when {
        id == 0 && bank == 0 -> scene
        id == 0 -> tempView!!
        else -> extraViews.getValue(id)[bank]
    }

    private fun createExtraColors(device: com.mojang.renderpearl.api.device.GpuDevice) {
        (activeColors - 0).filterNot(extraTextures::containsKey).forEach { id ->
            val textures = Array(2) { bank -> device.createTexture(
                { "vertex-colortex$id-$bank" },
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, w, h, 1, 1,
            ) }
            extraTextures[id] = textures
            extraViews[id] = Array(2) { device.createTextureView(textures[it]) }
        }
    }

    private fun colorId(name: String): Int? = when (name) {
        "gcolor" -> 0; "gdepth" -> 1; "gnormal" -> 2; "composite" -> 3
        "gaux1" -> 4; "gaux2" -> 5; "gaux3" -> 6; "gaux4" -> 7
        else -> COLORTEX.matchEntire(name)?.groupValues?.get(1)?.toInt()
    }

    private fun compile(
        device: com.mojang.renderpearl.api.device.GpuDevice,
        source: ShaderSource,
        vs: Identifier,
        fs: Identifier,
        layout: BindGroupLayout,
        colorTargets: Int = 1,
    ): CompiledRenderPipeline {
        val declarative = com.mojang.renderpearl.api.pipeline.RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("vertex", fs.path.substringAfterLast('/')))
            .withVertexShader(vs)
            .withFragmentShader(fs)
            .withBindGroupLayout(layout)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetStates(0, colorTargets - 1) {
                ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)
            }
            .build()
        return device.compilePipeline(declarative, source)
            ?: throw IllegalStateException("compile failed: ${fs.path}")
    }


    private fun id(path: String) = Identifier.fromNamespaceAndPath("vertex", path)

    private data class ScreenProgram(
        val name: String,
        val pipeline: CompiledRenderPipeline,
        val samplers: List<String>,
        val outputs: List<Int>,
    )

    private val COLORTEX = Regex("""colortex(\d|1[0-5])""")
    private val CLEAR_COLOR = Vector4f(0f, 0f, 0f, 0f)

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
