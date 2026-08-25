package dev.vertex

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

object Vertex : ClientModInitializer {
    const val MOD_ID: String = "vertex"
    val log = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        log.info(
            "[Vertex] initialized. Backend detection pending G0; " +
                "expected: co-resident VkDevice (see docs/DESIGN.md §1)"
        )
    }
}
