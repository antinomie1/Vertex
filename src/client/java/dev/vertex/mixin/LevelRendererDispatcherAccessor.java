package dev.vertex.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererDispatcherAccessor {
    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher vertex$getSectionRenderDispatcher();

    @Accessor("sectionRenderDispatcher")
    void vertex$setSectionRenderDispatcher(SectionRenderDispatcher dispatcher);
}
