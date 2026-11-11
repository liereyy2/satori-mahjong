package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Buffered real Rust events must finish before their current reaction buttons become visible. */
final class DecisionAnimationInteractionChecks {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private CloudView savedView;
    private CloudPresentation savedPresentation;
    private TableScene savedScene;

    private ClientController client() {
        return SatoriClient.TABLES.active();
    }

    private CloudView view(JsonObject source) {
        var raw = source.deepCopy();
        raw.addProperty("game_id", savedView.gameId.toString());
        return new CloudView(raw, savedView.gameId, savedView.viewerSeat);
    }

    private void open() {
        savedView = client().view;
        savedPresentation = client().presentation;
        savedScene = client().scene;
        JsonObject data;
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/decision-animation.json")),
                        StandardCharsets.UTF_8)) {
            data = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        client().presentation = null;
        client().scene = new TableScene(clock::get);
        client().view = view(data.getAsJsonObject("before"));
        client().scene.update(client().view, false, clock.get());
        for (var raw : data.getAsJsonArray("updates")) {
            var update = raw.getAsJsonObject();
            client().view = view(update.getAsJsonObject("view"));
            client().scene.append(client().view, update.getAsJsonArray("events"), clock.get());
        }
        if (client().view.buttonActions().isEmpty())
            throw new AssertionError("Missing real reaction actions");
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
    }

    private List<TileActionButton> buttons() {
        return Minecraft.getInstance().screen.children().stream()
                .filter(TileActionButton.class::isInstance)
                .map(TileActionButton.class::cast)
                .toList();
    }

    private void hidden() {
        if (!client().scene.animating(clock.get()) || buttons().isEmpty())
            throw new AssertionError("Buffered reaction animation was not exercised");
        if (buttons().stream().anyMatch(button -> button.visible || button.active))
            throw new AssertionError("Decision appeared before the tile animation queue completed");
    }

    private void restore() {
        client().view = savedView;
        client().presentation = savedPresentation;
        client().scene = savedScene;
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
    }

    List<HostedInteractionChecks.Step> steps() {
        return List.of(
                new HostedInteractionChecks.Step(
                        "load buffered reaction actions", () -> true, this::open),
                new HostedInteractionChecks.Step(
                        "capture-decisions-animating", () -> true, this::hidden),
                new HostedInteractionChecks.Step(
                        "finish an early queued tile motion",
                        () -> true,
                        () -> clock.addAndGet(200_000_000L)),
                new HostedInteractionChecks.Step(
                        "capture-decisions-still-buffered", () -> true, this::hidden),
                new HostedInteractionChecks.Step(
                        "finish the complete animation queue",
                        () -> true,
                        () -> clock.addAndGet(30_000_000_000L)),
                new HostedInteractionChecks.Step(
                        "capture-decisions-animation-complete",
                        () -> true,
                        () -> {
                            if (client().scene.animating(clock.get())
                                    || buttons().isEmpty()
                                    || buttons().stream()
                                            .anyMatch(button -> !button.visible || !button.active))
                                throw new AssertionError(
                                        "Current legal reactions did not appear after animation"
                                            + " completion");
                        }),
                new HostedInteractionChecks.Step(
                        "restore live view after decision timing", () -> true, this::restore));
    }
}
