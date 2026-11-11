import {bundleWebModule} from './web-tile-appearance.mjs';
import {writeFileSync,mkdirSync} from 'node:fs';
import {createRequire} from 'node:module';
import {fileURLToPath,pathToFileURL} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('../',import.meta.url));
const require=createRequire(import.meta.url);
const output=path.join(root,'src/test/resources/tile-occlusion-fixtures.json');
mkdirSync(path.dirname(output),{recursive:true});
const {module:{TileOcclusion}}=await bundleWebModule('services/web/packages/mahjong-board-3d/src/engine/tile-occlusion.ts');
const {Matrix4,Vector3}=await import(pathToFileURL(require.resolve('three')));
const cases=[
 {name:'single',poses:[new Matrix4().makeTranslation(0,.67,0)]},
 {name:'neighbors',poses:[new Matrix4().makeTranslation(0,.67,0),new Matrix4().makeTranslation(1.01,.67,0)]},
 {name:'stack',poses:[new Matrix4().makeTranslation(0,.415,0).multiply(new Matrix4().makeRotationX(-Math.PI/2)),new Matrix4().makeTranslation(0,1.245,0).multiply(new Matrix4().makeRotationX(-Math.PI/2))]},
 {name:'rotated',poses:[new Matrix4().makeTranslation(0,.67,0).multiply(new Matrix4().makeRotationY(.3)),new Matrix4().makeTranslation(.8,.67,.4).multiply(new Matrix4().makeRotationY(.3))]},
];
for(const c of cases){
 const cache=new TileOcclusion();const poses=c.poses.map((matrix,i)=>({id:String(i),matrix}));cache.update(poses,new Vector3(0,18,0));
 c.results=poses.map(p=>Array.from({length:6},(_,side)=>{const v=cache.faceValues(p.id,side);return{ambient:Array.from(v.ambient),direct:Array.from(v.direct)}}));
 c.poses=c.poses.map(m=>m.elements);cache.update(poses,new Vector3(0,18,0));if(cache.computed!==0)throw new Error('static cache recalculated');
}
writeFileSync(output,JSON.stringify(cases,null,2)+'\n');
console.log('Wrote independent native parity fixtures from shared Web occlusion math.');
