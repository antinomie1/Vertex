package dev.vertex.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.AddressMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.vertex.frontend.PackFrontend
import dev.vertex.frontend.PackRuntime
import dev.vertex.frontend.PackSemanticsParser
import dev.vertex.frontend.ColorFormat
import dev.vertex.mixin.GameRendererProjectionAccessor
import dev.vertex.mixin.ProjectionMatrixBufferAccessor
import dev.vertex.core.RuntimeDiagnostics
import dev.vertex.core.SharedVulkanContext
import dev.vertex.runtime.ImageAllocation
import dev.vertex.runtime.ImageClass
import dev.vertex.runtime.MemoryBudgetGovernor
import dev.vertex.runtime.UniformHeap
import dev.vertex.runtime.PerformanceWindow
import dev.vertex.runtime.PerformanceBaseline
import dev.vertex.runtime.PerformanceGate
import dev.vertex.runtime.ProgramFamily
import dev.vertex.runtime.RenderTier
import dev.vertex.runtime.RenderTargetBanks
import dev.vertex.runtime.RenderScalePolicy
import dev.vertex.runtime.ScreenPassOptimizer
import dev.vertex.translate.LegacyTranslator
import dev.vertex.translate.PackUniformCatalog
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.phys.Vec3
import java.util.Optional
import java.nio.file.Files
import java.nio.file.Path
import org.joml.Vector4f
import org.joml.Matrix4f
import org.joml.Vector3f
import java.time.LocalDateTime

/**
 * 包运行时核心：colortex0 + depthtex0 + normalsTex 三通道复合链。
 * 不透明地形在主渲染 pass 内由 TerrainMesh 接管，避免二次重绘。
 */
object PackChain {
    private var earlyPrograms = emptyList<ScreenProgram>()
    private var screenPrograms = emptyList<ScreenProgram>()
    private var blit: CompiledRenderPipeline? = null
    private var sceneToColor0: CompiledRenderPipeline? = null
    private var normals: CompiledRenderPipeline? = null
    private var depthScale: CompiledRenderPipeline? = null
    private var tempTex: GpuTexture? = null
    private var tempView: GpuTextureView? = null
    private val depthTextures = arrayOfNulls<GpuTexture>(3)
    private val depthViews = arrayOfNulls<GpuTextureView>(3)
    private val depthCaptured = BooleanArray(3)
    private var normalTex: GpuTexture? = null
    private var normalView: GpuTextureView? = null
    private var w = 0
    private var h = 0
    private var screenW = 0
    private var screenH = 0
    private var renderScale = 1f
    private var scaleResolved = false
    private var builtForW = 0
    private var builtForH = 0
    private var failed = false
    private var volumetricClouds = false
    private var dbgFrame = 0L
    private var needsNormals = false
    private var neededDepths = emptySet<Int>()
    private var activeColors = emptySet<Int>()
    private var colorFormats = List(16) { GpuFormat.RGBA8_UNORM }
    private var colorClears = List<Vector4f?>(16) { Vector4f() }
    private val extraTextures = hashMapOf<Int, Array<GpuTexture>>()
    private val extraViews = hashMapOf<Int, Array<GpuTextureView>>()
    private val staticTextures = hashMapOf<String, GpuTexture>()
    private val staticBindings = hashMapOf<String, TextureBinding>()
    private val staticByName = hashMapOf<String, TextureBinding>()
    private val textureSamplers = hashMapOf<TextureFilter, GpuSampler>()
    private var targetBytes = 0L
    private var packBudgetBytes = 512L * MIB
    private var staticBytes = 0L
    private val banks = RenderTargetBanks()
    private var frameReady = false
    private var frameProjection: Matrix4f? = null
    private var frameViewRotation: Matrix4f? = null
    private var frameCameraPosition: Vec3? = null
    private var frameFar = 0f
    private var pendingProjection: Matrix4f? = null
    private var pendingViewRotation: Matrix4f? = null
    private var pendingCameraPosition: Vec3? = null
    private var pendingFar = 0f
    // Vulkan may keep several submissions in flight; two UBO slots can be
    // overwritten while an older frame is still sampling them.
    private const val FRAME_SLOTS = 4
    private val uniformHeap = UniformHeap(PackUniformCatalog.layout, FRAME_SLOTS)
    private var uniformBuffer: GpuBuffer? = null
    private var uniformSlot = 0
    private var frameCounter = 0
    private var lastFrameNanos = System.nanoTime()
    private var frameTimeCounter = 0f
    private val previousCamera = FloatArray(3)
    private val previousModelView = Matrix4f()
    private val previousProjection = Matrix4f()
    private var previousFrameInitialized = false
    private val inverseMatrix = Matrix4f()
    private val projectionMatrix = Matrix4f()
    // Depth textures are inverted below for Vulkan's reverse-Z convention, so the
    // reconstructed clip-space Z must be inverted as well (near=-1, far=+1).
    private val vulkanToLegacyClip = Matrix4f().identity().m22(-2f).m32(1f)
    private val reverseDepth = DepthStencilState.DEFAULT.depthTest() == CompareOp.GREATER_THAN_OR_EQUAL
    private val matrixScratch = FloatArray(16)
    private val normalScratch = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val dateScratch = IntArray(8)
    private var dateSecond = Long.MIN_VALUE
    private var timingPool: GpuQueryPool? = null
    private var timestampPeriod = 1f
    private val timingArmed = BooleanArray(FRAME_SLOTS)
    private val gpuTimes = PerformanceWindow()
    private var timingsSinceReport = 0
    private var perfBaselineWritten = false

