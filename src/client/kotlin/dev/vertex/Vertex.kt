package dev.vertex

import dev.vertex.render.VertexRenderer
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

object Vertex : ClientModInitializer {
    const val MOD_ID: String = "vertex"
    val log = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        log.info("[Vertex] init; registering G0 frame seam (docs/DESIGN.md §10)")
        VertexRenderer.register()
    }
}
