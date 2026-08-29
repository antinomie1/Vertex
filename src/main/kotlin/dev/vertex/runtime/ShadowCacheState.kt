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
    private val dirtySections = LinkedHashMap<Section, Long>()

    @Synchronized
    fun invalidate() { dirtyEpoch++ }

    /** Marks only the tile containing a section; callers pass section, not block, coordinates. */
    @Synchronized
    fun invalidateSection(sectionX: Int, sectionZ: Int) {
        dirtyEpoch++
        dirtySections[Section(sectionX, sectionZ)] = dirtyEpoch
    }

    @Synchronized
    fun epoch(): Long = dirtyEpoch

    @Synchronized
    fun dirtyTiles(): Set<ShadowTile> = dirtySections.mapTo(linkedSetOf()) {
        ShadowTile(Math.floorDiv(it.key.x, tileSections), Math.floorDiv(it.key.z, tileSections))
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

    /** Commits a successful draw; invalidations after [renderEpoch] stay pending. */
    @Synchronized
    fun markRendered(angle: Float, cameraX: Double, cameraZ: Double, renderEpoch: Long = dirtyEpoch) {
        key = Key(baseKey(angle, cameraX, cameraZ), renderEpoch)
        dirtySections.entries.removeIf { it.value <= renderEpoch }
    }

    private fun key(angle: Float, cameraX: Double, cameraZ: Double) =
        Key(baseKey(angle, cameraX, cameraZ), dirtyEpoch)

    private fun baseKey(angle: Float, cameraX: Double, cameraZ: Double) = BaseKey(
        (angle / angleStepRadians).roundToInt(),
        floor(cameraX).toInt() shr 4,
        floor(cameraZ).toInt() shr 4,
    )

    private data class Section(val x: Int, val z: Int)
    private data class BaseKey(val sun: Int, val cameraSectionX: Int, val cameraSectionZ: Int)
    private data class Key(val base: BaseKey, val dirtyEpoch: Long)
}
