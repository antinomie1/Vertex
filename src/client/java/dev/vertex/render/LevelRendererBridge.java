package dev.vertex.render;

import dev.vertex.mixin.LevelRendererDispatcherAccessor;
import net.minecraft.core.SectionPos;
import net.minecraft.client.Options;
import net.minecraft.client.Camera;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;

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
        markVisibleSectionsDirty(renderer, level);
    }

    /**
     * A fresh ViewArea contains only UNCOMPILED meshes.  The vanilla extractor
     * normally marks them while processing block updates; a shader reload has
     * no block update to piggyback on, so enqueue the same dirty range here.
     */
    private static void markVisibleSectionsDirty(LevelRenderer renderer, ClientLevel level) {
        ViewArea viewArea = renderer.viewArea();
        if (viewArea == null) return;
        SectionPos center = viewArea.getCameraSectionPos();
        int radius = viewArea.getViewDistance() + 1;
        level.setSectionRangeDirty(
            center.x() - radius,
            viewArea.minSectionY(),
            center.z() - radius,
            center.x() + radius,
            viewArea.maxSectionY(),
            center.z() + radius
        );
    }
}
