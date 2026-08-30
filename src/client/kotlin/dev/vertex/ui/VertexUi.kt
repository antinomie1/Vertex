package dev.vertex.ui

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.Screen
import net.minecraft.resources.Identifier
import com.mojang.blaze3d.platform.InputConstants

object VertexUi {
    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("vertex", "key_category"))
    private val shaderMenu = KeyMapping("key.vertex.shader_menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_F7, category)

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client: Minecraft ->
            while (shaderMenu.consumeClick()) VertexUiBridge.show(VertexShadersScreen(client.gui.screen()))
        }
    }

    @JvmStatic
    fun open(parent: Screen?) = VertexUiBridge.show(VertexShadersScreen(parent))
}
