package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
