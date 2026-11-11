/** Shared studio resin material; generated unchanged for the native client. */
export { paintTileSurface, TILE_SURFACE } from './tile-surface';
export const TILE_PALETTE = {
  face: '#f4f1e7', ivory: '#f0ede3', ivoryDepth: '#e9e7dc',
  blue: '#507eac', blueDepth: '#3d648a', seam: '#586e7c',
};
// The printed face stays bright in both standing and flat poses; the resin shell is slightly shaded.
export const TILE_LIGHTING = { height: 18, shellBounce: .610, faceBounce: .820, shellSky: .740, faceSky: .900, sourceVariance: .060, sourceRadiance: 3.2 };

export const TILE_DIRECTION_GLSL = /* glsl */`vec3 satoriDecodeDirection(vec2 encoded) {
    vec3 n=vec3(encoded,1.0-abs(encoded.x)-abs(encoded.y));
    float t=max(-n.z,0.0);n.xy+=mix(vec2(t),vec2(-t),greaterThanEqual(n.xy,vec2(0.0)));
    return normalize(n);
}
`;

export const TILE_RESIN_GLSL = /* glsl */`
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
    float sourceVariance=${TILE_LIGHTING.sourceVariance.toFixed(3)};
    float variance=sourceVariance+roughness*roughness*.30;
    float lamp=${TILE_LIGHTING.sourceRadiance.toFixed(1)}*sourceVariance/variance*exp((dot(reflected,ceiling)-1.0)/variance);
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
    float bounce=mix(${TILE_LIGHTING.shellBounce.toFixed(3)},${TILE_LIGHTING.faceBounce.toFixed(3)},face);
    float lit=mix(${TILE_LIGHTING.shellSky.toFixed(3)},${TILE_LIGHTING.faceSky.toFixed(3)},face);
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
`;

export function paintTileFace(canvas: HTMLCanvasElement, image?: CanvasImageSource): void {
  const g = canvas.getContext('2d')!;
  g.fillStyle = TILE_PALETTE.face;
  g.fillRect(0, 0, canvas.width, canvas.height);
  if (image) g.drawImage(image, 32, 32, canvas.width - 64, canvas.height - 64);
}

/** Depth is measured from the white face to the blue backing, independently of UV orientation. */
export function paintTileSide(canvas: HTMLCanvasElement, axis: 'u' | 'v', whiteAtLow: boolean): void {
  const g = canvas.getContext('2d')!;
  const size = canvas.width;
  const gradient = axis === 'u' ? g.createLinearGradient(0, 0, size, 0) : g.createLinearGradient(0, size, 0, 0);
  const stops: [number, string][] = [
    [0, TILE_PALETTE.ivory], [.69, TILE_PALETTE.ivoryDepth],
    [.735, TILE_PALETTE.ivoryDepth], [.752, TILE_PALETTE.seam],
    [.769, TILE_PALETTE.blueDepth], [.87, TILE_PALETTE.blue], [1, TILE_PALETTE.blueDepth],
  ];
  for (const [position, color] of whiteAtLow ? stops : [...stops].reverse())
    gradient.addColorStop(whiteAtLow ? position : 1 - position, color);
  g.fillStyle = gradient;
  g.fillRect(0, 0, canvas.width, canvas.height);
}

export function paintTileBack(canvas: HTMLCanvasElement): void {
  const g = canvas.getContext('2d')!;
  const gradient = g.createLinearGradient(0, 0, canvas.width, canvas.height);
  gradient.addColorStop(0, TILE_PALETTE.blue);
  gradient.addColorStop(1, TILE_PALETTE.blueDepth);
  g.fillStyle = gradient;
  g.fillRect(0, 0, canvas.width, canvas.height);
}

export function paintTileVoid(canvas: HTMLCanvasElement): void {
  const g = canvas.getContext('2d')!;
  g.fillStyle = '#b13c34'; g.beginPath(); g.roundRect(28, 28, 84, 84, 12); g.fill();
  g.fillStyle = '#fff9e9'; g.font = 'bold 60px "Noto Sans CJK SC", sans-serif';
  g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText('缺', 70, 72);
}
