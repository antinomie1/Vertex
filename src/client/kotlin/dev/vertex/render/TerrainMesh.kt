package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.api.vertex.VertexFormat
import dev.vertex.Vertex
import dev.vertex.core.SharedVulkanContext
import dev.vertex.core.RuntimeDiagnostics
import dev.vertex.frontend.PackRuntime
import dev.vertex.frontend.PackSemanticsParser
import dev.vertex.frontend.BlockMaterialMap
import dev.vertex.runtime.ProgramFamily
import dev.vertex.runtime.RenderTier
import dev.vertex.translate.TerrainRequirementScanner
import dev.vertex.translate.TerrainRequirements
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.builders.UVPair
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.resources.Identifier
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.IdentityHashMap
import kotlin.jvm.JvmStatic

/**
 * Owns the opaque terrain mesh contract while retaining vanilla section draws.
 *
 * SOLID/CUTOUT/TRANSLUCENT receive the appended Normal element. Their existing section
 * buffers, index buffers, indirect draws, and RenderPearl passes remain intact;
 * the mixins replace only the vertex format at compile time and the pipeline at
 * draw time.
 */
object TerrainMesh {
    @Volatile private var customFormat = format(TerrainRequirements(false, false, false, false))
    @Volatile private var requirements = TerrainRequirements(false, false, false, false)
    @Volatile private var separateAo = false
    @Volatile private var noiseSampler = false
    @Volatile private var packSamplers = emptySet<String>()
    @Volatile private var blockMaterials = BlockMaterialMap.empty()
    private val materialTextures = HashMap<String, GpuTexture>()
    private val materialViews = HashMap<String, GpuTextureView>()

    private val currentEntityPayload = ThreadLocal<Int>()
    private val currentMidUv = ThreadLocal.withInitial { FloatArray(2) }
    private val midUvActive = ThreadLocal.withInitial { false }
    private val shaderId = Identifier.fromNamespaceAndPath("vertex", "terrain_mesh")
    // Fluid/translucent meshes use a different vanilla contract and are handled by
    // the water family; keep the widened opaque format off those buffers.
    private val terrainLayers = setOf(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT)

    private val indirectLogged = AtomicBoolean()
    private val separateLogged = AtomicBoolean()
    @Volatile
    private var prepared: Prepared? = null

    @JvmStatic
    fun close() {
        prepared?.compiled?.values?.distinct()?.forEach { runCatching { it.close() } }
        prepared = null
        requirements = TerrainRequirements(false, false, false, false)
        separateAo = false
        noiseSampler = false
        packSamplers = emptySet()
        blockMaterials = BlockMaterialMap.empty()
        closeMaterialTextures()
        customFormat = format(requirements)
        TerrainCommandCache.invalidate()
    }

    @JvmStatic
    @Synchronized
    fun prepare() {
        if (!PackRuntime.isEnabled()) {
            prepared = null
            return
        }
        if (SharedVulkanContext.attach().tier(ProgramFamily.TERRAIN_OPAQUE) != RenderTier.TIER_2) {
            prepared = null
            return
        }
        val device = try {
            RenderSystem.getDevice()
        } catch (t: Throwable) {
            Vertex.log.debug("[Vertex] terrain mesh deferred: GPU device unavailable", t)
            return
        }
        if (prepared?.device === device) return
        try {
            val runDir = Minecraft.getInstance().gameDirectory.toPath()
            val packRoot = PackRuntime.root(runDir)
            val terrainProg = dev.vertex.frontend.PackFrontend.loadTerrain(packRoot, PackRuntime.options())
            blockMaterials = BlockMaterialMap.load(packRoot.resolve("shaders/block.properties")).also {
                Vertex.log.info("[Vertex] terrain block material map: {} rules", it.size)
            }
            separateAo = PackSemanticsParser.load(packRoot, PackRuntime.options()).separateAo
            noiseSampler = terrainProg.samplers.contains("noisetex")
            packSamplers = terrainProg.samplers.toSet()
            createMaterialTextures(device)
            requirements = TerrainRequirementScanner.scan(terrainProg.vertexSource)
            customFormat = format(requirements)
            val translatedVsh = dev.vertex.translate.LegacyTranslator.terrainVertex(terrainProg, separateAo)
            val translatedFsh = dev.vertex.translate.LegacyTranslator.terrainFragment(
                terrainProg,
                separateAo,
                PackChain.usesReverseDepth(),
            )
            val source = shaderSource(translatedVsh, translatedFsh)
            val solid = createPipelinePair(ChunkSectionLayer.SOLID)
            val cutout = createPipelinePair(ChunkSectionLayer.CUTOUT)
            val compiled = IdentityHashMap<RenderPipeline, CompiledRenderPipeline>(4)
            listOf(solid.base, solid.multidraw, cutout.base, cutout.multidraw).forEach { pipeline ->
                compiled[pipeline] = device.compilePipeline(pipeline, source)
                    ?: error("RenderPearl rejected ${pipeline.location}")
            }
            prepared = Prepared(device, solid, cutout, compiled)
            TerrainCommandCache.invalidate()
            Vertex.log.info(
                "[Vertex] terrain mesh surgery armed: stride={} layers=solid,cutout (gbuffers_terrain translated)",
                customFormat.getVertexSize()
            )
        } catch (t: Throwable) {
            prepared = null
            closeMaterialTextures()
            RuntimeDiagnostics.disable(ProgramFamily.TERRAIN_OPAQUE, "terrain pipeline preparation", t)
        }
    }

