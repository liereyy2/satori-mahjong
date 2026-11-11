package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The actual Web RoundedBox mesh, exported with its six material groups and face UVs. */
public final class RoundedTileMesh {
    public record Vertex(
            float x, float y, float z, float nx, float ny, float nz, float u, float v) {}

    private static final List<List<Vertex>> FACES = load();

    private RoundedTileMesh() {}

    public static List<List<Vertex>> faces() {
        return FACES;
    }

    private static List<List<Vertex>> load() {
        try (var input =
                new InputStreamReader(
                        Objects.requireNonNull(
                                RoundedTileMesh.class.getResourceAsStream(
                                        "/assets/satori_mahjong/models/web_tile_mesh.json")),
                        StandardCharsets.UTF_8)) {
            var source = JsonParser.parseReader(input).getAsJsonObject();
            float scale = TileGeometry.WIDTH / source.get("width").getAsFloat();
            if (Math.abs(source.get("height").getAsFloat() * scale - TileGeometry.HEIGHT) > 1e-6
                    || Math.abs(source.get("depth").getAsFloat() * scale - TileGeometry.THICKNESS)
                            > 1e-6) throw new IllegalStateException("web_tile_dimensions_mismatch");
            List<List<Vertex>> faces = new ArrayList<>();
            for (var rawFace : source.getAsJsonArray("faces")) {
                List<Vertex> vertices = new ArrayList<>();
                for (var raw : rawFace.getAsJsonArray()) {
                    var v = raw.getAsJsonArray();
                    vertices.add(
                            new Vertex(
                                    v.get(0).getAsFloat() * scale,
                                    v.get(1).getAsFloat() * scale,
                                    v.get(2).getAsFloat() * scale,
                                    v.get(3).getAsFloat(),
                                    v.get(4).getAsFloat(),
                                    v.get(5).getAsFloat(),
                                    v.get(6).getAsFloat(),
                                    v.get(7).getAsFloat()));
                }
                if (vertices.size() % 4 != 0)
                    throw new IllegalStateException("invalid_web_tile_face");
                faces.add(List.copyOf(vertices));
            }
            if (faces.size() != 6) throw new IllegalStateException("invalid_web_tile_groups");
            return List.copyOf(faces);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
