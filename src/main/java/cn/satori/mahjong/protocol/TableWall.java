package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.util.*;

/** Physical slots supplied by Rust; this class contains no wall-opening rules. */
public final class TableWall {
    public record Deal(int seat, int index, int slot, int packet) {}

    public record Indicator(int slot, int face) {}

    public final int roller;
    public final boolean dealing;
    public final List<Integer> dice, remaining, deadWall;
    public final List<Deal> deal;
    public final List<Indicator> indicators;
    public final int firstDraw;
    private final JsonArray drawn;

    TableWall(JsonObject raw) {
        roller = raw.get("roller").getAsInt();
        dealing = raw.get("dealing").getAsBoolean();
        dice = ints(raw.getAsJsonArray("dice"));
        remaining = ints(raw.getAsJsonArray("remaining"));
        deadWall = ints(raw.getAsJsonArray("dead_wall"));
        firstDraw = raw.get("first_draw").getAsInt();
        drawn = raw.getAsJsonArray("drawn_slots").deepCopy();
        var deals = new ArrayList<Deal>();
        for (var e : raw.getAsJsonArray("deal")) {
            var d = e.getAsJsonObject();
            deals.add(
                    new Deal(
                            d.get("seat").getAsInt(),
                            d.get("index").getAsInt(),
                            d.get("slot").getAsInt(),
                            d.get("packet").getAsInt()));
        }
        deal = List.copyOf(deals);
        var markers = new ArrayList<Indicator>();
        for (var e : raw.getAsJsonArray("indicators")) {
            var d = e.getAsJsonObject();
            markers.add(new Indicator(d.get("slot").getAsInt(), d.get("face").getAsInt()));
        }
        indicators = List.copyOf(markers);
        if (roller < 0
                || roller > 3
                || dice.size() != 2
                || dice.stream().anyMatch(v -> v < 1 || v > 6)
                || deal.size() != 52
                || new HashSet<>(remaining).size() != remaining.size()
                || remaining.stream().anyMatch(v -> v < 0 || v > 135)
                || drawn.size() != 4) throw new IllegalArgumentException("invalid_wall_display");
    }

    private static List<Integer> ints(JsonArray values) {
        var result = new ArrayList<Integer>();
        values.forEach(v -> result.add(v.getAsInt()));
        return List.copyOf(result);
    }

    public int drawnSlot(int seat) {
        if (drawn.get(seat).isJsonNull()) throw new IllegalArgumentException("missing_draw_slot");
        return drawn.get(seat).getAsInt();
    }
}
