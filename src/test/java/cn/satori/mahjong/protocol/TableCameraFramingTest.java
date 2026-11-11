package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

class TableCameraFramingTest {
    private static List<Vector3f> project(
            TableCameraFraming.Settings settings, int side, double aspect) {
        var pose = TableCameraFraming.fit(settings, side, aspect);
        var eye = pose.eye();
        double yaw = Math.toRadians(pose.yaw()), pitch = Math.toRadians(pose.pitch());
        Vector3f origin = new Vector3f((float) eye.x(), (float) eye.y(), (float) eye.z());
        Vector3f target =
                new Vector3f(origin)
                        .add(
                                (float) (-Math.sin(yaw) * Math.cos(pitch)),
                                (float) -Math.sin(pitch),
                                (float) (Math.cos(yaw) * Math.cos(pitch)));
        Matrix4f matrix =
                new Matrix4f()
                        .perspective(
                                (float) Math.toRadians(TableCameraFraming.FOV),
                                (float) aspect,
                                .05f,
                                100)
                        .lookAt(origin, target, new Vector3f(0, 1, 0));
        return TableCameraFraming.boundsPoints().stream()
                .map(
                        p ->
                                matrix.transformProject(
                                        new Vector3f((float) p.x(), (float) p.y(), (float) p.z())))
                .toList();
    }

    @Test
    void defaultLandscapeFillsNinetyPercentAndKeepsTheWholeTable() {
        var points = project(TableCameraFraming.DEFAULT, 0, 16.0 / 9);
        double width =
                (points.stream().mapToDouble(p -> p.x).max().orElseThrow()
                                - points.stream().mapToDouble(p -> p.x).min().orElseThrow())
                        / 2;
        double height =
                (points.stream().mapToDouble(p -> p.y).max().orElseThrow()
                                - points.stream().mapToDouble(p -> p.y).min().orElseThrow())
                        / 2;
        assertEquals(.90, Math.max(width, height), .002);
        assertTrue(
                width > .88 && height > .88 && height <= .901,
                "Default table extent: " + width + ", " + height);
    }

    @Test
    void allViewportsAnglesAndChairsKeepBoundsWithinTheRequestedFrame() {
        for (double aspect : new double[] {9.0 / 16, 4.0 / 3, 16.0 / 9, 21.0 / 9})
            for (double coverage : new double[] {.70, .90, .98})
                for (double pitch : new double[] {40, 55, 65})
                    for (double yaw : new double[] {-20, 0, 20})
                        for (int side = 0; side < 4; side++) {
                            var points =
                                    project(
                                            new TableCameraFraming.Settings(coverage, pitch, yaw),
                                            side,
                                            aspect);
                            for (var point : points) {
                                assertTrue(
                                        Math.abs(point.x) <= coverage + .00001, point.toString());
                                assertTrue(
                                        Math.abs(point.y) <= coverage + .00001, point.toString());
                                assertTrue(point.z > -1 && point.z < 1);
                            }
                            assertEquals(
                                    coverage,
                                    points.stream()
                                            .mapToDouble(
                                                    p -> Math.max(Math.abs(p.x), Math.abs(p.y)))
                                            .max()
                                            .orElseThrow(),
                                    .00001);
                        }
    }

    @Test
    void zoomMovesCloserAndChairRotationPreservesHeightAndDistance() {
        var far = TableCameraFraming.fit(TableCameraFraming.DEFAULT.withCoverage(.70), 0, 16.0 / 9);
        var near =
                TableCameraFraming.fit(TableCameraFraming.DEFAULT.withCoverage(.98), 0, 16.0 / 9);
        assertTrue(near.eye().y() < far.eye().y());
        for (int side = 1; side < 4; side++) {
            var pose =
                    TableCameraFraming.fit(
                            TableCameraFraming.DEFAULT.withCoverage(.98), side, 16.0 / 9);
            assertEquals(near.eye().y(), pose.eye().y(), 1e-8);
            assertEquals(
                    Math.hypot(near.eye().x(), near.eye().z()),
                    Math.hypot(pose.eye().x(), pose.eye().z()),
                    1e-8);
            assertEquals(near.yaw() + side * 90, pose.yaw());
        }
    }

    @Test
    void controlsClampToTheirVisibleRanges() {
        assertEquals(.70, TableCameraFraming.DEFAULT.withCoverage(-1).coverage());
        assertEquals(.98, TableCameraFraming.DEFAULT.withCoverage(4).coverage());
        assertEquals(40, TableCameraFraming.DEFAULT.withPitch(0).pitch());
        assertEquals(65, TableCameraFraming.DEFAULT.withPitch(90).pitch());
        assertEquals(-20, TableCameraFraming.DEFAULT.withYaw(-200).yaw());
        assertEquals(20, TableCameraFraming.DEFAULT.withYaw(200).yaw());
    }

    @Test
    void invalidViewportOrSettingsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TableCameraFraming.fit(TableCameraFraming.DEFAULT, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> TableCameraFraming.fit(TableCameraFraming.DEFAULT, 4, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TableCameraFraming.Settings(Double.NaN, 55, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TableCameraFraming.Settings(.90, Double.POSITIVE_INFINITY, 0));
    }
}
