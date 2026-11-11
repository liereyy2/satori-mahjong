package cn.satori.mahjong.client;

import cn.satori.mahjong.TableBlockEntity;
import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native picking and transport checks against an explicitly synthetic, loopback-only game. */
public final class HandInteractionFixture implements AutoCloseable {
    public final LocalAccountFixture http;
    public final ClientController client;
    private final CloudView previousView = SatoriClient.TABLES.active().view;
    private final TableScene previousScene = SatoriClient.TABLES.active().scene;
    private final GameConnection previousGame = SatoriClient.TABLES.active().game;
    private final CloudPresentation previousPresentation =
            SatoriClient.TABLES.active().presentation;
    private final String previousConnection = SatoriClient.TABLES.active().connection;

    public HandInteractionFixture() throws IOException {
        http = new LocalAccountFixture();
        client = SatoriClient.TABLES.active();
        client.scene = new TableScene();
        JsonObject raw;
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(getClass().getResourceAsStream("/cloud-view.json")),
                        StandardCharsets.UTF_8)) {
            raw = JsonParser.parseReader(reader).getAsJsonObject();
        }
        UUID game = previousView.gameId;
        raw.addProperty("game_id", game.toString());
        JsonArray hand = new JsonArray();
        for (int id : new int[] {5, 34, 4, 4, 3, 13, 35, 14, 22, 36, 23, 27, 28, 0}) {
            JsonObject tile = new JsonObject();
            tile.addProperty("tile_id", id);
            tile.addProperty("tile", CloudView.tileName(id));
            tile.addProperty("slot_id", "synthetic-" + hand.size());
            hand.add(tile);
        }
        raw.getAsJsonObject("private").add("hand", hand);
        raw.getAsJsonObject("display")
                .add("drawn_seats", JsonParser.parseString("[false,false,false,false]"));
        raw.getAsJsonObject("public").getAsJsonArray("hand_counts").set(0, new JsonPrimitive(14));
        JsonObject decision = new JsonObject();
        decision.addProperty("decision_id", "synthetic-hand-decision");
        JsonArray actions = new JsonArray();
        for (int id : new int[] {4, 34, 13, 35, 22, 36, 0}) {
            JsonObject action = new JsonObject();
            action.addProperty("action_id", "discard-" + id);
            action.addProperty("type", "dahai");
            action.addProperty("tile", CloudView.tileName(id));
            actions.add(action);
        }
        for (String type : List.of("reach", "ryukyoku")) {
            JsonObject action = new JsonObject();
            action.addProperty("action_id", "synthetic-" + type);
            action.addProperty("type", type);
            actions.add(action);
        }
        decision.add("actions", actions);
        raw.add("decision", decision);
        http.presentation = new JsonObject();
        for (String key : List.of("game_id", "viewer_seat", "view_version", "cursor"))
            http.presentation.add(key, raw.get(key));
        http.presentation.add("settlement", JsonNull.INSTANCE);
        http.presentation.add("hand_hint", JsonNull.INSTANCE);
        http.presentation.add("discard_hints", new JsonArray());
        client.view = new CloudView(raw, game, 0);
        client.scene.update(client.view, false, client.scene.now());
        JsonObject session = new JsonObject();
        session.addProperty("seat", 0);
        session.addProperty("seat_token", "synthetic-seat-token");
        client.game = new GameConnection(http.api, game, session, (v, e) -> {}, status -> {});
        client.connection = "connected";

        mount();
    }

    public void mount() {
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client));
    }

    public HandTileButton tile(int id, int occurrence) {
        return Minecraft.getInstance().screen.children().stream()
                .filter(HandTileButton.class::isInstance)
                .map(HandTileButton.class::cast)
                .filter(b -> b.face == id)
                .skip(occurrence)
                .findFirst()
                .orElseThrow();
    }

    public void verifyLayout() {
        var mc = Minecraft.getInstance();
        var regions = HandTilePicker.project(client, mc.screen.width, mc.screen.height);
        require(regions.size() == 14, "Own 14 tiles not projected: " + regions.size());
        int top = regions.stream().mapToInt(HandTilePicker.Region::top).min().orElseThrow();
        for (var region : regions) {
            require(region.corners().size() == 8, "Tile cuboid lost projected corners");
            require(
                    java.util.stream.IntStream.range(0, 8)
                            .anyMatch(
                                    i -> {
                                        var a = region.corners().get(i);
                                        var b = region.corners().get(i ^ 1);
                                        return Math.abs(a.x() - b.x()) > .01
                                                && Math.abs(a.y() - b.y()) > .01;
                                    }),
                    "Depth edges unexpectedly aligned to screen axes");
            require(
                    region.tile().seat() == 0 && region.tile().key().startsWith("hand-0-"),
                    "Opponent tile target");
            require(
                    region.contains(
                            (region.left() + region.right()) / 2.0,
                            (region.top() + region.bottom()) / 2.0),
                    "Tile center outside silhouette");
        }
        require(
                client.view
                        .hand(0)
                        .equals(List.of(0, 3, 4, 4, 34, 5, 13, 35, 14, 22, 36, 23, 27, 28)),
                "Red five order");
        require(!tile(5, 0).active, "Illegal discard enabled");
        require(
                !tile(4, 0).isMouseOver(tile(4, 0).getX() - 2, tile(4, 0).getY()),
                "Outside tile picked");
        for (var action : client.view.buttonActions()) {
            Button button =
                    mc.screen.children().stream()
                            .filter(Button.class::isInstance)
                            .map(Button.class::cast)
                            .filter(
                                    b ->
                                            b instanceof TileActionButton choice
                                                    && choice.actionId.equals(action.actionId()))
                            .findFirst()
                            .orElseThrow();
            require(
                    button.visible
                            && button.active
                            && button.getBottom() < top
                            && button.getY() >= 65,
                    "Decision dock not above hand");
        }
        for (var widget : mc.screen.children())
            if (widget instanceof Button button && !(button instanceof HandTileButton))
                require(
                        !button.getMessage().getString().startsWith("打出 "),
                        "Old discard button remains");
    }

    public void click(int id, int occurrence) {
        var tile = tile(id, occurrence);
        var event =
                new MouseButtonEvent(
                        tile.getX() + tile.getWidth() / 2.0,
                        tile.getY() + tile.getHeight() / 2.0,
                        new MouseButtonInfo(0, 0));
        boolean enabled = tile.active;
        boolean handled = Minecraft.getInstance().screen.mouseClicked(event, false);
        if (enabled) require(handled, "Native tile mouse click not handled");
        Minecraft.getInstance().screen.mouseReleased(event);
    }

    public void verifySelection(int face) {
        var mc = Minecraft.getInstance();
        var table = (TableBlockEntity) mc.level.getBlockEntity(SeatedCamera.center());
        var renderer =
                (TableRenderer)
                        mc.getBlockEntityRenderDispatcher()
                                .<TableBlockEntity, TableRenderer.State>getRenderer(table);
        var state = renderer.createRenderState();
        renderer.extractRenderState(
                table, state, 0, mc.gameRenderer.getMainCamera().position(), null);
        require(
                state.selected != null && state.selected.face() == face,
                "World outline selected the wrong tile");
        require(
                state.selectedEdges.size() >= 4 && state.selectedEdges.size() <= 9,
                "World outline contains self-occluded edges");
        var pos = SeatedCamera.center();
        var camera = mc.gameRenderer.getMainCamera().position();
        var eye =
                new org.joml.Vector3f(
                        (float) (camera.x - pos.getX()),
                        (float) (camera.y - pos.getY()),
                        (float) (camera.z - pos.getZ()));
        TileGeometry.transform(state.selected, state.rotation).invert().transformPosition(eye);
        int farthest = (eye.x < 0 ? 4 : 0) | (eye.y < 0 ? 2 : 0) | (eye.z < 0 ? 1 : 0);
        require(
                state.selectedEdges.stream()
                        .noneMatch(e -> e.from() == farthest || e.to() == farthest),
                "Rear-corner edges leak through the tile");
    }

    public boolean received(int id) {
        return !client.busy
                && http.submitted != null
                && http.submitted.get("action_id").getAsString().equals("discard-" + id);
    }

    public void verifyReceipt(int id) {
        require(received(id), "Wrong physical face submitted");
        require(
                http.submitted.get("decision_id").getAsString().equals("synthetic-hand-decision"),
                "Wrong decision submitted");
        require(http.submitted.has("command_id"), "Missing idempotent command identity");
        http.submitted = null;
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @Override
    public void close() {
        client.closeGame();
        http.close();
        SatoriClient.TABLES.active().view = previousView;
        SatoriClient.TABLES.active().scene = previousScene;
        client.game = previousGame;
        client.presentation = previousPresentation;
        client.connection = previousConnection;
        Minecraft.getInstance().setScreen(null);
    }
}
