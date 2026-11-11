package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * First-party HTTP transport. Redirects are disabled; account cookies never enter seat requests.
 */
public final class SatoriApi implements AutoCloseable {
    public static final URI PRODUCTION = URI.create("https://mj.satori-ai.cn");
    private final URI origin;
    private final CookieManager cookies;
    private final HttpClient account;
    private final HttpClient seat;
    private final RequestDelivery delivery = new RequestDelivery();
    private volatile String csrf = "";
    private volatile User user;
    private volatile long identity;

    public record User(UUID id, String name, String publicId) {}

    // Package-private transport origin is used only by loopback protocol tests.
    SatoriApi(URI origin) {
        if (!origin.equals(PRODUCTION)
                && !(origin.getScheme().equals("http") && origin.getHost().equals("127.0.0.1")))
            throw new IllegalArgumentException("Unsupported Satori origin");
        this.origin = origin;
        cookies = AccountCookies.create(origin);
        account =
                HttpClient.newBuilder()
                        .cookieHandler(cookies)
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        seat =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public SatoriApi() {
        this(PRODUCTION);
    }

    public User user() {
        return user;
    }

    public SatoriApi fresh() {
        return new SatoriApi(origin);
    }

    public CompletableFuture<User> session() {
        long expected = identity;
        return request(account, "GET", "/api/account/session", null, null)
                .thenApply(
                        json -> {
                            if (expected != identity) throw new CancellationException();
                            User authenticated = null;
                            if (json.get("logged_in").getAsBoolean()) {
                                JsonObject u = json.getAsJsonObject("user");
                                authenticated =
                                        new User(
                                                UUID.fromString(u.get("id").getAsString()),
                                                u.get("username").getAsString(),
                                                u.get("public_id").getAsString());
                            }
                            if (expected != identity) throw new CancellationException();
                            csrf = json.get("csrf_token").getAsString();
                            user = authenticated;
                            return authenticated;
                        });
    }

    public CompletableFuture<User> login(String email, String secret, boolean code) {
        long expected = identity;
        return session()
                .thenCompose(
                        ignored -> {
                            if (expected != identity) throw new CancellationException();
                            JsonObject body = new JsonObject();
                            body.addProperty("email", email.trim());
                            body.addProperty(code ? "code" : "password", secret);
                            return request(account, "POST", "/api/account/login", body, null);
                        })
                .thenCompose(
                        ignored -> {
                            if (expected != identity) throw new CancellationException();
                            return session();
                        })
                .thenApply(
                        authenticated -> {
                            if (authenticated == null)
                                throw new ApiException(401, "login_session_missing");
                            return authenticated;
                        });
    }

    public CompletableFuture<JsonObject> sendCode(String email) {
        return session()
                .thenCompose(
                        ignored -> {
                            JsonObject body = new JsonObject();
                            body.addProperty("email", email.trim());
                            return request(
                                    account, "POST", "/api/account/login/send-code", body, null);
                        });
    }

    public CompletableFuture<JsonObject> logout() {
        var result = request(account, "POST", "/api/account/logout", new JsonObject(), null);
        // HttpClient's cookie filter runs asynchronously: retain the cookie until revocation is
        // sent.
        identity++;
        user = null;
        csrf = "";
        return result.whenComplete((ignored, error) -> forget());
    }

    public CompletableFuture<JsonObject> friends() {
        return request(account, "GET", "/api/play/friend/list", null, null);
    }

    public CompletableFuture<String> credits() {
        return request(account, "GET", "/api/account/credits", null, null)
                .thenApply(
                        json ->
                                new java.math.BigInteger(json.get("credits").getAsString()).toString());
    }

    public CompletableFuture<UUID> reportBug(BugReport report, String title, String description) {
        // Ticket creation is not idempotent: never automatically replay an ambiguous POST.
        return request(
                        account,
                        "POST",
                        "/api/messages/tickets",
                        report.ticket(title, description),
                        null)
                .thenApply(json -> UUID.fromString(json.get("id").getAsString()));
    }

    public CompletableFuture<SavedGames.Page> games(String cursor, int limit) {
        String query =
                "?limit="
                        + limit
                        + (cursor == null
                                ? ""
                                : "&cursor="
                                        + URLEncoder.encode(
                                                cursor, java.nio.charset.StandardCharsets.UTF_8));
        return request(account, "GET", "/api/game/v1/games" + query, null, null)
                .thenApply(SavedGames.Page::parse);
    }

    public CompletableFuture<JsonObject> deleteGame(UUID id) {
        return request(account, "DELETE", "/api/game/v1/games/" + id, null, null);
    }

    public CompletableFuture<List<AccountKey>> apiKeys() {
        return request(account, "GET", "/api/account/api-keys", null, null)
                .thenApply(
                        json ->
                                json.getAsJsonArray("items").asList().stream()
                                        .map(
                                                item -> {
                                                    JsonObject key = item.getAsJsonObject();
                                                    return new AccountKey(
                                                            UUID.fromString(
                                                                    key.get("id").getAsString()),
                                                            key.get("name").getAsString(),
                                                            key.get("key_masked").getAsString(),
                                                            key.get("status").getAsString());
                                                })
                                        .toList());
    }

    public CompletableFuture<String> apiKey(UUID id) {
        return request(account, "GET", "/api/account/api-keys/" + id + "/plaintext", null, null)
                .exceptionally(
                        error -> {
                            String code = errorCode(error);
                            if (code.equals("http_404") || code.equals("http_409"))
                                throw new ApiException(409, "key_unavailable");
                            throw new CompletionException(error);
                        })
                .thenApply(
                        json -> {
                            if (!id.toString().equals(json.get("key_id").getAsString()))
                                throw new ApiException(502, "protocol_error");
                            String value = json.get("api_key").getAsString();
                            if (value.isBlank()) throw new ApiException(409, "key_unavailable");
                            return value;
                        });
    }

    public CompletableFuture<JsonObject> create(JsonObject body) {
        JsonObject submitted = body.deepCopy();
        return delivery.send(() -> request(account, "POST", "/api/game/v1/games", submitted, null));
    }

    /** Account-authenticated server management. Seat clients never call this method. */
    public CompletableFuture<JsonObject> hosted(
            UUID table, String method, String endpoint, JsonObject body, String afterCursor) {
        if (!endpoint.matches("|/(commands|sessions|resume)"))
            throw new IllegalArgumentException("Invalid table endpoint");
        JsonObject submitted = body == null ? null : body.deepCopy();
        boolean render =
                (endpoint.isEmpty() && (method.equals("GET") || method.equals("PUT")))
                        || endpoint.equals("/commands");
        String path =
                "/api/game/v1/tables/"
                        + table
                        + endpoint
                        + (render
                                ? "?render_stream=true"
                                        + (afterCursor == null
                                                ? ""
                                                : "&after_cursor="
                                                        + URLEncoder.encode(
                                                                afterCursor,
                                                                StandardCharsets.UTF_8))
                                : "");
        if (endpoint.equals("/sessions") || method.equals("GET"))
            return request(account, method, path, submitted, null);
        return delivery.send(() -> request(account, method, path, submitted, null));
    }

    public CompletableFuture<JsonObject> join(UUID gameId) {
        return request(account, "POST", gamePath(gameId) + "/sessions", new JsonObject(), null);
    }

    public CompletableFuture<JsonObject> game(
            UUID gameId, String token, String method, String endpoint, JsonObject body) {
        if (!endpoint.matches(
                "/(state|decision|presentation|ready|start|actions|ai-authorization|resume|cancel|result|commands/[a-zA-Z0-9_-]+)"))
            throw new IllegalArgumentException("Invalid endpoint");
        if (!method.equals("GET") || endpoint.equals("/result")) {
            JsonObject submitted = body == null ? null : body.deepCopy();
            return delivery.send(
                    () -> request(seat, method, gamePath(gameId) + endpoint, submitted, token));
        }
        return request(seat, method, gamePath(gameId) + endpoint, body, token);
    }

    private static String gamePath(UUID gameId) {
        return "/api/game/v1/games/" + gameId;
    }

    public CompletableFuture<WebSocket> connect(UUID gameId, WebSocket.Listener listener) {
        String scheme = origin.getScheme().equals("https") ? "wss" : "ws";
        URI uri =
                URI.create(
                        scheme + "://" + origin.getRawAuthority() + gamePath(gameId) + "/stream");
        return seat.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .header("Origin", origin.toString())
                .buildAsync(uri, listener);
    }

    private CompletableFuture<JsonObject> request(
            HttpClient client, String method, String path, JsonObject body, String token) {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(origin.resolve(path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json")
                        .header("Origin", origin.toString());
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (client == account && !method.equals("GET") && !csrf.isBlank())
            b.header("X-CSRF-Token", csrf);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        return client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            int status = response.statusCode();
                            JsonObject json;
                            try {
                                json = JsonParser.parseString(response.body()).getAsJsonObject();
                            } catch (RuntimeException ex) {
                                throw new ApiException(status, "invalid_server_response");
                            }
                            if (status < 200 || status >= 300) {
                                String code = "http_" + status;
                                if (json.has("error") && json.get("error").isJsonObject()) {
                                    JsonElement value = json.getAsJsonObject("error").get("code");
                                    if (value != null
                                            && value.isJsonPrimitive()
                                            && value.getAsString().matches("[a-zA-Z0-9_.-]{1,100}"))
                                        code = value.getAsString();
                                }
                                throw new ApiException(
                                        status,
                                        code,
                                        client == account
                                                && (status == 401
                                                        || status == 403
                                                                && path.equals(
                                                                        "/api/account/login")));
                            }
                            return json;
                        });
    }

    public void forget() {
        identity++;
        user = null;
        csrf = "";
        cookies.getCookieStore().removeAll();
    }

    @Override
    public void close() {
        delivery.close();
        forget();
        account.shutdownNow();
        seat.shutdownNow();
    }

    public static final class ApiException extends RuntimeException {
        public final int status;
        public final String code;
        public final boolean accountAuthentication;

        public ApiException(int status, String code) {
            this(status, code, false);
        }

        public ApiException(int status, String code, boolean accountAuthentication) {
            super(code);
            this.status = status;
            this.code = code;
            this.accountAuthentication = accountAuthentication;
        }
    }

    public static boolean accountExpired(Throwable error) {
        while (error instanceof CompletionException || error instanceof ExecutionException)
            error = error.getCause();
        return error instanceof ApiException ex
                && (ex.accountAuthentication || ex.code.equals("login_session_missing"));
    }

    public static String errorCode(Throwable error) {
        while (error instanceof CompletionException || error instanceof ExecutionException)
            error = error.getCause();
        if (error instanceof ApiException api) return api.code;
        if (error instanceof IOException || error instanceof TimeoutException)
            return "network_error";
        // Exception messages may contain request bodies or credentials; display only controlled
        // codes.
        return "protocol_error";
    }
}
