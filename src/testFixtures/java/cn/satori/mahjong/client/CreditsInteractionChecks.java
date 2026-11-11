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
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Synthetic pause snapshots and local HTTP only; never charges an account or resumes a live game.
 */
public final class CreditsInteractionChecks implements AutoCloseable {
    public record Step(String name, BooleanSupplier ready, Runnable action) {}

    private LocalAccountFixture http;
    private ClientController client;
    private CompletableFuture<?> login;
    private JsonObject raw, display;
    private CloudView savedView;
    private CloudPresentation savedPresentation;
    private TableScene savedScene;
    private int savedWidth, savedHeight, savedScale;

    private void open() {
        var active = SatoriClient.TABLES.active();
        savedView = active.view;
        savedPresentation = active.presentation;
        savedScene = active.scene;
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/electric-examples.json")),
                        StandardCharsets.UTF_8)) {
            var example =
                    JsonParser.parseReader(reader)
                            .getAsJsonObject()
                            .getAsJsonObject("settlement_hora");
            raw = example.getAsJsonObject("view").deepCopy();
            display = example.getAsJsonObject("presentation").deepCopy();
            http = new LocalAccountFixture();
            http.acceptCommands = true;
            client = new ClientController(http.api, http.config);
            client.dimension = SatoriClient.TABLES.active().dimension;
            client.table = SatoriClient.TABLES.active().table;
            login = http.api.session();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void snapshot(String status, String reason, boolean owner) {
        var game = savedView.gameId;
        raw.addProperty("game_id", game.toString());
        raw.addProperty(
                "owner_user_id",
                owner ? client.user().id().toString() : new UUID(0, 900).toString());
        raw.addProperty("status", status);
        raw.addProperty("pause_reason", reason);
        raw.add("decision", JsonNull.INSTANCE);
        raw.addProperty("view_version", raw.get("view_version").getAsLong() + 1);
        raw.getAsJsonObject("display").add("decision_seat", JsonNull.INSTANCE);
        client.view = new CloudView(raw, game, 0);
        for (String field : List.of("game_id", "view_version", "cursor"))
            display.add(field, raw.get(field));
        display.add("settlement", JsonNull.INSTANCE);
        http.presentation = display.deepCopy();
        client.presentation = new CloudPresentation(display, client.view);
        client.scene = new TableScene();
        client.scene.update(client.view, false, client.scene.now());
        if (client.game == null) {
            JsonObject session = new JsonObject();
            session.addProperty("seat", 0);
            session.addProperty("seat_token", "synthetic-credits-seat");
            client.game = new GameConnection(http.api, game, session, (v, e) -> {}, s -> {});
        }
        client.connection = "connected";
        SatoriClient.TABLES.active().view = client.view;
        SatoriClient.TABLES.active().presentation = null;
        SatoriClient.TABLES.active().scene = client.scene;
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

    private void notice() {
        client.tick();
        require(
                Minecraft.getInstance().screen instanceof CreditsRequiredScreen,
                "Credits pause was hidden");
        require(!client.canAct(), "Paused game accepted a tile action");
    }

    private void checkControls() {
        var mc = Minecraft.getInstance();
        for (var child : mc.screen.children())
            if (child instanceof Button b && b.visible) {
                require(
                        b.getX() >= 0
                                && b.getRight() <= mc.screen.width
                                && b.getY() >= 0
                                && b.getBottom() <= mc.screen.height,
                        "Credits button outside viewport");
                require(
                        mc.font.width(b.getMessage()) + 10 <= b.getWidth(),
                        "Credits label clipped");
            }
        int lines =
                mc.font
                        .split(
                                Component.translatable("satori.credits.owner_help"),
                                Math.min(500, mc.screen.width - 16) - 24)
                        .size();
        require(
                40 + lines * (mc.font.lineHeight + 3) <= Math.min(300, mc.screen.height - 12) - 120,
                "Credits explanation overlaps buttons");
    }

    public List<Step> steps() {
        return List.of(
                new Step("credits setup", () -> true, this::open),
                new Step(
                        "capture-electric-credits",
                        () -> login.isDone(),
                        () -> {
                            login.join();
                            snapshot("paused", "ai_terminal_http_402", true);
                            Minecraft.getInstance().setScreen(new SeatedTableScreen(client));
                            notice();
                            checkControls();
                            require(button("credits.resume").active, "Host cannot resume");
                            require(
                                    http.submitted == null,
                                    "Pause automatically issued a paid retry");
                        }),
                new Step(
                        "credits browser destination",
                        () -> true,
                        () -> {
                            WebsiteInteractionChecks.openAndReturn(
                                    "satori.credits.open",
                                    "https://mj.satori-ai.cn/profile?view=credits");
                            notice();
                            require(http.submitted == null, "Opening wallet resumed the game");
                            var mc = Minecraft.getInstance();
                            savedWidth = mc.getWindow().getWidth();
                            savedHeight = mc.getWindow().getHeight();
                            savedScale = mc.options.guiScale().get();
                            mc.getWindow().setWindowed(640, 480);
                            mc.options.guiScale().set(2);
                            mc.resizeDisplay();
                        }),
                new Step(
                        "capture-electric-credits-small",
                        () ->
                                Minecraft.getInstance().getWindow().getWidth() == 640
                                        && Minecraft.getInstance().getWindow().getHeight() == 480,
                        this::checkControls),
                new Step(
                        "credits manual resume",
                        () -> true,
                        () -> {
                            restoreWindow();
                            CameraInteractionChecks.click(button("credits.resume"), .5);
                        }),
                new Step(
                        "credits accepted receipt awaits snapshot",
                        () -> !client.busy && http.submitted != null,
                        () -> {
                            require(
                                    http.submittedPath.endsWith("/" + savedView.gameId + "/resume"),
                                    "Wrong resume target");
                            require(
                                    http.submitted.has("command_id") && http.submitted.size() == 1,
                                    "Resume leaked credentials or changed action");
                            notice();
                            snapshot("playing", "", true);
                            Minecraft.getInstance().screen.tick();
                            require(
                                    Minecraft.getInstance().screen instanceof SeatedTableScreen,
                                    "Resume did not restore table");
                        }),
                new Step(
                        "credits repeated rejection and reconnect",
                        () -> true,
                        () -> {
                            snapshot("paused", "ai_terminal_http_402", true);
                            notice();
                            Minecraft.getInstance().setScreen(new GameRoomScreen(client));
                            notice();
                            Minecraft.getInstance().setScreen(new ManageScreen(client));
                        }),
                new Step(
                        "credits management link",
                        () -> !client.account.keysLoading,
                        () -> {
                            WebsiteInteractionChecks.openAndReturn(
                                    "satori.credits.open",
                                    "https://mj.satori-ai.cn/profile?view=credits");
                            snapshot("paused", "ai_terminal_http_402", false);
                            Minecraft.getInstance().setScreen(new GameRoomScreen(client));
                            notice();
                            require(
                                    !button("credits.open").visible
                                            && !button("credits.resume").active,
                                    "Guest asked to fund host account");
                            snapshot("paused", "ai_transport_error", true);
                            Minecraft.getInstance().screen.tick();
                            client.tick();
                            require(
                                    !(Minecraft.getInstance().screen
                                            instanceof CreditsRequiredScreen),
                                    "Other failures misreported as credits");
                        }));
    }

    private void restoreWindow() {
        if (savedWidth == 0) return;
        var mc = Minecraft.getInstance();
        mc.getWindow().setWindowed(savedWidth, savedHeight);
        mc.options.guiScale().set(savedScale);
        mc.resizeDisplay();
        savedWidth = 0;
    }

    @Override
    public void close() {
        restoreWindow();
        if (client != null) client.closeGame();
        if (http != null) http.close();
        SatoriClient.TABLES.active().view = savedView;
        SatoriClient.TABLES.active().presentation = savedPresentation;
        SatoriClient.TABLES.active().scene = savedScene;
        Minecraft.getInstance().setScreen(null);
    }
}
