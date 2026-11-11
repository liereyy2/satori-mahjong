package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.util.*;

/** Immutable seat-scoped display projection. No local rule or state transition engine. */
public final class CloudView {
    private final JsonObject data;
    public final UUID gameId;
    public final int viewerSeat;
    public final long version;
    public final String cursor;
    private final int decisionSeat;
    private final String reactionDiscard;
    private final CloudPresentation.Hint standingHint;
    public final TableWall wall;

    /** A required display contract is missing; this is not a recoverable transport failure. */
    public static final class ServerUpdateRequired extends IllegalArgumentException {
        public ServerUpdateRequired() {
            super("server_update_required");
        }
    }

    public CloudView(JsonObject source, UUID expectedGame, int expectedSeat) {
        data = source.deepCopy();
        gameId = UUID.fromString(data.get("game_id").getAsString());
        viewerSeat = data.get("viewer_seat").getAsInt();
        if (!gameId.equals(expectedGame)
                || viewerSeat != expectedSeat
                || viewerSeat < 0
                || viewerSeat > 4) throw new IllegalArgumentException("view_identity_mismatch");
        if (viewerSeat == 4
                && (!data.get("private").isJsonNull() || !data.get("decision").isJsonNull()))
            throw new IllegalArgumentException("private_observer_view");
        if (data.has("table_hands")) {
            if (viewerSeat != 4
                    || !data.get("private").isJsonNull()
                    || !data.get("decision").isJsonNull())
                throw new IllegalArgumentException("private_table_observer");
            var hands = data.getAsJsonObject("table_hands").getAsJsonArray("hands");
            var drawn = data.getAsJsonObject("table_hands").getAsJsonArray("last_tsumo_tiles");
            if (hands.size() != 4 || drawn.size() != 4)
                throw new IllegalArgumentException("invalid_table_hands");
            for (int seat = 0; seat < 4; seat++) {
                if (hands.get(seat).getAsJsonArray().size()
                        != data.getAsJsonObject("public")
                                .getAsJsonArray("hand_counts")
                                .get(seat)
                                .getAsInt())
                    throw new IllegalArgumentException("invalid_table_hands");
                for (var tile : hands.get(seat).getAsJsonArray())
                    if (tile.getAsInt() < 0 || tile.getAsInt() >= 37)
                        throw new IllegalArgumentException("invalid_table_hands");
            }
        }
        version = data.get("view_version").getAsLong();
        cursor = data.get("cursor").getAsString();
        if (data.getAsJsonObject("public").getAsJsonArray("hand_counts").size() != 4)
            throw new IllegalArgumentException("invalid_view");
        decisionSeat = validateDisplay(data.get("display"));
        var display = data.getAsJsonObject("display");
        if (viewerSeat < 4 && !display.has("standing_hint")) throw new ServerUpdateRequired();
        if (viewerSeat == 4 && display.has("standing_hint"))
            throw new IllegalArgumentException("private_observer_hint");
        standingHint =
                viewerSeat < 4 ? CloudPresentation.parseHint(display.get("standing_hint")) : null;
        if (!display.has("reaction_discard")) throw new ServerUpdateRequired();
        var reaction = display.get("reaction_discard");
        reactionDiscard = reaction.isJsonNull() ? null : validateReaction(reaction);
        if (!display.has("wall")) throw new ServerUpdateRequired();
        wall =
                display.get("wall").isJsonNull()
                        ? null
                        : new TableWall(display.getAsJsonObject("wall"));
        if (wall == null && !Set.of("waiting_players", "ready", "cancelled").contains(status()))
            throw new IllegalArgumentException("missing_round_wall");
    }

    private String validateReaction(JsonElement raw) {
        try {
            var value = raw.getAsJsonObject();
            int seat = value.get("seat").getAsBigDecimal().intValueExact();
            int index = value.get("index").getAsBigDecimal().intValueExact();
            if (seat < 0 || seat > 3 || index < 0) throw new IllegalArgumentException();
            var river =
                    data.getAsJsonObject("public")
                            .getAsJsonArray("discards")
                            .get(seat)
                            .getAsJsonArray();
            if (index >= river.size()
                    || river.get(index).getAsJsonObject().get("was_called").getAsBoolean())
                throw new IllegalArgumentException();
            return "river-" + seat + "-" + index;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid_reaction_discard", invalid);
        }
    }

    public String reactionDiscard() {
        return reactionDiscard;
    }

    /** Rust's last resolved own decision, delivered atomically with this authorized view. */
    public CloudPresentation.Hint standingHint() {
        return standingHint;
    }

