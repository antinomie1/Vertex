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
        var s = program.fragmentSource

        // 头部规范化
        s = s.replace(Regex("""^#version\s+\d+.*""", RegexOption.MULTILINE), "#version 330\n#extension GL_ARB_separate_shader_objects : require")
        // varying → in（带 location 由规则表统一补）
        s = s.replace(Regex("""varying\s+(\w+)\s+(\w+)\s*;"""), "layout(location = 0) in $1 $2;")
        // 采样器补 location 不需要；texture2D → texture
        s = s.replace(Regex("""texture2D\s*\(""") , "texture(")
        // gl_FragData[n] = expr → fragColor = expr（v0 仅支持 n==0）
        if (Regex("""gl_FragData\s*\[\s*[1-9]""").containsMatchIn(s))
            throw IllegalStateException("v0 仅支持 DRAWBUFFERS 单目标（gl_FragData[0]）")
        s = s.replace(Regex("""gl_FragData\s*\[\s*0\s*\]"""), "fragColor")
        if (!s.contains("fragColor")) throw IllegalStateException("未找到片元输出（gl_FragData[0]/fragColor）")
        if (!s.contains("layout(location = 0) out"))
            s = "layout(location = 0) out vec4 fragColor;\n" + s
        return s
    }
}
