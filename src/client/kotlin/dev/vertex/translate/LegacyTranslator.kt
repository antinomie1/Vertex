package dev.vertex.translate

import dev.vertex.frontend.LoadedProgram

/**
 * 旧语法→Vulkan GLSL 规则表 v0（DESIGN.md M2 的最小实现集）：
 * - varying → in（片元）
 * - texture2D → texture
 * - gl_FragData[0] = X → fragColor = X，并前置 layout(0) out 声明
 * - 顶点透传模式：整体替换为 gl_VertexIndex 规范形（输出包的 varying 名）
 */
object LegacyTranslator {

    fun vertex(program: LoadedProgram): String {
        val name = program.varyingName
            ?: throw IllegalStateException("无 varying，无法套用透传顶点")
        return """#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) out vec2 $name;

void main() {
    vec2 uv = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    gl_Position = vec4(uv * vec2(2, 2) - vec2(1, 1), 0.0, 1.0);
    $name = uv;
}
"""
    }

    fun fragment(program: LoadedProgram): String {
        return LegacyFragmentTranslator.translate(program.fragmentSource)
    }
    fun terrainVertex(program: LoadedProgram): String {
        return """#version 330
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
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 5) in ivec3 ChunkPosition;
layout(location = 6) in float ChunkVisibility;
layout(location = 7) in vec3 Normal;
#else
layout(location = 5) in vec3 Normal;
#endif

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;
layout(location = 5) out vec3 meshNormal;

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    meshNormal = normalize(Normal);

    const float chunkFullyVisibleRange = 16.0;
    #ifdef MULTIDRAW_TERRAIN
    float dist = length(pos);
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));
    #else
    chunkVisibility = 1.0;
    #endif
}
"""
    }

    fun terrainFragment(program: LoadedProgram): String {
        var body = program.fragmentSource
        body = body.replace(Regex("""^#version\s+\d+[^\n]*""", RegexOption.MULTILINE), "")
        body = body.replace(Regex("""^#extension[^\n]*""", RegexOption.MULTILINE), "")
        body = body.replace(Regex("""uniform\s+sampler2D\s+(texture|gtexture)\s*;"""), "")
        body = body.replace(Regex("""uniform\s+sampler2D\s+lightmap\s*;"""), "")
        body = body.replace(Regex("""varying\s+vec4\s+color\s*;"""), "")
        body = body.replace(Regex("""varying\s+vec2\s+texcoord\s*;"""), "")
        body = body.replace(Regex("""varying\s+vec3\s+normal\s*;"""), "")
        body = body.replace(Regex("""texture2D\s*\(\s*(texture|gtexture)\s*,\s*texcoord\s*\)"""), "((UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)))")
        body = body.replace(Regex("""texture2D\s*\("""), "texture(")
        body = body.replace(Regex("""\b(texture|gtexture)\b"""), "Sampler0")
        body = body.replace(Regex("""\blightmap\b"""), "Sampler2")
        body = body.replace(Regex("""\bcolor\b"""), "vertexColor")
        body = body.replace(Regex("""\btexcoord\b"""), "texCoord0")
        body = body.replace(Regex("""\bnormal\b"""), "meshNormal")
        body = body.replace(Regex("""gl_FragData\s*\[\s*0\s*\]\s*=\s*([^;]+);"""), "vec4 _outCol = $1;\n    #ifdef ALPHA_CUTOUT\n    if (_outCol.a < ALPHA_CUTOUT) discard;\n    #endif\n    _outCol = mix(FogColor * vec4(1, 1, 1, _outCol.a), _outCol, chunkVisibility);\n    fragColor = apply_fog(_outCol, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);")

        return """#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;
layout(location = 5) in vec3 meshNormal;
layout(location = 0) out vec4 fragColor;

""" + body.trimStart() + "\n"
    }
}
