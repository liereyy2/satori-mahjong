#version 330
in vec4 vertexColor;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 corner = max(abs(texCoord * 2.0 - 1.0) - vec2(0.65), vec2(0.0));
    float coverage = 1.0 - smoothstep(0.0, 0.35, length(corner));
    fragColor = vec4(vertexColor.rgb, vertexColor.a * coverage);
}
