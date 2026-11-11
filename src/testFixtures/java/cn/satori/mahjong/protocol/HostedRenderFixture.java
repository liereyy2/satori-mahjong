package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Transport-only fixture using visibility scopes captured from the real Rust engine. */
final class HostedRenderFixture {
    private final JsonArray template;
    private final Map<String, String> fixtureHuman = new HashMap<>();
    private final Map<String, JsonObject> current = new HashMap<>();
    private final Map<String, List<JsonObject>> updates = new HashMap<>();

    HostedRenderFixture() throws IOException {
        try (var in = getClass().getResourceAsStream("/table-stream-examples.json")) {
            template =
                    JsonParser.parseReader(
                                    new InputStreamReader(
                                            Objects.requireNonNull(in), StandardCharsets.UTF_8))
                            .getAsJsonObject()
                            .getAsJsonObject("opening")
                            .getAsJsonArray("views");
        }
    }

    void attach(JsonObject table, JsonObject seatView, String query) {
        String id = table.get("table_id").getAsString();
        String game = table.get("game_id").getAsString();
        // This native transport scenario has one human in seat zero, retained off-chair.
        var human = table.getAsJsonArray("seats").get(0).getAsJsonObject();
        if (human.has("player_id"))
            fixtureHuman.putIfAbsent(id, human.get("player_id").getAsString());
        var views = template.deepCopy();
        for (int seat = 0; seat < template.size(); seat++) {
            var view = views.get(seat).getAsJsonObject();
            for (String field : List.of("game_id", "status", "public", "display"))
                view.add(field, seatView.get(field).deepCopy());
            view.remove("cursor");
            view.remove("view_version");
            view.add("decision", JsonNull.INSTANCE);
            if (seat == 0) view.add("private", seatView.get("private").deepCopy());
            if (seat == 4) {
                var hand = new JsonArray();
                for (var tile : seatView.getAsJsonObject("private").getAsJsonArray("hand"))
                    hand.add(tile.getAsJsonObject().get("tile_id"));
                view.getAsJsonObject("table_hands").getAsJsonArray("hands").set(0, hand);
                view.getAsJsonObject("table_hands")
                        .getAsJsonArray("last_tsumo_tiles")
                        .set(0, seatView.getAsJsonObject("private").get("last_tsumo_tile"));
            }
        }
        var previous = current.get(id);
        var history = updates.computeIfAbsent(id, ignored -> new ArrayList<>());
        if (previous == null || !previous.get("content").equals(views)) {
            long sequence = previous == null ? 1 : previous.get("sequence").getAsLong() + 1;
            var next = new JsonObject();
            next.add("content", views.deepCopy());
            next.addProperty("sequence", sequence);
            String cursor = game + ":table:" + sequence;
            next.addProperty("cursor", cursor);
            for (var raw : views) {
                raw.getAsJsonObject().addProperty("cursor", cursor);
                raw.getAsJsonObject().addProperty("view_version", sequence);
            }
            next.add("views", views);
            var frame = new JsonObject();
            frame.addProperty("from_cursor", game + ":table:" + (sequence - 1));
            frame.addProperty("cursor", cursor);
            frame.add("views", views.deepCopy());
            var events = new JsonArray();
            for (int seat = 0; seat < template.size(); seat++) events.add(new JsonArray());
            frame.add("events", events);
            history.add(frame);
            current.put(id, next);
        }
        var now = current.get(id);
        var response = new JsonObject();
        var participants = new JsonObject();
        if (fixtureHuman.containsKey(id)) participants.addProperty(fixtureHuman.get(id), 0);
        response.add("participants", participants);
        response.addProperty("participant_revision", 1);
        response.add("views", now.get("views").deepCopy());
        response.add("cursor", now.get("cursor"));
        String after = null;
        for (String item : query.split("&"))
            if (item.startsWith("after_cursor="))
                after = URLDecoder.decode(item.substring(13), StandardCharsets.UTF_8);
        response.addProperty("reset", after == null);
        var delta = new JsonArray();
        if (after != null) {
            boolean found = false;
            for (var frame : history) {
                if (frame.get("from_cursor").getAsString().equals(after)) found = true;
                if (found) delta.add(frame.deepCopy());
            }
        }
        response.add("updates", delta);
        table.add("render_stream", response);
    }
}
