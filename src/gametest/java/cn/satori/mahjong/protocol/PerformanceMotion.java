package cn.satori.mahjong.protocol;

import com.google.gson.JsonObject;

import java.util.List;

/** Test-only access to the real presentation interpolation. */
public final class PerformanceMotion {
    private final TableTransition motion;

    public PerformanceMotion(JsonObject exchange) {
        var after = view(exchange.getAsJsonObject("after"));
        motion =
                new TableTransition(
                        TableScene.layout(view(exchange.getAsJsonObject("before"))),
                        TableScene.layout(after),
                        exchange.getAsJsonArray("events"),
                        0,
                        after.wall);
    }

    public List<TableScene.Tile> sample(long time) {
        return motion.sample(time % motion.endsAt());
    }

    public static CloudView view(JsonObject source) {
        return new CloudView(
                source,
                java.util.UUID.fromString(source.get("game_id").getAsString()),
                source.get("viewer_seat").getAsInt());
    }

    public int size() {
        return motion.target().size();
    }
}
