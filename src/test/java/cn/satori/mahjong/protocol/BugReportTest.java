package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class BugReportTest {
    @Test
    void ticketContainsOnlyTheTypedDescriptionAndExplicitPreviewedContext() {
        var report = new BugReport("test-version", "fabric", "1.21.11", new UUID(0, 7));
        var payload = report.ticket(" Tile selection ", " First line\n第二行 ");
        assertEquals("[SatoriMahjong Bug] Tile selection", payload.get("subject").getAsString());
        assertEquals("First line\n第二行\n\n" + report.context(), payload.get("body").getAsString());
        assertEquals(2, payload.size());
    }

    @Test
    void blankOrOversizedReportsAreRejectedBeforeSending() {
        var report = new BugReport("v", "forge", "1.21.11", null);
        assertThrows(IllegalArgumentException.class, () -> report.ticket(" ", "description"));
        assertThrows(IllegalArgumentException.class, () -> report.ticket("title", " "));
        assertThrows(
                IllegalArgumentException.class,
                () -> report.ticket("x".repeat(256), "description"));
        assertThrows(
                IllegalArgumentException.class, () -> report.ticket("title", "x".repeat(4000)));
        assertTrue(report.context().endsWith("Game ID: -"));
    }
}
