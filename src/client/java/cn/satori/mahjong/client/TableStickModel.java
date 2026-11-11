package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.TableScene;
import cn.satori.mahjong.protocol.TileLighting;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.renderer.*;

import org.joml.Vector3f;

/** Riichi sticks use native cubes; tile bodies use the shared instanced mesh. */
final class TableStickModel {
    private final ModelPart stick =
            ElectricTableModel.bake(
                    CubeListBuilder.create().addBox(-2.1f, -.045f, -.14f, 4.2f, .09f, .28f));
    private final ModelPart dot =
            ElectricTableModel.bake(
                    CubeListBuilder.create().addBox(-.11f, 0, -.09f, .22f, .05f, .18f));

    void render(
            TableScene.Tile tile,
            PoseStack pose,
            MultiBufferSource buffers,
            Vector3f lamp,
            Vector3f up) {
        pose.pushPose();
        pose.translate(tile.x(), tile.y(), tile.z());
        pose.scale(tile.scale(), tile.scale(), tile.scale());
        pose.mulPose(Axis.YP.rotationDegrees(tile.yaw()));
        stick.render(
                pose,
                buffers.getBuffer(TableTileMaterial.furniture("ivory")),
                TileLighting.packedLight(lamp, up),
                TileLighting.packedOverlay(lamp));
        dot.render(
                pose,
                buffers.getBuffer(TableTileMaterial.furniture("red")),
                TileLighting.packedLight(lamp, up),
                TileLighting.packedOverlay(lamp));
        pose.popPose();
    }
}
