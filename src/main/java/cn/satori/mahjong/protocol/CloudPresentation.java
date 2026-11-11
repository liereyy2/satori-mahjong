package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.util.*;

/** A version-bound Rust display projection; never calculates waits, yaku or scoring locally. */
public final class CloudPresentation {
    private final JsonObject data;
    public final long version;

    public static String errorMessage(Throwable error) {
        return switch (SatoriApi.errorCode(error)) {
            case "http_401", "seat_session_expired" -> "seat_session_expired";
            case "network_error",
                            "http_500",
                            "http_502",
                            "http_503",
                            "http_504",
                            "game_service_unavailable" ->
                    "presentation_error";
            default -> SatoriApi.errorCode(error);
        };
    }

    public CloudPresentation(JsonObject source, CloudView view) {
        if (!source.get("game_id").getAsString().equals(view.gameId.toString())
                || source.get("viewer_seat").getAsInt() != view.viewerSeat
                || source.get("view_version").getAsLong() != view.version
                || !source.get("cursor").getAsString().equals(view.cursor))
            throw new IllegalArgumentException("presentation_identity_mismatch");
        data = source.deepCopy();
        version = view.version;
    }

    public JsonObject settlement() {
        return data.get("settlement").isJsonNull()
                ? null
                : data.getAsJsonObject("settlement").deepCopy();
    }

    public record Hint(
            int shanten,
            List<Integer> waits,
            List<Integer> yakuWaits,
            boolean noYaku,
            boolean furiten) {}

    public Hint hint(Integer discard) {
        JsonElement value = data.get("hand_hint");
        if (discard != null) {
            value = JsonNull.INSTANCE;
            for (var raw : data.getAsJsonArray("discard_hints")) {
                var item = raw.getAsJsonObject();
                if (item.get("tile_id").getAsInt() == discard) {
                    value = item.get("hint");
                    break;
                }
            }
        }
        return parseHint(value);
    }

    static Hint parseHint(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        var hint = value.getAsJsonObject();
        return new Hint(
                hint.get("shanten").getAsInt(),
                integers(hint.getAsJsonArray("waits")),
                integers(hint.getAsJsonArray("yaku_waits")),
                hint.get("no_yaku").getAsBoolean(),
                hint.get("furiten").getAsBoolean());
    }

    private static List<Integer> integers(JsonArray values) {
        List<Integer> result = new ArrayList<>();
        values.forEach(v -> result.add(v.getAsInt()));
        return List.copyOf(result);
    }

    public static List<Integer> actionTiles(CloudView.Action action) {
        List<Integer> result = new ArrayList<>();
        var event = action.display().getAsJsonObject("event");
        if (event == null) return List.of();
        if (event.has("consumed") && !action.type().equals("kakan"))
            event.getAsJsonArray("consumed").forEach(v -> result.add(tileId(v.getAsString())));
        else if (event.has("pai")) result.add(tileId(event.get("pai").getAsString()));
        return List.copyOf(result);
    }

    static int tileId(String name) {
        for (int id = 0; id <= 37; id++) if (CloudView.tileName(id).equals(name)) return id;
        throw new IllegalArgumentException("invalid_action_tile");
    }
}
