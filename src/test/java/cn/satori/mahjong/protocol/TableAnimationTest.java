package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class TableAnimationTest {
    @Test
    void settledSamplesReuseImmutableGeometryAndUpdatesStillAdvance() {
        var scene = new TableScene();
        scene.update(view(1), new JsonArray(), 0);
        var settled = scene.sample(0);
        assertSame(settled, scene.sample(1));
        assertThrows(UnsupportedOperationException.class, settled::clear);
        scene.append(view(2), events("[{\"type\":\"dahai\",\"actor\":0}]"), 10);
        assertTrue(scene.animating(10));
        var after = scene.sample(2_000_000_000L);
        assertEquals(TableScene.layout(view(2)), after);
        assertSame(after, scene.sample(3_000_000_000L));
    }

    private static CloudView view(long version) {
        JsonObject raw = ProtocolTest.projection(0);
        raw.addProperty("view_version", version);
        return new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
    }

    private static JsonArray events(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    void liftOpensBeforeWallsRiseAndClosesBeforeActionsUnlock() {
        TableScene scene = new TableScene();
        CloudView view = view(1);
        long now = 10_000_000_000L;
        scene.update(view, events("[{\"type\":\"start_kyoku\"}]"), now);
        assertEquals(0, scene.shutter(now));
        assertTrue(scene.shutter(now + 600_000_000L) > .98);
        var hidden =
                scene.sample(now).stream()
                        .filter(t -> t.key().equals("wall-0"))
                        .findFirst()
                        .orElseThrow();
        var lifted =
                scene.sample(now + 1_400_000_000L).stream()
                        .filter(t -> t.key().equals("wall-0"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(lifted.y() - hidden.y() > .36);
        assertTrue(scene.animating(now + 2_000_000_000L));
        assertFalse(scene.animating(now + 4_300_000_000L));
        assertEquals(0, scene.shutter(now + 4_300_000_000L));
        assertEquals(TableScene.layout(view), scene.sample(now + 4_300_000_000L));
    }

    @Test
    void consecutiveUpdatesAndBatchedCuesRetainEventOrder() {
        TableScene scene = new TableScene();
        long now = 10_000_000_000L;
        scene.update(view(1), new JsonArray(), now);
        scene.update(
                view(2),
                events(
                        "[{\"type\":\"dahai\",\"actor\":0},{\"type\":\"chi\",\"actor\":1,\"target\":0}]"),
                now);
        scene.update(view(3), events("[{\"type\":\"kakan\",\"actor\":2}]"), now + 20_000_000);
        assertEquals("dahai", scene.cue(now + 100_000_000L));
        assertEquals("chi", scene.cue(now + 700_000_000L));
        assertEquals("kakan", scene.cue(now + 1_300_000_000L));
        assertEquals(TableScene.layout(view(3)), scene.sample(now + 2_100_000_000L));
    }

    @Test
    void duplicateVersionsDoNotRestartAndReconnectDoesNotReplay() {
        TableScene scene = new TableScene();
        long now = 10_000_000_000L;
        scene.update(view(1), events("[{\"type\":\"start_kyoku\"}]"), now);
        scene.update(view(1), events("[{\"type\":\"start_kyoku\"}]"), now + 3_000_000_000L);
        assertFalse(scene.animating(now + 4_300_000_000L));
        scene.update(
                view(2),
                events("[{\"type\":\"tsumo\",\"actor\":1,\"pai\":\"?\"}]"),
                now + 4_000_000_000L);
        scene.update(view(3), new JsonArray(), now + 4_100_000_000L);
        assertFalse(scene.animating(now + 4_100_000_000L));
        assertEquals(TableScene.layout(view(3)), scene.sample(now + 4_100_000_000L));
    }

    @Test
    void noAnimationFrameContainsAnotherPlayersConcealedFace() {
        TableScene scene = new TableScene();
        long now = 10_000_000_000L;
        scene.update(view(1), events("[{\"type\":\"start_kyoku\"}]"), now);
        for (int i = 0; i <= 40; i++)
            for (var tile : scene.sample(now + i * 100_000_000L))
                if (tile.key().startsWith("hand") && tile.seat() != 0)
                    assertTrue(tile.face() == 37 || tile.face() == -1);
    }

    @Test
    void everyRequiredActionHasAnOrderedPresentationCue() {
        for (String type :
                List.of(
                        "tsumo",
                        "dahai",
                        "chi",
                        "pon",
                        "daiminkan",
                        "ankan",
                        "kakan",
                        "reach",
                        "reach_accepted",
                        "hora",
                        "ryukyoku",
                        "dora")) {
            TableScene scene = new TableScene();
            scene.update(view(1), new JsonArray(), 0);
            scene.update(
                    view(2),
                    events("[{\"type\":\"" + type + "\",\"actor\":0,\"target\":1}]"),
                    1_000_000_000L);
            assertEquals(type, scene.cue(1_100_000_000L));
            assertEquals(TableScene.layout(view(2)), scene.sample(2_000_000_000L));
        }
    }

    private static TableScene.Tile tile(
            String key, int seat, int face, float x, float z, boolean sideways) {
        return new TableScene.Tile(
                key, seat, face, x, 1.08f, z, -seat * 90, key.startsWith("hand"), sideways);
    }

    @Test
    void drawAndDiscardTravelFromWallThroughHandIntoRiver() {
        var wall = tile("wall-54", 1, -1, -1.1f, 0, false);
        var hand = tile("hand-0-5-1", 0, 5, .5f, 1.3f, false);
        var draw =
                new TableTransition(
                        List.of(wall),
                        List.of(hand),
                        events("[{\"type\":\"tsumo\",\"actor\":0}]"),
                        0,
                        ProtocolTest.wall());
        assertEquals(wall.x(), draw.sample(0).getFirst().x());
        assertEquals(-1, draw.sample(0).getFirst().face());
        assertEquals(hand.y(), draw.sample(72_000_000).getFirst().y(), .00001f);
        assertEquals(List.of(hand), draw.sample(500_000_000));
        var river = tile("river-0-0", 0, 5, -.35f, .36f, false);
        var discard =
                new TableTransition(
                        List.of(hand),
                        List.of(river),
                        events("[{\"type\":\"dahai\",\"actor\":0}]"),
                        0,
                        ProtocolTest.wall());
        assertEquals(hand.z(), discard.sample(0).getFirst().z());
        assertEquals(river.y(), discard.sample(72_000_000).getFirst().y(), .00001f);
        assertEquals(List.of(river), discard.sample(240_000_000));
        assertEquals(150_000_000L, discard.endsAt(), 20);
    }

    @Test
    void calledTileTravelsFromDiscardWhileConsumedTilesTravelFromHand() {
        var first = tile("hand-0-5-1", 0, 5, -.3f, 1.3f, false);
        var second = tile("hand-0-5-2", 0, 5, -.1f, 1.3f, false);
        var called = tile("river-1-6", 1, 5, -.6f, .3f, false);
        var meld =
                List.of(
                        tile("meld-0-0-0", 0, 5, 1.1f, 1.3f, false),
                        tile("meld-0-0-1", 0, 5, 1, 1.3f, false),
                        tile("meld-0-0-2", 0, 5, .8f, 1.3f, true));
        var transition =
                new TableTransition(
                        List.of(first, second, called),
                        meld,
                        events("[{\"type\":\"pon\",\"actor\":0,\"target\":1}]"),
                        0,
                        ProtocolTest.wall());
        var start = transition.sample(0);
        assertEquals(first.x(), start.get(0).x());
        assertEquals(second.x(), start.get(1).x());
        assertEquals(called.x(), start.get(2).x());
        assertEquals(called.z(), start.get(2).z());
        assertEquals(meld, transition.sample(800_000_000));
    }

    @Test
    void meldTilesLiftAboveTheHandBeforeMovingWhileRetainedHandSlidesFlat() {
        var consumed = tile("hand-0-5-1", 0, 5, -.7f, 1.25f, false);
        var retained = tile("hand-0-7-1", 0, 7, .2f, 1.25f, false);
        var compacted =
                retained.pose(
                        -.2f,
                        retained.y(),
                        retained.z(),
                        retained.yaw(),
                        retained.pitch(),
                        retained.face());
        var meld = tile("meld-0-0-0", 0, 5, 1.1f, 1.25f, false);
        var transition =
                new TableTransition(
                        List.of(consumed, retained),
                        List.of(compacted, meld),
                        events("[{\"type\":\"ankan\",\"actor\":0}]"),
                        0,
                        ProtocolTest.wall());
        assertEquals(consumed.x(), transition.sample(100_000_000L).get(1).x(), .00001);
        for (long time = 160_000_000L; time < 570_000_000L; time += 20_000_000L) {
            var frame = transition.sample(time);
            assertEquals(retained.y(), frame.getFirst().y(), .00001);
            assertTrue(
                    frame.get(1).y() - TileGeometry.HEIGHT / 2 > 1.081f + TileGeometry.HEIGHT / 2,
                    "Meld passed through the top of a standing hand");
        }
        assertEquals(List.of(compacted, meld), transition.sample(800_000_000L));
    }

    @Test
    void openKanFourthTileUsesTheRemovedDiscardWithoutAGhost() {
        var before = new ArrayList<TableScene.Tile>();
        var after = new ArrayList<TableScene.Tile>();
        for (int i = 0; i < 3; i++) {
            before.add(tile("hand-0-5-" + i, 0, 5, -.4f + i * .12f, 1.3f, false));
            after.add(tile("meld-0-0-" + i, 0, 5, 1.1f - i * .12f, 1.3f, false));
        }
        var called = tile("river-1-6", 1, 5, -.6f, .3f, false);
        // An older same-face discard remains public and must never become the source.
        var retained = tile("river-1-0", 1, 5, -.6f, -.3f, false);
        before.add(called);
        before.add(retained);
        after.add(tile("meld-0-0-3", 0, 5, .6f, 1.3f, true));
        after.add(retained);
        var transition =
                new TableTransition(
                        before,
                        after,
                        events("[{\"type\":\"daiminkan\",\"actor\":0,\"target\":1}]"),
                        0,
                        ProtocolTest.wall());
        var frame = transition.sample(0);
        assertEquals(after.size(), frame.size());
        assertEquals(called.x(), frame.get(3).x());
        assertEquals(called.z(), frame.get(3).z());
        assertEquals(retained, frame.get(4));
        assertEquals(after, transition.sample(800_000_000));
    }

    @Test
    void riverCompactionWaitsForCallAndSlidesFlatAcrossTheRowBoundary() {
        var original = tile("river-1-6", 1, 8, -.8f, -.3f, false);
        var packed = tile("river-1-6", 1, 8, -.6f, .3f, false);
        var transition =
                new TableTransition(
                        List.of(original),
                        List.of(packed),
                        events(
                                "[{\"type\":\"dahai\",\"actor\":1},{\"type\":\"pon\",\"actor\":0,\"target\":1}]"),
                        0,
                        ProtocolTest.wall());
        assertEquals(original, transition.sample(140_000_000).getFirst());
        var sliding = transition.sample(264_000_000).getFirst();
        assertEquals((original.x() + packed.x()) / 2, sliding.x(), .000001f);
        assertEquals((original.z() + packed.z()) / 2, sliding.z(), .000001f);
        assertEquals(original.y(), sliding.y(), .000001f);
        assertEquals(packed, transition.sample(510_000_000).getFirst());
    }

    @Test
    void addedKanFourthTileStillComesFromHand() {
        var hand = tile("hand-0-5-1", 0, 5, -.4f, 1.3f, false);
        var fourth = tile("meld-0-0-3", 0, 5, 1.1f, 1.3f, true);
        var transition =
                new TableTransition(
                        List.of(hand),
                        List.of(fourth),
                        events("[{\"type\":\"kakan\",\"actor\":0}]"),
                        0,
                        ProtocolTest.wall());
        assertEquals(hand.x(), transition.sample(0).getFirst().x());
        assertEquals(List.of(fourth), transition.sample(800_000_000));
    }

    @Test
    void concealedKanOuterTilesStillTravelFromConsumedHand() {
        var before = new ArrayList<TableScene.Tile>();
        var after = new ArrayList<TableScene.Tile>();
        for (int i = 0; i < 4; i++) {
            before.add(tile("hand-0-5-" + (i + 1), 0, 5, -.4f + i * .12f, 1.3f, false));
            after.add(
                    tile(
                            "meld-0-0-" + i,
                            0,
                            i == 0 || i == 3 ? 37 : 5,
                            1.1f - i * .12f,
                            1.3f,
                            false));
        }
        var transition =
                new TableTransition(
                        before,
                        after,
                        events("[{\"type\":\"ankan\",\"actor\":0}]"),
                        0,
                        ProtocolTest.wall());
        assertEquals(
                new HashSet<>(before.stream().map(TableScene.Tile::x).toList()),
                new HashSet<>(transition.sample(0).stream().map(TableScene.Tile::x).toList()));
        assertEquals(after, transition.sample(800_000_000));
    }

    @Test
    void closedKanReservesRedOriginAndNeverMovesUnconsumedHandTiles() {
        var unrelated = tile("hand-0-0-1", 0, 0, -.9f, 1.3f, false);
        var red = tile("hand-0-34-1", 0, 34, -.7f, 1.3f, false);
        var before = new ArrayList<>(List.of(unrelated, red));
        var after = new ArrayList<TableScene.Tile>();
        for (int i = 0; i < 3; i++)
            before.add(tile("hand-0-4-" + i, 0, 4, -.5f + i * .12f, 1.3f, false));
        for (int i = 0; i < 4; i++) {
            int face = i == 0 || i == 3 ? 37 : i == 1 ? 34 : 4;
            var target = tile("meld-0-0-" + i, 0, face, .6f + i * .12f, 1.3f, false);
            after.add(
                    target.pose(
                            target.x(),
                            target.y(),
                            target.z(),
                            target.yaw(),
                            face == 37 ? 90 : -90,
                            face));
        }
        after.add(unrelated);
        var transition =
                new TableTransition(
                        before,
                        after,
                        events("[{\"type\":\"ankan\",\"actor\":0}]"),
                        0,
                        ProtocolTest.wall());
        var start = transition.sample(0);
        assertEquals(red.x(), start.get(1).x());
        assertEquals(34, start.get(1).face());
        assertEquals(unrelated.x(), start.get(4).x());
        assertEquals(5, new HashSet<>(start.stream().map(TableScene.Tile::x).toList()).size());
        assertEquals(after, transition.sample(800_000_000));
    }

    @Test
    void sameVersionSnapshotCompletesInterruptedAnimationWithoutReplay() {
        var scene = new TableScene();
        scene.update(view(1), events("[{\"type\":\"start_kyoku\"}]"), 0);
        scene.update(view(1), new JsonArray(), 100_000_000L);
        assertFalse(scene.animating(100_000_000L));
        assertEquals(TableScene.layout(view(1)), scene.sample(100_000_000L));
    }
}
