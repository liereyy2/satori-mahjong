package cn.satori.mahjong.protocol;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

/** Cached geometric visibility of presentation tiles, in shared tile-width units. */
public final class TileOcclusion {
    public record Pose(String id, Matrix4f matrix) {}

    public record Visibility(float[] ambient, float[] direct) {}

    private record Collider(
            String id,
            Matrix4f matrix,
            Matrix4f inverse,
            Vector3f center,
            float radius,
            Matrix3f normal,
            Vector3f extent,
            boolean aligned) {}

    private static final class Sample {
        final Vector3f origin, direction;
        final float limit;
        String hit;

        Sample(Vector3f origin, Vector3f direction, float limit) {
            this.origin = origin;
            this.direction = direction;
            this.limit = limit;
        }
    }

    private static final float[] HALF = {.5f, .67f, .415f};
    private static final float[][][] FRAMES = {
        {{1, 0, 0}, {0, 0, -1}, {0, 1, 0}}, {{-1, 0, 0}, {0, 0, 1}, {0, 1, 0}},
        {{0, 1, 0}, {1, 0, 0}, {0, 0, -1}}, {{0, -1, 0}, {1, 0, 0}, {0, 0, 1}},
        {{0, 0, 1}, {1, 0, 0}, {0, 1, 0}}, {{0, 0, -1}, {-1, 0, 0}, {0, 1, 0}}
    };
    private Map<String, Collider> colliders = new LinkedHashMap<>();
    private final Map<String, Visibility[]> values = new HashMap<>();
    private final Map<String, List<List<Sample>>> samples = new HashMap<>();
    private int computed;
    private Vector3f lastLamp;

    public int computed() {
        return computed;
    }

    private static float distance(Collider a, Collider b) {
        return a.center.distance(b.center) - a.radius - b.radius;
    }