    private static int validateDisplay(JsonElement raw) {
        if (raw == null) throw new ServerUpdateRequired();
        if (!raw.isJsonObject()) throw new IllegalArgumentException("invalid_display");
        var display = raw.getAsJsonObject();
        if (!display.has("drawn_seats") || !display.has("decision_seat"))
            throw new ServerUpdateRequired();
        var drawn = display.get("drawn_seats");
        if (!drawn.isJsonArray() || drawn.getAsJsonArray().size() != 4)
            throw new IllegalArgumentException("invalid_hand_display");
        for (var value : drawn.getAsJsonArray())
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("invalid_hand_display");
        var decision = display.get("decision_seat");
        if (decision.isJsonNull()) return -1;
        if (!decision.isJsonPrimitive() || !decision.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("invalid_decision_seat");
        int seat;
        try {
            seat = decision.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid_decision_seat", invalid);
        }
        if (seat < 0 || seat > 3) throw new IllegalArgumentException("invalid_decision_seat");
        return seat;
    }

    public String ruleMode() {
        return data.getAsJsonObject("rules").get("rule_mode").getAsString();
    }

    public int voidSuit(int seat) {
        if (!ruleMode().equals("sichuan")) return -1;
        var raw =
                data.getAsJsonObject("public")
                        .getAsJsonObject("sichuan")
                        .getAsJsonArray("void_suits")
                        .get(seat);
        return raw.isJsonNull() ? -1 : raw.getAsInt();
    }

    public String status() {
        return data.get("status").getAsString();
    }

    public String pauseReason() {
        return string(data, "pause_reason", "");
    }

    public boolean needsCredits() {
        return status().equals("paused") && pauseReason().equals("ai_terminal_http_402");
    }

    public UUID owner() {
        return UUID.fromString(data.get("owner_user_id").getAsString());
    }

    public JsonObject publicData() {
        return data.getAsJsonObject("public").deepCopy();
    }

    public JsonObject decision() {
        return data.has("decision") && !data.get("decision").isJsonNull()
                ? data.getAsJsonObject("decision").deepCopy()
                : null;
    }

    public List<Action> actions() {
        JsonObject d = decision();
        if (d == null) return List.of();
        List<Action> actions = new ArrayList<>();
        for (JsonElement e : d.getAsJsonArray("actions")) {
            JsonObject a = e.getAsJsonObject();
            actions.add(
                    new Action(
                            d.get("decision_id").getAsString(),
                            a.get("action_id").getAsString(),
                            a.get("type").getAsString(),
                            string(a, "tile", ""),
                            a.deepCopy()));
        }
        return List.copyOf(actions);
    }

    public record Action(
            String decisionId, String actionId, String type, String tile, JsonObject display) {
        public Action consumeSlots(Collection<String> slots) {
            var next = display.deepCopy();
            var selected = new JsonArray();
            slots.forEach(selected::add);
            next.add("consume_slot_ids", selected);
            return new Action(decisionId, actionId, type, tile, next);
        }
    }

    public List<Integer> hand(int seat) {
        if (seat < 0 || seat > 3) throw new IllegalArgumentException("invalid_seat");
        List<Integer> result = new ArrayList<>();
        if (tableObserver()) {
            for (var tile :
                    data.getAsJsonObject("table_hands")
                            .getAsJsonArray("hands")
                            .get(seat)
                            .getAsJsonArray()) result.add(tile.getAsInt());
        } else if (seat == viewerSeat) {
            for (JsonElement e : data.getAsJsonObject("private").getAsJsonArray("hand"))
                result.add(e.getAsJsonObject().get("tile_id").getAsInt());
        } else {
            JsonArray revealed = data.getAsJsonObject("public").getAsJsonArray("revealed_hands");
            if (revealed != null && !revealed.get(seat).isJsonNull()) {
                for (JsonElement e : revealed.get(seat).getAsJsonArray()) result.add(e.getAsInt());
            } else {
                int count =
                        data.getAsJsonObject("public")
                                .getAsJsonArray("hand_counts")
                                .get(seat)
                                .getAsInt();
                if (count < 0 || count > 14)
                    throw new IllegalArgumentException("invalid_hand_count");
                for (int i = 0; i < count; i++) result.add(37);
            }
        }
        result.sort(Comparator.comparingInt(CloudView::handOrder));
        return List.copyOf(result);
    }

    /**
     * Rust identifies the turn actor or the source of a pending reaction, including robbing kan.
     */
    public int decisionSeat() {
        return decisionSeat;
    }

    public List<Integer> aiSeats() {
        var result = new ArrayList<Integer>();
        for (var raw : data.getAsJsonObject("public").getAsJsonArray("seats")) {
            var seat = raw.getAsJsonObject();
            if (seat.get("kind").getAsString().equals("ai"))
                result.add(seat.get("seat").getAsInt());
        }
        return List.copyOf(result);
    }

    public OptionalInt winningTile(int seat) {
        if (!ruleMode().equals("sichuan")) return OptionalInt.empty();
        var tile =
                data.getAsJsonObject("public")
                        .getAsJsonObject("sichuan")
                        .getAsJsonArray("winning_tiles")
                        .get(seat);
        return tile.isJsonNull() ? OptionalInt.empty() : OptionalInt.of(tile.getAsInt());
    }

    public boolean revealed(int seat) {
        return !data.getAsJsonObject("public")
                .getAsJsonArray("revealed_hands")
                .get(seat)
                .isJsonNull();
    }

    public boolean tableObserver() {
        return data.has("table_hands");
    }

    public boolean hasDrawn(int seat) {
        return data.getAsJsonObject("display")
                .getAsJsonArray("drawn_seats")
                .get(seat)
                .getAsBoolean();
    }

    /** The drawing seat alone receives the face. Other players only get an opaque slot. */
    public int drawnFace(int seat) {
        if (!hasDrawn(seat)) throw new IllegalStateException("seat_has_no_drawn_tile");
        if (tableObserver())
            return data.getAsJsonObject("table_hands")
                    .getAsJsonArray("last_tsumo_tiles")
                    .get(seat)
                    .getAsInt();
        return seat == viewerSeat
                ? data.getAsJsonObject("private").get("last_tsumo_tile").getAsInt()
                : 37;
    }

    public static int handOrder(int id) {
        return switch (id) {
            case 34 -> 4 * 2 + 1;
            case 35 -> 13 * 2 + 1;
            case 36 -> 22 * 2 + 1;
            default -> id * 2;
        };
    }

    /** Map rendered physical copies to the exact Rust-owned slots in this decision window. */
    public Map<String, String> handSlots() {
        if (viewerSeat == 4) return Map.of();
        var held = new ArrayList<JsonObject>();
        for (var value : data.getAsJsonObject("private").getAsJsonArray("hand"))
            held.add(value.getAsJsonObject());
        var result = new LinkedHashMap<String, String>();
        if (hasDrawn(viewerSeat)) {
            int drawn = drawnFace(viewerSeat);
            for (int i = held.size() - 1; i >= 0; i--)
                if (held.get(i).get("tile_id").getAsInt() == drawn) {
                    result.put(
                            "hand-" + viewerSeat + "-drawn",
                            held.remove(i).get("slot_id").getAsString());
                    break;
                }
        }
        held.sort(Comparator.comparingInt(t -> handOrder(t.get("tile_id").getAsInt())));
        var occurrences = new HashMap<Integer, Integer>();
        for (var tile : held) {
            int face = tile.get("tile_id").getAsInt();
            result.put(
                    "hand-"
                            + viewerSeat
                            + "-"
                            + face
                            + "-"
                            + occurrences.merge(face, 1, Integer::sum),
                    tile.get("slot_id").getAsString());
        }
        return Collections.unmodifiableMap(result);
    }

    public Optional<Action> discardFor(String tileKey) {
        var slot = handSlots().get(tileKey);
        if (slot == null) return Optional.empty();
        int tileId =
                data.getAsJsonObject("private").getAsJsonArray("hand").asList().stream()
                        .map(JsonElement::getAsJsonObject)
                        .filter(t -> t.get("slot_id").getAsString().equals(slot))
                        .findFirst()
                        .orElseThrow()
                        .get("tile_id")
                        .getAsInt();
        return actions().stream()
                .filter(a -> a.type().equals("dahai") && a.tile().equals(tileName(tileId)))
                .findFirst()
                .map(a -> a.consumeSlots(List.of(slot)));
    }

    public List<Action> buttonActions() {
        return actions().stream()
                .filter(a -> !a.type().equals("dahai"))
                .sorted(
                        Comparator.comparing(
                                a -> a.type().equals("pass") || a.type().equals("none")))
                .toList();
    }

    public static String string(JsonObject obj, String key, String missing) {
        JsonElement value = obj.get(key);
        return value == null || value.isJsonNull() ? missing : value.getAsString();
    }

    public static String tileName(int id) {
        if (id >= 0 && id < 27) return (id % 9 + 1) + new String[] {"m", "p", "s"}[id / 9];
        return switch (id) {
            case 27 -> "E";
            case 28 -> "S";
            case 29 -> "W";
            case 30 -> "N";
            case 31 -> "P";
            case 32 -> "F";
            case 33 -> "C";
            case 34 -> "5mr";
            case 35 -> "5pr";
            case 36 -> "5sr";
            default -> "?";
        };
    }
}