    @JvmStatic
    fun formatFor(layer: ChunkSectionLayer): VertexFormat =
        if (prepared != null && layer in terrainLayers) customFormat else layer.vertexFormat()

    @JvmStatic
    fun pipelineFor(layer: ChunkSectionLayer, multidraw: Boolean): RenderPipeline? {
        val state = prepared ?: return null
        if (layer !in terrainLayers) return null
        val pair = when (layer) {
            ChunkSectionLayer.SOLID -> state.solid
            ChunkSectionLayer.CUTOUT -> state.cutout
            ChunkSectionLayer.TRANSLUCENT -> return null
        }
        return if (multidraw) pair.multidraw else pair.base
    }
    @JvmStatic
    fun compiledFor(pipeline: RenderPipeline): CompiledRenderPipeline? = prepared?.compiled?.get(pipeline)

    @JvmStatic
    fun isPrepared() = prepared != null

    @JvmStatic
    fun vertexFormat() = customFormat

    @JvmStatic
    fun isMultidrawPipeline(pipeline: RenderPipeline): Boolean? = prepared?.let { state -> when (pipeline) {
        state.solid.multidraw, state.cutout.multidraw -> true
        state.solid.base, state.cutout.base -> false
        else -> null
    } }
    @JvmStatic
    fun setCurrentBlock(blockState: net.minecraft.world.level.block.state.BlockState) {
        if (!requirements.entity) return
        val encoded = ((blockMaterials.id(blockState) + 1) shl 1)
        currentEntityPayload.set(encoded)
    }

    @JvmStatic
    fun setCurrentFluid(
        blockState: net.minecraft.world.level.block.state.BlockState,
        fluidState: net.minecraft.world.level.material.FluidState
    ) {
        if (!requirements.entity) return
        val encoded = ((blockMaterials.id(blockState) + 1) shl 1) or 1
        currentEntityPayload.set(encoded)
    }

    @JvmStatic
    fun clearCurrentBlock() {
        currentEntityPayload.remove()
    }

    @JvmStatic
    fun bindMaterialSamplers(pass: RenderPass) {
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        materialViews.forEach { (name, view) -> runCatching { pass.setUniform(name, view, sampler) } }
    }

    @JvmStatic
    fun applyQuadPayload(
        instance: com.mojang.blaze3d.vertex.QuadInstance,
        quad: net.minecraft.client.resources.model.geometry.BakedQuad
    ) {
        val payload = currentEntityPayload.get()
        if (requirements.entity && payload != null && payload != 0) instance.setOverlayCoords(payload)
        if (requirements.midTexCoord) {
            var u = 0f; var v = 0f
            for (vertex in 0..3) quad.packedUV(vertex).let { packed ->
                u += UVPair.unpackU(packed); v += UVPair.unpackV(packed)
            }
            currentMidUv.get()[0] = u * .25f
            currentMidUv.get()[1] = v * .25f
            midUvActive.set(true)
        }
    }

    @JvmStatic fun fillExtraVertex(consumer: com.mojang.blaze3d.vertex.VertexConsumer) {
        if (requirements.midTexCoord) {
            val uv = currentMidUv.get()
            if (!midUvActive.get()) { uv[0] = 0f; uv[1] = 0f }
            consumer.setUv3(uv[0], uv[1])
        }
    }

    private fun needsSeparateAo() = separateAo && prepared != null

    /** Move vanilla's grayscale AO/directional factor into Color.a for separateAo packs. */
    @JvmStatic
    fun prepareSeparateAo(instance: com.mojang.blaze3d.vertex.QuadInstance) {
        if (!needsSeparateAo()) return
        for (vertex in 0..3) {
            val color = instance.getColor(vertex)
            instance.setColor(vertex, net.minecraft.util.ARGB.color(
                net.minecraft.util.ARGB.red(color), 255, 255, 255,
            ))
        }
    }

    /** Apply a block tint without destroying the separated AO coefficient. */
    @JvmStatic
    fun multiplySeparateAoTint(instance: com.mojang.blaze3d.vertex.QuadInstance, tint: Int) {
        if (!needsSeparateAo()) {
            instance.multiplyColor(tint)
            return
        }
        for (vertex in 0..3) {
            val color = instance.getColor(vertex)
            val alpha = net.minecraft.util.ARGB.alpha(color) * net.minecraft.util.ARGB.alpha(tint) / 255
            instance.setColor(vertex, net.minecraft.util.ARGB.color(
                alpha,
                net.minecraft.util.ARGB.red(tint),
                net.minecraft.util.ARGB.green(tint),
                net.minecraft.util.ARGB.blue(tint),
            ))
        }
    }

