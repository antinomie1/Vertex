package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.vertex.Vertex
import dev.vertex.core.SharedVulkanContext
import dev.vertex.frontend.PackFrontend
import dev.vertex.frontend.PackRuntime
import dev.vertex.frontend.PackSemanticsParser
import dev.vertex.runtime.ShadowCacheState
import dev.vertex.runtime.ImageAllocation
import dev.vertex.runtime.ImageClass
import dev.vertex.runtime.ProgramFamily
import dev.vertex.runtime.RenderTier
import dev.vertex.translate.LegacyTranslator
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.Identifier
import net.minecraft.world.attribute.EnvironmentAttributes
import org.joml.Matrix4f
import org.joml.Vector4f
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Optional
import java.util.OptionalDouble
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Real light-space depth pass for the visible opaque terrain set. */
object ShadowRenderer {
    private var requested = emptySet<String>()
    private var discovered = false
    private var resolution = 2048
    private var depthTexture: GpuTexture? = null
    private var depthView: GpuTextureView? = null
    private var colorTexture: GpuTexture? = null
    private var colorView: GpuTextureView? = null
    private var uniformBuffer: GpuBuffer? = null
    private var base: CompiledRenderPipeline? = null
    private var multidraw: CompiledRenderPipeline? = null
    private var active = false
    private const val SHADOW_SLOTS = 4
    private const val SHADOW_SLOT_BYTES = 256L
    private var slot = 0
    private var failed = false
    private val logged = AtomicBoolean()
    private val cacheLogged = AtomicBoolean()
    private val cache = ShadowCacheState()
    private val matrix = Matrix4f()
    private val modelView = Matrix4f()
    private val projection = Matrix4f()
    private val rasterProjection = Matrix4f()
    private val inverseModelView = Matrix4f()
    private val inverseProjection = Matrix4f()
    private val scratch = FloatArray(16)
    private val staging = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())

    @JvmStatic
    fun close() {
        runCatching { depthView?.close() }; runCatching { depthTexture?.close() }
        runCatching { colorView?.close() }; runCatching { colorTexture?.close() }
        runCatching { uniformBuffer?.close() }
        listOf(base, multidraw).filterNotNull().distinct().forEach { runCatching { it.close() } }
        depthView = null; depthTexture = null; colorView = null; colorTexture = null; uniformBuffer = null
        base = null; multidraw = null; active = false; failed = false; discovered = false; requested = emptySet(); slot = 0
        logged.set(false); cacheLogged.set(false); cache.invalidate()
    }

    fun discover() {
        if (discovered) return
        discovered = true
        val mc = Minecraft.getInstance()
        val root = PackRuntime.root(mc.gameDirectory.toPath())
        requested = PackFrontend.loadScreenChain(root, PackRuntime.options()).flatMap { it.samplers }
            .filter(SHADOW_SAMPLERS::contains).toSet()
        resolution = System.getProperty("vertex.shadowResolution")?.toIntOrNull() ?: 2048
        require(resolution in 256..8192 && resolution.countOneBits() == 1) {
            "vertex.shadowResolution must be a power of two in 256..8192"
        }
    }

    fun allocations(): List<ImageAllocation> {
        discover()
        if (requested.isEmpty()) return emptyList()
        return listOf(ImageAllocation("shadowtex0", resolution, resolution, 4, ImageClass.SHADOW)) +
            if (requested.any { it.startsWith("shadowcolor") })
                listOf(ImageAllocation("shadowcolor0", resolution, resolution, 4, ImageClass.SHADOW)) else emptyList()
    }

    fun configure(allocations: List<ImageAllocation>) {
        allocations.firstOrNull { it.imageClass == ImageClass.SHADOW }?.let { resolution = it.width }
    }

    fun prepare() {
        if (!PackRuntime.isEnabled()) return
        if (SharedVulkanContext.attach().tier(ProgramFamily.TERRAIN_OPAQUE) != RenderTier.TIER_2 ||
            SharedVulkanContext.attach().tier(ProgramFamily.SCREEN_CHAIN) != RenderTier.TIER_2) return
        if (failed || base != null) return
        try {
            discover()
            if (requested.isEmpty()) return
            val mc = Minecraft.getInstance()
            val root = PackRuntime.root(mc.gameDirectory.toPath())
            val device = RenderSystem.getDevice()
            // Shadow pipelines consume the same terrain buffer contract as the main
            // terrain path; prepare it once so both draw paths share the ABI.
            TerrainMesh.prepare()
            depthTexture = device.createTexture(
                { "vertex-shadow-depth" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.D32_FLOAT, resolution, resolution, 1, 1,
            )
            depthView = device.createTextureView(depthTexture!!)
            if (requested.any { it.startsWith("shadowcolor") }) {
                colorTexture = device.createTexture(
                    { "vertex-shadow-color" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.RGBA8_UNORM, resolution, resolution, 1, 1,
                )
                colorView = device.createTextureView(colorTexture!!)
            }
            uniformBuffer = device.createBuffer(
                { "vertex-shadow-matrix" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
                SHADOW_SLOT_BYTES * SHADOW_SLOTS,
            )
            val pack = PackFrontend.loadShadow(root, PackRuntime.options())
            val requirements = pack?.let { dev.vertex.translate.TerrainRequirementScanner.scan(it.vertexSource) }
            val separateAo = PackSemanticsParser.load(root, PackRuntime.options()).separateAo
            compile(device, colorView != null, pack, separateAo, requirements?.midTexCoord == true)
            device.createCommandEncoder().createRenderPass(descriptor()).close()
            Vertex.log.info("[Vertex] shadow pass armed: {}x{}, samplers={}", resolution, resolution, requested.sorted())
        } catch (t: Throwable) {
            failed = true
            Vertex.log.error("[Vertex] shadow pass unavailable", t)
        }
    }

    @JvmStatic
    fun render(sections: ChunkSectionsToRender) {
        prepare()
        if (failed || base == null || !TerrainMesh.isPrepared()) return
        try {
            val mc = Minecraft.getInstance()
            val angle = sunAngle(mc)
            val camera = mc.gameRenderer.mainCamera().position()
            if (!cache.needsRender(angle, camera.x, camera.z)) {
                if (cacheLogged.compareAndSet(false, true)) Vertex.log.info("[Vertex] shadow cache hit verified")
                return
            }
            val renderEpoch = cache.epoch()
            val device = RenderSystem.getDevice()
            val encoder = device.createCommandEncoder()
            updateMatrix(encoder, angle)
            encoder.createRenderPass(descriptor()).use { pass ->
                RenderSystem.bindDefaultUniforms(pass)
                pass.setUniform("ShadowUniforms", uniformBuffer!!.slice(slot * SHADOW_SLOT_BYTES, 64L))
                val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
                val atlas = Minecraft.getInstance().textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).textureView
                active = true
                try {
                    sections.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false)
                } finally {
                    active = false
                }
            }
            cache.markRendered(angle, camera.x, camera.z, renderEpoch)
            if (logged.compareAndSet(false, true)) Vertex.log.info("[Vertex] shadow terrain draw verified")
            slot = (slot + 1) % SHADOW_SLOTS
        } catch (t: Throwable) {
            active = false
            failed = true
            Vertex.log.error("[Vertex] shadow pass disabled", t)
        }
    }

    @JvmStatic
    fun invalidate() = cache.invalidate()

    @JvmStatic
    fun invalidateSection(sectionX: Int, sectionZ: Int) = cache.invalidateSection(sectionX, sectionZ)

    @JvmStatic
    fun compiledFor(pipeline: RenderPipeline): CompiledRenderPipeline? {
        if (!active) return null
        return when (TerrainMesh.isMultidrawPipeline(pipeline)) {
            true -> multidraw
            false -> base
            null -> null
        }
    }

    fun view(name: String): GpuTextureView? = when (name) {
        "shadowtex0", "shadowtex1" -> depthView
        "shadowcolor0", "shadowcolor1" -> colorView
        else -> null
    }

    fun uniformMatrix(name: String): Matrix4f? = if (base == null) null else when (name) {
        "shadowModelView" -> modelView
        "shadowModelViewInverse" -> inverseModelView
        "shadowProjection" -> projection
        "shadowProjectionInverse" -> inverseProjection
        else -> null
    }

    private fun updateMatrix(encoder: com.mojang.renderpearl.api.commands.CommandEncoder, angle: Float) {
        val mc = Minecraft.getInstance()
        val range = mc.options.effectiveRenderDistance.coerceAtLeast(8) * 16f
        val x = cos(angle) * range
        val y = sin(angle) * range
        val upY = if (abs(y) > range * 0.95f) 0f else 1f
        val upZ = if (abs(y) > range * 0.95f) 1f else 0f
        // Pack-facing shadowProjection stays in legacy OpenGL NDC because BSL's
        // projMAD/DistortShadow expects [-1, 1]. Rasterization on Vulkan uses
        // [0, 1] depth, so only the MVP written to the shadow pass is converted.
        projection.setOrtho(-range, range, -range, range, -range * 2f, range * 2f)
        rasterProjection.setOrtho(
            -range, range, -range, range, -range * 2f, range * 2f,
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(),
        )
        modelView.identity().lookAt(x, y, range * 0.35f, 0f, 0f, 0f, 0f, upY, upZ)
        inverseProjection.set(projection).invert()
        inverseModelView.set(modelView).invert()
        matrix.set(rasterProjection).mul(modelView)
        staging.clear()
        matrix.get(scratch)
        for (value in scratch) staging.putFloat(value)
        staging.flip()
        encoder.writeToBuffer(uniformBuffer!!.slice(slot * SHADOW_SLOT_BYTES, 64L), staging)
    }

    private fun descriptor() = RenderPassDescriptor.builder { "vertex-shadow" }.also { builder ->
        colorView?.let { builder.withColorAttachment(it, Optional.of(CLEAR)) }
        builder.withDepthAttachment(depthView!!, OptionalDouble.of(1.0))
    }.build()

    private fun sunAngle(mc: Minecraft): Float {
        val degrees = mc.gameRenderer.mainCamera().attributeProbe()
            .getValue(EnvironmentAttributes.SUN_ANGLE, mc.deltaTracker.getGameTimeDeltaPartialTick(true))
        return Math.toRadians(degrees.toDouble()).toFloat()
    }

    private fun compile(
        device: GpuDevice,
        color: Boolean,
        program: dev.vertex.frontend.LoadedProgram?,
        separateAo: Boolean,
        midTexCoord: Boolean,
    ) {
        val layout = BindGroupLayout.builder()
            .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("ShadowUniforms", UniformType.UNIFORM_BUFFER)
            .withUniform("VertexPackUniforms", UniformType.UNIFORM_BUFFER)
            .build()
        fun pipeline(multidraw: Boolean): RenderPipeline {
            val snippet = if (multidraw) RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET else RenderPipelines.TERRAIN_SNIPPET
            val builder = RenderPipeline.builder(snippet)
                .withLocation(id("pipeline/shadow${if (multidraw) "_multidraw" else ""}"))
                .withVertexShader(id("shadow"))
                .withFragmentShader(id("shadow"))
                .withVertexBinding(0, TerrainMesh.vertexFormat())
                .withBindGroupLayout(layout)
                .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 1.1f, 4f))
            if (color) builder.withColorTargetState(ColorTargetState.DEFAULT)
            return builder.build()
        }
        val fallback = ShaderSource { shader, type -> when {
            shader == id("shadow") && type == ShaderType.VERTEX -> VERTEX_SHADER
            shader == id("shadow") && type == ShaderType.FRAGMENT -> if (color) COLOR_FRAGMENT else DEPTH_FRAGMENT
            else -> loadResource(shader, type)
        } }
        val translated = program?.let { pack -> ShaderSource { shader, type -> when {
            shader == id("shadow") && type == ShaderType.VERTEX -> LegacyTranslator.shadowVertex(pack, separateAo, midTexCoord)
            shader == id("shadow") && type == ShaderType.FRAGMENT -> LegacyTranslator.shadowFragment(pack)
            else -> loadResource(shader, type)
        } } }
        var usedPack = translated != null
        fun compilePipeline(pipe: RenderPipeline): CompiledRenderPipeline {
            val result = translated?.let {
                runCatching { device.compilePipeline(pipe, it) }
                    .onFailure { Vertex.log.warn("[Vertex] translated shadow pipeline rejected: {}", pipe.location, it) }
                    .getOrNull()
            }
            if (result != null) return result
            usedPack = false
            return device.compilePipeline(pipe, fallback) ?: error("shadow pipeline compilation failed")
        }
        base = compilePipeline(pipeline(false))
        multidraw = compilePipeline(pipeline(true))
        if (usedPack) Vertex.log.info("[Vertex] shadow program translated: {}", program!!.name)
        else if (program != null) Vertex.log.warn("[Vertex] {} shadow program rejected; using compatible fallback", program.name)
    }

    private fun loadResource(id: Identifier, type: ShaderType?): String? {
        val location = type?.idConverter()?.idToFile(id) ?: id
        return try { Minecraft.getInstance().resourceManager.openAsReader(location).use { it.readText() } }
        catch (_: IOException) { null }
    }

    private fun id(path: String) = Identifier.fromNamespaceAndPath("vertex", path)
    private val SHADOW_SAMPLERS = setOf("shadowtex0", "shadowtex1", "shadowcolor0", "shadowcolor1")
    private val CLEAR = Vector4f(1f, 1f, 1f, 1f)

    private const val VERTEX_SHADER = """#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:globals.glsl>
#ifndef MULTIDRAW_TERRAIN
#include <minecraft:chunksection.glsl>
#endif
layout(location=0) in vec3 Position;
layout(location=2) in vec2 UV0;
#ifdef MULTIDRAW_TERRAIN
layout(location=5) in ivec3 ChunkPosition;
#endif
layout(location=0) out vec2 shadowUv;
layout(std140) uniform ShadowUniforms { mat4 vertexShadowMvp; };
void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = vertexShadowMvp * vec4(pos, 1.0);
    shadowUv = UV0;
}
"""
    private const val DEPTH_FRAGMENT = """#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Sampler0;
layout(location=0) in vec2 shadowUv;
void main() { if (texture(Sampler0, shadowUv).a < 0.1) discard; }
"""
    private const val COLOR_FRAGMENT = """#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Sampler0;
layout(location=0) in vec2 shadowUv;
layout(location=0) out vec4 shadowColor;
void main() { vec4 c = texture(Sampler0, shadowUv); if (c.a < 0.1) discard; shadowColor = c; }
"""
}
