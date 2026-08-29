package dev.vertex.frontend

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class PackSemanticsParserTest {
    @Test
    fun `accepts every declared color format token`() {
        ColorFormat.entries.forEach { assertEquals(it, ColorFormat.parse(it.name)) }
        assertEquals(ColorFormat.RGBA8, ColorFormat.parse("RGBA"))
    }

    @Test
    fun `parses formats clear settings aliases and flips`() {
        val root = Files.createTempDirectory("vertex-semantics")
        val shaders = Files.createDirectory(root.resolve("shaders"))
        shaders.resolve("composite.fsh").writeText("""
            const int colortex1Format = RGBA16F;
            const bool gdepthClear = false;
            const vec4 colortex1ClearColor = vec4(0.25, 0.5, 1.0, 0.0);
        """.trimIndent())
        shaders.resolve("shaders.properties").writeText("""
            flip.composite.gdepth=false
            flip.final.colortex15=true
            texture.noise=/noise.png
            texture.composite.lut=/lut.png
            texture.final.grain=/grain.png
            separateAo=true
        """.trimIndent())

        val semantics = PackSemanticsParser.load(root)

        assertEquals(ColorFormat.RGBA16F, semantics.colors[1].format)
        assertFalse(semantics.colors[1].clear)
        assertEquals(listOf(0.25f, 0.5f, 1f, 0f), semantics.colors[1].clearColor)
        assertEquals(false, semantics.flips["composite"]?.get(1))
        assertEquals(true, semantics.flips["final"]?.get(15))
        assertEquals("/noise.png", semantics.noisePath)
        assertEquals("/lut.png", semantics.customTextures["composite"]?.get("lut"))
        assertEquals("/grain.png", semantics.customTextures["final"]?.get("grain"))
        assertEquals(true, semantics.separateAo)
    }

    @Test
    fun `rejects invalid boolean semantics`() {
        val root = Files.createTempDirectory("vertex-semantics-invalid")
        Files.createDirectory(root.resolve("shaders")).resolve("shaders.properties").writeText("separateAo=maybe")
        assertFailsWith<IllegalArgumentException> { PackSemanticsParser.load(root) }
    }
}
