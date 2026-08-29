package dev.vertex.core

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.backend.vulkan.VulkanDevice
import com.mojang.renderpearl.frontend.FrontendGpuDevice
import dev.vertex.mixin.FrontendGpuDeviceAccessor
import dev.vertex.mixin.VulkanDeviceAccessor
import dev.vertex.runtime.CompatibilityProbe
import dev.vertex.runtime.DeviceCapabilities
import dev.vertex.runtime.ProgramFamily
import dev.vertex.runtime.RenderTier
import dev.vertex.runtime.TierDecision
import dev.vertex.runtime.TierNegotiator
import net.fabricmc.loader.api.FabricLoader
import org.lwjgl.vulkan.VkDevice

data class SharedVulkanContext(
    val device: VkDevice?,
    val gpuName: String,
    val graphicsFamily: Int,
    val decisions: Map<ProgramFamily, TierDecision>,
) {
    fun tier(family: ProgramFamily): RenderTier = decisions.getValue(family).tier

    companion object {
        @Volatile private var current: SharedVulkanContext? = null

        @JvmStatic
        fun attach(): SharedVulkanContext = current ?: synchronized(this) {
            current ?: probe().also { current = it }
        }

        private fun probe(): SharedVulkanContext {
            val frontend = RenderSystem.getDevice()
            val backend = (frontend as? FrontendGpuDevice)
                ?.let { (it as FrontendGpuDeviceAccessor).`vertex$getBackend`() }
            val vulkan = backend as? VulkanDevice
            val featureNames = vulkan?.let {
                (it as VulkanDeviceAccessor).`vertex$getEnabledFeatures`().features().mapTo(hashSetOf()) { feature -> feature.name() }
            }.orEmpty()
            val info = frontend.getDeviceInfo()
            val loader = FabricLoader.getInstance()
            val sodium = loader.isModLoaded("sodium") || loader.isModLoaded("embeddium")
            val compatibility = CompatibilityProbe(
                sodiumTerrainConflict = sodium,
                translucentSortingConflict = sodium,
                dynamicCaptureAvailable = System.getProperty("vertex.dynamicCapture") != "false",
                externalWorldRendererPresent = loader.isModLoaded("distanthorizons") || loader.isModLoaded("replaymod"),
            )
            val caps = DeviceCapabilities(
                deviceHookAvailable = vulkan != null && VertexVulkanFeatures.injectionObserved,
                descriptorIndexing = "descriptorIndexing" in featureNames,
                updateAfterBind = "descriptorBindingSampledImageUpdateAfterBind" in featureNames,
                timelineSemaphore = "timelineSemaphore" in featureNames,
                multiDrawIndirect = info.features().multiDrawIndirect(),
                multiDrawIndirectCount = "drawIndirectCount" in featureNames,
                dynamicRendering = "dynamicRendering" in featureNames,
                synchronization2 = "synchronization2" in featureNames,
            )
            return SharedVulkanContext(
                device = vulkan?.vkDevice(),
                gpuName = info.name(),
                graphicsFamily = vulkan?.graphicsQueue()?.queueFamilyIndex() ?: -1,
                decisions = TierNegotiator.negotiate(
                    caps,
                    compatibility,
                    implementedTier2 = setOf(ProgramFamily.TERRAIN_OPAQUE, ProgramFamily.SCREEN_CHAIN),
                ),
            )
        }
    }
}
