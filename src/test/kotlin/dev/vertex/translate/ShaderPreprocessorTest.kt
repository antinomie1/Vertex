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
                |/* two
                |   lines */
                |#endif
            """.trimMargin(),
        )
        val output = ShaderPreprocessor(listOf(root)).process(root.resolve("main.fsh"))
        assertContains(output, "#define TONEMAP(x)")
        assertContains(output, "TONEMAP(inputColor)")
        assertFalse("removed" in output)
        assertEquals(7, output.count { it == '\n' })
    }
}
