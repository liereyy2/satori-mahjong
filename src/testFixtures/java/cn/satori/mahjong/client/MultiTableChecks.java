package cn.satori.mahjong.client;

import static cn.satori.mahjong.client.HandInteractionFixture.require;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.*;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Real HTTP/WebSocket isolation, including delayed creation while another table is open. */
public final class MultiTableChecks implements AutoCloseable {
    public record Step(String name, BooleanSupplier ready, Runnable action) {}

    private LocalTableSessionsFixture http;
    private ClientTables tables;
    private ClientController a, b;
    private BlockPos posA, posB;
    private GameConnection connectionA, connectionB;
    private Screen original, screenB;

    private boolean ready(BooleanSupplier predicate) {
        if (tables != null) tables.tick();
        if (http != null && http.failure != null) throw new AssertionError(http.failure);
        return predicate.getAsBoolean();
    }

    private TablePackets.Open packet(BlockPos pos, UUID game) {
        return new TablePackets.Open(pos, game == null ? "" : game.toString(), true);
    }

    private void verifyRendererLookup() {
        var mc = Minecraft.getInstance();
        var tableA = new TableBlockEntity(posA, SatoriMahjong.table().defaultBlockState());
        tableA.setLevel(mc.level);
        tableA.link(LocalTableSessionsFixture.A.toString());
        var tableB = new TableBlockEntity(posB, SatoriMahjong.table().defaultBlockState());
        tableB.setLevel(mc.level);
        tableB.link(LocalTableSessionsFixture.B.toString());
        require(
                tables.forTable(tableA) == a && tables.forTable(tableB) == b,
                "Renderer selected the active table instead of its own position");
        require(
                a.scene != b.scene && a.view != b.view && a.game != b.game,
                "Tables share private presentation or transport");
        require(
                TableCenter.from(a.view).seats().getFirst().score() == 25000
                        && TableCenter.from(b.view).seats().getFirst().score() == 31000,
                "Scores crossed between games");
        for (var controller : List.of(a, b))
            for (int seat = 1; seat < 4; seat++)
                require(
                        controller.view.hand(seat).stream().allMatch(tile -> tile == 37),
                        "Other player's concealed hand leaked");
    }

