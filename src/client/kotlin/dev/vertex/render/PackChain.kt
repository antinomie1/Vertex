package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.platform.NativeImage
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
import dev.vertex.frontend.PackSemanticsParser
import dev.vertex.frontend.ColorFormat
import dev.vertex.runtime.ImageAllocation
import dev.vertex.runtime.ImageClass
import dev.vertex.runtime.MemoryBudgetGovernor
import dev.vertex.translate.LegacyTranslator
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.util.Optional
import java.nio.file.Files
import java.nio.file.Path
import org.joml.Vector4f

/**
 * 包运行时核心：colortex0 + depthtex0 + normalsTex 三通道复合链。
 * 不透明地形在主渲染 pass 内由 TerrainMesh 接管，避免二次重绘。
 */
object PackChain {
    private var screenPrograms = emptyList<ScreenProgram>()
    private var blit: CompiledRenderPipeline? = null
    private var sceneToColor0: CompiledRenderPipeline? = null
    private var normals: CompiledRenderPipeline? = null
    private var tempTex: GpuTexture? = null
    private var tempView: GpuTextureView? = null
    private val depthTextures = arrayOfNulls<GpuTexture>(3)
    private val depthViews = arrayOfNulls<GpuTextureView>(3)
    private val depthCaptured = BooleanArray(3)
    private var normalTex: GpuTexture? = null
    private var normalView: GpuTextureView? = null
    private var w = 0
    private var h = 0
    private var builtForW = 0
    private var builtForH = 0
    private var failed = false
    private var dbgFrame = 0L
    private var needsNormals = false
    private var neededDepths = emptySet<Int>()
    private var activeColors = emptySet<Int>()
    private var colorFormats = List(16) { GpuFormat.RGBA8_UNORM }
    private var colorClears = List<Vector4f?>(16) { Vector4f() }
    private val extraTextures = hashMapOf<Int, Array<GpuTexture>>()
    private val extraViews = hashMapOf<Int, Array<GpuTextureView>>()
    private val staticTextures = hashMapOf<String, GpuTexture>()
    private val staticViews = hashMapOf<String, GpuTextureView>()
    private var targetBytes = 0L
    private var packBudgetBytes = 512L * MIB
    private var staticBytes = 0L
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
            ensureSize(device, main.width, main.height)
            ensurePipelines(device)

            val encoder = device.createCommandEncoder()
            val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
            val dbg = System.getProperty("vertex.debugReadback") == "true"
            if (dbg && dbgFrame % 120L == 0L) {
                debugColorReadback(device, main.colorTexture!!, "a-scene-in")
            }

            if (0 in neededDepths) encoder.copyTextureToTexture(main.depthTexture ?: return, depthTextures[0]!!, 0, 0, 0, 0, 0, w, h)
            if (needsNormals) pass(encoder, "vertex-pack-normals", normalView!!) { rp ->
                rp.setPipeline(normals!!)
                rp.setUniform("depthtex0", depthViews[0]!!, sampler)
            }

            for ((id, pair) in extraTextures) colorClears[id]?.let { color ->
                for (texture in pair) encoder.clearColorTexture(texture, color)
            }
            banks.fill(0)
            if (customColor0()) pass(encoder, "vertex-pack-scene-input", colorView(0, 0, sceneView)) { rp ->
                RenderSystem.bindDefaultUniforms(rp)
                rp.setPipeline(sceneToColor0!!)
                rp.setUniform("InSampler", sceneView, sampler)
            }
            screenPrograms.forEach { program ->
                pass(encoder, "vertex-pack-${program.name}", program.outputs, sceneView) { rp ->
                    RenderSystem.bindDefaultUniforms(rp)
                    rp.setPipeline(program.pipeline)
                    program.samplers.forEach { name ->
                        rp.setUniform(name, program.staticSamplers[name] ?: samplerView(name, banks, sceneView), sampler)
                    }
                }
                for (id in program.outputs) if (program.flips[id] != false) banks[id] = banks[id] xor 1
                for ((id, flip) in program.flips) if (flip) banks[id] = banks[id] xor 1
            }
            val finalColor = colorView(0, banks[0], sceneView)
            if (finalColor !== sceneView) pass(encoder, "vertex-pack-blit", sceneView) { rp ->
                RenderSystem.bindDefaultUniforms(rp)
                rp.setPipeline(blit!!)
                rp.setUniform("InSampler", finalColor, sampler)
            }


