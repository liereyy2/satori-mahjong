package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class CloudHandTest {
    private static JsonObject raw(int... faces) {
        JsonObject raw = ProtocolTest.projection(0);
        JsonArray hand = new JsonArray();
        for (int i = 0; i < faces.length; i++) {
            JsonObject tile = new JsonObject();
            tile.addProperty("tile_id", faces[i]);
            tile.addProperty("slot_id", "h-" + i);
            tile.addProperty("tile", CloudView.tileName(faces[i]));
            hand.add(tile);
        }
        raw.getAsJsonObject("private").add("hand", hand);
        raw.add("decision", JsonParser.parseString("{\"decision_id\":\"current\",\"actions\":[]}"));
        return raw;
    }

    private static CloudView view(JsonObject raw) {
        return new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
    }

    private static void action(JsonObject raw, String type, int face) {
        JsonObject action = new JsonObject();
        action.addProperty("action_id", type + "-" + face);
        action.addProperty("type", type);
        if (face >= 0) action.addProperty("tile", CloudView.tileName(face));
        raw.getAsJsonObject("decision").getAsJsonArray("actions").add(action);
    }

    @Test
    void allRedFivesFollowEveryOrdinaryFiveAndPrecedeSix() {
        JsonObject raw = raw(36, 35, 34, 5, 4, 4, 13, 14, 22, 22, 23, 27, 0, 33);
        String unchanged = raw.toString();
        assertEquals(
                List.of(0, 4, 4, 34, 5, 13, 35, 14, 22, 22, 36, 23, 27, 33), view(raw).hand(0));
        assertEquals(unchanged, raw.toString());
    }

    @Test
    void loneRedFivesStillStayBetweenFourAndSixInTheirOwnSuit() {
        JsonObject raw = raw(36, 5, 34, 3, 12, 14, 35, 21, 23);
        assertEquals(List.of(3, 34, 5, 12, 35, 14, 21, 36, 23), view(raw).hand(0));
        raw.getAsJsonObject("public")
                .getAsJsonArray("revealed_hands")
                .set(1, JsonParser.parseString("[36,23,22,34,4,5]"));
        assertEquals(List.of(4, 34, 5, 22, 36, 23), view(raw).hand(1));
        assertEquals(Collections.nCopies(13, 37), view(raw).hand(2));
    }

    @Test
    void clickingRedAndOrdinaryCopiesSelectsTheirExactCloudAction() {
        JsonObject raw = raw(34, 4, 4, 5);
        action(raw, "dahai", 4);
        action(raw, "dahai", 34);
        action(raw, "reach", -1);
        CloudView view = view(raw);
        assertEquals("dahai-4", view.discardFor("hand-0-4-1").orElseThrow().actionId());
        assertEquals("dahai-34", view.discardFor("hand-0-34-1").orElseThrow().actionId());
        assertEquals("current", view.discardFor("hand-0-34-1").orElseThrow().decisionId());
        assertTrue(view.discardFor("hand-0-5-1").isEmpty());
        assertTrue(view.discardFor("hand-0-35-1").isEmpty());
        assertTrue(view.discardFor("hand-0-37-1").isEmpty());
        assertEquals(
                List.of("reach"),
                view.buttonActions().stream().map(CloudView.Action::type).toList());
    }

    @Test
    void identicalHeldCopiesAndTheDrawCarryDifferentPhysicalSlots() {
        JsonObject raw = raw(0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0);
        raw.getAsJsonObject("private").addProperty("last_tsumo_tile", 0);
        raw.getAsJsonObject("display")
                .getAsJsonArray("drawn_seats")
                .set(0, new JsonPrimitive(true));
        action(raw, "dahai", 0);
        var view = view(raw);
        assertEquals(
                "h-0",
                view.discardFor("hand-0-0-1")
                        .orElseThrow()
                        .display()
                        .getAsJsonArray("consume_slot_ids")
                        .get(0)
                        .getAsString());
        assertEquals(
                "h-1",
                view.discardFor("hand-0-0-2")
                        .orElseThrow()
                        .display()
                        .getAsJsonArray("consume_slot_ids")
                        .get(0)
                        .getAsString());
        assertEquals(
                "h-13",
                view.discardFor("hand-0-drawn")
                        .orElseThrow()
                        .display()
                        .getAsJsonArray("consume_slot_ids")
                        .get(0)
                        .getAsString());
    }

    @Test
    void noDecisionCannotDiscardAndAllNonDiscardChoicesRemainButtons() {
        JsonObject raw = raw(4, 34);
        for (String type :
                List.of(
                        "pass",
                        "chi",
                        "pon",
                        "ankan",
                        "daiminkan",
                        "kakan",
                        "reach",
                        "hora",
                        "ryukyoku",
                        "none",
                        "continue")) action(raw, type, -1);
        action(raw, "dahai", 4);
        assertEquals(
                List.of(
                        "chi",
                        "pon",
                        "ankan",
                        "daiminkan",
                        "kakan",
                        "reach",
                        "hora",
                        "ryukyoku",
                        "continue",
                        "pass",
                        "none"),
                view(raw).buttonActions().stream().map(CloudView.Action::type).toList());
        raw.add("decision", JsonNull.INSTANCE);
        assertTrue(view(raw).discardFor("hand-0-4-1").isEmpty());
        assertTrue(view(raw).buttonActions().isEmpty());
    }
}
