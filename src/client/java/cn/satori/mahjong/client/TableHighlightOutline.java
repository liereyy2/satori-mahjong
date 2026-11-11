package cn.satori.mahjong.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;

import org.joml.Vector3f;

/** Thick yellow box around the full 3×3 table assembly, with depth-tested world geometry. */
final class TableHighlightOutline {
    static void submit(PoseStack pose, SubmitNodeCollector queue) {
        queue.order(2)
                .submitCustomGeometry(
                        pose,
                        RenderTypes.lines(),
                        (matrix, vertices) -> {
                            for (int corner = 0; corner < 8; corner++) {
                                for (int axis = 0; axis < 3; axis++) {
                                    int other = corner ^ (1 << axis);
                                    if (other < corner) continue;
                                    var a = point(corner);
                                    var b = point(other);
                                    var direction = new Vector3f(b).sub(a).normalize();
                                    vertices.addVertex(matrix, a)
                                            .setColor(0xFFFFDF00)
                                            .setNormal(matrix, direction)
                                            .setLineWidth(5);
                                    vertices.addVertex(matrix, b)
                                            .setColor(0xFFFFDF00)
                                            .setNormal(matrix, direction)
                                            .setLineWidth(5);
                                }
                            }
                        });
    }

    private static Vector3f point(int corner) {
        return new Vector3f(
                (corner & 1) == 0 ? -1.52f : 1.52f,
                (corner & 2) == 0 ? -.02f : 1.32f,
                (corner & 4) == 0 ? -1.52f : 1.52f);
    }
}
