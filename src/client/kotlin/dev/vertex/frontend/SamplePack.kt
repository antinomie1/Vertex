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
    vec3 c = texture2D(colortex0, texcoord).rgb;
    float g = dot(c, vec3(0.3, 0.59, 0.11));
    vec3 s = vec3(g * 1.25, g * 1.02, g * 0.72);
    vec3 o = mix(c, s, 0.7);
    float d = texture2D(depthtex0, texcoord).r;
    o = mix(o, vec3(0.55, 0.75, 1.0), pow(d, 16.0) * 0.85);
    /* DRAWBUFFERS:0 */
    gl_FragData[0] = vec4(o, 1.0);
}
"""

    fun ensure(dir: Path): Path {
        val root = dir.resolve(DIR_NAME).resolve("shaders")
        Files.createDirectories(root)
        val vsh = root.resolve("composite.vsh")
        val fsh = root.resolve("composite.fsh")
        if (!Files.exists(vsh)) Files.writeString(vsh, VSH)
        if (!Files.exists(fsh)) Files.writeString(fsh, FSH)
        return dir.resolve(DIR_NAME)
    }
}
