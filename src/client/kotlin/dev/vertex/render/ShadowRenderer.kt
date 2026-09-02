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
import dev.vertex.mixin.DrawIndirectAccessor
import dev.vertex.mixin.DrawSeparateAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
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
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private val compiled = IdentityHashMap<RenderPipeline, CompiledRenderPipeline>()
    private var active = false
    private const val SHADOW_SLOTS = 4
    private const val SHADOW_SLOT_BYTES = 256L
    private const val SHADOW_INTERVAL = 2f
    private const val TWO_PI = (Math.PI * 2.0).toFloat()
    // Iris keeps the shadow depth range pack-compatible and independent of
    // shadowDistance. BSL's DistortShadow applies its own z remap afterwards.
    private const val SHADOW_NEAR = -100.05f
    private const val SHADOW_FAR = 156f
    private var slot = 0
    private var shadowDistance = 160f
    private var sunPathRotation = 0f
    private var failed = false
    private var shadowValid = false
    private val logged = AtomicBoolean()
    private val cacheLogged = AtomicBoolean()
    private val emptyLogged = AtomicBoolean()
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
        compiled.values.distinct().forEach { runCatching { it.close() } }
        compiled.clear()
        depthView = null; depthTexture = null; colorView = null; colorTexture = null; uniformBuffer = null
        active = false; failed = false; discovered = false; requested = emptySet(); packSamplerNames = emptySet(); slot = 0
        shadowDistance = 160f; sunPathRotation = 0f
        logged.set(false); cacheLogged.set(false); cache.invalidate()
        shadowValid = false
        emptyLogged.set(false)
    }

    fun discover() {
        if (discovered) return
        discovered = true
        val mc = Minecraft.getInstance()
        val root = PackRuntime.root(mc.gameDirectory.toPath())
        requested = PackFrontend.loadScreenChain(root, PackRuntime.options(), PackRuntime.dimension()).flatMap { it.samplers }
            .filter(SHADOW_SAMPLERS::contains).toSet()
        parseShadowConstants(runCatching {
            PackFrontend.loadShadow(root, PackRuntime.options(), PackRuntime.dimension())?.vertexSource
        }.getOrNull())
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
        if (failed || compiled.isNotEmpty()) return
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
            val options = PackRuntime.options()
            val dimension = PackRuntime.dimension()
            val pack = PackFrontend.loadShadow(root, options, dimension)
            val layerPrograms = buildMap {
                put(ChunkSectionLayer.SOLID,
                    PackFrontend.loadProgram(root, "shadow_solid", options, dimension) ?: pack)
                put(ChunkSectionLayer.CUTOUT,
                    PackFrontend.loadProgram(root, "shadow_cutout", options, dimension) ?: pack)
                if (TerrainMesh.pipelineFor(ChunkSectionLayer.TRANSLUCENT, false) != null) {
                    put(ChunkSectionLayer.TRANSLUCENT,
                        PackFrontend.loadProgram(root, "shadow_water", options, dimension) ?: pack)
                }
            }
            parseShadowConstants(pack?.vertexSource)
            val separateAo = PackSemanticsParser.load(root, options, dimension).separateAo
            compile(device, colorView != null, pack, layerPrograms, separateAo)
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
        if (failed || compiled.isEmpty() || !TerrainMesh.isPrepared()) return
        try {
            val mc = Minecraft.getInstance()
            val angle = shadowAngle(mc)
            // Use the same interpolated render pose as the screen uniforms. The
            // mutable Camera can still contain the previous tick during movement,
            // which would otherwise invalidate the shadow cache one frame early.
            val camera = PackChain.cameraPositionForFrame()
            if (!cache.needsRender(angle, camera.x, camera.y, camera.z)) {
                if (cacheLogged.compareAndSet(false, true)) Vertex.log.info("[Vertex] shadow cache hit verified")
                return
            }
            val renderEpoch = cache.epoch()
            val hasGeometry = hasShadowGeometry(sections)
            val device = RenderSystem.getDevice()
            val encoder = device.createCommandEncoder()
            updateMatrix(encoder, angle)
            encoder.createRenderPass(descriptor()).use { pass ->
                RenderSystem.bindDefaultUniforms(pass)
                pass.setUniform("ShadowUniforms", uniformBuffer!!.slice(slot * SHADOW_SLOT_BYTES, 64L))
                val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
                val atlas = Minecraft.getInstance().textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).textureView
                packSamplerNames.forEach { name ->
                    if (!PackChain.bindStaticSampler(pass, name)) pass.setUniform(name, atlas, sampler)
                }
                active = true
                try {
                    sections.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false)
                    if (hasTranslucentPipeline()) {
                        sections.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, sampler, atlas, false)
                    }
                } finally {
                    active = false
                }
            }
            if (hasGeometry) {
                cache.markRendered(angle, camera.x, camera.y, camera.z, renderEpoch)
                shadowValid = true
                if (logged.compareAndSet(false, true)) Vertex.log.info("[Vertex] shadow terrain draw verified")
            } else if (emptyLogged.compareAndSet(false, true)) {
                Vertex.log.info("[Vertex] shadow pass deferred until visible sections are uploaded")
                cache.invalidate()
            } else {
                // The visible-set object is created before the asynchronous chunk
                // uploads complete. Keep retrying instead of caching an empty map.
                cache.invalidate()
            }
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
        return compiled[pipeline]
    }

    fun view(name: String): GpuTextureView? = when (name) {
        "shadowtex0", "shadowtex1" -> if (shadowValid) depthView else colorView ?: depthView
        "shadowcolor0", "shadowcolor1" -> colorView
        else -> null
    }

    fun uniformMatrix(name: String): Matrix4f? = if (compiled.isEmpty()) null else when (name) {
        "shadowModelView" -> modelView
        "shadowModelViewInverse" -> inverseModelView
        "shadowProjection" -> projection
        "shadowProjectionInverse" -> inverseProjection
        else -> null
    }

    private fun updateMatrix(encoder: com.mojang.renderpearl.api.commands.CommandEncoder, angle: Float) {
        val mc = Minecraft.getInstance()
        val range = shadowDistance.takeIf { it.isFinite() && it > 0f }
            ?: (mc.options.effectiveRenderDistance.coerceAtLeast(8) * 16f)
        val shadowTurn = (angle / TWO_PI).let { (it % 1f + 1f) % 1f }
        val skyAngle = if (shadowTurn < 0.25f) shadowTurn + 0.75f else shadowTurn - 0.25f
        // Pack-facing shadowProjection stays in legacy OpenGL NDC because BSL's
        // projMAD/DistortShadow expects [-1, 1]. Rasterization on Vulkan uses
        // [0, 1] depth, so only the MVP written to the shadow pass is converted.
        projection.setOrtho(-range, range, -range, range, SHADOW_NEAR, SHADOW_FAR)
        rasterProjection.setOrtho(
            -range, range, -range, range, SHADOW_NEAR, SHADOW_FAR,
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(),
        )
        // Match Iris' baseline shadow camera. The rotation order/signs are part
        // of the shader-pack ABI, including sunPathRotation.
        modelView.identity()
            .rotateX((Math.PI * 0.5).toFloat())
            .rotateZ(-skyAngle * TWO_PI)
            .rotateX(Math.toRadians(sunPathRotation.toDouble()).toFloat())
        val camera = PackChain.cameraPositionForFrame()
        val halfInterval = SHADOW_INTERVAL * 0.5f
        modelView.translate(
            camera.x.toFloat() % SHADOW_INTERVAL - halfInterval,
            camera.y.toFloat() % SHADOW_INTERVAL - halfInterval,
            camera.z.toFloat() % SHADOW_INTERVAL - halfInterval,
        )
        inverseProjection.set(projection).invert()
        inverseModelView.set(modelView).invert()
        matrix.set(rasterProjection).mul(modelView)
        staging.clear()
        matrix.get(scratch)
        for (value in scratch) staging.putFloat(value)
        staging.flip()
        encoder.writeToBuffer(uniformBuffer!!.slice(slot * SHADOW_SLOT_BYTES, 64L), staging)
    }

    private fun hasTranslucentPipeline(): Boolean =
        TerrainMesh.pipelineFor(ChunkSectionLayer.TRANSLUCENT, false)?.let(compiled::containsKey) == true

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun hasShadowGeometry(sections: ChunkSectionsToRender): Boolean {
        val layers = if (hasTranslucentPipeline()) {
            listOf(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT, ChunkSectionLayer.TRANSLUCENT)
        } else listOf(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT)
        return when (sections) {
            is ChunkSectionsToRender.DrawIndirect -> {
                val groups = (sections as DrawIndirectAccessor).`vertex$drawGroups`()
                layers.any { layer ->
                    groups[layer].orEmpty().any { it.drawCount() > 0 }
                }
            }
            is ChunkSectionsToRender.DrawSeparate -> {
                val draws = (sections as DrawSeparateAccessor).`vertex$drawsPerLayer`()
                layers.any { layer ->
                    !draws[layer].orEmpty().isEmpty()
                }
            }
            else -> false
        }
    }

    private fun descriptor() = RenderPassDescriptor.builder { "vertex-shadow" }.also { builder ->
        colorView?.let { builder.withColorAttachment(it, Optional.of(CLEAR)) }
        builder.withDepthAttachment(depthView!!, OptionalDouble.of(1.0))
    }.build()

    private fun shadowAngle(mc: Minecraft): Float {
        val probe = mc.gameRenderer.mainCamera().attributeProbe()
        val partialTick = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        val sun = irisSunTurn(probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick))
        val moon = irisSunTurn(probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTick))
        return (if (sun <= 0.5f) sun else moon) * TWO_PI
    }

    private fun irisSunTurn(degrees: Float): Float {
        val celestial = (degrees / 360f).let { (it % 1f + 1f) % 1f }
        return (if (celestial < 0.75f) celestial + 0.25f else celestial - 0.75f)
            .let { (it % 1f + 1f) % 1f }
    }

    private fun parseShadowConstants(source: String?) {
        if (source == null) return
        SHADOW_DISTANCE.find(source)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it > 0f }?.let { shadowDistance = it }
        SUN_PATH_ROTATION.find(source)?.groupValues?.get(1)?.toFloatOrNull()?.let { sunPathRotation = it }
    }

    private fun compile(
        device: GpuDevice,
        color: Boolean,
        fallbackProgram: dev.vertex.frontend.LoadedProgram?,
        layerPrograms: Map<ChunkSectionLayer, dev.vertex.frontend.LoadedProgram?>,
        separateAo: Boolean,
    ) {
        packSamplerNames = (layerPrograms.values.filterNotNull() + listOfNotNull(fallbackProgram)).flatMap { it.samplers }
            .filterNot { it in setOf("tex", "texture", "gtexture") }.toSet()
        val layout = BindGroupLayout.builder()
            .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
            .apply { packSamplerNames.forEach { withUniform(it, UniformType.COMBINED_IMAGE_SAMPLER) } }
            .withUniform("ShadowUniforms", UniformType.UNIFORM_BUFFER)
            .withUniform("VertexPackUniforms", UniformType.UNIFORM_BUFFER)
            .build()
        fun pipeline(layer: ChunkSectionLayer, multidraw: Boolean, shader: Identifier): RenderPipeline {
            val snippet = if (multidraw) RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET else RenderPipelines.TERRAIN_SNIPPET
            val builder = RenderPipeline.builder(snippet)
                .withLocation(id("pipeline/shadow_${layer.name.lowercase()}${if (multidraw) "_multidraw" else ""}"))
                .withVertexShader(shader)
                .withFragmentShader(shader)
                .withVertexBinding(0, TerrainMesh.vertexFormat())
                .withBindGroupLayout(layout)
                .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 1.1f, 4f))
            if (color) builder.withColorTargetState(ColorTargetState.DEFAULT)
            return builder.build()
        }
        fun source(shader: Identifier, program: dev.vertex.frontend.LoadedProgram?) = ShaderSource { id, type -> when {
            id == shader && program != null && type == ShaderType.VERTEX -> LegacyTranslator.shadowVertex(
                program,
                separateAo,
                dev.vertex.translate.TerrainRequirementScanner.scan(program.vertexSource).midTexCoord,
            )
            id == shader && program != null && type == ShaderType.FRAGMENT -> LegacyTranslator.shadowFragment(program)
            id == shader && program == null && type == ShaderType.VERTEX -> VERTEX_SHADER
            id == shader && program == null && type == ShaderType.FRAGMENT -> if (color) COLOR_FRAGMENT else DEPTH_FRAGMENT
            else -> loadResource(id, type)
        } }
        var translatedLayers = 0
        var specializedLayers = 0
        for ((layer, selectedProgram) in layerPrograms) {
            val shader = id("shadow_${layer.name.lowercase()}")
            var layerUsedPack = true
            for (multidraw in listOf(false, true)) {
                val terrainPipeline = TerrainMesh.pipelineFor(layer, multidraw)
                    ?: error("terrain pipeline unavailable for shadow $layer")
                val shadowPipeline = pipeline(layer, multidraw, shader)
                val candidates = listOfNotNull(selectedProgram, fallbackProgram.takeIf { it !== selectedProgram })
                var built: CompiledRenderPipeline? = null
                for (candidate in candidates) {
                    built = runCatching { device.compilePipeline(shadowPipeline, source(shader, candidate)) }
                        .onFailure { Vertex.log.warn(
                            "[Vertex] translated {} shadow pipeline rejected: {}",
                            layer.name.lowercase(), shadowPipeline.location, it,
                        ) }.getOrNull()
                    if (built != null) {
                        if (candidate !== selectedProgram) layerUsedPack = false
                        break
                    }
                }
                if (built == null) {
                    layerUsedPack = false
                    built = device.compilePipeline(shadowPipeline, source(shader, null))
                        ?: error("shadow pipeline compilation failed for $layer")
                }
                compiled[terrainPipeline] = built
            }
            if (selectedProgram != null && layerUsedPack) {
                translatedLayers++
                if (selectedProgram !== fallbackProgram) specializedLayers++
            }
        }
        if (translatedLayers > 0) Vertex.log.info(
            "[Vertex] shadow programs translated: {} layers{}",
            translatedLayers,
            if (specializedLayers == 0) "" else "; specialized=$specializedLayers",
        )
        if (translatedLayers < layerPrograms.size && fallbackProgram != null) Vertex.log.warn(
            "[Vertex] {} shadow layer(s) required a compatible fallback",
            layerPrograms.size - translatedLayers,
        )
    }

    private fun loadResource(id: Identifier, type: ShaderType?): String? {
        val location = type?.idConverter()?.idToFile(id) ?: id
        return try { Minecraft.getInstance().resourceManager.openAsReader(location).use { it.readText() } }
        catch (_: IOException) { null }
    }

    private fun id(path: String) = Identifier.fromNamespaceAndPath("vertex", path)
    private val SHADOW_SAMPLERS = setOf("shadowtex0", "shadowtex1", "shadowcolor0", "shadowcolor1")
    private var packSamplerNames = emptySet<String>()
    private val SHADOW_DISTANCE = Regex("""(?m)^\s*const\s+float\s+shadowDistance\s*=\s*([-+]?\d+(?:\.\d+)?)""")
    private val SUN_PATH_ROTATION = Regex("""(?m)^\s*const\s+float\s+sunPathRotation\s*=\s*([-+]?\d+(?:\.\d+)?)""")
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
