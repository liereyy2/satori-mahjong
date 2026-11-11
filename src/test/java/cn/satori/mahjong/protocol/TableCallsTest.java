package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class TableCallsTest {
    @Test
    void confirmedCallsFollowAnimationAndSnapshotsStaySilent() {
        var raw = ProtocolTest.projection(0);
        var game = UUID.fromString(raw.get("game_id").getAsString());
        var scene = new TableScene();
        scene.update(new CloudView(raw, game, 0), false, 1_000_000_000L);
        assertNull(scene.call(1_000_000_000L));
        raw.addProperty("view_version", 2);
        var events =
                JsonParser.parseString(
                                "[{\"type\":\"pon\",\"actor\":1,\"target\":0},{\"type\":\"pon\",\"actor\":2,\"target\":1},{\"type\":\"reach_accepted\",\"actor\":2}]")
                        .getAsJsonArray();
        scene.update(new CloudView(raw, game, 0), events, 2_000_000_000L);
        var first = scene.call(2_050_000_000L);
        var second = scene.call(2_800_000_000L);
        assertEquals("pon", first.voice());
        assertEquals(1, first.actor());
        assertEquals("pon", second.voice());
        assertEquals(2, second.actor());
        assertNotEquals(first.id(), second.id());
        assertEquals(first, scene.call(2_060_000_000L));
        assertNull(scene.call(3_500_000_000L));
    }

    @Test
    void ronAndSelfDrawUseDifferentVoicesAndDiscardIsSilent() {
        for (String type : new String[] {"hora", "ankan", "kakan", "daiminkan", "dahai", "tsumo"}) {
            for (int target : new int[] {0, 1}) {
                var events = new JsonArray();
                var event = new JsonObject();
                event.addProperty("type", type);
                event.addProperty("actor", 0);
                event.addProperty("target", target);
                events.add(event);
                var transition =
                        new TableTransition(
                                java.util.List.of(),
                                java.util.List.of(),
                                events,
                                1_000_000_000L,
                                ProtocolTest.wall());
                var call = transition.call(1_050_000_000L);
                if (type.equals("hora")) assertEquals(target == 0 ? "tsumo" : "ron", call.voice());
                else if (type.endsWith("kan")) assertEquals("kan", call.voice());
                else assertNull(call);
            }
        }
    }

    @Test
    void decisionSourceAndAiSeatsComeOnlyFromTheCloudProjection() {
        var raw = ProtocolTest.projection(0);
        raw.getAsJsonObject("display").addProperty("decision_seat", 3);
        raw.getAsJsonObject("public").addProperty("tsumo_actor", 0);
        raw.getAsJsonObject("public")
                .add(
                        "seats",
                        JsonParser.parseString(
                                "[{\"seat\":0,\"kind\":\"human\"},{\"seat\":1,\"kind\":\"ai\"},{\"seat\":2,\"kind\":\"human\"},{\"seat\":3,\"kind\":\"ai\"}]"));
        var view = new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
        assertEquals(3, view.decisionSeat());
        assertEquals(java.util.List.of(1, 3), view.aiSeats());
        raw.getAsJsonObject("display").add("decision_seat", JsonNull.INSTANCE);
        assertEquals(-1, new CloudView(raw, view.gameId, 0).decisionSeat());
    }
}
