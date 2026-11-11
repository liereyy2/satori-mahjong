package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class IndexedTileMeshTest {
    @Test
    void indexingPreservesEveryRealTriangleAndItsUvNormalAndWinding() {
        for (int side = 0; side < 6; side++) {
            var original = RoundedTileMesh.faces().get(side);
            List<RoundedTileMesh.Vertex> expected = new ArrayList<>();
            for (int offset = 0; offset < original.size(); offset += 4)
                for (var triangle : new int[][] {{0, 1, 2}, {0, 2, 3}}) {
                    var a = original.get(offset + triangle[0]);
                    var b = original.get(offset + triangle[1]);
                    var c = original.get(offset + triangle[2]);
                    if (!a.equals(b) && !b.equals(c) && !a.equals(c))
                        expected.addAll(List.of(a, b, c));
                }
            var indexed = IndexedTileMesh.faces().get(side);
            assertEquals(
                    expected, indexed.indices().stream().map(indexed.vertices()::get).toList());
            assertEquals(indexed.vertices().size(), new HashSet<>(indexed.vertices()).size());
            assertTrue(indexed.vertices().size() < 100);
            assertTrue(
                    indexed.indices().stream()
                            .allMatch(i -> i >= 0 && i < indexed.vertices().size()));
            assertThrows(UnsupportedOperationException.class, () -> indexed.indices().clear());
        }
        assertEquals(
                588 * 3, IndexedTileMesh.faces().stream().mapToInt(f -> f.indices().size()).sum());
        assertTrue(IndexedTileMesh.faces().stream().mapToInt(f -> f.vertices().size()).sum() < 600);
    }
}
