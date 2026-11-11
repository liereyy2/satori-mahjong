/** Export the actual Web 3D tile mesh and material canvases for the native client. */
import { createRequire } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { chromium } from 'playwright';
import { appearance, appearanceBundle, appearanceSource, appearanceSourceSha256, bundleWebModule } from './web-tile-appearance.mjs';
const root = fileURLToPath(new URL('../', import.meta.url));
const sourceRoot = path.join(root, 'asset-sources');
const require = createRequire(import.meta.url);
const { RoundedBoxGeometry } = await import(pathToFileURL(require.resolve('three/examples/jsm/geometries/RoundedBoxGeometry.js')));
const source = 'services/web/packages/mahjong-board-3d/src/engine/';
const constants = await readFile(path.join(sourceRoot, source, 'board-consts.ts'), 'utf8');
const number = name => Number(constants.match(new RegExp(`export const ${name} = ([0-9.]+);`))[1]);
const [width,height,depth] = ['TW','TH','TD'].map(number);
const segments = number('TILE_CORNER_SEGMENTS');
const radius = number('TILE_CORNER_RADIUS');
const geometry = new RoundedBoxGeometry(width,height,depth,segments,radius);
const faces = geometry.groups.map(({start,count}) => {
 const vertices=[];
 for(let i=start;i<start+count;i+=3){
  const triangle=[];
  for(let j=i;j<i+3;j++) triangle.push([
   geometry.attributes.position.getX(j),geometry.attributes.position.getY(j),geometry.attributes.position.getZ(j),
   geometry.attributes.normal.getX(j),geometry.attributes.normal.getY(j),geometry.attributes.normal.getZ(j),
   geometry.attributes.uv.getX(j),1-geometry.attributes.uv.getY(j),
  ]);
  vertices.push(...triangle,triangle[2]); // Native QUADS: preserve each actual Web triangle.
 }
 return vertices;
});
const resources = path.join(root,'src/main/resources/assets/satori_mahjong');
await mkdir(path.join(resources,'models'),{recursive:true});
await writeFile(path.join(resources,'models/web_tile_mesh.json'),JSON.stringify({width,height,depth,radius,segments,faces})+'\n');
await writeFile(path.join(resources,'models/hand_projection.json'), await readFile(path.join(sourceRoot, source, 'hand-projection-reference.json')));
await mkdir(path.join(resources,'shaders/include'),{recursive:true});
await writeFile(path.join(resources,'shaders/include/tile_resin.glsl'),appearance.TILE_RESIN_GLSL);
await writeFile(path.join(resources,'shaders/include/tile_direction.glsl'),appearance.TILE_DIRECTION_GLSL);
const lensSource = source + 'tile-lens.ts';
const lens = await bundleWebModule(lensSource);
await writeFile(path.join(resources,'shaders/include/tile_lens.glsl'),lens.module.TILE_LENS_GLSL);
await writeFile(path.join(resources,'models/tile_appearance.json'),JSON.stringify({source:appearanceSource,
 sourceSha256:appearanceSourceSha256,shaderSha256:createHash('sha256').update(appearance.TILE_RESIN_GLSL).digest('hex'),
 lensSource,lensSourceSha256:lens.sourceSha256,lensShaderSha256:createHash('sha256').update(lens.module.TILE_LENS_GLSL).digest('hex'),
 surfaceSource:source+'tile-surface.ts',surfaceSourceSha256:createHash('sha256').update(await readFile(path.join(sourceRoot,source,'tile-surface.ts'))).digest('hex'),
 surface:appearance.TILE_SURFACE,directionShaderSha256:createHash('sha256').update(appearance.TILE_DIRECTION_GLSL).digest('hex'),palette:appearance.TILE_PALETTE,lighting:appearance.TILE_LIGHTING},null,2)+'\n');
const folder=path.join(resources,'textures/block/furniture');
process.env.TMPDIR=path.join(root,'tmp/minecraft-assets');
await mkdir(process.env.TMPDIR,{recursive:true});
const browser=await chromium.launch({headless:true});
try {
 const page=await browser.newPage();
 await page.addScriptTag({content:appearanceBundle});
 for(const [name,axis,low] of [['web_tile_px','u',true],['web_tile_nx','u',false],['web_tile_py','v',true],['web_tile_ny','v',false],['web_tile_back',null,false],['web_tile_blank','front',false]]) {
  const data=await page.evaluate(({axis,low})=>{
   const c=document.createElement('canvas');c.width=c.height=64;const g=c.getContext('2d');
   if(axis===null) TileAppearance.paintTileBack(c);
   else if(axis==='front') TileAppearance.paintTileFace(c);
   else TileAppearance.paintTileSide(c,axis,low);
   return c.toDataURL('image/png');
  },{axis,low});
  await writeFile(path.join(folder,name+'.png'),Buffer.from(data.split(',')[1],'base64'));
  const surface=await page.evaluate(({axis})=>{const c=document.createElement('canvas');c.width=c.height=64;
   let face; if(axis==='front'){face=document.createElement('canvas');face.width=face.height=64;TileAppearance.paintTileFace(face);}
   TileAppearance.paintTileSurface(c,face);return c.toDataURL('image/png');},{axis});
  await writeFile(path.join(folder,name+'_surface.png'),Buffer.from(surface.split(',')[1],'base64'));
  await writeFile(path.join(folder,name+'_surface.png.mcmeta'),JSON.stringify({texture:{blur:true,clamp:true}},null,2)+'\n');
  await writeFile(path.join(folder,name+'.png.mcmeta'), JSON.stringify({ texture: { blur: true, clamp: true } }, null, 2) + '\n');
 }
} finally {await browser.close();}
console.log(`Exported Web RoundedBox ${width} × ${height} × ${depth}, radius ${radius}, ${faces.flat().length} native vertices.`);
