package dev.vertex.frontend

import java.nio.file.Files
import java.nio.file.Path
import dev.vertex.translate.ShaderPreprocessor
import dev.vertex.translate.LegacyUniformTranslator

/**
 * Minimal pack frontend for terrain and composite/final programs.
 */
data class LoadedProgram(
    val name: String,
    val vertexSource: String,
    val fragmentSource: String,
    val varyingName: String?,
    val samplers: List<String>,
    val outputs: List<Int>,
    val uniforms: Set<String>,
)

object PackFrontend {
    fun loadScreenChain(packRoot: Path, options: Map<String, String> = emptyMap()): List<LoadedProgram> {
        val sh = shaderRoots(packRoot).firstOrNull { hasScreenPair(it) }
            ?: throw IllegalArgumentException("${packRoot.resolve("shaders")}: expected setup, begin, prepare, deferred, composite, or final shader pair")
        val names = Files.list(sh).use { files ->
            files.map { it.fileName.toString() }
                .filter { it.endsWith(".fsh") }
                .map { it.removeSuffix(".fsh") }
                .filter(SCREEN_PROGRAM::matches)
                .filter { Files.isRegularFile(sh.resolve("$it.vsh")) }
                .sorted(SCREEN_ORDER)
                .toList()
        }
        require(names.isNotEmpty()) { "$sh: expected setup, begin, prepare, deferred, composite, or final shader pair" }
        return names.map { load(sh, it, options) }
    }

