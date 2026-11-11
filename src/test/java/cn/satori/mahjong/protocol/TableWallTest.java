package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class TableWallTest {
    @Test
    void realDrawAndKanFixturesConsumeTheExactRustSource() throws IOException {
        JsonObject examples;
        try (var input = TableWallTest.class.getResourceAsStream("/electric-examples.json")) {
            examples =
                    JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                            .getAsJsonObject();
        }
        for (var entry : examples.entrySet()) {
            var example = entry.getValue().getAsJsonObject();
            if (!example.has("before") || !example.has("update")) continue;
            var before = example.getAsJsonObject("before");
            var update = example.getAsJsonObject("update");
            var after = update.getAsJsonObject("view");
            var id = UUID.fromString(after.get("game_id").getAsString());
            var scene = new TableScene();
            scene.update(new CloudView(before, id, 0), false, 0);
            var target = new CloudView(after, id, 0);
            assertDoesNotThrow(
                    () -> scene.update(target, update.getAsJsonArray("events"), 0), entry.getKey());
            for (long time = 0; time <= 5_000_000_000L; time += 100_000_000L) {
                var tiles = scene.sample(time);
                assertEquals(
                        tiles.size(),
                        tiles.stream().map(TableScene.Tile::key).distinct().count(),
                        entry.getKey());
            }
            assertEquals(TableScene.layout(target), scene.sample(6_000_000_000L), entry.getKey());
        }
    }

    private static JsonObject opening() throws IOException {
        try (var input = TableWallTest.class.getResourceAsStream("/electric-examples.json")) {
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonObject("start_kyoku")
                    .getAsJsonObject("update");
        }
    }

    @Test
    void diceSettleBeforeDealingWithTheAuthoritativeTopFaceInEveryDirection() {
        Vector3f[] normals = {
            new Vector3f(0, 1, 0),
            new Vector3f(0, 0, 1),
            new Vector3f(1, 0, 0),
            new Vector3f(-1, 0, 0),
            new Vector3f(0, 0, -1),
            new Vector3f(0, -1, 0)
        };
        for (int dealer = 0; dealer < 4; dealer++)
            for (int value = 1; value <= 6; value++) {
                var settled = TableDice.sample(dealer, List.of(value, 7 - value), 2.01f);
                assertEquals(settled, TableDice.sample(dealer, List.of(value, 7 - value), 3.8f));
                for (var die : settled) {
                    var rotation =
                            new Matrix3f()
                                    .rotateY((float) Math.toRadians(die.yaw()))
                                    .rotateX((float) Math.toRadians(die.pitch()))
                                    .rotateZ((float) Math.toRadians(die.roll()));
                    var top = rotation.transform(new Vector3f(normals[die.top() - 1]));
                    assertEquals(1, top.y, .00001);
                    assertEquals(1.055f, die.y(), .00001);
                }
                var thrown = TableDice.sample(dealer, List.of(value, 7 - value), 1.45f).getFirst();
                assertTrue(Math.hypot(thrown.x(), thrown.z()) > 1.1);
            }
        assertTrue(TableDice.sample(0, List.of(1, 6), 1.44f).isEmpty());
        assertTrue(TableDice.sample(0, List.of(1, 6), 3.9f).isEmpty());
    }

    @Test
    void openingConsumesOnlyTheRustDealSlotsAndAllTilesConverge() throws IOException {
        var update = opening();
        var raw = update.getAsJsonObject("view");
        var view = new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
        var scene = new TableScene();
        scene.update(view, update.getAsJsonArray("events"), 0);
        assertEquals(136, scene.sample(2_000_000_000L).size());
        assertEquals(view.wall.dice.getFirst(), scene.dice(2_010_000_000L).getFirst().top());
        var first = view.wall.deal.getFirst();
        var firstWall = TableLayout.wall(first.slot(), 0);
        var frame = scene.sample(2_151_000_000L);
        assertEquals(136, frame.size());
        assertFalse(frame.stream().anyMatch(t -> t.key().equals("wall-" + first.slot())));
        var hand =
                frame.stream()
                        .filter(t -> t.key().startsWith("hand-" + first.seat() + "-"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(firstWall.x(), hand.x(), .001);
        assertEquals(firstWall.z(), hand.z(), .001);
        assertEquals(TableScene.layout(view), scene.sample(4_300_000_000L));
        var slots =
                TableScene.layout(view).stream()
                        .filter(t -> t.key().startsWith("wall-"))
                        .map(t -> Integer.parseInt(t.key().substring(5)))
                        .toList();
        assertEquals(new HashSet<>(view.wall.remaining), new HashSet<>(slots));
        for (var indicator : view.wall.indicators) {
            var tile =
                    TableScene.layout(view).stream()
                            .filter(t -> t.key().equals("wall-" + indicator.slot()))
                            .findFirst()
                            .orElseThrow();
            assertEquals(indicator.face(), tile.face());
            assertEquals(-90, tile.pitch());
        }
    }
}
