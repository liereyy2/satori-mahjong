package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class CloudDisplayContractTest {
    private static CloudView view(JsonObject raw) {
        return new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
    }

    @Test
    void standingHintIsRequiredForSeatsAndForbiddenToObservers() {
        var raw = ProtocolTest.projection(0);
        assertNull(view(raw).standingHint());
        raw.getAsJsonObject("display").remove("standing_hint");
        assertThrows(CloudView.ServerUpdateRequired.class, () -> view(raw));
        raw.getAsJsonObject("display").add("standing_hint", JsonNull.INSTANCE);
        raw.addProperty("viewer_seat", 4);
        raw.add("private", JsonNull.INSTANCE);
        var id = UUID.fromString(raw.get("game_id").getAsString());
        assertThrows(IllegalArgumentException.class, () -> new CloudView(raw, id, 4));
        raw.getAsJsonObject("display").remove("standing_hint");
        assertNull(new CloudView(raw, id, 4).standingHint());
    }

    @Test
    void standingHintIsAnImmutablePartOfTheViewIndependentOfPresentationRequests() {
        var raw = ProtocolTest.projection(0);
        raw.getAsJsonObject("display")
                .add(
                        "standing_hint",
                        JsonParser.parseString(
                                "{\"shanten\":0,\"waits\":[22,25],\"yaku_waits\":[22,25],\"no_yaku\":false,\"furiten\":true}"));
        var accepted = view(raw);
        assertEquals(java.util.List.of(22, 25), accepted.standingHint().waits());
        assertTrue(accepted.standingHint().furiten());
        raw.addProperty("view_version", 2);
        assertEquals(accepted.standingHint(), view(raw).standingHint());
        raw.getAsJsonObject("display").add("standing_hint", JsonNull.INSTANCE);
        assertNull(view(raw).standingHint());
        assertNotNull(accepted.standingHint());
        assertThrows(
                UnsupportedOperationException.class, () -> accepted.standingHint().waits().clear());
    }

    @Test
    void missingDecisionSeatIsRejectedBeforeTheFirstRender() {
        var raw = ProtocolTest.projection(0);
        raw.addProperty("status", "waiting_players");
        raw.getAsJsonObject("public").addProperty("oya", 0);
        raw.getAsJsonObject("display").remove("decision_seat");
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> {
                            var accepted = view(raw);
                            // Reproduce creation's first render if the transport incorrectly
                            // accepts it.
                            TableCenter.from(accepted);
                        });
        assertEquals("server_update_required", error.getMessage());
    }

    @Test
    void displayRequiresExplicitNullOrAnExactSeatAndFourBooleans() {
        for (String invalid :
                new String[] {"-1", "4", "1.5", "4294967296", "\"2\"", "true", "[]", "{}"}) {
            var raw = ProtocolTest.projection(0);
            raw.getAsJsonObject("display").add("decision_seat", JsonParser.parseString(invalid));
            assertThrows(IllegalArgumentException.class, () -> view(raw), invalid);
        }
        for (String invalid :
                new String[] {"null", "[]", "[false,false,false]", "[false,false,false,1]"}) {
            var raw = ProtocolTest.projection(0);
            raw.getAsJsonObject("display").add("drawn_seats", JsonParser.parseString(invalid));
            assertThrows(IllegalArgumentException.class, () -> view(raw), invalid);
        }
        var raw = ProtocolTest.projection(0);
        assertEquals(-1, view(raw).decisionSeat());
        for (int seat = 0; seat < 4; seat++) {
            raw.getAsJsonObject("display").addProperty("decision_seat", seat);
            var accepted = view(raw);
            raw.getAsJsonObject("display").remove("decision_seat");
            assertEquals(seat, accepted.decisionSeat());
        }
    }
}