    @JvmStatic fun clearQuadPayload() = midUvActive.set(false)


    @JvmStatic
    fun noteDrawPath(isIndirect: Boolean) {
        if (isIndirect && indirectLogged.compareAndSet(false, true)) {
            Vertex.log.info("[Vertex] terrain draw path verified: DrawIndirect (multi-draw indirect buffer)")
        } else if (!isIndirect && separateLogged.compareAndSet(false, true)) {
            Vertex.log.info("[Vertex] terrain draw path verified: DrawSeparate (separate per-draw bundle)")
        }
    }
    private fun createPipelinePair(layer: ChunkSectionLayer): PipelinePair {
        return PipelinePair(
            createPipeline(layer, multidraw = false),
            createPipeline(layer, multidraw = true),
        )
    }

    private fun createPipeline(layer: ChunkSectionLayer, multidraw: Boolean): RenderPipeline {
        val snippet = if (multidraw) {
            RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET
        } else {
            RenderPipelines.TERRAIN_SNIPPET
        }
        val builder = RenderPipeline.builder(snippet)
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "vertex",
                    "pipeline/terrain_mesh_${layer.name.lowercase()}${if (multidraw) "_multidraw" else ""}"
                )
            )
            .withVertexShader(shaderId)
            .withFragmentShader(shaderId)
            .withVertexBinding(0, customFormat)
            .withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                .apply { if (noiseSampler) withUniform("noisetex", UniformType.COMBINED_IMAGE_SAMPLER) }
                .apply {
                    packSamplers.filterNot { it in setOf("tex", "texture", "gtexture", "lightmap", "noisetex") }
                        .forEach { withUniform(it, UniformType.COMBINED_IMAGE_SAMPLER) }
                }
                .withUniform("VertexPackUniforms", UniformType.UNIFORM_BUFFER)
                .build())
        if (layer == ChunkSectionLayer.CUTOUT) {
            builder.withShaderDefine("ALPHA_CUTOUT", 0.5f)
        }
        val vanilla = layer.pipeline(multidraw)
        builder.withCull(vanilla.isCull())
        builder.withDepthStencilState(vanilla.depthStencilState ?: DepthStencilState.DEFAULT)
        vanilla.colorTargetStates.forEachIndexed { index, target -> target?.let { builder.withColorTargetState(index, it) } }
        return builder.build()
    }

    private fun shaderSource(vshSource: String, fshSource: String): ShaderSource = ShaderSource { id, type ->
        if (id.namespace == "vertex" && id == shaderId) {
            when (type) {
                ShaderType.VERTEX -> vshSource
                ShaderType.FRAGMENT -> fshSource
                else -> null
            }
        } else {
            loadResource(id, type)
        }
    }

    private fun loadResource(id: Identifier, type: ShaderType?): String? {
        val location = type?.idConverter()?.idToFile(id) ?: id
        return try {
            Minecraft.getInstance().resourceManager.openAsReader(location).use { it.readText() }
        } catch (_: IOException) {
            null
        }
    }

    private fun createMaterialTextures(device: GpuDevice) {
        closeMaterialTextures()
        // Missing optional pack textures must behave like an unmodified vanilla
        // surface: flat tangent normal and zero specular/emission data.
        val pixels = mapOf("normals" to 0xFF8080FF.toInt(), "specular" to 0xFF000000.toInt())
        val encoder = device.createCommandEncoder()
        pixels.filterKeys(packSamplers::contains).forEach { (name, pixel) ->
            val image = NativeImage(1, 1, false)
            image.setPixel(0, 0, pixel)
            val texture = device.createTexture(
                { "vertex-terrain-$name" },
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, 1, 1, 1, 1,
            )
            encoder.writeToTexture(texture, image)
            image.close()
            materialTextures[name] = texture
            materialViews[name] = device.createTextureView(texture)
        }
        encoder.submit()
    }

    private fun closeMaterialTextures() {
        materialViews.values.forEach { runCatching { it.close() } }
        materialTextures.values.forEach { runCatching { it.close() } }
        materialViews.clear(); materialTextures.clear()
    }

    private data class PipelinePair(
        val base: RenderPipeline,
        val multidraw: RenderPipeline,
    )

    private data class Prepared(
        val device: GpuDevice,
        val solid: PipelinePair,
        val cutout: PipelinePair,
        val compiled: IdentityHashMap<RenderPipeline, CompiledRenderPipeline>,
    )

    private fun format(requirements: TerrainRequirements) = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("UV1", GpuFormat.RG16_UINT)
        .addAttribute("UV2", GpuFormat.RG16_SINT)
        .apply { if (requirements.midTexCoord) addAttribute("UV3", GpuFormat.RG32_FLOAT) }
        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
        .build()

}
