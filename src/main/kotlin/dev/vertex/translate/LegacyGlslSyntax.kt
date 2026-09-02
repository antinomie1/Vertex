package dev.vertex.translate

/** Small, provider-independent rewrites for GLSL 1.20 array syntax. */
object LegacyGlslSyntax {
    fun translate(source: String): String {
        var translated = source
        // GLSL 1.20 permits the array extent before the variable name. GLSL 330
        // keeps the extent on the declarator instead.
        translated = translated.replace(ARRAY_TYPE_DECLARATION) { match ->
            "${match.groupValues[1]} ${match.groupValues[3]}[${match.groupValues[2]}] ${match.groupValues[4]}"
        }
        // glslang accepts unsized constructors for a declared array and infers
        // the element count from the initializer.
        translated = translated.replace(ARRAY_CONSTRUCTOR) { match -> "${match.groupValues[1]}[]${match.groupValues[2]}" }
        // Compatibility profiles implicitly convert integer constants to uint;
        // Vulkan GLSL requires the initializer type to match exactly.
        translated = translated.replace(UINT_CONSTANT) { match -> "${match.groupValues[1]}uint(${match.groupValues[2]})${match.groupValues[3]}" }
        translated = translated.replace(UINT_LOOP_LIMIT) { match ->
            "${match.groupValues[1]}${match.groupValues[2]} ${match.groupValues[3]} ${match.groupValues[4]}u"
        }
        // `sampler` is a reserved word in Vulkan GLSL, although compatibility
        // profile packs commonly use it as an opaque function parameter name.
        translated = translated.replace(Regex("""\bsampler\b"""), "vertexSampler")

        // Storage images have the same backend limitation.  Keep their XY
        // slice usable for voxel effects instead of rejecting the complete
        // program; image writes are redirected to a 2D slice.
        val image3dNames = IMAGE_3D.findAll(translated).map { it.groupValues[2] }.toSet()
        if (image3dNames.isNotEmpty()) {
            translated = translated.replace(IMAGE_3D) { m -> "${m.groupValues[1]}${m.groupValues[2].replace("image3D", "image2D").replace("uimage3D", "uimage2D")} ${m.groupValues[3]}" }
            image3dNames.forEach { name ->
                val escaped = Regex.escape(name)
                translated = translated
                    .replace(Regex("""\bimageStore\s*\(\s*$escaped\s*,\s*([^,()]+)\.?(?:xyz|xy)?\s*,"""), "imageStore($name, $1.xy,")
                    .replace(Regex("""\bimageLoad\s*\(\s*$escaped\s*,\s*([^,()]+)\.?(?:xyz|xy)?\s*\)"""), "imageLoad($name, $1.xy)")
            }
        }

        // RenderPearl currently exposes only 2D sampled images.  A number of
        // otherwise portable packs use 3D LUT/noise textures; flatten those
        // into a tiled 2D image and route sampling through a small compatibility
        // helper.  The renderer uploads the depth slices side-by-side, while
        // the helper reconstructs the slice coordinate from the square volume
        // convention used by Iris packs.
        val sampler3dNames = SAMPLER_3D.findAll(translated).map { it.groupValues[2] }.toSet()
        if (sampler3dNames.isNotEmpty()) {
            translated = translated.replace(SAMPLER_3D) { m ->
                "${m.groupValues[1]}sampler2D ${m.groupValues[2]}"
            }
            sampler3dNames.forEach { name ->
                val escaped = Regex.escape(name)
                translated = translated
                    .replace(Regex("""\btextureLod\s*\(\s*$escaped\s*,"""), "vertexTexture3DLod($name,")
                    .replace(Regex("""\btexture\s*\(\s*$escaped\s*,"""), "vertexTexture3D($name,")
                    .replace(Regex("""\btexelFetch\s*\(\s*$escaped\s*,"""), "vertexTexelFetch3D($name,")
                    .replace(Regex("""\btextureSize\s*\(\s*$escaped\s*,"""), "vertexTextureSize3D($name,")
            }
            translated = inject3dHelpers(translated)
        }

        // GLSL compatibility profiles implicitly compare uints with integer
        // literals.  Vulkan requires both operands to have the same type.
        // Single-letter identifiers are routinely reused by unrelated float
        // functions in expanded includes, so only rewrite stable descriptive
        // uint names here.  Decimal literals are explicitly excluded.
        val uintNames = UINT_DECLARATION.findAll(translated)
            .map { it.groupValues[1] }.filter { it.length > 1 }.toSet()
        uintNames.forEach { name ->
            val escaped = Regex.escape(name)
            translated = translated
                .replace(Regex("""(\bfor\s*\(\s*int\s+[A-Za-z_]\w*\s*=\s*[^;]+;\s*[A-Za-z_]\w*\s*(?:<|<=|>|>=)\s*)$escaped\b""")) { m ->
                    "${m.groupValues[1]}int($name)"
                }
                .replace(Regex("""\b($escaped)\s*(==|!=|<=|>=|<|>)\s*(\d+)(?![\w.])""")) { m -> "${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}u" }
                .replace(Regex("""(?<![\w.])(\d+)\s*(==|!=|<=|>=|<|>)\s*($escaped)\b""")) { m -> "${m.groupValues[1]}u ${m.groupValues[2]} ${m.groupValues[3]}" }
                .replace(Regex("""\b($escaped)\s*=\s*(\d+)(?![\w.])""")) { m -> "${m.groupValues[1]} = ${m.groupValues[2]}u" }
        }
        return translated
    }

