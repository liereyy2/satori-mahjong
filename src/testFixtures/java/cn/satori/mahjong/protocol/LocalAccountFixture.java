package cn.satori.mahjong.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only account service. No cloud requests, production credentials or bypass in the mod. */
public final class LocalAccountFixture implements AutoCloseable {
    private final HttpServer server;
    public final SatoriApi api;
    public final AccountConfig config;
    private final Path configFile;
    public volatile boolean emptyKeys;
    public volatile boolean authenticated, loginSucceeds, rejectAccounts, failLogout;
    public volatile boolean logoutAuthenticated;
    public final AtomicInteger logins = new AtomicInteger(), logouts = new AtomicInteger();
    public final AtomicInteger reveals = new AtomicInteger();
    public volatile JsonObject submitted;
    public volatile String submittedPath;
    public volatile boolean acceptCommands;
    public volatile boolean failCommandOnce;
    public final java.util.List<String> commandBodies =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    public volatile JsonObject presentation;
    public volatile int presentationStatus = 200;
    public final AtomicInteger presentations = new AtomicInteger();
    public final AtomicInteger catalogs = new AtomicInteger(), deletions = new AtomicInteger();
    public volatile boolean failCatalog;
    public final com.google.gson.JsonArray savedGames = new com.google.gson.JsonArray();
    public volatile JsonObject finalResult;
    public final AtomicInteger tickets = new AtomicInteger();
    public volatile boolean ticketAuthenticated;

    public LocalAccountFixture() throws IOException {
        this(true);
    }

    public LocalAccountFixture(boolean authenticated) throws IOException {
        this.authenticated = authenticated;
        loginSucceeds = authenticated;
        configFile = Files.createTempDirectory("satori-account-test-").resolve("account.json");
        config = new AccountConfig(configFile);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/account/session",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add("Set-Cookie", "satori_test=synthetic-session; Path=/; HttpOnly");
                    byte[] body =
                            (this.authenticated
                                            ? """
{"logged_in":true,"csrf_token":"test-csrf","user":{"id":"00000000-0000-0000-0000-000000000001","username":"Test player","public_id":123456789}}
"""
                                            : "{\"logged_in\":false,\"csrf_token\":\"test-csrf\",\"user\":null}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/api/account/login",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    logins.incrementAndGet();
                    this.authenticated = loginSucceeds;
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/api/account/logout",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    logouts.incrementAndGet();
                    logoutAuthenticated =
                            "test-csrf"
                                            .equals(
                                                    exchange.getRequestHeaders()
                                                            .getFirst("X-CSRF-Token"))
                                    && exchange
                                            .getRequestHeaders()
                                            .getOrDefault("Cookie", java.util.List.of())
                                            .stream()
                                            .anyMatch(
                                                    value ->
                                                            value.contains(
                                                                    "satori_test=synthetic-session"));
                    if (!failLogout) this.authenticated = false;
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(failLogout ? 503 : 200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/api/account/api-keys",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    String json;
                    if (path.endsWith("/plaintext")) {
                        reveals.incrementAndGet();
                        json =
                                "{\"key_id\":\"00000000-0000-0000-0000-000000000010\",\"api_key\":\"synthetic-selected-key\"}";
                    } else
                        json =
                                emptyKeys
                                        ? "{\"items\":[]}"
                                        : """
{"items":[{"id":"00000000-0000-0000-0000-000000000010","name":"Test key","key_masked":"sk_***0010","status":"active"},
{"id":"00000000-0000-0000-0000-000000000011","name":"Disabled key","key_masked":"sk_***0011","status":"revoked"}]}
""";
                    byte[] body = json.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(rejectAccounts ? 401 : 200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/api/game/v1/games",
                exchange -> {
                    if (exchange.getRequestMethod().equals("GET")
                            && exchange.getRequestURI().getPath().equals("/api/game/v1/games")) {
                        catalogs.incrementAndGet();
                        var query = new java.util.HashMap<String, String>();
                        for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                            String[] parts = pair.split("=", 2);
                            query.put(
                                    parts[0],
                                    java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                        }
                        int offset = Integer.parseInt(query.getOrDefault("cursor", "0"));
                        int limit = Integer.parseInt(query.get("limit"));
                        var page = new JsonObject();
                        var items = new com.google.gson.JsonArray();
                        for (int i = offset; i < Math.min(savedGames.size(), offset + limit); i++)
                            items.add(savedGames.get(i));
                        page.add("items", items);
                        if (offset + limit < savedGames.size())
                            page.addProperty("next_cursor", Integer.toString(offset + limit));
                        else page.add("next_cursor", com.google.gson.JsonNull.INSTANCE);
                        byte[] body =
                                (failCatalog
                                                ? "{\"error\":{\"code\":\"http_503\"}}"
                                                : page.toString())
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(failCatalog ? 503 : 200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                        return;
                    }
                    if (exchange.getRequestMethod().equals("GET")
                            && exchange.getRequestURI().getPath().endsWith("/result")) {
                        byte[] body = finalResult.toString().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                        return;
                    }
                    if (exchange.getRequestMethod().equals("GET")
                            && exchange.getRequestURI().getPath().endsWith("/presentation")) {
                        presentations.incrementAndGet();
                        byte[] body =
                                (presentationStatus == 200
                                                ? presentation.toString()
                                                : "{\"detail\":\"Test HTTP rejection\"}")
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(presentationStatus, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                        return;
                    }
                    submitted =
                            JsonParser.parseString(
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8))
                                    .getAsJsonObject();
                    submittedPath = exchange.getRequestURI().getPath();
                    commandBodies.add(submitted.toString());
                    if (failCommandOnce) {
                        failCommandOnce = false;
                        byte[] body =
                                "{\"error\":{\"code\":\"http_503\"}}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(503, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                        return;
                    }
                    if (exchange.getRequestMethod().equals("DELETE") && acceptCommands) {
                        deletions.incrementAndGet();
                        for (int i = savedGames.size() - 1; i >= 0; i--)
                            if (submittedPath.endsWith(
                                    savedGames
                                            .get(i)
                                            .getAsJsonObject()
                                            .get("game_id")
                                            .getAsString())) savedGames.remove(i);
                    }
                    byte[] body =
                            (acceptCommands
                                            ? "{\"accepted\":true}"
                                            : "{\"error\":{\"code\":\"synthetic_rejection\"}}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(acceptCommands ? 200 : 403, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/api/messages/tickets",
                exchange -> {
                    submittedPath = exchange.getRequestURI().getPath();
                    submitted =
                            JsonParser.parseString(
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8))
                                    .getAsJsonObject();
                    ticketAuthenticated =
                            "POST".equals(exchange.getRequestMethod())
                                    && exchange.getRequestHeaders().getFirst("Authorization")
                                            == null
                                    && "test-csrf"
                                            .equals(
                                                    exchange.getRequestHeaders()
                                                            .getFirst("X-CSRF-Token"))
                                    && exchange
                                            .getRequestHeaders()
                                            .getOrDefault("Cookie", java.util.List.of())
                                            .stream()
                                            .anyMatch(
                                                    value ->
                                                            value.contains(
                                                                    "satori_test=synthetic-session"));
                    tickets.incrementAndGet();
                    byte[] body =
                            "{\"id\":\"00000000-0000-0000-0000-000000000777\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        api = new SatoriApi(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @Override
    public void close() {
        api.close();
        server.stop(0);
        try {
            config.clear();
            Files.delete(configFile.getParent());
        } catch (IOException error) {
            throw new IllegalStateException("test_config_cleanup", error);
        }
    }
}
