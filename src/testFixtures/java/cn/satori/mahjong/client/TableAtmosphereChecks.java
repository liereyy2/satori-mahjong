package cn.satori.mahjong.client;

import static cn.satori.mahjong.client.HandInteractionFixture.require;

import cn.satori.mahjong.TableBlockEntity;
import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Both loaders exercise the actual renderer using recorded Rust views and a fixed render clock. */
public final class TableAtmosphereChecks implements AutoCloseable {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private CloudView previousView;
    private TableScene previousScene;
    private JsonObject examples;
    private int actor;

    private CloudView view(JsonObject source) {
        var raw = source.deepCopy();
        raw.addProperty("game_id", previousView.gameId.toString());
        return new CloudView(raw, previousView.gameId, 0);
    }

    private void open() {
        var client = SatoriClient.TABLES.active();
        previousView = client.view;
        previousScene = client.scene;
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/electric-examples.json")),
                        StandardCharsets.UTF_8)) {
            examples = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        var example = examples.getAsJsonObject("opponent_draw");
        client.scene = new TableScene(clock::get);
        client.view = view(example.getAsJsonObject("before"));
        client.scene.update(client.view, false, clock.get());
        var update = example.getAsJsonObject("update");
        client.view = view(update.getAsJsonObject("view"));
        client.scene.update(client.view, update.getAsJsonArray("events"), clock.get());
        actor = client.view.decisionSeat();
        require(actor != 0 && actor >= 0, "Fixture must depict another player's turn");
        clock.set(1_250_000_000L);
        Minecraft.getInstance().setScreen(null);
    }

    private TableRenderer.State rendered() {
        var mc = Minecraft.getInstance();
        var table = (TableBlockEntity) mc.level.getBlockEntity(SeatedCamera.center());
        var renderer =
                mc.getBlockEntityRenderDispatcher()
                        .<TableBlockEntity, TableRenderer.State>getRenderer(table);
        var state = renderer.createRenderState();
        renderer.extractRenderState(
                table, state, 0, mc.gameRenderer.getMainCamera().position(), null);
        require(state.complete && state.lit, "Complete table must stay illuminated");
        require(
                state.lightCoords == LightTexture.FULL_BRIGHT,
                "Actual table renderer must use noon brightness");
        return state;
    }

    private void indicator(long now, boolean lit) {
        clock.set(now);
        var state = rendered();
        require(
                state.center.activeSeat() == actor,
                "Other player's indicator was overwritten by own view");
        require(state.blink == lit, "Other player's indicator failed to pulse");
    }

    private void frozenIndicator(int seat) {
        JsonObject source = null;
        for (var entry : examples.entrySet()) {
            var example = entry.getValue().getAsJsonObject();
            var views = new ArrayList<JsonObject>();
            if (example.has("before")) views.add(example.getAsJsonObject("before"));
            if (example.has("view")) views.add(example.getAsJsonObject("view"));
            if (example.has("update"))
                views.add(example.getAsJsonObject("update").getAsJsonObject("view"));
            for (var raw : views) {
                var active = raw.getAsJsonObject("display").get("decision_seat");
                if (raw.get("viewer_seat").getAsInt() == 0
                        && !active.isJsonNull()
                        && active.getAsInt() == seat) source = raw;
            }
        }
        require(source != null, "Missing real decision snapshot for seat " + seat);
        var client = SatoriClient.TABLES.active();
        clock.set(1_000_000_000L);
        client.view = view(source);
        client.scene = new TableScene(clock::get);
        client.scene.update(client.view, false, clock.get());
        actor = seat;
        indicator(1_000_000_000L, true);
    }

    private void riichi() {
        var client = SatoriClient.TABLES.active();
        var example = examples.getAsJsonObject("riichi_replacement");
        client.view = view(example.getAsJsonObject("update").getAsJsonObject("view"));
        client.scene = new TableScene(clock::get);
        client.scene.update(client.view, false, clock.get());
        var state = rendered();
        boolean replacement = false;
        var rivers = client.view.publicData().getAsJsonArray("discards");
        for (int seat = 0; seat < 4; seat++) {
            var river = rivers.get(seat).getAsJsonArray();
            boolean calledMarker = false;
            for (int index = 0; index < river.size(); index++) {
                var discard = river.get(index).getAsJsonObject();
                boolean marker = discard.get("is_riichi_marker").getAsBoolean();
                if (marker && discard.get("was_called").getAsBoolean()) calledMarker = true;
                else if (marker && calledMarker) {
                    String key = "river-" + seat + "-" + index;
                    require(
                            state.tiles.stream().anyMatch(t -> t.key().equals(key) && t.sideways()),
                            "Called riichi replacement not horizontal in native renderer");
                    replacement = true;
                }
            }
        }
        require(replacement, "Missing legal riichi replacement");
    }

    public List<PresentationInteractionChecks.Step> steps() {
        return List.of(
                new PresentationInteractionChecks.Step("atmosphere setup", () -> true, this::open),
                new PresentationInteractionChecks.Step(
                        "capture-electric-opponent-indicator-on",
                        () -> true,
                        () -> indicator(1_250_000_000L, true)),
                new PresentationInteractionChecks.Step(
                        "capture-electric-opponent-indicator-off",
                        () -> true,
                        () -> indicator(1_650_000_000L, false)),
                new PresentationInteractionChecks.Step(
                        "capture-electric-indicator-2-on", () -> true, () -> frozenIndicator(2)),
                new PresentationInteractionChecks.Step(
                        "capture-electric-indicator-2-off",
                        () -> true,
                        () -> indicator(1_400_000_000L, false)),
                new PresentationInteractionChecks.Step(
                        "capture-electric-indicator-3-on", () -> true, () -> frozenIndicator(3)),
                new PresentationInteractionChecks.Step(
                        "capture-electric-indicator-3-off",
                        () -> true,
                        () -> indicator(1_400_000_000L, false)),
                new PresentationInteractionChecks.Step(
                        "capture-electric-riichi-replacement", () -> true, this::riichi));
    }

    @Override
    public void close() {
        if (previousView == null) return;
        SatoriClient.TABLES.active().view = previousView;
        SatoriClient.TABLES.active().scene = previousScene;
    }
}
