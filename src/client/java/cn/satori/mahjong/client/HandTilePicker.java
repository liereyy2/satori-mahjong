package cn.satori.mahjong.client;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.*;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.util.*;

/** Projects the same tile transforms as TableRenderer into GUI coordinates. */
final class HandTilePicker {
    record Point(double x, double y) {}

    record Region(
            TableScene.Tile tile,
            List<Point> outline,
            List<Point> corners,
            int left,
            int top,
            int right,
            int bottom) {
        boolean contains(double x, double y) {
            for (int i = 0; i < outline.size(); i++)
                if (cross(outline.get(i), outline.get((i + 1) % outline.size()), new Point(x, y))
                        < -.001) return false;
            return outline.size() >= 3;
        }
    }

    static List<Region> project(ClientController client, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (!client.seated() || client.view == null || mc.level == null) return List.of();
        var center = SeatedCamera.center();
        if (!(mc.level.getBlockEntity(center) instanceof TableBlockEntity table)
                || !TableBlock.complete(mc.level, center)
                || !table.gameId().equals(client.view.gameId.toString())) return List.of();
        float rotation =
                TableOrientation.sceneDegrees(client.view.viewerSeat, SeatedCamera.direction());
        List<Region> regions = new ArrayList<>();
        var camera = mc.gameRenderer.getMainCamera();
        var eye = camera.position();
        var viewRotation = camera.rotation().conjugate(new Quaternionf());
        for (var tile :
                TableViewTiles.sample(
                        client,
                        center,
                        mc.gameRenderer.getMainCamera().position(),
                        client.scene.now())) {
            if (!tile.key().startsWith("hand-") || tile.seat() != client.view.viewerSeat) continue;
            Matrix4f model = TileGeometry.transform(tile, rotation);
            List<Point> points = new ArrayList<>();
            for (int x : new int[] {-1, 1})
                for (int y : new int[] {-1, 1})
                    for (int z : new int[] {-1, 1}) {
                        Vector3f world =
                                model.transformPosition(
                                        new Vector3f(
                                                x * TileGeometry.WIDTH / 2,
                                                y * TileGeometry.HEIGHT / 2,
                                                z * TileGeometry.THICKNESS / 2));
                        Vec3 projected =
                                mc.gameRenderer.projectPointToScreen(
                                        new Vec3(
                                                center.getX() + world.x(),
                                                center.getY() + world.y(),
                                                center.getZ() + world.z()));
                        float depth = -new Vector3f((float)(center.getX() + world.x() - eye.x),
                                (float)(center.getY() + world.y() - eye.y),
                                (float)(center.getZ() + world.z() - eye.z)).rotate(viewRotation).z;
                        var optical = TileLens.of(tile).projectNdc(new Vector3f(
                                (float)projected.x, (float)projected.y, (float)projected.z), depth);
                        projected = new Vec3(optical.x, optical.y, optical.z);
                        if (projected.z < -1
                                || projected.z >= 1
                                || !Double.isFinite(projected.x + projected.y)) continue;
                        points.add(
                                new Point(
                                        (projected.x + 1) * width / 2,
                                        (1 - projected.y) * height / 2));
                    }
            if (points.size() != 8) continue;
            List<Point> hull = hull(points);
            int left =
                    Math.max(
                            0,
                            (int)
                                    Math.floor(
                                            points.stream()
                                                    .mapToDouble(Point::x)
                                                    .min()
                                                    .orElseThrow()));
            int top =
                    Math.max(
                            0,
                            (int)
                                    Math.floor(
                                            points.stream()
                                                    .mapToDouble(Point::y)
                                                    .min()
                                                    .orElseThrow()));
            int right =
                    Math.min(
                            width,
                            (int)
                                    Math.ceil(
                                            points.stream()
                                                    .mapToDouble(Point::x)
                                                    .max()
                                                    .orElseThrow()));
            int bottom =
                    Math.min(
                            height,
                            (int)
                                    Math.ceil(
                                            points.stream()
                                                    .mapToDouble(Point::y)
                                                    .max()
                                                    .orElseThrow()));
            if (right > left && bottom > top)
                regions.add(new Region(tile, hull, List.copyOf(points), left, top, right, bottom));
        }
        return List.copyOf(regions);
    }

    private static double cross(Point a, Point b, Point c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    private static List<Point> hull(List<Point> points) {
        List<Point> sorted =
                points.stream()
                        .distinct()
                        .sorted(Comparator.comparingDouble(Point::x).thenComparingDouble(Point::y))
                        .toList();
        List<Point> result = new ArrayList<>();
        for (Point point : sorted) {
            while (result.size() >= 2
                    && cross(result.get(result.size() - 2), result.getLast(), point) <= 0)
                result.removeLast();
            result.add(point);
        }
        int lower = result.size();
        for (int i = sorted.size() - 2; i >= 0; i--) {
            Point point = sorted.get(i);
            while (result.size() > lower
                    && cross(result.get(result.size() - 2), result.getLast(), point) <= 0)
                result.removeLast();
            result.add(point);
        }
        result.removeLast();
        return List.copyOf(result);
    }
}
