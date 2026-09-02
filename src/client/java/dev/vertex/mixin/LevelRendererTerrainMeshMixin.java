package dev.vertex.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vertex.render.PackChain;
import dev.vertex.render.TerrainMesh;
import dev.vertex.render.ShadowRenderer;
import dev.vertex.render.TerrainCommandCache;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Supplies the custom pipeline (and its bound vertex format/stride) to draw group extraction. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererTerrainMeshMixin {
    @Shadow
    private boolean usingMultiDrawIndirectForTerrain;

    @Invoker("executeClassicTransparency")
    protected abstract void vertex$executeClassicTransparency(
        ChunkSectionsToRender sections,
        FeatureRenderDispatcher.PreparedFrame features,
        RenderPass pass
    );

    /** Prepare pack uniforms before the frame graph's sky pass consumes them. */
    @Inject(method = "render", at = @At("HEAD"))
    private void vertex$beginPackFrame(
        com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator,
        boolean renderBlockOutline,
        net.minecraft.client.renderer.state.level.CameraRenderState camera,
        com.mojang.renderpearl.api.buffers.GpuBufferSlice fog,
        org.joml.Vector4f clearColor,
        boolean renderSky,
        boolean renderClouds,
        CallbackInfo ci
    ) {
        PackChain.setFrameCamera(camera);
    }

    /** Let the pack's deferred pass own volumetric clouds instead of drawing vanilla flat clouds. */
    @Redirect(
        method = "executeClassicTransparency",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/CloudRenderer;render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V")
    )
    private void vertex$renderPackCloudsOnly(
        net.minecraft.client.renderer.CloudRenderer renderer,
        net.minecraft.client.CloudStatus status,
        RenderPass pass
    ) {
        if (!PackChain.usesVolumetricClouds()) renderer.render(status, pass);
    }

    @Redirect(
        method = "executeOit",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/CloudRenderer;renderOit(Lnet/minecraft/client/CloudStatus;Lnet/minecraft/client/renderer/oit/OitStage;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider$Parameters;)V")
    )
    private void vertex$renderPackCloudsOnlyOit(
        net.minecraft.client.renderer.CloudRenderer renderer,
        net.minecraft.client.CloudStatus status,
        net.minecraft.client.renderer.oit.OitStage stage,
        net.minecraft.client.renderer.oit.OitRenderPassProvider.Parameters parameters
    ) {
        if (!PackChain.usesVolumetricClouds()) renderer.renderOit(status, stage, parameters);
    }

    @Inject(method = "lambda$addMainPass$0", at = @At("HEAD"))
    private void vertex$runEarlyPackPrograms(
        com.mojang.renderpearl.api.buffers.GpuBufferSlice fog,
        boolean oit,
        ChunkSectionsToRender sections,
        FeatureRenderDispatcher.PreparedFrame features,
        boolean outlines,
        boolean alwaysOnTop,
        CallbackInfo ci
    ) {
        ShadowRenderer.render(sections);
        // ShadowRenderer may refresh its cached light-space matrices on a
        // movement/angle boundary. Upload pack uniforms afterwards so screen
        // passes sample the shadow map with the same matrices that rendered it.
        PackChain.beginFrame();
    }

    @Redirect(
        method = "executeSolid",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V")
    )
    private void vertex$renderCachedTerrain(
        ChunkSectionsToRender sections,
        net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup group,
        RenderPass pass,
        com.mojang.renderpearl.api.textures.GpuSampler sampler,
        com.mojang.renderpearl.api.textures.GpuTextureView atlas,
        boolean wireframe
    ) {
        TerrainCommandCache.render(sections, group, pass, sampler, atlas, wireframe);
    }

    /** Bind pack samplers for the translucent terrain/water group as well. */
    @Redirect(
        method = "executeClassicTransparency",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V")
    )
    private void vertex$renderPackWater(
        ChunkSectionsToRender sections,
        net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup group,
        RenderPass pass,
        com.mojang.renderpearl.api.textures.GpuSampler sampler,
        com.mojang.renderpearl.api.textures.GpuTextureView atlas,
        boolean wireframe
    ) {
        TerrainCommandCache.render(sections, group, pass, sampler, atlas, wireframe);
    }

    /** Splits the main pass only when depthtex1 needs the pre-translucent snapshot. */
    @Redirect(
        method = "lambda$addMainPass$0",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"
        )
    )
    private void vertex$splitForDepthtex1(
        LevelRenderer instance,
        ChunkSectionsToRender sections,
        FeatureRenderDispatcher.PreparedFrame features,
        RenderPass opaquePass
    ) {
        if (!PackChain.needsDepth(1)) {
            vertex$executeClassicTransparency(sections, features, opaquePass);
            return;
        }
        opaquePass.close();
        PackChain.captureDepth(1);
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try (RenderPass translucent = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "vertex translucent continuation",
            main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty()
        )) {
            RenderSystem.bindDefaultUniforms(translucent);
            vertex$executeClassicTransparency(sections, features, translucent);
        }
    }

    /** OIT already ends the opaque pass before entering its separate transparency passes. */
    @Inject(method = "executeOit", at = @At("HEAD"))
    private void vertex$captureOitDepthtex1(
        ChunkSectionsToRender sections,
        FeatureRenderDispatcher.PreparedFrame features,
        CallbackInfo ci
    ) {
        PackChain.captureDepth(1);
    }

    @Inject(
        method = "render",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;usingMultiDrawIndirectForTerrain:Z",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD,
            shift = At.Shift.AFTER
        )
    )
    private void vertex$overrideDrawMode(CallbackInfo ci) {
        String mode = System.getProperty("vertex.drawMode");
        if ("separate".equalsIgnoreCase(mode)) {
            this.usingMultiDrawIndirectForTerrain = false;
        } else if ("indirect".equalsIgnoreCase(mode)) {
            this.usingMultiDrawIndirectForTerrain = true;
        }
    }

    @Redirect(
        method = "extractSectionDrawGroups",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;pipeline(Z)Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
        )
    )
    private RenderPipeline vertex$drawGroupPipeline(net.minecraft.client.renderer.chunk.ChunkSectionLayer layer, boolean multidraw) {
        RenderPipeline custom = TerrainMesh.pipelineFor(layer, multidraw);
        return custom != null ? custom : layer.pipeline(multidraw);
    }
}
