package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native settlement captures use real Rust records containing four meld groups. */
final class SettlementRowInteractionChecks {
    private CloudView savedView;
    private CloudPresentation savedPresentation;
    private TableScene savedScene;
    private JsonObject examples;

    private ClientController client() {
        return SatoriClient.TABLES.active();
    }

    private void open() {
        savedView = client().view;
        savedPresentation = client().presentation;
        savedScene = client().scene;
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/electric-examples.json")),
                        StandardCharsets.UTF_8)) {
            examples = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        show("settlement_hora");
    }

    private void show(String name) {
        var data = examples.getAsJsonObject(name);
        var raw = data.getAsJsonObject("view").deepCopy();
        var presentation = data.getAsJsonObject("presentation").deepCopy();
        raw.addProperty("game_id", savedView.gameId.toString());
        presentation.addProperty("game_id", savedView.gameId.toString());
        client().view = new CloudView(raw, savedView.gameId, raw.get("viewer_seat").getAsInt());
        client().scene = new TableScene();
        client().scene.update(client().view, false, client().scene.now());
        client().presentation = new CloudPresentation(presentation, client().view);
        var result = client().presentation.settlement();
        var entry = result.getAsJsonArray("entries").get(0).getAsJsonObject();
        var melds =
                name.equals("settlement_hora")
                        ? entry.getAsJsonArray("melds")
                        : entry.getAsJsonArray("hands")
                                .get(0)
                                .getAsJsonObject()
                                .getAsJsonArray("melds");
        if (melds.size() != 4) throw new AssertionError("Real four-meld settlement missing");
        Minecraft.getInstance().setScreen(new SettlementScreen(client(), result));
    }

    private void verify() {
        if (!(Minecraft.getInstance().screen instanceof SettlementScreen))
            throw new AssertionError("Settlement capture lost its screen");
    }

    private void restore() {
        TableActionLayoutChecks.resize(false);
        client().view = savedView;
        client().presentation = savedPresentation;
        client().scene = savedScene;
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
    }

    List<HostedInteractionChecks.Step> steps() {
        return List.of(
                new HostedInteractionChecks.Step(
                        "load four-meld win settlement", () -> true, this::open),
                new HostedInteractionChecks.Step(
                        "capture-settlement-meld-win", () -> true, this::verify),
                new HostedInteractionChecks.Step(
                        "small four-meld settlement",
                        () -> true,
                        () -> TableActionLayoutChecks.resize(true)),
                new HostedInteractionChecks.Step(
                        "capture-settlement-meld-win-small", () -> true, this::verify),
                new HostedInteractionChecks.Step(
                        "load four-meld draw settlement",
                        () -> true,
                        () -> show("settlement_ryukyoku")),
                new HostedInteractionChecks.Step(
                        "capture-settlement-meld-draw-small", () -> true, this::verify),
                new HostedInteractionChecks.Step(
                        "normal four-meld draw settlement",
                        () -> true,
                        () -> TableActionLayoutChecks.resize(false)),
                new HostedInteractionChecks.Step(
                        "capture-settlement-meld-draw", () -> true, this::verify),
                new HostedInteractionChecks.Step(
                        "restore table after settlement rows", () -> true, this::restore));
    }
}
