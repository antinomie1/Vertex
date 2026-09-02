package dev.vertex.frontend

import java.nio.file.Files
import java.nio.file.Path
import dev.vertex.translate.ShaderPreprocessor
import dev.vertex.translate.LegacyUniformTranslator
import dev.vertex.translate.ShaderExpression
import java.util.Properties

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
    fun loadScreenChain(
        packRoot: Path,
        options: Map<String, String> = emptyMap(),
        dimension: String? = null,
    ): List<LoadedProgram> {
        val (sh, names) = DimensionShaderRoots.ordered(packRoot, dimension).asSequence()
            .map { it to screenPrograms(packRoot, it, options) }
            .firstOrNull { (_, names) -> names.isNotEmpty() }
            ?: throw IllegalArgumentException(
                "${packRoot.resolve("shaders")}: expected an enabled setup, begin, prepare, deferred, composite, or final shader pair",
            )
        return names.map { load(sh, it, options) }
    }

    private fun screenPrograms(packRoot: Path, sh: Path, options: Map<String, String>): List<String> {
        if (!Files.isDirectory(sh)) return emptyList()
        return Files.list(sh).use { files ->
            files.map { it.fileName.toString() }
                .filter { it.endsWith(".fsh") }
                .map { it.removeSuffix(".fsh") }
                .filter(SCREEN_PROGRAM::matches)
                .filter { Files.isRegularFile(sh.resolve("$it.vsh")) }
                .filter { programEnabled(packRoot, sh, it, options) }
                .sorted(SCREEN_ORDER)
                .toList()
        }
    }

    fun loadComposite(packRoot: Path, options: Map<String, String> = emptyMap(), dimension: String? = null): LoadedProgram =
        loadScreenChain(packRoot, options, dimension).first()

    private fun load(sh: Path, name: String, options: Map<String, String>): LoadedProgram {
        val vsh = normalizeInterfaces(ShaderPreprocessor(includeRoots(sh), options).process(sh.resolve("$name.vsh")), true)
        val fragmentFile = sh.resolve("$name.fsh")
        val fsh = normalizeInterfaces(ShaderPreprocessor(includeRoots(sh), options).process(fragmentFile), false)
        // DRAWBUFFERS/RENDERTARGETS usually live in an included program file;
        // parse the expanded source rather than the tiny wrapper file.
        val outputs = outputs(fsh)

        val varying = Regex("""varying\s+\w+\s+(\w+)\s*;""").findAll(vsh)
            .map { it.groupValues[1] }.firstOrNull()

        val samplers = SAMPLER.findAll(fsh)
            .map { it.groupValues[1] }.toList()

        return LoadedProgram(name, vsh, fsh, varying, samplers, outputs,
            LegacyUniformTranslator.uniforms(vsh) + LegacyUniformTranslator.uniforms(fsh))
    }

    fun loadTerrain(packRoot: Path, options: Map<String, String> = emptyMap(), dimension: String? = null): LoadedProgram {
        val sh = DimensionShaderRoots.ordered(packRoot, dimension).firstOrNull { hasPair(it, "gbuffers_terrain") }
            ?: packRoot.resolve("shaders")
        val vshFile = sh.resolve("gbuffers_terrain.vsh")
        val fshFile = sh.resolve("gbuffers_terrain.fsh")
        val vsh = if (Files.isRegularFile(vshFile)) normalizeInterfaces(ShaderPreprocessor(includeRoots(sh), options).process(vshFile), true) else SamplePack.TERRAIN_VSH
        val fsh = if (Files.isRegularFile(fshFile)) normalizeInterfaces(ShaderPreprocessor(includeRoots(sh), options).process(fshFile), false) else SamplePack.TERRAIN_FSH
        val samplers = SAMPLER.findAll(fsh)
            .map { it.groupValues[1] }.toList()
        return LoadedProgram("gbuffers_terrain", vsh, fsh, null, samplers, outputs(fsh), emptySet())
    }

    fun loadWater(packRoot: Path, options: Map<String, String> = emptyMap(), dimension: String? = null): LoadedProgram? =
        loadPair(packRoot, listOf("gbuffers_water", "gbuffers_translucent"), options, dimension)

    /**
     * Loads the shared entity/hand program used by the dynamic RenderType bridge.
     * Iris packs use both singular and plural spellings, so resolve aliases once
     * here instead of making the renderer probe the filesystem on every draw.
     */
    fun loadDynamic(packRoot: Path, options: Map<String, String> = emptyMap(), dimension: String? = null): LoadedProgram? {
        return loadPair(
            packRoot,
            listOf("gbuffers_entities", "gbuffers_entity", "gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
            options, dimension,
        )
    }

    fun loadBlock(packRoot: Path, options: Map<String, String> = emptyMap(), dimension: String? = null): LoadedProgram? =
        loadPair(packRoot, listOf("gbuffers_block", "gbuffers_damagedblock"), options, dimension)

    fun loadParticle(packRoot: Path, options: Map<String, String> = emptyMap(), dimension: String? = null): LoadedProgram? =
        loadPair(packRoot, listOf("gbuffers_particles", "gbuffers_particle"), options, dimension)

    fun loadSky(packRoot: Path, options: Map<String, String> = emptyMap(), dimension: String? = null): LoadedProgram? =
        loadPair(packRoot, listOf("gbuffers_skybasic", "gbuffers_sky"), options, dimension)

    fun loadWeather(packRoot: Path, options: Map<String, String> = emptyMap(), dimension: String? = null): LoadedProgram? =
        loadPair(packRoot, listOf("gbuffers_weather", "gbuffers_weather_basic"), options, dimension)

    fun loadShadow(packRoot: Path, options: Map<String, String> = emptyMap(), dimension: String? = null): LoadedProgram? {
        val match = DimensionShaderRoots.ordered(packRoot, dimension).asSequence()
            .flatMap { sh -> listOf("shadow", "shadow_solid").asSequence().map { sh to it } }
            .firstOrNull { (sh, name) -> hasPair(sh, name) } ?: return null
        val (sh, name) = match
        return load(sh, name, options)
    }

    private fun loadPair(
        packRoot: Path,
        aliases: List<String>,
        options: Map<String, String>,
        dimension: String?,
    ): LoadedProgram? {
        val match = DimensionShaderRoots.ordered(packRoot, dimension).asSequence()
            .flatMap { sh -> aliases.asSequence().map { sh to it } }
            .firstOrNull { (sh, stem) -> hasPair(sh, stem) } ?: return null
        val (sh, stem) = match
        val vsh = normalizeInterfaces(ShaderPreprocessor(includeRoots(sh), options).process(sh.resolve("$stem.vsh")), true)
        val fsh = normalizeInterfaces(ShaderPreprocessor(includeRoots(sh), options).process(sh.resolve("$stem.fsh")), false)
        val samplers = SAMPLER.findAll(fsh).map { it.groupValues[1] }.toList()
        return LoadedProgram(stem, vsh, fsh, null, samplers, outputs(fsh), emptySet())
    }

    private fun hasPair(sh: Path, stem: String) =
        Files.isRegularFile(sh.resolve("$stem.vsh")) && Files.isRegularFile(sh.resolve("$stem.fsh"))

    private fun includeRoots(sh: Path): List<Path> = listOfNotNull(sh, sh.parent).distinct()

    /** Normalize GLSL 1.30+ stage interfaces onto the legacy varying ABI used by translators. */
    private fun normalizeInterfaces(source: String, vertex: Boolean): String {
        var normalized = MODERN_INTERFACE.replace(source) { match ->
            val qualifier = match.groupValues[2]
            if (qualifier == if (vertex) "out" else "in") {
                "varying ${match.groupValues[3]} ${match.groupValues[4]};"
            } else match.value
        }
        // RenderPearl's frontend cannot reflect a struct-valued stage interface.
        // Flatten it into ordinary varyings while preserving every member.  Both
        // stages see the same expanded includes, so names and locations remain
        // deterministic without any pack-specific schema.
        val structures = STRUCT.findAll(normalized).associate { declaration ->
            declaration.groupValues[1] to STRUCT_MEMBER.findAll(declaration.groupValues[2])
                .map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.filterValues { it.isNotEmpty() }
        val bridgeStatements = mutableListOf<String>()
        structures.forEach { (type, members) ->
            val declarations = Regex("""\bvarying\s+$type\s+([A-Za-z_]\w*)\s*;""")
            normalized = declarations.replace(normalized) { match ->
                val name = match.groupValues[1]
                bridgeStatements += members.map { (_, member) ->
                    if (vertex) "${name}_$member = $name.$member;" else "$name.$member = ${name}_$member;"
                }
                "$type $name;\n" + members.joinToString("\n") { (memberType, member) ->
                    "varying $memberType ${name}_$member;"
                }
            }
        }
        if (bridgeStatements.isNotEmpty()) normalized = injectMainBridge(normalized, bridgeStatements, vertex)
        return normalized
    }

    private fun injectMainBridge(source: String, statements: List<String>, atEnd: Boolean): String {
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(source) ?: return source
        val block = statements.joinToString("\n", prefix = "\n", postfix = "\n") { "    $it" }
        if (!atEnd) return source.replaceRange(main.range.last + 1, main.range.last + 1, block)
        var depth = 1
        var index = main.range.last + 1
        while (index < source.length && depth > 0) {
            when (source[index]) { '{' -> depth++; '}' -> depth-- }
            index++
        }
        return if (depth == 0) source.replaceRange(index - 1, index - 1, block) else source
    }

    private fun programEnabled(packRoot: Path, sh: Path, name: String, options: Map<String, String>): Boolean {
        val shaders = packRoot.resolve("shaders")
        val properties = Properties().apply {
            val file = shaders.resolve("shaders.properties")
            if (Files.isRegularFile(file)) Files.newBufferedReader(file).use(::load)
        }
        val dimension = sh.takeIf { it.parent == shaders }?.fileName?.toString()?.takeIf { it.startsWith("world") }
        val expression = listOfNotNull(
            dimension?.let { properties.getProperty("program.$it/$name.enabled") },
            properties.getProperty("program.$name.enabled"),
        ).firstOrNull()?.trim() ?: return true
        val symbols = defaultSettings(shaders).toMutableMap().apply { putAll(options) }
        return runCatching { ShaderExpression.evaluate(expression, symbols) }.getOrDefault(true)
    }

    private fun defaultSettings(shaders: Path): Map<String, String> {
        val file = listOf(shaders.resolve("settings.glsl"), shaders.resolve("lib/settings.glsl"))
            .firstOrNull(Files::isRegularFile) ?: return emptyMap()
        val values = linkedMapOf<String, String>()
        Files.readAllLines(file).forEach { line ->
            ACTIVE_SETTING.matchEntire(line)?.let { match ->
                values[match.groupValues[1]] = match.groupValues[2].substringBefore("//").trim().ifBlank { "1" }
            }
        }
        return values
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
    private val MODERN_INTERFACE = Regex(
        """(?m)^(\s*)(?:(?:flat|noperspective|smooth|centroid)\s+)*(in|out)\s+(?:(?:lowp|mediump|highp)\s+)?([A-Za-z_]\w*)\s+([^;]+);""",
    )
    private val ACTIVE_SETTING = Regex("""\s*#\s*define\s+([A-Za-z_]\w*)(?:\s+(.*))?""")
    private val STRUCT = Regex("""(?s)\bstruct\s+([A-Za-z_]\w*)\s*\{(.*?)}\s*;""")
    private val STRUCT_MEMBER = Regex("""\b([A-Za-z_]\w*)\s+([A-Za-z_]\w*)\s*;""")
    private val SCREEN_ORDER = compareBy<String>({
        when {
            it == "setup" -> 0; it == "begin" -> 1; it.startsWith("prepare") -> 2
            it.startsWith("deferred") -> 3; it.startsWith("composite") -> 4; else -> 5
        }
    }, { it.filter(Char::isDigit).toIntOrNull() ?: 0 })
}
