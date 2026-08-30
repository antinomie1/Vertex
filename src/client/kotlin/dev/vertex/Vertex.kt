package dev.vertex

import dev.vertex.render.VertexRenderer
import dev.vertex.render.PackChain
import dev.vertex.render.ShadowRenderer
import dev.vertex.render.TerrainMesh
import dev.vertex.render.DynamicRenderer
import dev.vertex.frontend.PackRuntime
import dev.vertex.ui.VertexUi
import net.minecraft.client.Minecraft
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import org.slf4j.LoggerFactory

object Vertex : ClientModInitializer {
    const val MOD_ID: String = "vertex"
    val log = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        log.info("[Vertex] init; registering shared-device frame seam")
        VertexRenderer.register()
        VertexUi.register()
        ClientLifecycleEvents.CLIENT_STARTED.register {
            PackRuntime.initialize(Minecraft.getInstance().gameDirectory.toPath())
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

    @JvmStatic
    fun reloadShaders(settings: PackRuntime.Settings) {
        val minecraft = Minecraft.getInstance()
        DynamicRenderer.close()
        PackChain.close()
        ShadowRenderer.close()
        TerrainMesh.close()
        PackRuntime.apply(minecraft.gameDirectory.toPath(), settings)
        if (settings.enabled) {
            PackChain.prepare()
            DynamicRenderer.prepare()
        }
        minecraft.level?.let { level ->
            minecraft.levelRenderer.invalidateCompiledGeometry(
                level,
                minecraft.options,
                minecraft.gameRenderer.mainCamera(),
                minecraft.getBlockColors(),
            )
        }
    }
}
