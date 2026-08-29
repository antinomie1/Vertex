package dev.vertex.runtime

/** Startup guard for snapshot/API drift; failures must fall back instead of breaking the client. */
data class RuntimeCompatibilityReport(val compatible: Boolean, val reasons: List<String>) {
    val reason: String get() = reasons.joinToString(", ")
}

object RuntimeCompatibility {
    private val REQUIRED_CLASSES = setOf(
        "com.mojang.renderpearl.api.device.GpuDevice",
        "com.mojang.renderpearl.frontend.FrontendGpuDevice",
        "com.mojang.renderpearl.backend.vulkan.VulkanDevice",
    )

    fun check(
        minecraftVersion: String,
        javaFeature: Int,
        classLoader: (String) -> Boolean = { name ->
            runCatching { Class.forName(name, false, RuntimeCompatibility::class.java.classLoader) }.isSuccess
        },
    ): RuntimeCompatibilityReport {
        val reasons = buildList {
            val version = parseMinecraft(minecraftVersion)
            if (version == null) add("unparseable Minecraft version '$minecraftVersion'")
            else if (version < MIN_MINECRAFT) add("Minecraft $minecraftVersion is below 26.2")
            if (javaFeature < 25) add("Java $javaFeature is below 25")
            REQUIRED_CLASSES.filterNot(classLoader).forEach { add("missing runtime class $it") }
        }
        return RuntimeCompatibilityReport(reasons.isEmpty(), reasons)
    }

    private fun parseMinecraft(value: String): Version? = VERSION.find(value)?.let {
        Version(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].ifEmpty { "0" }.toInt())
    }

    private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    }

    private val VERSION = Regex("""^\s*(\d+)\.(\d+)(?:\.(\d+))?""")
    private val MIN_MINECRAFT = Version(26, 2, 0)
}
