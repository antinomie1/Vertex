package dev.vertex.runtime

import kotlin.math.sqrt

enum class ImageClass { CRITICAL, NON_CRITICAL, SHADOW }

data class ImageAllocation(
    val id: String,
    val width: Int,
    val height: Int,
    val bytesPerPixel: Int,
    val imageClass: ImageClass,
) {
    init { require(id.isNotBlank() && width > 0 && height > 0 && bytesPerPixel > 0) }
    val bytes: Long get() = width.toLong() * height * bytesPerPixel
}

data class MemoryPlan(
    val allocations: List<ImageAllocation>,
    val bytes: Long,
    val degradations: List<String>,
)

/** Applies the visible, deterministic degradation order from DESIGN.md section 12.3. */
object MemoryBudgetGovernor {
    fun plan(
        requested: List<ImageAllocation>,
        budgetBytes: Long,
        screenWidth: Int,
        screenHeight: Int,
    ): MemoryPlan {
        require(budgetBytes > 0 && screenWidth > 0 && screenHeight > 0)
        val allocations = requested.toMutableList()
        val changes = mutableListOf<String>()
        fun total() = allocations.sumOf(ImageAllocation::bytes)
        if (total() <= budgetBytes) return MemoryPlan(allocations, total(), changes)

        allocations.indices.filter { allocations[it].imageClass == ImageClass.SHADOW }.forEach { index ->
            val old = allocations[index]
            allocations[index] = old.copy(width = maxOf(1, old.width / 2), height = maxOf(1, old.height / 2))
            changes += "${old.id}: shadow resolution halved"
        }
        if (total() <= budgetBytes) return MemoryPlan(allocations, total(), changes)

        val pixelCap = screenWidth.toLong() * screenHeight * 2
        allocations.indices.filter { allocations[it].imageClass == ImageClass.NON_CRITICAL }.forEach { index ->
            val old = allocations[index]
            val pixels = old.width.toLong() * old.height
            if (pixels > pixelCap) {
                val scale = sqrt(pixelCap.toDouble() / pixels)
                allocations[index] = old.copy(
                    width = maxOf(1, (old.width * scale).toInt()),
                    height = maxOf(1, (old.height * scale).toInt()),
                )
                changes += "${old.id}: capped to 2x screen pixels"
            }
        }
        val bytes = total()
        require(bytes <= budgetBytes) {
            "pack requires ${bytes / MIB} MiB after safe degradation; budget is ${budgetBytes / MIB} MiB"
        }
        return MemoryPlan(allocations, bytes, changes)
    }

    private const val MIB = 1024L * 1024L
}
