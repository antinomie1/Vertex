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

    @Test
    fun `normalizes modern stage interfaces for legacy translation`() {
        val pack = Files.createTempDirectory("vertex-pack-modern-interface")
        val shaders = pack.resolve("shaders").createDirectories()
        shaders.resolve("composite.vsh").writeText(
            "flat out vec3 ray;\nvoid main() { ray = vec3(1.0); gl_Position = vec4(0.0); }\n",
        )
        shaders.resolve("composite.fsh").writeText(
            "flat in vec3 ray;\nvoid main() { gl_FragColor = vec4(ray, 1.0); }\n",
        )
        val program = PackFrontend.loadScreenChain(pack).single()
        assertContains(program.vertexSource, "varying vec3 ray;")
        assertContains(program.fragmentSource, "varying vec3 ray;")
    }

    @Test
    fun `flattens struct stage interfaces`() {
        val pack = Files.createTempDirectory("vertex-pack-struct-interface")
        val shaders = pack.resolve("shaders").createDirectories()
        val structure = "struct Fog { vec3 scattering; float density; };\n"
        shaders.resolve("composite.vsh").writeText(
            structure + "flat out Fog fog;\nvoid main() { fog.scattering = vec3(1.0); fog.density = 0.5; gl_Position = vec4(0.0); }\n",
        )
        shaders.resolve("composite.fsh").writeText(
            structure + "flat in Fog fog;\nvoid main() { gl_FragColor = vec4(fog.scattering * fog.density, 1.0); }\n",
        )
        val program = PackFrontend.loadScreenChain(pack).single()
        assertContains(program.vertexSource, "varying vec3 fog_scattering;")
        assertContains(program.fragmentSource, "varying float fog_density;")
        assertContains(program.vertexSource, "fog_scattering = fog.scattering;")
        assertContains(program.fragmentSource, "fog.scattering = fog_scattering;")
    }

    @Test
    fun `skips screen programs disabled by pack settings`() {
        val pack = Files.createTempDirectory("vertex-pack-program-enabled")
        val shaders = pack.resolve("shaders")
        val world = shaders.resolve("world0").createDirectories()
        shaders.resolve("settings.glsl").writeText("//#define OPTIONAL_PASS\n")
        shaders.resolve("shaders.properties").writeText("program.world0/composite1.enabled=OPTIONAL_PASS\n")
        for (name in listOf("composite", "composite1")) {
            world.resolve("$name.vsh").writeText("void main() { gl_Position = vec4(0.0); }\n")
            world.resolve("$name.fsh").writeText("void main() { gl_FragColor = vec4(1.0); }\n")
        }
        assertEquals(listOf("composite"), PackFrontend.loadScreenChain(pack).map(LoadedProgram::name))
    }

    @Test
    fun `falls back to shared programs when dimension programs are disabled`() {
        val pack = Files.createTempDirectory("vertex-pack-program-fallback")
        val shaders = pack.resolve("shaders")
        val world = shaders.resolve("world0").createDirectories()
        shaders.resolve("shaders.properties").writeText("program.world0/composite.enabled=false\n")
        world.resolve("composite.vsh").writeText("vec3 dimensionProgram;\nvoid main() { gl_Position = vec4(0.0); }\n")
        world.resolve("composite.fsh").writeText("void main() { gl_FragColor = vec4(1.0); }\n")
        shaders.resolve("composite.vsh").writeText("vec3 sharedProgram;\nvoid main() { gl_Position = vec4(0.0); }\n")
        shaders.resolve("composite.fsh").writeText("void main() { gl_FragColor = vec4(1.0); }\n")

        val program = PackFrontend.loadScreenChain(pack).single()
        assertContains(program.vertexSource, "sharedProgram")
    }
}
