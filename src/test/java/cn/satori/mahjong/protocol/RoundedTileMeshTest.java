package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Stream;

class RoundedTileMeshTest {
    private static Stream<List<RoundedTileMesh.Vertex>> body() {
        return RoundedTileMesh.faces().stream();
    }

    private static Vector3f position(RoundedTileMesh.Vertex v) {
        return new Vector3f(v.x(), v.y(), v.z());
    }

    @Test
    void roundedBodyRetainsPhysicalSizeAndUnitNormalsWithinACompactVertexBudget() {
        var vertices = body().flatMap(Collection::stream).toList();
        assertEquals(
                TileGeometry.WIDTH,
                vertices.stream().mapToDouble(v -> v.x()).max().orElseThrow()
                        - vertices.stream().mapToDouble(v -> v.x()).min().orElseThrow(),
                1e-6);
        assertEquals(
                TileGeometry.HEIGHT,
                vertices.stream().mapToDouble(v -> v.y()).max().orElseThrow()
                        - vertices.stream().mapToDouble(v -> v.y()).min().orElseThrow(),
                1e-6);
        assertEquals(
                TileGeometry.THICKNESS,
                vertices.stream().mapToDouble(v -> v.z()).max().orElseThrow()
                        - vertices.stream().mapToDouble(v -> v.z()).min().orElseThrow(),
                1e-6);
        for (var v : vertices) {
            assertEquals(1, new Vector3f(v.nx(), v.ny(), v.nz()).length(), 1e-6);
            assertTrue(v.u() >= -1e-6 && v.u() <= 1.000001 && v.v() >= -1e-6 && v.v() <= 1.000001);
            assertFalse(
                    Math.abs(v.x()) > TileGeometry.WIDTH * .495f
                            && Math.abs(v.y())
                                    > TileGeometry.HEIGHT / 2 - TileGeometry.WIDTH * .005f,
                    "The outer corner is still square");
        }
        assertTrue(
                vertices.stream().anyMatch(v -> Math.abs(v.nz()) > .1 && Math.abs(v.nz()) < .9),
                "Missing curved front/back edges");
        assertEquals(1.34f, TileGeometry.HEIGHT / TileGeometry.WIDTH, 1e-6);
        assertEquals(.83f, TileGeometry.THICKNESS / TileGeometry.WIDTH, 1e-6);
        assertEquals(6, RoundedTileMesh.faces().size());
        assertTrue(vertices.size() <= 2400, "Web tile mesh exceeds the rendering budget");
    }

    @Test
    void roundedEdgesLeaveABroadFlatPrintedFace() {
        var flat = RoundedTileMesh.faces().get(4).stream().filter(v -> v.nz() > .999999f).toList();
        double width =
                flat.stream().mapToDouble(RoundedTileMesh.Vertex::x).max().orElseThrow()
                        - flat.stream().mapToDouble(RoundedTileMesh.Vertex::x).min().orElseThrow();
        double height =
                flat.stream().mapToDouble(RoundedTileMesh.Vertex::y).max().orElseThrow()
                        - flat.stream().mapToDouble(RoundedTileMesh.Vertex::y).min().orElseThrow();
        assertTrue(
                width / TileGeometry.WIDTH >= .79 && width / TileGeometry.WIDTH <= .81,
                "The bevel must preserve a broad face while retaining rounded edges");
        assertTrue(height / TileGeometry.HEIGHT >= .84);
    }

    private record Point(long x, long y, long z) implements Comparable<Point> {
        static Point of(RoundedTileMesh.Vertex v) {
            return new Point(
                    Math.round(v.x() * 1e7), Math.round(v.y() * 1e7), Math.round(v.z() * 1e7));
        }

        @Override
        public int compareTo(Point other) {
            int c = Long.compare(x, other.x);
            if (c == 0) c = Long.compare(y, other.y);
            return c == 0 ? Long.compare(z, other.z) : c;
        }
    }

    private record Edge(Point a, Point b) {
        static Edge of(RoundedTileMesh.Vertex a, RoundedTileMesh.Vertex b) {
            var p = Point.of(a);
            var q = Point.of(b);
            return p.compareTo(q) < 0 ? new Edge(p, q) : new Edge(q, p);
        }
    }

    @Test
    void bodyLayersJoinIntoAClosedOutwardFacingSurface() {
        Map<Edge, Integer> edges = new HashMap<>();
        body().forEach(
                        mesh -> {
                            assertEquals(0, mesh.size() % 4);
                            for (int offset = 0; offset < mesh.size(); offset += 4) {
                                for (var triangle : new int[][] {{0, 1, 2}, {0, 2, 3}}) {
                                    var a = mesh.get(offset + triangle[0]);
                                    var b = mesh.get(offset + triangle[1]);
                                    var c = mesh.get(offset + triangle[2]);
                                    var normal =
                                            position(b)
                                                    .sub(position(a))
                                                    .cross(position(c).sub(position(a)));
                                    if (normal.lengthSquared() < 1e-16f) continue;
                                    var expected =
                                            new Vector3f(
                                                    a.nx() + b.nx() + c.nx(),
                                                    a.ny() + b.ny() + c.ny(),
                                                    a.nz() + b.nz() + c.nz());
                                    assertTrue(
                                            normal.dot(expected) > 0, "Reversed triangle winding");
                                    edges.merge(Edge.of(a, b), 1, Integer::sum);
                                    edges.merge(Edge.of(b, c), 1, Integer::sum);
                                    edges.merge(Edge.of(c, a), 1, Integer::sum);
                                }
                            }
                        });
        assertTrue(edges.size() > 100);
        edges.forEach(
                (edge, count) -> assertEquals(2, count, "Gap or overlapping surface at " + edge));
    }

    @Test
    void webFrontFaceCarriesTheArtworkAcrossTheRoundedEdgeWithUprightUvs() throws Exception {
        float front = TileGeometry.THICKNESS / 2;
        var face = RoundedTileMesh.faces().get(4);
        assertTrue(
                face.stream().anyMatch(v -> v.z() < front - .001f && v.nz() > 0 && v.nz() < .9f));
        assertTrue(face.stream().anyMatch(v -> Math.abs(v.nz() - 1) < 1e-6));
        for (var v : face) {
            assertTrue(v.nz() >= -1e-6);
            if (v.y() > .001f) assertTrue(v.v() < .501f, "Front artwork is upside down");
            if (v.x() > .001f) assertTrue(v.u() > .499f, "Front artwork is mirrored");
        }
        for (String name : List.of("px", "nx", "py", "ny", "back", "blank")) {
            try (var input =
                    getClass()
                            .getResourceAsStream(
                                    "/assets/satori_mahjong/textures/block/furniture/web_tile_"
                                            + name
                                            + ".png.mcmeta")) {
                assertNotNull(input);
                var metadata =
                        com.google.gson.JsonParser.parseReader(
                                        new java.io.InputStreamReader(
                                                input, java.nio.charset.StandardCharsets.UTF_8))
                                .getAsJsonObject()
                                .getAsJsonObject("texture");
                assertTrue(
                        metadata.get("clamp").getAsBoolean(),
                        "Web edge sampling must not wrap blue pixels across a white edge");
                assertTrue(metadata.get("blur").getAsBoolean());
            }
        }
        assertThrows(UnsupportedOperationException.class, () -> face.clear());
        assertThrows(UnsupportedOperationException.class, () -> RoundedTileMesh.faces().clear());
    }
}
