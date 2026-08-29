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
        assertEquals(ResourceLifetime(0, 1), graph.lifetimes["a"])
        assertEquals(ResourceLifetime(2, 2), graph.lifetimes["b"])
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

    @Test
    fun `tracks rewrites and coalesces resource barriers`() {
        val graph = FrameGraphCompiler.compile(
            listOf(ResourceSpec("scene", 1, 1, "RGBA8", external = true), ResourceSpec("color", 1, 1, "RGBA8")),
            listOf(
                FrameNode("first", NodeCadence.PER_FRAME, setOf("scene"), setOf("color")),
                FrameNode("second", NodeCadence.PER_FRAME, setOf("scene"), setOf("color")),
                FrameNode("read", NodeCadence.PER_FRAME, setOf("color")),
            ),
        )
        assertEquals(
            listOf(Dependency(0, 1, "color"), Dependency(1, 2, "color")),
            graph.dependencies,
        )
        assertEquals(ResourceLifetime(0, 2), graph.lifetimes.getValue("color"))
        assertEquals(listOf(BarrierGroup(0, 1, listOf("color")), BarrierGroup(1, 2, listOf("color"))), graph.barrierGroups)
    }

    @Test
    fun `fuses only adjacent marked fullscreen passes with matching outputs`() {
        val graph = FrameGraphCompiler.compile(
            listOf(
                ResourceSpec("scene", 2, 2, "RGBA8", external = true),
                ResourceSpec("a", 2, 2, "RGBA16F"),
                ResourceSpec("b", 2, 2, "RGBA16F"),
                ResourceSpec("out", 2, 2, "RGBA8"),
            ),
            listOf(
                FrameNode("prepare", NodeCadence.PER_FRAME, setOf("scene"), setOf("a"), fullscreen = true),
                FrameNode("composite", NodeCadence.PER_FRAME, setOf("a"), setOf("b"), fullscreen = true),
                FrameNode("final", NodeCadence.PER_FRAME, setOf("b"), setOf("out"), fullscreen = false),
            ),
        )
        assertEquals(listOf(listOf("prepare", "composite"), listOf("final")), graph.fusionGroups)
    }
}
