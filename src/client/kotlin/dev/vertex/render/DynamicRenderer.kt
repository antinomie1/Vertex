package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import dev.vertex.Vertex
import dev.vertex.core.RuntimeDiagnostics
import dev.vertex.core.SharedVulkanContext
import dev.vertex.frontend.PackFrontend
import dev.vertex.frontend.PackRuntime
import dev.vertex.runtime.ProgramFamily
import dev.vertex.runtime.RenderTier
import dev.vertex.translate.LegacyTranslator
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.io.IOException
import java.util.IdentityHashMap

/**
 * Installs pack shaders into the vanilla RenderType cache without replacing the
 * game's buffer extraction or texture binding logic. The original pipeline state
 * is cloned byte-for-byte; only shader ids are changed, so unsupported packs can
 * fall back to the original pipeline independently of terrain and post effects.
 */
object DynamicRenderer {
    private val shaderId = Identifier.fromNamespaceAndPath("vertex", "dynamic_entities")
    private val pipelines = IdentityHashMap<RenderPipeline, RenderPipeline>()
    private val compiled = IdentityHashMap<RenderPipeline, CompiledRenderPipeline>()
    private var device: GpuDevice? = null
    private var prepared = false

    private val entityPipelines = listOf(
        RenderPipelines.ENTITY_SOLID,
        RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
        RenderPipelines.ENTITY_CUTOUT_CULL,
        RenderPipelines.ENTITY_CUTOUT,
        RenderPipelines.ENTITY_CUTOUT_Z_OFFSET,
        RenderPipelines.ENTITY_CUTOUT_DISSOLVE,
        RenderPipelines.ENTITY_TRANSLUCENT,
        RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE,
        RenderPipelines.ENTITY_TRANSLUCENT_CULL,
        RenderPipelines.ITEM_CUTOUT,
        RenderPipelines.ITEM_CUTOUT_GLINT,
        RenderPipelines.ITEM_CUTOUT_GLINT_SPECIAL,
        RenderPipelines.ITEM_TRANSLUCENT,
        RenderPipelines.ITEM_TRANSLUCENT_GLINT,
        RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL,
    )

    @JvmStatic
    @Synchronized
    fun prepare() {
        if (prepared) return
        val context = SharedVulkanContext.attach()
        if (context.tier(ProgramFamily.DYNAMIC_WORLD) != RenderTier.TIER_2) return
        try {
            val root = PackRuntime.root(Minecraft.getInstance().gameDirectory.toPath())
            val program = PackFrontend.loadDynamic(root, PackRuntime.options())
                ?: return disable("no gbuffers_entities shader pair")
            val vsh = LegacyTranslator.dynamicVertex(program)
            val fsh = LegacyTranslator.dynamicFragment(program)
            val source = shaderSource(vsh, fsh)
            val gpu = RenderSystem.getDevice()
            var failures = 0
            entityPipelines.distinct().forEach { original ->
                if (!compatible(original)) return@forEach
                val custom = clone(original)
                val built = gpu.compilePipeline(custom, source)
                if (built == null) {
                    failures++
                } else {
                    pipelines[original] = custom
                    compiled[custom] = built
                }
            }
            if (pipelines.isEmpty()) return disable("dynamic shader pair rejected by RenderPearl")
            device = gpu
            prepared = true
            context.health.downgrade(ProgramFamily.HAND, RenderTier.TIER_2, "dynamic entity bridge is shared")
            Vertex.log.info(
                "[Vertex] dynamic render bridge armed: {} pipelines{}",
                pipelines.size,
                if (failures == 0) "" else "; skipped=$failures",
            )
        } catch (t: Throwable) {
            disable("dynamic pipeline preparation", t)
        }
    }

    @JvmStatic
    fun pipelineFor(original: RenderPipeline): RenderPipeline = pipelines[original] ?: original

    @JvmStatic
    fun compiledFor(pipeline: RenderPipeline): CompiledRenderPipeline? = compiled[pipeline]

    @JvmStatic
    fun isPrepared() = prepared

    @JvmStatic
    @Synchronized
    fun close() {
        compiled.values.distinct().forEach { runCatching { it.close() } }
        compiled.clear(); pipelines.clear(); device = null; prepared = false
    }

    private fun disable(reason: String, failure: Throwable? = null) {
        val context = runCatching { SharedVulkanContext.attach() }.getOrNull() ?: return
        context.health.downgrade(ProgramFamily.DYNAMIC_WORLD, RenderTier.TIER_1, reason)
        context.health.downgrade(ProgramFamily.HAND, RenderTier.TIER_1, reason)
        if (failure != null) RuntimeDiagnostics.disable(ProgramFamily.DYNAMIC_WORLD, reason, failure)
        else Vertex.log.warn("[Vertex] dynamic render bridge unavailable; retaining vanilla path: {}", reason)
    }

    private fun compatible(pipeline: RenderPipeline): Boolean {
        val format = pipeline.getVertexFormatBinding(0) ?: return false
        return format.contains("Position") && format.contains("Color") &&
            format.contains("UV0") && format.contains("Normal")
    }

    private fun clone(original: RenderPipeline): RenderPipeline {
        val builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("vertex", "pipeline/dynamic_${original.getLocation().path.replace('/', '_')}"))
            .withVertexShader(shaderId)
            .withFragmentShader(shaderId)
            .withCull(original.isCull())
            .withPolygonMode(original.getPolygonMode())
            .withPrimitiveTopology(original.getPrimitiveTopology())
        original.getVertexFormatBindings().forEachIndexed { index, format ->
            format?.let { builder.withVertexBinding(index, it) }
        }
        original.getBindGroupLayouts().forEach(builder::withBindGroupLayout)
        original.getColorTargetStates().forEachIndexed { index, target ->
            if (target == null) builder.withUnusedColorTargetState(index) else builder.withColorTargetState(index, target)
        }
        original.getDepthStencilState()?.let(builder::withDepthStencilState)
        if (original.pushConstantSize() > 0) builder.withPushConstantSize(original.pushConstantSize())
        original.getShaderDefines().flags.forEach(builder::withShaderDefine)
        original.getShaderDefines().values.forEach { (name, value) ->
            value.toIntOrNull()?.let { builder.withShaderDefine(name, it) }
                ?: value.toFloatOrNull()?.let { builder.withShaderDefine(name, it) }
                ?: builder.withShaderDefine(name)
        }
        return builder.build()
    }

    private fun shaderSource(vsh: String, fsh: String): ShaderSource = ShaderSource { id, type ->
        if (id.namespace == "vertex" && id == shaderId) {
            when (type) {
                ShaderType.VERTEX -> vsh
                ShaderType.FRAGMENT -> fsh
                else -> null
            }
        } else loadResource(id, type)
    }

    private fun loadResource(id: Identifier, type: ShaderType?): String? {
        val location = type?.idConverter()?.idToFile(id) ?: id
        return try {
            Minecraft.getInstance().resourceManager.openAsReader(location).use { it.readText() }
        } catch (_: IOException) {
            null
        }
    }
}
