package cn.satori.mahjong.protocol;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Account-scoped catalog metadata; never contains concealed tiles or credentials. */
public final class SavedGames {
    private SavedGames() {}

    public record Entry(UUID id, Instant created, String status, String length, boolean canDelete) {
        static Entry parse(JsonObject json) {
            return new Entry(
                    UUID.fromString(json.get("game_id").getAsString()),
                    Instant.parse(json.get("date_created").getAsString()),
                    json.get("status").getAsString(),
                    json.getAsJsonObject("rules").get("game_length_mode").getAsString(),
                    json.get("can_delete").getAsBoolean());
        }
    }

    public record Page(List<Entry> items, String nextCursor) {
        public static Page parse(JsonObject json) {
            return new Page(
                    json.getAsJsonArray("items").asList().stream()
                            .map(item -> Entry.parse(item.getAsJsonObject()))
                            .toList(),
                    json.get("next_cursor").isJsonNull()
                            ? null
                            : json.get("next_cursor").getAsString());
        }
    }
}
