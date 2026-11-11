package cn.satori.mahjong.client;

import static cn.satori.mahjong.client.HandInteractionFixture.require;

import cn.satori.mahjong.TablePackets;
import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Native click, pagination, lobby and result checks shared by both production loaders. */
public final class NavigationInteractionChecks implements AutoCloseable {
    public record Step(String name, BooleanSupplier ready, Runnable action) {}

    private LocalAccountFixture http;
    private ClientController client;
    private CompletableFuture<?> login;
    private CloudView previousView;
    private JsonObject raw;
    private int savedWidth, savedHeight, savedScale;

    private void open() {
        var mc = Minecraft.getInstance();
        previousView = SatoriClient.TABLES.active().view;
        SatoriClient.TABLES.active().view = null;
        savedWidth = mc.getWindow().getWidth();
        savedHeight = mc.getWindow().getHeight();
        savedScale = mc.options.guiScale().get();
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/cloud-created.json")),
                        StandardCharsets.UTF_8)) {
            raw = JsonParser.parseReader(reader).getAsJsonObject();
            raw.getAsJsonObject("public")
                    .getAsJsonArray("seats")
                    .get(1)
                    .getAsJsonObject()
                    .addProperty("ready", true);
            http = new LocalAccountFixture();
            http.acceptCommands = true;
            client = new ClientController(http.api, http.config);
            client.dimension = SatoriClient.TABLES.active().dimension;
            client.table = SatoriClient.TABLES.active().table;
            client.table = new TablePackets.Open(SeatedCamera.center(), "", true);
            for (int i = 0; i < 7; i++) {
                var item = new JsonObject();
                item.addProperty("game_id", new UUID(0, 100 + i).toString());
                item.addProperty(
                        "date_created", "2026-10-15T" + String.format("%02d", 12 - i) + ":00:00Z");
                item.addProperty("status", i == 0 ? "paused" : "waiting_players");
                item.add("rules", JsonParser.parseString("{\"game_length_mode\":\"hanchan\"}"));
                item.addProperty("can_delete", i != 1);
                http.savedGames.add(item);
            }
            login = http.api.session();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Button button(String key) {
        String text = Component.translatable("satori." + key).getString();
        return Minecraft.getInstance().screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.getMessage().getString().equals(text))
                .findFirst()
                .orElseThrow();
    }

    private void click(String key) {
        CameraInteractionChecks.click(button(key), .5);
    }

    private boolean catalogSettled() {
        return !client.busy
                && Minecraft.getInstance().screen instanceof TableScreen screen
                && !screen.loading();
    }

    private void bounds() {
        var mc = Minecraft.getInstance();
        var widgets =
                mc.screen.children().stream()
                        .filter(AbstractWidget.class::isInstance)
                        .map(AbstractWidget.class::cast)
                        .filter(w -> w.visible)
                        .toList();
        for (var w : widgets) {
            require(
                    w.getX() >= 0
                            && w.getY() >= 0
                            && w.getRight() <= mc.screen.width
                            && w.getBottom() <= mc.screen.height,
                    "Navigation widget outside viewport");
            require(
                    mc.font.width(w.getMessage()) + 8 <= w.getWidth(),
                    "Navigation label clipped: " + w.getMessage().getString());
        }
        for (int i = 0; i < widgets.size(); i++)
            for (int j = i + 1; j < widgets.size(); j++) {
                var a = widgets.get(i);
                var b = widgets.get(j);
                require(
                        a.getRight() <= b.getX()
                                || b.getRight() <= a.getX()
                                || a.getBottom() <= b.getY()
                                || b.getBottom() <= a.getY(),
                        "Navigation controls overlap");
            }
    }

    private void lobby() {
        raw.addProperty("owner_user_id", client.user().id().toString());
        raw.getAsJsonObject("public")
                .getAsJsonArray("seats")
                .get(0)
                .getAsJsonObject()
                .addProperty("user_id", client.user().id().toString());
        client.view = new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
        var session =
                JsonParser.parseString("{\"seat\":0,\"seat_token\":\"synthetic-navigation\"}")
                        .getAsJsonObject();
        client.game =
                new GameConnection(
                        http.api, client.view.gameId, session, (v, e) -> {}, status -> {});
        client.connection = "connected";
        Minecraft.getInstance().setScreen(new GameRoomScreen(client));
    }

    private void finalResult() {
        raw.addProperty("status", "finished");
        client.view = new CloudView(raw, client.game.gameId, 0);
        var result = new JsonObject();
        result.addProperty("game_id", client.view.gameId.toString());
        result.add("seats", raw.getAsJsonObject("public").get("seats").deepCopy());
        result.add("scores", JsonParser.parseString("[41000,28000,24000,7000]"));
        result.add("ranks", JsonParser.parseString("[1,2,3,4]"));
        result.add("pt_deltas", JsonParser.parseString("[90,45,0,-145]"));
        http.finalResult = result;
        Minecraft.getInstance().setScreen(new MatchResultScreen(client));
    }

    public List<Step> steps() {
        return List.of(
                new Step("navigation setup", () -> true, this::open),
                new Step(
                        "navigation account",
                        () -> login.isDone(),
                        () -> {
                            login.join();
                            Minecraft.getInstance().setScreen(new AccountScreen(client));
                            bounds();
                            WebsiteInteractionChecks.openAndReturn(
                                    "satori.account.web_profile",
                                    "https://mj.satori-ai.cn/profile");
                            click("table.open");
                        }),
                new Step(
                        "capture-electric-games",
                        () -> catalogSettled() && http.catalogs.get() > 0,
                        () -> {
                            require(
                                    Minecraft.getInstance().screen instanceof TableScreen,
                                    "Catalog redirected itself");
                            bounds();
                            click("table.copy_id");
                            require(
                                    Minecraft.getInstance()
                                            .keyboardHandler
                                            .getClipboard()
                                            .equals(new UUID(0, 100).toString()),
                                    "Wrong copied game");
                        }),
                new Step("navigation next page", () -> true, () -> click("games.next")),
                new Step(
                        "navigation previous page",
                        () -> catalogSettled(),
                        () -> {
                            bounds();
                            click("games.previous");
                        }),
                new Step(
                        "navigation cancel deletion",
                        () -> catalogSettled(),
                        () -> {
                            click("games.delete");
                            require(
                                    Minecraft.getInstance().screen instanceof ConfirmScreen,
                                    "Delete lacks confirmation");
                            var cancel =
                                    Minecraft.getInstance().screen.children().stream()
                                            .filter(Button.class::isInstance)
                                            .map(Button.class::cast)
                                            .filter(
                                                    b ->
                                                            b.getMessage()
                                                                    .equals(
                                                                            net.minecraft.network
                                                                                    .chat
                                                                                    .CommonComponents
                                                                                    .GUI_NO))
                                            .findFirst()
                                            .orElseThrow();
                            CameraInteractionChecks.click(cancel, .5);
                            require(http.deletions.get() == 0, "Cancel deleted a game");
                            click("games.delete");
                            var yes =
                                    Minecraft.getInstance().screen.children().stream()
                                            .filter(Button.class::isInstance)
                                            .map(Button.class::cast)
                                            .filter(
                                                    b ->
                                                            b.getMessage()
                                                                    .equals(
                                                                            net.minecraft.network
                                                                                    .chat
                                                                                    .CommonComponents
                                                                                    .GUI_YES))
                                            .findFirst()
                                            .orElseThrow();
                            CameraInteractionChecks.click(yes, .5);
                        }),
                new Step(
                        "navigation deletion committed",
                        () -> catalogSettled() && http.deletions.get() == 1,
                        () -> {
                            require(!button("games.delete").active, "Guest delete became enabled");
                            http.failCatalog = true;
                            click("games.refresh");
                        }),
                new Step(
                        "navigation failed catalog",
                        () -> catalogSettled(),
                        () -> {
                            require(
                                    client.message.equals("http_503"),
                                    "Catalog error hidden as empty list");
                            http.failCatalog = false;
                            click("games.refresh");
                        }),
                new Step(
                        "navigation small window",
                        () -> catalogSettled(),
                        () -> {
                            var mc = Minecraft.getInstance();
                            mc.getWindow().setWindowed(640, 480);
                            mc.options.guiScale().set(2);
                            mc.resizeDisplay();
                        }),
                new Step(
                        "capture-electric-games-small",
                        () ->
                                catalogSettled()
                                        && Minecraft.getInstance().getWindow().getWidth() == 640,
                        this::bounds),
                new Step(
                        "capture-electric-lobby-small",
                        () -> true,
                        () -> {
                            lobby();
                            bounds();
                        }),
                new Step(
                        "navigation ready checkbox",
                        () -> true,
                        () -> {
                            var boxes =
                                    Minecraft.getInstance().screen.children().stream()
                                            .filter(Checkbox.class::isInstance)
                                            .map(Checkbox.class::cast)
                                            .toList();
                            require(
                                    boxes.size() == 4 && boxes.getFirst().active,
                                    "Missing own readiness checkbox");
                            require(
                                    boxes.stream().skip(1).noneMatch(b -> b.active),
                                    "Can change another player's readiness");
                            CameraInteractionChecks.click(boxes.getFirst(), .5);
                        }),
                new Step(
                        "navigation ready receipt",
                        () -> !client.busy,
                        () -> {
                            require(
                                    http.submittedPath.endsWith("/ready")
                                            && http.submitted.get("ready").getAsBoolean(),
                                    "Ready did not submit normal command");
                            require(
                                    !button("table.start").active,
                                    "Host started before everyone was ready");
                            restoreWindow();
                            lobby();
                        }),
                new Step("capture-electric-lobby", () -> true, this::bounds),
                new Step(
                        "navigation final result",
                        () -> true,
                        () -> {
                            client.busy = true;
                            finalResult();
                            Minecraft.getInstance().screen.tick();
                            require(
                                    client.result == null,
                                    "Result request overlapped pending action");
                            client.busy = false;
                        }),
                new Step(
                        "capture-electric-match-result",
                        () -> !client.busy && client.result != null,
                        () -> {
                            Minecraft.getInstance().screen.tick();
                            bounds();
                        }),
                new Step(
                        "navigation small result",
                        () -> true,
                        () -> {
                            var mc = Minecraft.getInstance();
                            mc.getWindow().setWindowed(640, 480);
                            mc.options.guiScale().set(2);
                            mc.resizeDisplay();
                        }),
                new Step(
                        "capture-electric-match-result-small",
                        () -> Minecraft.getInstance().getWindow().getWidth() == 640,
                        this::bounds),
                new Step("navigation result to catalog", () -> true, () -> click("games.title")),
                new Step(
                        "navigation catalog remains open",
                        this::catalogSettled,
                        () -> {
                            client.connection = "disconnected";
                            client.tick();
                            require(
                                    Minecraft.getInstance().screen instanceof TableScreen,
                                    "Result stole catalog navigation");
                        }));
    }

    private void restoreWindow() {
        var mc = Minecraft.getInstance();
        mc.getWindow().setWindowed(savedWidth, savedHeight);
        mc.options.guiScale().set(savedScale);
        mc.resizeDisplay();
    }

    @Override
    public void close() {
        restoreWindow();
        if (client != null) client.closeGame();
        if (http != null) http.close();
        SatoriClient.TABLES.active().view = previousView;
        Minecraft.getInstance().setScreen(null);
    }
}
