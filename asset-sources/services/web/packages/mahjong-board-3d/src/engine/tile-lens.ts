import type * as THREE from 'three';
import reference from './hand-projection-reference.json';

/** Optical camera data stays separate from the physical model transform. */
export interface TileLens { zoom: number; distance: number }
export const WORLD_LENS: Readonly<TileLens> = Object.freeze({ zoom: 1, distance: 0 });

export function handLens(camera: THREE.PerspectiveCamera, depth: number): TileLens {
  const zoom = 1 / Math.tan(reference.handHorizontalFov * Math.PI / 360) / camera.projectionMatrix.elements[0];
  return { zoom, distance: (zoom - 1) * depth };
}

export function tileLens(object: THREE.Object3D): Readonly<TileLens> {
  for (let node: THREE.Object3D | null = object; node; node = node.parent)
    if (node.userData.tileLens) return node.userData.tileLens as TileLens;
  return WORLD_LENS;
}

export function mixLens(from: Readonly<TileLens>, to: Readonly<TileLens>, progress: number): TileLens {
  return { zoom: from.zoom + (to.zoom - from.zoom) * progress,
    distance: from.distance + (to.distance - from.distance) * progress };
}

/** Project through the optical camera, retaining physical depth for world compositing. */
export function projectTilePoint(point: THREE.Vector3, camera: THREE.PerspectiveCamera,
  lens: Readonly<TileLens> = WORLD_LENS): THREE.Vector3 {
  point.applyMatrix4(camera.matrixWorldInverse);
  const depth = point.clone().applyMatrix4(camera.projectionMatrix).z;
  point.z -= lens.distance;
  point.applyMatrix4(camera.projectionMatrix);
  point.x *= lens.zoom; point.y *= lens.zoom; point.z = depth;
  return point;
}

export const TILE_LENS_GLSL = /* glsl */`
vec4 satoriLensProject(mat4 projection, vec4 position, vec2 lens) {
    vec4 physical = projection * position;
    if (lens.x == 1.0 && lens.y == 0.0) return physical;
    position.z -= lens.y;
    vec4 optical = projection * position;
    optical.xy *= lens.x;
    optical.z = physical.z * optical.w / physical.w;
    return optical;
}
`;
