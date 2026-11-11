package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

class ControlConfigTest {
    @TempDir Path directory;

    @Test
    void sharedIdentityPersistsAndLogoutRemovesOnlyCredentials() throws Exception {
        var file = directory.resolve("control.json");
        var config = new ControlConfig(file);
        var id = config.instance;
        var key = UUID.randomUUID();
        config.credentials("shared@test", "password");
        config.select(key);
        var restored = new ControlConfig(file);
        assertEquals(id, restored.instance);
        assertEquals(key, restored.keyId());
        assertEquals("password", restored.password());
        restored.clear();
        var cleared = new ControlConfig(file);
        assertEquals(id, cleared.instance);
        assertEquals("", cleared.username());
        assertEquals("", cleared.password());
        assertNull(cleared.keyId());
        assertFalse(Files.readString(file).contains("password\" : \"password"));
    }

    @Test
    void failedWriteDoesNotChangeInMemoryCredentials() throws Exception {
        var file = directory.resolve("control.json");
        var config = new ControlConfig(file);
        config.credentials("first", "secret");
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("occupied"), "keep");
        assertThrows(IOException.class, () -> config.credentials("second", "new"));
        assertEquals("first", config.username());
        assertEquals("secret", config.password());
    }

    @Test
    void invalidConfigIsPreservedAndPermissionIsPrivate() throws Exception {
        var file = directory.resolve("control.json");
        new ControlConfig(file);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            assertEquals(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(file));
        Files.writeString(file, "invalid");
        assertThrows(IOException.class, () -> new ControlConfig(file));
        assertEquals("invalid", Files.readString(file));
    }
}
