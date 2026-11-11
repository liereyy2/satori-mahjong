package cn.satori.mahjong.client;

import cn.satori.mahjong.HostedPackets;
import cn.satori.mahjong.protocol.GameConnection;

import com.google.gson.*;

import net.minecraft.client.Minecraft;

import java.util.*;

/** Native deletion presentation with the same recipient packet as the server bridge. */
final class DeletedGameInteractionChecks {
    static void verify() {
        var mc = Minecraft.getInstance();
        var original = SatoriClient.TABLES.active();
        var previous = mc.screen;
        var client = new ClientController(original.account);
        try {
            client.table = original.table;
            client.dimension = original.dimension;
            client.hosted = new HostedClient(client);
            client.hosted.packet = original.hosted.packet.deepCopy();
            var session = original.hosted.packet.getAsJsonObject("session");
            client.game =
                    new GameConnection(
                            original.account.api,
                            original.game.gameId,
                            session.getAsJsonObject("self_session"),
                            (view, events) -> {},
                            status -> {});
            client.view = original.view;
            mc.setScreen(new SeatedTableScreen(client));
            client.gameRemoved();
            require(
                    client.game == null && client.view == null && client.presentation == null,
                    "Deleted match retained private state");
            require(
                    mc.screen instanceof HostedLobbyScreen,
                    "Deleted match did not leave play screen");
            require(client.message.equals("game_not_found"), "Missing-game cause was hidden");
            var packet = original.hosted.packet.deepCopy();
            packet.remove("session");
            packet.addProperty("error", "game_deleted");
            var table = packet.getAsJsonObject("table");
            table.addProperty("deleted_game_id", original.game.gameId.toString());
            table.add("game_id", JsonNull.INSTANCE);
            table.add("public_view", JsonNull.INSTANCE);
            table.addProperty("phase", "preparing");
            for (var raw : table.getAsJsonArray("seats"))
                raw.getAsJsonObject().addProperty("ready", false);
            client.hosted.accept(
                    new HostedPackets.State(
                            original.dimension,
                            original.table.pos(),
                            false,
                            false,
                            packet.toString()),
                    packet);
            require(client.table.gameId().isEmpty(), "Deleted game remained bound to client table");
            require(
                    client.hosted.phase().equals("preparing")
                            && client.message.equals("game_deleted"),
                    "Deletion preparation notice was lost");
            require(
                    original.game != null && original.view != null,
                    "Deletion affected a different controller");
        } finally {
            client.closeGame();
            original.account.controllers.remove(client);
            mc.setScreen(previous);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
