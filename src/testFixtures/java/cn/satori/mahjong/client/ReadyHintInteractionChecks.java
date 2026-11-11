package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real Rust standing hands in the native screen, with seat and camera preserved. */
final class ReadyHintInteractionChecks {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private CloudView savedView;
    private CloudPresentation savedPresentation;
    private TableScene savedScene;
    private JsonObject examples;
    private SeatedTableHints.Panel previous;
    private TableCameraFraming.Settings cameraSettings;
    private long cameraChangedAt;

    private ClientController client() {
        return SatoriClient.TABLES.active();
    }

    private SeatedTableScreen screen() {
        return (SeatedTableScreen) Minecraft.getInstance().screen;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void open() {
        savedView = client().view;
        savedPresentation = client().presentation;
        savedScene = client().scene;
        try (var input =
                new InputStreamReader(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/electric-examples.json")),
                        StandardCharsets.UTF_8)) {
            examples = JsonParser.parseReader(input).getAsJsonObject();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        load("tenpai");
    }

    private void load(String name) {
        var data = examples.getAsJsonObject(name);
        var raw = data.getAsJsonObject("view").deepCopy();
        var presentation = data.getAsJsonObject("presentation").deepCopy();
        raw.addProperty("game_id", savedView.gameId.toString());
        presentation.addProperty("game_id", savedView.gameId.toString());
        client().view = new CloudView(raw, savedView.gameId, raw.get("viewer_seat").getAsInt());
        client().presentation = new CloudPresentation(presentation, client().view);
        client().scene = new TableScene(clock::get);
        client().scene.update(client().view, false, clock.get());
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
    }

    private void verify() {
        var panel = screen().fixedHint();
        require(panel != null && !panel.waits().isEmpty(), "Standing-hand hint not visible");
        var hand = HandTilePicker.project(client(), screen().width, screen().height);
        int handTop = hand.stream().mapToInt(HandTilePicker.Region::top).min().orElseThrow();
        require(panel.bottom() <= handTop - 6, "Fixed hint overlaps the hand");
        require(panel.right() < screen().width / 2, "Fixed hint is not left of the self river");
        require(
                panel.left() >= screen().width / 5 && panel.top() >= 36,
                "Fixed hint remained outside the table area");
        previous = panel;
    }

    private void pointerAway() {
        var mc = Minecraft.getInstance();
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(
                mc.getWindow().handle(), mc.getWindow().getScreenWidth() - 4, 4);
    }

    private void opponentAnimation() {
        var data = examples.getAsJsonObject("tenpai");
        var raw = data.getAsJsonObject("view").deepCopy();
        raw.addProperty("game_id", savedView.gameId.toString());
        raw.addProperty("view_version", client().view.version + 1);
        client().view = new CloudView(raw, savedView.gameId, raw.get("viewer_seat").getAsInt());
        client().presentation = null;
        client().scene
                .update(
                        client().view,
                        JsonParser.parseString("[{\"type\":\"reach\",\"actor\":1}]")
                                .getAsJsonArray(),
                        clock.get());
        clock.addAndGet(50_000_000L);
        require(client().scene.animating(clock.get()), "Opponent animation was not exercised");
    }

    private void restore() {
        if (cameraSettings != null) SeatedCamera.settings(cameraSettings);
        TableActionLayoutChecks.resize(false);
        client().view = savedView;
        client().presentation = savedPresentation;
        client().scene = savedScene;
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
    }

    List<HostedInteractionChecks.Step> steps() {
        return List.of(
                new HostedInteractionChecks.Step(
                        "open standing-hand hint fixture", () -> true, this::open),
                new HostedInteractionChecks.Step(
                        "capture-ready-riichi", () -> screen().fixedHint() != null, this::verify),
                new HostedInteractionChecks.Step(
                        "move pointer off the hand", () -> true, this::pointerAway),
                new HostedInteractionChecks.Step(
                        "fixed hint ignores pointer",
                        () -> true,
                        () ->
                                require(
                                        screen().fixedHint().equals(previous),
                                        "Pointer moved the fixed hint")),
                new HostedInteractionChecks.Step(
                        "change camera while showing waits",
                        () -> true,
                        () -> {
                            cameraSettings = SeatedCamera.settings();
                            SeatedCamera.settings(cameraSettings.withPitch(58).withYaw(12));
                            cameraChangedAt = System.nanoTime();
                        }),
                new HostedInteractionChecks.Step(
                        "capture-ready-fixed-camera",
                        () -> System.nanoTime() - cameraChangedAt > 350_000_000L,
                        () -> {
                            var panel = screen().fixedHint();
                            require(
                                    panel != null
                                            && panel.left() == previous.left()
                                            && panel.top() == previous.top(),
                                    "Camera moved the fixed hint");
                            SeatedCamera.settings(cameraSettings);
                        }),
                new HostedInteractionChecks.Step(
                        "animate opponent with a ready hand", () -> true, this::opponentAnimation),
                new HostedInteractionChecks.Step(
                        "capture-ready-opponent-animation",
                        () -> true,
                        () -> {
                            require(
                                    client().scene.animating(clock.get()),
                                    "Opponent animation ended before capture");
                            verify();
                        }),
                new HostedInteractionChecks.Step(
                        "finish hint animation", () -> true, () -> clock.addAndGet(5_000_000_000L)),
                new HostedInteractionChecks.Step(
                        "small ready-hand window",
                        () -> true,
                        () -> TableActionLayoutChecks.resize(true)),
                new HostedInteractionChecks.Step(
                        "capture-ready-riichi-small",
                        () -> screen().fixedHint() != null,
                        this::verify),
                new HostedInteractionChecks.Step(
                        "load standing hand without yaku", () -> true, () -> load("no_yaku")),
                new HostedInteractionChecks.Step(
                        "capture-ready-no-yaku",
                        () -> screen().fixedHint() != null,
                        () -> {
                            var panel = screen().fixedHint();
                            require(
                                    panel != null
                                            && panel.left() == previous.left()
                                            && panel.bottom() == previous.bottom(),
                                    "Wait content moved its fixed lower anchor");
                            verify();
                            require(screen().fixedHint().hint().noYaku(), "No-yaku warning lost");
                        }),
                new HostedInteractionChecks.Step(
                        "presentation queries do not clear standing waits",
                        () -> true,
                        () -> {
                            client().presentation = null;
                            require(
                                    screen().fixedHint() != null,
                                    "Standing waits disappeared with asynchronous presentation");
                        }),
                new HostedInteractionChecks.Step(
                        "restore standing-hand fixture", () -> true, this::restore));
    }
}
