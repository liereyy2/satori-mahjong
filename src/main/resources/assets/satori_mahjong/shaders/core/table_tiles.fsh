#version 330
#moj_import <satori_mahjong:tile_resin.glsl>
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec4 vertexColor;
in vec2 texCoord;
in vec3 viewNormal;
in vec3 viewPosition;
in float printedSurface;
in vec3 tileLightVector;
in vec4 ambientVisibility;
in vec4 directVisibility;
in vec3 studioUp;
out vec4 fragColor;

void main() {
    // Integrate a small pixel footprint so fine SVG strokes stay steady at oblique angles.
    vec2 dx = dFdx(texCoord) * 0.25;
    vec2 dy = dFdy(texCoord) * 0.25;
    vec4 sampleColor = (texture(Sampler0, texCoord - dx - dy)
                     + texture(Sampler0, texCoord + dx - dy)
                     + texture(Sampler0, texCoord - dx + dy)
                     + texture(Sampler0, texCoord + dx + dy)) * 0.25;
    vec4 color = sampleColor * vertexColor;
    if (color.a < 0.1) discard;
    vec4 surface = texture(Sampler1,texCoord);
    vec3 normal = normalize(viewNormal);
    vec2 visibility = vec2(satoriCornerVisibility(ambientVisibility,vec2(texCoord.x,1.0-texCoord.y)),
                          satoriCornerVisibility(directVisibility,vec2(texCoord.x,1.0-texCoord.y)));
    vec3 lit = satoriTileResin(satoriSrgbToLinear(color.rgb), normal, -viewPosition, tileLightVector, studioUp, printedSurface, surface, visibility);
    fragColor = vec4(satoriLinearToSrgb(lit), color.a);
}
