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
        "rainfall" to f(), "temperature" to f(), "frameCounter" to i(), "worldTime" to i(),
        "worldDay" to i(), "moonPhase" to i(), "isEyeInWater" to i(), "hideGUI" to i(), "biome" to i(),
        "biome_category" to i(), "biome_precipitation" to i(), "cameraPosition" to v3(),
        "previousCameraPosition" to v3(), "sunPosition" to v3(), "moonPosition" to v3(),
        "shadowLightPosition" to v3(), "upPosition" to v3(), "skyColor" to v3(),
        "eyeBrightness" to iv2(), "eyeBrightnessSmooth" to iv2(), "terrainTextureSize" to iv2(),
        "atlasSize" to iv2(), "taaOffset" to v2(), "entityColor" to v4(),
        "gbufferModelView" to m4(), "gbufferModelViewInverse" to m4(), "gbufferPreviousModelView" to m4(),
        "gbufferProjection" to m4(), "gbufferProjectionInverse" to m4(), "gbufferPreviousProjection" to m4(),
        "shadowModelView" to m4(), "shadowModelViewInverse" to m4(),
        "shadowProjection" to m4(), "shadowProjectionInverse" to m4(),
    )

    val layout: UniformLayout = UniformLayoutBuilder(256).also { builder ->
        specs.forEach { (name, spec) -> builder.add(name, spec.storageType) }
    }.build()

    val block: String = buildString {
        appendLine("layout(std140) uniform VertexPackUniforms {")
        specs.forEach { (name, spec) -> appendLine("    ${spec.glslType} $name;") }
        appendLine("};")
    }

    private fun f() = PackUniformSpec("float", UniformType.FLOAT)
    private fun i() = PackUniformSpec("int", UniformType.INT)
    private fun v2() = PackUniformSpec("vec2", UniformType.VEC2)
    private fun iv2() = PackUniformSpec("ivec2", UniformType.IVEC2)
    private fun v3() = PackUniformSpec("vec3", UniformType.VEC3)
    private fun v4() = PackUniformSpec("vec4", UniformType.VEC4)
    private fun m4() = PackUniformSpec("mat4", UniformType.MAT4)
}

object LegacyUniformTranslator {
    fun uniforms(source: String): Set<String> = DECLARATION.findAll(source).map { match ->
        val type = match.groupValues[1]
        val name = match.groupValues[2]
        val spec = PackUniformCatalog.specs[name]
            ?: throw IllegalArgumentException("unsupported shader uniform '$type $name'")
        require(spec.glslType == type) { "$name is declared as $type; expected ${spec.glslType}" }
        name
    }.toSet()

    fun translate(source: String): String {
        if (uniforms(source).isEmpty()) return source
        return PackUniformCatalog.block + DECLARATION.replace(source, "")
    }

    private val DECLARATION = Regex(
        """\buniform\s+(float|int|bool|vec[234]|ivec[234]|mat[234])\s+(\w+)\s*;""",
    )
}
