package dev.vertex.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Keeps the nullable screen hand-off compatible with the snapshot's Java annotations. */
public final class VertexUiBridge {
    private VertexUiBridge() {}

    public static void show(Screen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
    }
}
