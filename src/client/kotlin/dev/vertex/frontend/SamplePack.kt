package dev.vertex.frontend

import java.nio.file.Files
import java.nio.file.Path

/** 确保 run/shaderpacks 下存在一个"故意旧语法"的测试包（无网络依赖）。 */
object SamplePack {
    const val DIR_NAME = "vertex-test"
    val VSH = """#version 120
varying vec2 texcoord;
void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
}
"""
    val FSH = """#version 120
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
varying vec2 texcoord;
void main() {
    float d = texture2D(depthtex0, texcoord).r;
    o = mix(o, vec3(0.55, 0.75, 1.0), smoothstep(0.008, 0.05, d) * 0.85);
    /* DRAWBUFFERS:0 */
    gl_FragData[0] = vec4(o, 1.0);
}
"""

    fun ensure(dir: Path): Path {
        val root = dir.resolve(DIR_NAME).resolve("shaders")
        Files.createDirectories(root)
        val vsh = root.resolve("composite.vsh")
        val fsh = root.resolve("composite.fsh")
        // 测试包由本模组生成：每次启动强制覆盖，保证与当前版本一致
        Files.writeString(vsh, VSH)
        Files.writeString(fsh, FSH)
        return dir.resolve(DIR_NAME)
    }
}
