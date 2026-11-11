package cn.satori.mahjong.client;

import static cn.satori.mahjong.client.HandInteractionFixture.require;

import cn.satori.mahjong.protocol.LocalAccountFixture;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Shared, isolated account lifecycle exercised inside both native clients. */
public final class AccountLifecycleChecks implements AutoCloseable {
    public record Step(String name, BooleanSupplier ready, Runnable action) {}

    private final LocalAccountFixture http;
    private final ClientController client;

    public AccountLifecycleChecks() {
        try {
            http = new LocalAccountFixture(false);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        http.loginSucceeds = true;
        client = new ClientController(http.api, http.config);
        client.dimension = SatoriClient.TABLES.active().dimension;
        client.table = SatoriClient.TABLES.active().table;
    }

    private boolean idle() {
        return !client.busy && !client.account.keysLoading;
    }

    private void login(boolean code) {
        client.login("saved@example.test", code ? "123456" : "synthetic-password", code);
    }

    private void saved(boolean expected) {
        try {
            var value = http.config.read();
            require(value.isPresent() == expected, "Saved credential lifecycle mismatch");
            if (expected)
                require(
                        value.orElseThrow().username().equals("saved@example.test")
                                && value.orElseThrow().password().equals("synthetic-password"),
                        "Saved credentials changed");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private void signedOut() {
        require(
                client.user() == null
                        && client.game == null
                        && client.view == null
                        && client.account.selectedKey == null
                        && client.account.keys.isEmpty(),
                "Logout retained private state");
        require(
                Minecraft.getInstance().screen instanceof AccountScreen,
                "Login screen not restored");
        saved(false);
    }

    public List<Step> steps() {
        return List.of(
                new Step(
                        "password login",
                        () -> true,
                        () -> {
                            client.openAccount();
                            WebsiteInteractionChecks.openAndReturn(
                                    "satori.account.website", "https://mj.satori-ai.cn/login");
                            login(false);
                        }),
                new Step(
                        "saved login",
                        this::idle,
                        () -> {
                            require(client.user() != null, "Login failed");
                            saved(true);
                            client.disconnect();
                            saved(true);
                            client.openAccount();
                        }),
                new Step(
                        "automatic login",
                        this::idle,
                        () -> {
                            require(
                                    client.user() != null && http.logins.get() == 2,
                                    "Automatic login did not use saved account");
                            saved(true);
                            client.logout();
                        }),
                new Step(
                        "server logout",
                        this::idle,
                        () -> {
                            signedOut();
                            require(
                                    http.logouts.get() == 1
                                            && !http.authenticated
                                            && http.logoutAuthenticated,
                                    "Server session not revoked");
                            login(false);
                        }),
                new Step(
                        "expire session",
                        this::idle,
                        () -> {
                            saved(true);
                            http.authenticated = false;
                            client.validateSession();
                        }),
                new Step(
                        "anonymous session",
                        () -> client.message.equals("session_expired"),
                        () -> {
                            signedOut();
                            login(false);
                        }),
                new Step(
                        "reject account",
                        this::idle,
                        () -> {
                            http.rejectAccounts = true;
                            client.loadKeys();
                        }),
                new Step(
                        "unauthorized account",
                        () ->
                                client.message.equals("session_expired")
                                        && !client.account.keysLoading,
                        () -> {
                            signedOut();
                            http.rejectAccounts = false;
                            login(true);
                        }),
                new Step(
                        "one-time code",
                        this::idle,
                        () -> {
                            require(client.user() != null, "Code login failed");
                            saved(false);
                            login(false);
                        }),
                new Step(
                        "logout network error",
                        this::idle,
                        () -> {
                            saved(true);
                            http.failLogout = true;
                            client.logout();
                        }),
                new Step(
                        "local cleanup",
                        this::idle,
                        () -> {
                            signedOut();
                            require(
                                    client.message.equals("logout_network_error"),
                                    "Failed server logout reported as success");
                            require(http.reveals.get() == 0, "Account login requested an AI key");
                        }));
    }

    @Override
    public void close() {
        client.disconnect();
        http.close();
    }
}
