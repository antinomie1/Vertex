package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UniformHeapTest {
    @Test
    fun `std140 members and device segments are aligned`() {
        val layout = UniformLayoutBuilder(256)
            .add("time", UniformType.FLOAT)
            .add("camera", UniformType.VEC3)
            .add("mvp", UniformType.MAT4)
            .add("cascade", UniformType.VEC4, 3)
            .build()
        assertEquals(0, layout.member("time").offset)
        assertEquals(16, layout.member("camera").offset)
        assertEquals(32, layout.member("mvp").offset)
        assertEquals(96, layout.member("cascade").offset)
        assertEquals(256, layout.segmentBytes)
    }

    @Test
    fun `in flight slots remain isolated without allocation`() {
        val layout = UniformLayoutBuilder(64).add("frame", UniformType.INT).build()
        val heap = UniformHeap(layout, 2)
        heap.putInt(0, "frame", 7)
        heap.putInt(1, "frame", 9)
        assertEquals(7, heap.view(0).getInt(0))
        assertEquals(9, heap.view(1).getInt(0))
        assertFailsWith<IllegalArgumentException> { heap.view(2) }
    }

    @Test
    fun `typed writers preserve float and integer vector representation`() {
        val layout = UniformLayoutBuilder(32)
            .add("size", UniformType.VEC2)
            .add("light", UniformType.IVEC2)
            .build()
        val heap = UniformHeap(layout, 1)
        heap.putVec2(0, "size", 1920f, 1080f)
        heap.putIVec2(0, "light", 15, 7)
        val view = heap.view(0)
        assertEquals(1920f, view.getFloat(layout.member("size").offset))
        assertEquals(7, view.getInt(layout.member("light").offset + 4))
    }
}
