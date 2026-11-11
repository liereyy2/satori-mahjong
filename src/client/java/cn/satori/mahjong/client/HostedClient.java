package cn.satori.mahjong.client;

import cn.satori.mahjong.HostedPackets;
import cn.satori.mahjong.TablePackets;

import com.google.gson.*;

import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.function.Consumer;

/** Recipient-scoped cloud projections. No shared account credentials exist on this path. */
final class HostedClient {
    final ClientController client;
    JsonObject packet = new JsonObject();
    private String credential = "";
    private String openingGame = "";
    String removedGame = "";
    private Consumer<UUID> feedbackSuccess;
    private Consumer<String> feedbackFailure;
    private long reconnectAfter;

    HostedClient(ClientController client) {
        this.client = client;
    }

    JsonObject table() {
        return packet.has("table") ? packet.getAsJsonObject("table") : null;
    }

    JsonObject self() {
        if (table() != null)
            for (var value : table().getAsJsonArray("seats")) {
                var member = value.getAsJsonObject();
                if (member.has("player_id")
                        && member.get("player_id").equals(packet.get("player_id"))) return member;
            }
        return null;
    }

    boolean configured() {
        return packet.has("configured") && packet.get("configured").getAsBoolean();
    }

    String phase() {
        return table() == null ? "loading" : table().get("phase").getAsString();
    }

    void accept(HostedPackets.State message, JsonObject json) {
        boolean wasPreparing = table() != null && table().get("game_id").isJsonNull();
        packet = json;
        client.dimension = message.dimension();
        String game =
                table() == null || table().get("game_id").isJsonNull()
                        ? ""
                        : table().get("game_id").getAsString();
        if (game.isEmpty()) openingGame = "";
        else if (wasPreparing) openingGame = game;
        client.table = new TablePackets.Open(message.pos(), game, false);
        client.message = json.get("error").getAsString();
        String deleted =
                table() != null
                                && table().has("deleted_game_id")
                                && !table().get("deleted_game_id").isJsonNull()
                        ? table().get("deleted_game_id").getAsString()
                        : "";
        if (client.game != null && client.game.gameId.toString().equals(deleted)) {
            removedGame = deleted;
            client.gameRemoved();
        }
        if (!game.isEmpty() && !game.equals(removedGame)) removedGame = "";
        if (!removedGame.isEmpty()) client.message = "game_deleted";
        client.busy = false;
        var member = self();
        boolean confirmed = member != null && member.get("confirmed").getAsBoolean();
        if (!configured() || game.isEmpty() || game.equals(removedGame) || confirmed) {
            if (client.game != null) client.closeGame();
            credential = "";
        } else if (json.has("session") && client.seated()) {
            var session = json.getAsJsonObject("session");
            String token = session.getAsJsonObject("self_session").get("seat_token").getAsString();
            if (session.get("game_id").getAsString().equals(game) && !credential.equals(token)) {
                client.attach(session, game.equals(openingGame));
                openingGame = "";
                credential = token;
            }
        }
        if (json.has("reply")
                && json.getAsJsonObject("reply").has("ticket_id")
                && feedbackSuccess != null) {
            feedbackSuccess.accept(
                    UUID.fromString(json.getAsJsonObject("reply").get("ticket_id").getAsString()));
            feedbackSuccess = null;
            feedbackFailure = null;
            client.message = "feedback_sent";
        } else if (!client.message.isEmpty() && feedbackFailure != null) {
            feedbackFailure.accept(client.message);
            feedbackSuccess = null;
            feedbackFailure = null;
        }
        var mc = Minecraft.getInstance();
        if (message.open()
                || (game.isEmpty()
                        && mc.screen instanceof SeatedTableScreen page
                        && page.controller() == client)
                || ((confirmed || game.isEmpty())
                        && mc.screen instanceof MatchResultScreen result
                        && result.controller() == client))
            mc.setScreen(new HostedLobbyScreen(client));
    }

    void tick() {
        var mc = Minecraft.getInstance();
        if (!client.seated()) {
            if (client.game != null) client.closeGame();
            credential = "";
            return;
        }
        if (client.connection.equals("seat_session_expired")
                && System.nanoTime() >= reconnectAfter) {
            reconnectAfter = System.nanoTime() + 5_000_000_000L;
            command("session", new JsonObject());
        }
        if (mc.screen instanceof HostedLobbyScreen page
                && page.controller() == client
                && client.view != null
                && client.game != null) {
            if (client.view.status().equals("playing")) mc.setScreen(new SeatedTableScreen(client));
            else if (client.view.status().equals("finished")
                    && !(self() != null && self().get("confirmed").getAsBoolean()))
                mc.setScreen(new MatchResultScreen(client));
        }
    }

    void command(String kind, JsonObject body) {
        if (client.busy || client.table == null) return;
        var data = body.deepCopy();
        data.addProperty("kind", kind);
        client.busy = true;
        SatoriClient.sendHosted(
                new HostedPackets.Request(
                        client.dimension, client.table.pos(), false, data.toString()));
    }

    void feedback(
            cn.satori.mahjong.protocol.BugReport report,
            String title,
            String description,
            Consumer<UUID> success,
            Consumer<String> failure) {
        feedbackSuccess = success;
        feedbackFailure = failure;
        var data = new JsonObject();
        data.addProperty("subject", title);
        data.addProperty("description", description);
        var context = new JsonObject();
        context.addProperty("version", report.modVersion());
        context.addProperty("loader", report.loader());
        data.add("context", context);
        command("feedback", data);
    }

    void confirm() {
        var body = new JsonObject();
        body.addProperty("game_id", client.game.gameId.toString());
        command("confirm", body);
    }
}
