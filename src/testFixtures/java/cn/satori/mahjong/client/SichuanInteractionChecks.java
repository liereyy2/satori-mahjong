package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Native world rendering and decision controls from actual Rust blood-battle projections. */
final class SichuanInteractionChecks {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private CloudView savedView;
    private TableScene savedScene;
    private CloudPresentation savedPresentation;
    private JsonObject examples;
    private JsonObject savedResult;
    private long exchangeStarted;

    private void startExchangeMotion() {
        var motion = resource("sichuan-exchange-motion-1-player");
        var before = motion.getAsJsonObject("before").deepCopy();
        var after = motion.getAsJsonObject("after").deepCopy();
        before.addProperty("game_id", savedView.gameId.toString());
        after.addProperty("game_id", savedView.gameId.toString());
        exchangeStarted = clock.addAndGet(10_000_000_000L);
        client().scene = new TableScene(clock::get);
        client().scene.update(new CloudView(before, savedView.gameId, 0), false, exchangeStarted);
        client().view = new CloudView(after, savedView.gameId, 0);
        client().scene.append(client().view, motion.getAsJsonArray("events"), exchangeStarted);
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
    }

    private void exchangeFrame(long offset) {
        clock.set(exchangeStarted + offset);
        var frame = client().scene.sample(clock.get());
        var moving = frame.stream().filter(t -> t.key().startsWith("exchange-")).toList();
        require(moving.size() == 12, "Exchange lost one of the twelve physical tiles");
        require(client().scene.animating(clock.get()), "Exchange animation ended too soon");
        require(
                client().scene.center(clock.get()).openingPhase().equals("exchange_three"),
                "Center advanced to dingque before the exchange finished");
        if (offset >= 440_000_000L && offset <= 1_040_000_000L)
            require(
                    moving.stream().allMatch(t -> t.face() == 37 && t.pitch() == 90),
                    "Exchange exposed concealed tile faces in transit");
        if (offset >= 1_600_000_000L)
            require(
                    moving.stream().allMatch(t -> t.y() > 1.3f),
                    "Received tiles did not lift over the standing hand");
    }

    private ClientController client() {
        return SatoriClient.TABLES.active();
    }

