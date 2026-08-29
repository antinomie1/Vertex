package dev.vertex.runtime

import kotlin.math.roundToInt
import kotlin.math.floor

/** Stable shadow-map key; section uploads invalidate without forcing per-frame redraws. */
class ShadowCacheState(private val angleStepRadians: Float = Math.toRadians(0.25).toFloat()) {
    private var key: Key? = null
    private var dirtyEpoch = 0L

    fun invalidate() { dirtyEpoch++ }

    fun needsRender(angle: Float, cameraX: Double, cameraZ: Double): Boolean {
        val next = Key((angle / angleStepRadians).roundToInt(), floor(cameraX).toInt() shr 4,
            floor(cameraZ).toInt() shr 4, dirtyEpoch)
        return (next != key).also { if (it) key = next }
    }

    private data class Key(val sun: Int, val cameraSectionX: Int, val cameraSectionZ: Int, val dirtyEpoch: Long)
}
