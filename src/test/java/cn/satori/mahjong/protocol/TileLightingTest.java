package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class TileLightingTest {
    @Test
    void tableLampUsesTheSharedTileWidthAndMovesWithItsOwnTable() {
        assertEquals(18 * TileGeometry.WIDTH, TileLighting.height(), 1e-6);
        var lamp = new Vector3f(0, 1.003f + TileLighting.height(), 0);
        var a = new Matrix4f().translate(-5, 2, -8).rotateY(.6f);
        var b = new Matrix4f().translate(7, 2, -8).rotateY(-.4f);
        var la = a.transformPosition(new Vector3f(lamp));
        var lb = b.transformPosition(new Vector3f(lamp));
        assertEquals(12, lb.x - la.x, 1e-6);
        assertEquals(la.y, lb.y, 1e-6);
        assertEquals(la.z, lb.z, 1e-6);
    }

    @Test
    void packedInstanceLampAndLensNeverChangeTheNormalBlock() {
        var model = new Matrix4f().translate(2, 1, -4).rotateXYZ(.4f, .8f, -.2f).scale(.7f);
        var lens = new TileLens(2.3f, 8.6f);
        var lamp = new Vector3f(-4, 5, -9);
        var packed = TileLighting.instanceNormal(model, lens, lamp, new Vector3f(0, 1, 0));
        assertEquals(new Matrix3f(new Matrix4f(model).invert().transpose()), new Matrix3f(packed));
        assertEquals(lamp, packed.getTranslation(new Vector3f()));
        assertEquals(lens.zoom(), packed.m03());
        assertEquals(lens.distance(), packed.m13());
        assertEquals(
                new Vector3f(0, 1, 0),
                TileLighting.decodeDirection(new org.joml.Vector2f(packed.m23(), packed.m33())));
    }

    @Test
    void studioUpSurvivesBothOctahedralHemispheres() {
        for (var n :
                new Vector3f[] {
                    new Vector3f(1, 2, 3),
                    new Vector3f(-2, 1, -3),
                    new Vector3f(0, -1, 0),
                    new Vector3f(0, 0, -1)
                })
            assertTrue(
                    new Vector3f(n)
                                    .normalize()
                                    .distance(
                                            TileLighting.decodeDirection(
                                                    TileLighting.encodeDirection(n)))
                            < 1e-6);
    }

    @Test
    void stickLightCoordinatesPreserveSignedCameraRelativePositions() {
        for (var p :
                new Vector3f[] {
                    new Vector3f(-12.35f, 3.14f, -27.68f), new Vector3f(10000, -7000, 6000)
                }) {
            int xy = TileLighting.packedOverlay(p),
                    z = TileLighting.packedLight(p, new Vector3f(0, 1, 0));
            var actual =
                    new Vector3f(
                            Float.float16ToFloat((short) xy),
                            Float.float16ToFloat((short) (xy >>> 16)),
                            Float.float16ToFloat((short) z));
            assertTrue(actual.distance(p) <= .028f);
            int packed = TileLighting.packedLight(p, new Vector3f(.2f, -.5f, -.8f));
            var up =
                    TileLighting.decodeDirection(
                            new org.joml.Vector2f(
                                    ((packed >>> 16) & 255) / 255f * 2 - 1,
                                    ((packed >>> 24) & 255) / 255f * 2 - 1));
            assertTrue(up.distance(new Vector3f(.2f, -.5f, -.8f).normalize()) < .02f);
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TileLighting.packedLight(
                                new Vector3f(0, 0, Float.NaN), new Vector3f(0, 1, 0)));
    }
}
