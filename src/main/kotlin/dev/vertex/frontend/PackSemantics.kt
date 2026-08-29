package dev.vertex.frontend

import dev.vertex.translate.ShaderPreprocessor
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

enum class ColorFormat(val bytesPerPixel: Int) {
    R8(1), RG8(2), RGB8(3), RGBA8(4), R8_SNORM(1), RG8_SNORM(2), RGB8_SNORM(3), RGBA8_SNORM(4),
    R16(2), RG16(4), RGB16(6), RGBA16(8), R16_SNORM(2), RG16_SNORM(4), RGB16_SNORM(6), RGBA16_SNORM(8),
    R8I(1), R8UI(1), RG8I(2), RG8UI(2), RGB8I(3), RGB8UI(3), RGBA8I(4), RGBA8UI(4),
    R16I(2), R16UI(2), RG16I(4), RG16UI(4), RGB16I(6), RGB16UI(6), RGBA16I(8), RGBA16UI(8),
    R32I(4), R32UI(4), RG32I(8), RG32UI(8), RGB32I(12), RGB32UI(12), RGBA32I(16), RGBA32UI(16),
    R16F(2), RG16F(4), RGB16F(6), RGBA16F(8), R32F(4), RG32F(8), RGB32F(12), RGBA32F(16),
    RGBA2(4), RGBA4(4), R3_G3_B2(3), RGB5_A1(4), RGB565(3),
    RGB10_A2(4), RGB10_A2UI(4), R11F_G11F_B10F(4), RGB9_E5(4);

    companion object {
        fun parse(token: String): ColorFormat = when (token.uppercase()) {
            "R11F_G11F_B10F" -> R11F_G11F_B10F
            "RGBA", "RGBA8" -> RGBA8
            "RGB" -> RGB8
            else -> entries.find { it.name == token.uppercase() }
                ?: throw IllegalArgumentException("unsupported colortex format: $token")
        }
    }
}

data class ColorBufferSettings(
    val format: ColorFormat = ColorFormat.RGBA8,
    val clear: Boolean = true,
    val clearColor: List<Float>? = null,
)

data class PackSemantics(
    val colors: List<ColorBufferSettings>,
    val flips: Map<String, Map<Int, Boolean>>,
    val noisePath: String?,
    val noiseResolution: Int,
    val customTextures: Map<String, Map<String, String>>,
)

/** Reads the shader constants and shaders.properties directives that control render targets. */
object PackSemanticsParser {
    fun load(packRoot: Path, options: Map<String, String> = emptyMap()): PackSemantics {
        val shaders = packRoot.resolve("shaders")
        val settings = MutableList(16) { ColorBufferSettings() }
        var noiseResolution = 256
        Files.list(shaders).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.', "") in SHADER_EXTENSIONS }
                .sorted().forEach { path ->
                    LEGACY_GAUX4.find(Files.readString(path))?.groupValues?.get(1)?.let { settings[7] = settings[7].copy(format = ColorFormat.parse(it)) }
                    val source = ShaderPreprocessor(listOf(shaders), options).process(path)
                    parseShader(source, settings)
                    NOISE_RESOLUTION.find(source)?.groupValues?.get(1)?.toInt()?.let { noiseResolution = it }
                }
        }
        require(noiseResolution in 1..4096) { "noiseTextureResolution must be in 1..4096" }
        val properties = loadProperties(shaders.resolve("shaders.properties"))
        return PackSemantics(settings, parseFlips(properties), properties.getProperty("texture.noise")?.trim(),
            noiseResolution, parseCustomTextures(properties))
    }

    private fun parseShader(source: String, settings: MutableList<ColorBufferSettings>) {
        FORMAT.findAll(source).forEach { match -> update(settings, match.groupValues[1]) {
            copy(format = ColorFormat.parse(match.groupValues[2]))
        } }
        CLEAR.findAll(source).forEach { match -> update(settings, match.groupValues[1]) {
            copy(clear = match.groupValues[2].toBooleanStrict())
        } }
        CLEAR_COLOR.findAll(source).forEach { match -> update(settings, match.groupValues[1]) {
            val values = NUMBER.findAll(match.groupValues[2]).map { it.value.removeSuffix("f").toFloat() }.toList()
            require(values.size == 4) { "${match.groupValues[1]}ClearColor requires four components" }
            copy(clearColor = values)
        } }
    }

    private fun update(settings: MutableList<ColorBufferSettings>, name: String, change: ColorBufferSettings.() -> ColorBufferSettings) {
        val id = colorId(name) ?: return
        settings[id] = settings[id].change()
    }

    private fun loadProperties(path: Path) = Properties().apply {
        if (Files.isRegularFile(path)) Files.newBufferedReader(path).use(::load)
    }

    private fun parseFlips(properties: Properties): Map<String, Map<Int, Boolean>> {
        return properties.stringPropertyNames().mapNotNull { key ->
            val match = FLIP.matchEntire(key) ?: return@mapNotNull null
            val id = colorId(match.groupValues[2]) ?: return@mapNotNull null
            Triple(match.groupValues[1], id, properties.getProperty(key).trim().toBooleanStrict())
        }.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, values) -> values.toMap() }
    }

    private fun parseCustomTextures(properties: Properties): Map<String, Map<String, String>> =
        properties.stringPropertyNames().mapNotNull { key ->
            val match = TEXTURE.matchEntire(key) ?: return@mapNotNull null
            Triple(match.groupValues[1], match.groupValues[2].substringBefore('.'), properties.getProperty(key).trim())
        }.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, values) -> values.toMap() }

    fun colorId(name: String): Int? = when (name) {
        "gcolor" -> 0; "gdepth" -> 1; "gnormal" -> 2; "composite" -> 3
        "gaux1" -> 4; "gaux2" -> 5; "gaux3" -> 6; "gaux4" -> 7
        else -> COLORTEX.matchEntire(name)?.groupValues?.get(1)?.toInt()
    }

    private val SHADER_EXTENSIONS = setOf("vsh", "fsh", "gsh", "csh")
    private val FORMAT = Regex("""const\s+int\s+(\w+)Format\s*=\s*(\w+)\s*;""")
    private val CLEAR = Regex("""const\s+bool\s+(\w+)Clear\s*=\s*(true|false)\s*;""")
    private val CLEAR_COLOR = Regex("""const\s+vec4\s+(\w+)ClearColor\s*=\s*vec4\s*\(([^)]*)\)\s*;""")
    private val NOISE_RESOLUTION = Regex("""const\s+int\s+noiseTextureResolution\s*=\s*(\d+)\s*;""")
    private val NUMBER = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?f?""")
    private val FLIP = Regex("""flip\.([^.]+)\.([^.]+)""")
    private val TEXTURE = Regex("""texture\.(setup|begin|prepare|deferred|composite)\.([^.]+(?:\.[123])?)""")
    private val COLORTEX = Regex("""colortex(\d|1[0-5])""")
    private val LEGACY_GAUX4 = Regex("""GAUX4FORMAT\s*:\s*(\w+)""")
}
