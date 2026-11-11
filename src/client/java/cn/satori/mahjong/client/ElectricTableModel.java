package cn.satori.mahjong.client;

import cn.satori.mahjong.SatoriMahjong;
import cn.satori.mahjong.protocol.TileGeometry;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.*;

/** Beveled cabinet, felt, four real shutter openings and a recessed control console. */
final class ElectricTableModel {
    private final Map<String, CubeListBuilder> boxes = new LinkedHashMap<>();
    private final Map<String, ModelPart> parts = new LinkedHashMap<>();
    private final ModelPart shutter;

    ElectricTableModel() {
        box("steel", -1.47f, .71f, -1.47f, 2.94f, .06f, 2.94f);
        for (int side = 0; side < 4; side++) {
            rail(side, "walnut", -1.47f, .76f, 1.39f, 2.94f, .18f, .085f);
            rail(side, "brass", -1.46f, .915f, 1.46f, 2.92f, .018f, .02f);
            rail(side, "walnut", -1.48f, .94f, 1.35f, 2.96f, .07f, .13f);
            rail(side, "leather", -1.43f, 1.01f, 1.36f, 2.86f, .025f, .095f);
            rail(side, "brass", -1.39f, 1.012f, 1.342f, 2.78f, .009f, .012f);
            rail(side, "felt", -1.34f, .965f, 1.115f, 2.68f, .035f, .227f);
            rail(side, "steel", -.936f, .77f, .939f, 1.872f, .035f, .18f);
            rail(side, "brass", -.933f, .992f, .935f, 1.866f, .01f, .007f);
            rail(side, "brass", -.933f, .992f, 1.108f, 1.866f, .01f, .007f);
            rail(side, "screen", -.20f, 1.037f, 1.368f, .4f, .008f, .065f);
            rail(side, "light", -.055f, 1.046f, 1.389f, .11f, .001f, .012f);
            for (int vent = 0; vent < 14; vent++)
                rail(side, "steel", -.50f + vent * .074f, .79f, 1.477f, .045f, .08f, .003f);
            // Recessed cup tray with a metal rim.
            rail(side, "brass", .99f, 1.037f, 1.363f, .22f, .015f, .10f);
            rail(side, "screen", 1.008f, 1.053f, 1.377f, .184f, .002f, .074f);
        }
        box("felt", -.935f, .965f, -.935f, 1.87f, .035f, 1.87f);
        for (int x : new int[] {-1, 1})
            for (int z : new int[] {-1, 1})
                box(
                        "felt",
                        x < 0 ? -1.115f : .935f,
                        .965f,
                        z < 0 ? -1.115f : .935f,
                        .18f,
                        .035f,
                        .18f);
        float half = TileGeometry.CENTER_HALF;
        float frame = half - .013f, display = half - .04f;
        box("brass", -half, 1.002f, -half, half * 2, .014f, half * 2);
        box("steel", -frame, 1.018f, -frame, frame * 2, .018f, frame * 2);
        box("screen", -display, 1.037f, -display, display * 2, .003f, display * 2);
        for (int side = 0; side < 4; side++)
            rail(
                    side,
                    "light",
                    -display + .03f,
                    1.041f,
                    display - .014f,
                    (display - .03f) * 2,
                    .001f,
                    .009f);
        boxes.forEach((name, cubes) -> parts.put(name, bake(cubes)));
        shutter =
                bake(
                        CubeListBuilder.create()
                                .texOffs(0, 0)
                                .addBox(-14.94f, 15.53f, 15.03f, 29.88f, .42f, 2.76f));
    }

    private void box(String material, float x, float y, float z, float w, float h, float d) {
        boxes.computeIfAbsent(material, key -> CubeListBuilder.create())
                .texOffs(0, 0)
                .addBox(x * 16, y * 16, z * 16, w * 16, h * 16, d * 16);
    }

    private void rail(
            int side, String material, float x, float y, float z, float w, float h, float d) {
        switch (side) {
            case 0 -> box(material, x, y, z, w, h, d);
            case 1 -> box(material, -z - d, y, x, d, h, w);
            case 2 -> box(material, -x - w, y, -z - d, w, h, d);
            case 3 -> box(material, z, y, -x - w, d, h, w);
            default -> throw new IllegalArgumentException();
        }
    }

    static ModelPart bake(CubeListBuilder cubes) {
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("body", cubes, PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 64).bakeRoot().getChild("body");
    }

    static void draw(
            ModelPart part, String texture, PoseStack pose, SubmitNodeCollector queue, int light) {
        queue.submitModelPart(
                part,
                pose,
                RenderTypes.entitySolid(
                        SatoriMahjong.id("textures/block/furniture/" + texture + ".png")),
                light,
                OverlayTexture.NO_OVERLAY,
                null);
    }

    void submit(PoseStack pose, SubmitNodeCollector queue, int light, float opening, boolean lit) {
        parts.forEach(
                (material, part) ->
                        draw(
                                part,
                                material,
                                pose,
                                queue,
                                material.equals("light") && lit
                                        ? LightTexture.FULL_BRIGHT
                                        : light));
        for (int side = 0; side < 4; side++) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(side * 90));
            pose.translate(0, -.018 * opening, .19 * opening);
            draw(shutter, "felt", pose, queue, light);
            pose.popPose();
        }
    }
}
