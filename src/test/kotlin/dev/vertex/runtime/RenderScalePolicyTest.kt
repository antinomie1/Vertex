package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RenderScalePolicyTest {
    @Test fun `allows three supported tiers`() {
        assertEquals(0.5f, RenderScalePolicy.resolve(0.5f, listOf("texture(x, uv)")).scale)
        assertEquals(0.75f, RenderScalePolicy.resolve(0.75f, emptyList()).scale)
        assertEquals(1f, RenderScalePolicy.resolve(1f, listOf("texelFetch(x, p, 0)")).scale)
    }

    @Test fun `protects texel exact packs and rejects arbitrary scales`() {
        val decision = RenderScalePolicy.resolve(0.5f, listOf("texelFetch(x, p, 0)"))
        assertEquals(1f, decision.scale)
        assertEquals("pack uses texelFetch", decision.reason)
        assertFailsWith<IllegalArgumentException> { RenderScalePolicy.resolve(0.8f, emptyList()) }
    }
}
