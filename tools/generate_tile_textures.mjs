/** Rasterize the online tile-kit SVGs with MahjongBoard3D's canvas geometry. */
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { chromium } from 'playwright';
import { appearanceBundle, appearanceSource, appearanceSourceSha256 } from './web-tile-appearance.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const source = 'services/web/packages/tile-kit/src/assets';
const output = path.join(root, 'src/main/resources/assets/satori_mahjong/textures/tiles');
const names = [
  ...['m', 'p', 's'].flatMap(suit => Array.from({ length: 9 }, (_, i) => `${i + 1}${suit}`)),
  ...Array.from({ length: 7 }, (_, i) => `${i + 1}z`),
  '0m', '0p', '0s', 'blank',
];
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
await mkdir(output, { recursive: true });
const temporary = path.join(root, 'tmp/minecraft-assets');
await mkdir(temporary, { recursive: true });
process.env.TMPDIR = temporary;
const browser = await chromium.launch({ headless: true });
const tiles = [];
try {
  const page = await browser.newPage();
  await page.addScriptTag({ content: appearanceBundle });
  for (const [id, name] of names.entries()) {
    const svg = await readFile(path.join(root, 'asset-sources', source, `${name}.svg`));
    const images = await page.evaluate(async ({ svg }) => {
      const image = new Image();
      image.src = `data:image/svg+xml;base64,${svg}`;
      await image.decode();
      // Match board-tiles.ts loadTextures: raw face canvas 384 × 528, padding 32.
      const canvas = document.createElement('canvas');
      canvas.width = 384;
      canvas.height = 528;
      const ctx = canvas.getContext('2d');
      TileAppearance.paintTileFace(canvas, image);
      const plain = canvas.toDataURL('image/png');
      TileAppearance.paintTileVoid(canvas);
      const marked=canvas.toDataURL('image/png');
      const surface=document.createElement('canvas');surface.width=192;surface.height=264;
      TileAppearance.paintTileSurface(surface,canvas);const markedSurface=surface.toDataURL('image/png');
      TileAppearance.paintTileFace(canvas,image);TileAppearance.paintTileSurface(surface,canvas);
      return {plain,marked,surface:surface.toDataURL('image/png'),markedSurface};
    }, { svg: svg.toString('base64') });
    const png = Buffer.from(images.plain.split(',')[1], 'base64');
    await writeFile(path.join(output, `${id}.png`), png);
    await writeFile(path.join(output, `${id}.png.mcmeta`),
      JSON.stringify({ texture: { blur: true, clamp: true } }, null, 2) + '\n');
    const surface=Buffer.from(images.surface.split(',')[1],'base64');
    await writeFile(path.join(output,`${id}_surface.png`),surface);
    await writeFile(path.join(output,`${id}_surface.png.mcmeta`),JSON.stringify({texture:{blur:true,clamp:true}},null,2)+'\n');
    if (id < 27) {
      await mkdir(path.join(output, 'void'), { recursive: true });
      await writeFile(path.join(output, 'void', `${id}.png`), Buffer.from(images.marked.split(',')[1], 'base64'));
      await writeFile(path.join(output,'void',`${id}_surface.png`),Buffer.from(images.markedSurface.split(',')[1],'base64'));
      await writeFile(path.join(output,'void',`${id}_surface.png.mcmeta`),JSON.stringify({texture:{blur:true,clamp:true}},null,2)+'\n');
      await writeFile(path.join(output, 'void', `${id}.png.mcmeta`), JSON.stringify({ texture: { blur: true, clamp: true } }, null, 2) + '\n');
    }
    tiles.push({ id, surfaceTexture:`${id}_surface.png`,surfaceSha256:hash(surface),source: `${source}/${name}.svg`, sourceSha256: hash(svg),
      texture: `${id}.png`, textureSha256: hash(png), concealed: id === 37 });
  }
  await writeFile(path.join(output, 'manifest.json'), JSON.stringify({
    schema: 3, surfaceEncoding:'roughness-coating-linear',surfaceWidth:192,surfaceHeight:264,appearance: 'shared resin face', appearanceSource, appearanceSourceSha256, width: 384, height: 528, padding: 32,
    renderer: `Chromium ${browser.version()}`, tiles,
  }, null, 2) + '\n');
} finally {
  await browser.close();
}
console.log(`Generated ${tiles.length} tile textures from the online SVG source.`);