    @JvmStatic
    fun close() {
        earlyPrograms.flatMap { listOf(it.pipeline) }.plus(screenPrograms.map(ScreenProgram::pipeline))
            .plus(listOf(blit, sceneToColor0, normals, depthScale).filterNotNull())
            .distinct().forEach { runCatching { it.close() } }
        listOf(tempView to tempTex, normalView to normalTex).forEach { (view, texture) ->
            runCatching { view?.close() }; runCatching { texture?.close() }
        }
        depthViews.forEach { runCatching { it?.close() } }
        depthTextures.forEach { runCatching { it?.close() } }
        extraViews.values.flatMap { it.asIterable() }.forEach { runCatching { it.close() } }
        extraTextures.values.flatMap { it.asIterable() }.forEach { runCatching { it.close() } }
        staticBindings.values.forEach { runCatching { it.view.close() } }
        staticTextures.values.forEach { runCatching { it.close() } }
        textureSamplers.values.forEach { runCatching { it.close() } }
        runCatching { uniformBuffer?.close() }
        runCatching { timingPool?.close() }
        earlyPrograms = emptyList(); screenPrograms = emptyList()
        blit = null; sceneToColor0 = null; normals = null; depthScale = null
        tempTex = null; tempView = null; normalTex = null; normalView = null
        depthTextures.fill(null); depthViews.fill(null)
        extraTextures.clear(); extraViews.clear(); staticTextures.clear(); staticBindings.clear(); staticByName.clear(); textureSamplers.clear()
        uniformBuffer = null; timingPool = null; frameReady = false; failed = false
        previousFrameInitialized = false; uniformSlot = 0; frameCounter = 0; frameTimeCounter = 0f
        banks.reset()
        frameProjection = null; frameViewRotation = null; frameCameraPosition = null; frameFar = 0f
        pendingProjection = null; pendingViewRotation = null; pendingCameraPosition = null; pendingFar = 0f
        scaleResolved = false; builtForW = 0; builtForH = 0; w = 0; h = 0
        volumetricClouds = false
        targetBytes = 0; staticBytes = 0; needsNormals = false; neededDepths = emptySet(); activeColors = emptySet()
        timingArmed.fill(false); timingsSinceReport = 0; perfBaselineWritten = false; dateSecond = Long.MIN_VALUE
    }

    fun prepare() {
        if (!enabled()) return
        try {
            val device = RenderSystem.getDevice()
            val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            ensureMainSize(device, main.width, main.height)
            ensurePipelines(device)
            dev.vertex.Vertex.log.info("[Vertex] pack pipelines prewarmed ({}x{})", w, h)
        } catch (t: Throwable) {
            disable("screen pipeline prewarm", t)
        }
    }

    fun draw() {
        if (!enabled()) return
        try {
            if (!frameReady) beginFrame()
            if (failed || !frameReady) return
            val device = RenderSystem.getDevice()
            val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            val sceneView = main.colorTextureView ?: return
            ensureMainSize(device, main.width, main.height)
            ensurePipelines(device)

            val encoder = device.createCommandEncoder()
            val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
            val dbg = System.getProperty("vertex.debugReadback") == "true"
            if (dbg && dbgFrame % 120L == 0L) {
                debugColorReadback(device, main.colorTexture!!, "a-scene-in")
            }

            if (0 in neededDepths) captureDepth(encoder, main.depthTexture ?: return, main.depthTextureView ?: return, 0)
            if (needsNormals) pass(encoder, "vertex-pack-normals", normalView!!) { rp ->
                rp.setPipeline(normals!!)
                rp.setUniform("depthtex0", depthViews[0]!!, sampler)
            }

            banks[0] = 0 // world rendering refreshes the scene target after early programs
            if (dedicatedColor0()) pass(encoder, "vertex-pack-scene-input", colorView(0, 0, sceneView)) { rp ->
                RenderSystem.bindDefaultUniforms(rp)
                rp.setPipeline(sceneToColor0!!)
                rp.setUniform("InSampler", sceneView, sampler)
            }
            executePrograms(encoder, sampler, sceneView, screenPrograms)
            val finalColor = colorView(0, banks[0], sceneView)
            if (finalColor !== sceneView) pass(encoder, "vertex-pack-blit", sceneView) { rp ->
                RenderSystem.bindDefaultUniforms(rp)
                rp.setPipeline(blit!!)
                rp.setUniform("InSampler", finalColor, sampler)
            }
            ReplayCapture.capture(device, main.colorTexture!!, screenW, screenH)
            timingPool?.let { pool ->
                encoder.writeTimestamp(pool, uniformSlot * 2 + 1)
                timingArmed[uniformSlot] = true
            }


            if (dbg && dbgFrame % 120L == 0L) {
                debugColorReadback(device, if (dedicatedColor0()) extraTextures.getValue(0)[banks[0]] else tempTex!!, "b-composite-out")
                debugColorReadback(device, main.colorTexture!!, "c-screen-final")
                dev.vertex.Vertex.log.info("[Vertex] dbg frame={} paused={}", dbgFrame, Minecraft.getInstance().isPaused)
            }
            dbgFrame++
            frameReady = false
        } catch (t: Throwable) {
            disable("screen chain execution", t)
        }
    }

    @JvmStatic
    fun beginFrame() = beginFrame(null)

    @JvmStatic
    fun setFrameCamera(camera: CameraRenderState) {
        if (!camera.projectionMatrix.m00().isFinite()) return
        pendingProjection = Matrix4f(camera.projectionMatrix)
        pendingViewRotation = Matrix4f(camera.viewRotationMatrix)
        pendingCameraPosition = camera.pos
        pendingFar = camera.depthFar.takeIf { it.isFinite() && it > 0f } ?: 0f
    }

    /** The interpolated pose shared by the shadow and screen passes for this frame. */
    @JvmStatic
    fun cameraPositionForFrame(): Vec3 =
        pendingCameraPosition ?: frameCameraPosition ?: Minecraft.getInstance().gameRenderer.mainCamera().position()

