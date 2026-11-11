package cn.satori.mahjong.protocol;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Optional;

/** A contact footprint derived from the current presentation pose, never from game state. */
public final class TileContactShadow {
    public static final float SURFACE = 1.0008f;

    public record Footprint(float minX, float minZ, float maxX, float maxZ, float opacity) {}

    private TileContactShadow() {}

    public static Optional<Footprint> footprint(TableScene.Tile tile) {
        if (!TileGeometry.isTileBody(tile)) return Optional.empty();
        // The renderer applies the immutable presentation frame to the footprint once.
        var transform = TileGeometry.transform(tile.projected(null), 0);
        var lamp = new Vector3f(0, 1.003f + TileLighting.height(), 0);
        if (tile.viewTransform() != null)
            new Matrix4f(tile.viewTransform().matrix()).invert().transformPosition(lamp);
        float minX = Float.POSITIVE_INFINITY, minZ = minX, bottom = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxZ = maxX, top = maxX;
        for (int corner = 0; corner < 8; corner++) {
            var point = transform.transformPosition(TileOutline.corner(corner));
            float factor = (lamp.y - 1) / (lamp.y - point.y);
            float sx = lamp.x + (point.x - .5f - lamp.x) * factor;
            float sz = lamp.z + (point.z - .5f - lamp.z) * factor;
            minX = Math.min(minX, sx);
            maxX = Math.max(maxX, sx);
            minZ = Math.min(minZ, sz);
            maxZ = Math.max(maxZ, sz);
            bottom = Math.min(bottom, point.y);
            top = Math.max(top, point.y);
        }
        float height = bottom - 1;
        // Emerging wall tiles beneath the shutter cast no shadow through the closed tabletop.
        if (height < -.003f || top >= lamp.y - .001f) return Optional.empty();
        float spread = TileGeometry.WIDTH * .045f + Math.max(0, height) * .10f;
        float opacity = .26f * (float) Math.exp(-Math.max(0, height) / TileGeometry.WIDTH * .9f);
        return Optional.of(
                new Footprint(minX - spread, minZ - spread, maxX + spread, maxZ + spread, opacity));
    }
}
