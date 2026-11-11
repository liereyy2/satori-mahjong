package cn.satori.mahjong.client;

import static cn.satori.mahjong.client.HandInteractionFixture.require;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Real MC presence round-trip plus synthetic four-person form and real local ticket HTTP. */
public final class LobbyInteractionChecks implements AutoCloseable {
    public record Step(String name, BooleanSupplier ready, Runnable action) {}

    private LocalAccountFixture http;
    private ClientController client;
    private SatoriApi previousApi;
    private TablePackets.Open previousTable;
    private Screen previousScreen;
    private CompletableFuture<?> login;
    private long previousCheck;
    private String previousMessage;
    private int frames;

    private Button button(String key) {
        String label = Component.translatable("satori." + key).getString();
        return Minecraft.getInstance().screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.getMessage().getString().equals(label))
                .findFirst()
                .orElseThrow();
    }

    private boolean announced() {
        return client.lobby != null
                && client.lobby.seats().stream()
                        .anyMatch(s -> s.userId().equals(http.api.user().id().toString()));
    }

    public List<Step> steps() {
        return List.of(
                new Step(
                        "lobby local account",
                        () -> true,
                        () -> {
                            try {
                                http = new LocalAccountFixture();
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                            client = SatoriClient.TABLES.active();
                            require(client.seated(), "Presence fixture must be physically seated");
                            previousApi = client.account.api;
                            previousCheck = client.account.nextSessionCheck;
                            previousTable = client.table;
                            previousScreen = Minecraft.getInstance().screen;
                            previousMessage = client.message;
                            client.message = "";
                            login = http.api.session();
                        }),
                new Step(
                        "lobby announce over native loader",
                        () -> login.isDone(),
                        () -> {
                            login.join();
                            client.account.api = http.api;
                            client.account.nextSessionCheck = System.nanoTime() + 60_000_000_000L;
                        }),
                new Step(
                        "lobby seat and host acknowledged",
                        this::announced,
                        () -> {
                            int seat = SeatedCamera.direction();
                            require(
                                    client.lobby
                                            .seats()
                                            .get(seat)
                                            .userId()
                                            .equals(http.api.user().id().toString()),
                                    "Public identity was assigned to the wrong chair");
                            require(
                                    client.table.editable()
                                            && client.lobby.seats().get(seat).host(),
                                    "First participant did not become host");
                            SatoriClient.sendPresence(
                                    new TablePackets.Presence(
                                            net.minecraft.world.level.Level.NETHER,
                                            client.table.pos(),
                                            new UUID(0, 999).toString(),
                                            "Wrong dimension"));
                        }),
                new Step(
                        "lobby reject cross-dimension declaration",
                        () -> ++frames > 8,
                        () -> {
                            require(announced(), "Cross-dimension presence overwrote the player");
                            var seats = new ArrayList<TablePackets.Occupant>();
                            for (int i = 0; i < 4; i++)
                                seats.add(
                                        new TablePackets.Occupant(
                                                "Player" + i,
                                                new UUID(0, i + 1).toString(),
                                                "Satori " + i,
                                                i == 0));
                            client.lobby =
                                    new TablePackets.Lobby(
                                            client.dimension, client.table.pos(), "", true, seats);
                            // The form exercise leaves the real world's bound game untouched.
                            client.table = new TablePackets.Open(client.table.pos(), "", true);
                            Minecraft.getInstance().setScreen(new TableLobbyScreen(client));
                        }),
                new Step("capture-table-lobby", () -> true, TableActionLayoutChecks::verify),
                new Step(
                        "lobby automatic creation roster",
                        () -> true,
                        () -> {
                            CameraInteractionChecks.click(button("create.title"), .5);
                            var fields =
                                    Minecraft.getInstance().screen.children().stream()
                                            .filter(EditBox.class::isInstance)
                                            .map(EditBox.class::cast)
                                            .toList();
                            require(fields.size() == 4, "Missing creation seats");
                            for (int i = 0; i < 4; i++)
                                require(
                                        fields.get(i)
                                                .getValue()
                                                .equals(
                                                        new UUID(
                                                                        0,
                                                                        TableOrientation
                                                                                        .chairForSeat(
                                                                                                i)
                                                                                + 1)
                                                                .toString()),
                                        "Seated player not filled automatically");
                            require(
                                    !button("create.ai").active,
                                    "AI replaced a physically seated player");
                            Minecraft.getInstance().setScreen(new AccountScreen(client));
                            CameraInteractionChecks.click(button("feedback.title"), .5);
                            require(
                                    Minecraft.getInstance().screen instanceof FeedbackScreen,
                                    "Feedback entry did not open form");
                            for (var widget : Minecraft.getInstance().screen.children()) {
                                if (widget instanceof EditBox field)
                                    field.setValue("Tile selection regression");
                                if (widget instanceof MultiLineEditBox field)
                                    field.setValue(
                                            "Click a hand tile.\n"
                                                    + "Expected: one discard. Actual: no action.");
                            }
                            ((FeedbackScreen) Minecraft.getInstance().screen).refreshButtons();
                        }),
                new Step("capture-bug-feedback", () -> !client.account.keysLoading, () -> {}),
                new Step(
                        "feedback submit",
                        () -> true,
                        () -> CameraInteractionChecks.click(button("feedback.send"), .5)),
                new Step(
                        "feedback receipt and inbox",
                        () -> !client.busy && http.tickets.get() == 1,
                        () -> {
                            require(
                                    http.ticketAuthenticated,
                                    "Feedback lost account authentication");
                            require(
                                    http.submitted
                                            .get("subject")
                                            .getAsString()
                                            .equals(
                                                    "[SatoriMahjong Bug] Tile selection"
                                                            + " regression"),
                                    "Incorrect ticket title");
                            String body = http.submitted.get("body").getAsString();
                            require(
                                    body.contains("Actual: no action.")
                                            && body.contains(
                                                    "Minecraft 1.21.11 / " + SatoriClient.loader()),
                                    "Missing report context");
                            require(
                                    http.submitted.size() == 2
                                            && !body.contains("synthetic-session")
                                            && !body.contains("test-csrf"),
                                    "Secret in feedback body");
                            require(
                                    !button("feedback.send").active,
                                    "Completed ticket can be sent twice");
                            CameraInteractionChecks.click(button("feedback.copy"), .5);
                            require(
                                    Minecraft.getInstance()
                                            .keyboardHandler
                                            .getClipboard()
                                            .equals("00000000-0000-0000-0000-000000000777"),
                                    "Ticket ID not copied");
                            WebsiteInteractionChecks.openAndReturn(
                                    "satori.feedback.messages", "https://mj.satori-ai.cn/messages");
                            http.api.forget();
                        }),
                new Step(
                        "lobby logout withdraws identity",
                        () ->
                                client.lobby != null
                                        && client.lobby.seats().stream()
                                                .allMatch(s -> s.userId().isEmpty()),
                        () -> {}));
    }

    @Override
    public void close() {
        if (client != null) {
            client.account.api = previousApi;
            client.account.nextSessionCheck = previousCheck;
            client.table = previousTable;
            client.message = previousMessage;
            Minecraft.getInstance().setScreen(previousScreen);
        }
        if (http != null) http.close();
    }
}
