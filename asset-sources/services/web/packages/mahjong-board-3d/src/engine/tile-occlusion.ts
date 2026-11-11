import * as THREE from 'three';
import { TH, TD } from './board-consts';

export interface TileVisibility { ambient: Float32Array; direct: Float32Array }
export interface TileCollider { id: string; matrix: THREE.Matrix4 }
interface Collider extends TileCollider { inverse: number[]; center: number[]; radius: number; normal: THREE.Matrix3; extent:number[]; aligned:boolean; half:number[] }
interface RaySample { origin:number[]; direction:number[]; limit:number; hit:string|null }
const HALF = [.5, TH/2, TD/2];
// Local normal, UV right and UV up, matching RoundedBox's six material groups.
const FRAMES = [
  [[1,0,0],[0,0,-1],[0,1,0]], [[-1,0,0],[0,0,1],[0,1,0]],
  [[0,1,0],[1,0,0],[0,0,-1]], [[0,-1,0],[1,0,0],[0,0,1]],
  [[0,0,1],[1,0,0],[0,1,0]], [[0,0,-1],[-1,0,0],[0,1,0]],
];
function rayBox(origin: number[], direction: number[], limit: number, c: Collider): boolean {
  const x=c.center[0]-origin[0],y=c.center[1]-origin[1],z=c.center[2]-origin[2];
  const along=x*direction[0]+y*direction[1]+z*direction[2];
  if(along+c.radius<.012||along-c.radius>limit||x*x+y*y+z*z-along*along>c.radius*c.radius)return false;
  const m=c.inverse;
  let lo=.012,hi=limit;
  for(let axis=0;axis<3;axis++) {
    const p=c.aligned?origin[axis]-c.center[axis]:m[axis]*origin[0]+m[4+axis]*origin[1]+m[8+axis]*origin[2]+m[12+axis];
    const d=c.aligned?direction[axis]:m[axis]*direction[0]+m[4+axis]*direction[1]+m[8+axis]*direction[2];
    const half=c.aligned?c.extent[axis]:c.half[axis];
    if(Math.abs(d)<1e-9){if(Math.abs(p)>half)return false;continue;}
    let a=(-half-p)/d,b=(half-p)/d;if(a>b){const t=a;a=b;b=t;}
    lo=Math.max(lo,a);hi=Math.min(hi,b);if(hi<=lo)return false;
  }
  return true;
}
const distance=(a:Collider,b:Collider)=>Math.hypot(...a.center.map((v,i)=>v-b.center[i]))-a.radius-b.radius;