    private fun inject3dHelpers(source: String): String {
        val helpers = """
            |vec4 vertexTexture3D(sampler2D vertexSource, vec3 vertexCoord) {
            |    ivec2 vertexSize = textureSize(vertexSource, 0);
            |    float vertexLayers = max(1.0, floor(sqrt(float(vertexSize.x))));
            |    float vertexWidth = max(1.0, float(vertexSize.x) / vertexLayers);
            |    float vertexZ = clamp(vertexCoord.z, 0.0, 0.999999) * vertexLayers;
            |    float vertexSlice = floor(vertexZ);
            |    float vertexNext = min(vertexLayers - 1.0, vertexSlice + 1.0);
            |    float vertexMix = fract(vertexZ);
            |    vec2 vertexUv0 = vec2((vertexCoord.x * vertexWidth + vertexSlice * vertexWidth) / float(vertexSize.x), vertexCoord.y);
            |    vec2 vertexUv1 = vec2((vertexCoord.x * vertexWidth + vertexNext * vertexWidth) / float(vertexSize.x), vertexCoord.y);
            |    return mix(texture(vertexSource, vertexUv0), texture(vertexSource, vertexUv1), vertexMix);
            |}
            |vec4 vertexTexture3DLod(sampler2D vertexSource, vec3 vertexCoord, float vertexLod) { return vertexTexture3D(vertexSource, vertexCoord); }
            |vec4 vertexTexelFetch3D(sampler2D vertexSource, ivec3 vertexCoord, int vertexLod) {
            |    ivec2 vertexSize = textureSize(vertexSource, vertexLod);
            |    int vertexLayers = max(1, int(floor(sqrt(float(vertexSize.x)))));
            |    int vertexWidth = max(1, vertexSize.x / vertexLayers);
            |    return texelFetch(vertexSource, ivec2(vertexCoord.x + (vertexCoord.z % vertexLayers) * vertexWidth, vertexCoord.y), vertexLod);
            |}
            |ivec3 vertexTextureSize3D(sampler2D vertexSource, int vertexLod) {
            |    ivec2 vertexSize = textureSize(vertexSource, vertexLod);
            |    int vertexLayers = max(1, int(floor(sqrt(float(vertexSize.x)))));
            |    return ivec3(max(1, vertexSize.x / vertexLayers), vertexSize.y, vertexLayers);
            |}
        """.trimMargin()
        val version = Regex("""(?m)^#version[^\n]*\n""").find(source)
        return if (version != null) source.replaceRange(version.range.last + 1, version.range.last + 1, "$helpers\n") else "$helpers\n$source"
    }

    private val ARRAY_TYPE_DECLARATION = Regex(
        """\b(float|int|bool|vec[234]|ivec[234]|uvec[234]|mat[234])\s*\[\s*(\d+)\s*]\s+([A-Za-z_]\w*)\s*(=\s*)""",
    )
    private val ARRAY_CONSTRUCTOR = Regex(
        """\b(float|int|bool|vec[234]|ivec[234]|uvec[234]|mat[234])\s*\[\s*\d+\s*](\s*\()""",
    )
    private val UINT_CONSTANT = Regex(
        """(\bconst\s+uint\s+[A-Za-z_]\w*\s*=\s*)(\d+)(\s*;)""",
    )
    private val UINT_LOOP_LIMIT = Regex(
        """(\bfor\s*\(\s*uint\s+([A-Za-z_]\w*)\s*=\s*[^;]+;\s*)\2\s*(<|<=|>|>=)\s*(\d+)(?![\w.])""",
    )
    private val SAMPLER_3D = Regex("""(?m)(\b(?:uniform\s+|readonly\s+|writeonly\s+)?)(?:u|i)?sampler3D\s+([A-Za-z_]\w*)""")
    private val UINT_DECLARATION = Regex("""\buint\s+([A-Za-z_]\w*)\b""")
    private val IMAGE_3D = Regex("""(?m)(\b(?:uniform\s+|readonly\s+|writeonly\s+)?)(u?image3D)\s+([A-Za-z_]\w*)""")
}
