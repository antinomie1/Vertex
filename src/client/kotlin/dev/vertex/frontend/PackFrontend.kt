package dev.vertex.frontend

import java.nio.file.Files
import java.nio.file.Path
import dev.vertex.translate.ShaderPreprocessor

/**
 * Minimal pack frontend for terrain and composite/final programs.
 */
data class LoadedProgram(
    val name: String,
    val vertexSource: String,
    val fragmentSource: String,
    val varyingName: String?,
    val samplers: List<String>,
)

object PackFrontend {
    fun loadScreenChain(packRoot: Path, options: Map<String, String> = emptyMap()): List<LoadedProgram> {
        val sh = packRoot.resolve("shaders")
        val names = Files.list(sh).use { files ->
            files.map { it.fileName.toString() }
                .filter { it.endsWith(".fsh") }
                .map { it.removeSuffix(".fsh") }
                .filter(SCREEN_PROGRAM::matches)
                .filter { Files.isRegularFile(sh.resolve("$it.vsh")) }
                .sorted(SCREEN_ORDER)
                .toList()
        }
        require(names.isNotEmpty()) { "$sh: expected deferred, composite, or final shader pair" }
        return names.map { load(sh, it, options) }
    }

    fun loadComposite(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram =
        loadScreenChain(packRoot, options).first()

    private fun load(sh: Path, name: String, options: Map<String, String>): LoadedProgram {
        val vsh = ShaderPreprocessor(listOf(sh), options).process(sh.resolve("$name.vsh"))
        val fsh = ShaderPreprocessor(listOf(sh), options).process(sh.resolve("$name.fsh"))

        val varying = Regex("""varying\s+\w+\s+(\w+)\s*;""").findAll(vsh)
            .map { it.groupValues[1] }.firstOrNull()

        val samplers = SAMPLER.findAll(fsh)
            .map { it.groupValues[1] }.toList()

        return LoadedProgram(name, vsh, fsh, varying, samplers)
    }

    fun loadTerrain(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram {
        val sh = packRoot.resolve("shaders")
        val vshFile = sh.resolve("gbuffers_terrain.vsh")
        val fshFile = sh.resolve("gbuffers_terrain.fsh")
        val vsh = if (Files.isRegularFile(vshFile)) ShaderPreprocessor(listOf(sh), options).process(vshFile) else SamplePack.TERRAIN_VSH
        val fsh = if (Files.isRegularFile(fshFile)) ShaderPreprocessor(listOf(sh), options).process(fshFile) else SamplePack.TERRAIN_FSH
        val samplers = SAMPLER.findAll(fsh)
            .map { it.groupValues[1] }.toList()
        return LoadedProgram("gbuffers_terrain", vsh, fsh, null, samplers)
    }

    private val SAMPLER = Regex("""uniform\s+[iu]?sampler\w*\s+(\w+)\s*;""")
    private val SCREEN_PROGRAM = Regex("""(?:deferred\d*|composite\d*|final)""")
    private val SCREEN_ORDER = compareBy<String>({
        when { it.startsWith("deferred") -> 0; it.startsWith("composite") -> 1; else -> 2 }
    }, { it.filter(Char::isDigit).toIntOrNull() ?: 0 })
}
