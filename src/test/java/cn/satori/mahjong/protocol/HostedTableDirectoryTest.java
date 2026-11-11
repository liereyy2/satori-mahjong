package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class HostedTableDirectoryTest {
    @TempDir Path directory;

    @Test
    void activeAddressesSurviveRestartAndReleaseIndependently() throws Exception {
        var file = directory.resolve("tables.json");
        var first =
                new HostedTableDirectory.Entry(
                        UUID.randomUUID(), "minecraft:overworld", 100, 64, -200);
        var second =
                new HostedTableDirectory.Entry(
                        UUID.randomUUID(), "minecraft:the_nether", -3, 40, 2);
        var registry = new HostedTableDirectory(file);
        registry.put(first);
        registry.put(second);
        registry.put(first);
        assertEquals(List.of(first, second), new HostedTableDirectory(file).entries());
        registry.remove(first.id());
        assertEquals(List.of(second), new HostedTableDirectory(file).entries());
        registry.remove(second.id());
        assertTrue(new HostedTableDirectory(file).entries().isEmpty());
    }

    @Test
    void highlightBlinksForExactlyFiveSeconds() {
        assertTrue(TableHighlight.visible(0));
        assertFalse(TableHighlight.visible(250_000_000));
        assertTrue(TableHighlight.visible(500_000_000));
        assertFalse(TableHighlight.visible(5_000_000_000L));
        assertFalse(TableHighlight.visible(-1));
    }
}
