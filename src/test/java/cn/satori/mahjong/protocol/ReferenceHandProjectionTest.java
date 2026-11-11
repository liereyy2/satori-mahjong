package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

class ReferenceHandProjectionTest {
    private static void assertPhysicalShape(Matrix4f matrix) {
        var x = matrix.transformDirection(new Vector3f(1, 0, 0));
        var y = matrix.transformDirection(new Vector3f(0, 1, 0));
        var z = matrix.transformDirection(new Vector3f(0, 0, 1));
        assertEquals(1, y.length() / x.length(), 1e-4);
        assertEquals(1, z.length() / x.length(), 1e-4);
        x.normalize();
        y.normalize();
        z.normalize();
        assertEquals(0, x.dot(y), 1e-4);
        assertEquals(0, x.dot(z), 1e-4);
        assertEquals(0, y.dot(z), 1e-4);
    }

    private static TableScene.Tile hand(int seat, int slot) {
        var p =
                new Vector3f(
                                TableLayout.HAND_LEFT
                                        + TileGeometry.WIDTH / 2
                                        + slot * TileGeometry.HAND_PITCH,
                                1.003f + TileGeometry.HEIGHT / 2,
                                TableLayout.HAND_AWAY)
                        .rotateY((float) Math.toRadians(seat * 90));
        return new TableScene.Tile(
                "hand-" + seat + "-" + slot, seat, 4, p.x, p.y, p.z, seat * 90, true, false);
    }

    private static Matrix4f camera(double aspect, int seat) {
        var pose = TableCameraFraming.fit(TableCameraFraming.DEFAULT, seat, aspect);
        var p = pose.eye();
        return new Matrix4f()
                .lookAt(
                        new Vector3f((float) p.x(), (float) p.y(), (float) p.z()),
                        new Vector3f(0, 1, 0),
                        new Vector3f(0, 1, 0));
    }

    private static Matrix4f projection(double aspect) {
        return new Matrix4f().perspective((float) Math.toRadians(40), (float) aspect, .05f, 1024);
    }

    private static Vector3f projected(
            Matrix4f view, Matrix4f projection, TableScene.Tile tile, Vector3f local) {
        var point =
                new Matrix4f(view).mul(TileGeometry.localTransform(tile)).transformPosition(local);
        float depth = -point.z;
        projection.transformProject(point);
        return TileLens.of(tile).projectNdc(point, depth);
    }

    @Test
    void narrowHandLensLimitsEdgeFanWithoutChangingPhysicalProportions() {
        for (double aspect : new double[] {.5625, 1.333333, 1.777778, 2.333333}) {
            var view = camera(aspect, 0);
            var projection = projection(aspect);
            var stage = ReferenceHandProjection.fit(view, projection, (float) aspect, 0);
            assertEquals(
                    1 / Math.tan(Math.toRadians(15)), projection.m00() * stage.lens().zoom(), 1e-4);
            for (int slot : new int[] {0, 12}) {
                var tile = ReferenceHandProjection.apply(hand(0, slot), 0, stage);
                var a =
                        projected(
                                view,
                                projection,
                                tile,
                                new Vector3f(
                                        0, TileGeometry.HEIGHT / 2, TileGeometry.THICKNESS / 2));
                var b =
                        projected(
                                view,
                                projection,
                                tile,
                                new Vector3f(
                                        0, -TileGeometry.HEIGHT / 2, TileGeometry.THICKNESS / 2));
                double angle =
                        Math.toDegrees(
                                Math.atan2(Math.abs(a.x - b.x) * aspect, Math.abs(a.y - b.y)));
                assertTrue(angle < 4, "Edge tiles must keep weak perspective: " + angle);
                assertPhysicalShape(TileGeometry.localTransform(tile));
            }
        }
    }

    @Test
    void fullHandAndDrawnTileLeaveARightMarginAtEverySeat() {
        assertEquals(TileGeometry.WIDTH, TileGeometry.HAND_PITCH, 1e-7f);
        for (double aspect : new double[] {.5625, 1.333333, 1.777778, 2.333333})
            for (int seat = 0; seat < 4; seat++) {
                var view = camera(aspect, seat);
                var projection = projection(aspect);
                var stage = ReferenceHandProjection.fit(view, projection, (float) aspect, seat);
                float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
                for (int slot = 0; slot < 14; slot++) {
                    var tile = hand(seat, slot);
                    if (slot == 13) {
                        var offset =
                                new Vector3f(TileGeometry.WIDTH * .4f, 0, 0)
                                        .rotateY((float) Math.toRadians(seat * 90));
                        tile =
                                tile.pose(
                                        tile.x() + offset.x,
                                        tile.y(),
                                        tile.z() + offset.z,
                                        tile.yaw(),
                                        tile.pitch(),
                                        tile.face());
                    }
                    tile = ReferenceHandProjection.apply(tile, seat, stage);
                    for (float x : new float[] {-TileGeometry.WIDTH / 2, TileGeometry.WIDTH / 2})
                        for (float y :
                                new float[] {-TileGeometry.HEIGHT / 2, TileGeometry.HEIGHT / 2})
                            for (float z :
                                    new float[] {
                                        -TileGeometry.THICKNESS / 2, TileGeometry.THICKNESS / 2
                                    }) {
                                var point =
                                        projected(view, projection, tile, new Vector3f(x, y, z));
                                left = Math.min(left, point.x);
                                right = Math.max(right, point.x);
                                assertTrue(point.y > -1 && point.y < 1);
                            }
                }
                assertTrue(
                        left > -1 && right < .9f,
                        "Full hand reaches viewport edge: " + left + ", " + right);
                assertTrue(Math.abs(left + right) < .1f, "Hand row is biased too far to one side");
            }
    }