    private void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private void open() {
        savedView = client().view;
        savedScene = client().scene;
        savedPresentation = client().presentation;
        savedResult = client().result;
        try (var input = getClass().getResourceAsStream("/sichuan-examples.json")) {
            examples =
                    JsonParser.parseReader(
                                    new InputStreamReader(
                                            Objects.requireNonNull(input), StandardCharsets.UTF_8))
                            .getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        examples.add("exchange", resource("sichuan-exchange"));
        show("exchange");
    }

    private JsonObject resource(String name) {
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/" + name + ".json")),
                        StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void exchange() {
        var screen = (SeatedTableScreen) Minecraft.getInstance().screen;
        var choice = client().view.actions().getFirst();
        var faces = new ArrayList<>(CloudPresentation.actionTiles(choice));
        for (var child : screen.children())
            if (child instanceof HandTileButton tile && faces.remove((Integer) tile.face))
                tile.keyPressed(new net.minecraft.client.input.KeyEvent(257, 0, 0));
        require(
                faces.isEmpty() && screen.exchange.size() == 3,
                "Exchange did not select three physical hand tiles");
        require(
                screen.exchange
                        .action(client().view)
                        .orElseThrow()
                        .actionId()
                        .equals(choice.actionId()),
                "Selected tiles do not resolve to the native legal action");
    }

    private void settlement(boolean finished) {
        var data = resource(finished ? "sichuan-match" : "sichuan-draw");
        examples.add("result-view", data.getAsJsonObject("view"));
        show("result-view");
        var presentation = data.getAsJsonObject("presentation").deepCopy();
        presentation.addProperty("game_id", savedView.gameId.toString());
        client().presentation = new CloudPresentation(presentation, client().view);
        if (finished) {
            client().result = data.getAsJsonObject("result").deepCopy();
            client().result.addProperty("game_id", savedView.gameId.toString());
            Minecraft.getInstance().setScreen(new MatchResultScreen(client()));
        } else {
            var result = client().presentation.settlement();
            var screen = new SettlementScreen(client(), result);
            Minecraft.getInstance().setScreen(screen);
            for (int i = 1; i < result.getAsJsonArray("entries").size(); i++)
                screen.children().stream()
                        .filter(net.minecraft.client.gui.components.Button.class::isInstance)
                        .map(net.minecraft.client.gui.components.Button.class::cast)
                        .filter(b -> b.getMessage().getString().equals(">"))
                        .findFirst()
                        .orElseThrow()
                        .keyPressed(new net.minecraft.client.input.KeyEvent(257, 0, 0));
        }
    }

    private void showReady() {
        var data = resource("sichuan-ready");
        examples.add("ready", data.getAsJsonObject("view"));
        show("ready");
        var presentation = data.getAsJsonObject("presentation").deepCopy();
        presentation.addProperty("game_id", savedView.gameId.toString());
        client().presentation = new CloudPresentation(presentation, client().view);
        var hint = client().presentation.hint(null);
        require(
                hint != null && hint.shanten() == 0 && !hint.waits().isEmpty(),
                "Actual Sichuan ready hand did not reach the native hint bar");
    }

    private void showWinSettlement() {
        var data = resource("sichuan-win");
        examples.add("win-result", data.getAsJsonObject("view"));
        show("win-result");
        var presentation = data.getAsJsonObject("presentation").deepCopy();
        presentation.addProperty("game_id", savedView.gameId.toString());
        client().presentation = new CloudPresentation(presentation, client().view);
        var result = client().presentation.settlement();
        require(
                result.getAsJsonArray("entries")
                        .get(0)
                        .getAsJsonObject()
                        .get("kind")
                        .getAsString()
                        .equals("hora"),
                "Winning fixture must start with the actual win entry");
        Minecraft.getInstance().setScreen(new SettlementScreen(client(), result));
    }

    private void show(String name) {
        var raw = examples.getAsJsonObject(name).deepCopy();
        raw.addProperty("game_id", savedView.gameId.toString());
        client().view = new CloudView(raw, savedView.gameId, 0);
        client().scene = new TableScene(clock::get);
        client().presentation = null;
        client().scene.update(client().view, false, clock.get());
        clock.addAndGet(10_000_000_000L);
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
    }

    private void verify() {
        var view = client().view;
        require(view.ruleMode().equals("sichuan"), "Sichuan world view was replaced");
        var center = client().scene.center(clock.get());
        require(
                center.ruleMode().equals("sichuan") && center.matchRound() > 0,
                "Sichuan center labels missing");
        for (char c : "第16局川麻 · 血战到底缺万缺筒缺条已和定缺中换牌中".toCharArray())
            require(TableCenterGlyphs.width(String.valueOf(c), 23) > 0, "Missing world glyph " + c);
        if (view.decision() != null
                && view.decision().get("kind").getAsString().equals("dingque")) {
            var buttons =
                    Minecraft.getInstance().screen.children().stream()
                            .filter(TileActionButton.class::isInstance)
                            .map(TileActionButton.class::cast)
                            .toList();
            require(buttons.size() == 3, "Dingque needs three visible suit buttons");
            for (int s = 0; s < 3; s++) {
                String expected = Component.translatable("satori.action.dingque." + s).getString();
                require(
                        buttons.stream().anyMatch(b -> b.getMessage().getString().equals(expected)),
                        "Missing " + expected);
            }
        }
        for (var tile : client().scene.sample(clock.get()))
            if (tile.key().startsWith("hand-") && center.seats().get(tile.seat()).won())
                require(tile.pitch() != -90f, "Continuing winner's concealed tiles were revealed");
        if (center.seats().stream().anyMatch(TableCenter.Seat::won))
            require(
                    client().scene.sample(clock.get()).stream()
                            .anyMatch(t -> t.key().startsWith("win-") && t.pitch() == -90f),
                    "Public winning tile missing");
        TableActionLayoutChecks.verify();
    }

    List<HostedInteractionChecks.Step> steps() {
        return List.of(
                new HostedInteractionChecks.Step(
                        "open Sichuan native renderer",
                        () -> Minecraft.getInstance().screen instanceof SeatedTableScreen,
                        this::open),
                new HostedInteractionChecks.Step(
                        "select Sichuan exchange", () -> true, this::exchange),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-exchange", () -> true, this::verify),
                new HostedInteractionChecks.Step(
                        "start actual Sichuan exchange animation",
                        () -> true,
                        this::startExchangeMotion),
                new HostedInteractionChecks.Step(
                        "show Sichuan exchange send",
                        () -> true,
                        () -> clock.set(exchangeStarted + 440_000_000L)),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-exchange-send",
                        () -> true,
                        () -> exchangeFrame(440_000_000L)),
                new HostedInteractionChecks.Step(
                        "show Sichuan exchange transfer",
                        () -> true,
                        () -> clock.set(exchangeStarted + 740_000_000L)),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-exchange-transfer",
                        () -> true,
                        () -> exchangeFrame(740_000_000L)),
                new HostedInteractionChecks.Step(
                        "show Sichuan exchange receive",
                        () -> true,
                        () -> clock.set(exchangeStarted + 1_500_000_000L)),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-exchange-receive",
                        () -> true,
                        () -> exchangeFrame(1_500_000_000L)),
                new HostedInteractionChecks.Step(
                        "show Sichuan exchange insert",
                        () -> true,
                        () -> clock.set(exchangeStarted + 1_820_000_000L)),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-exchange-insert",
                        () -> true,
                        () -> exchangeFrame(1_820_000_000L)),
                new HostedInteractionChecks.Step(
                        "finish Sichuan exchange animation",
                        () -> true,
                        () -> {
                            clock.set(exchangeStarted + 2_200_000_000L);
                            require(
                                    !client().scene.animating(clock.get()),
                                    "Exchange never finished");
                            require(
                                    client().scene
                                            .sample(clock.get())
                                            .equals(TableScene.layout(client().view)),
                                    "Exchange did not converge to the cloud hand");
                        }),
                new HostedInteractionChecks.Step(
                        "show Sichuan dingque", () -> true, () -> show("dingque")),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-dingque", () -> true, this::verify),
                new HostedInteractionChecks.Step(
                        "show Sichuan active round", () -> true, () -> show("playing")),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-playing", () -> true, this::verify),
                new HostedInteractionChecks.Step(
                        "show Sichuan world without controls",
                        () -> true,
                        () -> Minecraft.getInstance().setScreen(null)),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-void-tiles",
                        () -> true,
                        () ->
                                require(
                                        client().scene
                                                .center(clock.get())
                                                .ruleMode()
                                                .equals("sichuan"),
                                        "Missing Sichuan world")),
                new HostedInteractionChecks.Step(
                        "restore Sichuan controls",
                        () -> true,
                        () -> Minecraft.getInstance().setScreen(new SeatedTableScreen(client()))),
                new HostedInteractionChecks.Step(
                        "show Sichuan ready hand", () -> true, this::showReady),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-ready",
                        () -> true,
                        () -> {
                            verify();
                            var panel =
                                    ((SeatedTableScreen) Minecraft.getInstance().screen)
                                            .fixedHint();
                            require(
                                    panel != null
                                            && panel.left()
                                                    >= Minecraft.getInstance().screen.width / 5
                                            && panel.right()
                                                    < Minecraft.getInstance().screen.width / 2,
                                    "Sichuan ready hint is not fixed inside the table area");
                        }),
                new HostedInteractionChecks.Step(
                        "show continuing blood-battle win", () -> true, () -> show("won")),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-winner",
                        () -> true,
                        () -> {
                            verify();
                            require(
                                    client().scene.center(clock.get()).seats().stream()
                                            .anyMatch(TableCenter.Seat::won),
                                    "Won-seat marker missing");
                        }),
                new HostedInteractionChecks.Step(
                        "show actual Sichuan winning settlement",
                        () -> true,
                        this::showWinSettlement),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-win",
                        () -> Minecraft.getInstance().screen instanceof SettlementScreen,
                        () -> {}),
                new HostedInteractionChecks.Step(
                        "show actual Sichuan exhaustive draw", () -> true, () -> settlement(false)),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-draw-hands",
                        () -> Minecraft.getInstance().screen instanceof SettlementScreen,
                        () -> {}),
                new HostedInteractionChecks.Step(
                        "scroll Sichuan settlement transfers",
                        () -> true,
                        () -> Minecraft.getInstance().screen.mouseScrolled(0, 0, 0, -20)),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-draw",
                        () -> Minecraft.getInstance().screen instanceof SettlementScreen,
                        () -> {}),
                new HostedInteractionChecks.Step(
                        "show actual Sichuan whole-match result",
                        () -> true,
                        () -> settlement(true)),
                new HostedInteractionChecks.Step(
                        "capture-sichuan-match",
                        () -> Minecraft.getInstance().screen instanceof MatchResultScreen,
                        () -> {}),
                new HostedInteractionChecks.Step(
                        "restore Riichi renderer",
                        () -> true,
                        () -> {
                            client().view = savedView;
                            client().scene = savedScene;
                            client().presentation = savedPresentation;
                            client().result = savedResult;
                            Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
                        }));
    }
}
