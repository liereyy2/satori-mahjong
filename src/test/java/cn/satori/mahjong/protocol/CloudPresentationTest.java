package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class CloudPresentationTest {
    @Test
    void presentationErrorsPreserveTheDeploymentAndSeatCause() {
        for (int status : new int[] {401, 404, 503}) {
            String expected =
                    switch (status) {
                        case 401 -> "seat_session_expired";
                        case 404 -> "http_404";
                        default -> "presentation_error";
                    };
            assertEquals(
                    expected,
                    CloudPresentation.errorMessage(
                            new java.util.concurrent.CompletionException(
                                    new SatoriApi.ApiException(status, "http_" + status))));
        }
        assertEquals(
                "game_not_found",
                CloudPresentation.errorMessage(new SatoriApi.ApiException(404, "game_not_found")));
        assertEquals(
                "presentation_error", CloudPresentation.errorMessage(new java.io.IOException()));
    }

    @Test
    void seatAndVersionBindingRejectsStaleDisplayWithoutMutatingTheView() {
        var source = ProtocolTest.projection(0);
        var view = new CloudView(source, UUID.fromString(source.get("game_id").getAsString()), 0);
        var data = new JsonObject();
        data.addProperty("game_id", view.gameId.toString());
        data.addProperty("viewer_seat", 0);
        data.addProperty("view_version", view.version);
        data.addProperty("cursor", view.cursor);
        data.add("settlement", JsonNull.INSTANCE);
        data.add(
                "hand_hint",
                JsonParser.parseString(
                        "{\"shanten\":0,\"waits\":[22,25],\"yaku_waits\":[],\"no_yaku\":true,\"furiten\":false}"));
        data.add("discard_hints", new JsonArray());
        var display = new CloudPresentation(data, view);
        assertTrue(display.hint(null).noYaku());
        assertEquals(List.of(22, 25), display.hint(null).waits());
        assertNull(display.hint(1));
        assertNull(display.settlement());
        data.addProperty("viewer_seat", 1);
        assertThrows(IllegalArgumentException.class, () -> new CloudPresentation(data, view));
        data.addProperty("viewer_seat", 0);
        data.addProperty("view_version", view.version + 1);
        assertThrows(IllegalArgumentException.class, () -> new CloudPresentation(data, view));
        assertTrue(display.hint(null).noYaku());
    }

    @Test
    void actionPicturesShowConsumedHandTilesAndPreserveRedFives() {
        var raw =
                JsonParser.parseString(
                                "{\"event\":{\"type\":\"pon\",\"pai\":\"5mr\",\"consumed\":[\"5m\",\"5m\"]}}")
                        .getAsJsonObject();
        assertEquals(
                List.of(4, 4),
                CloudPresentation.actionTiles(new CloudView.Action("d", "a", "pon", "5mr", raw)));
        raw =
                JsonParser.parseString(
                                "{\"event\":{\"type\":\"ankan\",\"consumed\":[\"5p\",\"5p\",\"5pr\",\"5p\"]}}")
                        .getAsJsonObject();
        assertEquals(
                List.of(13, 13, 35, 13),
                CloudPresentation.actionTiles(new CloudView.Action("d", "a", "ankan", "", raw)));
    }
}
