#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <satori_mahjong:tile_lens.glsl>
#moj_import <satori_mahjong:tile_direction.glsl>

in vec3 Position;
in vec2 UV0;
in vec3 Normal;
in vec4 Color;

layout(std140) uniform TileInstances {
    mat4 TileModelView[128];
    mat4 TileNormal[128];
};
layout(std140) uniform TileVisibility {
    vec4 TileAmbient[128];
    vec4 TileDirect[128];
};

out vec4 vertexColor;
out vec2 texCoord;
out vec3 viewNormal;
out vec3 viewPosition;
out float printedSurface;
out vec3 tileLightVector;
out vec4 ambientVisibility;
out vec4 directVisibility;
out vec3 studioUp;

void main() {
    mat4 modelView = ModelViewMat * TileModelView[gl_InstanceID];
    vec4 position = modelView * vec4(Position, 1.0);
    vec2 lens = vec2(TileNormal[gl_InstanceID][0][3], TileNormal[gl_InstanceID][1][3]);
    gl_Position = satoriLensProject(ProjMat, position, lens);
    vec3 lamp = (ModelViewMat * vec4(TileNormal[gl_InstanceID][3].xyz, 1.0)).xyz;
    tileLightVector = lamp - position.xyz;
    position.z -= lens.y;
    viewPosition = position.xyz;
    viewNormal = mat3(ModelViewMat) * mat3(TileNormal[gl_InstanceID]) * Normal;
    vertexColor = ColorModulator;
    printedSurface = Color.r * abs(Normal.z);
    texCoord = UV0;
    ambientVisibility = TileAmbient[gl_InstanceID];
    directVisibility = TileDirect[gl_InstanceID];
    studioUp = mat3(ModelViewMat)*satoriDecodeDirection(vec2(TileNormal[gl_InstanceID][2][3],TileNormal[gl_InstanceID][3][3]));
}
