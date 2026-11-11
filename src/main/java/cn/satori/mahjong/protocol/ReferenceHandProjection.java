package cn.satori.mahjong.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Reference hand direction and row, fitted with one rigid rotation and uniform scale. */
public final class ReferenceHandProjection {
    private static final JsonObject REFERENCE;

    static {
        try (var input =
                ReferenceHandProjection.class.getResourceAsStream(
                        "/assets/satori_mahjong/models/hand_projection.json")) {
            if (input == null) throw new IllegalStateException("Missing hand projection reference");
            REFERENCE =
                    JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                            .getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ReferenceHandProjection() {}

    private static float number(String name) {
        return REFERENCE.get(name).getAsFloat();
    }

    public static float step() {
        return REFERENCE.getAsJsonObject("presentation").get("handPitch").getAsFloat();
    }

    private static float firstX() {
        return number("firstX") / number("tileWidth")
                + REFERENCE.getAsJsonObject("presentation").get("ownOffsetX").getAsFloat();
    }

    private static Vector3f vector(String name) {
        var a = REFERENCE.getAsJsonArray(name);
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat())
                .div(number("tileWidth"));
    }

    public static Matrix4f view() {
        return new Matrix4f().lookAt(vector("eye"), vector("target"), new Vector3f(0, 1, 0));
    }

    public static Matrix4f projection(float near, float far) {
        float halfW = near * (float) Math.tan(Math.toRadians(number("horizontalFov") / 2));
        float halfH = halfW / number("projectionAspect");
        return new Matrix4f().frustum(-halfW, halfW, -halfH, halfH, near, far);
    }

    /** Main view and anchors are in table-local coordinates, without the center-block offset. */
    public static TileViewTransform fit(
            Matrix4f mainView, Matrix4f mainProjection, float aspect, int viewer) {
        var turn = new Matrix4f().rotateY((float) Math.toRadians(viewer * 90));
        float edge = 1.342f - .015f;
        var left = turn.transformPosition(new Vector3f(-edge, 1.003f, TableLayout.HAND_AWAY));
        var right = turn.transformPosition(new Vector3f(edge, 1.003f, TableLayout.HAND_AWAY));
        var floor = turn.transformPosition(new Vector3f(0, 1.003f, edge));
        var vp = new Matrix4f(mainProjection).mul(mainView);
        var a = vp.transformProject(new Vector3f(left));
        var b = vp.transformProject(new Vector3f(right));
        var anchor = vp.transformProject(new Vector3f(floor));
        float sx = Math.abs(b.x - a.x) / 2;
        var refView = view();
        var refProjection = projection(.05f, 1024);
        var base =
                new Vector3f(
                        0,
                        0,
                        number("rowZ") / number("tileWidth")
                                + TileGeometry.THICKNESS / TileGeometry.WIDTH / 2);
        float depth = -mainView.transformPosition(new Vector3f(floor)).z;
        float referenceDepth = -refView.transformPosition(new Vector3f(base)).z;
        if (!(sx > 0 && depth > 0 && referenceDepth > 0))
            throw new IllegalStateException("Invalid hand projection frame");
        float scale = depth / referenceDepth * sx * refProjection.m00() / mainProjection.m00();
        var frame = new Matrix4f(refView).setTranslation(0, 0, 0).scale(scale);
        var target =
                new Vector3f(
                        anchor.x * depth / mainProjection.m00(),
                        Math.max(-.96f, anchor.y) * depth / mainProjection.m11(),
                        -depth);
        frame.setTranslation(target.sub(frame.transformPosition(new Vector3f(base))));
        var canonical =
                new Matrix4f()
                        .translation(
                                firstX()
                                        - (TableLayout.HAND_LEFT + TileGeometry.WIDTH / 2)
                                                / TileGeometry.WIDTH,
                                -1.003f / TileGeometry.WIDTH,
                                number("rowZ") / number("tileWidth")
                                        - TableLayout.HAND_AWAY / TileGeometry.WIDTH)
                        .scale(1 / TileGeometry.WIDTH)
                        .rotateY((float) Math.toRadians(-viewer * 90));
        var stage = new Matrix4f(mainView).invert().mul(frame).mul(canonical);
        float zoom =
                1
                        / (float) Math.tan(Math.toRadians(number("handHorizontalFov") / 2))
                        / mainProjection.m00();
        return new TileViewTransform(stage, new TileLens(zoom, (zoom - 1) * depth));
    }

    public static TableScene.Tile apply(TableScene.Tile tile, int viewer, TileViewTransform stage) {
        return tile.seat() == viewer && tile.key().startsWith("hand-") && tile.pitch() == 0
                ? tile.projected(stage)
                : tile;
    }
}
