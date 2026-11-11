package cn.satori.mahjong.protocol;

import cn.satori.mahjong.protocol.TableScene.Tile;

import com.google.gson.*;

import java.util.*;

/** Allocate outgoing tiles before survivors, so a single physical tile never moves twice. */
final class TileOrigins {
    final Map<String, Tile> origins = new HashMap<>();
    final Set<String> used = new HashSet<>();
    private final List<Tile> before;
    private final Set<String> afterKeys = new HashSet<>();

    TileOrigins(List<Tile> before, List<Tile> after, JsonArray events, TableWall wall) {
        this.before = before;
        after.forEach(t -> afterKeys.add(t.key()));
        Set<String> oldKeys = new HashSet<>();
        before.forEach(t -> oldKeys.add(t.key()));
        // Reserve every discard / consumed tile before assigning the sorted surviving hand.
        // Reserve the exposed faces before choosing the two face-down kan tiles.
        // Otherwise an anonymous outer tile could consume the red five's origin.
        for (Tile tile :
                after.stream()
                        .sorted(
                                Comparator.comparingInt(
                                        t -> t.key().startsWith("meld-") && t.face() == 37 ? 1 : 0))
                        .toList()) {
            if (oldKeys.contains(tile.key())) continue;
            if (tile.key().startsWith("river-")) {
                JsonObject event = event(events, tile.seat(), Set.of("dahai"));
                if (event != null) put(tile, discarded(tile, event));
            } else if (tile.key().startsWith("meld-")) {
                JsonObject event =
                        event(
                                events,
                                tile.seat(),
                                Set.of("chi", "pon", "daiminkan", "ankan", "kakan"));
                if (event == null) continue;
                if (tile.sideways() && !event.get("type").getAsString().equals("kakan")) {
                    int seat = event.get("target").getAsInt();
                    put(
                            tile,
                            before.stream()
                                    .filter(
                                            t ->
                                                    t.key().startsWith("river-" + seat + "-")
                                                            && !afterKeys.contains(t.key())
                                                            && !used.contains(t.key())
                                                            && t.face() == tile.face())
                                    .reduce((a, b) -> b)
                                    .orElse(null));
                } else if (tile.face() == 37 && event.get("type").getAsString().equals("ankan")) {
                    String group = tile.key().substring(0, tile.key().lastIndexOf('-') + 1);
                    int normal =
                            after.stream()
                                    .filter(
                                            t ->
                                                    t.key().startsWith(group)
                                                            && t.face() >= 0
                                                            && t.face() < 34)
                                    .findFirst()
                                    .orElseThrow()
                                    .face();
                    put(tile, matchingHand(tile, normal));
                } else put(tile, matchingHand(tile));
            }
        }
        for (Tile tile : after) {
            if (!tile.key().startsWith("hand-")) continue;
            Tile same =
                    before.stream()
                            .filter(
                                    t ->
                                            t.key().equals(tile.key())
                                                    && t.face() == tile.face()
                                                    && !used.contains(t.key()))
                            .findFirst()
                            .orElse(null);
            Tile origin = same != null ? same : matchingHand(tile);
            if (tile.key().endsWith("-drawn") && same == null) origin = null;
            if (origin == null && event(events, tile.seat(), Set.of("tsumo")) != null) {
                String key = "wall-" + wall.drawnSlot(tile.seat());
                origin =
                        before.stream()
                                .filter(t -> t.key().equals(key) && !used.contains(t.key()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "missing_draw_origin:" + key));
            }
            put(tile, origin);
        }
    }

    private void put(Tile tile, Tile origin) {
        if (origin == null) return;
        origins.put(tile.key(), origin);
        used.add(origin.key());
    }

    private Tile matchingHand(Tile tile) {
        return matchingHand(tile, tile.face());
    }

    private Tile matchingHand(Tile tile, int face) {
        return before.stream()
                .filter(
                        t ->
                                t.key().startsWith("hand-")
                                        && t.seat() == tile.seat()
                                        && !used.contains(t.key())
                                        && (t.face() == face || t.face() == 37))
                .findFirst()
                .orElse(null);
    }

    private Tile discarded(Tile tile, JsonObject event) {
        boolean tsumogiri = event.has("tsumogiri") && event.get("tsumogiri").getAsBoolean();
        if (tsumogiri)
            return before.stream()
                    .filter(t -> t.key().equals("hand-" + tile.seat() + "-drawn"))
                    .findFirst()
                    .orElseThrow(
                            () -> new IllegalArgumentException("missing_drawn_discard_origin"));
        List<Tile> hand =
                before.stream()
                        .filter(
                                t ->
                                        t.key().startsWith("hand-")
                                                && t.seat() == tile.seat()
                                                && !t.key().endsWith("-drawn")
                                                && !used.contains(t.key()))
                        .sorted(
                                Comparator.comparingInt((Tile t) -> CloudView.handOrder(t.face()))
                                        .thenComparingInt(
                                                t ->
                                                        Integer.parseInt(
                                                                t.key()
                                                                        .substring(
                                                                                t.key()
                                                                                                .lastIndexOf(
                                                                                                        '-')
                                                                                        + 1))))
                        .toList();
        if (hand.isEmpty()) return null;
        if (event.has("hand_index")) {
            int index = event.get("hand_index").getAsInt();
            if (index < 0 || index >= hand.size())
                throw new IllegalArgumentException("invalid_discard_hand_index");
            var selected = hand.get(index);
            if (selected.face() != 37 && selected.face() != tile.face())
                throw new IllegalArgumentException("discard_source_face_mismatch");
            return selected;
        }
        if (hand.stream().allMatch(t -> t.face() == 37)) {
            // Public river identity seeds the visual choice; never a hidden tile or real hand
            // order.
            int seed = tile.key().hashCode();
            seed ^= seed >>> 16;
            seed *= 0x45d9f3b;
            seed ^= seed >>> 16;
            return hand.get(Math.floorMod(seed, hand.size()));
        }
        return hand.stream().filter(t -> t.face() == tile.face()).findFirst().orElse(null);
    }

    private static JsonObject event(JsonArray events, int actor, Set<String> types) {
        for (JsonElement raw : events) {
            JsonObject e = raw.getAsJsonObject();
            if (e.has("actor")
                    && e.get("actor").getAsInt() == actor
                    && types.contains(e.get("type").getAsString())) return e;
        }
        return null;
    }
}
