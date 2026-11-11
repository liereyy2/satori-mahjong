package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Set;

class TileOutlineTest {
    @Test
    void straightOnShowsOnlyFourFrontEdges() {
        assertEquals(
                Set.of(
                        new TileOutline.Edge(1, 3),
                        new TileOutline.Edge(1, 5),
                        new TileOutline.Edge(3, 7),
                        new TileOutline.Edge(5, 7)),
                Set.copyOf(TileOutline.visibleEdges(new Vector3f(0, 0, 2))));
        assertEquals(4, TileOutline.visibleEdges(new Vector3f(0, 2, 0)).size());
        assertEquals(4, TileOutline.visibleEdges(new Vector3f(-2, 0, 0)).size());
    }

    @Test
    void obliqueViewsNeverDrawEdgesAtTheFarCorner() {
        for (int corner = 0; corner < 8; corner++) {
            var eye = TileOutline.corner(corner).mul(40);
            var visible = TileOutline.visibleEdges(eye);
            assertEquals(9, visible.size());
            int farthest = corner ^ 7;
            assertTrue(visible.stream().noneMatch(e -> e.from() == farthest || e.to() == farthest));
        }
    }

    @Test
    void camerasAtFacePlanesDoNotExposeHiddenFaces() {
        var eye = new Vector3f(TileGeometry.WIDTH / 2, 2, 2);
        assertEquals(7, TileOutline.visibleEdges(eye).size());
        assertTrue(TileOutline.visibleEdges(new Vector3f()).isEmpty());
    }
}
