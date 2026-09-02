package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.pipeline.UniformType
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
    private val entityShaderId = Identifier.fromNamespaceAndPath("vertex", "dynamic_entities")
    private val blockShaderId = Identifier.fromNamespaceAndPath("vertex", "dynamic_blocks")
    private val skyShaderId = Identifier.fromNamespaceAndPath("vertex", "dynamic_sky")
    private val starShaderId = Identifier.fromNamespaceAndPath("vertex", "dynamic_stars")
    private val particleShaderId = Identifier.fromNamespaceAndPath("vertex", "dynamic_particles")
    private val pipelines = IdentityHashMap<RenderPipeline, RenderPipeline>()
    private val compiled = IdentityHashMap<RenderPipeline, CompiledRenderPipeline>()
    private var device: GpuDevice? = null
    private var prepared = false
    private var samplerNames = emptySet<String>()

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
    private val blockPipelines = listOf(
        RenderPipelines.SOLID_BLOCK,
        RenderPipelines.CUTOUT_BLOCK,
        RenderPipelines.TRANSLUCENT_BLOCK,
    )
    @JvmStatic
    @Synchronized
    fun prepare() {
        if (prepared || !PackRuntime.isEnabled()) return
        val context = SharedVulkanContext.attach()
        if (context.tier(ProgramFamily.DYNAMIC_WORLD) != RenderTier.TIER_2) return
        try {
            val root = PackRuntime.root(Minecraft.getInstance().gameDirectory.toPath())
            val gpu = RenderSystem.getDevice()
            device = gpu
            prepared = true
            val dimension = PackRuntime.dimension()
            val options = PackRuntime.options()
            val dynamic = PackFrontend.loadDynamic(root, options, dimension)
            val block = PackFrontend.loadBlock(root, options, dimension)
            samplerNames = (dynamic?.samplers.orEmpty() + block?.samplers.orEmpty()).toSet()
            dynamic?.let { program ->
                runCatching {
                    val failures = compileGroup(
                        gpu,
                        entityPipelines,
                        entityShaderId,
                        shaderSource(
                            entityShaderId,
                            LegacyTranslator.dynamicVertex(program),
                            LegacyTranslator.dynamicFragment(
                                program,
                                dropExtraTargets = true,
                                reverseDepth = PackChain.usesReverseDepth(),
                            ),
                        ),
                        ::compatible,
                        program.samplers.toSet(),
                        setOf("EMISSIVE"),
                    )
                    if (entityPipelines.any(pipelines::containsKey)) {
                        context.health.downgrade(ProgramFamily.HAND, RenderTier.TIER_2, "dynamic entity bridge is shared")
                        Vertex.log.info(
                            "[Vertex] dynamic render bridge armed: {} pipelines{}",
                            entityPipelines.count(pipelines::containsKey),
                            if (failures == 0) "" else "; skipped=$failures",
                        )
                    } else if (failures > 0) {
                        Vertex.log.warn("[Vertex] entity shader rejected by all {} compatible pipelines", failures)
                    }
                }.onFailure { Vertex.log.warn("[Vertex] entity shader rejected; retaining vanilla entity path", it) }
            }
            block?.let { program ->
                runCatching {
                    val failures = compileGroup(
                        gpu,
                        blockPipelines,
                        blockShaderId,
                        shaderSource(
                            blockShaderId,
                            LegacyTranslator.blockVertex(program),
                            LegacyTranslator.dynamicFragment(
                                program,
                                dropExtraTargets = true,
                                reverseDepth = PackChain.usesReverseDepth(),
                            ),
                        ),
                        ::blockCompatible,
                        program.samplers.toSet(),
                        setOf("EMISSIVE"),
                    )
                    if (blockPipelines.any(pipelines::containsKey)) {
                        Vertex.log.info(
                            "[Vertex] block render bridge armed: {} pipelines{}",
                            blockPipelines.count(pipelines::containsKey),
                            if (failures == 0) "" else "; skipped=$failures",
                        )
                    } else if (failures > 0) {
                        Vertex.log.warn("[Vertex] block shader rejected by all {} compatible pipelines", failures)
                    }
                }.onFailure { Vertex.log.warn("[Vertex] block shader rejected; retaining vanilla block path", it) }
            }
            if (entityPipelines.none(pipelines::containsKey) && blockPipelines.none(pipelines::containsKey)) {
                disable("no compatible entity or block shader pair")
            } else if (entityPipelines.none(pipelines::containsKey)) {
                context.health.downgrade(ProgramFamily.HAND, RenderTier.TIER_1, "entity shader bridge unavailable")
            }
            compileSpecializedPrograms(gpu, root, options, dimension)
            compileOptionalSceneFamilies(gpu, root, dimension)
        } catch (t: Throwable) {
            disable("dynamic pipeline preparation", t)
        }
    }

    @JvmStatic
    fun pipelineFor(original: RenderPipeline): RenderPipeline = pipelines[original] ?: original

    @JvmStatic
    fun compiledFor(pipeline: RenderPipeline): CompiledRenderPipeline? =
        compiled[pipeline] ?: pipelines[pipeline]?.let(compiled::get)

    @JvmStatic
    fun isPrepared() = prepared

    @JvmStatic
    fun samplerNames(): Set<String> = samplerNames

    @JvmStatic
    @Synchronized
    fun close() {
        compiled.values.distinct().forEach { runCatching { it.close() } }
        compiled.clear(); pipelines.clear(); device = null; prepared = false; samplerNames = emptySet()
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

    private fun blockCompatible(pipeline: RenderPipeline): Boolean {
        val format = pipeline.getVertexFormatBinding(0) ?: return false
        return format.contains("Position") && format.contains("Color") &&
            format.contains("UV0") && format.contains("UV2") && !format.contains("Normal")
    }

    private fun clone(original: RenderPipeline, shaderId: Identifier, samplers: Set<String>, skipDefines: Set<String> = emptySet()): RenderPipeline {
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
        builder.withBindGroupLayout(BindGroupLayout.builder().also { bindings ->
            samplers.forEach { bindings.withUniform(it, UniformType.COMBINED_IMAGE_SAMPLER) }
            bindings.withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER)
            bindings.withUniform("VertexPackUniforms", UniformType.UNIFORM_BUFFER)
        }.build())
        original.getColorTargetStates().forEachIndexed { index, target ->
            if (target == null) builder.withUnusedColorTargetState(index) else builder.withColorTargetState(index, target)
        }
        original.getDepthStencilState()?.let(builder::withDepthStencilState)
        if (original.pushConstantSize() > 0) builder.withPushConstantSize(original.pushConstantSize())
        original.getShaderDefines().flags.filterNot(skipDefines::contains).forEach(builder::withShaderDefine)
        original.getShaderDefines().values.forEach { (name, value) ->
            if (name in skipDefines) return@forEach
            value.toIntOrNull()?.let { builder.withShaderDefine(name, it) }
                ?: value.toFloatOrNull()?.let { builder.withShaderDefine(name, it) }
                ?: builder.withShaderDefine(name)
        }
        return builder.build()
    }

    private fun shaderSource(shaderId: Identifier, vsh: String, fsh: String): ShaderSource = ShaderSource { id, type ->
        if (id.namespace == "vertex" && id == shaderId) {
            when (type) {
                ShaderType.VERTEX -> vsh
                ShaderType.FRAGMENT -> fsh
                else -> null
            }
        } else loadResource(id, type)
    }

    private fun compileGroup(
        gpu: GpuDevice,
        originals: List<RenderPipeline>,
        shaderId: Identifier,
        source: ShaderSource,
        compatible: (RenderPipeline) -> Boolean,
        samplers: Set<String> = emptySet(),
        skipDefines: Set<String> = emptySet(),
    ): Int {
        var failures = 0
        originals.distinct().forEach { original ->
            if (!compatible(original)) return@forEach
            val custom = clone(original, shaderId, samplers, skipDefines)
            val built = gpu.compilePipeline(custom, source)
            if (built == null) failures++ else {
                pipelines[original] = custom
                compiled[custom] = built
            }
        }
        return failures
    }

    private fun compileSpecializedPrograms(
        gpu: GpuDevice,
        root: java.nio.file.Path,
        options: Map<String, String>,
        dimension: String,
    ) {
        var groups = 0
        var pipelineCount = 0
        fun entity(name: String, originals: List<RenderPipeline>) {
            val program = PackFrontend.loadProgram(root, name, options, dimension) ?: return
            samplerNames = samplerNames + program.samplers
            val armed = compileOverride(
                gpu, program, originals, ::compatible,
                LegacyTranslator.dynamicVertex(program),
                LegacyTranslator.dynamicFragment(program, dropExtraTargets = true, reverseDepth = PackChain.usesReverseDepth()),
            )
            if (armed > 0) { groups++; pipelineCount += armed }
        }
        fun block(name: String, originals: List<RenderPipeline>) {
            val program = PackFrontend.loadProgram(root, name, options, dimension) ?: return
            samplerNames = samplerNames + program.samplers
            val armed = compileOverride(
                gpu, program, originals, ::blockCompatible,
                LegacyTranslator.blockVertex(program),
                LegacyTranslator.dynamicFragment(program, dropExtraTargets = true, reverseDepth = PackChain.usesReverseDepth()),
            )
            if (armed > 0) { groups++; pipelineCount += armed }
        }
        fun auxiliary(
            name: String,
            originals: List<RenderPipeline>,
            compatible: (RenderPipeline) -> Boolean,
            vertex: (dev.vertex.frontend.LoadedProgram) -> String,
            bindAtlasFallback: Boolean = false,
            fragment: (dev.vertex.frontend.LoadedProgram) -> String = {
                LegacyTranslator.dynamicFragment(
                    it, dropExtraTargets = true, reverseDepth = PackChain.usesReverseDepth(),
                )
            },
        ) {
            val program = PackFrontend.loadProgram(root, name, options, dimension) ?: return
            samplerNames = samplerNames + program.samplers
            val translatedFragment = fragment(program)
            if (bindAtlasFallback && translatedFragment.contains("uniform sampler2D Sampler0;")) {
                samplerNames = samplerNames + "Sampler0"
            }
            val armed = compileOverride(
                gpu, program, originals, compatible, vertex(program), translatedFragment, bindAtlasFallback,
            )
            if (armed > 0) { groups++; pipelineCount += armed }
        }

        entity("gbuffers_entities_translucent", listOf(
            RenderPipelines.ENTITY_TRANSLUCENT,
            RenderPipelines.ENTITY_TRANSLUCENT_CULL,
        ))
        entity("gbuffers_entities_glowing", listOf(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE))
        entity("gbuffers_spidereyes", listOf(RenderPipelines.EYES))
        entity("gbuffers_hand", listOf(
            RenderPipelines.ITEM_CUTOUT,
            RenderPipelines.ITEM_CUTOUT_GLINT,
            RenderPipelines.ITEM_CUTOUT_GLINT_SPECIAL,
            RenderPipelines.ITEM_TRANSLUCENT,
            RenderPipelines.ITEM_TRANSLUCENT_GLINT,
            RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL,
        ))
        entity("gbuffers_hand_water", listOf(
            RenderPipelines.ITEM_TRANSLUCENT,
            RenderPipelines.ITEM_TRANSLUCENT_GLINT,
            RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL,
        ))
        entity("gbuffers_armor_glint", listOf(
            RenderPipelines.ARMOR_CUTOUT_NO_CULL_GLINT,
            RenderPipelines.ENTITY_SOLID_GLINT,
        ))
        block("gbuffers_block_translucent", listOf(RenderPipelines.TRANSLUCENT_BLOCK))
        block("gbuffers_damagedblock", listOf(RenderPipelines.CRUMBLING))
        block("gbuffers_beaconbeam", listOf(
            RenderPipelines.BEACON_BEAM_OPAQUE,
            RenderPipelines.BEACON_BEAM_TRANSLUCENT,
        ))
        auxiliary(
            "gbuffers_lightning", listOf(RenderPipelines.LIGHTNING), ::positionColor,
            vertex = { LegacyTranslator.coloredVertex(it) },
            bindAtlasFallback = true,
        )
        auxiliary("gbuffers_line", listOf(
            RenderPipelines.LINES,
            RenderPipelines.LINES_DEPTH_BIAS,
            RenderPipelines.LINES_TRANSLUCENT,
            RenderPipelines.LINES_TRANSLUCENT_NO_DEPTH_WRITE,
        ), ::positionColorNormal,
            vertex = { LegacyTranslator.coloredVertex(it, includeNormal = true) },
            bindAtlasFallback = true,
        )
        auxiliary(
            "gbuffers_skytextured",
            listOf(RenderPipelines.CELESTIAL),
            ::positionTexture,
            LegacyTranslator::texturedSkyVertex,
        ) { LegacyTranslator.skyFragment(it, includeFog = false, reverseDepth = PackChain.usesReverseDepth()) }
        PackFrontend.loadProgram(root, "gbuffers_clouds", options, dimension)?.let { program ->
            val cloudSamplers = program.samplers.filterNot { it in setOf("tex", "texture", "gtexture") }.toSet()
            samplerNames = samplerNames + cloudSamplers
            val cloudPipelines = listOf(RenderPipelines.FLAT_CLOUDS, RenderPipelines.CLOUDS)
            val armed = compileOverride(
                gpu,
                program,
                cloudPipelines,
                ::noVertexInput,
                LegacyTranslator.cloudVertex(program),
                LegacyTranslator.cloudFragment(program, PackChain.usesReverseDepth()),
                samplers = cloudSamplers,
            )
            if (armed > 0) { groups++; pipelineCount += armed }
        }
        if (groups > 0) Vertex.log.info(
            "[Vertex] specialized material bridges armed: {} groups, {} pipelines",
            groups, pipelineCount,
        )
    }

    private fun compileOverride(
        gpu: GpuDevice,
        program: dev.vertex.frontend.LoadedProgram,
        originals: List<RenderPipeline>,
        compatible: (RenderPipeline) -> Boolean,
        vertex: String,
        fragment: String,
        bindAtlasFallback: Boolean = false,
        samplers: Set<String> = program.samplers.toSet(),
    ): Int {
        val shaderId = Identifier.fromNamespaceAndPath("vertex", "dynamic_${program.name}")
        val previous = originals.associateWith { pipelines[it] }
        val failures = compileGroup(
            gpu,
            originals,
            shaderId,
            shaderSource(shaderId, vertex, fragment),
            compatible,
            samplers + if (bindAtlasFallback && fragment.contains("uniform sampler2D Sampler0;")) {
                setOf("Sampler0")
            } else emptySet(),
            setOf("EMISSIVE"),
        )
        val armed = originals.count { pipelines[it] !== previous[it] }
        if (armed == 0 && failures > 0) Vertex.log.warn(
            "[Vertex] specialized material program {} rejected by {} pipelines; retaining base bridge",
            program.name, failures,
        )
        return armed
    }

    private fun compileOptionalSceneFamilies(gpu: GpuDevice, root: java.nio.file.Path, dimension: String) {
        val options = PackRuntime.options()
        var skyArmed = false
        var particleArmed = false
        var weatherArmed = false
        val skyProgram = PackFrontend.loadSky(root, options, dimension)
        val particleProgram = PackFrontend.loadParticle(root, options, dimension)
        val weatherProgram = PackFrontend.loadWeather(root, options, dimension)
        skyProgram?.let { program ->
            runCatching {
                val source = shaderSource(
                    skyShaderId,
                    LegacyTranslator.skyVertex(program),
                    LegacyTranslator.skyFragment(program, reverseDepth = PackChain.usesReverseDepth()),
                )
                val skipped = compileGroup(
                    gpu,
                    // Minecraft clouds are procedural (CloudInfo/CloudFaces), not
                    // position-only geometry. Keep their vanilla pipeline; the pack
                    // sky shader has no cloud ABI and would otherwise remove them.
                    listOf(RenderPipelines.SKY),
                    skyShaderId,
                    source,
                    ::positionOnly,
                    program.samplers.toSet(),
                )
                skyArmed = pipelines.containsKey(RenderPipelines.SKY)
                if (skyArmed) Vertex.log.info("[Vertex] sky render bridge armed")
                if (skipped > 0) Vertex.log.debug("[Vertex] skipped {} sky pipelines", skipped)
            }.onFailure { Vertex.log.warn("[Vertex] sky shader rejected; retaining vanilla path", it) }
            runCatching {
                val source = shaderSource(
                    starShaderId,
                    LegacyTranslator.skyVertex(program, includeFog = false, forceTransparent = true),
                    LegacyTranslator.skyFragment(
                        program,
                        includeFog = false,
                        reverseDepth = PackChain.usesReverseDepth(),
                    ),
                )
                compileGroup(gpu, listOf(RenderPipelines.STARS), starShaderId, source, { positionOnly(it) }, program.samplers.toSet())
                skyArmed = skyArmed || pipelines.containsKey(RenderPipelines.STARS)
            }.onFailure { Vertex.log.debug("[Vertex] stars shader rejected; retaining vanilla path", it) }
        }
        particleProgram?.let { program ->
            runCatching {
                val source = shaderSource(
                    particleShaderId,
                    LegacyTranslator.particleVertex(program),
                    LegacyTranslator.dynamicFragment(program, dropExtraTargets = true, reverseDepth = PackChain.usesReverseDepth()),
                )
                val skipped = compileGroup(gpu, listOf(RenderPipelines.OPAQUE_PARTICLE, RenderPipelines.TRANSLUCENT_PARTICLE), particleShaderId, source, { particle(it) }, program.samplers.toSet())
                particleArmed = RenderPipelines.OPAQUE_PARTICLE in pipelines || RenderPipelines.TRANSLUCENT_PARTICLE in pipelines
                if (particleArmed) Vertex.log.info("[Vertex] particle render bridge armed")
                if (skipped > 0) Vertex.log.debug("[Vertex] skipped {} particle pipelines", skipped)
            }.onFailure { Vertex.log.warn("[Vertex] particle shader rejected; retaining vanilla path", it) }
        }
        PackFrontend.loadProgram(root, "gbuffers_particles_translucent", options, dimension)?.let { program ->
            samplerNames = samplerNames + program.samplers
            runCatching {
                val armed = compileOverride(
                    gpu,
                    program,
                    listOf(RenderPipelines.TRANSLUCENT_PARTICLE),
                    ::particle,
                    LegacyTranslator.particleVertex(program),
                    LegacyTranslator.dynamicFragment(
                        program,
                        dropExtraTargets = true,
                        reverseDepth = PackChain.usesReverseDepth(),
                    ),
                )
                if (armed > 0) Vertex.log.info("[Vertex] translucent particle material bridge armed")
            }.onFailure { Vertex.log.warn(
                "[Vertex] translucent particle shader rejected; retaining base particle bridge", it,
            ) }
        }
        weatherProgram?.let { program ->
            runCatching {
                val source = shaderSource(
                    particleShaderId,
                    LegacyTranslator.particleVertex(program),
                    LegacyTranslator.dynamicFragment(program, dropExtraTargets = true, reverseDepth = PackChain.usesReverseDepth()),
                )
                val skipped = compileGroup(gpu, listOf(RenderPipelines.WEATHER), particleShaderId, source, { particle(it) }, program.samplers.toSet())
                weatherArmed = RenderPipelines.WEATHER in pipelines
                if (weatherArmed) Vertex.log.info("[Vertex] weather render bridge armed")
                if (skipped > 0) Vertex.log.debug("[Vertex] skipped {} weather pipelines", skipped)
            }.onFailure { Vertex.log.warn("[Vertex] weather shader rejected; retaining vanilla path", it) }
        }
        if (skyProgram != null && !skyArmed || particleProgram != null && !particleArmed ||
            weatherProgram != null && !weatherArmed
        ) {
            SharedVulkanContext.attach().health.downgrade(
                ProgramFamily.SKY_WEATHER,
                RenderTier.TIER_1,
                "sky/weather shader bridge unavailable",
            )
        }
    }

    private fun positionOnly(pipeline: RenderPipeline): Boolean =
        pipeline.getVertexFormatBinding(0)?.let { it.contains("Position") && it.getElements().size == 1 } == true

    private fun particle(pipeline: RenderPipeline): Boolean =
        pipeline.getVertexFormatBinding(0)?.let {
            it.contains("Position") && it.contains("UV0") && it.contains("Color") && it.contains("UV2")
        } == true

    private fun positionColor(pipeline: RenderPipeline): Boolean =
        pipeline.getVertexFormatBinding(0)?.let {
            it.contains("Position") && it.contains("Color") && it.getElements().size == 2
        } == true

    private fun positionColorNormal(pipeline: RenderPipeline): Boolean =
        pipeline.getVertexFormatBinding(0)?.let {
            it.contains("Position") && it.contains("Color") && it.contains("Normal")
        } == true

    private fun positionTexture(pipeline: RenderPipeline): Boolean =
        pipeline.getVertexFormatBinding(0)?.let {
            it.contains("Position") && it.contains("UV0") && it.getElements().size == 2
        } == true

    private fun noVertexInput(pipeline: RenderPipeline): Boolean =
        pipeline.getVertexFormatBinding(0) == null

    private fun loadResource(id: Identifier, type: ShaderType?): String? {
        val location = type?.idConverter()?.idToFile(id) ?: id
        return try {
            Minecraft.getInstance().resourceManager.openAsReader(location).use { it.readText() }
        } catch (_: IOException) {
            null
        }
    }
}
