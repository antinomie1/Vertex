package dev.vertex.frontend

import java.nio.file.Files
import java.nio.file.Path
import dev.vertex.translate.ShaderPreprocessor

/**
 * Minimal pack frontend for terrain and composite/final programs.
 */
data class LoadedProgram(
    val vertexSource: String,
    val fragmentSource: String,
    val varyingName: String?,
    val samplers: List<String>,
)

object PackFrontend {
    fun loadComposite(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram {
        val sh = packRoot.resolve("shaders")
        val name = listOf("composite", "final").firstOrNull {
            Files.isRegularFile(sh.resolve("$it.vsh")) && Files.isRegularFile(sh.resolve("$it.fsh"))
        } ?: throw IllegalArgumentException("$sh: expected composite or final shader pair")
        val vsh = ShaderPreprocessor(listOf(sh), options).process(sh.resolve("$name.vsh"))
        val fsh = ShaderPreprocessor(listOf(sh), options).process(sh.resolve("$name.fsh"))

        val varying = Regex("""varying\s+\w+\s+(\w+)\s*;""").findAll(vsh)
            .map { it.groupValues[1] }.singleOrNull()
            ?: throw IllegalStateException("composite.vsh: 期望恰好一个 varying（透传模式）")

        val samplers = SAMPLER.findAll(fsh)
            .map { it.groupValues[1] }.toList()

        return LoadedProgram(vsh, fsh, varying, samplers)
    }

    fun loadTerrain(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram {
        val sh = packRoot.resolve("shaders")
        val vshFile = sh.resolve("gbuffers_terrain.vsh")
        val fshFile = sh.resolve("gbuffers_terrain.fsh")
        val vsh = if (Files.isRegularFile(vshFile)) ShaderPreprocessor(listOf(sh), options).process(vshFile) else SamplePack.TERRAIN_VSH
        val fsh = if (Files.isRegularFile(fshFile)) ShaderPreprocessor(listOf(sh), options).process(fshFile) else SamplePack.TERRAIN_FSH
        val samplers = SAMPLER.findAll(fsh)
            .map { it.groupValues[1] }.toList()
        return LoadedProgram(vsh, fsh, null, samplers)
    }

    private val SAMPLER = Regex("""uniform\s+[iu]?sampler\w*\s+(\w+)\s*;""")
}
