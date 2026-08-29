package dev.vertex.frontend

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PackFrontendTest {
    @Test
    fun `loads dimension programs with pack-root includes`() {
        val pack = Files.createTempDirectory("vertex-pack-front")
        val shaders = pack.resolve("shaders")
        val world = shaders.resolve("world-1")
        shaders.resolve("lib").createDirectories()
        world.createDirectories()
        shaders.resolve("lib/common.glsl").writeText("vec3 shared = vec3(1.0);\n")
        world.resolve("composite.vsh").writeText(
            "#version 120\n#include \"/lib/common.glsl\"\nvoid main() { gl_Position = ftransform(); }\n",
        )
        world.resolve("composite.fsh").writeText(
            "#version 120\nvoid main() { gl_FragColor = vec4(shared, 1.0); }\n",
        )

        val programs = PackFrontend.loadScreenChain(pack)
        assertEquals(listOf("composite"), programs.map(LoadedProgram::name))
        assertContains(programs.single().vertexSource, "vec3 shared")
    }
}
