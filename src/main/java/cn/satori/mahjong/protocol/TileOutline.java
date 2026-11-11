package cn.satori.mahjong.protocol;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/** Visible cuboid edges, in the tile's local coordinate system. */
public final class TileOutline {
    public record Edge(int from, int to) {}

    private TileOutline() {}

    // Corner bits match the picker's X, Y, Z traversal: X=4, Y=2, Z=1.
    public static Vector3f corner(int index) {
        return new Vector3f(
                (index & 4) == 0 ? -TileGeometry.WIDTH / 2 : TileGeometry.WIDTH / 2,
                (index & 2) == 0 ? -TileGeometry.HEIGHT / 2 : TileGeometry.HEIGHT / 2,
                (index & 1) == 0 ? -TileGeometry.THICKNESS / 2 : TileGeometry.THICKNESS / 2);
    }

    public static List<Edge> visibleEdges(Vector3fc eye) {
        List<Edge> result = new ArrayList<>();
        for (int from = 0; from < 8; from++) {
            Vector3f point = corner(from);
            boolean x = (eye.x() - point.x) * point.x > 0;
            boolean y = (eye.y() - point.y) * point.y > 0;
            boolean z = (eye.z() - point.z) * point.z > 0;
            for (int axis : new int[] {1, 2, 4}) {
                int to = from ^ axis;
                boolean visible =
                        switch (axis) {
                            case 1 -> x || y;
                            case 2 -> x || z;
                            case 4 -> y || z;
                            default -> throw new AssertionError(axis);
                        };
                if (to > from && visible) result.add(new Edge(from, to));
            }
        }
        return List.copyOf(result);
    }
}