    fun loadComposite(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram =
        loadScreenChain(packRoot, options).first()

    private fun load(sh: Path, name: String, options: Map<String, String>): LoadedProgram {
        val vsh = ShaderPreprocessor(includeRoots(sh), options).process(sh.resolve("$name.vsh"))
        val fragmentFile = sh.resolve("$name.fsh")
        val outputs = outputs(Files.readString(fragmentFile))
        val fsh = ShaderPreprocessor(includeRoots(sh), options).process(fragmentFile)

        val varying = Regex("""varying\s+\w+\s+(\w+)\s*;""").findAll(vsh)
            .map { it.groupValues[1] }.firstOrNull()

        val samplers = SAMPLER.findAll(fsh)
            .map { it.groupValues[1] }.toList()

        return LoadedProgram(name, vsh, fsh, varying, samplers, outputs,
            LegacyUniformTranslator.uniforms(vsh) + LegacyUniformTranslator.uniforms(fsh))
    }

    fun loadTerrain(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram {
        val sh = shaderRoots(packRoot).firstOrNull { hasPair(it, "gbuffers_terrain") }
            ?: packRoot.resolve("shaders")
        val vshFile = sh.resolve("gbuffers_terrain.vsh")
        val fshFile = sh.resolve("gbuffers_terrain.fsh")
        val vsh = if (Files.isRegularFile(vshFile)) ShaderPreprocessor(includeRoots(sh), options).process(vshFile) else SamplePack.TERRAIN_VSH
        val fsh = if (Files.isRegularFile(fshFile)) ShaderPreprocessor(includeRoots(sh), options).process(fshFile) else SamplePack.TERRAIN_FSH
        val samplers = SAMPLER.findAll(fsh)
            .map { it.groupValues[1] }.toList()
        return LoadedProgram("gbuffers_terrain", vsh, fsh, null, samplers, outputs(fsh), emptySet())
    }

    /**
     * Loads the shared entity/hand program used by the dynamic RenderType bridge.
     * Iris packs use both singular and plural spellings, so resolve aliases once
     * here instead of making the renderer probe the filesystem on every draw.
     */
    fun loadDynamic(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram? {
        return loadPair(
            packRoot,
            listOf("gbuffers_entities", "gbuffers_entity", "gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
            options,
        )
    }

    fun loadParticle(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram? =
        loadPair(packRoot, listOf("gbuffers_particles", "gbuffers_particle"), options)

    fun loadSky(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram? =
        loadPair(packRoot, listOf("gbuffers_skybasic", "gbuffers_sky"), options)

    fun loadWeather(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram? =
        loadPair(packRoot, listOf("gbuffers_weather", "gbuffers_weather_basic"), options)

    fun loadShadow(packRoot: Path, options: Map<String, String> = emptyMap()): LoadedProgram? {
        val match = shaderRoots(packRoot).asSequence()
            .flatMap { sh -> listOf("shadow", "shadow_solid").asSequence().map { sh to it } }
            .firstOrNull { (sh, name) -> hasPair(sh, name) } ?: return null
        val (sh, name) = match
        return load(sh, name, options)
    }

    private fun loadPair(packRoot: Path, aliases: List<String>, options: Map<String, String>): LoadedProgram? {
        val match = shaderRoots(packRoot).asSequence()
            .flatMap { sh -> aliases.asSequence().map { sh to it } }
            .firstOrNull { (sh, stem) -> hasPair(sh, stem) } ?: return null
        val (sh, stem) = match
        val vsh = ShaderPreprocessor(includeRoots(sh), options).process(sh.resolve("$stem.vsh"))
        val fsh = ShaderPreprocessor(includeRoots(sh), options).process(sh.resolve("$stem.fsh"))
        val samplers = SAMPLER.findAll(fsh).map { it.groupValues[1] }.toList()
        return LoadedProgram(stem, vsh, fsh, null, samplers, outputs(fsh), emptySet())
    }

    private fun shaderRoots(packRoot: Path): List<Path> {
        val shaders = packRoot.resolve("shaders")
        if (!Files.isDirectory(shaders)) return listOf(shaders)
        val worlds = Files.list(shaders).use { children ->
            children.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("world") }
                .sorted().toList()
        }
        // The client starts before a level is attached, so world0 is the only
        // safe default for packs that provide dimension-specific shader roots.
        // Keep the pack-root fallback and all other dimensions available.
        val overworld = worlds.firstOrNull { it.fileName.toString() == "world0" }
        return listOfNotNull(overworld, shaders) + worlds.filterNot { it == overworld }
    }

    private fun hasPair(sh: Path, stem: String) =
        Files.isRegularFile(sh.resolve("$stem.vsh")) && Files.isRegularFile(sh.resolve("$stem.fsh"))

    private fun includeRoots(sh: Path): List<Path> = listOfNotNull(sh, sh.parent).distinct()

    private fun hasScreenPair(sh: Path): Boolean = Files.list(sh).use { files ->
        files.anyMatch { file ->
            val name = file.fileName.toString()
            name.endsWith(".fsh") && SCREEN_PROGRAM.matches(name.removeSuffix(".fsh")) &&
                Files.isRegularFile(sh.resolve(name.removeSuffix(".fsh") + ".vsh"))
        }
    }

    private fun outputs(source: String): List<Int> {
        val targets = RENDER_TARGETS.find(source)?.groupValues?.get(1)?.split(',')
            ?.map(String::trim)?.map(String::toInt)
            ?: DRAW_BUFFERS.find(source)?.groupValues?.get(1)?.map { it.digitToInt(16) }
            ?: listOf(0)
        require(targets.isNotEmpty() && targets.distinct().size == targets.size && targets.all { it in 0..15 }) {
            "invalid DRAWBUFFERS/RENDERTARGETS declaration: $targets"
        }
        return targets
    }

    private val SAMPLER = Regex("""uniform\s+(?:(?:lowp|mediump|highp)\s+)?[iu]?sampler\w*\s+(\w+)\s*;""")
    private val SCREEN_PROGRAM = Regex("""(?:setup|begin|prepare\d*|deferred\d*|composite\d*|final)""")
    private val DRAW_BUFFERS = Regex("""DRAWBUFFERS\s*:\s*([0-9A-Fa-f]+)""")
    private val RENDER_TARGETS = Regex("""RENDERTARGETS\s*:\s*([0-9, ]+)""")
    private val SCREEN_ORDER = compareBy<String>({
        when {
            it == "setup" -> 0; it == "begin" -> 1; it.startsWith("prepare") -> 2
            it.startsWith("deferred") -> 3; it.startsWith("composite") -> 4; else -> 5
        }
    }, { it.filter(Char::isDigit).toIntOrNull() ?: 0 })
}
