package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class ExchangeAnimationTest {
    private static CloudView view(JsonObject raw) {
        return new CloudView(
                raw,
                UUID.fromString(raw.get("game_id").getAsString()),
                raw.get("viewer_seat").getAsInt());
    }

    @Test
    void allDirectionsAndRolesConserveTilesAndFinishAtTheAuthoritativeSnapshot() throws Exception {
        for (int direction = 1; direction <= 3; direction++) {
            for (String role : List.of("player", "observer", "public")) {
                var data =
                        ExchangeSelectionTest.fixture(
                                "sichuan-exchange-motion-" + direction + "-" + role);
                var before = view(data.getAsJsonObject("before"));
                var after = view(data.getAsJsonObject("after"));
                var scene = new TableScene();
                long start = 10_000_000_000L;
                scene.update(before, false, start);
                scene.append(after, data.getAsJsonArray("events"), start);
                assertEquals("exchange_three_completed", scene.cue(start + 500_000_000L));
                int size = TableLayout.layout(before).size();
                for (int tick = 0; tick < 212; tick++) {
                    long now = start + tick * 10_000_000L;
                    var tiles = scene.sample(now);
                    assertEquals(size, tiles.size(), role);
                    assertEquals(size, tiles.stream().map(TableScene.Tile::key).distinct().count());
                    if (tick >= 44 && tick <= 104) {
                        var moving =
                                tiles.stream()
                                        .filter(t -> t.key().startsWith("exchange-"))
                                        .toList();
                        assertEquals(12, moving.size());
                        assertTrue(
                                moving.stream().allMatch(t -> t.face() == 37 && t.pitch() == 90));
                    }
                    if (role.equals("public"))
                        assertTrue(
                                tiles.stream()
                                        .filter(t -> t.standing())
                                        .allMatch(t -> t.face() == 37));
                }
                assertTrue(scene.animating(start + 2_000_000_000L));
                assertFalse(scene.animating(start + 2_200_000_000L));
                assertEquals(TableLayout.layout(after), scene.sample(start + 2_200_000_000L));
                scene.update(after, false, start + 3_000_000_000L);
                assertFalse(scene.animating(start + 3_000_000_000L));
                assertEquals(TableLayout.layout(after), scene.sample(start + 3_000_000_000L));
            }
        }
    }

    @Test
    void evenIdenticalBatchesLeaveEachHandAndArriveBesideItBeforeInsertion() {
        var hand = new ArrayList<TableScene.Tile>();
        for (int seat = 0; seat < 4; seat++)
            for (int i = 0; i < 13; i++)
                hand.add(
                        TableLayout.tile(
                                "hand-" + seat + "-" + i + "-1",
                                seat,
                                i,
                                -.5f + i * TileGeometry.PITCH,
                                TableLayout.HAND_AWAY,
                                true,
                                false));
        var events =
                JsonParser.parseString(
                                "[{\"type\":\"exchange_three_completed\",\"direction\":2,\"source_indices\":[[0,1,2],[0,1,2],[0,1,2],[0,1,2]],\"exchanges\":[[\"1m\",\"2m\",\"3m\"],[\"1m\",\"2m\",\"3m\"],[\"1m\",\"2m\",\"3m\"],[\"1m\",\"2m\",\"3m\"]]}]")
                        .getAsJsonArray();
        var motion = new TableTransition(hand, hand, events, 0, null);
        var sent = motion.sample(440_000_000L);
        assertEquals(
                12,
                sent.stream()
                        .filter(
                                t ->
                                        t.key().startsWith("exchange-")
                                                && Math.hypot(t.x(), t.z()) < .7)
                        .count());
        var received = motion.sample(1_500_000_000L);
        assertEquals(
                12,
                received.stream()
                        .filter(
                                t ->
                                        t.key().startsWith("exchange-")
                                                && Math.hypot(t.x(), t.z()) > 1.45)
                        .count());
        for (var tile : motion.sample(1_800_000_000L))
            if (tile.key().startsWith("exchange-")) assertTrue(tile.y() >= 1.35f);
        assertEquals(hand, motion.sample(2_200_000_000L));
    }

    @Test
    void queuedDingqueDoesNotSkipExchangeAndAReconnectDoesNotReplayIt() throws Exception {
        var data = ExchangeSelectionTest.fixture("sichuan-exchange-motion-1-player");
        var before = view(data.getAsJsonObject("before"));
        var after = view(data.getAsJsonObject("after"));
        var scene = new TableScene();
        scene.update(before, false, 0);
        scene.append(after, data.getAsJsonArray("events"), 0);
        var next = data.getAsJsonObject("after").deepCopy();
        next.addProperty("view_version", after.version + 1);
        scene.append(view(next), new JsonArray(), 10_000_000L);
        assertEquals("exchange_three_completed", scene.cue(1_100_000_000L));
        assertEquals(TableLayout.layout(after), scene.sample(2_500_000_000L));
        scene.append(view(next), data.getAsJsonArray("events"), 3_000_000_000L);
        assertFalse(scene.animating(3_000_000_000L));
        scene.update(view(next), false, 4_000_000_000L);
        assertFalse(scene.animating(4_000_000_000L));
    }
}
