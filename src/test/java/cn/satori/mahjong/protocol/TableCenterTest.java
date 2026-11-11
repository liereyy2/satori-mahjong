package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class TableCenterTest {
    @Test
    void nextSeatIsOnTheRightForEveryViewerAndPhysicalChair() {
        for (int viewer = 0; viewer < 4; viewer++)
            for (int chair = 0; chair < 4; chair++) {
                float rotation =
                        (float) Math.toRadians(TableOrientation.sceneDegrees(viewer, chair));
                float cameraTurn = chair * (float) Math.PI / 2;
                for (int relative = 0; relative < 4; relative++) {
                    var t =
                            TableLayout.tile(
                                    "center", (viewer + relative) % 4, 0, 0, 1, false, false);
                    var point = new Vector3f(t.x(), 0, t.z()).rotateY(rotation);
                    float right =
                            point.x * (float) Math.cos(cameraTurn)
                                    + point.z * (float) Math.sin(cameraTurn);
                    float away =
                            -point.x * (float) Math.sin(cameraTurn)
                                    + point.z * (float) Math.cos(cameraTurn);
                    assertEquals(new float[] {0, 1, 0, -1}[relative], right, .000001);
                    assertEquals(new float[] {1, 0, -1, 0}[relative], away, .000001);
                }
            }
    }

    @Test
    void centerUsesAuthoritativeScoresWindsSticksAndMetaWithoutChangingProjection() {
        var raw = ProtocolTest.projection(1);
        var p = raw.getAsJsonObject("public");
        p.addProperty("oya", 2);
        p.addProperty("bakaze", 28);
        p.addProperty("kyoku", 3);
        p.addProperty("tsumo_actor", 3);
        raw.getAsJsonObject("display").addProperty("decision_seat", 1);
        p.addProperty("tiles_left", 33);
        p.addProperty("honba", 2);
        p.addProperty("kyotaku", 4);
        p.add("scores", JsonParser.parseString("[12000,24000,33000,27000]"));
        p.add("riichi_accepted", JsonParser.parseString("[true,false,false,true]"));
        String original = raw.toString();
        var center =
                TableCenter.from(
                        new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 1));
        assertEquals(1, center.viewer());
        assertEquals(1, center.roundWind());
        assertEquals(3, center.round());
        assertEquals(33, center.remaining());
        assertEquals(2, center.honba());
        assertEquals(4, center.kyotaku());
        assertEquals(
                new TableCenter.Seat(12000, 2, false, true, false, -1, false),
                center.seats().get(0));
        assertTrue(center.seats().get(2).dealer());
        assertTrue(center.seats().get(1).active());
        assertEquals(original, raw.toString());
    }

    @Test
    void sidewaysCalledTileFacesItsSourceInEverySeat() {
        for (int seat = 0; seat < 4; seat++)
            for (int direction = 1; direction < 4; direction++) {
                var raw = ProtocolTest.projection(seat);
                var meld = new JsonObject();
                meld.addProperty("type", "pon");
                meld.addProperty("target", (seat + direction) % 4);
                meld.add("tiles", JsonParser.parseString("[5,5,5]"));
                raw.getAsJsonObject("public")
                        .getAsJsonArray("melds")
                        .get(seat)
                        .getAsJsonArray()
                        .add(meld);
                int actor = seat;
                var tiles =
                        TableLayout.layout(
                                        new CloudView(
                                                raw,
                                                UUID.fromString(raw.get("game_id").getAsString()),
                                                seat))
                                .stream()
                                .filter(t -> t.key().startsWith("meld-" + actor + "-"))
                                .toList();
                var called =
                        tiles.stream().filter(TableScene.Tile::sideways).findFirst().orElseThrow();
                int index = tiles.indexOf(called);
                assertEquals(direction == 3 ? 2 : direction == 2 ? 1 : 0, index);
            }
    }
}