    @Test
    void exactReferenceCameraHasNaturalLateralPerspective() {
        var vp = ReferenceHandProjection.projection(.05f, 1024).mul(ReferenceHandProjection.view());
        var center = vp.transformProject(new Vector3f(0, .7f, 139.1f / 11.8f));
        assertEquals(0, center.x, 1e-6);
        assertEquals(3.7320508f, ReferenceHandProjection.projection(.05f, 1024).m00(), 1e-6);
        assertEquals(4.9636276f, ReferenceHandProjection.projection(.05f, 1024).m11(), 1e-5);
        // Camera is 13.766 degrees above the row, rather than each tile facing its eye.
        var normal = ReferenceHandProjection.view().transformDirection(new Vector3f(0, 0, 1));
        assertEquals(.9712743f, normal.z, 1e-5);
    }

    @Test
    void framingRetainsOneReferenceProjectionAcrossEverySeatAndViewport() {
        for (double aspect : new double[] {.5625, 1.333333, 1.777778, 2.333333})
            for (int seat = 0; seat < 4; seat++) {
                var view = camera(aspect, seat);
                var projection = projection(aspect);
                var stage = ReferenceHandProjection.fit(view, projection, (float) aspect, seat);
                assertTrue(stage.matrix().isAffine());
                assertTrue(stage.matrix().determinant() > 0);
                var first = ReferenceHandProjection.apply(hand(seat, 0), seat, stage);
                var last = ReferenceHandProjection.apply(hand(seat, 12), seat, stage);
                var modelA = TileGeometry.localTransform(first);
                var modelB = TileGeometry.localTransform(last);
                assertPhysicalShape(modelA);
                assertPhysicalShape(modelB);
                var actualNormal =
                        new Matrix4f(view)
                                .mul(modelA)
                                .transformDirection(new Vector3f(0, 0, 1))
                                .normalize();
                assertEquals(-.23796223f, actualNormal.y, 1e-4);
                assertEquals(.97127441f, actualNormal.z, 1e-4);
                var vp = new Matrix4f(projection).mul(view);
                var a = projected(view, projection, first, new Vector3f());
                var b = projected(view, projection, last, new Vector3f());
                assertTrue(a.x < b.x && Math.abs(a.y - b.y) < .0001);
                assertTrue(a.x > -1 && b.x < 1 && a.y > -1 && a.y < 1);
                // Parallel physical faces remain parallel, including the edge tiles.
                assertEquals(
                        modelA.transformDirection(new Vector3f(0, 0, 1)),
                        modelB.transformDirection(new Vector3f(0, 0, 1)));
                var other = hand((seat + 1) % 4, 0);
                assertSame(other, ReferenceHandProjection.apply(other, seat, stage));
                var revealed = hand(seat, 0).pose(0, 1.05f, 1, seat * 90, -90, 4);
                assertSame(
                        revealed,
                        ReferenceHandProjection.apply(revealed, seat, stage),
                        "Revealed flat hands remain on the real tabletop");
            }
    }

    @Test
    void discardAndDrawInterpolateTheSameTransformsAsTheirVisibleEndpoints() {
        var hand = hand(0, 0);
        var river = new TableScene.Tile("river-0-0", 0, 4, 0, 1.04f, .6f, 0, false, false);
        var stage = ReferenceHandProjection.fit(camera(1.778, 0), projection(1.778), 1.778f, 0);
        java.util.function.UnaryOperator<TableScene.Tile> transform =
                t -> ReferenceHandProjection.apply(t, 0, stage);
        var event =
                JsonParser.parseString("[{\"type\":\"dahai\",\"actor\":0,\"tsumogiri\":false}]")
                        .getAsJsonArray();
        var motion =
                new TableTransition(List.of(hand), List.of(river), event, 0, ProtocolTest.wall());
        assertTrue(
                TileGeometry.localTransform(transform.apply(hand))
                        .equals(
                                TileGeometry.localTransform(motion.sample(0, transform).getFirst()),
                                1e-5f));
        assertEquals(List.of(river), motion.sample(400_000_000, transform));
        for (long now = 0; now < 160_000_000; now += 1_000_000) {
            var matrix = TileGeometry.localTransform(motion.sample(now, transform).getFirst());
            assertTrue(
                    matrix.isFinite() && matrix.determinant() > 0,
                    "No collapsed or inverted transition");
            assertPhysicalShape(matrix);
        }
        var wall = TableLayout.wall(54, 0);
        var draw = JsonParser.parseString("[{\"type\":\"tsumo\",\"actor\":0}]").getAsJsonArray();
        motion = new TableTransition(List.of(wall), List.of(hand), draw, 0, ProtocolTest.wall());
        assertTrue(
                TileGeometry.localTransform(wall)
                        .equals(
                                TileGeometry.localTransform(motion.sample(0, transform).getFirst()),
                                1e-5f));
        assertEquals(List.of(transform.apply(hand)), motion.sample(400_000_000, transform));
    }
}
