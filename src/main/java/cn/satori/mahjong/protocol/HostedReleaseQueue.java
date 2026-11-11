package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Durable transport intents, not game state. Remove only after cloud acknowledgement. */
public final class HostedReleaseQueue {
    private final Path file;
    private final Set<UUID> pending = new LinkedHashSet<>();

    public HostedReleaseQueue(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file))
            for (var id : JsonParser.parseString(Files.readString(file)).getAsJsonArray())
                pending.add(UUID.fromString(id.getAsString()));
    }

    public List<UUID> pending() {
        return List.copyOf(pending);
    }

    public void add(UUID id) {
        var next = new LinkedHashSet<>(pending);
        next.add(id);
        save(next);
    }

    public void acknowledge(UUID id) {
        var next = new LinkedHashSet<>(pending);
        next.remove(id);
        save(next);
    }

    private void save(Set<UUID> next) {
        try {
            Files.createDirectories(file.getParent());
            Path staged = Files.createTempFile(file.getParent(), ".satori-release-", ".json");
            try {
                var data = new JsonArray();
                next.forEach(id -> data.add(id.toString()));
                Files.writeString(staged, data + "\n");
                Files.move(
                        staged,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                pending.clear();
                pending.addAll(next);
            } finally {
                Files.deleteIfExists(staged);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot persist cloud table release", e);
        }
    }
}
