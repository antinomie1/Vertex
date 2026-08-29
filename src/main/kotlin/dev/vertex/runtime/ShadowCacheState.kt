package dev.vertex.runtime

import kotlin.math.floor
import kotlin.math.roundToInt

data class ShadowTile(val x: Int, val z: Int)

/** Stable shadow-map key with section-local invalidation for an amortized static layer. */
class ShadowCacheState(
    private val angleStepRadians: Float = Math.toRadians(0.25).toFloat(),
    private val tileSections: Int = 8,
) {
    init {
        require(angleStepRadians.isFinite() && angleStepRadians > 0f)
        require(tileSections > 0)
    }

    private var key: Key? = null
    private var dirtyEpoch = 0L
    private val dirtySections = LinkedHashSet<Section>()

    @Synchronized
    fun invalidate() { dirtyEpoch++ }

    /** Marks only the tile containing a section; callers pass section, not block, coordinates. */
    @Synchronized
    fun invalidateSection(sectionX: Int, sectionZ: Int) {
        dirtySections += Section(sectionX, sectionZ)
        dirtyEpoch++
    }

    @Synchronized
    fun dirtyTiles(): Set<ShadowTile> = dirtySections.mapTo(linkedSetOf()) {
        ShadowTile(Math.floorDiv(it.x, tileSections), Math.floorDiv(it.z, tileSections))
    }

    @Synchronized
    fun pendingSectionCount(): Int = dirtySections.size

    @Synchronized
    fun needsRender(angle: Float, cameraX: Double, cameraZ: Double): Boolean {
        val next = key(angle, cameraX, cameraZ)
        val changed = next != key
        if (changed && dirtySections.isEmpty()) key = next
        return changed || dirtySections.isNotEmpty()
    }

    /** Commits a successful draw. Invalidations that arrived before this call are consumed. */
    @Synchronized
    fun markRendered(angle: Float, cameraX: Double, cameraZ: Double) {
        key = key(angle, cameraX, cameraZ)
        dirtySections.clear()
    }

    private fun key(angle: Float, cameraX: Double, cameraZ: Double) = Key(
        (angle / angleStepRadians).roundToInt(),
        floor(cameraX).toInt() shr 4,
        floor(cameraZ).toInt() shr 4,
        dirtyEpoch,
    )

    private data class Section(val x: Int, val z: Int)
    private data class Key(val sun: Int, val cameraSectionX: Int, val cameraSectionZ: Int, val dirtyEpoch: Long)
}
