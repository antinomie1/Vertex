package dev.vertex.runtime

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FrameHashTest {
    @Test
    fun `ignores backend row padding but detects visible changes`() {
        val compact = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val padded = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 99, 99, 5, 6, 7, 8, 88, 88))
        val expected = FrameHash.rgba(compact, 1, 2, 4)
        assertEquals(expected, FrameHash.rgba(padded, 1, 2, 6))
        compact.put(0, 9)
        assertNotEquals(expected, FrameHash.rgba(compact, 1, 2, 4))
    }
}
