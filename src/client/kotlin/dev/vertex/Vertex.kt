package dev.vertex

import dev.vertex.render.VertexRenderer
import dev.vertex.render.PackChain
import dev.vertex.frontend.PackRuntime
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import org.slf4j.LoggerFactory

object Vertex : ClientModInitializer {
    const val MOD_ID: String = "vertex"
    val log = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        log.info("[Vertex] init; registering G0 frame seam (docs/DESIGN.md §10)")
        VertexRenderer.register()
        ClientLifecycleEvents.CLIENT_STARTED.register { PackChain.prepare() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { PackRuntime.close() }
    }
}
