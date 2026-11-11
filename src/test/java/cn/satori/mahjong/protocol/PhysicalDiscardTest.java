package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.util.List;

class PhysicalDiscardTest {
    @Test
    void voidPresentationOrderDoesNotChangeCanonicalDiscardIndex() {
        var missing = TableLayout.tile("hand-0-0-1", 0, 0, .6f, TableLayout.HAND_AWAY, true, false);
        var normal = TableLayout.tile("hand-0-9-1", 0, 9, -.4f, TableLayout.HAND_AWAY, true, false);
        var river = TableLayout.tile("river-0-0", 0, 0, 0f, .3f, false, false);
        var events =
                JsonParser.parseString(
                                "[{\"type\":\"dahai\",\"actor\":0,\"tsumogiri\":false,\"hand_index\":0}]")
                        .getAsJsonArray();
        var origins =
                new TileOrigins(List.of(normal, missing), List.of(normal, river), events, null);
        assertEquals(missing, origins.origins.get(river.key()));
    }

    @Test
    void selectedSecondCopyLeavesWhileFirstCopyAndDrawSurvive() {
        var first = TableLayout.tile("hand-0-0-1", 0, 0, -.6f, TableLayout.HAND_AWAY, true, false);
        var second = TableLayout.tile("hand-0-0-2", 0, 0, -.4f, TableLayout.HAND_AWAY, true, false);
        var draw = TableLayout.tile("hand-0-drawn", 0, 0, .7f, TableLayout.HAND_AWAY, true, false);
        var river = TableLayout.tile("river-0-0", 0, 0, 0f, .3f, false, false);
        var after = List.of(first, second, river);
        var events =
                JsonParser.parseString(
                                "[{\"type\":\"dahai\",\"actor\":0,\"pai\":\"1m\",\"tsumogiri\":false,\"hand_index\":1}]")
                        .getAsJsonArray();
        var origins = new TileOrigins(List.of(first, second, draw), after, events, null);
        assertEquals(second, origins.origins.get(river.key()));
        assertEquals(first, origins.origins.get(first.key()));
        assertEquals(draw, origins.origins.get(second.key()));
    }
}
