package cn.satori.mahjong.protocol;

import org.joml.Vector3f;

/** An optical camera shift and focal multiplier; it never changes the physical model. */
public record TileLens(float zoom, float distance) {
    public static final TileLens WORLD = new TileLens(1, 0);

    public TileLens {
        if (!(zoom > 0) || !Float.isFinite(zoom + distance))
            throw new IllegalArgumentException("Invalid tile lens");
    }

    public static TileLens of(TableScene.Tile tile) {
        return tile.viewTransform() == null ? WORLD : tile.viewTransform().lens();
    }

    public TileLens blend(TileLens to, float progress) {
        return new TileLens(zoom + (to.zoom - zoom) * progress,
                distance + (to.distance - distance) * progress);
    }

    public Vector3f projectNdc(Vector3f point, float physicalDepth) {
        float factor = zoom * physicalDepth / (physicalDepth + distance);
        point.x *= factor;
        point.y *= factor;
        return point;
    }

    /** Custom contact vertices carry optics in their unused light coordinates. */
    public int packed() {
        int focal = Math.round(zoom * 1024), offset = Math.round(distance * 1024) + 32768;
        if (focal < 1 || focal > 65535 || offset < 0 || offset > 65535)
            throw new IllegalStateException("Tile lens exceeds contact vertex range");
        return focal | offset << 16;
    }
}
