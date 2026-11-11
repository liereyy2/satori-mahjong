
vec3 satoriSrgbToLinear(vec3 c) {
    return mix(pow((c + .055) / 1.055, vec3(2.4)), c / 12.92, lessThanEqual(c, vec3(.04045)));
}
vec3 satoriLinearToSrgb(vec3 c) {
    return mix(1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - .055,
               c * 12.92, lessThanEqual(c, vec3(.0031308)));
}
float satoriCornerVisibility(vec4 corners, vec2 uv) {
    return mix(mix(corners.x,corners.y,uv.x),mix(corners.z,corners.w,uv.x),uv.y);
}
vec3 satoriStudioEnvironment(vec3 reflected, vec3 ceiling, float roughness) {
    // Analytic prefilter of an axisymmetric ceiling softbox. No camera-fixed light.
    float sourceVariance=0.060;
    float variance=sourceVariance+roughness*roughness*.30;
    float lamp=3.2*sourceVariance/variance*exp((dot(reflected,ceiling)-1.0)/variance);
    float room=.018+.065*max(dot(reflected,ceiling),0.0);
    return vec3(room)+vec3(1.0,.985,.955)*lamp;
}
vec3 satoriIndirectVisibility(float visibility, vec3 albedo) {
    // Jimenez et al. 2016 multi-bounce AO; polynomial also documented by Filament:
    // https://github.com/google/filament/blob/main/shaders/src/surface_ambient_occlusion.fs
    vec3 reflected=albedo*(2.0404*visibility*visibility-4.7951*visibility+2.7552)
                  +vec3(-.3324*visibility*visibility+.6417*visibility+.6903);
    return clamp(reflected*visibility,vec3(visibility),vec3(1.0));
}
vec3 satoriTileResin(vec3 albedo, vec3 surfaceNormal, vec3 viewDirection,
                     vec3 lightVector, vec3 studioUp, float printedNormal, vec4 surface, vec2 visibility) {
    vec3 n=normalize(surfaceNormal),v=normalize(viewDirection),key=normalize(lightVector);
    float face=smoothstep(.65,.98,printedNormal);
    float facing=max(dot(n,key),0.0);
    float bounce=mix(0.610,0.820,face);
    float lit=mix(0.740,0.900,face);
    // Neighbors shadow the lamp separately from indirect light reflected by the room and tiles.
    vec3 incident=bounce*satoriIndirectVisibility(visibility.x,albedo)
                 +vec3((lit-bounce)*smoothstep(0.0,.85,facing)*visibility.y);
    float nv=max(dot(n,v),.001);
    float coating=surface.g;
    float f0=.04*coating;
    vec3 fresnel=coating*(vec3(.04)+vec3(.96)*pow(1.0-nv,5.0));
    float roughness=clamp(surface.r,.12,.75);
    vec3 environment=satoriStudioEnvironment(reflect(-v,n),normalize(studioUp),roughness);
    vec3 color=albedo*incident*(1.0-f0)
             + environment*fresnel*mix(visibility.x,visibility.y,.75);
    // Preserve hue while compressing only the specular shoulder; never flatten it into a white rim.
    float peak=max(max(color.r,color.g),color.b);
    float mapped=.85+.15*(1.0-exp(-max(peak-.85,0.0)/.15));
    return color*(peak>.85?mapped/peak:1.0);
}
