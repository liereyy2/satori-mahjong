package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Scripted native-UI fixture only. Authoritative rules are tested with real Rust/PostgreSQL. */
final class HostedTransportFixture {
    private final Map<String, JsonObject> tables = new HashMap<>();
    private boolean finished;
    private final Set<String> released = new HashSet<>();
    private final Map<String, Integer> commandCounts = new HashMap<>();
    private long entrySyncedAt, entryWaitMillis = -1;
    private int entrySessions;

    synchronized JsonObject snapshot() {
        var result = new JsonObject();
        var states = new JsonObject();
        tables.forEach((id, state) -> states.add(id, state.deepCopy()));
        result.add("tables", states);
        var deleted = new JsonArray();
        released.forEach(deleted::add);
        result.add("released", deleted);
        var counts = new JsonObject();
        commandCounts.forEach(counts::addProperty);
        result.add("command_counts", counts);
        result.addProperty("entry_wait_ms", entryWaitMillis);
        result.addProperty("entry_sessions", entrySessions);
        return result;
    }

    synchronized void finish() {
        finished = true;
    }

    synchronized JsonObject request(String path, String method, byte[] bytes) {
        String id = path.split("/")[5];
        if (method.equals("DELETE")) {
            tables.remove(id);
            released.add(id);
            var result = new JsonObject();
            result.addProperty("released", true);
            result.addProperty("table_id", id);
            return result;
        }
        if (released.contains(id)) throw new AssertionError("Destroyed table recreated: " + id);
        JsonObject body =
                bytes.length == 0
                        ? new JsonObject()
                        : JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                                .getAsJsonObject();
        JsonObject state =
                tables.computeIfAbsent(
                        id,
                        key -> {
                            var table = new JsonObject();
                            table.addProperty("table_id", key);
                            table.addProperty("instance_id", body.get("instance_id").getAsString());
                            table.addProperty("revision", 0);
                            table.addProperty("phase", "preparing");
                            table.add("game_id", JsonNull.INSTANCE);
                            table.addProperty("length", "hanchan");
                            table.addProperty("rule_mode", "riichi");
                            var seats = new JsonArray();
                            for (int i = 0; i < 4; i++) {
                                var row = new JsonObject();
                                row.addProperty("seat", i);
                                row.addProperty("kind", "empty");
                                seats.add(row);
                            }
                            table.add("seats", seats);
                            return table;
                        });
        if (finished && !state.get("game_id").isJsonNull()) state.addProperty("phase", "finished");
        if (path.endsWith("/sessions")) {
            if (entrySyncedAt != 0) {
                entryWaitMillis = (System.nanoTime() - entrySyncedAt) / 1_000_000;
                entrySessions++;
                entrySyncedAt = 0;
            }
            var response = new JsonObject();
            response.add("game_id", state.get("game_id"));
            var self = new JsonObject();
            self.addProperty("seat", 0);
            self.addProperty("start_cursor", state.get("game_id").getAsString() + ":0:0");
            self.addProperty("seat_token", "synthetic-seat-" + LocalTableSessionsFixture.A);
            response.add("self_session", self);
            return response;
        }
        if (path.endsWith("/commands")) {
            var seats = state.getAsJsonArray("seats");
            String kind = body.get("kind").getAsString();
            commandCounts.merge(kind, 1, Integer::sum);
            switch (kind) {
                case "sync" -> {
                    if (!state.get("game_id").isJsonNull()
                            && !body.getAsJsonArray("players").isEmpty())
                        entrySyncedAt = System.nanoTime();
                    for (var raw : seats) {
                        var row = raw.getAsJsonObject();
                        if (row.get("kind").getAsString().equals("human")) {
                            row.addProperty("kind", "empty");
                            row.remove("player_id");
                            row.remove("name");
                            row.addProperty("connected", false);
                            row.addProperty("ready", false);
                        }
                    }
                    for (var raw : body.getAsJsonArray("players")) {
                        var p = raw.getAsJsonObject();
                        var row = seats.get(p.get("seat").getAsInt()).getAsJsonObject();
                        row.addProperty("kind", "human");
                        row.add("player_id", p.get("player_id"));
                        row.add("name", p.get("name"));
                        row.addProperty("connected", true);
                        row.addProperty("confirmed", false);
                        row.addProperty("ready", false);
                    }
                }

                case "ai" ->
                        seats.get(body.get("seat").getAsInt())
                                .getAsJsonObject()
                                .addProperty(
                                        "kind",
                                        body.get("enabled").getAsBoolean() ? "ai" : "empty");
                case "length" -> state.add("length", body.get("length"));
                case "rules" -> {
                    state.add("rule_mode", body.get("rule_mode"));
                    state.addProperty(
                            "length",
                            body.get("rule_mode").getAsString().equals("sichuan")
                                    ? "8"
                                    : "hanchan");
                }
                case "ready" -> {
                    seats.get(0).getAsJsonObject().add("ready", body.get("ready"));
                    if (body.get("ready").getAsBoolean()
                            && seats.asList().stream()
                                    .noneMatch(
                                            r ->
                                                    r.getAsJsonObject()
                                                            .get("kind")
                                                            .getAsString()
                                                            .equals("empty"))) {
                        if (!body.get("api_key").getAsString().equals("synthetic-selected-key"))
                            throw new AssertionError("Wrong configured AI key");
                        state.addProperty("phase", "playing");
                        state.addProperty("game_id", LocalTableSessionsFixture.A.toString());
                    }
                }
                case "confirm" -> {
                    state.add("game_id", JsonNull.INSTANCE);
                    state.addProperty("phase", "preparing");
                    seats.get(0).getAsJsonObject().addProperty("ready", false);
                    finished = false;
                }
                default -> throw new AssertionError(kind);
            }
            state.addProperty("revision", state.get("revision").getAsInt() + 1);
        }
        return state.deepCopy();
    }

    JsonObject result(JsonObject view) {
        var r = new JsonObject();
        r.add("game_id", view.get("game_id"));
        r.add("seats", view.getAsJsonObject("public").get("seats"));
        r.add("scores", JsonParser.parseString("[40000,30000,20000,10000]"));
        r.add("ranks", JsonParser.parseString("[1,2,3,4]"));
        r.add("pt_deltas", JsonParser.parseString("[90,45,0,-145]"));
        return r;
    }
}
