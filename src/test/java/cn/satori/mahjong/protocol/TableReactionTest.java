package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class TableReactionTest {
    private static CloudView reaction(long version, boolean pending) {
        var raw = ProtocolTest.projection(0);
        raw.addProperty("view_version", version);
        raw.getAsJsonObject("public")
                .getAsJsonArray("discards")
                .set(
                        2,
                        JsonParser.parseString(
                                "[{\"tile\":4,\"was_called\":true},{\"tile\":4,\"was_called\":false}]"));
        raw.getAsJsonObject("display")
                .add(
                        "reaction_discard",
                        pending
                                ? JsonParser.parseString("{\"seat\":2,\"index\":1}")
                                : JsonNull.INSTANCE);
        return new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
    }

    @Test
    void reactionTargetsExactRiverIndexAndStopsAfterWindow() {
        var scene = new TableScene();
        scene.update(reaction(1, true), false, 0);
        assertEquals("river-2-1", scene.reactionDiscard(0));
        assertTrue(scene.reactionLit(0));
        assertFalse(scene.reactionLit(400_000_000));
        assertTrue(scene.reactionLit(600_000_000));
        scene.update(reaction(2, false), false, 700_000_000);
        assertNull(scene.reactionDiscard(700_000_000));
        assertFalse(scene.reactionLit(700_000_000));
    }

    @Test
    void animationAndReactionUseSameQueuedProjection() {
        var scene = new TableScene();
        scene.update(reaction(1, false), false, 0);
        scene.update(
                reaction(2, true),
                JsonParser.parseString("[{\"type\":\"dahai\",\"actor\":2}]").getAsJsonArray(),
                1_000_000_000);
        assertNull(scene.reactionDiscard(1_050_000_000));
        assertEquals("river-2-1", scene.reactionDiscard(1_500_000_000));
        scene.update(
                reaction(3, false),
                JsonParser.parseString("[{\"type\":\"pon\",\"actor\":1,\"target\":2}]")
                        .getAsJsonArray(),
                1_600_000_000);
        assertNull(scene.reactionDiscard(1_600_000_000));
    }

    @Test
    void actorAnchorStaysAtPhysicalSeatForEveryViewerAndChair() {
        for (int viewer = 0; viewer < 4; viewer++)
            for (int chair = 0; chair < 4; chair++) {
                float rotation = TableOrientation.sceneDegrees(viewer, chair);
                var own = TableOrientation.callAnchor(viewer, rotation).sub(.5f, 0, .5f);
                var expected =
                        new org.joml.Vector3f(0, 1.34f, .93f)
                                .rotateY((float) Math.toRadians(-chair * 90));
                assertEquals(0, own.distance(expected), .00001);
                var anchors = new HashSet<String>();
                for (int actor = 0; actor < 4; actor++) {
                    var anchor = TableOrientation.callAnchor(actor, rotation);
                    assertEquals(.93, Math.hypot(anchor.x - .5, anchor.z - .5), .00001);
                    anchors.add(Math.round(anchor.x * 100) + ":" + Math.round(anchor.z * 100));
                }
                assertEquals(4, anchors.size());
            }
        assertEquals(new org.joml.Vector3f(.5f, 1.34f, .5f), TableOrientation.callAnchor(-1, 90));
    }
}
