package cn.satori.mahjong.client;

import static cn.satori.mahjong.client.HandInteractionFixture.require;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Full creation receipt -> seat subscription -> first native frame on both loaders. */
public final class CreationInteractionChecks implements AutoCloseable {
    public record Step(String name, BooleanSupplier ready, Runnable action) {}

    private LocalCreationFixture server;
    private ClientController client;
    private CompletableFuture<?> login;
    private CloudView savedView;
    private TableScene savedScene;
    private CloudPresentation savedPresentation;

    private void open() {
        var active = SatoriClient.TABLES.active();
        savedView = active.view;
        savedScene = active.scene;
        savedPresentation = active.presentation;
        try {
            server = new LocalCreationFixture(savedView.gameId);
            client = new ClientController(server.api, server.config);
            client.dimension = SatoriClient.TABLES.active().dimension;
            client.table = SatoriClient.TABLES.active().table;
            var center = SeatedCamera.center();
            var table = (TableBlockEntity) Minecraft.getInstance().level.getBlockEntity(center);
            client.table = new TablePackets.Open(center, table.gameId(), true);
            login = server.api.session();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private void form(boolean missing) {
        server.missingDecisionSeat = missing;
        Minecraft.getInstance().setScreen(new CreateScreen(client));
    }

    private void submit() {
        var screen = Minecraft.getInstance().screen;
        var fields =
                screen.children().stream()
                        .filter(EditBox.class::isInstance)
                        .map(EditBox.class::cast)
                        .toList();
        for (int seat = 0; seat < 4; seat++) fields.get(seat).setValue(server.players().get(seat));
        String text = Component.translatable("satori.create.submit").getString();
        var button =
                screen.children().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(b -> b.getMessage().getString().equals(text))
                        .findFirst()
                        .orElseThrow();
        CameraInteractionChecks.click(button, .5);
    }

    private void checkFailure() {
        if (server.failure != null)
            throw new AssertionError("Local creation server", server.failure);
    }

    private boolean connected(String status) {
        checkFailure();
        return !client.busy && client.connection.equals(status);
    }

    private void renderCurrent() {
        SatoriClient.TABLES.active().view = client.view;
        SatoriClient.TABLES.active().scene = client.scene;
        SatoriClient.TABLES.active().presentation = null;
        Minecraft.getInstance().setScreen(new GameRoomScreen(client));
    }

    public List<Step> steps() {
        return List.of(
                new Step("open creation service", () -> true, this::open),
                new Step(
                        "valid creation form",
                        () -> login.isDone(),
                        () -> {
                            login.join();
                            form(false);
                        }),
                new Step("valid create click", () -> !client.account.keysLoading, this::submit),
                new Step(
                        "capture-created-game",
                        () -> connected("connected"),
                        () -> {
                            require(
                                    client.view != null
                                            && client.view.status().equals("waiting_players"),
                                    "Missing created lobby view");
                            require(client.view.decisionSeat() == -1, "Lobby has active decision");
                            require(
                                    client.game.gameId.equals(savedView.gameId),
                                    "Created game ID changed");
                            require(
                                    server.creations == 1 && server.subscriptions == 1,
                                    "Creation did not subscribe exactly once");
                            TableCenter.from(client.view);
                            var lobby = new GameRoomScreen(client);
                            Minecraft.getInstance().setScreen(lobby);
                            client.acknowledgeLink(
                                    new TablePackets.Linked(
                                            client.dimension,
                                            client.table.pos(),
                                            client.game.gameId.toString()));
                            require(
                                    Minecraft.getInstance().screen == lobby,
                                    "Link acknowledgement stole lobby navigation");
                            renderCurrent();
                        }),
                new Step("incomplete server form", () -> true, () -> form(true)),
                new Step(
                        "incomplete server create click",
                        () -> !client.account.keysLoading,
                        this::submit),
                new Step(
                        "capture-created-server-update",
                        () -> connected("server_update_required"),
                        () -> {
                            require(client.view == null, "Incomplete view reached renderer");
                            require(
                                    client.game != null
                                            && client.game.gameId.equals(savedView.gameId),
                                    "Created game ID lost");
                            require(
                                    client.message.equals("server_update_required"),
                                    "Missing upgrade explanation");
                            require(
                                    server.creations == 2 && server.subscriptions == 2,
                                    "Rejected projection retried creation");
                            renderCurrent();
                        }));
    }

    @Override
    public void close() {
        checkFailure();
        client.closeGame();
        server.close();
        SatoriClient.TABLES.active().view = savedView;
        SatoriClient.TABLES.active().scene = savedScene;
        SatoriClient.TABLES.active().presentation = savedPresentation;
        Minecraft.getInstance().setScreen(null);
    }
}
