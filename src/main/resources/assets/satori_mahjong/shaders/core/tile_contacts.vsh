#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <satori_mahjong:tile_lens.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
out vec4 vertexColor;
out vec2 texCoord;

void main() {
    vec2 lens = vec2(float(UV2.x & 65535) / 1024.0, float((UV2.y & 65535) - 32768) / 1024.0);
    gl_Position = satoriLensProject(ProjMat, ModelViewMat * vec4(Position, 1.0), lens);
    vertexColor = Color * ColorModulator;
    texCoord = UV0;
}
