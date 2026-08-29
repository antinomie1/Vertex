package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.vertex.VertexFormat
import dev.vertex.Vertex
import dev.vertex.core.SharedVulkanContext
import dev.vertex.frontend.PackRuntime
import dev.vertex.runtime.ProgramFamily
import dev.vertex.runtime.RenderTier
import net.minecraft.client.Minecraft
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
 * Only SOLID/CUTOUT receive the appended Normal element. Their existing section
 * buffers, index buffers, indirect draws, and RenderPearl passes remain intact;
 * the mixins replace only the vertex format at compile time and the pipeline at
 * draw time.
 */
object TerrainMesh {
    private val customFormat: VertexFormat = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("UV1", GpuFormat.RG16_SINT)
        .addAttribute("UV2", GpuFormat.RG16_SINT)
        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
        .build()

    private val currentEntityPayload = ThreadLocal<Int>()
    private val shaderId = Identifier.fromNamespaceAndPath("vertex", "terrain_mesh")
    private val opaqueLayers = setOf(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT)

    private val indirectLogged = AtomicBoolean()
    private val separateLogged = AtomicBoolean()
    @Volatile
    private var prepared: Prepared? = null

    @JvmStatic
    @Synchronized
    fun prepare() {
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
            val translatedVsh = dev.vertex.translate.LegacyTranslator.terrainVertex(terrainProg)
            val translatedFsh = dev.vertex.translate.LegacyTranslator.terrainFragment(terrainProg)
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
            Vertex.log.warn("[Vertex] terrain mesh surgery unavailable; vanilla terrain retained", t)
        }
    }

    @JvmStatic
    fun formatFor(layer: ChunkSectionLayer): VertexFormat =
        if (prepared != null && layer in opaqueLayers) customFormat else layer.vertexFormat()

    @JvmStatic
    fun pipelineFor(layer: ChunkSectionLayer, multidraw: Boolean): RenderPipeline? {
        val state = prepared ?: return null
        if (layer !in opaqueLayers) return null
        val pair = if (layer == ChunkSectionLayer.SOLID) state.solid else state.cutout
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
        val rawId = net.minecraft.world.level.block.Block.getId(blockState)
        val encoded = ((rawId + 1) shl 1)
        currentEntityPayload.set(encoded)
    }

    @JvmStatic
    fun setCurrentFluid(
        blockState: net.minecraft.world.level.block.state.BlockState,
        fluidState: net.minecraft.world.level.material.FluidState
    ) {
        val rawId = net.minecraft.world.level.block.Block.getId(blockState)
        val encoded = ((rawId + 1) shl 1) or 1
        currentEntityPayload.set(encoded)
    }

    @JvmStatic
    fun clearCurrentBlock() {
        currentEntityPayload.remove()
    }

    @JvmStatic
    fun applyQuadPayload(
        instance: com.mojang.blaze3d.vertex.QuadInstance,
        quad: net.minecraft.client.resources.model.geometry.BakedQuad
    ) {
        val payload = currentEntityPayload.get()
        if (payload != null && payload != 0) {
            instance.setOverlayCoords(payload)
        }
    }


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
            .withColorTargetState(ColorTargetState.DEFAULT)
        if (layer == ChunkSectionLayer.CUTOUT) {
            builder.withShaderDefine("ALPHA_CUTOUT", 0.5f)
        }
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

}
