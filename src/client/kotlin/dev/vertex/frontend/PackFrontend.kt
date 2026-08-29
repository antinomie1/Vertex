package dev.vertex.frontend

import java.nio.file.Files
import java.nio.file.Path
import dev.vertex.translate.ShaderPreprocessor

/**
 * 包前端 v0：只认 composite.{vsh,fsh}。
 * 提取：varying 名（顶点透传模式）、片元采样器清单、片元源码。
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
        val preprocessor = ShaderPreprocessor(listOf(sh), options)
        val vsh = preprocessor.process(sh.resolve("composite.vsh"))
        val fsh = preprocessor.process(sh.resolve("composite.fsh"))

        val varying = Regex("""varying\s+\w+\s+(\w+)\s*;""").findAll(vsh)
            .map { it.groupValues[1] }.singleOrNull()
            ?: throw IllegalStateException("composite.vsh: 期望恰好一个 varying（透传模式）")

        val samplers = Regex("""uniform\s+sampler2D\s+(\w+)\s*;""").findAll(fsh)
            .map { it.groupValues[1] }.toList()

        return LoadedProgram(vsh, fsh, varying, samplers)
    }

    fun loadTerrain(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram {
        val sh = packRoot.resolve("shaders")
        val vshFile = sh.resolve("gbuffers_terrain.vsh")
        val fshFile = sh.resolve("gbuffers_terrain.fsh")
        val preprocessor = ShaderPreprocessor(listOf(sh), options)
        val vsh = if (Files.isRegularFile(vshFile)) preprocessor.process(vshFile) else SamplePack.TERRAIN_VSH
        val fsh = if (Files.isRegularFile(fshFile)) preprocessor.process(fshFile) else SamplePack.TERRAIN_FSH
        val samplers = Regex("""uniform\s+sampler2D\s+(\w+)\s*;""").findAll(fsh)
            .map { it.groupValues[1] }.toList()
        return LoadedProgram(vsh, fsh, null, samplers)
    }
}
