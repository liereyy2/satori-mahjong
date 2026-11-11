package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class MatchResultTest {
    private final UUID id = new UUID(0, 99);

    private JsonObject result() {
        var json =
                JsonParser.parseString(
                                """
{"scores":[24000,41000,28000,7000],"ranks":[3,1,2,4],"pt_deltas":[0,90,45,-145],
 "seats":[{"seat":0,"kind":"human","user_id":"00000000-0000-0000-0000-000000000001"},
 {"seat":1,"kind":"ai","user_id":null},{"seat":2,"kind":"ai","user_id":null},
 {"seat":3,"kind":"ai","user_id":null}]}
""")
                        .getAsJsonObject();
        json.addProperty("game_id", id.toString());
        return json;
    }

    @Test
    void sortedRowsPreserveSeatScoreAndServerPt() {
        var parsed = MatchResult.parse(result(), id);
        assertEquals(
                List.of(1, 2, 0, 3),
                parsed.players().stream().map(MatchResult.Player::seat).toList());
        assertEquals(
                List.of(90, 45, 0, -145),
                parsed.players().stream().map(MatchResult.Player::pt).toList());
        assertEquals(24000, parsed.players().get(2).score());
        assertEquals(new UUID(0, 1).toString(), parsed.players().get(2).userId());
        assertFalse(parsed.players().get(2).ai());
    }

    @Test
    void vacantHumanSeatRetainsResultWithoutInventingAPlayer() {
        var raw = result();
        raw.getAsJsonArray("seats").get(0).getAsJsonObject().add("user_id", JsonNull.INSTANCE);
        var player = MatchResult.parse(raw, id).players().get(2);
        assertEquals("", player.userId());
        assertFalse(player.ai());
        assertEquals(24000, player.score());
    }

    @Test
    void rejectsForeignResultAndIncompleteOrDuplicatedRanks() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MatchResult.parse(result(), new UUID(0, 100)));
        var duplicate = result();
        duplicate.getAsJsonArray("ranks").set(0, new JsonPrimitive(1));
        assertThrows(IllegalArgumentException.class, () -> MatchResult.parse(duplicate, id));
        var incomplete = result();
        incomplete.getAsJsonArray("pt_deltas").remove(3);
        assertThrows(IllegalArgumentException.class, () -> MatchResult.parse(incomplete, id));
    }
}
