package dev.vertex

import dev.vertex.render.VertexRenderer
import dev.vertex.render.PackChain
import dev.vertex.render.ShadowRenderer
import dev.vertex.render.TerrainMesh
import dev.vertex.render.DynamicRenderer
import dev.vertex.frontend.PackRuntime
import dev.vertex.core.SharedVulkanContext
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
        closeRenderers()
        PackRuntime.apply(minecraft.gameDirectory.toPath(), settings)
        PackRuntime.activateDimension(minecraft.level?.dimension()?.identifier()?.toString())
        SharedVulkanContext.resetPackHealth()
        if (settings.enabled) {
            PackChain.prepare()
            DynamicRenderer.prepare()
        }
        rebuildGeometry(minecraft)
    }

    @JvmStatic
    fun switchDimension(identifier: String) {
        if (!PackRuntime.activateDimension(identifier) || !PackRuntime.isEnabled()) return
        val minecraft = Minecraft.getInstance()
        log.info("[Vertex] shader dimension changed: {}; rebuilding pack pipelines", identifier)
        closeRenderers()
        SharedVulkanContext.resetPackHealth()
        PackChain.prepare()
        DynamicRenderer.prepare()
        rebuildGeometry(minecraft)
    }

    private fun closeRenderers() {
        DynamicRenderer.close()
        PackChain.close()
        ShadowRenderer.close()
        TerrainMesh.close()
    }

    private fun rebuildGeometry(minecraft: Minecraft) {
        minecraft.level?.let { level ->
            dev.vertex.render.LevelRendererBridge.rebuildGeometry(
                minecraft.levelRenderer,
                level,
                minecraft.options,
                minecraft.gameRenderer.mainCamera(),
                minecraft.getBlockColors(),
            )
        }
    }
}
