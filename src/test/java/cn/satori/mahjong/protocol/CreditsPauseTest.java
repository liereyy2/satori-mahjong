package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import java.util.UUID;

final class CreditsPauseTest {
    private CloudView view(JsonObject raw) {
        return new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
    }

    @Test
    void onlyAuthoritativePaused402RequestsTopUp() {
        var raw = ProtocolTest.projection(0);
        raw.addProperty("status", "paused");
        raw.addProperty("pause_reason", "ai_terminal_http_402");
        String before = raw.toString();
        assertTrue(view(raw).needsCredits());
        assertEquals(before, raw.toString());
        for (String reason :
                new String[] {
                    "ai_terminal_http_401",
                    "ai_terminal_invalid_action",
                    "ai_service_unavailable",
                    "ai_transport_error",
                    ""
                }) {
            raw.addProperty("pause_reason", reason);
            assertFalse(view(raw).needsCredits(), reason);
        }
    }

    @Test
    void resumedAndFinishedViewsDoNotKeepBalanceNotice() {
        var raw = ProtocolTest.projection(0);
        raw.addProperty("pause_reason", "ai_terminal_http_402");
        for (String status : new String[] {"playing", "finished", "cancelled", "waiting_players"}) {
            raw.addProperty("status", status);
            assertFalse(view(raw).needsCredits(), status);
        }
    }
}
