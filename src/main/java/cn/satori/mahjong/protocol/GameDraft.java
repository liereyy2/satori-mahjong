package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.util.*;

/** Seat assignment validation only; all mahjong rules remain in the cloud Rust engine. */
public final class GameDraft {
    private GameDraft() {}

    public static JsonObject create(
            UUID owner, List<String> seats, String apiKey, boolean east, UUID requestId) {
        JsonObject result = plan(owner, seats, east, requestId);
        boolean hasAi = !result.getAsJsonArray("ai_seats").isEmpty();
        if (hasAi && apiKey.isBlank()) throw new IllegalArgumentException("ai_key_required");
        if (!hasAi && !apiKey.isBlank()) throw new IllegalArgumentException("unused_ai_key");
        if (hasAi) result.addProperty("api_key", apiKey);
        return result;
    }

    public static JsonObject plan(UUID owner, List<String> seats, boolean east, UUID requestId) {
        if (seats.size() != 4) throw new IllegalArgumentException("four_seats_required");
        Set<UUID> humans = new HashSet<>();
        JsonArray players = new JsonArray(), ai = new JsonArray();
        for (int i = 0; i < 4; i++) {
            String value = seats.get(i).trim();
            if (value.equalsIgnoreCase("AI")) {
                ai.add(i);
                continue;
            }
            UUID id;
            try {
                id = UUID.fromString(value);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("seat_requires_satori_uuid_or_ai");
            }
            if (!humans.add(id)) throw new IllegalArgumentException("duplicate_user");
            JsonObject player = new JsonObject();
            player.addProperty("user_id", id.toString());
            player.addProperty("seat", i);
            players.add(player);
        }
        if (!humans.contains(owner)) throw new IllegalArgumentException("owner_must_have_seat");
        JsonObject result = new JsonObject();
        result.addProperty("client_request_id", requestId.toString());
        result.addProperty("user_id", owner.toString());
        result.add("players", players);
        result.add("ai_seats", ai);
        JsonObject rules = new JsonObject();
        rules.addProperty("rule_mode", "riichi");
        rules.addProperty("n_players", 4);
        rules.addProperty("game_length_mode", east ? "east" : "hanchan");
        result.add("rules", rules);
        return result;
    }
}
