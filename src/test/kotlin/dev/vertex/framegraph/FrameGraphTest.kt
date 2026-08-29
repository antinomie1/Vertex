package dev.vertex.framegraph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameGraphTest {
    @Test
    fun `builds true edges and aliases disjoint compatible targets`() {
        val resources = listOf(
            ResourceSpec("scene", 1920, 1080, "RGBA8", external = true),
            ResourceSpec("a", 1920, 1080, "RGBA16F"),
            ResourceSpec("b", 1920, 1080, "RGBA16F"),
            ResourceSpec("out", 1920, 1080, "RGBA8"),
        )
        val nodes = listOf(
            FrameNode("prepare", NodeCadence.PER_FRAME, setOf("scene"), setOf("a")),
            FrameNode("composite", NodeCadence.PER_FRAME, setOf("a"), setOf("out")),
            FrameNode("final", NodeCadence.PER_FRAME, setOf("out"), setOf("b")),
        )
        val graph = FrameGraphCompiler.compile(resources, nodes)
        assertEquals(listOf(Dependency(0, 1, "a"), Dependency(1, 2, "out")), graph.dependencies)
        assertEquals(setOf("a", "b"), graph.aliasGroups.single { it.size == 2 }.toSet())
    }

    @Test
    fun `overlapping lifetimes and epochs never alias`() {
        val resources = listOf(
            ResourceSpec("in", 1, 1, "R8", external = true),
            ResourceSpec("a", 1, 1, "R8"),
            ResourceSpec("b", 1, 1, "R8", epoch = 1),
        )
        val nodes = listOf(
            FrameNode("n0", NodeCadence.AMORTIZED, setOf("in"), setOf("a")),
            FrameNode("n1", NodeCadence.PER_FRAME, setOf("a"), setOf("b")),
        )
        assertEquals(2, FrameGraphCompiler.compile(resources, nodes).aliasGroups.size)
    }

    @Test
    fun `invalid reads fail during pack loading`() {
        assertFailsWith<IllegalArgumentException> {
            FrameGraphCompiler.compile(
                listOf(ResourceSpec("a", 1, 1, "R8")),
                listOf(FrameNode("bad", NodeCadence.PER_FRAME, reads = setOf("a"))),
            )
        }
    }
}
