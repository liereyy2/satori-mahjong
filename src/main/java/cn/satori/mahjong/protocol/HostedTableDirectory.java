package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** World-scoped physical addresses. Match membership and status are always fetched from Rust. */
public final class HostedTableDirectory {
    public record Entry(UUID id, String dimension, int x, int y, int z) {}

    private final Path file;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public HostedTableDirectory(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file))
            for (var raw : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                var row = raw.getAsJsonObject();
                var entry =
                        new Entry(
                                UUID.fromString(row.get("id").getAsString()),
                                row.get("dimension").getAsString(),
                                row.get("x").getAsInt(),
                                row.get("y").getAsInt(),
                                row.get("z").getAsInt());
                if (entries.put(entry.id(), entry) != null)
                    throw new IOException("Duplicate physical table address");
            }
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public void put(Entry entry) {
        if (entry.equals(entries.get(entry.id()))) return;
        var next = new LinkedHashMap<>(entries);
        next.put(entry.id(), entry);
        save(next);
    }

    public void remove(UUID id) {
        if (!entries.containsKey(id)) return;
        var next = new LinkedHashMap<>(entries);
        next.remove(id);
        save(next);
    }

    private void save(Map<UUID, Entry> next) {
        try {
            Files.createDirectories(file.getParent());
            var stage = Files.createTempFile(file.getParent(), ".satori-tables-", ".json");
            try {
                var data = new JsonArray();
                for (var entry : next.values()) {
                    var row = new JsonObject();
                    row.addProperty("id", entry.id().toString());
                    row.addProperty("dimension", entry.dimension());
                    row.addProperty("x", entry.x());
                    row.addProperty("y", entry.y());
                    row.addProperty("z", entry.z());
                    data.add(row);
                }
                Files.writeString(stage, data + "\n");
                Files.move(
                        stage,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                entries.clear();
                entries.putAll(next);
            } finally {
                Files.deleteIfExists(stage);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot persist physical table directory", e);
        }
    }
}
