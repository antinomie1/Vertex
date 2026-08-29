package dev.vertex.core

import com.mojang.blaze3d.systems.RenderSystem
import dev.vertex.Vertex
import dev.vertex.runtime.ProgramFamily
import net.minecraft.client.Minecraft
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.time.Instant

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
                append(trace)
            }
            val logs = Minecraft.getInstance().gameDirectory.toPath().resolve("logs")
            Files.createDirectories(logs)
            Files.writeString(logs.resolve("vertex-fault-last.txt"), report)
        }.onFailure { Vertex.log.warn("[Vertex] could not write local fault report", it) }
    }
}
