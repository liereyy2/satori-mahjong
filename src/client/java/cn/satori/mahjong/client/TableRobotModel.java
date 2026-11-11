package cn.satori.mahjong.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small seated assistant: porcelain shell, blue ear pods, luminous eyes and a waving antenna. */
final class TableRobotModel {
    private final Map<String, CubeListBuilder> boxes = new LinkedHashMap<>();
    private final Map<String, ModelPart> parts = new LinkedHashMap<>();

    TableRobotModel() {
        box("ivory", -.22f, .66f, -.15f, .44f, .40f, .30f);
        box("tile_blue", -.19f, .63f, -.14f, .38f, .10f, .28f);
        box("steel", -.09f, 1.04f, -.08f, .18f, .08f, .16f);
        box("ivory", -.30f, 1.10f, -.24f, .60f, .43f, .43f);
        box("screen", -.25f, 1.17f, -.252f, .50f, .28f, .02f);
        for (int side : new int[] {-1, 1}) {
            box("tile_blue", side < 0 ? -.35f : .30f, 1.20f, -.13f, .05f, .22f, .22f);
            box("light", side < 0 ? -.16f : .08f, 1.29f, -.265f, .08f, .10f, .018f);
            box("tile_blue", side < 0 ? -.22f : .06f, .38f, -.27f, .16f, .27f, .18f);
            box("ivory", side < 0 ? -.24f : .04f, .34f, -.35f, .20f, .10f, .29f);
            box("tile_blue", side < 0 ? -.31f : .23f, .75f, -.10f, .08f, .24f, .12f);
            box("ivory", side < 0 ? -.32f : .22f, .71f, -.23f, .10f, .10f, .23f);
        }
        box("light", -.07f, 1.23f, -.265f, .14f, .018f, .018f);
        box("brass", -.025f, 1.53f, -.02f, .05f, .14f, .05f);
        box("light", -.055f, 1.65f, -.05f, .11f, .08f, .11f);
        box("screen", -.09f, .84f, -.16f, .18f, .12f, .015f);
        box("light", -.055f, .88f, -.18f, .11f, .035f, .015f);
        boxes.forEach((name, cubes) -> parts.put(name, ElectricTableModel.bake(cubes)));
    }

    private void box(String material, float x, float y, float z, float w, float h, float d) {
        boxes.computeIfAbsent(material, key -> CubeListBuilder.create())
                .texOffs(0, 0)
                .addBox(x * 16, y * 16, z * 16, w * 16, h * 16, d * 16);
    }

    void submit(int seat, float time, PoseStack pose, SubmitNodeCollector queue, int light) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(seat * 90));
        pose.translate(0, .008 * Math.sin(time * 2 + seat), 2);
        parts.forEach(
                (material, part) ->
                        ElectricTableModel.draw(
                                part,
                                material,
                                pose,
                                queue,
                                material.equals("light") ? LightTexture.FULL_BRIGHT : light));
        pose.popPose();
    }
}