            if (dbg && dbgFrame % 120L == 0L) {
                debugColorReadback(device, if (customColor0()) extraTextures.getValue(0)[banks[0]] else tempTex!!, "b-composite-out")
                debugColorReadback(device, main.colorTexture!!, "c-screen-final")
                dev.vertex.Vertex.log.info("[Vertex] dbg frame={} paused={}", dbgFrame, Minecraft.getInstance().isPaused)
            }
            dbgFrame++
        } catch (t: Throwable) {
            failed = true
            dev.vertex.Vertex.log.error("[Vertex] pack chain disabled for this session", t)
        }
    }

    @JvmStatic
    fun captureDepth(id: Int) {
        if (failed || id !in neededDepths) return
        try {
            val device = RenderSystem.getDevice()
            val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            ensureSize(device, main.width, main.height)
            device.createCommandEncoder().copyTextureToTexture(
                main.depthTexture ?: return, depthTextures[id]!!, 0, 0, 0, 0, 0, w, h,
            )
            if (!depthCaptured[id]) {
                depthCaptured[id] = true
                dev.vertex.Vertex.log.info("[Vertex] depthtex{} capture armed", id)
            }
        } catch (t: Throwable) {
            failed = true
            dev.vertex.Vertex.log.error("[Vertex] depthtex$id capture disabled the pack chain", t)
        }
    }

    @JvmStatic
    fun needsDepth(id: Int) = id in neededDepths


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
        listOf(tempView to tempTex, normalView to normalTex).forEach { (v, t) -> v?.close(); t?.close() }
        depthViews.forEach { it?.close() }; depthTextures.forEach { it?.close() }
        depthViews.fill(null); depthTextures.fill(null)
        extraViews.values.forEach { pair -> pair.forEach(GpuTextureView::close) }
        extraTextures.values.forEach { pair -> pair.forEach(GpuTexture::close) }
        extraViews.clear(); extraTextures.clear()
        if (!customColor0()) {
            tempTex = device.createTexture({ "vertex-temp" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
            tempView = device.createTextureView(tempTex!!)
        }
        normalTex = device.createTexture({ "vertex-normals" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
        normalView = device.createTextureView(normalTex!!)
        w = width; h = height
        createDepths(device)
        createExtraColors(device)
    }

    private fun ensurePipelines(device: com.mojang.renderpearl.api.device.GpuDevice) {
        if (screenPrograms.isNotEmpty() && blit != null && (!customColor0() || sceneToColor0 != null) &&
            (!needsNormals || normals != null && builtForW == w && builtForH == h)) return
        val runDir = Minecraft.getInstance().gameDirectory.toPath()
        val packRoot = PackRuntime.root(runDir)
        val programs = PackFrontend.loadScreenChain(packRoot, PackRuntime.options())
        val semantics = PackSemanticsParser.load(packRoot, PackRuntime.options())
        needsNormals = programs.any { "normalsTex" in it.samplers }
        neededDepths = programs.flatMap { it.samplers }
            .mapNotNull { DEPTH.matchEntire(it)?.groupValues?.get(1)?.toInt() }.toSet() +
            if (needsNormals) setOf(0) else emptySet()
        activeColors = (programs.flatMap { it.outputs } + programs.flatMap { it.samplers }.mapNotNull(::colorId) +
            semantics.flips.values.flatMap(Map<Int, Boolean>::keys)).toSet()
        enforceMemoryBudget(semantics.colors.map { it.format })
        colorFormats = semantics.colors.map { gpuFormat(it.format) }
        if (customColor0()) {
            tempView?.close(); tempTex?.close(); tempView = null; tempTex = null
        }
        createDepths(device)
        colorClears = semantics.colors.mapIndexed { id, setting ->
            if (!setting.clear) null else (setting.clearColor ?: if (id == 1) WHITE else ZERO).let(::vector)
        }
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
            val staticSamplers = samplers.mapNotNull { name ->
                staticSampler(device, packRoot.resolve("shaders"), semantics, program.name, name)?.let { name to it }
            }.toMap()
            require(samplers.all { colorId(it) != null || DEPTH.matches(it) || it == "normalsTex" || it in staticSamplers }) {
                "${program.name}: unsupported samplers ${samplers.filterNot { colorId(it) != null || DEPTH.matches(it) || it == "normalsTex" || it in staticSamplers }}"
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
            ScreenProgram(program.name, compile(device, programSource, vs, fs, layout,
                program.outputs.map(colorFormats::get)), samplers, program.outputs,
                semantics.flips[program.name].orEmpty(), staticSamplers)
        }
        if (blit == null) blit = compile(device, source, id("pack/post.v"), id("pack/blit.f"), BindGroupLayouts.IN_SAMPLER)
        if (customColor0() && sceneToColor0 == null) sceneToColor0 = compile(
            device, source, id("pack/post.v"), id("pack/blit.f"), BindGroupLayouts.IN_SAMPLER, listOf(colorFormats[0]),
        )

        builtForW = w; builtForH = h
    }

    private fun samplerView(name: String, banks: IntArray, scene: GpuTextureView): GpuTextureView = when (name) {
        "depthtex0" -> depthViews[0]!!
        "depthtex1" -> depthViews[1]!!
        "depthtex2" -> depthViews[2]!!
        "normalsTex" -> normalView!!
        else -> colorId(name)?.let { colorView(it, banks[it], scene) } ?: error("unsupported sampler '$name'")
    }

    private fun colorView(id: Int, bank: Int, scene: GpuTextureView): GpuTextureView = when {
        id == 0 && customColor0() -> extraViews.getValue(0)[bank]
        id == 0 && bank == 0 -> scene
        id == 0 -> tempView!!
        else -> extraViews.getValue(id)[bank]
    }

    private fun createExtraColors(device: com.mojang.renderpearl.api.device.GpuDevice) {
        activeColors.filter { it != 0 || customColor0() }.filterNot(extraTextures::containsKey).forEach { id ->
            val textures = Array(2) { bank -> device.createTexture(
                { "vertex-colortex$id-$bank" },
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_DST,
                colorFormats[id], w, h, 1, 1,
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

    private fun customColor0() = colorFormats[0] != GpuFormat.RGBA8_UNORM

    private fun staticSampler(
        device: com.mojang.renderpearl.api.device.GpuDevice,
        shaders: Path,
        semantics: dev.vertex.frontend.PackSemantics,
        program: String,
        name: String,
    ): GpuTextureView? {
        val stage = if (program.startsWith("deferred")) "deferred" else "composite"
        val path = if (name == "noisetex") semantics.noisePath else semantics.customTextures[stage]?.get(name)
        if (path == null && name != "noisetex") return null
        val key = path?.let { "file:$it" } ?: "noise:${semantics.noiseResolution}"
        return staticViews.getOrPut(key) {
            val image = path?.let { readImage(shaders, it) } ?: generateNoise(semantics.noiseResolution)
            image.use {
                val bytes = it.width.toLong() * it.height * 4L
                require(it.width <= 4096 && it.height <= 4096 && targetBytes + staticBytes + bytes <= packBudgetBytes) {
                    "static texture $name exceeds the pack memory budget"
                }
                val texture = device.createTexture(
                    { "vertex-$name" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, it.width, it.height, 1, 1,
                )
                device.createCommandEncoder().writeToTexture(texture, it)
                staticBytes += bytes
                staticTextures[key] = texture
                dev.vertex.Vertex.log.info("[Vertex] static texture '{}' loaded ({}x{})", name, it.width, it.height)
                device.createTextureView(texture)
            }
        }
    }

    private fun readImage(shaders: Path, value: String): NativeImage {
        val path = shaders.resolve(value.removePrefix("/")).normalize()
        require(path.startsWith(shaders) && Files.isRegularFile(path)) { "custom texture is outside the pack or missing: $value" }
        return Files.newInputStream(path).use(NativeImage::read)
    }

    private fun generateNoise(size: Int) = NativeImage(size, size, false).also { image ->
        for (y in 0 until size) for (x in 0 until size) {
            var n = x * 0x1f123bb5 + y * 0x05491333
            n = (n xor (n ushr 16)) * -0x7a143595
            image.setPixelABGR(x, y, -0x1000000 or (n and 0xFFFFFF))
        }
    }

    private fun createDepths(device: com.mojang.renderpearl.api.device.GpuDevice) {
        for (id in neededDepths) if (depthTextures[id] == null) {
            depthTextures[id] = device.createTexture(
                { "vertex-depthtex$id" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
                GpuFormat.D32_FLOAT, w, h, 1, 1,
            )
            depthViews[id] = device.createTextureView(depthTextures[id]!!)
        }
    }

    private fun enforceMemoryBudget(formats: List<ColorFormat>) {
        val allocations = activeColors.flatMap { id ->
            val copies = if (id == 0 && formats[id] == ColorFormat.RGBA8) 1 else 2
            List(copies) { bank -> ImageAllocation("colortex$id-$bank", w, h, formats[id].bytesPerPixel,
                if (id == 0) ImageClass.CRITICAL else ImageClass.NON_CRITICAL) }
        } + neededDepths.map { ImageAllocation("depthtex$it", w, h, 4, ImageClass.CRITICAL) } +
            if (needsNormals) listOf(ImageAllocation("normalsTex", w, h, 4, ImageClass.NON_CRITICAL)) else emptyList()
        val budgetMiB = System.getProperty("vertex.memoryBudgetMiB")?.toLongOrNull() ?: 512L
        require(budgetMiB in 64..16384) { "vertex.memoryBudgetMiB must be between 64 and 16384" }
        packBudgetBytes = budgetMiB * MIB
        val plan = MemoryBudgetGovernor.plan(allocations, packBudgetBytes, w, h)
        targetBytes = plan.bytes
        dev.vertex.Vertex.log.info("[Vertex] pack target budget: {} MiB / {} MiB", plan.bytes / MIB, budgetMiB)
    }

    private fun compile(
        device: com.mojang.renderpearl.api.device.GpuDevice,
        source: ShaderSource,
        vs: Identifier,
        fs: Identifier,
        layout: BindGroupLayout,
        colorTargets: List<GpuFormat> = listOf(GpuFormat.RGBA8_UNORM),
    ): CompiledRenderPipeline {
        val builder = com.mojang.renderpearl.api.pipeline.RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("vertex", fs.path.substringAfterLast('/')))
            .withVertexShader(vs)
            .withFragmentShader(fs)
            .withBindGroupLayout(layout)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        colorTargets.forEachIndexed { index, format ->
            builder.withColorTargetState(index, ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
        }
        return device.compilePipeline(builder.build(), source)
            ?: throw IllegalStateException("compile failed: ${fs.path}")
    }


    private fun id(path: String) = Identifier.fromNamespaceAndPath("vertex", path)

    private data class ScreenProgram(
        val name: String,
        val pipeline: CompiledRenderPipeline,
        val samplers: List<String>,
        val outputs: List<Int>,
        val flips: Map<Int, Boolean>,
        val staticSamplers: Map<String, GpuTextureView>,
    )

    private fun gpuFormat(format: ColorFormat): GpuFormat = when (format) {
        ColorFormat.R8 -> GpuFormat.R8_UNORM; ColorFormat.RG8 -> GpuFormat.RG8_UNORM; ColorFormat.RGB8 -> GpuFormat.RGB8_UNORM; ColorFormat.RGBA8 -> GpuFormat.RGBA8_UNORM
        ColorFormat.R8_SNORM -> GpuFormat.R8_SNORM; ColorFormat.RG8_SNORM -> GpuFormat.RG8_SNORM; ColorFormat.RGB8_SNORM -> GpuFormat.RGB8_SNORM; ColorFormat.RGBA8_SNORM -> GpuFormat.RGBA8_SNORM
        ColorFormat.R16 -> GpuFormat.R16_UNORM; ColorFormat.RG16 -> GpuFormat.RG16_UNORM; ColorFormat.RGB16 -> GpuFormat.RGB16_UNORM; ColorFormat.RGBA16 -> GpuFormat.RGBA16_UNORM
        ColorFormat.R16_SNORM -> GpuFormat.R16_SNORM; ColorFormat.RG16_SNORM -> GpuFormat.RG16_SNORM; ColorFormat.RGB16_SNORM -> GpuFormat.RGB16_SNORM; ColorFormat.RGBA16_SNORM -> GpuFormat.RGBA16_SNORM
        ColorFormat.R8I -> GpuFormat.R8_SINT; ColorFormat.R8UI -> GpuFormat.R8_UINT; ColorFormat.RG8I -> GpuFormat.RG8_SINT; ColorFormat.RG8UI -> GpuFormat.RG8_UINT
        ColorFormat.RGB8I -> GpuFormat.RGB8_SINT; ColorFormat.RGB8UI -> GpuFormat.RGB8_UINT; ColorFormat.RGBA8I -> GpuFormat.RGBA8_SINT; ColorFormat.RGBA8UI -> GpuFormat.RGBA8_UINT
        ColorFormat.R16I -> GpuFormat.R16_SINT; ColorFormat.R16UI -> GpuFormat.R16_UINT; ColorFormat.RG16I -> GpuFormat.RG16_SINT; ColorFormat.RG16UI -> GpuFormat.RG16_UINT
        ColorFormat.RGB16I -> GpuFormat.RGB16_SINT; ColorFormat.RGB16UI -> GpuFormat.RGB16_UINT; ColorFormat.RGBA16I -> GpuFormat.RGBA16_SINT; ColorFormat.RGBA16UI -> GpuFormat.RGBA16_UINT
        ColorFormat.R32I -> GpuFormat.R32_SINT; ColorFormat.R32UI -> GpuFormat.R32_UINT; ColorFormat.RG32I -> GpuFormat.RG32_SINT; ColorFormat.RG32UI -> GpuFormat.RG32_UINT
        ColorFormat.RGB32I -> GpuFormat.RGB32_SINT; ColorFormat.RGB32UI -> GpuFormat.RGB32_UINT; ColorFormat.RGBA32I -> GpuFormat.RGBA32_SINT; ColorFormat.RGBA32UI -> GpuFormat.RGBA32_UINT
        ColorFormat.R16F -> GpuFormat.R16_FLOAT; ColorFormat.RG16F -> GpuFormat.RG16_FLOAT; ColorFormat.RGB16F -> GpuFormat.RGB16_FLOAT; ColorFormat.RGBA16F -> GpuFormat.RGBA16_FLOAT
        ColorFormat.R32F -> GpuFormat.R32_FLOAT; ColorFormat.RG32F -> GpuFormat.RG32_FLOAT; ColorFormat.RGB32F -> GpuFormat.RGB32_FLOAT; ColorFormat.RGBA32F -> GpuFormat.RGBA32_FLOAT
        ColorFormat.RGBA2, ColorFormat.RGBA4, ColorFormat.RGB5_A1 -> GpuFormat.RGBA8_UNORM
        ColorFormat.R3_G3_B2, ColorFormat.RGB565 -> GpuFormat.RGB8_UNORM
        ColorFormat.RGB10_A2 -> GpuFormat.RGB10A2_UNORM; ColorFormat.RGB10_A2UI -> GpuFormat.RGB10A2_UINT
        ColorFormat.R11F_G11F_B10F, ColorFormat.RGB9_E5 -> GpuFormat.RG11B10_FLOAT
    }

    private fun vector(values: List<Float>) = Vector4f(values[0], values[1], values[2], values[3])

    private val COLORTEX = Regex("""colortex(\d|1[0-5])""")
    private val DEPTH = Regex("""depthtex([0-2])""")
    private val ZERO = listOf(0f, 0f, 0f, 0f)
    private val WHITE = listOf(1f, 1f, 1f, 1f)
    private const val MIB = 1024L * 1024L

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
