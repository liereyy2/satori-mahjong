package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;

import org.joml.Vector3f;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real-client material views of an authorized Rust record, including face and blue backing. */
final class RoundedTileInteractionChecks {
    private CloudView savedView;
    private CloudPresentation savedPresentation;
    private TableScene savedScene;
    private Entity savedCamera;
    private Marker closeupCamera;
    private int savedFov;
    private long changedAt;

    private ClientController client() {
        return SatoriClient.TABLES.active();
    }

    private void open() {
        var mc = Minecraft.getInstance();
        savedView = client().view;
        savedPresentation = client().presentation;
        savedScene = client().scene;
        savedCamera = mc.getCameraEntity();
        savedFov = mc.options.fov().get();
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/electric-examples.json")),
                        StandardCharsets.UTF_8)) {
            var raw =
                    JsonParser.parseReader(reader)
                            .getAsJsonObject()
                            .getAsJsonObject("tenpai")
                            .getAsJsonObject("view")
                            .deepCopy();
            raw.addProperty("game_id", savedView.gameId.toString());
            client().view = new CloudView(raw, savedView.gameId, raw.get("viewer_seat").getAsInt());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        client().scene = new TableScene(() -> 10_000_000_000L);
        client().scene.update(client().view, false, client().scene.now());
        client().presentation = null;
        mc.setScreen(null);
    }

    private void focus(boolean back) {
        var mc = Minecraft.getInstance();
        String prefix =
                back
                        ? "hand-" + (client().view.viewerSeat + 2) % 4 + "-"
                        : "river-" + client().view.viewerSeat + "-";
        var tiles =
                TableLayout.layout(client().view).stream()
                        .filter(tile -> tile.key().startsWith(prefix))
                        .toList();
        if (tiles.isEmpty())
            throw new AssertionError("Missing real tiles for rounded-body capture");
        var tile = back ? tiles.getFirst() : tiles.getLast();
        float rotation =
                TableOrientation.sceneDegrees(client().view.viewerSeat, SeatedCamera.direction());
        var transform = TileGeometry.transform(tile, rotation);
        var target = transform.transformPosition(new Vector3f());
        var eye = transform.transformPosition(new Vector3f(.20f, .14f, back ? -.43f : .43f));
        var center = SeatedCamera.center();
        if (closeupCamera == null) closeupCamera = new Marker(EntityType.PLAYER, mc.level);
        closeupCamera.setPos(
                center.getX() + eye.x,
                center.getY() + eye.y - closeupCamera.getEyeHeight(),
                center.getZ() + eye.z);
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        closeupCamera.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        closeupCamera.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
        closeupCamera.setOldPosAndRot();
        mc.options.fov().set(40);
        mc.setCameraEntity(closeupCamera);
        changedAt = System.nanoTime();
    }

    private void verify() {
        var mc = Minecraft.getInstance();
        if (!client().seated() || client().view == null || mc.screen != null)
            throw new AssertionError("Rounded tile capture changed participation or view");
        if (closeupCamera != null && mc.getCameraEntity() != closeupCamera)
            throw new AssertionError("Closeup camera was replaced");
    }

    private void restore() {
        var mc = Minecraft.getInstance();
        mc.setCameraEntity(savedCamera);
        mc.options.fov().set(savedFov);
        client().view = savedView;
        client().presentation = savedPresentation;
        client().scene = savedScene;
        mc.setScreen(new SeatedTableScreen(client()));
    }

    List<HostedInteractionChecks.Step> steps() {
        return List.of(
                new HostedInteractionChecks.Step("open rounded tile views", () -> true, this::open),
                new HostedInteractionChecks.Step(
                        "capture-rounded-tiles-overview", () -> true, this::verify),
                new HostedInteractionChecks.Step(
                        "focus rounded face and side", () -> true, () -> focus(false)),
                new HostedInteractionChecks.Step(
                        "capture-rounded-tiles-closeup",
                        () -> System.nanoTime() - changedAt > 400_000_000L,
                        this::verify),
                new HostedInteractionChecks.Step(
                        "focus rounded blue backing", () -> true, () -> focus(true)),
                new HostedInteractionChecks.Step(
                        "capture-rounded-tiles-back",
                        () -> System.nanoTime() - changedAt > 400_000_000L,
                        this::verify),
                new HostedInteractionChecks.Step(
                        "restore rounded tile views", () -> true, this::restore));
    }
}
