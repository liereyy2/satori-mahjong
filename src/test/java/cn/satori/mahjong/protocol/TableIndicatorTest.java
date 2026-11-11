package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class TableIndicatorTest {
    private CloudView view(int version, int active) {
        var raw = ProtocolTest.projection(0);
        raw.addProperty("view_version", version);
        raw.getAsJsonObject("display")
                .add("decision_seat", active < 0 ? JsonNull.INSTANCE : new JsonPrimitive(active));
        return new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
    }

    private JsonArray events(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    void queuedOpponentsKeepTheirLightsBeforeTheLatestOwnTurn() {
        var scene = new TableScene();
        scene.update(view(1, 0), false, 0);
        for (int seat = 1; seat <= 4; seat++) {
            int actor = seat % 4;
            scene.update(
                    view(seat + 1, actor),
                    events("[{\"type\":\"tsumo\",\"actor\":" + actor + "}]"),
                    1_000_000_000L);
        }
        for (int step = 0; step < 4; step++) {
            long now = 1_050_000_000L + step * 150_000_000L;
            var center = scene.center(now);
            assertEquals((step + 1) % 4, center.activeSeat());
            assertEquals(1, center.seats().stream().filter(TableCenter.Seat::active).count());
            assertTrue(scene.indicatorLit(now), "Every fast turn starts visibly lit");
        }
        assertFalse(scene.indicatorLit(1_900_000_000L));
        assertTrue(scene.indicatorLit(2_160_000_000L));
        assertEquals(0, scene.center(3_000_000_000L).activeSeat());
    }

    @Test
    void batchedDiscardCallDrawAndSettlementFollowTheirConfirmedCue() {
        var scene = new TableScene();
        scene.update(view(1, 2), false, 0);
        scene.update(
                view(2, 3),
                events(
                        "[{\"type\":\"dahai\",\"actor\":2},{\"type\":\"pon\",\"actor\":1},{\"type\":\"dahai\",\"actor\":1},{\"type\":\"tsumo\",\"actor\":3}]"),
                1_000_000_000L);
        assertEquals(2, scene.center(1_050_000_000L).activeSeat());
        assertEquals(1, scene.center(1_200_000_000L).activeSeat());
        assertEquals(1, scene.center(1_950_000_000L).activeSeat());
        assertEquals(3, scene.center(2_100_000_000L).activeSeat());
        scene.update(
                view(3, -1),
                events("[{\"type\":\"hora\",\"actor\":3,\"target\":3}]"),
                3_000_000_000L);
        assertEquals(-1, scene.center(3_050_000_000L).activeSeat());
        assertFalse(scene.indicatorLit(3_050_000_000L));
    }

    @Test
    void reconnectClearsObsoleteFramesAndUsesPublicDecisionSource() {
        var scene = new TableScene();
        scene.update(view(1, 0), false, 0);
        scene.update(view(2, 1), events("[{\"type\":\"pon\",\"actor\":1}]"), 1_000_000_000L);
        scene.update(view(3, 2), false, 1_050_000_000L);
        assertFalse(scene.animating(1_100_000_000L));
        assertEquals(2, scene.center(1_100_000_000L).activeSeat());
        assertTrue(scene.indicatorLit(1_100_000_000L));
    }
}
