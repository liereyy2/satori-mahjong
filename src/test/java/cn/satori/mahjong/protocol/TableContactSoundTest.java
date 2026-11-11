package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class TableContactSoundTest {
    @Test
    void openingFirstDrawLandsWithItsContactInsteadOfUsingTheDealingArc() {
        var raw = ProtocolTest.projection(0);
        raw.getAsJsonObject("display")
                .getAsJsonArray("drawn_seats")
                .set(0, new JsonPrimitive(true));
        raw.getAsJsonObject("private").addProperty("last_tsumo_tile", 34);
        var view = new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
        var target = TableScene.layout(view);
        var drawn =
                target.stream().filter(t -> t.key().endsWith("-drawn")).findFirst().orElseThrow();
        var events =
                JsonParser.parseString(
                                "[{\"type\":\"start_kyoku\"},{\"type\":\"tsumo\",\"actor\":0}]")
                        .getAsJsonArray();
        var motion = new TableTransition(List.of(), target, events, 1_000_000_000L, view.wall);
        assertTrue(
                motion.sample(4_900_000_000L).stream().noneMatch(t -> t.key().equals(drawn.key())));
        var origin = TableLayout.wall(view.wall.firstDraw, 0);
        var middle =
                motion.sample(4_972_000_000L).stream()
                        .filter(t -> t.key().equals(drawn.key()))
                        .findFirst()
                        .orElseThrow();
        assertEquals((origin.x() + drawn.x()) / 2, middle.x(), .00001f);
        assertEquals((origin.y() + drawn.y()) / 2, middle.y(), .00001f);
        var contact =
                motion.sample(motion.sounds().getFirst().at()).stream()
                        .filter(t -> t.key().equals(drawn.key()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(drawn.x(), contact.x(), .00001f);
        assertEquals(drawn.y(), contact.y(), .00001f);
        assertEquals(drawn.z(), contact.z(), .00001f);
    }

    @Test
    void contactsWaitForLandingAndRemainConsumableAfterFrameCompletion() {
        var raw = ProtocolTest.projection(0);
        var game = UUID.fromString(raw.get("game_id").getAsString());
        var scene = new TableScene();
        scene.update(new CloudView(raw, game, 0), false, 1_000_000_000L);
        assertTrue(scene.sounds(0, 1_000_000_000L).isEmpty());
        raw.addProperty("view_version", 2);
        scene.update(
                new CloudView(raw, game, 0),
                JsonParser.parseString(
                                "[{\"type\":\"tsumo\",\"actor\":1},{\"type\":\"dahai\",\"actor\":1}]")
                        .getAsJsonArray(),
                2_000_000_000L);
        assertTrue(scene.sounds(0, 2_131_000_000L).isEmpty());
        var draw = scene.sounds(0, 2_133_000_000L);
        assertEquals(1, draw.size());
        assertEquals("tile.draw", draw.getFirst().name());
        assertEquals(1, draw.getFirst().actor());
        scene.sample(3_000_000_000L);
        var discard = scene.sounds(draw.getFirst().id(), 3_000_000_000L);
        assertEquals(1, discard.size());
        assertEquals("tile.discard", discard.getFirst().name());
        assertTrue(scene.sounds(discard.getFirst().id(), 3_000_000_000L).isEmpty());
        raw.addProperty("view_version", 3);
        scene.append(new CloudView(raw, game, 0), new JsonArray(), 3_050_000_000L);
        assertEquals(discard, scene.sounds(draw.getFirst().id(), 3_050_000_000L));
        raw.addProperty("view_version", 4);
        scene.update(new CloudView(raw, game, 0), false, 3_100_000_000L);
        assertTrue(scene.sounds(0, 3_100_000_000L).isEmpty());
    }

    @Test
    void drawHasUniformTravelAndStopsAtTheSoundContact() {
        var a = new TableScene.Tile("hand-0-drawn", 0, 0, 0, 1, 0, 0, true, false);
        var b = a.pose(1, 1, 0, 0, 0, 0);
        var events = JsonParser.parseString("[{\"type\":\"tsumo\",\"actor\":0}]").getAsJsonArray();
        var motion =
                new TableTransition(
                        List.of(a), List.of(b), events, 1_000_000_000L, ProtocolTest.wall());
        assertEquals(.25f, motion.sample(1_042_000_000L).getFirst().x(), .0001f);
        assertEquals(.5f, motion.sample(1_072_000_000L).getFirst().x(), .0001f);
        assertEquals(.75f, motion.sample(1_102_000_000L).getFirst().x(), .0001f);
        assertEquals(1, motion.sample(motion.sounds().getFirst().at()).getFirst().x(), .0001f);
    }
}
