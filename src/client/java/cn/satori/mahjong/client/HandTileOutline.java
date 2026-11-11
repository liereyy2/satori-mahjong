package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.TableScene;
import cn.satori.mahjong.protocol.TileGeometry;
import cn.satori.mahjong.protocol.TileOutline;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;

import org.joml.Vector3f;

import java.util.List;

/** World-space outline: self-hidden edges are omitted, other occluders use the depth buffer. */
final class HandTileOutline {
    static void submit(
            TableScene.Tile tile,
            List<TileOutline.Edge> edges,
            PoseStack pose,
            SubmitNodeCollector queue) {
        submit(tile, edges, pose, queue, 0xFFEBD4A2, 2);
    }

    static void submit(
            TableScene.Tile tile,
            List<TileOutline.Edge> edges,
            PoseStack pose,
            SubmitNodeCollector queue,
            int color,
            float width) {
        pose.pushPose();
        pose.mulPose(TileGeometry.localTransform(tile));
        // Submit after opaque tile shells. The native line pipeline keeps depth testing enabled.
        queue.order(1)
                .submitCustomGeometry(
                        pose,
                        RenderTypes.lines(),
                        (matrix, vertices) -> {
                            for (var edge : edges) {
                                var a = TileOutline.corner(edge.from());
                                var b = TileOutline.corner(edge.to());
                                var direction = new Vector3f(b).sub(a).normalize();
                                vertices.addVertex(matrix, a)
                                        .setColor(color)
                                        .setNormal(matrix, direction)
                                        .setLineWidth(width);
                                vertices.addVertex(matrix, b)
                                        .setColor(color)
                                        .setNormal(matrix, direction)
                                        .setLineWidth(width);
                            }
                        });
        pose.popPose();
    }
}
