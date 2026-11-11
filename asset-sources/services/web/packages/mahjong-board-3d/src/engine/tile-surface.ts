/** Linear material data: R roughness, G coating coverage, B=0, A=1. */
export const TILE_SURFACE = {
    shellRoughness: .30,
    faceRoughness: .40,
    inkRoughness: .60,
    shellCoat: .35,
    faceCoat: .25,
    inkCoat: .006,
};

/** Ink coverage selects paint properties; a printed color is never a geometric height. */
export function paintTileSurface(canvas: HTMLCanvasElement, face?: HTMLCanvasElement): void {
    const context = canvas.getContext('2d')!;
    const width = canvas.width, height = canvas.height;
    const output = context.createImageData(width, height);
    let pixels: Uint8ClampedArray | undefined;
    if (face) {
        const sampled = document.createElement('canvas');
        sampled.width = width;
        sampled.height = height;
        const source = sampled.getContext('2d')!;
        source.drawImage(face, 0, 0, width, height);
        pixels = source.getImageData(0, 0, width, height).data;
    }
    for (let index = 0; index < width * height; index++) {
        const offset = index * 4;
        const coverage = pixels ? Math.max(0, Math.min(1,
            (228 - Math.min(pixels[offset], pixels[offset + 1], pixels[offset + 2])) / 110)) : 0;
        const roughness = face ? TILE_SURFACE.faceRoughness
            + (TILE_SURFACE.inkRoughness - TILE_SURFACE.faceRoughness) * coverage
            : TILE_SURFACE.shellRoughness;
        const coating = face ? TILE_SURFACE.faceCoat
            + (TILE_SURFACE.inkCoat - TILE_SURFACE.faceCoat) * coverage
            : TILE_SURFACE.shellCoat;
        output.data[offset] = Math.round(roughness * 255);
        output.data[offset + 1] = Math.round(coating * 255);
        output.data[offset + 2] = 0;
        output.data[offset + 3] = 255;
    }
    context.putImageData(output, 0, 0);
}
