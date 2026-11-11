package cn.satori.mahjong.protocol;

import com.google.gson.JsonParser;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** A table-local ceiling source; presentation cameras never move the lamp. */
public final class TileLighting {
    private static final float HEIGHT = readHeight();

    private TileLighting() {}

    private static float readHeight() {
        try (var input =
                TileLighting.class.getResourceAsStream(
                        "/assets/satori_mahjong/models/tile_appearance.json")) {
            if (input == null) throw new IllegalStateException("Missing tile lighting source");
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonObject("lighting")
                    .get("height")
                    .getAsFloat();
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static float height() {
        return TileGeometry.WIDTH * HEIGHT;
    }

    public static Vector2f encodeDirection(Vector3f direction) {
        if (!Float.isFinite(direction.x)
                || !Float.isFinite(direction.y)
                || !Float.isFinite(direction.z)
                || direction.lengthSquared() == 0)
            throw new IllegalArgumentException("Invalid studio direction");
        var n = new Vector3f(direction).normalize();
        n.div(Math.abs(n.x) + Math.abs(n.y) + Math.abs(n.z));
        if (n.z < 0) {
            float x = n.x;
            n.x = (1 - Math.abs(n.y)) * Math.copySign(1, x);
            n.y = (1 - Math.abs(x)) * Math.copySign(1, n.y);
        }
        return new Vector2f(n.x, n.y);
    }

    public static Vector3f decodeDirection(Vector2f encoded) {
        var n = new Vector3f(encoded.x, encoded.y, 1 - Math.abs(encoded.x) - Math.abs(encoded.y));
        float t = Math.max(-n.z, 0);
        n.x += n.x >= 0 ? -t : t;
        n.y += n.y >= 0 ? -t : t;
        return n.normalize();
    }

    public static Matrix4f instanceNormal(
            Matrix4f modelView, TileLens lens, Vector3f lamp, Vector3f up) {
        // mat3 reads only the normal block. Unused row/column carry optics and the lamp.
        var sky = encodeDirection(up);
        return new Matrix4f(modelView)
                .invert()
                .transpose()
                .m03(lens.zoom())
                .m13(lens.distance())
                .m23(sky.x)
                .m33(sky.y)
                .m30(lamp.x)
                .m31(lamp.y)
                .m32(lamp.z);
    }

    private static int coordinate(float value) {
        short encoded = Float.floatToFloat16(value);
        if (!Float.isFinite(value) || !Float.isFinite(Float.float16ToFloat(encoded)))
            throw new IllegalArgumentException("Invalid table light coordinate");
        return encoded & 0xffff;
    }

    // Native stick cubes already provide two unused short UV channels; no new vertex stream.
    public static int packedOverlay(Vector3f lamp) {
        return coordinate(lamp.x) | (coordinate(lamp.y) << 16);
    }

    public static int packedLight(Vector3f lamp, Vector3f up) {
        var v = encodeDirection(up);
        int a = Math.round((v.x * .5f + .5f) * 255), b = Math.round((v.y * .5f + .5f) * 255);
        return coordinate(lamp.z) | (a << 16) | (b << 24);
    }
}
