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
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.resources.Identifier
import java.io.IOException
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
        .addAttribute("UV2", GpuFormat.RG16_SINT)
        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
        .build()

    private val shaderId = Identifier.fromNamespaceAndPath("vertex", "terrain_mesh")
    private val opaqueLayers = setOf(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT)

    @Volatile
    private var prepared: Prepared? = null

    @JvmStatic
    @Synchronized
    fun prepare() {
        val device = try {
            RenderSystem.getDevice()
        } catch (t: Throwable) {
            Vertex.log.debug("[Vertex] terrain mesh deferred: GPU device unavailable", t)
            return
        }
        if (prepared?.device === device) return

        try {
            val source = shaderSource()
            val solid = createPipelinePair(ChunkSectionLayer.SOLID)
            val cutout = createPipelinePair(ChunkSectionLayer.CUTOUT)
            val compiled = IdentityHashMap<RenderPipeline, CompiledRenderPipeline>(4)
            listOf(solid.base, solid.multidraw, cutout.base, cutout.multidraw).forEach { pipeline ->
                compiled[pipeline] = device.compilePipeline(pipeline, source)
                    ?: error("RenderPearl rejected ${pipeline.location}")
            }
            prepared = Prepared(device, solid, cutout, compiled)
            Vertex.log.info(
                "[Vertex] terrain mesh surgery armed: stride={} layers=solid,cutout",
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
    fun strideFor(format: VertexFormat): Int =
        if (prepared != null && format == DefaultVertexFormat.BLOCK) customFormat.getVertexSize() else format.getVertexSize()

    @JvmStatic
    fun pipelineFor(layer: ChunkSectionLayer, multidraw: Boolean): RenderPipeline? {
        val state = prepared ?: return null
        if (layer !in opaqueLayers) return null
        val pair = if (layer == ChunkSectionLayer.SOLID) state.solid else state.cutout
        return if (multidraw) pair.multidraw else pair.base
    }

    @JvmStatic
    fun compiledFor(pipeline: RenderPipeline): CompiledRenderPipeline? = prepared?.compiled?.get(pipeline)

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

    private fun shaderSource(): ShaderSource = ShaderSource { id, type ->
        if (id.namespace == "vertex" && id == shaderId) {
            when (type) {
                ShaderType.VERTEX -> TERRAIN_MESH_VSH
                ShaderType.FRAGMENT -> TERRAIN_MESH_FSH
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

    private const val TERRAIN_MESH_VSH = """#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
layout(location = 6) in vec3 Normal;
#else
layout(location = 4) in vec3 Normal;
#endif

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;
layout(location = 5) out vec3 meshNormal;

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    meshNormal = normalize(Normal);

    const float chunkFullyVisibleRange = 16.0;
    #ifdef MULTIDRAW_TERRAIN
    float dist = length(pos);
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));
    #else
    chunkVisibility = 1.0;
    #endif
}
"""

    private const val TERRAIN_MESH_FSH = """#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;
layout(location = 5) in vec3 meshNormal;
layout(location = 0) out vec4 fragColor;

vec4 calculateFinalColor(vec4 color) {
    return apply_fog(
        color,
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );
}

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) discard;
    #endif

    // Consume the appended mesh normal without changing vanilla lighting semantics.
    color.rgb *= 0.98 + 0.02 * abs(meshNormal);
    fragColor = calculateFinalColor(color);
}
"""
}
