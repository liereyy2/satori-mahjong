/** Bundle the actual Web material painter for deterministic native assets and shader code. */
import { build } from 'esbuild';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const root = fileURLToPath(new URL('../asset-sources/', import.meta.url));
const nodeModules = fileURLToPath(new URL('./node_modules/', import.meta.url));
export const appearanceSource = 'services/web/packages/mahjong-board-3d/src/engine/tile-appearance.ts';
export async function bundleWebModule(source) {
  const sourceSha256 = createHash('sha256').update(await readFile(root + source)).digest('hex');
  const result = await build({ entryPoints: [root + source], bundle: true, write: false,
    format: 'iife', globalName: 'TileAppearance', logLevel: 'silent', nodePaths: [nodeModules] });
  const bundle = result.outputFiles[0].text;
  return { sourceSha256, bundle, module: new Function(bundle + '; return TileAppearance;')() };
}
const loaded = await bundleWebModule(appearanceSource);
export const appearanceSourceSha256 = loaded.sourceSha256;
export const appearanceBundle = loaded.bundle;
export const appearance = loaded.module;
