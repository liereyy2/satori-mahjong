package cn.satori.mahjong.protocol;

import java.util.*;

/** Lossless indexing of the exported Web mesh; identical attributes share one GPU vertex. */
public final class IndexedTileMesh {
    public record Face(List<RoundedTileMesh.Vertex> vertices, List<Integer> indices) {}

    private static final List<Face> FACES =
            RoundedTileMesh.faces().stream().map(IndexedTileMesh::index).toList();

    private IndexedTileMesh() {}

    public static List<Face> faces() {
        return FACES;
    }

    private static Face index(List<RoundedTileMesh.Vertex> source) {
        Map<RoundedTileMesh.Vertex, Integer> unique = new LinkedHashMap<>();
        List<Integer> indices = new ArrayList<>();
        for (int offset = 0; offset < source.size(); offset += 4) {
            for (var triangle : new int[][] {{0, 1, 2}, {0, 2, 3}}) {
                var a = source.get(offset + triangle[0]);
                var b = source.get(offset + triangle[1]);
                var c = source.get(offset + triangle[2]);
                // The Web exporter repeats the final triangle vertex to form a native QUAD.
                if (a.equals(b) || b.equals(c) || a.equals(c)) continue;
                for (var vertex : List.of(a, b, c))
                    indices.add(unique.computeIfAbsent(vertex, key -> unique.size()));
            }
        }
        return new Face(List.copyOf(unique.keySet()), List.copyOf(indices));
    }
}
