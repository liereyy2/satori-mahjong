#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <satori_mahjong:tile_direction.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec3 Normal;
in ivec2 UV1;
in ivec2 UV2;
out vec4 vertexColor;
out vec2 texCoord;
out vec3 viewNormal;
out vec3 viewPosition;
out float printedSurface;
out vec3 tileLightVector;
out vec4 ambientVisibility;
out vec4 directVisibility;
out vec3 studioUp;

float lightCoordinate(int bits) {
    int exponent = (bits >> 10) & 31;
    float mantissa = float(bits & 1023) / 1024.0;
    float value = exponent == 0 ? exp2(-14.0) * mantissa
                               : exp2(float(exponent - 15)) * (1.0 + mantissa);
    return (bits & 32768) == 0 ? value : -value;
}

void main() {
    vec4 position = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * position;
    vec3 lamp = (ModelViewMat * vec4(vec3(lightCoordinate(UV1.x), lightCoordinate(UV1.y), lightCoordinate(UV2.x)), 1.0)).xyz;
    tileLightVector = lamp - position.xyz;
    viewPosition = position.xyz;
    viewNormal = mat3(ModelViewMat) * Normal;
    vertexColor = Color * ColorModulator;
    texCoord = UV0;
    printedSurface = 0.0;
    ambientVisibility = vec4(1.0);
    directVisibility = vec4(1.0);
    vec2 oct=vec2(float(UV2.y&255),float((UV2.y>>8)&255))/255.0*2.0-1.0;
    studioUp=mat3(ModelViewMat)*satoriDecodeDirection(oct);
}
