package dev.vertex.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.vertex.core.VertexVulkanFeatures;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanFeatureSets.class)
public abstract class VulkanFeatureSetsMixin {
    @Inject(method = "optionalFeatureSets", at = @At("RETURN"), require = 0)
    private static void vertex$offerTier2(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        cir.getReturnValue().add(VertexVulkanFeatures.TIER_2);
        cir.getReturnValue().add(VertexVulkanFeatures.DEVICE_FAULT);
        VertexVulkanFeatures.injectionObserved = true;
    }
}