    @JvmStatic
    fun beginFrame(camera: CameraRenderState?) {
        if (!enabled() || frameReady) return
        if (camera != null) {
            frameProjection = Matrix4f(camera.projectionMatrix)
            frameViewRotation = Matrix4f(camera.viewRotationMatrix)
            frameCameraPosition = camera.pos
            frameFar = camera.depthFar.takeIf { it.isFinite() && it > 0f } ?: 0f
        } else {
            pendingProjection?.let { frameProjection = it }
            pendingViewRotation?.let { frameViewRotation = it }
            pendingCameraPosition?.let { frameCameraPosition = it }
            if (pendingFar > 0f) frameFar = pendingFar
            pendingProjection = null
            pendingViewRotation = null
            pendingCameraPosition = null
            pendingFar = 0f
        }
        try {
            val device = RenderSystem.getDevice()
            val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            val scene = main.colorTextureView ?: return
            ensureMainSize(device, main.width, main.height)
            ensurePipelines(device)
            val encoder = device.createCommandEncoder()
            collectGpuTiming(uniformSlot)
            timingPool?.let { encoder.writeTimestamp(it, uniformSlot * 2) }
            updateUniforms(encoder)
            // Keep the read bank from the previous frame for TAA/reflections;
            // only clear the bank this frame's first writer will replace.
            for ((id, pair) in extraTextures) colorClears[id]?.let { color ->
                encoder.clearColorTexture(pair[banks[id] xor 1], color)
            }
            val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
            if (dedicatedColor0()) pass(encoder, "vertex-pack-early-input", colorView(0, 0, scene)) { rp ->
                RenderSystem.bindDefaultUniforms(rp); rp.setPipeline(sceneToColor0!!); rp.setUniform("InSampler", scene, sampler)
            }
            executePrograms(encoder, sampler, scene, earlyPrograms)
            frameReady = true
        } catch (t: Throwable) {
            disable("early screen programs", t)
        }
    }

    private fun executePrograms(
        encoder: CommandEncoder,
        sampler: com.mojang.renderpearl.api.textures.GpuSampler,
        scene: GpuTextureView,
        programs: List<ScreenProgram>,
    ) = programs.forEach { program ->
        pass(encoder, "vertex-pack-${program.name}", program.outputs, scene) { rp ->
            RenderSystem.bindDefaultUniforms(rp)
            rp.setPipeline(program.pipeline)
            program.samplers.forEach { name ->
                val binding = program.staticSamplers[name]
                if (binding != null) rp.setUniform(name, binding.view, binding.sampler)
                else rp.setUniform(name, samplerView(name, banks, scene), sampler)
            }
            if (program.uniforms.isNotEmpty()) rp.setUniform(
                "VertexPackUniforms",
                uniformBuffer!!.slice(uniformHeap.segmentOffset(uniformSlot).toLong(), uniformHeap.layout.segmentBytes.toLong()),
            )
        }
        banks.commit(program.outputs, program.flips)
    }

    @JvmStatic
    fun captureDepth(id: Int) {
        if (!enabled() || id !in neededDepths) return
        try {
            val device = RenderSystem.getDevice()
            val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            ensureMainSize(device, main.width, main.height)
            captureDepth(device.createCommandEncoder(), main.depthTexture ?: return, main.depthTextureView ?: return, id)
            if (!depthCaptured[id]) {
                depthCaptured[id] = true
                dev.vertex.Vertex.log.info("[Vertex] depthtex{} capture armed", id)
            }
        } catch (t: Throwable) {
            disable("depthtex$id capture", t)
        }
    }

    @JvmStatic
    fun needsDepth(id: Int) = id in neededDepths

    @JvmStatic
    fun usesVolumetricClouds() = volumetricClouds

    /** Native RenderPearl depth is reversed on the Vulkan path. */
    @JvmStatic
    fun usesReverseDepth() = reverseDepth

    /** Binds the current frame's pack uniforms to game-owned render passes. */
    @JvmStatic
    fun bindUniforms(pass: RenderPass) {
        val buffer = uniformBuffer ?: return
        runCatching {
            pass.setUniform(
                "VertexPackUniforms",
                buffer.slice(uniformHeap.segmentOffset(uniformSlot).toLong(), uniformHeap.layout.segmentBytes.toLong()),
            )
        }
    }

    @JvmStatic
    fun bindTerrainSamplers(pass: RenderPass, sampler: GpuSampler, atlas: GpuTextureView) {
        if (!PackRuntime.isEnabled()) return
        // Minecraft's chunk-layer sampler is linear, but atlas coordinates point at
        // individual texels.  Nearest filtering keeps tile edges crisp and matches
        // the vanilla atlas sampler (including without mipmap support).
        val atlasSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        pass.setUniform("Sampler0", atlas, atlasSampler)
        staticByName["noisetex"]?.let { pass.setUniform("noisetex", it.view, it.sampler) }
        bindShadowSamplers(pass, sampler)
        bindUniforms(pass)
    }

    @JvmStatic
    fun bindTerrainAtlas(pass: RenderPass, atlas: GpuTextureView) {
        bindAtlas(pass, atlas)
    }

