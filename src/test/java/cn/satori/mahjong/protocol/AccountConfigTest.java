package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

class AccountConfigTest {
    @TempDir Path directory;

    @Test
    void credentialsSurviveRestartAndLogoutDeletesFile() throws Exception {
        Path path = directory.resolve("config/satori-mahjong-account.json");
        var config = new AccountConfig(path);
        var credentials =
                new AccountConfig.Credentials("player@example.test", "synthetic-password");
        assertTrue(config.read().isEmpty());
        config.save(credentials);
        assertEquals(credentials, new AccountConfig(path).read().orElseThrow());
        config.clear();
        assertFalse(Files.exists(path));
        assertTrue(config.read().isEmpty());
    }

    @Test
    void replacingCredentialsLeavesOnlyOnePrivateFile() throws Exception {
        Path path = directory.resolve("account.json");
        var config = new AccountConfig(path);
        config.save(new AccountConfig.Credentials("first", "secret-one"));
        config.save(new AccountConfig.Credentials("second", "secret-two"));
        assertFalse(Files.readString(path).contains("secret-one"));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(path));
    }

    @Test
    void invalidConfigIsReportedAndNeverSilentlyReplaced() throws Exception {
        var path = directory.resolve("account.json");
        Files.writeString(path, "{bad config}");
        assertThrows(IOException.class, () -> new AccountConfig(path).read());
        assertEquals("{bad config}", Files.readString(path));
    }

    @Test
    void storageErrorsAreNotReportedAsSaved() throws Exception {
        var parent = directory.resolve("not-a-directory");
        Files.writeString(parent, "occupied");
        assertThrows(
                IOException.class,
                () ->
                        new AccountConfig(parent.resolve("account.json"))
                                .save(new AccountConfig.Credentials("user", "secret")));
    }

    @Test
    void secretsAreRedactedAndSeatTokenErrorsDoNotExpireAccount() {
        assertFalse(new AccountConfig.Credentials("user", "secret").toString().contains("secret"));
        assertFalse(SatoriApi.accountExpired(new SatoriApi.ApiException(401, "seat_expired")));
        assertTrue(SatoriApi.accountExpired(new SatoriApi.ApiException(401, "http_401", true)));
    }
}
