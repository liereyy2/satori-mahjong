package cn.satori.mahjong.protocol;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Validated final projection. Rank/tie order and pt are calculated only by Rust. */
public record MatchResult(UUID gameId, List<Player> players) {
    public record Player(
            int seat, String userId, String name, boolean ai, int rank, int score, int pt) {}

    public static MatchResult parse(JsonObject json, UUID expected) {
        UUID id = UUID.fromString(json.get("game_id").getAsString());
        if (!id.equals(expected)) throw new IllegalArgumentException("result_identity_mismatch");
        for (String key : List.of("seats", "ranks", "scores", "pt_deltas"))
            if (json.getAsJsonArray(key).size() != 4)
                throw new IllegalArgumentException("invalid_result");
        var players = new ArrayList<Player>();
        var ranks = new HashSet<Integer>();
        for (int i = 0; i < 4; i++) {
            var seat = json.getAsJsonArray("seats").get(i).getAsJsonObject();
            int rank = json.getAsJsonArray("ranks").get(i).getAsInt();
            if (seat.get("seat").getAsInt() != i || rank < 1 || rank > 4 || !ranks.add(rank))
                throw new IllegalArgumentException("invalid_result_order");
            boolean ai = seat.get("kind").getAsString().equals("ai");
            String user =
                    ai
                            ? "AI " + (i + 1)
                            : seat.get("user_id").isJsonNull()
                                    ? ""
                                    : UUID.fromString(seat.get("user_id").getAsString()).toString();
            players.add(
                    new Player(
                            i,
                            user,
                            seat.has("name") ? seat.get("name").getAsString() : "",
                            ai,
                            rank,
                            json.getAsJsonArray("scores").get(i).getAsInt(),
                            json.getAsJsonArray("pt_deltas").get(i).getAsInt()));
        }
        players.sort(Comparator.comparingInt(Player::rank));
        return new MatchResult(id, List.copyOf(players));
    }
}
