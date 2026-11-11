package cn.satori.mahjong.protocol;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Immutable presentation transform, shared by the model, picking and animation endpoints. */
public final class TileViewTransform {
    private static final TileViewTransform IDENTITY = new TileViewTransform(new Matrix4f());
    private final Matrix4f matrix;
    private final Vector3f position;
    private final Quaternionf rotation;
    private final float scale;
    private final TileLens lens;

    public TileViewTransform(Matrix4f matrix) {
        this(matrix, TileLens.WORLD);
    }

    public TileViewTransform(Matrix4f matrix, TileLens lens) {
        this.matrix = new Matrix4f(matrix);
        this.lens = lens;
        scale = uniformScale(matrix);
        position = matrix.getTranslation(new Vector3f());
        rotation = matrix.getUnnormalizedRotation(new Quaternionf()).normalize();
    }

    private TileViewTransform(Vector3f position, Quaternionf rotation, float scale, TileLens lens) {
        this.position = position;
        this.rotation = rotation;
        this.scale = scale;
        this.lens = lens;
        matrix = new Matrix4f().translationRotateScale(position, rotation, new Vector3f(scale));
    }

    public Matrix4f matrix() {
        return new Matrix4f(matrix);
    }

    public TileLens lens() { return lens; }

    public static TileViewTransform blend(TileViewTransform a, TileViewTransform b, float t) {
        if (a == b || t <= 0) return a;
        if (t >= 1) return b;
        var from = a == null ? IDENTITY : a;
        var to = b == null ? IDENTITY : b;
        return new TileViewTransform(new Vector3f(from.position).lerp(to.position, t),
                new Quaternionf(from.rotation).slerp(to.rotation, t),
                from.scale + (to.scale - from.scale) * t, from.lens.blend(to.lens, t));
    }

    private static float uniformScale(Matrix4f matrix) {
        var scale = matrix.getScale(new Vector3f());
        var x = matrix.transformDirection(new Vector3f(1, 0, 0)).normalize();
        var y = matrix.transformDirection(new Vector3f(0, 1, 0)).normalize();
        var z = matrix.transformDirection(new Vector3f(0, 0, 1)).normalize();
        if (!matrix.isAffine() || !matrix.isFinite() || !(matrix.determinant() > 0)
                || !(scale.x > 0) || Math.abs(scale.y / scale.x - 1) > .0001f
                || Math.abs(scale.z / scale.x - 1) > .0001f
                || Math.abs(x.dot(y)) > .0001f || Math.abs(x.dot(z)) > .0001f
                || Math.abs(y.dot(z)) > .0001f)
            throw new IllegalStateException("Tile presentation must preserve physical proportions");
        return scale.x;
    }
}
