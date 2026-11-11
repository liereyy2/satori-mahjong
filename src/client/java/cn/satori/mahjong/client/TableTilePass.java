package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.TableCenter;
import cn.satori.mahjong.protocol.TableScene;
import cn.satori.mahjong.protocol.TileGeometry;
import cn.satori.mahjong.protocol.TileLighting;
import cn.satori.mahjong.protocol.TileOcclusion;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/** Frame-local presentation only. Shader packs finish the room before these depth-tested tiles. */
public final class TableTilePass {
    private record Table(
            BlockPos pos, List<TableScene.Tile> tiles, float rotation, TableCenter center) {}

    private static final Map<BlockPos, Table> TABLES = new LinkedHashMap<>();
    private static final SequencedMap<RenderType, ByteBufferBuilder> BUFFERS =
            new LinkedHashMap<>();
    private static final ByteBufferBuilder SHARED = new ByteBufferBuilder(16384);
    private static final MultiBufferSource.BufferSource SOURCE =
            MultiBufferSource.immediateWithBuffers(BUFFERS, SHARED);
    private static final TableStickModel STICKS = new TableStickModel();
    private static TableTileInstances instances;
    private static final Map<BlockPos, TileOcclusion> OCCLUSION = new HashMap<>();
    private static final MultiBufferSource BATCHED =
            type -> {
                BUFFERS.computeIfAbsent(type, key -> new ByteBufferBuilder(16384));
                return SOURCE.getBuffer(type);
            };

    public static void beginFrame() {
        TABLES.clear();
        if (instances != null) instances.beginFrame();
    }

    static void collect(
            BlockPos pos, List<TableScene.Tile> tiles, float rotation, TableCenter center) {
        TABLES.put(pos.immutable(), new Table(pos.immutable(), tiles, rotation, center));
    }

    public static void render() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || TABLES.isEmpty()) return;
        OCCLUSION.keySet().retainAll(TABLES.keySet());
        var camera = mc.gameRenderer.getMainCamera();
        var eye = camera.position();
        var pose = new PoseStack();
        pose.mulPose(camera.rotation().conjugate(new Quaternionf()));
        try {
            for (var table : TABLES.values()) {
                pose.pushPose();
                pose.translate(
                        table.pos.getX() + .5 - eye.x,
                        table.pos.getY() - eye.y,
                        table.pos.getZ() + .5 - eye.z);
                pose.mulPose(Axis.YP.rotationDegrees(table.rotation));
                for (var tile : table.tiles) TableTileShadows.render(tile, pose, BATCHED);
                pose.popPose();
            }
            SOURCE.endBatch();
            for (var table : TABLES.values()) {
                pose.pushPose();
                pose.translate(
                        table.pos.getX() + .5 - eye.x,
                        table.pos.getY() - eye.y,
                        table.pos.getZ() + .5 - eye.z);
                pose.mulPose(Axis.YP.rotationDegrees(table.rotation));
                var lamp =
                        pose.last()
                                .pose()
                                .transformPosition(
                                        new Vector3f(0, 1.003f + TileLighting.height(), 0));
                var up = pose.last().pose().transformDirection(new Vector3f(0, 1, 0)).normalize();
                var visibility = OCCLUSION.computeIfAbsent(table.pos, key -> new TileOcclusion());
                float width = TileGeometry.WIDTH;
                visibility.update(
                        table.tiles.stream()
                                .filter(TileGeometry::isTileBody)
                                .map(
                                        tile ->
                                                new TileOcclusion.Pose(
                                                        tile.key(),
                                                        new Matrix4f()
                                                                .scaling(1 / width)
                                                                .mul(
                                                                        TileGeometry.localTransform(
                                                                                tile))
                                                                .scale(width)))
                                .toList(),
                        new Vector3f(0, (1.003f + TileLighting.height()) / width, 0));
                for (var tile : table.tiles) {
                    if (!TileGeometry.isTileBody(tile)) {
                        STICKS.render(tile, pose, BATCHED, lamp, up);
                        continue;
                    }
                    boolean voidTile =
                            table.center != null
                                    && table.center.ruleMode().equals("sichuan")
                                    && tile.key().startsWith("hand-")
                                    && tile.face() >= 0
                                    && tile.face() < 27
                                    && table.center.seats().get(tile.seat()).voidSuit()
                                            == tile.face() / 9;
                    if (instances == null) instances = new TableTileInstances();
                    pose.pushPose();
                    pose.mulPose(TileGeometry.localTransform(tile));
                    instances.add(
                            tile, new Matrix4f(pose.last().pose()), lamp, up, voidTile, visibility);
                    pose.popPose();
                }
                pose.popPose();
            }
            SOURCE.endBatch();
            if (instances != null) instances.draw();
        } finally {
            TABLES.clear();
        }
    }

    public static void close() {
        TABLES.clear();
        OCCLUSION.clear();
        BUFFERS.values().forEach(ByteBufferBuilder::close);
        BUFFERS.clear();
        SHARED.close();
        if (instances != null) {
            instances.close();
            instances = null;
        }
    }
}
