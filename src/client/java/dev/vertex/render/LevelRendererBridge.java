package dev.vertex.render;

import dev.vertex.mixin.LevelRendererDispatcherAccessor;
import net.minecraft.client.Options;
import net.minecraft.client.Camera;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

/** Recreates chunk upload buffers when a pack changes vertex requirements. */
public final class LevelRendererBridge {
    private LevelRendererBridge() {}

    public static void rebuildGeometry(LevelRenderer renderer, ClientLevel level, Options options, Camera camera, BlockColors colors) {
        renderer.invalidateCompiledGeometry(level, options, camera, colors);
        LevelRendererDispatcherAccessor access = (LevelRendererDispatcherAccessor) (Object) renderer;
        if (access.vertex$getSectionRenderDispatcher() != null) {
            access.vertex$getSectionRenderDispatcher().dispose();
            access.vertex$setSectionRenderDispatcher(null);
        }
        renderer.invalidateCompiledGeometry(level, options, camera, colors);
    }
}
