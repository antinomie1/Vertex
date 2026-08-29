package dev.vertex.translate

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ShaderPreprocessorTest {
    @Test
    fun `includes and folds option conditionals`() {
        val root = Files.createTempDirectory("vertex-preprocessor")
        root.resolve("common.glsl").writeText("vec3 shared = vec3(QUALITY);\n")
        root.resolve("main.fsh").writeText(
            """#version 120
                |#include "common.glsl"
                |#if QUALITY >= 2 && defined(FOG)
                |vec3 selected = shared;
                |#else
                |bad token;
                |#endif
            """.trimMargin(),
        )
        val output = ShaderPreprocessor(listOf(root), mapOf("QUALITY" to "2", "FOG" to "1"))
            .process(root.resolve("main.fsh"))
        assertContains(output, "vec3 shared = vec3(2);")
        assertContains(output, "vec3 selected = shared;")
        assertFalse("bad token" in output)
        assertContains(output, "#line")
    }

    @Test
    fun `resolves pack-root absolute includes`() {
        val root = Files.createTempDirectory("vertex-preprocessor-root-include")
        root.resolve("lib.glsl").writeText("vec3 shared = vec3(1.0);\n")
        root.resolve("main.fsh").writeText("#include \"/lib.glsl\"\nvoid main() {}\n")
        assertContains(ShaderPreprocessor(listOf(root)).process(root.resolve("main.fsh")), "vec3 shared")
    }

    @Test
    fun `keeps statement terminators when macro has inline documentation`() {
        val root = Files.createTempDirectory("vertex-preprocessor-inline-comment")
        root.resolve("settings.glsl").writeText("#define SCALE 1.0 //[0.0 2.0]\n")
        val source = root.resolve("main.glsl")
        source.writeText("#include \"settings.glsl\"\nfloat value = SCALE;\n")
        assertContains(ShaderPreprocessor(listOf(root)).process(source), "float value = 1.0;")
    }

    @Test
    fun `include cycles fail with source context`() {
        val root = Files.createTempDirectory("vertex-preprocessor-cycle")
        root.resolve("a.glsl").writeText("#include \"b.glsl\"\n")
        root.resolve("b.glsl").writeText("#include \"a.glsl\"\n")
        assertFailsWith<IllegalArgumentException> {
            ShaderPreprocessor(listOf(root)).process(root.resolve("a.glsl"))
        }
    }

    @Test
    fun `pack macros survive while comments retain line count`() {
        val root = Files.createTempDirectory("vertex-preprocessor-macros")
        root.resolve("main.fsh").writeText(
            """#define TONEMAP(x) ((x) / (1.0 + (x)))
                |#define ENABLED 1
                |#if ENABLED
                |vec3 color = TONEMAP(inputColor); // removed
                |float enabled = ENABLED;
                |/* two
                |   lines */
                |#endif
            """.trimMargin(),
        )
        val output = ShaderPreprocessor(listOf(root)).process(root.resolve("main.fsh"))
        assertContains(output, "#define TONEMAP(x)")
        assertContains(output, "TONEMAP(inputColor)")
        assertContains(output, "float enabled = 1;")
        assertFalse("removed" in output)
        assertEquals(8, output.count { it == '\n' })
    }
}
