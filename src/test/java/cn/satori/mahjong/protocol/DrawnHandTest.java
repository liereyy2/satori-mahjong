package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class DrawnHandTest {
    private static JsonObject own(int... faces) {
        JsonObject raw = ProtocolTest.projection(0);
        JsonArray hand = new JsonArray();
        for (int face : faces) {
            JsonObject tile = new JsonObject();
            tile.addProperty("tile_id", face);
            hand.add(tile);
        }
        raw.getAsJsonObject("private").add("hand", hand);
        raw.getAsJsonObject("public")
                .getAsJsonArray("hand_counts")
                .set(0, new JsonPrimitive(faces.length));
        return raw;
    }

    private static void drawn(JsonObject raw, int seat, int face) {
        raw.getAsJsonObject("display")
                .getAsJsonArray("drawn_seats")
                .set(seat, new JsonPrimitive(true));
        if (seat == 0) raw.getAsJsonObject("private").addProperty("last_tsumo_tile", face);
    }

    private static List<TableScene.Tile> tiles(JsonObject raw) {
        return TableScene.layout(
                new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0));
    }

    private static List<TableScene.Tile> hand(List<TableScene.Tile> all, int seat) {
        return all.stream().filter(t -> t.key().startsWith("hand-") && t.seat() == seat).toList();
    }

    private static void discard(JsonObject raw, int seat, int face, int index) {
        JsonArray river =
                raw.getAsJsonObject("public").getAsJsonArray("discards").get(seat).getAsJsonArray();
        for (int i = 0; i <= index; i++) {
            JsonObject tile = new JsonObject();
            tile.addProperty("tile", face);
            river.add(tile);
        }
    }

    private static JsonArray event(int seat, boolean tsumogiri) {
        return JsonParser.parseString(
                        "[{\"type\":\"dahai\",\"actor\":"
                                + seat
                                + ",\"tsumogiri\":"
                                + tsumogiri
                                + "}]")
                .getAsJsonArray();
    }

    @Test
    void drawIsSeparateAtRightWhileNormalAndRedFivesRemainSorted() {
        JsonObject raw = own(34, 4, 5, 4, 0);
        drawn(raw, 0, 4);
        var hand = hand(tiles(raw), 0);
        assertEquals(List.of(0, 4, 34, 5, 4), hand.stream().map(TableScene.Tile::face).toList());
        assertTrue(hand.getLast().key().endsWith("-drawn"));
        assertEquals(1, hand.getFirst().scale());
        assertEquals(0, hand.getFirst().pitch());
        assertTrue(
                hand.getLast().x() - hand.get(3).x()
                        > TileGeometry.HAND_PITCH + TileGeometry.WIDTH * .39f);
        // A fresh snapshot produces precisely the same separation; no event replay is needed.
        assertEquals(tiles(raw), tiles(raw.deepCopy()));
    }

    @Test
    void ownHandDiscardClosesTheGapAndInsertsTheDrawnDuplicateAfterLanding() {
        JsonObject before = own(0, 4, 4, 34, 5);
        drawn(before, 0, 4);
        JsonObject after = own(4, 4, 34, 5);
        discard(after, 0, 0, 0);
        var target = tiles(after);
        var transition =
                new TableTransition(tiles(before), target, event(0, false), 0, ProtocolTest.wall());
        var first = transition.sample(0);
        var river =
                first.stream().filter(t -> t.key().equals("river-0-0")).findFirst().orElseThrow();
        assertEquals(hand(tiles(before), 0).getFirst().x(), river.x());
        assertEquals(hand(tiles(before), 0).getLast().x(), hand(first, 0).get(1).x());
        assertEquals(hand(first, 0), hand(transition.sample(140_000_000), 0));
        var gap = hand(transition.sample(344_000_000), 0);
        assertEquals(hand(first, 0).get(1).x(), gap.get(1).x());
        assertEquals(hand(target, 0).get(0).x(), gap.get(0).x(), .00001);
        assertEquals(hand(target, 0).get(2).x(), gap.get(2).x(), .00001);
        var crossing = hand(transition.sample(500_000_000), 0);
        assertTrue(
                crossing.get(1).y() - TileGeometry.HEIGHT * crossing.get(1).scale() / 2
                        > crossing.get(2).y() + TileGeometry.HEIGHT * crossing.get(2).scale() / 2,
                "Drawn tile must cross above the standing hand, after the gap opens");
        assertEquals(target, transition.sample(640_000_000));
        assertEquals(4, hand(target, 0).size());
        assertTrue(hand(target, 0).stream().noneMatch(t -> t.key().endsWith("-drawn")));
    }

    @Test
    void hiddenTsumogiriUsesDrawnSlotAndHandDiscardUsesStableVariedSlots() {
        Set<Float> origins = new HashSet<>();
        for (int n = 0; n < 12; n++) {
            int index = n;
            JsonObject before = own(0);
            before.getAsJsonObject("public")
                    .getAsJsonArray("hand_counts")
                    .set(1, new JsonPrimitive(14));
            drawn(before, 1, 37);
            // Existing public discards stay fixed, only the newest one gets a flight.
            if (n > 0) discard(before, 1, 5, n - 1);
            JsonObject after = own(0);
            discard(after, 1, 5, n);
            var start = tiles(before);
            var end = tiles(after);
            var tsumogiri = new TableTransition(start, end, event(1, true), 0, ProtocolTest.wall());
            var river =
                    tsumogiri.sample(0).stream()
                            .filter(t -> t.key().equals("river-1-" + index))
                            .findFirst()
                            .orElseThrow();
            assertEquals(hand(start, 1).getLast().z(), river.z());
            var discard = new TableTransition(start, end, event(1, false), 0, ProtocolTest.wall());
            var moving =
                    discard.sample(0).stream()
                            .filter(t -> t.key().equals("river-1-" + index))
                            .findFirst()
                            .orElseThrow();
            origins.add(moving.z());
            assertNotEquals(hand(start, 1).getLast().z(), moving.z());
            assertEquals(
                    discard.sample(0),
                    new TableTransition(start, end, event(1, false), 0, ProtocolTest.wall())
                            .sample(0));
            assertTrue(hand(discard.sample(250_000_000), 1).stream().allMatch(t -> t.face() == 37));
            assertEquals(end, discard.sample(650_000_000));
        }
        assertTrue(origins.size() > 4, "Hidden hand discards must not all leave from one position");
    }

    @Test
    void calledHandWithFourteenEquivalentTilesHasNoDrawnSlot() {
        JsonObject raw = own(0);
        raw.getAsJsonObject("public").getAsJsonArray("hand_counts").set(1, new JsonPrimitive(11));
        assertEquals(11, hand(tiles(raw), 1).size());
        assertTrue(hand(tiles(raw), 1).stream().noneMatch(t -> t.key().endsWith("-drawn")));
    }
}
