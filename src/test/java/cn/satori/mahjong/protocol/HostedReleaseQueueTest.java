package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class HostedReleaseQueueTest {
    @TempDir Path directory;

    @Test
    void destructionSurvivesRestartUntilCloudAcknowledges() throws Exception {
        Path file = directory.resolve("releases.json");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var queue = new HostedReleaseQueue(file);
        queue.add(first);
        queue.add(first);
        queue.add(second);
        var restored = new HostedReleaseQueue(file);
        assertEquals(List.of(first, second), restored.pending());
        restored.acknowledge(first);
        assertEquals(List.of(second), new HostedReleaseQueue(file).pending());
        restored.acknowledge(first);
        restored.acknowledge(second);
        assertTrue(new HostedReleaseQueue(file).pending().isEmpty());
    }
}
