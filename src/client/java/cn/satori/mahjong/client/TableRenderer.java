package cn.satori.mahjong.client;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.*;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.*;

/** Snapshot-based submit renderer; the same geometry and animation on both native loaders. */
public final class TableRenderer
        implements BlockEntityRenderer<TableBlockEntity, TableRenderer.State> {
    public static final class State extends BlockEntityRenderState {
        List<TableScene.Tile> tiles = List.of();
        List<TableDice.Die> dice = List.of();
        boolean complete, lit;
        float shutter, rotation, time;
        boolean blink, highlighted;
        List<Integer> robots = List.of();
        TableCenter center;
        TableScene.Tile selected;
        List<TileOutline.Edge> selectedEdges = List.of();
        TableScene.Tile reaction;
        List<TileOutline.Edge> reactionEdges = List.of();
    }

    private final TableCenterRenderer center;
    private final TableRobotModel robot = new TableRobotModel();
    private final ElectricTableModel furniture = new ElectricTableModel();
    private final TableDiceModel diceModel = new TableDiceModel();

    public TableRenderer(BlockEntityRendererProvider.Context context) {
        center = new TableCenterRenderer();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(
            TableBlockEntity table,
            State state,
            float delta,
            Vec3 camera,
            ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
        BlockEntityRenderer.super.extractRenderState(table, state, delta, camera, overlay);
        ClientController c = SatoriClient.TABLES.forTable(table);
        state.tiles = List.of();
        state.dice = List.of();
        state.robots = List.of();
        state.time = (System.nanoTime() % 60_000_000_000L) / 1_000_000_000f;
        state.blink = false;
        state.center = null;
        state.shutter = 0;
        state.rotation = 0;
        state.selected = null;
        state.selectedEdges = List.of();
        state.reaction = null;
        state.reactionEdges = List.of();
        state.highlighted = SatoriClient.TABLES.worldTables.highlighted(table);
        state.complete =
                table.getLevel() != null
                        && TableBlock.complete(table.getLevel(), table.getBlockPos());
        if (!state.complete) return;
        state.lightCoords =
                LevelRenderer.getLightColor(table.getLevel(), table.getBlockPos().above());
        state.lit = table.getBlockState().getValue(TableBlock.LIT);
        var observer = SatoriClient.TABLES.worldTables.forTable(table);
        boolean physicallySeated =
                Minecraft.getInstance().player != null
                        && Minecraft.getInstance().player.getVehicle()
                                instanceof TableSeatEntity chair
                        && chair.center().equals(table.getBlockPos());
        if (observer != null && (!physicallySeated || !observer.view.tableObserver())) {
            long now = observer.scene.now();
            state.tiles = observer.scene.sample(now);
            state.dice = observer.scene.dice(now);
            state.center = observer.scene.center(now);
            state.blink = observer.scene.indicatorLit(now);
            state.shutter = observer.scene.shutter(now);
            state.robots = observer.view.aiSeats();
            reaction(table, state, observer.scene, now, camera);
        }
        if (c == null) return;
        if (c.hosted != null && c.hosted.table() != null) {
            var robots = new java.util.ArrayList<Integer>();
            for (var raw : c.hosted.table().getAsJsonArray("seats")) {
                var row = raw.getAsJsonObject();
                if (row.get("kind").getAsString().equals("ai"))
                    robots.add(row.get("seat").getAsInt());
            }
            state.robots = java.util.List.copyOf(robots);
        }
        if (c.view != null
                && c.view.gameId.toString().equals(table.gameId())
                && (c.hosted == null || c.seated())) {
            long now = c.scene.now();
            state.blink = c.scene.indicatorLit(now);
            state.tiles = TableViewTiles.sample(c, table.getBlockPos(), camera, now);
            state.dice = c.scene.dice(now);
            state.shutter = c.scene.shutter(now);
            state.rotation = TableOrientation.sceneDegrees(c.view.viewerSeat, c.seatDirection);
            state.center = c.scene.center(now);
            state.robots = c.view.aiSeats();
            reaction(table, state, c.scene, now, camera);
            if (c.seated()
                    && table.getBlockPos().equals(SeatedCamera.center())
                    && Minecraft.getInstance().screen instanceof SeatedTableScreen screen
                    && screen.controller() == c) {
                String key = screen.selectedTile();
                for (var tile : state.tiles) {
                    if (!tile.key().equals(key)) continue;
                    var pos = table.getBlockPos();
                    var eye =
                            new Vector3f(
                                    (float) (camera.x - pos.getX()),
                                    (float) (camera.y - pos.getY()),
                                    (float) (camera.z - pos.getZ()));
                    TileGeometry.transform(tile, state.rotation).invert().transformPosition(eye);
                    state.selected = tile;
                    state.selectedEdges = TileOutline.visibleEdges(eye);
                    break;
                }
            }
        }
        if (Minecraft.getInstance().screen instanceof CreateScreen create
                && create.client == c
                && c.table != null
                && c.table.pos().equals(table.getBlockPos())) {
            state.robots = create.aiSeats();
            if (c.seated())
                state.rotation =
                        TableOrientation.sceneDegrees(
                                create.referenceSeat(), SeatedCamera.direction());
        }
    }

    private static void reaction(
            TableBlockEntity table, State state, TableScene scene, long now, Vec3 camera) {
        state.reaction = null;
        state.reactionEdges = List.of();
        if (!scene.reactionLit(now)) return;
        String key = scene.reactionDiscard(now);
        for (var tile : state.tiles) {
            if (!tile.key().equals(key)) continue;
            var pos = table.getBlockPos();
            var eye =
                    new Vector3f(
                            (float) (camera.x - pos.getX()),
                            (float) (camera.y - pos.getY()),
                            (float) (camera.z - pos.getZ()));
            TileGeometry.transform(tile, state.rotation).invert().transformPosition(eye);
            state.reaction = tile;
            state.reactionEdges = TileOutline.visibleEdges(eye);
            return;
        }
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public boolean shouldRender(TableBlockEntity table, Vec3 camera) {
        return BlockEntityRenderer.super.shouldRender(table, camera)
                && TableVisibility.visible(table.getBlockPos());
    }

    @Override
    public int getViewDistance() {
        return 40;
    }

    @Override
    public void submit(
            State state, PoseStack pose, SubmitNodeCollector queue, CameraRenderState camera) {
        if (!state.complete) return;
        pose.pushPose();
        pose.translate(.5, 0, .5);
        if (state.highlighted) TableHighlightOutline.submit(pose, queue);
        furniture.submit(pose, queue, state.lightCoords, state.shutter, state.lit);
        pose.mulPose(Axis.YP.rotationDegrees(state.rotation));
        TableTilePass.collect(state.blockPos, state.tiles, state.rotation, state.center);
        for (var die : state.dice) diceModel.submit(die, pose, queue, state.lightCoords);
        center.submit(state.center, pose, queue, state.blink);
        for (int seat : state.robots)
            robot.submit(seat, state.time, pose, queue, state.lightCoords);
        if (state.selected != null)
            HandTileOutline.submit(state.selected, state.selectedEdges, pose, queue);
        if (state.reaction != null)
            HandTileOutline.submit(
                    state.reaction, state.reactionEdges, pose, queue, 0xFFFFDF00, 3.5f);
        pose.popPose();
    }
}
