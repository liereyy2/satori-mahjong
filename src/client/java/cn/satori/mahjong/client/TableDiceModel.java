package cn.satori.mahjong.client;

import cn.satori.mahjong.SatoriMahjong;
import cn.satori.mahjong.protocol.TableDice;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Six individually engraved faces. Opposite faces sum to seven; one is red. */
final class TableDiceModel {
    private final ModelPart body =
            ElectricTableModel.bake(
                    CubeListBuilder.create().addBox(-.8f, -.8f, -.8f, 1.6f, 1.6f, 1.6f));

    void submit(TableDice.Die die, PoseStack pose, SubmitNodeCollector queue, int light) {
        pose.pushPose();
        pose.translate(die.x(), die.y(), die.z());
        pose.mulPose(Axis.YP.rotationDegrees(die.yaw()));
        pose.mulPose(Axis.XP.rotationDegrees(die.pitch()));
        pose.mulPose(Axis.ZP.rotationDegrees(die.roll()));
        ElectricTableModel.draw(body, "ivory", pose, queue, light);
        for (int value = 1; value <= 6; value++) {
            pose.pushPose();
            switch (value) {
                case 2 -> pose.mulPose(Axis.XP.rotationDegrees(90));
                case 3 -> pose.mulPose(Axis.ZP.rotationDegrees(-90));
                case 4 -> pose.mulPose(Axis.ZP.rotationDegrees(90));
                case 5 -> pose.mulPose(Axis.XP.rotationDegrees(-90));
                case 6 -> pose.mulPose(Axis.XP.rotationDegrees(180));
            }
            face(value, pose, queue, light);
            pose.popPose();
        }
        pose.popPose();
    }

    private static void face(int value, PoseStack pose, SubmitNodeCollector queue, int light) {
        // Explicit pip positions avoid text, textures and view-dependent face labels.
        int[][] pips =
                switch (value) {
                    case 1 -> new int[][] {{0, 0}};
                    case 2 -> new int[][] {{-1, -1}, {1, 1}};
                    case 3 -> new int[][] {{-1, -1}, {0, 0}, {1, 1}};
                    case 4 -> new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
                    case 5 -> new int[][] {{-1, -1}, {1, -1}, {0, 0}, {-1, 1}, {1, 1}};
                    case 6 -> new int[][] {{-1, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {1, 1}};
                    default -> throw new IllegalArgumentException();
                };
        int color = value == 1 ? 0xFFCE2020 : 0xFF101217;
        queue.submitCustomGeometry(
                pose,
                RenderTypes.entityCutoutNoCull(
                        SatoriMahjong.id("textures/block/furniture/ivory.png")),
                (matrix, vertices) -> {
                    for (int[] pip : pips) {
                        float x = pip[0] * .024f, z = pip[1] * .024f;
                        float radius = value == 1 ? .012f : .008f;
                        for (int n = 0; n < 16; n++) {
                            float a = (float) (n * Math.PI / 8),
                                    b = (float) ((n + 1) * Math.PI / 8);
                            float[][] quad = {
                                {x, z},
                                {
                                    x + radius * (float) Math.cos(b),
                                    z + radius * (float) Math.sin(b)
                                },
                                {
                                    x + radius * (float) Math.cos(a),
                                    z + radius * (float) Math.sin(a)
                                },
                                {x, z}
                            };
                            for (var p : quad)
                                vertices.addVertex(matrix, p[0], .0502f, p[1])
                                        .setColor(color)
                                        .setUv(.5f, .5f)
                                        .setOverlay(OverlayTexture.NO_OVERLAY)
                                        .setLight(light)
                                        .setNormal(matrix, 0, 1, 0);
                        }
                    }
                });
    }
}
