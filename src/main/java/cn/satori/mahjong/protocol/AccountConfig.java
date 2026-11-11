package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/** Explicitly requested local password storage. Never stores AI keys or cloud seat tokens. */
public final class AccountConfig {
    private final Path file;

    public AccountConfig(Path file) {
        this.file = file;
    }

    public record Credentials(String username, String password) {
        public Credentials {
            if (username == null || username.isBlank() || password == null || password.isEmpty())
                throw new IllegalArgumentException("invalid_account_config");
        }

        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    public Optional<Credentials> read() throws IOException {
        if (Files.notExists(file)) return Optional.empty();
        try {
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (json.get("version").getAsInt() != 1) throw new IllegalArgumentException();
            return Optional.of(
                    new Credentials(
                            json.get("username").getAsString(),
                            json.get("password").getAsString()));
        } catch (RuntimeException invalid) {
            throw new IOException("invalid_account_config");
        }
    }

    public void save(Credentials credentials) throws IOException {
        Files.createDirectories(file.getParent());
        boolean posix = Files.getFileStore(file.getParent()).supportsFileAttributeView("posix");
        Path staged =
                posix
                        ? Files.createTempFile(
                                file.getParent(),
                                ".satori-account-",
                                ".json",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rw-------")))
                        : Files.createTempFile(file.getParent(), ".satori-account-", ".json");
        try {
            var json = new JsonObject();
            json.addProperty("version", 1);
            json.addProperty("username", credentials.username());
            json.addProperty("password", credentials.password());
            Files.writeString(
                    staged, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n");
            Files.move(
                    staged,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }
}