    public void update(List<Pose> poses, Vector3f lamp) {
        var next = new LinkedHashMap<String, Collider>();
        var changed = new ArrayList<Collider>();
        for (var p : poses) {
            var previous = colliders.get(p.id);
            if (previous != null && previous.matrix.equals(p.matrix)) {
                next.put(p.id, previous);
                continue;
            }
            var m = new Matrix4f(p.matrix);
            var s = m.getScale(new Vector3f());
            float radius = new Vector3f(HALF).mul(s).length();
            var extent = new Vector3f();
            boolean aligned = true;
            for (int row = 0; row < 3; row++) {
                float size = 0;
                for (int column = 0; column < 3; column++)
                    size += Math.abs(m.get(column, row)) * HALF[column];
                extent.setComponent(row, size);
            }
            for (int column = 0; column < 3; column++) {
                int nonzero = 0;
                for (int row = 0; row < 3; row++)
                    if (Math.abs(m.get(column, row)) > 1e-6) nonzero++;
                if (nonzero != 1) aligned = false;
            }
            var c =
                    new Collider(
                            p.id,
                            m,
                            new Matrix4f(m).invert(),
                            m.getTranslation(new Vector3f()),
                            radius,
                            new Matrix3f(m).invert().transpose(),
                            extent,
                            aligned);
            next.put(p.id, c);
            changed.add(c);
            if (previous != null) changed.add(previous);
        }
        for (var c : colliders.values())
            if (!next.containsKey(c.id)) {
                changed.add(c);
                values.remove(c.id);
                samples.remove(c.id);
            }
        boolean lightChanged = lastLamp == null || !lastLamp.equals(lamp);
        lastLamp = new Vector3f(lamp);
        computed = 0;
        var changedIds = new HashSet<String>();
        for (var changedCollider : changed) changedIds.add(changedCollider.id);
        for (var c : next.values()) {
            if (!lightChanged
                    && values.containsKey(c.id)
                    && changed.stream()
                            .noneMatch(other -> other.id.equals(c.id) || distance(c, other) < 3))
                continue;
            var neighbors =
                    next.values().stream()
                            .filter(other -> other != c && distance(c, other) < 3)
                            .toList();
            boolean ownChanged = lightChanged || changedIds.contains(c.id);
            var rays =
                    ownChanged || !samples.containsKey(c.id)
                            ? Arrays.stream(FRAMES).map(frame -> face(c, frame, lamp)).toList()
                            : samples.get(c.id);
            var changedNeighbors =
                    neighbors.stream().filter(other -> changedIds.contains(other.id)).toList();
            for (var face : rays)
                for (var ray : face) {
                    if (!ownChanged && ray.hit != null && !changedIds.contains(ray.hit)) continue;
                    boolean full = ownChanged || ray.hit != null;
                    ray.hit = null;
                    for (var other : (full ? neighbors : changedNeighbors))
                        if (rayBox(ray.origin, ray.direction, ray.limit, other)) {
                            ray.hit = other.id;
                            break;
                        }
                }
            samples.put(c.id, rays);
            var faces = new Visibility[6];
            for (int side = 0; side < 6; side++) {
                float[] ambient = new float[4], direct = new float[4];
                var normal = c.normal.transform(new Vector3f(FRAMES[side][0])).normalize();
                var point = new Vector3f();
                for (var ray : rays.get(side)) point.fma(.25f, ray.origin);
                float visible = 1;
                for (var other : neighbors) {
                    float dx = other.center.x - point.x,
                            dy = other.center.y - point.y,
                            dz = other.center.z - point.z;
                    float numerator = dx * normal.x + dy * normal.y + dz * normal.z;
                    if (numerator <= 0) continue;
                    float d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 < 1e-10f) {
                        visible *= .3f;
                        continue;
                    }
                    if (d2 > (other.radius + 2.4f) * (other.radius + 2.4f)) continue;
                    float d = (float) Math.sqrt(d2),
                            falloff = Math.max(0, 1 - Math.max(0, d - other.radius) / 2.4f);
                    visible *=
                            1
                                    - .70f
                                            * other.radius
                                            * other.radius
                                            / (d2 + other.radius * other.radius)
                                            * numerator
                                            / d
                                            * falloff
                                            * falloff;
                }
                for (int corner = 0; corner < 4; corner++) {
                    ambient[corner] = Math.max(.35f, visible);
                    direct[corner] = rays.get(side).get(corner).hit == null ? 1 : 0;
                }
                faces[side] = new Visibility(ambient, direct);
            }
            values.put(c.id, faces);
            computed++;
        }
        colliders = next;
    }

    public Visibility faceValues(String id, int side) {
        var faces = values.get(id);
        if (faces == null) throw new IllegalStateException("tile_visibility_missing");
        return faces[side];
    }

    private static boolean rayBox(Vector3f origin, Vector3f direction, float limit, Collider c) {
        float dx = c.center.x - origin.x, dy = c.center.y - origin.y, dz = c.center.z - origin.z;
        float along = dx * direction.x + dy * direction.y + dz * direction.z;
        if (along + c.radius < .012f
                || along - c.radius > limit
                || dx * dx + dy * dy + dz * dz - along * along > c.radius * c.radius) return false;
        var p =
                c.aligned
                        ? new Vector3f(origin).sub(c.center)
                        : c.inverse.transformPosition(origin, new Vector3f());
        var d = c.aligned ? direction : c.inverse.transformDirection(direction, new Vector3f());
        float lo = .012f, hi = limit;
        for (int axis = 0; axis < 3; axis++) {
            float x = p.get(axis), v = d.get(axis);
            float half = c.aligned ? c.extent.get(axis) : HALF[axis];
            if (Math.abs(v) < 1e-9) {
                if (Math.abs(x) > half) return false;
                continue;
            }
            float a = (-half - x) / v, b = (half - x) / v;
            if (a > b) {
                float t = a;
                a = b;
                b = t;
            }
            lo = Math.max(lo, a);
            hi = Math.min(hi, b);
            if (hi <= lo) return false;
        }
        return true;
    }

    private static List<Sample> face(Collider c, float[][] frame, Vector3f lamp) {
        var n = new Vector3f(frame[0]);
        var u = new Vector3f(frame[1]);
        var v = new Vector3f(frame[2]);
        var normal = c.normal.transform(n, new Vector3f()).normalize();
        var samples = new ArrayList<Sample>();
        for (int corner = 0; corner < 4; corner++) {
            var point = new Vector3f();
            for (int axis = 0; axis < 3; axis++)
                point.setComponent(
                        axis,
                        n.get(axis) * HALF[axis]
                                + u.get(axis) * HALF[axis] * (corner % 2 == 1 ? .9f : -.9f)
                                + v.get(axis) * HALF[axis] * (corner > 1 ? .9f : -.9f));
            c.matrix.transformPosition(point);
            point.fma(.008f, normal);
            var ray = new Vector3f(lamp).sub(point);
            float length = ray.length();
            ray.div(length);
            samples.add(new Sample(point, ray, length));
        }
        return samples;
    }
}
