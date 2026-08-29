package dev.vertex

import dev.vertex.render.VertexRenderer
import dev.vertex.render.PackChain
import dev.vertex.render.ShadowRenderer
import dev.vertex.render.TerrainMesh
import dev.vertex.render.DynamicRenderer
import dev.vertex.frontend.PackRuntime
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import org.slf4j.LoggerFactory

object Vertex : ClientModInitializer {
    const val MOD_ID: String = "vertex"
    val log = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        log.info("[Vertex] init; registering shared-device frame seam")
        VertexRenderer.register()
        ClientLifecycleEvents.CLIENT_STARTED.register {
            PackChain.prepare()
            DynamicRenderer.prepare()
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            DynamicRenderer.close()
            PackChain.close()
            ShadowRenderer.close()
            TerrainMesh.close()
            PackRuntime.close()
        }
    }
}
