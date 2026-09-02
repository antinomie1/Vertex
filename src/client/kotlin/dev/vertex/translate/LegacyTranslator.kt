package dev.vertex.translate

import dev.vertex.frontend.LoadedProgram
import dev.vertex.frontend.ColorFormat

/**
 * 旧语法→Vulkan GLSL 规则表 v0（DESIGN.md M2 的最小实现集）：
 * - varying → in（片元）
 * - texture2D → texture
 * - gl_FragData[0] = X → fragColor = X，并前置 layout(0) out 声明
 * - 顶点透传模式：整体替换为 gl_VertexIndex 规范形（输出包的 varying 名）
 */
object LegacyTranslator {

    fun vertex(program: LoadedProgram): String {
        return LegacyFullscreenVertexTranslator.translate(program.vertexSource)
    }

    fun fragment(program: LoadedProgram, formats: List<ColorFormat> = emptyList()): String {
        return LegacyFragmentTranslator.translate(program.fragmentSource, formats.map(ColorFormat::numericType))
    }
    fun terrainVertex(
        program: LoadedProgram,
        separateAo: Boolean = false,
        abiRequirements: TerrainRequirements? = null,
    ): String {
        val requirements = abiRequirements ?: TerrainRequirementScanner.scan(program.vertexSource)
        val varyings = varyingDeclarations(program.vertexSource)
        val tangentAttribute = TANGENT_ATTRIBUTE.find(program.vertexSource)?.groupValues?.get(1)
        val midTexCoordType = Regex(
            """(?m)^\s*(?:attribute|in)\s+(?:(?:lowp|mediump|highp)\s+)?(\w+)\s+mc_midTexCoord\s*;""",
        ).find(program.vertexSource)?.groupValues?.get(1)
        var body = program.vertexSource
            .replace(Regex("""^\s*#(?:version|extension)[^\n]*""", RegexOption.MULTILINE), "")
            .replace(VARYING, "")
            .replace(Regex("""uniform\s+sampler2D\s+(?:tex|texture|gtexture)\s*;"""), "")
            .replace(Regex("""(?m)^\s*(?:attribute|in)\s+(?:(?:lowp|mediump|highp)\s+)?\w+\s+(?:mc_Entity|mc_midTexCoord|at_midBlock|at_tangent|tangent)\s*;"""), "")
        val unsupportedAttribute = Regex("""(?m)^\s*attribute\b[^;]*;""").find(body)?.value
        require(unsupportedAttribute == null) { "unsupported terrain vertex attribute: $unsupportedAttribute" }
        body = body
            .let(::modernizeTextureCalls)
            .replace(Regex("""\bgl_ModelViewProjectionMatrix\b"""), "(ProjMat * ModelViewMat)")
            .replace(Regex("""\bgl_ModelViewMatrix\b"""), "ModelViewMat")
            .replace(Regex("""\bgl_ProjectionMatrix\b"""), "ProjMat")
            .replace(Regex("""\bgl_NormalMatrix\b"""), "mat3(ModelViewMat)")
            .replace(Regex("""\bgl_TextureMatrix\s*\[\s*[01]\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(UV0, 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord1\b"""), "vec4(vec2(UV2) / 256.0 + vec2(1.0 / 32.0), 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord2\b"""), "vec4(UV0, 0.0, 1.0)")
            .replace(Regex("""\bgl_Color\b"""), if (separateAo) "Color" else "(Color * sample_lightmap(Sampler2, UV2))")
            .replace(Regex("""\b(?:tex|texture|gtexture)\b(?!\s*\()"""), "Sampler0")
            .replace(Regex("""\bgl_Normal\b"""), "Normal")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(vertexTerrainPos, 1.0)")
            .replace(Regex("""\bftransform\s*\(\s*\)"""), "(ProjMat * ModelViewMat * vec4(vertexTerrainPos, 1.0))")
            .replace(Regex("""\bmc_Entity\b"""), "vec4(float(UV1.x) / 2.0 - 1.0, float(UV1.x & 1u), 0.0, 0.0)")
            .replace(Regex("""\bat_midBlock\b"""), "((fract(Position) - vec3(0.5)) * 64.0)")
            .replace(Regex("""\bmc_midTexCoord\b"""), if (midTexCoordType == "vec2") "UV3" else "vec4(UV3, 0.0, 1.0)")
            .let(LegacyUniformTranslator::translate)
        tangentAttribute?.let { attribute ->
            body = body.replace(Regex("""\b${Regex.escape(attribute)}\b"""),
                "vec4(normalize(cross(abs(Normal.y) > 0.99 ? vec3(1, 0, 0) : vec3(0, 1, 0), Normal)), 1.0)")
        }
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(body)
            ?: error("${program.name}: terrain vertex shader has no main()")
        body = body.replaceRange(main.range, main.value + """
    vec3 vertexTerrainPos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    sphericalVertexDistance = fog_spherical_distance(vertexTerrainPos);
    cylindricalVertexDistance = fog_cylindrical_distance(vertexTerrainPos);
    #ifdef MULTIDRAW_TERRAIN
    float dist = length(vertexTerrainPos);
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((dist - 16.0) / 16.0, 0.0, 1.0));
    #else
    chunkVisibility = 1.0;
    #endif
""")
        val locations = varyingLocations(varyings, 3)
        val outputs = varyings.entries.map { (name, type) ->
            "layout(location = ${locations.getValue(name)}) ${interpolation(type)}out $type $name;"
        }.joinToString("\n")
        return """#version 450
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in uvec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 5) in ivec3 ChunkPosition;
layout(location = 6) in float ChunkVisibility;
layout(location = ${if (requirements.midTexCoord) 8 else 7}) in vec3 Normal;
#else
layout(location = ${if (requirements.midTexCoord) 6 else 5}) in vec3 Normal;
#endif
${if (requirements.midTexCoord) "#ifdef MULTIDRAW_TERRAIN\nlayout(location = 7) in vec2 UV3;\n#else\nlayout(location = 5) in vec2 UV3;\n#endif" else ""}
uniform sampler2D Sampler2;
uniform sampler2D Sampler0;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out float chunkVisibility;
$outputs
""" + body.trimStart() + "\n"
    }

    fun terrainFragment(program: LoadedProgram, separateAo: Boolean = false, reverseDepth: Boolean = false): String {
        val varyings = varyingDeclarations(program.vertexSource)
        val fragmentVaryings = varyingDeclarations(program.fragmentSource)
        require(fragmentVaryings.keys.all(varyings::containsKey)) { "terrain fragment references an undeclared varying" }
        var body = collapseSingleFragmentOutput(program.fragmentSource)
            .replace(Regex("""^#version\s+\d+[^\n]*""", RegexOption.MULTILINE), "")
            .replace(Regex("""^#extension[^\n]*""", RegexOption.MULTILINE), "")
            .replace(Regex("""uniform\s+[iu]?sampler\w*\s+(?:tex|texture|gtexture|lightmap|noisetex)\s*;"""), "")
            .let { replaceVaryingInputs(it, varyings, 3) }
            .replace(Regex("""texture2D\s*\("""), "texture(")
            .replace(Regex("""\b(?:tex|texture|gtexture)\b(?!\s*\()"""), "Sampler0")
            .replace(Regex("""\blightmap\b"""), "Sampler2")
            .replace(Regex("""\bgl_FragData\s*\[\s*0\s*]"""), "fragColor")
            .replace(Regex("""\bgl_FragColor\b"""), "fragColor")
            .replace(Regex("""\bgl_FragData\s*\[\s*[1-9]\d*\s*]\s*=\s*[^;]+;"""), "")
            .let(::modernizeTextureCalls)
            .let(LegacyUniformTranslator::translate)
            .let(::wrapAlphaTest)
        body = legacyFragmentDepth(body, reverseDepth)
        return """#version 450
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif
uniform sampler2D Sampler0;
${if (program.samplers.contains("noisetex")) "uniform sampler2D noisetex;" else ""}
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in float chunkVisibility;
layout(location = 0) out vec4 fragColor;
vec4 vertexPackAlphaTest(vec4 value) {
#ifdef ALPHA_CUTOUT
    if (value.a < ALPHA_CUTOUT) discard;
#endif
    return value;
}
""" + body.trimStart() + "\n"
    }

    /** Translates the legacy entity/hand family onto Minecraft's ENTITY vertex ABI. */
    fun dynamicVertex(program: LoadedProgram): String {
        val varyings = varyingDeclarations(program.vertexSource)
        val tangentAttribute = TANGENT_ATTRIBUTE.find(program.vertexSource)?.groupValues?.get(1)
        val midTexCoordType = Regex(
            """(?m)^\s*(?:attribute|in)\s+(?:(?:lowp|mediump|highp)\s+)?(\w+)\s+mc_midTexCoord\s*;""",
        ).find(program.vertexSource)?.groupValues?.get(1)
        var body = program.vertexSource
            .replace(Regex("""^\s*#(?:version|extension)[^\n]*""", RegexOption.MULTILINE), "")
            .replace(VARYING, "")
            .replace(Regex("""(?m)^\s*(?:attribute|in)\s+(?:(?:lowp|mediump|highp)\s+)?\w+\s+\w+\s*;"""), "")
            .let(::modernizeTextureCalls)
            .replace(Regex("""\bgl_ModelViewProjectionMatrix\b"""), "(ProjMat * ModelViewMat)")
            .replace(Regex("""\bgl_ModelViewMatrix\b"""), "ModelViewMat")
            .replace(Regex("""\bgl_ProjectionMatrix\b"""), "ProjMat")
            .replace(Regex("""\bgl_NormalMatrix\b"""), "mat3(ModelViewMat)")
            .replace(Regex("""\bgl_TextureMatrix\s*\[\s*0\s*]"""), "TextureMat")
            .replace(Regex("""\bgl_TextureMatrix\s*\[\s*[12]\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(UV0, 0.0, 1.0)")
            // Legacy entity shaders use texture coordinate set 1 for the
            // packed lightmap (not the modern overlay attribute UV1).
            .replace(Regex("""\bgl_MultiTexCoord1\b"""), "vec4(vec2(UV2) / 256.0 + vec2(1.0 / 32.0), 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord2\b"""), "vec4(UV1, 0.0, 1.0)")
            .replace(Regex("""\bgl_Color\b"""), "Color")
            .replace(Regex("""\bgl_Normal\b"""), "Normal")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(Position, 1.0)")
            .replace(Regex("""\bftransform\s*\(\s*\)"""), "(ProjMat * ModelViewMat * vec4(Position, 1.0))")
            .replace(Regex("""\bmc_midTexCoord\b"""), if (midTexCoordType == "vec4") "vec4(UV0, 0.0, 1.0)" else "vec2(UV0)")
            .replace(Regex("""\bmc_Entity\b"""), "vec4(0.0)")
            .let(LegacyUniformTranslator::translate)
        tangentAttribute?.let { attribute ->
            body = body.replace(Regex("""\b${Regex.escape(attribute)}\b"""),
                "vec4(normalize(cross(abs(Normal.y) > 0.99 ? vec3(1, 0, 0) : vec3(0, 1, 0), Normal)), 1.0)")
        }

        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(body)
            ?: error("${program.name}: dynamic vertex shader has no main()")
        body = body.replaceRange(main.range, main.value + """
    vec3 vertexPos = Position;
    sphericalVertexDistance = fog_spherical_distance(vertexPos);
    cylindricalVertexDistance = fog_cylindrical_distance(vertexPos);
""")
        val locations = varyingLocations(varyings, 2)
        val outputs = varyings.entries.map { (name, type) ->
            "layout(location = ${locations.getValue(name)}) ${interpolation(type)}out $type $name;"
        }.joinToString("\n")
        val lightmap = if (body.contains("Sampler2")) "uniform sampler2D Sampler2;" else ""
        return """#version 450
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;
$lightmap
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
$outputs
""" + body.trimStart()
    }

    /** Block/textured-lit variant using the BLOCK vertex ABI (no UV1 or normal). */
    fun blockVertex(program: LoadedProgram): String {
        val varyings = varyingDeclarations(program.vertexSource)
        var body = program.vertexSource
            .replace(Regex("""^\s*#(?:version|extension)[^\n]*""", RegexOption.MULTILINE), "")
            .replace(VARYING, "")
            .replace(Regex("""(?m)^\s*(?:attribute|in)\s+(?:(?:lowp|mediump|highp)\s+)?\w+\s+\w+\s*;"""), "")
            .replace(Regex("""\bgl_ModelViewProjectionMatrix\b"""), "(ProjMat * ModelViewMat)")
            .replace(Regex("""\bgl_ModelViewMatrix\b"""), "ModelViewMat")
            .replace(Regex("""\bgl_ProjectionMatrix\b"""), "ProjMat")
            .replace(Regex("""\bgl_TextureMatrix\s*\[\s*[01]\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(0.0)")
            .replace(Regex("""\bgl_MultiTexCoord[12]\b"""), "vec4(0.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(UV0, 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord[12]\b"""), "vec4(UV2, 0.0, 1.0)")
            .replace(Regex("""\bgl_Color\b"""), "Color")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(Position, 1.0)")
            .replace(Regex("""\bftransform\s*\(\s*\)"""), "(ProjMat * ModelViewMat * vec4(Position, 1.0))")
            .let(::modernizeTextureCalls)
            .let(LegacyUniformTranslator::translate)
        require(!Regex("""\bgl_Normal(?:Matrix)?\b""").containsMatchIn(body)) {
            "${program.name}: block vertex shader references unavailable normal attributes"
        }
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(body)
            ?: error("${program.name}: block vertex shader has no main()")
        body = body.replaceRange(main.range, main.value + """
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
""")
        val locations = varyingLocations(varyings, 2)
        val outputs = varyings.entries.map { (name, type) ->
            "layout(location = ${locations.getValue(name)}) ${interpolation(type)}out $type $name;"
        }.joinToString("\n")
        return """#version 450
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
$outputs
""" + body.trimStart()
    }

    /** Translates the fragment half while preserving the pack's varying names. */
    fun dynamicFragment(program: LoadedProgram, dropExtraTargets: Boolean = false, reverseDepth: Boolean = false): String {
        val varyings = varyingDeclarations(program.vertexSource)
        val fragmentVaryings = varyingDeclarations(program.fragmentSource)
        require(fragmentVaryings.keys.all(varyings::containsKey)) { "dynamic fragment references an undeclared varying" }
        var body = collapseSingleFragmentOutput(program.fragmentSource)
            .replace(Regex("""^\s*#(?:version|extension)[^\n]*""", RegexOption.MULTILINE), "")
            .replace(Regex("""uniform\s+sampler2D\s+(texture|gtexture)\s*;"""), "")
            .replace(Regex("""uniform\s+sampler2D\s+lightmap\s*;"""), "")
            .let { replaceVaryingInputs(it, varyings, 2) }
            .replace(Regex("""\b(texture|gtexture)\b(?!\s*\()"""), "Sampler0")
            .replace(Regex("""\blightmap\b"""), "Sampler2")
            .let(::modernizeTextureCalls)
            .replace(Regex("""\bgl_FragData\s*\[\s*0\s*]"""), "fragColor")
            .replace(Regex("""\bgl_FragColor\b"""), "fragColor")
            .let(LegacyUniformTranslator::translate)
            .let(::wrapAlphaTest)
        body = legacyFragmentDepth(body, reverseDepth)
        if (dropExtraTargets) body = body.replace(Regex("""\bgl_FragData\s*\[\s*[1-9]\s*]\s*=\s*[^;]+;"""), "")
        else require(!Regex("""gl_FragData\s*\[\s*[1-9]""").containsMatchIn(body)) {
            "dynamic fragment programs must write only render target 0"
        }
        val lightmap = if (body.contains("Sampler2")) "uniform sampler2D Sampler2;" else ""
        return """#version 450
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
uniform sampler2D Sampler0;
$lightmap
layout(location = 0) out vec4 fragColor;
vec4 vertexPackAlphaTest(vec4 value) {
#ifdef ALPHA_CUTOUT
    if (value.a < ALPHA_CUTOUT) discard;
#endif
    return value;
}
        """ + body.trimStart()
    }

    /** Sky uses the position-only pipeline; it intentionally has no lightmap ABI. */
    fun skyVertex(program: LoadedProgram, includeFog: Boolean = true, forceTransparent: Boolean = false): String {
        val varyings = varyingDeclarations(program.vertexSource)
        var body = program.vertexSource
            .replace(Regex("""^\s*#(?:version|extension)[^\n]*""", RegexOption.MULTILINE), "")
            .replace(VARYING, "")
            .replace(Regex("""\bgl_ModelViewProjectionMatrix\b"""), "(ProjMat * ModelViewMat)")
            .replace(Regex("""\bgl_ModelViewMatrix\b"""), "ModelViewMat")
            .replace(Regex("""\bgl_ProjectionMatrix\b"""), "ProjMat")
            .replace(Regex("""\bgl_TextureMatrix\s*\[\s*[01]\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_MultiTexCoord[012]\b"""), "vec4(0.0)")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(Position, 1.0)")
            .replace(Regex("""\bgl_Color\b"""), "ColorModulator")
            .replace(Regex("""\bftransform\s*\(\s*\)"""), "(ProjMat * ModelViewMat * vec4(Position, 1.0))")
            .let(::modernizeTextureCalls)
            .let(LegacyUniformTranslator::translate)
            .let { if (forceTransparent) it.replace("alpha = 1.0;", "alpha = 0.0;") else it }
        if (includeFog) {
            // Modern Minecraft's SKY mesh is only an upper-hemisphere disc.
            // Legacy packs calculate the sky direction from gl_FragCoord and
            // expect gbuffers_skybasic to cover every background pixel.
            body = body.replace(
                Regex("""\bgl_Position\s*=\s*\(ProjMat\s*\*\s*ModelViewMat\s*\*\s*vec4\(Position,\s*1\.0\)\)\s*;"""),
                "gl_Position = vertexFullscreenSky(Position);",
            )
        }
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(body)
            ?: error("${program.name}: sky vertex shader has no main()")
        if (includeFog) body = body.replaceRange(main.range, main.value + """
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
""")
        val locations = varyingLocations(varyings, 2)
        val outputs = varyings.entries.map { (name, type) ->
            "layout(location = ${locations.getValue(name)}) ${interpolation(type)}out $type $name;"
        }.joinToString("\n")
        return """#version 450
#extension GL_ARB_separate_shader_objects : require
${if (includeFog) "#include <minecraft:fog.glsl>" else ""}
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
layout(location = 0) in vec3 Position;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
$outputs
${if (includeFog) """
vec4 vertexFullscreenSky(vec3 position) {
    vec2 radial = position.xz;
    float radius = length(radial);
    return vec4(radius > 0.0 ? radial / radius * 1.5 : vec2(0.0), 0.0, 1.0);
}
""" else ""}
""" + body.trimStart()
    }

    fun skyFragment(program: LoadedProgram, includeFog: Boolean = true, reverseDepth: Boolean = false): String {
        val varyings = varyingDeclarations(program.vertexSource)
        val fragmentVaryings = varyingDeclarations(program.fragmentSource)
        require(fragmentVaryings.keys.all(varyings::containsKey)) { "sky fragment references an undeclared varying" }
        val body = collapseSingleFragmentOutput(program.fragmentSource)
            .replace(Regex("""^\s*#(?:version|extension)[^\n]*""", RegexOption.MULTILINE), "")
            .replace(Regex("""uniform\s+sampler2D\s+(texture|gtexture)\s*;"""), "")
            .let { replaceVaryingInputs(it, varyings, 2) }
            .replace(Regex("""\b(texture|gtexture)\b(?!\s*\()"""), "Sampler0")
            .replace(Regex("""\bgl_FragData\s*\[\s*0\s*]"""), "fragColor")
            .replace(Regex("""\bgl_FragColor\b"""), "fragColor")
            .let(::modernizeTextureCalls)
            .let(LegacyUniformTranslator::translate)
            .let { legacyFragmentDepth(it, reverseDepth) }
        require(!Regex("""gl_FragData\s*\[\s*[1-9]""").containsMatchIn(body)) {
            "sky fragment programs must write only render target 0"
        }
        val sampler = if (program.samplers.any { it == "texture" || it == "gtexture" }) "uniform sampler2D Sampler0;" else ""
        return """#version 450
#extension GL_ARB_separate_shader_objects : require
${if (includeFog) "#include <minecraft:fog.glsl>" else ""}
#include <minecraft:dynamictransforms.glsl>
$sampler
layout(location = 0) out vec4 fragColor;
""" + body.trimStart()
    }

    /** Particle/weather-compatible ABI (Position, UV0, Color, UV2). */
    fun particleVertex(program: LoadedProgram): String {
        val varyings = varyingDeclarations(program.vertexSource)
        val tangentAttribute = TANGENT_ATTRIBUTE.find(program.vertexSource)?.groupValues?.get(1)
        var body = program.vertexSource
            .replace(Regex("""^\s*#(?:version|extension)[^\n]*""", RegexOption.MULTILINE), "")
            .replace(VARYING, "")
            .replace(Regex("""(?m)^\s*(?:attribute|in)\s+(?:(?:lowp|mediump|highp)\s+)?\w+\s+\w+\s*;"""), "")
            .replace(Regex("""\bgl_ModelViewProjectionMatrix\b"""), "(ProjMat * ModelViewMat)")
            .replace(Regex("""\bgl_ModelViewMatrix\b"""), "ModelViewMat")
            .replace(Regex("""\bgl_ProjectionMatrix\b"""), "ProjMat")
            .replace(Regex("""\bgl_NormalMatrix\b"""), "mat3(ModelViewMat)")
            .replace(Regex("""\bgl_Normal\b"""), "vec3(0.0, 1.0, 0.0)")
            .replace(Regex("""\bmc_Entity\b"""), "vec4(0.0)")
            .replace(Regex("""\bmc_midTexCoord\b"""), "vec2(UV0)")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(Position, 1.0)")
            .replace(Regex("""\bgl_Color\b"""), "Color")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(UV0, 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord1\b"""), "vec4(UV2, 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord2\b"""), "vec4(UV2, 0.0, 1.0)")
            .replace(Regex("""\bgl_TextureMatrix\s*\[\s*0\s*]"""), "TextureMat")
            .replace(Regex("""\bgl_TextureMatrix\s*\[\s*1\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bftransform\s*\(\s*\)"""), "(ProjMat * ModelViewMat * vec4(Position, 1.0))")
            .let(::modernizeTextureCalls)
            .let(LegacyUniformTranslator::translate)
        tangentAttribute?.let { attribute ->
            body = body.replace(Regex("""\b${Regex.escape(attribute)}\b"""), "vec4(1.0, 0.0, 0.0, 1.0)")
        }
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(body)
            ?: error("${program.name}: particle vertex shader has no main()")
        body = body.replaceRange(main.range, main.value + """
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
""")
        val locations = varyingLocations(varyings, 2)
        val outputs = varyings.entries.map { (name, type) ->
            "layout(location = ${locations.getValue(name)}) ${interpolation(type)}out $type $name;"
        }.joinToString("\n")
        return """#version 450
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
$outputs
""" + body.trimStart()
    }

    fun particleFragment(program: LoadedProgram, reverseDepth: Boolean = false): String = dynamicFragment(program, reverseDepth = reverseDepth)

    fun shadowVertex(program: LoadedProgram, separateAo: Boolean = false, midTexCoord: Boolean = false): String {
        var location = 0
        val tangentAttribute = TANGENT_ATTRIBUTE.find(program.vertexSource)?.groupValues?.get(1)
        var body = program.vertexSource
            .replace(Regex("""^\s*#(?:version|extension)[^\n]*""", RegexOption.MULTILINE), "")
            .replace(Regex("""(?m)^\s*(?:attribute|in)\s+(?:(?:lowp|mediump|highp)\s+)?\w+\s+(?:mc_Entity|mc_midTexCoord|at_midBlock|at_tangent|tangent)\s*;"""), "")
            .replace(Regex("""uniform\s+sampler2D\s+(?:tex|texture|gtexture)\s*;"""), "")
            .replace(VARYING) { match ->
                match.groupValues[2].split(',').joinToString("\n") { raw ->
                    val current = location
                    location += locationWidth(match.groupValues[1], raw)
                    "layout(location = $current) ${interpolation(match.groupValues[1])}out ${match.groupValues[1]} ${raw.trim()};"
                }
            }
            // Pack shadow shaders use ftransform() to reconstruct the shadow
            // world position from the pack-facing OpenGL matrices. The final
            // clip position must use the native raster matrix instead; Vulkan
            // rejects the legacy [-1, 1] depth range used by shadowProjection.
            .replace(Regex("""\bgl_Position\s*=\s*shadowProjection\s*\*\s*shadowModelView\s*\*\s*position\s*;"""),
                "gl_Position = vertexShadowMvp * position;")
            .replace(Regex("""\bftransform\s*\(\s*\)"""), "shadowProjection * shadowModelView * vec4(vertexShadowPos, 1.0)")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(vertexShadowPos, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(UV0, 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord[12]\b"""), "vec4(0.0)")
            .replace(Regex("""\bgl_TextureMatrix\s*\[\s*[01]\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_ModelViewProjectionMatrix\b"""), "vertexShadowMvp")
            .replace(Regex("""\bgl_ModelViewMatrix\b|\bgl_ProjectionMatrix\b"""), "mat4(1.0)")
            .replace(Regex("""\bgl_Color\b"""), "Color")
            .replace(Regex("""\bgl_NormalMatrix\b"""), "mat3(1.0)")
            .replace(Regex("""\bgl_Normal\b"""), "Normal")
            .replace(Regex("""\bmc_Entity\b"""), "vec4(float(UV1.x) / 2.0 - 1.0, float(UV1.x & 1u), 0.0, 0.0)")
            .replace(Regex("""\bmc_midTexCoord\b"""), if (midTexCoord) "vec4(UV3, 0.0, 1.0)" else "vec4(0.0)")
            .replace(Regex("""\bat_midBlock\b"""), "((fract(Position) - vec3(0.5)) * 64.0)")
            .replace(Regex("""\b(?:tex|texture|gtexture)\b(?!\s*\()"""), "Sampler0")
            .let(::modernizeTextureCalls)
            .let(LegacyUniformTranslator::translate)
        tangentAttribute?.let { attribute ->
            body = body.replace(Regex("""\b${Regex.escape(attribute)}\b"""), "vec4(0.0, 0.0, 1.0, 1.0)")
        }
        // The shadow pass already provides world-space vertices. Reconstructing
        // them through pack UBO matrices makes the first depth draw use zeros,
        // leaving shadowtex0 at its clear value and blackening the whole scene.
        body = body.replace(
            Regex("""shadowModelViewInverse\s*\*\s*shadowProjectionInverse\s*\*\s*shadowProjection\s*\*\s*shadowModelView\s*\*\s*vec4\(vertexShadowPos, 1\.0\)"""),
            "vec4(vertexShadowPos, 1.0)",
        )
        // BSL applies its legacy OpenGL depth remap in the shadow vertex shader.
        // The raster matrix is already converted to Vulkan's 0..1 range, so keep
        // the same 0.5 + 0.1 * legacyDepth values expected by DistortShadow().
        body = body.replace(
            Regex("""gl_Position\.z\s*=\s*gl_Position\.z\s*\*\s*0\.2\s*;"""),
            "gl_Position.z = gl_Position.z * 0.2 + 0.4;",
        )
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(body)
            ?: error("${program.name}: shadow vertex shader has no main()")
        body = body.replaceRange(main.range, main.value + "\n    vec3 vertexShadowPos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;")
        val uv0 = 2
        val uv1 = uv0 + 1
        val uv2 = uv0 + 2
        val chunk = 5
        val visibility = chunk + 1
        val midBase = uv2 + 1
        val midMulti = visibility + 1
        val normalBase = if (midTexCoord) midBase + 1 else midBase
        val normalMulti = if (midTexCoord) midMulti + 1 else midMulti
        return shadowHeader(uv0, uv1, uv2, normalBase, normalMulti, chunk, visibility, midBase, midMulti, midTexCoord, separateAo) + body.trimStart()
    }

    fun shadowFragment(program: LoadedProgram): String {
        var location = 0
        var body = collapseSingleFragmentOutput(program.fragmentSource, "shadowColor")
            .replace(Regex("""^\s*#(?:version|extension)[^\n]*""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*/\*\s*(?:DRAWBUFFERS|RENDERTARGETS)\s*:[^*]*\*/\s*$""", RegexOption.MULTILINE), "")
            .replace(Regex("""uniform\s+sampler2D\s+(?:tex|texture|gtexture)\s*;"""), "")
            .replace(VARYING) { match ->
                match.groupValues[2].split(',').joinToString("\n") { raw ->
                    val current = location
                    location += locationWidth(match.groupValues[1], raw)
                    "layout(location = $current) ${interpolation(match.groupValues[1])}in ${match.groupValues[1]} ${raw.trim()};"
                }
            }
            // Replace only the legacy sampler identifier; keep modern texture(...) calls intact.
            .replace(Regex("""\b(?:tex|texture|gtexture)\b(?!\s*\()"""), "Sampler0")
            .let(::modernizeTextureCalls)
            .let(LegacyUniformTranslator::translate)
            .replace(Regex("""\bgl_FragData\s*\[\s*0\s*]"""), "shadowColor")
            .replace(Regex("""\bgl_FragColor\b"""), "shadowColor")
            .replace(Regex("""\bgl_FragData\s*\[\s*[1-9]\d*\s*]\s*=\s*[^;]+;"""), "")
            .replace(Regex("""\bshadowColor\s*=\s*([^;]+);""")) {
                "shadowColor = vertexShadowAlphaTest(${it.groupValues[1]});"
            }
        return """#version 450
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Sampler0;
layout(location = 0) out vec4 shadowColor;
vec4 vertexShadowAlphaTest(vec4 value) {
    if (value.a <= 0.1) discard;
    return value;
}
""" + body.trimStart()
    }

    private fun shadowHeader(uv0: Int, uv1: Int, uv2: Int, normalBase: Int, normalMulti: Int, chunk: Int, visibility: Int, midBase: Int, midMulti: Int, midTexCoord: Boolean, separateAo: Boolean) = """#version 450
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:globals.glsl>
#ifndef MULTIDRAW_TERRAIN
#include <minecraft:chunksection.glsl>
#endif
layout(location=0) in vec3 Position;
layout(location=1) in vec4 Color;
layout(location=$uv0) in vec2 UV0;
layout(location=$uv1) in uvec2 UV1;
layout(location=$uv2) in ivec2 UV2;
${if (midTexCoord) "#ifdef MULTIDRAW_TERRAIN\nlayout(location=$midMulti) in vec2 UV3;\n#else\nlayout(location=$midBase) in vec2 UV3;\n#endif" else ""}
#ifdef MULTIDRAW_TERRAIN
layout(location=$chunk) in ivec3 ChunkPosition;
layout(location=$visibility) in float ChunkVisibility;
layout(location=$normalMulti) in vec3 Normal;
#else
layout(location=$normalBase) in vec3 Normal;
#endif
uniform sampler2D Sampler0;
layout(std140) uniform ShadowUniforms { mat4 vertexShadowMvp; };
"""

    private val VARYING = Regex("""varying\s+(?:(?:lowp|mediump|highp)\s+)?(\w+)\s+([^;]+);""")
    private val TANGENT_ATTRIBUTE = Regex(
        """(?m)^\s*(?:attribute|in)\s+(?:(?:lowp|mediump|highp)\s+)?vec4\s+(at_tangent|tangent)\s*;""",
    )
    private val MODERN_FRAGMENT_OUTPUT = Regex(
        """(?m)^\s*(?:layout\s*\(\s*location\s*=\s*0\s*\)\s*)?out\s+(float|vec[234])\s+([A-Za-z_]\w*)\s*;\s*$""",
    )
    private val TEXTURE_CALL = Regex("""\b(texture2DGradARB|texture2DGradEXT|texture2DProj|texture2DLod|texture2D|texture3D|textureCube)\s*\(""")

    private fun modernizeTextureCalls(source: String): String {
        val modern = source
            .replace(TEXTURE_CALL) {
                (when (it.groupValues[1]) {
                    "texture2DProj" -> "textureProj"
                    "texture2DLod" -> "textureLod"
                    "texture2DGradARB", "texture2DGradEXT" -> "textureGrad"
                    else -> "texture"
                }) + "("
            }
        return LegacyShadowCompare.lower(modern)
    }

    /** Collapse a single-target modern output onto the renderer's fixed ABI. */
    private fun collapseSingleFragmentOutput(source: String, target: String = "fragColor"): String {
        val declaration = MODERN_FRAGMENT_OUTPUT.find(source) ?: return source
        val type = declaration.groupValues[1]
        val output = declaration.groupValues[2]
        if (type != "vec4") {
            val local = MODERN_FRAGMENT_OUTPUT.replace(source, "$type $output;")
            return injectAtMainEnd(local, "$target = ${if (type == "vec3") "vec4($output, 1.0)" else "vec4($output)"};")
        }
        return MODERN_FRAGMENT_OUTPUT.replace(source, "")
            .replace(Regex("""\b${Regex.escape(output)}\b"""), target)
    }

    private fun injectAtMainEnd(source: String, statement: String): String {
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(source) ?: return source
        var depth = 1
        var index = main.range.last + 1
        while (index < source.length && depth > 0) {
            when (source[index]) { '{' -> depth++; '}' -> depth-- }
            index++
        }
        return if (depth == 0) source.replaceRange(index - 1, index - 1, "\n    $statement\n") else source
    }

    /** Legacy pack shaders expect the OpenGL depth direction in gl_FragCoord.z. */
    private fun legacyFragmentDepth(source: String, reverseDepth: Boolean): String =
        if (reverseDepth) source.replace(Regex("""\bgl_FragCoord\.z\b"""), "(1.0 - gl_FragCoord.z)") else source

    /** RenderPipeline carries the vanilla cutout threshold as a define. */
    private fun wrapAlphaTest(source: String): String = source.replace(
        Regex("""\bfragColor\s*=\s*([^;]+);"""),
    ) { "fragColor = vertexPackAlphaTest(${it.groupValues[1]});" }

    private fun replaceVaryingInputs(source: String, varyings: Map<String, String>, base: Int) =
        varyingLocations(varyings, base).let { locations -> source.replace(VARYING) { match ->
            match.groupValues[2].split(',').joinToString("\n") { raw ->
                val name = raw.trim()
                "layout(location = ${locations.getValue(name)}) ${interpolation(match.groupValues[1])}in ${match.groupValues[1]} $name;"
            }
        } }

    private fun interpolation(type: String): String =
        if (type == "int" || type == "uint" || type.startsWith("ivec") || type.startsWith("uvec") || type.startsWith("bvec")) "flat " else ""

    private fun varyingLocations(varyings: Map<String, String>, base: Int): Map<String, Int> {
        var location = base
        return varyings.mapValues { (declaration, type) -> location.also { location += locationWidth(type, declaration) } }
    }

    private fun locationWidth(type: String, declaration: String = ""): Int {
        val columns = Regex("""(?:d?mat)([234])(?:x[234])?""")
            .matchEntire(type)?.groupValues?.get(1)?.toInt() ?: 1
        val expression = Regex("""\[\s*([^]]+)\s*]""").find(declaration)?.groupValues?.get(1) ?: return columns
        val extent = expression.trim().toIntOrNull()
            ?: Regex("""(\d+)\s*/\s*(\d+)""").matchEntire(expression.trim())?.destructured
                ?.let { (left, right) -> left.toInt() / right.toInt() }
            ?: Regex("""(\d+)\s*\*\s*(\d+)""").matchEntire(expression.trim())?.destructured
                ?.let { (left, right) -> left.toInt() * right.toInt() }
            ?: 1
        return columns * extent.coerceAtLeast(1)
    }

    private fun varyingDeclarations(source: String): LinkedHashMap<String, String> = LinkedHashMap<String, String>().also { result ->
        VARYING.findAll(source).forEach { match ->
            match.groupValues[2].split(',').map(String::trim).forEach { name ->
                require(result.put(name, match.groupValues[1]) == null) { "duplicate terrain varying '$name'" }
            }
        }
    }
}
