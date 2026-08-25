package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
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
 * G1 切片3：包链三通道——colortex0 + depthtex0（真实深度拷贝）+ normalsTex（深度派生法线）。
 * 不接管地形绘制：官方优化路径原样运行，gbuffer 数据以附加 pass 方式补齐。
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

            fun pass(
                label: String,
                color: GpuTextureView,
                input: GpuTextureView?,
                samplerName: String?,
                pipe: CompiledRenderPipeline,
                extraInput: GpuTextureView? = null,
                extraName: String? = null,
            ) {
                val pass: RenderPass = encoder.createRenderPass({ label }, color, Optional.empty())
                pass.use {
                    RenderSystem.bindDefaultUniforms(it)
                    it.setPipeline(pipe)
                    if (input != null && samplerName != null) it.setUniform(samplerName, input, sampler)
                    if (extraInput != null && extraName != null) it.setUniform(extraName, extraInput, sampler)
                    it.draw(3, 1, 0, 0)
                }
            }

            // N：深度→法线（屏幕空间求导，体素平面场景效果良好）
            pass("vertex-pack-normals", normalView!!, null, null, normals!!)

            // P1：包 composite —— albedo + 深度 + 法线
            pass("vertex-pack-composite", tempView!!, sceneView, "colortex0", composite!!, normalView!!, "normalsTex")

            // P2：回屏
            pass("vertex-pack-blit", sceneView, tempView!!, "InSampler", blit!!)
        } catch (t: Throwable) {
            failed = true
            dev.vertex.Vertex.log.error("[Vertex] pack chain disabled for this session", t)
        }
    }

    private fun ensureSize(device: com.mojang.renderpearl.api.device.GpuDevice, width: Int, height: Int) {
        if (normalView != null && w == width && h == height) return
        listOf(tempView to tempTex, normalView to normalTex, depthView to depthTex).forEach { (v, t) ->
            v?.close(); t?.close()
        }
        tempTex = device.createTexture({ "vertex-temp" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
        tempView = device.createTextureView(tempTex!!)
        normalTex = device.createTexture({ "vertex-normals" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
        normalView = device.createTextureView(normalTex!!)
        depthTex = device.createTexture({ "vertex-depth" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, width, height, 1, 1)
        depthView = device.createTextureView(depthTex!!)
        w = width; h = height
    }

    /** 分辨率变化时重建含分辨率常量的管线。 */
    private fun ensurePipelines(device: com.mojang.renderpearl.api.device.GpuDevice) {
        if (composite != null && blit != null && builtForW == w && builtForH == h) return
        val runDir = Minecraft.getInstance().gameDirectory.toPath()
        val packRoot = SamplePack.ensure(runDir.resolve("shaderpacks"))
        val prog = PackFrontend.loadComposite(packRoot)
        dev.vertex.Vertex.log.info("[Vertex] pack loaded ({}/{}): samplers={} varying='{}'", w, h, prog.samplers, prog.varyingName)

        val source = ShaderSource { id, _ ->
            when (id.path) {
                "pack/post.v" -> POST_VSH
                "pack/normals.f" -> NORMAL_FSH.replace("__TEXEL__", "vec2(${1.0 / w}, ${1.0 / h})")
                "pack/composite.f" -> LegacyTranslator.fragment(prog)
                "pack/blit.f" -> BLIT_FSH
                else -> null
            }
        }

        normals = compile(device, source, id("pack/post.v"), id("pack/normals.f"),
            BindGroupLayout.builder().withUniform("depthtex0", UniformType.COMBINED_IMAGE_SAMPLER).build(),
            w, h)
        composite = compile(device, source, id("pack/post.v"), id("pack/composite.f"),
            BindGroupLayout.builder()
                .withUniform("colortex0", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("depthtex0", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("normalsTex", UniformType.COMBINED_IMAGE_SAMPLER)
                .build(),
            w, h)
        blit = compile(device, source, id("pack/post.v"), id("pack/blit.f"),
            BindGroupLayouts.IN_SAMPLER, w, h)
        builtForW = w; builtForH = h
    }

    private fun compile(
        device: com.mojang.renderpearl.api.device.GpuDevice,
        source: ShaderSource,
        vs: Identifier,
        fs: Identifier,
        layout: BindGroupLayout,
        rw: Int,
        rh: Int,
    ): CompiledRenderPipeline {
        // 身份随分辨率变化：location 标签携带尺寸，保证缓存键不同（DESIGN.md game-api 教训）
        val declarative = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("vertex", "${fs.path.substringAfterLast('/')}_{$rw}x${rh}"))
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
