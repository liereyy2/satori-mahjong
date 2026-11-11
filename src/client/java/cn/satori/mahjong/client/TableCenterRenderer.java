package cn.satori.mahjong.client;

import cn.satori.mahjong.SatoriMahjong;
import cn.satori.mahjong.protocol.TableCenter;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Native rendering of the same center square, score ring and wind badges as MahjongBoard3D. */
final class TableCenterRenderer {
    // These surfaces are opaque and vertically layered. Transparent quad sorting by
    // center distance puts the far/side indicator underneath its own background.
    private static final RenderType PANEL =
            RenderType.create(
                    "satori_table_center",
                    RenderSetup.builder(
                                    RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                                            .withLocation(SatoriMahjong.id("pipeline/table_center"))
                                            .withoutBlend()
                                            .withDepthWrite(true)
                                            .withCull(false)
                                            .build())
                            .createRenderSetup());

    void submit(TableCenter center, PoseStack pose, SubmitNodeCollector queue, boolean blink) {
        float u = TableCenter.UNIT;
        // Logical coordinates are the web board's 448px canvas, centered on the table.
        rect(-218, -218, 218, 218, 1.042f, 0xFF22375C, pose, queue);
        rect(-211, -211, 211, 211, 1.043f, 0xFF14233D, pose, queue);
        float inner = TableCenter.INNER_HALF / u;
        rect(-inner - 1, -inner - 1, inner + 1, inner + 1, 1.044f, 0xFF526D8B, pose, queue);
        rect(-inner, -inner, inner, inner, 1.045f, 0xFF0D1A30, pose, queue);
        if (center == null) {
            text("SATORI", 0, -20, 39, 0xFFF0D489, pose, queue);
            text("RIICHI", 0, 35, 25, 0xFFCFE0F2, pose, queue);
            return;
        }
        for (int seat = 0; seat < 4; seat++) {
            var info = center.seats().get(seat);
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(seat * 90));
            rect(
                    -212,
                    146,
                    -146,
                    212,
                    1.047f,
                    info.dealer() ? 0xFFC8601E : 0xFF1E304C,
                    pose,
                    queue);
            text(wind(info.wind()), -179, 179, 46, 0xFFF3EEE2, pose, queue);
            // Reserved rail between the inner metadata panel and the score ring.
            rect(
                    -100,
                    124,
                    100,
                    130,
                    1.048f,
                    info.active() && blink ? 0xFFFFCF54 : 0xFF344357,
                    pose,
                    queue);
            score(info.score(), TableCenter.SCORE_RADIUS / u, 0xFFEEF2F7, pose, queue);
            if (center.ruleMode().equals("sichuan"))
                text(
                        info.won()
                                ? "已和"
                                : info.voidSuit() < 0
                                        ? center.openingPhase().equals("exchange_three")
                                                ? "换牌中"
                                                : "定缺中"
                                        : "缺" + new String[] {"万", "筒", "条"}[info.voidSuit()],
                        0,
                        199,
                        23,
                        info.won() ? 0xFFF0D489 : 0xFF77E0C8,
                        pose,
                        queue);
            pose.popPose();
        }
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(center.viewer() * 90));
        text(
                center.ruleMode().equals("sichuan")
                        ? "第" + center.matchRound() + "局"
                        : wind(center.roundWind()) + center.round() + "局",
                0,
                -40,
                52,
                0xFFF0D489,
                pose,
                queue);
        text("余 " + center.remaining(), 0, 48, 42, 0xFFCFE0F2, pose, queue);
        text(
                center.ruleMode().equals("sichuan")
                        ? "川麻 · 血战到底"
                        : "本场 " + center.honba() + " 供托 " + center.kyotaku(),
                0,
                95,
                28,
                0xFFACBFD4,
                pose,
                queue);
        pose.popPose();
    }

    private static String wind(int value) {
        return new String[] {"東", "南", "西", "北"}[value];
    }

    private void score(
            int value, float radius, int color, PoseStack pose, SubmitNodeCollector queue) {
        String all = Integer.toString(value);
        boolean split = Math.abs((long) value) >= 100;
        String high = split ? all.substring(0, all.length() - 2) : all;
        String low = split ? all.substring(all.length() - 2) : "";
        float large = TableCenterGlyphs.width(high, 46), small = TableCenterGlyphs.width(low, 30);
        float width = large + small;
        text(high, -width / 2 + large / 2, radius, 46, color, pose, queue);
        if (split) text(low, width / 2 - small / 2, radius + 7, 30, color, pose, queue);
    }

    private void text(
            String value,
            float x,
            float z,
            float size,
            int color,
            PoseStack pose,
            SubmitNodeCollector queue) {
        TableCenterGlyphs.submit(value, x, z, size, color, pose, queue);
    }

    private void rect(
            float left,
            float top,
            float right,
            float bottom,
            float y,
            int color,
            PoseStack pose,
            SubmitNodeCollector queue) {
        float u = TableCenter.UNIT;
        queue.submitCustomGeometry(
                pose,
                PANEL,
                (matrix, vertices) -> {
                    vertices.addVertex(matrix, left * u, y, bottom * u).setColor(color);
                    vertices.addVertex(matrix, right * u, y, bottom * u).setColor(color);
                    vertices.addVertex(matrix, right * u, y, top * u).setColor(color);
                    vertices.addVertex(matrix, left * u, y, top * u).setColor(color);
                });
    }
}
