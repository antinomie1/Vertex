package dev.vertex.translate

import dev.vertex.runtime.UniformLayout
import dev.vertex.runtime.UniformLayoutBuilder
import dev.vertex.runtime.UniformType

data class PackUniformSpec(val glslType: String, val storageType: UniformType)

/** Fixed std140 ABI shared by every translated shader stage and every in-flight slot. */
object PackUniformCatalog {
    val specs = linkedMapOf(
        "near" to f(), "far" to f(), "viewWidth" to f(), "viewHeight" to f(), "aspectRatio" to f(),
        "frameTime" to f(), "frameTimeCounter" to f(), "rainStrength" to f(), "wetness" to f(),
        "eyeAltitude" to f(), "sunAngle" to f(), "shadowAngle" to f(), "nightVision" to f(),
        "blindness" to f(), "darknessFactor" to f(), "screenBrightness" to f(), "centerDepthSmooth" to f(),
        "rainfall" to f(), "temperature" to f(), "fogStart" to f(), "fogEnd" to f(),
        "endFlashIntensity" to f(), "shadowFade" to f(), "timeAngle" to f(), "timeBrightness" to f(),
        "blindFactor" to f(), "dhFarPlane" to f(), "dhNearPlane" to f(), "framemod2" to f(),
        "framemod8" to f(), "isBasalt" to f(), "isCold" to f(), "isCrimson" to f(), "isDesert" to f(),
        "isJungle" to f(), "isMesa" to f(), "isMushroom" to f(), "isSavanna" to f(), "isSwamp" to f(),
        "isValley" to f(), "isWarped" to f(),
        "darknessLightFactor" to f(), "heldBlockLightValue" to i(), "heldBlockLightValue2" to i(),
        "playerMood" to f(), "constantMood" to f(), "thunderStrength" to f(), "cloudTime" to f(),
        "cloudHeight" to f(), "frameTimeSmooth" to f(), "frameCounter" to i(), "worldTime" to i(),
        "worldDay" to i(), "moonPhase" to i(), "isEyeInWater" to i(), "hideGUI" to b(), "biome" to i(),
        "biome_category" to i(), "biome_precipitation" to i(), "heldItemId" to i(), "heldItemId2" to i(),
        "entityId" to i(), "blockEntityId" to i(), "currentRenderedItemId" to i(), "renderStage" to i(),
        "bedrockLevel" to i(), "dhRenderDistance" to i(), "heightLimit" to i(), "vxRenderDistance" to i(),
        "fogMode" to i(), "fogShape" to i(), "anisotropicFiltering" to i(), "textureReloadCount" to i(),
        "isRightHanded" to b(), "is_sneaking" to b(), "is_sprinting" to b(), "is_hurt" to b(),
        "is_invisible" to b(), "is_burning" to b(), "is_on_ground" to b(), "firstPersonCamera" to b(),
        "isSpectator" to b(), "cameraPosition" to v3(),
        "previousCameraPosition" to v3(), "sunPosition" to v3(), "moonPosition" to v3(),
        "shadowLightPosition" to v3(), "upPosition" to v3(), "skyColor" to v3(),
        "fogColor" to v3(), "heldBlockLightColor" to v3(), "heldBlockLightColor2" to v3(),
        "cameraPositionFract" to v3(), "previousCameraPositionFract" to v3(),
        "endFlashPosition" to v3(), "relativeEyePosition" to v3(),
        "cameraPositionInt" to iv3(), "previousCameraPositionInt" to iv3(), "currentDate" to iv3(),
        "currentTime" to iv3(),
        "eyeBrightness" to iv2(), "eyeBrightnessSmooth" to iv2(), "terrainTextureSize" to iv2(),
        "atlasSize" to iv2(), "currentYearTime" to iv2(), "taaOffset" to v2(), "entityColor" to v4(),
        "gbufferModelView" to m4(), "gbufferModelViewInverse" to m4(), "gbufferPreviousModelView" to m4(),
        "gbufferProjection" to m4(), "gbufferProjectionInverse" to m4(), "gbufferPreviousProjection" to m4(),
        "shadowModelView" to m4(), "shadowModelViewInverse" to m4(),
        "shadowProjection" to m4(), "shadowProjectionInverse" to m4(),
        "dhProjection" to m4(), "dhPreviousProjection" to m4(), "dhProjectionInverse" to m4(),
        "vxProj" to m4(), "vxProjInv" to m4(), "vxProjPrev" to m4(),
        "gbufferNormal" to m3(), "gbufferNormalInverse" to m3(), "gbufferPreviousNormal" to m3(),
    )

