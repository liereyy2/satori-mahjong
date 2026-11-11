package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;

class PublicTableViewTest {
    @Test
    void publicTableKeepsBoardAndScoresAndHidesAllConcealedHands() throws Exception {
        JsonObject data = ProtocolTest.projection(0);
        data.getAsJsonObject("public").getAsJsonArray("hand_counts").set(0, new JsonPrimitive(1));
        data.getAsJsonObject("public")
                .getAsJsonArray("riichi_accepted")
                .set(1, new JsonPrimitive(true));
        UUID game = UUID.fromString(data.get("game_id").getAsString());
        var personal = new CloudView(data, game, 0);
        data.addProperty("viewer_seat", 4);
        assertThrows(IllegalArgumentException.class, () -> new CloudView(data, game, 4));
        data.add("private", JsonNull.INSTANCE);
        data.add("decision", JsonNull.INSTANCE);
        data.getAsJsonObject("display").remove("standing_hint");
        var observer = new CloudView(data, game, 4);
        assertTrue(observer.actions().isEmpty());
        assertTrue(observer.discardFor("hand-0-0-1").isEmpty());
        for (int seat = 0; seat < 4; seat++)
            assertTrue(observer.hand(seat).stream().allMatch(tile -> tile == 37));
        var scene = new TableScene();
        scene.update(observer, false, 0);
        assertFalse(scene.sample(0).isEmpty());
        assertEquals(TableCenter.from(personal).seats(), scene.center(0).seats());
        assertEquals(
                TableLayout.layout(personal).stream()
                        .filter(t -> !t.key().startsWith("hand-"))
                        .toList(),
                scene.sample(0).stream().filter(t -> !t.key().startsWith("hand-")).toList());
    }
}
