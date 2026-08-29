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

    @Test
    fun `prefers overworld root when dimensions are present`() {
        val pack = Files.createTempDirectory("vertex-pack-dimension")
        val shaders = pack.resolve("shaders")
        shaders.resolve("world-1").createDirectories()
        shaders.resolve("world0").createDirectories()
        for (world in listOf("world-1", "world0")) {
            val marker = if (world == "world0") "overworld" else "nether"
            shaders.resolve(world).resolve("composite.vsh").writeText("vec3 $marker;\nvoid main() { gl_Position = vec4(0.0); }\n")
            shaders.resolve(world).resolve("composite.fsh").writeText("void main() { gl_FragColor = vec4(1.0); }\n")
        }
        assertEquals(listOf("composite"), PackFrontend.loadScreenChain(pack).map(LoadedProgram::name))
        assertContains(PackFrontend.loadScreenChain(pack).single().vertexSource, "overworld")
    }

    @Test
    fun `reads drawbuffers from included screen program`() {
        val pack = Files.createTempDirectory("vertex-pack-drawbuffers")
        val shaders = pack.resolve("shaders")
        val program = shaders.resolve("program")
        program.createDirectories()
        shaders.resolve("composite.vsh").writeText("#version 120\nvoid main() { gl_Position = ftransform(); }\n")
        shaders.resolve("composite.fsh").writeText("#version 120\n#include \"/program/composite.glsl\"\n")
        program.resolve("composite.glsl").writeText(
            "void main() { /* DRAWBUFFERS:01 */ gl_FragData[0] = vec4(1.0); gl_FragData[1] = vec4(0.0); }\n",
        )

        assertEquals(listOf(0, 1), PackFrontend.loadScreenChain(pack).single().outputs)
    }
}