    public List<Step> steps() {
        return List.of(
                new Step(
                        "multi-table shared login",
                        () -> true,
                        () -> {
                            try {
                                http = new LocalTableSessionsFixture();
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                            var mc = Minecraft.getInstance();
                            original = mc.screen;
                            posA = mc.player.blockPosition().offset(48, 0, 0);
                            posB = posA.offset(12, 0, 0);
                            tables = new ClientTables(http.api, http.config);
                            tables.open(packet(posA, null));
                            a = tables.active();
                            a.login("tables@example.test", "synthetic-password", false);
                        }),
                new Step(
                        "multi-table delayed A creation",
                        () -> ready(() -> !a.busy),
                        () -> {
                            require(a.user() != null, "Login failed");
                            Minecraft.getInstance().setScreen(new CreateScreen(a));
                            a.create(http.players(), true);
                        }),
                new Step(
                        "multi-table independent B creation",
                        () -> ready(() -> http.creations.get() == 1),
                        () -> {
                            tables.open(packet(posB, null));
                            b = tables.active();
                            require(
                                    b != a && b.account == a.account && b.user() == a.user(),
                                    "Opening another table changed the shared identity");
                            require(a.busy && !b.busy, "A request blocked B");
                            Minecraft.getInstance().setScreen(new CreateScreen(b));
                            b.create(http.players(), false);
                        }),
                new Step(
                        "multi-table release background response",
                        () ->
                                ready(
                                        () ->
                                                b.view != null
                                                        && !b.busy
                                                        && b.connection.equals("connected")),
                        () -> {
                            screenB = Minecraft.getInstance().screen;
                            connectionB = b.game;
                            http.releaseCreation.countDown();
                        }),
                new Step(
                        "multi-table two live games",
                        () ->
                                ready(
                                        () ->
                                                a.view != null
                                                        && !a.busy
                                                        && a.connection.equals("connected")
                                                        && a.presentation != null
                                                        && b.presentation != null
                                                        && http.liveStreams() == 2),
                        () -> {
                            require(
                                    http.logins.get() == 1 && http.creations.get() == 2,
                                    "Account or game duplicated");
                            require(
                                    Minecraft.getInstance().screen == screenB
                                            && tables.active() == b,
                                    "A creation navigated B's screen");
                            connectionA = a.game;
                            verifyRendererLookup();
                            tables.linked(
                                    new TablePackets.Linked(
                                            a.dimension,
                                            posA,
                                            LocalTableSessionsFixture.A.toString()));
                            require(
                                    tables.active() == b
                                            && Minecraft.getInstance().screen == screenB,
                                    "A binding acknowledgement stole B's focus");
                            tables.linked(
                                    new TablePackets.Linked(
                                            b.dimension,
                                            posB,
                                            LocalTableSessionsFixture.B.toString()));
                            a.dismissedSettlement = "only-a";
                            require(
                                    b.dismissedSettlement == null,
                                    "Settlement acknowledgement crossed tables");
                            http.update(LocalTableSessionsFixture.A);
                        }),
                new Step(
                        "multi-table background stream update",
                        () ->
                                ready(
                                        () ->
                                                a.view.version == 7
                                                        && a.presentation != null
                                                        && a.presentation.version == 7),
                        () -> {
                            require(
                                    b.view.version == 6 && b.game == connectionB,
                                    "A update replaced B");
                            require(
                                    Minecraft.getInstance().screen == screenB,
                                    "A update stole B's screen");
                            a.command("PUT", "/ready", new JsonObject());
                        }),
                new Step(
                        "multi-table decision routing",
                        () -> ready(() -> !a.busy && http.commands.size() == 1),
                        () -> {
                            require(
                                    http.commands.equals(
                                            List.of(LocalTableSessionsFixture.A.toString())),
                                    "Command used another game's identity");
                            tables.open(packet(posA, LocalTableSessionsFixture.A));
                            require(
                                    tables.active() == a
                                            && a.game == connectionA
                                            && b.game == connectionB
                                            && http.liveStreams() == 2,
                                    "Switching tables replaced a connection");
                            verifyRendererLookup();
                            // A new association is still waiting because its table is far away.
                            a.table = packet(posA, null);
                            a.link(a.table, LocalTableSessionsFixture.A.toString());
                            b.deleteGame(LocalTableSessionsFixture.A, () -> {});
                        }),
                new Step(
                        "multi-table deletion cancels deferred binding",
                        () -> ready(() -> !b.busy && http.liveStreams() == 1),
                        () -> {
                            tables.linked(
                                    new TablePackets.Linked(
                                            a.dimension,
                                            posA,
                                            LocalTableSessionsFixture.A.toString()));
                            require(
                                    a.game == null && a.view == null && a.table.gameId().isEmpty(),
                                    "Deleted game retained its deferred table binding");
                            require(b.game == connectionB && b.view != null, "Deleting A closed B");
                            Minecraft.getInstance().setScreen(new TableScreen(a));
                            http.delaySession = true;
                            a.join(LocalTableSessionsFixture.A.toString(), false);
                        }),
                new Step(
                        "multi-table binding changes during join",
                        () -> ready(() -> http.sessions.get() == 1),
                        () -> {
                            var seats =
                                    java.util.Collections.nCopies(
                                            4, new TablePackets.Occupant("", "", "", false));
                            tables.lobby(
                                    new TablePackets.Lobby(
                                            a.dimension,
                                            posA,
                                            LocalTableSessionsFixture.B.toString(),
                                            false,
                                            seats));
                            http.releaseSession.countDown();
                        }),
                new Step(
                        "multi-table stale join cannot overwrite a new binding",
                        () -> ready(() -> !a.busy),
                        () -> {
                            require(
                                    a.game == null
                                            && a.view == null
                                            && a.message.equals("table_changed"),
                                    "Stale join attached to a different table binding");
                            require(
                                    a.table.gameId().equals(LocalTableSessionsFixture.B.toString())
                                            && b.game == connectionB
                                            && http.liveStreams() == 1,
                                    "Stale join changed another game or table");
                            b.logout();
                        }),
                new Step(
                        "multi-table logout clears every table",
                        () -> ready(() -> !b.busy && http.liveStreams() == 0),
                        () -> {
                            for (var controller : List.of(a, b))
                                require(
                                        controller.user() == null
                                                && controller.game == null
                                                && controller.view == null
                                                && controller.presentation == null
                                                && controller.dismissedSettlement == null,
                                        "Logout retained private state in another table");
                            try {
                                require(
                                        http.config.read().isEmpty(),
                                        "Logout retained saved credentials");
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                            require(
                                    Minecraft.getInstance().screen instanceof AccountScreen,
                                    "Login page missing");
                            a.login("tables@example.test", "synthetic-password", false);
                        }),
                new Step(
                        "multi-table rejoin after login",
                        () -> ready(() -> !a.busy),
                        () -> {
                            a.join(LocalTableSessionsFixture.A.toString(), false);
                            b.join(LocalTableSessionsFixture.B.toString(), false);
                        }),
                new Step(
                        "multi-table shared expiry",
                        () ->
                                ready(
                                        () ->
                                                a.view != null
                                                        && b.view != null
                                                        && !a.busy
                                                        && !b.busy
                                                        && http.liveStreams() == 2),
                        () -> {
                            http.authenticated = false;
                            a.validateSession();
                        }),
                new Step(
                        "multi-table expired identity clears both",
                        () ->
                                ready(
                                        () ->
                                                a.user() == null
                                                        && a.view == null
                                                        && b.view == null
                                                        && http.liveStreams() == 0),
                        () -> {
                            require(
                                    a.game == null
                                            && b.game == null
                                            && b.message.equals("session_expired"),
                                    "Shared expiry did not clear another table");
                        }));
    }

    @Override
    public void close() {
        if (tables != null) {
            tables.active().account.clear(false, "welcome");
            tables.active().account.api.close();
        }
        if (http != null) http.close();
        Minecraft.getInstance().setScreen(original);
    }
}
