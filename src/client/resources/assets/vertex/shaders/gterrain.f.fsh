#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
uniform sampler2D Sampler0;
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 t = texture(Sampler0, texCoord0) * vertexColor;
    fragColor = vec4(mix(t.rgb, vec3(0.85, 0.06, 0.06), 0.55), t.a);
}