    val layout: UniformLayout = UniformLayoutBuilder(256).also { builder ->
        specs.forEach { (name, spec) -> builder.add(name, spec.storageType) }
    }.build()

    fun block(visible: Set<String>): String = buildString {
        appendLine("layout(std140) uniform VertexPackUniforms {")
        specs.forEach { (name, spec) ->
            appendLine("    ${spec.glslType} ${if (name in visible) name else "vertexUniform_$name"};")
        }
        appendLine("};")
    }

    private fun f() = PackUniformSpec("float", UniformType.FLOAT)
    private fun i() = PackUniformSpec("int", UniformType.INT)
    private fun b() = PackUniformSpec("bool", UniformType.INT)
    private fun v2() = PackUniformSpec("vec2", UniformType.VEC2)
    private fun iv2() = PackUniformSpec("ivec2", UniformType.IVEC2)
    private fun iv3() = PackUniformSpec("ivec3", UniformType.IVEC3)
    private fun v3() = PackUniformSpec("vec3", UniformType.VEC3)
    private fun v4() = PackUniformSpec("vec4", UniformType.VEC4)
    private fun m4() = PackUniformSpec("mat4", UniformType.MAT4)
    private fun m3() = PackUniformSpec("mat3", UniformType.MAT3)
}

object LegacyUniformTranslator {
    fun uniforms(source: String): Set<String> = DECLARATION.findAll(source).flatMap { match ->
        val type = match.groupValues[1]
        splitDeclarators(match.groupValues[2]).asSequence().map { raw ->
            val name = DECLARATOR.matchEntire(raw.trim())?.groupValues?.get(1)
                ?: throw IllegalArgumentException("unsupported shader uniform '$type ${raw.trim()}'")
            PackUniformCatalog.specs[name]?.let { spec ->
                require(spec.glslType == type) { "$name is declared as $type; expected ${spec.glslType}" }
            }
            name
        }
    }.toSet()

    fun translate(source: String): String {
        val implicit = mutableSetOf<String>()
        if (Regex("""\bgl_Fog\.start\b""").containsMatchIn(source)) implicit += "fogStart"
        if (Regex("""\bgl_Fog\.(?:end|scale)\b""").containsMatchIn(source)) implicit += setOf("fogStart", "fogEnd")
        if (Regex("""\bgl_Fog\.color\b""").containsMatchIn(source)) implicit += "fogColor"
        val normalized = LegacyGlslSyntax.translate(source)
            .replace(Regex("""\bgl_Fog\.start\b"""), "fogStart")
            .replace(Regex("""\bgl_Fog\.end\b"""), "fogEnd")
            .replace(Regex("""\bgl_Fog\.scale\b"""), "(1.0 / max(fogEnd - fogStart, 0.0001))")
            .replace(Regex("""\bgl_Fog\.color\b"""), "vec4(fogColor, 1.0)")
        if (!DECLARATION.containsMatchIn(normalized) && implicit.isEmpty()) return normalized
        val declared = uniforms(normalized)
        val dependencies = mutableSetOf<String>()
        val rewritten = DECLARATION.replace(normalized) { match ->
            val type = match.groupValues[1]
            splitDeclarators(match.groupValues[2]).mapNotNull { raw ->
                val declarator = DECLARATOR.matchEntire(raw.trim())
                    ?: throw IllegalArgumentException("unsupported shader uniform '$type ${raw.trim()}'")
                val name = declarator.groupValues[1]
                if (name in PackUniformCatalog.specs) null else {
                    dependencies += fallbackDependencies(name)
                    val initializer = declarator.groupValues[2].trim().takeIf(String::isNotEmpty)
                        ?: fallback(type, name)
                    "$type $name = $initializer;"
                }
            }.joinToString("\n")
        }
        return PackUniformCatalog.block(declared + dependencies + implicit) + rewritten
    }

