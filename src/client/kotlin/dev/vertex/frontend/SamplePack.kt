package dev.vertex.frontend

import com.mojang.blaze3d.platform.NativeImage
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
    val TERRAIN_VSH = """#version 120
varying vec4 color;
varying vec2 texcoord;
varying vec3 normal;
void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    color = gl_Color;
    normal = gl_NormalMatrix * gl_Normal;
}
"""

    val TERRAIN_FSH = """#version 120
uniform sampler2D texture;
varying vec4 color;
varying vec2 texcoord;
varying vec3 normal;
void main() {
    vec4 albedo = texture2D(texture, texcoord) * color;
    vec3 n = normalize(normal);
    // 轻量方向光漫反射：证明包着色器逻辑直接接管地形
    float diff = max(dot(n, normalize(vec3(0.2, 0.9, 0.3))), 0.0) * 0.25 + 0.75;
    vec3 col = albedo.rgb * diff;
    /* DRAWBUFFERS:0 */
    gl_FragData[0] = vec4(col, albedo.a);
}
"""
    val FSH = """#version 120
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;
uniform sampler2D depthtex2;
uniform sampler2D noisetex;
uniform sampler2D lut;
uniform sampler2D normalsTex;
varying vec2 texcoord;
void main() {
    vec3 c = texture2D(colortex0, texcoord).rgb;
    float g = dot(c, vec3(0.3, 0.59, 0.11));
    vec3 s = vec3(g * 1.25, g * 1.02, g * 0.72);
    vec3 o = mix(c, s, 0.15);
    float d = texture2D(depthtex0, texcoord).r;
    d = min(d, min(texture2D(depthtex1, texcoord).r, texture2D(depthtex2, texcoord).r));
    d += texture2D(noisetex, texcoord * 16.0).r * 0.000001;
    d += texture2D(lut, texcoord).r * 0.000001;
    o = mix(o, vec3(0.55, 0.75, 1.0), smoothstep(0.006, 0.05, d) * 0.9);
    vec3 n = texture2D(normalsTex, texcoord).rgb * 2.0 - 1.0;
    float li = dot(normalize(n), normalize(vec3(0.35, 0.7, 0.45))) * 0.5 + 0.5;
    o *= 0.55 + 0.75 * li;
    /* DRAWBUFFERS:0 */
    gl_FragData[0] = vec4(o, 1.0);
}
"""
    val FSH_2 = """#version 120
uniform sampler2D colortex0;
varying vec2 texcoord;
const int colortex0Format = RGBA16F;
const int colortex1Format = RGBA16F;
const vec4 colortex1ClearColor = vec4(0.1, 0.2, 0.3, 1.0);
void main() {
    vec3 c = texture2D(colortex0, texcoord).rgb;
    /* DRAWBUFFERS:01 */
    gl_FragData[0] = vec4(pow(c, vec3(0.98)), 1.0);
    gl_FragData[1] = vec4(c, 1.0);
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
        Files.writeString(root.resolve("composite1.vsh"), VSH)
        Files.writeString(root.resolve("composite1.fsh"), FSH_2)
        Files.writeString(root.resolve("shaders.properties"),
            "flip.composite1.colortex1=false\ntexture.composite.lut=/lut.png\n")
        NativeImage(2, 2, false).use { image ->
            image.fillRect(0, 0, 2, 2, -1)
            image.writeToFile(root.resolve("lut.png"))
        }
        val tvsh = root.resolve("gbuffers_terrain.vsh")
        val tfsh = root.resolve("gbuffers_terrain.fsh")
        Files.writeString(tvsh, TERRAIN_VSH)
        Files.writeString(tfsh, TERRAIN_FSH)
        return dir.resolve(DIR_NAME)
    }
}
