package cn.satori.mahjong.protocol;

import java.util.ArrayList;
import java.util.List;

/** Presentation-only perspective fitting for the complete cabinet, pedestal and standing tiles. */
public final class TableCameraFraming {
    public static final double FOV = 40;
    public static final Settings DEFAULT = new Settings(.90, 45, 0);

    public record Point(double x, double y, double z) {
        double dot(Point other) {
            return x * other.x + y * other.y + z * other.z;
        }
    }

    public record Pose(Point eye, double yaw, double pitch) {}

    public record Settings(double coverage, double pitch, double yaw) {
        public Settings {
            if (!(coverage >= .70
                    && coverage <= .98
                    && pitch >= 40
                    && pitch <= 65
                    && yaw >= -20
                    && yaw <= 20))
                throw new IllegalArgumentException("invalid_table_camera_settings");
        }

        public Settings withCoverage(double value) {
            return new Settings(Math.clamp(value, .70, .98), pitch, yaw);
        }

        public Settings withPitch(double value) {
            return new Settings(coverage, Math.clamp(value, 40, 65), yaw);
        }

        public Settings withYaw(double value) {
            return new Settings(coverage, pitch, Math.clamp(value, -20, 20));
        }
    }

    private static final List<Point> BOUNDS = bounds();

    private TableCameraFraming() {}

    private static List<Point> bounds() {
        List<Point> points = new ArrayList<>();
        for (double[] box :
                new double[][] {
                    {1.50, .675, .71}, {1.48, .71, 1.055}, {1.34, 1.0, 1.205}, {.375, .03125, .675}
                })
            for (double x : new double[] {-box[0], box[0]})
                for (double z : new double[] {-box[0], box[0]})
                    for (double y : new double[] {box[1], box[2]}) points.add(new Point(x, y, z));
        return List.copyOf(points);
    }

    public static List<Point> boundsPoints() {
        return BOUNDS;
    }

    /**
     * Fit all corners into a centered frustum; adjust distance and vertical framing analytically.
     */
    public static Pose fit(Settings settings, int side, double aspect) {
        if (side < 0 || side > 3 || !(aspect > 0) || !Double.isFinite(aspect))
            throw new IllegalArgumentException("invalid_table_camera_viewport");
        double turn = Math.toRadians(side * 90 + settings.yaw);
        double tilt = Math.toRadians(settings.pitch);
        Point right = new Point(Math.cos(turn), 0, Math.sin(turn));
        Point up =
                new Point(
                        Math.sin(turn) * Math.sin(tilt),
                        Math.cos(tilt),
                        -Math.cos(turn) * Math.sin(tilt));
        Point back =
                new Point(
                        -Math.sin(turn) * Math.cos(tilt),
                        Math.sin(tilt),
                        Math.cos(turn) * Math.cos(tilt));
        double vertical = Math.tan(Math.toRadians(FOV / 2)) * settings.coverage;
        double horizontal = vertical * aspect;
        double distance = 0, lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY;
        for (Point corner : BOUNDS) {
            Point relative = new Point(corner.x, corner.y - 1, corner.z);
            double x = relative.dot(right), y = relative.dot(up), depth = relative.dot(back);
            distance = Math.max(distance, depth + Math.abs(x) / horizontal);
            lower = Math.max(lower, y + vertical * depth);
            upper = Math.min(upper, y - vertical * depth);
        }
        distance = Math.max(distance, (lower - upper) / (2 * vertical));
        double shift = (lower + upper) / 2;
        return new Pose(
                new Point(
                        back.x * distance + up.x * shift,
                        1 + back.y * distance + up.y * shift,
                        back.z * distance + up.z * shift),
                180 + side * 90 + settings.yaw,
                settings.pitch);
    }
}
