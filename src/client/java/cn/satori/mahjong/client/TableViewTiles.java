package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.*;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/** One fixed seated-view projection for the native model, picking hull and selection edges. */
final class TableViewTiles {
    private record Frame(
            int viewer,
            int direction,
            TableCameraFraming.Settings settings,
            int width,
            int height) {}

    private static Frame lastFrame;
    private static TileViewTransform lastProjection;

    static List<TableScene.Tile> sample(
            ClientController client, BlockPos center, Vec3 camera, long now) {
        if (!client.seated() || !center.equals(SeatedCamera.center()))
            return client.scene.sample(now);
        var mc = Minecraft.getInstance();
        var frame =
                new Frame(
                        client.view.viewerSeat,
                        SeatedCamera.direction(),
                        SeatedCamera.settings(),
                        mc.getWindow().getWidth(),
                        mc.getWindow().getHeight());
        if (!frame.equals(lastFrame)) {
            float aspect = (float) frame.width / frame.height;
            var target = TableCameraFraming.fit(frame.settings, frame.direction, aspect);
            var p = target.eye();
            var eye = new Vector3f((float) p.x(), (float) p.y(), (float) p.z());
            double yaw = Math.toRadians(target.yaw()), pitch = Math.toRadians(target.pitch());
            var forward =
                    new Vector3f(
                            (float) (-Math.sin(yaw) * Math.cos(pitch)),
                            (float) -Math.sin(pitch),
                            (float) (Math.cos(yaw) * Math.cos(pitch)));
            float rotation = TableOrientation.sceneDegrees(frame.viewer, frame.direction);
            // The reference belongs to the seated table view. Entry and close-up cameras move
            // around this model naturally; they never aim or reproject each hand tile.
            var view =
                    new Matrix4f()
                            .lookAt(eye, new Vector3f(eye).add(forward), new Vector3f(0, 1, 0))
                            .rotateY((float) Math.toRadians(rotation));
            lastProjection =
                    ReferenceHandProjection.fit(
                            view,
                            mc.gameRenderer.getProjectionMatrix((float) TableCameraFraming.FOV),
                            aspect,
                            frame.viewer);
            lastFrame = frame;
        }
        var projection = lastProjection;
        return client.scene.sample(
                now,
                tile -> ReferenceHandProjection.apply(tile, client.view.viewerSeat, projection));
    }
}
