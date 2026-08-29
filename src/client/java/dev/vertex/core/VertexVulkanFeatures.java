package dev.vertex.core;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import java.util.Set;

/** Optional as a group: unsupported hardware must still reach the Tier 0 renderer. */
public final class VertexVulkanFeatures {
    public static final FeatureSet TIER_2 = new FeatureSet(
        "Vertex Tier 2",
        Set.of(),
        Set.of(
            new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "descriptorIndexing"),
            new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "descriptorBindingSampledImageUpdateAfterBind"),
            new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "descriptorBindingPartiallyBound"),
            new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "runtimeDescriptorArray"),
            new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "drawIndirectCount")
        )
    );

    public static volatile boolean injectionObserved;

    private VertexVulkanFeatures() {}
}
