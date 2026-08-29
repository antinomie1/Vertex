package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShadowCacheStateTest {
    @Test
    fun `reuses stable map and invalidates on meaningful changes`() {
        val cache = ShadowCacheState(0.1f)
        assertTrue(cache.needsRender(0f, 1.0, 1.0))
        assertFalse(cache.needsRender(0.04f, 15.0, 15.0))
        assertTrue(cache.needsRender(0.06f, 15.0, 15.0))
        assertTrue(cache.needsRender(0.06f, 16.0, 15.0))
        assertFalse(cache.needsRender(0.06f, 16.0, 15.0))
        cache.invalidate()
        assertTrue(cache.needsRender(0.06f, 16.0, 15.0))
    }

    @Test
    fun `section invalidation maps to tiles until a successful draw`() {
        val cache = ShadowCacheState(0.1f, tileSections = 8)
        cache.markRendered(0f, 0.0, 0.0)
        cache.invalidateSection(-1, 16)
        cache.invalidateSection(7, 17)
        assertEquals(setOf(ShadowTile(-1, 2), ShadowTile(0, 2)), cache.dirtyTiles())
        assertTrue(cache.needsRender(0f, 0.0, 0.0))
        assertEquals(2, cache.pendingSectionCount())
        cache.markRendered(0f, 0.0, 0.0)
        assertTrue(cache.dirtyTiles().isEmpty())
        assertFalse(cache.needsRender(0f, 0.0, 0.0))
    }

    @Test
    fun `keeps invalidation that arrives during a draw`() {
        val cache = ShadowCacheState(0.1f)
        cache.markRendered(0f, 0.0, 0.0)
        cache.invalidateSection(1, 1)
        val renderEpoch = cache.epoch()
        cache.invalidateSection(2, 2)
        cache.markRendered(0f, 0.0, 0.0, renderEpoch)
        assertEquals(1, cache.pendingSectionCount())
        assertTrue(cache.needsRender(0f, 0.0, 0.0))
    }
}
