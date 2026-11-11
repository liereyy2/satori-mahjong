package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.util.*;

class TileOcclusionTest {
    private static TileOcclusion.Pose pose(String id, float x) {
        return new TileOcclusion.Pose(id, new Matrix4f().translation(x, .67f, 0));
    }

    @Test
    void sharedWebFixturesMatchAllNativeFacesAndCorners() {
        var input =
                Objects.requireNonNull(
                        getClass().getResourceAsStream("/tile-occlusion-fixtures.json"));
        for (var raw : JsonParser.parseReader(new InputStreamReader(input)).getAsJsonArray()) {
            var fixture = raw.getAsJsonObject();
            var poses = new ArrayList<TileOcclusion.Pose>();
            for (var matrix : fixture.getAsJsonArray("poses")) {
                float[] values = new float[16];
                for (int i = 0; i < 16; i++)
                    values[i] = matrix.getAsJsonArray().get(i).getAsFloat();
                poses.add(new TileOcclusion.Pose("" + poses.size(), new Matrix4f().set(values)));
            }
            var cache = new TileOcclusion();
            cache.update(poses, new Vector3f(0, 18, 0));
            var expected = fixture.getAsJsonArray("results");
            for (int tile = 0; tile < poses.size(); tile++)
                for (int side = 0; side < 6; side++) {
                    var actual = cache.faceValues("" + tile, side);
                    var e = expected.get(tile).getAsJsonArray().get(side).getAsJsonObject();
                    for (int corner = 0; corner < 4; corner++) {
                        assertEquals(
                                e.getAsJsonArray("ambient").get(corner).getAsFloat(),
                                actual.ambient()[corner],
                                1e-5,
                                fixture.get("name").getAsString());
                        assertEquals(
                                e.getAsJsonArray("direct").get(corner).getAsFloat(),
                                actual.direct()[corner],
                                1e-5);
                    }
                }
        }
    }

    @Test
    void neighborsDarkenTheFacingSurfaceAndRemovalRestoresIt() {
        var cache = new TileOcclusion();
        var a = pose("a", 0);
        var b = pose("b", 1.01f);
        var lamp = new Vector3f(0, 18, 0);
        cache.update(List.of(a, b), lamp);
        assertTrue(
                Arrays.stream(
                                        new double[] {
                                            cache.faceValues("a", 0).ambient()[0],
                                            cache.faceValues("a", 0).ambient()[1]
                                        })
                                .min()
                                .orElseThrow()
                        < 1);
        cache.update(List.of(a), lamp);
        for (float v : cache.faceValues("a", 0).ambient()) assertEquals(1, v);
    }

    @Test
    void staticCacheAndLightChangesHaveExplicitInvalidation() {
        var cache = new TileOcclusion();
        var poses = List.of(pose("a", 0));
        cache.update(poses, new Vector3f(0, 18, 0));
        assertEquals(1, cache.computed());
        cache.update(poses, new Vector3f(0, 18, 0));
        assertEquals(0, cache.computed());
        cache.update(poses, new Vector3f(2, 18, 0));
        assertEquals(1, cache.computed());
    }

    @Test
    void incrementalVisibilityMatchesAColdSceneAfterMotionAndRemoval() {
        var cache = new TileOcclusion();
        var a = pose("a", 0);
        var lamp = new Vector3f(0, 18, 0);
        for (float x : new float[] {1.01f, 1.3f, 2.1f, 6, .9f}) {
            var poses = List.of(a, pose("b", x));
            cache.update(poses, lamp);
            var cold = new TileOcclusion();
            cold.update(poses, lamp);
            for (var p : poses)
                for (int face = 0; face < 6; face++) {
                    assertArrayEquals(
                            cold.faceValues(p.id(), face).ambient(),
                            cache.faceValues(p.id(), face).ambient(),
                            1e-6f);
                    assertArrayEquals(
                            cold.faceValues(p.id(), face).direct(),
                            cache.faceValues(p.id(), face).direct(),
                            1e-6f);
                }
        }
    }
}