    /** Keep pack-defined uniforms local so one optional Iris expression cannot reject the pack. */
    private fun fallback(type: String, name: String): String = when (name) {
        "view_res" -> "vec2(viewWidth, viewHeight)"
        "view_pixel_size" -> "vec2(1.0 / max(viewWidth, 1.0), 1.0 / max(viewHeight, 1.0))"
        "view_up_dir" -> "normalize(upPosition)"
        "view_sun_dir" -> "normalize(sunPosition)"
        "view_moon_dir" -> "normalize(moonPosition)"
        "view_light_dir" -> "normalize(shadowLightPosition)"
        "sun_dir" -> "normalize(mat3(gbufferModelViewInverse) * sunPosition)"
        "moon_dir" -> "normalize(mat3(gbufferModelViewInverse) * moonPosition)"
        "light_dir" -> "normalize(mat3(gbufferModelViewInverse) * shadowLightPosition)"
        "modelViewMatrix", "dhModelView" -> "gbufferModelView"
        "dhModelViewInverse" -> "gbufferModelViewInverse"
        "projectionMatrix" -> "gbufferProjection"
        else -> when (type) {
            "float" -> "0.0"; "int" -> "0"; "bool" -> "false"
            "vec2" -> "vec2(0.0)"; "vec3" -> "vec3(0.0)"; "vec4" -> "vec4(0.0)"
            "ivec2" -> "ivec2(0)"; "ivec3" -> "ivec3(0)"; "ivec4" -> "ivec4(0)"
            "mat2" -> "mat2(1.0)"; "mat3" -> "mat3(1.0)"; "mat4" -> "mat4(1.0)"
            else -> error("unsupported shader uniform type '$type'")
        }
    }

    private fun fallbackDependencies(name: String): Set<String> = when (name) {
        "view_res", "view_pixel_size" -> setOf("viewWidth", "viewHeight")
        "view_up_dir" -> setOf("upPosition")
        "view_sun_dir" -> setOf("sunPosition")
        "view_moon_dir" -> setOf("moonPosition")
        "view_light_dir" -> setOf("shadowLightPosition")
        "sun_dir" -> setOf("gbufferModelViewInverse", "sunPosition")
        "moon_dir" -> setOf("gbufferModelViewInverse", "moonPosition")
        "light_dir" -> setOf("gbufferModelViewInverse", "shadowLightPosition")
        "modelViewMatrix", "dhModelView" -> setOf("gbufferModelView")
        "dhModelViewInverse" -> setOf("gbufferModelViewInverse")
        "projectionMatrix" -> setOf("gbufferProjection")
        else -> emptySet()
    }

    private fun splitDeclarators(source: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        source.forEachIndexed { index, char -> when (char) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ',' -> if (depth == 0) { parts += source.substring(start, index); start = index + 1 }
        } }
        parts += source.substring(start)
        return parts
    }

    private val DECLARATION = Regex(
        """\buniform\s+(?:(?:lowp|mediump|highp)\s+)?(float|int|bool|vec[234]|ivec[234]|mat[234])\s+([^;]+);""",
    )
    private val DECLARATOR = Regex("""([A-Za-z_]\w*)(?:\s*=\s*(.*))?""")
}
