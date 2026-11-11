package cn.satori.mahjong.client;

import static cn.satori.mahjong.client.HandInteractionFixture.require;

import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Shared native display checks use real Rust records and an isolated HTTP seat endpoint. */
public final class PresentationInteractionChecks implements AutoCloseable {
    public record Step(String name, BooleanSupplier ready, Runnable action) {}

    private LocalAccountFixture http;
    private ClientController client;
    private JsonObject examples;
    private CloudView previousView;
    private TableScene previousScene;
    private CloudPresentation previousPresentation;
    private int savedWidth, savedHeight, savedGuiScale;

    private void open() {
        String kan =
                Minecraft.getInstance().getLanguageManager().getSelected().startsWith("zh_")
                        ? "杠"
                        : "Kan";
        for (String action : List.of("ankan", "daiminkan", "kakan"))
            require(
                    Component.translatable("satori.action." + action).getString().equals(kan),
                    "Kan label language mismatch");
        previousView = SatoriClient.TABLES.active().view;
        previousScene = SatoriClient.TABLES.active().scene;
        previousPresentation = SatoriClient.TABLES.active().presentation;
        SatoriClient.TABLES.active().presentation = null;
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/electric-examples.json")),
                        StandardCharsets.UTF_8)) {
            examples = JsonParser.parseReader(reader).getAsJsonObject();
            http = new LocalAccountFixture();
            client = new ClientController(http.api, http.config);
            client.dimension = SatoriClient.TABLES.active().dimension;
            client.table = SatoriClient.TABLES.active().table;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        http.presentationStatus = 404;
        mount("settlement_hora");
    }

    private void mount(String name) {
        mount(name, false);
    }

    private void mount(String name, boolean malformed) {
        client.closeGame();
        var example = examples.getAsJsonObject(name);
        var raw = example.getAsJsonObject("view").deepCopy();
        var presentation = example.getAsJsonObject("presentation").deepCopy();
        var game = previousView.gameId;
        raw.addProperty("game_id", game.toString());
        presentation.addProperty("game_id", game.toString());
        int seat = raw.get("viewer_seat").getAsInt();
        client.view = new CloudView(raw, game, seat);
        client.scene = new TableScene();
        client.scene.update(client.view, false, client.scene.now());
        JsonObject session = new JsonObject();
        session.addProperty("seat", seat);
        session.addProperty("seat_token", "synthetic-seat-token");
        client.game = new GameConnection(http.api, game, session, (v, e) -> {}, status -> {});
        client.connection = "connected";
        http.presentation = malformed ? new JsonObject() : presentation;
        http.submitted = null;
        SatoriClient.TABLES.active().view = client.view;
        SatoriClient.TABLES.active().scene = client.scene;
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client));
        client.tick();
    }

    private boolean loaded() {
        client.tick();
        return client.presentation != null && !client.busy;
    }

    private boolean rejected(String error) {
        client.tick();
        return client.presentationError.equals(error);
    }

    private Button button(String key) {
        String text = Component.translatable(key).getString();
        return Minecraft.getInstance().screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.getMessage().getString().equals(text))
                .findFirst()
                .orElseThrow();
    }

    public List<Step> steps() {
        return List.of(
                new Step("display setup", () -> true, this::open),
                new Step(
                        "missing deployed endpoint",
                        () -> rejected("http_404"),
                        () -> {
                            require(client.presentation == null, "404 produced a presentation");
                            require(
                                    Minecraft.getInstance().screen instanceof SeatedTableScreen,
                                    "404 produced a settlement");
                            require(client.statusMessage().equals("http_404"), "404 cause hidden");
                            http.presentationStatus = 200;
                        }),
                new Step(
                        "automatic settlement",
                        this::loaded,
                        () -> {
                            require(
                                    Minecraft.getInstance().screen instanceof SettlementScreen,
                                    "Round end did not open settlement");
                            var result = client.presentation.settlement();
                            require(
                                    result.getAsJsonArray("entries")
                                                    .get(0)
                                                    .getAsJsonObject()
                                                    .getAsJsonArray("yakus")
                                                    .size()
                                            > 0,
                                    "Missing authoritative yaku");
                            require(
                                    http.presentations.get() > 0,
                                    "Presentation did not use seat HTTP endpoint");
                            require(
                                    client.presentationError.isEmpty(),
                                    "Recovered request kept error");
                        }),
                new Step("capture-electric-settlement", () -> true, () -> {}),
                new Step(
                        "small settlement window",
                        () -> true,
                        () -> {
                            var mc = Minecraft.getInstance();
                            savedWidth = mc.getWindow().getWidth();
                            savedHeight = mc.getWindow().getHeight();
                            savedGuiScale = mc.options.guiScale().get();
                            mc.getWindow().setWindowed(640, 480);
                            mc.options.guiScale().set(2);
                            mc.resizeDisplay();
                        }),
                new Step(
                        "capture-electric-settlement-small",
                        () ->
                                Minecraft.getInstance().getWindow().getWidth() == 640
                                        && Minecraft.getInstance().getWindow().getHeight() == 480,
                        () -> {
                            var mc = Minecraft.getInstance();
                            require(
                                    mc.screen instanceof SettlementScreen,
                                    "Resize lost the current settlement");
                            for (var child : mc.screen.children()) {
                                if (!(child instanceof Button b)) continue;
                                require(
                                        b.getX() >= 0
                                                && b.getRight() <= mc.screen.width
                                                && b.getY() >= 0
                                                && b.getBottom() <= mc.screen.height,
                                        "Settlement control outside small window");
                                require(
                                        mc.font.width(b.getMessage()) + 10 <= b.getWidth(),
                                        "Settlement button label clipped");
                            }
                        }),
                new Step("restore settlement window", () -> true, this::restoreWindow),
                new Step(
                        "confirm round",
                        () -> true,
                        () -> {
                            http.acceptCommands = true;
                            http.failCommandOnce = true;
                            http.commandBodies.clear();
                            CameraInteractionChecks.click(button("satori.settlement.continue"), .5);
                        }),
                new Step(
                        "round confirmation identity",
                        () -> !client.busy && http.submitted != null,
                        () -> {
                            require(
                                    http.commandBodies.size() == 2
                                            && http.commandBodies
                                                    .get(0)
                                                    .equals(http.commandBodies.get(1)),
                                    "Automatic confirmation changed the command identity");
                            require(
                                    client.message.isEmpty(),
                                    "Accepted command left an unnecessary message");
                            var action =
                                    client.view.actions().stream()
                                            .filter(a -> a.type().equals("continue"))
                                            .findFirst()
                                            .orElseThrow();
                            require(
                                    http.submitted
                                            .get("action_id")
                                            .getAsString()
                                            .equals(action.actionId()),
                                    "Wrong round action");
                            require(
                                    http.submitted
                                            .get("decision_id")
                                            .getAsString()
                                            .equals(action.decisionId()),
                                    "Wrong round decision");
                            mount("settlement_ryukyoku");
                        }),
                new Step(
                        "draw settlement",
                        this::loaded,
                        () ->
                                require(
                                        Minecraft.getInstance().screen instanceof SettlementScreen,
                                        "Draw did not open settlement")),
                new Step("capture-electric-draw", () -> true, () -> {}),
                new Step("load nine terminals", () -> true, () -> mount("settlement_kyushukyuhai")),
                new Step(
                        "nine terminals hand",
                        this::loaded,
                        () -> {
                            var detail =
                                    client.presentation
                                            .settlement()
                                            .getAsJsonArray("entries")
                                            .get(0)
                                            .getAsJsonObject();
                            require(
                                    detail.get("reason").getAsString().equals("kyushukyuhai"),
                                    "Wrong draw reason");
                            var hands = detail.getAsJsonArray("hands");
                            require(
                                    hands.size() == 1
                                            && hands.get(0)
                                                            .getAsJsonObject()
                                                            .getAsJsonArray("hand")
                                                            .size()
                                                    == 14,
                                    "Nine terminals did not expose exactly the declaring hand");
                            require(
                                    Minecraft.getInstance().screen instanceof SettlementScreen,
                                    "Nine terminals screen missing");
                        }),
                new Step("capture-electric-nine-terminals", () -> true, () -> {}),
                new Step("load tenpai", () -> true, () -> mount("tenpai")),
                new Step(
                        "tenpai hint",
                        this::loaded,
                        () -> {
                            var hint = client.presentation.hint(null);
                            require(
                                    hint.shanten() == 0
                                            && !hint.noYaku()
                                            && !hint.yakuWaits().isEmpty(),
                                    "Tenpai hint missing");
                            require(
                                    Minecraft.getInstance().screen instanceof SeatedTableScreen,
                                    "Stale settlement remained");
                        }),
                new Step("capture-electric-tenpai", () -> true, () -> {}),
                new Step("load no yaku", () -> true, () -> mount("no_yaku")),
                new Step(
                        "no yaku hint",
                        this::loaded,
                        () -> {
                            var hint = client.presentation.hint(null);
                            require(
                                    hint.shanten() == 0 && hint.noYaku() && !hint.waits().isEmpty(),
                                    "No-yaku hint missing");
                            require(
                                    Minecraft.getInstance().screen.children().stream()
                                                    .filter(Button.class::isInstance)
                                                    .map(Button.class::cast)
                                                    .filter(b -> b.getX() == 10)
                                                    .count()
                                            == 1,
                                    "Collapsed menu shows extra buttons");
                        }),
                new Step("capture-electric-no-yaku", () -> true, () -> {}),
                new Step(
                        "expand menu",
                        () -> true,
                        () -> {
                            CameraInteractionChecks.openMenu();
                            require(
                                    Minecraft.getInstance().screen.children().stream()
                                                    .filter(Button.class::isInstance)
                                                    .map(Button.class::cast)
                                                    .filter(b -> b.getX() == 10)
                                                    .count()
                                            == 6,
                                    "Menu options including feedback are not a single column");
                        }),
                new Step("capture-electric-menu", () -> true, () -> {}),
                new Step(
                        "collapse menu",
                        () -> true,
                        () -> {
                            CameraInteractionChecks.click(button("satori.menu.close"), .5);
                            require(
                                    Minecraft.getInstance().screen.children().stream()
                                                    .filter(Button.class::isInstance)
                                                    .map(Button.class::cast)
                                                    .filter(b -> b.getX() == 10)
                                                    .count()
                                            == 1,
                                    "Menu did not collapse");
                        }),
                new Step(
                        "temporary presentation failure",
                        () -> true,
                        () -> {
                            http.presentationStatus = 503;
                            mount("settlement_hora");
                        }),
                new Step(
                        "retry temporary failure",
                        () -> rejected("presentation_error"),
                        () -> http.presentationStatus = 200),
                new Step(
                        "automatic recovery",
                        this::loaded,
                        () -> {
                            require(
                                    Minecraft.getInstance().screen instanceof SettlementScreen,
                                    "Recovered settlement did not open");
                            require(client.presentationError.isEmpty(), "Transient error remained");
                            http.presentationStatus = 401;
                            mount("settlement_hora");
                        }),
                new Step(
                        "expired seat",
                        () -> rejected("seat_session_expired"),
                        () -> {
                            require(
                                    client.game != null && client.view != null,
                                    "Seat rejection erased the game or account");
                            http.presentationStatus = 200;
                            mount("settlement_hora", true);
                        }),
                new Step(
                        "malformed projection",
                        () -> rejected("protocol_error"),
                        () -> {
                            require(
                                    client.presentation == null,
                                    "Malformed JSON installed a presentation");
                            mount("settlement_hora");
                        }),
                new Step(
                        "reconnect settlement",
                        this::loaded,
                        () -> {
                            require(
                                    Minecraft.getInstance().screen instanceof SettlementScreen,
                                    "Reconnected settlement missing");
                            require(
                                    client.presentationError.isEmpty(),
                                    "Reconnect kept stale error");
                        }),
                new Step(
                        "load red closed kan",
                        () -> true,
                        () -> {
                            client.closeGame();
                            var raw =
                                    examples.getAsJsonObject("ankan_red")
                                            .getAsJsonObject("owner_view")
                                            .deepCopy();
                            raw.addProperty("game_id", previousView.gameId.toString());
                            var controller = SatoriClient.TABLES.active();
                            controller.view =
                                    new CloudView(
                                            raw,
                                            previousView.gameId,
                                            raw.get("viewer_seat").getAsInt());
                            controller.scene = new TableScene();
                            controller.scene.update(controller.view, false, controller.scene.now());
                            controller.presentation = null;
                            Minecraft.getInstance().setScreen(null);
                        }),
                new Step(
                        "capture-electric-ankan-red",
                        () -> true,
                        () -> {
                            var controller = SatoriClient.TABLES.active();
                            var tiles = controller.scene.sample(controller.scene.now());
                            var red =
                                    tiles.stream()
                                            .filter(
                                                    t ->
                                                            t.key().startsWith("meld-")
                                                                    && t.face() >= 34
                                                                    && t.face() <= 36
                                                                    && t.key().endsWith("-1")
                                                                    && controller
                                                                            .view
                                                                            .publicData()
                                                                            .getAsJsonArray("melds")
                                                                            .get(t.seat())
                                                                            .getAsJsonArray()
                                                                            .get(
                                                                                    Integer
                                                                                            .parseInt(
                                                                                                    t
                                                                                                            .key()
                                                                                                            .split(
                                                                                                                    "-")[
                                                                                                            2]))
                                                                            .getAsJsonObject()
                                                                            .get("type")
                                                                            .getAsString()
                                                                            .equals("ankan"))
                                            .findFirst()
                                            .orElseThrow();
                            String group = red.key().substring(0, red.key().lastIndexOf('-') + 1);
                            var kan =
                                    tiles.stream().filter(t -> t.key().startsWith(group)).toList();
                            require(
                                    kan.size() == 4 && red.pitch() == -90,
                                    "Red kan face not exposed");
                            require(
                                    kan.stream()
                                                    .filter(t -> t.face() == 37 && t.pitch() == 90)
                                                    .count()
                                            == 2,
                                    "Closed kan outer tiles do not expose their blue backs");
                        }));
    }

    @Override
    public void close() {
        restoreWindow();
        if (client != null) client.closeGame();
        if (http != null) http.close();
        SatoriClient.TABLES.active().view = previousView;
        SatoriClient.TABLES.active().scene = previousScene;
        SatoriClient.TABLES.active().presentation = previousPresentation;
        Minecraft.getInstance().setScreen(null);
    }

    private void restoreWindow() {
        if (savedWidth == 0) return;
        var mc = Minecraft.getInstance();
        mc.getWindow().setWindowed(savedWidth, savedHeight);
        mc.options.guiScale().set(savedGuiScale);
        mc.resizeDisplay();
        savedWidth = 0;
    }
}