/** Cached presentation-space occlusion. No game state, screen-space readback or per-pixel tile loop. */
export class TileOcclusion {
  constructor(private readonly half = HALF) {}
  private colliders=new Map<string,Collider>();
  private values=new Map<string,TileVisibility[]>();
  private samples=new Map<string,RaySample[][]>();
  private lamp=new THREE.Vector3(Infinity,Infinity,Infinity);
  computed=0;
  update(poses:TileCollider[],lamp:THREE.Vector3):void {
    const HALF = this.half;
    const next=new Map<string,Collider>(),changed:Collider[]=[];
    for(const p of poses) {
      const previous=this.colliders.get(p.id);
      if(previous?.matrix.equals(p.matrix)){next.set(p.id,previous);continue;}
      const matrix=p.matrix.clone(),m=matrix.elements;
      const radius=Math.hypot(...HALF.map((v,i)=>v*Math.hypot(m[i*4],m[i*4+1],m[i*4+2])));
      const extent=[0,1,2].map(axis=>Math.abs(m[axis])*HALF[0]+Math.abs(m[axis+4])*HALF[1]+Math.abs(m[axis+8])*HALF[2]);
      const aligned=[0,1,2].every(col=>[0,1,2].filter(row=>Math.abs(m[col*4+row])>1e-6).length===1);
      const c={id:p.id,matrix,inverse:matrix.clone().invert().elements,center:[m[12],m[13],m[14]],radius,normal:new THREE.Matrix3().getNormalMatrix(matrix),extent,aligned,half:this.half};
      next.set(p.id,c);changed.push(c);if(previous)changed.push(previous);
    }
    for(const [id,c]of this.colliders)if(!next.has(id)){changed.push(c);this.values.delete(id);this.samples.delete(id);}
    const lightChanged=!this.lamp.equals(lamp);this.lamp.copy(lamp);
    this.computed=0;const changedIds=new Set(changed.map(c=>c.id));
    for(const c of next.values()) {
      if(!lightChanged&&this.values.has(c.id)&&!changed.some(other=>other.id===c.id||distance(c,other)<3))continue;
      const neighbors=[...next.values()].filter(other=>other!==c&&distance(c,other)<3);
      const previous=this.samples.get(c.id);
      const ownChanged=changedIds.has(c.id)||lightChanged;
      const samples=ownChanged||!previous?FRAMES.map(frame=>this.face(c,frame,lamp)):previous;
      const changedNeighbors=neighbors.filter(other=>changedIds.has(other.id));
      for(const face of samples)for(const ray of face){
        if(!ownChanged&&ray.hit&&!changedIds.has(ray.hit))continue;
        const candidates=ownChanged||ray.hit?neighbors:changedNeighbors;
        ray.hit=candidates.find(other=>rayBox(ray.origin,ray.direction,ray.limit,other))?.id??null;
      }
      this.samples.set(c.id,samples);
      this.values.set(c.id,samples.map(face=>{
        const ambient=new Float32Array(4),direct=new Float32Array(4);
        const side=samples.indexOf(face),normal=new THREE.Vector3(...FRAMES[side][0] as [number,number,number]).applyMatrix3(c.normal).normalize();
        const point=[0,0,0];for(const ray of face)for(let axis=0;axis<3;axis++)point[axis]+=ray.origin[axis]*.25;
        let visible=1;
        for(const other of neighbors){
          const dx=other.center[0]-point[0],dy=other.center[1]-point[1],dz=other.center[2]-point[2];
          const cosineNumerator=dx*normal.x+dy*normal.y+dz*normal.z;
          if(cosineNumerator<=0)continue;
          const d2=dx*dx+dy*dy+dz*dz;if(d2<1e-10){visible*=.3;continue;}
          if(d2>(other.radius+2.4)*(other.radius+2.4))continue;
          const d=Math.sqrt(d2),falloff=Math.max(0,1-Math.max(0,d-other.radius)/2.4);
          visible*=1-.70*other.radius*other.radius/(d2+other.radius*other.radius)*cosineNumerator/d*falloff*falloff;
        }
        for(let corner=0;corner<4;corner++){ambient[corner]=Math.max(.35,visible);direct[corner]=face[corner].hit?0:1;}
        return{ambient,direct};
      }));this.computed++;
    }
    this.colliders=next;
  }
  faceValues(id:string,side:number):TileVisibility {
    const value=this.values.get(id)?.[side];if(!value)throw new Error('tile_visibility_missing');return value;
  }
  private face(c:Collider,frame:number[][],lamp:THREE.Vector3):RaySample[] {
    const HALF = this.half;
    const [n,u,v]=frame;
    const normal=new THREE.Vector3(...n as [number,number,number]).applyMatrix3(c.normal).normalize();
    const samples:RaySample[]=[];
    for(let corner=0;corner<4;corner++) {
      const local=n.map((value,i)=>value*HALF[i]+u[i]*HALF[i]*(corner%2? .9:-.9)+v[i]*HALF[i]*(corner>1?.9:-.9));
      const point=new THREE.Vector3(...local as [number,number,number]).applyMatrix4(c.matrix).addScaledVector(normal,.008);
      const origin=point.toArray();
      const ray=lamp.clone().sub(point),length=ray.length();ray.divideScalar(length);
      samples.push({origin,direction:ray.toArray(),limit:length,hit:null});
    }
    return samples;
  }
}
