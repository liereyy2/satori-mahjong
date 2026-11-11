package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Instance-wide server configuration. Never copied into world NBT or public packets. */
public final class ControlConfig {
    private final Path file;
    public final UUID instance;
    private String username = "", password = "";
    private UUID keyId;

    public ControlConfig(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            try {
                var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (json.get("version").getAsInt() != 1) throw new IllegalArgumentException();
                instance = UUID.fromString(json.get("instance_id").getAsString());
                username = json.get("username").getAsString();
                password = json.get("password").getAsString();
                keyId =
                        json.get("key_id").isJsonNull()
                                ? null
                                : UUID.fromString(json.get("key_id").getAsString());
            } catch (RuntimeException error) {
                throw new IOException("invalid_account_config");
            }
        } else {
            instance = UUID.randomUUID();
            save(username, password, keyId);
        }
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public UUID keyId() {
        return keyId;
    }

    public void credentials(String email, String secret) throws IOException {
        save(email, secret, null);
        username = email;
        password = secret;
        keyId = null;
    }

    public void select(UUID id) throws IOException {
        save(username, password, id);
        keyId = id;
    }

    public void clear() throws IOException {
        save("", "", null);
        username = "";
        password = "";
        keyId = null;
    }

    private void save(String username, String password, UUID keyId) throws IOException {
        Files.createDirectories(file.getParent());
        Path staged =
                Files.getFileStore(file.getParent()).supportsFileAttributeView("posix")
                        ? Files.createTempFile(
                                file.getParent(),
                                ".satori-control-",
                                ".json",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rw-------")))
                        : Files.createTempFile(file.getParent(), ".satori-control-", ".json");
        try {
            var value = new JsonObject();
            value.addProperty("version", 1);
            value.addProperty("instance_id", instance.toString());
            value.addProperty("username", username);
            value.addProperty("password", password);
            value.addProperty("key_id", keyId == null ? null : keyId.toString());
            Files.writeString(
                    staged,
                    new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(value)
                            + "\n");
            Files.move(
                    staged,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
