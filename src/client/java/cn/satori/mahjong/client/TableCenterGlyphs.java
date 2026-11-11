package cn.satori.mahjong.client;

import cn.satori.mahjong.SatoriMahjong;
import cn.satori.mahjong.protocol.TableCenter;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** High-resolution glyphs retain normal world depth, perspective and seat orientation. */
final class TableCenterGlyphs {
    private static final JsonObject ATLAS = load();
    private static final JsonObject GLYPHS = ATLAS.getAsJsonObject("glyphs");
    private static final float CELL = ATLAS.get("cell").getAsFloat();
    private static final float EM = ATLAS.get("size").getAsFloat();
    private static final int COLUMNS = ATLAS.get("columns").getAsInt();
    private static final float WIDTH = ATLAS.get("width").getAsFloat();
    private static final float HEIGHT = ATLAS.get("height").getAsFloat();

    private static JsonObject load() {
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                TableCenterGlyphs.class.getResourceAsStream(
                                        "/assets/satori_mahjong/textures/gui/center-glyphs.json")),
                        StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static float width(String text, float size) {
        float width = 0;
        for (char c : text.toCharArray())
            width += GLYPHS.getAsJsonObject(String.valueOf(c)).get("advance").getAsFloat() * size;
        return width;
    }

    static void submit(
            String text,
            float x,
            float z,
            float size,
            int color,
            PoseStack pose,
            SubmitNodeCollector queue) {
        float left = x - width(text, size) / 2;
        queue.submitCustomGeometry(
                pose,
                RenderTypes.entityTranslucent(SatoriMahjong.id("textures/gui/center-glyphs.png")),
                (matrix, vertices) -> {
                    float cursor = left;
                    float half = size * CELL / EM / 2, u = TableCenter.UNIT;
                    for (char c : text.toCharArray()) {
                        JsonObject glyph = GLYPHS.getAsJsonObject(String.valueOf(c));
                        int index = glyph.get("index").getAsInt();
                        float advance = glyph.get("advance").getAsFloat() * size;
                        float center = cursor + advance / 2;
                        float u0 = index % COLUMNS * CELL / WIDTH,
                                v0 = index / COLUMNS * CELL / HEIGHT;
                        float u1 = u0 + CELL / WIDTH, v1 = v0 + CELL / HEIGHT;
                        vertices.addVertex(matrix, (center - half) * u, 1.055f, (z + half) * u)
                                .setColor(color)
                                .setUv(u0, v1)
                                .setOverlay(OverlayTexture.NO_OVERLAY)
                                .setLight(LightTexture.FULL_BRIGHT)
                                .setNormal(matrix, 0, 1, 0);
                        vertices.addVertex(matrix, (center + half) * u, 1.055f, (z + half) * u)
                                .setColor(color)
                                .setUv(u1, v1)
                                .setOverlay(OverlayTexture.NO_OVERLAY)
                                .setLight(LightTexture.FULL_BRIGHT)
                                .setNormal(matrix, 0, 1, 0);
                        vertices.addVertex(matrix, (center + half) * u, 1.055f, (z - half) * u)
                                .setColor(color)
                                .setUv(u1, v0)
                                .setOverlay(OverlayTexture.NO_OVERLAY)
                                .setLight(LightTexture.FULL_BRIGHT)
                                .setNormal(matrix, 0, 1, 0);
                        vertices.addVertex(matrix, (center - half) * u, 1.055f, (z - half) * u)
                                .setColor(color)
                                .setUv(u0, v0)
                                .setOverlay(OverlayTexture.NO_OVERLAY)
                                .setLight(LightTexture.FULL_BRIGHT)
                                .setNormal(matrix, 0, 1, 0);
                        cursor += advance;
                    }
                });
    }
}
