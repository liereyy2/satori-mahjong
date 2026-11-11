package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class TileContactShadowTest {
    @Test
    void hiddenWallFacesStillHaveABodyAndAShadow() {
        var hidden =
                new TableScene.Tile(
                        "wall-0-1",
                        0,
                        -1,
                        0,
                        1 + TileGeometry.HEIGHT / 2,
                        0,
                        0,
                        true,
                        false,
                        0,
                        1,
                        0);
        assertTrue(TileGeometry.isTileBody(hidden));
        assertTrue(TileContactShadow.footprint(hidden).isPresent());
        var stick = hidden.pose(0, 1, 0, 0, 0, -2);
        assertFalse(TileGeometry.isTileBody(stick));
        assertTrue(TileContactShadow.footprint(stick).isEmpty());
    }

    private static TableScene.Tile tile(
            float x, float z, float yaw, float pitch, float y, float scale) {
        return new TableScene.Tile("hand-0-1", 0, 1, x, y, z, yaw, true, false, pitch, scale, 0);
    }

    @Test
    void anOffCenterTileCastsAwayFromTheCeilingSource() {
        var shadow =
                TileContactShadow.footprint(tile(.7f, .9f, 0, 0, 1 + TileGeometry.HEIGHT / 2, 1))
                        .orElseThrow();
        assertTrue((shadow.minX() + shadow.maxX()) / 2 > .7f);
        assertTrue((shadow.minZ() + shadow.maxZ()) / 2 > .9f);
        assertTrue(shadow.maxX() - shadow.minX() > TileGeometry.WIDTH);
    }

    @Test
    void centeredSeatRotationsSwapTheProjectedDimensions() {
        float y = 1 + TileGeometry.HEIGHT / 2;
        var a = TileContactShadow.footprint(tile(0, 0, 0, 0, y, 1)).orElseThrow();
        var b = TileContactShadow.footprint(tile(0, 0, 90, 0, y, 1)).orElseThrow();
        assertEquals(a.maxX() - a.minX(), b.maxZ() - b.minZ(), 1e-5);
        assertEquals(a.maxZ() - a.minZ(), b.maxX() - b.minX(), 1e-5);
    }

    @Test
    void flatTilesKeepTheirLongEdgeInTheProjectedFootprint() {
        var a =
                TileContactShadow.footprint(tile(0, 0, 0, -90, 1 + TileGeometry.THICKNESS / 2, 1))
                        .orElseThrow();
        assertTrue(a.maxZ() - a.minZ() > a.maxX() - a.minX());
    }

    @Test
    void airborneShadowsExpandAndFadeWhileBuriedTilesDoNotCast() {
        assertTrue(TileContactShadow.footprint(tile(0, 0, 0, 0, .8f, 1)).isEmpty());
        var low =
                TileContactShadow.footprint(tile(0, 0, 0, 0, 1 + TileGeometry.HEIGHT / 2, 1))
                        .orElseThrow();
        var high = TileContactShadow.footprint(tile(0, 0, 0, 0, 1.5f, 1)).orElseThrow();
        assertTrue(high.opacity() < low.opacity());
        assertTrue(high.maxX() - high.minX() > low.maxX() - low.minX());
    }

    @Test
    void presentationTranslationIsNotIncludedTwiceInTheFootprint() {
        var physical = tile(.7f, .9f, 0, 0, 1 + TileGeometry.HEIGHT / 2, 1);
        var projected =
                physical.projected(new TileViewTransform(new Matrix4f().translation(1, 0, 0)));
        var shadow = TileContactShadow.footprint(projected).orElseThrow();
        assertTrue(
                (shadow.minX() + shadow.maxX()) / 2 < 1,
                "The renderer applies the presentation frame once");
        assertEquals(
                TileContactShadow.footprint(physical),
                TileContactShadow.footprint(
                        physical.projected(new TileViewTransform(new Matrix4f()))));
    }

    @Test
    void scaledTilesCastLargerShadows() {
        var a =
                TileContactShadow.footprint(tile(0, 0, 0, 0, 1 + TileGeometry.HEIGHT / 2, 1))
                        .orElseThrow();
        float scale = 1.18f;
        var b =
                TileContactShadow.footprint(
                                tile(0, 0, 0, 0, 1 + TileGeometry.HEIGHT * scale / 2, scale))
                        .orElseThrow();
        assertTrue(b.maxX() - b.minX() > a.maxX() - a.minX());
    }
}