    /** Minecraft's atlas is a point-sampled pixel-art texture. */
    @JvmStatic
    fun bindAtlas(pass: RenderPass, atlas: GpuTextureView) {
        if (!PackRuntime.isEnabled()) return
        pass.setUniform("Sampler0", atlas, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
    }

    private fun bindShadowSamplers(pass: RenderPass, sampler: GpuSampler) {
        fun bind(name: String, view: GpuTextureView?) {
            if (view != null) runCatching { pass.setUniform(name, view, sampler) }
        }
        bind("shadowtex0", ShadowRenderer.view("shadowtex0"))
        bind("shadowtex1", ShadowRenderer.view("shadowtex1"))
        bind("shadowcolor0", ShadowRenderer.view("shadowcolor0"))
        bind("shadowcolor1", ShadowRenderer.view("shadowcolor1"))
    }

    @JvmStatic
    fun bindDynamicSamplers(pass: RenderPass) {
        if (!PackRuntime.isEnabled()) return
        val scene = Minecraft.getInstance().gameRenderer.mainRenderTarget().colorTextureView
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        fun bind(name: String, view: GpuTextureView?) {
            if (view == null) return
            runCatching { pass.setUniform(name, view, sampler) }
        }
        staticByName["noisetex"]?.let { runCatching { pass.setUniform("noisetex", it.view, it.sampler) } }
        bind("Sampler2", Minecraft.getInstance().gameRenderer.lightmap())
        bind("depthtex0", depthViews[0] ?: scene)
        bind("depthtex1", depthViews[1] ?: scene)
        bind("depthtex2", depthViews[2] ?: scene)
        bind("normalsTex", normalView)
        bindShadowSamplers(pass, sampler)
    }

    private fun enabled() = !failed && PackRuntime.isEnabled() &&
        SharedVulkanContext.attach().tier(ProgramFamily.SCREEN_CHAIN) == RenderTier.TIER_2


    private fun debugColorReadback(device: com.mojang.renderpearl.api.device.GpuDevice, tex: GpuTexture, tag: String) {
        val bw = tex.getWidth(0) / 4 * 4
        val bh = tex.getHeight(0) / 4 * 4
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

    private fun captureDepth(encoder: CommandEncoder, source: GpuTexture, sourceView: GpuTextureView, id: Int) {
        if (renderScale == 1f && !reverseDepth) {
            encoder.copyTextureToTexture(source, depthTextures[id]!!, 0, 0, 0, 0, 0, w, h)
            return
        }
        val descriptor = RenderPassDescriptor.builder { "vertex-depth-scale-$id" }
            .withDepthAttachment(depthViews[id]!!, java.util.OptionalDouble.empty()).build()
        encoder.createRenderPass(descriptor).use { pass ->
            RenderSystem.bindDefaultUniforms(pass)
            pass.setPipeline(depthScale!!)
            pass.setUniform("InSampler", sourceView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
            pass.draw(3, 1, 0, 0)
        }
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
        if (!dedicatedColor0()) {
            tempTex = device.createTexture({ "vertex-temp" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
            tempView = device.createTextureView(tempTex!!)
        }
        normalTex = device.createTexture({ "vertex-normals" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
        normalView = device.createTextureView(normalTex!!)
        w = width; h = height
        createDepths(device)
        createExtraColors(device)
    }

    private fun ensureMainSize(device: com.mojang.renderpearl.api.device.GpuDevice, width: Int, height: Int) {
        screenW = width; screenH = height
        ensureSize(device, (width * renderScale).toInt().coerceAtLeast(1),
            (height * renderScale).toInt().coerceAtLeast(1))
    }

    private fun ensurePipelines(device: com.mojang.renderpearl.api.device.GpuDevice) {
        if ((screenPrograms.isNotEmpty() || earlyPrograms.isNotEmpty()) && blit != null && (!dedicatedColor0() || sceneToColor0 != null) &&
            (!needsNormals || normals != null && builtForW == w && builtForH == h)) return
        val runDir = Minecraft.getInstance().gameDirectory.toPath()
        val packRoot = PackRuntime.root(runDir)
        ShadowRenderer.discover()
        val loadedPrograms = PackFrontend.loadScreenChain(packRoot, PackRuntime.options())
        val programs = loadedPrograms.filterNot {
            ScreenPassOptimizer.isIdentityCopy(it.fragmentSource, it.outputs, it.samplers)
        }
        volumetricClouds = programs.any { it.name == "deferred1" && it.fragmentSource.contains("DrawCloudVolumetric") }
        if (volumetricClouds) dev.vertex.Vertex.log.info("[Vertex] volumetric cloud pass detected")
        if (programs.size != loadedPrograms.size) dev.vertex.Vertex.log.info(
            "[Vertex] eliminated {} identity screen-pass boundaries", loadedPrograms.size - programs.size,
        )
        val semantics = PackSemanticsParser.load(packRoot, PackRuntime.options())
        if (!scaleResolved) {
            val decision = RenderScalePolicy.resolve(
                System.getProperty("vertex.renderScale")?.toFloatOrNull() ?: 1f,
                programs.map { it.fragmentSource },
            )
            renderScale = decision.scale
            decision.reason?.let { dev.vertex.Vertex.log.warn("[Vertex] render scale disabled: {}", it) }
            scaleResolved = true
            ensureMainSize(device, screenW, screenH)
            if (renderScale < 1f) dev.vertex.Vertex.log.info("[Vertex] internal render scale: {} ({}x{})", renderScale, w, h)
        }
        needsNormals = programs.any { "normalsTex" in it.samplers }
        neededDepths = programs.flatMap { it.samplers }
            .mapNotNull { DEPTH.matchEntire(it)?.groupValues?.get(1)?.toInt() }.toSet() +
            if (needsNormals) setOf(0) else emptySet()
        activeColors = (programs.flatMap { it.outputs } + programs.flatMap { it.samplers }.mapNotNull(::colorId) +
            semantics.flips.values.flatMap(Map<Int, Boolean>::keys)).toSet()
        enforceMemoryBudget(semantics.colors.map { it.format })
        ShadowRenderer.prepare()
        colorFormats = semantics.colors.map { gpuFormat(it.format) }
        if (dedicatedColor0()) {
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
                "pack/depth_scale.f" -> DEPTH_SCALE_FSH.replace(
                    "__DEPTH__", if (reverseDepth) "1.0 - texture(InSampler, texCoord).r" else "texture(InSampler, texCoord).r",
                )
                else -> null
            }
        }

        if (needsNormals && (normals == null || builtForW != w || builtForH != h)) {
            normals?.close()
            normals = compile(device, source, id("pack/post.v"), id("pack/normals.f"),
                BindGroupLayout.builder().withUniform("depthtex0", UniformType.COMBINED_IMAGE_SAMPLER).build())
        }
        if (neededDepths.isNotEmpty() && (renderScale < 1f || reverseDepth) && depthScale == null) {
            val pipeline = com.mojang.renderpearl.api.pipeline.RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pack/depth_scale"))
                .withVertexShader(id("pack/post.v"))
                .withFragmentShader(id("pack/depth_scale.f"))
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, true))
                .build()
            depthScale = device.compilePipeline(pipeline, source) ?: error("depth scale pipeline compilation failed")
        }
        if (screenPrograms.isEmpty() && earlyPrograms.isEmpty()) {
            val compiled = programs.map { program ->
            val samplers = program.samplers.distinct()
            val staticSamplers = samplers.mapNotNull { name ->
                staticSampler(device, packRoot.resolve("shaders"), semantics, program.name, name)?.let { name to it }
            }.toMap()
            require(samplers.all { colorId(it) != null || DEPTH.matches(it) || it == "normalsTex" ||
                ShadowRenderer.view(it) != null || it in staticSamplers }) {
                "${program.name}: unsupported samplers ${samplers.filterNot { colorId(it) != null || DEPTH.matches(it) ||
                    it == "normalsTex" || ShadowRenderer.view(it) != null || it in staticSamplers }}"
            }
            val layout = BindGroupLayout.builder().also { builder ->
                samplers.forEach { builder.withUniform(it, UniformType.COMBINED_IMAGE_SAMPLER) }
                if (program.uniforms.isNotEmpty()) builder.withUniform("VertexPackUniforms", UniformType.UNIFORM_BUFFER)
            }.build()
            val vs = id("pack/${program.name}.v")
            val fs = id("pack/${program.name}.f")
            val programSource = ShaderSource { _, type -> when (type) {
                com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX -> LegacyTranslator.vertex(program)
                com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT ->
                    LegacyTranslator.fragment(program, program.outputs.map { semantics.colors[it].format })
                else -> null
            } }
            ScreenProgram(program.name, compile(device, programSource, vs, fs, layout,
                program.outputs.map(colorFormats::get)), samplers, program.outputs,
                semantics.flips[program.name].orEmpty(), staticSamplers, program.uniforms)
            }
            earlyPrograms = compiled.filter { it.name == "setup" || it.name == "begin" }
            screenPrograms = compiled - earlyPrograms.toSet()
        }
        if (blit == null) blit = compile(device, source, id("pack/post.v"), id("pack/blit.f"), BindGroupLayouts.IN_SAMPLER)
        if (programs.any { it.uniforms.isNotEmpty() } && uniformBuffer == null) {
            uniformBuffer = device.createBuffer(
                { "vertex-pack-uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
                uniformHeap.layout.segmentBytes.toLong() * FRAME_SLOTS,
            )
            dev.vertex.Vertex.log.info("[Vertex] uniform heap armed: {} used members, {} bytes x{} slots",
                programs.flatMap { it.uniforms }.distinct().size, uniformHeap.layout.segmentBytes, FRAME_SLOTS)
        }
        if (dedicatedColor0() && sceneToColor0 == null) sceneToColor0 = compile(
            device, source, id("pack/post.v"), id("pack/blit.f"), BindGroupLayouts.IN_SAMPLER, listOf(colorFormats[0]),
        )
        if (timingPool == null) runCatching {
            timingPool = device.createTimestampQueryPool(FRAME_SLOTS * 2)
            timestampPeriod = device.deviceInfo.timestampPeriod()
        }.onFailure { dev.vertex.Vertex.log.warn("[Vertex] GPU timestamps unavailable", it) }

        builtForW = w; builtForH = h
    }

    private fun samplerView(name: String, banks: RenderTargetBanks, scene: GpuTextureView): GpuTextureView = when (name) {
        "depthtex0" -> depthViews[0]!!
        "depthtex1" -> depthViews[1]!!
        "depthtex2" -> depthViews[2]!!
        "normalsTex" -> normalView!!
        "shadowtex0", "shadowtex1", "shadowcolor0", "shadowcolor1" -> ShadowRenderer.view(name)!!
        else -> colorId(name)?.let { colorView(it, banks[it], scene) } ?: error("unsupported sampler '$name'")
    }

    private fun colorView(id: Int, bank: Int, scene: GpuTextureView): GpuTextureView = when {
        id == 0 && dedicatedColor0() -> extraViews.getValue(0)[bank]
        id == 0 && bank == 0 -> scene
        id == 0 -> tempView!!
        else -> extraViews.getValue(id)[bank]
    }

    private fun createExtraColors(device: com.mojang.renderpearl.api.device.GpuDevice) {
        activeColors.filter { it != 0 || dedicatedColor0() }.filterNot(extraTextures::containsKey).forEach { id ->
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
    private fun dedicatedColor0() = customColor0() || renderScale < 1f

    private fun collectGpuTiming(slot: Int) {
        val pool = timingPool ?: return
        if (!timingArmed[slot]) return
        val start = pool.getValue(slot * 2)
        val end = pool.getValue(slot * 2 + 1)
        if (start.isEmpty || end.isEmpty || end.asLong < start.asLong) return
        gpuTimes.record(((end.asLong - start.asLong) * timestampPeriod / 1_000f).toLong())
        timingArmed[slot] = false
        val interval = System.getProperty("vertex.perfLogFrames")?.toIntOrNull()?.coerceAtLeast(1) ?: 600
        if (++timingsSinceReport >= interval) {
            timingsSinceReport = 0
            val p = gpuTimes.snapshot()
            dev.vertex.Vertex.log.info("[Vertex] pack GPU time: p50={} us p99={} us (n={})", p.p50Micros, p.p99Micros, p.samples)
            checkPerformance(p)
        }
    }

    private fun checkPerformance(current: dev.vertex.runtime.Percentiles) {
        val configured = System.getProperty("vertex.perfBaseline")?.takeIf(String::isNotBlank) ?: return
        val raw = Path.of(configured)
        val path = if (raw.isAbsolute) raw else Minecraft.getInstance().gameDirectory.toPath().resolve(raw)
        if (System.getProperty("vertex.perfUpdateBaseline") == "true") {
            if (!perfBaselineWritten) {
                path.parent?.let(Files::createDirectories)
                Files.writeString(path, PerformanceBaseline(current.p50Micros, current.p99Micros).encode())
                perfBaselineWritten = true
                dev.vertex.Vertex.log.info("[Vertex] performance baseline updated: {}", path)
            }
            return
        }
        val baseline = PerformanceBaseline.decode(Files.readString(path))
            ?: error("invalid performance baseline: $path")
        PerformanceGate.compare(
            baseline,
            current,
            System.getProperty("vertex.perfThresholdPercent")?.toDoubleOrNull() ?: 3.0,
        )?.let { regression ->
            val message = "GPU performance regression: p50=${"%.2f".format(regression.p50Percent)}%, " +
                "p99=${"%.2f".format(regression.p99Percent)}%"
            if (System.getProperty("vertex.perfGate") == "true") error(message)
            dev.vertex.Vertex.log.warn("[Vertex] {}", message)
        }
    }

    private fun disable(stage: String, failure: Throwable) {
        failed = true
        RuntimeDiagnostics.disable(ProgramFamily.SCREEN_CHAIN, stage, failure)
    }

    private fun updateUniforms(encoder: CommandEncoder) {
        if (uniformBuffer == null) return
        uniformSlot = frameCounter % FRAME_SLOTS
        val now = System.nanoTime()
        val frameTime = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 1f)
        lastFrameNanos = now
        frameTimeCounter = (frameTimeCounter + frameTime) % 3600f
        val mc = Minecraft.getInstance()
        // CameraRenderState is the interpolated pose used by this render pass;
        // the mutable Camera object can still contain the previous tick while
        // movement is being integrated.
        val camera = frameCameraPosition ?: mc.gameRenderer.mainCamera().position()
        val clock = mc.level?.defaultClockTime ?: 0L
        val partialTick = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        val day = Math.floorMod(clock, 24000L).toInt()
        val probe = mc.gameRenderer.mainCamera().attributeProbe()
        // EnvironmentAttributes.SUN_ANGLE is an absolute degree rotation (0°
        // is the sun overhead). Legacy shader packs expose the orbit as a
        // normalized turn, with 0.25 at noon and 0.75 at midnight.
        val angleDegrees = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick)
        val moonAngleDegrees = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTick)
        val sunTurn = irisSunTurn(angleDegrees)
        val moonTurn = irisSunTurn(moonAngleDegrees)
        val uploadedProjection = ((mc.gameRenderer as GameRendererProjectionAccessor).`vertex$getLevelProjectionMatrixBuffer`()
            as ProjectionMatrixBufferAccessor).`vertex$getLastUploadedProjection`()
        val projection = frameProjection ?: uploadedProjection?.getMatrix(Matrix4f())
        uniformHeap.putFloat(uniformSlot, "near", uploadedProjection?.zNear() ?: 0.05f)
        val far = frameFar.takeIf { it > 0f } ?: uploadedProjection?.zFar()
            ?: mc.options.effectiveRenderDistance * 16f
        uniformHeap.putFloat(uniformSlot, "far", far)
        uniformHeap.putFloat(uniformSlot, "viewWidth", w.toFloat())
        uniformHeap.putFloat(uniformSlot, "viewHeight", h.toFloat())
        uniformHeap.putFloat(uniformSlot, "aspectRatio", w.toFloat() / h)
        uniformHeap.putFloat(uniformSlot, "frameTime", frameTime)
        uniformHeap.putFloat(uniformSlot, "frameTimeCounter", frameTimeCounter)
        uniformHeap.putFloat(uniformSlot, "eyeAltitude", camera.y.toFloat())
        uniformHeap.putFloat(uniformSlot, "sunAngle", sunTurn)
        uniformHeap.putFloat(uniformSlot, "shadowAngle", if (sunTurn <= 0.5f) sunTurn else moonTurn)
        uniformHeap.putFloat(uniformSlot, "timeAngle", day / 24000f)
        uniformHeap.putFloat(uniformSlot, "timeBrightness", kotlin.math.sin(day / 24000f * (Math.PI * 2.0).toFloat()).coerceAtLeast(0f))
        val shadowFadeOut1 = ((day - 12330) / 230f).coerceIn(0f, 1f)
        val shadowFadeIn1 = ((day - 13010) / 220f).coerceIn(0f, 1f)
        val shadowFadeOut2 = ((day - 22770) / 220f).coerceIn(0f, 1f)
        val shadowFadeIn2 = ((day - 23440) / 230f).coerceIn(0f, 1f)
        uniformHeap.putFloat(uniformSlot, "shadowFade", (1f - (shadowFadeOut1 - shadowFadeIn1 + shadowFadeOut2 - shadowFadeIn2)).coerceIn(0f, 1f))
        uniformHeap.putFloat(uniformSlot, "screenBrightness", mc.options.gamma().get().toFloat())
        val rain = mc.level?.getRainLevel(partialTick) ?: 0f
        uniformHeap.putFloat(uniformSlot, "rainStrength", rain)
        uniformHeap.putFloat(uniformSlot, "wetness", rain)
        uniformHeap.putFloat(uniformSlot, "rainfall", rain)
        uniformHeap.putFloat(uniformSlot, "thunderStrength", mc.level?.getThunderLevel(partialTick) ?: 0f)
        uniformHeap.putFloat(uniformSlot, "fogStart", probe.getValue(EnvironmentAttributes.FOG_START_DISTANCE, partialTick))
        uniformHeap.putFloat(uniformSlot, "fogEnd", probe.getValue(EnvironmentAttributes.FOG_END_DISTANCE, partialTick))
        uniformHeap.putFloat(uniformSlot, "cloudHeight", probe.getValue(EnvironmentAttributes.CLOUD_HEIGHT, partialTick))
        uniformHeap.putInt(uniformSlot, "frameCounter", frameCounter)
        uniformHeap.putFloat(uniformSlot, "framemod2", (frameCounter and 1).toFloat())
        uniformHeap.putFloat(uniformSlot, "framemod8", (frameCounter and 7).toFloat())
        uniformHeap.putInt(uniformSlot, "worldTime", day)
        uniformHeap.putInt(uniformSlot, "worldDay", (clock / 24000L).toInt())
        uniformHeap.putInt(uniformSlot, "moonPhase", Math.floorMod(clock / 24000L, 8L).toInt())
        val player = mc.player
        uniformHeap.putInt(uniformSlot, "isEyeInWater", if (player?.isUnderWater == true) 1 else 0)
        uniformHeap.putInt(uniformSlot, "isRightHanded", if (mc.options.mainHand().get().name == "RIGHT") 1 else 0)
        uniformHeap.putInt(uniformSlot, "is_sneaking", if (player?.isCrouching == true) 1 else 0)
        uniformHeap.putInt(uniformSlot, "is_sprinting", if (player?.isSprinting == true) 1 else 0)
        uniformHeap.putInt(uniformSlot, "is_burning", if (player?.isOnFire == true) 1 else 0)
        uniformHeap.putInt(uniformSlot, "is_on_ground", if (player?.onGround() == true) 1 else 0)
        uniformHeap.putInt(uniformSlot, "is_invisible", if (player?.isInvisible == true) 1 else 0)
        uniformHeap.putInt(uniformSlot, "firstPersonCamera", if (mc.options.cameraType.isFirstPerson) 1 else 0)
        uniformHeap.putInt(uniformSlot, "isSpectator", if (player?.isSpectator == true) 1 else 0)
        uniformHeap.putInt(uniformSlot, "blockEntityId", -1)
        uniformHeap.putInt(uniformSlot, "currentRenderedItemId", -1)
        uniformHeap.putVec3(uniformSlot, "cameraPosition", camera.x.toFloat(), camera.y.toFloat(), camera.z.toFloat())
        if (!previousFrameInitialized) {
            previousCamera[0] = camera.x.toFloat(); previousCamera[1] = camera.y.toFloat(); previousCamera[2] = camera.z.toFloat()
        }
        uniformHeap.putVec3(uniformSlot, "previousCameraPosition", previousCamera[0], previousCamera[1], previousCamera[2])
        val previousX = previousCamera[0]; val previousY = previousCamera[1]; val previousZ = previousCamera[2]
        previousCamera[0] = camera.x.toFloat(); previousCamera[1] = camera.y.toFloat(); previousCamera[2] = camera.z.toFloat()
        val blockX = kotlin.math.floor(camera.x).toInt(); val blockY = kotlin.math.floor(camera.y).toInt(); val blockZ = kotlin.math.floor(camera.z).toInt()
        uniformHeap.putIVec3(uniformSlot, "cameraPositionInt", blockX, blockY, blockZ)
        uniformHeap.putVec3(uniformSlot, "cameraPositionFract", camera.x.toFloat() - blockX, camera.y.toFloat() - blockY, camera.z.toFloat() - blockZ)
        uniformHeap.putVec3(uniformSlot, "relativeEyePosition", camera.x.toFloat() - blockX, camera.y.toFloat() - blockY, camera.z.toFloat() - blockZ)
        val oldX = kotlin.math.floor(previousX).toInt(); val oldY = kotlin.math.floor(previousY).toInt(); val oldZ = kotlin.math.floor(previousZ).toInt()
        uniformHeap.putIVec3(uniformSlot, "previousCameraPositionInt", oldX, oldY, oldZ)
        uniformHeap.putVec3(uniformSlot, "previousCameraPositionFract", previousX - oldX, previousY - oldY, previousZ - oldZ)
        val sky = probe.getValue(EnvironmentAttributes.SKY_COLOR, partialTick)
        val fog = probe.getValue(EnvironmentAttributes.FOG_COLOR, partialTick)
        uniformHeap.putVec3(uniformSlot, "skyColor", ARGB.redFloat(sky), ARGB.greenFloat(sky), ARGB.blueFloat(sky))
        uniformHeap.putVec3(uniformSlot, "fogColor", ARGB.redFloat(fog), ARGB.greenFloat(fog), ARGB.blueFloat(fog))
        val wallSecond = System.currentTimeMillis() / 1_000L
        if (wallSecond != dateSecond) {
            val date = LocalDateTime.now()
            dateScratch[0] = date.year; dateScratch[1] = date.monthValue; dateScratch[2] = date.dayOfMonth
            dateScratch[3] = date.hour; dateScratch[4] = date.minute; dateScratch[5] = date.second
            dateScratch[6] = date.dayOfYear; dateScratch[7] = if (date.toLocalDate().isLeapYear) 366 else 365
            dateSecond = wallSecond
        }
        uniformHeap.putIVec3(uniformSlot, "currentDate", dateScratch[0], dateScratch[1], dateScratch[2])
        uniformHeap.putIVec3(uniformSlot, "currentTime", dateScratch[3], dateScratch[4], dateScratch[5])
        uniformHeap.putIVec2(uniformSlot, "currentYearTime", dateScratch[6], dateScratch[7])
        val celestialView = frameViewRotation ?: mc.gameRenderer.mainCamera().getViewRotationMatrix(Matrix4f())
        val sunPosition = celestialPosition(celestialView, angleDegrees)
        val moonPosition = celestialPosition(celestialView, moonAngleDegrees)
        val shadowPosition = if (sunTurn <= 0.5f) sunPosition else moonPosition
        val upPosition = celestialPosition(celestialView, 0f)
        uniformHeap.putVec3(uniformSlot, "sunPosition", sunPosition.x, sunPosition.y, sunPosition.z)
        uniformHeap.putVec3(uniformSlot, "shadowLightPosition", shadowPosition.x, shadowPosition.y, shadowPosition.z)
        uniformHeap.putVec3(uniformSlot, "moonPosition", moonPosition.x, moonPosition.y, moonPosition.z)
        uniformHeap.putVec3(uniformSlot, "upPosition", upPosition.x, upPosition.y, upPosition.z)
        uniformHeap.putIVec2(uniformSlot, "eyeBrightness", 240, 240)
        uniformHeap.putIVec2(uniformSlot, "eyeBrightnessSmooth", 240, 240)
        val modelView = celestialView
        if (!previousFrameInitialized) previousModelView.set(modelView)
        putMatrix("gbufferModelView", modelView)
        putMatrix("gbufferModelViewInverse", inverseMatrix.set(modelView).invert())
        putMatrix("gbufferPreviousModelView", previousModelView)
        previousModelView.set(modelView)
        projection?.let(projectionMatrix::set) ?: projectionMatrix.identity()
        if (projection != null && RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()) {
            // Legacy packs reconstruct view space from OpenGL NDC (-1..1), while
            // RenderPearl/Vulkan stores depth in 0..1. Convert the clip-space Z
            // row once so both the inverse projection and sampled depth agree.
            vulkanToLegacyClip.mul(projectionMatrix, projectionMatrix)
        }
        if (!previousFrameInitialized) previousProjection.set(projectionMatrix)
        putMatrix("gbufferProjection", projectionMatrix)
        putMatrix("gbufferProjectionInverse", inverseMatrix.set(projectionMatrix).invert())
        putMatrix("gbufferPreviousProjection", previousProjection)
        previousProjection.set(projectionMatrix)
        SHADOW_MATRICES.forEach { putMatrix(it, ShadowRenderer.uniformMatrix(it) ?: IDENTITY) }
        NORMAL_MATRICES.forEach { uniformHeap.putMat3(uniformSlot, it, normalScratch) }
        encoder.writeToBuffer(
            uniformBuffer!!.slice(uniformHeap.segmentOffset(uniformSlot).toLong(), uniformHeap.layout.segmentBytes.toLong()),
            uniformHeap.view(uniformSlot),
        )
        previousFrameInitialized = true
        frameCounter++
    }

    private fun irisSunTurn(degrees: Float): Float {
        val celestial = (degrees / 360f).let { (it % 1f + 1f) % 1f }
        return (if (celestial < 0.75f) celestial + 0.25f else celestial - 0.75f)
            .let { (it % 1f + 1f) % 1f }
    }

    /** Match vanilla's celestial modelview: rotate around X, then the -90° yaw. */
    private fun celestialPosition(view: Matrix4f, degrees: Float): Vector3f =
        Vector3f(0f, 100f, 0f)
            .rotateX(Math.toRadians(degrees.toDouble()).toFloat())
            .rotateY((-Math.PI * 0.5).toFloat())
            .let(view::transformDirection)

    private fun putMatrix(name: String, matrix: Matrix4f) =
        uniformHeap.putFloats(uniformSlot, name, matrix.get(matrixScratch))

    private fun staticSampler(
        device: com.mojang.renderpearl.api.device.GpuDevice,
        shaders: Path,
        semantics: dev.vertex.frontend.PackSemantics,
        program: String,
        name: String,
    ): TextureBinding? {
        val stage = when {
            program == "setup" -> "setup"; program == "begin" -> "begin"
            program.startsWith("prepare") -> "prepare"; program.startsWith("deferred") -> "deferred"
            program == "final" -> "final"; else -> "composite"
        }
        val path = if (name == "noisetex") semantics.noisePath else semantics.customTextures[stage]?.get(name)
        if (path == null && name != "noisetex") return null
        val key = path?.let { "file:$it" } ?: "noise:${semantics.noiseResolution}"
        val binding = staticBindings.getOrPut(key) {
            val image = path?.let { readImage(shaders, it) } ?: generateNoise(semantics.noiseResolution)
            val filtering = path?.let { readFiltering(shaders, it) } ?: TextureFilter(blur = false, clamp = false)
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
                val sampler = textureSamplers.getOrPut(filtering) {
                    device.createSampler(
                        if (filtering.clamp) AddressMode.CLAMP_TO_EDGE else AddressMode.REPEAT,
                        if (filtering.clamp) AddressMode.CLAMP_TO_EDGE else AddressMode.REPEAT,
                        if (filtering.blur) FilterMode.LINEAR else FilterMode.NEAREST,
                        if (filtering.blur) FilterMode.LINEAR else FilterMode.NEAREST,
                        1, java.util.OptionalDouble.empty(),
                    )
                }
                dev.vertex.Vertex.log.info("[Vertex] static texture '{}' loaded ({}x{}, blur={}, clamp={})",
                    name, it.width, it.height, filtering.blur, filtering.clamp)
                TextureBinding(device.createTextureView(texture), sampler)
            }
        }
        staticByName[name] = binding
        return binding
    }

    private fun readImage(shaders: Path, value: String): NativeImage {
        val path = shaders.resolve(value.removePrefix("/")).normalize()
        require(path.startsWith(shaders) && Files.isRegularFile(path)) { "custom texture is outside the pack or missing: $value" }
        return Files.newInputStream(path).use(NativeImage::read)
    }

    private fun readFiltering(shaders: Path, value: String): TextureFilter {
        val image = shaders.resolve(value.removePrefix("/")).normalize()
        val metadata = image.resolveSibling(image.fileName.toString() + ".mcmeta")
        if (!Files.isRegularFile(metadata)) return TextureFilter(blur = false, clamp = false)
        val json = Files.readString(metadata)
        return TextureFilter(booleanProperty("blur").containsMatchIn(json), booleanProperty("clamp").containsMatchIn(json))
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
                { "vertex-depthtex$id" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_RENDER_ATTACHMENT,
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
            (if (needsNormals) listOf(ImageAllocation("normalsTex", w, h, 4, ImageClass.NON_CRITICAL)) else emptyList()) +
            ShadowRenderer.allocations()
        val budgetMiB = System.getProperty("vertex.memoryBudgetMiB")?.toLongOrNull() ?: 512L
        require(budgetMiB in 64..16384) { "vertex.memoryBudgetMiB must be between 64 and 16384" }
        packBudgetBytes = budgetMiB * MIB
        val plan = MemoryBudgetGovernor.plan(allocations, packBudgetBytes, w, h)
        ShadowRenderer.configure(plan.allocations)
        targetBytes = plan.bytes
        plan.degradations.forEach { dev.vertex.Vertex.log.warn("[Vertex] memory budget: {}", it) }
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
        val staticSamplers: Map<String, TextureBinding>,
        val uniforms: Set<String>,
    )

    private data class TextureBinding(val view: GpuTextureView, val sampler: GpuSampler)
    private data class TextureFilter(val blur: Boolean, val clamp: Boolean)

    private fun gpuFormat(format: ColorFormat): GpuFormat = when (format) {
        ColorFormat.R8 -> GpuFormat.R8_UNORM; ColorFormat.RG8 -> GpuFormat.RG8_UNORM
        // Vulkan has no portable three-component color-attachment format. Keep
        // the pack's RGB semantics while using the widely supported RGBA target.
        ColorFormat.RGB8 -> GpuFormat.RGBA8_UNORM; ColorFormat.RGBA8 -> GpuFormat.RGBA8_UNORM
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
    private fun booleanProperty(name: String) = Regex("""[\"']$name[\"']\s*:\s*true\b""", RegexOption.IGNORE_CASE)
    private val IDENTITY = Matrix4f()
    private val SHADOW_MATRICES = arrayOf("shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse")
    private val NORMAL_MATRICES = arrayOf("gbufferNormal", "gbufferNormalInverse", "gbufferPreviousNormal")
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
    private const val DEPTH_SCALE_FSH = """#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
void main() { gl_FragDepth = __DEPTH__; }
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
