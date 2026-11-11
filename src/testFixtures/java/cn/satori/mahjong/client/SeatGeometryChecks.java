package cn.satori.mahjong.client;

import cn.satori.mahjong.TableSeatEntity;
import cn.satori.mahjong.TableSeating;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/** Measures real vanilla body vertices after riding, with both skins and scaled avatars. */
public final class SeatGeometryChecks {
    public static void verify(Player player, TableSeatEntity chair) {
        double original = player.getAttribute(Attributes.SCALE).getBaseValue();
        try {
            for (double scale : new double[] {.5, 1, 1.5}) {
                player.getAttribute(Attributes.SCALE).setBaseValue(scale);
                chair.positionRider(player);
                for (boolean slim : new boolean[] {false, true}) {
                    var model =
                            new PlayerModel(
                                    LayerDefinition.create(
                                                    PlayerModel.createMesh(
                                                            CubeDeformation.NONE, slim),
                                                    64,
                                                    64)
                                            .bakeRoot(),
                                    slim);
                    var state = new AvatarRenderState();
                    state.isPassenger = player.isPassenger();
                    model.setupAnim(state);
                    if (!state.isPassenger || model.leftLeg.xRot > -1 || model.rightLeg.xRot > -1)
                        throw new AssertionError("Seated avatar has standing legs");
                    model.jacket.visible = false;
                    var pose = new PoseStack();
                    pose.translate(0, player.getY(), 0);
                    pose.scale(player.getScale(), player.getScale(), player.getScale());
                    pose.scale(-1, -1, 1);
                    pose.scale(.9375f, .9375f, .9375f);
                    pose.translate(0, -1.501f, 0);
                    var vertices = new Bounds();
                    model.body.render(pose, vertices, 0, OverlayTexture.NO_OVERLAY);
                    double cushion =
                            TableSeating.chair(chair.center(), chair.direction()).getY() + .625;
                    if (Math.abs(vertices.minY - cushion) > .00002)
                        throw new AssertionError(
                                "Avatar pelvis misses cushion: "
                                        + vertices.minY
                                        + " vs "
                                        + cushion);
                }
            }
        } finally {
            player.getAttribute(Attributes.SCALE).setBaseValue(original);
            chair.positionRider(player);
        }
        System.out.println("SATORI_SEAT_VERIFIED side=" + chair.direction() + " scaled_models=6");
    }

    private static final class Bounds implements VertexConsumer {
        float minY = Float.POSITIVE_INFINITY;

        public VertexConsumer addVertex(float x, float y, float z) {
            minY = Math.min(minY, y);
            return this;
        }

        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        public VertexConsumer setColor(int argb) {
            return this;
        }

        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
