package dev.vertex.core

import com.mojang.blaze3d.systems.RenderSystem
import dev.vertex.Vertex
import dev.vertex.runtime.ProgramFamily
import net.minecraft.client.Minecraft
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.time.Instant
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.EXTDeviceFault
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import org.lwjgl.vulkan.VkDeviceFaultAddressInfoEXT
import org.lwjgl.vulkan.VkDeviceFaultCountsEXT
import org.lwjgl.vulkan.VkDeviceFaultInfoEXT
import org.lwjgl.vulkan.VkDeviceFaultVendorInfoEXT

/** Captures local-only evidence and opens the affected family's circuit breaker. */
object RuntimeDiagnostics {
    fun disable(family: ProgramFamily, stage: String, failure: Throwable) {
        val context = runCatching { SharedVulkanContext.attach() }.getOrElse {
            Vertex.log.error("[Vertex] failure before device negotiation at $stage", failure)
            return
        }
        if (!context.health.disable(family, "$stage: ${failure.message ?: failure.javaClass.simpleName}")) return
        Vertex.log.error("[Vertex] {} disabled for this session at {}; other families remain active", family, stage, failure)
        runCatching {
            val device = RenderSystem.getDevice()
            val trace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }
            val report = buildString {
                appendLine("time=${Instant.now()}")
                appendLine("family=$family")
                appendLine("stage=$stage")
                appendLine("gpu=${context.gpuName}")
                appendLine("debugging=${device.isDebuggingEnabled}")
                device.lastDebugMessages.takeLast(64).forEach { appendLine("gpu-message=$it") }
                append(deviceFault(context.device, device.getDeviceInfo().underlyingExtensions()))
                append(trace)
            }
            val logs = Minecraft.getInstance().gameDirectory.toPath().resolve("logs")
            Files.createDirectories(logs)
            Files.writeString(logs.resolve("vertex-fault-last.txt"), report)
        }.onFailure { Vertex.log.warn("[Vertex] could not write local fault report", it) }
    }

    private fun deviceFault(device: org.lwjgl.vulkan.VkDevice?, extensions: Set<String>): String {
        if (device == null || "VK_EXT_device_fault" !in extensions) return ""
        return runCatching {
            MemoryStack.stackPush().use { stack ->
                val counts = VkDeviceFaultCountsEXT.calloc(stack).`sType$Default`()
                if (EXTDeviceFault.vkGetDeviceFaultInfoEXT(device, counts, null) != VK_SUCCESS) return@use ""
                val addresses = VkDeviceFaultAddressInfoEXT.calloc(counts.addressInfoCount(), stack)
                val vendors = VkDeviceFaultVendorInfoEXT.calloc(counts.vendorInfoCount(), stack)
                val binary = counts.vendorBinarySize().takeIf { it in 1..MAX_BINARY }?.toInt()?.let(stack::malloc)
                val info = VkDeviceFaultInfoEXT.calloc(stack).`sType$Default`()
                    .pAddressInfos(addresses).pVendorInfos(vendors)
                binary?.let(info::pVendorBinaryData)
                if (EXTDeviceFault.vkGetDeviceFaultInfoEXT(device, counts, info) != VK_SUCCESS) return@use ""
                buildString {
                    appendLine("device-fault-description=${info.descriptionString()}")
                    appendLine("device-fault-addresses=${counts.addressInfoCount()}")
                    appendLine("device-fault-vendors=${counts.vendorInfoCount()}")
                    appendLine("device-fault-binary-bytes=${counts.vendorBinarySize()}")
                }
            }
        }.getOrElse {
            Vertex.log.warn("[Vertex] device fault report unavailable", it)
            ""
        }
    }

    private const val MAX_BINARY = 1L shl 20
}
